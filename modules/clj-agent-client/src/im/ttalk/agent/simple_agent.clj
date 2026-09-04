(ns im.ttalk.agent.simple-agent
  "SimpleAgent - 统一 Agent（合并原 kernel-agent / process-agent）

   对话历史由 ChatClient 的 ChatMemory store 按 conversation-id 自管（Memory Filter）。
   Agent 只持 conversation-id + 轻量控制状态。

   **Callback 体系**（独立于 chat-client filter）：
   通过 :callbacks map 注册 9 个回调，用于监控和控制 agent 执行过程：
     :on-turn-start   (fn [metadata])                  新 turn 开始
     :on-turn-end     (fn [metadata])                  turn 正常完成
     :on-turn-error   (fn [error metadata])            turn 出错
     :on-llm-call     (fn [messages metadata])         每次 LLM 调用前（在 react 层触发）
     :on-llm-result   (fn [response metadata])         每次 LLM 返回后（在 react 层触发）
     :on-tool-call    (fn [tool-name args])             tool 调用前；返回 {:interrupt reason} 触发中断
     :on-tool-result  (fn [tool-name result])          tool 执行后（在 react 层触发）
     :on-interrupt    (fn [interrupt-info metadata])   进入中断状态时
     :on-resume       (fn [interrupt-info metadata])   从中断状态恢复时

   pause/resume 为可选能力：**配置 callbacks :on-tool-call 即启用**。

   线程安全：单个 agent 实例不可被多线程并发 chat/resume。每个 agent 应绑定
   单一对话线程；并发请按会话各建一个 agent（共享底层持久 store + 各自
   :conversation-id 即可隔离）。

   使用示例：

   ;; 简单同步
   (def a (create-agent {:provider p :model \"glm-4.7\" :tools [#'get-weather]}))
   (chat a \"北京天气?\")    ;; => {:status :completed :text \"...\" :tool-calls-made [...]}

   ;; 带 callbacks 的可观测 agent
   (def a (create-agent {:provider p :tools [#'my-tool]
                         :callbacks {:on-turn-start  (fn [m] (println \"Turn\" (:turn-count m)))
                                     :on-tool-result (fn [n r] (println n \"=>\" r))
                                     :on-tool-call   (fn [n a] (when (risky? n) {:interrupt :need-approval}))}}))

   ;; 工具审批：on-tool-call 返回 {:interrupt reason} 触发暂停
   (def a (create-agent {:provider p :tools [#'delete-file]
                         :callbacks {:on-tool-call (fn [n _] (when (= :delete-file n) {:interrupt \"需要审批\"}))
                                     :on-interrupt (fn [info _] (println \"等待审批\" (:reason info)))}}))
   (let [r (chat a \"删除 /tmp/x\")]
     (when (= :paused (:status r))
       (resume a \"approved\")))"
  (:refer-clojure :exclude [reset!])
  (:require [clojure.string :as str]
            [im.ttalk.agent.filter.memory :as memory-filter]
            [im.ttalk.agent.chat-client :as chat-client]
            [im.ttalk.agent.tool-registry :as registry]
            [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.async :as async]
            [im.ttalk.agent.callbacks :as cb]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.error :as errors]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.pause :as pause]
            [im.ttalk.agent.react :as agent-loop]
            [im.ttalk.agent.common :as common]
            [im.ttalk.agent.streaming :as streaming]
            [taoensso.timbre :as log]))

(set! *warn-on-reflection* true)

;; 便捷再导出：取消令牌（亦可直接 require im.ttalk.agent.streaming）
(def make-cancel-token
  "创建取消令牌，传给 chat-stream 的 opts :cancel-token。见 im.ttalk.agent.streaming。"
  streaming/make-cancel-token)
(def request-cancel!
  "请求取消 chat-stream（取消上游 + 停止循环）。见 im.ttalk.agent.streaming。"
  streaming/request-cancel!)

;;; ============================================================
;;; 创建
;;; ============================================================

(defn- chat-client-memory-store
  "从已构建 chat-client 的 memory-filter 提取其绑定的 store（无 memory-filter 则 nil）。"
  [chat-client]
  (some #(when (= :memory (:name %)) (:store %)) (:filters chat-client)))

(defn- with-memory-filter
  "返回把 memory-filter(store) 挂到（或替换进）chat-client 的副本。
   store 为 nil 时只移除原有 memory-filter（无记忆 chat-client）。
   memory-filter 始终放最前，确保其他 filter 看到完整历史。"
  [k store]
  (let [others (vec (remove #(= :memory (:name %)) (:filters k)))]
    ;; 必须走 with-filters：直接 assoc :filters 会让 chat-client 预编译的 hooks 与之
    ;; 脱钩（filter-hooks 每次重编兜底，但那是白扔装配期成果）
    (flt/with-filters k
      (if store
        (into [(memory-filter/memory-filter store)] others)
        others))))

(defn create-agent
  "创建 Agent

   参数 opts:
   - :chat-client        预构建 ChatClient（提供则跳过构建）
   - :provider      ILLMProvider 实例
   - :model         模型名（默认 \"glm-4\"）
   - :max-tokens    最大 token（默认 4096）
   - :temperature   温度
   - :system-prompt 系统提示词
   - :tools         tool var / inline-tool map 列表
   - :memory        ChatMemory store（可选，默认 in-memory；false → 无记忆）
   - :pause-store   PauseStore（可选，见 im.ttalk.agent.pause）：暂停快照自动
                    持久化，进程重启后同 conversation-id + 同 store 重建 agent
                    即可 resume（跨重启 HITL；对话历史请配 SQLite ChatMemory）
   - :conversation-id 会话 ID（可选）
   - :max-iterations 最大工具循环次数（默认 10）
   - :tool-context   装配期塞进 ToolContext 的种子 map（可选，见返回值注释）
   - :on-pause      (fn [{:keys [pending-tool reason]}])（可选）：**配置即启用
                    pause/resume**——声明 `deftool {:sensitive true}` 的工具在执行
                    前自动暂停，等 `resume`。暂停发生时本回调被触发。
   - :callbacks     回调 map（:on-turn-start/:on-turn-end/:on-turn-error/:on-llm-call/
                              :on-llm-result/:on-tool-call/:on-tool-result/:on-interrupt/:on-resume）

   **两条 pause 启用路径，可并存**（缺省两条都不配 = 永不暂停，`chat` 只会返回
   `:completed`/`:error`，无人值守调用方不会收到意外的暂停态）：
   - `:on-pause` —— 声明式，`{:sensitive true}` 的工具一律暂停；
   - `:callbacks :on-tool-call` —— 命令式，返回 `{:interrupt reason}` 即暂停。
   两条都配时先问回调；回调放行的，若工具是敏感工具**仍暂停**——`:sensitive`
   是工具作者立的下限，不该被一个泛泛的回调放行掉。

   :chat-client 与 :memory 可独立同时指定，store 解析规则：
   - :memory store   → 用它（预构建 chat-client 上的 memory-filter 会被重挂到该 store）
   - :memory false   → 无记忆（预构建 chat-client 上的 memory-filter 会被移除）
   - :memory 缺省    → 复用 chat-client memory-filter 的 store；都没有则默认 in-memory

   Agent 层不暴露 chat-client filter（传入 :filters 会被忽略并警告）；
   需要 filter 请用 chat-client/build-chat-client 自建后经 :chat-client 传入。

   返回 Agent map"
  [opts]
  (when (contains? opts :filters)
    (log/warn "create-agent 不接受 :filters（agent 层只暴露 :callbacks）；"
              "如需 chat-client filter，请自建 chat-client 后以 :chat-client 传入"))
  (let [opts (dissoc opts :filters)
        prebuilt (:chat-client opts)
        kstore (when prebuilt (chat-client-memory-store prebuilt))
        store (cond
                (false? (:memory opts)) nil   ;; 显式 false → 无记忆（子 agent 隔离用）
                (:memory opts) (:memory opts) ;; 显式 store → 以用户指定为准
                kstore kstore                 ;; 复用预构建 chat-client 自带的 store
                :else  (memory/in-memory-store))
        k (cond
            (nil? prebuilt)
            (common/build-chat-client (assoc opts :memory store))

            (identical? store kstore)
            prebuilt   ;; store 未变，原样复用

            :else
            (do
              (when (and kstore store)
                (log/info "create-agent 同时收到 :chat-client 与不同的 :memory；"
                          "以 :memory 为准，chat-client memory-filter 已重挂到该 store"))
              (with-memory-filter prebuilt store)))]
    {:id              (str "agent-" (java.util.UUID/randomUUID))
     :chat-client          k
     :memory          store
     :pause-store     (:pause-store opts)
     :conversation-id (or (:conversation-id opts)
                          (str "agent-" (java.util.UUID/randomUUID)))
     :callbacks       (or (:callbacks opts) {})
     :state-atom      (atom {:status :idle :paused-state nil :turn-count 0 :run-id nil})
     ;; **装配期塞进 ToolContext 的种子**（每次 invoke 现铺，见 `tctx`）。
     ;; 给的是「工具要用、但不该由模型经参数传」的东西——今天唯一的用法是
     ;; 嵌套委派：`subagent/manager` 往这里塞 `:subagent/observer`，子 agent 里
     ;; 那把 `delegate-tool` 于是能把自己的 lane 挂到父 lane 底下。
     ;; ⚠️ 允许放活对象（函数之类）：它**不进状态快照**（`emit-state!` 按
     ;; `edn-safe?` 滤）也**不进暂停快照**（`pause/strip-unserializable`）。
     :tool-context    (:tool-context opts)
     :settings        (select-keys opts [:system-prompt :max-iterations :on-env-error :on-pause])}))

;;; ============================================================
;;; 内部辅助
;;; ============================================================

(defn- store [agent] (:memory agent))

(defn- tctx [agent]
  (ctx/with-conversation-id (ctx/create (:tool-context agent))
                            (:conversation-id agent)))

(defn- pause-save* [agent result]
  (when-let [ps (:pause-store agent)]
    (try (pause/pause-save! ps (:conversation-id agent)
                            (pause/snapshot (:conversation-id agent) result))
         (catch Throwable t
           (log/warn "暂停快照持久化失败（resume 仅本进程内可用）:" (.getMessage t))))))

(defn- pause-clear* [agent]
  (when-let [ps (:pause-store agent)]
    (try (pause/pause-clear! ps (:conversation-id agent))
         (catch Throwable _ nil))))

(defn- paused-state
  "当前暂停态：优先本进程 state-atom；没有则回落 PauseStore
   （跨重启恢复——重启后的新 agent 实例经此透明拿到快照）。"
  [agent]
  (or (:paused-state @(:state-atom agent))
      (when-let [ps (:pause-store agent)]
        (pause/pause-load ps (:conversation-id agent)))))

(defn- resume-context
  "resume 用的 ToolContext：恢复暂停快照中的累积 context（各轮 :writes 折叠
   结果），再钉上 conversation-id。此前这里用裸 (tctx agent)，暂停前累积的
   state slot 会被静默丢弃。"
  [agent paused]
  ;; `(:tool-context paused)` 是**恢复出来的累积 context**，
  ;; `(:tool-context agent)` 是**装配期的种子**（活对象，落不进快照，见 create-agent）
  ;; ——种子要盖在恢复值上面，否则跨 resume 的那一轮拿不到它。
  (ctx/with-conversation-id (merge (or (:tool-context paused) (ctx/create))
                                   (:tool-context agent))
                            (:conversation-id agent)))

(defn- build-meta
  "从 agent 当前状态构建回调元数据。"
  ([agent] (build-meta agent nil))
  ([agent run-id]
   (let [state @(:state-atom agent)]
     {:agent-id        (:id agent)
      :conversation-id (:conversation-id agent)
      :turn-count      (get state :turn-count 0)
      :run-id          (or run-id (:run-id state))
      :timestamp       (System/currentTimeMillis)})))

(defn- sensitive-tool?
  "工具是否声明 `deftool {:sensitive true}`。声明在装配期就汇进了 ToolMeta 的
   `:func-def`（`tool-registry/build-func-def`），这里只是一次查表。"
  [agent tool-call]
  (boolean (get-in (registry/tool-meta (:chat-client agent) (:name tool-call))
                   [:func-def :sensitive])))

(defn- gate-of
  "构建 gate fn（nil = 不启用暂停机制，`chat` 永远不返回 `:paused`）。

   **两条启用路径，可并存**：
   1. `:callbacks :on-tool-call` —— 返回 `{:interrupt reason}` 即暂停（细粒度，
      调用方按工具名/参数临场决定）；
   2. `:on-pause` —— 声明 `deftool {:sensitive true}` 的工具自动暂停（声明式，
      不必为每个工具写回调）。

   两条都配时先问回调：回调说暂停就暂停；回调放行的，若工具是敏感工具仍暂停
   ——`:sensitive` 是工具作者立的下限，不该被一个泛泛的回调放行掉。

   **这两条曾经只剩第 1 条**：callbacks 体系（2026-06）落地时 `:on-tool-call`
   版 gate **替换**而非补充了 `:on-pause` 版，`:on-pause` 一并从 `:settings` 的
   select-keys 里掉了。于是 README「方式二」、`deftool {:sensitive true}` 的
   文档、`docs/unified-invoke-agent.md`（状态 ✅ 已实施，明写「gate 仅靠
   :on-pause」）三处同时变成幽灵——**而且没有任何运行期症状**：不暂停的 agent
   照跑，只是敏感工具直接执行了。2026-08-25 修复并补测试钉住。"
  [agent]
  (let [on-tool-call (get-in agent [:callbacks :on-tool-call])
        on-pause     (:on-pause (:settings agent))]
    (when (or on-tool-call on-pause)
      (fn [tc]
        (let [tool-name (let [n (:name tc)] (if (keyword? n) (name n) (str n)))
              cb-result (when on-tool-call
                          (try (on-tool-call tool-name (:args tc))
                               (catch Throwable _ nil)))]
          (cond
            (and (map? cb-result) (:interrupt cb-result)) :pause
            (and on-pause (sensitive-tool? agent tc))     :pause
            :else                                          :proceed))))))

(defn- sys-prompts
  "本轮的 system 段。

   两个键**语义相反，别混**：
   - `:system-prompt`        **覆盖**——opts 压 settings，一如既往（agent 的人设）
   - `:extra-system-prompts` **追加**——一串字符串，接在人设后面

   追加位是给「每轮由调用方带上来的一段上下文」用的：AG-UI 的
   `RunAgentInput.context`（前端 `useAgentContext` 注册的那些）就是这个形状，
   它是**这一轮**的环境说明，不该把 agent 自己的人设顶掉。

   **不落 ChatMemory**：`react/build-chat-opts` 只把这些段落拼进本次请求的
   system，历史里一个字都不留。所以下一轮不带就自动消失——turn 级，正是
   前端上下文该有的生命周期（同 `:state` 的取舍，§7.1）。"
  [agent opts]
  (let [base (or (:system-prompt opts) (:system-prompt (:settings agent)))
        extra (->> (:extra-system-prompts opts)
                   (keep #(let [t (some-> % clojure.core/str str/trim)]
                            (when (seq t) t))))]
    (not-empty
     (into (if base [{:role "system" :content base}] [])
           (map (fn [t] {:role "system" :content t}))
           extra))))

(defn- env-error-policy
  "环境类工具失败的屏障策略：显式配置优先；缺省——配置了 :on-tool-call
   （HITL 已启用，宿主会处理 :paused）的 agent 用 :pause，否则 :proceed
   （错误结果照常交给模型，不会让无人值守调用方收到意外的暂停态）。"
  [agent opts]
  (or (:on-env-error opts)
      (:on-env-error (:settings agent))
      (if (gate-of agent) :pause :proceed)))

(defn- cancel-pending!
  "未-resume 保护：暂停态下开新对话时，重置控制状态并清持久化快照。
   历史里悬空 tool_use 的配对由 loop/invoke 入口自愈完成。"
  [agent]
  (when (or (= :paused (:status @(:state-atom agent)))
            (some? (paused-state agent)))
    (swap! (:state-atom agent) assoc :status :idle :paused-state nil)
    (pause-clear* agent)))

(defn- finalize
  "把 loop/invoke|resume 的结果写入 state-atom 并标准化返回，同时触发 turn 级别回调。"
  [agent result]
  (let [callbacks (:callbacks agent)
        meta (build-meta agent)]
    (case (:status result)
      :completed
      (do
        (swap! (:state-atom agent) #(-> %
                                         (assoc :status :completed :paused-state nil :run-id nil)
                                         (update :turn-count (fnil inc 0))))
        (pause-clear* agent)   ;; 循环已越过暂停点，持久化快照随之失效
        (cb/invoke callbacks :on-turn-end (build-meta agent))
        {:status :completed
         :text (get-in result [:response :text])
         :tool-calls-made (:tool-calls-made result)})

      :paused
      (do
        (swap! (:state-atom agent) assoc :status :paused :paused-state result :run-id nil)
        (pause-save* agent result)   ;; 暂停快照自动持久化（配置 :pause-store 时）
        (cb/invoke callbacks :on-interrupt
                   {:pending-tool (:pending-tool result)
                    :reason (:pause-reason result)}
                   meta)
        ;; `:on-pause` 是 callbacks 体系之前的暂停通知入口，与 :on-interrupt 并存
        ;; （README「方式二」教的就是它）。两者都配则都触发，顺序：先内层后外层。
        (when-let [on-pause (:on-pause (:settings agent))]
          (try (on-pause {:pending-tool (:pending-tool result)
                          :reason (:pause-reason result)})
               (catch Throwable t
                 (log/warn "on-pause 回调抛异常（已吞，不影响暂停本身）:" (.getMessage t)))))
        {:status :paused
         :text nil
         :pause-reason (:pause-reason result)
         :pending-tool (:pending-tool result)
         :tool-calls-made (:tool-calls-made result)})

      :cancelled
      (do
        (swap! (:state-atom agent) assoc :status :idle :paused-state nil :run-id nil)
        (pause-clear* agent)
        {:status :cancelled
         :text (get-in result [:response :text])
         :tool-calls-made (:tool-calls-made result)})

      :error
      (do
        (swap! (:state-atom agent) assoc :status :error :paused-state nil :run-id nil)
        (pause-clear* agent)
        (cb/invoke callbacks :on-turn-error (:error result) meta)
        {:status :error
         :text nil
         :error (:error result)
         :tool-calls-made (:tool-calls-made result)})

      (do
        (swap! (:state-atom agent) assoc :status :error :paused-state nil :run-id nil)
        {:status :error
         :text nil
         :error (errors/error :provider-error
                              (str "未知的 loop 结果状态: " (:status result))
                              {:context result})
         :tool-calls-made (:tool-calls-made result)}))))

(defn- error-result
  "Throwable → {:status :error ...}。**`Error`（OOM 等）原样重抛**——只兜 Exception，
   与拆分前的 `(catch Exception e)` 逐字同义。同步与异步两条路径共用。"
  [t]
  (cond
    (instance? clojure.lang.ExceptionInfo t)
    {:status :error
     :error (errors/exception->error t)
     :tool-calls-made (:tool-calls-made (ex-data t))}

    (instance? Exception t)
    {:status :error
     :error (errors/exception->error t)}

    :else (throw t)))

(defn- run-loop
  "执行 loop 调用并捕获异常为 {:status :error}，再交给 finalize 统一处理。"
  [agent f]
  (finalize agent (try (f) (catch Throwable t (error-result t)))))

(defn- run-loop-async
  "`run-loop` 的异步孪生：f 返回 deferred，finalize 在其完成后（工作线程上）执行。

   与 `run-loop` 同义——`fcatch` 既接住 f **同步**抛出的异常（如 invoke-async
   的前置校验），也接住 error channel 上的失败；`error-result` 两边共用，
   故 `Error` 照样逃逸。返回 deferred<标准化结果>。

   **回调线程**：`:on-turn-end` / `:on-interrupt` 等 turn 级回调在虚拟线程上触发，
   不在调用线程——回调内若要碰 UI/请求作用域的东西，自行切回。"
  [agent f]
  (flt/fcatch
    (flt/fmap (f) #(finalize agent %))
    (fn [t] (finalize agent (error-result t)))))

(defn- start-turn!
  "chat / chat-stream / chat-async 共用的开场：取消未 resume 的暂停、登记 run-id、
   发 `:on-turn-start`。返回 run-id。"
  [agent]
  (cancel-pending! agent)
  (let [run-id (str (java.util.UUID/randomUUID))]
    (swap! (:state-atom agent) assoc :run-id run-id)
    (cb/invoke (:callbacks agent) :on-turn-start (build-meta agent run-id))
    run-id))

(def default-max-iterations
  "工具循环的缺省上限。提成常量是为了**装配方能如实上报**——`/info` 的
   `capabilities.execution.maxIterations` 要报这个数，抄一份魔数过去迟早对不上。"
  10)

(def ^:private loop-passthrough-keys
  "循环认识、由调用方逐次决定的键——**四个入口共用一份透传**。

   此前没有这份名单：`chat-stream` 在 `build-invoke-opts` **之后**手动 assoc
   `:on-token` / `:cancel-token`，`chat-async` 没补，`resume-prep` 连 opts 位都没有。
   于是异步入口不能流式也不能取消，HITL 第二段（审批后的续跑，往往正是最终答案）
   两样都没有。加键时往这里加，别再各补各的。"
  [:on-token :cancel-token])

(defn- passthrough
  "把 `loop-passthrough-keys` 里**实际出现**的键并进 loop opts（nil 不覆盖）。"
  [loop-opts opts]
  (reduce (fn [m k] (if (some? (get opts k)) (assoc m k (get opts k)) m))
          loop-opts loop-passthrough-keys))

(defn- build-invoke-opts
  "构建传给 agent-loop/invoke 的 opts，含 callbacks（带 metadata）。"
  [agent run-id opts]
  (let [meta (build-meta agent run-id)
        ;; 把 metadata 嵌入 callbacks，供 react 层 on-llm-call 等使用
        callbacks-with-meta (assoc (:callbacks agent) :metadata meta)]
    (-> (cond-> {:context (tctx agent)
                 :tool-gate (gate-of agent)
                 :callbacks callbacks-with-meta
                 :on-env-error (env-error-policy agent opts)
                 :max-iterations (or (:max-iterations opts)
                                     (:max-iterations (:settings agent))
                                     default-max-iterations)}
          (:tool-choice opts) (assoc :tool-choice (:tool-choice opts))
          (sys-prompts agent opts) (assoc :system-prompts (sys-prompts agent opts)))
        (passthrough opts))))

;;; ============================================================
;;; 公开 API
;;; ============================================================

(defn chat
  "对话。返回 {:status :completed :text ...} 或 {:status :paused ...}"
  ([agent message] (chat agent message nil))
  ([agent message opts]
   (let [run-id (start-turn! agent)]
     (run-loop agent
       #(agent-loop/invoke (:chat-client agent) (store agent) [(msg/user message)]
          (build-invoke-opts agent run-id opts))))))

(defn chat-async
  "同 `chat`，但**立刻返回 `CompletableFuture`**，整个 turn 跑在虚拟线程上。

   给 Ring / Luminus 异步 handler 用：

   ```clojure
   (defn handler [request respond raise]
     (-> (agent/chat-async a (get-in request [:body-params :message]))
         (flt/fmap (fn [r] {:status 200 :body {:text (:text r)}}))
         (async/on-complete respond raise)))
   ```

   返回值形状与 `chat` 逐字相同（`:completed` / `:paused` / `:error`），只是被
   deferred 包了一层：`async/join` 阻塞取、`flt/fmap` 组合、`async/on-complete`
   接回调。turn 级回调在工作线程上触发（见 `run-loop-async`）。

   **opts 与 `chat-stream` 同源**：传 `:on-token` 即异步流式，传 `:cancel-token`
   即可从别的线程取消（见 `loop-passthrough-keys`）。

   ⚠️ agent 的 state-atom 是单会话状态机：**同一个 agent 上不要并发多个
   chat-async**（`start-turn!` 会互相踩 run-id / 暂停态）。web 场景下每个会话
   一个 agent 实例，或自行串行化。"
  (^java.util.concurrent.CompletableFuture [agent message] (chat-async agent message nil))
  (^java.util.concurrent.CompletableFuture [agent message opts]
   (let [run-id (start-turn! agent)]
     (run-loop-async agent
       #(agent-loop/invoke-async (:chat-client agent) (store agent) [(msg/user message)]
          (build-invoke-opts agent run-id opts))))))

(defn chat-stream
  "流式对话。`on-token` 接收 {:token / :reasoning-token ...}（增量 token，需全文请自行累积）。

   返回最终结果（与 chat 同形）。取消：opts 传 `:cancel-token`。

   **`chat-async` 也吃 `:on-token`**（opts 透传，见 `loop-passthrough-keys`）——
   要「异步 + 流式 + 可取消」用 `(chat-async a msg {:on-token f :cancel-token t})`。"
  ([agent message on-token] (chat-stream agent message on-token nil))
  ([agent message on-token opts]
   (let [run-id (start-turn! agent)]
     (run-loop agent
       #(agent-loop/invoke (:chat-client agent) (store agent) [(msg/user message)]
          (build-invoke-opts agent run-id (assoc opts :on-token on-token)))))))

(defn- resume-prep
  "resume / resume-async 共用的前置：校验暂停态、翻译 decision、登记 run-id、
   发 `:on-resume`，返回 `{:ls :loop-decision :opts}`（喂给 react/resume(-async)）。

   `opts` 与 `chat` 侧同义（`:on-token` / `:cancel-token` / `:system-prompt` /
   `:on-env-error`），经 `passthrough` 并进 loop opts。"
  [agent decision payload opts]
  (let [paused (paused-state agent)
        _ (when-not paused
            (throw (ex-info "Agent 未处于暂停状态"
                            {:status (:status @(:state-atom agent))})))
        ls (:loop-state paused)
        env? (= :env-retry (:phase ls))
        reply? (contains? #{"reply" :reply} decision)
        _ (when (and env? reply?)
            (throw (ex-info "环境类暂停不支持 :reply（用 \"retry\" 或 \"proceed\"）" {})))
        approved? (contains? #{"approved" :approved "retry" :retry} decision)
        loop-decision (cond
                        env?    (if approved? :retry :proceed)
                        reply?  :reply
                        approved? :approved
                        :else   :rejected)
        run-id (str (java.util.UUID/randomUUID))
        meta (build-meta agent run-id)
        callbacks (:callbacks agent)]
    (swap! (:state-atom agent) assoc :run-id run-id)
    (cb/invoke callbacks :on-resume
               (cond-> {:approved? approved? :decision loop-decision}
                 payload (assoc :payload payload))
               meta)
    {:ls ls
     :loop-decision loop-decision
     :opts (-> (cond-> {:context (resume-context agent paused)
                        :tool-gate (gate-of agent)
                        :on-env-error (env-error-policy agent opts)
                        :callbacks (assoc callbacks :metadata meta)}
                 payload (assoc :payload payload)
                 (sys-prompts agent opts) (assoc :system-prompts (sys-prompts agent opts)))
               (passthrough opts))}))

(defn paused?
  "是否处于暂停态（本进程 state-atom 或 PauseStore 中的持久化快照）。"
  [agent]
  (or (= :paused (:status @(:state-atom agent)))
      (some? (paused-state agent))))

(defn resume
  "恢复暂停的 Agent（本进程暂停或跨重启的持久化暂停均可）。

   审批暂停：
   - \"approved\"/:approved 批准；payload 可带 {:args 新参数}（编辑后批准，
     pending 工具以替换后的参数执行）
   - \"reply\"/:reply 答复即结果（ask-user 语义）：payload 必带 {:message 答复}，
     pending 工具不执行、答复直接作为其结果回模型
   - 其余 → 拒绝；payload 可带 {:message 理由}（结果「已拒绝执行：<理由>」，
     模型直接拿到原因，省一轮干猜）

   环境类暂停（:env-retry）：\"retry\"/:retry 或 \"approved\"/:approved 表示
   环境已修复、重跑失败工具；其余 → 错误结果交给模型（不支持 :reply）。

   resume 的 ToolContext 恢复自暂停快照（各轮 :writes 的累积折叠结果保留）。

   4-arity 的 `opts` 与 `chat` 侧同义，**尤其是 `:on-token` / `:cancel-token`**：
   HITL 第二段（审批之后的续跑）往往正是最终答案那段，不给这两个键，前端就只能
   干等若干秒再一次性收到整段文字，期间「停止」也按不动。
   `payload` 是**用户答复**的载荷（`:args` / `:message`），传输选项走 `opts`
   ——两件事不挤一个槽。"
  ([agent decision] (resume agent decision nil nil))
  ([agent decision payload] (resume agent decision payload nil))
  ([agent decision payload opts]
   (let [{:keys [ls loop-decision opts]} (resume-prep agent decision payload opts)]
     (run-loop agent
       #(agent-loop/resume (:chat-client agent) ls loop-decision opts)))))

(defn resume-async
  "同 `resume`，但立刻返回 `CompletableFuture`，延续跑在虚拟线程上。

   HITL 的第二段（人答复之后接着跑）与第一段一样长——首段用了 `chat-async`，
   这段还阻塞 HTTP 线程就白费了。返回值形状同 `resume`；4-arity 的 `opts`
   见 `resume`（`:on-token` / `:cancel-token` 在这里同样成立）。"
  (^java.util.concurrent.CompletableFuture [agent decision] (resume-async agent decision nil nil))
  (^java.util.concurrent.CompletableFuture [agent decision payload] (resume-async agent decision payload nil))
  (^java.util.concurrent.CompletableFuture [agent decision payload opts]
   (let [{:keys [ls loop-decision opts]} (resume-prep agent decision payload opts)]
     (run-loop-async agent
       #(agent-loop/resume-async (:chat-client agent) ls loop-decision opts)))))

(defn reset!
  "清空会话历史、持久化暂停快照并重置控制状态"
  [agent]
  (when-let [s (store agent)]
    (memory/mem-clear s (:conversation-id agent)))
  (pause-clear* agent)
  (clojure.core/reset! (:state-atom agent) {:status :idle :paused-state nil :turn-count 0 :run-id nil})
  nil)

(defn get-history
  "获取该会话的完整中立消息历史"
  [agent]
  (if-let [s (store agent)]
    (memory/mem-get s (:conversation-id agent))
    []))

;; 工作消息等同完整历史（无双轨）
(def get-messages get-history)
