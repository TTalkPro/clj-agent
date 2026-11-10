(ns im.ttalk.agent.server.adapter-test
  "S-03：同一个 SSE 处理器（纯 Ring：StreamableResponseBody）跑在 Jetty（缺省 adapter）、http-kit（+ 垫片）、
   Undertow（+ 垫片）上——帧逐个到达（不是攒满）、心跳、对端断开后 gone?。designs/server-module.md §1。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.server.adapter.http-kit :as hk-shim]
            [im.ttalk.agent.server.adapter.undertow :as ut-shim]
            [im.ttalk.agent.server.jetty :as jetty]
            [im.ttalk.agent.server.sse :as sse]
            [im.ttalk.agent.server.support :as t])
  (:import (java.io InputStream)
           (java.net ServerSocket)))

(defn- free-port [] (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(defn- start!
  "起一台服务端，回 [base-url stop-fn]。"
  [kind handler]
  (case kind
    :jetty (let [s (jetty/start handler {:port 0})]
             [(str/replace (jetty/base-address s) #"/$" "") #(jetty/stop! s)])
    :http-kit (let [port (free-port)
                    stop ((requiring-resolve 'org.httpkit.server/run-server) (hk-shim/wrap-streaming handler) {:port port})]
                [(str "http://127.0.0.1:" port) #(stop)])
    :undertow (let [port (free-port)
                    _ (ut-shim/install!)
                    u ((requiring-resolve 'ring.adapter.undertow/run-undertow) handler {:port port :host "127.0.0.1"})]
                [(str "http://127.0.0.1:" port) #(.stop ^java.io.Closeable u)])))

(defn- gaps
  "按到达时刻读事件：[[ms data] …]。"
  [^InputStream in]
  (let [t0 (System/currentTimeMillis)
        r (t/read-stream in)]
    (loop [acc []]
      (if-let [e (t/next-event r)]
        (recur (conj acc [(- (System/currentTimeMillis) t0) (:data e)]))
        acc))))

(def ^:private servers [:jetty :http-kit :undertow])

(deftest sse-streams-incrementally-on-every-server
  (doseq [kind servers]
    (testing (name kind)
      (let [[base stop] (start! kind (fn [req]
                                       (sse/response req {} (fn [s]
                                                              (dotimes [i 3]
                                                                (sse/write-event! s (str i))
                                                                (Thread/sleep 300))))))]
        (try
          (let [{:keys [status headers stream]} (t/send-stream! :get (str base "/"))
                evs (gaps stream)]
            (is (= 200 status))
            (is (str/starts-with? (first (get headers "content-type")) "text/event-stream"))
            (is (= ["0" "1" "2"] (mapv second evs)))
            ;; 真流式：第一帧远早于最后一帧（攒满才发时三帧同时到）
            (is (>= (- (first (last evs)) (first (first evs))) 450) (pr-str evs)))
          (finally (stop)))))))

(deftest heartbeat-on-every-server
  (doseq [kind servers]
    (testing (name kind)
      (let [[base stop] (start! kind (fn [req] (sse/response req {:heartbeat-ms 100} (fn [_] (Thread/sleep 450)))))]
        (try
          (let [raw (t/read-all (:stream (t/send-stream! :get (str base "/"))))]
            (is (>= (t/count-of raw ": clj-agent-alive") 2) raw))
          (finally (stop)))))))

;; ⚠️ 不含 http-kit：http-kit 2.8 在客户端断开后 send! 仍回 true、open? 仍为 true、:on-close 不触发（实测：关套接字后
;; 连写 40 次、每次 8 KB 都「成功」）——垫片无从得知，SSE 只能等 producer 自己结束。见 adapter.http-kit 的 ns 文档。
(deftest disconnect-is-noticed-on-jetty-and-undertow
  (doseq [kind [:jetty :undertow]]
    (testing (name kind)
      (let [gone (promise)
            [base stop] (start! kind (fn [req]
                                       (sse/response req {:heartbeat-ms 50}
                                                     (fn [s]
                                                       (sse/write-event! s "hello")
                                                       (deliver gone (sse/await-aborted s 5000))))))]
        (try
          (let [{:keys [stream]} (t/send-stream! :get (str base "/"))
                r (t/read-stream stream)]
            (is (= "hello" (:data (t/next-event r))))
            (.close ^java.io.Closeable r)
            (is (true? (deref gone 6000 ::timeout)) "对端走了，心跳那一次写发现"))
          (finally (stop)))))))

(deftest plain-responses-pass-through-the-shims
  (doseq [kind [:http-kit :undertow]]
    (testing (name kind)
      (let [[base stop] (start! kind (fn [_] {:status 201 :headers {"Content-Type" "text/plain"} :body "plain"}))]
        (try
          (let [r (t/send! :get (str base "/"))]
            (is (= 201 (:status r)))
            (is (= "plain" (:body r))))
          (finally (stop)))))))

(deftest shim-predicates
  (is (hk-shim/streaming-body? (:body (sse/response {} {} (fn [_])))))
  (is (not (hk-shim/streaming-body? "x")))
  (is (true? (ut-shim/install!)))
  (is (true? (ut-shim/install!)) "幂等"))
