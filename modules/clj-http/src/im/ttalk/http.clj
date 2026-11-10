(ns im.ttalk.http
  "通用 HTTP 客户端（`im.ttalk/clj-http`）：JDK `java.net.http` 之上的 Ring 式请求 / 响应 map、同步与
   `CompletableFuture` 异步、按行真流式、大小写不敏感的头工具（designs/http-module.md）。零依赖：只用 JDK；
   JSON、日志、错误模型都是调用方的事。与 agent 无关，可单独使用；clj-agent 的 provider / mcp / tools 都建在它上面。
   （与社区的 dakrone/clj-http 无关：坐标是 im.ttalk/clj-http。）

   ```clojure
   (require '[im.ttalk.http :as http])

   (http/request {:method :post :url \"https://api.example.com/v1/x\"
                  :headers {\"Authorization\" \"Bearer …\"} :body \"{…}\" :timeout-ms 30000})
   ;; => {:status 200 :headers {\"content-type\" [\"application/json\"]} :body \"…\" :url \"…\"}

   (http/request-async req)                       ;; => CompletableFuture<同形状>
   (http/stream-lines (assoc req :on-line f))     ;; 按行真流式 => {:future :cancel}
   (http/header resp \"Retry-After\")               ;; 取头：大小写不敏感，单值 / 多值都收
   ```

   - **client 就是 `java.net.http.HttpClient` 本身**（`client` 造、`default-client` 取共享的那个）；
     各处的 `:client` 选项收的都是它。
   - **响应头**：小写头名 ⇒ 串向量（去掉 HTTP/2 伪头）。取值一律走 `header` / `header-values`，
     不要直接 `get`——这正是 provider-reliability.md §1.3 那个 Retry-After 从未生效的 bug 的根因。
   - **失败**：同步版原样抛（`IOException` / `HttpTimeoutException` / `IllegalArgumentException` …），
     异步版异常完成。把它们映射成什么（canonical error、`{:status 0 :error …}`、工具错误码）由调用方定。

   头工具移植自 Keelson `jvm/modules/clj-net` 的 `im.ttalk.net.http`（5c745869），改动：头名也收关键字；
   值也收 `java.util.List`。"
  (:require [clojure.string :as str]
            [im.ttalk.http.proxy :as proxy])
  (:import [java.io InputStream]
           [java.net ProxySelector URI URLEncoder]
           [java.net.http HttpClient HttpClient$Builder HttpClient$Redirect HttpClient$Version HttpHeaders
                          HttpRequest HttpRequest$BodyPublishers HttpRequest$Builder
                          HttpResponse HttpResponse$BodyHandler HttpResponse$BodyHandlers
                          HttpResponse$BodySubscribers HttpResponse$ResponseInfo]
           [java.nio.charset Charset StandardCharsets]
           [java.time Duration]
           [java.util Locale Map$Entry]
           [java.util.concurrent CancellationException CompletableFuture CompletionException Executor Executors
                                 Flow$Subscriber Flow$Subscription ScheduledExecutorService ThreadFactory TimeUnit]
           [java.util.concurrent.atomic AtomicBoolean AtomicLong]
           [java.util.function BiConsumer Function]))

(set! *warn-on-reflection* true)

(defn- lower ^String [^String s] (.toLowerCase s Locale/ROOT))

(defn- hname
  "头名：关键字取 name，其余 str。"
  ^String [n]
  (if (keyword? n) (name n) (str n)))

;;; ============================================================
;;; 头（移植自 clj-net im.ttalk.net.http）
;;; ============================================================

(defn- as-values [v]
  (cond (nil? v) []
        (string? v) [v]
        (or (sequential? v) (instance? java.util.List v)) (vec v)
        :else [v]))

(defn header-values
  "头 `name` 的全部值（大小写不敏感；没有 ⇒ []）。`m` 是头 map，或带 `:headers` 的请求 / 响应。
   头值可以是串、串向量或 `java.util.List`；头名可以是串或关键字。"
  [m name]
  (let [hs (if (and (map? m) (contains? m :headers)) (:headers m) m)
        k (lower (hname name))]
    (into [] (mapcat (fn [[n v]] (when (= k (lower (hname n))) (as-values v)))) hs)))

(defn header
  "头 `name` 的第一个值；没有 ⇒ nil。"
  [m name]
  (first (header-values m name)))

(defn dissoc-header
  "头 map 去掉 `name`（大小写不敏感）。"
  [hs name]
  (let [k (lower (hname name))]
    (into (empty (or hs {})) (remove (fn [[n _]] (= k (lower (hname n))))) hs)))

(defn assoc-header
  "头 map 里 `name` 换成这一个值（先去掉大小写不同的同名头）。"
  [hs name value]
  (assoc (dissoc-header hs name) name value))

(defn header-seq
  "头 map ⇒ [名 值] 串对的序列（多值展开）。"
  [hs]
  (for [[n v] hs, x (as-values v)] [(hname n) (str x)]))

(defn media-type
  "Content-Type 的媒体类型（小写、不含参数）；没有 ⇒ nil。`m` 同 `header-values`。"
  [m]
  (when-let [v (header m "Content-Type")]
    (let [v (str v) semi (.indexOf v ";")]
      (lower (str/trim (if (neg? semi) v (subs v 0 semi)))))))

(defn charset
  "Content-Type 的 charset=（认不得 / 没给 ⇒ UTF-8）。"
  ^Charset [m]
  (or (when-let [v (header m "Content-Type")]
        (some (fn [p]
                (let [t (str/trim p)]
                  (when (str/starts-with? (lower t) "charset=")
                    (try (Charset/forName (str/replace (str/trim (subs t 8)) "\"" ""))
                         (catch RuntimeException _ nil)))))
              (str/split (str v) #";")))
      StandardCharsets/UTF_8))

(defn success?
  "2xx。"
  [resp]
  (<= 200 (long (or (:status resp) 0)) 299))

;;; ============================================================
;;; URL
;;; ============================================================

(defn url-encode
  "application/x-www-form-urlencoded 编码（空格 ⇒ +）。nil ⇒ nil。"
  [s]
  (when (some? s) (URLEncoder/encode (str s) StandardCharsets/UTF_8)))

(defn query-string
  "map ⇒ 查询串（键取 name）；空 ⇒ nil。"
  [params]
  (when (seq params)
    (str/join "&" (map (fn [[k v]] (str (url-encode (hname k)) "=" (url-encode v))) params))))

(defn with-query
  "给 URL 接上查询参数（已有 ? 用 &）。"
  [url params]
  (if (seq params)
    (str url (if (str/includes? (str url) "?") "&" "?") (query-string params))
    (str url)))

;;; ============================================================
;;; client
;;; ============================================================

(defn- proxy-selector ^ProxySelector [p]
  (cond (nil? p) nil
        (instance? ProxySelector p) p
        (= :env p) (proxy/->proxy-selector (proxy/from-environment))
        (or (string? p) (instance? URI p)) (proxy/->proxy-selector (proxy/web-proxy p))
        (map? p) (proxy/->proxy-selector p)
        :else (throw (IllegalArgumentException. (str ":proxy 认不得：" (pr-str p))))))

(defn client
  "造一个 `java.net.http.HttpClient`。选项（都可省）：

   - `:connect-timeout-ms` 建连上限，缺省 30000；nil ⇒ 不设
   - `:version`  `:http-1.1`（缺省）| `:http-2`。缺省固定 1.1：LiteGate 等本地网关在 HTTP/2 握手阶段直接断连
   - `:executor` `:virtual`（缺省，虚拟线程）| 任意 `java.util.concurrent.Executor`
   - `:redirect` `:never`（缺省，同 JDK 缺省）| `:normal`（不从 https 跟到 http）| `:always`
   - `:proxy`    nil（缺省，直连）| `:env`（读 http_proxy / https_proxy / all_proxy / no_proxy，大小写两种）|
                 代理地址串 / URI | `im.ttalk.http.proxy` 的 map | `java.net.ProxySelector`"
  (^HttpClient [] (client nil))
  (^HttpClient [{:keys [connect-timeout-ms version executor redirect proxy]
                 :or {connect-timeout-ms 30000 version :http-1.1 executor :virtual redirect :never}}]
   (let [^HttpClient$Builder b (HttpClient/newBuilder)]
     (when connect-timeout-ms (.connectTimeout b (Duration/ofMillis (long connect-timeout-ms))))
     (.version b (case version
                   :http-1.1 HttpClient$Version/HTTP_1_1
                   :http-2 HttpClient$Version/HTTP_2))
     (.executor b (if (= :virtual executor)
                    (Executors/newVirtualThreadPerTaskExecutor)
                    ^Executor executor))
     (.followRedirects b (case redirect
                           :never HttpClient$Redirect/NEVER
                           :normal HttpClient$Redirect/NORMAL
                           :always HttpClient$Redirect/ALWAYS))
     (when-let [ps (proxy-selector proxy)] (.proxy b ps))
     (.build b))))

(defonce ^:private default* (delay (client)))

(defn default-client
  "进程共享的缺省 client（第一次调用时建；`(client)` 的缺省设置）。连接池因此在各调用方之间复用。"
  ^HttpClient []
  @default*)

;;; ============================================================
;;; 请求
;;; ============================================================

(def ^:private restricted
  "JDK 不许设的头：设了会抛 IllegalArgumentException。静默丢掉（同 clj-net im.ttalk.net.http.jdk）。"
  #{"host" "connection" "content-length" "expect" "upgrade" "transfer-encoding" "keep-alive"})

(defn- publisher [body]
  (cond (nil? body) (HttpRequest$BodyPublishers/noBody)
        (string? body) (HttpRequest$BodyPublishers/ofString ^String body StandardCharsets/UTF_8)
        (bytes? body) (HttpRequest$BodyPublishers/ofByteArray ^bytes body)
        :else (throw (IllegalArgumentException. (str ":body 只收串 / 字节数组 / nil，收到 " (class body))))))

(defn build-request
  "请求 map ⇒ `HttpRequest`。键：`:method`（关键字或串，缺省 :get）`:url`（串或 URI）`:query-params`
   `:headers` `:body`（串 / 字节 / nil）`:timeout-ms`（总时长，nil ⇒ 不设）。

   有正文而没给 Content-Type ⇒ 补 `application/json`（MiniMax 缺它报「缺 messages」；本库的正文几乎都是 JSON）。"
  ^HttpRequest [{:keys [method url query-params headers body timeout-ms] :or {method :get}}]
  (when (nil? url) (throw (IllegalArgumentException. "请求缺 :url")))
  (let [^URI uri (if (seq query-params)
                   (URI/create (with-query url query-params))
                   (if (instance? URI url) url (URI/create (str url))))
        ^HttpRequest$Builder b (HttpRequest/newBuilder uri)]
    (when timeout-ms (.timeout b (Duration/ofMillis (long timeout-ms))))
    (.method b (str/upper-case (hname method)) (publisher body))
    (doseq [[n v] (header-seq headers)
            :when (not (restricted (lower n)))]
      (.header b n v))
    (when (and (some? body) (empty? (header-values headers "Content-Type")))
      (.header b "Content-Type" "application/json"))
    (.build b)))

(defn- body-handler ^HttpResponse$BodyHandler [as]
  (case (or as :string)
    :string (HttpResponse$BodyHandlers/ofString)          ;; charset= 优先，缺省 UTF-8
    :bytes (HttpResponse$BodyHandlers/ofByteArray)
    :input-stream (HttpResponse$BodyHandlers/ofInputStream)
    :discard (HttpResponse$BodyHandlers/discarding)))

(defn- headers->map [^HttpHeaders hs]
  (persistent!
    (reduce (fn [m ^Map$Entry e]
              (let [^String k (.getKey e)]
                (if (.startsWith k ":")                    ;; HTTP/2 伪头
                  m
                  (assoc! m (lower k) (vec (.getValue e))))))
            (transient {})
            (.entrySet (.map hs)))))

(defn- ->response [^HttpResponse r]
  {:status (.statusCode r)
   :headers (headers->map (.headers r))
   :body (.body r)
   :url (str (.uri r))})

(defn request
  "同步发一条请求，回 `{:status :headers :body :url}`。请求键见 `build-request`，另有：
   - `:as`     `:string`（缺省）| `:bytes` | `:input-stream`（调用方负责关）| `:discard`
   - `:client` 缺省 `(default-client)`
   失败原样抛（IOException 一族、HttpTimeoutException、IllegalArgumentException）。"
  [req]
  (let [^HttpClient c (or (:client req) (default-client))]
    (->response (.send c (build-request req) (body-handler (:as req))))))

(defn- unwrap ^Throwable [^Throwable t]
  (if (and (instance? CompletionException t) (.getCause t)) (.getCause t) t))

(defn request-async
  "`request` 的异步版：回 `CompletableFuture<同形状响应>`，失败（含请求构造失败）异常完成。"
  ^CompletableFuture [req]
  (try
    (let [^HttpClient c (or (:client req) (default-client))]
      (.thenApply (.sendAsync c (build-request req) (body-handler (:as req)))
                  (reify Function (apply [_ r] (->response r)))))
    (catch Throwable t
      (CompletableFuture/failedFuture t))))

;;; ============================================================
;;; 按行真流式
;;; ============================================================

(defonce ^:private watchdog
  ;; 首字节 / 空闲超时的共享调度器：单个 daemon 线程，每条流一个「到点检查 → 未超时按剩余时间重排」
  ;; 的任务（不是固定频率轮询，零空转）。
  (delay (Executors/newSingleThreadScheduledExecutor
           (reify ThreadFactory
             (newThread [_ r]
               (doto (Thread. ^Runnable r "clj-http-watchdog")
                 (.setDaemon true)))))))

(defn timeout-error?
  "`stream-lines` 的 watchdog 超时？回 `:first-byte` / `:idle`，不是 ⇒ nil。"
  [t]
  (::timeout (ex-data t)))

(defn stream-lines
  "按行真流式：每行到达即回调（`BodyHandlers/fromLineSubscriber`，真增量、可取消）。请求键同 `request`，另有：

   - `:on-response` (fn [status headers])  响应头到达时（任何状态码）
   - `:on-line`     (fn [line])            **仅 2xx**：每行一次，单条流内串行；抛异常 ⇒ 中止流、future 以它异常完成
   - `:first-byte-ms` 发出请求到第一行的上限（缺省 = `:idle-ms`）
   - `:idle-ms`       相邻两行之间的上限（空行、SSE 注释心跳也算活动）

   回 `{:future CompletableFuture :cancel (fn [])}`：
   - 2xx 正常结束 ⇒ future 完成为 `{:status :headers}`；
   - 非 2xx ⇒ 不调 `:on-line`，future 完成为 `{:status :headers :body 错误体串}`；
   - watchdog 超时 ⇒ 取消上游，future 以 ex-info 异常完成（`timeout-error?` 回 :first-byte / :idle，ex-data 带 :limit-ms）；
   - IO 失败 ⇒ future 以原异常完成；
   - `cancel` ⇒ 取消上游，future 被取消（CancellationException）。`:on-line` 里调它也行。
   **终止只发生一次**：完成 / 超时 / 取消 / 失败竞争时先到者为准，之后不再回调 `:on-line`。"
  [{:keys [on-line on-response first-byte-ms idle-ms] :as req}]
  (let [result        (CompletableFuture.)
        sub-p         (promise)
        cf-p          (promise)
        done?         (AtomicBoolean. false)
        got-first?    (AtomicBoolean. false)
        last-activity (AtomicLong. (System/nanoTime))
        info          (atom nil)
        abort!        (fn []
                        (when (realized? sub-p) (.cancel ^Flow$Subscription @sub-p))
                        (when (realized? cf-p) (.cancel ^CompletableFuture @cf-p true)))
        succeed!      (fn [v] (when (.compareAndSet done? false true) (.complete result v)))
        fail!         (fn [t] (when (.compareAndSet done? false true) (.completeExceptionally result t)))
        cancel        (fn []
                        (when (.compareAndSet done? false true)
                          (.completeExceptionally result (CancellationException. "stream-lines 已取消")))
                        (abort!))
        subscriber    (reify Flow$Subscriber
                        (onSubscribe [_ s]
                          (deliver sub-p s)
                          (if (.get done?)
                            (.cancel s)
                            (.request s Long/MAX_VALUE)))
                        (onNext [_ line]
                          (.set last-activity (System/nanoTime))
                          (.set got-first? true)
                          (when-not (.get done?)
                            (when on-line
                              (try (on-line line)
                                   (catch Throwable t (fail! t) (abort!))))))
                        (onError [_ t] (fail! (unwrap t)))
                        (onComplete [_] (succeed! @info)))
        handler       (reify HttpResponse$BodyHandler
                        (apply [_ ri]
                          (let [^HttpResponse$ResponseInfo ri ri
                                status (.statusCode ri)
                                hs (headers->map (.headers ri))]
                            (reset! info {:status status :headers hs})
                            (when on-response (on-response status hs))
                            (if (<= 200 status 299)
                              (HttpResponse$BodySubscribers/fromLineSubscriber subscriber)
                              (HttpResponse$BodySubscribers/ofString (charset hs))))))]
    (try
      (let [^HttpClient c (or (:client req) (default-client))
            ^CompletableFuture cf (.sendAsync c (build-request req) handler)]
        (deliver cf-p cf)
        (.whenComplete cf (reify BiConsumer
                            (accept [_ resp t]
                              (if t
                                (fail! (unwrap t))
                                (let [^HttpResponse resp resp]
                                  (when-not (<= 200 (.statusCode resp) 299)
                                    (succeed! (assoc @info :body (.body resp))))))))))
      (catch Throwable t (fail! t)))
    (when (and (or first-byte-ms idle-ms) (not (.get done?)))
      (letfn [(schedule [ms]
                (.schedule ^ScheduledExecutorService @watchdog
                           ^Runnable check (long (max 1 ms)) TimeUnit/MILLISECONDS))
              (check []
                (when-not (.get done?)
                  (let [first? (.get got-first?)
                        limit (if first? idle-ms (or first-byte-ms idle-ms))]
                    (when limit
                      (let [elapsed (quot (- (System/nanoTime) (.get last-activity)) 1000000)]
                        (if (>= elapsed limit)
                          (do (fail! (ex-info (str "流式" (if first? "空闲" "首字节") "超时（" limit "ms 内没有收到数据）")
                                              {::timeout (if first? :idle :first-byte) :limit-ms limit}))
                              (abort!))
                          (schedule (- limit elapsed))))))))]
        (schedule (or first-byte-ms idle-ms))))
    {:future result
     :cancel cancel}))
