(ns im.ttalk.agent.tools.provider.composite
  "CompositeToolProvider - 组合工具提供者

   聚合多个 Provider，提供统一的工具访问接口。

   支持：
   - 多 Provider 聚合
   - 冲突解决策略（:first-wins, :last-wins, :error）
   - 工具缓存
   - 动态添加/移除 Provider

   使用示例：

   ;; 创建组合 Provider
   (def composite
     (create-composite-provider
       [local-provider mcp-provider]
       :conflict-resolution :first-wins))

   ;; 动态添加 Provider
   (add-provider composite another-provider)

   ;; 列出所有工具（来自所有 Provider）
   (list-tools composite)

   ;; 执行工具（自动路由到正确的 Provider）
   (execute-tool composite :some-tool {...})"
  (:require [im.ttalk.agent.tools.protocol :as proto]))

;;; ============================================================
;;; 冲突解决策略
;;; ============================================================

(def conflict-strategies
  "支持的冲突解决策略"
  #{:first-wins   ; 使用第一个提供者的工具（默认）
    :last-wins    ; 使用最后一个提供者的工具
    :error        ; 遇到冲突抛出错误
    :all})        ; 保留所有（不去重）

;;; ============================================================
;;; CompositeToolProvider Record
;;; ============================================================

(defrecord CompositeToolProvider [name providers conflict-resolution cache]
  proto/IToolProvider

  ;; -------------------- 基本信息 --------------------

  (provider-name [_] name)

  (provider-enabled? [_] true)  ; 组合 Provider 始终启用

  ;; -------------------- 生命周期 --------------------

  (initialize-provider [this]
    ;; 初始化所有子 Provider
    (doseq [p @providers]
      (proto/initialize-provider p))
    ;; 刷新缓存
    (proto/refresh-cache this)
    this)

  (shutdown-provider [this]
    ;; 关闭所有子 Provider
    (doseq [p @providers]
      (proto/shutdown-provider p))
    ;; 清空缓存
    (reset! cache {})
    this)

  (enable-provider [this] this)  ; 组合 Provider 不可单独禁用

  (disable-provider [this] this)

  ;; -------------------- 工具管理 --------------------

  (list-tools [this]
    (proto/list-tools this {}))

  (list-tools [_ opts]
    (let [;; 从所有启用的 Provider 收集工具
          all-tools (->> @providers
                         (filter proto/provider-enabled?)
                         (mapcat #(proto/list-tools % opts)))]
      (if (= conflict-resolution :all)
        ;; 不去重
        (vec all-tools)
        ;; 按策略去重
        (->> all-tools
             (group-by proto/tool-name)
             (map (fn [[_ tools]]
                    (case conflict-resolution
                      :first-wins (first tools)
                      :last-wins (last tools)
                      :error (if (= 1 (count tools))
                               (first tools)
                               (throw (ex-info "Tool name conflict"
                                               {:tool (proto/tool-name (first tools))
                                                :providers (mapv #(-> % meta :provider) tools)})))
                      (first tools))))
             vec))))

  (get-tool [this tool-name]
    (let [k (keyword tool-name)]
      ;; 先查缓存
      (or (get @cache k)
          ;; 缓存未命中，从 Provider 查找
          (some #(proto/get-tool % tool-name)
                (filter proto/provider-enabled? @providers)))))

  (supports-tool? [_ tool-name]
    (some #(proto/supports-tool? % tool-name)
          (filter proto/provider-enabled? @providers)))

  (register-tool [_ _]
    ;; 组合 Provider 不支持直接注册
    (throw (ex-info "Cannot register tool directly to CompositeToolProvider. Register to a sub-provider instead." {})))

  (unregister-tool [_ _]
    ;; 组合 Provider 不支持直接注销
    (throw (ex-info "Cannot unregister tool from CompositeToolProvider. Unregister from a sub-provider instead." {})))

  ;; -------------------- 执行 --------------------

  (execute-tool [_ tool-name args]
    (if-let [provider (->> @providers
                           (filter proto/provider-enabled?)
                           (filter #(proto/supports-tool? % tool-name))
                           first)]
      (proto/execute-tool provider tool-name args)
      {:success false :error (str "No provider supports tool: " tool-name)}))

  ;; -------------------- Registry 功能 --------------------

  proto/IToolRegistry

  (add-provider [this provider]
    (swap! providers conj provider)
    ;; 使缓存失效
    (reset! cache {})
    this)

  (remove-provider [this provider-name]
    (swap! providers
           (fn [ps] (vec (remove #(= (proto/provider-name %) provider-name) ps))))
    ;; 使缓存失效
    (reset! cache {})
    this)

  (get-provider [_ provider-name]
    (some #(when (= (proto/provider-name %) provider-name) %)
          @providers))

  (list-providers [_] @providers)

  (refresh-cache [this]
    (reset! cache
            (->> (proto/list-tools this)
                 (map (fn [t] [(proto/tool-name t) t]))
                 (into {})))
    this)

  (find-tool-with-provider [_ tool-name]
    (some (fn [p]
            (when-let [tool (proto/get-tool p tool-name)]
              {:tool tool :provider p}))
          (filter proto/provider-enabled? @providers))))

;;; ============================================================
;;; 工厂函数
;;; ============================================================

(defn create-composite-provider
  "创建组合工具提供者

   参数:
   - providers: IToolProvider 列表
   - opts:      选项 map
     - :name               Provider 名称（默认 :composite）
     - :conflict-resolution 冲突解决策略（默认 :first-wins）

   返回: CompositeToolProvider 实例

   冲突解决策略：
   - :first-wins  使用第一个提供者的工具（优先级最高）
   - :last-wins   使用最后一个提供者的工具
   - :error       遇到同名工具时抛出错误
   - :all         保留所有工具（不去重）

   示例:
   (create-composite-provider
     [local-provider mcp-provider]
     :conflict-resolution :first-wins)"
  [providers & {:keys [name conflict-resolution]
                :or {name :composite
                     conflict-resolution :first-wins}}]
  (when-not (contains? conflict-strategies conflict-resolution)
    (throw (ex-info "Invalid conflict resolution strategy"
                    {:strategy conflict-resolution
                     :valid-strategies conflict-strategies})))
  (->CompositeToolProvider name (atom (vec providers)) conflict-resolution (atom {})))

;;; ============================================================
;;; 便捷函数
;;; ============================================================

(defn add-provider!
  "添加 Provider 到组合（便捷函数）

   参数:
   - composite: CompositeToolProvider
   - provider:  要添加的 IToolProvider

   返回: composite"
  [composite provider]
  (proto/add-provider composite provider))

(defn remove-provider!
  "从组合移除 Provider（便捷函数）

   参数:
   - composite:     CompositeToolProvider
   - provider-name: 要移除的 Provider 名称

   返回: composite"
  [composite provider-name]
  (proto/remove-provider composite provider-name))

(defn provider-count
  "获取子 Provider 数量

   参数:
   - composite: CompositeToolProvider

   返回: 整数"
  [composite]
  (count @(:providers composite)))

(defn total-tool-count
  "获取所有工具总数（去重后）

   参数:
   - composite: CompositeToolProvider

   返回: 整数"
  [composite]
  (count (proto/list-tools composite)))

(defn tools-by-provider
  "按 Provider 分组列出工具

   参数:
   - composite: CompositeToolProvider

   返回: {provider-name [tools...]}"
  [composite]
  (->> @(:providers composite)
       (filter proto/provider-enabled?)
       (map (fn [p]
              [(proto/provider-name p) (proto/list-tools p)]))
       (into {})))

;;; ============================================================
;;; Schema 导出
;;; ============================================================

(defn tools-to-schemas
  "将所有工具转换为 Schema

   参数:
   - composite: CompositeToolProvider
   - format:    :anthropic | :openai | :generic（可选）

   返回: Schema 列表"
  ([composite]
   (tools-to-schemas composite :generic))
  ([composite format]
   (mapv #(proto/tool-to-schema % format)
         (proto/list-tools composite))))
