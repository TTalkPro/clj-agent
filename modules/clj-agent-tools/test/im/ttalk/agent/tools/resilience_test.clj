(ns im.ttalk.agent.tools.resilience-test
  "弹性执行模块测试

   测试内容：
   - 重试机制（指数退避）
   - 降级处理
   - 超时控制
   - 断路器
   - 速率限制"
  (:require [clojure.test :refer [deftest testing is are]]
            [im.ttalk.agent.tools.resilience :as r]))

;;; ============================================================
;;; 延迟计算测试
;;; ============================================================

(deftest calculate-delay-test
  (testing "指数退避延迟计算"
    (let [config {:initial-delay-ms 1000
                  :max-delay-ms 30000
                  :backoff-factor 2.0
                  :jitter? false}]
      (is (= 1000 (r/calculate-delay 0 config)))
      (is (= 2000 (r/calculate-delay 1 config)))
      (is (= 4000 (r/calculate-delay 2 config)))
      (is (= 8000 (r/calculate-delay 3 config)))))

  (testing "最大延迟限制"
    (let [config {:initial-delay-ms 1000
                  :max-delay-ms 5000
                  :backoff-factor 2.0
                  :jitter? false}]
      ;; 2^4 * 1000 = 16000 > 5000，应该被限制
      (is (= 5000 (r/calculate-delay 4 config)))))

  (testing "带抖动的延迟"
    (let [config {:initial-delay-ms 1000
                  :max-delay-ms 30000
                  :backoff-factor 2.0
                  :jitter? true
                  :jitter-factor 0.1}
          ;; 执行多次，验证抖动效果
          delays (repeatedly 10 #(r/calculate-delay 0 config))]
      ;; 抖动范围应在 ±10% 内
      (is (every? #(and (>= % 900) (<= % 1100)) delays))
      ;; 抖动应该产生不同的值（大概率）
      (is (> (count (set delays)) 1)))))

;;; ============================================================
;;; 重试机制测试
;;; ============================================================

(deftest retry-success-test
  (testing "首次成功不重试"
    (let [call-count (atom 0)
          result (r/retry
                   (fn []
                     (swap! call-count inc)
                     "success")
                   {:max-retries 3
                    :initial-delay-ms 10})]
      (is (= "success" result))
      (is (= 1 @call-count)))))

(deftest retry-eventual-success-test
  (testing "失败后最终成功"
    (let [call-count (atom 0)
          result (r/retry
                   (fn []
                     (swap! call-count inc)
                     (if (< @call-count 3)
                       (throw (Exception. "临时错误"))
                       "success"))
                   {:max-retries 5
                    :initial-delay-ms 10})]
      (is (= "success" result))
      (is (= 3 @call-count)))))

(deftest retry-exhausted-test
  (testing "重试耗尽后抛出异常"
    (let [call-count (atom 0)]
      (is (thrown-with-msg?
            Exception #"持续失败"
            (r/retry
              (fn []
                (swap! call-count inc)
                (throw (Exception. "持续失败")))
              {:max-retries 3
               :initial-delay-ms 10})))
      (is (= 4 @call-count)))))  ;; 1 次初始 + 3 次重试

(deftest retry-with-callback-test
  (testing "重试回调被调用"
    (let [retry-attempts (atom [])]
      (try
        (r/retry
          (fn [] (throw (Exception. "失败")))
          {:max-retries 2
           :initial-delay-ms 10
           :on-retry (fn [attempt ex delay]
                       (swap! retry-attempts conj
                              {:attempt attempt
                               :message (.getMessage ex)
                               :delay delay}))})
        (catch Exception _))
      (is (= 2 (count @retry-attempts)))
      (is (= [1 2] (mapv :attempt @retry-attempts))))))

(deftest with-retry-macro-test
  (testing "with-retry 宏"
    (let [result (r/with-retry {:max-retries 1 :initial-delay-ms 10}
                   (+ 1 2 3))]
      (is (= 6 result)))))

;;; ============================================================
;;; 降级处理测试
;;; ============================================================

(deftest fallback-success-test
  (testing "主函数成功时不调用降级"
    (let [fallback-called (atom false)
          result (r/fallback
                   (fn [] "primary")
                   (fn [_]
                     (reset! fallback-called true)
                     "fallback"))]
      (is (= "primary" result))
      (is (false? @fallback-called)))))

(deftest fallback-failure-test
  (testing "主函数失败时调用降级"
    (let [result (r/fallback
                   (fn [] (throw (Exception. "失败")))
                   (fn [ex] (str "fallback: " (.getMessage ex))))]
      (is (= "fallback: 失败" result)))))

(deftest fallback-value-test
  (testing "带默认值的降级"
    (is (= "default" (r/fallback-value
                       (fn [] (throw (Exception. "失败")))
                       "default")))
    (is (= "success" (r/fallback-value
                       (fn [] "success")
                       "default")))))

(deftest with-fallback-macro-test
  (testing "with-fallback 宏"
    (is (= "default"
           (r/with-fallback "default"
             (throw (Exception. "失败")))))
    (is (= "success"
           (r/with-fallback "default"
             "success")))))

;;; ============================================================
;;; 超时控制测试
;;; ============================================================

(deftest with-timeout-fn-success-test
  (testing "在超时内完成"
    (let [result (r/with-timeout-fn
                   (fn [] (Thread/sleep 10) "done")
                   1000)]
      (is (= "done" result)))))

(deftest with-timeout-fn-timeout-test
  (testing "超时抛出异常"
    (is (thrown? java.util.concurrent.TimeoutException
                 (r/with-timeout-fn
                   (fn [] (Thread/sleep 1000) "done")
                   50)))))

(deftest with-timeout-macro-test
  (testing "with-timeout 宏"
    (is (= "fast" (r/with-timeout 1000 "fast")))
    (is (thrown? java.util.concurrent.TimeoutException
                 (r/with-timeout 50
                   (Thread/sleep 1000)
                   "slow")))))

;;; ============================================================
;;; 断路器测试
;;; ============================================================

(deftest circuit-breaker-closed-test
  (testing "关闭状态下正常执行"
    (let [cb (r/create-circuit-breaker {:failure-threshold 3})
          result (r/execute-with-circuit-breaker cb (fn [] "success"))]
      (is (= "success" result)))))

(deftest circuit-breaker-open-test
  (testing "失败达到阈值后断路器打开"
    (let [cb (r/create-circuit-breaker {:failure-threshold 3
                                        :reset-timeout-ms 60000})]
      ;; 触发 3 次失败
      (dotimes [_ 3]
        (try
          (r/execute-with-circuit-breaker cb (fn [] (throw (Exception. "失败"))))
          (catch Exception _)))

      ;; 断路器应该打开，拒绝新请求
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Circuit breaker is open"
            (r/execute-with-circuit-breaker cb (fn [] "should not run")))))))

(deftest circuit-breaker-half-open-test
  (testing "超时后进入半开状态"
    (let [cb (r/create-circuit-breaker {:failure-threshold 2
                                        :success-threshold 2
                                        :reset-timeout-ms 50})]
      ;; 触发断路器打开
      (dotimes [_ 2]
        (try
          (r/execute-with-circuit-breaker cb (fn [] (throw (Exception. "失败"))))
          (catch Exception _)))

      ;; 等待超时
      (Thread/sleep 100)

      ;; 应该允许请求（半开状态）
      (is (= "recovered"
             (r/execute-with-circuit-breaker cb (fn [] "recovered")))))))

(deftest circuit-breaker-recovery-test
  (testing "半开状态成功后恢复"
    (let [cb (r/create-circuit-breaker {:failure-threshold 2
                                        :success-threshold 2
                                        :reset-timeout-ms 50})]
      ;; 触发断路器打开
      (dotimes [_ 2]
        (try
          (r/execute-with-circuit-breaker cb (fn [] (throw (Exception. "失败"))))
          (catch Exception _)))

      ;; 等待超时进入半开状态
      (Thread/sleep 100)

      ;; 连续成功，恢复到关闭状态
      (r/execute-with-circuit-breaker cb (fn [] "success1"))
      (r/execute-with-circuit-breaker cb (fn [] "success2"))

      ;; 应该完全恢复
      (is (= "normal"
             (r/execute-with-circuit-breaker cb (fn [] "normal")))))))

;;; ============================================================
;;; 速率限制测试
;;; ============================================================

(deftest rate-limiter-acquire-test
  (testing "获取令牌"
    (let [rl (r/create-rate-limiter {:max-tokens 3
                                     :refill-rate-per-second 10})]
      ;; 应该能获取 3 个令牌
      (is (true? (r/acquire-token! rl)))
      (is (true? (r/acquire-token! rl)))
      (is (true? (r/acquire-token! rl)))
      ;; 第 4 个应该失败（不等待）
      (is (false? (r/acquire-token! rl 0))))))

(deftest rate-limiter-refill-test
  (testing "令牌补充"
    (let [rl (r/create-rate-limiter {:max-tokens 2
                                     :refill-rate-per-second 100})]  ;; 快速补充用于测试
      ;; 消耗所有令牌
      (r/acquire-token! rl)
      (r/acquire-token! rl)

      ;; 等待令牌补充
      (Thread/sleep 50)

      ;; 应该能再次获取
      (is (true? (r/acquire-token! rl))))))

(deftest execute-with-rate-limit-test
  (testing "带速率限制的执行"
    (let [rl (r/create-rate-limiter {:max-tokens 2})]
      ;; 前两次应该成功
      (is (= "result1" (r/execute-with-rate-limit rl (fn [] "result1"))))
      (is (= "result2" (r/execute-with-rate-limit rl (fn [] "result2"))))
      ;; 第三次应该因速率限制失败
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Rate limit exceeded"
            (r/execute-with-rate-limit rl (fn [] "result3")))))))

;;; ============================================================
;;; 组合弹性模式测试
;;; ============================================================

(deftest with-resilience-fn-test
  (testing "组合多种弹性模式"
    (let [call-count (atom 0)
          result (r/with-resilience-fn
                   (fn []
                     (swap! call-count inc)
                     (if (< @call-count 2)
                       (throw (Exception. "临时失败"))
                       "success"))
                   {:retry {:max-retries 3
                            :initial-delay-ms 10}
                    :fallback {:value "fallback"}})]
      (is (= "success" result))
      (is (= 2 @call-count))))

  (testing "所有重试失败后使用降级"
    (let [result (r/with-resilience-fn
                   (fn [] (throw (Exception. "永久失败")))
                   {:retry {:max-retries 2
                            :initial-delay-ms 10}
                    :fallback {:value "fallback"}})]
      (is (= "fallback" result)))))

(deftest with-resilience-macro-test
  (testing "with-resilience 宏"
    (let [result (r/with-resilience
                   {:timeout {:ms 1000}
                    :fallback {:value "timeout-fallback"}}
                   (Thread/sleep 10)
                   "quick-result")]
      (is (= "quick-result" result)))))
