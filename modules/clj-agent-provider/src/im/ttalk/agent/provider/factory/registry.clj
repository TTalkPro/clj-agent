(ns im.ttalk.agent.provider.factory.registry
  "LLM 提供商注册表

   管理 LLM Provider 的注册、注销和查询。

   本命名空间与具体 Provider 实现无关：它只维护一个
   {:provider-type factory-fn} 的注册表，工厂函数由上层模块
   （如 clj-agent-model）在初始化时注入。

   使用示例：

   (require '[im.ttalk.agent.provider.factory.registry :as registry])

   ;; 注册新的 Provider
   (registry/register-provider! :custom (fn [] (->CustomProvider)))

   ;; 检查 Provider 是否存在
   (registry/provider-exists? :openai) ; => true

   ;; 获取支持的 Provider 列表
   (registry/supported-providers) ; => [:anthropic :openai :zhipu ...]")

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 注册表
;;; ============================================================

;; LLM 提供商工厂注册表
;; 格式：{:provider-type factory-fn}
;; factory-fn 为无参函数，返回 Provider 实例
(defonce ^:private providers (atom {}))

;;; ============================================================
;;; 内部函数
;;; ============================================================

(defn get-providers
  "获取当前的 Provider 注册表

   返回：
   所有已注册 Provider 的 map"
  []
  @providers)

(defn get-factory
  "获取 Provider 的工厂函数

   参数：
   - provider-type: Provider 类型（关键字）

   返回：
   工厂函数或 nil"
  [provider-type]
  (get @providers provider-type))

;;; ============================================================
;;; 公共 API
;;; ============================================================

(defn register-provider!
  "注册新的 LLM 提供商

   参数：
   - name:    Provider 名称（关键字）
   - factory: 工厂函数，签名 (fn [] provider) 或 (fn [opts] provider)

   返回：
   更新后的 Provider 注册表

   示例：
   (register-provider! :gemini
     (fn [] (->GeminiProvider)))"
  [name factory]
  (swap! providers assoc name factory)
  @providers)

(defn unregister-provider!
  "注销 LLM 提供商

   参数：
   - name: Provider 名称（关键字）

   返回：
   更新后的 Provider 注册表

   示例：
   (unregister-provider! :custom)"
  [name]
  (swap! providers dissoc name)
  @providers)

(defn provider-exists?
  "检查 Provider 是否已注册

   参数：
   - name: Provider 名称（关键字）

   返回：
   boolean

   示例：
   (provider-exists? :anthropic) ; => true
   (provider-exists? :unknown) ; => false"
  [name]
  (contains? @providers name))

(defn supported-providers
  "返回支持的 Provider 列表

   返回：
   关键字序列

   示例：
   (supported-providers)
   ; => (:anthropic :openai :zhipu :ollama :gemini :mistral :mock)"
  []
  (keys @providers))

(defn reset-registry!
  "重置 Provider 注册表（主要用于测试）

   参数：
   - new-providers: 新的 Provider map（可选）

   返回：
   更新后的注册表"
  ([]
   (reset! providers {})
   @providers)
  ([new-providers]
   (reset! providers new-providers)
   @providers))
