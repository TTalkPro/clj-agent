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
     :max-supersteps            - 最大超步数（默认 100）
     :initial-global-state      - 初始全局状态
     :checkpointer              - GraphCheckpointManager 实例（可选）
     :checkpoint-policy         - checkpoint 策略（默认 :every-superstep）
                                  :every-superstep - 每个超步后保存
                                  :on-complete     - 仅完成时保存
                                  :on-error        - 仅出错时保存
                                  :on-complete-or-error - 完成或出错时保存
     :on-checkpoint             - checkpoint 保存后的回调 (fn [checkpoint])
     :run-id                    - 执行 ID（使用 checkpointer 时必需）
     :graph-name                - 图名称（可选）

     ;; 恢复参数（从 checkpoint 恢复时使用）
     :resume-superstep          - 恢复的起始超步号（默认 0）
     :initial-pending-messages  - 初始待处理消息 {vertex-id -> [data...]}
     :resume-data               - 恢复数据 {vertex-id -> data}，会传递给顶点

   返回: {:status :completed/:max-supersteps/:error
          :vertices final-vertices
          :supersteps n
          :global-state final-global-state}"
  [vertices & {:keys [max-supersteps initial-global-state
                      checkpointer checkpoint-policy on-checkpoint
                      run-id graph-name
                      ;; 恢复参数
                      resume-superstep initial-pending-messages resume-data]
               :or {max-supersteps 100
                    initial-global-state {}
                    checkpoint-policy :every-superstep
                    resume-superstep 0
                    initial-pending-messages {}}}]
  (let [;; checkpoint 辅助函数
        do-checkpoint!
        (fn [vtx gs pending-msgs step cp-type & [failed-vtx-ids]]
          (when (and checkpointer run-id)
            (let [checkpoint-fn (requiring-resolve
                                  'im.ttalk.agent.core.graph.checkpoint/save-from-pregel)
                  pregel-state {:superstep step
                                :vertices vtx
                                :global-state gs
                                :pending-messages pending-msgs
                                :active-vertices (vec (keys (filter (fn [[_ v]] (not (:halted v))) vtx)))
                                :failed-vertices (or failed-vtx-ids [])}
                  cp-id (checkpoint-fn checkpointer run-id pregel-state
                                        {:checkpoint-type cp-type
                                         :graph-name graph-name})]
              (when on-checkpoint
                (on-checkpoint {:checkpoint-id cp-id
                                :superstep step
                                :checkpoint-type cp-type
                                :failed-vertices failed-vtx-ids}))
              cp-id)))

        should-checkpoint?
        (fn [cp-type]
          (case checkpoint-policy
            :every-superstep true
            :on-complete (= cp-type :final)
            :on-error (= cp-type :error)
            :on-complete-or-error (contains? #{:final :error} cp-type)
            false))]

    (loop [vertices vertices
           global-state initial-global-state
           pending-messages initial-pending-messages
           superstep resume-superstep]
      (let [;; 计算活跃顶点
            active-vertices (compute-active-vertices vertices pending-messages)]
        (cond
          ;; 没有活跃顶点 -> 完成
          (empty? active-vertices)
          (do
            (when (should-checkpoint? :final)
              (do-checkpoint! vertices global-state pending-messages superstep :final))
            {:status STATUS-COMPLETED
             :vertices vertices
             :supersteps superstep
             :global-state global-state})

          ;; 达到最大超步
          (>= superstep max-supersteps)
          (do
            (when (should-checkpoint? :final)
              (do-checkpoint! vertices global-state pending-messages superstep :final))
            {:status STATUS-MAX-SUPERSTEPS
             :vertices vertices
             :supersteps superstep
             :global-state global-state})

          ;; 执行超步
          :else
          (let [num-vertices (count vertices)
                ;; 执行所有活跃顶点
                results
                (reduce
                  (fn [acc vertex-id]
                    (let [vertex (get vertices vertex-id)
                          messages (get pending-messages vertex-id [])
                          vertex-resume-data (get resume-data vertex-id)
                          context (vertex/create-context
                                    (cond-> {:superstep superstep
                                             :num-vertices num-vertices
                                             :global-state global-state
                                             :messages messages}
                                      vertex-resume-data
                                      (assoc :resume-data vertex-resume-data)))]
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
            ;; 先应用成功顶点的更新（即使有错误也要保存成功的结果）
            (let [new-vertices (-> vertices
                                   (update-vertices-from-deltas (:deltas results))
                                   (update-halted-vertices (:halted results)))
                  new-messages (route-messages (:messages results))
                  failed-vertex-ids (mapv :vertex-id (:errors results))]
              ;; 检查错误
              (if (seq (:errors results))
                (do
                  (when (should-checkpoint? :error)
                    ;; 保存更新后的状态，包含成功顶点的新值和失败顶点列表
                    (do-checkpoint! new-vertices global-state new-messages superstep :error
                                    failed-vertex-ids))
                  {:status STATUS-ERROR
                   :vertices new-vertices  ;; 返回更新后的状态
                   :supersteps superstep
                   :global-state global-state
                   :errors (:errors results)
                   :failed-vertices failed-vertex-ids})
                ;; 无错误，准备下一超步
                (do
                  (when (should-checkpoint? :superstep)
                    (do-checkpoint! new-vertices global-state new-messages (inc superstep) :superstep nil))
                  (recur new-vertices
                         global-state
                         new-messages
                         (inc superstep)))))))))))

;;; ============================================================
;;; 并行执行（使用 Workers）
;;; ============================================================

(defn run
  "并行执行 Pregel 计算

   使用多个 Worker 并行执行顶点计算。

   参数:
   - vertices: 顶点 map {vertex-id -> vertex}
   - opts: 选项
     :num-workers               - Worker 数量（默认 4）
     :max-supersteps            - 最大超步数（默认 100）
     :initial-global-state      - 初始全局状态
     :timeout-ms                - 超步超时（毫秒）
     :checkpointer              - GraphCheckpointManager 实例（可选）
     :checkpoint-policy         - checkpoint 策略（默认 :every-superstep）
     :on-checkpoint             - checkpoint 保存后的回调 (fn [checkpoint])
     :run-id                    - 执行 ID（使用 checkpointer 时必需）
     :graph-name                - 图名称（可选）

     ;; 恢复参数（从 checkpoint 恢复时使用）
     :resume-superstep          - 恢复的起始超步号（默认 0）
     :initial-pending-messages  - 初始待处理消息 {vertex-id -> [data...]}
     :resume-data               - 恢复数据 {vertex-id -> data}

   返回: {:status :completed/:max-supersteps/:error
          :vertices final-vertices
          :supersteps n
          :global-state final-global-state}"
  [vertices & {:keys [num-workers max-supersteps initial-global-state timeout-ms
                      checkpointer checkpoint-policy on-checkpoint run-id graph-name
                      ;; 恢复参数
                      resume-superstep initial-pending-messages resume-data]
               :or {num-workers 4
                    max-supersteps 100
                    initial-global-state {}
                    timeout-ms 60000
                    checkpoint-policy :every-superstep
                    resume-superstep 0
                    initial-pending-messages {}}}]
  (let [;; checkpoint 辅助函数
        do-checkpoint!
        (fn [vtx gs pending-msgs step cp-type & [failed-vtx-ids]]
          (when (and checkpointer run-id)
            (let [checkpoint-fn (requiring-resolve
                                  'im.ttalk.agent.core.graph.checkpoint/save-from-pregel)
                  pregel-state {:superstep step
                                :vertices vtx
                                :global-state gs
                                :pending-messages pending-msgs
                                :active-vertices (vec (keys (filter (fn [[_ v]] (not (:halted v))) vtx)))
                                :failed-vertices (or failed-vtx-ids [])}
                  cp-id (checkpoint-fn checkpointer run-id pregel-state
                                        {:checkpoint-type cp-type
                                         :graph-name graph-name})]
              (when on-checkpoint
                (on-checkpoint {:checkpoint-id cp-id
                                :superstep step
                                :checkpoint-type cp-type
                                :failed-vertices failed-vtx-ids}))
              cp-id)))

        should-checkpoint?
        (fn [cp-type]
          (case checkpoint-policy
            :every-superstep true
            :on-complete (= cp-type :final)
            :on-error (= cp-type :error)
            :on-complete-or-error (contains? #{:final :error} cp-type)
            false))

        ;; 分区顶点
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
             pending-messages initial-pending-messages
             superstep resume-superstep]
        (let [;; 计算活跃顶点
              all-vertices (apply merge (map worker/worker-vertices workers))
              active-vertices (compute-active-vertices all-vertices pending-messages)]
          (cond
            ;; 没有活跃顶点
            (empty? active-vertices)
            (do
              (when (should-checkpoint? :final)
                (do-checkpoint! all-vertices global-state pending-messages superstep :final))
              {:status STATUS-COMPLETED
               :vertices all-vertices
               :supersteps superstep
               :global-state global-state})

            ;; 达到最大超步
            (>= superstep max-supersteps)
            (do
              (when (should-checkpoint? :final)
                (do-checkpoint! all-vertices global-state pending-messages superstep :final))
              {:status STATUS-MAX-SUPERSTEPS
               :vertices all-vertices
               :supersteps superstep
               :global-state global-state})

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
                                        :superstep superstep
                                        :resume-data resume-data}))

                ;; 4. 等待所有 Workers 完成
                (barrier/reset-barrier result-barrier)
                (let [worker-results (barrier/await-all-reusable result-barrier)
                      ;; 合并结果
                      all-deltas (mapcat #(get-in % [:result :deltas]) worker-results)
                      all-messages (mapcat #(get-in % [:result :messages]) worker-results)
                      all-halted (mapcat #(get-in % [:result :halted]) worker-results)
                      all-errors (mapcat #(get-in % [:result :errors]) worker-results)
                      failed-vertex-ids (mapv :vertex-id all-errors)]

                  ;; 5. 先应用成功顶点的更新（即使有错误也要保存成功的结果）
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

                  ;; 获取更新后的顶点状态
                  (let [updated-vertices (apply merge (map worker/worker-vertices workers))
                        new-messages (route-messages all-messages)]

                    ;; 6. 检查错误
                    (if (seq all-errors)
                      (do
                        (when (should-checkpoint? :error)
                          ;; 保存更新后的状态，包含成功顶点的新值和失败顶点列表
                          (do-checkpoint! updated-vertices global-state new-messages superstep :error
                                          failed-vertex-ids))
                        {:status STATUS-ERROR
                         :vertices updated-vertices  ;; 返回更新后的状态
                         :supersteps superstep
                         :global-state global-state
                         :errors all-errors
                         :failed-vertices failed-vertex-ids})

                      ;; 无错误，准备下一超步
                      (do
                        (when (should-checkpoint? :superstep)
                          (do-checkpoint! updated-vertices global-state new-messages (inc superstep) :superstep nil))

                        (recur global-state
                               new-messages
                               (inc superstep)))))))))))
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
