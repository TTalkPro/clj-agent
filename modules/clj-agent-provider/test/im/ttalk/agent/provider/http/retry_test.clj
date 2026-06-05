(ns im.ttalk.agent.provider.http.retry-test
  "重试与错误分类单测"
  (:require [clojure.test :refer [deftest testing is are]]
            [im.ttalk.agent.provider.http.retry :as retry]))

;; ============================================================
;; 错误分类
;; ============================================================

(deftest transient-status?-test
  (testing "可重试状态码"
    (are [code] (true? (retry/transient-status? code))
      408 409 425 429 500 502 503 504 529))
  (testing "不可重试状态码"
    (are [code] (false? (retry/transient-status? code))
      200 201 400 401 403 404 422)))

(deftest transient-response?-test
  (testing "网络层错误可重试"
    (is (true? (retry/transient-response? {:error "connection refused" :status 0}))))
  (testing "5xx / 429 可重试"
    (is (true? (retry/transient-response? {:status 503 :success? false})))
    (is (true? (retry/transient-response? {:status 429 :success? false}))))
  (testing "成功响应不重试"
    (is (false? (retry/transient-response? {:status 200 :success? true}))))
  (testing "不可重试 4xx"
    (is (false? (retry/transient-response? {:status 400 :success? false})))
    (is (false? (retry/transient-response? {:status 401 :success? false})))))

;; ============================================================
;; Retry-After 解析
;; ============================================================

(deftest parse-retry-after-test
  (testing "秒数格式 -> 毫秒"
    (is (= 120000 (retry/parse-retry-after {:retry-after "120"})))
    (is (= 1000 (retry/parse-retry-after {"Retry-After" "1"}))))
  (testing "缺失头返回 nil"
    (is (nil? (retry/parse-retry-after {})))
    (is (nil? (retry/parse-retry-after nil))))
  (testing "HTTP-date 格式（注入 now 计算差值）"
    (let [now (.toEpochMilli (java.time.Instant/parse "2025-01-01T00:00:00Z"))
          ;; 目标时间晚 30 秒
          date "Wed, 01 Jan 2025 00:00:30 GMT"]
      (is (= 30000 (retry/parse-retry-after {:retry-after date} now)))))
  (testing "过期的 HTTP-date 返回 0（不为负）"
    (let [now (.toEpochMilli (java.time.Instant/parse "2025-01-01T01:00:00Z"))
          date "Wed, 01 Jan 2025 00:00:00 GMT"]
      (is (= 0 (retry/parse-retry-after {:retry-after date} now))))))

;; ============================================================
;; 退避计算
;; ============================================================

(deftest compute-backoff-test
  (testing "满抖动上界 = base * mult^attempt（rand=1）"
    (let [opts {:base-delay 1000 :multiplier 2.0 :max-delay 100000}]
      (is (= 1000 (retry/compute-backoff 0 opts (constantly 1.0))))
      (is (= 2000 (retry/compute-backoff 1 opts (constantly 1.0))))
      (is (= 4000 (retry/compute-backoff 2 opts (constantly 1.0))))))
  (testing "抖动下界 0（rand=0）"
    (is (= 0 (retry/compute-backoff 5 {:base-delay 1000} (constantly 0.0)))))
  (testing "受 max-delay 封顶"
    (let [opts {:base-delay 1000 :multiplier 10.0 :max-delay 5000}]
      (is (= 5000 (retry/compute-backoff 3 opts (constantly 1.0)))))))

;; ============================================================
;; with-retry 重试循环
;; ============================================================

(defn- counting-fn
  "返回一个 [calls-atom fn]，fn 每次返回 resp"
  [resp]
  (let [calls (atom 0)]
    [calls (fn [] (swap! calls inc) resp)]))

(deftest with-retry-test
  (let [no-sleep {:base-delay 0 :rand-fn (constantly 0.0)}]
    (testing "可重试响应耗尽 max-retries：调用 max-retries+1 次"
      (let [[calls f] (counting-fn {:status 503 :success? false})]
        (retry/with-retry f (merge no-sleep {:max-retries 3}))
        (is (= 4 @calls))))
    (testing "不可重试响应只调用一次"
      (let [[calls f] (counting-fn {:status 400 :success? false})]
        (retry/with-retry f (merge no-sleep {:max-retries 3}))
        (is (= 1 @calls))))
    (testing "成功立即返回"
      (let [[calls f] (counting-fn {:status 200 :success? true})]
        (is (= {:status 200 :success? true}
               (retry/with-retry f (merge no-sleep {:max-retries 3}))))
        (is (= 1 @calls))))
    (testing "中途成功则停止重试"
      (let [calls (atom 0)
            f (fn [] (swap! calls inc)
                (if (< @calls 2)
                  {:status 503 :success? false}
                  {:status 200 :success? true}))
            r (retry/with-retry f (merge no-sleep {:max-retries 5}))]
        (is (= 200 (:status r)))
        (is (= 2 @calls))))
    (testing "on-retry 回调被调用"
      (let [events (atom [])
            [_ f] (counting-fn {:status 503 :success? false})]
        (retry/with-retry f (merge no-sleep {:max-retries 2
                                             :on-retry #(swap! events conj (:attempt %))}))
        (is (= [1 2] @events))))))

(deftest maybe-with-retry-test
  (testing "config 无 :retry 时不重试，直接调用一次"
    (let [[calls f] (counting-fn {:status 503 :success? false})]
      (retry/maybe-with-retry {} f)
      (is (= 1 @calls))))
  (testing "config :retry 为 map 时启用重试"
    (let [[calls f] (counting-fn {:status 503 :success? false})]
      (retry/maybe-with-retry {:retry {:max-retries 2 :base-delay 0 :rand-fn (constantly 0.0)}} f)
      (is (= 3 @calls))))
  (testing "config :retry false 时不重试"
    (let [[calls f] (counting-fn {:status 503 :success? false})]
      (retry/maybe-with-retry {:retry false} f)
      (is (= 1 @calls)))))
