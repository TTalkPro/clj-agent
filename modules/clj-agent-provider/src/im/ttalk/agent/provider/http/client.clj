(ns im.ttalk.agent.provider.http.client
  "HTTP 客户端工具模块（基于 java.net.http）——**仅非流式**请求/响应。

   流式（SSE）已迁移到 `im.ttalk.agent.provider.http.stream-client`（java.net.http 真增量，
   见 docs/streaming-async-design.md）；本模块不再提供流式 API。

   用法：
   (require '[im.ttalk.agent.provider.http.client :as http])
   (http/get  \"https://api.example.com/users\")
   (http/post \"https://api.example.com/users\" :body {:name \"张三\"})
   (http/post-async url callback :body {...})   ; 异步回调
   (http/request :get url :async? true :callback (fn [resp] ...))"
  (:refer-clojure :exclude [get])
  (:require [cheshire.core :as json]
            [taoensso.timbre :as log]
            [clojure.string :as str]
            [im.ttalk.agent.model.error :as errors])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$Builder HttpRequest$BodyPublishers
                          HttpResponse HttpResponse$BodyHandlers HttpHeaders]
           [java.time Duration]
           [java.nio.charset StandardCharsets]))

(set! *warn-on-reflection* true)

;; ============================================================
;; 共享 HttpClient（连接池复用；executor 可换虚拟线程）
;; ============================================================

(defonce ^{:doc "默认共享 HttpClient。executor 用虚拟线程：回调跑在虚拟线程上，
   高并发不被固定线程池饿死；JDK 24+ 起 synchronized 不再 pin 虚拟线程。"}
  default-client
  (delay
    (-> (HttpClient/newBuilder)
        (.connectTimeout (Duration/ofSeconds 30))
        (.executor (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor))
        (.build))))

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

(defn request-id
  "从响应头提取请求 ID（用于排障 / 上报），大小写无关

   Anthropic 用 request-id，OpenAI 用 x-request-id。"
  [headers]
  (when headers
    (or (clojure.core/get headers :request-id)
        (clojure.core/get headers "request-id")
        (clojure.core/get headers :x-request-id)
        (clojure.core/get headers "x-request-id"))))

(defn- http-headers->map
  "将 java.net.http.HttpHeaders 转换为 Clojure map，key 转为小写字符串。
    HttpHeaders.map() 返回 Map<String,List<String>>，遍历得到 Map.Entry。"
  [^HttpHeaders http-headers]
  (persistent!
    (reduce (fn [m [^String k v]]
              (assoc! m (str/lower-case k) v))
            (transient {})
            (.map http-headers))))

(defn- parse-response
  "解析 HTTP 响应

   根据 as 参数决定 body 解析方式：
   - :json       尝试 JSON 解析（默认）
   - :text/:stream/:byte-array  原样返回"
  [{:keys [status headers body error]} as]
  (if error
    {:status 0 :error (str error) :success? false}
    {:status status
     :headers headers
     :request-id (request-id headers)
     :body (if (= as :json)
             (if (string? body)
               (try (json/parse-string body true)
                    (catch Exception e
                      (log/debug "HTTP 响应体 JSON 解析失败，回退原始字符串"
                                 {:status status :error (.getMessage e)})
                      body))
               body)
             body)
     :success? (<= 200 status 299)}))

(defn response->error
  "把失败的 HTTP 响应（本模块 request/post 返回的 map）转为 canonical error
   （D5：ex-info data 即 canonical error map；保留 body/headers/request-id 于 :context 供排查）。

   之前 openai_compat / anthropic / dashscope 各自维护同一逻辑，现统一于此。"
  [response provider]
  (let [status (or (:status response) 0)
        base (if (and (zero? status) (:error response))
               ;; 连接级失败（无 HTTP 状态码）：网络错误，可重试
               (errors/error :network-error
                             (str "连接失败: " (:error response))
                             {:provider provider})
               ;; HTTP 4xx/5xx：按状态码分类（401/403→auth 不可重试；429→限流；5xx→provider 可重试）
               (errors/http-response->error response provider))]
    (assoc base :context (select-keys response [:body :headers :request-id :error]))))

;; ============================================================
;; 请求构建
;; ============================================================

(defn- has-content-type?
  [headers]
  (some (fn [[k _]] (= "content-type" (str/lower-case (name k)))) headers))

(defn- build-request
  ^HttpRequest [method url headers body timeout]
  (let [method-kw (keyword method)
        body-str (when body
                   (if (string? body) body (json/generate-string body)))
        uri (URI/create url)
        timeout-dur (Duration/ofMillis (or timeout *default-timeout*))
        ^HttpRequest$Builder base-builder (-> (HttpRequest/newBuilder uri)
                                              (.timeout timeout-dur))
        base-builder (if-not (has-content-type? headers)
                       (doto base-builder (.header "Content-Type" "application/json"))
                       base-builder)
        ;; 注：HttpRequest.Builder 没有 .PATCH 方法，PATCH 必须走通用 .method
        ^HttpRequest$Builder req-builder
        (cond
          (= method-kw :get) (.GET base-builder)
          (= method-kw :post) (.POST base-builder (HttpRequest$BodyPublishers/ofString body-str))
          (= method-kw :put) (.PUT base-builder (HttpRequest$BodyPublishers/ofString body-str))
          (= method-kw :patch) (.method base-builder "PATCH" (HttpRequest$BodyPublishers/ofString body-str))
          (= method-kw :delete) (.DELETE base-builder))]
    (doseq [[k v] headers]
      (.header req-builder (name k) (str v)))
    (.build req-builder)))

(defn- body-handler
  "根据 :as 参数返回合适的 HttpResponse.BodyHandler"
  [as]
  (case as
    :json (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8)
    :text (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8)
    :byte-array (HttpResponse$BodyHandlers/ofByteArray)
    :stream (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8)
    (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8)))

;; ============================================================
;; 核心请求函数
;; ============================================================

(defn request
  "发送 HTTP 请求"
  [method url & {:keys [async? callback headers body query-params timeout as]
                 :or {async? false
                      timeout *default-timeout*
                      as :json}}]
  (let [url (if query-params (build-url url query-params) url)
        headers (merge *default-headers* headers)
        req (build-request method url headers body timeout)
        handler (body-handler as)
        client @default-client]
    (if async?
      (let [cf (.sendAsync ^HttpClient client req handler)]
        (.thenApply cf
          (reify java.util.function.Function
            (apply [_ resp]
              (let [^HttpResponse resp resp
                    status (.statusCode resp)
                    headers (http-headers->map (.headers resp))
                    body (.body resp)
                    parsed (parse-response {:status status :headers headers :body body} as)]
                (when callback
                  (callback parsed))
                parsed)))))
      (try
        (let [^HttpResponse resp (.send ^HttpClient client req handler)
              status (.statusCode resp)
              headers (http-headers->map (.headers resp))
              body (.body resp)]
          (parse-response {:status status :headers headers :body body} as))
        (catch java.io.IOException e
          {:status 0 :error (.getMessage e) :success? false})))))

;; ============================================================
;; HTTP 便捷方法（宏批量生成）
;; ============================================================

(defmacro ^:private defhttp-methods
  "批量生成同步和异步 HTTP 便捷方法"
  [& specs]
  `(do
     ~@(mapcat
         (fn [[method has-body?]]
           (let [sync-name (symbol (name method))
                 async-name (symbol (str (name method) "-async"))
                 method-kw (keyword method)]
             [(if has-body?
                `(defn ~sync-name
                   ~(str "同步 " (clojure.string/upper-case (name method)) " 请求")
                   [~'url & {:keys [~'headers ~'body ~'query-params ~'timeout]
                             :or {~'timeout *default-timeout*}}]
                   (request ~method-kw ~'url
                            :headers ~'headers :body ~'body
                            :query-params ~'query-params
                            :timeout ~'timeout :async? false))
                `(defn ~sync-name
                   ~(str "同步 " (clojure.string/upper-case (name method)) " 请求")
                   [~'url & {:keys [~'headers ~'query-params ~'timeout]
                             :or {~'timeout *default-timeout*}}]
                   (request ~method-kw ~'url
                            :headers ~'headers
                            :query-params ~'query-params
                            :timeout ~'timeout :async? false)))
               (if has-body?
                 `(defn ~async-name
                    ~(str "异步 " (clojure.string/upper-case (name method)) " 请求")
                    [~'url ~'callback & {:keys [~'headers ~'body ~'query-params ~'timeout]
                                         :or {~'timeout *default-timeout*}}]
                    (request ~method-kw ~'url
                             :headers ~'headers :body ~'body
                             :query-params ~'query-params
                             :timeout ~'timeout
                             :async? true :callback ~'callback))
                 `(defn ~async-name
                    ~(str "异步 " (clojure.string/upper-case (name method)) " 请求")
                    [~'url ~'callback & {:keys [~'headers ~'query-params ~'timeout]
                                         :or {~'timeout *default-timeout*}}]
                    (request ~method-kw ~'url
                             :headers ~'headers
                             :query-params ~'query-params
                             :timeout ~'timeout
                             :async? true :callback ~'callback)))]))
         specs)))

(defhttp-methods
  [get false]
  [post true]
  [put true]
  [patch true]
  [delete false])

;; ============================================================
;; JSON 便捷方法
;; ============================================================

(defn post-json
  "POST JSON 请求（简化版）"
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
  "GET JSON 请求（简化版）"
  ([url]
   (get-json url {}))
  ([url opts]
   (get url
        :headers (merge {"Accept" "application/json"}
                        (:headers opts))
        :query-params (:query-params opts)
        :timeout (or (:timeout opts) *default-timeout*))))