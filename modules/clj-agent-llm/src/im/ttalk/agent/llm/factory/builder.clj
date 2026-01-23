(ns im.ttalk.agent.llm.factory.builder
  "LLM 提供商构建器

   根据配置创建 Provider 实例。

   使用示例：

   (require '[im.ttalk.agent.llm.factory.builder :as builder])

   ;; 创建基本 Provider
   (builder/create-provider :openai)

   ;; 带配置创建
   (builder/create-provider :openai {:api-key \"sk-...\"})

   ;; 从环境变量创建
   (builder/create-provider-from-env :openai)

   ;; 自动解析配置并创建
   (builder/create-provider-auto :openai {:model \"gpt-4\"})"
  (:require [im.ttalk.agent.llm.factory.registry :as registry]
            [im.ttalk.agent.llm.factory.config :as config]
            [taoensso.timbre :as log]))

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
;;; 基本创建函数
;;; ============================================================

(defn create-provider
  "根据类型创建 LLM Provider

   参数：
   - provider-type: Provider 类型（关键字）
   - opts:          配置选项（可选）

   返回：
   Provider 实例

   抛出：
   ExceptionInfo - 如果 Provider 类型未知

   示例：
   (create-provider :anthropic)
   (create-provider :openai {:api-key \"sk-...\"})"
  ([provider-type]
   (ensure-providers-registered!)
   (if-let [factory (registry/get-factory provider-type)]
     (factory)
     (throw (ex-info "Unknown LLM provider"
                     {:provider provider-type
                      :supported (registry/supported-providers)}))))
  ([provider-type opts]
   (ensure-providers-registered!)
   (if-let [factory (registry/get-factory provider-type)]
     (factory opts)
     (throw (ex-info "Unknown LLM provider"
                     {:provider provider-type
                      :supported (registry/supported-providers)})))))

;;; ============================================================
;;; 环境变量创建
;;; ============================================================

(defn create-provider-from-env
  "从环境变量创建 Provider

   自动从环境变量加载配置并创建 Provider 实例。

   参数：
   - provider-type: Provider 类型

   返回：
   Provider 实例

   抛出：
   ExceptionInfo - 如果配置验证失败

   示例：
   (create-provider-from-env :openai)
   ; 使用 OPENAI_API_KEY, OPENAI_BASE_URL 等环境变量"
  [provider-type]
  (let [loaded-config (config/load-config-from-env provider-type)
        [status result] (config/validate-config provider-type loaded-config)]
    (log/info ::creating-provider-from-env
              {:provider provider-type})
    (case status
      :ok    (create-provider provider-type result)
      :error (throw (ex-info "Invalid provider configuration from environment"
                             {:provider provider-type
                              :errors result})))))

;;; ============================================================
;;; 默认配置创建
;;; ============================================================

(defn create-provider-with-defaults
  "使用默认配置创建 Provider

   合并默认配置和用户配置。

   参数：
   - provider-type: Provider 类型
   - user-opts:     用户配置（可选）

   返回：
   Provider 实例

   抛出：
   ExceptionInfo - 如果配置验证失败

   示例：
   (create-provider-with-defaults :openai)
   (create-provider-with-defaults :openai {:model \"gpt-4-turbo\"})"
  ([provider-type]
   (create-provider-with-defaults provider-type {}))
  ([provider-type user-opts]
   (let [default-config (or (config/get-default-config provider-type) {})
         merged-config (merge default-config user-opts)
         [status result] (config/validate-config provider-type merged-config)]
     (case status
       :ok    (create-provider provider-type result)
       :error (throw (ex-info "Invalid provider configuration"
                              {:provider provider-type
                               :errors result}))))))

;;; ============================================================
;;; 自动配置创建
;;; ============================================================

(defn create-provider-auto
  "自动创建 Provider（智能配置解析）

   自动从多个来源解析配置：
   1. 默认配置（最低优先级）
   2. 环境变量
   3. 用户配置（最高优先级）

   参数：
   - provider-type: Provider 类型
   - opts:          用户配置（可选）
   - use-env?:      是否使用环境变量（默认 true）

   返回：
   Provider 实例

   抛出：
   ExceptionInfo - 如果配置解析失败

   示例：
   ;; 使用环境变量 + 用户配置
   (create-provider-auto :openai {:model \"gpt-4\"})

   ;; 仅使用用户配置（不读取环境变量）
   (create-provider-auto :openai {:api-key \"sk-...\"} false)"
  ([provider-type]
   (create-provider-auto provider-type {}))
  ([provider-type opts]
   (create-provider-auto provider-type opts true))
  ([provider-type opts use-env?]
   (let [[status result] (config/resolve-config provider-type opts use-env?)]
     (case status
       :ok    (create-provider provider-type result)
       :error (throw (ex-info "Failed to resolve provider configuration"
                              {:provider provider-type
                               :errors result}))))))

