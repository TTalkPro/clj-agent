(ns im.ttalk.agent.retry-test
  "ChatModel 层重试（`im.ttalk.agent.retry`）。

   重点钉三条**上移后最容易悄悄丢掉**的性质：
   1. 重试在 filter 栈**之下** —— filter 只看到一次逻辑调用（否则 memory 会
      把同一轮 delta 写两遍，且没有任何运行期症状）；
   2. **流式不重试** —— token 已投递给 sink，重跑即重复内容；
   3. 判据是 canonical error 的 `:retryable?`，401 这类**永不**重试。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.chat-client :as chat-client]
            [im.ttalk.agent.chat-model :as cm]
            [im.ttalk.agent.model :as provider]
            [im.ttalk.agent.model.error :as errors]
            [im.ttalk.agent.model.request :as req]
            [im.ttalk.agent.model.response :as resp]
            [im.ttalk.agent.retry :as retry]))

;;; ============================================================
;;; 测试替身
;;; ============================================================

(defn- flaky-provider
  "前 `n-fail` 次调用抛 `err`，此后返回 {:text \"ok\"}。calls 记录实际尝试次数。"
  [calls n-fail err]
  (reify provider/ILLMProvider
    (provider-name [_] :flaky)
    (call-llm [_ _ _ _]
      (let [n (swap! calls inc)]
        (if (<= n n-fail) (errors/throw! err) {:text "ok" :tool-calls nil})))
    (supports-stream? [_] true)
    (call-llm-stream [_ _ _ _ on-token]
      (let [n (swap! calls inc)]
        (when on-token (on-token {:token "tok"}))
        (if (<= n n-fail) (errors/throw! err) {:text "ok" :tool-calls nil})))
    (extract-tool-calls [_ r] (:tool-calls r))
    (extract-text [_ r] (:text r))
    (build-tool-result [_ id content] {:role "tool" :tool_call_id id :content content})))

(def ^:private transient-err
  (errors/error :network-error "连接抖动" {:retryable? true}))

;; 走真实路径构造：401 经 http-response->error 归为 :auth-error / :retryable? false。
;; **不要**手写 (errors/error :provider-error ... {:status 401})——`errors/error` 的
;; 默认表里 :provider-error 属可重试类，:status 不参与判定，那样构造出来的 401
;; 是可重试的，测试就测不到想测的东西了。
(def ^:private auth-err
  (errors/http-response->error {:status 401 :body "unauthorized"} :flaky))

(def ^:private no-sleep {:sleep-fn (fn [_]) :rand-fn (constantly 0.0)})

;;; ============================================================
;;; retry/run 本体
;;; ============================================================

(deftest run-retries-transient-then-succeeds
  (testing "可重试错误退避后成功；尝试次数 = 失败次数 + 1"
    (let [calls (atom 0)
          f #(let [n (swap! calls inc)]
               (if (< n 3) (errors/throw! transient-err) :done))]
      (is (= :done (retry/run f (merge {:max-retries 3} no-sleep))))
      (is (= 3 @calls)))))

(deftest run-never-retries-non-retryable
  (testing "401 不可重试 —— 即便 max-retries 很大也只打一次"
    (let [calls (atom 0)
          f #(do (swap! calls inc) (errors/throw! auth-err))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (retry/run f (merge {:max-retries 5} no-sleep))))
      (is (= 1 @calls) "对本质不可重试的错误反复打 API 是在烧钱和触发风控")))

  (testing "没有 :retryable? 的异常（框架 bug / 用户工具抛的）一律不重试"
    (let [calls (atom 0)
          f #(do (swap! calls inc) (throw (ex-info "boom" {})))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (retry/run f (merge {:max-retries 5} no-sleep))))
      (is (= 1 @calls)))))

(deftest run-rethrows-canonical-error-intact
  (testing "重试耗尽后**原样重抛** —— :retryable? / :status 全程不丢"
    (let [f #(errors/throw! (errors/error :provider-error "503" {:status 503 :retryable? true}))]
      (try
        (retry/run f (merge {:max-retries 1} no-sleep))
        (is false "应当抛出")
        (catch clojure.lang.ExceptionInfo e
          (let [d (ex-data e)]
            (is (= 503 (:status d)))
            (is (true? (:retryable? d)))
            (is (= :provider-error (:type d)))))))))

(deftest run-on-retry-observes-real-attempts
  (testing ":on-retry 每次退避前触发一次 —— 这是观测真实尝试的唯一入口"
    (let [events (atom [])
          calls  (atom 0)
          f #(let [n (swap! calls inc)]
               (if (< n 3) (errors/throw! transient-err) :done))]
      (retry/run f (merge {:max-retries 3
                           :on-retry #(swap! events conj (select-keys % [:attempt :delay-ms]))}
                          no-sleep))
      (is (= [1 2] (mapv :attempt @events)))
      (is (every? #(>= (:delay-ms %) 0) @events)))))

(deftest retry-after-respected-and-capped
  (testing "服务端 Retry-After 优先于退避计算，但**受 :max-delay 约束**"
    (let [slept (atom [])
          calls (atom 0)
          err   (assoc (errors/error :rate-limit-error "429" {:status 429 :retryable? true})
                       :retry-after-ms 3600000)          ;; 服务端说等一小时
          f #(let [n (swap! calls inc)]
               (if (< n 2) (errors/throw! err) :done))]
      (retry/run f {:max-retries 2 :max-delay 30000 :respect-retry-after? true
                    :rand-fn (constantly 0.0)
                    :sleep-fn #(swap! slept conj %)})
      (is (= [30000] @slept)
          "照单全收会让同步线程睡满一小时，:max-delay 形同虚设")))

  (testing ":respect-retry-after? false → 走退避计算，不看服务端建议"
    (let [slept (atom [])
          calls (atom 0)
          err   (assoc (errors/error :rate-limit-error "429" {:status 429 :retryable? true})
                       :retry-after-ms 3600000)
          f #(let [n (swap! calls inc)]
               (if (< n 2) (errors/throw! err) :done))]
      (retry/run f {:max-retries 2 :base-delay 1000 :max-delay 30000
                    :respect-retry-after? false
                    :rand-fn (constantly 1.0)
                    :sleep-fn #(swap! slept conj %)})
      (is (= [1000] @slept)))))

;;; ============================================================
;;; 三级取值
;;; ============================================================

(deftest resolve-opts-three-levels
  (testing "框架默认"
    (is (= 2 (:max-retries (retry/resolve-opts {} {})))))

  (testing "provider config 覆盖默认"
    (is (= 5 (:max-retries (retry/resolve-opts {:retry {:max-retries 5}} {})))))

  (testing "单次 opts 覆盖 provider config"
    (is (= 1 (:max-retries (retry/resolve-opts {:retry {:max-retries 5}}
                                               {:retry {:max-retries 1}})))))

  (testing ":retry false 关闭（任一级都行）"
    (is (= 0 (:max-retries (retry/resolve-opts {:retry false} {}))))
    (is (= 0 (:max-retries (retry/resolve-opts {:retry {:max-retries 5}} {:retry false})))))

  (testing ":retry true 沿用上一级，不重置"
    (is (= 5 (:max-retries (retry/resolve-opts {:retry {:max-retries 5}} {:retry true})))))

  (testing "非法值装配期即抛"
    (is (thrown? clojure.lang.ExceptionInfo (retry/resolve-opts {:retry "3"} {})))))

;;; ============================================================
;;; ChatModel 集成
;;; ============================================================

(deftest chat-model-call-retries
  (testing "DefaultChatModel.call 内建重试"
    (let [calls (atom 0)
          model (cm/create-chat-model (flaky-provider calls 2 transient-err)
                                      {:model "m" :retry {:max-retries 3}})]
      (is (= "ok" (resp/response-text
                    (cm/call model (req/chat-request [{:role :user :content "hi"}]
                                                     no-sleep)))))
      (is (= 3 @calls))))

  (testing ":retry false 关闭 —— 只打一次就抛"
    (let [calls (atom 0)
          model (cm/create-chat-model (flaky-provider calls 2 transient-err)
                                      {:model "m" :retry false})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (cm/call model (req/chat-request [{:role :user :content "hi"}]))))
      (is (= 1 @calls)))))

(deftest chat-model-stream-does-not-retry
  (testing "流式**不重试** —— token 已投递给 sink，重跑会让下游看到重复内容"
    (let [calls  (atom 0)
          tokens (atom [])
          model  (cm/create-chat-model (flaky-provider calls 2 transient-err)
                                       {:model "m" :retry {:max-retries 5}})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (cm/stream-call model
                                   (req/chat-request [{:role :user :content "hi"}])
                                   #(swap! tokens conj %))))
      (is (= 1 @calls) "重试次数配了 5，流式路径也必须只打一次")
      (is (= 1 (count @tokens)) "重跑会让这里变成 2 个重复 token"))))

;;; ============================================================
;;; 与 filter 链的位置关系（本次重构的核心不变量）
;;; ============================================================

(deftest retry-sits-below-the-filter-stack
  (testing "重试重入不经过 :chat filter —— filter 只看到一次逻辑调用"
    (let [calls      (atom 0)
          filter-hit (atom 0)
          counting   {:name :counting
                      :chat (fn [req chain] (swap! filter-hit inc) (chain req))}
          model      (cm/create-chat-model (flaky-provider calls 2 transient-err)
                                           {:model "m" :retry {:max-retries 3}})
          cc         (chat-client/build-chat-client {:chat-model model :filters [counting]})]
      (chat-client/invoke-chat cc [{:role :user :content "hi"}] no-sleep)
      (is (= 3 @calls) "provider 真的被打了 3 次")
      (is (= 1 @filter-hit)
          "filter 若跟着重入，memory-filter 就会把同一轮 delta 写 3 遍——
           这正是重试必须待在 filter 栈之下的理由"))))
