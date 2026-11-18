(ns im.ttalk.http.netty-test
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.http.netty :as netty]
            [im.ttalk.http.transport :as transport])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress URI]
           [java.util.concurrent CompletableFuture TimeUnit]))

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

(defn- respond! [^HttpExchange ex status ^String body]
  (.readAllBytes (.getRequestBody ex))
  (let [b (.getBytes body)]
    (.sendResponseHeaders ex status (alength b))
    (doto (.getResponseBody ex)
      (.write b)
      (.close))))

(defn- error-kind [f kind]
  (let [e (try (f) nil (catch Throwable x x))]
    (is (some? e))
    (is (transport/error? e kind))))

(defn- late-respond! [^HttpExchange ex status ^String body]
  (.readAllBytes (.getRequestBody ex))
  (.sendResponseHeaders ex status 0)
  (Thread/sleep 300)
  (let [b (.getBytes body)]
    (.sendResponseHeaders ex status (alength b))
    (doto (.getResponseBody ex)
      (.write b)
      (.close))))

(deftest netty-request-contract
  (let [t (netty/transport)]
    (is (transport/transport? t))
    (is (= #{:string :bytes :discard :stream-lines} (:capabilities t)))
    (with-server [url (^void fn [_ ^HttpExchange ex]
                        (.add (.getResponseHeaders ex) "X-Multi" "1")
                        (.add (.getResponseHeaders ex) "x-multi" "2")
                        (respond! ex 200 "abc"))]
      (let [r (transport/request t {:method :post :url url :body "abc"})]
        (is (= 200 (:status r)))
        (is (= ["1" "2"] (get-in r [:headers "x-multi"])))
        (is (= "abc" (:body r)))
        (is (= url (:url r))))
      (let [r (transport/request t {:url url
                                    :query-params {:a "1" :b "x y"}})]
        (is (= 200 (:status r))))
      (let [bytes (.getBytes "binary")
            r (transport/request t {:url url :body bytes :as :bytes})]
        (is (bytes? (:body r))))
      (is (nil? (:body (transport/request t {:url url :as :discard}))))
      (let [f ^CompletableFuture (transport/request-async t {:url url})
            r (.get f 5 TimeUnit/SECONDS)]
        (is (= 200 (:status r)))))
    (transport/close t)
    (transport/close t)))

(deftest netty-stream-contract
  (let [t (netty/transport)]
    (with-server [url (^void fn [_ ^HttpExchange ex]
                        (respond! ex 200 "data: a\n\nline\n"))]
      (let [lines (atom [])
            h (transport/stream-lines t {:url url
                                         :on-line #(swap! lines conj %)})
            result (.get ^CompletableFuture (:future h) 5 TimeUnit/SECONDS)]
        (is (= 200 (:status result)))
        (is (= ["data: a" "" "line"] @lines))))
    (with-server [url (^void fn [_ ^HttpExchange ex]
                        (respond! ex 503 "{\"error\":\"boom\"}"))]
      (let [h (transport/stream-lines t {:url url
                                         :on-line (fn [_] (is false "non-2xx must not emit lines"))})
            r (.get ^CompletableFuture (:future h) 5 TimeUnit/SECONDS)]
        (is (= 503 (:status r)))
        (is (re-find #"boom" (:body r)))))
    (transport/close t)))

(deftest netty-stream-cancel-and-timeouts
  (let [t (netty/transport)]
    (with-server [url (^void fn [_ ^HttpExchange ex]
                        (.readAllBytes (.getRequestBody ex))
                        (.sendResponseHeaders ex 200 0)
                        (let [out (.getResponseBody ex)]
                          (.write out (.getBytes "line\n"))
                          (.flush out)
                          (Thread/sleep 500)
                          (.close out)))]
      (let [lines (atom [])
            h (transport/stream-lines t {:url url :on-line #(swap! lines conj %)})]
        (Thread/sleep 50)
        ((:cancel h))
        (is (thrown? java.util.concurrent.CancellationException
                     (.get ^CompletableFuture (:future h) 2 TimeUnit/SECONDS)))))
    (with-server [url (^void fn [_ ^HttpExchange ex]
                        (.readAllBytes (.getRequestBody ex))
                        (.sendResponseHeaders ex 200 0)
                        (Thread/sleep 300)
                        (.close (.getResponseBody ex)))]
      (let [h (transport/stream-lines t {:url url :first-byte-ms 50
                                         :on-line (fn [_])})
            e (try (.get ^CompletableFuture (:future h) 2 TimeUnit/SECONDS)
                   nil (catch Throwable x x))]
        (is (transport/error? (ex-cause e) :timeout-error))
        (is (= :first-byte (get-in (ex-data (ex-cause e)) [::transport/data :phase])))))
    (transport/close t)))

(deftest netty-error-mapping-and-options
  (let [t (netty/transport)]
    (error-kind #(transport/request t {:url "http://no-such-host.invalid/"
                                       :timeout-ms 5000})
                :name-resolution-error)
    (let [socket (java.net.ServerSocket. 0)
          port (.getLocalPort socket)]
      (.close socket)
      (error-kind #(transport/request t {:url (str "http://127.0.0.1:" port "/")
                                         :timeout-ms 5000})
                  :connection-error))
    (with-server [url (^void fn [_ ^HttpExchange ex]
                        (late-respond! ex 200 "late"))]
      (error-kind #(transport/request t {:url url :timeout-ms 50}) :timeout-error))
    (with-server [plain-url (^void fn [_ ^HttpExchange ex] (respond! ex 200 "plain"))]
      (let [https-url (str "https://127.0.0.1:" (.getPort (URI. plain-url)))
            tls (netty/transport {:connect-timeout-ms 500})]
        (try
          (error-kind #(transport/request tls {:url https-url :timeout-ms 5000})
                      :secure-connection-error)
          (finally (transport/close tls)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"redirect"
                          (transport/request t {:url "https://fake" :redirect :normal})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pool"
                          (transport/request t {:url "https://fake" :pool {}})))
    (transport/close t)))

(deftest netty-shared-event-loop-and-injected-validation
  (let [eg (io.netty.channel.nio.NioEventLoopGroup. 1)
        t (netty/transport {:event-loop-group eg})]
    (is (transport/transport? t))
    (with-server [url (^void fn [_ ^HttpExchange ex] (respond! ex 200 "ok"))]
      (is (= "ok" (:body (transport/request t {:url url})))))
    (transport/close t)
    ;; 注入的 event loop 归宿主所有，adapter close 不能关闭它。
    (is (not (.isShutdown eg)))
    (.shutdownGracefully eg))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"event-loop-group"
                        (netty/transport {:event-loop-group "bad"}))))
