(ns im.ttalk.agent.tools.executor-test
  "工具执行器测试

   测试内容：
   - 执行结果创建
   - 超时执行
   - 重试执行（委托给 resilience）
   - 降级执行
   - 并行执行
   - 批量执行
   - 策略执行
   - 执行统计"
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [im.ttalk.agent.tools.executor :as executor]
            [im.ttalk.agent.tools.tool-registry :as tool-registry]))

;;; ============================================================
;;; 测试工具注册
;;; ============================================================

(def ^:dynamic *test-registry* nil)

(defn create-test-registry
  "创建测试用的 ToolRegistry"
  []
  (let [registry (tool-registry/create-tool-registry)]
    ;; 成功执行的工具
    (tool-registry/register-tool! registry
      :echo
      "回显输入"
      {:type "object" :properties {:message {:type "string"}}}
      (fn [{:keys [message]}]
        message))

    ;; 模拟慢工具
    (tool-registry/register-tool! registry
      :slow-tool
      "模拟慢工具"
      {:type "object" :properties {:delay-ms {:type "integer"}}}
      (fn [{:keys [delay-ms]}]
        (Thread/sleep (or delay-ms 100))
        "slow-result"))

    ;; 可能失败的工具
    (tool-registry/register-tool! registry
      :flaky-tool
      "可能失败的工具"
      {:type "object" :properties {:fail-count {:type "integer"}}}
      (let [call-count (atom 0)]
        (fn [{:keys [fail-count]}]
          (swap! call-count inc)
          (if (<= @call-count (or fail-count 0))
            (throw (Exception. "模拟失败"))
            (str "成功于第 " @call-count " 次调用")))))

    ;; 计算工具
    (tool-registry/register-tool! registry
      :add
      "加法计算"
      {:type "object" :properties {:a {:type "number"} :b {:type "number"}}}
      (fn [{:keys [a b]}]
        (str (+ a b))))

    registry))

(use-fixtures :each
  (fn [test-fn]
    (binding [*test-registry* (create-test-registry)]
      (test-fn))))

;;; ============================================================
;;; 执行结果测试
;;; ============================================================

(deftest make-execution-result-test
  (testing "创建成功执行结果"
    (let [result (executor/make-execution-result :echo true "hello" 100)]
      (is (= :echo (:tool-name result)))
      (is (true? (:success result)))
      (is (= "hello" (:result result)))
      (is (nil? (:error result)))
      (is (= 100 (:duration-ms result)))))

  (testing "创建失败执行结果"
    (let [result (executor/make-execution-result :echo false "error msg" 50)]
      (is (= :echo (:tool-name result)))
      (is (false? (:success result)))
      (is (nil? (:result result)))
      (is (= "error msg" (:error result)))))

  (testing "带元数据的执行结果"
    (let [result (executor/make-execution-result :echo true "result" 100 2 {:source "test"})]
      (is (= 2 (:retry-count result)))
      (is (= {:source "test"} (:metadata result))))))

;;; ============================================================
;;; 超时执行测试
;;; ============================================================

(deftest execute-with-timeout-success-test
  (testing "在超时内完成"
    (let [result (executor/execute-with-timeout *test-registry* :echo {:message "hello"} 5000)]
      (is (true? (:success result)))
      (is (= "hello" (:result result))))))

(deftest execute-with-timeout-timeout-test
  (testing "超时返回失败"
    (let [result (executor/execute-with-timeout *test-registry* :slow-tool {:delay-ms 1000} 100)]
      (is (false? (:success result)))
      (is (string? (:error result)))
      (is (re-find #"timeout" (or (:error result) ""))))))

;;; ============================================================
;;; 重试执行测试
;;; ============================================================

(deftest execute-with-retry-success-test
  (testing "首次成功不重试"
    (let [result (executor/execute-with-retry *test-registry* :echo {:message "hello"}
                                              {:max-retries 3})]
      (is (true? (:success result)))
      (is (= "hello" (:result result)))
      (is (= 0 (:retry-count result))))))

(deftest execute-with-retry-unknown-tool-test
  (testing "未知工具返回失败"
    (let [result (executor/execute-with-retry *test-registry* :unknown-tool {}
                                              {:max-retries 1
                                               :initial-delay-ms 10})]
      (is (false? (:success result))))))

;;; ============================================================
;;; 降级执行测试
;;; ============================================================

(deftest execute-with-fallback-primary-success-test
  (testing "主工具成功时不使用降级"
    (let [result (executor/execute-with-fallback *test-registry* :echo {:message "primary"} :add)]
      (is (true? (:success result)))
      (is (= "primary" (:result result))))))

(deftest execute-with-fallback-use-fallback-test
  (testing "主工具失败时使用降级"
    (let [result (executor/execute-with-fallback *test-registry*
                   :unknown-primary {}
                   :echo {:message "fallback"})]
      (is (true? (:success result)))
      (is (= "fallback" (:result result)))
      (is (= :unknown-primary (get-in result [:metadata :primary-tool]))))))

;;; ============================================================
;;; 并行执行测试
;;; ============================================================

(deftest execute-parallel-test
  (testing "并行执行多个工具"
    (let [tool-calls [{:name :echo :args {:message "msg1"}}
                      {:name :echo :args {:message "msg2"}}
                      {:name :add :args {:a 1 :b 2}}]
          results (executor/execute-parallel *test-registry* tool-calls)]
      (is (= 3 (count results)))
      (is (every? :success results))
      (is (= "msg1" (:result (first results))))
      (is (= "msg2" (:result (second results))))
      (is (= "3" (:result (nth results 2)))))))

(deftest execute-parallel-empty-test
  (testing "空列表返回空结果"
    (is (= [] (executor/execute-parallel *test-registry* [])))))

(deftest execute-parallel-with-timeout-test
  (testing "并行执行带全局超时"
    (let [tool-calls [{:name :echo :args {:message "fast"}}
                      {:name :slow-tool :args {:delay-ms 10}}]
          results (executor/execute-parallel-with-timeout *test-registry* tool-calls 5000)]
      (is (= 2 (count results)))
      (is (every? :success results)))))

;;; ============================================================
;;; 批量执行测试
;;; ============================================================

(deftest execute-batch-test
  (testing "分批执行"
    (let [tool-calls (vec (for [i (range 6)]
                            {:name :echo :args {:message (str "msg" i)}}))
          ;; 每批 2 个，共 3 批
          results (executor/execute-batch *test-registry* tool-calls 2)]
      (is (= 6 (count results)))
      (is (every? :success results)))))

(deftest execute-batch-empty-test
  (testing "空列表返回空结果"
    (is (= [] (executor/execute-batch *test-registry* [] 5)))))

;;; ============================================================
;;; 策略执行测试
;;; ============================================================

(deftest execute-with-strategy-parallel-test
  (testing ":parallel 策略"
    (let [tool-calls [{:name :echo :args {:message "a"}}
                      {:name :echo :args {:message "b"}}]
          results (executor/execute-with-strategy *test-registry* tool-calls :parallel)]
      (is (= 2 (count results)))
      (is (every? :success results)))))

(deftest execute-with-strategy-sequential-test
  (testing ":sequential 策略"
    (let [tool-calls [{:name :echo :args {:message "a"}}
                      {:name :echo :args {:message "b"}}]
          results (executor/execute-with-strategy *test-registry* tool-calls :sequential)]
      (is (= 2 (count results)))
      (is (every? :success results)))))

(deftest execute-with-strategy-batch-test
  (testing ":batch 策略"
    (let [tool-calls (vec (for [i (range 4)]
                            {:name :echo :args {:message (str i)}}))
          results (executor/execute-with-strategy *test-registry* tool-calls :batch {:batch-size 2})]
      (is (= 4 (count results)))
      (is (every? :success results)))))

;;; ============================================================
;;; 统计测试
;;; ============================================================

(deftest calculate-stats-test
  (testing "计算执行统计"
    (let [results [(executor/make-execution-result :t1 true "r1" 100)
                   (executor/make-execution-result :t2 true "r2" 200)
                   (executor/make-execution-result :t3 false "err" 150)]
          stats (executor/calculate-stats results)]
      (is (= 3 (:total stats)))
      (is (= 2 (:success stats)))
      (is (= 1 (:failed stats)))
      (is (= 150 (:avg-duration stats)))  ;; (100+200+150)/3
      (is (= 200 (:max-duration stats)))
      (is (= 100 (:min-duration stats))))))

(deftest calculate-stats-empty-test
  (testing "空结果统计"
    (let [stats (executor/calculate-stats [])]
      (is (= 0 (:total stats)))
      (is (= 0 (:success stats)))
      (is (= 0 (:avg-duration stats))))))
