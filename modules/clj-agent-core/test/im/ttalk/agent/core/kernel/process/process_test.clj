(ns im.ttalk.agent.core.kernel.process.process-test
  "Process Framework 综合测试（Channel-based 并行 Runtime）"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string]
            [im.ttalk.agent.core.kernel.process.event :as event]
            [im.ttalk.agent.core.kernel.process.step :as step]
            [im.ttalk.agent.core.kernel.process.builder :as builder]
            [im.ttalk.agent.core.kernel.process.runtime :as runtime]
            [im.ttalk.agent.core.kernel.context :as ctx]))

;; 默认测试超时
(def ^:private test-timeout 5000)

;; ============================================================
;; Phase 1: Event 创建与路由
;; ============================================================

(deftest event-create-test
  (testing "创建基本事件"
    (let [e (event/create :start "hello")]
      (is (= :start (:name e)))
      (is (= "hello" (:data e)))
      (is (nil? (:source e)))
      (is (= :public (:type e)))))

  (testing "创建无数据事件"
    (let [e (event/create :start)]
      (is (= :start (:name e)))
      (is (nil? (:data e)))))

  (testing "创建错误事件"
    (let [e (event/error-event :step-a "something broke")]
      (is (= :error (:name e)))
      (is (= :step-a (:source e)))
      (is (= {:reason "something broke"} (:data e)))
      (is (= :error (:type e)))))

  (testing "标记事件来源"
    (let [e (-> (event/create :done "result")
                (event/with-source :step-b))]
      (is (= :step-b (:source e))))))

(deftest event-binding-test
  (testing "创建基本绑定"
    (let [b (event/binding :start :step-a :input)]
      (is (= :start (:event-name b)))
      (is (= :step-a (:target-step b)))
      (is (= :input (:target-input b)))
      (is (nil? (:transform b)))))

  (testing "创建带 transform 的绑定"
    (let [t (fn [data] (str data "!"))
          b (event/binding :start :step-a :input t)]
      (is (= t (:transform b))))))

(deftest event-route-test
  (testing "路由事件到单个目标"
    (let [bindings [(event/binding :start :step-a :data)]
          e (event/create :start "hello")
          deliveries (event/route e bindings)]
      (is (= 1 (count deliveries)))
      (is (= {:step-id :step-a :input-name :data :data "hello"}
             (first deliveries)))))

  (testing "路由事件到多个目标（fan-out）"
    (let [bindings [(event/binding :start :step-a :data)
                    (event/binding :start :step-b :input)]
          e (event/create :start "hello")
          deliveries (event/route e bindings)]
      (is (= 2 (count deliveries)))
      (is (= :step-a (:step-id (first deliveries))))
      (is (= :step-b (:step-id (second deliveries))))))

  (testing "路由不匹配事件"
    (let [bindings [(event/binding :start :step-a :data)]
          e (event/create :other "hello")
          deliveries (event/route e bindings)]
      (is (empty? deliveries))))

  (testing "路由带 transform"
    (let [bindings [(event/binding :start :step-a :data
                                   (fn [d] (str d " world")))]
          e (event/create :start "hello")
          deliveries (event/route e bindings)]
      (is (= "hello world" (:data (first deliveries)))))))

;; ============================================================
;; Phase 2: Step 初始化与激活
;; ============================================================

(deftest step-init-test
  (testing "初始化无 init 函数的 step"
    (let [step-spec {:id :my-step
                    :on-activate (fn [_ _ _] {})}
          state (step/init-step step-spec)]
      (is (= step-spec (:step-spec state)))
      (is (nil? (:state state)))
      (is (= {} (:collected-inputs state)))
      (is (= 0 (:activation-count state)))))

  (testing "初始化有 init 函数的 step"
    (let [step-spec {:id :my-step
                    :init (fn [config] {:counter (:start config)})
                    :config {:start 10}
                    :on-activate (fn [_ _ _] {})}
          state (step/init-step step-spec)]
      (is (= {:counter 10} (:state state))))))

(deftest step-collect-input-test
  (testing "收集单个输入"
    (let [state (step/init-step {:id :s :on-activate (fn [_ _ _] {})})
          state (step/collect-input state :data "hello")]
      (is (= "hello" (get-in state [:collected-inputs :data])))))

  (testing "收集多个输入"
    (let [state (-> (step/init-step {:id :s :on-activate (fn [_ _ _] {})})
                    (step/collect-input :a 1)
                    (step/collect-input :b 2))]
      (is (= {:a 1 :b 2} (:collected-inputs state)))))

  (testing "清除输入"
    (let [state (-> (step/init-step {:id :s :on-activate (fn [_ _ _] {})})
                    (step/collect-input :a 1)
                    (step/clear-inputs))]
      (is (= {} (:collected-inputs state))))))

(deftest step-activation-check-test
  (testing "默认 required-inputs [:input]"
    (let [state (step/init-step {:id :s :on-activate (fn [_ _ _] {})})]
      (is (not (step/check-activation state)))
      (let [state (step/collect-input state :input "data")]
        (is (step/check-activation state)))))

  (testing "自定义 required-inputs"
    (let [state (step/init-step {:id :s
                                 :on-activate (fn [_ _ _] {})
                                 :required-inputs [:a :b]})]
      (is (not (step/check-activation state)))
      (let [state (step/collect-input state :a 1)]
        (is (not (step/check-activation state))))
      (let [state (-> state
                      (step/collect-input :a 1)
                      (step/collect-input :b 2))]
        (is (step/check-activation state)))))

  (testing "can-activate? 守卫"
    (let [state (step/init-step
                  {:id :s
                   :on-activate (fn [_ _ _] {})
                   :can-activate? (fn [inputs _state]
                                    (> (:input inputs) 5))})
          state-low (step/collect-input state :input 3)
          state-high (step/collect-input state :input 10)]
      (is (not (step/check-activation state-low)))
      (is (step/check-activation state-high)))))

(deftest step-execute-test
  (testing "正常执行产出事件"
    (let [step-spec {:id :adder
                    :on-activate (fn [inputs state _ctx]
                                   {:events [{:name :result :data (+ (:a inputs) (:b inputs))}]
                                    :state (inc (or state 0))})
                    :required-inputs [:a :b]}
          state (-> (step/init-step step-spec)
                    (step/collect-input :a 3)
                    (step/collect-input :b 4))
          {:keys [result step-state]} (step/execute state (ctx/create))]
      (is (= 7 (:data (first (:events result)))))
      (is (= :adder (:source (first (:events result)))))
      (is (= 1 (:state step-state)))
      (is (= {} (:collected-inputs step-state)))
      (is (= 1 (:activation-count step-state)))))

  (testing "执行产出暂停"
    (let [step-spec {:id :pauser
                    :on-activate (fn [inputs _state _ctx]
                                   {:pause {:reason "需要审批" :state {:pending inputs}}})}
          state (-> (step/init-step step-spec)
                    (step/collect-input :input "data"))
          {:keys [result step-state]} (step/execute state (ctx/create))]
      (is (= "需要审批" (get-in result [:pause :reason])))
      (is (= {:pending {:input "data"}} (:state step-state)))))

  (testing "执行产出错误"
    (let [step-spec {:id :failer
                    :on-activate (fn [_ _ _]
                                   {:error {:reason "something broke"}})}
          state (-> (step/init-step step-spec)
                    (step/collect-input :input "x"))
          {:keys [result]} (step/execute state (ctx/create))]
      (is (= "something broke" (get-in result [:error :reason])))))

  (testing "执行时异常被捕获"
    (let [step-spec {:id :thrower
                    :on-activate (fn [_ _ _]
                                   (throw (Exception. "boom")))}
          state (-> (step/init-step step-spec)
                    (step/collect-input :input "x"))
          {:keys [result]} (step/execute state (ctx/create))]
      (is (= "boom" (get-in result [:error :reason]))))))

(deftest step-resume-test
  (testing "恢复暂停的 step"
    (let [step-spec {:id :resumable
                    :on-activate (fn [_ _ _]
                                   {:pause {:reason "wait" :state {:waiting true}}})
                    :on-resume (fn [data state _ctx]
                                 {:events [{:name :resumed :data data}]
                                  :state (assoc state :waiting false)})}
          state (-> (step/init-step step-spec)
                    (step/collect-input :input "x"))
          {:keys [step-state]} (step/execute state (ctx/create))
          {:keys [result step-state]} (step/resume-step step-state "approved" (ctx/create))]
      (is (= "approved" (:data (first (:events result)))))
      (is (= {:waiting false} (:state step-state)))))

  (testing "不支持 resume 的 step 返回 nil"
    (let [step-spec {:id :no-resume
                    :on-activate (fn [_ _ _] {})}
          state (step/init-step step-spec)]
      (is (nil? (step/resume-step state "data" (ctx/create)))))))

;; ============================================================
;; Phase 3: Builder API
;; ============================================================

(deftest builder-create-test
  (testing "创建 builder"
    (let [b (builder/builder :my-process)]
      (is (:__process_builder__ b))
      (is (= :my-process (:name b)))
      (is (= {} (:steps b)))
      (is (= [] (:bindings b)))
      (is (= [] (:initial-events b)))
      (is (nil? (:error-handler b))))))

(deftest builder-add-step-test
  (testing "添加 step"
    (let [b (-> (builder/builder :p)
                (builder/add-step {:id :s1
                                   :on-activate (fn [_ _ _] {})}))]
      (is (contains? (:steps b) :s1))))

  (testing "缺少 :id 抛异常"
    (is (thrown-with-msg? Exception #"缺少 :id"
          (builder/add-step (builder/builder :p)
                            {:on-activate (fn [_ _ _] {})}))))

  (testing "缺少 :on-activate 抛异常"
    (is (thrown-with-msg? Exception #"缺少 :on-activate"
          (builder/add-step (builder/builder :p)
                            {:id :s1})))))

(deftest builder-on-event-test
  (testing "添加事件绑定"
    (let [b (-> (builder/builder :p)
                (builder/on-event :start :s1 :input))]
      (is (= 1 (count (:bindings b))))
      (is (= :start (:event-name (first (:bindings b))))))))

(deftest builder-initial-event-test
  (testing "设置初始事件"
    (let [b (-> (builder/builder :p)
                (builder/set-initial-event :start "go"))]
      (is (= 1 (count (:initial-events b))))
      (is (= :start (:name (first (:initial-events b)))))
      (is (= "go" (:data (first (:initial-events b))))))))

(deftest builder-build-test
  (testing "正常 build"
    (let [process-spec (-> (builder/builder :test)
                          (builder/add-step {:id :s1
                                            :on-activate (fn [_ _ _] {})})
                          (builder/on-event :start :s1 :input)
                          (builder/set-initial-event :start)
                          (builder/build))]
      (is (:__process_spec__ process-spec))
      (is (= :test (:name process-spec)))
      (is (contains? (:steps process-spec) :s1))
      (is (= 1 (count (:bindings process-spec))))))

  (testing "空 steps 抛异常"
    (is (thrown-with-msg? Exception #"至少需要一个 step"
          (builder/build (builder/builder :empty)))))

  (testing "绑定目标不存在抛异常"
    (is (thrown-with-msg? Exception #"不存在"
          (-> (builder/builder :p)
              (builder/add-step {:id :s1 :on-activate (fn [_ _ _] {})})
              (builder/on-event :start :s2 :input)
              (builder/build)))))

  (testing "error-handler 不存在抛异常"
    (is (thrown-with-msg? Exception #"不存在"
          (-> (builder/builder :p)
              (builder/add-step {:id :s1 :on-activate (fn [_ _ _] {})})
              (builder/set-error-handler :nonexistent)
              (builder/build))))))

;; ============================================================
;; Phase 4: Runtime 执行（Channel-based 并行）
;; ============================================================

(deftest runtime-linear-flow-test
  (testing "线性流程 A → B → C"
    (let [process-spec (-> (builder/builder :linear)
                          (builder/add-step
                            {:id :step-a
                             :on-activate (fn [inputs _state _ctx]
                                            {:events [{:name :a-done
                                                       :data (str (:input inputs) "-A")}]})})
                          (builder/add-step
                            {:id :step-b
                             :on-activate (fn [inputs _state _ctx]
                                            {:events [{:name :b-done
                                                       :data (str (:input inputs) "-B")}]})})
                          (builder/add-step
                            {:id :step-c
                             :on-activate (fn [inputs _state ctx]
                                            {:events []
                                             :context (ctx/set-var ctx :result (:input inputs))})})
                          (builder/on-event :start :step-a :input)
                          (builder/on-event :a-done :step-b :input)
                          (builder/on-event :b-done :step-c :input)
                          (builder/set-initial-event :start "hello")
                          (builder/build))
          result (runtime/run-process process-spec {:timeout-ms test-timeout})]
      (is (= :completed (:status result)))
      (is (= "hello-A-B" (ctx/get-var (:context result) :result))))))

(deftest runtime-fan-out-test
  (testing "Fan-out：一个事件触发多个 step（并行执行）"
    (let [execution-log (atom [])
          process-spec (-> (builder/builder :fan-out)
                          (builder/add-step
                            {:id :source
                             :on-activate (fn [inputs _state _ctx]
                                            {:events [{:name :data-ready :data (:input inputs)}]})})
                          (builder/add-step
                            {:id :consumer-a
                             :on-activate (fn [inputs _state ctx]
                                            (swap! execution-log conj :consumer-a)
                                            {:context (ctx/set-var ctx :a-got (:input inputs))})})
                          (builder/add-step
                            {:id :consumer-b
                             :on-activate (fn [inputs _state ctx]
                                            (swap! execution-log conj :consumer-b)
                                            {:context (ctx/set-var ctx :b-got (:input inputs))})})
                          (builder/on-event :start :source :input)
                          (builder/on-event :data-ready :consumer-a :input)
                          (builder/on-event :data-ready :consumer-b :input)
                          (builder/set-initial-event :start "shared")
                          (builder/build))
          result (runtime/run-process process-spec {:timeout-ms test-timeout})]
      (is (= :completed (:status result)))
      (is (= "shared" (ctx/get-var (:context result) :a-got)))
      (is (= "shared" (ctx/get-var (:context result) :b-got)))
      ;; 验证两个 consumer 都执行了
      (is (= 2 (count @execution-log))))))

(deftest runtime-fan-in-test
  (testing "Fan-in：多个输入汇聚到一个 step"
    (let [process-spec (-> (builder/builder :fan-in)
                          (builder/add-step
                            {:id :source-a
                             :on-activate (fn [inputs _state _ctx]
                                            {:events [{:name :a-ready :data (str (:input inputs) "-A")}]})})
                          (builder/add-step
                            {:id :source-b
                             :on-activate (fn [inputs _state _ctx]
                                            {:events [{:name :b-ready :data (str (:input inputs) "-B")}]})})
                          (builder/add-step
                            {:id :aggregator
                             :required-inputs [:from-a :from-b]
                             :on-activate (fn [inputs _state ctx]
                                            {:context (ctx/set-var ctx :combined
                                                        (str (:from-a inputs) "+" (:from-b inputs)))})})
                          (builder/on-event :start :source-a :input)
                          (builder/on-event :start :source-b :input)
                          (builder/on-event :a-ready :aggregator :from-a)
                          (builder/on-event :b-ready :aggregator :from-b)
                          (builder/set-initial-event :start "X")
                          (builder/build))
          result (runtime/run-process process-spec {:timeout-ms test-timeout})]
      (is (= :completed (:status result)))
      (is (= "X-A+X-B" (ctx/get-var (:context result) :combined))))))

(deftest runtime-cycle-test
  (testing "循环流程：step 循环直到条件满足"
    (let [process-spec (-> (builder/builder :cycle)
                          (builder/add-step
                            {:id :counter
                             :init (fn [_] {:count 0})
                             :on-activate (fn [_inputs state _ctx]
                                            (let [n (inc (:count state))]
                                              (if (>= n 3)
                                                {:events [{:name :done :data n}]
                                                 :state {:count n}}
                                                {:events [{:name :again :data n}]
                                                 :state {:count n}})))})
                          (builder/add-step
                            {:id :collector
                             :on-activate (fn [inputs _state ctx]
                                            {:context (ctx/set-var ctx :final-count (:input inputs))})})
                          (builder/on-event :start :counter :input)
                          (builder/on-event :again :counter :input)
                          (builder/on-event :done :collector :input)
                          (builder/set-initial-event :start "go")
                          (builder/build))
          result (runtime/run-process process-spec {:timeout-ms test-timeout})]
      (is (= :completed (:status result)))
      (is (= 3 (ctx/get-var (:context result) :final-count))))))

(deftest runtime-pause-resume-test
  (testing "暂停和恢复"
    (let [process-spec (-> (builder/builder :pausable)
                          (builder/add-step
                            {:id :approval-step
                             :on-activate (fn [inputs _state _ctx]
                                            {:pause {:reason "需要审批"
                                                     :state {:request (:input inputs)}}})
                             :on-resume (fn [data state _ctx]
                                          {:events [{:name :approved
                                                     :data {:request (:request state)
                                                            :decision data}}]
                                           :state nil})})
                          (builder/add-step
                            {:id :final-step
                             :on-activate (fn [inputs _state ctx]
                                            {:context (ctx/set-var ctx :outcome (:input inputs))})})
                          (builder/on-event :start :approval-step :input)
                          (builder/on-event :approved :final-step :input)
                          (builder/set-initial-event :start "deploy v2.0")
                          (builder/build))
          ;; 运行到暂停
          paused (runtime/run-process process-spec {:timeout-ms test-timeout})]
      (is (= :paused (:status paused)))
      (is (= :approval-step (:paused-step paused)))
      (is (= "需要审批" (:pause-reason paused)))
      ;; 恢复
      (let [result (runtime/run-resume paused "approved")]
        (is (= :completed (:status result)))
        (is (= {:request "deploy v2.0" :decision "approved"}
               (ctx/get-var (:context result) :outcome)))))))

(deftest runtime-error-handling-test
  (testing "无 error-handler 时标记 failed"
    (let [process-spec (-> (builder/builder :fail-test)
                          (builder/add-step
                            {:id :bad-step
                             :on-activate (fn [_ _ _]
                                            {:error {:reason "oops"}})})
                          (builder/on-event :start :bad-step :input)
                          (builder/set-initial-event :start "go")
                          (builder/build))
          result (runtime/run-process process-spec {:timeout-ms test-timeout})]
      (is (= :failed (:status result)))
      (is (= "oops" (get-in result [:error :reason])))))

  (testing "有 error-handler 时路由错误"
    (let [process-spec (-> (builder/builder :error-handled)
                          (builder/add-step
                            {:id :bad-step
                             :on-activate (fn [_ _ _]
                                            {:error {:reason "oops"}})})
                          (builder/add-step
                            {:id :error-handler
                             :required-inputs [:error]
                             :on-activate (fn [inputs _state ctx]
                                            {:context (ctx/set-var ctx :handled
                                                        (:reason (:error inputs)))})})
                          (builder/on-event :start :bad-step :input)
                          (builder/set-error-handler :error-handler)
                          (builder/set-initial-event :start "go")
                          (builder/build))
          result (runtime/run-process process-spec {:timeout-ms test-timeout})]
      (is (= :completed (:status result)))
      (is (= "oops" (ctx/get-var (:context result) :handled))))))

(deftest runtime-exception-handling-test
  (testing "Step 抛异常被捕获并标记 failed"
    (let [process-spec (-> (builder/builder :exception-test)
                          (builder/add-step
                            {:id :thrower
                             :on-activate (fn [_ _ _]
                                            (throw (Exception. "kaboom")))})
                          (builder/on-event :start :thrower :input)
                          (builder/set-initial-event :start "go")
                          (builder/build))
          result (runtime/run-process process-spec {:timeout-ms test-timeout})]
      (is (= :failed (:status result)))
      (is (= "kaboom" (get-in result [:error :reason]))))))

(deftest runtime-timeout-test
  (testing "全局超时"
    (let [process-spec (-> (builder/builder :slow)
                          (builder/add-step
                            {:id :slow-step
                             :on-activate (fn [_ _ _]
                                            (Thread/sleep 3000)
                                            {:events [{:name :done :data nil}]})})
                          (builder/on-event :start :slow-step :input)
                          (builder/set-initial-event :start nil)
                          (builder/build))
          result (runtime/run-process process-spec {:timeout-ms 500})]
      (is (= :failed (:status result)))
      (is (clojure.string/includes? (str (:reason (:error result))) "超时")))))

(deftest runtime-step-state-persistence-test
  (testing "Step 状态在多次激活间持久化"
    (let [process-spec (-> (builder/builder :stateful)
                          (builder/add-step
                            {:id :accumulator
                             :init (fn [_] {:sum 0})
                             :on-activate (fn [inputs state _ctx]
                                            (let [new-sum (+ (:sum state) (:input inputs))]
                                              (if (>= new-sum 10)
                                                {:events [{:name :done :data new-sum}]
                                                 :state {:sum new-sum}}
                                                {:events [{:name :add-more :data new-sum}]
                                                 :state {:sum new-sum}})))})
                          (builder/add-step
                            {:id :feeder
                             :on-activate (fn [_ _ _]
                                            {:events [{:name :feed :data 3}]})})
                          (builder/add-step
                            {:id :result
                             :on-activate (fn [inputs _state ctx]
                                            {:context (ctx/set-var ctx :total (:input inputs))})})
                          (builder/on-event :start :accumulator :input)
                          (builder/on-event :add-more :feeder :input)
                          (builder/on-event :feed :accumulator :input)
                          (builder/on-event :done :result :input)
                          (builder/set-initial-event :start 3)
                          (builder/build))
          result (runtime/run-process process-spec {:timeout-ms test-timeout})]
      (is (= :completed (:status result)))
      (is (>= (ctx/get-var (:context result) :total) 10)))))

(deftest runtime-context-passing-test
  (testing "Context 在 step 之间传递"
    (let [process-spec (-> (builder/builder :ctx-pass)
                          (builder/add-step
                            {:id :step-a
                             :on-activate (fn [_ _ ctx]
                                            {:events [{:name :next :data nil}]
                                             :context (ctx/set-var ctx :a-was-here true)})})
                          (builder/add-step
                            {:id :step-b
                             :on-activate (fn [_ _ ctx]
                                            {:context (ctx/set-var ctx :b-saw-a
                                                        (ctx/get-var ctx :a-was-here))})})
                          (builder/on-event :start :step-a :input)
                          (builder/on-event :next :step-b :input)
                          (builder/set-initial-event :start nil)
                          (builder/build))
          result (runtime/run-process process-spec {:timeout-ms test-timeout})]
      (is (= :completed (:status result)))
      (is (true? (ctx/get-var (:context result) :a-was-here)))
      (is (true? (ctx/get-var (:context result) :b-saw-a))))))

(deftest runtime-transform-binding-test
  (testing "绑定的 transform 函数生效"
    (let [process-spec (-> (builder/builder :transform)
                          (builder/add-step
                            {:id :source
                             :on-activate (fn [inputs _ _]
                                            {:events [{:name :data-out :data (:input inputs)}]})})
                          (builder/add-step
                            {:id :target
                             :on-activate (fn [inputs _ ctx]
                                            {:context (ctx/set-var ctx :result (:input inputs))})})
                          (builder/on-event :start :source :input)
                          (builder/on-event :data-out :target :input
                                            (fn [data] (clojure.string/upper-case data)))
                          (builder/set-initial-event :start "hello")
                          (builder/build))
          result (runtime/run-process process-spec {:timeout-ms test-timeout})]
      (is (= :completed (:status result)))
      (is (= "HELLO" (ctx/get-var (:context result) :result))))))

(deftest runtime-idle-start-test
  (testing "没有初始事件时状态为 completed"
    (let [process-spec (-> (builder/builder :idle-test)
                          (builder/add-step
                            {:id :s1
                             :on-activate (fn [_ _ _] {})})
                          (builder/on-event :start :s1 :input)
                          (builder/build))
          result (runtime/run-process process-spec {:timeout-ms test-timeout})]
      (is (= :completed (:status result))))))

(deftest runtime-resume-not-paused-test
  (testing "对非 paused 状态调用 resume 抛异常"
    (let [process-spec (-> (builder/builder :test)
                          (builder/add-step
                            {:id :s1
                             :on-activate (fn [_ _ _] {})})
                          (builder/on-event :start :s1 :input)
                          (builder/set-initial-event :start nil)
                          (builder/build))
          result (runtime/run-process process-spec {:timeout-ms test-timeout})]
      (is (thrown-with-msg? Exception #"只能恢复"
            (runtime/run-resume result "data"))))))

(deftest runtime-parallel-execution-test
  (testing "Fan-out step 实际并行执行"
    (let [start-times (atom {})
          process-spec (-> (builder/builder :parallel-test)
                          (builder/add-step
                            {:id :trigger
                             :on-activate (fn [_ _ _]
                                            {:events [{:name :go :data nil}]})})
                          (builder/add-step
                            {:id :slow-a
                             :on-activate (fn [_ _ ctx]
                                            (swap! start-times assoc :a (System/currentTimeMillis))
                                            (Thread/sleep 200)
                                            {:context (ctx/set-var ctx :a-done true)})})
                          (builder/add-step
                            {:id :slow-b
                             :on-activate (fn [_ _ ctx]
                                            (swap! start-times assoc :b (System/currentTimeMillis))
                                            (Thread/sleep 200)
                                            {:context (ctx/set-var ctx :b-done true)})})
                          (builder/on-event :start :trigger :input)
                          (builder/on-event :go :slow-a :input)
                          (builder/on-event :go :slow-b :input)
                          (builder/set-initial-event :start nil)
                          (builder/build))
          t0 (System/currentTimeMillis)
          result (runtime/run-process process-spec {:timeout-ms test-timeout})
          elapsed (- (System/currentTimeMillis) t0)]
      (is (= :completed (:status result)))
      (is (true? (ctx/get-var (:context result) :a-done)))
      (is (true? (ctx/get-var (:context result) :b-done)))
      ;; 如果真正并行，总耗时应 < 400ms（两个 200ms 串行需要 400ms+）
      ;; 允许一些调度开销，检查 < 380ms
      (is (< elapsed 380)
          (str "并行执行耗时应 < 380ms，实际: " elapsed "ms")))))

(deftest runtime-multiple-initial-events-test
  (testing "多个初始事件"
    (let [process-spec (-> (builder/builder :multi-init)
                          (builder/add-step
                            {:id :collector
                             :required-inputs [:a :b]
                             :on-activate (fn [inputs _state ctx]
                                            {:context (ctx/set-var ctx :got
                                                        (str (:a inputs) "+" (:b inputs)))})})
                          (builder/on-event :event-a :collector :a)
                          (builder/on-event :event-b :collector :b)
                          (builder/set-initial-event :event-a "hello")
                          (builder/set-initial-event :event-b "world")
                          (builder/build))
          result (runtime/run-process process-spec {:timeout-ms test-timeout})]
      (is (= :completed (:status result)))
      (is (= "hello+world" (ctx/get-var (:context result) :got))))))

;; ============================================================
;; Phase 5: Rehydration（状态恢复）
;; ============================================================

(deftest runtime-resume-preserves-all-step-states-test
  (testing "resume 后非暂停 step 的 state 保持不变"
    (let [process-spec (-> (builder/builder :resume-state-test)
                          (builder/add-step
                            {:id :counter
                             :init (fn [_] {:count 0})
                             :on-activate (fn [inputs state _ctx]
                                            (let [n (inc (:count state))]
                                              (if (= n 1)
                                                {:events [{:name :first-count :data n}]
                                                 :state {:count n}}
                                                {:events [{:name :second-count :data n}]
                                                 :state {:count n}})))})
                          (builder/add-step
                            {:id :gate
                             :on-activate (fn [inputs _state _ctx]
                                            {:pause {:reason "等待审批"
                                                     :state {:value (:input inputs)}}})
                             :on-resume (fn [data state _ctx]
                                          {:events [{:name :resume-trigger :data "go-again"}]
                                           :state nil})})
                          (builder/add-step
                            {:id :final
                             :on-activate (fn [inputs _state ctx]
                                            {:context (ctx/set-var ctx :final-count (:input inputs))})})
                          (builder/on-event :start :counter :input)
                          (builder/on-event :first-count :gate :input)
                          (builder/on-event :resume-trigger :counter :input)
                          (builder/on-event :second-count :final :input)
                          (builder/set-initial-event :start "go")
                          (builder/build))
          paused (runtime/run-process process-spec {:timeout-ms test-timeout})]
      (is (= :paused (:status paused)))
      (is (= :gate (:paused-step paused)))
      ;; resume → counter 应该从 {:count 1} 继续，计为 2
      (let [result (runtime/run-resume paused "approved")]
        (is (= :completed (:status result)))
        (is (= 2 (ctx/get-var (:context result) :final-count)))))))

(deftest runtime-snapshot-is-pure-data-test
  (testing "create-process-snapshot 返回纯数据 map"
    (let [process-spec (-> (builder/builder :snapshot-test)
                          (builder/add-step
                            {:id :worker
                             :init (fn [_] {:items []})
                             :on-activate (fn [inputs state _ctx]
                                            {:pause {:reason "需要审批"
                                                     :state (update state :items conj (:input inputs))}})
                             :on-resume (fn [data state _ctx]
                                          {:events []
                                           :state state})})
                          (builder/on-event :start :worker :input)
                          (builder/set-initial-event :start "item-1")
                          (builder/build))
          paused (runtime/run-process process-spec {:timeout-ms test-timeout})
          snapshot (:snapshot paused)]
      ;; snapshot 存在且是 map
      (is (map? snapshot))
      ;; 包含必要字段
      (is (= :snapshot-test (:process-name snapshot)))
      (is (= :paused (:status snapshot)))
      (is (= :worker (:paused-step snapshot)))
      (is (= "需要审批" (:pause-reason snapshot)))
      (is (map? (:context snapshot)))
      (is (map? (:step-states snapshot)))
      (is (number? (:created-at snapshot)))
      ;; step-states 包含 worker 的状态
      (is (= {:items ["item-1"]}
             (get-in snapshot [:step-states :worker :state])))
      ;; 验证纯数据（无 atom/channel）
      (is (not (instance? clojure.lang.Atom (:context snapshot))))
      (is (not (instance? clojure.lang.Atom (get-in snapshot [:step-states :worker])))))))

(deftest runtime-restore-from-snapshot-test
  (testing "从纯数据快照恢复 process 执行"
    (let [process-spec (-> (builder/builder :restore-test)
                          (builder/add-step
                            {:id :accumulator
                             :init (fn [_] {:sum 0})
                             :on-activate (fn [inputs state _ctx]
                                            (let [v (if (number? (:input inputs))
                                                      (:input inputs) 0)
                                                  new-sum (+ (:sum state) v)]
                                              (if (< new-sum 10)
                                                {:events [{:name :need-more :data new-sum}]
                                                 :state {:sum new-sum}}
                                                {:events [{:name :enough :data new-sum}]
                                                 :state {:sum new-sum}})))})
                          (builder/add-step
                            {:id :gate
                             :on-activate (fn [inputs _state _ctx]
                                            {:pause {:reason "确认继续"
                                                     :state {:pending (:input inputs)}}})
                             :on-resume (fn [data state _ctx]
                                          ;; resume 时传递 pending 值回 accumulator
                                          {:events [{:name :add-more :data (:pending state)}]
                                           :state nil})})
                          (builder/add-step
                            {:id :result
                             :on-activate (fn [inputs _state ctx]
                                            {:context (ctx/set-var ctx :total (:input inputs))})})
                          (builder/on-event :start :accumulator :input)
                          (builder/on-event :need-more :gate :input)
                          (builder/on-event :add-more :accumulator :input)
                          (builder/on-event :enough :result :input)
                          (builder/set-initial-event :start 5)
                          (builder/build))
          ;; 第一次运行 → accumulator sum=5 < 10, 发 :need-more, gate 暂停
          paused (runtime/run-process process-spec {:timeout-ms test-timeout})
          snapshot (:snapshot paused)]

      ;; 验证快照状态
      (is (= :paused (:status paused)))
      (is (= 5 (get-in snapshot [:step-states :accumulator :state :sum])))

      ;; 从快照恢复（模拟跨进程）
      ;; gate 的 pending=5, resume 发 :add-more 5 给 accumulator
      ;; accumulator: sum=5+5=10 >= 10, 发 :enough 10 给 result
      (let [result (runtime/run-restore snapshot process-spec "go")]
        (is (= :completed (:status result)))
        (is (= 10 (ctx/get-var (:context result) :total)))))))

(deftest runtime-cycle-state-preserved-on-resume-test
  (testing "循环场景下 resume 后 step 计数器不重置"
    (let [process-spec (-> (builder/builder :cycle-resume-test)
                          (builder/add-step
                            {:id :looper
                             :init (fn [_] {:iteration 0})
                             :on-activate (fn [inputs state _ctx]
                                            (let [n (inc (:iteration state))]
                                              (cond
                                                ;; 第 2 次暂停等待审批
                                                (= n 2)
                                                {:pause {:reason (str "暂停 #" n)
                                                         :state {:iteration n}}}
                                                ;; 达到 4 次 → 完成
                                                (>= n 4)
                                                {:events [{:name :done :data n}]
                                                 :state {:iteration n}}
                                                ;; 继续循环
                                                :else
                                                {:events [{:name :continue-loop :data n}]
                                                 :state {:iteration n}})))
                             :on-resume (fn [data state _ctx]
                                          ;; resume 后继续循环
                                          {:events [{:name :continue-loop :data (:iteration state)}]
                                           :state state})})
                          (builder/add-step
                            {:id :final
                             :on-activate (fn [inputs _state ctx]
                                            {:context (ctx/set-var ctx :iterations (:input inputs))})})
                          (builder/on-event :start :looper :input)
                          (builder/on-event :continue-loop :looper :input)
                          (builder/on-event :done :final :input)
                          (builder/set-initial-event :start "go")
                          (builder/build))
          ;; 第一次：iteration 0→1 (continue-loop), 1→2 (暂停)
          paused1 (runtime/run-process process-spec {:timeout-ms test-timeout})]
      (is (= :paused (:status paused1)))
      (is (= "暂停 #2" (:pause-reason paused1)))
      ;; resume → looper 从 iteration=2 继续
      ;; on-resume 发 continue-loop, then on-activate: 2→3 (continue-loop), 3→4 (done)
      (let [result (runtime/run-resume paused1 "continue")]
        (is (= :completed (:status result)))
        (is (= 4 (ctx/get-var (:context result) :iterations)))))))

;; ============================================================
;; on-terminate 生命周期测试
;; ============================================================

(deftest step-terminate-test
  (testing "terminate-step 调用 on-terminate"
    (let [terminated (atom false)
          step-spec {:id :s
                     :on-activate (fn [_ _ _] {})
                     :on-terminate (fn [state _ctx]
                                     (reset! terminated state))}
          state (step/init-step step-spec)
          state (assoc state :state {:resource "open"})]
      (step/terminate-step state (ctx/create))
      (is (= {:resource "open"} @terminated))))

  (testing "terminate-step 无 on-terminate 不报错"
    (let [step-spec {:id :s :on-activate (fn [_ _ _] {})}
          state (step/init-step step-spec)]
      (step/terminate-step state (ctx/create))
      (is true)))

  (testing "terminate-step 异常被捕获"
    (let [step-spec {:id :s
                     :on-activate (fn [_ _ _] {})
                     :on-terminate (fn [_ _] (throw (Exception. "cleanup error")))}
          state (step/init-step step-spec)]
      (step/terminate-step state (ctx/create))
      (is true "不应抛出异常"))))

(deftest runtime-on-terminate-called-on-completion-test
  (testing "process 完成时调用所有 step 的 on-terminate"
    (let [terminated-steps (atom [])
          process-spec (-> (builder/builder :terminate-test)
                          (builder/add-step
                            {:id :step-a
                             :init (fn [_] {:name "a"})
                             :on-activate (fn [inputs state _ctx]
                                            {:events [{:name :a-done :data "from-a"}]
                                             :state state})
                             :on-terminate (fn [state _ctx]
                                             (swap! terminated-steps conj [:a state]))})
                          (builder/add-step
                            {:id :step-b
                             :init (fn [_] {:name "b"})
                             :on-activate (fn [inputs state _ctx]
                                            {:state state})
                             :on-terminate (fn [state _ctx]
                                             (swap! terminated-steps conj [:b state]))})
                          (builder/on-event :start :step-a :input)
                          (builder/on-event :a-done :step-b :input)
                          (builder/set-initial-event :start "go")
                          (builder/build))
          result (runtime/run-process process-spec {:timeout-ms test-timeout})]
      (is (= :completed (:status result)))
      (is (= 2 (count @terminated-steps)))
      (is (some #(= :a (first %)) @terminated-steps))
      (is (some #(= :b (first %)) @terminated-steps)))))

(deftest runtime-on-terminate-called-on-failure-test
  (testing "process 失败时也调用 on-terminate"
    (let [terminated (atom false)
          process-spec (-> (builder/builder :fail-terminate)
                          (builder/add-step
                            {:id :failing
                             :on-activate (fn [_ _ _]
                                            {:error {:reason "boom"}})
                             :on-terminate (fn [_ _]
                                             (reset! terminated true))})
                          (builder/on-event :start :failing :input)
                          (builder/set-initial-event :start "go")
                          (builder/build))
          result (runtime/run-process process-spec {:timeout-ms test-timeout})]
      (is (= :failed (:status result)))
      (is (true? @terminated)))))

;; ============================================================
;; on-quiescent 静止点回调测试
;; ============================================================

(deftest runtime-on-quiescent-fan-out-test
  (testing "fan-out 场景：并发 step 全部完成后触发 on-quiescent"
    (let [quiescent-snapshots (atom [])
          process-spec (-> (builder/builder :fan-out-quiescent)
                          (builder/add-step
                            {:id :splitter
                             :init (fn [_] {:name "splitter"})
                             :on-activate (fn [inputs state _ctx]
                                            {:events [{:name :branch-a :data "a"}
                                                      {:name :branch-b :data "b"}]
                                             :state state})})
                          (builder/add-step
                            {:id :worker-a
                             :init (fn [_] {:name "a"})
                             :on-activate (fn [inputs state ctx]
                                            (Thread/sleep 50)
                                            {:events [{:name :done-a :data "result-a"}]
                                             :state (assoc state :result "a-done")})})
                          (builder/add-step
                            {:id :worker-b
                             :init (fn [_] {:name "b"})
                             :on-activate (fn [inputs state ctx]
                                            (Thread/sleep 80)
                                            {:events [{:name :done-b :data "result-b"}]
                                             :state (assoc state :result "b-done")})})
                          (builder/add-step
                            {:id :collector
                             :required-inputs [:a :b]
                             :on-activate (fn [inputs state ctx]
                                            {:state {:collected true}
                                             :context (ctx/set-var ctx :done true)})})
                          (builder/on-event :start :splitter :input)
                          (builder/on-event :branch-a :worker-a :input)
                          (builder/on-event :branch-b :worker-b :input)
                          (builder/on-event :done-a :collector :a)
                          (builder/on-event :done-b :collector :b)
                          (builder/set-initial-event :start "go")
                          (builder/build))
          result (runtime/run-process process-spec
                   {:timeout-ms test-timeout
                    :on-quiescent (fn [snapshot]
                                    (swap! quiescent-snapshots conj snapshot))})]
      (is (= :completed (:status result)))
      (is (true? (ctx/get-var (:context result) :done)))
      ;; on-quiescent 应该至少触发过（splitter完成后、worker-a+b完成后）
      (is (pos? (count @quiescent-snapshots)))
      ;; 每个 snapshot 都有正确的结构
      (doseq [snap @quiescent-snapshots]
        (is (= :quiescent (:reason snap)))
        (is (= :running (:status snap)))
        (is (map? (:step-states snap)))
        (is (some? (:context snap)))))))

(deftest runtime-on-quiescent-linear-test
  (testing "线性 process：中间步骤完成时触发 on-quiescent"
    (let [quiescent-count (atom 0)
          process-spec (-> (builder/builder :linear-quiescent)
                          (builder/add-step
                            {:id :step-1
                             :init (fn [_] {:n 1})
                             :on-activate (fn [_ state _]
                                            {:events [{:name :to-2 :data (:n state)}]
                                             :state state})})
                          (builder/add-step
                            {:id :step-2
                             :init (fn [_] {:n 2})
                             :on-activate (fn [_ state _]
                                            {:events [{:name :to-3 :data (:n state)}]
                                             :state state})})
                          (builder/add-step
                            {:id :step-3
                             :init (fn [_] {:n 3})
                             :on-activate (fn [_ state ctx]
                                            {:state state
                                             :context (ctx/set-var ctx :final 3)})})
                          (builder/on-event :start :step-1 :input)
                          (builder/on-event :to-2 :step-2 :input)
                          (builder/on-event :to-3 :step-3 :input)
                          (builder/set-initial-event :start "go")
                          (builder/build))
          result (runtime/run-process process-spec
                   {:timeout-ms test-timeout
                    :on-quiescent (fn [_snapshot]
                                    (swap! quiescent-count inc))})]
      (is (= :completed (:status result)))
      (is (= 3 (ctx/get-var (:context result) :final)))
      ;; step-1 和 step-2 完成后各触发一次（step-3 是最后一步，不触发）
      (is (= 2 @quiescent-count)))))

(deftest runtime-on-quiescent-not-called-without-option-test
  (testing "未设置 on-quiescent 时不影响正常执行"
    (let [process-spec (-> (builder/builder :no-quiescent)
                          (builder/add-step
                            {:id :only
                             :on-activate (fn [_ _ ctx]
                                            {:context (ctx/set-var ctx :ok true)})})
                          (builder/on-event :start :only :input)
                          (builder/set-initial-event :start "go")
                          (builder/build))
          result (runtime/run-process process-spec {:timeout-ms test-timeout})]
      (is (= :completed (:status result)))
      (is (true? (ctx/get-var (:context result) :ok))))))

(deftest runtime-on-quiescent-on-pause-test
  (testing "暂停时触发 on-quiescent，reason 为 :paused"
    (let [quiescent-snapshots (atom [])
          process-spec (-> (builder/builder :pause-quiescent)
                          (builder/add-step
                            {:id :step-a
                             :init (fn [_] {:count 0})
                             :on-activate (fn [inputs state ctx]
                                            {:events [{:name :to-b :data (:input inputs)}]
                                             :state (update state :count inc)})})
                          (builder/add-step
                            {:id :step-b
                             :init (fn [_] {:processed false})
                             :on-activate (fn [inputs state _ctx]
                                            {:pause {:reason "需要审批"
                                                     :state (assoc state :processed true)}})
                             :on-resume (fn [data state ctx]
                                          {:events [{:name :done :data data}]
                                           :state state})})
                          (builder/add-step
                            {:id :step-c
                             :on-activate (fn [inputs state ctx]
                                            {:context (ctx/set-var ctx :final (:input inputs))})})
                          (builder/on-event :start :step-a :input)
                          (builder/on-event :to-b :step-b :input)
                          (builder/on-event :done :step-c :input)
                          (builder/set-initial-event :start "hello")
                          (builder/build))
          result (runtime/run-process process-spec
                   {:timeout-ms test-timeout
                    :on-quiescent (fn [snapshot]
                                    (swap! quiescent-snapshots conj snapshot))})]
      (is (= :paused (:status result)))
      ;; on-quiescent 被触发：step-a 完成时 (:quiescent) + 暂停时 (:paused)
      (is (>= (count @quiescent-snapshots) 1))
      ;; 最后一个 snapshot 应该是 pause 触发的
      (let [pause-snap (last @quiescent-snapshots)]
        (is (= :paused (:reason pause-snap)))
        (is (= :paused (:status pause-snap)))
        (is (= :step-b (:paused-step pause-snap)))
        (is (= "需要审批" (:pause-reason pause-snap)))
        (is (map? (:step-states pause-snap)))
        ;; step-a 的状态应该已更新
        (is (= 1 (get-in pause-snap [:step-states :step-a :state :count])))
        ;; step-b 的状态应该已更新
        (is (true? (get-in pause-snap [:step-states :step-b :state :processed]))))
      ;; quiescent 类型的 snapshot
      (let [quiescent-snaps (filter #(= :quiescent (:reason %)) @quiescent-snapshots)]
        (when (seq quiescent-snaps)
          (doseq [snap quiescent-snaps]
            (is (= :running (:status snap)))))))))
