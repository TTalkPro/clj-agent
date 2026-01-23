(ns im.ttalk.agent.tools.tool-registry
  "ToolRegistry - 统一的工具注册中心

   提供统一的接口来管理工具和 Provider：
   - 直接注册单个工具
   - 注册整个 Provider
   - 自动合并和去重
   - 冲突解决策略

   与 registry.clj 的区别：
   - registry.clj 是全局单例，使用 atom 存储
   - tool-registry.clj 是实例化的，每个 Agent 可以有自己的 Registry

   使用示例：
   ========================================

   (require '[im.ttalk.agent.tools.tool-registry :as tr])

   ;; 创建 Registry
   (def registry (tr/create-tool-registry))

   ;; 注册单个工具
   (tr/register-tool! registry :calc \"计算器\"
     {:type \"object\" :properties {:expr {:type \"string\"}}}
     (fn [{:keys [expr]}] (str (eval (read-string expr)))))

   ;; 注册 Provider
   (tr/register-provider! registry my-mcp-provider)

   ;; 传给 Agent
   (create-agent llm registry {:prompt \"你是助手\"})

   ========================================

   冲突解决策略：
   - :local-first    本地工具优先（默认）
   - :provider-first Provider 工具优先
   - :error          冲突时抛出异常
   - :last-wins      后注册的覆盖先注册的"
  (:require [im.ttalk.agent.tools.protocol :as proto]
            [taoensso.timbre :as log]))

;;; ============================================================
;;; 常量定义
;;; ============================================================

(def ^:const default-conflict-resolution
  "默认冲突解决策略"
  :local-first)

;;; ============================================================
;;; 内部辅助函数
;;; ============================================================

(defn- execute-local-tool
  "执行本地注册的工具

   参数:
   - tool-def: 工具定义 {:name :handler ...}
   - args:     参数 map

   返回: {:success bool :result/error ...}"
  [tool-def args]
  (if-let [handler (:handler tool-def)]
    (try
      {:success true
       :result (handler args)}
      (catch Exception e
        {:success false
         :error (.getMessage e)}))
    {:success false
     :error "Tool has no handler"}))

(defn- find-tool-in-providers
  "在所有 Provider 中查找工具

   参数:
   - providers: Provider map {name -> provider}
   - tool-name: 工具名称

   返回: 工具定义或 nil"
  [providers tool-name]
  (let [kw-name (keyword tool-name)]
    (some (fn [[_ provider]]
            (proto/get-tool provider kw-name))
          providers)))

(defn- find-provider-for-tool
  "查找包含指定工具的 Provider

   参数:
   - providers: Provider map
   - tool-name: 工具名称

   返回: [provider-name provider] 或 nil"
  [providers tool-name]
  (let [kw-name (keyword tool-name)]
    (some (fn [[pname provider]]
            (when (proto/get-tool provider kw-name)
              [pname provider]))
          providers)))

(defn- resolve-tool-conflicts
  "解决工具名称冲突

   参数:
   - tools:      工具列表
   - resolution: 冲突解决策略

   返回: 去重后的工具列表"
  [tools resolution]
  (case resolution
    :error
    ;; 检查是否有重复，有则抛出异常
    (let [names (map #(keyword (:name %)) tools)
          duplicates (filter (fn [[_ v]] (> v 1))
                             (frequencies names))]
      (when (seq duplicates)
        (throw (ex-info "Tool name conflicts detected"
                        {:duplicates (keys duplicates)})))
      (vec tools))

    ;; :local-first, :first-wins - 保留第一个
    (:local-first :first-wins)
    (vec (vals (reduce (fn [acc tool]
                         (let [name (keyword (:name tool))]
                           (if (contains? acc name)
                             acc
                             (assoc acc name tool))))
                       {}
                       tools)))

    ;; :provider-first, :last-wins - 保留最后一个
    (:provider-first :last-wins)
    (vec (vals (reduce (fn [acc tool]
                         (assoc acc (keyword (:name tool)) tool))
                       {}
                       tools)))

    ;; 默认行为
    (vec tools)))

;;; ============================================================
;;; ToolRegistry Record
;;; ============================================================

(defrecord ToolRegistry [name local-tools providers conflict-resolution]

  proto/IToolProvider

  (provider-name [_] name)

  (list-tools [this]
    ;; 合并本地工具和所有 Provider 的工具
    (let [local (vals @local-tools)
          from-providers (mapcat (fn [[_ p]] (proto/list-tools p)) @providers)
          ;; 根据冲突策略决定合并顺序
          all-tools (case conflict-resolution
                      :local-first (concat local from-providers)
                      :provider-first (concat from-providers local)
                      (concat local from-providers))]
      ;; 根据冲突策略处理重复
      (resolve-tool-conflicts all-tools conflict-resolution)))

  (get-tool [this tool-name]
    (let [kw-name (keyword tool-name)]
      ;; 根据冲突策略决定查找顺序
      (case conflict-resolution
        :local-first
        (or (get @local-tools kw-name)
            (find-tool-in-providers @providers kw-name))

        :provider-first
        (or (find-tool-in-providers @providers kw-name)
            (get @local-tools kw-name))

        ;; :last-wins, :error - 从合并列表中查找
        (first (filter #(= (keyword (:name %)) kw-name)
                       (proto/list-tools this))))))

  (execute-tool [this tool-name args]
    (let [kw-name (keyword tool-name)]
      (case conflict-resolution
        ;; 本地优先
        :local-first
        (if-let [local-tool (get @local-tools kw-name)]
          (execute-local-tool local-tool args)
          (if-let [[provider-name provider] (find-provider-for-tool @providers kw-name)]
            (do
              (log/debug "[Registry] Executing tool" kw-name "from provider" provider-name)
              (proto/execute-tool provider kw-name args))
            {:success false :error (str "Tool not found: " tool-name)}))

        ;; Provider 优先
        :provider-first
        (if-let [[provider-name provider] (find-provider-for-tool @providers kw-name)]
          (do
            (log/debug "[Registry] Executing tool" kw-name "from provider" provider-name)
            (proto/execute-tool provider kw-name args))
          (if-let [local-tool (get @local-tools kw-name)]
            (execute-local-tool local-tool args)
            {:success false :error (str "Tool not found: " tool-name)}))

        ;; 其他策略 - 先本地后 Provider
        (if-let [local-tool (get @local-tools kw-name)]
          (execute-local-tool local-tool args)
          (if-let [[provider-name provider] (find-provider-for-tool @providers kw-name)]
            (do
              (log/debug "[Registry] Executing tool" kw-name "from provider" provider-name)
              (proto/execute-tool provider kw-name args))
            {:success false :error (str "Tool not found: " tool-name)})))))

  (register-tool [this tool-def]
    (let [tool-name (keyword (:name tool-def))]
      ;; 检查冲突
      (when (and (= conflict-resolution :error)
                 (or (get @local-tools tool-name)
                     (find-tool-in-providers @providers tool-name)))
        (throw (ex-info "Tool already exists"
                        {:tool-name tool-name
                         :conflict-resolution conflict-resolution})))
      (swap! local-tools assoc tool-name tool-def)
      this))

  (unregister-tool [this tool-name]
    (swap! local-tools dissoc (keyword tool-name))
    this)

  proto/IToolRegistry

  (add-provider [this provider]
    (let [pname (proto/provider-name provider)]
      ;; 检查冲突
      (when (and (= conflict-resolution :error)
                 (get @providers pname))
        (throw (ex-info "Provider already registered"
                        {:provider-name pname})))
      (swap! providers assoc pname provider)
      this))

  (remove-provider [this provider-name]
    (swap! providers dissoc provider-name)
    this)

  (get-provider [this provider-name]
    (get @providers provider-name))

  (list-providers [_]
    (keys @providers))

  (refresh-cache [this]
    ;; ToolRegistry 是即时查询，不需要缓存刷新
    this)

  (find-tool-with-provider [this tool-name]
    (let [kw-name (keyword tool-name)]
      ;; 先检查本地工具
      (if-let [local-tool (get @local-tools kw-name)]
        {:tool local-tool :provider this}
        ;; 然后检查 Provider
        (when-let [[pname provider] (find-provider-for-tool @providers kw-name)]
          {:tool (proto/get-tool provider kw-name)
           :provider provider})))))

;;; ============================================================
;;; 工厂函数
;;; ============================================================

(defn create-tool-registry
  "创建工具注册中心

   参数（可选）：
   - :name                注册中心名称（默认 :tool-registry）
   - :conflict-resolution 冲突解决策略：
     - :local-first     本地工具优先（默认）
     - :provider-first  Provider 工具优先
     - :error           冲突时抛出异常
     - :last-wins       后注册的覆盖先注册的

   返回: ToolRegistry 实例

   示例:
   (create-tool-registry)
   (create-tool-registry :conflict-resolution :error)"
  [& {:keys [name conflict-resolution]
      :or {name :tool-registry
           conflict-resolution default-conflict-resolution}}]
  (->ToolRegistry name (atom {}) (atom {}) conflict-resolution))

;;; ============================================================
;;; 便捷 API - 工具注册
;;; ============================================================

(defn register-tool!
  "注册单个工具到 Registry

   参数:
   - registry:    ToolRegistry 实例
   - tool-name:   工具名称（keyword）
   - description: 工具描述
   - schema:      参数 schema
   - handler:     处理函数 (fn [args] -> result)

   返回: registry

   示例:
   (register-tool! registry :calc \"执行计算\"
     {:type \"object\" :properties {:expr {:type \"string\"}}}
     (fn [{:keys [expr]}] (str (eval (read-string expr)))))"
  [registry tool-name description input-schema handler]
  (let [tool-def {:name (keyword tool-name)
                  :description description
                  :input-schema input-schema
                  :handler handler}]
    (proto/register-tool registry tool-def)))

(defn register-provider!
  "注册 Provider 到 Registry

   参数:
   - registry: ToolRegistry 实例
   - provider: IToolProvider 实例

   返回: registry

   示例:
   (register-provider! registry my-mcp-provider)"
  [registry provider]
  (proto/add-provider registry provider))

(defn unregister-tool!
  "从 Registry 移除工具

   参数:
   - registry:  ToolRegistry 实例
   - tool-name: 工具名称

   返回: registry"
  [registry tool-name]
  (proto/unregister-tool registry tool-name))

(defn unregister-provider!
  "从 Registry 移除 Provider

   参数:
   - registry:      ToolRegistry 实例
   - provider-name: Provider 名称

   返回: registry"
  [registry provider-name]
  (proto/remove-provider registry provider-name))

;;; ============================================================
;;; 查询 API
;;; ============================================================

(defn list-tools
  "列出所有工具

   参数:
   - registry: ToolRegistry 实例

   返回: 工具定义列表"
  [registry]
  (proto/list-tools registry))

(defn list-providers
  "列出所有已注册的 Provider

   参数:
   - registry: ToolRegistry 实例

   返回: Provider 名称列表"
  [registry]
  (proto/list-providers registry))

(defn get-tool
  "获取指定工具

   参数:
   - registry:  ToolRegistry 实例
   - tool-name: 工具名称

   返回: 工具定义或 nil"
  [registry tool-name]
  (proto/get-tool registry tool-name))

(defn get-provider
  "获取指定 Provider

   参数:
   - registry:      ToolRegistry 实例
   - provider-name: Provider 名称

   返回: Provider 实例或 nil"
  [registry provider-name]
  (proto/get-provider registry provider-name))

(defn tool-count
  "获取工具总数

   参数:
   - registry: ToolRegistry 实例

   返回: 工具数量"
  [registry]
  (count (proto/list-tools registry)))

(defn provider-count
  "获取 Provider 数量

   参数:
   - registry: ToolRegistry 实例

   返回: Provider 数量"
  [registry]
  (count @(:providers registry)))

(defn local-tool-count
  "获取本地工具数量

   参数:
   - registry: ToolRegistry 实例

   返回: 本地工具数量"
  [registry]
  (count @(:local-tools registry)))

;;; ============================================================
;;; 执行 API
;;; ============================================================

(defn execute-tool
  "执行工具

   参数:
   - registry:  ToolRegistry 实例
   - tool-name: 工具名称
   - args:      参数 map

   返回: {:success bool :result/error ...}"
  [registry tool-name args]
  (proto/execute-tool registry tool-name args))

;;; ============================================================
;;; 批量操作
;;; ============================================================

(defn register-tools!
  "批量注册工具

   参数:
   - registry: ToolRegistry 实例
   - tools:    工具定义列表 [{:name :description :input-schema :handler} ...]

   返回: registry"
  [registry tools]
  (doseq [{:keys [name description input-schema handler]} tools]
    (register-tool! registry name description input-schema handler))
  registry)

(defn register-providers!
  "批量注册 Provider

   参数:
   - registry:  ToolRegistry 实例
   - providers: Provider 列表

   返回: registry"
  [registry providers]
  (doseq [provider providers]
    (register-provider! registry provider))
  registry)

;;; ============================================================
;;; 统计和调试
;;; ============================================================

(defn registry-stats
  "获取 Registry 统计信息

   参数:
   - registry: ToolRegistry 实例

   返回: 统计信息 map"
  [registry]
  {:name (:name registry)
   :conflict-resolution (:conflict-resolution registry)
   :local-tools-count (local-tool-count registry)
   :providers-count (provider-count registry)
   :total-tools-count (tool-count registry)
   :providers (vec (list-providers registry))
   :local-tool-names (vec (keys @(:local-tools registry)))})

(defn registry?
  "检查是否为 ToolRegistry 实例

   参数:
   - x: 任意值

   返回: boolean"
  [x]
  (instance? ToolRegistry x))

;;; ============================================================
;;; 便捷构造器
;;; ============================================================

(defn from-tools
  "从工具列表创建 Registry

   参数:
   - tools: 工具定义列表

   返回: ToolRegistry 实例"
  [tools]
  (let [registry (create-tool-registry)]
    (register-tools! registry tools)))

(defn from-providers
  "从 Provider 列表创建 Registry

   参数:
   - providers: Provider 列表

   返回: ToolRegistry 实例"
  [providers]
  (let [registry (create-tool-registry)]
    (register-providers! registry providers)))

(defn from-tools-and-providers
  "从工具和 Provider 创建 Registry

   参数:
   - tools:     工具定义列表
   - providers: Provider 列表
   - opts:      选项（同 create-tool-registry）

   返回: ToolRegistry 实例"
  [tools providers & opts]
  (let [registry (apply create-tool-registry opts)]
    (-> registry
        (register-tools! tools)
        (register-providers! providers))))
