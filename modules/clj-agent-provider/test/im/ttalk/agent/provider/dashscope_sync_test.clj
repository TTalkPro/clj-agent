(ns im.ttalk.agent.provider.dashscope-sync-test
  "DashScope 同步路径回归测试（本地 HTTP 服务，不联网）。

   回归背景：迁移到 http/client wrapper 后（bdbefd3），do-request 曾对已被
   :as :json 解析成 map 的 :body 再次 json/parse-string → ClassCastException →
   成功响应也被误报 :parse-error。本测试钉住修复后的行为。"
  (:require [clojure.test :refer [deftest testing is]]
            [cheshire.core :as json]
            [im.ttalk.agent.provider.dashscope :as dashscope])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]))

(defn- start-server [status body]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ exchange]
          (let [b (.getBytes (json/generate-string body) "UTF-8")]
            (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
            (.sendResponseHeaders exchange status (count b))
            (with-open [os (.getResponseBody exchange)] (.write os b))))))
    (.start server)
    {:server server :port (.getPort (.getAddress server))}))

(deftest sync-success-returns-parsed-response-test
  (testing "成功响应返回解析后的 OpenAI 兼容 map（回归：曾必抛 :parse-error）"
    (let [{:keys [server port]}
          (start-server 200 {:output {:choices [{:finish_reason "stop"
                                                 :message {:role "assistant" :content "杭州"}}]}
                             :usage {:input_tokens 5 :output_tokens 2}
                             :request_id "req-1"})]
      (try
        (let [resp (dashscope/call-dashscope {:model "qwen-plus"}
                                             [{:role "user" :content "hi"}]
                                             []
                                             {:api-key "k"
                                              :base-url (str "http://127.0.0.1:" port "/")})]
          (is (= "杭州" (get-in resp [:choices 0 :message :content])))
          (is (= "stop" (get-in resp [:choices 0 :finish_reason]))))
        (finally (.stop server 0))))))

(deftest sync-4xx-throws-canonical-error-test
  (testing "4xx → canonical error，DashScope 错误体 :code/:request_id 并入 :context"
    (let [{:keys [server port]}
          (start-server 401 {:code "InvalidApiKey" :message "无效的 key" :request_id "r-9"})]
      (try
        (let [e (try (dashscope/call-dashscope {:model "qwen-plus"}
                                               [{:role "user" :content "hi"}] []
                                               {:api-key "bad"
                                                :base-url (str "http://127.0.0.1:" port "/")})
                     nil
                     (catch clojure.lang.ExceptionInfo ex (ex-data ex)))]
          (is (some? e) "4xx 必须抛出，不能当正常响应返回")
          (is (= :auth-error (:type e)))
          (is (false? (:retryable? e)))
          (is (= "InvalidApiKey" (get-in e [:context :code])))
          (is (= "r-9" (get-in e [:context :request-id]))))
        (finally (.stop server 0))))))
