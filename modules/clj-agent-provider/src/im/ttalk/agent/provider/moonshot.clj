(ns im.ttalk.agent.provider.moonshot
  "Moonshot（Kimi）Provider —— OpenAI 兼容实现（统一由 base/defprovider 生成）。

   端点：https://api.moonshot.cn/v1/chat/completions（Bearer 鉴权，国内站）。
   国际站请显式覆盖 base-url：
   `(create-provider {:base-url \"https://api.moonshot.ai/v1\"})`
   或设环境变量 MOONSHOT_BASE_URL（经 factory/create-provider-auto 生效）。

   需要 MOONSHOT_API_KEY 或显式 :api-key。
   模型：kimi-k2.5、kimi-k2.6、kimi-k2.7-code、kimi-k3、moonshot-v1-{8k,32k,128k} 等。

   Kimi 专属（经 config 透传，存在才发送——见 common.openai-compat/build-params）：
   - :thinking {:type \"enabled\"|\"disabled\" :budget_tokens n}（K2.5+ 思考开关）
   - :reasoning-effort（K3 目前只支持 \"max\"）
   - 其余私有字段走 :extra-body

   (require '[im.ttalk.agent.provider.moonshot :as moonshot])
   (def provider (moonshot/create-provider {:api-key \"sk-...\"}))"
  (:require [im.ttalk.agent.provider.common.base :as base]))

(set! *warn-on-reflection* true)

(def default-model "kimi-k2.5")

(base/defprovider moonshot
  :base-url "https://api.moonshot.cn/v1"
  :env-key "MOONSHOT_API_KEY"
  :default-model "kimi-k2.5"
  :require-api-key? true)
