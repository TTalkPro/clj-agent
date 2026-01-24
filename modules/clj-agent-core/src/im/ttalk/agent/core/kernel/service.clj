(ns im.ttalk.agent.core.kernel.service
  "LLM Service 工厂

   提供通用的 create-service，将任意 ILLMProvider 适配为 Kernel service map。

   使用示例：

   (require '[im.ttalk.agent.core.kernel.service :as service])
   (require '[im.ttalk.agent.core.kernel.provider :as provider])

   ;; 从 provider 创建 service
   (def svc (service/create-service my-provider
              {:model \"gpt-4\" :max-tokens 4096}))

   ;; service 是一个 map：
   ;; {:chat-fn           (fn [messages opts] -> normalized-response)
   ;;  :build-result-msgs (fn [assistant-msg tool-results] -> [msg ...])}"
  (:require [im.ttalk.agent.core.kernel.provider :as provider]))

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

   规范化规则：
   - text: 空字符串视为 nil
   - tool-calls: 空列表视为 nil
   - assistant-msg: 由 provider 构建
   - raw-response: 保留原始响应

   参数：
   - provider: ILLMProvider 实例
   - response: Provider 原始响应

   返回：
   {:text str|nil :tool-calls vec|nil :assistant-msg map :raw-response any}"
  [provider response]
  {:text          (when-let [t (provider/extract-text provider response)]
                    (when (seq t) t))
   :tool-calls    (let [tcs (provider/extract-tool-calls provider response)]
                    (when (seq tcs) tcs))
   :assistant-msg (provider/build-assistant-message provider response)
   :raw-response  response})

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
