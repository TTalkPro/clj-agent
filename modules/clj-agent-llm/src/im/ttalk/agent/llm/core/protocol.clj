(ns im.ttalk.agent.llm.core.protocol
  "LLM Provider 协议定义（向后兼容层）

   此命名空间从 im.ttalk.agent.core.kernel.provider re-export 所有定义。
   新代码建议直接使用 im.ttalk.agent.core.kernel.provider。

   使用示例：

   (defrecord MyProvider [api-key]
     ILLMProvider
     (provider-name [_] :my-provider)
     (call-llm [this config messages tools] ...)
     (extract-tool-calls [_ response] ...)
     (extract-text [_ response] ...)
     (build-tool-result [_ tool-id content] ...)
     (build-assistant-message [_ response] ...)
     (build-result-messages [_ assistant-msg tool-results] ...))"
  (:require [im.ttalk.agent.core.kernel.provider :as provider]))

;;; ============================================================
;;; Re-export Protocol
;;; ============================================================

(def ILLMProvider provider/ILLMProvider)

;;; ============================================================
;;; Re-export Protocol 方法 vars
;;; ============================================================

(def provider-name provider/provider-name)
(def call-llm provider/call-llm)
(def call-llm-stream provider/call-llm-stream)
(def extract-tool-calls provider/extract-tool-calls)
(def extract-text provider/extract-text)
(def build-tool-result provider/build-tool-result)
(def supports-function-calling? provider/supports-function-calling?)
(def supports-stream? provider/supports-stream?)
(def tool->schema provider/tool->schema)
(def build-assistant-message provider/build-assistant-message)
(def build-result-messages provider/build-result-messages)

;;; ============================================================
;;; Re-export 辅助函数
;;; ============================================================

(def provider? provider/provider?)
(def call-with-tools provider/call-with-tools)
(def call-simple provider/call-simple)
