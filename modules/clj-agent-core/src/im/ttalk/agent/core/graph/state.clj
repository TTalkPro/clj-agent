(ns im.ttalk.agent.core.graph.state
  "Graph State - 不可变状态管理

   提供图执行过程中的状态容器，支持：
   - 不可变操作（所有函数返回新状态）
   - 用户上下文隔离存储
   - 批量更新

   使用示例:

   (def s (create {:input \"data\"}))
   (def s2 (set-val s :result 42))
   (get-val s2 :result)  ;; => 42

   ;; 用户上下文
   (def s3 (set-context s2 {:user-id \"u123\"}))
   (get-context s3 :user-id)  ;; => \"u123\"")

;;; ============================================================
;;; 内部常量
;;; ============================================================

(def ^:private user-context-key
  "用户上下文的内部存储键"
  ::user-context)

;;; ============================================================
;;; 状态创建
;;; ============================================================

(defn create
  "创建 graph state

   参数:
   - initial-map: 初始状态 map（可选）

   返回: state map"
  ([]
   (create {}))
  ([initial-map]
   (if (map? initial-map)
     initial-map
     (throw (ex-info "initial-map 必须是 map" {:got (type initial-map)})))))

;;; ============================================================
;;; 基础操作
;;; ============================================================

(defn get-val
  "获取状态值

   参数:
   - state: state map
   - key: 键
   - default: 默认值（可选）

   返回: 值或默认值"
  ([state key]
   (get state key))
  ([state key default]
   (get state key default)))

(defn set-val
  "设置状态值

   参数:
   - state: state map
   - key: 键
   - value: 值

   返回: 新的 state map"
  [state key value]
  (assoc state key value))

(defn set-many
  "批量设置状态值

   参数:
   - state: state map
   - kvs: 键值对集合（map 或 [[k v] ...] 序列）

   返回: 新的 state map"
  [state kvs]
  (if (map? kvs)
    (merge state kvs)
    (reduce (fn [s [k v]] (assoc s k v)) state kvs)))

(defn update-val
  "更新状态值

   参数:
   - state: state map
   - key: 键
   - f: 更新函数 (fn [old-value] -> new-value)
   - args: 传递给 f 的额外参数

   返回: 新的 state map"
  [state key f & args]
  (apply update state key f args))

(defn delete-val
  "删除状态值

   参数:
   - state: state map
   - key: 键

   返回: 新的 state map"
  [state key]
  (dissoc state key))

(defn contains-key?
  "检查键是否存在

   参数:
   - state: state map
   - key: 键

   返回: boolean"
  [state key]
  (contains? state key))

;;; ============================================================
;;; 用户上下文（隔离存储）
;;; ============================================================

(defn set-context
  "设置用户上下文（整体替换）

   用户上下文与主状态隔离，用于存储用户自定义数据。

   参数:
   - state: state map
   - ctx-map: 用户上下文 map

   返回: 新的 state map"
  [state ctx-map]
  (assoc state user-context-key ctx-map))

(defn get-context
  "获取用户上下文值

   参数:
   - state: state map
   - key: 上下文键（可选，不提供则返回整个上下文）

   返回: 上下文值或整个上下文 map"
  ([state]
   (get state user-context-key {}))
  ([state key]
   (get-in state [user-context-key key]))
  ([state key default]
   (get-in state [user-context-key key] default)))

(defn update-context
  "更新用户上下文

   参数:
   - state: state map
   - key: 上下文键
   - f: 更新函数

   返回: 新的 state map"
  [state key f & args]
  (apply update-in state [user-context-key key] f args))

(defn merge-context
  "合并用户上下文

   参数:
   - state: state map
   - ctx-map: 要合并的上下文 map

   返回: 新的 state map"
  [state ctx-map]
  (update state user-context-key merge ctx-map))

;;; ============================================================
;;; 工具函数
;;; ============================================================

(defn keys-of
  "获取状态的所有键（不包括内部键）

   参数:
   - state: state map

   返回: 键序列"
  [state]
  (remove #{user-context-key} (keys state)))

(defn to-map
  "转换为普通 map（不包括内部键）

   参数:
   - state: state map

   返回: 普通 map"
  [state]
  (dissoc state user-context-key))

(defn from-map
  "从普通 map 创建 state（别名）"
  [m]
  (create m))

;;; ============================================================
;;; 路径操作
;;; ============================================================

(defn get-in-val
  "获取嵌套值

   参数:
   - state: state map
   - path: 路径向量 [k1 k2 ...]
   - default: 默认值（可选）

   返回: 嵌套值或默认值"
  ([state path]
   (get-in state path))
  ([state path default]
   (get-in state path default)))

(defn set-in-val
  "设置嵌套值

   参数:
   - state: state map
   - path: 路径向量 [k1 k2 ...]
   - value: 值

   返回: 新的 state map"
  [state path value]
  (assoc-in state path value))

(defn update-in-val
  "更新嵌套值

   参数:
   - state: state map
   - path: 路径向量 [k1 k2 ...]
   - f: 更新函数

   返回: 新的 state map"
  [state path f & args]
  (apply update-in state path f args))
