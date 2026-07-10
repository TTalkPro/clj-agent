(ns im.ttalk.agent.provider.http.stream-client-test
  "stream-client（java.net.http 真流式传输）集成测试。

   用 JDK 内置 com.sun.net.httpserver 起本地 SSE 服务（零依赖、不联网），
   验证真实 java.net.http 行为：真增量时序、Content-Type 发送、回调流、
   非 2xx → canonical error、cancel 取消上游。"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [im.ttalk.agent.provider.http.stream-client :as sc])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]))

;;; ============================================================
;;; 本地 SSE 测试服务器
;;; ============================================================

(defn- start-server
  "起本地 HTTP 服务，返回 {:server :port :captured}。
   captured 记录收到的请求头与 body，便于断言客户端发了什么。"
  [handler-fn]
  (let [captured (atom {})
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ exchange]
          (reset! captured
                  {:content-type (.getFirst (.getRequestHeaders exchange) "Content-Type")
                   :authorization (.getFirst (.getRequestHeaders exchange) "Authorization")
                   :body (slurp (.getRequestBody exchange))})
          (handler-fn exchange))))
    (.start server)
    {:server server
     :port (.getPort (.getAddress server))
     :captured captured}))

(defn- write-sse-chunks!
  "向响应逐块写 SSE 行（每块间隔 delay-ms，flush 确保分块发出）。"
  [exchange lines delay-ms]
  (let [h (.getResponseHeaders exchange)]
    (.add h "Content-Type" "text/event-stream"))
  (.sendResponseHeaders exchange 200 0)        ;; 0 => chunked
  (with-open [os (.getResponseBody exchange)]
    (doseq [line lines]
      (.write os (.getBytes (str line "\n") "UTF-8"))
      (.flush os)
      (when (pos? delay-ms) (Thread/sleep delay-ms)))))

(defn- error-response! [exchange status body-str]
  (let [b (.getBytes body-str "UTF-8")]
    (.sendResponseHeaders exchange status (count b))
    (with-open [os (.getResponseBody exchange)] (.write os b))))

;;; 简单 parse/process：data 行解析 JSON，每个事件作为一个 token emit
(defn- parse-data [line]
  (when (and (seq line) (str/starts-with? line "data: ") (not= line "data: [DONE]"))
    (json/parse-string (subs line 6) true)))

(defn- accumulate [ev state]
  [(update state :vals (fnil conj []) (:v ev)) ev])

(defn- now [] (System/currentTimeMillis))

;;; ============================================================
;;; 测试
;;; ============================================================

(deftest incremental-streaming-test
  (testing "token 随每块到达而逐个回调（真增量），on-complete 拿到累积状态"
    (let [lines ["data: {\"v\":1}" "" "data: {\"v\":2}" "" "data: {\"v\":3}" "" "data: [DONE]"]
          {:keys [server port captured]}
          (start-server (fn [ex] (write-sse-chunks! ex lines 60)))
          tokens (atom [])
          times  (atom [])
          final  (promise)]
      (try
        (let [{:keys [future]}
              (sc/post-stream-async (str "http://127.0.0.1:" port "/sse")
                {:headers {"Authorization" "Bearer secret"}
                 :body {:hello "world"}
                 :parse-fn parse-data
                 :process-fn accumulate
                 :initial-state {}
                 :on-token (fn [t] (swap! tokens conj t) (swap! times conj (now)))
                 :on-complete (fn [state] (deliver final state))
                 :on-error (fn [e] (deliver final [:error e]))})]
          @future
          (let [state (deref final 5000 :timeout)]
            ;; token 分发正确
            (is (= [{:v 1} {:v 2} {:v 3}] @tokens))
            ;; on-complete 拿到累积状态
            (is (= {:vals [1 2 3]} state))
            ;; 真增量：3 块间隔 60ms，首尾 token 时间差应 >= ~100ms（非一次性爆出）
            (is (>= (- (last @times) (first @times)) 100)
                (str "token 时间差应体现增量，实际: " (- (last @times) (first @times)) "ms"))
            ;; 客户端默认补了 Content-Type，且 Authorization 透传
            (is (= "application/json" (:content-type @captured)))
            (is (= "Bearer secret" (:authorization @captured)))
            (is (= {:hello "world"} (json/parse-string (:body @captured) true)))))
        (finally (.stop server 0))))))

(deftest non-2xx-canonical-error-test
  (testing "非 2xx → on-error 收到 canonical error（按状态码分类），不调 on-complete"
    (let [{:keys [server port]}
          (start-server (fn [ex] (error-response! ex 401 "{\"error\":{\"message\":\"bad key\"}}")))
          result (promise)]
      (try
        (let [{:keys [future]}
              (sc/post-stream-async (str "http://127.0.0.1:" port "/x")
                {:headers {"Authorization" "Bearer x"}
                 :body {:a 1}
                 :parse-fn parse-data :process-fn accumulate :initial-state {}
                 :on-token (fn [_] (deliver result :unexpected-token))
                 :on-complete (fn [_] (deliver result :unexpected-complete))
                 :on-error (fn [e] (deliver result e))
                 :provider :test})]
          @future
          (let [e (deref result 5000 :timeout)]
            (is (map? e))
            (is (= :auth-error (:type e)))     ;; 401 → auth，不可重试
            (is (false? (:retryable? e)))
            (is (= 401 (:status e)))
            (is (= :test (:provider e)))
            ;; 嵌套 {:error {:message}} 提取为字符串
            (is (= "bad key" (:message e)))))
        (finally (.stop server 0))))))

(deftest explicit-content-type-respected-test
  (testing "调用方显式传 Content-Type 时不被默认值覆盖"
    (let [{:keys [server port captured]}
          (start-server (fn [ex] (write-sse-chunks! ex ["data: [DONE]"] 0)))
          done (promise)]
      (try
        (let [{:keys [future]}
              (sc/post-stream-async (str "http://127.0.0.1:" port "/x")
                {:headers {"Content-Type" "application/xml" "Authorization" "Bearer x"}
                 :body "<x/>"
                 :parse-fn parse-data :process-fn accumulate :initial-state {}
                 :on-token (fn [_])
                 :on-complete (fn [_] (deliver done :ok))
                 :on-error (fn [e] (deliver done [:err e]))})]
          @future
          (deref done 5000 :timeout)
          (is (= "application/xml" (:content-type @captured))))
        (finally (.stop server 0))))))

;;; ============================================================
;;; post-stream-sync 建链重试（opt-in）
;;; ============================================================

(defn- start-flaky-server
  "前 fail-times 次请求回 status（如 503），之后走 SSE 成功流。返回含 :attempts 计数。"
  [fail-times status lines]
  (let [attempts (atom 0)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ ex]
          (let [n (swap! attempts inc)]
            (if (<= n fail-times)
              (error-response! ex status "{\"error\":{\"message\":\"busy\"}}")
              (write-sse-chunks! ex lines 0))))))
    (.start server)
    {:server server :port (.getPort (.getAddress server)) :attempts attempts}))

(deftest sync-retry-transient-then-success-test
  (testing ":retry 开启时，503 建链失败退避重试后成功；token 只流出一次"
    (let [{:keys [server port attempts]}
          (start-flaky-server 1 503 ["data: {\"v\":1}" "" "data: [DONE]"])
          tokens (atom [])]
      (try
        (let [resp (sc/post-stream-sync (str "http://127.0.0.1:" port "/x")
                     {:headers {} :body {:a 1}
                      :parse-fn parse-data :process-fn accumulate
                      :make-initial-state (fn [] {})
                      :build-response identity
                      :on-token (fn [t] (swap! tokens conj t))
                      :retry {:max-retries 2 :base-delay 1 :max-delay 5}
                      :provider :test})]
          (is (= 2 @attempts) "首次 503 + 重试成功 = 2 次请求")
          (is (= {:vals [1]} resp))
          (is (= [{:v 1}] @tokens) "token 不重复"))
        (finally (.stop server 0))))))

(deftest sync-retry-not-on-non-transient-test
  (testing "401（不可重试）即使开了 :retry 也立即抛出，只打一次"
    (let [{:keys [server port attempts]} (start-flaky-server 99 401 [])]
      (try
        (let [e (try (sc/post-stream-sync (str "http://127.0.0.1:" port "/x")
                       {:headers {} :body {:a 1}
                        :parse-fn parse-data :process-fn accumulate
                        :make-initial-state (fn [] {})
                        :build-response identity
                        :on-token (fn [_])
                        :retry {:max-retries 3 :base-delay 1}
                        :provider :test})
                     nil
                     (catch clojure.lang.ExceptionInfo ex (ex-data ex)))]
          (is (= :auth-error (:type e)))
          (is (= 1 @attempts) "不可重试错误不应重试"))
        (finally (.stop server 0))))))

(deftest sync-no-retry-by-default-test
  (testing "未开 :retry 时 503 直接抛出，只打一次（默认行为不变）"
    (let [{:keys [server port attempts]} (start-flaky-server 99 503 [])]
      (try
        (let [e (try (sc/post-stream-sync (str "http://127.0.0.1:" port "/x")
                       {:headers {} :body {:a 1}
                        :parse-fn parse-data :process-fn accumulate
                        :make-initial-state (fn [] {})
                        :build-response identity
                        :on-token (fn [_])
                        :provider :test})
                     nil
                     (catch clojure.lang.ExceptionInfo ex (ex-data ex)))]
          (is (true? (:retryable? e)))
          (is (= 1 @attempts)))
        (finally (.stop server 0))))))

(deftest cancel-stops-upstream-test
  (testing "cancel 后停止接收后续 token（取消上游）"
    (let [;; 20 块、每块间隔 80ms，给 cancel 留出时间
          lines (interleave (map #(str "data: {\"v\":" % "}") (range 20)) (repeat ""))
          {:keys [server port]}
          (start-server (fn [ex] (try (write-sse-chunks! ex lines 80)
                                      (catch Exception _ nil))))  ;; 客户端断开会抛，忽略
          tokens (atom [])
          cancel-holder (atom nil)]
      (try
        (let [{:keys [future cancel]}
              (sc/post-stream-async (str "http://127.0.0.1:" port "/x")
                {:headers {"Authorization" "Bearer x"}
                 :body {:a 1}
                 :parse-fn parse-data :process-fn accumulate :initial-state {}
                 :on-token (fn [t]
                             (swap! tokens conj t)
                             ;; 收到第 3 个 token 后取消
                             (when (= 3 (count @tokens)) (@cancel-holder)))
                 :on-complete (fn [_])
                 :on-error (fn [_])})]
          (reset! cancel-holder cancel)
          ;; 等一段时间让流推进（若没取消，20 块 *80ms ≈ 1.6s 会全部到达）
          (Thread/sleep 800)
          ;; 取消生效后，token 数应远小于 20（停在取消点附近）
          (is (< (count @tokens) 20)
              (str "cancel 后不应收满 20 个 token，实际: " (count @tokens))))
        (finally (.stop server 0))))))
