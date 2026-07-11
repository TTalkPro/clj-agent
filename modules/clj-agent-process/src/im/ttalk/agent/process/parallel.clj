(ns im.ttalk.agent.process.parallel
  "Process V2 执行引擎：core.async 并行事件循环（fan-out 真并行 + 外部事件 + 超时）。

   与 V1（runtime，纯函数同步）的关系：
   - event/step/builder 三个纯函数层完全复用——同一 process-spec 两个引擎都能跑
   - V1：确定性执行、可快照（on-quiescent / Timeline）；V2：fan-out 真并行、
     外部事件注入（ProcessHandle）、单步与全局超时
   - V2 不提供 on-quiescent（并行执行没有确定性静止点）；需要快照/时间旅行用 V1

   架构（docs/process-parallel-design.md）：
     event-chan / external-chan → router go-loop 路由 → 各 step 的 input-chan
     → step worker go-loop（collect → activate → on-activate 跑在 async/thread）
     → 产出事件回流 event-chan。
   完成判定用 in-flight 计数：事件入队 inc、每个投递 inc、路由完 dec、
   worker 处理完 dec；计数归零（且非暂停）即 :completed。

   并发语义（须知）：
   - 同一 step 的激活串行（单 worker），不同 step 之间并行
   - context 是共享 atom：step 拿快照执行，写回时只合并**相对快照有变化**的
     key（last-writer-wins）。并行 step 写同一 key 时最终值取决于完成顺序
   - 暂停是尽力而为的屏障：router 停止消费新事件，但已在执行中的 step 会跑完
   - 单步超时后 on-activate 的线程仍在后台跑完（JVM 无法安全强杀），
     但其返回值会被丢弃、写不进任何共享状态

   用法：
     ;; 异步：ProcessHandle
     (def h (start-process spec {:context (ctx/create ...) :auto-complete? false}))
     (send-event h :user-reply \"yes\")
     (wait-for-completion h 5000)
     (stop-process h)
     ;; 同步（与 V1 同构）：
     (run-process spec)            ;; => 终态或 :paused 结果
     (resume paused \"approved\")    ;; => 续跑到下一个终态/暂停"
  (:require [clojure.core.async :as async :refer [<! <!! >! alts! alts!! chan
                                                  close! go go-loop promise-chan
                                                  put!]]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.process.event :as event]
            [im.ttalk.agent.process.step :as step]))

(set! *warn-on-reflection* true)

(def ^:private default-max-events
  "路由事件总数上限（循环失控保险丝；可经 opts :max-events 覆盖）"
  10000)

(def ^:private default-buffer
  "event-chan / external-chan / 各 input-chan 的缓冲大小（opts :buffer 覆盖）"
  256)

;;; ============================================================
;;; 结果 / 清理
;;; ============================================================

(defn- public-steps-state
  [steps]
  (update-vals steps (fn [{:keys [state]}]
                       (select-keys @state [:state :activation-count]))))

(defn- result-map
  [rt status & [error]]
  (cond-> {:status      status
           :context     @(:context rt)
           :steps-state (public-steps-state (:steps rt))}
    error (assoc :error error)))

(defn- terminate-all!
  "结束时逐 step 调 on-terminate（异常吞掉，不影响其他 step 清理）。"
  [rt]
  (doseq [id (get-in rt [:spec :step-order])
          :let [f (get-in rt [:spec :steps id :on-terminate])]
          :when f]
    (try (f (:state @(get-in rt [:steps id :state])) @(:context rt))
         (catch Throwable _ nil))))

(defn- finish!
  "终态收尾（幂等）：置状态 → on-terminate 清理 → 交付结果 → 关闭通道。
   on-terminate 可能阻塞，整体放独立线程执行（清理完才交付结果，与 V1 顺序一致）。"
  [rt status & [error]]
  (when (compare-and-set! (:finished? rt) false true)
    (reset! (:status rt) status)
    (async/thread
      (terminate-all! rt)
      (put! (:completion-chan rt) (result-map rt status error))
      ;; control-chan 关闭令 router 退出；input-chan 关闭令各 worker 退出。
      ;; event-chan / external-chan 不关：终态后的 put! 只是无人消费，随句柄被 GC。
      (close! (:control-chan rt))
      (doseq [{:keys [input-chan]} (vals (:steps rt))]
        (close! input-chan))))
  rt)

;;; ============================================================
;;; in-flight 计数与完成判定
;;; ============================================================

(defn- maybe-complete!
  [rt]
  (when (and (:auto-complete? rt)
             (zero? @(:in-flight rt))
             (= :running @(:status rt))
             (empty? @(:paused rt)))
    (finish! rt :completed)))

(defn- dec-in-flight!
  [rt]
  (when (zero? (swap! (:in-flight rt) dec))
    (maybe-complete! rt)))

(defn- enqueue-events!
  "step 产出的事件补 :source 后入队（每个事件先 inc 再入队，防误判归零）。"
  [rt source events]
  (doseq [{:keys [name data type]} events]
    (swap! (:in-flight rt) inc)
    (put! (:event-chan rt) (event/make-event name data {:source source :type type}))))

;;; ============================================================
;;; step 结果应用
;;; ============================================================

(defn- merge-context!
  "把 step 基于快照产出的 context 写回共享 atom：只合并相对快照**有变化**的
   key（并行下直接整体替换会覆盖其他 step 的并发写入）。不处理 key 删除。"
  [rt snapshot new-ctx]
  (when (and new-ctx (not (identical? snapshot new-ctx)))
    (let [changed (into {} (remove (fn [[k v]] (= v (get snapshot k)))) new-ctx)]
      (when (seq changed)
        (swap! (:context rt) merge changed)))))

(defn- apply-result!
  "把 on-activate / on-resume 的返回值应用到运行时（语义对齐 V1
   apply-step-result）。返回 outcome ∈ :continue | :paused | :terminated | :failed。"
  [rt step-id ctx-snapshot result]
  (let [state-atom (get-in rt [:steps step-id :state])]
    (cond
      ;; 错误：有 handler → :error 事件路由；无 → :failed
      (:error result)
      (let [err (assoc (:error result) :step step-id)]
        (if (get-in rt [:spec :error-handler])
          (do (swap! state-atom step/after-activation result)
              (swap! (:in-flight rt) inc)
              (put! (:event-chan rt) (event/error-event step-id (:error result)))
              :continue)
          (do (finish! rt :failed err)
              :failed)))

      ;; 暂停：不清 inputs、不加计数；router 见状态后停止消费
      (:pause result)
      (let [{:keys [reason state]} (:pause result)]
        (when (contains? (:pause result) :state)
          (swap! state-atom assoc :state state))
        (swap! (:paused rt) assoc step-id (or reason "paused"))
        (reset! (:status rt) :paused)
        (put! (:pause-chan rt) {:paused-step step-id :pause-reason reason})
        :paused)

      ;; 显式完成
      (:terminate result)
      (do (merge-context! rt ctx-snapshot (:context result))
          (swap! state-atom step/after-activation result)
          (finish! rt :completed)
          :terminated)

      ;; 正常：context / state / 事件回流
      :else
      (do (merge-context! rt ctx-snapshot (:context result))
          (swap! state-atom step/after-activation result)
          (enqueue-events! rt step-id (or (:events result) []))
          :continue))))

;;; ============================================================
;;; worker / router go-loop
;;; ============================================================

(defn- exec-ch
  "在独立线程执行用户函数，返回结果 channel（异常折为 :error result）。"
  [f & args]
  (async/thread
    (try (apply f args)
         (catch Throwable t
           {:error {:reason (or (not-empty (.getMessage t))
                                (.getName (class t)))
                    :exception t}}))))

(defn- worker-loop!
  "step 专属循环：收投递 → collect → 激活则在线程执行（含单步超时）→ 应用结果。
   同一 step 的激活天然串行；step 私有 state atom 仅本 worker 写。"
  [rt step-id]
  (let [{:keys [input-chan state]} (get-in rt [:steps step-id])
        step-spec  (get-in rt [:spec :steps step-id])
        timeout-ms (:timeout-ms step-spec)]
    (go-loop []
      (when-let [{:keys [input data]} (<! input-chan)]
        (swap! state step/deliver-input input data)
        (when (and (not (contains? @(:paused rt) step-id))
                   (step/activatable? step-spec @state))
          (let [snap     @state
                ctx-snap @(:context rt)
                ch       (exec-ch (:on-activate step-spec)
                                  (:collected-inputs snap) (:state snap) ctx-snap)
                result   (if timeout-ms
                           (let [[v c] (alts! [ch (async/timeout timeout-ms)])]
                             (if (= c ch)
                               v
                               {:error {:reason :step-timeout
                                        :timeout-ms timeout-ms}}))
                           (<! ch))]
            (apply-result! rt step-id ctx-snap result)))
        (dec-in-flight! rt)
        (recur)))))

(defn- router-loop!
  "事件总线：内部/外部事件 → bindings 路由 → 投递到目标 step 的 input-chan。
   暂停时只听 control-chan（:resume 恢复消费；关闭即退出）。"
  [rt]
  (let [{:keys [event-chan external-chan control-chan]} rt]
    (go-loop []
      (if (= :paused @(:status rt))
        (when (<! control-chan)                       ;; :resume → 继续；nil（关闭）→ 退出
          (recur))
        (let [[v c] (alts! [control-chan event-chan external-chan] :priority true)]
          (cond
            (and (= c control-chan) (nil? v)) nil     ;; 终态：control-chan 已关闭
            (= c control-chan) (recur)                ;; 控制信号在暂停分支消费，此处忽略
            :else
            (let [n (swap! (:events-routed rt) inc)]
              (if (> n (:max-events rt))
                (finish! rt :failed {:reason :max-events-exceeded
                                     :max-events (:max-events rt)})
                (do (doseq [{:keys [step input data]}
                            (event/route v (:effective-bindings rt))]
                      (swap! (:in-flight rt) inc)
                      (>! (get-in rt [:steps step :input-chan])
                          {:input input :data data}))
                    (dec-in-flight! rt)
                    (recur))))))))))

;;; ============================================================
;;; 启动 / ProcessHandle API
;;; ============================================================

(defn- effective-bindings
  "用户 bindings + error-handler 的隐式绑定（同 V1）。"
  [{:keys [bindings error-handler]}]
  (cond-> bindings
    error-handler (conj {:event-name :error :target-step error-handler
                         :target-input :input :transform nil})))

(defn start-process
  "异步启动 process，返回 ProcessHandle（供 send-event / get-status /
   wait-for-completion / stop-process / resume 使用）。

   opts：
   - :context        初始 ToolContext（缺省 (ctx/create)）
   - :step-states    {step-id {:state s :activation-count n}} 从快照恢复
   - :initial-events 覆盖 spec 的初始事件
   - :max-events     路由事件总数上限（缺省 10000）
   - :timeout-ms     全局超时：到点未终态 → :failed {:reason :timeout}
   - :buffer         各通道缓冲大小（缺省 256）
   - :auto-complete? 缺省 true（事件流干即 :completed）。等外部事件驱动的
                     常驻 process 置 false——只能由 :terminate / stop / 超时结束"
  ([spec] (start-process spec {}))
  ([spec {:keys [context step-states initial-events max-events timeout-ms
                 buffer auto-complete?]
          :or   {auto-complete? true}}]
   (let [buf (or buffer default-buffer)
         rt  {:spec               spec
              :effective-bindings (effective-bindings spec)
              :steps              (into {}
                                        (map (fn [id]
                                               [id {:input-chan (chan buf)
                                                    :state (atom (step/init-state
                                                                  (get-in spec [:steps id])
                                                                  (get step-states id)))}]))
                                        (:step-order spec))
              :status             (atom :running)
              :finished?          (atom false)
              :paused             (atom {})
              :context            (atom (or context (ctx/create)))
              :in-flight          (atom 0)
              :events-routed      (atom 0)
              :max-events         (or max-events default-max-events)
              :auto-complete?     auto-complete?
              :event-chan         (chan buf)
              :external-chan      (chan buf)
              :control-chan       (chan 8)
              :pause-chan         (chan 8)
              :completion-chan    (promise-chan)}
         evs (or initial-events (:initial-events spec))]
     (doseq [id (:step-order spec)]
       (worker-loop! rt id))
     (router-loop! rt)
     (doseq [{:keys [name data]} evs]
       (swap! (:in-flight rt) inc)
       (put! (:event-chan rt) (event/make-event name data)))
     (when (empty? evs)
       (maybe-complete! rt))
     (when timeout-ms
       (go (let [[_ c] (alts! [(:completion-chan rt) (async/timeout timeout-ms)])]
             (when (not= c (:completion-chan rt))
               (finish! rt :failed {:reason :timeout :timeout-ms timeout-ms})))))
     rt)))

(defn get-status
  "当前状态：:running | :paused | :completed | :failed | :stopped。"
  [handle]
  @(:status handle))

(defn pause-info
  "暂停详情 {step-id reason}（未暂停返回空 map）。"
  [handle]
  @(:paused handle))

(defn send-event
  "向运行中的 process 注入外部事件（暂停中也可注入，resume 后消费）。
   返回 true=已接收；false=process 已终态、事件被拒收。"
  ([handle event-name] (send-event handle event-name nil))
  ([handle event-name data]
   (if @(:finished? handle)
     false
     (do (swap! (:in-flight handle) inc)
         (put! (:external-chan handle) (event/make-event event-name data {:type :external}))
         true))))

(defn wait-for-completion
  "阻塞等待终态结果（:completed / :failed / :stopped）。带 timeout-ms 时超时
   返回 nil（process 继续运行）。注意：:paused 不是终态——human-in-the-loop
   用 run-process/resume 同步组合，或轮询 get-status。"
  ([handle] (<!! (:completion-chan handle)))
  ([handle timeout-ms]
   (first (alts!! [(:completion-chan handle) (async/timeout timeout-ms)]))))

(defn stop-process
  "主动停止：置 :stopped、逐 step on-terminate、交付并返回终态结果。幂等
   （已终态时返回既有结果）。"
  [handle]
  (finish! handle :stopped)
  (wait-for-completion handle))

;;; ============================================================
;;; 同步 API（与 V1 run-process/resume 同构）
;;; ============================================================

(defn- ->handle
  [x]
  (or (::handle x)
      (when (:completion-chan x) x)
      (throw (ex-info "需要 ProcessHandle 或 run-process 返回的 :paused 结果" {}))))

(defn- await-outcome
  "阻塞到终态或下一次暂停。暂停返回 {:status :paused ... ::handle h}。"
  [rt]
  (let [[v c] (alts!! [(:completion-chan rt) (:pause-chan rt)] :priority true)]
    (if (= c (:completion-chan rt))
      v
      (assoc (result-map rt :paused)
             :paused-step  (:paused-step v)
             :pause-reason (:pause-reason v)
             ::handle      rt))))

(defn run-process
  "同步执行：阻塞直到终态或暂停。opts 同 start-process。
   终态 → {:status :completed/:failed/:stopped :context :steps-state (:error)}
   暂停 → {:status :paused :paused-step :pause-reason ...}（交给 resume 续跑）"
  ([spec] (run-process spec {}))
  ([spec opts]
   (await-outcome (start-process spec opts))))

(defn- resume-step!
  "对暂停 step 执行 on-resume 并应用结果；全部暂停解除时唤醒 router。"
  [rt step-id data]
  (when-not (contains? @(:paused rt) step-id)
    (throw (ex-info (str "step " step-id " 未处于暂停态")
                    {:paused (keys @(:paused rt)) :status (get-status rt)})))
  (let [f (get-in rt [:spec :steps step-id :on-resume])
        _ (when-not f
            (throw (ex-info (str "step " step-id " 未定义 :on-resume") {:step step-id})))
        state-atom (get-in rt [:steps step-id :state])
        ctx-snap   @(:context rt)
        result     (try (f data (:state @state-atom) ctx-snap)
                        (catch Throwable t
                          {:error {:reason (or (not-empty (.getMessage t))
                                               (.getName (class t)))
                                   :exception t}}))
        ;; 先摘除暂停标记；若结果是再次 pause，apply-result! 会重新挂上
        _       (swap! (:paused rt) dissoc step-id)
        outcome (apply-result! rt step-id ctx-snap result)]
    (when (and (not= :paused outcome) (empty? @(:paused rt)))
      (reset! (:status rt) :running)
      (put! (:control-chan rt) :resume)
      (maybe-complete! rt))
    outcome))

(defn resume
  "从暂停续跑（同步，阻塞到下一个终态/暂停）。接受 run-process 的 :paused
   返回值或 ProcessHandle。data 交给暂停 step 的 :on-resume
   (fn [data state context])，返回值语义与 on-activate 相同。
   多个 step 同时暂停时须用三参形式指定 step-id。

   与 V1 的差别：V2 是活运行时，同一个 :paused 结果只能 resume 一次
   （V1 的纯数据快照可对同一暂停点多次 resume）。"
  ([paused-or-handle data]
   (let [h      (->handle paused-or-handle)
         paused @(:paused h)]
     (when (empty? paused)
       (throw (ex-info "process 未处于暂停态" {:status (get-status h)})))
     (when (> (count paused) 1)
       (throw (ex-info "多个 step 同时暂停，须指定 step-id"
                       {:paused (keys paused)})))
     (resume paused-or-handle (first (keys paused)) data)))
  ([paused-or-handle step-id data]
   (let [h (->handle paused-or-handle)]
     (resume-step! h step-id data)
     (await-outcome h))))

(defn paused?
  "run-process / resume 的返回值是否处于暂停态。"
  [result]
  (= :paused (:status result)))

(defn handle-of
  "取 :paused 结果携带的 ProcessHandle（handle 自身原样返回）——
   同步流程中途需要 send-event / get-status / stop-process 时用。"
  [paused-or-handle]
  (->handle paused-or-handle))
