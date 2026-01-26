(ns im.ttalk.agent.core.pregel.core
  "Pregel Core - BSP 图计算引擎

   实现 Bulk Synchronous Parallel (BSP) 模型的图计算引擎。

   BSP 执行模型:
   1. 每个超步开始时，Master 广播全局状态
   2. 所有活跃顶点并行执行计算
   3. 顶点产出新值和消息
   4. 屏障同步，等待所有顶点完成
   5. Master 合并结果，分发消息
   6. 重复直到所有顶点停止或达到最大超步

   与 Graph Executor 的区别:
   - Executor: 事件驱动，节点完成即可继续
   - Pregel: BSP 同步，等待所有节点完成才进入下一超步

   使用示例:

   (def vertices
     {:v1 (vertex/create-vertex :v1 pagerank-compute :value 1.0)
      :v2 (vertex/create-vertex :v2 pagerank-compute :value 1.0)})

   (def edges
     {:v1 [:v2]
      :v2 [:v1]})

   (def result
     (run vertices edges
          :max-supersteps 100
          :num-workers 4))"
  (:require [clojure.core.async :as async :refer [go go-loop <! >! <!! chan put! close!]]
            [im.ttalk.agent.core.pregel.vertex :as vertex]
            [im.ttalk.agent.core.pregel.worker :as worker]
            [im.ttalk.agent.core.pregel.barrier :as barrier]))

;;; ============================================================
;;; 执行状态常量
;;; ============================================================

(def ^:const STATUS-COMPLETED :completed)
(def ^:const STATUS-MAX-SUPERSTEPS :max-supersteps)
(def ^:const STATUS-ERROR :error)
(def ^:const STATUS-RUNNING :running)

;;; ============================================================
;;; 消息路由
;;; ============================================================

(defn- route-messages
  "将消息按目标顶点分组

   参数:
   - messages: 消息列表 [{:target vertex-id :data any}]

   返回: {vertex-id -> [message-data...]}"
  [messages]
  (reduce
    (fn [acc msg]
      (update acc (:target msg) (fnil conj []) (:data msg)))
    {}
    messages))

(defn- partition-messages-by-worker
  "将消息按 Worker 分区

   参数:
   - messages: 消息 map {vertex-id -> [data...]}
   - vertex-to-worker: 顶点到 Worker 的映射

   返回: {worker-id -> {vertex-id -> [data...]}}"
  [messages vertex-to-worker]
  (reduce-kv
    (fn [acc vertex-id data]
      (let [worker-id (get vertex-to-worker vertex-id)]
        (assoc-in acc [worker-id vertex-id] data)))
    {}
    messages))

;;; ============================================================
;;; 顶点分区
;;; ============================================================

(defn- partition-vertices
  "将顶点分配给 Workers

   使用简单的 hash 分区策略。

   参数:
   - vertices: 顶点 map {vertex-id -> vertex}
   - num-workers: Worker 数量

   返回: {:partitions {worker-id -> {vertex-id -> vertex}}
          :vertex-to-worker {vertex-id -> worker-id}}"
  [vertices num-workers]
  (let [vertex-ids (keys vertices)
        partitions (reduce
                     (fn [acc vertex-id]
                       (let [worker-id (mod (hash vertex-id) num-workers)
                             vertex (get vertices vertex-id)]
                         (assoc-in acc [worker-id vertex-id] vertex)))
                     {}
                     vertex-ids)
        vertex-to-worker (reduce
                           (fn [acc vertex-id]
                             (assoc acc vertex-id
                                    (mod (hash vertex-id) num-workers)))
                           {}
                           vertex-ids)]
    {:partitions partitions
     :vertex-to-worker vertex-to-worker}))

;;; ============================================================
;;; Master 协调
;;; ============================================================

(defn- create-master-state
  "创建 Master 状态

   参数:
   - vertices: 所有顶点
   - num-workers: Worker 数量
   - opts: 选项

   返回: master-state map"
  [vertices num-workers opts]
  (let [{:keys [partitions vertex-to-worker]} (partition-vertices vertices num-workers)]
    {:vertices vertices
     :partitions partitions
     :vertex-to-worker vertex-to-worker
     :num-workers num-workers
     :global-state (or (:initial-global-state opts) {})
     :pending-messages {}
     :active-vertices (set (keys vertices))  ;; 初始全部激活
     :superstep 0
     :max-supersteps (or (:max-supersteps opts) 100)}))

(defn- update-vertices-from-deltas
  "从 delta 更新顶点值

   参数:
   - vertices: 顶点 map
   - deltas: delta 列表 [{:vertex-id :value}]

   返回: 更新后的顶点 map"
  [vertices deltas]
  (reduce
    (fn [vs delta]
      (let [vid (:vertex-id delta)
            new-value (:value delta)]
        (if (contains? vs vid)
          (update vs vid vertex/set-value new-value)
          vs)))
    vertices
    deltas))

(defn- update-halted-vertices
  "更新停止的顶点

   参数:
   - vertices: 顶点 map
   - halted-ids: 停止的顶点 ID 列表

   返回: 更新后的顶点 map"
  [vertices halted-ids]
  (reduce
    (fn [vs vid]
      (if (contains? vs vid)
        (update vs vid vertex/halt)
        vs))
    vertices
    halted-ids))

(defn- compute-active-vertices
  "计算下一超步的活跃顶点

   顶点在以下情况下活跃:
   1. 有待接收的消息
   2. 未投票停止

   参数:
   - vertices: 顶点 map
   - pending-messages: 待处理消息 {vertex-id -> [data...]}

   返回: 活跃顶点 ID 集合"
  [vertices pending-messages]
  (let [has-messages (set (keys pending-messages))
        not-halted (set (keep (fn [[vid v]]
                                (when-not (vertex/vertex-halted? v)
                                  vid))
                              vertices))]
    (clojure.set/union has-messages not-halted)))

;;; ============================================================
;;; 单机执行（简化版）
;;; ============================================================

(defn run-simple
  "单机简化执行（不使用 Worker 进程）

   适用于小规模图或测试场景。

   参数:
   - vertices: 顶点 map {vertex-id -> vertex}
   - opts: 选项
     :max-supersteps       - 最大超步数（默认 100）
     :initial-global-state - 初始全局状态

   返回: {:status :completed/:max-supersteps/:error
          :vertices final-vertices
          :supersteps n
          :global-state final-global-state}"
  [vertices & {:keys [max-supersteps initial-global-state]
               :or {max-supersteps 100
                    initial-global-state {}}}]
  (loop [vertices vertices
         global-state initial-global-state
         pending-messages {}
         superstep 0]
    (let [;; 计算活跃顶点
          active-vertices (compute-active-vertices vertices pending-messages)]
      (cond
        ;; 没有活跃顶点 -> 完成
        (empty? active-vertices)
        {:status STATUS-COMPLETED
         :vertices vertices
         :supersteps superstep
         :global-state global-state}

        ;; 达到最大超步
        (>= superstep max-supersteps)
        {:status STATUS-MAX-SUPERSTEPS
         :vertices vertices
         :supersteps superstep
         :global-state global-state}

        ;; 执行超步
        :else
        (let [num-vertices (count vertices)
              ;; 执行所有活跃顶点
              results
              (reduce
                (fn [acc vertex-id]
                  (let [vertex (get vertices vertex-id)
                        messages (get pending-messages vertex-id [])
                        context (vertex/create-context
                                  {:superstep superstep
                                   :num-vertices num-vertices
                                   :global-state global-state
                                   :messages messages})]
                    (try
                      (let [compute-fn (vertex/vertex-compute-fn vertex)
                            result (compute-fn vertex context)
                            new-value (:value result)
                            out-messages (:messages result)
                            vote-to-halt (:vote-to-halt result)]
                        (-> acc
                            (update :deltas conj {:vertex-id vertex-id :value new-value})
                            (update :messages into out-messages)
                            (cond-> vote-to-halt
                              (update :halted conj vertex-id))))
                      (catch Exception e
                        (update acc :errors conj
                                {:vertex-id vertex-id
                                 :error (.getMessage e)})))))
                {:deltas [] :messages [] :halted [] :errors []}
                active-vertices)]
          ;; 检查错误
          (if (seq (:errors results))
            {:status STATUS-ERROR
             :vertices vertices
             :supersteps superstep
             :global-state global-state
             :errors (:errors results)}
            ;; 更新状态，准备下一超步
            (let [new-vertices (-> vertices
                                   (update-vertices-from-deltas (:deltas results))
                                   (update-halted-vertices (:halted results)))
                  new-messages (route-messages (:messages results))]
              (recur new-vertices
                     global-state
                     new-messages
                     (inc superstep)))))))))

;;; ============================================================
;;; 并行执行（使用 Workers）
;;; ============================================================

(defn run
  "并行执行 Pregel 计算

   使用多个 Worker 并行执行顶点计算。

   参数:
   - vertices: 顶点 map {vertex-id -> vertex}
   - opts: 选项
     :num-workers          - Worker 数量（默认 4）
     :max-supersteps       - 最大超步数（默认 100）
     :initial-global-state - 初始全局状态
     :timeout-ms           - 超步超时（毫秒）

   返回: {:status :completed/:max-supersteps/:error
          :vertices final-vertices
          :supersteps n
          :global-state final-global-state}"
  [vertices & {:keys [num-workers max-supersteps initial-global-state timeout-ms]
               :or {num-workers 4
                    max-supersteps 100
                    initial-global-state {}
                    timeout-ms 60000}}]
  (let [;; 分区顶点
        {:keys [partitions vertex-to-worker]} (partition-vertices vertices num-workers)
        ;; 创建 Workers
        workers (mapv (fn [worker-id]
                        (worker/create-worker worker-id
                                              (get partitions worker-id {})))
                      (range num-workers))
        ;; 创建命令 channels
        command-chans (mapv (fn [_] (chan 10)) (range num-workers))
        ;; 创建 barrier
        result-barrier (barrier/create-reusable-barrier num-workers
                                                        :timeout-ms timeout-ms)
        ;; 启动 Worker loops
        worker-handles (mapv (fn [w ch]
                               (worker/start-worker-loop w ch result-barrier))
                             workers command-chans)]
    (try
      ;; 执行循环
      (loop [global-state initial-global-state
             pending-messages {}
             superstep 0]
        (let [;; 计算活跃顶点
              all-vertices (apply merge (map worker/worker-vertices workers))
              active-vertices (compute-active-vertices all-vertices pending-messages)]
          (cond
            ;; 没有活跃顶点
            (empty? active-vertices)
            {:status STATUS-COMPLETED
             :vertices all-vertices
             :supersteps superstep
             :global-state global-state}

            ;; 达到最大超步
            (>= superstep max-supersteps)
            {:status STATUS-MAX-SUPERSTEPS
             :vertices all-vertices
             :supersteps superstep
             :global-state global-state}

            ;; 执行超步
            :else
            (do
              ;; 1. 广播全局状态
              (doseq [handle worker-handles]
                (worker/send-command handle
                                     {:type :update-global-state
                                      :global-state global-state}))

              ;; 2. 分配消息给 Workers
              (let [messages-by-worker (partition-messages-by-worker
                                         pending-messages vertex-to-worker)
                    ;; 分配活跃顶点给 Workers
                    active-by-worker (group-by #(get vertex-to-worker %)
                                               active-vertices)]

                ;; 3. 发送执行命令
                (doseq [[worker-id handle] (map-indexed vector worker-handles)]
                  (worker/send-command handle
                                       {:type :execute-superstep
                                        :active-vertices (set (get active-by-worker worker-id))
                                        :messages (get messages-by-worker worker-id {})
                                        :superstep superstep}))

                ;; 4. 等待所有 Workers 完成
                (barrier/reset-barrier result-barrier)
                (let [worker-results (barrier/await-all-reusable result-barrier)
                      ;; 合并结果
                      all-deltas (mapcat #(get-in % [:result :deltas]) worker-results)
                      all-messages (mapcat #(get-in % [:result :messages]) worker-results)
                      all-halted (mapcat #(get-in % [:result :halted]) worker-results)
                      all-errors (mapcat #(get-in % [:result :errors]) worker-results)]

                  ;; 5. 检查错误
                  (if (seq all-errors)
                    {:status STATUS-ERROR
                     :vertices all-vertices
                     :supersteps superstep
                     :global-state global-state
                     :errors all-errors}

                    ;; 6. 更新顶点并准备下一超步
                    (do
                      ;; 更新 Workers 中的顶点
                      (doseq [delta all-deltas]
                        (let [vid (:vertex-id delta)
                              wid (get vertex-to-worker vid)
                              w (nth workers wid)]
                          (worker/update-vertex w vid
                                                (vertex/set-value
                                                  (get all-vertices vid)
                                                  (:value delta)))))
                      ;; 更新停止状态
                      (doseq [vid all-halted]
                        (let [wid (get vertex-to-worker vid)
                              w (nth workers wid)]
                          (worker/update-vertex w vid
                                                (vertex/halt
                                                  (get (worker/worker-vertices w) vid)))))

                      (recur global-state
                             (route-messages all-messages)
                             (inc superstep))))))))))
      (finally
        ;; 清理 Workers
        (doseq [handle worker-handles]
          (worker/stop-worker handle))))))

;;; ============================================================
;;; 便捷函数
;;; ============================================================

(defn create-compute-fn
  "创建标准的 compute 函数包装器

   简化 compute 函数的编写。

   参数:
   - f: 简化的函数 (fn [value messages global-state] -> {:value :messages :halt})

   返回: 标准 compute 函数"
  [f]
  (fn [vertex context]
    (let [value (vertex/vertex-value vertex)
          messages (vertex/context-messages context)
          global-state (vertex/context-global-state context)
          result (f value messages global-state)]
      (vertex/compute-result
        {:value (:value result value)
         :messages (or (:messages result) [])
         :vote-to-halt (:halt result false)}))))
