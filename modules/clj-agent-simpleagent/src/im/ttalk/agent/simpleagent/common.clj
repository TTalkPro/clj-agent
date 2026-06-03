(ns im.ttalk.agent.simpleagent.common
  "SimpleAgent 公共构建逻辑

   提供 kernel 构建与结果处理等共享功能。
   对话历史由 Kernel 的 ChatMemory store 自管（Memory Filter 模式），
   Agent 自身只持 conversation-id + 轻量 state。"
  (:require [im.ttalk.agent.core.kernel :as kernel]
            [im.ttalk.agent.llm.kernel.chat :as chat]
            [im.ttalk.agent.simpleagent.memory-advisor :as memory-advisor]))

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
   - :filters       Filter / Advisor 列表（可选）
   - :memory        ChatMemory store（已解析；以 memory-advisor 形态挂进 kernel）
   - :max-iterations 最大工具调用循环次数（默认 10）"
  [opts]
  (let [service (chat/create-service
                  {:provider    (:provider opts)
                   :model       (:model opts "glm-4")
                   :max-tokens  (:max-tokens opts 4096)
                   :temperature (:temperature opts)})
        tools (:tools opts [])
        store (:memory opts)
        filters (:filters opts [])]
    (cond-> (kernel/create-kernel-builder
              {:max-tool-iterations (or (:max-iterations opts) 10)})
      true  (kernel/add-service service)
      true  (kernel/add-tools tools)
      store (kernel/add-filter (memory-advisor/memory-advisor store))
      true  (as-> b (reduce kernel/add-filter b filters))
      true  (kernel/build-kernel))))

(defn ensure-kernel
  "获取或构建 Kernel（opts 已含 :kernel 则直接用）"
  [opts]
  (or (:kernel opts) (build-kernel opts)))
