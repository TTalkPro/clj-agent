(ns im.ttalk.agent.provider.http.reliability-http-test
  "designs/provider-reliability.md 的真实 HTTP 行为（本地 com.sun.net.httpserver，不联网）：
   欠费 429 不重试且带 Retry-After（§1）、首字节 / 空闲超时（§4）、模型名核对（§4）、
   限流快照挂到同步 / 流式响应（§6）、HTTP 200 业务错（§1）。"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [im.ttalk.agent.chat-model :as cm]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.request :as req]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.provider.anthropic :as anthropic]
            [im.ttalk.agent.provider.common.openai-compat :as compat]
            [im.ttalk.agent.provider.http.stream-client :as sc]
            [im.ttalk.agent.provider.openai :as openai])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]))

(defn- start-server [handler-fn]
  (let [hits (atom 0)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ ex]
          (swap! hits inc)
          (slurp (.getRequestBody ^HttpExchange ex))
          (try (handler-fn ex @hits)
               (catch java.io.IOException _ nil)))))
    (.setExecutor server (java.util.concurrent.Executors/newCachedThreadPool))
    (.start server)
    {:server server :port (.getPort (.getAddress server)) :hits hits}))

(defn- url [port path] (str "http://127.0.0.1:" port path))

(defn- respond! [^HttpExchange ex status body & [headers]]
  (let [b (.getBytes ^String body "UTF-8")]
    (doseq [[k v] headers] (.add (.getResponseHeaders ex) k v))
    (.sendResponseHeaders ex status (count b))
    (with-open [os (.getResponseBody ex)] (.write os b))))

(defn- sse! [^HttpExchange ex lines & {:keys [delay-ms headers before-ms]}]
  (doseq [[k v] headers] (.add (.getResponseHeaders ex) k v))
  (.add (.getResponseHeaders ex) "Content-Type" "text/event-stream")
  (.sendResponseHeaders ex 200 0)
  (with-open [os (.getResponseBody ex)]
    (when before-ms (.flush os) (Thread/sleep (long before-ms)))
    (doseq [l lines]
      (.write os (.getBytes (str l "\n") "UTF-8"))
      (.flush os)
      (when delay-ms (Thread/sleep (long delay-ms))))))

(defn- chunk [content & [model]]
  (str "data: {\"id\":\"c1\",\"model\":\"" (or model "m") "\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
       content "\"}}]}"))

(def ^:private finish
  "data: {\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}")

(def ^:private quota-body
  "{\"error\":{\"message\":\"You exceeded your current quota\",\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}")

(defn- run-stream [port opts]
  (let [result (promise)
        tokens (atom [])
        {:keys [future]}
        (sc/post-stream-async (url port "/s")
          (merge {:headers {} :body {:a 1}
                  :parse-fn (fn [l] (when (str/starts-with? l "data: ") (subs l 6)))
                  :process-fn (fn [ev st] [(update st :n (fnil inc 0)) {:token ev}])
                  :initial-state {}
                  :on-token #(swap! tokens conj %)
                  :on-complete #(deliver result [:ok %])
                  :on-error #(deliver result [:err %])
                  :provider :test}
                 opts))]
    (try @future (catch Throwable _ nil))
    [(deref result 5000 :timeout) @tokens]))

(deftest stream-quota-429-test
  (testing "流式 429 + insufficient_quota → :quota-exhausted-error，带 Retry-After"
    (let [{:keys [server port]} (start-server (fn [ex _] (respond! ex 429 quota-body {"Retry-After" "9"})))]
      (try
        (let [[[tag e] _] (run-stream port {})]
          (is (= :err tag))
          (is (= :quota-exhausted-error (:type e)))
          (is (false? (:retryable? e)))
          (is (= 9000 (:retry-after-ms e)) "流式非 2xx 现在也带响应头"))
        (finally (.stop server 0)))))
  (testing "ChatModel 同步路径：欠费 429 只打一次（过去会被当限流退避重试）"
    (let [{:keys [server port hits]} (start-server (fn [ex _] (respond! ex 429 quota-body)))]
      (try
        (let [p (openai/create-provider {:api-key "k" :base-url (url port "/v1")})
              m (cm/create-chat-model p {:model "gpt" :retry {:max-retries 3 :base-delay 1}})]
          (is (= :quota-exhausted-error
                 (try (cm/call m (req/chat-request [(msg/user "hi")])) nil
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
          (is (= 1 @hits)))
        (finally (.stop server 0))))))

(deftest stream-watchdog-test
  (testing "首字节超时 → :timeout-error :first-byte-timeout（可重试），无 token"
    (let [{:keys [server port]} (start-server (fn [ex _] (sse! ex [(chunk "a")] :before-ms 800)))]
      (try
        (let [t0 (System/currentTimeMillis)
              [[tag e] tokens] (run-stream port {:first-byte-ms 200})]
          (is (= :err tag))
          (is (= :first-byte-timeout (:code e)))
          (is (true? (:retryable? e)))
          (is (empty? tokens))
          (is (< (- (System/currentTimeMillis) t0) 700) "没有等到服务端吐字"))
        (finally (.stop server 0)))))
  (testing "吐一行后静默 → :idle-timeout"
    (let [{:keys [server port]} (start-server (fn [ex _] (sse! ex [(chunk "a") (chunk "b")] :delay-ms 800)))]
      (try
        (let [[[tag e] tokens] (run-stream port {:idle-ms 250})]
          (is (= :err tag))
          (is (= :idle-timeout (:code e)))
          (is (= 1 (count tokens))))
        (finally (.stop server 0)))))
  (testing "心跳行续命：每 100ms 一行，idle 250ms 不超时"
    (let [{:keys [server port]} (start-server (fn [ex _] (sse! ex [": ping" ": ping" (chunk "a") ": ping" finish]
                                                               :delay-ms 100)))]
      (try
        (let [[[tag _] _] (run-stream port {:idle-ms 250 :first-byte-ms 250})]
          (is (= :ok tag)))
        (finally (.stop server 0))))))

(deftest model-check-and-rate-limit-test
  (testing "流式模型名不一致 → :model-mismatch，不投递 token"
    (let [{:keys [server port]} (start-server (fn [ex _] (sse! ex [(chunk "x" "other-model") finish "data: [DONE]"])))]
      (try
        (let [tokens (atom [])]
          (is (= :model-mismatch
                 (try (compat/call-api-stream (url port "/c") "k" {:model "m" :check-model? true}
                                              [{:role "user" :content "hi"}] nil #(swap! tokens conj %))
                      nil
                      (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))
          (is (empty? @tokens)))
        (finally (.stop server 0)))))
  (testing "流式：响应头的限流信息挂到最终响应，ChatModel 读得到"
    (let [{:keys [server port]} (start-server
                                  (fn [ex _] (sse! ex [(chunk "hi") finish "data: [DONE]"]
                                                   :headers {"x-ratelimit-remaining-tokens" "4321"
                                                             "x-ratelimit-limit-requests" "60"})))]
      (try
        (let [p (openai/create-provider {:api-key "k" :base-url (url port "/v1")})
              m (cm/create-chat-model p {:model "m"})
              r (cm/stream-call m (req/chat-request [(msg/user "hi")]) (fn [_]))]
          (is (= "hi" (response/response-text r)))
          (is (= {:tokens-remaining 4321 :requests-limit 60} (response/response-rate-limit r))))
        (finally (.stop server 0)))))
  (testing "同步：限流头 + HTTP 200 业务错（MiniMax base_resp）"
    (let [{:keys [server port]}
          (start-server (fn [ex n]
                          (if (= 1 n)
                            (respond! ex 200 "{\"id\":\"1\",\"model\":\"m\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"
                                      {"x-ratelimit-remaining-requests" "7"})
                            (respond! ex 200 "{\"base_resp\":{\"status_code\":1039,\"status_msg\":\"余额不足\"}}"))))]
      (try
        (let [b (compat/call-api (url port "/c") "k" {:model "m"} [{:role "user" :content "hi"}] nil)]
          (is (= 7 (get-in b [:rate-limit :requests-remaining]))))
        (is (= :quota-exhausted-error
               (try (anthropic/call-anthropic {:model "m" :api-key "k" :provider-name :minimax
                                               :base-url (url port "") :api-path "/v1/messages"
                                               :auth-scheme :bearer}
                                              [{:role "user" :content "hi"}] nil)
                    nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (finally (.stop server 0))))))

(deftest chat-model-stream-retry-over-http-test
  (testing "首字节超时（无 token 出门）→ ChatModel 流式重试成功"
    (let [{:keys [server port hits]}
          (start-server (fn [ex n]
                          (if (= 1 n)
                            (sse! ex [(chunk "late")] :before-ms 600)
                            (sse! ex [(chunk "ok") finish "data: [DONE]"]))))]
      (try
        (let [p (openai/create-provider {:api-key "k" :base-url (url port "/v1")})
              m (cm/create-chat-model p {:model "m" :first-byte-ms 200 :retry {:base-delay 1 :max-delay 2}})
              tokens (atom [])
              r (cm/stream-call m (req/chat-request [(msg/user "hi")]) #(swap! tokens conj (:token %)))]
          (is (= "ok" (response/response-text r)))
          (is (= ["ok"] (remove nil? @tokens)))
          (is (= 2 @hits))
          (is (= 2 (response/response-attempts r))))
        (finally (.stop server 0))))))
