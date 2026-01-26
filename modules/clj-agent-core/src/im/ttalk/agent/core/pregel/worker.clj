(ns im.ttalk.agent.core.pregel.worker
  "Pregel Worker - 工作进程

   Worker 负责执行分配给它的顶点计算。
   每个 Worker 管理一个顶点分区。

   Worker 生命周期:
   1. 接收 global-state 广播
   2. 接收激活的顶点列表和消息
   3. 执行顶点计算
   4. 收集 delta 和新消息
   5. 通过 barrier 同步结果"
  (:require [clojure.core.async :as async :refer [go go-loop <! >! chan put! close!]]
            [im.ttalk.agent.core.pregel.vertex :as vertex]
            [im.ttalk.agent.core.pregel.barrier :as barrier]))

;;; ============================================================
;;; Worker 状态
;;; ============================================================

(defn create-worker
  "创建 Worker

   参数:
   - worker-id: Worker 标识
   - vertices: 分配给此 Worker 的顶点 map {vertex-id -> vertex}
   - opts: 可选参数

   返回: worker map"
  [worker-id vertices & {:keys []
                         :or {}}]
  {:id worker-id
   :vertices (atom vertices)
   :global-state (atom {})
   :status (atom :idle)})

(defn worker-id
  "获取 Worker ID"
  [worker]
  (:id worker))

(defn worker-vertices
  "获取 Worker 的顶点"
  [worker]
  @(:vertices worker))

(defn worker-status
  "获取 Worker 状态"
  [worker]
  @(:status worker))

;;; ============================================================
;;; Worker 操作
;;; ============================================================

(defn update-global-state
  "更新 Worker 的全局状态视图

   参数:
   - worker: Worker
   - global-state: 新的全局状态

   返回: worker"
  [worker global-state]
  (reset! (:global-state worker) global-state)
  worker)

(defn update-vertex
  "更新 Worker 中的顶点

   参数:
   - worker: Worker
   - vertex-id: 顶点 ID
   - vertex: 新的顶点定义

   返回: worker"
  [worker vertex-id vertex]
  (swap! (:vertices worker) assoc vertex-id vertex)
  worker)

;;; ============================================================
;;; 顶点计算
;;; ============================================================

(defn- execute-vertex
  "执行单个顶点的计算

   参数:
   - vertex: 顶点
   - context: 计算上下文

   返回: {:vertex-id id
          :status :ok/:error
          :result compute-result
          :error error-info}"
  [vertex context]
  (let [vertex-id (vertex/vertex-id vertex)
        compute-fn (vertex/vertex-compute-fn vertex)]
    (try
      (let [result (compute-fn vertex context)]
        {:vertex-id vertex-id
         :status :ok
         :result result})
      (catch Exception e
        {:vertex-id vertex-id
         :status :error
         :error {:message (.getMessage e)
                 :type (str (type e))}}))))

(defn execute-superstep
  "执行一个超步

   参数:
   - worker: Worker
   - active-vertices: 激活的顶点 ID 集合
   - messages: 消息 map {vertex-id -> [messages]}
   - superstep: 超步编号

   返回: {:deltas [{vertex-id delta}...]
          :messages [{:target :data}...]
          :halted [vertex-id...]
          :errors [{:vertex-id :error}...]}"
  [worker active-vertices messages superstep]
  (reset! (:status worker) :computing)
  (let [vertices @(:vertices worker)
        global-state @(:global-state worker)
        num-vertices (count vertices)
        ;; 执行所有激活的顶点
        results
        (reduce
          (fn [acc vertex-id]
            (if-let [vertex (get vertices vertex-id)]
              (let [vertex-messages (get messages vertex-id [])
                    context (vertex/create-context
                              {:superstep superstep
                               :num-vertices num-vertices
                               :global-state global-state
                               :messages vertex-messages})
                    exec-result (execute-vertex vertex context)]
                (if (= :ok (:status exec-result))
                  (let [result (:result exec-result)
                        new-value (:value result)
                        out-messages (:messages result)
                        vote-to-halt (:vote-to-halt result)]
                    (-> acc
                        ;; 收集 delta
                        (update :deltas conj
                                {:vertex-id vertex-id
                                 :value new-value})
                        ;; 收集输出消息
                        (update :messages into out-messages)
                        ;; 收集停止投票
                        (cond-> vote-to-halt
                          (update :halted conj vertex-id))))
                  ;; 错误
                  (update acc :errors conj
                          {:vertex-id vertex-id
                           :error (:error exec-result)})))
              ;; 顶点不存在，跳过
              acc))
          {:deltas [] :messages [] :halted [] :errors []}
          active-vertices)]
    (reset! (:status worker) :idle)
    results))

;;; ============================================================
;;; 异步 Worker（Channel 驱动）
;;; ============================================================

(defn start-worker-loop
  "启动 Worker 的 go-loop

   参数:
   - worker: Worker
   - command-chan: 接收命令的 channel
   - result-barrier: 结果同步屏障

   命令格式:
   {:type :execute-superstep
    :active-vertices #{...}
    :messages {...}
    :superstep n}

   {:type :update-global-state
    :global-state {...}}

   {:type :stop}

   返回: worker handle"
  [worker command-chan result-barrier]
  (let [worker-id (:id worker)]
    (go-loop []
      (when-let [cmd (<! command-chan)]
        (case (:type cmd)
          :execute-superstep
          (let [result (execute-superstep
                         worker
                         (:active-vertices cmd)
                         (:messages cmd)
                         (:superstep cmd))]
            ;; 通过 barrier 报告结果
            (barrier/arrive result-barrier
                            {:worker-id worker-id
                             :result result})
            (recur))

          :update-global-state
          (do
            (update-global-state worker (:global-state cmd))
            (recur))

          :stop
          (reset! (:status worker) :stopped)

          ;; 未知命令，继续
          (recur))))
    {:worker worker
     :command-chan command-chan}))

(defn send-command
  "向 Worker 发送命令

   参数:
   - worker-handle: start-worker-loop 返回的 handle
   - command: 命令 map

   返回: true"
  [worker-handle command]
  (put! (:command-chan worker-handle) command)
  true)

(defn stop-worker
  "停止 Worker

   参数:
   - worker-handle: Worker handle

   返回: nil"
  [worker-handle]
  (send-command worker-handle {:type :stop})
  (close! (:command-chan worker-handle))
  nil)
