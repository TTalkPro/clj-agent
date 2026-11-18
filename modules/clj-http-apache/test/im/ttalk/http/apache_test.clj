(ns im.ttalk.http.apache-test
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.http.apache :as apache]
            [im.ttalk.http.transport :as transport])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.util.concurrent TimeUnit]))

(set! *warn-on-reflection* true)

(defn- start-server [handler]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/" (reify HttpHandler
                                 (handle [_ exchange]
                                   (handler ^HttpServer server ^HttpExchange exchange))))
    (.start server)
    server))

(defn- stop-server [^HttpServer server]
  (.stop server 0))

(defmacro with-server [[binding handler] & body]
  `(let [server# (start-server ~handler)
         ~binding (let [^HttpServer server# server#]
                    (str "http://127.0.0.1:" (.getPort (.getAddress server#)) "/"))]
     (try ~@body (finally (stop-server server#)))))

(defn- read-and-respond! [^HttpExchange ex status body]
  (.readAllBytes (.getRequestBody ex))
  (let [b (.getBytes ^String body)]
    (.sendResponseHeaders ex status (alength b))
    (doto (.getResponseBody ex)
      (.write b)
      (.close))))

(defn- error-kind [f kind]
  (let [e (try (f) nil (catch Throwable x x))]
    (is (some? e))
    (is (transport/error? e kind))))

(deftest apache-contract-and-request
  (let [t (apache/transport)]
    (is (transport/transport? t))
    (is (= #{:string :bytes :discard :stream-lines} (:capabilities t)))
    (with-server [url (^void fn [_ ^HttpExchange ex]
                        (.add (.getResponseHeaders ex) "X-Multi" "1")
                        (.add (.getResponseHeaders ex) "x-multi" "2")
                        (let [b (.getBytes "abc")]
                          (.sendResponseHeaders ex 200 (alength b))
                          (.write (.getResponseBody ex) b)
                          (.close (.getResponseBody ex))))]
      (let [r (transport/request t {:method :post :url url :body "abc"})]
        (is (= 200 (:status r)))
        (is (= ["1" "2"] (get-in r [:headers "x-multi"])))
        (is (= "abc" (:body r))))
      (let [r (.get ^java.util.concurrent.CompletableFuture
                    (transport/request-async t {:url url :as :bytes})
                    5 TimeUnit/SECONDS)]
        (is (bytes? (:body r))))
      (let [r (transport/request t {:url url :as :discard})]
        (is (nil? (:body r))))
      (transport/close t))))

(deftest apache-streams-and-errors
  (let [t (apache/transport)]
    (with-server [url (^void fn [_ ^HttpExchange ex]
                        (let [b (.getBytes "data: a\n\nline\n")]
                          (.sendResponseHeaders ex 200 (alength b))
                          (doto (.getResponseBody ex)
                            (.write b)
                            (.close))))]
      (let [lines (atom [])
            h (transport/stream-lines t {:url url
                                         :on-line #(swap! lines conj %)})
            result (.get ^java.util.concurrent.CompletableFuture (:future h)
                         5 TimeUnit/SECONDS)]
        (is (= 200 (:status result)))
        (is (= ["data: a" "" "line"] @lines))))
    (with-server [url (^void fn [_ ^HttpExchange ex]
                        (read-and-respond! ex 503 "{\"error\":\"boom\"}"))]
      (let [h (transport/stream-lines t {:url url :on-line (fn [_] (throw (RuntimeException. "must not call")))})
            r (.get ^java.util.concurrent.CompletableFuture (:future h)
                    5 TimeUnit/SECONDS)]
        (is (= 503 (:status r)))
        (is (re-find #"boom" (:body r)))))
    (transport/close t)
    (transport/close t)))

(deftest apache-stream-cancel
  (let [t (apache/transport)]
    (with-server [url (^void fn [_ ^HttpExchange ex]
                        (.readAllBytes (.getRequestBody ex))
                        (.sendResponseHeaders ex 200 0)
                        (let [out (.getResponseBody ex)]
                          (.write out (.getBytes "data: a\n"))
                          (.flush out)
                          (Thread/sleep 500)
                          (.close out)))]
      (let [lines (atom [])
            h (transport/stream-lines t {:url url :on-line #(swap! lines conj %)})]
        (Thread/sleep 30)
        ((:cancel h))
        (is (thrown? java.util.concurrent.CancellationException
                     (.get ^java.util.concurrent.CompletableFuture (:future h)
                           2 TimeUnit/SECONDS)))
        ;; 本次回环 body 是一个 chunk，cancel 竞争下不承诺已消费行数。
        (is (vector? @lines))))
    (transport/close t)))

(deftest apache-stream-watchdogs
  (let [t (apache/transport)]
    (with-server [url (^void fn [_ ^HttpExchange ex]
                        (.readAllBytes (.getRequestBody ex))
                        (.sendResponseHeaders ex 200 0)
                        (Thread/sleep 300)
                        (.close (.getResponseBody ex)))]
      (let [h (transport/stream-lines t {:url url :first-byte-ms 50 :on-line (fn [_])})
            e (try (.get ^java.util.concurrent.CompletableFuture (:future h)
                         2 TimeUnit/SECONDS)
                   nil (catch Throwable x x))]
        (is (transport/error? (ex-cause e) :timeout-error))
        (is (= :first-byte (get-in (ex-data (ex-cause e)) [::transport/data :phase])))))
    (with-server [url (^void fn [_ ^HttpExchange ex]
                        (read-and-respond! ex 200 "data: a\n")
                        (Thread/sleep 300))]
      ;; Apache 端只会收到一次性 body；这里用较宽 idle 验证正常结束后 watchdog 清理。
      (let [h (transport/stream-lines t {:url url :idle-ms 500 :on-line (fn [_])})
            r (.get ^java.util.concurrent.CompletableFuture (:future h)
                    2 TimeUnit/SECONDS)]
        (is (= 200 (:status r)))))
    (transport/close t)))

(deftest apache-error-mapping
  (let [t (apache/transport)]
    (error-kind #(transport/request t {:url "http://no-such-host.invalid/" :timeout-ms 500})
                :name-resolution-error)
    (let [socket (java.net.ServerSocket. 0)
          port (.getLocalPort socket)]
      (.close socket)
      (error-kind #(transport/request t {:url (str "http://127.0.0.1:" port "/") :timeout-ms 500})
                  :connection-error))
    (with-server [url (^void fn [_ ^HttpExchange ex]
                        (.readAllBytes (.getRequestBody ex))
                        (Thread/sleep 400)
                        (read-and-respond! ex 200 "late"))]
      (error-kind #(transport/request t {:url url :timeout-ms 50})
                  :timeout-error))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"body"
                          (transport/request t {:url "https://fake" :body 123})))
    (transport/close t)
    (transport/close t)))

(deftest apache-first-byte-timeout
  (let [t (apache/transport)]
    (with-server [_url (^void fn [_ ^HttpExchange ex]
                         (try
                           (.sendResponseHeaders ex 200 0)
                           (Thread/sleep 500)
                           (.close (.getResponseBody ex))
                           (catch Exception _ nil)))]
      (let [h (transport/stream-lines t {:url _url :first-byte-ms 50 :idle-ms 50
                                         :on-line (fn [_])})
            e (try (.get ^java.util.concurrent.CompletableFuture (:future h)
                         5 TimeUnit/SECONDS)
                   nil (catch Throwable x x))]
        (is (some? e))
        (let [cause (ex-cause e)]
          (is (transport/error? cause :timeout-error))
          (is (= :first-byte (:phase (::transport/data (ex-data cause))))))))
    (transport/close t)))
