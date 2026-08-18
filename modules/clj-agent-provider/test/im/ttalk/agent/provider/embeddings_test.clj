(ns im.ttalk.agent.provider.embeddings-test
  "Embedding provider 集成测试。

   用 JDK 内置 com.sun.net.httpserver 起本地服务（零依赖、不联网），
   断言**发出去的请求体**与**回来的归一化结果**，而不是只 stub 返回值——
   embedding 的坑几乎全在两端形状上（批次切片、顺序、维度参数）。"
  (:require [clojure.test :refer [deftest testing is]]
            [cheshire.core :as json]
            [im.ttalk.agent.model.embedding :as emb]
            [im.ttalk.agent.provider.anthropic :as anthropic]
            [im.ttalk.agent.provider.embeddings :as e])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]))

;;; ============================================================
;;; 本地测试服务器
;;; ============================================================

(defn- start-server
  "起本地 HTTP 服务，handler-fn 收到 (parsed-body) 返回 [status body-map]。
   返回 {:server :port :requests}，requests 记录每次收到的请求体与头。"
  [handler-fn]
  (let [requests (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ exchange]
          (let [raw (slurp (.getRequestBody exchange))
                body (try (json/parse-string raw true) (catch Exception _ raw))
                _ (swap! requests conj
                         {:path (.getPath (.getRequestURI exchange))
                          :authorization (.getFirst (.getRequestHeaders exchange) "Authorization")
                          :body body})
                [status resp] (handler-fn body)
                bytes (.getBytes (json/generate-string resp) "UTF-8")]
            (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
            (.sendResponseHeaders exchange status (count bytes))
            (with-open [os (.getResponseBody exchange)]
              (.write os bytes))))))
    (.start server)
    {:server server
     :port (.getPort (.getAddress server))
     :requests requests}))

(defn- stop! [{:keys [^HttpServer server]}] (.stop server 0))

(defn- openai-style-response
  "按请求里的 input 条数造 data 数组（index 故意倒序返回，考察重排）"
  [body]
  (let [n (count (:input body))]
    [200 {:object "list"
          :model (:model body)
          :data (->> (range n)
                     (map (fn [i] {:object "embedding"
                                   :index i
                                   :embedding [(double i) (double (:dimensions body 0))]}))
                     reverse
                     vec)
          :usage {:prompt_tokens (* 2 n) :total_tokens (* 2 n)}}]))

;;; ============================================================
;;; OpenAI 兼容形态
;;; ============================================================

(deftest openai-compat-embed-test
  (let [{:keys [port requests] :as srv} (start-server openai-style-response)]
    (try
      (let [p (e/create-provider :openai-compat
                                 {:base-url (str "http://127.0.0.1:" port "/v1")
                                  :api-key "test-key"
                                  :model "bge-m3"})
            result (e/embed p {:dimensions 256} ["甲" "乙" "丙"])]
        (testing "请求：端点、鉴权、模型、input、dimensions"
          (let [req (first @requests)]
            (is (= "/v1/embeddings" (:path req)))
            (is (= "Bearer test-key" (:authorization req)))
            (is (= "bge-m3" (get-in req [:body :model])))
            (is (= ["甲" "乙" "丙"] (get-in req [:body :input])))
            (is (= 256 (get-in req [:body :dimensions])))))

        (testing "响应：按服务端 index 重排，与入参同序等长"
          (is (= 3 (count (:embeddings result))))
          (is (= [[0.0 256.0] [1.0 256.0] [2.0 256.0]] (:embeddings result))))

        (testing "usage 归一化 + provider 标记"
          (is (= {:input-tokens 6 :total-tokens 6} (:usage result)))
          (is (= :openai-compat (:provider result)))
          (is (= "bge-m3" (:model result)))))
      (finally (stop! srv)))))

(deftest batching-test
  (testing "超过 batch-size 自动切片，结果按原序拼回，usage 累加"
    (let [{:keys [port requests] :as srv} (start-server openai-style-response)]
      (try
        (let [p (e/create-provider :openai-compat
                                   {:base-url (str "http://127.0.0.1:" port "/v1")
                                    :api-key "k" :model "m" :batch-size 2})
              texts ["a" "b" "c" "d" "e"]
              result (e/embed p texts)]
          (is (= 3 (count @requests)) "5 条 / 每片 2 条 = 3 次请求")
          (is (= [2 2 1] (mapv #(count (get-in % [:body :input])) @requests)))
          (is (= 5 (count (:embeddings result))))
          (is (= {:input-tokens 10 :total-tokens 10} (:usage result))))
        (finally (stop! srv))))))

(deftest embed-one-test
  (let [{:keys [port] :as srv} (start-server openai-style-response)]
    (try
      (let [p (e/create-provider :openai-compat
                                 {:base-url (str "http://127.0.0.1:" port "/v1")
                                  :api-key "k" :model "m"})]
        (is (= [0.0 0.0] (e/embed-one p "只要一条"))))
      (finally (stop! srv)))))

(deftest error-paths-test
  (testing "HTTP 4xx → 规范错误（认证类不可重试）"
    (let [{:keys [port] :as srv} (start-server (fn [_] [401 {:error {:message "bad key"}}]))]
      (try
        (let [p (e/create-provider :openai-compat
                                   {:base-url (str "http://127.0.0.1:" port "/v1")
                                    :api-key "k" :model "m"})
              e* (try (e/embed p ["x"]) nil (catch clojure.lang.ExceptionInfo ex ex))]
          (is (some? e*))
          (is (= :auth-error (:type (ex-data e*))))
          (is (false? (:retryable? (ex-data e*))))
          (is (= 401 (:status (ex-data e*)))))
        (finally (stop! srv)))))

  (testing "返回条数对不上 → :parse-error（宁可报错也不给出错位的向量）"
    (let [{:keys [port] :as srv} (start-server
                                   (fn [body]
                                     [200 {:model (:model body)
                                           :data [{:index 0 :embedding [1.0]}]
                                           :usage {}}]))]
      (try
        (let [p (e/create-provider :openai-compat
                                   {:base-url (str "http://127.0.0.1:" port "/v1")
                                    :api-key "k" :model "m"})
              e* (try (e/embed p ["a" "b"]) nil (catch clojure.lang.ExceptionInfo ex ex))]
          (is (= :parse-error (:type (ex-data e*)))))
        (finally (stop! srv)))))

  (testing "入参校验：空 texts / 非字符串 / 缺 model"
    (let [p (e/create-provider :openai-compat
                               {:base-url "http://127.0.0.1:1/v1" :api-key "k" :model "m"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"不能为空" (e/embed p [])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"必须都是字符串" (e/embed p [42]))))
    (let [p (e/create-provider :openai-compat
                               {:base-url "http://127.0.0.1:1/v1" :api-key "k"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":model" (e/embed p ["a"]))))))

;;; ============================================================
;;; DashScope 原生形态
;;; ============================================================

(deftest dashscope-native-test
  (let [{:keys [port requests] :as srv}
        (start-server (fn [body]
                        (let [texts (get-in body [:input :texts])]
                          [200 {:output {:embeddings
                                         (vec (map-indexed
                                                (fn [i _] {:text_index i :embedding [(double i)]})
                                                texts))}
                                :usage {:total_tokens (count texts)}
                                :request_id "req-1"}])))]
    (try
      (let [p (e/create-provider :dashscope
                                 {:base-url (str "http://127.0.0.1:" port "/emb")
                                  :api-key "dk" :model "text-embedding-v3"})
            result (e/embed p {:dimensions 1024 :text-type "document"} ["甲" "乙"])
            req (first @requests)]
        (testing "原生请求形状：input.texts + parameters.dimension/text_type"
          (is (= "/emb" (:path req)))
          (is (= ["甲" "乙"] (get-in req [:body :input :texts])))
          (is (= 1024 (get-in req [:body :parameters :dimension])))
          (is (= "document" (get-in req [:body :parameters :text_type]))))
        (testing "output.embeddings 按 text_index 归一"
          (is (= [[0.0] [1.0]] (:embeddings result)))
          (is (= :dashscope (:provider result)))))
      (finally (stop! srv)))))

(deftest dashscope-batch-limit-test
  (testing "DashScope 单次上限 10 条，默认自动切片"
    (let [{:keys [port requests] :as srv}
          (start-server (fn [body]
                          (let [texts (get-in body [:input :texts])]
                            [200 {:output {:embeddings (vec (map-indexed
                                                              (fn [i _] {:text_index i :embedding [(double i)]})
                                                              texts))}
                                  :usage {:total_tokens (count texts)}}])))]
      (try
        (let [p (e/create-provider :dashscope
                                   {:base-url (str "http://127.0.0.1:" port "/emb")
                                    :api-key "dk"})
              result (e/embed p (mapv str (range 23)))]
          (is (= [10 10 3] (mapv #(count (get-in % [:body :input :texts])) @requests)))
          (is (= 23 (count (:embeddings result)))))
        (finally (stop! srv))))))

;;; ============================================================
;;; 工厂与能力探测
;;; ============================================================

(deftest factory-validation-test
  (testing "未知类型：报错且列出支持项"
    (let [e* (try (e/create-provider :no-such) nil (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :validation-error (:type (ex-data e*))))
      (is (contains? (set (get-in (ex-data e*) [:context :supported])) :openai))))

  (testing ":openai-compat 缺 base-url → 创建即失败，不拖到调用时"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":base-url"
                          (e/create-provider :openai-compat {:api-key "k"}))))

  (testing "缺 api-key → 创建即失败，并指出环境变量名"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"OPENAI_API_KEY"
                          (e/create-provider :openai {:api-key nil}))))

  (testing "内置默认：端点 / 模型 / 批大小来自表，opts 可覆盖"
    (let [p (e/create-provider :siliconflow {:api-key "k"})]
      (is (= "BAAI/bge-m3" (get-in p [:config :model])))
      (is (= "https://api.siliconflow.cn/v1" (get-in p [:config :base-url]))))
    (let [p (e/create-provider :openai {:api-key "k" :model "text-embedding-3-large"})]
      (is (= "text-embedding-3-large" (get-in p [:config :model]))))))

(deftest capability-probe-test
  (testing "embedding 能力探测不撒谎：实现了才为真"
    (is (true? (emb/embedding-provider? (e/create-provider :mock))))
    (is (false? (emb/embedding-provider? {:not "a provider"})))
    (is (false? (emb/embedding-provider? nil))))

  (testing "Anthropic 没有 embedding 服务 → 这里根本没有条目，探测为假"
    (is (not (contains? (set (e/supported-providers)) :anthropic)))
    (is (false? (emb/embedding-provider?
                  (anthropic/create-provider {:api-key "k"})))))

  (testing "ensure-embedding-provider! 对不支持者抛规范错误"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"不支持 embedding"
                          (emb/ensure-embedding-provider! {})))))

(deftest mock-provider-test
  (let [p (e/create-provider :mock)]
    (testing "同文本同向量（可作离线测试基线）"
      (is (= (e/embed-one p "稳定") (e/embed-one p "稳定"))))
    (testing "不同文本不同向量；维度可配"
      (is (not= (e/embed-one p "甲") (e/embed-one p "乙")))
      (is (= 4 (count (e/embed-one p {:dimensions 4} "甲")))))
    (testing "批量与单条同序"
      (is (= [(e/embed-one p "甲") (e/embed-one p "乙")]
             (:embeddings (e/embed p ["甲" "乙"])))))))
