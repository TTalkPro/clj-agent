(ns im.ttalk.agent.agui.runtime
  "Agent Runtime —— 会话注册表 + run 生命周期 + 事件订阅。

   设计见 docs/agent-runtime-design.md。一句话：**run 的寿命不再由调用方的寿命
   决定**。`agent/chat-stream` 把 token 直推调用方的 sink，sink 死了这轮的输出就
   没了；这里 run 立刻起跑，事件进会话的环形缓冲再扇出给订阅者——断线重连凭
   `:since` 补齐缺口，另一个请求线程凭 conversation-id 就能 stop / resume。

   **零 web 依赖**（design-principles §2）：订阅是回调（`on-event` + 返回退订
   函数），与 `on-token` 同族。SSE 帧 / 路由 / 鉴权在 examples/copilotkit/。

   本 ns 立的不变量（§3.3）：

   > **一个 conversation-id 恰好对应一个 agent 状态机，且同时至多一个 in-flight run。**

   这条今天靠 `simple-agent` docstring 里的警告维持（「同一个 agent 上不要并发多个
   chat-async」），进了注册表就靠结构维持。

   ```clojure
   (def rt (runtime {:agent-fn (fn [{:keys [conversation-id tools]}]
                                 (agent/create-agent {:provider p :memory store
                                                      :conversation-id conversation-id
                                                      :tools (into [#'my-tool] tools)}))}))
   (subscribe rt \"c1\" {:on-event prn})          ;; => 退订函数
   (start-run! rt \"c1\" \"北京天气?\")             ;; => {:status :started :run-id ...}
   (stop! rt \"c1\")
   ```"
  (:require [im.ttalk.agent.agui.emit :as emit]
            [im.ttalk.agent.agui.event :as event]
            [im.ttalk.agent.agui.subagent :as subagent]
            [im.ttalk.agent.agui.tools :as agui-tools]
            [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.simple-agent :as agent]
            [im.ttalk.agent.streaming :as streaming]
            [taoensso.timbre :as log])
  (:import [java.util UUID]
           [java.util.concurrent CompletableFuture]))

(set! *warn-on-reflection* true)

(def ^:private defaults
  {:on-concurrent     :reject     ;; | :supersede
   :subagent-events?  false
   :state-tools?      false
   :buffer-size       512
   :idle-ttl-ms       (* 30 60 1000)
   :supersede-wait-ms 5000
   :shutdown-wait-ms  5000})

;;; ============================================================
;;; 会话条目
;;; ============================================================

(defn- new-entry [conversation-id buffer-size now]
  {:conversation-id conversation-id
   :lock            (Object.)
   :seq             (atom -1)
   :buffer          (atom {:cap buffer-size :events []})
   :subs            (atom {})
   :sub-ids         (atom 0)
   :run             (atom nil)     ;; {:run-id :emitter :cancel-token :done :started-at}
   :awaiting        (atom nil)     ;; 暂停中 {:run-id :reason :pending-tool}
   :state-atom      (atom {:status :idle :paused-state nil :turn-count 0 :run-id nil})
   :last-active     (atom now)})

(defn- buf-add! [buf ev]
  (swap! buf (fn [{:keys [cap events] :as b}]
               (let [e (conj events ev)]
                 (assoc b :events (if (> (count e) cap)
                                    (subvec e (- (count e) cap))
                                    e))))))

(defn- buf-since
  "`since` 之后的事件。返回 `{:events [...] :gap? bool}`——`gap?` 为真表示缓冲
   起点已经越过 `since`，中间那段**永远补不回来了**，订阅侧该走 resync（§4.5）。"
  [buf since]
  (let [{:keys [events]} @buf]
    (if (nil? since)
      {:events [] :gap? false}
      {:events (filterv #(> (:seq %) since) events)
       :gap?   (boolean (and (seq events) (> (:seq (first events)) (inc since))))})))

;;; ============================================================
;;; Runtime
;;; ============================================================

(defn runtime
  "构造运行时。**显式值，无全局单例**——CopilotKit 的 in-memory store 是进程全局
   的，为此得写一段 warning 解释「你把所有会话的 bound 改了」（§8.4）；这里想建
   几个建几个。

   - `:agent-fn`          **必填** `(fn [{:keys [conversation-id tools]}] agent)`。
                          `:tools` 是本 run 追加的内联工具（AG-UI 前端 action），
                          没有则为 `[]`。同一 conversation-id 的 agent 会被
                          runtime 换上会话级 state-atom，故每次现建也不丢暂停态。
   - `:on-concurrent`     `:reject`（缺省）| `:supersede`（§4.4）
   - `:buffer-size`       会话事件环形缓冲条数（缺省 512）
   - `:idle-ttl-ms`       空闲会话驱逐（缺省 30min；有 run / 有订阅者 / 暂停中的不驱逐）
   - `:supersede-wait-ms` supersede 时等旧 run 收尾的上限（缺省 5s）
   - `:now`               `(fn [] ms)`，可注入（测试）
   - `:state-tools?`      给模型两把写共享状态的工具（缺省 **false**）。开了之后
                          `agui.tools/state-tools` 的 `AGUISendStateSnapshot` /
                          `AGUISendStateDelta` 进每个 run 的工具表，模型于是写得动
                          共享状态。**缺省关**：它改变模型看见的工具列表，装配方
                          该显式点头；`/info` 的 `capabilities.state` 跟着报
   - `:subagent-events?`  子 agent lane 的事件（缺省 **false**）。开了之后
                          `:agent-fn` 会多收到一个 `:subagent-observer`，把它交给
                          `delegate-tool` 的 `:observer`，委派期间的 token / 工具调用
                          就带着 `subagentRunId` 进事件流
                          （docs/subagent-event-attribution-design.md）。
                          **缺省关不是保守**：`@ag-ui/client` ≤ 0.0.57 在 HTTP
                          transport 里校验事件的 discriminated union，一条
                          `SUBAGENT_STARTED` 掐断整条流，客户端侧救不回来。
   - `:event-transform`    `(fn [{:keys [run-id conversation-id]}] (fn [event] events))`，
                          可选。**事件流插件的挂点**（见 `agui.event/emitter` 的
                          `:transform`）：每个 run 现造一个有状态的 transform。
                          `agui.a2ui/event-transform`（本模块）与
                          `copilotkit.genui/event-transform`（examples）各是现成的一个"
  [{:keys [agent-fn] :as opts}]
  (when-not (fn? agent-fn)
    (throw (ex-info "runtime 需要 :agent-fn (fn [{:keys [conversation-id tools]}] agent)" {})))
  (let [cfg (merge defaults opts)]
    {:agent-fn agent-fn
     :cfg cfg
     :event-transform (:event-transform opts)
     :now (or (:now opts) #(System/currentTimeMillis))
     :conversations (atom {})
     :closed? (atom false)}))

(defn- touch! [entry now] (reset! (:last-active entry) now))

(defn- evictable? [entry now ttl]
  (and (nil? @(:run entry))
       (nil? @(:awaiting entry))
       (empty? @(:subs entry))
       (> (- now @(:last-active entry)) ttl)))

(defn- sweep!
  "机会式驱逐：没有背景线程（§8.4「不建防御性机器」的同款取舍——一个只在有人
   来的时候扫一遍的表，不值得为它长一根线程）。"
  [rt]
  (let [now ((:now rt))
        ttl (get-in rt [:cfg :idle-ttl-ms])]
    (swap! (:conversations rt)
           (fn [m] (into {} (remove (fn [[_ e]] (evictable? e now ttl))) m)))))

(defn- entry-of! [rt conv-id]
  (sweep! rt)
  (let [now ((:now rt))
        m (swap! (:conversations rt)
                 (fn [m] (if (contains? m conv-id)
                           m
                           (assoc m conv-id (new-entry conv-id (get-in rt [:cfg :buffer-size]) now)))))
        entry (get m conv-id)]
    (touch! entry now)
    entry))

(defn- entry-peek [rt conv-id] (get @(:conversations rt) conv-id))

;;; ============================================================
;;; 事件出口：缓冲 + 扇出
;;; ============================================================

(defn- make-sink
  "事件出口。**在会话锁内**入缓冲并扇出，`subscribe` 也在同一把锁里注册——
   两者不能交错，否则新订阅者会漏掉「重放完到注册完」之间的那几条。

   订阅者抛异常 → 摘掉它，run 不受影响（§6.3）。回调的契约是**立刻交出去**
   （往 SSE channel put 一下就返回），与 `on-token` 逐字同源。"
  [entry]
  (fn [ev]
    (locking (:lock entry)
      (buf-add! (:buffer entry) ev)
      (doseq [[id {:keys [on-event]}] @(:subs entry)]
        (try
          (on-event ev)
          (catch Throwable t
            (swap! (:subs entry) dissoc id)
            (log/warn "agui 订阅者抛异常，已摘除:" (.getMessage t))))))))

(defn- next-seq-fn [entry]
  (fn [] (locking (:lock entry) (swap! (:seq entry) inc))))

;;; ============================================================
;;; run 生命周期
;;; ============================================================

(defn- terminal-type
  "本 run 的终态类型。**停止意图取自本 run 自己的 holder**，不是会话上的共享
   标志——否则新 run 一重置，被 supersede 掉的旧 run 收尾时会把自己标成
   `:run/error`（CopilotKit `RunFinalizeControl` 修的就是这个，§2.1 第 4 条）。"
  [em result throwable]
  (cond
    (= :paused (:status result))    :run/paused
    (event/stop-requested? em)      :run/cancelled
    (some? throwable)               :run/error
    (= :error (:status result))     :run/error
    (= :cancelled (:status result)) :run/cancelled
    :else                           :run/finished))

(defn- terminal-payload [t result throwable]
  (case t
    :run/paused   {:reason (:pause-reason result)
                   :pending-tool (:pending-tool result)
                   :tool-calls-made (:tool-calls-made result)}
    :run/error    {:error (or (:error result)
                              (when throwable
                                {:class :provider-error
                                 :message (or (ex-message throwable) (str throwable))}))}
    :run/cancelled {:text (:text result) :tool-calls-made (:tool-calls-made result)}
    {:text (:text result) :tool-calls-made (:tool-calls-made result)}))

(defn- finish-run!
  [entry run-id em result throwable ^CompletableFuture done]
  (locking (:lock entry)
    (let [t (terminal-type em result throwable)]
      (when (= :run/finished t)
        (event/ensure-text! em (str run-id "-final") (:text result)))
      (event/finish! em t (terminal-payload t result throwable))
      (reset! (:awaiting entry)
              (when (= :run/paused t)
                {:run-id run-id
                 :reason (:pause-reason result)
                 :pending-tool (:pending-tool result)}))
      (when (= run-id (:run-id @(:run entry)))
        (reset! (:run entry) nil))))
  (.complete done true)
  nil)

(defn- launch!
  "在会话锁内起一个 run。`kind` = `:chat` | `:resume`。

   `chat-async` / `resume-async` **立刻返回 deferred**（整轮跑在虚拟线程上），
   所以锁只握住「装配 + 派发」这一小段，不握住 LLM 往返。"
  [rt entry kind {:keys [message decision payload tools opts parent-run-id state]}]
  (let [conv-id (:conversation-id entry)
        ;; **起跑前的水位**：run 一旦起跑就立刻发 `:run/started`，而 HTTP 层要等
        ;; 拿到返回值才订阅——中间这一段是真空。把水位交出去，调用方用它作
        ;; `:since` 订阅，一条不漏也一条不重。联调时正是栽在这：SSE 流从
        ;; `id: 1` 开始，前端第一件事就是 `First event must be 'RUN_STARTED'`。
        since @(:seq entry)
        run-id (str (UUID/randomUUID))
        token (streaming/make-cancel-token)
        em (event/emitter {:run-id run-id
                           :conversation-id conv-id
                           :next-seq (next-seq-fn entry)
                           :sink (make-sink entry)
                           ;; 会话锁交给发射器：取号 + 记账 + 投递在它里面一次做完
                           ;; （见 `event/deliver!`）。`subscribe` 拿的也是这把，
                           ;; 于是「重放完到注册完」那段照旧不会与扇出交错
                           :gate (:lock entry)
                           :now (:now rt)
                           ;; 插件的 transform 是**有状态的**（要记住这一轮见过哪些
                           ;; tool-call），所以按 run 现造一个，不是全局共享一个
                           :transform (when-let [f (:event-transform rt)]
                                        (f {:run-id run-id :conversation-id conv-id}))})
        _ (event/seed-state! em state)   ;; 客户端发上来的那份，作为 delta 的基线
        done (CompletableFuture.)
        a (-> ((:agent-fn rt) {:conversation-id conv-id
                               ;; 写共享状态的两把工具**闭包在本 run 的发射器上**
                               ;; ——它们要往这一条流里发 STATE_*，而 var 是进程级的，
                               ;; 装不进 run。开关关着就一把都不加
                               :tools (cond-> (vec tools)
                                        (get-in rt [:cfg :state-tools?])
                                        (into (agui-tools/state-tools em)))
                               ;; 开关关着就是 nil——`delegate-tool` 于是走老路径。
                               ;; 「不塞就什么都不会发生」是这条链路每一跳的性质
                               :subagent-observer (when (get-in rt [:cfg :subagent-events?])
                                                    (subagent/observer-factory em))})
              (assoc :conversation-id conv-id :state-atom (:state-atom entry))
              (emit/attach em))
        invoke-opts (merge opts {:on-token (emit/token-fn em)
                                 :cancel-token token})]
    (reset! (:run entry) {:run-id run-id :emitter em :cancel-token token
                          :done done :kind kind :started-at ((:now rt))})
    (event/emit! em :run/started (cond-> {:kind kind}
                                   message  (assoc :input {:message message})
                                   decision (assoc :input {:decision decision :payload payload})
                                   ;; 调用方声明的父 run（AG-UI 的
                                   ;; `RunAgentInput.parentRunId`）。**原样带着走**：
                                   ;; 我们不解释它，只让它出现在 `RUN_STARTED` 上，
                                   ;; 客户端据此把这条 run 挂回它的父链
                                   parent-run-id (assoc :parent-run-id parent-run-id)))
    (try
      (-> (case kind
            :chat   (agent/chat-async a message invoke-opts)
            :resume (agent/resume-async a decision payload invoke-opts))
          (flt/fmap   (fn [result] (finish-run! entry run-id em result nil done) result))
          (flt/fcatch (fn [t] (finish-run! entry run-id em nil t done) nil)))
      (catch Throwable t
        ;; 同步抛出的（如 resume 的暂停态校验）——照样落进事件流，不甩给调用方
        (finish-run! entry run-id em nil t done)))
    {:status :started :run-id run-id :conversation-id conv-id :since since}))

(defn- request-stop!*
  "标记 + 取消。返回是否**本次**才登记上（幂等：重复 stop 返回 false）。"
  [r]
  (when (event/request-stop! (:emitter r))
    (streaming/request-cancel! (:cancel-token r))
    true))

(defn- start-or-supersede!
  "并发策略（§4.4）。`:supersede` 要**先放锁再等**旧 run 收尾——收尾本身要拿同
   一把锁（`finish-run!`），握着等必死锁。"
  [rt entry kind {:keys [discard-pause?] :as args}]
  (let [policy (get-in rt [:cfg :on-concurrent])
        wait-ms (get-in rt [:cfg :supersede-wait-ms])]
    (loop [attempt 0]
      (let [step (locking (:lock entry)
                   (cond
                     (and (= :chat kind) (some? @(:awaiting entry)) (not discard-pause?))
                     [:awaiting @(:awaiting entry)]

                     ;; 显式丢弃未答复的暂停（见 start-run! 的 :discard-pause?）。
                     ;; 只清 runtime 这一侧的记号——agent 那侧由 `start-turn!` 的
                     ;; `cancel-pending!` 负责（重置 state-atom + 清 PauseStore），
                     ;; 我们不重复实现一遍。
                     (and (= :chat kind) (some? @(:awaiting entry)) discard-pause?)
                     (do (reset! (:awaiting entry) nil)
                         [:done (launch! rt entry kind args)])

                     (nil? @(:run entry))
                     [:done (launch! rt entry kind args)]

                     (not= :supersede policy)
                     [:busy (:run-id @(:run entry))]

                     :else
                     (let [r @(:run entry)]
                       (request-stop!* r)
                       [:wait (:done r)])))]
        (case (first step)
          :done     (second step)
          :busy     {:status :busy :run-id (second step)}
          :awaiting (let [{:keys [run-id reason pending-tool]} (second step)]
                      {:status :awaiting-resume :run-id run-id
                       :reason reason :pending-tool pending-tool})
          :wait     (if (>= attempt 3)
                      {:status :busy :run-id (:run-id @(:run entry))}
                      (do (deref (second step) wait-ms ::timeout)
                          (recur (inc attempt)))))))))

;;; ============================================================
;;; 公开 API
;;; ============================================================

(defn start-run!
  "起一个新 run。**立刻返回**，run 在后台跑。

   返回 `{:status :started :run-id ... :since <起跑前的 seq 水位>}`，或：
   - `{:status :busy :run-id <在跑的那个>}`（`:reject` 策略下已有 in-flight run）
   - `{:status :awaiting-resume :pending-tool ... :reason ...}`——会话暂停中，
     先 `resume-run!`。**这里刻意不沿用 `simple-agent` 的 `cancel-pending!`
     语义**（新 turn 静默丢弃未 resume 的暂停）：web 场景下「用户没点审批就又发
     了一句」应当显式暴露，静默丢一次审批是隐蔽的语义损失（§4.4）。

   **`:since` 是给 HTTP 层用的**：run 立刻起跑并发出 `:run/started`，而你要等本
   函数返回才能 `subscribe`。拿返回的 `:since` 去订阅，那一段真空就补齐了——
   这正是会话级单调 `:seq` 的用处（§4.2 契约 1）。

   `:discard-pause?` —— **显式丢弃**会话上那个没人答复的暂停，直接起新 run。
   给 AG-UI 前端工具用：客户端把工具塞在 `RunAgentInput.tools` 里，模型调了它，
   会话就停在那儿等结果——**而客户端可能永远不回**（CopilotKit 的 suggestions
   就是这样：它只读 `TOOL_CALL_ARGS`，从不回结果）。那种情况下用户下一句话到达
   时，正确的做法是丢掉它继续，而不是把会话永远卡住。**缺省仍是拒绝**（§4.4）：
   人工审批被静默丢掉是隐蔽的语义损失，要丢就显式说。

   `:state` —— `RunAgentInput.state`，作为本 run 共享状态的**基线**装上（不发事件，
   客户端本来就有这份）。后续 delta 对着它算，服务端与客户端才不会各算各的。

   `:parent-run-id` —— 调用方声明「这一轮挂在哪条 run 下面」。我们**不解释**它
   （不建父子索引、不做级联停止），只把它挂到 `:run/started` 上原样发出去：
   AG-UI 的 `RUN_STARTED.parentRunId` 就是这么个用途，客户端拿它把这条 run 接回
   父链。协议里它是可选的，没有就没有。

   `opts` 直通 `agent/chat-async`（`:system-prompt` / `:max-iterations` / `:tool-choice` …）；
   `:tools` 是本 run 追加的内联工具，会交给 `:agent-fn`。"
  ([rt conv-id message] (start-run! rt conv-id message nil))
  ([rt conv-id message {:keys [tools discard-pause? parent-run-id state] :as opts}]
   (when @(:closed? rt) (throw (ex-info "runtime 已关闭" {:conversation-id conv-id})))
   (let [entry (entry-of! rt conv-id)]
     (start-or-supersede! rt entry :chat
                          {:message message :tools tools
                           :discard-pause? discard-pause?
                           :parent-run-id parent-run-id
                           :state state
                           ;; 这四个是 runtime 自己的键，别漏进 chat-async 的 opts
                           :opts (dissoc opts :tools :discard-pause? :parent-run-id :state)}))))

(defn run-detached!
  "在一个**临时发射器**上跑一次 agent 调用——**不进注册表**：没有会话条目、
   没有环形缓冲、没有订阅表、没有 stop / resume / 暂停。

   为什么要有这条：CopilotKit 的 `/suggest`（「猜用户下一句想说什么」）是一次
   **不留痕**的 run。它刻意不走 runner——runner 会按 threadId 写线程表，于是
   一次一次性的建议把一堆废线程留在那儿。这里同样：事件直接给 `on-event`，
   跑完就没了。

   `agent` 由调用方给（**它决定这次用哪份配置、落不落 memory**——策略不该埋在
   库里）。返回 `CompletableFuture`，完成值是 agent 的结果。

   - `:message`         这一轮的用户输入
   - `:on-event`        (fn [中立事件])
   - `:opts`            透传给 `chat-async`（`:tool-choice` / `:max-iterations` …）
   - `:run-id`          可选，缺省现生成"
  [{:keys [agent message on-event opts run-id now]}]
  (let [run-id (or run-id (str (UUID/randomUUID)))
        n (atom -1)
        token (streaming/make-cancel-token)
        em (event/emitter {:run-id run-id
                           :conversation-id (:conversation-id agent)
                           :next-seq #(swap! n inc)
                           :sink (or on-event (fn [_]))
                           :now now})
        a (emit/attach agent em)
        done (CompletableFuture.)
        finish! (fn [result throwable]
                  (let [t (terminal-type em result throwable)]
                    (when (= :run/finished t)
                      (event/ensure-text! em (str run-id "-final") (:text result)))
                    (event/finish! em t (terminal-payload t result throwable)))
                  (.complete done (or result {})))]
    (event/emit! em :run/started {:kind :suggest :input {:message message}})
    (try
      (-> (agent/chat-async a message (merge opts {:on-token (emit/token-fn em)
                                                  :cancel-token token}))
          (flt/fmap   (fn [result] (finish! result nil) result))
          (flt/fcatch (fn [t] (finish! nil t) nil)))
      (catch Throwable t (finish! nil t)))
    done))

(defn awaiting
  "会话是否在等 resume（暂停中）。返回 `{:run-id :reason :pending-tool}` 或 nil。"
  [rt conv-id]
  (some-> (entry-peek rt conv-id) :awaiting deref))

(defn resume-run!
  "凭 conversation-id 恢复暂停的会话——**这就是跨请求 HITL**：审批按钮是另一个
   HTTP 请求，它手上只有 conv-id，没有 agent 对象。

   `decision` / `payload` 语义与 `agent/resume` 逐字相同（approved / rejected /
   reply / retry / proceed，见 docs/hitl-timeline-design.md §2）。

   前端工具（AG-UI client-side tool）走的也是这条路：`:reply` + `{:message 结果}`
   ——「pending 工具不执行、载荷即其结果」正是 ask-user 语义（§7.2）。"
  ([rt conv-id decision] (resume-run! rt conv-id decision nil))
  ([rt conv-id decision payload] (resume-run! rt conv-id decision payload nil))
  ([rt conv-id decision payload {:keys [tools] :as opts}]
   (when @(:closed? rt) (throw (ex-info "runtime 已关闭" {:conversation-id conv-id})))
   (let [entry (entry-of! rt conv-id)
         paused? (or (some? @(:awaiting entry))
                     ;; 跨重启：本进程没有暂停记号，但 PauseStore 里可能有
                     (try (agent/paused? ((:agent-fn rt) {:conversation-id conv-id :tools []}))
                          (catch Throwable _ false)))]
     (if-not paused?
       {:status :not-paused}
       (do (reset! (:awaiting entry) nil)
           (start-or-supersede! rt entry :resume
                                {:decision decision :payload payload :tools tools
                                 :parent-run-id (:parent-run-id opts)
                                 :opts (dissoc opts :tools :parent-run-id)}))))))

(defn stop!
  "请求停止。**返回 true 只表示「取消已登记」，不表示「已经停了」**——JVM 上没有
   抢占原语，取消 = 放弃等待 + 协作式中断：provider 流式循环下次检查时退出、
   react 循环在轮边界不再续跑，**已经在跑的工具会跑完**（§4.8，口径同
   docs/tool-timeout-design.md）。停稳的信号是 `:run/cancelled` 事件。

   传 `run-id` 则只停这一个 run，绝不误伤会话上更新的那个。"
  ([rt conv-id] (stop! rt conv-id nil))
  ([rt conv-id run-id]
   (if-let [entry (entry-peek rt conv-id)]
     (locking (:lock entry)
       (if-let [r @(:run entry)]
         (if (or (nil? run-id) (= run-id (:run-id r)))
           (boolean (request-stop!* r))
           false)
         false))
     false)))

(defn run-status
  [rt conv-id]
  (when-let [entry (entry-peek rt conv-id)]
    (let [r @(:run entry)
          aw @(:awaiting entry)]
      {:conversation-id conv-id
       :state (cond r :running aw :awaiting-resume :else :idle)
       :run-id (or (:run-id r) (:run-id aw))
       ;; 「取消已登记、但还没停稳」——UI 上该显示「正在停止…」而不是「已停止」
       ;; （§4.8：JVM 上没有抢占，正在跑的工具会跑完）
       :stopping? (boolean (some-> r :emitter event/stop-requested?))
       :pending-tool (:pending-tool aw)
       :seq @(:seq entry)
       :last-active @(:last-active entry)
       :subscribers (count @(:subs entry))})))

(defn buffered-events
  "会话环形缓冲里**现存**的事件（只读）。

   注意这是**有界**的（`:buffer-size`，缺省 512 条）且**不落库**——它是为断线
   续传服务的，不是事件日志。想要完整历史找 ChatMemory（§8.2「不做 durable
   execution」）。`/threads/:id/events` 这类只读端点照实返回它现有的部分。"
  [rt conv-id]
  (some-> (entry-peek rt conv-id) :buffer deref :events vec))

(defn forget!
  "把一个会话从注册表里摘掉（**不动 ChatMemory**——历史归历史）。

   在跑的 run 先停掉：不停的话它收尾时还会往一个没人再读的缓冲里写。"
  [rt conv-id]
  (stop! rt conv-id)
  (swap! (:conversations rt) dissoc conv-id)
  nil)

(defn conversations [rt] (vec (keys @(:conversations rt))))

(defn subscribe
  "订阅一个会话的事件流。返回**退订函数**。

   - `:since`   上次收到的 `:seq`（会话级单调，跨 run 连续）。缺省 nil = 只收新事件。
   - `:on-event` `(fn [event])`——**必须立刻返回**（往你的 sink 里 put 一下）。
     抛异常即被摘除。
   - `:on-close` `(fn [reason])`，可选（退订 / `shutdown!` 时触发）。

   `:since` 早于缓冲起点时先发一条 `:run/resync`（带 ChatMemory 里的完整消息
   快照）再接 live tail——**折叠后的真相 ChatMemory 里本来就有**，不在事件侧再造
   一份（§8.1：不建第二个真相店，也因此不需要 CopilotKit 的 `compactEvents`）。"
  [rt conv-id {:keys [since on-event on-close]}]
  (let [entry (entry-of! rt conv-id)]
    (locking (:lock entry)
      (let [id (swap! (:sub-ids entry) inc)
            {:keys [events gap?]} (buf-since (:buffer entry) since)]
        (when gap?
          (let [msgs (try (agent/get-history ((:agent-fn rt) {:conversation-id conv-id :tools []}))
                          (catch Throwable t
                            (log/warn "resync 取历史失败:" (.getMessage t))
                            []))]
            (on-event {:type :run/resync
                       :conversation-id conv-id
                       :seq @(:seq entry)
                       :ts ((:now rt))
                       :messages (vec msgs)})))
        (doseq [ev events] (on-event ev))
        (swap! (:subs entry) assoc id {:on-event on-event :on-close on-close})
        (fn unsubscribe! []
          (locking (:lock entry)
            (when-let [sub (get @(:subs entry) id)]
              (swap! (:subs entry) dissoc id)
              (when-let [c (:on-close sub)] (try (c :unsubscribed) (catch Throwable _ nil))))
            nil))))))

(defn shutdown!
  "停掉所有 in-flight run、等它们收尾（有上限）、关掉所有订阅者、清表。

   等不到的不硬等：`:shutdown-wait-ms` 到点就走人——事件流的终态由 `finish-run!`
   在各自线程上照常补，只是我们不再等它。"
  [rt]
  (reset! (:closed? rt) true)
  (let [entries (vals @(:conversations rt))
        dones (doall (keep (fn [entry]
                             (locking (:lock entry)
                               (when-let [r @(:run entry)]
                                 (request-stop!* r)
                                 (:done r))))
                           entries))]
    (doseq [^CompletableFuture d dones]
      (deref d (get-in rt [:cfg :shutdown-wait-ms]) ::timeout))
    (doseq [entry entries]
      (locking (:lock entry)
        (doseq [[_ {:keys [on-close]}] @(:subs entry)]
          (when on-close (try (on-close :shutdown) (catch Throwable _ nil))))
        (reset! (:subs entry) {})))
    (reset! (:conversations rt) {})
    nil))
