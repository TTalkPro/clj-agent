(ns im.ttalk.agent.provider.http.client
  "provider 的 HTTP 门面——**仅非流式**请求/响应。传输在 `im.ttalk.http`（clj-http，
   designs/http-module.md）；本 ns 只留 provider 的约定：JSON 编解码、`{:status 0 :error …}`（IO 失败不抛）、
   `:success?`、canonical error 映射（`response->error` / `success-body` / `check-model!`）。
   公开函数的签名与返回形状自 0.4.0 起未变。

   流式（SSE）已迁移到 `im.ttalk.agent.provider.http.stream-client`（java.net.http 真增量，
   见 docs/streaming-async-design.md）；本模块不再提供流式 API。

   用法：
   (require '[im.ttalk.agent.provider.http.client :as http])
   (http/get  \"https://api.example.com/users\")
   (http/post \"https://api.example.com/users\" :body {:name \"张三\"})
   (http/post-deferred url :body {...})         ; 异步：返回 deferred<响应 map>
   (http/post-async url callback :body {...})   ; 异步回调（低层，形状同上）
   (http/request :get url :async? true :callback (fn [resp] ...))"
  (:refer-clojure :exclude [get])
  (:require [cheshire.core :as json]
            [taoensso.timbre :as log]
            [im.ttalk.agent.filter :as flt]
            [im.ttalk.http :as http]
            [im.ttalk.agent.model.error :as errors]
            [im.ttalk.agent.provider.common.business-codes :as codes]
            [im.ttalk.agent.provider.common.rate-limit :as rate-limit]
            [im.ttalk.agent.provider.http.retry :as retry])
  (:import [java.util.function Function]))

(set! *warn-on-reflection* true)

;; ============================================================
;; 共享 HttpClient（连接池复用；executor 可换虚拟线程）
;; ============================================================

(defonce ^{:doc "默认共享 HttpClient（delay）——就是 `im.ttalk.http/default-client`：HTTP/1.1、虚拟线程
   executor、connect 30s。保留这个 var 是因为宿主可能 `@default-client` 拿它。"}
  default-client
  (delay (http/default-client)))

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
  (when s (http/url-encode s)))

(defn build-query-string
  "将 map 转换为查询字符串

   示例:
   (build-query-string {:q \"test\" :page 1})
   ; => \"q=test&page=1\""
  [params]
  (http/query-string params))

(defn build-url
  "构建带查询参数的 URL

   示例:
   (build-url \"https://api.example.com/search\" {:q \"test\"})
   ; => \"https://api.example.com/search?q=test\""
  [base-url params]
  (if (seq params) (http/with-query base-url params) base-url))

;; ============================================================
;; 响应处理
;; ============================================================

(defn request-id
  "从响应头提取请求 ID（用于排障 / 上报），大小写无关

   Anthropic 用 request-id，OpenAI 用 x-request-id。回**字符串**（此前直接 `get`，拿到的是
   java.net.http 的 List）。"
  [headers]
  (when headers
    (or (http/header headers "request-id")
        (http/header headers "x-request-id"))))

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

   之前 openai_compat / anthropic / dashscope 各自维护同一逻辑，现统一于此；流式非 2xx
   （`stream-client`）也走这里。

   - 状态码初判之后**过厂商业务码表**（`provider.common.business-codes/refine-error`）：
     429 + insufficient_quota → :quota-exhausted-error（不可重试）、400 + prompt is too long →
     :context-overflow-error、智谱 1309 → 欠费……（designs/provider-reliability.md §1）
   - 把 `Retry-After` 响应头解析成 `:retry-after-ms`：**解析 HTTP 头是 provider 边界的事**，
     重试在 ChatModel（core，不认识 HTTP）。没有头时扫正文里的 `try again in 1.5s`。"
  [response provider]
  (let [status (or (:status response) 0)
        base (if (and (zero? status) (:error response))
               ;; 连接级失败（无 HTTP 状态码）：网络错误，可重试
               (errors/error :network-error
                             (str "连接失败: " (:error response))
                             {:provider provider})
               ;; HTTP 4xx/5xx：按状态码分类，再按业务码收紧
               (let [e (errors/http-response->error response provider)
                     ;; 状态码层只认 {:error {:message}} / {:message}；智谱 {:msg}、MiniMax base_resp.status_msg
                     ;; 在这里补挖一次，免得错误消息只剩一句「HTTP 400 error」
                     e (if-let [m (and (re-matches #"HTTP \d+ error" (str (:message e)))
                                       (codes/extract-message (:body response)))]
                         (assoc e :message m)
                         e)]
                 (codes/refine-error e (:body response))))
        retry-after (or (retry/parse-retry-after (:headers response))
                        (codes/retry-delay-text (:message base))
                        (codes/retry-delay-text (let [b (:body response)] (when (string? b) b))))]
    (cond-> (assoc base :context (select-keys response [:body :headers :request-id :error]))
      retry-after (assoc :retry-after-ms retry-after))))

(defn check-model!
  "回包模型名核对（opt-in，designs/provider-reliability.md §4）：requested 与 answered 都非空且不相等
   → 抛 `{:type :provider-error :retryable? false :code :model-mismatch}`。"
  [provider requested answered]
  (when (and (string? requested) (seq requested)
             (string? answered) (seq answered)
             (not= requested answered))
    (errors/throw! (errors/error :provider-error
                                 (str "请求的模型是 " requested "，服务端却以 " answered " 作答")
                                 {:provider provider :status 200 :retryable? false
                                  :code :model-mismatch}))))

(defn success-body
  "成功（2xx）响应 → 原始响应体，统一做三件事（openai 兼容 / anthropic / responses 的同步与异步路径共用）：

   1. HTTP 200 里的业务错（MiniMax / 智谱 `base_resp.status_code ≠ 0`）→ 抛 canonical error（§1）；
   2. opts `{:check-model? true :model \"请求的模型\"}` 时核对 `(:model body)`（§4）；
   3. 响应头里的限流信息折成 `:rate-limit` 挂到响应体上（§6；body 是 map 时）。"
  ([response provider] (success-body response provider nil))
  ([response provider {:keys [check-model? model]}]
   (let [body (:body response)]
     (when-let [err (codes/body-error provider body)]
       (errors/throw! (assoc err :context (select-keys response [:body :headers :request-id]))))
     (when (and check-model? (map? body))
       (check-model! provider model (:model body)))
     (let [rl (rate-limit/parse-rate-limit (:headers response))]
       (cond-> body
         (and rl (map? body)) (assoc :rate-limit rl))))))

;; ============================================================
;; 核心请求函数（传输在 im.ttalk.http）
;; ============================================================

(defn- ->http-request
  "provider 的请求约定 ⇒ `im.ttalk.http` 的请求 map：合并 `*default-headers*`、非串正文转 JSON、
   `:as :byte-array` ⇒ 字节，其余（:json / :text / :stream）⇒ 串（`:stream` 一直是整段收完的串，名不副实，
   真流式在 `stream-client`）。"
  [method url {:keys [headers body query-params timeout as]}]
  {:method method
   :url url
   :query-params query-params
   :headers (merge *default-headers* headers)
   :body (when (some? body) (if (string? body) body (json/generate-string body)))
   :timeout-ms (or timeout *default-timeout*)
   :as (if (= as :byte-array) :bytes :string)
   :client @default-client})

(defn- finish [resp as]
  (parse-response (select-keys resp [:status :headers :body]) as))

(defn- io-failure
  "IO 失败 ⇒ `{:status 0 :error 串}`。⚠️ `:error` 必须非空：JDK 的 ConnectException 常常没有 message，
   此前 `:error nil` 让 `response->error` 认不出这是连接失败（它靠 `(:error response)` 判），误当成 HTTP 0 状态码。"
  [^Throwable t]
  {:status 0 :error (or (.getMessage t) (.getName (class t))) :success? false})

(defn request
  "发送 HTTP 请求"
  [method url & {:keys [async? callback headers body query-params timeout as]
                 :or {async? false
                      timeout *default-timeout*
                      as :json}
                 :as opts}]
  (let [req (->http-request method url (assoc opts :timeout timeout :as as))]
    (if async?
      (.thenApply (http/request-async req)
                  (reify Function
                    (apply [_ resp]
                      (let [parsed (finish resp as)]
                        (when callback (callback parsed))
                        parsed))))
      (try
        (finish (http/request req) as)
        (catch java.io.IOException e
          (io-failure e))))))

(defn request-deferred
  "`request` 的异步孪生：**不阻塞**，返回 deferred<响应 map>。

   **响应 map 的形状与同步 `request` 逐字相同**（`{:status :headers :body :success?}`
   / IO 失败时 `{:status 0 :error … :success? false}`）——形状对齐是刻意的：
   provider 的异步路径因此能与同步路径**共用**后处理（`:success?` 判定、
   `response->error`、限流头解析），不必另写一条更短的路。仓库里旧的
   `xxx-call-async` 旁路正是栽在没有这条对齐上（docs/async-chat-model-design.md §0）。

   非 IO 异常（如响应体解析失败）**原样落 error channel**，与同步版一致
   （同步版也不吞它们）。"
  [method url & {:keys [timeout as]
                 :or {timeout *default-timeout*
                      as :json}
                 :as opts}]
  (let [req (->http-request method url (assoc opts :timeout timeout :as as))]
    (flt/fcatch
      (flt/fmap (http/request-async req) #(finish % as))
      (fn [t]
        (if (instance? java.io.IOException t)
          (io-failure t)
          (throw t))))))

(defn post-deferred
  "异步 POST，返回 deferred<响应 map>（形状同同步 `post`）。"
  [url & {:as opts}]
  (apply request-deferred :post url (apply concat opts)))

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
