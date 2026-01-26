(ns im.ttalk.agent.core.graph.graph-test
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.core.graph.api :as g]))

;;; ============================================================
;;; State 测试
;;; ============================================================

(deftest state-basic-test
  (testing "创建和基本操作"
    (let [s (g/state {:a 1 :b 2})]
      (is (= 1 (g/get-val s :a)))
      (is (= 2 (g/get-val s :b)))
      (is (nil? (g/get-val s :c)))
      (is (= "default" (g/get-val s :c "default")))))

  (testing "设置值"
    (let [s1 (g/state {:a 1})
          s2 (g/set-val s1 :b 2)]
      (is (= 1 (g/get-val s2 :a)))
      (is (= 2 (g/get-val s2 :b)))
      ;; 不可变性
      (is (nil? (g/get-val s1 :b)))))

  (testing "批量设置"
    (let [s1 (g/state {})
          s2 (g/set-many s1 {:a 1 :b 2 :c 3})]
      (is (= 1 (g/get-val s2 :a)))
      (is (= 2 (g/get-val s2 :b)))
      (is (= 3 (g/get-val s2 :c)))))

  (testing "用户上下文"
    (let [s1 (g/state {:data "test"})
          s2 (g/set-context s1 {:user-id "u123"})]
      (is (= "u123" (g/get-context s2 :user-id)))
      (is (= {:user-id "u123"} (g/get-context s2))))))

;;; ============================================================
;;; Node 测试
;;; ============================================================

(deftest node-result-test
  (testing "ok 结果"
    (let [result (g/ok {:data "test"})]
      (is (contains? result :ok))
      (is (= {:data "test"} (:ok result)))))

  (testing "error 结果"
    (let [result (g/error "something wrong")]
      (is (contains? result :error))
      (is (= "something wrong" (:error result)))))

  (testing "interrupt 结果"
    (let [result (g/interrupt "need approval" {:pending true})]
      (is (contains? result :interrupt))
      (is (= "need approval" (get-in result [:interrupt :reason])))
      (is (= {:pending true} (get-in result [:interrupt :state])))))

  (testing "command 结果"
    (let [result (g/command {:update {:count 1} :goto :next-step})]
      (is (contains? result :command))
      (is (= {:count 1} (get-in result [:command :update])))
      (is (= :next-step (get-in result [:command :goto]))))))

;;; ============================================================
;;; Reducer 测试
;;; ============================================================

(deftest reducer-test
  (testing "last-write-wins"
    (is (= "new" (g/last-write-wins "old" "new"))))

  (testing "append"
    (is (= [1 2 3 4] (g/append [1 2] [3 4])))
    (is (= [1 2 3] (g/append [1 2] 3)))
    (is (= [1] (g/append nil [1]))))

  (testing "deep-merge"
    (is (= {:a {:b 2 :c 3}}
           (g/deep-merge {:a {:b 1 :c 3}} {:a {:b 2}}))))

  (testing "increment"
    (is (= 15 (g/increment 10 5)))
    (is (= 5 (g/increment nil 5))))

  (testing "apply-delta"
    (let [state {:count 10 :items [1 2]}
          delta {:count 5 :items [3]}
          reducers {:count g/increment :items g/append}
          result (g/apply-delta state delta reducers)]
      (is (= 15 (:count result)))
      (is (= [1 2 3] (:items result))))))

;;; ============================================================
;;; Dispatch 测试
;;; ============================================================

(deftest dispatch-test
  (testing "创建 dispatch"
    (let [d (g/dispatch :process {:item "data"})]
      (is (true? (:__dispatch__ d)))
      (is (= :process (:node d)))
      (is (= {:item "data"} (:input d)))
      (is (string? (:id d)))))

  (testing "fan-out"
    (let [items ["a" "b" "c"]
          dispatches (g/fan-out :process items (fn [x] {:item x}))]
      (is (= 3 (count dispatches)))
      (is (every? :__dispatch__ dispatches))
      (is (= [:process :process :process] (map :node dispatches)))
      (is (= [{:item "a"} {:item "b"} {:item "c"}]
             (map :input dispatches)))))

  (testing "fan-out-indexed"
    (let [items ["x" "y"]
          dispatches (g/fan-out-indexed :worker items
                                        (fn [i x] {:index i :data x}))]
      (is (= 2 (count dispatches)))
      (is (= {:index 0 :data "x"} (:input (first dispatches))))
      (is (= {:index 1 :data "y"} (:input (second dispatches)))))))

;;; ============================================================
;;; Builder 测试
;;; ============================================================

(deftest builder-test
  (testing "构建简单图"
    (let [graph-spec (-> (g/graph :test)
                         (g/add-node :process (fn [s _] (g/ok s)))
                         (g/add-edge g/START :process)
                         (g/add-edge :process g/END)
                         (g/set-entry :process)
                         (g/compile))]
      (is (= :test (:name graph-spec)))
      (is (contains? (:vertices graph-spec) :process))
      (is (= :process (:entry graph-spec)))))

  (testing "线性图"
    (let [graph-spec (g/linear :pipeline
                               [{:id :step1 :handler (fn [s _] (g/ok (assoc s :step1 true)))}
                                {:id :step2 :handler (fn [s _] (g/ok (assoc s :step2 true)))}])]
      (is (= :pipeline (:name graph-spec)))
      (is (contains? (:vertices graph-spec) :step1))
      (is (contains? (:vertices graph-spec) :step2)))))

;;; ============================================================
;;; Executor 测试
;;; ============================================================

(deftest executor-simple-test
  (testing "简单线性执行"
    (let [graph-spec (-> (g/graph :simple)
                         (g/add-node :process
                                     (fn [state _]
                                       (g/ok (assoc state :processed true))))
                         (g/add-edge g/START :process)
                         (g/add-edge :process g/END)
                         (g/set-entry :process)
                         (g/compile))
          result (g/run graph-spec (g/state {:input "data"}))]
      (is (= g/COMPLETED (:status result)))
      (is (true? (get-in result [:state :processed])))
      (is (= "data" (get-in result [:state :input]))))))

(deftest executor-conditional-test
  (testing "条件路由"
    (let [graph-spec (-> (g/graph :conditional)
                         (g/add-node :check
                                     (fn [state _]
                                       (g/ok state)))
                         (g/add-node :path-a
                                     (fn [state _]
                                       (g/ok (assoc state :path :a))))
                         (g/add-node :path-b
                                     (fn [state _]
                                       (g/ok (assoc state :path :b))))
                         (g/add-edge g/START :check)
                         (g/add-conditional-edge :check
                                                 (fn [state]
                                                   (if (:go-a state) :path-a :path-b)))
                         (g/add-edge :path-a g/END)
                         (g/add-edge :path-b g/END)
                         (g/set-entry :check)
                         (g/compile))
          ;; 测试路径 A
          result-a (g/run graph-spec (g/state {:go-a true}))
          ;; 测试路径 B
          result-b (g/run graph-spec (g/state {:go-a false}))]
      (is (= :a (get-in result-a [:state :path])))
      (is (= :b (get-in result-b [:state :path]))))))

(deftest executor-parallel-test
  (testing "静态并行（fanout）"
    (let [graph-spec (-> (g/graph :parallel)
                         (g/add-node :start
                                     (fn [state _]
                                       (g/ok state)))
                         (g/add-node :worker-a
                                     (fn [state _]
                                       (g/ok (update state :results conj :a))))
                         (g/add-node :worker-b
                                     (fn [state _]
                                       (g/ok (update state :results conj :b))))
                         (g/add-node :aggregate
                                     (fn [state _]
                                       (g/ok (assoc state :done true))))
                         (g/add-edge g/START :start)
                         (g/add-edge :start [:worker-a :worker-b])
                         (g/add-edge :worker-a :aggregate)
                         (g/add-edge :worker-b :aggregate)
                         (g/add-edge :aggregate g/END)
                         (g/set-entry :start)
                         (g/compile))
          result (g/run graph-spec
                        (g/state {:results []})
                        :field-reducers {:results g/append})]
      (is (= g/COMPLETED (:status result)))
      (is (true? (get-in result [:state :done])))
      ;; 两个 worker 都应该执行
      (is (= 2 (count (get-in result [:state :results]))))
      (is (contains? (set (get-in result [:state :results])) :a))
      (is (contains? (set (get-in result [:state :results])) :b)))))

(deftest executor-dynamic-parallel-test
  (testing "动态并行（dispatch）"
    (let [graph-spec (-> (g/graph :dynamic-parallel)
                         (g/add-node :generator
                                     (fn [state _]
                                       (g/ok (assoc state :items [1 2 3]))))
                         (g/add-node :processor
                                     (fn [state vertex-input]
                                       (let [item (:item vertex-input)
                                             result (* item 10)]
                                         (g/ok (update state :results conj result)))))
                         (g/add-node :finalize
                                     (fn [state _]
                                       (g/ok (assoc state :done true))))
                         (g/add-edge g/START :generator)
                         (g/add-conditional-edge :generator
                                                 (fn [state]
                                                   (g/fan-out :processor
                                                              (:items state)
                                                              (fn [item] {:item item}))))
                         (g/add-edge :processor :finalize)
                         (g/add-edge :finalize g/END)
                         (g/set-entry :generator)
                         (g/compile))
          result (g/run graph-spec
                        (g/state {:results []})
                        :field-reducers {:results g/append})]
      (is (= g/COMPLETED (:status result)))
      (is (true? (get-in result [:state :done])))
      ;; 3 个 dispatch 都应该执行
      (is (= #{10 20 30} (set (get-in result [:state :results])))))))

(deftest executor-error-test
  (testing "节点错误"
    (let [graph-spec (-> (g/graph :error-test)
                         (g/add-node :fail
                                     (fn [_ _]
                                       (g/error "intentional error")))
                         (g/add-edge g/START :fail)
                         (g/add-edge :fail g/END)
                         (g/set-entry :fail)
                         (g/compile))
          result (g/run graph-spec (g/state {}))]
      (is (= g/ERROR (:status result)))
      (is (seq (:errors result))))))

(deftest executor-interrupt-test
  (testing "节点中断"
    (let [graph-spec (-> (g/graph :interrupt-test)
                         (g/add-node :pause
                                     (fn [state _]
                                       (g/interrupt "need approval" state)))
                         (g/add-edge g/START :pause)
                         (g/add-edge :pause g/END)
                         (g/set-entry :pause)
                         (g/compile))
          result (g/run graph-spec (g/state {:data "test"}))]
      (is (= g/INTERRUPTED (:status result)))
      (is (seq (:interrupts result))))))

(deftest executor-command-test
  (testing "Command 模式"
    (let [graph-spec (-> (g/graph :command-test)
                         (g/add-node :commander
                                     (fn [_ _]
                                       (g/command {:update {:count 42}
                                                   :goto :target})))
                         (g/add-node :target
                                     (fn [state _]
                                       (g/ok (assoc state :reached true))))
                         (g/add-edge g/START :commander)
                         ;; 注意：这个边不会被使用，因为 command 覆盖了路由
                         (g/add-edge :commander g/END)
                         (g/add-edge :target g/END)
                         (g/set-entry :commander)
                         (g/compile))
          result (g/run graph-spec (g/state {}))]
      (is (= g/COMPLETED (:status result)))
      (is (= 42 (get-in result [:state :count])))
      (is (true? (get-in result [:state :reached]))))))
