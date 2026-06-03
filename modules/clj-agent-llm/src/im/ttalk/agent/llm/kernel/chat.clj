(ns im.ttalk.agent.llm.kernel.chat
  "LLM Chat Service 工厂

   创建符合 Kernel service 接口的 LLM 服务 map。
   service 的 chat-fn 接收**中立消息**（见 core/llm/message），
   在此处按 provider 转成各家 wire 格式后再调用底层 provider。

   Service 格式：
     {:chat-fn           (fn [neutral-messages opts] -> normalized-response)
      :build-result-msgs (fn [assistant-msg tool-results] -> [msg ...])}

   使用示例：

   (require '[im.ttalk.agent.llm.kernel.chat :as chat])
   (require '[im.ttalk.agent.core.kernel :as kernel])

   (def service (chat/create-service
                  {:model \"glm-4-flash-250414\"
                   :base-url \"https://open.bigmodel.cn/api/anthropic\"
                   :api-key (System/getenv \"ZHIPU_API_KEY\")}))

   (def app-kernel
     (-> (kernel/create-kernel-builder)
         (kernel/add-tools my-tools)
         (kernel/add-service service)
         (kernel/build-kernel)))"
  (:require [im.ttalk.agent.core.kernel.service :as service]
            [im.ttalk.agent.core.llm.provider :as provider]
            [im.ttalk.agent.llm.wire.openai :as wire-openai]
            [im.ttalk.agent.llm.wire.anthropic :as wire-anthropic]))

;;; ============================================================
;;; 中立消息 → provider wire
;;; ============================================================

(defn neutral->wire
  "把中立消息列表转成指定 provider 的 wire 形态。

   返回 {:messages [...] :system <str|nil>}
   （OpenAI 家族 system 内联在 messages，:system 为 nil；
     Anthropic system 提到顶层 :system）"
  [llm-provider neutral-msgs]
  (case (provider/provider-name llm-provider)
    :anthropic (wire-anthropic/neutral->wire neutral-msgs)
    ;; 默认 OpenAI 兼容家族（openai/zhipu/ollama/gemini/mistral/mock/...）
    (wire-openai/neutral->wire neutral-msgs)))

;;; ============================================================
;;; Service 工厂
;;; ============================================================

(defn create-service
  "创建 LLM Chat Service

   chat-fn 接收中立消息，内部转 wire 后委托底层 provider。

   参数:
   - opts: 配置 map
     {:provider    ILLMProvider 实例（若提供则直接使用）
      :model       模型名称（默认 \"glm-4\"）
      :max-tokens  最大生成 token 数（默认 4096）
      :base-url    API 基础 URL
      :api-key     API 密钥
      :system-prompt 系统提示词（可选）
      :temperature   温度参数（可选）}

   返回:
   {:chat-fn (fn [neutral-messages opts] -> normalized-response)
    :build-result-msgs (fn [assistant-msg tool-results] -> [msg ...])}"
  [{:keys [provider model max-tokens base-url api-key
           system-prompt temperature]}]
  (let [llm-provider (or provider
                         (let [create-fn (requiring-resolve
                                           'im.ttalk.agent.llm.provider.anthropic/create-provider)]
                           (create-fn (cond-> {}
                                        api-key  (assoc :api-key api-key)
                                        base-url (assoc :base-url base-url)))))
        config (cond-> {:model      (or model "glm-4")
                        :max-tokens (or max-tokens 4096)}
                 system-prompt (assoc :system-prompt system-prompt)
                 temperature   (assoc :temperature temperature))
        base (service/create-service llm-provider config)]
    ;; 包装 chat-fn：中立消息 → wire（system 并入 opts）
    (assoc base :chat-fn
      (fn [neutral-msgs opts]
        (let [{:keys [messages system]} (neutral->wire llm-provider neutral-msgs)
              opts* (cond-> opts
                      (and system (not (:system-prompt opts)))
                      (assoc :system-prompt system))]
          ((:chat-fn base) messages opts*))))))
