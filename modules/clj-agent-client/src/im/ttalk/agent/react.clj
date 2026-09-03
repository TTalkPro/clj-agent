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
   （system-prompt 合并 + 工具 schema 过滤 + tool-choice）。"
  [chat-client opts]
  (let [system-prompts (or (:system-prompts opts) [])
        sp-str (when (seq system-prompts)
                 (->> system-prompts (map :content) (clojure.string/join "\n")))
        tool-schemas (filter-tools-by-tags chat-client opts)
        tool-choice (or (:tool-choice opts) :auto)]
    (cond-> {:tools tool-schemas :tool-choice tool-choice}
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

(defn- invoke-one
  "执行单个 tool call（含 gate reject 分支）。异常折为错误结果，不逃逸。
   返回 {:tc tc :value str (:writes {k v}) (:error {:class :message})
         (:rejected? true)}——
   错误/拒绝的结果没有 :writes，reduce 时自动跳过（单工具事务性）；
   :error 携带故障类别，供屏障处策略路由（S2）。"
  [chat-client tc decision tool-context on-tool-result]
  (cond
    (= :reject decision)
    {:tc tc :value "已拒绝执行" :rejected? true}

    ;; 拒绝带理由：模型直接拿到原因，省一轮干猜
    (and (map? decision) (contains? decision :reject))
    {:tc tc :value (str "已拒绝执行：" (:reject decision)) :rejected? true}

    ;; 答复即结果（ask-user 语义）：不执行工具，载荷直接作为工具结果回模型
    (and (map? decision) (contains? decision :reply))
    {:tc tc :value (str (:reply decision)) :replied? true}

    :else
    (let [fn-key (keyword (:name tc))
          {:keys [value writes error]}
           (try (invoke-with-retry chat-client fn-key (:args tc) tool-context)
                (catch Throwable t
                  (let [{:keys [message class]} (err/contain-throwable t)]
                    {:value (str "错误: " message)
                     :error {:class class :message message}})))]
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
  [chat-client tool-calls decisions tool-context on-tool-result]
  (mapv (fn [tc d] (invoke-one chat-client tc d tool-context on-tool-result))
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
  [^ExecutorService executor chat-client tool-calls decisions tool-context on-tool-result]
  (let [futs (mapv (fn [tc d]
                     (.submit executor
                              ^Callable
                              (bound-fn* (fn [] (invoke-one chat-client tc d tool-context
                                                            on-tool-result)))))
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
                               {:id      (:id tc)
                                :name    (:name tc)
                                :class   (:class error)
                                :message (:message error)
                                :tc      tc})))
                     results)
     :context  context}))

(defn- execute-batch-via
  "map + 屏障骨架，三个 manager 实现共用。

   executor 为 nil → 全程内联（Sequential 引擎：从不构造 Future）。
   executor 非 nil → 并行提交，但下列两种情形仍退化为内联：
   - 批内任一工具声明 :serial（整批退化按序，声明级契约，manager 不得违反）
   - 批内只有一个调用（并行无收益）"
  [executor chat-client tool-calls gate tool-context init-records on-tool-result]
  (let [decisions (gate-decisions gate tool-calls)
        serial?   (boolean (some #(registry/serial-tool? chat-client (:name %)) tool-calls))
        results   (if (or (nil? executor) serial? (<= (count tool-calls) 1))
                    (run-inline chat-client tool-calls decisions tool-context on-tool-result)
                    (run-on-executor executor chat-client tool-calls decisions
                                     tool-context on-tool-result))]
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
          :errors [{:id :name :class :message :tc} ...]}
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
                           (:on-tool-result opts))))))

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
                           (:on-tool-result opts))))))

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
                           (:on-tool-result opts)))))

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

(defn- env-pause
  "构造环境类错误的暂停返回值（屏障处策略钩子，S2）。
   批次已执行完且结果/写折叠均已落定；暂停发生在「结果交给模型之前」。
   resume 决策：:retry（环境已修复，重跑失败调用）| :proceed（错误结果交给模型）。

   **`:loop-state` 只放可 EDN 往返的值——尤其不放 record**：PauseStore 存档时
   它不走 `pause/strip-unserializable`（那只剥 tool-context），record 会让
   `sqlite-pause-store` 的 `pause-load` 抛而 in-memory 毫发无伤，即「单进程测试
   全绿、重启后 resume 崩」。护栏见 `pause-test/loop-state-edn-roundtrip-test`。"
  [env-errors batch-messages records remaining tctx]
  {:status :paused
   :pause-reason (str "环境类错误，需人工介入: "
                      (clojure.string/join "; "
                        (map #(str (:name %) ": " (:message %)) env-errors)))
   :loop-state {:phase          :env-retry
                :batch-messages batch-messages
                :failed-calls   (mapv :tc env-errors)
                :remaining      remaining
                :records        records}
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
        remaining* (volatile! remaining)
        records*   (volatile! records)
        iteration-terminal
        (fn [ireq]
          (let [delta (:messages ireq)
                tctx  (:context ireq)]
            (if (streaming/cancelled? token)
              {:status :cancelled :tool-context tctx :tool-calls-made @records*}
              (do
                ;; 每次 LLM 调用前触发（观察用，不影响流程）
                (cb/invoke callbacks :on-llm-call delta meta)
                ;; 唯一的异步点：LLM 调用。其后整段（工具批次、屏障、收尾判定）
                ;; 是 fmap 的续延——同步下当场执行，异步下在 LLM 完成时执行。
                (flt/fmap
                 (chat-fn chat-client delta (assoc chat-opts :context tctx) token)
                 (fn [{:keys [response context]}]
                  (let [tctx context
                      calls (response/response-tool-calls response)]
                  ;; 每次 LLM 返回后触发（观察用，不影响流程）
                  (cb/invoke callbacks :on-llm-result response meta)
                  (cond
                    (streaming/cancelled? token)
                    {:status :cancelled :response response
                     :tool-context tctx :tool-calls-made @records*}

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
                          paused-call (when gate-cache
                                        (first (filter #(= :pause (get gate-cache (:id %) :proceed)) calls)))]
                      (cond
                        (some? paused-call)
                        {:status :paused
                         :pause-reason (str "需要审批: " (:name paused-call))
                         ;; 批次尚未执行，故这里**预扣**一次：resume 时执行它就是那一次消耗。
                         ;; :loop-state 只放可 EDN 往返的值（不放 record）——理由同
                         ;; env-pause 的 docstring；护栏是 loop-state-edn-roundtrip-test
                         :loop-state {:tool-calls calls :remaining (dec @remaining*)
                                      :records @records*
                                      :pending-id (:id paused-call)}   ;; resume payload 定位用
                         :pending-tool {:name (:name paused-call)
                                        :args (:args paused-call)
                                        :tool-call paused-call}
                         :tool-calls-made @records*
                         :tool-context tctx}

                        (<= @remaining* 0)
                        (throw (ex-info "工具调用循环次数超过上限（max-iterations）"
                                        {:reason :max-iterations-exceeded
                                         :tool-call-count (count @records*)
                                         :tool-calls-made @records*}))

                        :else
                        (let [batch-opts {:gate cached-gate
                                          :tool-context tctx
                                          :records @records*
                                          :on-tool-result on-tool-result}
                              {:keys [messages records context errors]}
                              (if-let [tm (:tool-manager chat-client)]
                                (tool-calling-manager/execute-tool-calls tm chat-client response batch-opts)
                                (execute-batch chat-client calls cached-gate tctx @records* on-tool-result))
                              ;; 批次真跑了 → 当场记账。filter 重入会再跑一次，也再记一次。
                              _ (vreset! records* records)
                              _ (vswap! remaining* dec)
                              env-errors (when (= :pause (:on-env-error policy))
                                           (filterv #(= :environment (:class %)) errors))]
                          (cond
                            ;; 屏障处策略钩子：环境类失败 → 带一致快照暂停等人
                            (seq env-errors)
                            (env-pause env-errors messages records @remaining* context)

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
                            {:status :continue :messages messages :context context}))))))))))))
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

(defn- chat-client-memory-store
  "取 chat-client 上 memory filter 绑定的 store（filter 刻意暴露 :store）。
   没挂 memory filter → nil（此时工具结果本就不落库，直接返回也无需补）。"
  [chat-client]
  (some #(when (= :memory (:name %)) (:store %)) (:filters chat-client)))

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
        callbacks (or (:callbacks opts) {})
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
                                     {:on-env-error (or (:on-env-error opts) :proceed)})
                            (flt/fmap #(persist-direct-messages! % chat-client conv-id))))
        turn-chain ((:turn (flt/filter-hooks chat-client)) turn-terminal)]
    ;; heal 也走 run：异步模式下它是 store IO，不该占住调用线程
    (flt/fcatch
      (-> (run (fn [] (heal-dangling-tool-calls! store conv-id)))
          (flt/fbind (fn [_] (turn-chain {:messages messages :context init-ctx})))
          (flt/fmap (fn [result]
                      (when (and ephemeral? store (= :completed (:status result)))
                        (memory/mem-clear store conv-id))
                      result)))
      (fn [t]
        (when (and ephemeral? store) (memory/mem-clear store conv-id))
        (throw t)))))

(defn invoke
  "工具调用循环主入口（统一循环）。**同步**：阻塞到出结果。

   参数:
   - chat-client:   ChatClient 实例（需配置 ChatModel）
   - store:    ChatMemory store（与 chat-client 上 memory-filter 同一实例；用于 heal/临时清理）
   - messages: 本轮新消息（中立消息）
   - opts:     {:context :system-prompts :max-iterations :tool-choice :tool-gate :tags/:exclude-tags
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

(defn- resume-env
  "从 :env-retry 暂停恢复（批已执行、环境类失败）。
   decision :retry   → 环境已修复：重跑失败调用，结果按 id 替换进原批次消息；
                       若仍有环境类失败则再次暂停（同 phase）。
   decision 其他     → :proceed：原错误结果照常交给模型。

   `run` / `loop-fn` 见 `invoke*`：重跑那一批是**阻塞**的工具执行，异步模式下
   进虚拟线程；随后的循环由 `loop-fn` 决定同步还是 deferred。"
  [chat-client {:keys [batch-messages failed-calls remaining records]} decision opts run loop-fn]
  (let [tctx (or (:context opts) (ctx/create))
        gate (:tool-gate opts)
        callbacks (or (:callbacks opts) {})
        policy {:on-env-error (or (:on-env-error opts) :pause)}
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
                         (env-pause env-errors merged records remaining context)
                         (loop-fn chat-client merged remaining records context
                                  gate chat-opts callbacks policy)))))
      (loop-fn chat-client batch-messages remaining records tctx
               gate chat-opts callbacks policy))))

(declare ^:private resume-approval resume-approval-batch)

(defn- resume*
  "resume / resume-async 的唯一实现；两者只差 `run` / `loop-fn` 两个注入项，见 `invoke*`。"
  [chat-client loop-state decision opts run loop-fn]
  (let [continuation
        (fn []
          (if (= :env-retry (:phase loop-state))
            (resume-env chat-client loop-state decision opts run loop-fn)
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
                                  {:on-env-error (or (:on-env-error opts) :proceed)}))
                       ;; 延续与重入都可能撞上 return-direct 收尾
                       (flt/fmap #(persist-direct-messages! % chat-client (ctx/conversation-id tctx)))))
        turn-chain ((:turn (flt/filter-hooks chat-client)) terminal)]
    (turn-chain {:resume? true :messages nil :context tctx})))

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
   - :reply    + {:message 答复}  → 答复即 pending 工具的结果（必填）

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
                     (loop-fn chat-client messages remaining records context
                              gate
                              (build-chat-opts chat-client opts)
                              callbacks
                              {:on-env-error (or (:on-env-error opts) :proceed)}))))))

(defn- resume-approval-batch
  "审批延续的**阻塞段**：校验 payload、组 resume-gate、执行工具批次。
   返回 {:messages :records :context}。"
  [chat-client loop-state decision opts]
  (let [{:keys [tool-calls records pending-id]} loop-state
        payload (:payload opts)
        _ (when (and (= :reply decision) (not (string? (:message payload))))
            (throw (ex-info ":reply 需要 opts :payload {:message \"...\"}"
                            {:payload payload})))
        _ (when (and (= :reply decision) (nil? pending-id))
            (throw (ex-info "loop-state 缺少 :pending-id（旧版本暂停态不支持 :reply）"
                            {})))
        ;; 编辑后批准：替换 pending 工具的参数
        tool-calls (if-let [new-args (and (= :approved decision) (:args payload))]
                     (mapv #(if (= pending-id (:id %)) (assoc % :args new-args) %)
                           tool-calls)
                     tool-calls)
        tctx (or (:context opts) (ctx/create))
        gate (:tool-gate opts)
        callbacks (or (:callbacks opts) {})
        on-tool-result (:on-tool-result callbacks)
        resume-gate
        (case decision
          :approved (constantly :proceed)
          :reply    (fn [tc] (if (= pending-id (:id tc))
                               {:reply (:message payload)}
                               :proceed))
          ;; 缺省 :rejected：gate 判敏感的拒绝（可带理由）
          (fn [tc] (if (and gate (= :pause (gate tc)))
                     (if-let [m (:message payload)] {:reject m} :reject)
                      :proceed)))
        response (response/make-response :tool-calls tool-calls)
        batch-opts {:gate resume-gate
                    :tool-context tctx
                    :records records
                    :on-tool-result on-tool-result}
        {:keys [messages records context]}
        (if-let [tm (:tool-manager chat-client)]
          (tool-calling-manager/execute-tool-calls tm chat-client response batch-opts)
          (execute-batch chat-client tool-calls resume-gate tctx records on-tool-result))]
    {:messages messages :records records :context context}))
