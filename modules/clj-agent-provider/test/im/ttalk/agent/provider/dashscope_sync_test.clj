(ns im.ttalk.agent.provider.dashscope-sync-test
  "DashScope 同步路径回归测试（本地 HTTP 服务，不联网）。

   回归背景：迁移到 http/client wrapper 后（bdbefd3），do-request 曾对已被
   :as :json 解析成 map 的 :body 再次 json/parse-string → ClassCastException →
   成功响应也被误报 :parse-error。本测试钉住修复后的行为。"
  (:require [clojure.test :refer [deftest testing is]]
            [cheshire.core :as json]
            [im.ttalk.agent.model :as model]
            [im.ttalk.agent.model.content :as content]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.provider.dashscope :as dashscope])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]))

(defn- start-server [status body]
  (let [captured (atom nil)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ exchange]
          (reset! captured (slurp (.getRequestBody exchange)))
          (let [b (.getBytes (json/generate-string body) "UTF-8")]
            (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
            (.sendResponseHeaders exchange status (count b))
            (with-open [os (.getResponseBody exchange)] (.write os b))))))
    (.start server)
    {:server server :port (.getPort (.getAddress server)) :captured captured}))

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

(deftest deftool-input-schema-not-dropped-test
  (testing "deftool 风格工具（:input_schema）参数不被丢成空对象（回归：旧手写转换只认 :parameters）"
    (let [{:keys [server port captured]}
          (start-server 200 {:output {:choices [{:finish_reason "stop"
                                                 :message {:role "assistant" :content "ok"}}]}})
          input-schema {:type "object"
                        :properties {:city {:type "string" :description "城市"}}
                        :required ["city"]}]
      (try
        (dashscope/call-dashscope {:model "qwen-plus"}
                                  [{:role "user" :content "北京天气"}]
                                  [{:name :get-weather
                                    :description "查天气"
                                    :input_schema input-schema}]
                                  {:api-key "k" :base-url (str "http://127.0.0.1:" port "/")})
        (let [sent (json/parse-string @captured true)
              tool (first (get-in sent [:parameters :tools]))]
          (is (= "get-weather" (get-in tool [:function :name])))
          (is (= (json/parse-string (json/generate-string input-schema) true)
                 (get-in tool [:function :parameters]))
              "deftool 的 :input_schema 必须落到 wire 的 :parameters"))
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

;;; ============================================================
;;; 中立消息 → DashScope wire（协议边界）
;;; ============================================================

(deftest neutral-messages-converted-at-protocol-boundary-test
  (testing "多轮工具历史转成 DashScope（= OpenAI 同构）形状，而不是把中立键直接发出去"
    ;; 回归：call-llm 曾把中立消息原样塞进请求体——:tool-calls / :args / keyword role
    ;; DashScope 一个都不认，多轮工具调用的历史等于没发。
    (let [{:keys [server port captured]}
          (start-server 200 {:output {:choices [{:finish_reason "stop"
                                                 :message {:role "assistant" :content "ok"}}]}})
          provider (dashscope/create-provider {:api-key "k"
                                               :base-url (str "http://127.0.0.1:" port "/")})]
      (try
        (model/call-llm provider {:model "qwen-plus"}
                        [(msg/user "北京天气？")
                         (msg/assistant-tool-calls [(msg/tool-call "c1" "get_weather" {:city "北京"})])
                         (msg/tool-result "c1" "get_weather" "晴 22°C")]
                        [])
        (let [sent (json/parse-string @captured true)
              [user assistant tool] (get-in sent [:input :messages])]
          (is (= {:role "user" :content "北京天气？"} user))
          (is (= "assistant" (:role assistant)))
          (is (= "get_weather" (get-in assistant [:tool_calls 0 :function :name])))
          (is (= "{\"city\":\"北京\"}" (get-in assistant [:tool_calls 0 :function :arguments]))
              "arguments 必须是 JSON 字符串，不是 map")
          (is (= {:role "tool" :tool_call_id "c1" :name "get_weather" :content "晴 22°C"} tool)
              "DashScope 的 tool 结果消息文档里带 name（OpenAI 侧多给无害）")
          (is (not (contains? assistant :tool-calls)) "中立键不得泄漏到 wire"))
        (finally (.stop server 0))))))

(deftest multimodal-rejected-with-actionable-error-test
  (testing "原生 text-generation 端点不收内容部件 → 边界处报错并指路，不发出去等 400"
    (let [provider (dashscope/create-provider {:api-key "k"})
          e (try (model/call-llm provider {:model "qwen-vl-plus"}
                                 [(msg/user [(content/text-part "这是什么")
                                             (content/image-part "https://x/a.png")])]
                                 [])
                 nil
                 (catch clojure.lang.ExceptionInfo ex (ex-data ex)))]
      (is (some? e))
      (is (= :validation-error (:type e)))
      (is (false? (:retryable? e)))
      (is (= :dashscope (:provider e))))))
