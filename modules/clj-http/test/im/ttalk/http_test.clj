(ns im.ttalk.http-test
  "H-01：client / request / request-async / 头工具（本机回环服务，不打外部端点）。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.http :as http]
            [im.ttalk.http.test-server :as ts])
  (:import (com.sun.net.httpserver HttpExchange)
           (java.io IOException InputStream)
           (java.net ServerSocket)
           (java.net.http HttpClient HttpClient$Redirect HttpClient$Version HttpTimeoutException)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent ExecutionException TimeUnit)))

(defn- echo
  "把收到的方法 / 路径 / 查询 / 头 / 正文写回成 EDN。"
  [^HttpExchange ex]
  (let [hs (into {} (map (fn [[k v]] [(.toLowerCase ^String k) (vec v)])) (.getRequestHeaders ex))]
    (ts/respond! ex 200 {"Content-Type" "application/edn" "X-Multi" ["a" "b"]}
                 (pr-str {:method (.getRequestMethod ex)
                          :path (.getPath (.getRequestURI ex))
                          :query (.getRawQuery (.getRequestURI ex))
                          :headers hs
                          :body (ts/read-body ex)}))))

(defn- edn-body [resp] (read-string (:body resp)))

(deftest methods-query-and-body
  (ts/with-server [url echo]
    (doseq [m [:get :post :put :patch :delete]]
      (let [r (http/request {:method m :url (str url "/x") :body (when (#{:post :put :patch} m) "{\"a\":1}")})]
        (is (= 200 (:status r)))
        (is (= (.toUpperCase (name m)) (:method (edn-body r))))))
    (testing "query 编码与已有 ?"
      (is (= "q=a+b&n=1" (:query (edn-body (http/request {:url (str url "/s") :query-params {:q "a b" :n 1}})))))
      (is (= "x=1&q=%E4%BD%A0" (:query (edn-body (http/request {:url (str url "/s?x=1") :query-params {"q" "你"}}))))))
    (testing "正文 UTF-8；字节正文也收"
      (is (= "你好" (:body (edn-body (http/request {:method :post :url url :body "你好"})))))
      (is (= "ab" (:body (edn-body (http/request {:method :post :url url :body (.getBytes "ab")}))))))))

(deftest content-type-default-and-explicit
  (ts/with-server [url echo]
    (is (= ["application/json"] (get-in (edn-body (http/request {:method :post :url url :body "{}"})) [:headers "content-type"])))
    (is (= ["text/plain"] (get-in (edn-body (http/request {:method :post :url url :body "x"
                                                           :headers {"content-type" "text/plain"}}))
                                  [:headers "content-type"])))
    (is (= ["text/plain"] (get-in (edn-body (http/request {:method :post :url url :body "x"
                                                           :headers {:Content-Type "text/plain"}}))
                                  [:headers "content-type"]))
        "关键字头名")
    (testing "没正文不补"
      (is (nil? (get-in (edn-body (http/request {:url url})) [:headers "content-type"]))))))

(deftest restricted-headers-are-dropped-not-thrown
  (ts/with-server [url echo]
    (let [r (http/request {:url url :headers {"Host" "evil" "Connection" "close" "Content-Length" "9" "X-Ok" 1}})]
      (is (= 200 (:status r)))
      (is (= ["1"] (get-in (edn-body r) [:headers "x-ok"]))))))

(deftest response-shape-and-as
  (ts/with-server [url echo]
    (let [r (http/request {:url url})]
      (is (= ["a" "b"] (get-in r [:headers "x-multi"])))
      (is (= ["a" "b"] (http/header-values r "X-MULTI")))
      (is (= "application/edn" (http/media-type r)))
      (is (string? (:body r)))
      (is (http/success? r)))
    (is (bytes? (:body (http/request {:url url :as :bytes}))))
    (let [r (http/request {:url url :as :input-stream})]
      (is (instance? InputStream (:body r)))
      (is (map? (read-string (with-open [^InputStream in (:body r)] (String. (.readAllBytes in) StandardCharsets/UTF_8))))))
    (is (nil? (:body (http/request {:url url :as :discard}))))))

(defn- dead-port []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(deftest failures
  (testing "同步：IO 失败原样抛"
    (is (thrown? IOException (http/request {:url (str "http://127.0.0.1:" (dead-port) "/")}))))
  (testing "同步：请求超时"
    (ts/with-server [url (fn [ex] (Thread/sleep 1500) (ts/respond! ex 200 "late"))]
      (is (thrown? HttpTimeoutException (http/request {:url url :timeout-ms 200})))))
  (testing "异步：异常完成，不同步抛"
    (let [f (http/request-async {:url (str "http://127.0.0.1:" (dead-port) "/")})]
      (is (instance? IOException (try @f nil (catch ExecutionException e (.getCause e))))))
    (let [f (http/request-async {:url nil})]
      (is (instance? IllegalArgumentException (try @f nil (catch ExecutionException e (.getCause e))))))))

(deftest async-success
  (ts/with-server [url echo]
    (let [r (.get (http/request-async {:method :post :url url :body "{}"}) 5 TimeUnit/SECONDS)]
      (is (= 200 (:status r)))
      (is (= "POST" (:method (edn-body r)))))))

(deftest client-options
  (let [c (http/client)]
    (is (instance? HttpClient c))
    (is (= HttpClient$Version/HTTP_1_1 (.version c)))
    (is (= HttpClient$Redirect/NEVER (.followRedirects c)))
    (is (= 30000 (.toMillis ^java.time.Duration (.get (.connectTimeout c)))))
    (is (.isEmpty (.proxy c))))
  (let [c (http/client {:version :http-2 :redirect :normal :connect-timeout-ms nil :proxy "http://127.0.0.1:9"})]
    (is (= HttpClient$Version/HTTP_2 (.version c)))
    (is (= HttpClient$Redirect/NORMAL (.followRedirects c)))
    (is (.isEmpty (.connectTimeout c)))
    (is (.isPresent (.proxy c))))
  (is (identical? (http/default-client) (http/default-client)))
  (is (thrown? IllegalArgumentException (http/client {:proxy 42}))))

(deftest redirect-follow
  (ts/with-server [url (fn [^HttpExchange ex]
                         (if (= "/a" (.getPath (.getRequestURI ex)))
                           (ts/respond! ex 302 {"Location" "/b"} "")
                           (ts/respond! ex 200 "b")))]
    (is (= 302 (:status (http/request {:url (str url "/a")}))) "缺省不跟（同 JDK 缺省）")
    (is (= "b" (:body (http/request {:url (str url "/a") :client (http/client {:redirect :normal})}))))))

;; provider-reliability.md §1.3：java.net.http 给的是 List，旧代码 (str v) 得 "[120]"
(deftest header-tools-accept-every-value-shape
  (doseq [v ["120" ["120"] (java.util.ArrayList. ["120"]) (java.util.List/of "120" "x")]]
    (is (= "120" (http/header {"Retry-After" v} "retry-after")) (pr-str (class v))))
  (is (= "1" (http/header {:x-request-id "1"} "X-Request-Id")) "关键字头名")
  (is (= "1" (http/header {:headers {"a" "1"}} "A")) "带 :headers 的响应")
  (is (nil? (http/header {} "a")))
  (is (= [] (http/header-values nil "a")))
  (is (= {"A" "2"} (http/assoc-header {"a" "1"} "A" "2")))
  (is (= {"b" "1"} (http/dissoc-header {"A" "x" "b" "1"} "a")))
  (is (= [["a" "1"] ["a" "2"] ["n" "3"]] (vec (http/header-seq {"a" ["1" "2"] :n 3}))))
  (is (= "text/html" (http/media-type {"content-type" "Text/HTML; charset=gbk"})))
  (is (= "GBK" (str (http/charset {"content-type" "text/html; charset=\"gbk\""}))))
  (is (= "UTF-8" (str (http/charset {"content-type" "text/html; charset=nonsense"})))))

(deftest url-helpers
  (is (= "https://h/p" (http/with-query "https://h/p" nil)))
  (is (= "https://h/p?a=1&b=x+y" (http/with-query "https://h/p" (array-map :a 1 :b "x y"))))
  (is (nil? (http/url-encode nil))))
