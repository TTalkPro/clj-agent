(ns im.ttalk.http.failure-test
  "H-02：网络错分类。socket 表的用例移植自 Keelson clj-net test/im/ttalk/net/http_retry_test.clj 的 socket-error-table
   （原断言判 transient?，本 ns 不给可重试判定，改判 socket-kind）。"
  (:require [clojure.test :refer [deftest is]]
            [im.ttalk.http :as http]
            [im.ttalk.http.failure :as f])
  (:import (java.io IOException)
           (java.net ConnectException ServerSocket SocketException UnknownHostException)
           (java.net.http HttpConnectTimeoutException HttpTimeoutException)
           (java.util.concurrent TimeoutException)
           (javax.net.ssl SSLHandshakeException)))

(deftest socket-error-table
  (is (= :transient (f/socket-kind (IOException. "x" (SocketException. "Connection reset")))))
  (is (= :transient (f/socket-kind (UnknownHostException. "h: Temporary failure in name resolution"))))
  (is (= :permanent (f/socket-kind (UnknownHostException. "h: Name or service not known"))))
  (is (= :permanent (f/socket-kind (doto (ConnectException. "x") (.initCause (UnknownHostException. "h"))))) "解析错优先")
  (is (= :none (f/socket-kind (IllegalStateException.)))))

(deftest classify-kinds
  (is (= :name-resolution-error (f/classify (doto (ConnectException. "x") (.initCause (UnknownHostException. "h"))))))
  (is (= :secure-connection-error (f/classify (IOException. "x" (SSLHandshakeException. "bad cert")))))
  (is (= :connection-error (f/classify (ConnectException. "Connection refused"))))
  (is (= :connection-error (f/classify (HttpConnectTimeoutException. "connect timed out"))))
  (is (= :unknown (f/classify (IOException. "whatever"))))
  (is (every? f/kinds (map f/classify [(IOException.) (ConnectException.)]))))

(deftest timeouts
  (is (f/timeout? (HttpTimeoutException. "request timed out")))
  (is (f/timeout? (TimeoutException.)))
  (is (not (f/timeout? (HttpConnectTimeoutException. "connect timed out"))) "建连超时 = 还没发出去")
  (is (not (f/timeout? (IOException.)))))

(deftest real-jdk-exceptions
  (let [port (with-open [s (ServerSocket. 0)] (.getLocalPort s))
        e (try (http/request {:url (str "http://127.0.0.1:" port "/")}) nil (catch Exception e e))]
    (is (= :connection-error (f/classify e)))
    (is (= :transient (f/socket-kind e))))
  (let [e (try (http/request {:url "http://no-such-host.invalid/"}) nil (catch Exception e e))]
    (is (= :name-resolution-error (f/classify e)))))
