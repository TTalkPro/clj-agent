(ns im.ttalk.agent.simpleagent.common
  "SimpleAgent 公共构建逻辑

   提供 kernel 构建与结果处理等共享功能。
   对话历史由 Kernel 的 ChatMemory store 自管（Memory Filter 模式），
   Agent 自身只持 conversation-id + 轻量 state。"
  (:require [im.ttalk.agent.core.kernel :as kernel]
            [im.ttalk.agent.llm.kernel.chat :as chat]))

;;; ============================================================
;;; Kernel 构建
;;; ============================================================

(defn build-kernel
  "根据配置 map 构建 Kernel 实例

   参数 opts:
   - :provider      LLM Provider 实例
   - :model         模型名称（默认 \"glm-4\"）
   - :max-tokens    最大生成 token 数（默认 4096）
   - :temperature   温度参数（可选）
   - :tools         tool var 列表
   - :filters       Filter 列表（可选）
   - :memory        ChatMemory store（可选，不给则 Kernel 默认 in-memory）
   - :max-iterations 最大工具调用循环次数（默认 10）"
  [opts]
  (let [service (chat/create-service
                  {:provider    (:provider opts)
                   :model       (:model opts "glm-4")
                   :max-tokens  (:max-tokens opts 4096)
                   :temperature (:temperature opts)})
        tools (:tools opts [])
        filters (:filters opts [])]
    (cond-> (kernel/create-kernel-builder
              {:max-tool-iterations (or (:max-iterations opts) 10)})
      true (kernel/add-service service)
      true (kernel/add-tools tools)
      (:memory opts) (kernel/add-memory (:memory opts))
      true (as-> b (reduce kernel/add-filter b filters))
      true (kernel/build-kernel))))

(defn ensure-kernel
  "获取或构建 Kernel（opts 已含 :kernel 则直接用）"
  [opts]
  (or (:kernel opts) (build-kernel opts)))

;;; ============================================================
;;; 结果处理
;;; ============================================================

(defn finalize-result
  "处理工具循环结果，更新 agent 的 state-atom 并返回标准化响应

   - :completed → 清暂停态，返回文本和工具记录
   - :paused    → 保存暂停状态（loop-state），触发 on-pause 回调

   历史持久化由 ChatMemory store 负责，这里不再管理 context。"
  [{:keys [state-atom settings]} result]
  (case (:status result)
    :completed
    (let [response {:text (:text result)
                    :tool-calls-made (:tool-calls-made result)}]
      (reset! state-atom {:status :completed :paused-state nil :last-response response})
      (assoc response :status :completed))

    :paused
    (do
      (reset! state-atom {:status :paused
                          :paused-state result
                          :last-response nil})
      (when-let [on-pause (:on-pause settings)]
        (on-pause {:reason (:pause-reason result)
                   :pending-tool (:pending-tool result)}))
      {:status :paused
       :text nil
       :pause-reason (:pause-reason result)
       :pending-tool (:pending-tool result)
       :tool-calls-made (:tool-calls-made result)})))
