(ns im.ttalk.agent.reliability-test
  "designs/provider-reliability.md §1 / §2 / §10 的 core 侧：新错误类型、重试三格预算、
   空补全、流式首 token 前重试、attempts 记录、pause_turn 判定。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.async :as async]
            [im.ttalk.agent.chat-model :as cm]
            [im.ttalk.agent.model :as proto]
            [im.ttalk.agent.model.error :as errors]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.request :as req]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.retry :as retry]))

(def ^:private no-sleep {:sleep-fn (fn [_]) :rand-fn (constantly 0.0)})

;;; ============================================================
;;; §1 错误类型
;;; ============================================================

(deftest new-error-types-test
  (testing "三个新类型的缺省可重试性（契约）"
    (is (false? (:retryable? (errors/error :quota-exhausted-error "欠费"))))
    (is (false? (:retryable? (errors/error :context-overflow-error "超长"))))
    (is (true? (:retryable? (errors/error :empty-completion-error "空")))))
  (testing "402 → 欠费"
    (let [e (errors/http-response->error {:status 402 :body "payment required"} :x)]
      (is (= :quota-exhausted-error (:type e)))
      (is (errors/quota-exhausted? e))))
  (testing "rate-limit-error? 不认欠费 429"
    (is (errors/rate-limit-error? (errors/error :rate-limit-error "429" {:status 429})))
    (is (not (errors/rate-limit-error? (errors/error :quota-exhausted-error "欠费" {:status 429})))))
  (testing "谓词"
    (is (errors/context-overflow? (errors/error :context-overflow-error "x")))
    (is (errors/empty-completion? (errors/error :empty-completion-error "x"))))
  (testing "工具错误路由：欠费 → :environment"
    (is (= :environment (errors/classify-exception
                          (ex-info "欠费" (errors/error :quota-exhausted-error "欠费"))))))
  (testing ":code 可选键"
    (is (= "insufficient_quota" (:code (errors/error :quota-exhausted-error "x" {:code "insufficient_quota"}))))))

(deftest with-ex-data-test
  (testing "ExceptionInfo：同消息、同 cause、data 追加"
    (let [cause (RuntimeException. "root")
          e (ex-info "boom" {:type :provider-error :message "boom" :retryable? true} cause)
          e2 (errors/with-ex-data e {:attempts [1 2]})]
      (is (= "boom" (.getMessage e2)))
      (is (identical? cause (.getCause e2)))
      (is (= [1 2] (:attempts (ex-data e2))))
      (is (= :provider-error (:type (ex-data e2))))))
  (testing "非 ExceptionInfo：先归一为 canonical error，原异常作 cause"
    (let [io (java.io.IOException. "reset")
          e2 (errors/with-ex-data io {:fallback {:x 1}})]
      (is (= :network-error (:type (ex-data e2))))
      (is (identical? io (.getCause e2)))
      (is (= {:x 1} (:fallback (ex-data e2)))))))

;;; ============================================================
;;; §2 retry/run 三格预算
;;; ============================================================

(def ^:private transient-err (errors/error :network-error "抖动" {:retryable? true}))

(deftest empty-completion-budget-test
  (testing "空结果补打一次后成功；不消耗 :max-retries；:attempts 记录"
    (let [calls (atom 0)
          events (atom [])
          f #(if (= 1 (swap! calls inc)) {:text ""} {:text "ok"})
          r (retry/run f (merge no-sleep {:max-retries 0 :empty? (comp empty? :text)
                                          :record-attempts? true
                                          :on-retry #(swap! events conj (:kind %))}))]
      (is (= "ok" (:text r)))
      (is (= 2 (:attempts r)))
      (is (= [:empty-completion] @events))))
  (testing "超预算 + :empty-exhausted :throw → :empty-completion-error，且不进 HTTP 预算"
    (let [calls (atom 0)
          f #(do (swap! calls inc) {:text ""})]
      (try (retry/run f (merge no-sleep {:max-retries 5 :empty? (comp empty? :text)
                                         :empty-retries 1 :empty-exhausted :throw}))
           (is false "应抛")
           (catch clojure.lang.ExceptionInfo e
             (is (= :empty-completion-error (:type (ex-data e))))
             (is (= 2 (count (:attempts (ex-data e)))))))
      (is (= 2 @calls) "max-retries 5 不该再追空补全")))
  (testing "缺省 :empty-exhausted :return → 空结果原样交回"
    (let [calls (atom 0)
          r (retry/run #(do (swap! calls inc) {:text ""})
                       (merge no-sleep {:empty? (comp empty? :text)}))]
      (is (= {:text ""} r))
      (is (= 2 @calls))))
  (testing "不给 :empty? → 旧行为，空结果直接返回"
    (let [calls (atom 0)]
      (is (= {:text ""} (retry/run #(do (swap! calls inc) {:text ""}) no-sleep)))
      (is (= 1 @calls)))))

(deftest retry-gate-and-attempts-test
  (testing ":retry-gate 为假 → 不重试"
    (let [calls (atom 0)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (retry/run #(do (swap! calls inc) (errors/throw! transient-err))
                              (merge no-sleep {:max-retries 3 :retry-gate (constantly false)}))))
      (is (= 1 @calls))))
  (testing "多次失败 → ex-data :attempts 列出每次失败；只试一次 → 不改写异常"
    (try (retry/run #(errors/throw! transient-err) (merge no-sleep {:max-retries 2}))
         (catch clojure.lang.ExceptionInfo e
           (is (= 3 (count (:attempts (ex-data e)))))
           (is (= :network-error (:type (ex-data e))))))
    (let [e0 (ex-info "401" (errors/error :auth-error "401"))]
      (try (retry/run #(throw e0) (merge no-sleep {:max-retries 2}))
           (catch clojure.lang.ExceptionInfo e
             (is (identical? e0 e))))))
  (testing "欠费不重试（429 欠费不再被当限流）"
    (let [calls (atom 0)
          q (errors/error :quota-exhausted-error "insufficient_quota" {:status 429})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (retry/run #(do (swap! calls inc) (errors/throw! q)) (merge no-sleep {:max-retries 3}))))
      (is (= 1 @calls))))
  (testing "run-async 同语义：空补全补打 + attempts"
    (let [calls (atom 0)
          r (async/join (retry/run-async #(async/vthread (fn [] (if (= 1 (swap! calls inc)) {:text ""} {:text "ok"})))
                                         (merge no-sleep {:empty? (comp empty? :text) :record-attempts? true}))
                        3000)]
      (is (= {:text "ok" :attempts 2} r))))
  (testing "resolve-opts：:retry false 三格全关"
    (is (= [0 0 0] ((juxt :max-retries :empty-retries :stream-retries) (retry/resolve-opts {:retry false} {}))))
    (is (= [2 1 2] ((juxt :max-retries :empty-retries :stream-retries) (retry/resolve-opts {} {}))))))

;;; ============================================================
;;; §2 DefaultChatModel 接入
;;; ============================================================

(defrecord ScriptProvider [log script]
  ;; script: 每次调用取一项：Throwable → 抛；{:tokens [...] :then x} → 先吐 token 再处理 x；map → 返回
  proto/ILLMProvider
  (provider-name [_] :script)
  (call-llm [_ config _ _]
    (swap! log conj {:path :sync :config config})
    (let [r (first @script)] (swap! script rest)
      (if (instance? Throwable r) (throw r) r)))
  (call-llm-stream [_ config _ _ on-token]
    (swap! log conj {:path :stream :config config})
    (let [r (first @script)] (swap! script rest)
      (let [{:keys [tokens then]} (if (and (map? r) (contains? r :then)) r {:then r})]
        (doseq [t tokens] (on-token {:token t}))
        (if (instance? Throwable then) (throw then) then))))
  (extract-tool-calls [_ r] (:tool-calls r))
  (extract-text [_ r] (:text r))
  (build-tool-result [_ id c] {:role "tool" :id id :content c})
  (supports-function-calling? [_] true)
  (supports-stream? [_] true)
  (tool->schema [_ t] t))

(defrecord AsyncScriptProvider [log script]
  proto/ILLMProvider
  (provider-name [_] :script-async)
  (call-llm [_ _ _ _] (throw (ex-info "不该走同步" {})))
  (call-llm-stream [_ _ _ _ _] (throw (ex-info "不该走同步" {})))
  (extract-tool-calls [_ r] (:tool-calls r))
  (extract-text [_ r] (:text r))
  (build-tool-result [_ id c] {:role "tool" :id id :content c})
  (supports-function-calling? [_] true)
  (supports-stream? [_] true)
  (tool->schema [_ t] t)
  proto/IAsyncLLMProvider
  (call-llm-async [_ _ _ _]
    (async/vthread (fn [] (swap! log conj :async)
                     (let [r (first @script)] (swap! script rest)
                       (if (instance? Throwable r) (throw r) r)))))
  (call-llm-stream-async [_ _ _ _ on-token]
    (async/vthread (fn [] (swap! log conj :async-stream)
                     (let [r (first @script)] (swap! script rest)
                       (let [{:keys [tokens then]} (if (and (map? r) (contains? r :then)) r {:then r})]
                         (doseq [t tokens] (on-token {:token t}))
                         (if (instance? Throwable then) (throw then) then)))))))

(defn- rq [& [opts]] (req/chat-request [(msg/user "hi")] (merge no-sleep opts)))
(defn- ex [e] (ex-info (:message e) e))

(deftest chat-model-empty-completion-test
  (testing "同步：空补全补打一次 → 成功，:attempts 2"
    (let [log (atom [])
          m (cm/create-chat-model (->ScriptProvider log (atom [{:text ""} {:text "ok"}])) {:model "m"})
          r (cm/call m (rq))]
      (is (= "ok" (response/response-text r)))
      (is (= 2 (response/response-attempts r)))
      (is (= 2 (count @log)))))
  (testing "仍空 → 缺省原样返回；:empty-exhausted :throw → :empty-completion-error"
    (let [m (cm/create-chat-model (->ScriptProvider (atom []) (atom [{:text ""} {:text ""}])) {:model "m"})]
      (is (nil? (response/response-text (cm/call m (rq))))))
    (let [m (cm/create-chat-model (->ScriptProvider (atom []) (atom [{:text ""} {:text ""}]))
                                  {:model "m" :retry {:empty-exhausted :throw}})]
      (is (= :empty-completion-error
             (try (cm/call m (rq)) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))
  (testing "content-filter / max-tokens 不算空"
    (is (not (response/empty-completion? (response/make-response :finish-reason "content_filter"))))
    (is (not (response/empty-completion? (response/make-response :finish-reason "length"))))
    (is (response/empty-completion? (response/make-response :text "  " :finish-reason "stop")))
    (is (not (response/empty-completion? (response/make-response :tool-calls [{:id "1" :name "x" :args {}}])))))
  (testing ":retry {:empty-retries 0} → 不判空，只打一次"
    (let [log (atom [])
          m (cm/create-chat-model (->ScriptProvider log (atom [{:text ""} {:text "ok"}]))
                                  {:model "m" :retry {:empty-retries 0}})]
      (cm/call m (rq))
      (is (= 1 (count @log))))))

(deftest chat-model-stream-retry-test
  (testing "首 token 之前的可重试失败 → 重试，成功"
    (let [log (atom []) tokens (atom [])
          m (cm/create-chat-model (->ScriptProvider log (atom [(ex transient-err)
                                                              {:tokens ["a" "b"] :then {:text "ab"}}]))
                                  {:model "m"})
          r (cm/stream-call m (rq) #(swap! tokens conj (:token %)))]
      (is (= "ab" (response/response-text r)))
      (is (= ["a" "b"] @tokens))
      (is (= 2 (count @log)))))
  (testing "吐过 token 之后失败 → 只打一次，原样抛"
    (let [log (atom []) tokens (atom [])
          m (cm/create-chat-model (->ScriptProvider log (atom [{:tokens ["a"] :then (ex transient-err)}
                                                              {:text "不该到"}]))
                                  {:model "m" :retry {:stream-retries 5}})]
      (is (thrown? clojure.lang.ExceptionInfo (cm/stream-call m (rq) #(swap! tokens conj (:token %)))))
      (is (= 1 (count @log)))
      (is (= ["a"] @tokens))))
  (testing "传给 provider 的 config 剥掉 :retry（传输层不叠一层）"
    (let [log (atom [])
          m (cm/create-chat-model (->ScriptProvider log (atom [{:text "x"}]))
                                  {:model "m" :retry {:max-retries 3}})]
      (cm/stream-call m (rq) (fn [_]))
      (is (not (contains? (:config (first @log)) :retry)))))
  (testing ":retry false → 流式也不重试"
    (let [log (atom [])
          m (cm/create-chat-model (->ScriptProvider log (atom [(ex transient-err) {:text "x"}]))
                                  {:model "m" :retry false})]
      (is (thrown? clojure.lang.ExceptionInfo (cm/stream-call m (rq) (fn [_]))))
      (is (= 1 (count @log))))))

(deftest chat-model-async-parity-test
  (testing "call-async：空补全补打 + 可重试错误重试"
    (let [log (atom [])
          m (cm/create-chat-model (->AsyncScriptProvider log (atom [(ex transient-err) {:text ""} {:text "ok"}]))
                                  {:model "m"})
          r (async/join (cm/call-async* m (rq)) 5000)]
      (is (= "ok" (response/response-text r)))
      (is (= 3 (response/response-attempts r)))))
  (testing "stream-call-async：首 token 前重试；token 后不重试"
    (let [log (atom []) tokens (atom [])
          m (cm/create-chat-model (->AsyncScriptProvider log (atom [(ex transient-err)
                                                                   {:tokens ["z"] :then {:text "z"}}]))
                                  {:model "m"})
          r (async/join (cm/stream-call-async* m (rq) #(swap! tokens conj (:token %))) 5000)]
      (is (= "z" (response/response-text r)))
      (is (= 2 (count @log))))
    (let [log (atom [])
          m (cm/create-chat-model (->AsyncScriptProvider log (atom [{:tokens ["z"] :then (ex transient-err)}
                                                                   {:text "不该到"}]))
                                  {:model "m"})]
      (is (thrown? Exception (async/join (cm/stream-call-async* m (rq) (fn [_])) 5000)))
      (is (= 1 (count @log))))))

(deftest rate-limit-carried-to-response-test
  (testing "原始响应上的 :rate-limit 搬到 ChatResponse"
    (let [m (cm/create-chat-model (->ScriptProvider (atom []) (atom [{:text "x" :rate-limit {:tokens-remaining 9}}]))
                                  {:model "m"})]
      (is (= {:tokens-remaining 9} (response/response-rate-limit (cm/call m (rq))))))))

;;; ============================================================
;;; §10 pause_turn
;;; ============================================================

(deftest pause-turn-resumable-test
  (let [blocks {:format :anthropic-content :data [{:type "server_tool_use" :id "s1" :name "web_search"}]}]
    (testing "pause + 无工具调用 + 有回放块 → 可续"
      (is (response/pause-turn-resumable?
            (response/make-response :finish-reason "pause_turn" :replay-blocks blocks))))
    (testing "最后一轮不续"
      (is (not (response/pause-turn-resumable?
                 (response/make-response :finish-reason "pause_turn" :replay-blocks blocks) true))))
    (testing "没有回放块 → 续不了；有本地工具调用 → 走工具循环"
      (is (not (response/pause-turn-resumable? (response/make-response :finish-reason "pause_turn"))))
      (is (not (response/pause-turn-resumable?
                 (response/make-response :finish-reason "pause_turn" :replay-blocks blocks
                                         :tool-calls [{:id "1" :name "t" :args {}}])))))
    (testing "非 pause 不续"
      (is (not (response/pause-turn-resumable?
                 (response/make-response :finish-reason "end_turn" :replay-blocks blocks)))))
    (testing "pause 不算空补全"
      (is (not (response/empty-completion? (response/make-response :finish-reason "pause_turn")))))))
