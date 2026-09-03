(ns im.ttalk.agent.subagent.manager
  "子 Agent 管理器 — 受管异步注册表（对标 beamai_subagent_manager）

   维护一个全局 atom 注册表，每个子 agent 在虚拟线程上运行
   （j.u.c.Future 语义，kill! 走 future-cancel 中断）。

   状态机：:running → :done | :failed | :killed

   使用模式：
   1. 同步委派（spawn→await→drop）：
        (let [{:ok id} (spawn! spec)]
          (await! id 60000)
          (drop! id))

   2. 异步管理（LLM 跨轮自管）：
        (let [{:ok id} (spawn! spec)]
          ;; 稍后查询
          (result id)
          ;; 或 kill
          (kill! id))

   spec 格式：
   {:subagent-config map    — 传给 client/create-agent 的配置
    :prompt          string — 子 agent 的用户输入
    :result-fn       fn?    — (fn [chat-result] -> string)，默认取 :text
    :owner           any?   — 归属标识，用于 list-agents 过滤
    :observer        fn?    — 观察者工厂，见下
    :subagent-name   string — 给观察者看的标签（缺省 \"subagent\"）
    :task            string — 同上，缺省取 :prompt
    :parent-tool-call-id string — 同上，发起本次委派的 tool-call id}

   **观察者（`:observer`）——协议无关的观察挂点**

   `(fn [{:keys [id name task owner attempt parent-tool-call-id]}]
      -> {:decorate :chat-opts :start! :settle!})`

   | 键 | 类型 | 说明 |
   |---|---|---|
   | `:decorate`  | `(fn [agent] agent')` | 给子 agent 挂采集（如事件发射）。抛异常 → 用原 agent |
   | `:chat-opts` | map | 并进子 agent 的 `chat` opts（如 `:on-token`） |
   | `:start!`    | `(fn [])` | 建 agent 之前 |
   | `:settle!`   | `(fn [outcome])` | **在 `finally` 里**，故 kill / 超时 / 崩溃三条路径都覆盖得到 |

   三条契约：

   1. **工厂在 worker 线程上调用，每次 spawn 一次**（`restart!` 算新的一次，
      `:attempt` 递增）——fan-out N 路 = N 个观察者实例，各自独立；
   2. **四个钩子全可选、各自吞异常**：观察者永远不许改变被观察者的行为；
   3. 本 ns **不认识任何协议**。AG-UI 那侧（`agui.subagent`）负责把这四个钩子
      翻译成事件——client 模块不依赖 agui，反向依赖也不存在。"
  (:require [taoensso.timbre :as log])
  (:import [java.util.concurrent ExecutorService Executors Callable]))

(set! *warn-on-reflection* true)

;; 全局子 agent 注册表: {id -> entry}
(defonce ^:private registry (atom {}))

(defonce ^{:private true
           :doc "子 agent 工作线程池：虚拟线程 per task。
   子 agent 内部是阻塞式 LLM 调用（同步 chat / 流式 @future），用 clojure future
   （无界平台线程池）会在大量并发子 agent 时堆真线程——与 HTTP 层的虚拟线程策略
   （http/client、stream_client 的 executor）保持一致。"}
  worker-executor
  (Executors/newVirtualThreadPerTaskExecutor))

(defn- gen-id []
  (str "sub-" (java.util.UUID/randomUUID)))

(defn- now-ms [] (System/currentTimeMillis))

;;; ============================================================
;;; 工作线程
;;; ============================================================

(defn- safe-call
  "调观察钩子：**吞异常**。观察者不许改变被观察者的行为——一个日志回调抛异常
   把子 agent 跑挂了，是最难查的那类故障。返回 nil 表示没钩子或钩子炸了。"
  [f & args]
  (when f
    (try (apply f args)
         (catch Throwable t
           (log/warn "子 agent 观察钩子抛异常（已兜住，子 agent 不受影响）:" (.getMessage t))
           nil))))

(defn- observer-of
  "在 **worker 线程上**造一次观察者。工厂本身抛异常也兜住——没有观察者就当没挂。"
  [spec id attempt]
  (when-let [f (:observer spec)]
    (safe-call f {:id      id
                  :attempt attempt
                  :name    (or (:subagent-name spec) "subagent")
                  :task    (or (:task spec) (:prompt spec))
                  :owner   (:owner spec)
                  :parent-tool-call-id (:parent-tool-call-id spec)})))

(defn- do-run
  "在独立线程中创建并运行子 agent，返回 {:ok result-str} 或 {:error reason}。
   使用延迟 require 避免与 client 的循环依赖（manager 在 client 之后加载）。

   `observer` 可为 nil；非 nil 时它的 `:decorate` / `:chat-opts` 在这里生效。"
  [spec observer]
  (let [{:keys [subagent-config prompt result-fn]} spec
        rfn (or result-fn :text)]
    (try
      ;; 延迟 require：子 agent 使用 client/create-agent + client/chat
      (require 'im.ttalk.agent.simple-agent)
      (let [create-agent (resolve 'im.ttalk.agent.simple-agent/create-agent)
            chat-fn      (resolve 'im.ttalk.agent.simple-agent/chat)
            ;; 子 agent 默认隔离：无记忆 + 独立 conversation-id
            config (merge {:memory false
                           :conversation-id (str "sub-" (java.util.UUID/randomUUID))}
                          subagent-config)
            agent  (create-agent config)
            ;; :decorate 抛异常 → safe-call 返回 nil → 退回未装饰的 agent。
            ;; 「观察挂不上」不该等于「子 agent 跑不了」。
            agent  (or (safe-call (:decorate observer) agent) agent)
            resp   (chat-fn agent prompt (:chat-opts observer))]
        (if (= :completed (:status resp))
          {:ok (rfn resp)}
          {:error {:status (:status resp) :detail resp}}))
      (catch Throwable t
        {:error {:crashed true :message (.getMessage t)}}))))

(defn- finish!
  "worker 终结登记：仅当 entry 仍属于本次运行（同一 promise）且仍 :running 时生效。
   守卫两类竞态：kill! 已标 :killed 后被中断的 worker 不得覆盖成 :failed；
   restart! 换代后旧 worker 不得践踏新一代的状态。"
  [id result-promise status result]
  (swap! registry update id
         (fn [entry]
           (if (and (identical? result-promise (:promise entry))
                    (= :running (:status entry)))
             (merge entry {:status status :result result :finished-at (now-ms)})
             entry))))

(defn- spawn-worker!
  "在虚拟线程上运行子 agent，返回 j.u.c.Future（kill! 用 future-cancel 中断）。

   **不用 `bound-fn*` 是故意的，别顺手「修好」**（有测试钉住）：子 agent 是
   新 ChatClient + 新 TCM = **新执行边界**，调用方的动态绑定等 ambient 状态不该隐式
   穿过去（设计原则 §3「一个 ChatClient 绑定一个 TCM，不跨边界」的「边界外不流通」）。
   要把状态传给子 agent，走 `subagent-config` / prompt **显式**传。

   对照：同一 chat-client 内的 `react/run-on-executor` **必须**用 `bound-fn*`——那是
   边界**内**，同一条原则的另一侧（「边界内一致」）。"
  [id spec result-promise attempt]
  (.submit ^ExecutorService worker-executor
           ^Callable
           (fn []
             (let [observer (observer-of spec id attempt)]
               (try
                 (safe-call (:start! observer))
                 (let [outcome (do-run spec observer)]
                   (deliver result-promise outcome)
                   (finish! id result-promise (if (:ok outcome) :done :failed) outcome))
                 (catch Throwable t
                   (let [err {:error {:crashed true :message (.getMessage t)}}]
                     (deliver result-promise err)
                     (finish! id result-promise :failed err)))
                 (finally
                   ;; **在 finally 里**：kill 会从调用方线程 deliver {:error :killed} 并
                   ;; 中断本线程，中断的 unwind 照样跑 finally，于是三条异常路径
                   ;; （kill / 超时后 kill / 崩溃）都有且只有一次 settle。
                   ;; promise 此刻必已 deliver（try 的三条出口各自 deliver 过一次），
                   ;; 0ms 兜底只是不信任「必然」。
                   (safe-call (:settle! observer)
                              (deref result-promise 0 {:error :unknown}))))))))

;;; ============================================================
;;; API
;;; ============================================================

(defn spawn!
  "异步启动一个子 agent，立即返回 {:ok id}。

   spec 键：:subagent-config :prompt :result-fn :owner"
  [spec]
  (let [id (gen-id)
        p  (promise)]
    ;; 先登记条目、后启动 worker：worker 若秒完成，finish! 需要条目已存在，
    ;; 否则终结登记会被随后的注册覆盖，状态永远卡在 :running（真实竞态，测试曾复现）。
    (swap! registry assoc id
           {:id          id
            :promise     p
            :future      nil
            :status      :running
            :result      nil
            :spec        spec
            :owner       (:owner spec)
            :attempt     0
            :started-at  (now-ms)
            :finished-at nil})
    (swap! registry update id assoc :future (spawn-worker! id spec p 0))
    {:ok id}))

(defn await!
  "同步等待子 agent 完成（便利方法）。timeout-ms 到则返回 {:error :timeout}（不 kill）。

   返回：{:ok result-str} | {:error reason}"
  [id timeout-ms]
  (if-let [entry (get @registry id)]
    (if (= :running (:status entry))
      (let [outcome (deref (:promise entry) timeout-ms {:error :timeout})]
        outcome)
      (:result entry))
    {:error :not-found}))

(defn result
  "非阻塞查询结果。running 时返回 {:error :not-ready}。"
  [id]
  (if-let [entry (get @registry id)]
    (if (= :running (:status entry))
      {:error :not-ready}
      {:ok (:result entry)})
    {:error :not-found}))

(defn list-agents
  "列出所有（或指定 owner 的）子 agent 信息。"
  ([] (list-agents nil))
  ([owner]
   (let [entries (vals @registry)]
     (cond->> entries
       owner (filter #(= owner (:owner %)))
       true  (mapv #(select-keys % [:id :status :owner :started-at :finished-at]))))))

(defn kill!
  "Kill 指定子 agent（cancel future + deliver error）。

   :result 同步写入 {:error :killed}——kill 后 await!/result 返回明确错误而非 nil；
   被中断的 worker 随后的 finish! 因状态已终结而 no-op，不会把 :killed 覆盖成 :failed。"
  [id]
  (when-let [entry (get @registry id)]
    (when (= :running (:status entry))
      ;; :future 可能尚未落账（spawn! 两步注册的间隙）——nil 时只标记状态即可
      (when-let [f (:future entry)] (future-cancel f))
      ;; 安全 deliver：已 deliver 时 no-op
      (deliver (:promise entry) {:error :killed})
      (swap! registry update id merge
             {:status :killed :result {:error :killed} :finished-at (now-ms)})))
  nil)

(defn restart!
  "用原 spec 重启子 agent（同 id，状态回 :running）。"
  [id]
  (if-let [entry (get @registry id)]
    (let [spec (:spec entry)
          ;; **换代号**：restart 是同一条委派的另一次尝试，观察者据此把两次区分开
          ;; （同一个 lane id 开两次，消费方只能把两次尝试叠在一起看）。
          attempt (inc (or (:attempt entry) 0))]
      (kill! id)
      (let [p (promise)]
        ;; 同 spawn!：先换代（promise/status），后启动 worker
        (swap! registry update id merge
               {:promise     p
                :future      nil
                :status      :running
                :result      nil
                :attempt     attempt
                :started-at  (now-ms)
                :finished-at nil})
        (swap! registry update id assoc :future (spawn-worker! id spec p attempt)))
      {:ok id})
    {:error :not-found}))

(defn drop!
  "从注册表移除（running 时先 kill）。"
  [id]
  (kill! id)
  (swap! registry dissoc id)
  nil)

(defn clear-all!
  "清空整个注册表（kill 所有 running 的子 agent）。测试/重置用。"
  []
  (doseq [[id entry] @registry :when (= :running (:status entry))]
    (kill! id))
  (reset! registry {})
  nil)
