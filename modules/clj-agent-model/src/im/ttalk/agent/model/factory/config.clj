(ns im.ttalk.agent.model.factory.config
  "Provider 配置管理：加载、验证、合并

   机制（环境变量读取/校验/三级合并）与 provider 专属数据
   （环境变量前缀、默认配置、免 api-key 列表）都在此 model 空间内。

   配置优先级（从高到低）：
   1. 用户提供的 opts
   2. 环境变量
   3. 默认配置"
  (:require [clojure.string :as str])
  (:import [java.lang System]))

;;; ============================================================
;;; 状态
;;; ============================================================

(defonce ^:private env-var-mappings (atom {}))
(defonce ^:private default-configs (atom {}))
(defonce ^:private no-api-key-providers (atom #{}))

;;; ============================================================
;;; 注入 API
;;; ============================================================

(defn register-env-mapping!
  "注册 provider 的环境变量前缀（如 :openai \"OPENAI\"）"
  [provider prefix]
  (swap! env-var-mappings assoc provider prefix)
  @env-var-mappings)

(defn register-default-config!
  "注册 provider 的默认配置（完全替换）"
  [provider config]
  (swap! default-configs assoc provider config)
  @default-configs)

(defn register-no-api-key-provider!
  "标记某 provider 不需要 api-key 校验（如 :ollama :mock）"
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
  "获取环境变量，如 (get-env-var :openai :api-key) => OPENAI_API_KEY"
  [provider key]
  (when-let [prefix (get @env-var-mappings provider)]
    (let [env-name (str prefix "_" (str/upper-case (name key)))]
      (System/getenv env-name))))

(defn load-config-from-env
  "从环境变量加载配置（合并默认配置和环境变量）"
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
  "验证配置，返回 [:ok config] 或 [:error errors]

   - api-key:  非空字符串（已注册为免 api-key 的 provider 除外）
   - base-url: 非空字符串
   - timeout:  正整数
   - model:    非空字符串"
  [provider config]
  (let [errors (atom {})]
    (when-not (contains? @no-api-key-providers provider)
      (let [api-key (:api-key config)]
        (cond
          (nil? api-key)
          (swap! errors assoc :api-key ["is required"])

          (str/blank? api-key)
          (swap! errors assoc :api-key ["must not be empty"]))))

    (when-let [base-url (:base-url config)]
      (when (str/blank? base-url)
        (swap! errors assoc :base-url ["must not be empty"])))

    (when-let [timeout (:timeout config)]
      (when (or (not (integer? timeout))
                (<= timeout 0))
        (swap! errors assoc :timeout ["must be a positive integer"])))

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
  "获取 Provider 的默认配置"
  [provider]
  (get @default-configs provider))

(defn update-default-config!
  "更新 Provider 的默认配置（与现有配置合并）"
  [provider config]
  (swap! default-configs update provider merge config)
  (get @default-configs provider))

(defn set-default-config!
  "设置 Provider 的默认配置（完全替换）"
  [provider config]
  (swap! default-configs assoc provider config)
  config)

;;; ============================================================
;;; 配置解析
;;; ============================================================

(defn resolve-config
  "解析配置（default -> env -> user，后者覆盖前者），
   返回 [:ok config] 或 [:error errors]"
  ([provider user-opts]
   (resolve-config provider user-opts true))
  ([provider user-opts use-env?]
   (let [default-config (get @default-configs provider {})
         env-config (if use-env?
                      (load-config-from-env provider)
                      {})
         merged-config (merge default-config env-config user-opts)]
     (validate-config provider merged-config))))

;;; ============================================================
;;; Provider 专属数据 + 注入
;;; ============================================================

(def ^:private builtin-env-mappings
  {:openai  "OPENAI"
   :anthropic "ANTHROPIC"
   :zhipu   "ZHIPU"
   :ollama  "OLLAMA"
   :gemini  "GOOGLE"
   :mistral "MISTRAL"})

(def ^:private builtin-default-configs
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

(defonce ^:private _init
  (do
    (doseq [[provider prefix] builtin-env-mappings]
      (register-env-mapping! provider prefix))
    (doseq [[provider config] builtin-default-configs]
      (register-default-config! provider config))
    (register-no-api-key-provider! :ollama)
    (register-no-api-key-provider! :mock)
    true))
