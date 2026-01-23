(ns im.ttalk.agent.llm.factory.config
  "LLM 提供商配置管理

   管理 Provider 的配置加载、验证和合并。

   配置优先级（从高到低）：
   1. 环境变量
   2. 用户提供的 opts
   3. 默认配置

   环境变量命名规则：
   - OPENAI_API_KEY, OPENAI_BASE_URL, OPENAI_MODEL
   - ANTHROPIC_API_KEY, ANTHROPIC_BASE_URL, ANTHROPIC_MODEL
   - ZHIPU_API_KEY, ZHIPU_BASE_URL, ZHIPU_MODEL
   - 等等

   使用示例：

   (require '[im.ttalk.agent.llm.factory.config :as config])

   ;; 从环境变量加载配置
   (config/load-config-from-env :openai)

   ;; 解析配置（合并多个来源）
   (config/resolve-config :openai {:model \"gpt-4\"})"
  (:require [clojure.string :as str])
  (:import [java.lang System]))

;;; ============================================================
;;; 环境变量映射
;;; ============================================================

(def ^:private env-var-mappings
  "Provider 到环境变量前缀的映射"
  {:openai  "OPENAI"
   :anthropic  "ANTHROPIC"
   :zhipu   "ZHIPU"
   :ollama  "OLLAMA"
   :gemini  "GOOGLE"
   :mistral "MISTRAL"})

;;; ============================================================
;;; 默认配置
;;; ============================================================

(def ^:private default-configs
  "Provider 默认配置

   每个 Provider 都有一组默认值，
   用户配置和环境变量会覆盖这些值"
  (atom
    {:openai  {:api-key  ""
               :base-url "https://api.openai.com/v1"
               :timeout  60000
               :model    "gpt-4"}
     :anthropic  {:api-key  ""
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
               :model    "mistral-large-latest"}}))

;;; ============================================================
;;; 环境变量读取
;;; ============================================================

(defn- get-env-var
  "获取环境变量

   参数：
   - provider: Provider 类型
   - key:      配置键

   返回：
   环境变量值或 nil

   示例：
   (get-env-var :openai :api-key)
   ; => \"sk-...\" (来自 OPENAI_API_KEY)"
  [provider key]
  (when-let [prefix (get env-var-mappings provider)]
    (let [env-name (str prefix "_" (str/upper-case (name key)))]
      (System/getenv env-name))))

(defn load-config-from-env
  "从环境变量加载配置

   参数：
   - provider: Provider 类型

   返回：
   配置 map（合并默认配置和环境变量）

   示例：
   (load-config-from-env :openai)
   ; => {:api-key \"sk-...\"
   ;     :base-url \"https://api.openai.com/v1\"
   ;     :timeout 60000
   ;     :model \"gpt-4\"}"
  [provider]
  (let [default-config (get @default-configs provider {})
        env-keys [:api-key :base-url :timeout :model]
        env-config (->> env-keys
                        (keep (fn [k]
                                (when-let [v (get-env-var provider k)]
                                  [k (if (= k :timeout)
                                       (parse-long v)
                                       v)])))
                        (into {}))]
    (merge default-config env-config)))

;;; ============================================================
;;; 配置验证
;;; ============================================================

(defn validate-config
  "验证配置

   参数：
   - provider: Provider 类型
   - config:   配置 map

   返回：
   [:ok validated-config] 或 [:error errors]

   验证规则：
   - api-key:  必须是非空字符串（Ollama 和 Mock 除外）
   - base-url: 必须是非空字符串
   - timeout:  必须是正整数
   - model:    必须是非空字符串

   示例：
   (validate-config :openai {:api-key \"sk-...\" :model \"gpt-4\"})
   ; => [:ok {:api-key \"sk-...\" :model \"gpt-4\"}]

   (validate-config :openai {:api-key \"\"})
   ; => [:error {:api-key [\"must not be empty\"]}]"
  [provider config]
  (let [errors (atom {})]
    ;; 验证 api-key（Ollama 和 Mock 不需要）
    (when (and (not (#{:ollama :mock} provider)))
      (let [api-key (:api-key config)]
        (cond
          (nil? api-key)
          (swap! errors assoc :api-key ["is required"])

          (str/blank? api-key)
          (swap! errors assoc :api-key ["must not be empty"]))))

    ;; 验证 base-url
    (when-let [base-url (:base-url config)]
      (when (str/blank? base-url)
        (swap! errors assoc :base-url ["must not be empty"])))

    ;; 验证 timeout
    (when-let [timeout (:timeout config)]
      (when (or (not (integer? timeout))
                (<= timeout 0))
        (swap! errors assoc :timeout ["must be a positive integer"])))

    ;; 验证 model
    (when-let [model (:model config)]
      (when (str/blank? model)
        (swap! errors assoc :model ["must not be empty"])))

    (if (empty? @errors)
      [:ok config]
      [:error @errors])))

;;; ============================================================
;;; 默认配置管理
;;; ============================================================

(defn get-default-config
  "获取 Provider 的默认配置

   参数：
   - provider: Provider 类型

   返回：
   默认配置 map 或 nil

   示例：
   (get-default-config :openai)
   ; => {:api-key \"\"
   ;     :base-url \"https://api.openai.com/v1\"
   ;     :timeout 60000
   ;     :model \"gpt-4\"}"
  [provider]
  (get @default-configs provider))

(defn update-default-config!
  "更新 Provider 的默认配置

   参数：
   - provider: Provider 类型
   - config:   新的配置（会与现有配置合并）

   返回：
   更新后的配置

   示例：
   (update-default-config! :openai {:model \"gpt-4-turbo\"})"
  [provider config]
  (swap! default-configs update provider merge config)
  (get @default-configs provider))

(defn set-default-config!
  "设置 Provider 的默认配置（完全替换）

   参数：
   - provider: Provider 类型
   - config:   新的配置

   返回：
   新的配置"
  [provider config]
  (swap! default-configs assoc provider config)
  config)

;;; ============================================================
;;; 配置解析
;;; ============================================================

(defn resolve-config
  "解析配置（合并多个来源）

   配置优先级（从高到低）：
   1. 用户提供的 opts（最高）
   2. 环境变量
   3. 默认配置

   参数：
   - provider: Provider 类型
   - user-opts: 用户配置
   - use-env?: 是否使用环境变量（默认 true）

   返回：
   [:ok resolved-config] 或 [:error errors]

   示例：
   (resolve-config :openai {:model \"gpt-4\"})
   ; => [:ok {:api-key \"sk-...\" (from env)
   ;          :model \"gpt-4\"    (from opts)
   ;          :base-url \"...\"   (from default)
   ;          ...}]"
  ([provider user-opts]
   (resolve-config provider user-opts true))
  ([provider user-opts use-env?]
   (let [default-config (get @default-configs provider {})
         env-config (if use-env?
                      (load-config-from-env provider)
                      {})
         ;; 注意顺序：default -> env -> user（后面覆盖前面）
         merged-config (merge default-config env-config user-opts)]
     (validate-config provider merged-config))))

