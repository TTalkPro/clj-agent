(ns im.ttalk.agent.client
  "SimpleAgent - 统一 Agent（合并原 kernel-agent / process-agent）

   对话历史由 Kernel 的 ChatMemory store 按 conversation-id 自管（Memory Filter）。
   Agent 只持 conversation-id + 轻量控制状态。

   pause/resume 为可选能力：**配置 :on-pause 即启用**——遇到 :sensitive 工具
   会暂停并触发回调，之后用 resume 批准/拒绝。不配 :on-pause 则为简单同步模式。

   线程安全：**单个 agent 实例不可被多线程并发 chat/resume**。它持有可变控制状态
   （:state-atom）与按 conversation-id 的历史，内部是 check-then-act + reset! 序列，
   并发调用会相互覆盖、历史交叉。每个 agent 应绑定单一对话线程；并发请按会话各建
   一个 agent（共享底层持久 store + 各自 :conversation-id 即可隔离）。

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
  (:require [im.ttalk.agent.kernel :as kernel]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.tool :as tool]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.error :as errors]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.react :as agent-loop]
            [im.ttalk.agent.common :as common]
            [taoensso.timbre :as log]))

;;; ============================================================
;;; 创建
;;; ============================================================

(defn- kernel-memory-store
  "从已构建 kernel 的 memory-filter 提取其绑定的 store（无 memory-filter 则 nil）。"
  [kernel]
  (some #(when (= :memory (:name %)) (:store %)) (:filters kernel)))

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
   - :memory        ChatMemory store（可选，默认 in-memory；持久化见 simpleagent.memory.sqlite）
   - :conversation-id 会话 ID（可选）。提供则可配合持久 store 跨重启恢复该会话；
                      不提供则生成随机 UUID
   - :max-iterations 最大工具循环次数（默认 10）
   - :on-pause      暂停回调 (fn [{:keys [reason pending-tool]}])；配置即启用 pause/resume
   - :on-error      错误回调 (fn [{:keys [error]}])（可选）；LLM/工具循环异常时触发，
                    chat/resume 返回 {:status :error :error {...}} 而非抛裸异常

   返回 Agent map"
  [opts]
  (let [;; store 由 agent 持有（kernel 不再持有 memory）；并以 memory-filter 形态挂进 kernel。
        ;; 关键：react 的 heal/clear 与 kernel memory-filter 的落库**必须是同一 store 实例**。
        ;; 若传入预构建 :kernel，绝不能另造一个 store——那会与 kernel 落库处脱节，
        ;; 导致第二轮起 LLM 看不到历史 / heal/clear 操作错误 store。改为复用 kernel 自带的 store。
        prebuilt (:kernel opts)
        kstore (when prebuilt (kernel-memory-store prebuilt))
        store (cond
                kstore kstore
                :else  (or (:memory opts) (memory/in-memory-store)))
        _ (when prebuilt
            (cond
              (and kstore (:memory opts) (not (identical? kstore (:memory opts))))
              (log/warn "create-agent 同时收到 :kernel 与不同的 :memory；以 kernel 自带 memory-filter 的 store 为准（实际落库处）")
              (nil? kstore)
              (log/warn "create-agent 收到的预构建 :kernel 未挂载 memory-filter；多轮对话历史不会被 kernel 持久化，有状态对话将退化")))
        k (or prebuilt (common/build-kernel (assoc opts :memory store)))]
    {:kernel          k
     :memory          store
     :conversation-id (or (:conversation-id opts)
                          (str "agent-" (java.util.UUID/randomUUID)))
     :state-atom      (atom {:status :idle :paused-state nil})
     :settings        (select-keys opts [:system-prompt :max-iterations :on-pause :on-error])}))

;;; ============================================================
;;; 内部
;;; ============================================================

(defn- store [agent] (:memory agent))

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
  "未-resume 保护：暂停态下开新对话时，只重置控制状态。
   历史里悬空 tool_use 的配对（补「已取消」结果）由 loop/invoke 入口自愈完成，
   故此处不再手动操作消息，避免重复。"
  [agent]
  (when (= :paused (:status @(:state-atom agent)))
    (clojure.core/reset! (:state-atom agent) {:status :idle :paused-state nil})))

(defn- finalize
  "把 loop/invoke|resume 的结果写入 state-atom 并标准化返回"
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
         :tool-calls-made (:tool-calls-made result)})

    ;; LLM/工具循环异常：归一化为错误结果，不向调用方抛裸异常
    :error
    (do (clojure.core/reset! (:state-atom agent) {:status :error :paused-state nil})
        (when-let [on-error (:on-error (:settings agent))]
          (on-error {:error (:error result)}))
        {:status :error
         :text nil
         :error (:error result)
         :tool-calls-made (:tool-calls-made result)})

    ;; 兜底：未知 status 不再让 case 抛 IllegalArgumentException
    (do (clojure.core/reset! (:state-atom agent) {:status :error :paused-state nil})
        {:status :error
         :text nil
         :error (errors/error :provider-error
                              (str "未知的 loop 结果状态: " (:status result))
                              {:context result})
         :tool-calls-made (:tool-calls-made result)})))

(defn- run-loop
  "执行 loop 调用并捕获异常为 {:status :error}，再交给 finalize 统一处理。
   保留 ex-info 里 react 携带的 :tool-calls-made。"
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

;;; ============================================================
;;; 公开 API
;;; ============================================================

(defn chat
  "对话。返回 {:status :completed :text ...} 或 {:status :paused ...}"
  ([agent message] (chat agent message nil))
  ([agent message opts]
   (cancel-pending! agent)   ;; 未-resume 保护：开新对话前清理悬空 tool_use
   (run-loop agent
     #(agent-loop/invoke (:kernel agent) (store agent) [(msg/user message)]
        (cond-> {:context (tctx agent)
                 :tool-gate (gate-of agent)
                 :max-iterations (or (:max-iterations opts)
                                     (:max-iterations (:settings agent)) 10)}
          (:tool-choice opts) (assoc :tool-choice (:tool-choice opts))
          (sys-prompts agent opts) (assoc :system-prompts (sys-prompts agent opts)))))))

(defn chat-stream
  "流式对话。`on-token` 接收 {:token / :reasoning-token / :accumulated ...}——
   ReAct 循环里每个 LLM 回合都流式（工具回合通常只产 tool_calls 无 :token，最终文本回合逐 token）。

   返回最终结果（与 chat 同形：{:status :completed :text ... :tool-calls-made [...]}）。
   对话历史在每个回合流结束时落库，与 chat **不分叉**。provider 不支持流式时由 service 回退同步
   并把全文作为单个 token emit。

   注意：单个 agent 实例不可并发（见 ns 线程安全说明）。"
  ([agent message on-token] (chat-stream agent message on-token nil))
  ([agent message on-token opts]
   (cancel-pending! agent)
   (run-loop agent
     #(agent-loop/invoke (:kernel agent) (store agent) [(msg/user message)]
        (cond-> {:context (tctx agent)
                 :tool-gate (gate-of agent)
                 :on-token on-token
                 :max-iterations (or (:max-iterations opts)
                                     (:max-iterations (:settings agent)) 10)}
          (:tool-choice opts) (assoc :tool-choice (:tool-choice opts))
          (sys-prompts agent opts) (assoc :system-prompts (sys-prompts agent opts)))))))

(defn paused?
  [agent]
  (= :paused (:status @(:state-atom agent))))

(defn resume
  "恢复暂停的 Agent。decision = \"approved\"/:approved 批准，其余拒绝。"
  [agent decision]
  (when-not (paused? agent)
    (throw (ex-info "Agent 未处于暂停状态" {:status (:status @(:state-atom agent))})))
  (let [ls (:loop-state (:paused-state @(:state-atom agent)))
        approved? (or (= decision "approved") (= decision :approved))]
    (run-loop agent
      #(agent-loop/resume (:kernel agent) ls (if approved? :approved :rejected)
         (cond-> {:context (tctx agent)
                  :tool-gate (gate-of agent)}
           (sys-prompts agent nil) (assoc :system-prompts (sys-prompts agent nil)))))))

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
