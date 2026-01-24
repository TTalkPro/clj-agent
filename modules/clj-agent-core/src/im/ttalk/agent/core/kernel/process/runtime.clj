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
                                                   put! close! alt! timeout]]
            [im.ttalk.agent.core.kernel.process.event :as event]
            [im.ttalk.agent.core.kernel.process.step :as step]
            [im.ttalk.agent.core.kernel.context :as ctx]))

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
   - step-def:    step 定义
   - input-chan:  接收投递输入的 channel
   - event-chan:  事件总线（产出事件放入此处）
   - context:    context atom
   - in-flight:  进行中计数 atom
   - on-pause:   暂停回调 (fn [step-id reason state])
   - on-error:   错误回调 (fn [step-id reason])
   - opts:       可选参数
     :initial-step-state  恢复的 step 状态 {:state any :activation-count int}

   返回: step-runtime map（含 state atom 和 worker handle）"
  [step-id step-def input-chan event-chan context in-flight on-pause on-error
   & {:keys [initial-step-state]}]
  (let [init-fn (:init step-def)
        config (or (:config step-def) {})
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
                  step-state {:step-def         step-def
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
                (let [ctx-snapshot @context
                      result-ch
                      (async/thread
                        (try
                          (let [on-activate (:on-activate step-def)
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
                  (let [exec-result (<! result-ch)]
                    (cond
                      ;; 执行异常
                      (:error exec-result)
                      (do
                        (swap! in-flight dec)
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
                                  ;; 新事件产出：先 dec 自己的 in-flight，再 inc 新事件的
                                  (swap! in-flight + (dec (count events)))
                                  (doseq [evt events]
                                    (>! event-chan evt)))
                                ;; 无事件产出
                                (swap! in-flight dec))))

                          ;; 暂停
                          (:pause result)
                          (do
                            (when (contains? (:pause result) :state)
                              (swap! state-atom assoc :state
                                     (get-in result [:pause :state])))
                            (swap! in-flight dec)
                            (on-pause step-id
                                      (get-in result [:pause :reason])
                                      @state-atom))

                          ;; 错误
                          (:error result)
                          (do
                            (swap! in-flight dec)
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
                            (swap! in-flight dec))))))))
                ;; 未激活 → dec in-flight（输入已收集但 step 未执行）
                (swap! in-flight dec))
            (recur))))]
    {:step-def   step-def
     :input-chan input-chan
     :state-atom state-atom
     :worker     worker}))

;;; ============================================================
;;; Router
;;; ============================================================

(defn- start-router
  "启动事件路由 go-loop

   从 event-chan 取事件，根据 bindings 路由到目标 step 的 input-chan。
   监听 control-chan 的 pause/stop 信号。

   参数:
   - event-chan:   事件总线
   - control-chan: 控制信号 channel
   - bindings:     事件绑定列表
   - steps:        {step-id step-runtime}
   - in-flight:    进行中计数 atom
   - on-idle:      空闲回调（event-chan 空且 in-flight=0）

   返回: router go-block handle"
  [event-chan control-chan bindings steps in-flight on-idle]
  (go-loop []
    (alt!
      ;; 控制信号优先
      control-chan
      ([signal]
       (when signal
         (case (:action signal)
           :stop nil  ;; 停止 router
           :pause nil ;; 暂停 router（不再消费事件）
           :resume (recur)  ;; 恢复
           (recur))))

      ;; 事件处理
      event-chan
      ([event]
       (if event
         (do
           ;; 路由事件
           (let [deliveries (event/route event bindings)]
             (if (seq deliveries)
               (do
                 ;; 增加 in-flight（每个 delivery 一次）
                 (swap! in-flight + (dec (count deliveries)))
                 ;; 投递到目标 step
                 (doseq [{:keys [step-id input-name data]} deliveries]
                   (when-let [step-rt (get steps step-id)]
                     (put! (:input-chan step-rt)
                           {:input-name input-name :data data}))))
               ;; 无匹配路由 → dec in-flight
               (swap! in-flight dec)))
           (recur))
         ;; event-chan 关闭
         nil))

      ;; 空闲检测：短暂等待后检查
      (timeout 10)
      ([_]
       (if (and (zero? @in-flight))
         (on-idle)
         (recur))))))

;;; ============================================================
;;; Process 生命周期
;;; ============================================================

(defn start-process
  "启动 process（异步）

   初始化所有 step worker，启动 router，放入初始事件。

   参数:
   - process-spec: 编译后的 process 定义
   - opts:        选项 map
     :context      Context 对象（可选）
     :timeout-ms   全局超时毫秒（默认 60000）
     :step-states  恢复的 step 状态 {step-id {:state any :activation-count int}}

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
        event-chan (chan 256)
        control-chan (chan 16)
        result-chan (chan 1)
        in-flight (atom 0)
        status (atom :running)
        error-atom (atom nil)
        paused-step-atom (atom nil)
        pause-reason-atom (atom nil)

        ;; 完成回调
        on-idle (fn []
                  (when (compare-and-set! status :running :completed)
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
                (fn [acc step-id step-def]
                  (let [input-chan (chan 64)
                        saved-state (get step-states step-id)
                        step-rt (start-step-worker
                                  step-id step-def input-chan event-chan
                                  context-atom in-flight on-pause on-error
                                  :initial-step-state saved-state)]
                    (assoc acc step-id step-rt)))
                {}
                (:steps process-spec))

        ;; 错误事件绑定（如有 error-handler）
        error-bindings (when-let [eh (:error-handler process-spec)]
                         [(event/binding :error eh :error)])
        all-bindings (into (vec (:bindings process-spec))
                           (or error-bindings []))

        ;; 启动 router
        router (start-router event-chan control-chan all-bindings
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
                 :router       router}]

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
  "关闭 runtime 的所有 channel 和 worker"
  [runtime]
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
        step-def (get-in process-spec [:steps paused-step-id])
        on-resume (:on-resume step-def)
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
        step-def (get-in process-spec [:steps paused-step-id])
        on-resume (:on-resume step-def)
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
