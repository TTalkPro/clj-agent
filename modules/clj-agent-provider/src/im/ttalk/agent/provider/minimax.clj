(ns im.ttalk.agent.provider.minimax
  "MiniMax Provider —— OpenAI 兼容实现，自定义 chatcompletion_v2 端点
   （统一由 base/defprovider 生成）。

   需要 MINIMAX_API_KEY 或显式 :api-key。
   模型：abab6.5s-chat、abab6.5-chat 等。

   (require '[im.ttalk.agent.provider.minimax :as minimax])
   (def provider (minimax/create-provider {:api-key \"...\"}))"
  (:require [im.ttalk.agent.provider.base :as base]))

(base/defprovider minimax
  :base-url "https://api.minimax.chat"
  :endpoint "/v1/text/chatcompletion_v2"
  :env-key "MINIMAX_API_KEY"
  :default-model "abab6.5s-chat"
  :require-api-key? true)
