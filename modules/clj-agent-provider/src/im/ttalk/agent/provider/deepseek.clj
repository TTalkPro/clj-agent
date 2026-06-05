(ns im.ttalk.agent.provider.deepseek
  "DeepSeek Provider —— OpenAI 兼容实现（统一由 base/defprovider 生成）。

   需要 DEEPSEEK_API_KEY 或显式 :api-key。
   模型：deepseek-chat、deepseek-reasoner。

   推理模型 deepseek-reasoner 会在 message.reasoning_content（流式为
   delta.reasoning_content）返回独立于最终答案的思维链 —— 已由统一响应层
   归一化到 :reasoning 字段（见 model.response/response-reasoning），:text 保持
   干净答案；流式时推理 token 通过回调的 :reasoning-token 单独下发。

   提示词控制（经 config 透传）：temperature/top-p/frequency-penalty/
   presence-penalty/stop/logprobs/top-logprobs/response-format/seed 等。

   (require '[im.ttalk.agent.provider.deepseek :as deepseek])
   (def provider (deepseek/create-provider {:api-key \"sk-...\"}))"
  (:require [im.ttalk.agent.provider.base :as base]))

(base/defprovider deepseek
  :base-url "https://api.deepseek.com"
  :env-key "DEEPSEEK_API_KEY"
  :default-model "deepseek-chat"
  :require-api-key? true)
