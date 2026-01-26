(ns im.ttalk.agent.core.pregel.api
  "Pregel API - 统一门面

   BSP (Bulk Synchronous Parallel) 图计算引擎。

   使用示例:

   (require '[im.ttalk.agent.core.pregel.api :as pregel])

   ;; 定义 PageRank 计算函数
   (def pagerank-compute
     (pregel/create-compute-fn
       (fn [value messages global-state]
         (let [;; 收集邻居的 rank 贡献
               incoming-rank (reduce + 0 messages)
               ;; 计算新 rank
               damping 0.85
               num-vertices (:num-vertices global-state)
               new-rank (+ (/ (- 1 damping) num-vertices)
                          (* damping incoming-rank))
               ;; 获取出边
               out-edges (get global-state (:id value) [])]
           {:value new-rank
            :messages (pregel/send-messages out-edges (/ new-rank (count out-edges)))
            :halt (< (Math/abs (- new-rank value)) 0.001)}))))

   ;; 创建顶点
   (def vertices
     {:a (pregel/vertex :a pagerank-compute :value 1.0)
      :b (pregel/vertex :b pagerank-compute :value 1.0)
      :c (pregel/vertex :c pagerank-compute :value 1.0)})

   ;; 执行
   (def result (pregel/run vertices :max-supersteps 100))"
  (:require [im.ttalk.agent.core.pregel.vertex :as vertex]
            [im.ttalk.agent.core.pregel.barrier :as barrier]
            [im.ttalk.agent.core.pregel.worker :as worker]
            [im.ttalk.agent.core.pregel.core :as core]))

;;; ============================================================
;;; Vertex API
;;; ============================================================

(def vertex
  "创建顶点"
  vertex/create-vertex)

(def vertex-id
  "获取顶点 ID"
  vertex/vertex-id)

(def vertex-value
  "获取顶点值"
  vertex/vertex-value)

(def set-value
  "设置顶点值"
  vertex/set-value)

(def halt
  "标记顶点停止"
  vertex/halt)

(def activate
  "激活顶点"
  vertex/activate)

(def halted?
  "检查顶点是否停止"
  vertex/vertex-halted?)

;;; ============================================================
;;; 消息 API
;;; ============================================================

(def send-message
  "创建发送消息"
  vertex/send-message)

(def send-messages
  "创建发送给多个目标的消息"
  vertex/send-messages)

;;; ============================================================
;;; 计算上下文 API
;;; ============================================================

(def context-superstep
  "获取当前超步"
  vertex/context-superstep)

(def context-messages
  "获取收到的消息"
  vertex/context-messages)

(def context-global-state
  "获取全局状态"
  vertex/context-global-state)

(def has-messages?
  "检查是否有消息"
  vertex/has-messages?)

;;; ============================================================
;;; 计算结果 API
;;; ============================================================

(def compute-result
  "创建计算结果"
  vertex/compute-result)

(def create-compute-fn
  "创建标准 compute 函数包装器"
  core/create-compute-fn)

;;; ============================================================
;;; 执行 API
;;; ============================================================

(def run
  "并行执行 Pregel 计算"
  core/run)

(def run-simple
  "单机简化执行"
  core/run-simple)

;;; ============================================================
;;; 状态常量
;;; ============================================================

(def COMPLETED core/STATUS-COMPLETED)
(def MAX-SUPERSTEPS core/STATUS-MAX-SUPERSTEPS)
(def ERROR core/STATUS-ERROR)

;;; ============================================================
;;; Barrier API（高级用法）
;;; ============================================================

(def create-barrier
  "创建同步屏障"
  barrier/create-barrier)

(def arrive
  "到达屏障"
  barrier/arrive)

(def await-all
  "等待所有参与者"
  barrier/await-all)
