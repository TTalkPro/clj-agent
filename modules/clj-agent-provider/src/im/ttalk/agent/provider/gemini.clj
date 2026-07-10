(ns im.ttalk.agent.provider.gemini
  "Google Gemini Provider —— 经 OpenAI 兼容端点（统一由 base/defprovider 生成）。

   需要 GOOGLE_API_KEY 或显式 :api-key。

   (require '[im.ttalk.agent.provider.gemini :as gemini])
   (def provider (gemini/create-provider {:api-key \"AIza...\"}))"
  (:require [im.ttalk.agent.provider.common.base :as base]))

(set! *warn-on-reflection* true)

;; Google 的 OpenAI 兼容端点为 .../v1beta/openai/chat/completions，
;; base-url 必须含 /openai（默认 endpoint 再拼 /chat/completions）。
(base/defprovider gemini
  :base-url "https://generativelanguage.googleapis.com/v1beta/openai"
  :env-key "GOOGLE_API_KEY"
  :default-model "gemini-2.0-flash-exp"
  :require-api-key? true)
