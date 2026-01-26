(ns im.ttalk.agent.core.graph.executor
  "Graph Executor - Channel 驱动的图执行引擎

   使用 core.async channel 实现图的并行执行。

   架构:
   ┌─────────────┐
   │  Executor   │ ←── control-chan (stop/pause)
   └──────┬──────┘
          │ activation-chan
          ▼
   ┌─────────────┐
   │ Worker Pool │  (async/thread)
   └──────┬──────┘
          │ result-chan
          ▼
   ┌─────────────┐
   │  Executor   │ → 合并 delta → 发送下一轮激活 → 检测终止
   └─────────────┘

   使用示例:

   (def result (run graph-spec initial-state
                    :field-reducers {:messages append}
                    :max-iterations 100))

   ;; 流式执行
   (def iter-fn (stream graph-spec initial-state))
   (loop [f iter-fn]
     (let [r (f)]
       (if (:done r)
         (:result r)
         (recur (:next r)))))"
  (:require [clojure.core.async :as async :refer [go go-loop <! >! <!! >!!
                                                   chan put! close! alt!
                                                   timeout]]
            [im.ttalk.agent.core.graph.state :as state]
            [im.ttalk.agent.core.graph.node :as node]
            [im.ttalk.agent.core.graph.edge :as edge]
            [im.ttalk.agent.core.graph.reducer :as reducer]
            [im.ttalk.agent.core.graph.dispatch :as dispatch]))

;;; ============================================================
;;; 执行结果状态
;;; ============================================================

(def ^:const STATUS-COMPLETED :completed)
(def ^:const STATUS-ERROR :error)
(def ^:const STATUS-INTERRUPTED :interrupted)
(def ^:const STATUS-MAX-ITERATIONS :max-iterations)
(def ^:const STATUS-RUNNING :running)
(def ^:const STATUS-PAUSED :paused)

;;; ============================================================
;;; 内部数据结构
;;; ============================================================

(defn- create-activation
  "创建激活请求"
  [node-id vertex-input iteration]
  {:node-id node-id
   :vertex-input vertex-input
   :iteration iteration
   :dispatch-id (when vertex-input (:__dispatch_id__ vertex-input))})

(defn- create-result
  "创建执行结果"
  [node-id status delta activations & {:keys [error interrupt-reason]}]
  {:node-id node-id
   :status status
   :delta delta
   :activations activations
   :error error
   :interrupt-reason interrupt-reason})

;;; ============================================================
;;; 节点执行
;;; ============================================================

(defn- execute-node
  "执行单个节点

   在 async/thread 中执行，避免阻塞 go 线程池。

   参数:
   - vertex: 顶点定义
   - state: 当前全局状态
   - vertex-input: dispatch 输入（可选）

   返回: channel，包含执行结果"
  [vertex state vertex-input]
  (async/thread
    (try
      (let [handler (:handler vertex)
            node-id (:id vertex)
            ;; 执行 handler
            result (handler state vertex-input)]
        (cond
          ;; 成功
          (node/ok? result)
          (let [new-state (:ok result)
                delta (reducer/compute-delta state new-state)
                ;; 解析出边，确定下一步激活
                edges (:edges vertex)
                activations (if (seq edges)
                              (reduce
                                (fn [acc e]
                                  (let [{:keys [targets dispatches]}
                                        (edge/resolve-edge e new-state)]
                                    (-> acc
                                        (into targets)
                                        (into dispatches))))
                                []
                                edges)
                              [])]
            (create-result node-id :ok delta activations))

          ;; Command 模式
          (node/command? result)
          (let [cmd (:command result)
                delta (:update cmd {})
                goto (:goto cmd)
                activations (cond
                              (nil? goto) []
                              (keyword? goto) [goto]
                              (sequential? goto) (vec goto)
                              :else [])]
            (create-result node-id :ok delta activations))

          ;; 中断
          (node/interrupt? result)
          (let [interrupt-data (:interrupt result)
                new-state (:state interrupt-data)
                delta (if new-state
                        (reducer/compute-delta state new-state)
                        {})]
            (create-result node-id :interrupt delta []
                           :interrupt-reason (:reason interrupt-data)))

          ;; 错误
          (node/error? result)
          (create-result node-id :error {} []
                         :error (:error result))

          ;; 未知返回格式
          :else
          (create-result node-id :error {} []
                         :error {:reason "Unknown result format"
                                 :result result})))
      (catch Exception e
        (create-result (:id vertex) :error {} []
                       :error {:reason (.getMessage e)
                               :exception (str (type e))})))))

;;; ============================================================
;;; Worker 管理
;;; ============================================================

(defn- start-workers
  "启动 worker，处理激活请求

   参数:
   - activation-chan: 激活请求通道
   - result-chan: 结果通道
   - vertices: 顶点 map
   - state-atom: 全局状态 atom
   - num-workers: worker 数量

   返回: worker channel 列表（用于关闭）"
  [activation-chan result-chan vertices state-atom num-workers]
  (dotimes [_ num-workers]
    (go-loop []
      (when-let [activation (<! activation-chan)]
        (let [node-id (:node-id activation)
              vertex-input (:vertex-input activation)
              vertex (get vertices node-id)]
          (if vertex
            ;; 启动执行线程并等待结果
            (let [exec-chan (execute-node vertex @state-atom vertex-input)
                  result (<! exec-chan)]
              (>! result-chan result))
            ;; 节点不存在（可能是 END）
            (when (not= node-id node/END)
              (>! result-chan
                  (create-result node-id :error {} []
                                 :error {:reason "Node not found"})))))
        (recur)))))

;;; ============================================================
;;; 执行器核心
;;; ============================================================

(defn- process-results
  "处理执行结果，合并 delta，收集下一轮激活

   参数:
   - results: 结果列表
   - state: 当前状态
   - field-reducers: 字段 reducer 配置

   返回: {:state new-state
          :activations [...]
          :errors [...]
          :interrupts [...]}"
  [results state field-reducers]
  (let [deltas (mapv :delta results)
        activations (mapcat :activations results)
        errors (filterv #(= :error (:status %)) results)
        interrupts (filterv #(= :interrupt (:status %)) results)
        ;; 合并所有 delta
        new-state (reducer/apply-deltas state deltas field-reducers)
        ;; 分离普通目标和 dispatch
        {:keys [targets dispatches]} (dispatch/separate-targets-and-dispatches activations)]
    {:state new-state
     :targets targets
     :dispatches dispatches
     :errors errors
     :interrupts interrupts}))

(defn- run-iteration
  "执行一次迭代

   参数:
   - activation-chan: 激活通道
   - result-chan: 结果通道
   - vertices: 顶点 map
   - state: 当前状态
   - activations: 本次激活的节点/dispatch
   - iteration: 当前迭代号
   - field-reducers: 字段 reducer

   返回: channel，包含迭代结果"
  [activation-chan result-chan vertices state activations iteration field-reducers]
  (go
    (if (empty? activations)
      ;; 无激活 -> 完成
      {:status STATUS-COMPLETED
       :state state
       :iteration iteration}
      ;; 发送激活请求
      (let [;; 分离普通目标和 dispatch
            {:keys [targets dispatches]} (dispatch/separate-targets-and-dispatches activations)
            ;; 检查是否到达 END
            has-end? (some #(= node/END %) targets)
            ;; 过滤掉 END
            targets (filterv #(not= node/END %) targets)
            ;; 计算总激活数
            total-activations (+ (count targets) (count dispatches))]
        (if (and has-end? (zero? total-activations))
          ;; 只有 END -> 完成
          {:status STATUS-COMPLETED
           :state state
           :iteration iteration}
          ;; 发送激活
          (do
            ;; 发送普通目标激活
            (doseq [node-id targets]
              (>! activation-chan (create-activation node-id nil iteration)))
            ;; 发送 dispatch 激活
            (doseq [d dispatches]
              (let [node-id (:node d)
                    input (assoc (:input d) :__dispatch_id__ (:id d))]
                (>! activation-chan (create-activation node-id input iteration))))
            ;; 收集结果
            (let [results (loop [collected []
                                 remaining total-activations]
                            (if (zero? remaining)
                              collected
                              (let [result (<! result-chan)]
                                (recur (conj collected result)
                                       (dec remaining)))))
                  ;; 处理结果
                  processed (process-results results state field-reducers)]
              (cond
                ;; 有错误
                (seq (:errors processed))
                {:status STATUS-ERROR
                 :state (:state processed)
                 :iteration iteration
                 :errors (:errors processed)}

                ;; 有中断
                (seq (:interrupts processed))
                {:status STATUS-INTERRUPTED
                 :state (:state processed)
                 :iteration iteration
                 :interrupts (:interrupts processed)}

                ;; 正常继续
                :else
                {:status STATUS-RUNNING
                 :state (:state processed)
                 :iteration iteration
                 :next-activations (into (:targets processed)
                                         (:dispatches processed))}))))))))

;;; ============================================================
;;; 公共 API
;;; ============================================================

(defn run
  "同步执行图（阻塞直到完成）

   参数:
   - graph-spec: 编译后的图规范
   - initial-state: 初始状态
   - opts: 可选参数
     :field-reducers  - 字段 reducer 配置
     :max-iterations  - 最大迭代次数（覆盖图定义）
     :num-workers     - worker 数量（默认 4）
     :timeout-ms      - 执行超时（毫秒）

   返回:
   {:status :completed/:error/:interrupted/:max-iterations
    :state final-state
    :iterations n
    :errors [...] (如有)
    :interrupts [...] (如有)}"
  [graph-spec initial-state & {:keys [field-reducers max-iterations
                                       num-workers timeout-ms]
                                :or {field-reducers {}
                                     num-workers 4
                                     timeout-ms 60000}}]
  (let [max-iter (or max-iterations (:max-iterations graph-spec) 100)
        vertices (:vertices graph-spec)
        entry (:entry graph-spec)
        ;; 创建 channel
        activation-chan (chan 100)
        result-chan (chan 100)
        state-atom (atom initial-state)
        ;; 启动 workers
        _ (start-workers activation-chan result-chan vertices state-atom num-workers)
        ;; 执行循环
        final-result
        (<!!
          (go-loop [state initial-state
                    activations [entry]
                    iteration 0]
            (if (>= iteration max-iter)
              {:status STATUS-MAX-ITERATIONS
               :state state
               :iterations iteration}
              (let [;; 更新 state-atom 供 worker 读取
                    _ (reset! state-atom state)
                    ;; 执行一次迭代
                    iter-result (<! (run-iteration
                                      activation-chan result-chan
                                      vertices state activations
                                      iteration field-reducers))]
                (case (:status iter-result)
                  :completed
                  {:status STATUS-COMPLETED
                   :state (:state iter-result)
                   :iterations (inc iteration)}

                  :error
                  {:status STATUS-ERROR
                   :state (:state iter-result)
                   :iterations (inc iteration)
                   :errors (:errors iter-result)}

                  :interrupted
                  {:status STATUS-INTERRUPTED
                   :state (:state iter-result)
                   :iterations (inc iteration)
                   :interrupts (:interrupts iter-result)}

                  :running
                  (recur (:state iter-result)
                         (:next-activations iter-result)
                         (inc iteration))

                  ;; 未知状态
                  {:status STATUS-ERROR
                   :state state
                   :iterations iteration
                   :errors [{:reason "Unknown iteration status"
                             :status (:status iter-result)}]})))))]
    ;; 关闭 channel
    (close! activation-chan)
    (close! result-chan)
    final-result))

(defn run-async
  "异步执行图，返回 ExecutorHandle

   参数: 同 run

   返回: ExecutorHandle map
   {:result-chan channel  ;; 结果通道
    :control-chan channel ;; 控制通道
    :status-atom atom}    ;; 状态 atom"
  [graph-spec initial-state & {:keys [field-reducers max-iterations
                                       num-workers]
                                :or {field-reducers {}
                                     num-workers 4}}]
  (let [max-iter (or max-iterations (:max-iterations graph-spec) 100)
        vertices (:vertices graph-spec)
        entry (:entry graph-spec)
        ;; 创建 channel
        activation-chan (chan 100)
        result-chan (chan 100)
        final-result-chan (chan 1)
        control-chan (chan 1)
        state-atom (atom initial-state)
        status-atom (atom STATUS-RUNNING)
        ;; 启动 workers
        _ (start-workers activation-chan result-chan vertices state-atom num-workers)]
    ;; 启动执行循环
    (go-loop [state initial-state
              activations [entry]
              iteration 0]
      ;; 检查控制信号
      (let [[v port] (async/alts! [control-chan (timeout 0)] :default :continue)]
        (cond
          ;; 停止信号
          (= v :stop)
          (do
            (reset! status-atom STATUS-COMPLETED)
            (>! final-result-chan {:status STATUS-COMPLETED
                                   :state state
                                   :iterations iteration
                                   :stopped true}))

          ;; 达到最大迭代
          (>= iteration max-iter)
          (do
            (reset! status-atom STATUS-MAX-ITERATIONS)
            (>! final-result-chan {:status STATUS-MAX-ITERATIONS
                                   :state state
                                   :iterations iteration}))

          ;; 继续执行
          :else
          (do
            (reset! state-atom state)
            (let [iter-result (<! (run-iteration
                                    activation-chan result-chan
                                    vertices state activations
                                    iteration field-reducers))]
              (case (:status iter-result)
                :completed
                (do
                  (reset! status-atom STATUS-COMPLETED)
                  (>! final-result-chan {:status STATUS-COMPLETED
                                         :state (:state iter-result)
                                         :iterations (inc iteration)}))

                :error
                (do
                  (reset! status-atom STATUS-ERROR)
                  (>! final-result-chan {:status STATUS-ERROR
                                         :state (:state iter-result)
                                         :iterations (inc iteration)
                                         :errors (:errors iter-result)}))

                :interrupted
                (do
                  (reset! status-atom STATUS-INTERRUPTED)
                  (>! final-result-chan {:status STATUS-INTERRUPTED
                                         :state (:state iter-result)
                                         :iterations (inc iteration)
                                         :interrupts (:interrupts iter-result)}))

                :running
                (recur (:state iter-result)
                       (:next-activations iter-result)
                       (inc iteration))))))))
    ;; 返回 handle
    {:result-chan final-result-chan
     :control-chan control-chan
     :status-atom status-atom
     :state-atom state-atom}))

(defn stop-async
  "停止异步执行

   参数:
   - handle: run-async 返回的 handle

   返回: nil"
  [handle]
  (put! (:control-chan handle) :stop))

(defn wait-async
  "等待异步执行完成

   参数:
   - handle: run-async 返回的 handle

   返回: 执行结果"
  [handle]
  (<!! (:result-chan handle)))

;;; ============================================================
;;; 流式执行
;;; ============================================================

(defn stream
  "流式执行图，返回迭代器函数

   每次调用迭代器函数执行一次迭代。

   参数:
   - graph-spec: 编译后的图规范
   - initial-state: 初始状态
   - opts: 可选参数（同 run）

   返回: 迭代器函数
   调用迭代器返回:
   {:yield state :iteration n :next fn} - 中间状态
   {:done result} - 执行完成"
  [graph-spec initial-state & {:keys [field-reducers max-iterations
                                       num-workers]
                                :or {field-reducers {}
                                     num-workers 4}}]
  (let [max-iter (or max-iterations (:max-iterations graph-spec) 100)
        vertices (:vertices graph-spec)
        entry (:entry graph-spec)
        ;; 创建 channel
        activation-chan (chan 100)
        result-chan (chan 100)
        state-atom (atom initial-state)
        ;; 启动 workers
        _ (start-workers activation-chan result-chan vertices state-atom num-workers)]
    ;; 返回迭代器函数
    (letfn [(make-iterator [state activations iteration]
              (fn []
                (if (>= iteration max-iter)
                  {:done {:status STATUS-MAX-ITERATIONS
                          :state state
                          :iterations iteration}}
                  (let [_ (reset! state-atom state)
                        iter-result (<!! (run-iteration
                                           activation-chan result-chan
                                           vertices state activations
                                           iteration field-reducers))]
                    (case (:status iter-result)
                      :completed
                      {:done {:status STATUS-COMPLETED
                              :state (:state iter-result)
                              :iterations (inc iteration)}}

                      :error
                      {:done {:status STATUS-ERROR
                              :state (:state iter-result)
                              :iterations (inc iteration)
                              :errors (:errors iter-result)}}

                      :interrupted
                      {:done {:status STATUS-INTERRUPTED
                              :state (:state iter-result)
                              :iterations (inc iteration)
                              :interrupts (:interrupts iter-result)}}

                      :running
                      {:yield (:state iter-result)
                       :iteration (inc iteration)
                       :next (make-iterator
                               (:state iter-result)
                               (:next-activations iter-result)
                               (inc iteration))})))))]
      ;; 初始迭代器
      (make-iterator initial-state [entry] 0))))
