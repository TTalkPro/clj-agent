(ns im.ttalk.agent.core.kernel.process.snapshot-manager-test
  "IProcessSnapshotManager 协议与 Runtime 集成测试

   使用 mock checkpointer 验证：
   - 协议谓词
   - 三种 checkpoint-policy 的保存时机
   - 保存的 snapshot 数据格式"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.core.kernel.process.snapshot-manager :as sm]
            [im.ttalk.agent.core.kernel.process.builder :as builder]
            [im.ttalk.agent.core.kernel.process.runtime :as runtime]
            [im.ttalk.agent.core.kernel.context :as ctx]))

(def ^:private test-timeout 5000)

;;; ============================================================
;;; Mock Checkpointer
;;; ============================================================

(defrecord MockCheckpointer [saves-atom checkpoints-atom]
  sm/IProcessSnapshotManager

  (save-checkpoint [_ thread-id snapshot metadata]
    (let [id (str (java.util.UUID/randomUUID))
          record {:id id :thread-id thread-id :snapshot snapshot :metadata metadata}]
      (swap! saves-atom conj record)
      (swap! checkpoints-atom assoc id record)
      id))

  (load-checkpoint [_ thread-id checkpoint-id]
    (when-let [record (get @checkpoints-atom checkpoint-id)]
      (when (= thread-id (:thread-id record))
        {:snapshot (:snapshot record)
         :metadata (:metadata record)
         :id checkpoint-id})))

  (load-latest [_ thread-id]
    (let [matching (->> (vals @checkpoints-atom)
                        (filter #(= thread-id (:thread-id %)))
                        (sort-by #(get-in % [:metadata :created-at]) >))]
      (when-let [latest (first matching)]
        {:snapshot (:snapshot latest)
         :metadata (:metadata latest)
         :id (:id latest)})))

  (list-checkpoints [_ thread-id opts]
    (let [limit (or (:limit opts) 100)]
      (->> (vals @checkpoints-atom)
           (filter #(= thread-id (:thread-id %)))
           (sort-by #(get-in % [:metadata :created-at]) >)
           (take limit)
           (mapv (fn [r] {:id (:id r) :metadata (:metadata r)})))))

  (go-back [_ _thread-id _steps] nil)
  (go-forward [_ _thread-id _steps] nil)
  (goto-checkpoint [_ _thread-id _checkpoint-id] nil)
  (create-branch [_ _thread-id _checkpoint-id _branch-name] nil)
  (list-branches [_ _thread-id] [])
  (switch-branch [_ _thread-id _branch-id] nil))

(defn- create-mock-checkpointer []
  (->MockCheckpointer (atom []) (atom {})))

(defn- get-saves [checkpointer]
  @(:saves-atom checkpointer))

;;; ============================================================
;;; 协议谓词测试
;;; ============================================================

(deftest process-snapshot-manager-predicate-test
  (testing "MockCheckpointer 满足 process-snapshot-manager?"
    (let [cp (create-mock-checkpointer)]
      (is (sm/process-snapshot-manager? cp))))

  (testing "非实现返回 false"
    (is (not (sm/process-snapshot-manager? nil)))
    (is (not (sm/process-snapshot-manager? {})))
    (is (not (sm/process-snapshot-manager? "string")))))

;;; ============================================================
;;; checkpoint-policy :on-pause-only 测试
;;; ============================================================

(deftest checkpoint-on-pause-only-completed-test
  (testing ":on-pause-only 策略：完成时保存一次"
    (let [cp (create-mock-checkpointer)
          process-spec (-> (builder/builder :cp-test)
                          (builder/add-step
                            {:id :step-a
                             :on-activate (fn [inputs _state ctx]
                                            {:context (ctx/set-var ctx :done true)})})
                          (builder/on-event :start :step-a :input)
                          (builder/set-initial-event :start "go")
                          (builder/build))
          result (runtime/run-process process-spec
                   {:timeout-ms test-timeout
                    :checkpointer cp
                    :thread-id "test-thread-1"
                    :checkpoint-policy :on-pause-only})]
      (is (= :completed (:status result)))
      ;; 完成时应保存一次
      (let [saves (get-saves cp)]
        (is (= 1 (count saves)))
        (let [save (first saves)]
          (is (= "test-thread-1" (:thread-id save)))
          (is (= :completed (get-in save [:snapshot :status])))
          (is (= :cp-test (get-in save [:snapshot :process-name])))
          (is (= :completed (get-in save [:metadata :reason]))))))))

(deftest checkpoint-on-pause-only-paused-test
  (testing ":on-pause-only 策略：暂停时保存一次"
    (let [cp (create-mock-checkpointer)
          process-spec (-> (builder/builder :cp-pause-test)
                          (builder/add-step
                            {:id :gate
                             :on-activate (fn [_ _ _]
                                            {:pause {:reason "等待审批"
                                                     :state {:pending true}}})
                             :on-resume (fn [_ _ _] {:events []})})
                          (builder/on-event :start :gate :input)
                          (builder/set-initial-event :start "go")
                          (builder/build))
          result (runtime/run-process process-spec
                   {:timeout-ms test-timeout
                    :checkpointer cp
                    :thread-id "test-thread-2"
                    :checkpoint-policy :on-pause-only})]
      (is (= :paused (:status result)))
      (let [saves (get-saves cp)]
        (is (= 1 (count saves)))
        (let [save (first saves)]
          (is (= "test-thread-2" (:thread-id save)))
          (is (= :paused (get-in save [:snapshot :status])))
          (is (= :gate (get-in save [:snapshot :paused-step])))
          (is (= "等待审批" (get-in save [:snapshot :pause-reason])))
          (is (= :paused (get-in save [:metadata :reason])))
          (is (= :gate (get-in save [:metadata :step]))))))))

(deftest checkpoint-on-pause-only-linear-test
  (testing ":on-pause-only 策略：多步线性流程仅完成时保存"
    (let [cp (create-mock-checkpointer)
          process-spec (-> (builder/builder :linear-cp)
                          (builder/add-step
                            {:id :step-a
                             :on-activate (fn [_ _ _]
                                            {:events [{:name :next :data "a"}]})})
                          (builder/add-step
                            {:id :step-b
                             :on-activate (fn [_ _ _]
                                            {:events [{:name :done :data "b"}]})})
                          (builder/add-step
                            {:id :step-c
                             :on-activate (fn [_ _ ctx]
                                            {:context (ctx/set-var ctx :result "c")})})
                          (builder/on-event :start :step-a :input)
                          (builder/on-event :next :step-b :input)
                          (builder/on-event :done :step-c :input)
                          (builder/set-initial-event :start "go")
                          (builder/build))
          result (runtime/run-process process-spec
                   {:timeout-ms test-timeout
                    :checkpointer cp
                    :thread-id "test-thread-3"
                    :checkpoint-policy :on-pause-only})]
      (is (= :completed (:status result)))
      ;; 仅完成时保存一次，中间步骤不保存
      (is (= 1 (count (get-saves cp)))))))

;;; ============================================================
;;; checkpoint-policy :every-step 测试
;;; ============================================================

(deftest checkpoint-every-step-test
  (testing ":every-step 策略：产出事件的 step 完成后保存"
    (let [cp (create-mock-checkpointer)
          process-spec (-> (builder/builder :every-step-test)
                          (builder/add-step
                            {:id :step-a
                             :on-activate (fn [_ _ _]
                                            {:events [{:name :to-b :data "a"}]})})
                          (builder/add-step
                            {:id :step-b
                             :on-activate (fn [_ _ _]
                                            {:events [{:name :to-c :data "b"}]})})
                          (builder/add-step
                            {:id :step-c
                             :on-activate (fn [_ _ ctx]
                                            {:context (ctx/set-var ctx :final true)})})
                          (builder/on-event :start :step-a :input)
                          (builder/on-event :to-b :step-b :input)
                          (builder/on-event :to-c :step-c :input)
                          (builder/set-initial-event :start "go")
                          (builder/build))
          result (runtime/run-process process-spec
                   {:timeout-ms test-timeout
                    :checkpointer cp
                    :thread-id "test-thread-4"
                    :checkpoint-policy :every-step})]
      (is (= :completed (:status result)))
      ;; step-a, step-b 产出事件触发 on-step-checkpoint
      ;; step-c 无事件产出（:else 分支），不触发 on-step-checkpoint
      ;; + 1 次 completion = 3 次保存
      (let [saves (get-saves cp)
            step-saves (filter #(= :step-done (get-in % [:metadata :reason])) saves)
            completion-saves (filter #(= :completed (get-in % [:metadata :reason])) saves)]
        (is (= 2 (count step-saves)))
        (is (= 1 (count completion-saves)))
        ;; step-done saves 有 :step 信息
        (doseq [s step-saves]
          (is (some? (get-in s [:metadata :step]))))
        ;; 所有保存的 thread-id 一致
        (doseq [s saves]
          (is (= "test-thread-4" (:thread-id s))))))))

(deftest checkpoint-every-step-snapshot-format-test
  (testing ":every-step 策略：snapshot 包含正确的 step-states"
    (let [cp (create-mock-checkpointer)
          process-spec (-> (builder/builder :format-test)
                          (builder/add-step
                            {:id :counter
                             :init (fn [_] {:n 0})
                             :on-activate (fn [_ state _]
                                            {:state {:n (inc (:n state))}
                                             :events [{:name :done :data nil}]})})
                          (builder/add-step
                            {:id :sink
                             :on-activate (fn [_ _ ctx]
                                            {:context (ctx/set-var ctx :ok true)})})
                          (builder/on-event :start :counter :input)
                          (builder/on-event :done :sink :input)
                          (builder/set-initial-event :start nil)
                          (builder/build))
          _result (runtime/run-process process-spec
                    {:timeout-ms test-timeout
                     :checkpointer cp
                     :thread-id "test-format"
                     :checkpoint-policy :every-step})]
      ;; 检查最后一次 step-done snapshot 的格式
      (let [saves (get-saves cp)
            last-step-save (last (filter #(= :step-done (get-in % [:metadata :reason])) saves))
            snapshot (:snapshot last-step-save)]
        (is (map? snapshot))
        (is (= :format-test (:process-name snapshot)))
        (is (= :running (:status snapshot)))
        (is (map? (:step-states snapshot)))
        (is (map? (:context snapshot)))
        (is (number? (:created-at snapshot)))))))

;;; ============================================================
;;; checkpoint-policy :on-quiescent 测试
;;; ============================================================

(deftest checkpoint-on-quiescent-test
  (testing ":on-quiescent 策略：静止点和完成时保存"
    (let [cp (create-mock-checkpointer)
          process-spec (-> (builder/builder :quiescent-cp-test)
                          (builder/add-step
                            {:id :step-a
                             :on-activate (fn [_ _ _]
                                            {:events [{:name :to-b :data "a"}]})})
                          (builder/add-step
                            {:id :step-b
                             :on-activate (fn [_ _ ctx]
                                            {:context (ctx/set-var ctx :done true)})})
                          (builder/on-event :start :step-a :input)
                          (builder/on-event :to-b :step-b :input)
                          (builder/set-initial-event :start "go")
                          (builder/build))
          ;; on-quiescent 回调必须提供，否则静止点检测机制不会初始化
          result (runtime/run-process process-spec
                   {:timeout-ms test-timeout
                    :checkpointer cp
                    :thread-id "test-thread-5"
                    :checkpoint-policy :on-quiescent
                    :on-quiescent (fn [_] nil)})]
      (is (= :completed (:status result)))
      ;; 至少有 quiescent 保存和 completed 保存
      (let [saves (get-saves cp)
            quiescent-saves (filter #(= :quiescent (get-in % [:metadata :reason])) saves)
            completion-saves (filter #(= :completed (get-in % [:metadata :reason])) saves)]
        (is (pos? (count quiescent-saves)))
        (is (= 1 (count completion-saves)))))))

(deftest checkpoint-on-quiescent-pause-test
  (testing ":on-quiescent 策略：暂停时保存"
    (let [cp (create-mock-checkpointer)
          process-spec (-> (builder/builder :quiescent-pause-cp)
                          (builder/add-step
                            {:id :worker
                             :on-activate (fn [_ _ _]
                                            {:events [{:name :to-gate :data "x"}]})})
                          (builder/add-step
                            {:id :gate
                             :on-activate (fn [_ _ _]
                                            {:pause {:reason "审批" :state {:ok true}}})
                             :on-resume (fn [_ _ _] {:events []})})
                          (builder/on-event :start :worker :input)
                          (builder/on-event :to-gate :gate :input)
                          (builder/set-initial-event :start "go")
                          (builder/build))
          result (runtime/run-process process-spec
                   {:timeout-ms test-timeout
                    :checkpointer cp
                    :thread-id "test-thread-6"
                    :checkpoint-policy :on-quiescent
                    :on-quiescent (fn [_] nil)})]
      (is (= :paused (:status result)))
      (let [saves (get-saves cp)
            pause-saves (filter #(= :paused (get-in % [:metadata :reason])) saves)]
        (is (pos? (count pause-saves)))
        (let [pause-save (first pause-saves)]
          (is (= :paused (get-in pause-save [:snapshot :status]))))))))

;;; ============================================================
;;; 无 checkpointer 时不受影响
;;; ============================================================

(deftest no-checkpointer-test
  (testing "无 checkpointer 时正常执行"
    (let [process-spec (-> (builder/builder :no-cp)
                          (builder/add-step
                            {:id :s
                             :on-activate (fn [_ _ ctx]
                                            {:context (ctx/set-var ctx :ok true)})})
                          (builder/on-event :start :s :input)
                          (builder/set-initial-event :start nil)
                          (builder/build))
          result (runtime/run-process process-spec
                   {:timeout-ms test-timeout
                    :checkpoint-policy :every-step})]
      (is (= :completed (:status result)))
      (is (true? (ctx/get-var (:context result) :ok))))))

;;; ============================================================
;;; thread-id 自动生成测试
;;; ============================================================

(deftest thread-id-auto-generate-test
  (testing "提供 checkpointer 但不提供 thread-id 时自动生成"
    (let [cp (create-mock-checkpointer)
          process-spec (-> (builder/builder :auto-tid)
                          (builder/add-step
                            {:id :s
                             :on-activate (fn [_ _ ctx]
                                            {:context (ctx/set-var ctx :ok true)})})
                          (builder/on-event :start :s :input)
                          (builder/set-initial-event :start nil)
                          (builder/build))
          result (runtime/run-process process-spec
                   {:timeout-ms test-timeout
                    :checkpointer cp})]
      (is (= :completed (:status result)))
      (let [saves (get-saves cp)]
        (is (= 1 (count saves)))
        ;; thread-id 是自动生成的 UUID
        (is (some? (:thread-id (first saves))))
        (is (string? (:thread-id (first saves))))))))

;;; ============================================================
;;; Runtime 包含 thread-id 和 checkpointer
;;; ============================================================

(deftest runtime-contains-checkpointer-info-test
  (testing "runtime map 包含 thread-id 和 checkpointer"
    (let [cp (create-mock-checkpointer)
          process-spec (-> (builder/builder :rt-info)
                          (builder/add-step
                            {:id :gate
                             :on-activate (fn [_ _ _]
                                            {:pause {:reason "wait" :state nil}})
                             :on-resume (fn [_ _ _] {:events []})})
                          (builder/on-event :start :gate :input)
                          (builder/set-initial-event :start nil)
                          (builder/build))
          result (runtime/run-process process-spec
                   {:timeout-ms test-timeout
                    :checkpointer cp
                    :thread-id "my-thread"})]
      (is (= :paused (:status result)))
      ;; runtime 可以通过 result 获取
      (let [rt (:runtime result)]
        (is (= "my-thread" (:thread-id rt)))
        (is (= cp (:checkpointer rt)))))))

;;; ============================================================
;;; load-checkpoint / load-latest 测试
;;; ============================================================

(deftest mock-load-checkpoint-test
  (testing "save 后可以 load-checkpoint"
    (let [cp (create-mock-checkpointer)
          snapshot {:process-name :test :status :paused :step-states {}}
          metadata {:reason :paused :created-at 12345}
          checkpoint-id (sm/save-checkpoint cp "thread-1" snapshot metadata)]
      (is (string? checkpoint-id))
      (let [loaded (sm/load-checkpoint cp "thread-1" checkpoint-id)]
        (is (= snapshot (:snapshot loaded)))
        (is (= metadata (:metadata loaded))))))

  (testing "load-latest 返回最新的"
    (let [cp (create-mock-checkpointer)]
      (sm/save-checkpoint cp "thread-2" {:n 1} {:reason :step-done :created-at 100})
      (sm/save-checkpoint cp "thread-2" {:n 2} {:reason :step-done :created-at 200})
      (let [latest (sm/load-latest cp "thread-2")]
        (is (= {:n 2} (:snapshot latest)))))))

(deftest mock-list-checkpoints-test
  (testing "list-checkpoints 按时间倒序"
    (let [cp (create-mock-checkpointer)]
      (sm/save-checkpoint cp "t" {:n 1} {:reason :step-done :created-at 100})
      (sm/save-checkpoint cp "t" {:n 2} {:reason :step-done :created-at 200})
      (sm/save-checkpoint cp "t" {:n 3} {:reason :step-done :created-at 300})
      (let [list (sm/list-checkpoints cp "t" {:limit 10})]
        (is (= 3 (count list)))
        ;; 最新在前
        (is (= 300 (get-in (first list) [:metadata :created-at])))))))
