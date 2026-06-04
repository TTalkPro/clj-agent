(ns im.ttalk.agent.llm.factory.builder
  "LLM 提供商构建器（编排已迁移到 core 模块）

   本命名空间负责：
   1. 持有 provider 的延迟注册逻辑 `ensure-providers-registered!`
      （硬编码具体 provider，属 llm 模块知识）；
   2. 把该回调注入 core builder，保持原有公共 API 不变。"
  (:require [im.ttalk.agent.core.llm.factory.builder :as core]
            [im.ttalk.agent.core.llm.factory.registry :as registry]
            ;; 加载以触发 provider 配置数据注入 core
            [im.ttalk.agent.llm.factory.config]))

;;; ============================================================
;;; 初始化 Provider 注册
;;; ============================================================

(defn- ensure-providers-registered!
  "确保默认 Provider 已注册

   延迟加载以避免循环依赖"
  []
  (when (empty? (registry/get-providers))
    ;; 延迟 require 以避免循环依赖
    (require '[im.ttalk.agent.llm.provider.anthropic :as anthropic]
             '[im.ttalk.agent.llm.provider.openai :as openai]
             '[im.ttalk.agent.llm.provider.zhipu :as zhipu]
             '[im.ttalk.agent.llm.provider.mock :as mock]
             '[im.ttalk.agent.llm.provider.ollama :as ollama]
             '[im.ttalk.agent.llm.provider.gemini :as gemini]
             '[im.ttalk.agent.llm.provider.mistral :as mistral])
    (registry/register-provider! :anthropic (resolve 'im.ttalk.agent.llm.provider.anthropic/create-provider))
    (registry/register-provider! :openai    (resolve 'im.ttalk.agent.llm.provider.openai/create-provider))
    (registry/register-provider! :zhipu     (resolve 'im.ttalk.agent.llm.provider.zhipu/create-provider))
    (registry/register-provider! :mock      (resolve 'im.ttalk.agent.llm.provider.mock/create-mock-provider))
    (registry/register-provider! :ollama    (resolve 'im.ttalk.agent.llm.provider.ollama/create-provider))
    (registry/register-provider! :gemini    (resolve 'im.ttalk.agent.llm.provider.gemini/create-provider))
    (registry/register-provider! :mistral   (resolve 'im.ttalk.agent.llm.provider.mistral/create-provider))))

;;; ============================================================
;;; 公共 API（委托 core，注入 ensure-providers-registered!）
;;; ============================================================

(defn create-provider
  "根据类型创建 LLM Provider

   参数：
   - provider-type: Provider 类型（关键字）
   - opts:          配置选项（可选）"
  ([provider-type]
   (core/create-provider ensure-providers-registered! provider-type))
  ([provider-type opts]
   (core/create-provider ensure-providers-registered! provider-type opts)))

(defn create-provider-from-env
  "从环境变量创建 Provider"
  [provider-type]
  (core/create-provider-from-env ensure-providers-registered! provider-type))

(defn create-provider-with-defaults
  "使用默认配置创建 Provider"
  ([provider-type]
   (core/create-provider-with-defaults ensure-providers-registered! provider-type))
  ([provider-type user-opts]
   (core/create-provider-with-defaults ensure-providers-registered! provider-type user-opts)))

(defn create-provider-auto
  "自动创建 Provider（智能配置解析）"
  ([provider-type]
   (core/create-provider-auto ensure-providers-registered! provider-type))
  ([provider-type opts]
   (core/create-provider-auto ensure-providers-registered! provider-type opts))
  ([provider-type opts use-env?]
   (core/create-provider-auto ensure-providers-registered! provider-type opts use-env?)))
