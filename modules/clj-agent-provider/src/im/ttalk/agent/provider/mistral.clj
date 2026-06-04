(ns im.ttalk.agent.provider.mistral
  "Mistral AI Provider —— OpenAI 兼容实现（统一由 base/defprovider 生成）。

   需要 MISTRAL_API_KEY 或显式 :api-key。

   (require '[im.ttalk.agent.provider.mistral :as mistral])
   (def provider (mistral/create-provider {:api-key \"...\"}))"
  (:require [im.ttalk.agent.provider.base :as base]))

(base/defprovider mistral
  :base-url "https://api.mistral.ai/v1"
  :env-key "MISTRAL_API_KEY"
  :default-model "mistral-large-latest"
  :require-api-key? true)
