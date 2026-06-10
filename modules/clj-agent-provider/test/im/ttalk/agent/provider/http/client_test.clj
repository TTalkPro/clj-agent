(ns im.ttalk.agent.provider.http.client-test
  "HTTP 客户端模块测试"
  (:require [clojure.test :refer [deftest testing is are]]
            [clojure.string :as str]
            [im.ttalk.agent.provider.http.client :as http]))

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
;; 集成测试（可选，需要网络）
;; ============================================================

;; 注意：以下测试需要网络连接，可能会被跳过
;; 使用 httpbin.org 作为测试服务

;; 注意：http-kit 网络失败不抛异常而是返回 {:status 0 :error ...}，
;; 因此跳过逻辑须判断 :error 而非 catch。

(deftest ^:integration get-request-test
  (testing "GET 请求到 httpbin"
    (let [response (http/get "https://httpbin.org/get"
                             :timeout 10000)]
      (if (:error response)
        (do (println "Skipping integration test - network unavailable:" (:error response))
            (is true "skipped - network unavailable"))
        (do
          (is (map? response))
          (is (contains? response :status))
          (is (contains? response :body))
          (when (:success? response)
            (is (= 200 (:status response)))))))))

(deftest ^:integration post-json-test
  (testing "POST JSON 请求到 httpbin"
    (let [response (http/post-json "https://httpbin.org/post"
                                   {:name "test" :value 123}
                                   {:timeout 10000})]
      (if (:error response)
        (do (println "Skipping integration test - network unavailable:" (:error response))
            (is true "skipped - network unavailable"))
        (do
          (is (map? response))
          (when (:success? response)
            (is (= 200 (:status response)))
            (is (= "test" (get-in response [:body :json :name])))))))))

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
