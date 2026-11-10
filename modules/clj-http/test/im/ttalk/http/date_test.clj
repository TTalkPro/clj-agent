(ns im.ttalk.http.date-test
  "H-02：HTTP 日期与 Retry-After。用例移植自 Keelson clj-net test/im/ttalk/net/http_retry_test.clj 的
   retry-after-headers / retry-after-header-dictionary / retry-after-http-date；**上限规则不搬**（本 ns 不判上限），
   原「≥ 上限 ⇒ nil」的断言改为断言原值。"
  (:require [clojure.test :refer [deftest is]]
            [im.ttalk.http.date :as date])
  (:import (java.time ZoneOffset ZonedDateTime)))

(def ^:private now (.toEpochMilli (.toInstant (ZonedDateTime/of 2026 10 9 12 0 0 0 ZoneOffset/UTC))))

(defn- ra [headers] (date/retry-after-ms headers now))

(deftest retry-after-headers
  (is (= 1500 (ra {"retry-after-ms" "1500"})))
  (is (= 1500 (ra {"Retry-After-Ms" "1500"})))
  (is (= 2000 (ra {"Retry-After" " 2 "})))
  (is (= 500 (ra {"retry-after" "0.5"})))
  (is (= 120000 (ra {"Retry-After" "120"})) "不判上限")
  (is (nil? (ra {"Retry-After" "-1"})))
  (is (nil? (ra {"Retry-After" "soon"})))
  (is (nil? (ra {})))
  (is (nil? (ra nil)))
  (is (= 10 (ra {"retry-after-ms" "10" "Retry-After" "5"})))
  (is (= 5000 (ra {"retry-after-ms" "x" "Retry-After" "5"})))
  (is (= 3000 (ra {"RETRY-AFTER" "3"})))
  (is (= 3000 (ra {"retry-after" ["3"]})) "java.net.http 的值是列表")
  (is (= 3000 (ra {"retry-after" (java.util.List/of "3")})))
  (is (= 3000 (ra {:headers {"retry-after" "3"}})) "整个响应也收"))

(deftest retry-after-http-date
  (is (= 30000 (ra {"Retry-After" "Fri, 09 Oct 2026 12:00:30 GMT"})))
  (is (= 30000 (ra {"Retry-After" "Friday, 09-Oct-26 12:00:30 GMT"})))
  (is (= 30000 (ra {"Retry-After" "Fri Oct  9 12:00:30 2026"})))
  (is (= 0 (ra {"Retry-After" "Fri, 09 Oct 2026 11:00:00 GMT"})))
  (is (= 300000 (ra {"Retry-After" "Fri, 09 Oct 2026 12:05:00 GMT"})) "不判上限"))

(deftest parse-http-date
  (is (some? (date/parse-http-date "Sun, 06 Nov 1994 08:49:37 GMT")))
  (is (some? (date/parse-http-date "Sunday, 06-Nov-94 08:49:37 GMT")))
  (is (some? (date/parse-http-date "Sun Nov  6 08:49:37 1994")))
  (is (nil? (date/parse-http-date "Mon, 06 Nov 1994 08:49:37 GMT")) "星期对不上")
  (is (nil? (date/parse-http-date "garbage")))
  (is (nil? (date/parse-http-date nil))))
