(ns im.ttalk.agent.provider.siliconflow
  "SiliconFlow（硅基流动）Provider —— OpenAI 兼容实现（由 base/defprovider 生成）。

   端点：https://api.siliconflow.cn/v1/chat/completions（Bearer 鉴权）
   需要 SILICONFLOW_API_KEY 或显式 :api-key。
   模型 id 带组织前缀：\"Qwen/Qwen3-8B\"、\"deepseek-ai/DeepSeek-V3\"、
   \"THUDM/GLM-4-9B-0414\" …（国际站 api.siliconflow.com 请覆盖 :base-url）

   embedding 走独立实例（provider/embeddings，默认 BAAI/bge-m3）：
   `(embeddings/create-provider :siliconflow)`

   (require '[im.ttalk.agent.provider.siliconflow :as siliconflow])
   (def provider (siliconflow/create-provider {:api-key \"sk-...\"}))"
  (:require [im.ttalk.agent.provider.common.base :as base]))

(set! *warn-on-reflection* true)

(def default-model "Qwen/Qwen3-8B")

(base/defprovider siliconflow
  :base-url "https://api.siliconflow.cn/v1"
  :env-key "SILICONFLOW_API_KEY"
  :default-model "Qwen/Qwen3-8B"
  :require-api-key? true)
