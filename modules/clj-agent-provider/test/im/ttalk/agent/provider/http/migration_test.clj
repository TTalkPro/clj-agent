(ns im.ttalk.agent.provider.http.migration-test
  "H-04：迁到 clj-http 之后新增的能力（designs/http-module.md §5）。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.provider.http.client :as http]
            [im.ttalk.agent.provider.http.retry :as retry]
            [im.ttalk.agent.provider.http.stream-client :as stream-client]
            [im.ttalk.agent.provider.common.rate-limit :as rate-limit])
  (:import (java.net ServerSocket)
           (java.time ZoneOffset ZonedDateTime)))

(def ^:private now (.toEpochMilli (.toInstant (ZonedDateTime/of 2026 10 9 12 0 0 0 ZoneOffset/UTC))))

(deftest retry-after-more-formats
  (is (= 30000 (retry/parse-retry-after {"retry-after" "Friday, 09-Oct-26 12:00:30 GMT"} now)) "RFC 850")
  (is (= 30000 (retry/parse-retry-after {"retry-after" "Fri Oct  9 12:00:30 2026"} now)) "asctime")
  (is (= 1500 (retry/parse-retry-after {"retry-after-ms" "1500" "retry-after" "9"} now)) "retry-after-ms 优先")
  (is (= 1500 (retry/parse-retry-after {"Retry-After" "1.5"} now)) "秒可带小数")
  (is (= 120000 (retry/parse-retry-after {"retry-after" (java.util.List/of "120")} now)) "java.net.http 的 List"))

(deftest response-error-carries-retry-after-ms-header
  (let [e (http/response->error {:status 429 :body {:error {:message "slow"}} :headers {"retry-after-ms" ["700"]}} :openai)]
    (is (= 700 (:retry-after-ms e)))))

(deftest request-id-is-a-string
  (is (= "req_1" (http/request-id {"request-id" ["req_1"]})))
  (is (= "x_2" (http/request-id {"x-request-id" (java.util.List/of "x_2")})))
  (is (nil? (http/request-id {}))))

(deftest rate-limit-header-value-delegates
  (is (= "5" (rate-limit/header-value {"X-RateLimit-Remaining-Requests" ["5"]} "x-ratelimit-remaining-requests")))
  (is (= "5" (rate-limit/header-value {:x-ratelimit-remaining-requests 5} "X-RateLimit-Remaining-Requests"))))

(defn- dead-port [] (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(defn- stream-error [url]
  (let [err (promise)
        {:keys [future]} (stream-client/post-stream-async
                           url {:body {} :parse-fn identity :process-fn (fn [_ s] [s nil])
                                :provider :test :on-error #(deliver err %)})]
    (try @future (catch Throwable _))
    (deref err 5000 nil)))

(deftest stream-network-errors-get-a-fine-grained-code
  (testing "连接被拒 ≠ 域名解析失败；可重试判定不变"
    (let [refused (stream-error (str "http://127.0.0.1:" (dead-port) "/"))
          dns (stream-error "http://no-such-host.invalid/")]
      (is (= :network-error (:type refused)))
      (is (= :connection-error (:code refused)))
      (is (= :network-error (:type dns)))
      (is (= :name-resolution-error (:code dns)))
      (is (= (:retryable? refused) (:retryable? dns))))))

(deftest sync-io-failure-shape-unchanged
  (let [r (http/get (str "http://127.0.0.1:" (dead-port) "/") :timeout 2000)]
    (is (= 0 (:status r)))
    (is (false? (:success? r)))
    (is (string? (:error r)))))
