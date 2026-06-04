(ns im.ttalk.agent.provider.openai
  "OpenAI Provider —— OpenAI 兼容实现（统一由 base/defprovider 生成）。

   生成：default-config、call-openai{,-stream,-async,-stream-async}、create-provider。

   (require '[im.ttalk.agent.provider.openai :as openai])
   (def provider (openai/create-provider {:api-key \"sk-...\"}))"
  (:require [im.ttalk.agent.provider.base :as base]))

(base/defprovider openai
  :base-url "https://api.openai.com/v1"
  :env-key "OPENAI_API_KEY"
  :default-model "gpt-4")
