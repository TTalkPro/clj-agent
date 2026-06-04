(ns im.ttalk.agent.provider.zhipu
  "智谱 GLM Provider —— OpenAI 兼容实现（统一由 base/defprovider 生成）。

   (require '[im.ttalk.agent.provider.zhipu :as zhipu])
   (def provider (zhipu/create-provider {:api-key \"...\"}))"
  (:require [im.ttalk.agent.provider.base :as base]))

(base/defprovider zhipu
  :base-url "https://open.bigmodel.cn/api/paas/v4"
  :env-key "ZHIPU_API_KEY"
  :default-model "glm-4")
