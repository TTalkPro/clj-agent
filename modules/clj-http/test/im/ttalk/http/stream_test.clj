(ns im.ttalk.http.stream-test
  "H-03：stream-lines——真增量、非 2xx、watchdog、取消、单次终止。时序用闸门，不用 sleep 赌。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.http :as http]
            [im.ttalk.http.test-server :as ts])
  (:import (com.sun.net.httpserver HttpExchange)
           (java.io OutputStream)
           (java.net ServerSocket)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent CancellationException ExecutionException TimeUnit)))

(defn- write! [^OutputStream o ^String s]
  (.write o (.getBytes s StandardCharsets/UTF_8))
  (.flush o))

(defn- outcome [f]
  (try {:ok (.get ^java.util.concurrent.Future f 5 TimeUnit/SECONDS)}
       (catch ExecutionException e {:err (.getCause e)})
       (catch CancellationException e {:err e})))

(deftest lines-arrive-incrementally
  (let [first-seen (promise)
        lines (atom [])
        resp (atom nil)]
    (ts/with-server [url (fn [ex]
                           (with-open [o (ts/start-chunked! ex 200 {"Content-Type" "text/event-stream"})]
                             (write! o "data: 1\n")
                             ;; 第二行要等客户端已经收到第一行才写——证明不是整段收完才回调
                             (deref first-seen 3000 nil)
                             (write! o "\ndata: 2\r\n\r\n")))]
      (let [{:keys [future]} (http/stream-lines {:method :post :url url :body "{}"
                                                 :on-response (fn [s h] (reset! resp [s (http/header h "content-type")]))
                                                 :on-line (fn [l]
                                                            (swap! lines conj l)
                                                            (when (= l "data: 1") (deliver first-seen true)))})
            {:keys [ok]} (outcome future)]
        (is (realized? first-seen))
        (is (= ["data: 1" "" "data: 2" ""] @lines))
        (is (= [200 "text/event-stream"] @resp))
        (is (= 200 (:status ok)))
        (is (nil? (:body ok)))))))

(deftest non-2xx-delivers-error-body-without-lines
  (let [lines (atom [])]
    (ts/with-server [url (fn [ex] (ts/respond! ex 429 {"Retry-After" "3"} "{\"error\":\"slow down\"}"))]
      (let [{:keys [ok]} (outcome (:future (http/stream-lines {:url url :on-line #(swap! lines conj %)})))]
        (is (= 429 (:status ok)))
        (is (= "{\"error\":\"slow down\"}" (:body ok)))
        (is (= "3" (http/header ok "retry-after")))
        (is (empty? @lines))))))

(deftest first-byte-timeout
  (let [release (promise)]
    (ts/with-server [url (fn [ex]
                           (with-open [o (ts/start-chunked! ex 200 {})]
                             (deref release 3000 nil)
                             (try (write! o "late\n") (catch Exception _))))]
      (let [{:keys [err]} (outcome (:future (http/stream-lines {:url url :first-byte-ms 200 :idle-ms 5000})))]
        (deliver release true)
        (is (= :first-byte (http/timeout-error? err)))
        (is (= 200 (:limit-ms (ex-data err))))))))

(deftest idle-timeout-and-heartbeat-keeps-alive
  (let [release (promise)
        lines (atom [])]
    (ts/with-server [url (fn [ex]
                           (with-open [o (ts/start-chunked! ex 200 {})]
                             (write! o "data: a\n")
                             ;; 心跳注释行续命：3 × 150ms < 每次 300ms 的空闲上限
                             (dotimes [_ 3] (Thread/sleep 150) (write! o ": ping\n"))
                             (deref release 3000 nil)
                             (try (write! o "late\n") (catch Exception _))))]
      (let [{:keys [err]} (outcome (:future (http/stream-lines {:url url :idle-ms 300 :on-line #(swap! lines conj %)})))]
        (deliver release true)
        (is (= :idle (http/timeout-error? err)))
        (is (= ["data: a" ": ping" ": ping" ": ping"] @lines))))))

(deftest cancel-from-on-line-stops-callbacks
  (let [lines (atom [])
        handle (promise)]
    (ts/with-server [url (fn [ex]
                           (with-open [o (ts/start-chunked! ex 200 {})]
                             (try (dotimes [i 50] (write! o (str "l" i "\n")) (Thread/sleep 10))
                                  (catch Exception _))))]
      (let [h (http/stream-lines {:url url :on-line (fn [l]
                                                      (swap! lines conj l)
                                                      (when (= l "l2") ((:cancel @handle))))})]
        (deliver handle h)
        (let [{:keys [err]} (outcome (:future h))]
          (is (instance? CancellationException err))
          (Thread/sleep 100)
          (is (= ["l0" "l1" "l2"] @lines)))))))

(deftest cancel-from-outside
  (let [started (promise)]
    (ts/with-server [url (fn [ex]
                           (with-open [o (ts/start-chunked! ex 200 {})]
                             (try (write! o "x\n") (Thread/sleep 3000) (catch Exception _))))]
      (let [{:keys [future cancel]} (http/stream-lines {:url url :on-line (fn [_] (deliver started true))})]
        (deref started 3000 nil)
        (cancel)
        (cancel)                                            ;; 幂等
        (is (instance? CancellationException (:err (outcome future))))))))

(deftest on-line-exception-aborts
  (ts/with-server [url (fn [ex]
                         (with-open [o (ts/start-chunked! ex 200 {})]
                           (try (dotimes [i 20] (write! o (str i "\n")) (Thread/sleep 10)) (catch Exception _))))]
    (let [{:keys [err]} (outcome (:future (http/stream-lines {:url url :on-line (fn [l] (when (= l "1") (throw (ex-info "boom" {}))))})))]
      (is (= "boom" (ex-message err))))))

(deftest io-failure
  (let [port (with-open [s (ServerSocket. 0)] (.getLocalPort s))
        {:keys [err]} (outcome (:future (http/stream-lines {:url (str "http://127.0.0.1:" port "/") :idle-ms 2000})))]
    (is (instance? java.io.IOException err))))

;; 终止只一次：超时与正常结束竞争、反复跑，future 状态与回调次数都不乱
(deftest single-termination-under-races
  (dotimes [_ 20]
    (ts/with-server [url (fn [ex]
                           (with-open [o (ts/start-chunked! ex 200 {})]
                             (Thread/sleep (rand-int 6))
                             (try (write! o "x\n") (catch Exception _))))]
      (let [{:keys [future]} (http/stream-lines {:url url :first-byte-ms 3})
            r (outcome future)]
        (is (or (= 200 (get-in r [:ok :status]))
                (= :first-byte (http/timeout-error? (:err r)))
                (instance? java.io.IOException (:err r))))))))
