(ns im.ttalk.agent.simpleagent.process-agent
  "Process Agent - 支持 pause/resume 的模式

   遇到 sensitive 工具时暂停等待审批，审批后恢复执行。

   使用示例：

   (def agent (create-process-agent
                {:provider my-provider
                 :model \"glm-4.7\"
                 :tools [my-plugin]
                 :on-pause (fn [{:keys [reason pending-tool]}]
                             (println \"需要审批:\" reason))}))

   (let [result (chat agent \"删除文件 /tmp/test\")]
     (when (= :paused (:status result))
       (resume agent \"approved\")))"
  (:refer-clojure :exclude [reset!])
  (:require [im.ttalk.agent.core.kernel.core :as kernel]
            [im.ttalk.agent.core.kernel.context :as ctx]
            [im.ttalk.agent.core.kernel.plugin :as kp]
            [im.ttalk.agent.core.kernel.tool :as tool]
            [im.ttalk.agent.llm.kernel.chat :as chat]
            [clojure.string :as str]))

;;; ============================================================
;;; 内部：从 opts 构建 Kernel
;;; ============================================================

(defn- build-kernel-from-opts [opts]
  (let [service (chat/create-service
                  {:provider    (:provider opts)
                   :model       (:model opts "glm-4")
                   :max-tokens  (:max-tokens opts 4096)
                   :temperature (:temperature opts)})
        tools (:tools opts [])
        plugins (if (and (seq tools) (var? (first tools)))
                  [(kp/create-plugin :agent-tools "Agent 工具集" tools)]
                  tools)
        filters (:filters opts [])]
    (-> (kernel/create-kernel-builder
          {:max-tool-iterations (or (:max-iterations opts) 10)})
        (kernel/add-service service)
        (as-> b (reduce kernel/add-plugin b plugins))
        (as-> b (reduce kernel/add-filter b filters))
        (kernel/build-kernel))))

;;; ============================================================
;;; 内部：Sensitive 检查
;;; ============================================================

(defn- is-sensitive?
  "检查工具调用是否为 sensitive 工具"
  [kernel tool-call]
  (when-let [{:keys [tool-var]} (kernel/find-function kernel (:name tool-call))]
    (tool/sensitive? tool-var)))

;;; ============================================================
;;; 内部：工具执行
;;; ============================================================

(defn- execute-tool-calls-safe
  "批量执行工具调用（与 core.clj 中逻辑一致）

   参数:
   - kernel:     Kernel 实例
   - tool-calls: 工具调用列表
   - context:    当前 Context

   返回:
   {:results [{:tool-id :name :result}...]
    :context updated-ctx
    :records [{:name :args :result}...]}"
  [kernel tool-calls context]
  (reduce
    (fn [acc tc]
      (let [fn-name (keyword (:name tc))
            args (:input tc)
            {:keys [value context]}
            (try (kernel/invoke-tool kernel fn-name args (:context acc))
                 (catch Exception e
                   {:value (str "错误: " (.getMessage e))
                    :context (:context acc)}))
            tool-msg {:role "tool" :tool_call_id (:id tc)
                      :content (if (string? value) value (pr-str value))}
            new-ctx (ctx/track-message context tool-msg)]
        {:results (conj (:results acc)
                        {:tool-id (:id tc) :name fn-name :result value})
         :context new-ctx
         :records (conj (:records acc)
                        {:name fn-name :args args :result value})}))
    {:results [] :context context :records []}
    tool-calls))

;;; ============================================================
;;; 内部：工具调用循环（带 sensitive 检查）
;;; ============================================================

(defn- tool-calling-loop
  "工具调用循环，遇到 sensitive 工具时暂停

   参数:
   - kernel:   Kernel 实例
   - context:  Context
   - messages: 新消息列表
   - settings: 配置 map

   返回:
   {:status :completed|:paused
    :text \"...\"|nil
    :tool-calls-made [...]
    :context ctx
    ;; :paused 时额外字段:
    :pause-reason \"...\"
    :pending-tool {:name :args :tool-call}
    :loop-state {...}}"
  [kernel context messages settings]
  (let [system-prompts (when-let [sp (:system-prompt settings)]
                         [{:role "system" :content sp}])
        max-iter (or (:__remaining settings)
                     (:max-iterations settings)
                     10)
        init-tool-calls (or (:__all-tool-calls settings) [])
        service (:service kernel)]
    (loop [conv-msgs (into (vec (ctx/get-messages context)) messages)
           remaining max-iter
           all-tool-calls init-tool-calls
           ctx (reduce ctx/track-message context messages)]
      (if (zero? remaining)
        {:status :completed
         :text "工具调用循环超过上限"
         :tool-calls-made all-tool-calls
         :context ctx}
        (let [system-prompt-str (when (seq system-prompts)
                                  (->> system-prompts
                                       (map :content)
                                       (str/join "\n")))
              chat-opts (cond-> {:tools (:tools kernel)
                                 :tool-choice :auto
                                 :context ctx}
                          system-prompt-str
                          (assoc :system-prompt system-prompt-str))
              {:keys [response context]} (kernel/invoke-chat kernel conv-msgs chat-opts)
              ctx context]
          (if (seq (:tool-calls response))
            ;; 检查是否有 sensitive 工具
            (let [tool-calls (:tool-calls response)
                  sensitive-tc (first (filter #(is-sensitive? kernel %) tool-calls))]
              (if sensitive-tc
                ;; 暂停：保存循环状态
                {:status :paused
                 :pause-reason (str "需要审批: " (:name sensitive-tc))
                 :pending-tool {:name (:name sensitive-tc)
                                :args (:input sensitive-tc)
                                :tool-call sensitive-tc}
                 :loop-state {:conv-msgs conv-msgs
                              :remaining remaining
                              :all-tool-calls all-tool-calls
                              :tool-calls tool-calls
                              :assistant-msg (:assistant-msg response)}
                 :tool-calls-made all-tool-calls
                 :context ctx}
                ;; 全部安全：执行所有工具
                (let [ctx (ctx/track-message ctx (:assistant-msg response))
                      {:keys [results context records]}
                      (execute-tool-calls-safe kernel tool-calls ctx)
                      new-msgs ((:build-result-msgs service)
                                (:assistant-msg response) results)]
                  (recur (into conv-msgs new-msgs)
                         (dec remaining)
                         (into all-tool-calls records)
                         context))))
            ;; 文本响应
            (let [ctx (ctx/track-message ctx (:assistant-msg response))]
              {:status :completed
               :text (:text response)
               :tool-calls-made all-tool-calls
               :context ctx})))))))

;;; ============================================================
;;; 内部：Resume 逻辑
;;; ============================================================

(defn- resume-tool-loop
  "从暂停状态恢复

   参数:
   - kernel:       Kernel 实例
   - paused-state: 暂停时保存的状态
   - decision:     \"approved\" 或其他（拒绝）
   - settings:     配置 map

   返回:
   tool-calling-loop 的返回值"
  [kernel paused-state decision settings]
  (let [{:keys [loop-state context]} paused-state
        {:keys [conv-msgs remaining all-tool-calls tool-calls assistant-msg]} loop-state
        service (:service kernel)]
    (if (= decision "approved")
      ;; 批准：执行所有工具（包括 sensitive 的）继续循环
      (let [ctx (ctx/track-message context assistant-msg)
            {:keys [results context records]}
            (execute-tool-calls-safe kernel tool-calls ctx)
            new-msgs ((:build-result-msgs service) assistant-msg results)]
        (tool-calling-loop kernel context
                           (into conv-msgs new-msgs)
                           (assoc settings
                                  :__remaining (dec remaining)
                                  :__all-tool-calls (into all-tool-calls records))))
      ;; 拒绝：跳过 sensitive 工具，添加拒绝消息
      (let [ctx (ctx/track-message context assistant-msg)
            ;; 为所有 tool-call 生成结果（sensitive 的用拒绝消息）
            rejection-results
            (mapv (fn [tc]
                    (if (is-sensitive? kernel tc)
                      {:tool-id (:id tc) :name (keyword (:name tc))
                       :result "用户拒绝执行此敏感操作"}
                      ;; 非 sensitive 的正常执行
                      (let [{:keys [value]} (try (kernel/invoke-tool kernel
                                                   (keyword (:name tc)) (:input tc) ctx)
                                                 (catch Exception e
                                                   {:value (str "错误: " (.getMessage e))}))]
                        {:tool-id (:id tc) :name (keyword (:name tc)) :result value})))
                  tool-calls)
            ;; track 所有 tool result messages
            ctx (reduce (fn [c r]
                          (ctx/track-message c
                            {:role "tool" :tool_call_id (:tool-id r)
                             :content (if (string? (:result r)) (:result r) (pr-str (:result r)))}))
                        ctx rejection-results)
            new-msgs ((:build-result-msgs service) assistant-msg rejection-results)]
        (tool-calling-loop kernel ctx
                           (into conv-msgs new-msgs)
                           (assoc settings
                                  :__remaining (dec remaining)
                                  :__all-tool-calls (into all-tool-calls
                                                          (mapv (fn [r] {:name (:name r) :result (:result r)})
                                                                rejection-results))))))))

;;; ============================================================
;;; 公开 API
;;; ============================================================

(defn create-process-agent
  "创建 Process Agent

   参数:
   - opts: 配置 map
     {:kernel        预构建 Kernel（提供时跳过自动构建）
      :provider      ILLMProvider 实例
      :model         模型名称（默认 \"glm-4\"）
      :max-tokens    最大 token 数（默认 4096）
      :system-prompt 系统提示词
      :temperature   温度参数
      :tools         KernelPlugin 实例列表或 tool var 列表
      :filters       Filter 列表
      :max-iterations 最大工具调用循环次数（默认 10）
      :on-pause      暂停回调 (fn [{:keys [reason pending-tool]}])}

   返回:
   Agent map"
  [opts]
  (let [k (or (:kernel opts) (build-kernel-from-opts opts))]
    {:kernel       k
     :context-atom (atom (ctx/create))
     :state-atom   (atom {:status :idle :paused-state nil :last-response nil})
     :settings     (select-keys opts [:system-prompt :max-iterations :on-pause])}))

(defn paused?
  "检查 Agent 是否处于暂停状态

   参数:
   - agent: Agent 实例

   返回: boolean"
  [agent]
  (= :paused (:status @(:state-atom agent))))

(defn chat
  "对话

   参数:
   - agent:   Agent 实例
   - message: 用户消息字符串
   - opts:    可选选项 map

   返回:
   {:status :completed|:paused
    :text \"...\"|nil
    :tool-calls-made [...]
    ;; :paused 时:
    :pause-reason \"...\"
    :pending-tool {:name :args :tool-call}}"
  ([agent message]
   (chat agent message nil))
  ([agent message opts]
   (let [kernel (:kernel agent)
         settings (merge (:settings agent) (select-keys (or opts {}) [:system-prompt :max-iterations]))
         current-ctx @(:context-atom agent)
         user-msg {:role "user" :content message}
         result (tool-calling-loop kernel current-ctx [user-msg] settings)]
     (clojure.core/reset! (:context-atom agent) (:context result))
     (case (:status result)
       :completed
       (do (swap! (:state-atom agent) assoc
                  :status :completed
                  :paused-state nil
                  :last-response {:text (:text result) :tool-calls-made (:tool-calls-made result)})
           {:status :completed :text (:text result) :tool-calls-made (:tool-calls-made result)})
       :paused
       (do (swap! (:state-atom agent) assoc
                  :status :paused
                  :paused-state (assoc result :context @(:context-atom agent)))
           (when-let [on-pause (:on-pause settings)]
             (on-pause {:reason (:pause-reason result)
                        :pending-tool (:pending-tool result)}))
           {:status :paused
            :text nil
            :pause-reason (:pause-reason result)
            :pending-tool (:pending-tool result)
            :tool-calls-made (:tool-calls-made result)})))))

(defn resume
  "恢复暂停的 Agent

   参数:
   - agent:    Agent 实例
   - decision: \"approved\" 批准执行，其他值拒绝

   返回:
   {:status :completed|:paused ...}"
  [agent decision]
  (when-not (paused? agent)
    (throw (ex-info "Agent 未处于暂停状态" {:status (:status @(:state-atom agent))})))
  (let [kernel (:kernel agent)
        settings (:settings agent)
        paused-state (:paused-state @(:state-atom agent))
        result (resume-tool-loop kernel paused-state decision settings)]
    (clojure.core/reset! (:context-atom agent) (:context result))
    (case (:status result)
      :completed
      (do (swap! (:state-atom agent) assoc
                 :status :completed
                 :paused-state nil
                 :last-response {:text (:text result) :tool-calls-made (:tool-calls-made result)})
          {:status :completed :text (:text result) :tool-calls-made (:tool-calls-made result)})
      :paused
      (do (swap! (:state-atom agent) assoc
                 :status :paused
                 :paused-state (assoc result :context @(:context-atom agent)))
          (when-let [on-pause (:on-pause settings)]
            (on-pause {:reason (:pause-reason result)
                       :pending-tool (:pending-tool result)}))
          {:status :paused
           :pause-reason (:pause-reason result)
           :pending-tool (:pending-tool result)
           :tool-calls-made (:tool-calls-made result)}))))

(defn reset!
  "重置状态

   参数:
   - agent: Agent 实例

   返回: nil"
  [agent]
  (clojure.core/reset! (:context-atom agent) (ctx/create))
  (clojure.core/reset! (:state-atom agent) {:status :idle :paused-state nil :last-response nil})
  nil)

(defn get-context
  "获取当前 context

   参数:
   - agent: Agent 实例

   返回: Context map"
  [agent]
  @(:context-atom agent))
