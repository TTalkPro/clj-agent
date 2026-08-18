(ns im.ttalk.agent.provider.xai
  "xAI (Grok) Provider —— OpenAI 兼容实现（统一由 base/defprovider 生成）。

   端点：https://api.x.ai/v1/chat/completions（Bearer 鉴权）
   需要 XAI_API_KEY 或显式 :api-key。
   模型：grok-4.5、grok-4.3、grok-4.20-reasoning、grok-4.20-non-reasoning、grok-latest 等。

   继承 common.openai-compat 的全部通用能力（工具调用 / 流式 / 结构化输出 /
   :reasoning-effort / :extra-body 逃生通道）；grok 的推理内容经
   message.reasoning_content 返回，已由统一响应层归一化到 :reasoning。

   (require '[im.ttalk.agent.provider.xai :as xai])
   (def provider (xai/create-provider {:api-key \"xai-...\"}))"
  (:require [im.ttalk.agent.provider.common.base :as base]))

(set! *warn-on-reflection* true)

(def default-model "grok-4.5")

(base/defprovider xai
  :base-url "https://api.x.ai/v1"
  :env-key "XAI_API_KEY"
  :default-model "grok-4.5"
  :require-api-key? true)
