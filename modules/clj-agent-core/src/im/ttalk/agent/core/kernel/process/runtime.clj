(ns im.ttalk.agent.core.kernel.process.runtime
  "Process Runtime - Channel-based 并行执行引擎

   基于 core.async 的事件驱动并行执行模型。
   每个 Step 拥有独立的 go-loop worker，通过 channel 通信。

   架构:
                     ┌──────────────┐
                     │  event-chan   │  ← 事件总线
                     └──────┬───────┘
                            │
                     ┌──────▼───────┐
                     │   router     │  ← go-loop: 路由事件到 step
                     └──────┬───────┘
                            │
               ┌────────────┼────────────┐
               ▼            ▼            ▼
         [step-a]      [step-b]      [step-c]   ← 各自 worker
               │            │            │
               └────────────┼────────────┘
                            ▼
                     ┌──────────────┐
                     │  event-chan   │  ← 产出事件回流
                     └──────────────┘

   Runtime 结构:
   {:status       atom  ;; :running | :paused | :completed | :failed
    :process-spec  map
    :event-chan   chan   ;; 事件总线
    :control-chan chan   ;; 控制信号
    :steps        {step-id step-runtime}
    :context      atom  ;; 共享 context
    :in-flight    atom  ;; 进行中计数
    :error        atom  ;; 错误信息
    :result-chan  chan   ;; 最终结果输出
    :paused-step  atom  ;; 暂停的 step-id
    :pause-reason atom} ;; 暂停原因

   使用:
   ;; 异步
   (let [result-ch (start-process process-spec opts)]
     (async/<!! result-ch))

   ;; 同步便利
   (run-process process-spec opts)
   ;; -> {:status :completed :context ctx}

   ;; 暂停/恢复
   (let [rt (run-process process-spec opts)]
     (when (= :paused (:status rt))
       (run-resume rt resume-data opts)))"
  (:require [clojure.core.async :as async :refer [go go-loop <! >! >!! <!! chan
                                                   put! close! alt! alt!! timeout]]
            [im.ttalk.agent.core.kernel.process.event :as event]
            [im.ttalk.agent.core.kernel.process.step :as step]
            [im.ttalk.agent.core.kernel.process.snapshot-manager :as sm]
            [im.ttalk.agent.core.kernel.context :as ctx]))

;;; ============================================================
;;; ProcessHandle - 外部事件交互句柄
;;; ============================================================

(defrecord ProcessHandle
  [runtime           ;; 完整 runtime map
   external-chan     ;; 外部事件通道
   result-chan       ;; 结果通道
   status-atom])     ;; 状态 atom

(def ^:private default-timeout-ms
  "默认全局超时（防止无限等待）"
  60000)

;;; ============================================================
;;; Step Worker
;;; ============================================================

(defn- start-step-worker
  "启动 step 的 worker go-loop

   监听 input-chan，收集输入，检查激活条件，执行 on-activate。
   执行结果的事件通过 event-chan 回流。

   参数:
   - step-id:     step 标识
   - step-spec:    step 定义
   - input-chan:  接收投递输入的 channel
   - event-chan:  事件总线（产出事件放入此处）
   - context:    context atom
   - in-flight:  进行中计数 atom
   - on-pause:   暂停回调 (fn [step-id reason state])
   - on-error:   错误回调 (fn [step-id reason])
   - opts:       可选参数
     :initial-step-state  恢复的 step 状态 {:state any :activation-count int}
     :active-count        正在执行 on-activate 的 step 计数 atom
     :on-step-done        step 完成后回调 (fn [step-id])

   返回: step-runtime map（含 state atom 和 worker handle）"
  [step-id step-spec input-chan event-chan context in-flight on-pause on-error
   & {:keys [initial-step-state active-count on-step-done on-step-checkpoint]}]
  (let [init-fn (:init step-spec)
        config (or (:config step-spec) {})
        initial-state (if initial-step-state
                        (:state initial-step-state)
                        (when init-fn (init-fn config)))
        initial-count (if initial-step-state
                        (:activation-count initial-step-state)
                        0)
        state-atom (atom {:collected-inputs {}
                          :state            initial-state
                          :activation-count initial-count})
        worker
        (go-loop []
          (when-let [{:keys [input-name data]} (<! input-chan)]
            ;; 收集输入
            (swap! state-atom
                   (fn [s] (assoc-in s [:collected-inputs input-name] data)))
            ;; 检查激活条件
            (let [current @state-atom
                  step-state {:step-spec         step-spec
                              :state            (:state current)
                              :collected-inputs (:collected-inputs current)
                              :activation-count (:activation-count current)}]
              (if (step/check-activation step-state)
                (do ;; 清除输入 + 增加计数
                (swap! state-atom
                       (fn [s]
                         (-> s
                             (assoc :collected-inputs {})
                             (update :activation-count inc))))
                ;; 在独立线程执行（避免阻塞 go-loop 线程池）
                (when active-count (swap! active-count inc))
                (let [ctx-snapshot @context
                      result-ch
                      (async/thread
                        (try
                          (let [on-activate (:on-activate step-spec)
                                inputs (:collected-inputs current)
                                result (on-activate inputs (:state current) ctx-snapshot)
                                ;; 标记事件来源
                                result (if (:events result)
                                         (update result :events
                                                 (fn [evts]
                                                   (mapv #(event/with-source % step-id) evts)))
                                         result)]
                            {:ok result})
                          (catch Exception e
                            {:error (.getMessage e)})))]
                  ;; 等待执行结果
                  (let [exec-result (<! result-ch)
                        ;; active-count 辅助：dec 并检查是否触发 on-step-done
                        dec-active! (fn []
                                      (when active-count
                                        (let [n (swap! active-count dec)]
                                          (when (and (zero? n) on-step-done)
                                            (on-step-done step-id)))))]
                    (cond
                      ;; 执行异常
                      (:error exec-result)
                      (do
                        (swap! in-flight dec)
                        (dec-active!)
                        (on-error step-id (:error exec-result)))

                      ;; 正常结果
                      (:ok exec-result)
                      (let [result (:ok exec-result)]
                        (cond
                          ;; 产出事件
                          (:events result)
                          (do
                            ;; 更新 step state
                            (when (contains? result :state)
                              (swap! state-atom assoc :state (:state result)))
                            ;; 更新 context
                            (when (:context result)
                              (swap! context
                                     (fn [old-ctx]
                                       (-> old-ctx
                                           (update :variables merge
                                                   (:variables (:context result)))
                                           (assoc :messages (:messages (:context result)))
                                           (assoc :history (:history (:context result)))))))
                            ;; 事件入队
                            (let [events (or (:events result) [])]
                              (if (seq events)
                                (do
                                  (swap! in-flight + (dec (count events)))
                                  ;; 先 dec active-count 再分发事件，避免竞态
                                  (dec-active!)
                                  ;; Checkpointer: per-step checkpoint
                                  (when on-step-checkpoint
                                    (on-step-checkpoint step-id))
                                  (doseq [evt events]
                                    (>! event-chan evt)))
                                (do
                                  (swap! in-flight dec)
                                  (dec-active!)
                                  ;; Checkpointer: per-step checkpoint
                                  (when on-step-checkpoint
                                    (on-step-checkpoint step-id))))))

                          ;; 暂停
                          (:pause result)
                          (do
                            (when (contains? (:pause result) :state)
                              (swap! state-atom assoc :state
                                     (get-in result [:pause :state])))
                            (swap! in-flight dec)
                            (dec-active!)
                            (on-pause step-id
                                      (get-in result [:pause :reason])
                                      @state-atom))

                          ;; 错误
                          (:error result)
                          (do
                            (swap! in-flight dec)
                            (dec-active!)
                            (on-error step-id (get-in result [:error :reason])))

                          ;; 无事件产出（正常结束）
                          :else
                          (do
                            (when (contains? result :state)
                              (swap! state-atom assoc :state (:state result)))
                            (when (:context result)
                              (swap! context
                                     (fn [old-ctx]
                                       (-> old-ctx
                                           (update :variables merge
                                                   (:variables (:context result)))
                                           (assoc :messages (:messages (:context result)))
                                           (assoc :history (:history (:context result)))))))
                            (swap! in-flight dec)
                            (dec-active!)
                            ;; 如果 step 返回 :terminate true，发送终止信号
                            (when (:terminate result)
                              (put! event-chan {:name :__terminate__ :type :internal})))))))))
                ;; 未激活 → dec in-flight（输入已收集但 step 未执行）
                (swap! in-flight dec))
            (recur))))]
    {:step-spec   step-spec
     :input-chan input-chan
     :state-atom state-atom
     :worker     worker}))

;;; ============================================================
;;; Router
;;; ============================================================

(defn- start-router
  "启动事件路由 go-loop（支持外部事件）

   从 event-chan 和 external-chan 取事件，根据 bindings 路由到目标 step 的 input-chan。
   监听 control-chan 的 pause/stop 信号。

   参数:
   - event-chan:    事件总线（内部事件）
   - external-chan: 外部事件通道（可选，nil 则不监听）
   - control-chan:  控制信号 channel
   - bindings:      事件绑定列表
   - steps:         {step-id step-runtime}
   - in-flight:     进行中计数 atom
   - on-idle:       空闲回调（event-chan 空且 in-flight=0）

   返回: router go-block handle"
  [event-chan external-chan control-chan bindings steps in-flight on-idle]
  (let [;; 构建 alt! 需要监听的 channel 列表
        route-event!
        (fn [event]
          (let [deliveries (event/route event bindings)]
            (if (seq deliveries)
              (do
                ;; 增加 in-flight（每个 delivery 一次，减去原来的 1）
                (swap! in-flight + (dec (count deliveries)))
                ;; 投递到目标 step
                (doseq [{:keys [step-id input-name data]} deliveries]
                  (when-let [step-rt (get steps step-id)]
                    (put! (:input-chan step-rt)
                          {:input-name input-name :data data}))))
              ;; 无匹配路由 → dec in-flight
              (swap! in-flight dec))))
        ;; 跟踪 external-chan 是否已关闭
        external-closed (atom false)
        ;; 构建监听的 channel 列表（过滤掉 nil）
        base-ports [control-chan event-chan]
        all-ports (if external-chan
                    (conj base-ports external-chan)
                    base-ports)]
    (go-loop []
      ;; 使用 alts! 支持动态 channel 列表
      (let [timeout-ch (timeout 10)
            ports-with-timeout (conj all-ports timeout-ch)
            [v port] (async/alts! ports-with-timeout :priority true)]
        (cond
          ;; 控制信号
          (= port control-chan)
          (when v
            (case (:action v)
              :stop nil  ;; 停止 router
              :pause nil ;; 暂停 router
              :resume (recur)
              (recur)))

          ;; 内部事件
          (= port event-chan)
          (if v
            (do
              ;; 检查是否是终止信号
              (if (= :__terminate__ (:name v))
                (do
                  (reset! external-closed true)
                  (recur))
                (do
                  (route-event! v)
                  (recur))))
            ;; event-chan 关闭
            nil)

          ;; 外部事件
          (and external-chan (= port external-chan))
          (if v
            (do
              (swap! in-flight inc)
              (route-event! v)
              (recur))
            (do
              (reset! external-closed true)
              (recur)))

          ;; 超时 - 空闲检测
          (= port timeout-ch)
          (if (zero? @in-flight)
            (if (and external-chan (not @external-closed))
              (recur)
              (on-idle))
            (recur))

          ;; 其他情况
          :else (recur))))))

;;; ============================================================
;;; Checkpointer 辅助
;;; ============================================================

(defn- capture-snapshot-from-atoms
  "从运行时 atoms 捕获当前快照（纯数据）

   参数:
   - process-spec: process 定义
   - steps:        {step-id step-runtime}
   - context-atom: context atom
   - extra:        额外合并的字段

   返回: 纯数据 snapshot map"
  [process-spec steps context-atom extra]
  (let [step-states (reduce-kv
                      (fn [acc step-id step-rt]
                        (let [state-data @(:state-atom step-rt)]
                          (assoc acc step-id
                                 {:state (:state state-data)
                                  :activation-count (:activation-count state-data)})))
                      {}
                      steps)]
    (merge {:process-name (:name process-spec)
            :status       :running
            :paused-step  nil
            :pause-reason nil
            :context      @context-atom
            :step-states  step-states
            :created-at   (System/currentTimeMillis)}
           extra)))

(defn- do-checkpoint!
  "执行 checkpoint 保存（静默忽略异常）

   参数:
   - checkpointer: IProcessSnapshotManager 实例
   - thread-id:    线程标识
   - snapshot:     快照数据
   - metadata:     元数据"
  [checkpointer thread-id snapshot metadata]
  (try
    (sm/save-checkpoint checkpointer thread-id snapshot metadata)
    (catch Exception _e nil)))

;;; ============================================================
;;; Process 生命周期
;;; ============================================================

(defn start-process
  "启动 process（异步）

   初始化所有 step worker，启动 router，放入初始事件。

   参数:
   - process-spec: 编译后的 process 定义
   - opts:        选项 map
     :context       Context 对象（可选）
     :timeout-ms    全局超时毫秒（默认 60000）
     :step-states   恢复的 step 状态 {step-id {:state any :activation-count int}}
     :on-quiescent  静止点回调 (fn [snapshot] ...)
                    当所有并发 step 执行完毕时触发，snapshot 格式同 create-process-snapshot
     :checkpointer  IProcessSnapshotManager 实例（可选）
                    提供时自动在关键节点保存检查点
     :thread-id     执行线程标识（可选，默认自动生成 UUID）
     :checkpoint-policy 检查点策略（可选，默认 :on-pause-only）
                    :every-step    每个 step 完成时保存
                    :on-pause-only 仅暂停/完成时保存
                    :on-quiescent  暂停/完成/静止点时保存

   返回:
   runtime map，包含 result-chan（完成时放入结果）

   结果格式:
   {:status  :completed/:paused/:failed
    :context updated-ctx
    :error   nil-or-error-info
    :paused-step nil-or-step-id
    :pause-reason nil-or-reason
    :runtime runtime}  ;; 用于 resume"
  [process-spec opts]
  (let [step-states (or (:step-states opts) {})
        context-atom (atom (or (:context opts) (ctx/create)))
        timeout-ms (or (:timeout-ms opts) default-timeout-ms)
        on-quiescent (:on-quiescent opts)
        ;; Checkpointer 支持
        checkpointer (:checkpointer opts)
        thread-id (or (:thread-id opts)
                      (when checkpointer (str (java.util.UUID/randomUUID))))
        checkpoint-policy (or (:checkpoint-policy opts) :on-pause-only)
        event-chan (chan 256)
        control-chan (chan 16)
        result-chan (chan 1)
        in-flight (atom 0)
        status (atom :running)
        error-atom (atom nil)
        paused-step-atom (atom nil)
        pause-reason-atom (atom nil)
        ;; steps-ref 用于 on-quiescent 和 checkpointer
        need-steps-ref (or on-quiescent checkpointer)
        ;; on-quiescent 支持
        active-count (when on-quiescent (atom 0))
        steps-ref (when need-steps-ref (atom nil))
        on-step-done (when on-quiescent
                       (fn [_step-id]
                         (when (and (= :running @status)
                                    (pos? @in-flight))
                           (let [steps @steps-ref
                                 snapshot
                                 (reduce-kv
                                   (fn [acc sid step-rt]
                                     (let [sd @(:state-atom step-rt)]
                                       (assoc acc sid
                                              {:state (:state sd)
                                               :activation-count (:activation-count sd)})))
                                   {}
                                   steps)
                                 quiescent-snapshot {:process-name (:name process-spec)
                                                    :reason       :quiescent
                                                    :status       :running
                                                    :step-states  snapshot
                                                    :context      @context-atom
                                                    :created-at   (System/currentTimeMillis)}]
                             (try (on-quiescent quiescent-snapshot)
                                  (catch Exception _e nil))
                             ;; Checkpointer: on-quiescent 策略时保存
                             (when (and checkpointer (= checkpoint-policy :on-quiescent))
                               (do-checkpoint! checkpointer thread-id quiescent-snapshot
                                               {:reason :quiescent
                                                :created-at (System/currentTimeMillis)}))))))

        ;; Checkpointer: per-step 回调（:every-step 策略）
        on-step-checkpoint (when (and checkpointer (= checkpoint-policy :every-step))
                             (fn [step-id]
                               (when steps-ref
                                 (let [snapshot (capture-snapshot-from-atoms
                                                  process-spec @steps-ref context-atom
                                                  {:status :running})]
                                   (do-checkpoint! checkpointer thread-id snapshot
                                                   {:step step-id
                                                    :reason :step-done
                                                    :created-at (System/currentTimeMillis)})))))

        ;; 完成回调
        on-idle (fn []
                  (when (compare-and-set! status :running :completed)
                    ;; Checkpointer: 完成时保存
                    (when (and checkpointer steps-ref)
                      (let [snapshot (capture-snapshot-from-atoms
                                      process-spec @steps-ref context-atom
                                      {:status :completed})]
                        (do-checkpoint! checkpointer thread-id snapshot
                                        {:reason :completed
                                         :created-at (System/currentTimeMillis)})))
                    (put! result-chan
                           {:status       :completed
                            :context      @context-atom
                            :error        nil
                            :paused-step  nil
                            :pause-reason nil})))

        ;; 暂停回调
        on-pause (fn [step-id reason _state]
                   (when (compare-and-set! status :running :paused)
                     (reset! paused-step-atom step-id)
                     (reset! pause-reason-atom reason)
                     ;; Checkpointer: 暂停时保存
                     (when (and checkpointer steps-ref)
                       (let [snapshot (capture-snapshot-from-atoms
                                        process-spec @steps-ref context-atom
                                        {:status :paused
                                         :paused-step step-id
                                         :pause-reason reason})]
                         (do-checkpoint! checkpointer thread-id snapshot
                                         {:step step-id
                                          :reason :paused
                                          :created-at (System/currentTimeMillis)})))
                     ;; 触发 on-quiescent（reason :paused）
                     (when (and on-quiescent steps-ref)
                       (let [steps @steps-ref
                             step-snapshot
                             (reduce-kv
                               (fn [acc sid step-rt]
                                 (let [sd @(:state-atom step-rt)]
                                   (assoc acc sid
                                          {:state (:state sd)
                                           :activation-count (:activation-count sd)})))
                               {}
                               steps)]
                         (try
                           (on-quiescent {:process-name  (:name process-spec)
                                          :reason        :paused
                                          :status        :paused
                                          :paused-step   step-id
                                          :pause-reason  reason
                                          :step-states   step-snapshot
                                          :context       @context-atom
                                          :created-at    (System/currentTimeMillis)})
                           (catch Exception _e nil))))
                     ;; 停止 router
                     (put! control-chan {:action :pause})
                     (put! result-chan
                            {:status       :paused
                             :context      @context-atom
                             :error        nil
                             :paused-step  step-id
                             :pause-reason reason})))

        ;; 错误回调
        on-error (fn [step-id reason]
                   (let [error-handler-id (:error-handler process-spec)]
                     (if (and error-handler-id (not= step-id error-handler-id))
                       ;; 路由到 error-handler step
                       (let [step-rt (get (:steps (meta event-chan)) error-handler-id)]
                         ;; 直接投递到 error-handler 的 input-chan
                         ;; 注意：此时需要通过 steps map
                         (swap! in-flight inc)
                         (let [err-data {:reason reason :source step-id}]
                           (put! event-chan (event/error-event step-id reason))))
                       ;; 无 handler 或 handler 自身出错 → failed
                       (when (compare-and-set! status :running :failed)
                         (reset! error-atom {:step step-id :reason reason})
                         (put! control-chan {:action :stop})
                         (put! result-chan
                                {:status       :failed
                                 :context      @context-atom
                                 :error        {:step step-id :reason reason}
                                 :paused-step  nil
                                 :pause-reason nil})))))

        ;; 创建 step workers
        steps (reduce-kv
                (fn [acc step-id step-spec]
                  (let [input-chan (chan 64)
                        saved-state (get step-states step-id)
                        step-rt (start-step-worker
                                  step-id step-spec input-chan event-chan
                                  context-atom in-flight on-pause on-error
                                  :initial-step-state saved-state
                                  :active-count active-count
                                  :on-step-checkpoint on-step-checkpoint
                                  :on-step-done on-step-done)]
                    (assoc acc step-id step-rt)))
                {}
                (:steps process-spec))
        _ (when steps-ref (reset! steps-ref steps))

        ;; 错误事件绑定（如有 error-handler）
        error-bindings (when-let [eh (:error-handler process-spec)]
                         [(event/binding :error eh :error)])
        all-bindings (into (vec (:bindings process-spec))
                           (or error-bindings []))

        ;; 启动 router（传递 nil 表示不支持外部事件）
        router (start-router event-chan nil control-chan all-bindings
                             steps in-flight on-idle)

        ;; Runtime 结构
        runtime {:status       status
                 :process-spec  process-spec
                 :event-chan   event-chan
                 :control-chan control-chan
                 :steps        steps
                 :context      context-atom
                 :in-flight    in-flight
                 :error        error-atom
                 :paused-step  paused-step-atom
                 :pause-reason pause-reason-atom
                 :result-chan  result-chan
                 :router       router
                 :thread-id   thread-id
                 :checkpointer checkpointer}]

    ;; 放入初始事件
    (let [initial-events (:initial-events process-spec)]
      (if (seq initial-events)
        (do
          (reset! in-flight (count initial-events))
          (doseq [evt initial-events]
            (put! event-chan evt)))
        ;; 无初始事件 → 直接完成
        (do
          (compare-and-set! status :running :completed)
          (put! result-chan {:status       :completed
                            :context      @context-atom
                            :error        nil
                            :paused-step  nil
                            :pause-reason nil}))))

    ;; 超时保护
    (go
      (<! (timeout timeout-ms))
      (when (compare-and-set! status :running :failed)
        (reset! error-atom {:reason "全局超时" :timeout-ms timeout-ms})
        (put! control-chan {:action :stop})
        (put! result-chan
               {:status       :failed
                :context      @context-atom
                :error        {:reason "全局超时" :timeout-ms timeout-ms}
                :paused-step  nil
                :pause-reason nil})))

    ;; 返回 runtime（含 result-chan）
    (assoc runtime :result-chan result-chan)))

(defn- shutdown-runtime
  "关闭 runtime：先调用各 step 的 on-terminate，再关闭 channel"
  [runtime]
  ;; 调用各 step 的 on-terminate 进行资源清理
  (let [context @(:context runtime)]
    (doseq [[_ step-rt] (:steps runtime)]
      (let [state-data @(:state-atom step-rt)
            step-state (assoc state-data :step-spec (:step-spec step-rt))]
        (step/terminate-step step-state context))))
  ;; 关闭 channel
  (close! (:event-chan runtime))
  (close! (:control-chan runtime))
  (doseq [[_ step-rt] (:steps runtime)]
    (close! (:input-chan step-rt))))

;;; ============================================================
;;; Process Snapshot（纯数据快照）
;;; ============================================================

(defn create-process-snapshot
  "将运行时状态捕获为纯数据快照（无 atom/channel）

   用于跨进程持久化。调用时机：process 暂停后。

   参数:
   - runtime:       runtime map
   - paused-result: 暂停结果 map

   返回:
   {:process-name   keyword
    :status         :paused
    :paused-step    keyword
    :pause-reason   string
    :context        context-map
    :step-states    {step-id {:state any :activation-count int}}
    :created-at     long}"
  [runtime paused-result]
  (let [step-states (reduce-kv
                      (fn [acc step-id step-rt]
                        (let [state-data @(:state-atom step-rt)]
                          (assoc acc step-id
                                 {:state (:state state-data)
                                  :activation-count (:activation-count state-data)})))
                      {}
                      (:steps runtime))]
    {:process-name   (get-in runtime [:process-spec :name])
     :status         :paused
     :paused-step    (:paused-step paused-result)
     :pause-reason   (:pause-reason paused-result)
     :context        (:context paused-result)
     :step-states    step-states
     :created-at     (System/currentTimeMillis)}))

;;; ============================================================
;;; 同步便利 API
;;; ============================================================

(defn run-process
  "运行 process 直到完成、暂停或失败（同步阻塞）

   参数:
   - process-spec: 编译后的 process 定义
   - opts:        选项 map
     :context      Context 对象（可选）
     :timeout-ms   全局超时毫秒（默认 60000）
     :step-states  恢复的 step 状态（可选）
     :on-quiescent 静止点回调（可选），并发 step 全部完成时触发

   返回:
   {:status  :completed/:paused/:failed
    :context updated-ctx
    :error   nil-or-error-info
    :paused-step nil-or-step-id
    :pause-reason nil-or-reason
    :runtime runtime     ;; paused 时可用于 resume
    :snapshot snapshot}   ;; paused 时自动生成的纯数据快照"
  ([process-spec]
   (run-process process-spec {}))
  ([process-spec opts]
   (let [runtime (start-process process-spec opts)
         result (<!! (:result-chan runtime))
         result (assoc result :runtime runtime)
         result (if (= :paused (:status result))
                  (assoc result :snapshot (create-process-snapshot runtime result))
                  result)]
     (shutdown-runtime runtime)
     result)))

;;; ============================================================
;;; 恢复暂停的 Process
;;; ============================================================

(defn resume-process
  "恢复暂停的 process（异步）

   重新创建 runtime，恢复 step 状态，调用 on-resume，继续执行。

   参数:
   - paused-result: run-process 返回的 paused 结果
   - data:          恢复数据（传给 step 的 on-resume）
   - opts:          选项 map

   返回:
   新的 runtime map（含 result-chan）"
  [paused-result data opts]
  (when (not= :paused (:status paused-result))
    (throw (ex-info "只能恢复处于 :paused 状态的 process"
                    {:current-status (:status paused-result)})))
  (let [process-spec (:process-spec (:runtime paused-result))
        paused-step-id (:paused-step paused-result)
        old-runtime (:runtime paused-result)
        old-step-state @(get-in old-runtime [:steps paused-step-id :state-atom])

        ;; 调用 step 的 on-resume
        step-spec (get-in process-spec [:steps paused-step-id])
        on-resume (:on-resume step-spec)
        _ (when-not on-resume
            (throw (ex-info "Step 不支持 resume（缺少 :on-resume）"
                            {:step paused-step-id})))
        ctx (:context paused-result)
        resume-result (try
                        (let [r (on-resume data (:state old-step-state) ctx)]
                          {:ok r})
                        (catch Exception e
                          {:error (.getMessage e)}))]

    (cond
      ;; resume 执行出错
      (:error resume-result)
      (let [result-chan (chan 1)]
        (put! result-chan {:status       :failed
                          :context      ctx
                          :error        {:step paused-step-id
                                         :reason (:error resume-result)}
                          :paused-step  nil
                          :pause-reason nil})
        {:result-chan result-chan})

      ;; resume 正常
      (:ok resume-result)
      (let [result (:ok resume-result)
            ;; 标记事件来源
            result (if (:events result)
                     (update result :events
                             (fn [evts]
                               (mapv #(event/with-source % paused-step-id) evts)))
                     result)]
        (cond
          ;; resume 产出事件 → 继续执行
          (:events result)
          (let [new-ctx (or (:context result) ctx)
                ;; 收集所有 step 状态
                old-step-states (reduce-kv
                                  (fn [acc step-id step-rt]
                                    (let [state-data @(:state-atom step-rt)]
                                      (assoc acc step-id
                                             {:state (:state state-data)
                                              :activation-count (:activation-count state-data)})))
                                  {}
                                  (:steps old-runtime))
                ;; 更新 paused step 的状态（如 on-resume 返回了新 state）
                updated-step-states (if (contains? result :state)
                                      (assoc-in old-step-states
                                                [paused-step-id :state] (:state result))
                                      old-step-states)
                ;; 构造一个新的 process-spec，用 resume 产出的事件作为初始事件
                resumed-def (assoc process-spec
                                   :initial-events (or (:events result) []))
                new-opts (merge opts {:context new-ctx
                                      :step-states updated-step-states
                                      :timeout-ms (or (:timeout-ms opts) default-timeout-ms)})]
            (start-process resumed-def new-opts))

          ;; resume 再次暂停
          (:pause result)
          (let [result-chan (chan 1)]
            (put! result-chan {:status       :paused
                              :context      ctx
                              :error        nil
                              :paused-step  paused-step-id
                              :pause-reason (get-in result [:pause :reason])})
            {:result-chan result-chan})

          ;; resume 报错
          (:error result)
          (let [result-chan (chan 1)]
            (put! result-chan {:status       :failed
                              :context      ctx
                              :error        {:step paused-step-id
                                             :reason (get-in result [:error :reason])}
                              :paused-step  nil
                              :pause-reason nil})
            {:result-chan result-chan})

          ;; resume 无事件（完成）
          :else
          (let [new-ctx (or (:context result) ctx)
                result-chan (chan 1)]
            (put! result-chan {:status       :completed
                              :context      new-ctx
                              :error        nil
                              :paused-step  nil
                              :pause-reason nil})
            {:result-chan result-chan}))))))

(defn run-resume
  "恢复暂停的 process（同步阻塞）

   参数:
   - paused-result: run-process 返回的 paused 结果
   - data:          恢复数据
   - opts:          选项 map

   返回:
   {:status :context :error ...}"
  ([paused-result data]
   (run-resume paused-result data {}))
  ([paused-result data opts]
   (let [runtime (resume-process paused-result data opts)
         result (<!! (:result-chan runtime))]
     (when (:event-chan runtime)
       (shutdown-runtime runtime))
     result)))

;;; ============================================================
;;; 跨进程 Rehydration（从纯数据快照恢复）
;;; ============================================================

(defn restore-from-snapshot
  "从快照恢复 process（跨进程 rehydration）

   Process 定义由调用方提供（函数引用无法序列化）。

   参数:
   - snapshot:     纯数据快照 map（由 create-process-snapshot 生成）
   - process-spec:  编译后的 process 定义
   - resume-data:  传给 paused step 的 on-resume 的数据
   - opts:         运行选项

   返回: runtime map（含 result-chan）"
  [snapshot process-spec resume-data opts]
  (let [paused-step-id (:paused-step snapshot)
        step-spec (get-in process-spec [:steps paused-step-id])
        on-resume (:on-resume step-spec)
        _ (when-not on-resume
            (throw (ex-info "Step 不支持 resume（缺少 :on-resume）"
                            {:step paused-step-id})))
        ctx (:context snapshot)
        step-states (:step-states snapshot)
        paused-step-state (get-in step-states [paused-step-id :state])

        ;; 调用 on-resume
        resume-result (try
                        {:ok (on-resume resume-data paused-step-state ctx)}
                        (catch Exception e
                          {:error (.getMessage e)}))]

    (cond
      ;; resume 执行出错
      (:error resume-result)
      (let [result-chan (chan 1)]
        (put! result-chan {:status       :failed
                          :context      ctx
                          :error        {:step paused-step-id
                                         :reason (:error resume-result)}
                          :paused-step  nil
                          :pause-reason nil})
        {:result-chan result-chan})

      ;; resume 正常
      (:ok resume-result)
      (let [result (:ok resume-result)
            result (if (:events result)
                     (update result :events
                             (fn [evts]
                               (mapv #(event/with-source % paused-step-id) evts)))
                     result)]
        (cond
          ;; 产出事件 → 继续执行
          (:events result)
          (let [new-ctx (or (:context result) ctx)
                ;; 更新 paused step 的状态
                updated-step-states (if (contains? result :state)
                                      (assoc-in step-states
                                                [paused-step-id :state] (:state result))
                                      step-states)
                resumed-def (assoc process-spec
                                   :initial-events (:events result))
                new-opts (merge opts {:context new-ctx
                                      :step-states updated-step-states
                                      :timeout-ms (or (:timeout-ms opts) default-timeout-ms)})]
            (start-process resumed-def new-opts))

          ;; 再次暂停
          (:pause result)
          (let [result-chan (chan 1)]
            (put! result-chan {:status       :paused
                              :context      ctx
                              :error        nil
                              :paused-step  paused-step-id
                              :pause-reason (get-in result [:pause :reason])})
            {:result-chan result-chan})

          ;; 报错
          (:error result)
          (let [result-chan (chan 1)]
            (put! result-chan {:status       :failed
                              :context      ctx
                              :error        {:step paused-step-id
                                             :reason (get-in result [:error :reason])}
                              :paused-step  nil
                              :pause-reason nil})
            {:result-chan result-chan})

          ;; 无事件（完成）
          :else
          (let [new-ctx (or (:context result) ctx)
                result-chan (chan 1)]
            (put! result-chan {:status       :completed
                              :context      new-ctx
                              :error        nil
                              :paused-step  nil
                              :pause-reason nil})
            {:result-chan result-chan}))))))

(defn run-restore
  "从快照恢复 process（同步阻塞）

   参数:
   - snapshot:     纯数据快照 map
   - process-spec:  编译后的 process 定义
   - resume-data:  传给 paused step 的 on-resume 的数据
   - opts:         运行选项（可选）

   返回:
   {:status :context :error ...}"
  ([snapshot process-spec resume-data]
   (run-restore snapshot process-spec resume-data {}))
  ([snapshot process-spec resume-data opts]
   (let [runtime (restore-from-snapshot snapshot process-spec resume-data opts)
         result (<!! (:result-chan runtime))]
     (when (:event-chan runtime)
       (shutdown-runtime runtime))
     result)))

;;; ============================================================
;;; 外部事件支持 - 异步 Process 交互
;;; ============================================================

(defn- start-process-with-external
  "启动 process（带外部事件通道支持）

   与 start-process 类似，但接受外部事件通道用于接收外部注入的事件。

   参数:
   - process-spec:  编译后的 process 定义
   - opts:         选项 map
   - external-chan: 外部事件通道

   返回: runtime map"
  [process-spec opts external-chan]
  (let [step-states (or (:step-states opts) {})
        context-atom (atom (or (:context opts) (ctx/create)))
        timeout-ms (or (:timeout-ms opts) default-timeout-ms)
        on-quiescent (:on-quiescent opts)
        ;; Checkpointer 支持
        checkpointer (:checkpointer opts)
        thread-id (or (:thread-id opts)
                      (when checkpointer (str (java.util.UUID/randomUUID))))
        checkpoint-policy (or (:checkpoint-policy opts) :on-pause-only)
        event-chan (chan 256)
        control-chan (chan 16)
        result-chan (chan 1)
        in-flight (atom 0)
        status (atom :running)
        error-atom (atom nil)
        paused-step-atom (atom nil)
        pause-reason-atom (atom nil)
        ;; steps-ref 用于 on-quiescent 和 checkpointer
        need-steps-ref (or on-quiescent checkpointer)
        ;; on-quiescent 支持
        active-count (when on-quiescent (atom 0))
        steps-ref (when need-steps-ref (atom nil))
        on-step-done (when on-quiescent
                       (fn [_step-id]
                         (when (and (= :running @status)
                                    (pos? @in-flight))
                           (let [steps @steps-ref
                                 snapshot
                                 (reduce-kv
                                   (fn [acc sid step-rt]
                                     (let [sd @(:state-atom step-rt)]
                                       (assoc acc sid
                                              {:state (:state sd)
                                               :activation-count (:activation-count sd)})))
                                   {}
                                   steps)
                                 quiescent-snapshot {:process-name (:name process-spec)
                                                    :reason       :quiescent
                                                    :status       :running
                                                    :step-states  snapshot
                                                    :context      @context-atom
                                                    :created-at   (System/currentTimeMillis)}]
                             (try (on-quiescent quiescent-snapshot)
                                  (catch Exception _e nil))
                             ;; Checkpointer: on-quiescent 策略时保存
                             (when (and checkpointer (= checkpoint-policy :on-quiescent))
                               (do-checkpoint! checkpointer thread-id quiescent-snapshot
                                               {:reason :quiescent
                                                :created-at (System/currentTimeMillis)}))))))

        ;; Checkpointer: per-step 回调（:every-step 策略）
        on-step-checkpoint (when (and checkpointer (= checkpoint-policy :every-step))
                             (fn [step-id]
                               (when steps-ref
                                 (let [snapshot (capture-snapshot-from-atoms
                                                  process-spec @steps-ref context-atom
                                                  {:status :running})]
                                   (do-checkpoint! checkpointer thread-id snapshot
                                                   {:step step-id
                                                    :reason :step-done
                                                    :created-at (System/currentTimeMillis)})))))

        ;; 完成回调
        on-idle (fn []
                  (when (compare-and-set! status :running :completed)
                    ;; Checkpointer: 完成时保存
                    (when (and checkpointer steps-ref)
                      (let [snapshot (capture-snapshot-from-atoms
                                      process-spec @steps-ref context-atom
                                      {:status :completed})]
                        (do-checkpoint! checkpointer thread-id snapshot
                                        {:reason :completed
                                         :created-at (System/currentTimeMillis)})))
                    (put! result-chan
                           {:status       :completed
                            :context      @context-atom
                            :error        nil
                            :paused-step  nil
                            :pause-reason nil})))

        ;; 暂停回调
        on-pause (fn [step-id reason _state]
                   (when (compare-and-set! status :running :paused)
                     (reset! paused-step-atom step-id)
                     (reset! pause-reason-atom reason)
                     ;; Checkpointer: 暂停时保存
                     (when (and checkpointer steps-ref)
                       (let [snapshot (capture-snapshot-from-atoms
                                        process-spec @steps-ref context-atom
                                        {:status :paused
                                         :paused-step step-id
                                         :pause-reason reason})]
                         (do-checkpoint! checkpointer thread-id snapshot
                                         {:step step-id
                                          :reason :paused
                                          :created-at (System/currentTimeMillis)})))
                     ;; 触发 on-quiescent（reason :paused）
                     (when (and on-quiescent steps-ref)
                       (let [steps @steps-ref
                             step-snapshot
                             (reduce-kv
                               (fn [acc sid step-rt]
                                 (let [sd @(:state-atom step-rt)]
                                   (assoc acc sid
                                          {:state (:state sd)
                                           :activation-count (:activation-count sd)})))
                               {}
                               steps)]
                         (try
                           (on-quiescent {:process-name  (:name process-spec)
                                          :reason        :paused
                                          :status        :paused
                                          :paused-step   step-id
                                          :pause-reason  reason
                                          :step-states   step-snapshot
                                          :context       @context-atom
                                          :created-at    (System/currentTimeMillis)})
                           (catch Exception _e nil))))
                     ;; 停止 router
                     (put! control-chan {:action :pause})
                     (put! result-chan
                            {:status       :paused
                             :context      @context-atom
                             :error        nil
                             :paused-step  step-id
                             :pause-reason reason})))

        ;; 错误回调
        on-error (fn [step-id reason]
                   (let [error-handler-id (:error-handler process-spec)]
                     (if (and error-handler-id (not= step-id error-handler-id))
                       ;; 路由到 error-handler step
                       (do
                         (swap! in-flight inc)
                         (put! event-chan (event/error-event step-id reason)))
                       ;; 无 handler 或 handler 自身出错 → failed
                       (when (compare-and-set! status :running :failed)
                         (reset! error-atom {:step step-id :reason reason})
                         (put! control-chan {:action :stop})
                         (put! result-chan
                                {:status       :failed
                                 :context      @context-atom
                                 :error        {:step step-id :reason reason}
                                 :paused-step  nil
                                 :pause-reason nil})))))

        ;; 创建 step workers
        steps (reduce-kv
                (fn [acc step-id step-spec]
                  (let [input-chan (chan 64)
                        saved-state (get step-states step-id)
                        step-rt (start-step-worker
                                  step-id step-spec input-chan event-chan
                                  context-atom in-flight on-pause on-error
                                  :initial-step-state saved-state
                                  :active-count active-count
                                  :on-step-checkpoint on-step-checkpoint
                                  :on-step-done on-step-done)]
                    (assoc acc step-id step-rt)))
                {}
                (:steps process-spec))
        _ (when steps-ref (reset! steps-ref steps))

        ;; 错误事件绑定（如有 error-handler）
        error-bindings (when-let [eh (:error-handler process-spec)]
                         [(event/binding :error eh :error)])
        all-bindings (into (vec (:bindings process-spec))
                           (or error-bindings []))

        ;; 启动 router（传递 external-chan）
        router (start-router event-chan external-chan control-chan all-bindings
                             steps in-flight on-idle)

        ;; Runtime 结构
        runtime {:status        status
                 :process-spec  process-spec
                 :event-chan    event-chan
                 :external-chan external-chan
                 :control-chan  control-chan
                 :steps         steps
                 :context       context-atom
                 :in-flight     in-flight
                 :error         error-atom
                 :paused-step   paused-step-atom
                 :pause-reason  pause-reason-atom
                 :result-chan   result-chan
                 :router        router
                 :thread-id     thread-id
                 :checkpointer  checkpointer}]

    ;; 放入初始事件
    (let [initial-events (:initial-events process-spec)]
      (when (seq initial-events)
        (reset! in-flight (count initial-events))
        (doseq [evt initial-events]
          (put! event-chan evt))))

    ;; 超时保护
    (go
      (<! (timeout timeout-ms))
      (when (compare-and-set! status :running :failed)
        (reset! error-atom {:reason "全局超时" :timeout-ms timeout-ms})
        (put! control-chan {:action :stop})
        (put! result-chan
               {:status       :failed
                :context      @context-atom
                :error        {:reason "全局超时" :timeout-ms timeout-ms}
                :paused-step  nil
                :pause-reason nil})))

    ;; 返回 runtime
    runtime))

(defn start-process-async
  "异步启动 process，返回 ProcessHandle 用于外部交互

   与 run-process 不同，此函数立即返回 ProcessHandle，
   允许外部通过 send-event 向运行中的 process 注入事件。

   参数:
   - process-spec: 编译后的 process 定义
   - opts:        选项 map（同 start-process）

   返回:
   ProcessHandle 实例，可用于:
   - (send-event handle :event-name data) - 发送外部事件
   - (get-status handle) - 获取当前状态
   - (wait-for-completion handle) - 等待完成
   - (stop-process handle) - 停止进程

   示例:
   (let [handle (start-process-async my-process {})]
     (send-event handle :user-input {:text \"hello\"})
     (let [result (wait-for-completion handle)]
       (println \"Result:\" result)))"
  ([process-spec]
   (start-process-async process-spec {}))
  ([process-spec opts]
   (let [external-chan (chan 64)
         runtime (start-process-with-external process-spec opts external-chan)]
     (->ProcessHandle
       runtime
       external-chan
       (:result-chan runtime)
       (:status runtime)))))

(defn send-event
  "向运行中的 process 发送外部事件（非阻塞）

   参数:
   - handle:     ProcessHandle 实例
   - event-name: 事件名称（keyword）
   - data:       事件数据（可选）

   返回:
   true 如果事件已入队，false 如果 process 已结束

   示例:
   (send-event handle :user-input {:text \"hello\"})"
  ([handle event-name]
   (send-event handle event-name nil))
  ([handle event-name data]
   (let [status @(:status-atom handle)]
     (if (= :running status)
       (do
         (put! (:external-chan handle)
               (event/external-event event-name data))
         true)
       false))))

(defn send-event!
  "向运行中的 process 发送外部事件（同步阻塞版本）

   阻塞直到事件被接受或超时。

   参数:
   - handle:     ProcessHandle 实例
   - event-name: 事件名称
   - data:       事件数据
   - timeout-ms: 超时毫秒（默认 5000）

   返回:
   true 如果事件已发送，false 如果超时或 process 已结束"
  ([handle event-name data]
   (send-event! handle event-name data 5000))
  ([handle event-name data timeout-ms]
   (let [status @(:status-atom handle)]
     (if (= :running status)
       (alt!!
         [[(:external-chan handle) (event/external-event event-name data)]] true
         (timeout timeout-ms) false)
       false))))

(defn get-status
  "获取 process 当前状态

   参数:
   - handle: ProcessHandle 实例

   返回: :running | :paused | :completed | :failed"
  [handle]
  @(:status-atom handle))

(defn wait-for-completion
  "等待 process 完成（阻塞）

   参数:
   - handle:     ProcessHandle 实例
   - timeout-ms: 超时毫秒（可选）

   返回:
   结果 map {:status :context :error ...}
   超时时返回 {:status :timeout :error {:reason \"等待超时\"}}"
  ([handle]
   (<!! (:result-chan handle)))
  ([handle timeout-ms]
   (alt!!
     (:result-chan handle) ([result] result)
     (timeout timeout-ms) {:status :timeout
                           :error {:reason "等待超时"}})))

(defn stop-process
  "停止运行中的 process

   发送停止信号并关闭外部事件通道。

   参数:
   - handle: ProcessHandle 实例

   返回: true"
  [handle]
  (let [runtime (:runtime handle)
        status-atom (:status-atom handle)]
    ;; 标记为停止状态（阻止后续 send-event）
    (reset! status-atom :stopped)
    (put! (:control-chan runtime) {:action :stop})
    (close! (:external-chan handle))
    ;; 关闭 runtime 资源
    (shutdown-runtime runtime)
    true))
