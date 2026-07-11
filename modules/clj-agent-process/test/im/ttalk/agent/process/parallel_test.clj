(ns im.ttalk.agent.process.parallel-test
  "Parallel V2：设计文档全部特性的行为测试——
   线性 / fan-out 真并行 / fan-in / 并行 context 合并 / pause-resume /
   error 与 error-handler / 外部事件 / 单步与全局超时 / stop / max-events。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.process.builder :as pb]
            [im.ttalk.agent.process.parallel :as pp]))

;;; ============================================================
;;; 线性流 A → B → C（V1 同款 spec，V2 引擎跑通）
;;; ============================================================

(deftest linear-flow-test
  (let [spec (-> (pb/builder :linear)
                 (pb/add-step {:id :a
                               :on-activate (fn [inputs _ ctx]
                                              {:events [{:name :a-done
                                                         :data (str (:input inputs) "-a")}]
                                               :context (ctx/set-var ctx :a-ran true)})})
                 (pb/add-step {:id :b
                               :on-activate (fn [inputs _ _]
                                              {:events [{:name :b-done
                                                         :data (str (:input inputs) "-b")}]})})
                 (pb/add-step {:id :c
                               :on-activate (fn [inputs _ ctx]
                                              {:context (ctx/set-var ctx :final (:input inputs))
                                               :events []})})
                 (pb/on-event :start :a)
                 (pb/on-event :a-done :b)
                 (pb/on-event :b-done :c)
                 (pb/set-initial-event :start "x")
                 (pb/build))
        result (pp/run-process spec {:timeout-ms 5000})]
    (is (= :completed (:status result)))
    (is (= "x-a-b" (ctx/get-var (:context result) :final)))
    (is (true? (ctx/get-var (:context result) :a-ran)))
    (is (= 1 (get-in result [:steps-state :a :activation-count])))
    (is (= 1 (get-in result [:steps-state :c :activation-count])))))

;;; ============================================================
;;; Fan-out 真并行：两个 step 互相等对方启动——串行引擎会死等超时
;;; ============================================================

(deftest fan-out-true-parallel-test
  (let [a-started (promise)
        b-started (promise)
        mk (fn [id own other]
             {:id id
              :on-activate (fn [_ _ _]
                             (deliver own true)
                             (if (true? (deref other 3000 false))
                               {:events []}
                               {:error {:reason :not-parallel}}))})
        spec (-> (pb/builder :fan-out-par)
                 (pb/add-step (mk :a a-started b-started))
                 (pb/add-step (mk :b b-started a-started))
                 (pb/on-event :start :a)
                 (pb/on-event :start :b)
                 (pb/set-initial-event :start nil)
                 (pb/build))
        result (pp/run-process spec {:timeout-ms 10000})]
    (is (= :completed (:status result))
        "a/b 必须同时在跑才能互相等到对方（V1 串行下 a 会等穿 3s 报 :not-parallel）")
    (is (= 1 (get-in result [:steps-state :a :activation-count])))
    (is (= 1 (get-in result [:steps-state :b :activation-count])))))

;;; ============================================================
;;; Fan-in：join 两个 required-inputs 收齐才激活（生产者并行）
;;; ============================================================

(deftest fan-in-test
  (let [spec (-> (pb/builder :fan-in)
                 (pb/add-step {:id :a
                               :on-activate (fn [_ _ _] {:events [{:name :a-done :data "A"}]})})
                 (pb/add-step {:id :b
                               :on-activate (fn [_ _ _] {:events [{:name :b-done :data "B"}]})})
                 (pb/add-step {:id :join
                               :required-inputs [:from-a :from-b]
                               :on-activate (fn [inputs _ ctx]
                                              {:context (ctx/set-var ctx :joined
                                                                     (str (:from-a inputs)
                                                                          (:from-b inputs)))
                                               :events []})})
                 (pb/on-event :start :a)
                 (pb/on-event :start :b)
                 (pb/on-event :a-done :join :from-a)
                 (pb/on-event :b-done :join :from-b)
                 (pb/set-initial-event :start nil)
                 (pb/build))
        result (pp/run-process spec {:timeout-ms 5000})]
    (is (= :completed (:status result)))
    (is (= "AB" (ctx/get-var (:context result) :joined)))
    (is (= 1 (get-in result [:steps-state :join :activation-count]))
        "两输入收齐只激活一次")))

;;; ============================================================
;;; 并行 context 合并：两个并行 step 写不同 key，都不丢
;;; ============================================================

(deftest parallel-context-merge-test
  (let [gate-a (promise)
        gate-b (promise)
        mk (fn [id own other k v]
             {:id id
              :on-activate (fn [_ _ c]
                             (deliver own true)
                             (deref other 3000 false)   ;; 确保两者拿的是同一份快照
                             {:context (ctx/set-var c k v) :events []})})
        spec (-> (pb/builder :ctx-merge)
                 (pb/add-step (mk :a gate-a gate-b :from-a 1))
                 (pb/add-step (mk :b gate-b gate-a :from-b 2))
                 (pb/on-event :start :a)
                 (pb/on-event :start :b)
                 (pb/set-initial-event :start nil)
                 (pb/build))
        result (pp/run-process spec {:timeout-ms 10000})]
    (is (= :completed (:status result)))
    (is (= 1 (ctx/get-var (:context result) :from-a)) "并行写回不互相覆盖（diff 合并）")
    (is (= 2 (ctx/get-var (:context result) :from-b)))))

;;; ============================================================
;;; Pause / Resume（human-in-the-loop，同步 API 与 V1 同构）
;;; ============================================================

(defn- approval-spec []
  (-> (pb/builder :approval)
      (pb/add-step {:id :ask
                    :on-activate (fn [inputs _ _]
                                   {:pause {:reason "等待审批"
                                            :state {:pending (:input inputs)}}})
                    :on-resume (fn [data state _]
                                 (if (= "approved" data)
                                   {:events [{:name :approved :data (:pending state)}]
                                    :state state}
                                   {:pause {:reason "再次等待" :state state}}))})
      (pb/add-step {:id :do-it
                    :on-activate (fn [inputs _ ctx]
                                   {:context (ctx/set-var ctx :done (:input inputs))
                                    :events []})})
      (pb/on-event :start :ask)
      (pb/on-event :approved :do-it)
      (pb/set-initial-event :start "删库")
      (pb/build)))

(deftest pause-resume-test
  (testing "pause → 批准 → 完成"
    (let [paused (pp/run-process (approval-spec))]
      (is (pp/paused? paused))
      (is (= :ask (:paused-step paused)))
      (is (= "等待审批" (:pause-reason paused)))
      (is (= {:pending "删库"} (get-in paused [:steps-state :ask :state]))
          "pause 携带的 :state 已落账")
      (let [result (pp/resume paused "approved")]
        (is (= :completed (:status result)))
        (is (= "删库" (ctx/get-var (:context result) :done))))))
  (testing "pause → 拒绝再暂停 → 批准（V2 活运行时：沿同一时间线续 resume）"
    (let [paused (pp/run-process (approval-spec))
          again  (pp/resume paused "nope")]
      (is (pp/paused? again))
      (is (= "再次等待" (:pause-reason again)))
      (is (= :completed (:status (pp/resume again "approved")))))))

(deftest resume-requires-on-resume-test
  (let [spec (-> (pb/builder :no-resume)
                 (pb/add-step {:id :p :on-activate (fn [_ _ _] {:pause {:reason "stuck"}})})
                 (pb/on-event :start :p)
                 (pb/set-initial-event :start nil)
                 (pb/build))
        paused (pp/run-process spec)]
    (is (pp/paused? paused))
    (is (= :paused (pp/get-status (pp/handle-of paused))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"on-resume"
          (pp/resume paused "x")))))

;;; ============================================================
;;; 错误：无 handler → :failed；有 handler → :error 事件路由
;;; ============================================================

(deftest error-fails-process-test
  (let [spec (-> (pb/builder :boom)
                 (pb/add-step {:id :a
                               :on-activate (fn [_ _ _] (throw (ex-info "炸了" {})))})
                 (pb/on-event :start :a)
                 (pb/set-initial-event :start nil)
                 (pb/build))
        result (pp/run-process spec {:timeout-ms 5000})]
    (is (= :failed (:status result)))
    (is (= "炸了" (get-in result [:error :reason])))
    (is (= :a (get-in result [:error :step])))))

(deftest error-handler-test
  (let [spec (-> (pb/builder :handled)
                 (pb/add-step {:id :a
                               :on-activate (fn [_ _ _] {:error {:reason "业务失败"}})})
                 (pb/add-step {:id :recover
                               :on-activate (fn [inputs _ ctx]
                                              {:context (ctx/set-var ctx :recovered-from
                                                                     (:input inputs))
                                               :events []})})
                 (pb/on-event :start :a)
                 (pb/on-error :recover)
                 (pb/set-initial-event :start nil)
                 (pb/build))
        result (pp/run-process spec {:timeout-ms 5000})]
    (is (= :completed (:status result)) "错误被 handler 消化，process 正常结束")
    (let [err (ctx/get-var (:context result) :recovered-from)]
      (is (= "业务失败" (:reason err)))
      (is (= :a (:step err))))))

;;; ============================================================
;;; 外部事件：ProcessHandle + send-event + :auto-complete? false
;;; ============================================================

(deftest external-events-test
  (let [spec (-> (pb/builder :external)
                 (pb/add-step {:id :collector
                               :init (fn [_] {:n 0})
                               :on-activate
                               (fn [_ state ctx]
                                 (let [n (inc (:n state))]
                                   (if (>= n 3)
                                     {:terminate true
                                      :context (ctx/set-var ctx :got n)}
                                     {:events [] :state {:n n}})))})
                 (pb/on-event :ping :collector)
                 (pb/build))
        h (pp/start-process spec {:auto-complete? false})]
    (is (= :running (pp/get-status h)))
    (is (nil? (pp/wait-for-completion h 100))
        ":auto-complete? false：没有事件也不自动结束")
    (is (true? (pp/send-event h :ping)))
    (pp/send-event h :ping)
    (pp/send-event h :ping)
    (let [result (pp/wait-for-completion h 5000)]
      (is (= :completed (:status result)) "第 3 个外部事件触发 :terminate")
      (is (= 3 (ctx/get-var (:context result) :got))))
    (is (false? (pp/send-event h :ping)) "终态后拒收外部事件")))

;;; ============================================================
;;; 超时：单步 + 全局
;;; ============================================================

(deftest step-timeout-test
  (let [spec (-> (pb/builder :slow-step)
                 (pb/add-step {:id :slow
                               :timeout-ms 100
                               :on-activate (fn [_ _ _]
                                              (Thread/sleep 2000)
                                              {:events []})})
                 (pb/on-event :start :slow)
                 (pb/set-initial-event :start nil)
                 (pb/build))
        result (pp/run-process spec {:timeout-ms 5000})]
    (is (= :failed (:status result)))
    (is (= :step-timeout (get-in result [:error :reason])))
    (is (= :slow (get-in result [:error :step])))))

(deftest global-timeout-test
  (let [spec (-> (pb/builder :slow-process)
                 (pb/add-step {:id :slow
                               :on-activate (fn [_ _ _]
                                              (Thread/sleep 3000)
                                              {:events []})})
                 (pb/on-event :start :slow)
                 (pb/set-initial-event :start nil)
                 (pb/build))
        result (pp/run-process spec {:timeout-ms 150})]
    (is (= :failed (:status result)))
    (is (= :timeout (get-in result [:error :reason])))))

;;; ============================================================
;;; stop-process：主动停止 + on-terminate 清理
;;; ============================================================

(deftest stop-process-test
  (let [cleaned (atom [])
        spec (-> (pb/builder :stoppable)
                 (pb/add-step {:id :waiter
                               :init (fn [_] {:res :handle})
                               :on-activate (fn [_ _ _]
                                              (Thread/sleep 5000)
                                              {:events []})
                               :on-terminate (fn [state _]
                                               (swap! cleaned conj (:res state)))})
                 (pb/on-event :start :waiter)
                 (pb/set-initial-event :start nil)
                 (pb/build))
        h (pp/start-process spec)
        result (pp/stop-process h)]
    (is (= :stopped (:status result)))
    (is (= :stopped (pp/get-status h)))
    (is (= [:handle] @cleaned) "stop 也走 on-terminate 清理")
    (is (= :stopped (:status (pp/stop-process h))) "stop 幂等")))

;;; ============================================================
;;; max-events 保险丝：无终止条件的自环不会挂死
;;; ============================================================

(deftest max-events-guard-test
  (let [spec (-> (pb/builder :runaway)
                 (pb/add-step {:id :spin
                               :on-activate (fn [_ _ _] {:events [{:name :again :data nil}]})})
                 (pb/on-event :start :spin)
                 (pb/on-event :again :spin)
                 (pb/set-initial-event :start nil)
                 (pb/build))
        result (pp/run-process spec {:max-events 50 :timeout-ms 10000})]
    (is (= :failed (:status result)))
    (is (= :max-events-exceeded (get-in result [:error :reason])))))
