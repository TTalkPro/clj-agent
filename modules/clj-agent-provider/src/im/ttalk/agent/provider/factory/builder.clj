(ns im.ttalk.agent.provider.factory.builder
  "Provider 构建器：根据配置创建 Provider 实例

   持有内置 provider 的延迟注册逻辑（ensure-providers-registered!），
   并在此基础上提供 create-provider / create-provider-auto 等编排函数。"
  (:require [im.ttalk.agent.provider.factory.registry :as registry]
            [im.ttalk.agent.provider.factory.config :as config]
            [taoensso.timbre :as log]))

;;; ============================================================
;;; 初始化 Provider 注册（延迟，避免循环依赖）
;;; ============================================================

(defn- ensure-providers-registered!
  "确保内置 Provider 已注册"
  []
  (when (empty? (registry/get-providers))
    (require '[im.ttalk.agent.provider.anthropic]
             '[im.ttalk.agent.provider.openai]
             '[im.ttalk.agent.provider.zhipu]
             '[im.ttalk.agent.provider.mock]
             '[im.ttalk.agent.provider.ollama]
             '[im.ttalk.agent.provider.gemini]
             '[im.ttalk.agent.provider.mistral]
             '[im.ttalk.agent.provider.deepseek]
             '[im.ttalk.agent.provider.minimax]
             '[im.ttalk.agent.provider.bailian])
    (registry/register-provider! :anthropic (resolve 'im.ttalk.agent.provider.anthropic/create-provider))
    (registry/register-provider! :openai    (resolve 'im.ttalk.agent.provider.openai/create-provider))
    (registry/register-provider! :zhipu     (resolve 'im.ttalk.agent.provider.zhipu/create-provider))
    (registry/register-provider! :mock      (resolve 'im.ttalk.agent.provider.mock/create-mock-provider))
    (registry/register-provider! :ollama    (resolve 'im.ttalk.agent.provider.ollama/create-provider))
    (registry/register-provider! :gemini    (resolve 'im.ttalk.agent.provider.gemini/create-provider))
    (registry/register-provider! :mistral   (resolve 'im.ttalk.agent.provider.mistral/create-provider))
    (registry/register-provider! :deepseek  (resolve 'im.ttalk.agent.provider.deepseek/create-provider))
    (registry/register-provider! :minimax   (resolve 'im.ttalk.agent.provider.minimax/create-provider))
    (registry/register-provider! :bailian   (resolve 'im.ttalk.agent.provider.bailian/create-provider))))

;;; ============================================================
;;; 基本创建
;;; ============================================================

(defn create-provider
  "根据类型创建 LLM Provider

   参数：
   - provider-type: Provider 类型（关键字）
   - opts:          配置选项（可选）

   抛出 ExceptionInfo - 如果 Provider 类型未知"
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
  "从环境变量加载配置并创建 Provider"
  [provider-type]
  (let [loaded-config (config/load-config-from-env provider-type)
        [status result] (config/validate-config provider-type loaded-config)]
    (log/info ::creating-provider-from-env {:provider provider-type})
    (case status
      :ok    (create-provider provider-type result)
      :error (throw (ex-info "Invalid provider configuration from environment"
                             {:provider provider-type
                              :errors result})))))

;;; ============================================================
;;; 默认配置创建
;;; ============================================================

(defn create-provider-with-defaults
  "合并默认配置和用户配置后创建 Provider"
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
;;; 自动配置创建（default -> env -> user）
;;; ============================================================

(defn create-provider-auto
  "自动从默认配置/环境变量/用户配置解析后创建 Provider"
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
