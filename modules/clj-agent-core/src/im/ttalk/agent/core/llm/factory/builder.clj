(ns im.ttalk.agent.core.llm.factory.builder
  "LLM 提供商构建器（provider 无关编排）

   根据配置创建 Provider 实例。本命名空间不认识任何具体 provider，
   每个构建函数的首个参数是 `ensure-registered!` 回调——一个无参函数，
   由上层模块（如 clj-agent-llm）实现，用于在查表前确保所需 provider
   已注册到 core 的 registry。

   使用示例（通常由 llm 模块包装后再暴露）：

   (require '[im.ttalk.agent.core.llm.factory.builder :as builder])

   (builder/create-provider ensure-fn :openai)
   (builder/create-provider ensure-fn :openai {:api-key \"sk-...\"})
   (builder/create-provider-auto ensure-fn :openai {:model \"gpt-4\"})"
  (:require [im.ttalk.agent.core.llm.factory.registry :as registry]
            [im.ttalk.agent.core.llm.factory.config :as config]
            [taoensso.timbre :as log]))

;;; ============================================================
;;; 基本创建函数
;;; ============================================================

(defn create-provider
  "根据类型创建 LLM Provider

   参数：
   - ensure-registered!: 无参回调，确保 provider 已注册
   - provider-type:      Provider 类型（关键字）
   - opts:               配置选项（可选）

   返回：
   Provider 实例

   抛出：
   ExceptionInfo - 如果 Provider 类型未知"
  ([ensure-registered! provider-type]
   (ensure-registered!)
   (if-let [factory (registry/get-factory provider-type)]
     (factory)
     (throw (ex-info "Unknown LLM provider"
                     {:provider provider-type
                      :supported (registry/supported-providers)}))))
  ([ensure-registered! provider-type opts]
   (ensure-registered!)
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
   - ensure-registered!: 无参回调，确保 provider 已注册
   - provider-type:      Provider 类型

   返回：
   Provider 实例

   抛出：
   ExceptionInfo - 如果配置验证失败"
  [ensure-registered! provider-type]
  (let [loaded-config (config/load-config-from-env provider-type)
        [status result] (config/validate-config provider-type loaded-config)]
    (log/info ::creating-provider-from-env
              {:provider provider-type})
    (case status
      :ok    (create-provider ensure-registered! provider-type result)
      :error (throw (ex-info "Invalid provider configuration from environment"
                             {:provider provider-type
                              :errors result})))))

;;; ============================================================
;;; 默认配置创建
;;; ============================================================

(defn create-provider-with-defaults
  "使用默认配置创建 Provider（合并默认配置和用户配置）

   参数：
   - ensure-registered!: 无参回调，确保 provider 已注册
   - provider-type:      Provider 类型
   - user-opts:          用户配置（可选）

   返回：
   Provider 实例

   抛出：
   ExceptionInfo - 如果配置验证失败"
  ([ensure-registered! provider-type]
   (create-provider-with-defaults ensure-registered! provider-type {}))
  ([ensure-registered! provider-type user-opts]
   (let [default-config (or (config/get-default-config provider-type) {})
         merged-config (merge default-config user-opts)
         [status result] (config/validate-config provider-type merged-config)]
     (case status
       :ok    (create-provider ensure-registered! provider-type result)
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
   - ensure-registered!: 无参回调，确保 provider 已注册
   - provider-type:      Provider 类型
   - opts:               用户配置（可选）
   - use-env?:           是否使用环境变量（默认 true）

   返回：
   Provider 实例

   抛出：
   ExceptionInfo - 如果配置解析失败"
  ([ensure-registered! provider-type]
   (create-provider-auto ensure-registered! provider-type {}))
  ([ensure-registered! provider-type opts]
   (create-provider-auto ensure-registered! provider-type opts true))
  ([ensure-registered! provider-type opts use-env?]
   (let [[status result] (config/resolve-config provider-type opts use-env?)]
     (case status
       :ok    (create-provider ensure-registered! provider-type result)
       :error (throw (ex-info "Failed to resolve provider configuration"
                              {:provider provider-type
                               :errors result}))))))
