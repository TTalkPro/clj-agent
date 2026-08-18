(ns im.ttalk.agent.provider.openrouter
  "OpenRouter Provider —— 多厂商聚合网关，OpenAI 兼容（由 base/defprovider 生成）。

   端点：https://openrouter.ai/api/v1/chat/completions（Bearer 鉴权）
   需要 OPENROUTER_API_KEY 或显式 :api-key。
   模型 id 带厂商前缀：\"openai/gpt-4o-mini\"、\"anthropic/claude-sonnet-4.5\"、
   \"deepseek/deepseek-chat\"、\"google/gemini-2.5-pro\" …

   OpenRouter 专属（都走既有通道，不新增字段）：
   - 排行榜署名头：`{:extra-headers {\"HTTP-Referer\" \"https://your.app\"
                                     \"X-Title\" \"Your App\"}}`
   - 路由/兜底等私有字段：`{:extra-body {:provider {:order [\"anthropic\"]}
                                        :models [\"openai/gpt-4o\"]}}`

   (require '[im.ttalk.agent.provider.openrouter :as openrouter])
   (def provider (openrouter/create-provider {:api-key \"sk-or-...\"}))"
  (:require [im.ttalk.agent.provider.common.base :as base]))

(set! *warn-on-reflection* true)

(def default-model "openai/gpt-4o-mini")

(base/defprovider openrouter
  :base-url "https://openrouter.ai/api/v1"
  :env-key "OPENROUTER_API_KEY"
  :default-model "openai/gpt-4o-mini"
  :require-api-key? true)
