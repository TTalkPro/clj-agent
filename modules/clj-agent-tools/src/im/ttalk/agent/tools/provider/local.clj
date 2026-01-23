(ns im.ttalk.agent.tools.provider.local
  "LocalToolProvider - 本地工具提供者

   管理本地注册的工具，是最基础的 Provider 实现。

   使用示例：

   ;; 创建 Provider
   (def provider (create-local-provider))

   ;; 注册工具
   (register-tool! provider
     (simple/make-tool :calc \"计算\" {...} calc-fn))

   ;; 或使用便捷函数
   (-> (create-local-provider)
       (register-tool! :calc \"计算\" {...} calc-fn)
       (register-tool! :time \"时间\" {...} time-fn))

   ;; 列出工具
   (list-tools provider)

   ;; 执行工具
   (execute-tool provider :calc {:expression \"1+1\"})"
  (:require [im.ttalk.agent.tools.protocol :as proto]
            [im.ttalk.agent.tools.impl.simple :as simple]))

;;; ============================================================
;;; LocalToolProvider Record
;;; ============================================================

(defrecord LocalToolProvider [name enabled tools]
  proto/IToolProvider

  ;; -------------------- 基本信息 --------------------

  (provider-name [_] name)

  (provider-enabled? [_] @enabled)

  ;; -------------------- 生命周期 --------------------

  (initialize-provider [this] this)

  (shutdown-provider [this]
    (reset! tools {})
    this)

  (enable-provider [this]
    (reset! enabled true)
    this)

  (disable-provider [this]
    (reset! enabled false)
    this)

  ;; -------------------- 工具管理 --------------------

  (list-tools [this]
    (proto/list-tools this {}))

  (list-tools [_ opts]
    (let [all-tools (vals @tools)]
      (if-let [cat (:category opts)]
        (filter #(= (proto/tool-category %) cat) all-tools)
        all-tools)))

  (get-tool [_ tool-name]
    (get @tools (keyword tool-name)))

  (supports-tool? [_ tool-name]
    (contains? @tools (keyword tool-name)))

  (register-tool [this tool]
    (let [tool-name (proto/tool-name tool)]
      (swap! tools assoc tool-name tool))
    this)

  (unregister-tool [this tool-name]
    (swap! tools dissoc (keyword tool-name))
    this)

  ;; -------------------- 执行 --------------------

  (execute-tool [this tool-name args]
    (if-let [tool (proto/get-tool this tool-name)]
      ;; 先验证参数
      (let [{:keys [valid errors]} (proto/tool-validate tool args)]
        (if valid
          (proto/tool-execute tool args)
          {:success false :error (str "Validation failed: " (first errors))}))
      {:success false :error (str "Tool not found: " tool-name)})))

;;; ============================================================
;;; 工厂函数
;;; ============================================================

(defn create-local-provider
  "创建本地工具提供者

   参数:
   - name: 提供者名称（默认 :local）

   返回: LocalToolProvider 实例

   示例:
   (def provider (create-local-provider))
   (def provider (create-local-provider :my-tools))"
  ([] (create-local-provider :local))
  ([name]
   (->LocalToolProvider name (atom true) (atom {}))))

;;; ============================================================
;;; 工具注册便捷函数
;;; ============================================================

(defn register-tool!
  "注册工具到 Provider

   支持多种调用格式：

   格式 1: ITool 实例
   (register-tool! provider my-tool)

   格式 2: 工具定义 map
   (register-tool! provider {:name :calc :description \"...\" :handler fn})

   格式 3: 参数列表
   (register-tool! provider :calc \"计算\" {:type \"object\" ...} calc-fn)

   返回: provider"
  ([provider tool]
   (cond
     ;; ITool 实例
     (proto/tool? tool)
     (proto/register-tool provider tool)

     ;; Map -> 转为 SimpleTool
     (map? tool)
     (proto/register-tool provider (simple/from-map tool))

     :else
     (throw (ex-info "Invalid tool format" {:tool tool}))))
  ([provider name description parameters handler]
   (proto/register-tool provider
     (simple/make-tool name description parameters handler))))

(defn register-tools!
  "批量注册工具

   参数:
   - provider: LocalToolProvider
   - tools: ITool 实例列表或工具定义 map 列表

   返回: provider

   示例:
   (register-tools! provider
     [{:name :calc :description \"...\" :handler fn}
      {:name :time :description \"...\" :handler fn}])"
  [provider tools]
  (doseq [tool tools]
    (register-tool! provider tool))
  provider)

;;; ============================================================
;;; 从工具列表创建 Provider
;;; ============================================================

(defn from-tool-list
  "从工具列表创建 LocalToolProvider

   参数:
   - tools: ITool 实例列表或工具定义 map 列表
   - name:  Provider 名称（可选，默认 :local）

   返回: LocalToolProvider 实例

   示例:
   (from-tool-list
     [{:name :calc :description \"计算\" :parameters {...} :handler fn}])"
  ([tools] (from-tool-list tools :local))
  ([tools name]
   (let [provider (create-local-provider name)]
     (register-tools! provider tools)
     provider)))

(defn from-registry-tools
  "从旧 registry 格式的工具列表创建 Provider

   参数:
   - tools: 旧格式工具列表 [{:name :description :parameters :handler ...}]

   返回: LocalToolProvider 实例

   用于兼容性迁移"
  [tools]
  (let [provider (create-local-provider :legacy)]
    (doseq [tool tools]
      (proto/register-tool provider
        (simple/tool-map->simple-tool tool)))
    provider))

;;; ============================================================
;;; 工具查询便捷函数
;;; ============================================================

(defn tool-names
  "获取所有工具名称

   参数:
   - provider: LocalToolProvider

   返回: keyword 列表"
  [provider]
  (keys @(:tools provider)))

(defn tool-count
  "获取工具数量

   参数:
   - provider: LocalToolProvider

   返回: 整数"
  [provider]
  (count @(:tools provider)))

(defn find-tools-by-category
  "按分类查找工具

   参数:
   - provider: LocalToolProvider
   - category: 分类关键字

   返回: ITool 列表"
  [provider category]
  (proto/list-tools provider {:category category}))

(defn get-categories
  "获取所有工具分类

   参数:
   - provider: LocalToolProvider

   返回: keyword 集合"
  [provider]
  (->> (proto/list-tools provider)
       (map proto/tool-category)
       (into #{})))

;;; ============================================================
;;; Schema 导出
;;; ============================================================

(defn tools-to-schemas
  "将 Provider 中的所有工具转换为 Schema

   参数:
   - provider: LocalToolProvider
   - format:   :anthropic | :openai | :generic（可选，默认 :generic）

   返回: Schema 列表"
  ([provider]
   (tools-to-schemas provider :generic))
  ([provider format]
   (mapv #(proto/tool-to-schema % format)
         (proto/list-tools provider))))
