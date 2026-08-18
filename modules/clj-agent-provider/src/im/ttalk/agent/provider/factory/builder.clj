(ns im.ttalk.agent.provider.factory.builder
  "Provider 构建器：根据配置创建 Provider 实例

   持有内置 provider 的延迟注册逻辑（ensure-providers-registered!），
   并在此基础上提供 create-provider / create-provider-auto 等编排函数。"
  (:require [im.ttalk.agent.provider.factory.registry :as registry]
            [im.ttalk.agent.provider.factory.config :as config]
            [taoensso.timbre :as log]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 初始化 Provider 注册（延迟，避免循环依赖）
;;; ============================================================

(def ^:private builtin-providers
  "内置 provider -> [ns 符号, create 函数符号]"
  {:anthropic ['im.ttalk.agent.provider.anthropic 'im.ttalk.agent.provider.anthropic/create-provider]
   :openai    ['im.ttalk.agent.provider.openai 'im.ttalk.agent.provider.openai/create-provider]
   :zhipu     ['im.ttalk.agent.provider.zhipu 'im.ttalk.agent.provider.zhipu/create-provider]
   :mock      ['im.ttalk.agent.provider.mock 'im.ttalk.agent.provider.mock/create-mock-provider]
   :ollama    ['im.ttalk.agent.provider.ollama 'im.ttalk.agent.provider.ollama/create-provider]
   :gemini    ['im.ttalk.agent.provider.gemini 'im.ttalk.agent.provider.gemini/create-provider]
   :mistral   ['im.ttalk.agent.provider.mistral 'im.ttalk.agent.provider.mistral/create-provider]
   :deepseek  ['im.ttalk.agent.provider.deepseek 'im.ttalk.agent.provider.deepseek/create-provider]
   :minimax   ['im.ttalk.agent.provider.minimax 'im.ttalk.agent.provider.minimax/create-provider]
   :dashscope ['im.ttalk.agent.provider.dashscope 'im.ttalk.agent.provider.dashscope/create-provider]
   :xai       ['im.ttalk.agent.provider.xai 'im.ttalk.agent.provider.xai/create-provider]
   :moonshot  ['im.ttalk.agent.provider.moonshot 'im.ttalk.agent.provider.moonshot/create-provider]
   :openrouter ['im.ttalk.agent.provider.openrouter 'im.ttalk.agent.provider.openrouter/create-provider]
   :siliconflow ['im.ttalk.agent.provider.siliconflow 'im.ttalk.agent.provider.siliconflow/create-provider]
   :openai-compat ['im.ttalk.agent.provider.openai-compat-provider 'im.ttalk.agent.provider.openai-compat-provider/create-provider]})

(defn- ensure-providers-registered!
  "确保内置 Provider 已注册。

   逐个补齐缺失的内置 provider，而非「注册表非空就整体跳过」——后者会导致用户
   先 register-provider! 自定义 provider 后，内置 provider 永不注册（create-provider :openai 抛 Unknown）。
   已存在的同名（含用户覆盖）保持不动。"
  []
  (doseq [[ptype [ns-sym fn-sym]] builtin-providers
          :when (nil? (registry/get-factory ptype))]
    (require ns-sym)
    (registry/register-provider! ptype (resolve fn-sym))))

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
