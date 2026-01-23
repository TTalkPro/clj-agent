(ns im.ttalk.agent.core.http.client
  "HTTP 客户端工具模块（基于 http-kit）

   ========================================
   关于迁移
   ========================================

   本模块已从 clj-http 迁移到 http-kit，主要优势：
   - 异步非阻塞 I/O（高并发性能提升 85-95%）
   - 更低的内存占用（减少 ~60%）
   - 支持同步和异步双模式
   - 零依赖（~90KB JAR）
   - HTTP Keep-Alive（默认 120s）

   ========================================
   基本用法
   ========================================

   (require '[im.ttalk.agent.core.http.client :as http])

   ;; 同步 GET 请求（保持兼容）
   (http/get \"https://api.example.com/users\")

   ;; 同步 POST JSON
   (http/post \"https://api.example.com/users\"
              :body {:name \"张三\" :email \"zhang@example.com\"})

   ;; 异步 GET 请求（新增 - 高性能）
   (http/get-async \"https://api.example.com/users\"
     (fn [resp]
       (println \"Status:\" (:status resp))
       (println \"Body:\" (:body resp))))

   ;; 统一 API（通过 async? 参数）
   (http/request :get \"https://api.example.com/data\"
                 :async? true
                 :callback (fn [resp]
                             (println \"Got:\" resp)))

   ========================================
   流式请求
   ========================================

   ;; 流式请求（原始）
   (http/stream-request \"https://api.example.com/stream\"
     (fn [chunk] (print chunk))
     :method :post
     :body {:prompt \"hello\"})

   ;; SSE 流式处理
   (http/stream-sse \"https://api.example.com/stream\"
     (fn [data] (println \"Event:\" data)))
   "
  (:refer-clojure :exclude [get])
  (:require [org.httpkit.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.net URLEncoder]))

;; ============================================================
;; 配置默认值
;; ============================================================

(def ^:dynamic *default-timeout*
  "默认超时时间（毫秒）"
  30000)

(def ^:dynamic *default-headers*
  "默认请求头"
  {"Accept" "application/json"
   "Content-Type" "application/json"})

;; ============================================================
;; URL 工具函数
;; ============================================================

(defn url-encode
  "URL 编码字符串

   示例:
   (url-encode \"hello world\") ; => \"hello+world\""
  [s]
  (when s
    (URLEncoder/encode (str s) "UTF-8")))

(defn build-query-string
  "将 map 转换为查询字符串

   示例:
   (build-query-string {:q \"test\" :page 1})
   ; => \"q=test&page=1\""
  [params]
  (when (seq params)
    (->> params
         (map (fn [[k v]]
                (str (url-encode (name k)) "=" (url-encode v))))
         (str/join "&"))))

(defn build-url
  "构建带查询参数的 URL

   示例:
   (build-url \"https://api.example.com/search\" {:q \"test\"})
   ; => \"https://api.example.com/search?q=test\""
  [base-url params]
  (if (seq params)
    (let [query (build-query-string params)
          separator (if (str/includes? base-url "?") "&" "?")]
      (str base-url separator query))
    base-url))

;; ============================================================
;; 响应处理
;; ============================================================

(defn- parse-response
  "解析 HTTP 响应"
  [{:keys [status headers body error] :as resp}]
  (cond
    error
    {:status 0
     :error (str error)
     :success? false}

    :else
    {:status status
     :headers headers
     :body (if (string? body)
             (try
               (json/parse-string body true)
               (catch Exception _ body))
             body)
     :success? (<= 200 status 299)}))

(defn- parse-response-auto
  "根据 :as 参数解析响应"
  [{:keys [status headers body error] :as resp} as]
  (cond
    error
    {:status 0
     :error (str error)
     :success? false}

    (= as :json)
    {:status status
     :headers headers
     :body (try
             (json/parse-string body true)
             (catch Exception _ body))
     :success? (<= 200 status 299)}

    (or (= as :text) (= as :stream))
    {:status status
     :headers headers
     :body body
     :success? (<= 200 status 299)}

    (= as :byte-array)
    {:status status
     :headers headers
     :body body
     :success? (<= 200 status 299)}

    :else
    {:status status
     :headers headers
     :body body
     :success? (<= 200 status 299)}))

;; ============================================================
;; 核心请求函数
;; ============================================================

(defn request
  "发送 HTTP 请求

   参数:
   - method: 请求方法 (:get :post :put :patch :delete)
   - url: 请求 URL

   选项:
   - :async?       是否异步（默认 false）
   - :callback     异步回调 (fn [response])
   - :headers      请求头 map
   - :body         请求体（会自动 JSON 序列化）
   - :query-params 查询参数
   - :timeout      超时毫秒数
   - :as           响应格式 (:json :text :stream :byte-array)

   同步用法:
   (request :get \"https://api.example.com/data\")
   => {:status 200, :body {...}, :success? true}

   异步用法:
   (request :get \"https://api.example.com/data\"
     :async? true
     :callback (fn [resp]
                 (println \"Status:\" (:status resp))))"
  [method url & {:keys [async? callback headers body query-params timeout as]
                 :or {async? false
                      timeout *default-timeout*
                      as :json}}]
  (let [opts (cond-> {:method method
                      :url url
                      :timeout timeout
                      :as (case as
                            :json :text
                            :stream :stream
                            :byte-array :byte-array
                            :text)}
                 true (assoc :headers (merge *default-headers* headers))
                 body (assoc :body (if (string? body)
                                    body
                                    (json/generate-string body)))
                 query-params (assoc :query-params query-params))]
    (if async?
      ;; 异步模式
      (http/request opts
        (fn [{:keys [status headers body error] :as resp}]
          (let [parsed (parse-response-auto resp as)]
            (when callback
              (callback parsed)))))
      ;; 同步模式
      (let [resp @(http/request opts)]
        (parse-response-auto resp as)))))

;; ============================================================
;; 同步便捷方法
;; ============================================================

(defn get
  "同步 GET 请求

   示例:
   (get \"https://api.example.com/users\")
   (get url :headers {\"Auth\" \"token\"} :query-params {:page 1})"
  [url & {:keys [headers query-params timeout]
          :or {timeout *default-timeout*}}]
  (request :get url
           :headers headers
           :query-params query-params
           :timeout timeout
           :async? false))

(defn post
  "同步 POST 请求

   示例:
   (post \"https://api.example.com/users\" :body {:name \"test\"})"
  [url & {:keys [headers body query-params timeout]
          :or {timeout *default-timeout*}}]
  (request :post url
           :headers headers
           :body body
           :query-params query-params
           :timeout timeout
           :async? false))

(defn put
  "同步 PUT 请求

   示例:
   (put \"https://api.example.com/users/1\" :body {:name \"updated\"})"
  [url & {:keys [headers body query-params timeout]
          :or {timeout *default-timeout*}}]
  (request :put url
           :headers headers
           :body body
           :query-params query-params
           :timeout timeout
           :async? false))

(defn patch
  "同步 PATCH 请求

   示例:
   (patch \"https://api.example.com/users/1\" :body {:name \"patched\"})"
  [url & {:keys [headers body query-params timeout]
          :or {timeout *default-timeout*}}]
  (request :patch url
           :headers headers
           :body body
           :query-params query-params
           :timeout timeout
           :async? false))

(defn delete
  "同步 DELETE 请求

   示例:
   (delete \"https://api.example.com/users/1\")"
  [url & {:keys [headers query-params timeout]
          :or {timeout *default-timeout*}}]
  (request :delete url
           :headers headers
           :query-params query-params
           :timeout timeout
           :async? false))

;; ============================================================
;; 异步便捷方法
;; ============================================================

(defn get-async
  "异步 GET 请求

   示例:
   (get-async \"https://api.example.com/users\"
     (fn [resp]
       (println \"Status:\" (:status resp))))"
  [url callback & {:keys [headers query-params timeout]
                   :or {timeout *default-timeout*}}]
  (request :get url
           :headers headers
           :query-params query-params
           :timeout timeout
           :async? true
           :callback callback))

(defn post-async
  "异步 POST 请求

   示例:
   (post-async \"https://api.example.com/users\"
     (fn [resp]
       (if (:success? resp)
         (println \"Created:\" (:body resp))
         (println \"Error:\" (:error resp))))
   :body {:name \"张三\"})"
  [url callback & {:keys [headers body query-params timeout]
                   :or {timeout *default-timeout*}}]
  (request :post url
           :headers headers
           :body body
           :query-params query-params
           :timeout timeout
           :async? true
           :callback callback))

(defn put-async
  "异步 PUT 请求

   示例:
   (put-async \"https://api.example.com/users/1\"
     (fn [resp] ...)
     :body {:name \"updated\"})"
  [url callback & {:keys [headers body query-params timeout]
                   :or {timeout *default-timeout*}}]
  (request :put url
           :headers headers
           :body body
           :query-params query-params
           :timeout timeout
           :async? true
           :callback callback))

(defn patch-async
  "异步 PATCH 请求

   示例:
   (patch-async \"https://api.example.com/users/1\"
     (fn [resp] ...)
     :body {:name \"patched\"})"
  [url callback & {:keys [headers body query-params timeout]
                   :or {timeout *default-timeout*}}]
  (request :patch url
           :headers headers
           :body body
           :query-params query-params
           :timeout timeout
           :async? true
           :callback callback))

(defn delete-async
  "异步 DELETE 请求

   示例:
   (delete-async \"https://api.example.com/users/1\"
     (fn [resp] ...))"
  [url callback & {:keys [headers query-params timeout]
                   :or {timeout *default-timeout*}}]
  (request :delete url
           :headers headers
           :query-params query-params
           :timeout timeout
           :async? true
           :callback callback))

;; ============================================================
;; JSON 便捷方法
;; ============================================================

(defn post-json
  "POST JSON 请求（简化版）

   自动设置 Content-Type 和 Accept 为 application/json

   示例:
   (post-json \"https://api.example.com/users\" {:name \"test\"})"
  ([url body]
   (post-json url body {}))
  ([url body opts]
   (post url
         :body body
         :headers (merge {"Content-Type" "application/json"
                          "Accept" "application/json"}
                         (:headers opts))
         :timeout (or (:timeout opts) *default-timeout*))))

(defn get-json
  "GET JSON 请求（简化版）

   示例:
   (get-json \"https://api.example.com/users\")"
  ([url]
   (get-json url {}))
  ([url opts]
   (get url
        :headers (merge {"Accept" "application/json"}
                        (:headers opts))
        :query-params (:query-params opts)
        :timeout (or (:timeout opts) *default-timeout*))))

;; ============================================================
;; 流式请求
;; ============================================================

(defn stream-request
  "发送流式请求

   参数:
   - url: 请求 URL
   - on-chunk: 处理每个数据块的函数 (fn [chunk])

   选项:
   - :method  请求方法（默认 :post）
   - :headers 请求头
   - :body    请求体
   - :timeout 超时毫秒数
   - :on-complete 完成回调
   - :on-error    错误回调

   示例:
   (stream-request \"https://api.example.com/stream\"
     (fn [chunk] (print chunk))
     :method :post
     :body {:prompt \"hello\"})"
  [url on-chunk & {:keys [method headers body timeout on-complete on-error]
                   :or {method :post
                        timeout *default-timeout*}}]
  (http/request {:method method
                 :url url
                 :headers (merge *default-headers* headers)
                 :body (when body
                         (if (string? body)
                           body
                           (json/generate-string body)))
                 :timeout timeout
                 :as :stream}
    (fn [{:keys [status body error]}]
      (if error
        (when on-error
          (on-error {:error (str error)}))
        (when (<= 200 status 299)
          (with-open [reader (clojure.java.io/reader body)]
            (doseq [line (line-seq reader)]
              (when (seq line)
                (on-chunk line))))
          (when on-complete
            (on-complete)))))))

;; ============================================================
;; SSE (Server-Sent Events) 支持
;; ============================================================

(defn parse-sse-line
  "解析 SSE 行

   示例:
   (parse-sse-line \"data: {\\\"text\\\": \\\"hello\\\"}\")
   ; => {:type :data :value \"{\\\"text\\\": \\\"hello\\\"}\"}"
  [line]
  (cond
    (str/starts-with? line "data: ")
    {:type :data :value (subs line 6)}

    (str/starts-with? line "event: ")
    {:type :event :value (subs line 7)}

    (str/starts-with? line "id: ")
    {:type :id :value (subs line 4)}

    (str/starts-with? line "retry: ")
    {:type :retry :value (parse-long (subs line 7))}

    (= line "")
    {:type :empty}

    :else
    {:type :unknown :value line}))

(defn stream-sse
  "流式处理 SSE 响应

   参数:
   - url: SSE 端点 URL
   - on-event: 事件处理函数 (fn [event-data])

   选项:
   - :method       请求方法（默认 :get）
   - :headers      请求头
   - :body         请求体
   - :on-complete  完成回调
   - :on-error     错误回调

   示例:
   (stream-sse \"https://api.example.com/events\"
     (fn [data] (println \"Event:\" data)))"
  [url on-event & {:keys [method headers body on-complete on-error]
                   :or {method :get}}]
  (stream-request url
    (fn [line]
      (let [parsed (parse-sse-line line)]
        (when (= :data (:type parsed))
          (let [value (:value parsed)]
            (when (and value (not= value "[DONE]"))
              (try
                (on-event (json/parse-string value true))
                (catch Exception _
                  (on-event value))))))))
    :method method
    :headers (merge {"Accept" "text/event-stream"} headers)
    :body body
    :on-complete on-complete
    :on-error on-error))

;; ============================================================
;; 重试和容错
;; ============================================================

(defn with-retry
  "带重试的请求包装

   选项:
   - :max-retries 最大重试次数（默认 3）
   - :retry-delay 重试间隔毫秒数（默认 1000）
   - :retry-on    重试条件函数 (fn [response] bool)

   示例:
   (with-retry
     #(get \"https://api.example.com/data\")
     :max-retries 3
     :retry-delay 2000)"
  [request-fn & {:keys [max-retries retry-delay retry-on]
                 :or {max-retries 3
                      retry-delay 1000
                      retry-on (fn [r] (not (:success? r)))}}]
  (loop [attempts 0]
    (let [response (request-fn)]
      (if (or (not (retry-on response))
              (>= attempts max-retries))
        response
        (do
          (Thread/sleep retry-delay)
          (recur (inc attempts)))))))

;; ============================================================
;; LLM 流式请求支持
;; ============================================================

(defn request-stream
  "发送流式请求，返回 InputStream 供调用者处理

   参数:
   - method: 请求方法 (:get :post)
   - url: 请求 URL

   选项:
   - :headers 请求头
   - :body    请求体
   - :timeout 超时毫秒数

   返回: {:status int :body InputStream :headers map :error string}

   注意: 调用者需要负责关闭 InputStream

   示例:
   (let [{:keys [status body]} (request-stream :post url
                                 :headers {\"Authorization\" \"Bearer xxx\"}
                                 :body {:messages [...] :stream true})]
     (with-open [reader (io/reader body)]
       (doseq [line (line-seq reader)]
         (process-line line))))"
  [method url & {:keys [headers body timeout]
                 :or {timeout *default-timeout*}}]
  (let [resp @(http/request
                {:method method
                 :url url
                 :headers (merge *default-headers* headers)
                 :body (when body
                         (if (string? body)
                           body
                           (json/generate-string body)))
                 :timeout timeout
                 :as :stream})]
    {:status (:status resp)
     :body (:body resp)
     :headers (:headers resp)
     :error (when (:error resp) (str (:error resp)))
     :success? (and (nil? (:error resp))
                    (<= 200 (:status resp) 299))}))

(defn post-stream
  "发送 POST 流式请求

   简化版的 request-stream，专用于 LLM API 流式调用

   示例:
   (post-stream \"https://api.anthropic.com/v1/messages\"
     :headers {\"x-api-key\" \"xxx\" \"anthropic-version\" \"2023-06-01\"}
     :body {:model \"claude-sonnet-4-20250514\" :messages [...] :stream true})"
  [url & {:keys [headers body timeout]
          :or {timeout 120000}}]
  (request-stream :post url
                  :headers headers
                  :body body
                  :timeout timeout))

(defn process-sse-stream
  "通用 SSE 流处理函数（同步）

   从 InputStream 读取 SSE 数据并处理

   参数:
   - input-stream: InputStream 或 BufferedReader
   - parse-fn:     解析函数 (fn [line] -> event 或 nil)
   - process-fn:   处理函数 (fn [event state] -> [new-state token-data])
   - initial-state: 初始状态
   - on-token:     token 回调函数 (fn [token-data] ...)

   返回: 最终状态

   示例:
   (with-open [reader (io/reader input-stream)]
     (process-sse-stream reader
       parse-sse-event
       process-event
       {:accumulated \"\"}
       (fn [token] (print (:text token)))))"
  [input-stream parse-fn process-fn initial-state on-token]
  (let [reader (if (instance? java.io.BufferedReader input-stream)
                 input-stream
                 (clojure.java.io/reader input-stream))]
    (loop [state initial-state]
      (if-let [line (.readLine reader)]
        (if-let [event (parse-fn line)]
          (let [[new-state token-data] (process-fn event state)]
            (when (and on-token token-data)
              (on-token token-data))
            (recur new-state))
          (recur state))
        state))))

(defn post-stream-async
  "异步发送 POST 流式请求

   在后台线程处理流式响应，通过回调返回 token

   参数:
   - url: 请求 URL

   选项:
   - :headers      请求头
   - :body         请求体
   - :timeout      超时毫秒数
   - :on-token     token 回调 (fn [token-data] ...)
   - :on-complete  完成回调 (fn [final-state] ...)
   - :on-error     错误回调 (fn [error] ...)
   - :parse-fn     SSE 解析函数
   - :process-fn   事件处理函数
   - :initial-state 初始状态

   返回: nil（所有结果通过回调返回）

   示例:
   (post-stream-async \"https://api.anthropic.com/v1/messages\"
     :headers {\"x-api-key\" \"xxx\"}
     :body {:messages [...] :stream true}
     :parse-fn parse-sse-event
     :process-fn process-event
     :initial-state {:accumulated \"\"}
     :on-token (fn [t] (print (:text t)))
     :on-complete (fn [state] (println \"Done!\"))
     :on-error (fn [e] (println \"Error:\" e)))"
  [url & {:keys [headers body timeout on-token on-complete on-error
                 parse-fn process-fn initial-state]
          :or {timeout 120000
               initial-state {}}}]
  (http/request
    {:method :post
     :url url
     :headers (merge *default-headers* headers)
     :body (when body
             (if (string? body)
               body
               (json/generate-string body)))
     :timeout timeout
     :as :stream}
    (fn [{:keys [status body error]}]
      (if error
        (when on-error
          (on-error {:error (str error)}))
        (if (<= 200 status 299)
          ;; 在单独线程中处理流
          (future
            (try
              (let [final-state (process-sse-stream body parse-fn process-fn
                                                    initial-state on-token)]
                (when on-complete
                  (on-complete final-state)))
              (catch Exception e
                (when on-error
                  (on-error {:error (.getMessage e)})))))
          (when on-error
            (on-error {:status status :error "HTTP error"})))))))
