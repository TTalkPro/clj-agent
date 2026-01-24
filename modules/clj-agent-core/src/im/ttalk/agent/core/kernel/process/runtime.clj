(ns im.ttalk.agent.core.kernel.process.runtime
  "Process Runtime - 事件驱动循环执行引擎

   纯函数式同步执行模型。维护事件队列，逐个处理事件，
   路由到目标 step，检查激活条件，执行并产出新事件。

   Runtime State:
   {:status         :running    ;; :idle | :running | :paused | :completed | :failed
    :process-def    process-def
    :steps-state    {:step-id step-runtime-state ...}
    :event-queue    [event ...]
    :context        context
    :paused-step    nil
    :pause-reason   nil
    :error          nil}

   使用:
   (run-process process-def {:context ctx :max-iterations 100})
   ;; -> {:status :completed :context ctx ...}

   (let [result (run-process process-def opts)]
     (when (= :paused (:status result))
       (resume result resume-data)))
   ;; -> {:status :completed :context ctx ...}"
  (:require [im.ttalk.agent.core.kernel.process.event :as event]
            [im.ttalk.agent.core.kernel.process.step :as step]
            [im.ttalk.agent.core.kernel.context :as ctx]))

(def ^:private default-max-iterations
  "默认最大迭代次数（防止无限循环）"
  1000)

;;; ============================================================
;;; Runtime 初始化
;;; ============================================================

(defn init-runtime
  "初始化 runtime state

   参数:
   - process-def: 编译后的 process 定义
   - opts:        选项 map
     :context       Context 对象（可选）
     :initial-data  附加到第一个 initial-event 的数据（可选）

   返回: runtime state map"
  [process-def opts]
  (let [context (or (:context opts) (ctx/create))
        ;; 初始化所有 step
        steps-state (reduce-kv
                      (fn [acc step-id step-def]
                        (assoc acc step-id (step/init-step step-def)))
                      {}
                      (:steps process-def))
        ;; 初始事件队列
        initial-events (:initial-events process-def)
        event-queue (vec initial-events)]
    {:status       (if (seq event-queue) :running :idle)
     :process-def  process-def
     :steps-state  steps-state
     :event-queue  event-queue
     :context      context
     :paused-step  nil
     :pause-reason nil
     :error        nil}))

;;; ============================================================
;;; 事件处理
;;; ============================================================

(defn- deliver-inputs
  "将 deliveries 投递到对应 step 的 collected-inputs

   参数:
   - steps-state: 所有 step 的 runtime state map
   - deliveries:  [{:step-id :input-name :data}]

   返回: 更新后的 steps-state"
  [steps-state deliveries]
  (reduce
    (fn [acc {:keys [step-id input-name data]}]
      (if (contains? acc step-id)
        (update acc step-id step/collect-input input-name data)
        acc))
    steps-state
    deliveries))

(defn- find-activated-steps
  "查找所有满足激活条件的 step

   参数:
   - steps-state: 所有 step 的 runtime state map

   返回: 已激活的 step-id 列表"
  [steps-state]
  (reduce-kv
    (fn [acc step-id step-state]
      (if (step/check-activation step-state)
        (conj acc step-id)
        acc))
    []
    steps-state))

(defn- handle-error
  "处理 step 执行错误

   如果有 error-handler，路由错误事件到 handler 并立即执行。
   如果 error-handler 自身出错，标记 process 为 failed。
   无 error-handler 时直接标记 failed。

   参数:
   - runtime:  runtime state
   - step-id:  出错的 step id
   - reason:   错误原因

   返回: 更新后的 runtime state"
  [runtime step-id reason]
  (let [process-def (:process-def runtime)
        error-handler (:error-handler process-def)]
    (if (and error-handler (not= step-id error-handler))
      ;; 路由到 error handler 并执行
      (let [err-event (event/error-event step-id reason)
            deliveries [{:step-id    error-handler
                         :input-name :error
                         :data       (:data err-event)}]
            runtime (update runtime :steps-state deliver-inputs deliveries)
            ;; 检查 error-handler 是否激活并执行
            eh-state (get-in runtime [:steps-state error-handler])]
        (if (step/check-activation eh-state)
          (let [{:keys [result step-state]}
                (step/execute eh-state (:context runtime))
                runtime (assoc-in runtime [:steps-state error-handler] step-state)]
            (cond
              (:events result)
              (-> runtime
                  (update :event-queue into (or (:events result) []))
                  (assoc :context (or (:context result) (:context runtime))))

              (:error result)
              (assoc runtime
                     :status :failed
                     :error {:step error-handler
                             :reason (get-in result [:error :reason])})

              :else
              (assoc runtime :context (or (:context result) (:context runtime)))))
          runtime))
      ;; 无 handler 或 error-handler 自身出错，标记 failed
      (assoc runtime
             :status :failed
             :error {:step step-id :reason reason}))))

(defn- execute-activated-steps
  "顺序执行所有已激活的 step

   参数:
   - runtime:       runtime state
   - activated-ids: 已激活的 step-id 列表

   返回: 更新后的 runtime state"
  [runtime activated-ids]
  (reduce
    (fn [rt step-id]
      ;; 如果已经 paused 或 failed，不再执行后续 step
      (if (#{:paused :failed} (:status rt))
        rt
        (let [step-state (get-in rt [:steps-state step-id])
              {:keys [result step-state]} (step/execute step-state (:context rt))]
          (cond
            ;; 正常完成：入队事件，更新 context
            (:events result)
            (let [new-events (or (:events result) [])
                  new-ctx (or (:context result) (:context rt))]
              (-> rt
                  (assoc-in [:steps-state step-id] step-state)
                  (update :event-queue into new-events)
                  (assoc :context new-ctx)))

            ;; 暂停
            (:pause result)
            (-> rt
                (assoc-in [:steps-state step-id] step-state)
                (assoc :status :paused
                       :paused-step step-id
                       :pause-reason (get-in result [:pause :reason])))

            ;; 错误
            (:error result)
            (-> rt
                (assoc-in [:steps-state step-id] step-state)
                (handle-error step-id (get-in result [:error :reason])))

            ;; 无事件产出（正常结束，无后续）
            :else
            (let [new-ctx (or (:context result) (:context rt))]
              (-> rt
                  (assoc-in [:steps-state step-id] step-state)
                  (assoc :context new-ctx)))))))
    runtime
    activated-ids))

;;; ============================================================
;;; 单步前进
;;; ============================================================

(defn step-forward
  "执行一步：取出队首事件，路由，激活，执行

   参数:
   - runtime: runtime state

   返回: 更新后的 runtime state"
  [runtime]
  (let [queue (:event-queue runtime)]
    (if (empty? queue)
      ;; 队列空 → completed
      (assoc runtime :status :completed)
      ;; 取出队首事件
      (let [current-event (first queue)
            remaining-queue (vec (rest queue))
            runtime (assoc runtime :event-queue remaining-queue)
            ;; 路由事件
            bindings (get-in runtime [:process-def :bindings])
            deliveries (event/route current-event bindings)
            ;; 投递输入
            runtime (update runtime :steps-state deliver-inputs deliveries)
            ;; 查找激活的 step
            activated (find-activated-steps (:steps-state runtime))]
        (if (seq activated)
          (execute-activated-steps runtime activated)
          runtime)))))

;;; ============================================================
;;; 执行入口
;;; ============================================================

(defn run-process
  "运行 process 直到完成、暂停或失败

   参数:
   - process-def: 编译后的 process 定义
   - opts:        选项 map
     :context         Context 对象（可选）
     :max-iterations  最大迭代次数（默认 1000）

   返回:
   runtime state map
   {:status :completed/:paused/:failed
    :context updated-ctx
    :steps-state ...
    :error ...}"
  ([process-def]
   (run-process process-def {}))
  ([process-def opts]
   (let [max-iter (or (:max-iterations opts) default-max-iterations)
         runtime (init-runtime process-def opts)]
     (loop [rt runtime
            iterations 0]
       (cond
         ;; 非 running 状态，返回
         (not= :running (:status rt))
         rt

         ;; 超过最大迭代
         (>= iterations max-iter)
         (assoc rt :status :failed
                   :error {:reason "超过最大迭代次数"
                           :max-iterations max-iter
                           :iterations iterations})

         ;; 队列空 → completed
         (empty? (:event-queue rt))
         (assoc rt :status :completed)

         ;; 继续执行
         :else
         (recur (step-forward rt) (inc iterations)))))))

;;; ============================================================
;;; 恢复暂停的 Process
;;; ============================================================

(defn resume
  "恢复暂停的 process

   参数:
   - runtime: 处于 :paused 状态的 runtime state
   - data:    恢复数据（传给 step 的 on-resume）
   - opts:    选项 map（:max-iterations）

   返回:
   更新后的 runtime state（继续执行直到下一个终态）"
  ([runtime data]
   (resume runtime data {}))
  ([runtime data opts]
   (when (not= :paused (:status runtime))
     (throw (ex-info "只能恢复处于 :paused 状态的 process"
                     {:current-status (:status runtime)})))
   (let [paused-step (:paused-step runtime)
         step-state (get-in runtime [:steps-state paused-step])
         max-iter (or (:max-iterations opts) default-max-iterations)]
     ;; 调用 step 的 on-resume
     (if-let [{:keys [result step-state]}
              (step/resume-step step-state data (:context runtime))]
       (let [;; 处理 resume 结果
             runtime (assoc-in runtime [:steps-state paused-step] step-state)
             runtime (cond
                       (:events result)
                       (-> runtime
                           (update :event-queue into (or (:events result) []))
                           (assoc :status :running
                                  :paused-step nil
                                  :pause-reason nil
                                  :context (or (:context result) (:context runtime))))

                       (:pause result)
                       (assoc runtime
                              :pause-reason (get-in result [:pause :reason]))

                       (:error result)
                       (handle-error runtime paused-step
                                     (get-in result [:error :reason]))

                       :else
                       (assoc runtime
                              :status :running
                              :paused-step nil
                              :pause-reason nil))]
         ;; 继续执行循环
         (if (= :running (:status runtime))
           (loop [rt runtime
                  iterations 0]
             (cond
               (not= :running (:status rt)) rt
               (>= iterations max-iter)
               (assoc rt :status :failed
                         :error {:reason "恢复后超过最大迭代次数"})
               (empty? (:event-queue rt))
               (assoc rt :status :completed)
               :else
               (recur (step-forward rt) (inc iterations))))
           runtime))
       ;; step 不支持 resume
       (throw (ex-info "Step 不支持 resume（缺少 :on-resume）"
                       {:step paused-step}))))))
