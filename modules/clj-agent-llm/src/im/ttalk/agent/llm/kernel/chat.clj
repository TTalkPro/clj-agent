(ns im.ttalk.agent.llm.kernel.chat
  "LLM Chat Service 工厂

   创建符合 Kernel service 接口的 LLM 服务 map。
   Service 格式：
     {:chat-fn           (fn [messages opts] -> normalized-response)
      :build-result-msgs (fn [assistant-msg tool-results] -> [msg ...])}

   使用示例：

   (require '[im.ttalk.agent.llm.kernel.chat :as chat])
   (require '[im.ttalk.agent.core.kernel.core :as kernel])

   ;; 方式 1: 使用 create-service 创建 service 并传给 Kernel
   (def service (chat/create-service
                  {:model \"glm-4-flash-250414\"
                   :base-url \"https://open.bigmodel.cn/api/anthropic\"
                   :api-key (System/getenv \"ZHIPU_API_KEY\")}))

   (def app-kernel
     (-> (kernel/create-kernel-builder)
         (kernel/add-tools my-tools)
         (kernel/add-service service)
         (kernel/build-kernel)))

   ;; 对话
   (kernel/invoke-chat-with-tools app-kernel messages {})"
  (:require [im.ttalk.agent.core.kernel.service :as service]))

;;; ============================================================
;;; Service 工厂
;;; ============================================================

(defn create-service
  "创建 LLM Chat Service

   创建符合 Kernel service 接口的 map，包含 :chat-fn 和 :build-result-msgs。

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
   {:chat-fn (fn [messages opts] -> normalized-response)
    :build-result-msgs (fn [assistant-msg tool-results] -> [msg ...])}"
  [{:keys [provider model max-tokens base-url api-key
           system-prompt temperature]}]
  (let [;; 创建或使用已有 provider
        llm-provider (or provider
                         (let [create-fn (requiring-resolve
                                           'im.ttalk.agent.llm.provider.anthropic/create-provider)]
                           (create-fn (cond-> {}
                                        api-key  (assoc :api-key api-key)
                                        base-url (assoc :base-url base-url)))))
        ;; 模型配置
        config (cond-> {:model      (or model "glm-4")
                        :max-tokens (or max-tokens 4096)}
                 system-prompt (assoc :system-prompt system-prompt)
                 temperature   (assoc :temperature temperature))]
    (service/create-service llm-provider config)))
