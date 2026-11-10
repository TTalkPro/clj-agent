(ns im.ttalk.http.failure
  "网络错的分类（与客户端无关）：按 `java.net` / `javax.net.ssl` 的异常类型与 JDK 固定的系统文案认。
   任何客户端底下抛的都是这批异常，拿来分类都行。

   移植自 Keelson `jvm/modules/clj-net/src/im/ttalk/net/failure.clj`（5c745869）的 `classify` / `socket-kind` /
   `timeout?`，判定逻辑未改（`timeout?` 修了一处原版 bug，见函数体注释）。**只给分类，不给可重试判定**：clj-net 的 `transient?` 不把超时算可重试，而 clj-agent
   的 canonical error 一直把 `HttpTimeoutException` 算 `:transient`——可重试与否仍由调用方的既有口径决定
   （designs/http-module.md §1.3）。"
  (:require [clojure.string :as str])
  (:import (java.net ConnectException NoRouteToHostException PortUnreachableException SocketException
                     SocketTimeoutException UnknownHostException)
           (java.nio.channels UnresolvedAddressException)
           (java.util Locale)
           (java.util.concurrent TimeoutException)
           (javax.net.ssl SSLException)))

(set! *warn-on-reflection* true)

(def kinds
  "`classify` 的取值。"
  #{:name-resolution-error :connection-error :secure-connection-error :unknown})

(defn- causes [^Throwable e] (take-while some? (iterate (fn [^Throwable x] (.getCause x)) e)))

(defn- lower ^String [^String s] (if s (.toLowerCase s Locale/ROOT) ""))

(defn- class-chain [^Object o]
  (take-while some? (iterate (fn [^Class c] (.getSuperclass c)) (.getClass o))))

(defn timeout?
  "超时：j.u.c 的 TimeoutException、JDK HttpClient 的请求超时（建连超时不算，它是「还没发出去」）。"
  [e]
  (boolean
    (or (instance? TimeoutException e)
        ;; 沿父类链找**第一个**认得的类名。⚠️ 不能用 `some`：建连超时那一格回 false，`some` 会把 false 当「没找到」
        ;; 接着往上走到父类 HttpTimeoutException ⇒ true——clj-net 原版正是这样把建连超时误判成请求超时的。
        (= "java.net.http.HttpTimeoutException"
           (some (fn [^Class c]
                   (#{"java.net.http.HttpConnectTimeoutException" "java.net.http.HttpTimeoutException"} (.getName c)))
                 (class-chain e))))))

(defn- try-again?
  "getaddrinfo 的 EAI_AGAIN（JDK 原样带 gai_strerror 的话）。"
  [^Throwable e]
  (str/includes? (lower (.getMessage e)) "temporary failure in name resolution"))

(def ^:private transient-texts
  ["connection reset" "broken pipe" "connection abort" "software caused connection abort" "network is unreachable"
   "network is down" "host is unreachable" "connection refused" "timed out" "shutdown"])

(defn socket-kind
  "沿原因链找第一个 socket 层的错：`:permanent`（主机名不存在）、`:transient`（ECONNRESET 那张表，含解析的 TryAgain）、`:none`。"
  [e]
  (let [chain (causes e)]
    ;; 解析错优先：JDK 把它包在 ConnectException 里
    (or (some (fn [x]
                (cond (instance? UnknownHostException x) (if (try-again? x) :transient :permanent)
                      (instance? UnresolvedAddressException x) :permanent))
              chain)
        (some (fn [^Throwable x]
                (cond (or (instance? ConnectException x) (instance? NoRouteToHostException x)
                          (instance? PortUnreachableException x) (instance? SocketTimeoutException x)) :transient
                      (instance? SocketException x)
                      (let [m (lower (.getMessage x))]
                        (if (some #(str/includes? m %) transient-texts) :transient :none))))
              chain)
        :none)))

(defn classify
  "归到 `kinds` 之一：解析失败 `:name-resolution-error`、TLS `:secure-connection-error`、建连 `:connection-error`，
   其余 `:unknown`。"
  [e]
  (let [chain (causes e)]
    (or (some (fn [x]
                (cond (or (instance? UnknownHostException x) (instance? UnresolvedAddressException x)) :name-resolution-error
                      (instance? SSLException x) :secure-connection-error))
              chain)
        (some (fn [^Throwable x]
                (when (or (instance? ConnectException x) (instance? NoRouteToHostException x)
                          (and (instance? SocketTimeoutException x) (str/includes? (lower (.getMessage x)) "connect"))
                          (= "java.net.http.HttpConnectTimeoutException" (.getName (.getClass x))))
                  :connection-error))
              chain)
        :unknown)))
