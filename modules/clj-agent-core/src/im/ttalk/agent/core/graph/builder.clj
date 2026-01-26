(ns im.ttalk.agent.core.graph.builder
  "Graph Builder - 图构建器

   提供链式 API 构建图定义，编译为可执行结构。

   使用示例:

   (-> (graph :my-workflow)
       (add-node :fetch fetch-handler)
       (add-node :process process-handler)
       (add-edge START :fetch)
       (add-edge :fetch :process)
       (add-conditional-edge :process
         (fn [state]
           (if (:done state) END :fetch)))
       (set-entry :fetch)
       (compile))

   支持的边类型:
   - add-edge: 直接边（单目标）或扇出边（多目标）
   - add-conditional-edge: 条件边（动态路由）"
  (:refer-clojure :exclude [compile])
  (:require [im.ttalk.agent.core.graph.node :as node]
            [im.ttalk.agent.core.graph.edge :as edge]))

;;; ============================================================
;;; Builder 创建
;;; ============================================================

(defn graph
  "创建图构建器

   参数:
   - name: 图名称（keyword）

   返回: builder map"
  [name]
  {:__graph_builder__ true
   :name name
   :nodes {}
   :edges []
   :entry nil
   :max-iterations 100})

(defn builder?
  "检查是否为有效的 builder"
  [x]
  (and (map? x) (:__graph_builder__ x)))

;;; ============================================================
;;; 节点操作
;;; ============================================================

(defn add-node
  "添加节点到图

   参数:
   - g: builder
   - id: 节点 ID（keyword）
   - handler: 处理函数 (fn [state vertex-input] -> result)
   - opts: 可选参数（传递给 node/create-node）

   返回: 更新后的 builder"
  [g id handler & {:as opts}]
  (let [node-def (apply node/create-node id handler (mapcat identity opts))]
    (assoc-in g [:nodes id] node-def)))

(defn add-node-def
  "添加预定义的节点

   参数:
   - g: builder
   - node-def: 节点定义 map（由 node/create-node 创建）

   返回: 更新后的 builder"
  [g node-def]
  (let [id (:id node-def)]
    (assoc-in g [:nodes id] node-def)))

(defn remove-node
  "移除节点

   参数:
   - g: builder
   - id: 节点 ID

   返回: 更新后的 builder"
  [g id]
  (-> g
      (update :nodes dissoc id)
      ;; 同时移除相关的边
      (update :edges (fn [edges]
                       (filterv #(and (not= id (:from %))
                                      (not= id (:to %)))
                                edges)))))

;;; ============================================================
;;; 边操作
;;; ============================================================

(defn add-edge
  "添加边到图

   根据 to 参数自动判断边类型:
   - keyword: 直接边
   - vector/list: 扇出边

   参数:
   - g: builder
   - from: 源节点 ID
   - to: 目标节点 ID 或 ID 列表

   返回: 更新后的 builder"
  [g from to]
  (let [edge-def (if (sequential? to)
                   (edge/fanout from (vec to))
                   (edge/direct from to))]
    (update g :edges conj edge-def)))

(defn add-conditional-edge
  "添加条件边到图

   参数:
   - g: builder
   - from: 源节点 ID
   - router-fn: 路由函数 (fn [state] -> target)
   - opts: 可选参数
     :route-map - 路由映射 {key -> node-id}

   返回: 更新后的 builder"
  [g from router-fn & {:keys [route-map]}]
  (let [edge-def (edge/conditional from router-fn :route-map route-map)]
    (update g :edges conj edge-def)))

(defn add-edge-def
  "添加预定义的边

   参数:
   - g: builder
   - edge-def: 边定义 map（由 edge/* 函数创建）

   返回: 更新后的 builder"
  [g edge-def]
  (update g :edges conj edge-def))

;;; ============================================================
;;; 图配置
;;; ============================================================

(defn set-entry
  "设置图的入口节点

   参数:
   - g: builder
   - node-id: 入口节点 ID

   返回: 更新后的 builder"
  [g node-id]
  (assoc g :entry node-id))

(defn set-max-iterations
  "设置最大迭代次数

   参数:
   - g: builder
   - n: 最大迭代次数

   返回: 更新后的 builder"
  [g n]
  (assoc g :max-iterations n))

;;; ============================================================
;;; 验证
;;; ============================================================

(defn- validate-nodes
  "验证节点定义"
  [nodes]
  (when (empty? nodes)
    (throw (ex-info "图至少需要一个节点" {})))
  (doseq [[id node-def] nodes]
    (when-not (= id (:id node-def))
      (throw (ex-info "节点 ID 不匹配" {:key id :node-id (:id node-def)})))))

(defn- validate-edges
  "验证边定义"
  [edges nodes]
  (let [node-ids (set (keys nodes))
        special-nodes #{node/START node/END}
        valid-ids (clojure.set/union node-ids special-nodes)]
    (doseq [e edges]
      (when-not (or (contains? valid-ids (:from e))
                    (= node/START (:from e)))
        (throw (ex-info "边的源节点不存在"
                        {:from (:from e) :available valid-ids})))
      ;; 验证直接边和扇出边的目标
      (when (edge/direct? e)
        (when-not (contains? valid-ids (:to e))
          (throw (ex-info "边的目标节点不存在"
                          {:to (:to e) :available valid-ids}))))
      (when (edge/fanout? e)
        (doseq [t (:to e)]
          (when-not (contains? valid-ids t)
            (throw (ex-info "扇出边的目标节点不存在"
                            {:target t :available valid-ids}))))))))

(defn- validate-entry
  "验证入口节点"
  [entry nodes]
  (when (and entry (not (contains? nodes entry)))
    (throw (ex-info "入口节点不存在" {:entry entry :available (keys nodes)}))))

(defn validate
  "验证 builder

   参数:
   - g: builder

   返回: g（验证通过）
   异常: 验证失败时抛出 ex-info"
  [g]
  (validate-nodes (:nodes g))
  (validate-edges (:edges g) (:nodes g))
  (validate-entry (:entry g) (:nodes g))
  g)

;;; ============================================================
;;; 编译
;;; ============================================================

(defn- build-vertex
  "构建扁平化顶点结构

   将节点定义和出边合并为顶点。"
  [node-def edges]
  (let [node-id (:id node-def)
        out-edges (edge/edges-from edges node-id)]
    {:id node-id
     :handler (:handler node-def)
     :metadata (:metadata node-def {})
     :timeout (:timeout node-def)
     :retry (:retry node-def 0)
     :edges out-edges
     :halted true}))  ;; 初始状态为 halted

(defn- build-vertices
  "构建所有顶点"
  [nodes edges entry]
  (reduce-kv
    (fn [m id node-def]
      (let [vertex (build-vertex node-def edges)
            ;; 入口节点初始为非 halted
            vertex (if (= id entry)
                     (assoc vertex :halted false)
                     vertex)]
        (assoc m id vertex)))
    {}
    nodes))

(defn compile
  "编译 builder 为可执行的图规范

   执行验证并生成最终的图定义。

   参数:
   - g: builder

   返回: graph-spec map

   异常: 验证失败时抛出 ex-info"
  [g]
  ;; 验证
  (validate g)
  ;; 编译
  (let [nodes (:nodes g)
        edges (:edges g)
        entry (:entry g)
        ;; 如果没有显式入口，尝试从 START 边推断
        entry (or entry
                  (some (fn [e]
                          (when (= node/START (:from e))
                            (if (edge/direct? e)
                              (:to e)
                              (first (:to e)))))
                        edges))
        vertices (build-vertices nodes edges entry)]
    {:__graph_spec__ true
     :name (:name g)
     :vertices vertices
     :entry entry
     :max-iterations (:max-iterations g)
     ;; 保留原始边定义（用于调试）
     :edges edges}))

;;; ============================================================
;;; 便捷方法
;;; ============================================================

(defn linear
  "创建线性图（节点按顺序执行）

   参数:
   - name: 图名称
   - node-specs: 节点规范列表 [{:id :a :handler fn} ...]

   返回: 编译后的 graph-spec"
  [name node-specs]
  (let [ids (mapv :id node-specs)
        g (reduce
            (fn [g spec]
              (add-node g (:id spec) (:handler spec)
                        :metadata (or (:metadata spec) {})
                        :timeout (:timeout spec)))
            (graph name)
            node-specs)
        ;; 添加边: START -> first, a -> b -> c -> ... -> END
        g (add-edge g node/START (first ids))
        g (reduce
            (fn [g [from to]]
              (add-edge g from to))
            g
            (partition 2 1 ids))
        g (add-edge g (last ids) node/END)
        g (set-entry g (first ids))]
    (compile g)))

;;; ============================================================
;;; 重导出常用符号
;;; ============================================================

(def START node/START)
(def END node/END)
