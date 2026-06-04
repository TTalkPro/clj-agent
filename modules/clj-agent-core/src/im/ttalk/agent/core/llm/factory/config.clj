(ns im.ttalk.agent.core.llm.factory.config
  "LLM 提供商配置管理（provider 无关机制）

   提供配置的加载、验证和合并机制。具体 provider 的数据
   （环境变量前缀、默认配置、是否免 api-key）由上层模块
   （如 clj-agent-llm）在初始化时通过 register-* 注入。

   配置优先级（从高到低）：
   1. 用户提供的 opts
   2. 环境变量
   3. 默认配置

   使用示例：

   (require '[im.ttalk.agent.core.llm.factory.config :as config])

   ;; 注入 provider 数据（通常由 llm 模块完成）
   (config/register-env-mapping! :openai \"OPENAI\")
   (config/register-default-config! :openai {:base-url \"...\" :model \"gpt-4\"})

   ;; 解析配置（合并多个来源）
   (config/resolve-config :openai {:model \"gpt-4\"})"
  (:require [clojure.string :as str])
  (:import [java.lang System]))

;;; ============================================================
;;; 状态（core 持有，由上层模块注入 provider 数据）
;;; ============================================================

;; Provider -> 环境变量前缀，如 {:openai "OPENAI"}
(defonce ^:private env-var-mappings (atom {}))

;; Provider -> 默认配置 map
(defonce ^:private default-configs (atom {}))

;; 免 api-key 校验的 provider 集合（如 :ollama :mock）
(defonce ^:private no-api-key-providers (atom #{}))

;;; ============================================================
;;; 注入 API
;;; ============================================================

(defn register-env-mapping!
  "注册 provider 的环境变量前缀

   参数：
   - provider: Provider 类型（关键字）
   - prefix:   环境变量前缀字符串，如 \"OPENAI\""
  [provider prefix]
  (swap! env-var-mappings assoc provider prefix)
  @env-var-mappings)

(defn register-default-config!
  "注册 provider 的默认配置（完全替换）

   参数：
   - provider: Provider 类型
   - config:   默认配置 map"
  [provider config]
  (swap! default-configs assoc provider config)
  @default-configs)

(defn register-no-api-key-provider!
  "标记某 provider 不需要 api-key 校验（如 :ollama :mock）

   参数：
   - provider: Provider 类型"
  [provider]
  (swap! no-api-key-providers conj provider)
  @no-api-key-providers)

(defn get-env-mappings
  "获取当前的环境变量前缀映射"
  []
  @env-var-mappings)

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
  (when-let [prefix (get @env-var-mappings provider)]
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
   - api-key:  必须是非空字符串（已注册为免 api-key 的 provider 除外）
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
    ;; 验证 api-key（已注册为免 api-key 的 provider 不需要）
    (when-not (contains? @no-api-key-providers provider)
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
   默认配置 map 或 nil"
  [provider]
  (get @default-configs provider))

(defn update-default-config!
  "更新 Provider 的默认配置（与现有配置合并）

   参数：
   - provider: Provider 类型
   - config:   新的配置

   返回：
   更新后的配置"
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
   [:ok resolved-config] 或 [:error errors]"
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
