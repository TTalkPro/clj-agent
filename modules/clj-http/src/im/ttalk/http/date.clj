(ns im.ttalk.http.date
  "HTTP 日期与 Retry-After 解析。移植自 Keelson `jvm/modules/clj-net/src/im/ttalk/net/retry.clj`（5c745869）的
   `parse-http-date` / `retry-after-ms`，改动：**不判上限**（clj-net 是「≥ 上限且 ≥ 退避就忽略」；clj-agent 的口径是
   「取 min(值, :max-delay)」，由调用方做，见 designs/http-module.md §1.3）；时钟改为毫秒数参数。"
  (:require [clojure.string :as str]
            [im.ttalk.http :as http])
  (:import (java.time DayOfWeek Instant LocalDateTime ZoneOffset ZonedDateTime)
           (java.time.format DateTimeFormatter DateTimeFormatterBuilder TextStyle)
           (java.time.temporal ChronoField TemporalAccessor)
           (java.util Locale)))

(set! *warn-on-reflection* true)

;; RFC 9110 §5.6.7：IMF-fixdate（首选）、RFC 850、asctime
(def ^:private rfc850
  (-> (DateTimeFormatterBuilder.) (.parseCaseInsensitive) (.appendText ChronoField/DAY_OF_WEEK TextStyle/FULL)
      (.appendLiteral ", ") (.appendPattern "dd-MMM-") (.appendValueReduced ChronoField/YEAR 2 2 1950)
      (.appendPattern " HH:mm:ss 'GMT'") (.toFormatter Locale/US)))

(def ^:private asctime
  (-> (DateTimeFormatterBuilder.) (.parseCaseInsensitive) (.appendPattern "EEE MMM") (.appendLiteral \space)
      (.optionalStart) (.appendLiteral \space) (.optionalEnd) (.appendPattern "d HH:mm:ss yyyy") (.toFormatter Locale/US)))

(defn- ldt-of [^TemporalAccessor p]
  (let [g (fn [f] (.get p ^ChronoField f))
        ldt (LocalDateTime/of (int (g ChronoField/YEAR)) (int (g ChronoField/MONTH_OF_YEAR)) (int (g ChronoField/DAY_OF_MONTH))
                              (int (g ChronoField/HOUR_OF_DAY)) (int (g ChronoField/MINUTE_OF_HOUR)) (int (g ChronoField/SECOND_OF_MINUTE)))]
    (when (= (.getDayOfWeek ldt) (DayOfWeek/of (int (g ChronoField/DAY_OF_WEEK))))
      (.toInstant ldt ZoneOffset/UTC))))

(defn parse-http-date
  "HTTP 日期（IMF-fixdate / RFC 850 / asctime 三种）⇒ Instant；解析不出 ⇒ nil。"
  ^Instant [s]
  (when s
    (let [s (str s)
          t (str/replace (str/trim s) #"\s+" " ")]
      (or (try (.toInstant (ZonedDateTime/parse t DateTimeFormatter/RFC_1123_DATE_TIME)) (catch java.time.DateTimeException _ nil))
          (try (ldt-of (.parse ^DateTimeFormatter rfc850 t)) (catch java.time.DateTimeException _ nil))
          (try (ldt-of (.parse ^DateTimeFormatter asctime (str/trim s))) (catch java.time.DateTimeException _ nil))))))

(defn- parse-double* [s]
  (when s
    (let [t (str/trim (str s))]
      (when (and (seq t) (re-matches #"[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?|[+-]?(Infinity|∞)|NaN" t))
        (try (Double/parseDouble (str/replace t "∞" "Infinity")) (catch NumberFormatException _ nil))))))

(defn- non-negative [s]
  (let [d (parse-double* s)] (when (and d (>= (double d) 0) (Double/isFinite d)) d)))

(defn- retry-after-value [s now-ms]
  (if-let [sec (non-negative s)]
    (Math/round (* (double sec) 1000.0))
    (when-not (or (nil? s) (parse-double* s))              ; 负数 / 非有限：不认
      (when-let [at (parse-http-date s)]
        (max 0 (- (.toEpochMilli at) (long now-ms)))))))

(defn retry-after-ms
  "服务端要求的等待（毫秒）；没有 / 认不得 ⇒ nil。先看 `retry-after-ms` 头（毫秒，OpenAI 用），再看 `Retry-After`
   （delta-seconds，可带小数；或 HTTP 日期 ⇒ 距 `now-ms` 多久，已过 ⇒ 0）。负数、非有限数不认。
   `headers`：头 map（头名大小写不敏感，值可为串 / 串向量 / List）或带 :headers 的响应。**不判上限。**"
  ([headers] (retry-after-ms headers (System/currentTimeMillis)))
  ([headers now-ms]
   (or (when-let [d (non-negative (http/header headers "retry-after-ms"))] (Math/round (double d)))
       (retry-after-value (http/header headers "retry-after") now-ms))))
