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
    (let [step-def {:id :my-step
                    :on-activate (fn [_ _ _] {})}
          state (step/init-step step-def)]
      (is (= step-def (:step-def state)))
      (is (nil? (:state state)))
      (is (= {} (:collected-inputs state)))
      (is (= 0 (:activation-count state)))))

  (testing "初始化有 init 函数的 step"
    (let [step-def {:id :my-step
                    :init (fn [config] {:counter (:start config)})
                    :config {:start 10}
                    :on-activate (fn [_ _ _] {})}
          state (step/init-step step-def)]
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
    (let [step-def {:id :adder
                    :on-activate (fn [inputs state _ctx]
                                   {:events [{:name :result :data (+ (:a inputs) (:b inputs))}]
                                    :state (inc (or state 0))})
                    :required-inputs [:a :b]}
          state (-> (step/init-step step-def)
                    (step/collect-input :a 3)
                    (step/collect-input :b 4))
          {:keys [result step-state]} (step/execute state (ctx/create))]
      (is (= 7 (:data (first (:events result)))))
      (is (= :adder (:source (first (:events result)))))
      (is (= 1 (:state step-state)))
      (is (= {} (:collected-inputs step-state)))
      (is (= 1 (:activation-count step-state)))))

  (testing "执行产出暂停"
    (let [step-def {:id :pauser
                    :on-activate (fn [inputs _state _ctx]
                                   {:pause {:reason "需要审批" :state {:pending inputs}}})}
          state (-> (step/init-step step-def)
                    (step/collect-input :input "data"))
          {:keys [result step-state]} (step/execute state (ctx/create))]
      (is (= "需要审批" (get-in result [:pause :reason])))
      (is (= {:pending {:input "data"}} (:state step-state)))))

  (testing "执行产出错误"
    (let [step-def {:id :failer
                    :on-activate (fn [_ _ _]
                                   {:error {:reason "something broke"}})}
          state (-> (step/init-step step-def)
                    (step/collect-input :input "x"))
          {:keys [result]} (step/execute state (ctx/create))]
      (is (= "something broke" (get-in result [:error :reason])))))

  (testing "执行时异常被捕获"
    (let [step-def {:id :thrower
                    :on-activate (fn [_ _ _]
                                   (throw (Exception. "boom")))}
          state (-> (step/init-step step-def)
                    (step/collect-input :input "x"))
          {:keys [result]} (step/execute state (ctx/create))]
      (is (= "boom" (get-in result [:error :reason]))))))

(deftest step-resume-test
  (testing "恢复暂停的 step"
    (let [step-def {:id :resumable
                    :on-activate (fn [_ _ _]
                                   {:pause {:reason "wait" :state {:waiting true}}})
                    :on-resume (fn [data state _ctx]
                                 {:events [{:name :resumed :data data}]
                                  :state (assoc state :waiting false)})}
          state (-> (step/init-step step-def)
                    (step/collect-input :input "x"))
          {:keys [step-state]} (step/execute state (ctx/create))
          {:keys [result step-state]} (step/resume-step step-state "approved" (ctx/create))]
      (is (= "approved" (:data (first (:events result)))))
      (is (= {:waiting false} (:state step-state)))))

  (testing "不支持 resume 的 step 返回 nil"
    (let [step-def {:id :no-resume
                    :on-activate (fn [_ _ _] {})}
          state (step/init-step step-def)]
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
    (let [process-def (-> (builder/builder :test)
                          (builder/add-step {:id :s1
                                            :on-activate (fn [_ _ _] {})})
                          (builder/on-event :start :s1 :input)
                          (builder/set-initial-event :start)
                          (builder/build))]
      (is (:__process_def__ process-def))
      (is (= :test (:name process-def)))
      (is (contains? (:steps process-def) :s1))
      (is (= 1 (count (:bindings process-def))))))

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
    (let [process-def (-> (builder/builder :linear)
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
          result (runtime/run-process process-def {:timeout-ms test-timeout})]
      (is (= :completed (:status result)))
      (is (= "hello-A-B" (ctx/get-var (:context result) :result))))))

(deftest runtime-fan-out-test
  (testing "Fan-out：一个事件触发多个 step（并行执行）"
    (let [execution-log (atom [])
          process-def (-> (builder/builder :fan-out)
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
          result (runtime/run-process process-def {:timeout-ms test-timeout})]
      (is (= :completed (:status result)))
      (is (= "shared" (ctx/get-var (:context result) :a-got)))
      (is (= "shared" (ctx/get-var (:context result) :b-got)))
      ;; 验证两个 consumer 都执行了
      (is (= 2 (count @execution-log))))))

(deftest runtime-fan-in-test
  (testing "Fan-in：多个输入汇聚到一个 step"
    (let [process-def (-> (builder/builder :fan-in)
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
          result (runtime/run-process process-def {:timeout-ms test-timeout})]
      (is (= :completed (:status result)))
      (is (= "X-A+X-B" (ctx/get-var (:context result) :combined))))))

(deftest runtime-cycle-test
  (testing "循环流程：step 循环直到条件满足"
    (let [process-def (-> (builder/builder :cycle)
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
          result (runtime/run-process process-def {:timeout-ms test-timeout})]
      (is (= :completed (:status result)))
      (is (= 3 (ctx/get-var (:context result) :final-count))))))

(deftest runtime-pause-resume-test
  (testing "暂停和恢复"
    (let [process-def (-> (builder/builder :pausable)
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
          paused (runtime/run-process process-def {:timeout-ms test-timeout})]
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
    (let [process-def (-> (builder/builder :fail-test)
                          (builder/add-step
                            {:id :bad-step
                             :on-activate (fn [_ _ _]
                                            {:error {:reason "oops"}})})
                          (builder/on-event :start :bad-step :input)
                          (builder/set-initial-event :start "go")
                          (builder/build))
          result (runtime/run-process process-def {:timeout-ms test-timeout})]
      (is (= :failed (:status result)))
      (is (= "oops" (get-in result [:error :reason])))))

  (testing "有 error-handler 时路由错误"
    (let [process-def (-> (builder/builder :error-handled)
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
          result (runtime/run-process process-def {:timeout-ms test-timeout})]
      (is (= :completed (:status result)))
      (is (= "oops" (ctx/get-var (:context result) :handled))))))

(deftest runtime-exception-handling-test
  (testing "Step 抛异常被捕获并标记 failed"
    (let [process-def (-> (builder/builder :exception-test)
                          (builder/add-step
                            {:id :thrower
                             :on-activate (fn [_ _ _]
                                            (throw (Exception. "kaboom")))})
                          (builder/on-event :start :thrower :input)
                          (builder/set-initial-event :start "go")
                          (builder/build))
          result (runtime/run-process process-def {:timeout-ms test-timeout})]
      (is (= :failed (:status result)))
      (is (= "kaboom" (get-in result [:error :reason]))))))

(deftest runtime-timeout-test
  (testing "全局超时"
    (let [process-def (-> (builder/builder :slow)
                          (builder/add-step
                            {:id :slow-step
                             :on-activate (fn [_ _ _]
                                            (Thread/sleep 3000)
                                            {:events [{:name :done :data nil}]})})
                          (builder/on-event :start :slow-step :input)
                          (builder/set-initial-event :start nil)
                          (builder/build))
          result (runtime/run-process process-def {:timeout-ms 500})]
      (is (= :failed (:status result)))
      (is (clojure.string/includes? (str (:reason (:error result))) "超时")))))

(deftest runtime-step-state-persistence-test
  (testing "Step 状态在多次激活间持久化"
    (let [process-def (-> (builder/builder :stateful)
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
          result (runtime/run-process process-def {:timeout-ms test-timeout})]
      (is (= :completed (:status result)))
      (is (>= (ctx/get-var (:context result) :total) 10)))))

(deftest runtime-context-passing-test
  (testing "Context 在 step 之间传递"
    (let [process-def (-> (builder/builder :ctx-pass)
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
          result (runtime/run-process process-def {:timeout-ms test-timeout})]
      (is (= :completed (:status result)))
      (is (true? (ctx/get-var (:context result) :a-was-here)))
      (is (true? (ctx/get-var (:context result) :b-saw-a))))))

(deftest runtime-transform-binding-test
  (testing "绑定的 transform 函数生效"
    (let [process-def (-> (builder/builder :transform)
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
          result (runtime/run-process process-def {:timeout-ms test-timeout})]
      (is (= :completed (:status result)))
      (is (= "HELLO" (ctx/get-var (:context result) :result))))))

(deftest runtime-idle-start-test
  (testing "没有初始事件时状态为 completed"
    (let [process-def (-> (builder/builder :idle-test)
                          (builder/add-step
                            {:id :s1
                             :on-activate (fn [_ _ _] {})})
                          (builder/on-event :start :s1 :input)
                          (builder/build))
          result (runtime/run-process process-def {:timeout-ms test-timeout})]
      (is (= :completed (:status result))))))

(deftest runtime-resume-not-paused-test
  (testing "对非 paused 状态调用 resume 抛异常"
    (let [process-def (-> (builder/builder :test)
                          (builder/add-step
                            {:id :s1
                             :on-activate (fn [_ _ _] {})})
                          (builder/on-event :start :s1 :input)
                          (builder/set-initial-event :start nil)
                          (builder/build))
          result (runtime/run-process process-def {:timeout-ms test-timeout})]
      (is (thrown-with-msg? Exception #"只能恢复"
            (runtime/run-resume result "data"))))))

(deftest runtime-parallel-execution-test
  (testing "Fan-out step 实际并行执行"
    (let [start-times (atom {})
          process-def (-> (builder/builder :parallel-test)
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
          result (runtime/run-process process-def {:timeout-ms test-timeout})
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
    (let [process-def (-> (builder/builder :multi-init)
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
          result (runtime/run-process process-def {:timeout-ms test-timeout})]
      (is (= :completed (:status result)))
      (is (= "hello+world" (ctx/get-var (:context result) :got))))))
