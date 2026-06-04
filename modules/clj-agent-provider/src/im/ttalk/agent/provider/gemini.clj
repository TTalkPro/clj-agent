(ns im.ttalk.agent.provider.gemini
  "Google Gemini Provider —— 经 OpenAI 兼容端点（统一由 base/defprovider 生成）。

   需要 GOOGLE_API_KEY 或显式 :api-key。

   (require '[im.ttalk.agent.provider.gemini :as gemini])
   (def provider (gemini/create-provider {:api-key \"AIza...\"}))"
  (:require [im.ttalk.agent.provider.base :as base]))

(base/defprovider gemini
  :base-url "https://generativelanguage.googleapis.com/v1beta"
  :env-key "GOOGLE_API_KEY"
  :default-model "gemini-2.0-flash-exp"
  :require-api-key? true)
