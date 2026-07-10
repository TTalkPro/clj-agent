(ns im.ttalk.agent.provider.http.client-test
  "HTTP 客户端模块测试

   集成用例走本地 com.sun.net.httpserver（零依赖、不联网）——
   曾用 httpbin.org，外网超时会导致 assertion 数波动（CI flake）。"
  (:require [clojure.test :refer [deftest testing is are]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [im.ttalk.agent.provider.http.client :as http])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]))

;; ============================================================
;; 本地测试服务器
;; ============================================================

(defn- start-echo-server
  "起本地 HTTP 服务：记录请求方法/body，响应 200 + {:json <请求体解析结果>}
   （模拟 httpbin.org/post 的回显形状）。返回 {:server :port :captured}。"
  []
  (let [captured (atom nil)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ exchange]
          (let [req-body (slurp (.getRequestBody exchange))
                parsed (when (seq req-body)
                         (try (json/parse-string req-body true) (catch Exception _ nil)))]
            (reset! captured {:method (.getRequestMethod exchange) :body parsed})
            (let [b (.getBytes (json/generate-string {:json parsed :ok true}) "UTF-8")]
              (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
              (.sendResponseHeaders exchange 200 (count b))
              (with-open [os (.getResponseBody exchange)] (.write os b)))))))
    (.start server)
    {:server server :port (.getPort (.getAddress server)) :captured captured}))

;; ============================================================
;; URL 工具函数测试
;; ============================================================

(deftest url-encode-test
  (testing "基本编码"
    (is (= "hello" (http/url-encode "hello")))
    (is (= "hello+world" (http/url-encode "hello world"))))

  (testing "特殊字符编码"
    (is (= "%E4%B8%AD%E6%96%87" (http/url-encode "中文")))
    (is (= "a%26b%3Dc" (http/url-encode "a&b=c"))))

  (testing "nil 处理"
    (is (nil? (http/url-encode nil)))))

(deftest build-query-string-test
  (testing "单个参数"
    (is (= "q=test" (http/build-query-string {:q "test"}))))

  (testing "多个参数"
    (let [qs (http/build-query-string {:q "test" :page "1"})]
      (is (or (= "q=test&page=1" qs)
              (= "page=1&q=test" qs)))))

  (testing "特殊字符参数"
    (is (= "q=hello+world" (http/build-query-string {:q "hello world"}))))

  (testing "空参数"
    (is (nil? (http/build-query-string {})))
    (is (nil? (http/build-query-string nil)))))

(deftest build-url-test
  (testing "无参数"
    (is (= "https://api.example.com"
           (http/build-url "https://api.example.com" nil)))
    (is (= "https://api.example.com"
           (http/build-url "https://api.example.com" {}))))

  (testing "添加参数"
    (is (= "https://api.example.com?q=test"
           (http/build-url "https://api.example.com" {:q "test"}))))

  (testing "已有参数时追加"
    (is (= "https://api.example.com?a=1&q=test"
           (http/build-url "https://api.example.com?a=1" {:q "test"})))))

;; ============================================================
;; SSE 解析测试
;; ============================================================

;; ============================================================
;; 配置测试
;; ============================================================

(deftest default-timeout-test
  (testing "默认超时值"
    (is (= 30000 http/*default-timeout*))))

(deftest default-headers-test
  (testing "默认请求头"
    (is (map? http/*default-headers*))
    (is (contains? http/*default-headers* "Accept"))
    (is (contains? http/*default-headers* "Content-Type"))))

;; ============================================================
;; 集成测试（本地服务，不联网）
;; ============================================================

(deftest get-request-test
  (testing "GET 请求（本地回显服务）"
    (let [{:keys [server port]} (start-echo-server)]
      (try
        (let [response (http/get (str "http://127.0.0.1:" port "/get") :timeout 5000)]
          (is (map? response))
          (is (= 200 (:status response)))
          (is (true? (:success? response)))
          (is (true? (get-in response [:body :ok]))))
        (finally (.stop server 0))))))

(deftest post-json-test
  (testing "POST JSON 请求（本地回显服务，模拟 httpbin 形状）"
    (let [{:keys [server port captured]} (start-echo-server)]
      (try
        (let [response (http/post-json (str "http://127.0.0.1:" port "/post")
                                       {:name "test" :value 123}
                                       {:timeout 5000})]
          (is (map? response))
          (is (= 200 (:status response)))
          (is (= "test" (get-in response [:body :json :name])))
          (is (= {:name "test" :value 123} (:body @captured))))
        (finally (.stop server 0))))))

(deftest patch-request-test
  (testing "PATCH 走通用 .method（回归：HttpRequest.Builder 无 .PATCH 方法，曾必然运行时崩溃）"
    (let [{:keys [server port captured]} (start-echo-server)]
      (try
        (let [response (http/patch (str "http://127.0.0.1:" port "/patch")
                                   :body {:op "replace"}
                                   :timeout 5000)]
          (is (= 200 (:status response)))
          (is (= "PATCH" (:method @captured)))
          (is (= {:op "replace"} (:body @captured))))
        (finally (.stop server 0))))))

;; ============================================================
;; 模拟请求测试（不需要网络）
;; ============================================================

(deftest request-to-invalid-url-test
  (testing "请求无效 URL"
    (let [response (http/get "http://invalid.local.host.that.does.not.exist:9999"
                             :timeout 1000)]
      (is (map? response))
      (is (not (:success? response)))
      (is (or (:error response)
              (not= 200 (:status response)))))))

(deftest request-error-handling-test
  (testing "错误响应结构"
    (let [response (http/get "http://localhost:1"
                             :timeout 100)]
      (is (map? response))
      (is (contains? response :success?))
      (is (false? (:success? response))))))

;; ============================================================
;; 动态绑定测试
;; ============================================================

(deftest dynamic-timeout-test
  (testing "修改默认超时"
    (binding [http/*default-timeout* 5000]
      (is (= 5000 http/*default-timeout*)))))

(deftest dynamic-headers-test
  (testing "修改默认请求头"
    (binding [http/*default-headers* {"X-Custom" "value"}]
      (is (= {"X-Custom" "value"} http/*default-headers*)))))
