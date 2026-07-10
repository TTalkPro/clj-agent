(ns im.ttalk.agent.process.runtime-test
  "Runtime V1：设计文档全部支持模式的行为测试——
   线性 / Fan-out / Fan-in / 循环 / pause-resume / error-handler /
   on-quiescent / 快照恢复 / on-terminate / max-events 保险丝。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.process.builder :as pb]
            [im.ttalk.agent.process.runtime :as rt]))

;;; ============================================================
;;; 线性流 A → B → C
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
        result (rt/run-process spec)]
    (testing "顺序执行，context 贯穿"
      (is (= :completed (:status result)))
      (is (= "x-a-b" (ctx/get-var (:context result) :final)))
      (is (true? (ctx/get-var (:context result) :a-ran))))
    (testing "steps-state 记录激活次数与最终 state"
      (is (= 1 (get-in result [:steps-state :a :activation-count])))
      (is (= 1 (get-in result [:steps-state :c :activation-count]))))))

;;; ============================================================
;;; Fan-out：一个事件两个绑定
;;; ============================================================

(deftest fan-out-test
  (let [order (atom [])
        mk (fn [id] {:id id
                     :on-activate (fn [inputs _ _]
                                    (swap! order conj id)
                                    {:events [{:name (keyword (str (name id) "-done"))
                                               :data (:input inputs)}]})})
        spec (-> (pb/builder :fan-out)
                 (pb/add-step (mk :a))
                 (pb/add-step (mk :b))
                 (pb/add-step (mk :c))
                 (pb/on-event :start :a)
                 (pb/on-event :a-done :b)
                 (pb/on-event :a-done :c)          ;; 同一事件 → 两个下游
                 (pb/set-initial-event :start 1)
                 (pb/build))
        result (rt/run-process spec)]
    (is (= :completed (:status result)))
    (is (= [:a :b :c] @order) "V1 顺序执行：同批激活按注册顺序")
    (is (= 1 (get-in result [:steps-state :b :activation-count])))
    (is (= 1 (get-in result [:steps-state :c :activation-count])))))

;;; ============================================================
;;; Fan-in：C 有两个 required-inputs，齐了才激活
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
        result (rt/run-process spec)]
    (is (= :completed (:status result)))
    (is (= "AB" (ctx/get-var (:context result) :joined)))
    (is (= 1 (get-in result [:steps-state :join :activation-count]))
        "两输入收齐只激活一次")))

;;; ============================================================
;;; 循环 + step 私有 state + :terminate 退出
;;; ============================================================

(deftest loop-with-state-test
  (let [spec (-> (pb/builder :loop)
                 (pb/add-step {:id :counter
                               :init (fn [_] {:count 0})
                               :on-activate
                               (fn [_ state ctx]
                                 (let [n (inc (:count state))]
                                   (if (>= n 3)
                                     {:terminate true
                                      :state {:count n}
                                      :context (ctx/set-var ctx :total n)}
                                     {:events [{:name :again :data n}]
                                      :state {:count n}})))})
                 (pb/on-event :start :counter)
                 (pb/on-event :again :counter)        ;; 自环
                 (pb/set-initial-event :start 0)
                 (pb/build))
        result (rt/run-process spec)]
    (is (= :completed (:status result)))
    (is (= 3 (ctx/get-var (:context result) :total)))
    (is (= 3 (get-in result [:steps-state :counter :activation-count])))
    (is (= {:count 3} (get-in result [:steps-state :counter :state]))
        "init 建 state₀，:state 返回值逐轮推进")))

(deftest can-activate-guard-test
  (testing "守卫 false 时不激活也不清 inputs；下一次投递再查"
    (let [spec (-> (pb/builder :guard)
                   (pb/add-step {:id :batcher
                                 :required-inputs [:item]
                                 :init (fn [_] {:seen 0})
                                 ;; 只有 item >= 2 才放行
                                 :can-activate? (fn [inputs _] (>= (:item inputs) 2))
                                 :on-activate (fn [inputs _ ctx]
                                                {:context (ctx/set-var ctx :fired (:item inputs))
                                                 :events []})})
                   (pb/add-step {:id :feeder
                                 :init (fn [_] {:n 0})
                                 :on-activate (fn [_ state _]
                                                (let [n (inc (:n state))]
                                                  (if (<= n 2)
                                                    {:events [{:name :item :data n}
                                                              {:name :feed :data n}]
                                                     :state {:n n}}
                                                    {:events [] :state state})))})
                   (pb/on-event :start :feeder)
                   (pb/on-event :feed :feeder)
                   (pb/on-event :item :batcher :item)
                   (pb/set-initial-event :start nil)
                   (pb/build))
          result (rt/run-process spec)]
      (is (= :completed (:status result)))
      (is (= 2 (ctx/get-var (:context result) :fired)) "item=1 被守卫拦下，item=2 放行")
      (is (= 1 (get-in result [:steps-state :batcher :activation-count]))))))

;;; ============================================================
;;; Pause / Resume（human-in-the-loop）
;;; ============================================================

(defn- approval-spec []
  (-> (pb/builder :approval)
      (pb/add-step {:id :ask
                    :on-activate (fn [inputs state _]
                                   {:pause {:reason "等待审批"
                                            :state {:pending (:input inputs)}}})
                    :on-resume (fn [data state ctx]
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
  (let [paused (rt/run-process (approval-spec))]
    (testing "pause：状态与原因外显"
      (is (rt/paused? paused))
      (is (= :ask (:paused-step paused)))
      (is (= "等待审批" (:pause-reason paused)))
      (is (= {:pending "删库"} (get-in paused [:steps-state :ask :state]))
          "pause 携带的 :state 已落账"))
    (testing "resume approved → 续跑至完成"
      (let [result (rt/resume paused "approved")]
        (is (= :completed (:status result)))
        (is (= "删库" (ctx/get-var (:context result) :done)))))
    (testing "resume 拒绝 → 再次暂停，可再 resume"
      (let [again (rt/resume paused "nope")]
        (is (rt/paused? again))
        (is (= "再次等待" (:pause-reason again)))
        (is (= :completed (:status (rt/resume again "approved"))))))))

(deftest resume-requires-on-resume-test
  (let [spec (-> (pb/builder :no-resume)
                 (pb/add-step {:id :p :on-activate (fn [_ _ _] {:pause {:reason "stuck"}})})
                 (pb/on-event :start :p)
                 (pb/set-initial-event :start nil)
                 (pb/build))
        paused (rt/run-process spec)]
    (is (rt/paused? paused))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"on-resume"
          (rt/resume paused "x")))))

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
        result (rt/run-process spec)]
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
        result (rt/run-process spec)]
    (is (= :completed (:status result)) "错误被 handler 消化，process 正常结束")
    (let [err (ctx/get-var (:context result) :recovered-from)]
      (is (= "业务失败" (:reason err)))
      (is (= :a (:step err)) "错误载荷携带出错 step"))))

;;; ============================================================
;;; on-quiescent 静止点（触发时机对齐设计文档示例）
;;; ============================================================

(deftest on-quiescent-linear-test
  (testing "线性 A→B→C：A 后、B 后各一次；C 后 process 结束不触发"
    (let [snaps (atom [])
          mk (fn [id ev] {:id id :on-activate
                          (fn [_ _ _] {:events (if ev [{:name ev :data nil}] [])})})
          spec (-> (pb/builder :q-linear)
                   (pb/add-step (mk :a :a-done))
                   (pb/add-step (mk :b :b-done))
                   (pb/add-step (mk :c nil))
                   (pb/on-event :start :a)
                   (pb/on-event :a-done :b)
                   (pb/on-event :b-done :c)
                   (pb/set-initial-event :start nil)
                   (pb/build))]
      (rt/run-process spec {:on-quiescent #(swap! snaps conj %)})
      (is (= [:quiescent :quiescent] (mapv :reason @snaps)))
      (is (every? #(= :q-linear (:process-name %)) @snaps))
      (is (every? #(contains? % :created-at) @snaps)))))

(deftest on-quiescent-paused-test
  (let [snaps (atom [])
        paused (rt/run-process (approval-spec) {:on-quiescent #(swap! snaps conj %)})]
    (is (rt/paused? paused))
    (let [p (last @snaps)]
      (is (= :paused (:reason p)))
      (is (= :ask (:paused-step p)))
      (is (= "等待审批" (:pause-reason p))))))

;;; ============================================================
;;; 快照恢复：step-states + context + initial-events 续驱
;;; ============================================================

(deftest restore-from-snapshot-test
  (let [spec (-> (pb/builder :restorable)
                 (pb/add-step {:id :acc
                               :init (fn [_] {:sum 0})
                               :on-activate
                               (fn [inputs state ctx]
                                 (let [sum (+ (:sum state) (:input inputs))]
                                   {:state {:sum sum}
                                    :context (ctx/set-var ctx :sum sum)
                                    :events []}))})
                 (pb/on-event :add :acc)
                 (pb/set-initial-event :add 10)
                 (pb/build))
        ;; 第一段：跑到 sum=10，拿快照
        r1 (rt/run-process spec)
        _ (is (= 10 (get-in r1 [:steps-state :acc :state :sum])))
        ;; 第二段：从快照恢复（跳过 init），换一批 initial-events 续驱
        r2 (rt/run-process spec {:step-states  (:steps-state r1)
                                 :context      (:context r1)
                                 :initial-events [{:name :add :data 5}]})]
    (is (= 15 (get-in r2 [:steps-state :acc :state :sum])) "state 从快照续算而非重 init")
    (is (= 15 (ctx/get-var (:context r2) :sum)))
    (is (= 2 (get-in r2 [:steps-state :acc :activation-count])) "激活计数也延续")))

;;; ============================================================
;;; on-terminate：结束时逐 step 清理，异常吞掉
;;; ============================================================

(deftest on-terminate-test
  (let [cleaned (atom [])
        spec (-> (pb/builder :cleanup)
                 (pb/add-step {:id :a
                               :init (fn [_] {:res :file-handle})
                               :on-activate (fn [_ _ _] {:events []})
                               :on-terminate (fn [state _]
                                               (swap! cleaned conj (:res state)))})
                 (pb/add-step {:id :bad-cleaner
                               :on-activate (fn [_ _ _] {:events []})
                               :on-terminate (fn [_ _] (throw (ex-info "清理炸了" {})))})
                 (pb/add-step {:id :c
                               :on-activate (fn [_ _ _] {:events []})
                               :on-terminate (fn [_ _] (swap! cleaned conj :c-cleaned))})
                 (pb/on-event :start :a)
                 (pb/on-event :start :bad-cleaner)
                 (pb/on-event :start :c)
                 (pb/set-initial-event :start nil)
                 (pb/build))
        result (rt/run-process spec)]
    (is (= :completed (:status result)))
    (is (= [:file-handle :c-cleaned] @cleaned)
        "bad-cleaner 的异常不影响其他 step 清理")))

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
        result (rt/run-process spec {:max-events 50})]
    (is (= :failed (:status result)))
    (is (= :max-events-exceeded (get-in result [:error :reason])))))

;;; ============================================================
;;; binding transform
;;; ============================================================

(deftest binding-transform-test
  (let [spec (-> (pb/builder :xform)
                 (pb/add-step {:id :sink
                               :on-activate (fn [inputs _ ctx]
                                              {:context (ctx/set-var ctx :got (:input inputs))
                                               :events []})})
                 (pb/on-event :start :sink :input {:transform #(* 2 %)})
                 (pb/set-initial-event :start 21)
                 (pb/build))
        result (rt/run-process spec)]
    (is (= 42 (ctx/get-var (:context result) :got)))))

;;; ============================================================
;;; 与 Kernel 集成：step 内经 context 取 kernel 调工具
;;; ============================================================

(deftest kernel-integration-test
  (let [_ (require '[im.ttalk.agent.kernel :as kernel]
                   '[im.ttalk.agent.tool :as tool])
        kernel-ns (find-ns 'im.ttalk.agent.kernel)
        build-kernel (ns-resolve kernel-ns 'build-kernel)
        invoke-tool  (ns-resolve kernel-ns 'invoke-tool)
        ;; 内联工具（inline handler，免 deftool 宏在测试里的 var 依赖）
        k (build-kernel {:service {:chat-fn (fn [_ _] {:text "unused"})}
                         :tools [{:name "double" :description "翻倍"
                                  :input_schema {:type "object" :properties {}}
                                  :handler (fn [args _ctx] (str (* 2 (:n args))))}]})
        spec (-> (pb/builder :with-kernel)
                 (pb/add-step {:id :worker
                               :on-activate
                               (fn [inputs _ c]
                                 (let [kk (ctx/get-var c :kernel)
                                       {:keys [value context]}
                                       (invoke-tool kk :double {:n (:input inputs)} c)]
                                   {:context (ctx/set-var context :result value)
                                    :events []}))})
                 (pb/on-event :start :worker)
                 (pb/set-initial-event :start 21)
                 (pb/build))
        result (rt/run-process spec {:context (ctx/create {:kernel k})})]
    (is (= :completed (:status result)))
    (is (= "42" (ctx/get-var (:context result) :result))
        "step 内经 context 注入的 kernel 调工具，结果写回共享 context")))
