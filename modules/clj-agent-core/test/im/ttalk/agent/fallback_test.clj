(ns im.ttalk.agent.fallback-test
  "主备模型切换（designs/provider-reliability.md §3）+ 注册表接法（§8）。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.async :as async]
            [im.ttalk.agent.chat-model :as cm]
            [im.ttalk.agent.chat-model.fallback :as fb]
            [im.ttalk.agent.model.error :as errors]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.request :as req]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.model.spec :as spec]))

(defn- err [type & [opts]] (ex-info (name type) (errors/error type (name type) opts)))

(defn- stub
  "FnChatModel 替身：script 每次调用取一项（Throwable → 抛；map → 作为 make-response 参数）；
   seen 记录收到的请求。tokens 非空时流式先吐 token。"
  [seen script & [tokens]]
  (let [next! (fn [r]
                (swap! seen conj r)
                (let [x (first @script)] (swap! script rest)
                  (if (instance? Throwable x) (throw x) (apply response/make-response (mapcat identity x)))))]
    {:chat-fn (fn [messages opts] (next! {:messages messages :options opts}))
     :stream-fn (fn [messages opts on-token]
                  (doseq [t tokens] (on-token {:token t}))
                  (next! {:messages messages :options opts}))}))

(def ^:private thinking-msg
  (msg/with-blocks (msg/assistant "上一轮") {:format :anthropic-content :data [{:type "thinking" :signature "s"}]}))

(defn- rq [] (req/chat-request [thinking-msg (msg/user "hi")] {:model "claude-x"}))

(deftest switch-on-provider-failure-test
  (testing "主站 5xx → 切备用，报账；备用惰性构造；跨 provider 剪 :blocks、去 :model"
    (let [s0 (atom []) s1 (atom []) created (atom 0) switches (atom [])
          f (fb/fallback-chat-model
              (stub s0 (atom [(err :provider-error {:retryable? true :status 503})]))
              [{:ref "openai/gpt-4o" :create (fn [] (swap! created inc) (stub s1 (atom [{:text "备用答"}])))}]
              {:primary-ref "anthropic/claude-x"
               :on-switch #(swap! switches conj [(:from %) (:to %)])})
          r (cm/call f (rq))]
      (is (= "备用答" (response/response-text r)))
      (is (= "openai/gpt-4o" (:model-ref r)))
      (is (= 1 @created))
      (is (= [["anthropic/claude-x" "openai/gpt-4o"]] @switches))
      (let [rep (fb/report r)]
        (is (= 1 (:switches rep)))
        (is (= "anthropic/claude-x" (:primary rep)))
        (is (= [:provider-error] (mapv (comp :type :error) (:attempts rep))))
        (is (= 1 (count (:trail rep)))))
      (is (contains? (first (:messages (first @s0))) :blocks) "主站原样")
      (is (not-any? :blocks (:messages (first @s1))) "跨 provider 剪掉 thinking 回放块")
      (is (nil? (:model (:options (first @s1)))) "不拿主模型的名字问备胎")))
  (testing "同 provider 换模型不剪 :blocks"
    (let [s1 (atom [])
          f (fb/fallback-chat-model
              (stub (atom []) (atom [(err :quota-exhausted-error)]))
              [{:ref "anthropic/claude-y" :chat-model (stub s1 (atom [{:text "ok"}]))}]
              {:primary-ref "anthropic/claude-x"})]
      (cm/call f (rq))
      (is (some :blocks (:messages (first @s1))))))
  (testing "主站一次过：不构造备用，报账 switches 0"
    (let [created (atom 0)
          f (fb/fallback-chat-model (stub (atom []) (atom [{:text "主答"}]))
                                    [{:ref "openai/gpt" :create #(do (swap! created inc) nil)}]
                                    {:primary-ref "a/b"})
          r (cm/call f (rq))]
      (is (= 0 @created))
      (is (= 0 (:switches (fb/report r)))))))

(deftest no-switch-cases-test
  (testing "validation / context-overflow 不切，原样抛（ex-data 带报告）"
    (doseq [t [:validation-error :context-overflow-error]]
      (let [created (atom 0)
            f (fb/fallback-chat-model (stub (atom []) (atom [(err t)]))
                                      [{:ref "openai/gpt" :create #(do (swap! created inc) nil)}]
                                      {:primary-ref "a/b"})]
        (try (cm/call f (rq)) (is false)
             (catch clojure.lang.ExceptionInfo e
               (is (= t (:type (ex-data e))))
               (is (= 0 (:switches (fb/report e))))))
        (is (= 0 @created)))))
  (testing "流式吐过 token 后失败不切"
    (let [created (atom 0)
          f (fb/fallback-chat-model (stub (atom []) (atom [(err :network-error)]) ["半句"])
                                    [{:ref "openai/gpt" :create #(do (swap! created inc) nil)}]
                                    {:primary-ref "a/b"})]
      (is (thrown? clojure.lang.ExceptionInfo (cm/stream-call f (rq) (fn [_]))))
      (is (= 0 @created))))
  (testing "流式首 token 前失败 → 切"
    (let [tokens (atom [])
          f (fb/fallback-chat-model (stub (atom []) (atom [(err :network-error)]))
                                    [{:ref "openai/gpt" :chat-model (stub (atom []) (atom [{:text "ok"}]) ["o" "k"])}]
                                    {:primary-ref "a/b"})
          r (cm/stream-call f (rq) #(swap! tokens conj (:token %)))]
      (is (= "ok" (response/response-text r)))
      (is (= ["o" "k"] @tokens)))))

(deftest construction-and-chain-failure-test
  (testing "备用构造抛错 → 硬切下一站（不看判据）"
    (let [f (fb/fallback-chat-model (stub (atom []) (atom [(err :auth-error)]))
                                    [{:ref "x/broken" :create #(throw (ex-info "没配 key" {}))}
                                     {:ref "y/ok" :chat-model (stub (atom []) (atom [{:text "y"}]))}]
                                    {:primary-ref "a/b"})
          r (cm/call f (rq))]
      (is (= "y/ok" (:model-ref r)))
      (is (= 2 (:switches (fb/report r))))))
  (testing "全链失败：抛最后一个错误，报告 switches = 尝试数 − 1"
    (let [f (fb/fallback-chat-model (stub (atom []) (atom [(err :provider-error {:retryable? true})]))
                                    [{:ref "x/a" :chat-model (stub (atom []) (atom [(err :rate-limit-error)]))}]
                                    {:primary-ref "a/b"})]
      (try (cm/call f (rq)) (is false)
           (catch clojure.lang.ExceptionInfo e
             (is (= :rate-limit-error (:type (ex-data e))))
             (is (= 1 (:switches (fb/report e))))
             (is (= "x/a" (:model (fb/report e))))
             (is (= 2 (count (:attempts (fb/report e)))))))))
  (testing "空补全且有下一站 → 切"
    (let [f (fb/fallback-chat-model (stub (atom []) (atom [{:text ""}]))
                                    [{:ref "x/a" :chat-model (stub (atom []) (atom [{:text "有内容"}]))}]
                                    {:primary-ref "a/b"})]
      (is (= "有内容" (response/response-text (cm/call f (rq)))))))
  (testing "ref 形状校验"
    (is (thrown? clojure.lang.ExceptionInfo
                 (fb/fallback-chat-model (stub (atom []) (atom [])) [{:ref "noslash" :create (fn [])}])))))

(deftest async-fallback-test
  (testing "call-async* / stream-call-async* 同语义"
    (let [f (fb/fallback-chat-model (stub (atom []) (atom [(err :timeout-error)]))
                                    [{:ref "x/a" :chat-model (stub (atom []) (atom [{:text "异步备用"}]))}]
                                    {:primary-ref "a/b"})]
      (is (= "异步备用" (response/response-text (async/join (cm/call-async* f (rq)) 5000)))))
    (let [f (fb/fallback-chat-model (stub (atom []) (atom [(err :validation-error)]))
                                    [{:ref "x/a" :chat-model (stub (atom []) (atom [{:text "不该到"}]))}]
                                    {:primary-ref "a/b"})]
      (is (thrown? Exception (async/join (cm/call-async* f (rq)) 5000))))
    (let [f (fb/fallback-chat-model (stub (atom []) (atom [(err :network-error)]))
                                    [{:ref "x/a" :chat-model (stub (atom []) (atom [{:text "s"}]) ["s"])}]
                                    {:primary-ref "a/b"})]
      (is (= "s" (response/response-text (async/join (cm/stream-call-async* f (rq) (fn [_])) 5000)))))))

(deftest from-registry-test
  (testing "按 spec/fallback-chain 展开，惰性 create"
    (let [reg (spec/registry [["a/main" {:fallbacks ["b/alt" "c/unknown"]}]
                              ["b/alt" {:fallbacks ["d/third"]}]
                              ["d/third" {}]])
          created (atom [])
          create (fn [s]
                   (swap! created conj (:ref s))
                   (case (:ref s)
                     "a/main" (stub (atom []) (atom [(err :network-error)]))
                     "b/alt" (stub (atom []) (atom [(err :network-error)]))
                     "d/third" (stub (atom []) (atom [{:text "第三站"}]))))
          f (fb/from-registry reg "a/main" {:create create})
          r (cm/call f (rq))]
      (is (= ["b/alt" "c/unknown" "d/third"] (spec/fallback-chain reg "a/main")))
      (is (= "第三站" (response/response-text r)))
      (is (= ["a/main" "b/alt" "d/third"] @created) "未登记的 c/unknown 轮到才报错、硬切")
      (is (= "d/third" (:model-ref r))))))
