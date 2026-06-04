(ns im.ttalk.agent.llm.factory.config
  "LLM 提供商配置数据（机制已迁移到 core 模块）

   本命名空间负责把各 provider 的专属数据（环境变量前缀、默认配置、
   免 api-key 列表）注入 core 的配置机制。配置的加载/校验/合并逻辑
   见 im.ttalk.agent.core.llm.factory.config。"
  (:require [im.ttalk.agent.core.llm.factory.config :as core]))

;;; ============================================================
;;; Provider 专属数据
;;; ============================================================

(def ^:private env-var-mappings
  "Provider 到环境变量前缀的映射"
  {:openai  "OPENAI"
   :anthropic "ANTHROPIC"
   :zhipu   "ZHIPU"
   :ollama  "OLLAMA"
   :gemini  "GOOGLE"
   :mistral "MISTRAL"})

(def ^:private default-configs
  "Provider 默认配置"
  {:openai  {:api-key  ""
             :base-url "https://api.openai.com/v1"
             :timeout  60000
             :model    "gpt-4"}
   :anthropic {:api-key  ""
               :base-url "https://api.anthropic.com/v1"
               :timeout  60000
               :model    "claude-3-5-sonnet-20241022"}
   :zhipu   {:api-key  ""
             :base-url "https://open.bigmodel.cn/api/paas/v4"
             :timeout  60000
             :model    "glm-4"}
   :ollama  {:base-url "http://localhost:11434"
             :timeout  120000
             :model    "llama2"}
   :gemini  {:api-key  ""
             :base-url "https://generativelanguage.googleapis.com"
             :timeout  120000
             :model    "gemini-2.0-flash-exp"}
   :mistral {:api-key  ""
             :base-url "https://api.mistral.ai"
             :timeout  120000
             :model    "mistral-large-latest"}})

;;; ============================================================
;;; 注入 core（加载本命名空间时执行一次）
;;; ============================================================

(defonce ^:private _init
  (do
    (doseq [[provider prefix] env-var-mappings]
      (core/register-env-mapping! provider prefix))
    (doseq [[provider config] default-configs]
      (core/register-default-config! provider config))
    (core/register-no-api-key-provider! :ollama)
    (core/register-no-api-key-provider! :mock)
    true))
