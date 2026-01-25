(ns im.ttalk.agent.llm.core.protocol
  "LLM Provider 协议定义（已弃用 - 向后兼容层）

   ⚠️ 此命名空间已弃用，将在未来版本中删除。
   请直接使用 im.ttalk.agent.core.kernel.provider。

   迁移指南：
   将 (require '[im.ttalk.agent.llm.core.protocol :as proto])
   改为 (require '[im.ttalk.agent.core.kernel.provider :as proto])"
  {:deprecated "1.0.0"
   :superseded-by 'im.ttalk.agent.core.kernel.provider}
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
