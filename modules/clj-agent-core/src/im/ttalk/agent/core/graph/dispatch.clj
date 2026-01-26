(ns im.ttalk.agent.core.graph.dispatch
  "Graph Dispatch - 动态并行分发

   Dispatch 机制允许在运行时动态创建并行分支。
   每个 dispatch 代表一个独立的执行分支，携带自己的输入参数。

   与静态 fanout 的区别:
   - fanout: 编译时确定的固定并行目标
   - dispatch: 运行时根据状态动态创建的并行分支

   使用示例:

   ;; 在条件边路由函数中返回 dispatch
   (conditional :process-items
     (fn [state]
       ;; 为每个 item 创建独立的 dispatch
       (map #(dispatch :process-item {:item %})
            (:items state))))

   ;; 使用 fan-out 批量创建
   (conditional :fetch
     (fn [state]
       (fan-out :process (:urls state)
         (fn [url] {:url url}))))

   ;; 带索引的 fan-out
   (conditional :batch
     (fn [state]
       (fan-out-indexed :worker (:tasks state)
         (fn [idx task] {:index idx :task task}))))")

;;; ============================================================
;;; Dispatch 创建
;;; ============================================================

(defn dispatch
  "创建 dispatch（动态并行分支）

   参数:
   - node-id: 目标节点 ID
   - input: 分支输入参数 map（可选）
   - opts: 可选参数
     :id       - 自定义 dispatch ID（默认生成 UUID）
     :metadata - 额外元数据

   返回: dispatch 对象"
  ([node-id]
   (dispatch node-id {} {}))
  ([node-id input]
   (dispatch node-id input {}))
  ([node-id input opts]
   {:__dispatch__ true
    :node node-id
    :input (or input {})
    :id (or (:id opts) (str (random-uuid)))
    :metadata (or (:metadata opts) {})}))

(defn dispatch?
  "检查是否为 dispatch 对象"
  [x]
  (and (map? x) (true? (:__dispatch__ x))))

;;; ============================================================
;;; Dispatch 属性访问
;;; ============================================================

(defn dispatch-node
  "获取 dispatch 的目标节点"
  [d]
  (:node d))

(defn dispatch-input
  "获取 dispatch 的输入参数"
  [d]
  (:input d))

(defn dispatch-id
  "获取 dispatch 的 ID"
  [d]
  (:id d))

(defn dispatch-metadata
  "获取 dispatch 的元数据"
  [d]
  (:metadata d {}))

;;; ============================================================
;;; 批量创建
;;; ============================================================

(defn fan-out
  "批量创建 dispatch（Map over items）

   参数:
   - node-id: 目标节点 ID
   - items: 数据项集合
   - transform-fn: 转换函数 (fn [item] -> input-map)

   返回: dispatch 列表

   示例:
   (fan-out :process-url urls (fn [url] {:url url}))
   ;; => [{:__dispatch__ true :node :process-url :input {:url \"http://...\"} ...}
   ;;     {...}]"
  [node-id items transform-fn]
  (mapv #(dispatch node-id (transform-fn %)) items))

(defn fan-out-indexed
  "带索引的批量创建 dispatch

   参数:
   - node-id: 目标节点 ID
   - items: 数据项集合
   - transform-fn: 转换函数 (fn [index item] -> input-map)

   返回: dispatch 列表

   示例:
   (fan-out-indexed :worker tasks (fn [i t] {:index i :task t}))"
  [node-id items transform-fn]
  (vec (map-indexed
         (fn [idx item]
           (dispatch node-id (transform-fn idx item)))
         items)))

(defn fan-out-with-id
  "创建带自定义 ID 的 dispatch 批量

   参数:
   - node-id: 目标节点 ID
   - items: 数据项集合
   - transform-fn: 转换函数 (fn [item] -> {:input ... :id ...})

   返回: dispatch 列表"
  [node-id items transform-fn]
  (mapv (fn [item]
          (let [{:keys [input id metadata]} (transform-fn item)]
            (dispatch node-id input {:id id :metadata metadata})))
        items))

;;; ============================================================
;;; Dispatch 分组
;;; ============================================================

(defn group-by-node
  "按目标节点分组 dispatch

   参数:
   - dispatches: dispatch 列表

   返回: {node-id -> [dispatch...]}"
  [dispatches]
  (group-by :node dispatches))

(defn group-inputs-by-node
  "按目标节点分组 dispatch，只保留 input

   参数:
   - dispatches: dispatch 列表

   返回: {node-id -> [input...]}"
  [dispatches]
  (reduce
    (fn [m d]
      (update m (:node d) (fnil conj []) (:input d)))
    {}
    dispatches))

;;; ============================================================
;;; Dispatch 工具
;;; ============================================================

(defn merge-dispatches
  "合并多个 dispatch 结果（用于调试）

   参数:
   - dispatches: dispatch 列表

   返回: 合并后的信息 map"
  [dispatches]
  {:count (count dispatches)
   :nodes (distinct (map :node dispatches))
   :ids (map :id dispatches)})

(defn filter-by-node
  "筛选指定节点的 dispatch

   参数:
   - dispatches: dispatch 列表
   - node-id: 目标节点 ID

   返回: 匹配的 dispatch 列表"
  [dispatches node-id]
  (filterv #(= node-id (:node %)) dispatches))

;;; ============================================================
;;; 与 Edge 集成
;;; ============================================================

(defn separate-targets-and-dispatches
  "从混合列表中分离普通目标和 dispatch

   参数:
   - targets: 混合列表（可能包含 keyword 和 dispatch）

   返回: {:targets [node-ids...] :dispatches [dispatch...]}"
  [targets]
  (reduce
    (fn [acc item]
      (if (dispatch? item)
        (update acc :dispatches conj item)
        (update acc :targets conj item)))
    {:targets [] :dispatches []}
    targets))
