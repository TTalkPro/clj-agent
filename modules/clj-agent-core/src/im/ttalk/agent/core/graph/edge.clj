(ns im.ttalk.agent.core.graph.edge
  "Graph Edge - 边定义

   边定义节点之间的转移关系，支持三种类型：

   1. Direct Edge（直接边）
      无条件转移到单一目标节点
      (direct :node-a :node-b)

   2. Fanout Edge（扇出边）
      无条件转移到多个目标节点（静态并行）
      (fanout :node-a [:node-b :node-c])

   3. Conditional Edge（条件边）
      基于状态的动态路由
      (conditional :node-a router-fn)
      (conditional :node-a router-fn :route-map {...})

   使用示例:

   ;; 直接边
   (direct :start :process)

   ;; 扇出边（静态并行）
   (fanout :fetch [:process-a :process-b :process-c])

   ;; 条件边
   (conditional :check
     (fn [state]
       (if (:valid state) :continue :error)))

   ;; 条件边 + 路由映射
   (conditional :router
     (fn [state] (:next-step state))
     :route-map {:step-a :node-a
                 :step-b :node-b})"
  (:require [im.ttalk.agent.core.graph.node :as node]))

;;; ============================================================
;;; 边类型常量
;;; ============================================================

(def ^:const DIRECT :direct)
(def ^:const FANOUT :fanout)
(def ^:const CONDITIONAL :conditional)

;;; ============================================================
;;; 边创建
;;; ============================================================

(defn direct
  "创建直接边（无条件转移到单一目标）

   参数:
   - from: 源节点 ID
   - to: 目标节点 ID

   返回: 边定义 map"
  [from to]
  {:type DIRECT
   :from from
   :to to})

(defn fanout
  "创建扇出边（无条件转移到多个目标，静态并行）

   参数:
   - from: 源节点 ID
   - targets: 目标节点 ID 列表

   返回: 边定义 map"
  [from targets]
  (when-not (sequential? targets)
    (throw (ex-info "fanout targets 必须是序列" {:targets targets})))
  {:type FANOUT
   :from from
   :to (vec targets)})

(defn conditional
  "创建条件边（基于状态的动态路由）

   router-fn 签名: (fn [state] -> target)
   - 返回单个节点 ID: 转移到该节点
   - 返回节点 ID 列表: 并行转移
   - 返回 dispatch 列表: 动态并行（见 dispatch.clj）

   参数:
   - from: 源节点 ID
   - router-fn: 路由函数
   - opts: 可选参数
     :route-map - 路由映射 {key -> node-id}，用于将 router-fn 返回值映射到节点

   返回: 边定义 map"
  [from router-fn & {:keys [route-map]}]
  (when-not (fn? router-fn)
    (throw (ex-info "router-fn 必须是函数" {:from from})))
  {:type CONDITIONAL
   :from from
   :router router-fn
   :route-map route-map})

;;; ============================================================
;;; 边类型判断
;;; ============================================================

(defn edge?
  "检查是否为有效的边定义"
  [x]
  (and (map? x)
       (contains? #{DIRECT FANOUT CONDITIONAL} (:type x))
       (keyword? (:from x))))

(defn direct?
  "检查是否为直接边"
  [edge]
  (= DIRECT (:type edge)))

(defn fanout?
  "检查是否为扇出边"
  [edge]
  (= FANOUT (:type edge)))

(defn conditional?
  "检查是否为条件边"
  [edge]
  (= CONDITIONAL (:type edge)))

;;; ============================================================
;;; 边属性访问
;;; ============================================================

(defn edge-from
  "获取边的源节点"
  [edge]
  (:from edge))

(defn edge-to
  "获取边的目标节点（仅适用于 direct 边）"
  [edge]
  (:to edge))

(defn edge-targets
  "获取边的目标节点列表（仅适用于 fanout 边）"
  [edge]
  (:to edge))

(defn edge-router
  "获取边的路由函数（仅适用于 conditional 边）"
  [edge]
  (:router edge))

(defn edge-route-map
  "获取边的路由映射（仅适用于 conditional 边）"
  [edge]
  (:route-map edge))

;;; ============================================================
;;; 边解析
;;; ============================================================

(defn- apply-route-map
  "应用路由映射，将路由结果转换为实际目标"
  [result route-map]
  (if route-map
    (if (sequential? result)
      (mapv #(get route-map % %) result)
      (get route-map result result))
    result))

(defn- normalize-targets
  "规范化目标为统一格式 {:targets [...] :dispatches [...]}"
  [result]
  (cond
    ;; 单个节点 ID
    (keyword? result)
    {:targets [result] :dispatches []}

    ;; END 节点
    (= result node/END)
    {:targets [node/END] :dispatches []}

    ;; 节点列表或混合列表
    (sequential? result)
    (let [dispatches (filterv #(and (map? %) (:__dispatch__ %)) result)
          targets (filterv #(or (keyword? %) (= % node/END)) result)]
      {:targets targets :dispatches dispatches})

    ;; 单个 dispatch
    (and (map? result) (:__dispatch__ result))
    {:targets [] :dispatches [result]}

    ;; nil 或无效值 -> 无目标
    :else
    {:targets [] :dispatches []}))

(defn resolve-edge
  "解析边，返回目标节点和 dispatch

   参数:
   - edge: 边定义
   - state: 当前状态（用于条件边）

   返回:
   {:targets [node-ids...]      ;; 普通目标节点
    :dispatches [dispatch...]}  ;; 动态并行 dispatch"
  [edge state]
  (case (:type edge)
    :direct
    {:targets [(:to edge)] :dispatches []}

    :fanout
    {:targets (:to edge) :dispatches []}

    :conditional
    (let [router (:router edge)
          route-map (:route-map edge)
          result (router state)
          result (apply-route-map result route-map)]
      (normalize-targets result))

    ;; 未知类型
    {:targets [] :dispatches []}))

;;; ============================================================
;;; 辅助函数
;;; ============================================================

(defn edges-from
  "从边列表中筛选指定源节点的边

   参数:
   - edges: 边列表
   - node-id: 源节点 ID

   返回: 匹配的边列表"
  [edges node-id]
  (filterv #(= node-id (:from %)) edges))

(defn all-targets
  "获取边列表中所有可能的目标节点

   参数:
   - edges: 边列表

   返回: 目标节点 ID 集合"
  [edges]
  (into #{}
        (mapcat (fn [edge]
                  (case (:type edge)
                    :direct [(:to edge)]
                    :fanout (:to edge)
                    :conditional (when-let [rm (:route-map edge)]
                                   (vals rm))
                    [])))
        edges))
