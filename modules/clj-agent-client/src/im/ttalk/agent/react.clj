(ns im.ttalk.agent.react
  "工具调用循环（从 chat-client 下沉而来）

   chat-client 只提供原语 invoke-chat / invoke-tool；'驱动 LLM↔工具直到文本或暂停'
   这套策略(含 max-iterations / gate 暂停 / resume / 悬空 tool_use 自愈)属编排层，
   放在 simpleagent。每轮只向 invoke-chat 传 delta，由 chat-client 的 memory filter
   按 conversation-id 拼出完整历史。

   store 显式传入(chat-client 不再持有 memory)：用于 heal 与临时会话清理；
   与 chat-client 上挂载的 memory-filter 必须是同一个 store 实例。

   callbacks 独立于 chat-client filter：:on-llm-call/:on-llm-result/:on-tool-result
   在循环关键节点直接触发，不走 filter 链。gate 评估结果缓存，确保每个工具调用
   恰好触发一次观察回调（不重复）。"
  (:require [clojure.string]
            [im.ttalk.agent.callbacks :as cb]
            [im.ttalk.agent.chat-client :as chat-client]
            [im.ttalk.agent.tool-registry :as registry]
            [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.async :as async]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.tool :as tool]
            [im.ttalk.agent.model.error :as err]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.filter.memory :as memory-filter]
            [im.ttalk.agent.streaming :as streaming]
            [im.ttalk.agent.tool-calling-manager :as tool-calling-manager]
            [taoensso.timbre :as log])
   (:import [java.io Closeable]
            [java.util.concurrent Callable ExecutorService Executors
             ThreadFactory]))

(set! *warn-on-reflection* true)

(def ^:private default-max-iterations
  "工具调用循环默认最大次数"
  10)

(defn- filter-tools-by-tags
  "根据 tags 过滤 chat-client 的 tool schemas（OR 逻辑；均 nil 时返回全部）。"
  [chat-client {:keys [tags exclude-tags]}]
  (let [reg (registry/registry-of chat-client)
        all-tools (:tools reg)
        tool-vars-map (:tool-vars reg)
        tags-set (when tags (set tags))
        exclude-tags-set (when exclude-tags (set exclude-tags))]
    (if (and (nil? tags-set) (nil? exclude-tags-set))
      all-tools
      (vec (for [tool-schema all-tools
                 :let [fn-name (keyword (:name tool-schema))
                       v (get tool-vars-map fn-name)]
                 :when (and (or (nil? v)  ;; inline tools（无 var）始终保留
                                (and (or (nil? tags-set) (tool/has-any-tag? v tags-set))
                                     (or (nil? exclude-tags-set) (not (tool/has-any-tag? v exclude-tags-set))))))]
             tool-schema)))))

(defn- build-chat-opts
  "从 invoke/resume 的 opts 构建传给 invoke-chat 的 chat 选项
   （system-prompt 合并 + 工具 schema 过滤 + tool-choice）。

   `:chat-options`（map）原样并进**每一轮**的 chat 选项——思考档、温度这类单次选项
   （`DefaultChatModel` 认 config ⊕ 单次选项）。循环自己管的那几格（`:tools` /
   `:tool-choice` / `:system-prompt` / `:on-token` / `:cancel-token` / `:context`）
   以循环为准，`:chat-options` 里同名的不生效（观澜反馈 2026-11-12 ④）。"
  [chat-client opts]
  (let [system-prompts (or (:system-prompts opts) [])
        sp-str (when (seq system-prompts)
                 (->> system-prompts (map :content) (clojure.string/join "\n")))
        tool-schemas (filter-tools-by-tags chat-client opts)
        tool-choice (or (:tool-choice opts) :auto)]
    (cond-> (merge (dissoc (:chat-options opts) :context)
                   {:tools tool-schemas :tool-choice tool-choice})
      sp-str (assoc :system-prompt sp-str)
      (:on-token opts) (assoc :on-token (:on-token opts))
      (:cancel-token opts) (assoc :cancel-token (:cancel-token opts)))))

(def ^:private tool-executor
  "VirtualThreadToolCallingManager 用的共享虚拟线程 executor。

   注意这是**进程全局**单例：同 JVM 所有选了 VT 引擎的 chat-client 的工具批共享它。
   虚拟线程无界，故不会「耗尽」，但也没有舱壁/限流/可关停边界——需要这些的场景
   注入 ThreadPoolToolCallingManager（每实例一个有界池）。**缺省引擎（Sequential）
   不用此 executor**——全程内联在调用方线程上。"
  (delay (Executors/newVirtualThreadPerTaskExecutor)))

(defn- invoke-with-retry
  "调用 chat-client/invoke-tool；仅当错误为 :transient 且工具声明 :retry 时
   按策略指数退避重试（幂等前提；重试会重跑整条 tool filter 链）。

   工具声明的 `:timeout` 由 `chat-client/invoke-tool` **自己**强制（单次调用的时间上限
   是它的职责，不是循环的）——超时归 :transient，故这里的重试对它天然生效。
   幂等前提在超时下更要紧：重试发起时上一次调用可能仍在跑（打不断的那种）。"
  [chat-client fn-key args tool-context]
  (let [policy (registry/retry-policy chat-client fn-key)
        max-r  (long (or (:max-retries policy) 0))]
    (loop [attempt 0]
      (let [resp (chat-client/invoke-tool chat-client fn-key args tool-context)]
        (if (and policy
                 (= :transient (get-in resp [:error :class]))
                 (< attempt max-r))
          (do (Thread/sleep (long (* (:initial-delay-ms policy)
                                     (Math/pow 2 attempt))))
              (recur (inc attempt)))
          resp)))))

(defn emit-event!
  "发一个生命周期事件（designs/runtime-borrow.md §11）。`callbacks` 里没有
   `:on-event` 就什么都不做；出口抛的异常吞掉（观察者不许打断回合）。
   事件带 `:type` `:ts`，以及回调元数据里的 `:conversation-id` / `:run-id`。"
  [callbacks type m]
  (when-let [f (:on-event callbacks)]
    (try
      (f (merge {:type type :ts (System/currentTimeMillis)}
                (select-keys (:metadata callbacks) [:conversation-id :run-id])
                m))
      (catch Throwable _ nil))))

(defn- pause-decision?
  "gate 决策是不是「暂停等审批」：`:pause` 或带信息的 `{:pause {...}}`（R-05）。"
  [d]
  (or (= :pause d) (and (map? d) (contains? d :pause))))

(declare invoke-one*)

(defn- invoke-one
  "执行单个 tool call（含 gate reject 分支）。异常折为错误结果，不逃逸。
   返回 {:tc tc :value str (:writes {k v}) (:error {:class :message})
         (:rejected? true)}——
   错误/拒绝的结果没有 :writes，reduce 时自动跳过（单工具事务性）；
   :error 携带故障类别，供屏障处策略路由（S2）。

   `obs`：`{:on-tool-result f :on-event f :index i :metadata m}`——观察挂点，
   `:tool/started` / `:tool/finished` 事件在这里成对发出（R-11）。"
  [chat-client tc decision tool-context obs]
  (let [t0 (System/currentTimeMillis)
        ev {:index (:index obs) :call-id (:id tc) :name (:name tc)}]
    (emit-event! obs :tool/started (assoc ev :args (:args tc)))
    (let [r (invoke-one* chat-client tc decision tool-context (:on-tool-result obs))]
      (emit-event! obs :tool/finished
                   (cond-> (assoc ev
                                  :status (cond (:rejected? r) :rejected
                                                (:replied? r)  :replied
                                                (:error r)     :error
                                                :else          :ok)
                                  :elapsed-ms (- (System/currentTimeMillis) t0))
                     (:error r) (assoc :error-class (get-in r [:error :class]))))
      r)))

(defn- invoke-one*
  [chat-client tc decision tool-context on-tool-result]
  (cond
    (= :reject decision)
    {:tc tc :value "已拒绝执行" :rejected? true}

    ;; 宿主给了整句文案（resume 的 `:reject-note` 钩子）：原样交给模型
    (and (map? decision) (contains? decision :reject) (string? (:text decision)))
    {:tc tc :value (:text decision) :rejected? true}

    ;; 无理由的拒绝（只是为了带上 :cancelled? 之类的标记才写成 map）
    (and (map? decision) (contains? decision :reject) (nil? (:reject decision)))
    {:tc tc :value "已拒绝执行" :rejected? true}

    ;; 拒绝带理由：模型直接拿到原因，省一轮干猜
    (and (map? decision) (contains? decision :reject))
    {:tc tc :value (str "已拒绝执行：" (:reject decision)) :rejected? true}

    ;; 答复即结果（ask-user 语义）：不执行工具，载荷直接作为工具结果回模型
    (and (map? decision) (contains? decision :reply))
    {:tc tc :value (str (:reply decision)) :replied? true}

    :else
    (let [fn-key (keyword (:name tc))
          ;; **本次调用的 tool-call id 钉进 context**（`ctx/framework-keys` 里声明）。
          ;; 工具此前拿不到它：`invoke-tool` 的入参是 (chat-client fn-key args ctx)，
          ;; 手里有 id 的只有这一层。子 agent 委派要靠它把 lane 挂回那张工具卡片
          ;; （docs/subagent-event-attribution-design.md §3.7a）。
          ;;
          ;; **只钉在这一份 per-call 快照上**：reduce 阶段折 `:writes` 用的是
          ;; `collect-batch` 手里那份轮初 context，所以这个键不会随折叠外泄，
          ;; 也不会进暂停快照。发状态快照的那一侧另有过滤（`agui.emit/emit-state!`）。
          tctx (cond-> tool-context
                 (and (map? tool-context) (:id tc)) (assoc :tool/call-id (:id tc)))
          {:keys [value writes error]}
           (try (invoke-with-retry chat-client fn-key (:args tc) tctx)
                (catch Throwable t
                  (let [{:keys [message class]} (err/contain-throwable t)]
                    {:value (str "错误: " message)
                     ;; **ex-data 一并带上**：屏障处的路由分支可能要读工具作者标注的
                     ;; 那几个键（`:subagent-suspended` 就靠里面的 `:spawn-id` 找到
                     ;; 停着的那个子 agent）。只留 class/message 的话，那条信息在
                     ;; 这里就断了
                     :error (cond-> {:class class :message message}
                              (ex-data t) (assoc :data (ex-data t)))})))]
      ;; 完成即触发（并行下批内顺序不确定；需确定顺序请读 :records）
      (when on-tool-result
        (try (on-tool-result (name fn-key) value) (catch Throwable _ nil)))
      (cond-> {:tc tc :value value}
        (seq writes) (assoc :writes writes)
        error        (assoc :error error)))))

(defn- return-direct-batch?
  "整批是否 return-direct。与 Spring AI 一致取**全体**语义（allMatch）：
   混批（部分声明）时继续正常回灌 LLM——「一半直接返回、一半交给模型」
   没有自洽解释。"
  [chat-client calls]
  (and (seq calls)
       (every? #(registry/return-direct-tool? chat-client (:name %)) calls)))

(defn- direct-response
  "把工具结果消息拼成最终响应（return-direct 时不再问 LLM，结果即答案）。"
  [messages]
  (response/make-response
    :text (->> messages (keep :content) (clojure.string/join "\n"))
    :finish-reason :stop))

(defn- gate-decisions
  "批前串行预判（审批可交互，绝不并发），按原序返回决策。"
  [gate tool-calls]
  (mapv #(if gate (gate %) :proceed) tool-calls))

(defn- run-inline
  "map 阶段：按序内联执行，**不构造 Future**。"
  [chat-client tool-calls decisions tool-context obs]
  (mapv (fn [tc d] (invoke-one chat-client tc d tool-context obs))
        tool-calls decisions))

(defn- run-on-executor
  "map 阶段：全部提交给 executor 后按原序收齐（屏障）。

   任务用 `bound-fn*` 包装：把调用方的**动态绑定帧**带进工作线程。不带的话，
   同一个工具会因「这批里有几个 tool-call」（run-inline vs 本函数，**由 LLM 临场
   决定**）而看到不同的 `binding` 值——引擎与批次大小本不该改变「跑的是什么」。
   与 `clojure.core/future` / `pmap` 的传导语义一致。

   **R7: 拆 ExecutionException**——`Future.get` 把 callable 抛的任何 Throwable
   包成 ExecutionException（普通 Exception）。invoke-one 忠实重抛的 OOM 若被包
   裹会逃逸类型随引擎而变（串行=裸 OOM，并发=EE），违反 §3「引擎不改变可观察语义」。
   此处拆 cause 原样重抛。"
  [^ExecutorService executor chat-client tool-calls decisions tool-context obs]
  (let [futs (mapv (fn [tc d]
                     (.submit executor
                              ^Callable
                              (bound-fn* (fn [] (invoke-one chat-client tc d tool-context
                                                            obs)))))
                   tool-calls decisions)]
    (mapv (fn [^java.util.concurrent.Future f]
            (try (.get f)
                 (catch java.util.concurrent.ExecutionException ee
                   (let [cause (.getCause ee)]
                     (throw (if (instance? Throwable cause) cause ee))))))
          futs)))

(defn- collect-batch
  "reduce 阶段（屏障）：writes 按原始序折叠进 context；
   messages / records / errors 按原始序排回。三个引擎共用，保证返回形状一致。"
  [chat-client tool-context init-records results]
  (let [{:keys [context conflicts]}
        (ctx/apply-writes tool-context
                          (keep :writes results)
                          (get-in chat-client [:settings :state-slots]))]
    (when (seq conflicts)
      (log/warn "同批多个工具写入未声明 reducer 的槽（last-writer 按调用序生效）:"
                conflicts))
    ;; :writes 随消息进历史——event-sourcing 元数据，wire 层不发给 LLM；
    ;; 失败/被拒调用无 writes，历史中自然缺席
    {:messages (mapv (fn [{:keys [tc value writes]}]
                       (msg/tool-result (:id tc) (:name tc) value writes))
                     results)
     :records  (into init-records
                     (map (fn [{:keys [tc value rejected?]}]
                            {:name   (keyword (:name tc))
                             :args   (:args tc)
                             :result (if rejected? :rejected value)}))
                     results)
     :errors   (into []
                     (keep (fn [{:keys [tc error]}]
                             (when error
                               (cond-> {:id      (:id tc)
                                        :name    (:name tc)
                                        :class   (:class error)
                                        :message (:message error)
                                        :tc      tc}
                                 (:data error) (assoc :data (:data error))))))
                     results)
     :context  context}))

(defn- execute-batch-via
  "map + 屏障骨架，三个 manager 实现共用。

   executor 为 nil → 全程内联（Sequential 引擎：从不构造 Future）。
   executor 非 nil → 并行提交，但下列两种情形仍退化为内联：
   - 批内任一工具声明 :serial（整批退化按序，声明级契约，manager 不得违反）
   - 批内只有一个调用（并行无收益）"
  [executor chat-client tool-calls gate tool-context init-records obs]
  (let [obs       (if (map? obs) obs {:on-tool-result obs})
        decisions (gate-decisions gate tool-calls)
        serial?   (boolean (some #(registry/serial-tool? chat-client (:name %)) tool-calls))
        results   (if (or (nil? executor) serial? (<= (count tool-calls) 1))
                    (run-inline chat-client tool-calls decisions tool-context obs)
                    (run-on-executor executor chat-client tool-calls decisions
                                     tool-context obs))]
    (collect-batch chat-client tool-context init-records results)))

(defn execute-batch
  "MapReduce 执行一批工具调用（设计见 docs/agent-loop-concurrency-design.md §9）。

   **缺省串行**（无 `:tool-manager` 时走这里）：每个调用按序在调用方线程执行。
   并发要求同批工具的副作用彼此无序依赖——那是调用方才知道的性质，框架不替它
   假定。要并发就注入 `virtual-thread-tool-calling-manager`（或 thread-pool 版）。

   map：每个调用拿同一份轮初 tool-context 快照（同批工具互相看不到对方的写）。
   批内任一工具声明 :serial 时，即使选了并发引擎也整批退化为按序执行。
   **状态语义与引擎无关**：三个引擎都是快照 + 屏障折叠。

   reduce：屏障收齐后，各工具的 :writes 按 tool-call 原始序经 chat-client
   :state-slots 的槽级 reducer 折叠进 context（未声明槽默认 last-writer；
   同批多工具写同一未声明槽记 warn）。messages/records 按原始序排回。

   gate: (fn [tool-call] -> 决策)，nil 视为全 :proceed；批前串行预判
   （审批可交互，绝不并发）。决策词汇：
   - :proceed          执行
   - :reject           跳过，结果「已拒绝执行」
   - {:reject 理由}    跳过，结果「已拒绝执行：<理由>」（模型直接拿到原因）
   - {:reply 结果}     不执行，载荷即工具结果（ask-user 语义）

   on-tool-result: (fn [tool-name result-str])，每个实际执行的工具恰好触发
   一次（:reject / {:reply} 不触发）；并行下批内触发顺序不确定。

   返回: {:messages [...] :records [...] :context 新ctx
          :errors [{:id :name :class :message :tc :data?} ...]}
   :errors 为本批失败调用及其故障类别（重试耗尽后仍失败的才出现在此），
   供屏障处策略路由（环境类 → 暂停等人，见 run-tool-loop）。"
  ([chat-client tool-calls gate tool-context init-records]
   (execute-batch chat-client tool-calls gate tool-context init-records nil))
  ([chat-client tool-calls gate tool-context init-records on-tool-result]
   ;; executor = nil → 全程内联。**缺省串行**（v0.3 破坏性变更：此前缺省是
   ;; @tool-executor 的虚拟线程并行）。并发是显式选择，见 defn 文档。
   (execute-batch-via nil chat-client tool-calls gate tool-context
                      init-records on-tool-result)))

;;; ============================================================
;;; 执行引擎（ToolCallingManager 实现）
;;;
;;; 换 manager = 换执行引擎：线程模型 + 隔离边界 + 调度策略。
;;; 三个实现共用 execute-batch-via 的 map+屏障骨架，故返回形状与
;;; :serial / :tool filter / :writes 折叠三条契约完全一致——引擎只决定
;;; 「怎么把这批跑完」，不决定「跑的是什么」。
;;; ============================================================

(defn- batch-obs
  "从 `execute-tool-calls` 的 opts 摘出观察挂点（`:on-tool-result` / `:on-event` /
   `:index` / `:metadata`）。自实现的 TCM 只传 `:on-tool-result` 也照常工作。"
  [opts]
  (select-keys opts [:on-tool-result :on-event :index :metadata]))

(defn- check-timeout-opt!
  [t]
  (tool/check-timeout! "ToolCallingManager" t))

(defrecord VirtualThreadToolCallingManager [timeout]
  tool-calling-manager/ToolCallingManager
  (execute-tool-calls [_ chat-client response opts]
    (binding [tool-calling-manager/*active-manager-timeout* timeout]
      (let [calls (response/response-tool-calls response)]
        (execute-batch-via @tool-executor chat-client calls
                           (:gate opts)
                           (:tool-context opts)
                           (:records opts)
                           (batch-obs opts))))))

(defn virtual-thread-tool-calling-manager
  "并发引擎：每调用一根虚拟线程，尊重 :serial 声明。

   **不是缺省**——缺省是串行（见 `sequential-tool-calling-manager`）。同轮多个
   tool-call 并发跑，要求这些工具的副作用彼此无序依赖；那是**调用方才知道**的事，
   故须显式选择。

   线程模型：虚拟线程，无界。
   隔离边界：**无**——用的是进程全局共享 executor（见 tool-executor）。
   需要舱壁 / 限流 / 可关停边界时改用 thread-pool-tool-calling-manager。

   opts:
   - :timeout  本引擎为**没有声明 `:timeout`** 的工具设的缺省超时（毫秒）。
               缺省 nil = 不超时。工具自己的声明恒优先。"
  ([] (virtual-thread-tool-calling-manager {}))
  ([{:keys [timeout]}]
   (check-timeout-opt! timeout)
   (->VirtualThreadToolCallingManager timeout)))

(defrecord SequentialToolCallingManager [timeout]
  tool-calling-manager/ToolCallingManager
  (execute-tool-calls [_ chat-client response opts]
    (binding [tool-calling-manager/*active-manager-timeout* timeout]
      (let [calls (response/response-tool-calls response)]
        ;; executor = nil：全程内联，从不构造 Future
        (execute-batch-via nil chat-client calls
                           (:gate opts)
                           (:tool-context opts)
                           (:records opts)
                           (batch-obs opts))))))

(defn sequential-tool-calling-manager
  "**缺省引擎**：每个工具按调用序在调用方线程执行，无并发。

   **为什么串行是缺省**：并发要求同批工具的副作用彼此无序依赖——那是**调用方才
   知道**的性质，框架不该替它假定。串行是那个「无论工具长什么样都成立」的选择：
   顺序可预期、可调试、不会因为 LLM 某轮多发一个 tool-call 就把副作用交错起来。
   要并发是**显式**的决定：注入 `virtual-thread-tool-calling-manager`。
   （状态语义两者相同：都是轮初快照 + 屏障折叠。）

   线程模型：调用方线程，**全程不构造 Future**（故不背 ExecutionException 包装
   与 Future.get 的中断语义——这是它与「给 VT 引擎塞一个 same-thread executor」
   的本质区别）。**例外：声明了 `:timeout` 的工具**——超时在 terminal 内由
   `call-with-timeout` 强制（R1），此时起一根 VT 跑工具本体、调用方线程等 deref，
   「不构造 Future」承诺在此情形下不成立（R2 诚实降级）。未声明超时的工具（大多数）
   照常在调用方线程上直接跑。
   隔离边界：不持任何资源，无可争之物。

   opts:
   - :timeout  本引擎为**没有声明 `:timeout`** 的工具设的缺省超时（毫秒）。
               缺省 nil = 不超时。工具自己的声明恒优先。"
  ([] (sequential-tool-calling-manager {}))
  ([{:keys [timeout]}]
   (check-timeout-opt! timeout)
   (->SequentialToolCallingManager timeout)))

(defn- pool-thread-factory
  ^ThreadFactory [prefix]
  (let [counter (atom 0)]
    (reify ThreadFactory
      (newThread [_ r]
        (doto (Thread. ^Runnable r (str prefix (swap! counter inc)))
          ;; daemon：用户忘记 close 时不至于吊住 JVM 退出
          (.setDaemon true))))))

(defrecord ThreadPoolToolCallingManager [^ExecutorService pool timeout]
  tool-calling-manager/ToolCallingManager
  (execute-tool-calls [_ chat-client response opts]
    (when (.isShutdown pool)
      (throw (ex-info "ThreadPoolToolCallingManager 的线程池已关闭，无法再执行工具批"
                      {:error-class :environment})))
    (binding [tool-calling-manager/*active-manager-timeout* timeout]
      (let [calls (response/response-tool-calls response)]
        (execute-batch-via pool chat-client calls
                           (:gate opts)
                           (:tool-context opts)
                           (:records opts)
                           (batch-obs opts)))))

  Closeable
  (close [_] (.shutdown pool)))

(defn thread-pool-tool-calling-manager
  "有界平台线程池引擎：工具批在**本实例自己的池**里跑，形成舱壁隔离。

   线程模型：固定大小的平台线程池（daemon 线程）。
   隔离边界：每个实例一个池——工具执行的并发上限 = pool-size，不与其他 chat-client
   相互挤占；池随实例关停（见下）。这是 VT 引擎给不了的：后者用进程全局无界池。

   opts:
   - :pool-size          正整数，缺省 (availableProcessors)
   - :thread-name-prefix 线程名前缀，缺省 \"clj-agent-tool-\"
   - :timeout            为**没有声明 `:timeout`** 的工具设的缺省超时（毫秒），
                          缺省 nil = 不超时；工具自己的声明恒优先。
                          **本引擎尤其值得给一个**：没有超时的工具若卡死（如无限
                          循环、socket 挂死），会**永久占住一个池槽**（舱壁逐格坏死）；
                          有超时则池线程在 deref 超时后释放（但注意 R2：有超时的工具
                          实际跑在 VT 上、池线程只等 deref，舱壁退化为信号量）

   **生命周期由持有者负责**——池不会自己关。实现了 java.io.Closeable：

     (with-open [m (thread-pool-tool-calling-manager {:pool-size 4})]
       (let [cc (chat-client/build-chat-client {:chat-model cm :tools [...] :tool-manager m})]
         ...))

   关停后再执行工具批抛 ex-info（:error-class :environment）。

   **不变量：一个引擎属于一个 chat-client，不跨 delegate 边界。**
   子 agent 是独立 agent，自有引擎——这本来就是默认：delegate 的 subagent-config
   全部来自用户的 :subagent-fn，父 chat-client 的 :tool-manager 没有渠道流进去。

   故**不要把同一个实例亲手塞进 subagent-config 复用**（那是绕过默认去踩）：
   delegate-tool 是 spawn→await→drop，父批的工具会占着池线程阻塞等子 agent 跑完，
   而子 agent 的批又要同一个池的线程——互等，永久挂起。子 agent 要限流就给它
   **自己的**实例（各自封顶，互不嵌套）；不需要就留默认 VT 引擎。

   同理，**一个引擎的批不嵌套自己**：别在本引擎某个工具的函数体里，再拿同一个
   实例去跑一批（同样自锁）。框架不为此设防——不变量本身就是答案。

   顺带一提：有界平台池是给**真正干活**的工具封顶用的。delegate 这类只是阻塞等
   网络的工具，占一根平台线程停几秒到几分钟，本就不该走有界池。"
  ([] (thread-pool-tool-calling-manager {}))
  ([{:keys [pool-size thread-name-prefix timeout]
     :or   {pool-size          (.availableProcessors (Runtime/getRuntime))
            thread-name-prefix "clj-agent-tool-"}}]
   (when-not (pos-int? pool-size)
     (throw (ex-info "pool-size 必须为正整数" {:pool-size pool-size})))
   (check-timeout-opt! timeout)
   (->ThreadPoolToolCallingManager
     (Executors/newFixedThreadPool pool-size (pool-thread-factory thread-name-prefix))
     timeout)))

(defn shutdown-tool-calling-manager!
  "关停 manager 持有的资源。只有 ThreadPoolToolCallingManager 持有池；
   VT / Sequential 无资源，调用即 no-op。等价于对 Closeable 实现调 .close。"
  [m]
  (when (instance? Closeable m)
    (.close ^Closeable m)))

(defn run-tools
  "execute-batch 的无 gate 特例：全部执行。供外部手搓工具循环使用。

   **恒串行**（无视 `:tool-manager`）——不复用引擎的调度，但**吃引擎的 `:timeout`
   缺省**（经 invoke-tool → effective-tool-timeout 读 `:tool-manager`）。
   不对称是 v0.3 引入的：`:timeout` 在 invoke-tool 强制，而调度在 react——两者
   分属不同层，run-tools 只走前者。要完整引擎行为（含调度）请用 invoke/resume。"
  [chat-client tool-calls tool-context]
  (execute-batch chat-client tool-calls nil tool-context []))

(defn- dangling-tool-call-ids
  "history 中出现在 assistant :tool-calls 里、但没有对应 tool 结果消息的 {:id :name}。"
  [history]
  (let [paired (into #{} (keep :tool-call-id) history)]
    (for [m history :when (= :assistant (:role m))
          {:keys [id name]} (:tool-calls m)
          :when (not (paired id))]
      {:id id :name name})))

(defn heal-dangling-tool-calls!
  "开新一轮前的自愈：为 conv-id 历史里的悬空 tool_use 补「已取消」中立结果，使会话重新配平。
   无悬空则 no-op。store 即挂载于 chat-client 的同一 memory store。
   store 为 nil 时（如 :memory false 的子 Agent）直接跳过，无需自愈。"
  [store conv-id]
  (when (and store conv-id)
    (let [dangling (dangling-tool-call-ids (memory/mem-get store conv-id))]
      (when (seq dangling)
        (memory/mem-add store conv-id
                        (mapv #(msg/tool-result (:id %) (:name %)
                                                "已取消（上一轮工具调用未审批/未恢复）")
                              dangling))))))

(defn- subagent-pause
  "委派出去的子 agent 挂起 → 父也停下来等人（批已执行完，只有挂起那几个没结果）。

   与 `env-pause` 的分工：那个是「工具坏了、修好再重跑」，这个是「子 agent 在等人、
   人答完接着往下跑」。所以 resume 的语义也不同——不是 `:retry`（重跑），而是把
   决定**送进那个停着的子 agent**（见 `resume-subagent`）。

   `:by-call` 是 tool-call id → spawn id 的对照表：同一批里几个委派并发挂起时，
   人的答复要落到正确那一个上，猜不得。

   `:pending-tool` 取**子 agent 里那把敏感工具**（不是委派工具本身）——用户要审批的
   是「子 agent 想做什么」，`SUBAGENT_STARTED` 那张卡片下面显示的也是它。"
  [suspended batch-messages records remaining tctx transcript]
  (let [by-call (into {} (map (fn [e] [(:id (:tc e)) (:spawn-id (:data e))])) suspended)
        first-e (first suspended)]
    {:status :paused
     :pause-reason (str "子 agent 等待人工答复: "
                        (clojure.string/join "; " (map :message suspended)))
     :loop-state {:phase          :subagent-suspended
                  :batch-messages batch-messages
                  :suspended-calls (mapv :tc suspended)
                  :by-call        by-call
                  :remaining      remaining
                  :records        records
                  :transcript     transcript}
     :pending-tool (or (:pending-tool (:data first-e))
                       {:name (:name first-e) :args (:args (:tc first-e)) :tool-call (:tc first-e)})
     :tool-calls-made records
     :tool-context tctx}))

(defn- env-pause
  "构造环境类错误的暂停返回值（屏障处策略钩子，S2）。
   批次已执行完且结果/写折叠均已落定；暂停发生在「结果交给模型之前」。
   resume 决策：:retry（环境已修复，重跑失败调用）| :proceed（错误结果交给模型）。

   **`:loop-state` 只放可 EDN 往返的值——尤其不放 record**：PauseStore 存档时
   它不走 `pause/strip-unserializable`（那只剥 tool-context），record 会让
   `sqlite-pause-store` 的 `pause-load` 抛而 in-memory 毫发无伤，即「单进程测试
   全绿、重启后 resume 崩」。护栏见 `pause-test/loop-state-edn-roundtrip-test`。"
  [env-errors batch-messages records remaining tctx transcript]
  {:status :paused
   :pause-reason (str "环境类错误，需人工介入: "
                      (clojure.string/join "; "
                        (map #(str (:name %) ": " (:message %)) env-errors)))
   :loop-state {:phase          :env-retry
                :batch-messages batch-messages
                :failed-calls   (mapv :tc env-errors)
                :remaining      remaining
                :records        records
                :transcript     transcript}
   :pending-tool (let [e (first env-errors)]
                   {:name (:name e) :args (:args (:tc e)) :tool-call (:tc e)})
   :tool-calls-made records
   :tool-context tctx})

(defn- sync-chat-call
  "同步 LLM 调用：流式/非流式分派 + 取消令牌的动态绑定。返回 {:response :context}。"
  [chat-client delta chat-opts token]
  (if (:on-token chat-opts)
    (binding [streaming/*register-cancel* (when token (streaming/binding-register token))]
      (chat-client/invoke-chat-stream chat-client delta chat-opts))
    (chat-client/invoke-chat chat-client delta chat-opts)))

(defn- async-chat-call
  "异步 LLM 调用：同 `sync-chat-call`，返回 deferred<{:response :context}>。

   `binding` 只包**发起**（登记取消令牌必须在调用线程完成——动态 var 不跨异步
   边界；虚拟线程兜底路径靠 `bound-fn*` 传导绑定帧，原生异步 provider 则在返回
   deferred 之前当场登记）。见 docs/async-chat-model-design.md §6.1。"
  [chat-client delta chat-opts token]
  (if (:on-token chat-opts)
    (binding [streaming/*register-cancel* (when token (streaming/binding-register token))]
      (chat-client/invoke-chat-stream-async chat-client delta chat-opts))
    (chat-client/invoke-chat-async chat-client delta chat-opts)))

(defn- drive-sync
  "同步 driver：`loop/recur`——**常数栈**，长循环也不吃栈。"
  [chain remaining* delta tctx]
  (loop [delta delta, tctx tctx, index 0]
    (let [result (chain {:messages delta :context tctx
                         :index index :remaining @remaining*})]
      (if (= :continue (:status result))
        (recur (:messages result) (:context result) (inc index))
        result))))

(defn- drive-async
  "异步 driver：`fbind` 自递归。deferred 由别的线程完成时续延不吃栈；即便当场
   完成（同步 provider 兜底）递归深度也 ≤ max-iterations（缺省 10）。"
  [chain remaining* delta tctx]
  (letfn [(step [delta tctx index]
            (flt/fbind (chain {:messages delta :context tctx
                               :index index :remaining @remaining*})
                       (fn [result]
                         (if (= :continue (:status result))
                           (step (:messages result) (:context result) (inc index))
                           result))))]
    (step delta tctx 0)))

(defn- chat-client-memory-store
  "取 chat-client 上 memory filter 绑定的 store（filter 刻意暴露 :store）。
   没挂 memory filter → nil（此时工具结果本就不落库，直接返回也无需补）。"
  [chat-client]
  (some #(when (= :memory (:name %)) (:store %)) (:filters chat-client)))

(declare response->assistant-message)

(defn- pause-transcript
  "暂停快照里随身带的那段对话（`:loop-state :transcript`，观澜反馈 2026-11-12 ③）：
   **到 assistant(tool_calls) 为止**的完整历史，resume 拿它接上工具结果就能续跑，
   不依赖 ChatMemory 还在不在。

   - 没挂 memory filter：**恒带**——本轮 delta 就是全部上下文，不带的话 resume 只剩孤立的
     工具结果（曾经如此：resume 后模型看不到用户的问题）；
   - 挂了 memory filter：`:pause-transcript? true` 才带（取 store 里这一会话的历史，此刻已含
     assistant(tool_calls)）。缺省不带——同进程续跑 memory 本来就在，没必要把整段对话塞进快照。"
  [chat-client policy delta response tctx]
  (if-let [store (chat-client-memory-store chat-client)]
    (when (:pause-transcript? policy)
      (when-let [cid (ctx/conversation-id tctx)]
        (vec (memory/mem-get store cid))))
    (conj (vec delta) (response->assistant-message response))))

(defn- resume-delta
  "resume 时把快照里的 `:transcript` 接回去，返回续跑第一轮的 delta：
   - 没挂 memory：transcript ++ 工具结果（循环自己带完整对话，同 invoke）；
   - 挂了 memory：store 里这一会话**是空的**（重启后 in-memory 丢了 / 换了实例）才用 transcript
     回填，随后照常只送工具结果；store 里有东西就不动它（同进程续跑，以 store 为准）。
   快照里没有 transcript（旧快照 / memory 下没开）→ 原样返回。"
  [chat-client loop-state tctx msgs]
  (if-let [t (seq (:transcript loop-state))]
    (if-let [store (chat-client-memory-store chat-client)]
      (let [cid (ctx/conversation-id tctx)]
        (when (and cid (empty? (memory/mem-get store cid)))
          (memory/mem-add store cid (mapv msg/normalize t)))
        msgs)
      (into (mapv msg/normalize t) msgs))
    msgs))

(defn- loop-policy
  "工具循环的策略位：`:on-env-error`（缺省值随入口不同）+ `:pause-transcript?`。"
  [opts default-env]
  {:on-env-error (or (:on-env-error opts) default-env)
   :pause-transcript? (boolean (:pause-transcript? opts))})

(defn- response->assistant-message
  "把带 tool-calls 的响应还原成中立 assistant 消息。

   有 memory filter 时这一步由 filter 侧补：本轮响应已落库，下一轮只要回传
   工具结果 delta，历史自会拼成 assistant(tool_calls) → tool(results)。
   但 `:memory false` 是合法的编排形态——子 Agent / 一次性调用都不该被迫落库。
   这时循环自己必须把 assistant(tool_calls) 带上，否则下一轮只有孤立的
   tool result，OpenAI / Anthropic / Responses 网关都会以 tool id 找不到拒绝；
   而且要接在本轮发出去的全部消息后面（完整 transcript），否则历史丢失。"
  [response]
  (let [text (response/response-text response)
        calls (response/response-tool-calls response)
        m (msg/assistant-tool-calls
           (mapv (fn [{:keys [id name args]}] (msg/tool-call id name args)) calls)
           (when (seq text) text))]
    (msg/with-blocks m (response/response-replay-blocks response))))

(defn- add-usage
  "累计两份归一化 usage（数值键相加）。"
  [a b]
  (cond (nil? b) a (nil? a) b :else (merge-with #(if (and (number? %1) (number? %2)) (+ %1 %2) %2) a b)))

(defn- response->plain-assistant
  "不带工具调用的响应 → 中立 assistant 消息（含不透明回放块）。pause_turn 续跑在
   没有 memory filter 时要把它原样带回下一轮。"
  [response]
  (msg/with-blocks (msg/assistant (response/response-text response))
                   (response/response-replay-blocks response)))

(defn- default-pause-turn?
  "pause_turn 续跑判据（R-13，同 Keelson `PauseTurn.CanResume`）：finish-reason 为
   `:pause`、且有可回放的内容（文本或不透明块）。没内容就续不了——服务端没东西可接。"
  [response]
  ;; 主判据用 provider 侧的 `response/pause-turn-resumable?`（P-10：有回放块、无本地工具调用）；
  ;; 迭代上限由本循环自己的预算管。补一条：只有文本、没有块的 :pause 也续（服务端可接着写）。
  (or (response/pause-turn-resumable? response)
      (and (= :pause (response/response-finish-reason response))
           (not (response/has-tool-calls? response))
           (boolean (seq (response/response-text response))))))

(defn- pause-turn?
  "可由 `:settings :pause-turn-fn` 覆盖（provider 若有更准的判定可接进来）。"
  [chat-client response]
  ((or (get-in chat-client [:settings :pause-turn-fn]) default-pause-turn?) response))

(defn- stop-result
  "工具侧请求停回合（context 里的 `:loop/stop`）→ `:completed` 收尾。
   本批工具结果经 `:direct-messages` 补落库（同 return-direct：没有下一次 invoke-chat
   替它落库，不补就留悬空 tool_use）。"
  [context messages records]
  (let [{:keys [reason by]} (:loop/stop context)]
    {:status :completed
     :response (response/make-response :text (str reason) :finish-reason :stop)
     :tool-context (dissoc context :loop/stop)
     :tool-calls-made records
     :direct-messages messages
     :stopped-by (or by :tool)
     :stop-reason reason}))

(defn- run-tool-loop*
  "统一工具循环：从 delta 起步，驱动 LLM ↔ 工具，直到文本响应或暂停。

   callbacks 携带观察回调（:on-llm-call/:on-llm-result/:on-tool-result）和元数据
   （:metadata）。gate 评估结果按 tool-call :id 缓存，确保每个工具调用恰好触发
   一次（不因 pause 检测的两阶段逻辑而重复）。

   policy: {:on-env-error :pause|:proceed}——屏障处发现 :environment 类错误时
   暂停等人（HITL）还是照常把错误结果交给模型（缺省 :proceed）。

   **每轮经 `:iteration` 链**（filter.clj 第四条 around 链）：一轮 = LLM 调用 +
   该轮工具批次。终端返回 `{:status :continue :messages <下一轮 delta> :context c}`
   或既有终态；外层 loop 只在 `:continue` 时推进。没挂 `:iteration` filter 时链是
   `identity`——终端即循环体本身，与加这层之前逐句等价。

   **remaining / records 是跨轮累积量，故存 volatile 而非 loop 参数**：
   `:iteration` filter 重入一轮时，那一轮的 LLM 调用与工具批次**真的又跑了一遍**，
   预算与记录都该如实计入（记「发生过什么」，不记「逻辑上算几轮」）——这也让
   `max-iterations` 对 filter 重入仍是硬上限。扣减点在**批次实际执行之后**，
   与改造前 `(recur … (dec remaining) …)` 的时机逐字相同。

   **同步 / 异步同源**（2026-09-03）：迭代体只有一份，两条路径只差两个注入项——
   `chat-fn`（`invoke-chat` 还是 `invoke-chat-async`）与 `drive`（`loop/recur`
   还是 `fbind` 自递归）。迭代体的响应侧走 `flt/fmap`，同步下就是恒等展开
   （逐字等价于改造前），异步下自动变成续延。设计见
   docs/async-chat-model-design.md §7。

   **designs/runtime-borrow.md 的新增**（全部缺省无感）：
   - IterationRequest 的 `:notes` → 本轮临时提醒（不落库，§0 挂点 1）；
   - `:continue` 结果带 `:calls` / `:errors`（本轮 tool-call 与失败）；
   - 批后 context 里有 `:loop/stop` → 以 `:completed` 收尾（§0 挂点 2）；
   - `pause_turn` 响应原样续跑（R-13）；
   - `:on-event` 生命周期事件（R-11）；
   - gate 可返回 `{:pause {...}}`，暂停结果带 `:pending-tools`（§15）。

   返回:
   {:status :completed :response r :tool-context c :tool-calls-made [...]}
   {:status :paused    :loop-state {...} :pending-tool {...} :tool-context c}
   （暂停两种 phase：审批（工具未执行）与 :env-retry（批已执行、环境类失败））
   ——异步 driver 下同形，只是被 deferred 包了一层。"
  [chat-client delta remaining records tctx gate chat-opts callbacks policy
   {:keys [chat-fn drive]}]
  (let [token (:cancel-token chat-opts)
        meta (or (:metadata callbacks) {})
        on-tool-result (:on-tool-result callbacks)
        usage* (or (::usage callbacks) (atom nil))
        memory? (some? (chat-client-memory-store chat-client))
        remaining* (volatile! remaining)
        records*   (volatile! records)
        iteration-terminal
        (fn [ireq]
          (let [delta (:messages ireq)
                tctx  (:context ireq)
                index (:index ireq)
                notes (seq (:notes ireq))]
            (if (streaming/cancelled? token)
              {:status :cancelled :tool-context tctx :tool-calls-made @records*}
              (let [;; 临时提醒：有 memory filter 时钉在本次请求的 context 上，由它在展开历史
                    ;; **之后**追加（不落库）；没有就直接拼进本轮 delta（本就不落库）
                    call-delta (if (and notes (not memory?))
                                 (conj (vec delta) (memory-filter/notes-message notes))
                                 delta)
                    call-ctx (cond-> tctx (and notes memory?) (assoc :loop/notes (vec notes)))
                    t0 (System/currentTimeMillis)
                    first-tok (volatile! nil)
                    call-opts (cond-> (assoc chat-opts :context call-ctx)
                                (and (:on-token chat-opts) (:on-event callbacks))
                                (update :on-token (fn [f] (fn [tok]
                                                            (when-not @first-tok
                                                              (vreset! first-tok (System/currentTimeMillis)))
                                                            (f tok)))))]
                (emit-event! callbacks :iteration/started {:index index})
                ;; 每次 LLM 调用前触发（观察用，不影响流程）
                (cb/invoke callbacks :on-llm-call call-delta meta)
                ;; 唯一的异步点：LLM 调用。其后整段（工具批次、屏障、收尾判定）
                ;; 是 fmap 的续延——同步下当场执行，异步下在 LLM 完成时执行。
                (flt/fmap
                 (chat-fn chat-client call-delta call-opts token)
                 (fn [{:keys [response context]}]
                  (let [tctx (dissoc context :loop/notes)
                        calls (response/response-tool-calls response)
                        usage (response/response-usage response)]
                  (when (:on-event callbacks)
                    (let [total (swap! usage* add-usage usage)]
                      (emit-event! callbacks :llm/finished
                                   (cond-> {:index index
                                            :elapsed-ms (- (System/currentTimeMillis) t0)
                                            :usage usage
                                            :finish-reason (response/response-finish-reason response)}
                                     @first-tok (assoc :ttft-ms (- @first-tok t0))))
                      (emit-event! callbacks :usage {:index index :round usage :total total})))
                  ;; 每次 LLM 返回后触发（观察用，不影响流程）
                  (cb/invoke callbacks :on-llm-result response meta)
                  (cond
                    (streaming/cancelled? token)
                    {:status :cancelled :response response
                     :tool-context tctx :tool-calls-made @records*}

                    ;; R-13 pause_turn：服务端工具跑久了先交回来——原样续跑，不加 user 消息
                    (and (empty? calls) (pause-turn? chat-client response))
                    (if (<= @remaining* 0)
                      {:status :completed :response response
                       :tool-context tctx :tool-calls-made @records*}
                      (do (vswap! remaining* dec)
                          {:status :continue
                           :messages (if memory? [] (conj (vec delta) (response->plain-assistant response)))
                           :context tctx :calls [] :errors [] :pause-turn true}))

                    (empty? calls)
                    {:status :completed :response response
                     :tool-context tctx :tool-calls-made @records*}

                    ;; 续跑判据（对标 Spring AI ToolExecutionEligibilityChecker）：
                    ;; 判据说停 → 带 tool-call 的响应也按最终答案收尾（工具不执行）
                    (not ((or (get-in chat-client [:settings :eligibility-fn]) (constantly true))
                          response tctx))
                    {:status :completed :response response
                     :tool-context tctx :tool-calls-made @records*}

                    ;; gate 评估缓存：按 :id 存结果，确保每个 tool-call 恰好评估一次。
                    ;; 这修正了原来 some+filter 两阶段导致的双重触发问题，
                    ;; 使 on-tool-call 集成在 gate 中时能保证「每工具恰好一次」语义。
                    :else
                    (let [gate-cache (when gate
                                       (into {} (mapv #(vector (:id %) (gate %)) calls)))
                          cached-gate (when gate-cache
                                        (fn [tc] (get gate-cache (:id tc) :proceed)))
                          paused-calls (when gate-cache
                                         (filterv #(pause-decision? (get gate-cache (:id %) :proceed)) calls))
                          paused-call (first paused-calls)]
                      (cond
                        (some? paused-call)
                        (let [pending (fn [tc]
                                        (let [d (get gate-cache (:id tc))]
                                          (cond-> {:name (:name tc) :args (:args tc) :tool-call tc}
                                            (map? d) (assoc :approval (:pause d)))))]
                          {:status :paused
                           :pause-reason (str "需要审批: " (clojure.string/join ", " (map :name paused-calls)))
                           ;; 批次尚未执行，故这里**预扣**一次：resume 时执行它就是那一次消耗。
                           ;; :loop-state 只放可 EDN 往返的值（不放 record）——理由同
                           ;; env-pause 的 docstring；护栏是 loop-state-edn-roundtrip-test
                           :loop-state {:tool-calls calls :remaining (dec @remaining*)
                                        :records @records*
                                        :pending-id (:id paused-call)   ;; resume payload 定位用
                                        :pending-ids (mapv :id paused-calls)
                                        :transcript (pause-transcript chat-client policy delta response tctx)}
                           :pending-tool (pending paused-call)
                           :pending-tools (mapv pending paused-calls)
                           :tool-calls-made @records*
                           :tool-context tctx})

                        (<= @remaining* 0)
                        (throw (ex-info "工具调用循环次数超过上限（max-iterations）"
                                        {:reason :max-iterations-exceeded
                                         :tool-call-count (count @records*)
                                         :tool-calls-made @records*}))

                        :else
                        (let [batch-opts {:gate cached-gate
                                          :tool-context tctx
                                          :records @records*
                                          :on-tool-result on-tool-result
                                          :on-event (:on-event callbacks)
                                          :metadata meta
                                          :index index}
                              {:keys [messages records context errors]}
                              (if-let [tm (:tool-manager chat-client)]
                                (tool-calling-manager/execute-tool-calls tm chat-client response batch-opts)
                                (execute-batch-via nil chat-client calls cached-gate tctx @records*
                                                   (batch-obs batch-opts)))
                              ;; 批次真跑了 → 当场记账。filter 重入会再跑一次，也再记一次。
                              _ (vreset! records* records)
                              _ (vswap! remaining* dec)
                              env-errors (when (= :pause (:on-env-error policy))
                                           (filterv #(= :environment (:class %)) errors))
                              ;; **不看 `:on-env-error` 策略**：那是「环境坏了要不要
                              ;; 停」的选择，而子 agent 挂起是人已经被叫住了，没得选
                              suspended (filterv #(= :subagent-suspended (:class %)) errors)]
                          (cond
                            ;; 屏障处第一支：委派出去的子 agent 在等人答复
                            (seq suspended)
                            (subagent-pause suspended messages records @remaining* context
                                            (pause-transcript chat-client policy delta response context))

                            ;; 屏障处策略钩子：环境类失败 → 带一致快照暂停等人
                            (seq env-errors)
                            (env-pause env-errors messages records @remaining* context
                                       (pause-transcript chat-client policy delta response context))

                            ;; 工具侧请求停回合（tool-guard / 拒绝熔断，§0 挂点 2）
                            (:loop/stop context)
                            (stop-result context messages records)

                            ;; return direct：结果即最终答案，不再回灌 LLM。
                            ;; :direct-messages 交给调用方落库——正常路径下工具结果是靠
                            ;; **下一次 invoke-chat** 经 memory filter 落库的，这里没有
                            ;; 下一次，不补落库就会在历史里留下悬空 tool_use。
                            (return-direct-batch? chat-client calls)
                            {:status :completed
                             :response (direct-response messages)
                             :tool-context context
                             :tool-calls-made records
                             :direct-messages messages
                             :return-direct true}

                            :else
                            ;; 没有 memory filter ⇒ 循环自己累积完整对话：本轮发出去的消息（不含临时
                            ;; 提醒）+ assistant(tool_calls) + 工具结果。曾经只带后两样，第二轮起历史
                            ;; （用户的问题）就丢了（观澜反馈 2026-11-11 ②）
                            (let [next-delta (if memory?
                                               messages
                                               (-> (vec delta)
                                                   (conj (response->assistant-message response))
                                                   (into messages)))]
                              {:status :continue :messages next-delta :context context
                               :calls calls :errors errors})))))))))))))
        iteration-chain ((:iteration (flt/filter-hooks chat-client)) iteration-terminal)]
    (drive iteration-chain remaining* delta tctx)))

(defn- run-tool-loop
  "工具循环（**同步**）：`invoke-chat` + `loop/recur` driver。行为与加异步孪生之前逐字相同。"
  [chat-client delta remaining records tctx gate chat-opts callbacks policy]
  (run-tool-loop* chat-client delta remaining records tctx gate chat-opts callbacks policy
                  {:chat-fn sync-chat-call :drive drive-sync}))

(defn- run-tool-loop-async
  "工具循环（**异步**）：`invoke-chat-async` + `fbind` 自递归 driver，返回 deferred。"
  [chat-client delta remaining records tctx gate chat-opts callbacks policy]
  (run-tool-loop* chat-client delta remaining records tctx gate chat-opts callbacks policy
                  {:chat-fn async-chat-call :drive drive-async}))

(defn- persist-direct-messages!
  "return-direct 收尾时补落库工具结果消息。

   正常路径下，某轮工具结果是靠**下一轮 invoke-chat** 经 memory filter 落库的
   （每轮只向 invoke-chat 传 delta）。return-direct 没有下一轮，若不补这一刀，
   历史里就只剩 assistant(tool_calls) 而无对应结果——下个 turn 的
   heal-dangling-tool-calls! 会把它整条摘掉，于是「用户问了、也答了」在历史里
   双双蒸发。

   落库形状 = 正常路径的形状（user → assistant(tool_calls) → tool(results)），
   与 Spring AI returnDirect 的 history 一致：不额外造一条 assistant 消息。

   幂等：只在结果带 :direct-messages 时落一次，随后摘掉该键——turn filter
   递归重入时不会重复落库。无 memory filter / 无 conv-id 时跳过。"
  [result chat-client conv-id]
  (if-let [ms (:direct-messages result)]
    (let [store (chat-client-memory-store chat-client)]
      (when (and store conv-id)
        (memory/mem-add store conv-id (mapv msg/normalize ms)))
      (dissoc result :direct-messages))
    result))

(defn- emit-turn-finished!
  "`:turn/finished`（R-11）：正常结果带 :status，异常带 :status :error 与 :error 消息。"
  [callbacks t0 result ^Throwable t]
  (emit-event! callbacks :turn/finished
               (cond-> {:status (if t :error (:status result))
                        :elapsed-ms (- (System/currentTimeMillis) t0)
                        :usage (some-> (::usage callbacks) deref)}
                 (:stopped-by result) (assoc :stopped-by (:stopped-by result))
                 t (assoc :error (or (.getMessage t) (str (class t)))))))

(defn- invoke*
  "invoke / invoke-async 的**唯一**实现；两者只差一个 `run`（跑法）。

   两个注入项：
   - `run`     `(fn [thunk] -> 链结果)`——`async/inline` 当场执行返回普通值，
               `async/vthread` 丢进虚拟线程返回 CompletableFuture。只用在 heal
               这一处**阻塞 store IO** 上（异步模式下它不该占住调用线程）。
   - `loop-fn` 工具循环本体——`run-tool-loop`（同步）或 `run-tool-loop-async`
               （异步，返回 deferred）。异步循环自己就不阻塞，故**不需要**再包
               一层 vthread。

   其余逐字相同——响应侧全走 `flt/fmap` / `flt/fcatch`（契约 C1「形态保持」的
   直接兑现：同一份编排代码，同步进同步出、异步进异步出）。"
  [chat-client store messages opts run loop-fn]
  (when-not (:chat-model chat-client)
    (throw (ex-info "ChatClient 未配置 ChatModel（请在 build-chat-client 中提供 :chat-model）"
                    {:chat-client-keys (keys chat-client)})))
  (let [base-ctx (or (:context opts) (ctx/create))
        ephemeral? (nil? (ctx/conversation-id base-ctx))
        conv-id (or (ctx/conversation-id base-ctx)
                    (str "conv-" (java.util.UUID/randomUUID)))
        init-ctx (ctx/with-conversation-id base-ctx conv-id)
        max-iter (or (:max-iterations opts)
                     (get-in chat-client [:settings :max-tool-iterations])
                     default-max-iterations)
        callbacks (assoc (or (:callbacks opts) {}) ::usage (atom nil))
        t0 (System/currentTimeMillis)
        ;; turn 链：包整个工具循环（每 turn 一次；filter 可改写 :messages/:context、
        ;; 递归重入——每次重入获得全新 max-iterations 预算）。设计见 §14。
        ;; 异步 loop-fn 直接返回 deferred，turn filter 因此**真的**拿到 deferred
        ;; （含递归重入：每次重入都是一条新的 deferred 链）。
        turn-terminal (fn [treq]
                        (-> (loop-fn chat-client
                                     (mapv msg/normalize (:messages treq))
                                     max-iter [] (or (:context treq) init-ctx)
                                     (:tool-gate opts)
                                     (build-chat-opts chat-client opts)
                                     callbacks
                                     (loop-policy opts :proceed))
                            (flt/fmap #(persist-direct-messages! % chat-client conv-id))))
        turn-chain ((:turn (flt/filter-hooks chat-client)) turn-terminal)]
    (emit-event! callbacks :turn/started {:resumed? false})
    ;; heal 也走 run：异步模式下它是 store IO，不该占住调用线程
    (flt/fcatch
      (-> (run (fn [] (heal-dangling-tool-calls! store conv-id)))
          (flt/fbind (fn [_] (turn-chain {:messages messages :context init-ctx})))
          (flt/fmap (fn [result]
                      (when (and ephemeral? store (= :completed (:status result)))
                        (memory/mem-clear store conv-id))
                      (emit-turn-finished! callbacks t0 result nil)
                      result)))
      (fn [t]
        (when (and ephemeral? store) (memory/mem-clear store conv-id))
        (emit-turn-finished! callbacks t0 nil t)
        (throw t)))))

(defn invoke
  "工具调用循环主入口（统一循环）。**同步**：阻塞到出结果。

   参数:
   - chat-client:   ChatClient 实例（需配置 ChatModel）
   - store:    ChatMemory store（与 chat-client 上 memory-filter 同一实例；用于 heal/临时清理）
   - messages: 本轮新消息（中立消息）。**没挂 memory filter** 时这就是全部上下文：循环自己把
               每轮的 assistant(tool_calls) 与工具结果接在后面（完整 transcript）；暂停时这段对话随
               `:loop-state :transcript` 走，resume 不靠 memory
   - opts:     {:context :system-prompts :max-iterations :tool-choice :tool-gate :tags/:exclude-tags
               :chat-options 原样并进每一轮 chat 选项的 map（思考档 / 温度…；循环自管的键不让）
               :pause-transcript? 挂了 memory 时也把对话历史放进暂停快照（缺省 false）——
                              resume 时 store 里这一会话是空的（重启后）就用它回填
               :reject-note  resume 时人拒绝 / 取消的结果文案钩子（见 `resume`）
               :on-env-error :pause|:proceed（缺省 :proceed——环境类工具失败照常交给模型；
                              :pause 时在屏障处暂停等人，resume :retry/:proceed 续跑）
               :callbacks  回调 map（:on-llm-call/:on-llm-result/:on-tool-result/:metadata 等）}

   返回:
   {:status :completed :response r :tool-context c :tool-calls-made [...]}
   {:status :paused    :loop-state {...} :pending-tool {...} :tool-context c}"
  [chat-client store messages opts]
  (invoke* chat-client store messages opts async/inline run-tool-loop))

(defn invoke-async
  "同 `invoke`，但**立刻返回 `CompletableFuture`**，整个 turn 跑在虚拟线程上。

   给 Ring / Luminus 异步 handler 用：HTTP 工作线程不被 LLM 往返占住。
   取结果用 `flt/fmap` 组合、`async/on-complete` 接回调，或 `async/join` 阻塞。

   语义与 `invoke` **逐字相同**（同一份 `invoke*`）：暂停/取消/错误状态、
   ephemeral 会话清理、turn filter 的递归重入都照旧；差别只在返回值被
   deferred 包了一层。turn filter 在这条路径上会真的拿到 deferred——
   写响应侧时必须走 `flt/fmap` / `flt/fbind`，不能 `let` 出来直接用（§2.6.4）。

   **全链路 deferred**（2026-09-03，P2）：循环本体走 `run-tool-loop-async`——
   每轮 LLM 调用经 `chat-client/invoke-chat-async`，`:chat` / `:iteration` /
   `:turn` 三条链都在异步形态下运行。ChatModel 未实现 `IAsyncChatModel` 时由
   `chat-model/call-async*` 用虚拟线程兜底，行为一致、只是每次 LLM 调用占一根
   虚拟线程。`:tool` 终端仍同步（工具是用户代码，阻塞是常态；批次并行本就在
   executor 上），见 docs/async-chat-model-design.md §8。"
  ^java.util.concurrent.CompletableFuture
  [chat-client store messages opts]
  (invoke* chat-client store messages opts async/vthread run-tool-loop-async))

(defn- replace-tool-results
  "把重试产出的 tool 结果消息按 :tool-call-id 替换进原批次消息（保持原序）。"
  [orig-messages retry-messages]
  (let [by-id (into {} (map (juxt :tool-call-id identity)) retry-messages)]
    (mapv #(or (get by-id (:tool-call-id %)) %) orig-messages)))

(defn- resume-subagent
  "从 `:subagent-suspended` 暂停恢复：人的答复要送进**那个停着的子 agent**。

   与 `resume-env` 的形状一样（重跑挂起那几个调用、结果按 id 替换进原批次消息），
   但**语义相反**：那边是「环境修好了，把工具重跑一遍」；这边重跑的是同一个委派
   工具，而它认出 ToolContext 里的 `:subagent/resume` 之后**不会重开子 agent**，
   而是把决定送进停着的那个（`delegate/resume-target` → `mgr/resume!`）。
   重开等于把人刚才的答复扔了、token 再烧一遍。

   `:observer` 从 opts 的 context 里拿——续跑发生在**新的一条 run** 上，事件要进
   新那条流；lane id 仍由 spawn id 决定，所以 `subagentRunId` 跨 resume 不变。

   续跑之后还可能再次挂起（子 agent 一轮里连着两个敏感工具），那时照样落回同一个
   `subagent-pause`，人再答一次。"
  [chat-client {:keys [batch-messages suspended-calls by-call remaining records transcript] :as loop-state}
   decision opts run loop-fn]
  (let [tctx (or (:context opts) (ctx/create))
        gate (:tool-gate opts)
        callbacks (or (:callbacks opts) {})
        policy (loop-policy opts :pause)
        chat-opts (build-chat-opts chat-client opts)
        ;; 决定 + 对照表钉进 ToolContext，委派工具在 handler 里认它
        tctx (assoc tctx :subagent/resume {:decision decision
                                           :payload (:payload opts)
                                           :by-call by-call
                                           :observer (:subagent/observer tctx)})]
    (-> (run (fn []
               (let [response (response/make-response :tool-calls suspended-calls)
                     batch-opts {:gate nil
                                 :tool-context tctx
                                 :records records
                                 :on-tool-result (:on-tool-result callbacks)}
                     {:keys [messages records context errors]}
                     (if-let [tm (:tool-manager chat-client)]
                       (tool-calling-manager/execute-tool-calls tm chat-client response batch-opts)
                       (execute-batch chat-client suspended-calls nil tctx records
                                      (:on-tool-result callbacks)))]
                 {:merged (replace-tool-results batch-messages messages)
                  :records records
                  ;; **续跑用的钥匙不留在 context 里**：它只对这一批有效，
                  ;; 折进长期 context 会让下一轮的委派误以为自己也在续跑
                  :context (dissoc context :subagent/resume)
                  :suspended (filterv #(= :subagent-suspended (:class %)) errors)})))
        (flt/fbind (fn [{:keys [merged records context suspended]}]
                     (if (seq suspended)
                       (subagent-pause suspended merged records remaining context transcript)
                       (loop-fn chat-client (resume-delta chat-client loop-state context merged)
                                remaining records context
                                gate chat-opts callbacks policy)))))))

(defn- resume-env
  "从 :env-retry 暂停恢复（批已执行、环境类失败）。
   decision :retry   → 环境已修复：重跑失败调用，结果按 id 替换进原批次消息；
                       若仍有环境类失败则再次暂停（同 phase）。
   decision 其他     → :proceed：原错误结果照常交给模型。

   `run` / `loop-fn` 见 `invoke*`：重跑那一批是**阻塞**的工具执行，异步模式下
   进虚拟线程；随后的循环由 `loop-fn` 决定同步还是 deferred。"
  [chat-client {:keys [batch-messages failed-calls remaining records transcript] :as loop-state}
   decision opts run loop-fn]
  (let [tctx (or (:context opts) (ctx/create))
        gate (:tool-gate opts)
        callbacks (or (:callbacks opts) {})
        policy (loop-policy opts :pause)
        chat-opts (build-chat-opts chat-client opts)]
    (if (= :retry decision)
      (-> (run (fn []
                 (let [response (response/make-response :tool-calls failed-calls)
                       batch-opts {:gate nil
                                   :tool-context tctx
                                   :records records
                                   :on-tool-result (:on-tool-result callbacks)}
                       {:keys [messages records context errors]}
                       (if-let [tm (:tool-manager chat-client)]
                         (tool-calling-manager/execute-tool-calls tm chat-client response batch-opts)
                         (execute-batch chat-client failed-calls nil tctx records
                                        (:on-tool-result callbacks)))]
                   {:merged (replace-tool-results batch-messages messages)
                    :records records
                    :context context
                    :env-errors (when (= :pause (:on-env-error policy))
                                  (filterv #(= :environment (:class %)) errors))})))
          (flt/fbind (fn [{:keys [merged records context env-errors]}]
                       (if (seq env-errors)
                         (env-pause env-errors merged records remaining context transcript)
                         (loop-fn chat-client (resume-delta chat-client loop-state context merged)
                                  remaining records context
                                  gate chat-opts callbacks policy)))))
      (loop-fn chat-client (resume-delta chat-client loop-state tctx batch-messages)
               remaining records tctx
               gate chat-opts callbacks policy))))

(declare ^:private resume-approval resume-approval-batch)

(defn- resume*
  "resume / resume-async 的唯一实现；两者只差 `run` / `loop-fn` 两个注入项，见 `invoke*`。"
  [chat-client loop-state decision opts run loop-fn]
  (let [opts (update opts :callbacks #(assoc (or % {}) ::usage (atom nil)))
        callbacks (:callbacks opts)
        t0 (System/currentTimeMillis)
        continuation
        (fn []
          (case (:phase loop-state)
            :env-retry          (resume-env chat-client loop-state decision opts run loop-fn)
            :subagent-suspended (resume-subagent chat-client loop-state decision opts run loop-fn)
            (resume-approval chat-client loop-state decision opts run loop-fn)))
        tctx (or (:context opts) (ctx/create))
        max-iter (or (:max-iterations opts)
                     (get-in chat-client [:settings :max-tool-iterations])
                     default-max-iterations)
        consumed? (atom false)
        ;; 终端一次性分派：首调 = 暂停延续（自带 run：批次执行是阻塞的）；
        ;; 重入 = 常规循环（新 delta，loop-fn 自己决定同步/异步）
        terminal (fn [treq]
                   (-> (if (compare-and-set! consumed? false true)
                         (continuation)
                         (loop-fn chat-client
                                  (mapv msg/normalize (:messages treq))
                                  max-iter [] (or (:context treq) tctx)
                                  (:tool-gate opts)
                                  (build-chat-opts chat-client opts)
                                  (or (:callbacks opts) {})
                                  (loop-policy opts :proceed)))
                       ;; 延续与重入都可能撞上 return-direct 收尾
                       (flt/fmap #(persist-direct-messages! % chat-client (ctx/conversation-id tctx)))))
        turn-chain ((:turn (flt/filter-hooks chat-client)) terminal)]
    (emit-event! callbacks :turn/started {:resumed? true})
    (flt/fcatch
      (flt/fmap (turn-chain {:resume? true :messages nil :context tctx})
                (fn [result] (emit-turn-finished! callbacks t0 result nil) result))
      (fn [t] (emit-turn-finished! callbacks t0 nil t) (throw t)))))

(defn resume
  "从 paused 的 loop-state 继续工具循环。**同步**：阻塞到出结果。

   两种暂停 phase：
   - 审批暂停（缺省，工具未执行）：decision :approved（强制全部执行）
     | :rejected（gate 决定，敏感→拒绝）| :reply（不执行 pending 工具，
     payload :message 直接作为其结果——ask-user 语义）
   - :env-retry（批已执行、环境类失败）：decision :retry（环境已修复，重跑
     失败调用）| :proceed（错误结果交给模型）

   opts :payload（审批 phase 可选，携带用户答复）：
   - :approved + {:args 新参数}   → pending 工具以替换后的参数执行（编辑后批准）
   - :rejected + {:message 理由}  → 结果「已拒绝执行：<理由>」（模型直接拿到原因）
                 payload（或逐件决定）带 `:cancelled? true` = 用户是**取消**而非拒绝（交给钩子区分）
   - :reply    + {:message 答复}  → 答复即 pending 工具的结果（必填）

   opts :reject-note `(fn [{:keys [tool args call-id reason cancelled? expired?]}] 文本|nil)`：
   人拒绝 / 取消时交给模型的那句由宿主写，nil 回落缺省「已拒绝执行[：理由]」。

   快照带 `:transcript`（无 memory 恒带；有 memory 时 `:pause-transcript? true` 才带）时，
   resume 不依赖 ChatMemory：无 memory 直接接上；有 memory 而 store 里这一会话为空则先回填。

   参数:
   - chat-client:     ChatClient 实例
   - loop-state: invoke 返回的 :loop-state
   - decision:   见上
   - opts:       同 invoke（须含带 conversation-id 的 :context 以接续历史；
                 可含 :payload）

   turn 链语义：resume 同样经过 :turn 洋葱——首次进入终端执行「暂停 turn 的
   延续」（消费 loop-state）；turn filter 的递归重入（如校验反馈）走全新
   工具循环（新 delta，上下文由 memory 拼接）。TurnRequest 带 :resume? true
   标记，请求侧改写类 filter（RAG 注入等）应据此跳过首次改写。

   返回: 同 invoke（:completed 或再次 :paused）"
  [chat-client loop-state decision opts]
  (resume* chat-client loop-state decision opts async/inline run-tool-loop))

(defn resume-async
  "同 `resume`，但立刻返回 `CompletableFuture`，延续跑在虚拟线程上。

   HITL 的第二段（人答复之后接着跑）在 web 场景与首段同样长——首段异步了、
   这段还阻塞 HTTP 线程就没有意义。语义与 `resume` 逐字相同（同一份 `resume*`）。"
  ^java.util.concurrent.CompletableFuture
  [chat-client loop-state decision opts]
  (resume* chat-client loop-state decision opts async/vthread run-tool-loop-async))

(defn- resume-approval
  "审批暂停的延续（工具未执行）：按 decision/payload 组 resume-gate，
   执行批次后继续循环。

   **校验与批次执行整个进 `run`**：异步模式下 payload 校验抛出的异常要落进
   返回的 deferred（而不是同步抛给调用方——那会让 `chat-async` 的调用点
   突然需要 try/catch）。同步模式 `run` = `inline`，与拆分前逐字相同。"
  [chat-client loop-state decision opts run loop-fn]
  (let [{:keys [remaining]} loop-state
        gate (:tool-gate opts)
        callbacks (or (:callbacks opts) {})
        tctx (or (:context opts) (ctx/create))]
    (-> (run #(resume-approval-batch chat-client loop-state decision opts))
        (flt/fbind (fn [{:keys [messages records context]}]
                     (if (:loop/stop context)
                       ;; 续跑那一批里有工具请求停回合（失败保护 / 拒绝熔断）
                       (stop-result context messages records)
                       (loop-fn chat-client (resume-delta chat-client loop-state context messages)
                                remaining records context
                                gate
                                (build-chat-opts chat-client opts)
                                callbacks
                                (loop-policy opts :proceed))))))))

(defn- decision-kw
  "决定词归一：接受 keyword 或字符串（JSON 过来的是字符串）。"
  [d]
  (case (if (keyword? d) (name d) (str d))
    ("approved" "approve" "allow") :approved
    "reply" :reply
    :rejected))

(defn- per-call-decisions
  "payload `:decisions` → {call-id(字符串) {:decision kw :args? :message?}}（§15）。"
  [payload]
  (into {}
        (map (fn [[k v]]
               (let [v (if (map? v) v {:decision v})]
                 [(if (keyword? k) (name k) (str k))
                  (assoc v :decision (decision-kw (:decision v)))])))
        (:decisions payload)))

(defn check-decisions!
  "校验 resume payload 的逐件决定 `:decisions`（§15）：每条 :reply 都带 :message，
   且**答全、答对**——点名了不在待审批里的 id → ex-info `:interrupt-unknown`；
   有待审批的调用没答 → `:interrupt-incomplete`。没有 `:decisions` 时什么都不做。
   旧暂停态没有 :pending-ids 时以 [:pending-id] 为准。

   公开出来，是为了让调用方（simple-agent 的 resume）在**动暂停态之前**先校验：
   答错是调用方的输入错误，不该把暂停快照（连同整批待审批）清掉。"
  [loop-state payload]
  (let [{:keys [pending-id pending-ids]} loop-state
        per (per-call-decisions payload)]
    (doseq [[id d] per]
      (when (and (= :reply (:decision d)) (not (string? (:message d))))
        (throw (ex-info (str "调用 " id " 的 :reply 需要 :message") {:decision d}))))
    (when (seq per)
      (let [pending (set (or (seq pending-ids) (when pending-id [pending-id])))
            unknown (remove pending (keys per))
            missing (remove (set (keys per)) pending)]
        (when (seq unknown)
          (throw (ex-info (str "resume :decisions 里有未知的调用 id：" (vec unknown))
                          {:type :interrupt-unknown :unknown (vec unknown) :pending (vec pending)})))
        (when (seq missing)
          (throw (ex-info (str "resume :decisions 漏答了待审批调用：" (vec missing))
                          {:type :interrupt-incomplete :missing (vec missing) :pending (vec pending)})))))
    nil))

(defn- with-reject-note
  "resume 的 `:reject-note` 钩子（观澜反馈 2026-11-12 ②）：人拒绝 / 取消时交给模型的
   那句工具结果由宿主写。缺省（不配）仍是「已拒绝执行[：理由]」。

   钩子入参 `{:tool 名 :args 参数 :call-id id :reason 理由或nil :cancelled? bool :expired? bool}`，
   返回字符串即用它，返回 nil 回落缺省文案。`:cancelled?` 来自 payload（顶层或逐件），
   AG-UI 的 `resume[].status = cancelled` 由 server 置上；`:expired?` 是审批超时转的拒绝。
   **只管 resume 时人的决定**——策略 `:forbid` 的拒绝不经它（「用户拒绝」与「策略禁止」是两种处境）。"
  [gate note per payload]
  (fn [tc]
    (let [d (gate tc)]
      (if (or (= :reject d) (and (map? d) (contains? d :reject)))
        (let [pd (get per (:id tc))
              src (if (map? pd) pd payload)
              reason (when (map? d) (:reject d))
              text (note {:tool (str (:name tc)) :args (:args tc) :call-id (:id tc)
                          :reason reason
                          :cancelled? (boolean (:cancelled? src))
                          :expired? (boolean (:expired? payload))})]
          (if (string? text) {:reject reason :text text} d))
        d))))

(defn- resume-approval-batch
  "审批延续的**阻塞段**：校验 payload、组 resume-gate、执行工具批次。
   返回 {:messages :records :context}。

   逐件决定（§15，为 AG-UI「一次暂停多条审批 / 审批时改参数」预留）：payload
   `:decisions {call-id {:decision :approved|:rejected|:reply :args? :message?}}`
   点名的调用按各自决定（`:approved` + `:args` = 改参数后批准，**整体替换不合并**）；
   给了 `:decisions` 就必须覆盖全部待审批调用（漏答抛 `:interrupt-incomplete`、
   未知 id 抛 `:interrupt-unknown`）。不给 `:decisions` 时按顶层 decision——与今天逐字相同。"
  [chat-client loop-state decision opts]
  (let [{:keys [tool-calls records pending-id pending-ids]} loop-state
        payload (:payload opts)
        per (per-call-decisions payload)
        _ (when (and (= :reply decision) (not (string? (:message payload))))
            (throw (ex-info ":reply 需要 opts :payload {:message \"...\"}"
                            {:payload payload})))
        _ (when (and (= :reply decision) (nil? pending-id))
            (throw (ex-info "loop-state 缺少 :pending-id（旧版本暂停态不支持 :reply）"
                            {})))
        _ (check-decisions! loop-state payload)
        ;; 编辑后批准：替换参数（顶层 :args 作用于 pending-id；逐件 :args 作用于各自）
        tool-calls (mapv (fn [tc]
                           (let [d (get per (:id tc))]
                             (cond
                               (and d (= :approved (:decision d)) (contains? d :args))
                               (assoc tc :args (:args d))

                               (and (nil? d) (= :approved decision) (= pending-id (:id tc))
                                    (:args payload))
                               (assoc tc :args (:args payload))

                               :else tc)))
                         tool-calls)
        tctx (or (:context opts) (ctx/create))
        gate (:tool-gate opts)
        callbacks (or (:callbacks opts) {})
        on-tool-result (:on-tool-result callbacks)
        pending-set (when (seq pending-ids) (set pending-ids))
        base-gate
        (case decision
          :approved (constantly :proceed)
          :reply    (fn [tc] (if (= pending-id (:id tc))
                               {:reply (:message payload)}
                               :proceed))
          ;; 缺省 :rejected：当时被叫停的那几件拒绝（可带理由）；旧暂停态没有
          ;; :pending-ids 时回落到「gate 判暂停的」
          (fn [tc] (if (if pending-set
                         (contains? pending-set (:id tc))
                         (and gate (pause-decision? (gate tc))))
                     (if-let [m (:message payload)] {:reject m} :reject)
                     :proceed)))
        resume-gate
        (if (empty? per)
          base-gate
          (fn [tc]
            (if-let [{d :decision m :message} (get per (:id tc))]
              (case d
                :approved :proceed
                :reply {:reply m}
                (if m {:reject m} :reject))
              (base-gate tc))))
        resume-gate (if-let [note (:reject-note opts)]
                      (with-reject-note resume-gate note per payload)
                      resume-gate)
        response (response/make-response :tool-calls tool-calls)
        batch-opts {:gate resume-gate
                    :tool-context tctx
                    :records records
                    :on-tool-result on-tool-result
                    :on-event (:on-event callbacks)
                    :metadata (:metadata callbacks)}
        {:keys [messages records context]}
        (if-let [tm (:tool-manager chat-client)]
          (tool-calling-manager/execute-tool-calls tm chat-client response batch-opts)
          (execute-batch-via nil chat-client tool-calls resume-gate tctx records
                             (batch-obs batch-opts)))]
    {:messages messages :records records :context context}))
