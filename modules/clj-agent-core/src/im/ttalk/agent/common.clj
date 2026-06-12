(ns im.ttalk.agent.common
  "SimpleAgent 公共构建逻辑

    提供 kernel 构建与结果处理等共享功能。
    对话历史由 Kernel 的 ChatMemory store 自管（Memory Filter 模式），
    Agent 自身只持 conversation-id + 轻量 state。"
  (:require [im.ttalk.agent.kernel :as kernel]
            [im.ttalk.agent.model.service :as service]
            [im.ttalk.agent.advisor.memory :as memory-filter]))

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
    - :memory        ChatMemory store（已解析；以 memory-filter 形态挂进 kernel）
    - :max-iterations 最大工具调用循环次数（默认 10）

    Agent 层不暴露 kernel filter：此处只挂 memory-filter。
    需要自定义 filter 时，请直接用 kernel/build-kernel 自建后传 :kernel。"
  [opts]
  (let [service (service/create-service
                  (:provider opts)
                  (cond-> {:model      (:model opts "glm-4")
                           :max-tokens (:max-tokens opts 4096)}
                    (:temperature opts) (assoc :temperature (:temperature opts))))
        tools (:tools opts [])
        store (:memory opts)
        filters (if store
                  [(memory-filter/memory-filter store)]
                  [])]
    (kernel/build-kernel
      {:service  service
       :tools    tools
       :filters  filters
       :settings {:max-tool-iterations (or (:max-iterations opts) 10)}})))

(defn ensure-kernel
  "获取或构建 Kernel（opts 已含 :kernel 则直接用）"
  [opts]
  (or (:kernel opts) (build-kernel opts)))
