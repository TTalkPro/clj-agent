(ns im.ttalk.agent.core.graph.api
  "Graph API - 统一门面

   提供图执行系统的统一入口，重导出常用 API。

   使用示例:

   (require '[im.ttalk.agent.core.graph.api :as g])

   ;; 构建图
   (def workflow
     (-> (g/graph :my-workflow)
         (g/add-node :fetch (fn [state _] (g/ok (assoc state :data \"fetched\"))))
         (g/add-node :process (fn [state _] (g/ok (assoc state :processed true))))
         (g/add-edge g/START :fetch)
         (g/add-conditional-edge :fetch
           (fn [state] (if (:done state) g/END :process)))
         (g/add-edge :process g/END)
         (g/set-entry :fetch)
         (g/compile)))

   ;; 执行
   (def result (g/run workflow (g/state {:input \"data\"})
                      :field-reducers {:logs g/append}))

   ;; 流式执行
   (def iter-fn (g/stream workflow (g/state {})))
   (loop [f iter-fn]
     (let [r (f)]
       (if (:done r)
         (println \"完成:\" (:result r))
         (do
           (println \"中间状态:\" (:yield r))
           (recur (:next r))))))"
  (:refer-clojure :exclude [compile])
  (:require [im.ttalk.agent.core.graph.state :as state]
            [im.ttalk.agent.core.graph.node :as node]
            [im.ttalk.agent.core.graph.edge :as edge]
            [im.ttalk.agent.core.graph.reducer :as reducer]
            [im.ttalk.agent.core.graph.dispatch :as dispatch]
            [im.ttalk.agent.core.graph.builder :as builder]
            [im.ttalk.agent.core.graph.executor :as executor]))

;;; ============================================================
;;; 状态 API
;;; ============================================================

(def state
  "创建 graph state"
  state/create)

(def get-val
  "获取状态值"
  state/get-val)

(def set-val
  "设置状态值"
  state/set-val)

(def set-many
  "批量设置状态值"
  state/set-many)

(def update-val
  "更新状态值"
  state/update-val)

(def get-context
  "获取用户上下文"
  state/get-context)

(def set-context
  "设置用户上下文"
  state/set-context)

;;; ============================================================
;;; 节点 API
;;; ============================================================

(def START
  "图的起始节点"
  node/START)

(def END
  "图的终止节点"
  node/END)

(def create-node
  "创建节点定义"
  node/create-node)

(def ok
  "构造成功结果"
  node/ok)

(def error
  "构造错误结果"
  node/error)

(def interrupt
  "构造中断结果"
  node/interrupt)

(def command
  "构造 Command 结果"
  node/command)

;;; ============================================================
;;; 边 API
;;; ============================================================

(def direct
  "创建直接边"
  edge/direct)

(def fanout
  "创建扇出边"
  edge/fanout)

(def conditional
  "创建条件边"
  edge/conditional)

;;; ============================================================
;;; Dispatch API
;;; ============================================================

(def dispatch
  "创建 dispatch（动态并行分支）"
  dispatch/dispatch)

(def fan-out
  "批量创建 dispatch"
  dispatch/fan-out)

(def fan-out-indexed
  "带索引的批量创建 dispatch"
  dispatch/fan-out-indexed)

;;; ============================================================
;;; Reducer API
;;; ============================================================

(def last-write-wins
  "后值覆盖 reducer"
  reducer/last-write-wins)

(def append
  "列表追加 reducer"
  reducer/append)

(def prepend
  "列表前置 reducer"
  reducer/prepend)

(def deep-merge
  "Map 深度合并 reducer"
  reducer/deep-merge)

(def increment
  "数值增量 reducer"
  reducer/increment)

(def compute-delta
  "计算状态增量"
  reducer/compute-delta)

(def apply-delta
  "应用增量到状态"
  reducer/apply-delta)

;;; ============================================================
;;; Builder API
;;; ============================================================

(def graph
  "创建图构建器"
  builder/graph)

(def add-node
  "添加节点到图"
  builder/add-node)

(def add-edge
  "添加边到图"
  builder/add-edge)

(def add-conditional-edge
  "添加条件边到图"
  builder/add-conditional-edge)

(def set-entry
  "设置入口节点"
  builder/set-entry)

(def set-max-iterations
  "设置最大迭代次数"
  builder/set-max-iterations)

(def compile
  "编译图"
  builder/compile)

(def linear
  "创建线性图"
  builder/linear)

;;; ============================================================
;;; 执行 API
;;; ============================================================

(def run
  "同步执行图"
  executor/run)

(def run-async
  "异步执行图"
  executor/run-async)

(def stop-async
  "停止异步执行"
  executor/stop-async)

(def wait-async
  "等待异步执行完成"
  executor/wait-async)

(def stream
  "流式执行图"
  executor/stream)

;;; ============================================================
;;; 执行状态常量
;;; ============================================================

(def COMPLETED executor/STATUS-COMPLETED)
(def ERROR executor/STATUS-ERROR)
(def INTERRUPTED executor/STATUS-INTERRUPTED)
(def MAX-ITERATIONS executor/STATUS-MAX-ITERATIONS)
