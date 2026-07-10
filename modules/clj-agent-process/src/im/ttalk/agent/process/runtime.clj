(ns im.ttalk.agent.process.runtime
  "Process V1 执行引擎：纯函数式同步事件循环（无 core.async，确定性执行）。

   循环模型：
     1. 若有已激活的 step（required-inputs 收齐 + can-activate?）→ 按注册顺序执行一批
     2. 否则出队一个事件 → 经 bindings 路由 → 投递到目标 step 的输入槽
     3. 队列空且无激活 → :completed
     4. step 返回 {:pause ...} → :paused（可 resume 续跑）
     5. step 返回 {:error ...} / 抛异常 → error-handler（若配置）或 :failed

   step 的 on-activate 返回值：
     {:events [...] :state s :context ctx}   ;; 正常：产事件 / 更新私有 state / 更新共享 context
     {:pause {:reason \"...\" :state s}}      ;; 暂停（human-in-the-loop）
     {:error {:reason \"...\"}}               ;; 报错
     {:terminate true :context ctx}          ;; 显式完成（循环场景的退出信号）

   on-quiescent 静止点回调（保存快照的安全时机）：
     - :quiescent — 一批 step 执行完且队列仍有事件（process 未结束）
     - :paused    — step 暂停时（快照含 :paused-step / :pause-reason）

   用法：
     (run-process spec {:context (ctx/create {:kernel k})
                        :on-quiescent (fn [snapshot] (save! snapshot))})
     ;; => {:status :completed/:failed/:paused :context ... :steps-state ...}
     (resume paused-result \"approved\")   ;; 从 :paused 返回值续跑"
  (:require [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.process.event :as event]
            [im.ttalk.agent.process.step :as step]))

(set! *warn-on-reflection* true)

(def ^:private default-max-events
  "事件处理总数上限（循环失控保险丝；可经 opts :max-events 覆盖）"
  10000)

;;; ============================================================
;;; 快照 / 结果
;;; ============================================================

(defn- public-steps-state
  "对外暴露的 step 状态（去掉 collected-inputs 等瞬态）。"
  [steps-state]
  (update-vals steps-state #(select-keys % [:state :activation-count])))

(defn- snapshot
  "构造 on-quiescent 快照。"
  [rt reason]
  (cond-> {:process-name (get-in rt [:spec :name])
           :reason       reason
           :status       (if (= :paused reason) :paused :running)
           :step-states  (public-steps-state (:steps-state rt))
           :context      (:context rt)
           :created-at   (System/currentTimeMillis)}
    (= :paused reason) (assoc :paused-step  (:paused-step rt)
                              :pause-reason (:pause-reason rt))))

(defn- notify-quiescent! [rt reason]
  (when-let [f (:on-quiescent rt)]
    (try (f (snapshot rt reason)) (catch Throwable _ nil)))
  rt)

(defn- terminate-all!
  "process 结束时逐 step 调 on-terminate（异常吞掉，不影响其他 step 清理）。"
  [rt]
  (doseq [id (get-in rt [:spec :step-order])
          :let [f (get-in rt [:spec :steps id :on-terminate])]
          :when f]
    (try (f (get-in rt [:steps-state id :state]) (:context rt))
         (catch Throwable _ nil)))
  rt)

(defn- finish
  [rt status & [error]]
  (terminate-all! rt)
  (cond-> {:status      status
           :context     (:context rt)
           :steps-state (public-steps-state (:steps-state rt))}
    error (assoc :error error)))

;;; ============================================================
;;; 事件路由 / 激活
;;; ============================================================

(defn- effective-bindings
  "用户 bindings + error-handler 的隐式绑定（:error 事件 → handler 的 :input）。"
  [{:keys [bindings error-handler]}]
  (cond-> bindings
    error-handler (conj {:event-name :error :target-step error-handler
                         :target-input :input :transform nil})))

(defn- deliver-event
  "出队首事件并投递到所有匹配的输入槽。"
  [rt]
  (let [[ev & rest-q] (:event-queue rt)
        deliveries (event/route ev (:effective-bindings rt))]
    (reduce (fn [r {:keys [step input data]}]
              (update-in r [:steps-state step] step/deliver-input input data))
            (assoc rt :event-queue (vec rest-q))
            deliveries)))

(defn- activated-steps
  "当前可激活的 step id（按注册顺序，保证确定性）。"
  [rt]
  (filterv #(step/activatable? (get-in rt [:spec :steps %])
                               (get-in rt [:steps-state %]))
           (get-in rt [:spec :step-order])))

;;; ============================================================
;;; step 结果应用
;;; ============================================================

(defn- enqueue-events
  "step 产出的事件补 :source 后入队。"
  [rt source events]
  (update rt :event-queue into
          (map (fn [{:keys [name data type]}]
                 (event/make-event name data {:source source :type type}))
               events)))

(defn- apply-step-result
  "把 on-activate / on-resume 的返回值应用到 runtime。
   返回 [rt outcome]，outcome ∈ :continue | :paused | :terminated | [:failed error]。"
  [rt step-id result]
  (cond
    ;; 显式错误
    (:error result)
    (let [err (assoc (:error result) :step step-id)]
      (if (get-in rt [:spec :error-handler])
        ;; 路由给 error-handler（清空出错 step 的 inputs，避免同因重触发）
        [(-> rt
             (update-in [:steps-state step-id] step/after-activation result)
             (update :event-queue conj (event/error-event step-id (:error result))))
         :continue]
        [(assoc rt :error err) [:failed err]]))

    ;; 暂停：state 从 pause map 里取（可选）
    (:pause result)
    (let [{:keys [reason state]} (:pause result)]
      [(-> rt
           ;; 暂停不清 inputs、不加计数——resume 后由 on-resume 决定走向
           (cond-> (contains? (:pause result) :state)
             (assoc-in [:steps-state step-id :state] state))
           (assoc :paused-step step-id :pause-reason reason))
       :paused])

    ;; 显式完成（外部循环场景的退出信号）
    (:terminate result)
    [(-> rt
         (cond-> (:context result) (assoc :context (:context result)))
         (update-in [:steps-state step-id] step/after-activation result))
     :terminated]

    ;; 正常：事件 / state / context
    :else
    [(-> rt
         (cond-> (:context result) (assoc :context (:context result)))
         (update-in [:steps-state step-id] step/after-activation result)
         (enqueue-events step-id (or (:events result) [])))
     :continue]))

(defn- execute-step
  "执行单个已激活 step 的 on-activate（异常折为 :error 结果）。"
  [rt step-id]
  (let [spec   (get-in rt [:spec :steps step-id])
        sstate (get-in rt [:steps-state step-id])
        result (try ((:on-activate spec)
                     (:collected-inputs sstate) (:state sstate) (:context rt))
                    (catch Throwable t
                      {:error {:reason (or (not-empty (.getMessage t))
                                           (.getName (class t)))
                               :exception t}}))]
    (apply-step-result rt step-id result)))

;;; ============================================================
;;; 主循环
;;; ============================================================

(declare finish-paused)

(defn- drive
  "推进 runtime 直到 completed / paused / failed。"
  [rt]
  (loop [rt rt, executed-since-delivery? false]
    (let [batch (activated-steps rt)]
      (cond
        ;; 有激活：按注册顺序逐个执行；pause/terminate/failed 即时短路
        (seq batch)
        (let [[rt outcome]
              (reduce (fn [[r _] id]
                        (let [[r' o] (execute-step r id)]
                          (if (= :continue o) [r' o] (reduced [r' o]))))
                      [rt :continue]
                      batch)]
          (case outcome
            :continue   (recur rt true)
            :paused     (do (notify-quiescent! rt :paused)
                            (finish-paused rt))
            :terminated (finish rt :completed)
            ;; [:failed err]
            (finish rt :failed (second outcome))))

        ;; 无激活、队列空 → 完成
        (empty? (:event-queue rt))
        (finish rt :completed)

        ;; 无激活、队列有事件 → 静止点（刚执行过一批才触发）→ 投递下一事件
        :else
        (let [rt (if executed-since-delivery?
                   (notify-quiescent! rt :quiescent)
                   rt)
              n  (inc (:events-processed rt))]
          (if (> n (:max-events rt))
            (finish rt :failed {:reason :max-events-exceeded
                                :max-events (:max-events rt)})
            (recur (-> rt deliver-event (assoc :events-processed n))
                   false)))))))

(defn- finish-paused
  "暂停返回值：带上续跑所需的完整 runtime 数据。"
  [rt]
  {:status       :paused
   :paused-step  (:paused-step rt)
   :pause-reason (:pause-reason rt)
   :context      (:context rt)
   :steps-state  (public-steps-state (:steps-state rt))
   ;; resume 所需（私有，勿手改）
   ::runtime     (select-keys rt [:spec :effective-bindings :steps-state
                                  :event-queue :context :on-quiescent
                                  :max-events :events-processed
                                  :paused-step :pause-reason])})

;;; ============================================================
;;; 公开 API
;;; ============================================================

(defn run-process
  "同步执行 process，返回 {:status :context :steps-state (:error) (:paused-* )}。

   opts：
   - :context      初始 ToolContext（缺省 (ctx/create)）
   - :on-quiescent (fn [snapshot])   静止点/暂停回调（保存快照的安全时机）
   - :step-states  {step-id {:state s :activation-count n}}  从快照恢复
   - :initial-events 覆盖 spec 的初始事件（快照恢复时驱动后续步骤用）
   - :max-events   事件处理总数上限（缺省 10000，防循环失控）"
  ([spec] (run-process spec {}))
  ([spec {:keys [context on-quiescent step-states initial-events max-events]}]
   (let [steps-state (into {}
                           (map (fn [id]
                                  [id (step/init-state (get-in spec [:steps id])
                                                       (get step-states id))]))
                           (:step-order spec))
         rt {:spec               spec
             :effective-bindings (effective-bindings spec)
             :steps-state        steps-state
             :event-queue        (vec (map #(event/make-event (:name %) (:data %))
                                           (or initial-events (:initial-events spec))))
             :context            (or context (ctx/create))
             :on-quiescent       on-quiescent
             :max-events         (or max-events default-max-events)
             :events-processed   0}]
     (drive rt))))

(defn- resume*
  "对 rt 的暂停 step 执行 on-resume 并继续驱动（resume 与 resume-from-snapshot 共用）。"
  [rt step-id data]
  (let [f (get-in rt [:spec :steps step-id :on-resume])
        _ (when-not f
            (throw (ex-info (str "step " step-id " 未定义 :on-resume") {:step step-id})))
        sstate (get-in rt [:steps-state step-id])
        result (try (f data (:state sstate) (:context rt))
                    (catch Throwable t
                      {:error {:reason (or (not-empty (.getMessage t))
                                           (.getName (class t)))
                               :exception t}}))
        rt (dissoc rt :paused-step :pause-reason)
        [rt outcome] (apply-step-result rt step-id result)]
    (case outcome
      :continue   (drive rt)
      :paused     (do (notify-quiescent! rt :paused) (finish-paused rt))
      :terminated (finish rt :completed)
      (finish rt :failed (second outcome)))))

(defn resume
  "从 :paused 返回值续跑。data 交给暂停 step 的 :on-resume
   (fn [data state context])，其返回值语义与 on-activate 相同
   （events 继续 / 再次 pause / error / terminate）。"
  [paused data]
  (let [rt (::runtime paused)]
    (when-not rt
      (throw (ex-info "resume 需要 run-process 返回的 :paused 结果" {})))
    (resume* rt (:paused-step rt) data)))

(defn resume-from-snapshot
  "跨进程重启的恢复：用 on-quiescent 的 :paused 快照重建 runtime 并 resume。

   与 resume 的区别：resume 用运行期的返回值（含未消费的事件队列）；
   本函数只有可序列化快照——**事件队列不在快照中**（设计文档已知限制），
   暂停点之后完全由 on-resume 产出的事件驱动。

   参数:
   - spec:     原 process-spec（快照不含函数，须由代码侧提供同一 spec）
   - snapshot: on-quiescent 收到的 :paused 快照（{:step-states :context :paused-step ...}）
   - data:     交给 on-resume 的恢复数据
   - opts:     {:on-quiescent :max-events}（同 run-process）"
  [spec {:keys [step-states context paused-step]} data
   & [{:keys [on-quiescent max-events]}]]
  (when-not paused-step
    (throw (ex-info "resume-from-snapshot 需要 reason=:paused 的快照" {})))
  (let [steps-state (into {}
                          (map (fn [id]
                                 [id (step/init-state (get-in spec [:steps id])
                                                      (get step-states id))]))
                          (:step-order spec))
        rt {:spec               spec
            :effective-bindings (effective-bindings spec)
            :steps-state        steps-state
            :event-queue        []
            :context            (or context (ctx/create))
            :on-quiescent       on-quiescent
            :max-events         (or max-events default-max-events)
            :events-processed   0}]
    (resume* rt paused-step data)))

(defn paused?
  "结果是否处于暂停态。"
  [result]
  (= :paused (:status result)))
