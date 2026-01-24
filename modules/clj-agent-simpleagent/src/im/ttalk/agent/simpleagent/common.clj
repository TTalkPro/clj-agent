(ns im.ttalk.agent.simpleagent.common
  "SimpleAgent 公共构建逻辑

   提供 kernel 构建、工具执行、结果处理等共享功能。"
  (:require [im.ttalk.agent.core.kernel.core :as kernel]
            [im.ttalk.agent.core.kernel.context :as ctx]
            [im.ttalk.agent.core.kernel.plugin :as kp]
            [im.ttalk.agent.llm.kernel.chat :as chat]))

;;; ============================================================
;;; Kernel 构建
;;; ============================================================

(defn- normalize-plugins
  "将工具配置统一转换为 KernelPlugin 列表

   支持 KernelPlugin 实例列表或 tool var 列表（自动包装为 plugin）。"
  [tools]
  (if (and (seq tools) (var? (first tools)))
    [(kp/create-plugin :agent-tools "Agent 工具集" tools)]
    (vec tools)))

(defn build-kernel
  "根据配置 map 构建 Kernel 实例

   参数 opts:
   - :provider      LLM Provider 实例
   - :model         模型名称（默认 \"glm-4\"）
   - :max-tokens    最大生成 token 数（默认 4096）
   - :temperature   温度参数（可选）
   - :tools         KernelPlugin 列表或 tool var 列表
   - :filters       Filter 列表（可选）
   - :max-iterations 最大工具调用循环次数（默认 10）"
  [opts]
  (let [service (chat/create-service
                  {:provider    (:provider opts)
                   :model       (:model opts "glm-4")
                   :max-tokens  (:max-tokens opts 4096)
                   :temperature (:temperature opts)})
        plugins (normalize-plugins (:tools opts []))
        filters (:filters opts [])]
    (-> (kernel/create-kernel-builder
          {:max-tool-iterations (or (:max-iterations opts) 10)})
        (kernel/add-service service)
        (as-> b (reduce kernel/add-plugin b plugins))
        (as-> b (reduce kernel/add-filter b filters))
        (kernel/build-kernel))))

(defn ensure-kernel
  "获取或构建 Kernel

   若 opts 中已包含 :kernel 直接使用，否则调用 build-kernel 构建。"
  [opts]
  (or (:kernel opts) (build-kernel opts)))

;;; ============================================================
;;; 工具执行
;;; ============================================================

(defn execute-tools
  "批量执行工具调用，累积 context 和执行记录

   对每个 tool-call 调用 kernel/invoke-tool，将结果消息追踪到 context。

   返回: {:results [{:tool-id :name :result}...]
          :context updated-ctx
          :records [{:name :args :result}...]}"
  [kernel tool-calls context]
  (reduce
    (fn [{:keys [context] :as acc} tc]
      (let [fn-name (keyword (:name tc))
            {:keys [value context]}
            (try (kernel/invoke-tool kernel fn-name (:input tc) context)
                 (catch Exception e
                   {:value (str "错误: " (.getMessage e)) :context context}))
            content (if (string? value) value (pr-str value))]
        (-> acc
            (update :results conj {:tool-id (:id tc) :name fn-name :result value})
            (update :records conj {:name fn-name :args (:input tc) :result value})
            (assoc :context (ctx/track-message context
                              {:role "tool" :tool_call_id (:id tc) :content content})))))
    {:results [] :context context :records []}
    tool-calls))

;;; ============================================================
;;; 结果处理
;;; ============================================================

(defn finalize-result
  "处理工具循环结果，更新 agent 状态并返回标准化响应

   根据 result 中的 :status（:completed 或 :paused）执行对应的状态转换：
   - :completed → 重置暂停状态，返回文本和工具记录
   - :paused    → 保存暂停快照，触发 on-pause 回调"
  [{:keys [context-atom state-atom settings]} result]
  (clojure.core/reset! context-atom (:context result))
  (case (:status result)
    :completed
    (let [response {:text (:text result) :tool-calls-made (:tool-calls-made result)}]
      (clojure.core/reset! state-atom {:status :completed :paused-state nil :last-response response})
      (assoc response :status :completed))

    :paused
    (do (clojure.core/reset! state-atom {:status :paused
                                          :paused-state (assoc result :context @context-atom)
                                          :last-response nil})
        (when-let [on-pause (:on-pause settings)]
          (on-pause {:reason (:pause-reason result)
                     :pending-tool (:pending-tool result)}))
        {:status :paused
         :text nil
         :pause-reason (:pause-reason result)
         :pending-tool (:pending-tool result)
         :tool-calls-made (:tool-calls-made result)})))
