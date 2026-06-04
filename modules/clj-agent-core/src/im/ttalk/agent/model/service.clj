(ns im.ttalk.agent.model.service
  "LLM Service 工厂

   提供通用的 create-service，将任意 ILLMProvider 适配为 Kernel service map。
   响应使用统一格式（response/make-response），支持 OpenAI/Anthropic 等多 Provider。

   使用示例：

   (require '[im.ttalk.agent.model.service :as service])
   (require '[im.ttalk.agent.model :as provider])

   ;; 从 provider 创建 service
   (def svc (service/create-service my-provider
              {:model \"gpt-4\" :max-tokens 4096}))

   ;; service 是一个 map：
   ;; {:chat-fn           (fn [messages opts] -> ILLMResponse)
   ;;  :build-result-msgs (fn [assistant-msg tool-results] -> [msg ...])}"
  (:require [im.ttalk.agent.model :as provider]
            [im.ttalk.agent.model.response :as response]))

;;; ============================================================
;;; 辅助函数
;;; ============================================================

(defn- build-call-config
  "合并基础配置和调用时选项

   合并策略：
   - tool-choice 为 :none 时不传 tools（强制纯文本回复）
   - tool-choice :auto/:required 转换为 API 格式 {:type \"auto\"}/{:type \"any\"}
   - system-prompt 直接传递

   参数：
   - config: 基础配置 {:model ... :max-tokens ...}
   - opts:   调用时选项 {:tools [...] :tool-choice ... :system-prompt ...}

   返回：
   合并后的调用配置 map"
  [config opts]
  (let [tool-choice (:tool-choice opts)
        tools (when (not= tool-choice :none)
                (:tools opts))
        system-prompt (:system-prompt opts)]
    (cond-> config
      (seq tools)
      (assoc :tools tools)
      (and tool-choice (not= tool-choice :none))
      (assoc :tool-choice
             (case tool-choice
               :auto {:type "auto"}
               :required {:type "any"}
               tool-choice))
      system-prompt
      (assoc :system-prompt system-prompt))))

;;; ============================================================
;;; Service 工厂
;;; ============================================================

(defn- normalize-response
  "将 Provider 原始响应规范化为 Kernel 统一格式

   使用 response/make-response 创建 LLMResponse，自动归一化：
   - usage: 统一为 {:input-tokens :output-tokens :total-tokens}
   - finish-reason: 统一为关键字 :stop | :tool-use | :max-tokens 等

   参数：
   - provider: ILLMProvider 实例
   - raw-response: Provider 原始响应

   返回：
   LLMResponse record（实现 ILLMResponse 协议）"
  [provider raw-response]
  (let [text (provider/extract-text provider raw-response)
        tool-calls (provider/extract-tool-calls provider raw-response)
        ;; 获取 provider 特定的字段
        usage (or (:usage raw-response)
                  (get-in raw-response [:choices 0 :usage]))
        finish-reason (or (:stop_reason raw-response)
                          (get-in raw-response [:choices 0 :finish_reason]))
        assistant-msg (provider/build-assistant-message provider raw-response)]
    (response/make-response
      :id (:id raw-response)
      :model (:model raw-response)
      :text (when (seq text) text)
      :tool-calls (when (seq tool-calls) tool-calls)
      :assistant-msg assistant-msg
      :usage usage
      :finish-reason finish-reason
      :provider (provider/provider-name provider)
      :raw-response raw-response)))

(defn create-service
  "从 ILLMProvider 创建 Kernel service

   参数：
   - provider: ILLMProvider 实例
   - config:   模型配置 {:model \"...\" :max-tokens n :system-prompt \"...\"}

   返回：
   {:chat-fn           (fn [messages opts] -> normalized-response)
    :build-result-msgs (fn [assistant-msg tool-results] -> [msg ...])}"
  [provider config]
  {:chat-fn
   (fn [messages opts]
     (let [call-config (build-call-config config opts)
           tools       (:tools call-config)
           response    (provider/call-llm provider call-config messages tools)]
       (normalize-response provider response)))
   :build-result-msgs
   (fn [assistant-msg tool-results]
     (provider/build-result-messages provider assistant-msg tool-results))})
