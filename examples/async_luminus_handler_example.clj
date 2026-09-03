(ns async-luminus-handler-example
  "Ring / Luminus **异步 handler** 端到端示例（离线可跑，不需要 API Key）。

   ## 这个例子在证明什么

   `agent/chat-async` 立刻返回 `CompletableFuture`，整个 turn 跑在虚拟线程上——
   HTTP 工作线程不被 LLM 往返占住。配 Ring 3 的三参数异步 handler
   `(fn [request respond raise])` 正好严丝合缝：

   ```clojure
   (defn chat-handler [request respond raise]
     (-> (agent/chat-async (session-agent request) (message-of request))
         (flt/fmap ->ring-response)      ;; 响应侧改写：同步/异步同一份代码
         (async/on-complete respond raise)))
   ```

   Luminus（reitit + ring）里就是把这个三参数函数直接挂到路由上：

   ```clojure
   [\"/api/chat\" {:post {:handler chat-handler}}]   ;; reitit 按 arity 自动识别异步
   ;; 服务器需支持异步：ring-jetty {:async? true}、immutant、http-kit 均可
   ```

   本文件**不引任何 web 依赖**——用一个十行的假容器（`fake-container`）按
   Ring 异步契约调 handler，从而在 `clojure -M -e ...` 下就能跑完全套。
   换成真 jetty 时 handler 一个字都不用改。

   ## 场景

   1. **并发**：8 个会话同时进来，墙钟 ≈ 一次往返而不是 8 次（不阻塞的实证）
   2. **同义**：同一问题走同步 `chat` 与异步 `chat-async`，结果逐字相同
   3. **raise**：渲染函数抛异常 → 走 Ring 的 `raise` 回调（而不是静默 500）
   4. **HITL**：敏感工具暂停 → 202 + pending-tool → `resume-async` 续跑
   5. **turn filter**：一个用 `flt/fmap` 写的计时 filter，在异步链上照常工作

   设计依据：docs/filter-chain-design.md §2.6（为什么是 around 不是 before/after）
   与 §2.6.4（`fmap`/`fbind`/`fcatch` 契约）。

   运行：
     clojure -M -e \"(load-file \\\"examples/async_luminus_handler_example.clj\\\")\""
  (:require [im.ttalk.agent.async :as async]
            [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.chat-client :as chat-client]
            [im.ttalk.agent.filter.memory :as ma]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.simple-agent :as agent]
            [im.ttalk.agent.tool :refer [deftool]]))

(def failures (atom 0))

(defn- check [ok? label & [detail]]
  (if ok?
    (println "  ✓" label (or detail ""))
    (do (swap! failures inc)
        (println "  ✗" label (or detail "")))))

;;; ============================================================
;;; 桩：一个「慢」的 provider + 一个敏感工具
;;; ============================================================

(def ^:private llm-latency-ms 200)

(deftool refund-order
  "给订单退款（敏感操作，需人工批准）"
  [[order-id :string "订单号"]]
  {:sensitive true}
  (str "订单 " order-id " 已退款"))

(defn- slow-chat-model
  "每次 LLM 调用睡 llm-latency-ms 毫秒，模拟真实往返。`reply-fn` 决定回什么。"
  [reply-fn]
  {:chat-fn (fn [msgs opts]
              (Thread/sleep (long llm-latency-ms))
              (reply-fn msgs opts))})

(defn- make-agent
  "一个会话一个 agent（agent 的 state-atom 是单会话状态机，别在同一个上并发）。"
  [reply-fn & {:keys [tools filters]}]
  (agent/create-agent
    {:chat-client (chat-client/build-chat-client
                    (cond-> {:chat-model (slow-chat-model reply-fn)
                             :filters (vec (cons (ma/memory-filter (memory/in-memory-store))
                                                 filters))}
                      tools (assoc :tools tools)))
     :on-pause (fn [_] nil)}))          ;; 配置即启用声明式暂停（:sensitive 工具）

(defn- echo-agent [answer]
  (make-agent (fn [_ _] (response/make-response :text answer :tool-calls nil))))

;;; ============================================================
;;; 被测主体：Ring 异步 handler
;;; ============================================================

(defn- ->ring-response
  "把 agent 结果渲染成 Ring 响应。三种终态各有 HTTP 语义：
   completed → 200；paused → 202 Accepted（还需人工介入）；error → 500。"
  [r]
  (case (:status r)
    :completed {:status 200 :body {:text (:text r)}}
    :paused    {:status 202 :body {:pending-tool (:name (:pending-tool r))
                                   :reason (:pause-reason r)}}
    {:status 500 :body {:error (:message (:error r))}}))

(defn chat-handler
  "**这就是要交付给 Luminus 的东西**：Ring 3 的三参数异步 handler。

   `agent` 从请求里取（真实场景是 session/租户 → agent 实例的映射）。
   注意响应侧全走 `flt/fmap`——若哪天 `chat-async` 换成同步 `chat`，
   这个 handler 一个字都不用改（契约 C1）。"
  [render]
  (fn [request respond raise]
    (-> (agent/chat-async (:agent request) (:message request))
        (flt/fmap render)
        (async/on-complete respond raise))))

(defn resume-handler
  "HITL 第二段：人批准之后接着跑，同样不阻塞 HTTP 线程。"
  [render]
  (fn [request respond raise]
    (-> (agent/resume-async (:agent request) (:decision request))
        (flt/fmap render)
        (async/on-complete respond raise))))

(defn- fake-container
  "十行假容器：按 Ring 异步契约调 handler——立刻返回，结果稍后从 promise 取。
   真 jetty/immutant 做的是同一件事（respond/raise 写回 servlet 的 AsyncContext）。"
  [handler request]
  (let [p (promise)]
    (handler request #(deliver p [:respond %]) #(deliver p [:raise %]))
    p))

(defn- ms-since [t0] (/ (- (System/nanoTime) t0) 1e6))

;;; ============================================================
;;; 场景 1：并发——墙钟 ≈ 一次往返
;;; ============================================================

(defn scenario-concurrency []
  (println "\n[1] 并发：8 个会话同时进来")
  (let [n 8
        handler (chat-handler ->ring-response)
        agents (mapv #(echo-agent (str "答复-" %)) (range n))
        t0 (System/nanoTime)
        ps (mapv (fn [a i] (fake-container handler {:agent a :message (str "问题-" i)}))
                 agents (range n))
        dispatch (ms-since t0)
        outs (mapv #(deref % 10000 :timeout) ps)
        total (ms-since t0)]
    (check (< dispatch llm-latency-ms) "派发不阻塞"
           (format "8 个请求全部发出耗时 %.1fms（单次 LLM 往返 %dms）" dispatch llm-latency-ms))
    (check (= (mapv (fn [i] [:respond {:status 200 :body {:text (str "答复-" i)}}]) (range n))
              outs)
           "8 个响应都正确回来了")
    (check (< total (* llm-latency-ms 3)) "并发生效"
           (format "总墙钟 %.1fms（串行需 %dms）" total (* n llm-latency-ms)))))

;;; ============================================================
;;; 场景 2：异步与同步逐字同义
;;; ============================================================

(defn scenario-same-semantics []
  (println "\n[2] 同义：chat 与 chat-async 结果逐字相同")
  (let [sync-r  (agent/chat (echo-agent "一样的答案") "hi")
        async-r (async/join (agent/chat-async (echo-agent "一样的答案") "hi") 10000)]
    (check (= (dissoc sync-r :tool-calls-made) (dissoc async-r :tool-calls-made))
           "两条路径返回同一形状" (pr-str (select-keys async-r [:status :text])))
    (check (= (->ring-response sync-r) (->ring-response async-r))
           "同一个渲染函数吃两种结果")))

;;; ============================================================
;;; 场景 3：raise —— 渲染出错走 Ring 的错误回调
;;; ============================================================

(defn scenario-raise []
  (println "\n[3] raise：渲染函数抛异常 → 走 Ring 的 raise，而不是悄悄 500")
  (let [handler (chat-handler (fn [_] (throw (ex-info "模板渲染炸了" {:tpl :chat}))))
        [kind payload] (deref (fake-container handler {:agent (echo-agent "x") :message "hi"})
                              10000 [:timeout nil])]
    (check (= :raise kind) "走的是 raise 回调")
    (check (= "模板渲染炸了" (ex-message payload)) "拿到的是原异常，不是 CompletionException 包装"
           (pr-str (ex-data payload)))))

;;; ============================================================
;;; 场景 4：HITL —— 202 → 人批准 → resume-async
;;; ============================================================

(defn scenario-hitl []
  (println "\n[4] HITL：敏感工具暂停 → 202 → resume-async 续跑")
  (let [calls (atom 0)
        a (make-agent (fn [_ _]
                        (if (= 1 (swap! calls inc))
                          (response/make-response :text nil
                            :tool-calls [{:id "r1" :name "refund-order" :args {:order-id "A-1"}}])
                          (response/make-response :text "退款已完成" :tool-calls nil)))
                      :tools [#'refund-order])
        [_ paused] (deref (fake-container (chat-handler ->ring-response)
                                          {:agent a :message "给订单 A-1 退款"})
                          10000 [:timeout nil])
        [_ done] (deref (fake-container (resume-handler ->ring-response)
                                        {:agent a :decision "approved"})
                        10000 [:timeout nil])]
    (check (= 202 (:status paused)) "暂停渲染成 202 Accepted" (pr-str (:body paused)))
    (check (= "refund-order" (get-in paused [:body :pending-tool])) "带回待批准的工具名")
    (check (= {:status 200 :body {:text "退款已完成"}} done) "resume-async 续跑到完成")))

;;; ============================================================
;;; 场景 5：turn filter 在异步链上照常工作
;;; ============================================================

(defn- timing-turn-filter
  "turn 级计时 filter：响应侧走 fmap，故同步/异步两条链都能用。
   （若写成 `(let [r (chain req)] ...)`，异步下 r 是个 deferred，计时会立刻结束、
   拿到的也不是结果——这正是 §2.6.4 要求响应侧走组合子的原因。）"
  [record!]
  (flt/create-filter :timing
    :turn (fn [req chain]
            (let [t0 (System/nanoTime)]
              (flt/fmap (chain req)
                        (fn [result]
                          (record! {:ms (ms-since t0) :status (:status result)})
                          result))))))

(defn scenario-turn-filter []
  (println "\n[5] turn filter：同一份 fmap 写法，同步链与异步链都对")
  (let [sync-log (atom []) async-log (atom [])
        mk (fn [log] (make-agent (fn [_ _] (response/make-response :text "ok" :tool-calls nil))
                                 :filters [(timing-turn-filter #(swap! log conj %))]))]
    (agent/chat (mk sync-log) "hi")
    (async/join (agent/chat-async (mk async-log) "hi") 10000)
    (check (= 1 (count @sync-log) (count @async-log)) "两条链各触发一次")
    (check (= [:completed] (mapv :status @sync-log) (mapv :status @async-log))
           "filter 看到的是真正的 TurnResult，不是 deferred")
    (check (every? #(>= (:ms %) llm-latency-ms) (concat @sync-log @async-log))
           "计时覆盖了整个 turn"
           (format "sync %.0fms / async %.0fms"
                   (:ms (first @sync-log)) (:ms (first @async-log))))))

;;; ============================================================
;;; 汇总
;;; ============================================================

(defn run
  "跑全部场景，**返回失败项数**（不 exit——嵌入 run_all_examples 时要接着往下跑）。"
  []
  (reset! failures 0)
  (println "Ring / Luminus 异步 handler 示例 | 桩 provider（离线）| 单次 LLM 往返"
           llm-latency-ms "ms")
  (doseq [f [scenario-concurrency scenario-same-semantics scenario-raise
             scenario-hitl scenario-turn-filter]]
    (try (f)
         (catch Throwable t
           (swap! failures inc)
           (println "  ✗ 场景异常:" (.getMessage t)))))
  (println)
  (if (zero? @failures)
    (println "全部通过 ✓")
    (println @failures "项失败 ✗"))
  @failures)

;;; 独立运行才 exit；被 run_all_examples `load-file` 进来时只定义、不自跑。
(when-not (System/getProperty "clj-agent.embedded-examples")
  (System/exit (if (zero? (run)) 0 1)))
