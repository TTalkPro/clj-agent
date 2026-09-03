(ns im.ttalk.agent.async-chat-model-test
  "ChatModel 异步入口：`IAsyncChatModel` / `IAsyncLLMProvider` 探测、虚拟线程兜底、
   归一化共用、重试等价。契约见 docs/async-chat-model-design.md §3–§5。

   贯穿全篇的一条断言：**两条路径逐字同义**——provider 收到的参数、返回的
   ChatResponse、重试次数，同步与异步必须一模一样。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.async :as async]
            [im.ttalk.agent.chat-model :as cm]
            [im.ttalk.agent.model :as proto]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.request :as req]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.retry :as retry])
  (:import [java.util.concurrent CompletableFuture]))

;;; ============================================================
;;; Mock provider：同步版 / 原生异步版
;;; ============================================================

(defn- raw [text] {:text text :thinking [{:type "thinking" :sig "s1"}]})

(defrecord SyncOnlyProvider [log responses]
  proto/ILLMProvider
  (provider-name [_] :mock-sync)
  (call-llm [_ config messages tools]
    (swap! log conj {:path :sync :messages messages :config config :tools tools})
    (let [r (first @responses)]
      (swap! responses rest)
      (if (instance? Throwable r) (throw r) r)))
  (call-llm-stream [_ config messages tools on-token]
    (swap! log conj {:path :sync-stream :messages messages})
    (when on-token (on-token {:token "hi"}))
    (raw "hi"))
  (extract-tool-calls [_ _] nil)
  (extract-text [_ r] (:text r))
  (build-tool-result [_ id content] {:role "tool" :id id :content content})
  (supports-function-calling? [_] true)
  (supports-stream? [_] true)
  (tool->schema [_ t] t)

  proto/IReplayableResponse
  (replay-blocks [_ r] (when (:thinking r) {:format :mock :data (:thinking r)})))

(defrecord AsyncProvider [log responses]
  proto/ILLMProvider
  (provider-name [_] :mock-async)
  (call-llm [_ config messages tools]
    (swap! log conj {:path :sync :messages messages :config config :tools tools})
    (raw "同步分支"))
  (call-llm-stream [_ config messages tools on-token]
    (swap! log conj {:path :sync-stream :messages messages})
    (raw "同步流"))
  (extract-tool-calls [_ _] nil)
  (extract-text [_ r] (:text r))
  (build-tool-result [_ id content] {:role "tool" :id id :content content})
  (supports-function-calling? [_] true)
  (supports-stream? [_] true)
  (tool->schema [_ t] t)

  proto/IReplayableResponse
  (replay-blocks [_ r] (when (:thinking r) {:format :mock :data (:thinking r)}))

  proto/IAsyncLLMProvider
  (call-llm-async [_ config messages tools]
    (swap! log conj {:path :async :messages messages :config config :tools tools})
    (async/vthread (fn []
                     (let [r (first @responses)]
                       (swap! responses rest)
                       (if (instance? Throwable r) (throw r) r)))))
  (call-llm-stream-async [_ config messages tools on-token]
    (swap! log conj {:path :async-stream :messages messages})
    (async/vthread (fn [] (when on-token (on-token {:token "hi"})) (raw "hi")))))

(defn- request [] (req/chat-request [(msg/user "问题")] {:tools []}))

;;; ============================================================
;;; 探测与兜底
;;; ============================================================

(deftest fallback-test
  (testing "provider 没有原生异步：call-async* 照样给 deferred（虚拟线程兜底），
            结果与同步 call 逐字相同"
    (let [log (atom []) p (->SyncOnlyProvider log (atom (repeat 4 (raw "答案"))))
          m (cm/create-chat-model p {:model "m"})
          sync-r (cm/call m (request))
          async-r (async/join (cm/call-async* m (request)) 3000)]
      (is (= "答案" (response/response-text sync-r) (response/response-text async-r)))
      (is (= sync-r async-r) "ChatResponse 逐字相同")
      (is (= [:sync :sync] (mapv :path @log)) "兜底走的仍是同步 call-llm")))

  (testing "ChatModel 本身没实现 IAsyncChatModel（FnChatModel）：同样兜底"
    (let [m (cm/->FnChatModel (fn [_ _] (response/make-response :text "fn" :tool-calls nil)) nil {})
          d (cm/call-async* m (request))]
      (is (async/deferred? d))
      (is (= "fn" (response/response-text (async/join d 3000))))))

  (testing "provider 有原生异步：走 call-llm-async，不占虚拟线程包同步调用"
    (let [log (atom []) p (->AsyncProvider log (atom (repeat 4 (raw "原生"))))
          m (cm/create-chat-model p {:model "m"})
          r (async/join (cm/call-async* m (request)) 3000)]
      (is (= "原生" (response/response-text r)))
      (is (= [:async] (mapv :path @log)) "确实走了原生异步分支"))))

;;; ============================================================
;;; 归一化不跑偏（旧旁路的反例锚点）
;;; ============================================================

(deftest normalization-anchor-test
  (testing "异步路径与同步路径**收到同样的参数**（messages/config/tools 不走样）"
    (let [slog (atom []) alog (atom [])
          sp (->SyncOnlyProvider slog (atom (repeat 2 (raw "x"))))
          ap (->AsyncProvider alog (atom (repeat 2 (raw "x"))))
          cfg {:model "m" :max-tokens 99}
          _ (cm/call (cm/create-chat-model sp cfg) (request))
          _ (async/join (cm/call-async* (cm/create-chat-model ap cfg) (request)) 3000)
          s (first @slog) a (first @alog)]
      (is (= (:messages s) (:messages a)))
      (is (= (:config s) (:config a)) "build-call-config 是同一份")
      (is (= (:tools s) (:tools a)))))

  (testing "异步返回的是 ChatResponse，不是裸 HTTP/原始 map；replay blocks 在位"
    (let [p (->AsyncProvider (atom []) (atom (repeat 2 (raw "x"))))
          m (cm/create-chat-model p {:model "m"})
          r (async/join (cm/call-async* m (request)) 3000)]
      (is (satisfies? response/ILLMResponse r) "归一化过，不是原始响应")
      (is (= {:format :mock :data [{:type "thinking" :sig "s1"}]} (:replay-blocks r))
          "可选协议的回放载荷在异步路径上照样抽取——这正是旧旁路丢掉的东西"))))

;;; ============================================================
;;; 重试等价
;;; ============================================================

(defn- retryable [] (ex-info "抖动" {:retryable? true}))

(deftest retry-parity-test
  (testing "run / run-async：尝试次数、退避序列、:on-retry 观测逐字相同"
    (let [mk (fn [] (let [n (atom 0)]
                      (fn [] (if (< (swap! n inc) 3) (throw (retryable)) [:ok @n]))))
          sync-delays (atom []) async-delays (atom [])
          sync-obs (atom []) async-obs (atom [])
          opts (fn [delays obs]
                 {:max-retries 3 :base-delay 100 :multiplier 2.0 :max-delay 30000
                  :rand-fn (constantly 1.0)          ;; 关掉抖动，序列可比
                  :sleep-fn #(swap! delays conj %)
                  :on-retry #(swap! obs conj (:attempt %))})
          sync-r (retry/run (mk) (opts sync-delays sync-obs))
          f (mk)
          async-r (async/join (retry/run-async #(async/vthread f) (opts async-delays async-obs)) 3000)]
      (is (= [:ok 3] sync-r async-r))
      (is (= [100 200] @sync-delays @async-delays) "退避曲线同一份")
      (is (= [1 2] @sync-obs @async-obs) ":on-retry 观测到同样的真实尝试")))

  (testing "不可重试的错误：两条路径都当场抛，且是原异常"
    (let [e (ex-info "401" {:retryable? false})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"401"
            (retry/run (fn [] (throw e)) {:max-retries 3})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"401"
            (async/join (retry/run-async (fn [] (async/failed e)) {:max-retries 3}) 3000)))))

  (testing "ChatModel 层：原生异步 provider 的失败照样被重试，次数与同步一致"
    (let [p (->AsyncProvider (atom []) (atom [(retryable) (retryable) (raw "终于")]))
          m (cm/create-chat-model p {:model "m" :retry {:max-retries 3 :base-delay 1 :max-delay 2}})
          r (async/join (cm/call-async* m (request)) 5000)]
      (is (= "终于" (response/response-text r))))))

;;; ============================================================
;;; 流式
;;; ============================================================

(deftest stream-async-test
  (testing "原生异步流式：token 照常到达，返回归一化响应"
    (let [log (atom []) toks (atom [])
          p (->AsyncProvider log (atom []))
          m (cm/create-chat-model p {:model "m"})
          r (async/join (cm/stream-call-async* m (request) #(swap! toks conj (:token %))) 3000)]
      (is (= ["hi"] @toks))
      (is (= "hi" (response/response-text r)))
      (is (= [:async-stream] (mapv :path @log)))))

  (testing "无原生异步：虚拟线程兜底，语义与 stream-call 相同"
    (let [log (atom []) toks (atom [])
          p (->SyncOnlyProvider log (atom []))
          m (cm/create-chat-model p {:model "m"})
          r (async/join (cm/stream-call-async* m (request) #(swap! toks conj (:token %))) 3000)]
      (is (= ["hi"] @toks))
      (is (= "hi" (response/response-text r)))
      (is (= [:sync-stream] (mapv :path @log)))))

  (testing "call-async* 返回的确实是 deferred（不是同步值伪装）"
    (let [p (->AsyncProvider (atom []) (atom [(raw "x")]))
          m (cm/create-chat-model p {:model "m"})]
      (is (instance? CompletableFuture (cm/call-async* m (request)))))))
