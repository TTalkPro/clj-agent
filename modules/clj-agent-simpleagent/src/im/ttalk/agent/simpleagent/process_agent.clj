(ns im.ttalk.agent.simpleagent.process-agent
  "Process Agent - 支持 pause/resume 的模式（Memory Filter 版）

   遇到 sensitive 工具时暂停等待审批，审批后恢复执行。
   对话历史由 Kernel 的 ChatMemory store 按 conversation-id 自管，
   暂停状态只需保存待执行的 tool-calls（历史已在 store 中）。

   使用示例：

   (def agent (create-process-agent
                {:provider my-provider
                 :model \"glm-4.7\"
                 :tools [#'delete-file]
                 :on-pause (fn [{:keys [reason pending-tool]}]
                             (println \"需要审批:\" reason))}))

   (let [result (chat agent \"删除文件 /tmp/test\")]
     (when (= :paused (:status result))
       (resume agent \"approved\")))"
  (:refer-clojure :exclude [reset!])
  (:require [im.ttalk.agent.core.kernel :as kernel]
            [im.ttalk.agent.core.kernel.context :as ctx]
            [im.ttalk.agent.core.kernel.tool :as tool]
            [im.ttalk.agent.core.llm.message :as msg]
            [im.ttalk.agent.core.llm.response :as resp]
            [im.ttalk.agent.core.memory :as memory]
            [im.ttalk.agent.simpleagent.common :as common]))

;;; ============================================================
;;; 内部：Sensitive 检查
;;; ============================================================

(defn- sensitive-call?
  [kernel tool-call]
  (when-let [{:keys [tool-var]} (kernel/find-function kernel (:name tool-call))]
    (tool/sensitive? tool-var)))

;;; ============================================================
;;; 内部：chat 选项
;;; ============================================================

(defn- chat-opts
  [kernel tctx settings]
  (cond-> {:tools (:tools kernel)
           :tool-choice :auto
           :context tctx}
    (:system-prompt settings) (assoc :system-prompt (:system-prompt settings))))

;;; ============================================================
;;; 内部：工具执行（拒绝路径：sensitive 用拒绝消息，其余正常执行）
;;; ============================================================

(defn- reject-tools
  [kernel tool-calls tctx]
  (reduce
    (fn [{:keys [messages records context]} tc]
      (if (sensitive-call? kernel tc)
        {:messages (conj messages (msg/tool-result (:id tc) (:name tc) "用户拒绝执行此敏感操作"))
         :records  (conj records {:name (keyword (:name tc)) :result "用户拒绝执行此敏感操作"})
         :context  context}
        (let [{:keys [value context]}
              (try (kernel/invoke-tool kernel (keyword (:name tc)) (:input tc) context)
                   (catch Exception e {:value (str "错误: " (.getMessage e)) :context context}))]
          {:messages (conj messages (msg/tool-result (:id tc) (:name tc) value))
           :records  (conj records {:name (keyword (:name tc)) :result value})
           :context  context})))
    {:messages [] :records [] :context tctx}
    tool-calls))

;;; ============================================================
;;; 内部：单步驱动的工具循环（带 sensitive 暂停）
;;; ============================================================

(defn- run-loop
  "从给定 delta 起步，单步驱动工具循环。
   每步 invoke-chat（Memory Filter 存 delta+展开历史+存 assistant），
   遇 sensitive 工具暂停。

   参数:
   - delta: 本步要发送的中立消息（user 或 tool 结果）"
  [kernel tctx delta remaining settings records]
  (loop [delta delta
         remaining remaining
         records records
         tctx tctx]
    (if (zero? remaining)
      {:status :completed :text "工具调用循环超过上限"
       :tool-calls-made records :tool-context tctx}
      (let [{:keys [response context]}
            (kernel/invoke-chat kernel delta (chat-opts kernel tctx settings))
            tctx context
            calls (resp/response-tool-calls response)]
        (if (seq calls)
          (if-let [sens (first (filter #(sensitive-call? kernel %) calls))]
            ;; 暂停：assistant(tool-calls) 已被 Memory Filter 存入，仅需保存待执行 calls
            {:status :paused
             :pause-reason (str "需要审批: " (:name sens))
             :pending-tool {:name (:name sens) :args (:input sens) :tool-call sens}
             :loop-state {:tool-calls calls :remaining (dec remaining)}
             :tool-calls-made records
             :tool-context tctx}
            ;; 全部安全：执行后继续
            (let [{new-msgs :messages new-records :records context :context}
                  (kernel/run-tools kernel calls tctx)]
              (recur new-msgs (dec remaining) (into records new-records) context)))
          ;; 文本响应
          {:status :completed
           :text (resp/response-text response)
           :tool-calls-made records
           :tool-context tctx})))))

;;; ============================================================
;;; 公开 API
;;; ============================================================

(defn create-process-agent
  "创建 Process Agent

   参数同 kernel-agent，外加：
   - :on-pause 暂停回调 (fn [{:keys [reason pending-tool]}])

   返回 Agent map（持 conversation-id + state-atom）"
  [opts]
  (let [k (common/ensure-kernel opts)
        conv-id (str "proc-" (java.util.UUID/randomUUID))]
    {:kernel          k
     :conversation-id conv-id
     :state-atom      (atom {:status :idle :paused-state nil :last-response nil})
     :settings        (select-keys opts [:system-prompt :max-iterations :on-pause])}))

(defn paused?
  [agent]
  (= :paused (:status @(:state-atom agent))))

(defn- tctx-of [agent]
  (ctx/with-conversation-id (ctx/create) (:conversation-id agent)))

(defn chat
  "对话。返回 {:status :completed|:paused ...}"
  ([agent message]
   (chat agent message nil))
  ([agent message opts]
   (let [kernel (:kernel agent)
         settings (merge (:settings agent)
                         (select-keys (or opts {}) [:system-prompt :max-iterations]))
         max-iter (or (:max-iterations settings) 10)
         result (run-loop kernel (tctx-of agent)
                          [(msg/user message)] max-iter settings [])]
     (common/finalize-result agent result))))

(defn resume
  "恢复暂停的 Agent。decision = \"approved\" 批准，其余拒绝。"
  [agent decision]
  (when-not (paused? agent)
    (throw (ex-info "Agent 未处于暂停状态" {:status (:status @(:state-atom agent))})))
  (let [kernel (:kernel agent)
        settings (:settings agent)
        {:keys [tool-calls remaining]} (:loop-state (:paused-state @(:state-atom agent)))
        tctx (tctx-of agent)
        {:keys [messages records context]}
        (if (= decision "approved")
          (kernel/run-tools kernel tool-calls tctx)
          (reject-tools kernel tool-calls tctx))
        result (run-loop kernel context messages remaining settings records)]
    (common/finalize-result agent result)))

(defn reset!
  "重置状态并清空会话历史"
  [agent]
  (memory/mem-clear (:memory (:kernel agent)) (:conversation-id agent))
  (clojure.core/reset! (:state-atom agent) {:status :idle :paused-state nil :last-response nil})
  nil)

(defn get-history
  "获取该会话的完整中立消息历史"
  [agent]
  (memory/mem-get (:memory (:kernel agent)) (:conversation-id agent)))
