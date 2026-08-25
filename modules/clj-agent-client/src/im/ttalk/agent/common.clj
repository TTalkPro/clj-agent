(ns im.ttalk.agent.common
  "SimpleAgent 公共构建逻辑

    提供 chat-client 构建与结果处理等共享功能。
    对话历史由 ChatClient 的 ChatMemory store 自管（Memory Filter 模式），
    Agent 自身只持 conversation-id + 轻量 state。"
  (:require [im.ttalk.agent.chat-client :as chat-client]
            [im.ttalk.agent.chat-model :as chat-model]
            [im.ttalk.agent.filter.memory :as memory-filter]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; ChatClient 构建
;;; ============================================================

(def ^:private orchestration-keys
  "只属于 agent/chat-client **编排层**的键——绝不下沉到 provider 调用 config。

   这份名单是白名单改排除法后唯一的把关处，加 agent 级选项时**必须**同步加进来。
   `:tools` 尤其危险：chat-model config 里的 `:tools` 指**已编译的 tool schema**，
   而 agent 的 `:tools` 是 tool var 向量——漏下去 provider 会转出 `{:name nil}`，
   MiniMax 当场 400「invalid params, function name is empty」。

   `:system-prompt` 不在这里下沉是因为它**每次调用单独下发**（见 client 的
   :settings → chat-model/build-call-config），基础 config 里再放一份是重复真相。"
  #{:provider :tools :tool-vars :chat-client :filters :memory :pause-store
    :callbacks :conversation-id :max-iterations :state-slots :tool-manager
    :eligibility-fn :system-prompt :on-pause :on-error :on-env-error
    :cancel-token :tool-choice :id})

(defn chat-model-config
  "opts → provider 调用 config（基础值，每次调用可再覆盖）。

   **排除法而非白名单**：早先这里只放行 {:model :max-tokens :temperature}，
   于是 provider 专属能力（Anthropic/MiniMax 的 :thinking、:cache-strategy、
   :service-tier、:top-k、:beta …）明明在 provider 侧实现了，走 create-agent 却
   **递不到底**——只能自建 ChatClient / ChatModel 绕开 agent 门面。那是「说了能用却用不了」，
   不是设计取舍。放行未知键的代价可控：各 provider 的 build-params 只解构自己认识的
   键，多余的原样忽略。"
  [opts]
  (->> (apply dissoc opts orchestration-keys)
       (remove (comp nil? val))          ;; 显式传 nil 不该覆盖 provider 侧默认值
       (into {:model      (:model opts "glm-4")
              :max-tokens (:max-tokens opts 4096)})))

(defn build-chat-client
  "根据配置 map 构建 ChatClient 实例

    参数 opts:
    - :provider      LLM Provider 实例
    - :model         模型名称（默认 \"glm-4\"）
    - :max-tokens    最大生成 token 数（默认 4096）
    - :temperature   温度参数（可选）
    - :tools         tool var 列表
    - :memory        ChatMemory store（已解析；以 memory-filter 形态挂进 chat-client）
    - :max-iterations 最大工具调用循环次数（默认 10）
    - **其余键一律透传给 provider 调用 config**（见 chat-model-config）：
      如 Anthropic/MiniMax 的 :thinking、:cache-strategy、:service-tier、:top-k、
      :beta、:retry、:timeout 等。编排层自己的键见 orchestration-keys。

    Agent 层不暴露 chat-client filter：此处只挂 memory-filter。
    需要自定义 filter 时，请直接用 chat-client/build-chat-client 自建后传 :chat-client。"
  [opts]
  (let [chat-model (chat-model/create-chat-model (:provider opts) (chat-model-config opts))
        tools (:tools opts [])
        store (:memory opts)
        filters (if store
                  [(memory-filter/memory-filter store)]
                  [])]
    (chat-client/build-chat-client
      (cond-> {:chat-model  chat-model
               :tools    tools
               :filters  filters
               :settings {:max-tool-iterations (or (:max-iterations opts) 10)}}
        (:state-slots opts) (assoc :state-slots (:state-slots opts))
        (:tool-manager opts) (assoc :tool-manager (:tool-manager opts))))))
