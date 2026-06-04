(ns im.ttalk.agent.provider.deepseek
  "DeepSeek Provider —— OpenAI 兼容实现（统一由 base/defprovider 生成）。

   需要 DEEPSEEK_API_KEY 或显式 :api-key。
   模型：deepseek-chat、deepseek-reasoner。

   (require '[im.ttalk.agent.provider.deepseek :as deepseek])
   (def provider (deepseek/create-provider {:api-key \"sk-...\"}))"
  (:require [im.ttalk.agent.provider.base :as base]))

(base/defprovider deepseek
  :base-url "https://api.deepseek.com"
  :env-key "DEEPSEEK_API_KEY"
  :default-model "deepseek-chat"
  :require-api-key? true)
