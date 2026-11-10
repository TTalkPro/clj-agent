(ns im.ttalk.agent.server.jetty.server-test
  "C# tests/Keelson.Net.Server.Tests/ServerTests.cs：真 Jetty（回环、内核分配端口），JDK HttpClient；处理器都是 Ring。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.server.strings :as err]
            [im.ttalk.agent.server.jetty :as server]
            [im.ttalk.agent.server.http :as http]
            [im.ttalk.agent.server.sse :as sse]
            [im.ttalk.agent.server.support :as t :refer [with-server]]
            [im.ttalk.http.sse :as frame])
  (:import (java.io IOException InputStream)))

(set! *warn-on-reflection* true)

(defn- thrown [f] (try (f) nil (catch Exception e e)))

(deftest starts-on-loopback-with-kernel-port
  (with-server [s [["GET" "/hello" (fn [_] {:status 200 :body "hi"})]] nil]
    (is (pos? (server/port s)))
    (is (str/starts-with? (server/base-address s) "http://127.0.0.1:"))
    (is (= "hi" (:body (t/send! :get (server/url s "/hello")))))))

(deftest unknown-path-is-404
  (with-server [s [["GET" "/a" (fn [_] nil)]] nil]
    (is (= 404 (:status (t/send! :get (server/url s "/nope")))))
    (testing "处理器什么都没写 ⇒ 200 空正文（ASP.NET 同）"
      (let [r (t/send! :get (server/url s "/a"))]
        (is (= 200 (:status r)))
        (is (= "" (:body r)))))))

(deftest post-body-round-trips
  (with-server [s [["POST" "/echo" (fn [req] {:status 200 :body (str/upper-case (http/read-body-string req))})]] nil]
    (is (= "ABC 中文" (:body (t/send! :post (server/url s "/echo") :body "abc 中文"))))))

(deftest sse-frames-arrive-in-order-with-headers
  (with-server [s [["GET" "/sse" (fn [req]
                                   (sse/response req (fn [x] (dotimes [i 3] (sse/write-event! x (str "{\"n\":" i "}"))))))]] nil]
    (let [r (t/send! :get (server/url s "/sse"))]
      (is (= 200 (:status r)))
      (is (str/starts-with? (t/header r "content-type") "text/event-stream"))
      (is (nil? (t/header r "content-length")))
      (is (str/includes? (str (t/header r "cache-control")) "no-cache"))
      (is (= "no" (t/header r "x-accel-buffering")))
      (is (= ["{\"n\":0}" "{\"n\":1}" "{\"n\":2}"] (t/data-of (:body r)))))))

(deftest frames-are-flushed-one-by-one
  ;; 每帧冲一次：客户端在服务端还没写第二帧时就能读到第一帧
  (let [gate (promise)]
    (with-server [s [["GET" "/sse" (fn [req]
                                     (sse/response req (fn [x]
                                                         (sse/write-event! x "first")
                                                         (deref gate 10000 nil)
                                                         (sse/write-event! x "second"))))]] nil]
      (let [{:keys [^InputStream stream]} (t/send-stream! :get (server/url s "/sse"))
            r (t/read-stream stream)
            first-ev (future (t/next-event r))]
        (is (= "first" (:data (t/deref-or first-ev 5000))))
        (deliver gate true)
        (is (= "second" (:data (t/next-event r))))
        (.close ^java.io.Closeable r)))))

(deftest heartbeat-is-a-comment-not-an-event
  (with-server [s [["GET" "/sse" (fn [req]
                                   (sse/response req {:heartbeat-ms 40} (fn [x] (Thread/sleep 300) (sse/write-event! x "done"))))]] nil]
    (let [raw (:body (t/send! :get (server/url s "/sse")))]
      (is (>= (t/count-of raw (str ": " sse/heartbeat-text "\n\n")) 2) raw)
      (is (str/ends-with? raw "data: done\n\n"))
      ;; 解析器只把 data: 当事件
      (is (= ["done"] (t/data-of raw))))))

(deftest custom-heartbeat-frame-is-a-data-event
  (let [n (atom 0)]
    (with-server [s [["GET" "/sse" (fn [req]
                                     (sse/response req {:heartbeat-ms 40
                                                        :heartbeat-frame #(frame/event-frame (str "{\"n\":" (swap! n inc) "}") {:event "ping"})}
                                                   (fn [x] (Thread/sleep 300) (sse/write-event! x "done"))))]] nil]
      (let [raw (:body (t/send! :get (server/url s "/sse")))
            evs (t/events raw)]
        (is (not (str/includes? raw sse/heartbeat-text)) "给了帧就不再写注释")
        (is (>= (count evs) 3) raw)
        (is (= {:event-type "message" :data "done"} (peek evs)))
        (let [pings (pop evs)]
          (is (every? #(= "ping" (:event-type %)) pings))
          (is (= (map #(str "{\"n\":" % "}") (range 1 (inc (count pings)))) (map :data pings))))))))

(deftest no-heartbeat-when-disabled
  (with-server [s [["GET" "/sse" (fn [req]
                                   (sse/response req (fn [x] (Thread/sleep 150) (sse/write-event! x "done"))))]] nil]
    (is (= "data: done\n\n" (:body (t/send! :get (server/url s "/sse")))))))

(deftest client-disconnect-is-observed-by-the-handler
  (let [gone (promise)]
    (with-server [s [["GET" "/sse" (fn [req]
                                     (sse/response req (fn [x]
                                                         (loop [i 0]
                                                           (when (and (< i 200) (not (sse/gone? x)))
                                                             (sse/write-event! x "tick")
                                                             (sse/await-aborted x 10)
                                                             (recur (inc i))))
                                                         (deliver gone (sse/gone? x)))))]] nil]
      (let [{:keys [^InputStream stream]} (t/send-stream! :get (server/url s "/sse"))
            r (t/read-stream stream)]
        (is (= "tick" (:data (t/next-event r))))
        (.close ^java.io.Closeable r))
      (is (true? (t/deref-or gone 5000))))))

(deftest graceful-stop-ends-in-flight-sse-stream
  (let [started (promise)
        s (server/start [["GET" "/sse" (fn [req]
                                         (sse/response req (fn [x]
                                                             (sse/write-event! x "hello")
                                                             (deliver started true)
                                                             ;; 停机时自己收尾（流照常结束，不是被掐）
                                                             (sse/await-aborted x))))]]
                        {:shutdown-timeout-ms 5000})]
    (try
      (let [{:keys [^InputStream stream]} (t/send-stream! :get (server/url s "/sse"))]
        (is (true? (t/deref-or started 5000)))
        (let [t0 (System/nanoTime)]
          (server/stop! s)
          (is (< (/ (- (System/nanoTime) t0) 1e6) 4000) "处理器没观察到 stopping"))
        (is (= ["hello"] (t/data-of (t/read-all stream))))
        (server/stop! s)                               ; 幂等
        (is (instance? IOException (thrown #(t/send! :get (server/url s "/sse") :timeout-ms 10000)))
            "停了之后连不上"))
      (finally (server/stop! s)))))

(deftest bad-host-is-rejected
  (is (= "server/bad-host" (err/code (thrown #(server/create (fn [_]) {:host "not an ip"})))))
  (is (= "server/localhost-port-zero" (err/code (thrown #(server/create (fn [_]) {:host "localhost" :port 0}))))))

(deftest localhost-binds-loopback
  (with-server [s [["GET" "/hello" (fn [_] {:status 200 :body "hi"})]]
                {:host "localhost" :port (with-open [ss (java.net.ServerSocket. 0)] (.getLocalPort ss))}]
    (is (str/starts-with? (server/base-address s) "http://localhost:"))
    (is (= "hi" (:body (t/send! :get (str "http://127.0.0.1:" (server/port s) "/hello")))))))

(deftest routing-precedence-and-405
  (with-server [s [[:any "/agent/:id/stop/:tid" (fn [req] {:status 200 :body (str "generic " (http/param req :id))})]
                   ["POST" "/agent/mine/stop/:tid" (fn [req] {:status 200 :body (str "mine " (http/param req :tid))})]
                   ["GET" "/only-get" (fn [_] {:status 200 :body "g"})]
                   ["GET" "/files/*rest" (fn [req] {:status 200 :body (http/param req :rest)})]
                   ["GET" "/p/:x" (fn [req] {:status 200 :body (http/param req :x)})]] nil]
    (is (= "mine t-9" (:body (t/send! :post (server/url s "/agent/mine/stop/t-9")))) "字面量段胜过参数段（与登记顺序无关）")
    (is (= "generic other" (:body (t/send! :post (server/url s "/agent/other/stop/t-9")))))
    (is (= "generic mine" (:body (t/send! :get (server/url s "/agent/mine/stop/t-9")))) "方法不中的跳过")
    (let [r (t/send! :post (server/url s "/only-get"))]
      (is (= 405 (:status r)))
      (is (= "GET" (t/header r "allow"))))
    (is (= "a/b c" (:body (t/send! :get (server/url s "/files/a/b%20c")))))
    (is (= "a/b" (:body (t/send! :get (server/url s "/p/a%2Fb")))) "%2F 留在段里")
    (is (= "g" (:body (t/send! :get (server/url s "/only-get/")))) "末尾的 / 不计")))

(deftest handlers-are-plain-ring
  ;; 路由、请求体上限、错误兜底都是 Ring：不起服务端也能调（同步与异步两种元数）
  (let [r (im.ttalk.agent.server.routing/router
           [["GET" "/a/:x" (fn [req] {:status 200 :body (http/param req :x)})]
            ["POST" "/b" (fn [req] {:status 200 :body (http/read-body-string req)})]])
        h (http/wrap-errors (http/wrap-body-limit r 3))]
    (is (= "q" (:body (r {:request-method :get :uri "/a/q"}))))
    (is (= 405 (:status (r {:request-method :post :uri "/a/q"}))))
    (is (= 404 (:status (r {:request-method :get :uri "/nope"}))))
    (is (= "abc" (:body (h {:request-method :post :uri "/b" :body (java.io.ByteArrayInputStream. (.getBytes "abc"))}))))
    (is (= 413 (:status (h {:request-method :post :uri "/b" :body (java.io.ByteArrayInputStream. (.getBytes "abcd"))}))))
    (is (= 413 (:status (h {:request-method :post :uri "/b" :content-length 9 :body nil}))))
    (let [p (promise)]
      (r {:request-method :get :uri "/a/z"} #(deliver p %) #(deliver p %))
      (is (= "z" (:body @p))))))
