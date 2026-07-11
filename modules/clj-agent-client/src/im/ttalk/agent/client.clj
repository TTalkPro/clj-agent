(ns im.ttalk.agent.client
  "SimpleAgent - 统一 Agent（合并原 kernel-agent / process-agent）

   对话历史由 Kernel 的 ChatMemory store 按 conversation-id 自管（Memory Filter）。
   Agent 只持 conversation-id + 轻量控制状态。

   **Callback 体系**（独立于 kernel filter）：
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
  (:require [im.ttalk.agent.advisor.memory :as memory-filter]
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

(defn- kernel-memory-store
  "从已构建 kernel 的 memory-filter 提取其绑定的 store（无 memory-filter 则 nil）。"
  [kernel]
  (some #(when (= :memory (:name %)) (:store %)) (:filters kernel)))

(defn- with-memory-filter
  "返回把 memory-filter(store) 挂到（或替换进）kernel 的副本。
   store 为 nil 时只移除原有 memory-filter（无记忆 kernel）。
   memory-filter 始终放最前，确保其他 filter 看到完整历史。"
  [kernel store]
  (let [others (vec (remove #(= :memory (:name %)) (:filters kernel)))]
    (assoc kernel :filters
           (if store
             (into [(memory-filter/memory-filter store)] others)
             others))))

(defn create-agent
  "创建 Agent

   参数 opts:
   - :kernel        预构建 Kernel（提供则跳过构建）
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
   - :callbacks     回调 map（:on-turn-start/:on-turn-end/:on-turn-error/:on-llm-call/
                              :on-llm-result/:on-tool-call/:on-tool-result/:on-interrupt/:on-resume）

   :kernel 与 :memory 可独立同时指定，store 解析规则：
   - :memory store   → 用它（预构建 kernel 上的 memory-filter 会被重挂到该 store）
   - :memory false   → 无记忆（预构建 kernel 上的 memory-filter 会被移除）
   - :memory 缺省    → 复用 kernel memory-filter 的 store；都没有则默认 in-memory

   Agent 层不暴露 kernel filter（传入 :filters 会被忽略并警告）；
   需要 filter 请用 kernel/build-kernel 自建后经 :kernel 传入。

   返回 Agent map"
  [opts]
  (when (contains? opts :filters)
    (log/warn "create-agent 不接受 :filters（agent 层只暴露 :callbacks）；"
              "如需 kernel filter，请自建 kernel 后以 :kernel 传入"))
  (let [opts (dissoc opts :filters)
        prebuilt (:kernel opts)
        kstore (when prebuilt (kernel-memory-store prebuilt))
        store (cond
                (false? (:memory opts)) nil   ;; 显式 false → 无记忆（子 agent 隔离用）
                (:memory opts) (:memory opts) ;; 显式 store → 以用户指定为准
                kstore kstore                 ;; 复用预构建 kernel 自带的 store
                :else  (memory/in-memory-store))
        k (cond
            (nil? prebuilt)
            (common/build-kernel (assoc opts :memory store))

            (identical? store kstore)
            prebuilt   ;; store 未变，原样复用

            :else
            (do
              (when (and kstore store)
                (log/info "create-agent 同时收到 :kernel 与不同的 :memory；"
                          "以 :memory 为准，kernel memory-filter 已重挂到该 store"))
              (with-memory-filter prebuilt store)))]
    {:id              (str "agent-" (java.util.UUID/randomUUID))
     :kernel          k
     :memory          store
     :pause-store     (:pause-store opts)
     :conversation-id (or (:conversation-id opts)
                          (str "agent-" (java.util.UUID/randomUUID)))
     :callbacks       (or (:callbacks opts) {})
     :state-atom      (atom {:status :idle :paused-state nil :turn-count 0 :run-id nil})
     :settings        (select-keys opts [:system-prompt :max-iterations :on-env-error])}))

;;; ============================================================
;;; 内部辅助
;;; ============================================================

(defn- store [agent] (:memory agent))

(defn- tctx [agent]
  (ctx/with-conversation-id (ctx/create) (:conversation-id agent)))

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
  (ctx/with-conversation-id (or (:tool-context paused) (ctx/create))
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

(defn- gate-of
  "构建 gate fn。仅当 callbacks :on-tool-call 存在时启用暂停机制。
   on-tool-call 返回 {:interrupt reason} 则暂停，否则放行。"
  [agent]
  (when-let [on-tool-call (get-in agent [:callbacks :on-tool-call])]
    (fn [tc]
      (let [tool-name (let [n (:name tc)] (if (keyword? n) (name n) (str n)))
            cb-result (try (on-tool-call tool-name (:args tc))
                           (catch Throwable _ nil))]
        (if (and (map? cb-result) (:interrupt cb-result))
          :pause
          :proceed)))))

(defn- sys-prompts [agent opts]
  (when-let [sp (or (:system-prompt opts) (:system-prompt (:settings agent)))]
    [{:role "system" :content sp}]))

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

(defn- run-loop
  "执行 loop 调用并捕获异常为 {:status :error}，再交给 finalize 统一处理。"
  [agent f]
  (finalize agent
            (try
              (f)
              (catch clojure.lang.ExceptionInfo e
                {:status :error
                 :error (errors/exception->error e)
                 :tool-calls-made (:tool-calls-made (ex-data e))})
              (catch Exception e
                {:status :error
                 :error (errors/exception->error e)}))))

(defn- build-invoke-opts
  "构建传给 agent-loop/invoke 的 opts，含 callbacks（带 metadata）。"
  [agent run-id opts]
  (let [meta (build-meta agent run-id)
        ;; 把 metadata 嵌入 callbacks，供 react 层 on-llm-call 等使用
        callbacks-with-meta (assoc (:callbacks agent) :metadata meta)]
    (cond-> {:context (tctx agent)
             :tool-gate (gate-of agent)
             :callbacks callbacks-with-meta
             :on-env-error (env-error-policy agent opts)
             :max-iterations (or (:max-iterations opts)
                                 (:max-iterations (:settings agent)) 10)}
      (:tool-choice opts) (assoc :tool-choice (:tool-choice opts))
      (sys-prompts agent opts) (assoc :system-prompts (sys-prompts agent opts)))))

;;; ============================================================
;;; 公开 API
;;; ============================================================

(defn chat
  "对话。返回 {:status :completed :text ...} 或 {:status :paused ...}"
  ([agent message] (chat agent message nil))
  ([agent message opts]
   (cancel-pending! agent)
   (let [run-id (str (java.util.UUID/randomUUID))]
     (swap! (:state-atom agent) assoc :run-id run-id)
     (cb/invoke (:callbacks agent) :on-turn-start (build-meta agent run-id))
     (run-loop agent
       #(agent-loop/invoke (:kernel agent) (store agent) [(msg/user message)]
          (build-invoke-opts agent run-id opts))))))

(defn chat-stream
  "流式对话。`on-token` 接收 {:token / :reasoning-token ...}（增量 token，需全文请自行累积）。

   返回最终结果（与 chat 同形）。取消：opts 传 `:cancel-token`。"
  ([agent message on-token] (chat-stream agent message on-token nil))
  ([agent message on-token opts]
   (cancel-pending! agent)
   (let [run-id (str (java.util.UUID/randomUUID))]
     (swap! (:state-atom agent) assoc :run-id run-id)
     (cb/invoke (:callbacks agent) :on-turn-start (build-meta agent run-id))
     (run-loop agent
       #(agent-loop/invoke (:kernel agent) (store agent) [(msg/user message)]
          (cond-> (build-invoke-opts agent run-id opts)
            true               (assoc :on-token on-token)
            (:cancel-token opts) (assoc :cancel-token (:cancel-token opts))))))))

(defn paused?
  "是否处于暂停态（本进程 state-atom 或 PauseStore 中的持久化快照）。"
  [agent]
  (or (= :paused (:status @(:state-atom agent)))
      (some? (paused-state agent))))

(defn resume
  "恢复暂停的 Agent（本进程暂停或跨重启的持久化暂停均可）。

   审批暂停：decision = \"approved\"/:approved 批准，其余拒绝。
   环境类暂停（:env-retry，工具环境失败）：decision = \"retry\"/:retry 或
   \"approved\"/:approved 表示环境已修复、重跑失败工具；其余 → 错误结果交给模型。

   resume 的 ToolContext 恢复自暂停快照（各轮 :writes 的累积折叠结果保留）。"
  [agent decision]
  (let [paused (paused-state agent)
        _ (when-not paused
            (throw (ex-info "Agent 未处于暂停状态"
                            {:status (:status @(:state-atom agent))})))
        ls (:loop-state paused)
        env? (= :env-retry (:phase ls))
        approved? (contains? #{"approved" :approved "retry" :retry} decision)
        loop-decision (if env?
                        (if approved? :retry :proceed)
                        (if approved? :approved :rejected))
        run-id (str (java.util.UUID/randomUUID))
        meta (build-meta agent run-id)
        callbacks (:callbacks agent)]
    (swap! (:state-atom agent) assoc :run-id run-id)
    (cb/invoke callbacks :on-resume {:approved? approved?} meta)
    (run-loop agent
      #(agent-loop/resume (:kernel agent) ls loop-decision
         (cond-> {:context (resume-context agent paused)
                  :tool-gate (gate-of agent)
                  :on-env-error (env-error-policy agent nil)
                  :callbacks (assoc callbacks :metadata meta)}
           (sys-prompts agent nil) (assoc :system-prompts (sys-prompts agent nil)))))))

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
