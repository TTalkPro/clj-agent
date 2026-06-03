(ns im.ttalk.agent.simpleagent
  "SimpleAgent - 统一 Agent（合并原 kernel-agent / process-agent）

   对话历史由 Kernel 的 ChatMemory store 按 conversation-id 自管（Memory Filter）。
   Agent 只持 conversation-id + 轻量控制状态。

   pause/resume 为可选能力：**配置 :on-pause 即启用**——遇到 :sensitive 工具
   会暂停并触发回调，之后用 resume 批准/拒绝。不配 :on-pause 则为简单同步模式。

   使用示例：

   ;; 简单同步
   (def a (create-agent {:provider p :model \"glm-4.7\" :tools [#'get-weather]}))
   (chat a \"北京天气?\")            ;; => {:status :completed :text \"...\" :tool-calls-made [...]}

   ;; 带敏感工具审批
   (def a (create-agent {:provider p :tools [#'delete-file]
                         :on-pause (fn [{:keys [reason pending-tool]}] ...)}))
   (let [r (chat a \"删除 /tmp/x\")]
     (when (= :paused (:status r))
       (resume a \"approved\")))"
  (:refer-clojure :exclude [reset!])
  (:require [im.ttalk.agent.core.kernel :as kernel]
            [im.ttalk.agent.core.kernel.context :as ctx]
            [im.ttalk.agent.core.kernel.tool :as tool]
            [im.ttalk.agent.core.llm.message :as msg]
            [im.ttalk.agent.core.memory :as memory]
            [im.ttalk.agent.simpleagent.common :as common]))

;;; ============================================================
;;; 创建
;;; ============================================================

(defn create-agent
  "创建 Agent

   参数 opts:
   - :kernel        预构建 Kernel（提供则跳过构建）
   - :provider      ILLMProvider 实例
   - :model         模型名（默认 \"glm-4\"）
   - :max-tokens    最大 token（默认 4096）
   - :temperature   温度
   - :system-prompt 系统提示词
   - :tools         tool var 列表
   - :filters       Filter 列表
   - :memory        ChatMemory store（可选，默认 in-memory；持久化见 core.memory.sqlite）
   - :conversation-id 会话 ID（可选）。提供则可配合持久 store 跨重启恢复该会话；
                      不提供则生成随机 UUID
   - :max-iterations 最大工具循环次数（默认 10）
   - :on-pause      暂停回调 (fn [{:keys [reason pending-tool]}])；配置即启用 pause/resume

   返回 Agent map"
  [opts]
  (let [k (common/ensure-kernel opts)]
    {:kernel          k
     :conversation-id (or (:conversation-id opts)
                          (str "agent-" (java.util.UUID/randomUUID)))
     :state-atom      (atom {:status :idle :paused-state nil})
     :settings        (select-keys opts [:system-prompt :max-iterations :on-pause])}))

;;; ============================================================
;;; 内部
;;; ============================================================

(defn- store [agent] (:memory (:kernel agent)))

(defn- tctx [agent]
  (ctx/with-conversation-id (ctx/create) (:conversation-id agent)))

(defn- gate-of
  "配置了 :on-pause 才返回 gate（敏感工具 → :pause），否则 nil（永不暂停）"
  [agent]
  (when (:on-pause (:settings agent))
    (let [k (:kernel agent)]
      (fn [tc]
        (if (some-> (kernel/find-function k (:name tc)) :tool-var tool/sensitive?)
          :pause :proceed)))))

(defn- sys-prompts [agent opts]
  (when-let [sp (or (:system-prompt opts) (:system-prompt (:settings agent)))]
    [{:role "system" :content sp}]))

(defn- cancel-pending!
  "未-resume 保护：若 agent 处于暂停态，开新对话前为待执行的 tool-calls
   补一条「已取消」中立结果，避免历史里留下悬空的 tool_use（严格 provider 会报错），
   并重置控制状态。"
  [agent]
  (when (= :paused (:status @(:state-atom agent)))
    (let [calls (get-in @(:state-atom agent) [:paused-state :loop-state :tool-calls])
          cancels (mapv (fn [tc]
                          (msg/tool-result (:id tc) (:name tc)
                                           "已取消（未审批，开始了新对话）"))
                        calls)]
      (when (seq cancels)
        (memory/mem-add (store agent) (:conversation-id agent) cancels))
      (clojure.core/reset! (:state-atom agent) {:status :idle :paused-state nil}))))

(defn- finalize
  "把 kernel/invoke|resume 的结果写入 state-atom 并标准化返回"
  [agent result]
  (case (:status result)
    :completed
    (do (clojure.core/reset! (:state-atom agent) {:status :completed :paused-state nil})
        {:status :completed
         :text (get-in result [:response :text])
         :tool-calls-made (:tool-calls-made result)})

    :paused
    (do (clojure.core/reset! (:state-atom agent) {:status :paused :paused-state result})
        (when-let [on-pause (:on-pause (:settings agent))]
          (on-pause {:reason (:pause-reason result)
                     :pending-tool (:pending-tool result)}))
        {:status :paused
         :text nil
         :pause-reason (:pause-reason result)
         :pending-tool (:pending-tool result)
         :tool-calls-made (:tool-calls-made result)})))

;;; ============================================================
;;; 公开 API
;;; ============================================================

(defn chat
  "对话。返回 {:status :completed :text ...} 或 {:status :paused ...}"
  ([agent message] (chat agent message nil))
  ([agent message opts]
   (cancel-pending! agent)   ;; 未-resume 保护：开新对话前清理悬空 tool_use
   (let [result (kernel/invoke (:kernel agent) [(msg/user message)]
                  (cond-> {:context (tctx agent)
                           :tool-gate (gate-of agent)
                           :max-iterations (or (:max-iterations opts)
                                               (:max-iterations (:settings agent)) 10)}
                    (:tool-choice opts) (assoc :tool-choice (:tool-choice opts))
                    (sys-prompts agent opts) (assoc :system-prompts (sys-prompts agent opts))))]
     (finalize agent result))))

(defn paused?
  [agent]
  (= :paused (:status @(:state-atom agent))))

(defn resume
  "恢复暂停的 Agent。decision = \"approved\"/:approved 批准，其余拒绝。"
  [agent decision]
  (when-not (paused? agent)
    (throw (ex-info "Agent 未处于暂停状态" {:status (:status @(:state-atom agent))})))
  (let [ls (:loop-state (:paused-state @(:state-atom agent)))
        approved? (or (= decision "approved") (= decision :approved))
        result (kernel/resume (:kernel agent) ls (if approved? :approved :rejected)
                 (cond-> {:context (tctx agent)
                          :tool-gate (gate-of agent)}
                   (sys-prompts agent nil) (assoc :system-prompts (sys-prompts agent nil))))]
    (finalize agent result)))

(defn reset!
  "清空会话历史并重置控制状态"
  [agent]
  (memory/mem-clear (store agent) (:conversation-id agent))
  (clojure.core/reset! (:state-atom agent) {:status :idle :paused-state nil})
  nil)

(defn get-history
  "获取该会话的完整中立消息历史"
  [agent]
  (memory/mem-get (store agent) (:conversation-id agent)))

;; 工作消息等同完整历史（无双轨）
(def get-messages get-history)
