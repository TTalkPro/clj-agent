(ns im.ttalk.http.apache
  "Apache HttpClient 5 的 Ring 风格 transport。

   主路径只有 async client；同步 `transport/request` 是 `CompletableFuture` 的
   `.get`。SSE / 普通流式用 `AbstractCharResponseConsumer` 在每个 chunk 解码后
   按行回调，不先聚合完整 body。异常统一折成 `im.ttalk.http.transport` 约定，
   Apache / reactor 类型不穿过插件边界。"
  (:require [clojure.string :as str]
            [im.ttalk.http.transport :as transport])
  (:import [java.net URI UnknownHostException ConnectException]
           [java.nio ByteBuffer CharBuffer]
           [java.nio.charset Charset StandardCharsets]
           [java.util.concurrent CompletableFuture CancellationException ExecutionException
            Future
            TimeUnit TimeoutException]
           [java.util.concurrent.atomic AtomicBoolean AtomicLong]
           [java.util.function BiFunction]
           [javax.net.ssl SSLException]
           [org.apache.hc.client5.http.async.methods AbstractCharResponseConsumer
            SimpleRequestProducer SimpleRequestBuilder]
           [org.apache.hc.client5.http.async.methods SimpleHttpRequest
            SimpleHttpResponse]
           [org.apache.hc.client5.http.config RequestConfig]
           [org.apache.hc.client5.http.impl.async CloseableHttpAsyncClient
            HttpAsyncClientBuilder HttpAsyncClients]
           [org.apache.hc.client5.http.impl.nio PoolingAsyncClientConnectionManagerBuilder]
           [org.apache.hc.core5.concurrent FutureCallback]
           [org.apache.hc.core5.http ContentType HttpHost]
           [org.apache.hc.core5.util Timeout]))

(set! *warn-on-reflection* true)

;;; ---------- options ----------

(defn- timeout-value ^Timeout [v default]
  (if (some? v)
    (Timeout/ofMilliseconds (long v))
    default))

(defn- redirect? [v]
  (cond
    (nil? v) false
    (boolean? v) v
    (= :never v) false
    (#{:normal :always} v) true
    :else (transport/invalid-request! ":redirect 只收 :never / :normal / :always / boolean")))

(defn- proxy-host ^HttpHost [v]
  (when (some? v)
    (if (instance? HttpHost v)
      v
      (HttpHost/create (str v)))))

;;; ---------- request/response ----------

(defn- headers-map [^org.apache.hc.core5.http.MessageHeaders response]
  (persistent!
   (reduce (fn [acc [k v]]
             (assoc! acc (str/lower-case (str k))
                     (conj (get acc (str/lower-case (str k)) []) (str v))))
           (transient {})
           (map (fn [h] [(.getName ^org.apache.hc.core5.http.Header h)
                         (.getValue ^org.apache.hc.core5.http.Header h)])
                (iterator-seq (.headerIterator response))))))

(defn- request-config [{:keys [connect-timeout-ms timeout-ms redirect]}]
  (-> (RequestConfig/custom)
      (.setConnectTimeout (timeout-value connect-timeout-ms (Timeout/ofMilliseconds 30000)))
      (.setResponseTimeout (timeout-value timeout-ms Timeout/DISABLED))
      (.setRedirectsEnabled (redirect? redirect))
      (.build)))

(defn- apache-request ^SimpleHttpRequest [{:keys [method url headers body timeout-ms]}]
  (let [method-name (str/upper-case (if (keyword? method) (name method) (str method)))
        content-type (or (some-> headers (get "content-type") first ContentType/parse)
                         ContentType/APPLICATION_JSON)
        b (doto (SimpleRequestBuilder/create method-name)
            (.setUri (str (if (instance? URI url) (.toString ^URI url) url)))
            (.setRequestConfig (request-config
                                {:connect-timeout-ms nil
                                 :timeout-ms timeout-ms
                                 :redirect nil})))]
    (doseq [[k vs] headers
            v vs]
      (when-not (= "content-type" (str/lower-case (str k)))
        (.addHeader b ^String k ^String v)))
    (when (some? body)
      (if (string? body)
        (.setBody b ^String body content-type)
        (.setBody b ^bytes body content-type)))
    (.build b)))

(defn- body-of [resp]
  (if (instance? org.apache.hc.client5.http.async.methods.SimpleHttpResponse resp)
    (or (.getBodyBytes ^org.apache.hc.client5.http.async.methods.SimpleHttpResponse resp)
        "")
    ""))

(defn- normalize-simple-response [resp as]
  (let [bytes (body-of resp)
        body (case as
               :string (String. ^bytes bytes StandardCharsets/UTF_8)
               :bytes bytes
               :discard nil)]
    (transport/normalize-response
     {:status (.getCode ^org.apache.hc.client5.http.async.methods.SimpleHttpResponse resp)
      :headers (headers-map resp)
      :body body})))

;;; HC5 在父类上定义了 protected final setCharset；Clojure 的常规 interop 反射
;;; 只找 public 方法，这里用已授权的反射访问 adapter 自己的子类实例。
(defn- set-consumer-charset! [consumer ^Charset charset]
  (let [method (.getDeclaredMethod org.apache.hc.core5.http.nio.entity.AbstractCharDataConsumer
                                   "setCharset"
                                   (into-array Class [Charset]))]
    (doto method (.setAccessible true))
    (.invoke method consumer (to-array [charset]))))

;;; ---------- errors ----------

(defn- unwrap [t]
  (loop [t t, seen #{}]
    (if (or (nil? t) (contains? seen t))
      t
      (if (or (instance? ExecutionException t) (instance? RuntimeException t)
              (= (class t) java.lang.reflect.InvocationTargetException))
        (let [cause (.getCause ^Throwable t)]
          (if cause
            (recur cause (conj seen t))
            t))
        t))))

(defn- cause-chain [t]
  (take-while some? (iterate #(.getCause ^Throwable %) t)))

(defn- has-cause? [^Class cls t]
  (boolean (some #(instance? cls %) (cause-chain t))))

(defn wrap-throwable ^Throwable [^Throwable t]
  (if (transport/error? t)
    t
    (let [^Throwable raw (unwrap t)]
      (if (transport/error? raw)
        raw
        (let [kind (cond
                 (or (instance? UnknownHostException raw)
                     (has-cause? UnknownHostException raw)) :name-resolution-error
                 (or (instance? SSLException raw)
                     (has-cause? SSLException raw)) :secure-connection-error
                 (or (instance? TimeoutException raw)
                     (instance? java.net.http.HttpTimeoutException raw)
                     (has-cause? java.net.http.HttpTimeoutException raw)) :timeout-error
                 (instance? ConnectException raw) :connection-error
                 (instance? CancellationException raw) :cancelled-error
                 (instance? InterruptedException raw) :cancelled-error
                 (instance? java.io.IOException raw) :unknown-io
                 (instance? IllegalArgumentException raw) :invalid-request
                 :else :unknown-io)]
          (transport/error (or (.getMessage raw) (.getName (class raw))) kind t {}))))))

(defn- bridge ^FutureCallback [^CompletableFuture f]
  (reify FutureCallback
    (completed [_ result] (.complete f result))
    (failed [_ t] (.completeExceptionally f (wrap-throwable t)))
    (cancelled [_] (.cancel f false))))

(defn- complete-once [^AtomicBoolean done? ^CompletableFuture fut v]
  (when (.compareAndSet done? false true)
    (.complete fut v)))

(defn- complete-exception-once [^AtomicBoolean done? ^CompletableFuture fut ^Throwable t]
  (when (.compareAndSet done? false true)
    (.completeExceptionally fut (wrap-throwable t))))

;;; ---------- watchdog helpers ----------

(defn- new-scheduler []
  (java.util.concurrent.Executors/newSingleThreadScheduledExecutor
   (reify java.util.concurrent.ThreadFactory
     (newThread [_ runnable]
       (doto (Thread. runnable "clj-http-apache-stream-watchdog")
         (.setDaemon true))))))

;;; ---------- streaming consumer ----------

(defn- touch! [state]
  (.set ^AtomicLong (:last-activity state) (System/nanoTime))
  (.set ^AtomicBoolean (:seen-data state) true))

(defn- feed-lines! [state ^CharBuffer chars {:keys [on-line]}]
  (let [sb ^StringBuilder (:line state)]
    (loop []
      (when (.hasRemaining chars)
        (let [ch (.get chars)]
          (cond
            (= \newline ch) (let [line (str sb)]
                              (.setLength sb 0)
                              (on-line line))
            (= \return ch) nil
            :else (.append sb ch))
          (recur))))))

(defn- line-consumer
  [headers-atom body-atom status-atom state {:keys [as on-line on-response]} fut done? terminate!]
  (proxy [AbstractCharResponseConsumer] []
    (start [response content-type]
      (let [code (.getCode ^org.apache.hc.core5.http.HttpResponse response)
            headers (headers-map response)]
        (reset! status-atom code)
        (reset! headers-atom headers)
        (when on-response (on-response code headers))
        (when (= 2 (quot code 100))
          (set-consumer-charset! this
                                 (or (some-> ^org.apache.hc.core5.http.ContentType content-type
                                             (.getCharset StandardCharsets/UTF_8))
                                     StandardCharsets/UTF_8)))))
    (data [chars end]
      (try
        (touch! state)
        (if (= :discard as)
          nil
          (if (= 2 (quot @status-atom 100))
            (feed-lines! state chars {:on-line (fn [line]
                                                (when-not (.get ^AtomicBoolean done?)
                                                  (on-line line)))})
            (do
              (.append ^StringBuilder (:body state) (str chars))
              (.position ^CharBuffer chars (.limit ^CharBuffer chars)))))
        (catch Throwable t
          (complete-exception-once done? fut t)
          (throw (ex-info "stream callback failed" {} t)))))
    (buildResult []
      (let [status @status-atom]
        (touch! state)
        (if (= 2 (quot status 100))
          {:status status :headers @headers-atom}
          (do
            (reset! body-atom (.toString ^StringBuilder (:body state)))
            {:status status :headers @headers-atom :body @body-atom}))))
    (failed [t]
      (complete-exception-once done? fut t))
    (releaseResources []
      (.setLength ^StringBuilder (:body state) 0)
      (.setLength ^StringBuilder (:line state) 0))))

;;; ---------- transport ----------

(defn- build-client [{:keys [connect-timeout-ms request-timeout-ms pool redirect proxy]}]
  (let [pool-b (cond-> (PoolingAsyncClientConnectionManagerBuilder/create)
                 (and (map? pool) (integer? (:max-total pool))) (.setMaxConnTotal (int (:max-total pool)))
                 (and (map? pool) (integer? (:max-per-route pool))) (.setMaxConnPerRoute (int (:max-per-route pool))))
        ^HttpAsyncClientBuilder b (cond-> (HttpAsyncClients/custom)
                                    true (.setConnectionManager (.build pool-b))
                                    true (.setDefaultRequestConfig
                                          (request-config
                                           {:connect-timeout-ms connect-timeout-ms
                                            :timeout-ms request-timeout-ms
                                            :redirect redirect}))
                                    proxy (.setProxy (proxy-host proxy)))]
    (.build b)))

(defn transport
  ([] (transport {}))
  ([opts]
   (let [client (delay (build-client opts))
         closed? (AtomicBoolean. false)
         started? (AtomicBoolean. false)
         ensure-started! (fn []
                           (when (.compareAndSet started? false true)
                             (.start ^CloseableHttpAsyncClient @client)))]
     {:name :apache
      :capabilities #{:string :bytes :discard :stream-lines}
      :request (fn [req]
                 (try
                   (ensure-started!)
                   (let [wrapped (CompletableFuture.)
                         timeout (:timeout-ms req)
                         _ (.execute ^CloseableHttpAsyncClient @client
                                     (apache-request req)
                                     (bridge wrapped))]
                     (normalize-simple-response
                      (if (some? timeout)
                        (.get ^CompletableFuture wrapped (long timeout) TimeUnit/MILLISECONDS)
                        (.get ^CompletableFuture wrapped))
                      (:as req))
                   )
                   (catch Throwable t (throw (wrap-throwable t)))))
      :request-async (fn [req]
                       (ensure-started!)
                       (let [wrapped (CompletableFuture.)]
                         (.execute ^CloseableHttpAsyncClient @client
                                   (apache-request req)
                                   (bridge wrapped))
                         (.thenApply ^CompletableFuture wrapped
                                     (reify java.util.function.Function
                                       (apply [_ resp]
                                         (normalize-simple-response resp (:as req)))))) )
      :stream-lines (fn [{:keys [as on-response] :as req}]
                      (ensure-started!)
                      (let [fut (CompletableFuture.)
                            done? (AtomicBoolean. false)
                            seen-data? (AtomicBoolean. false)
                            state {:line (StringBuilder.)
                                   :body (StringBuilder.)
                                   :last-activity (AtomicLong. (System/nanoTime))
                                   :seen-data seen-data?}
                            headers (atom nil)
                            body (atom nil)
                            status (atom 0)
                            cancelled? (AtomicBoolean. false)
                            watchdog (atom nil)
                            raw (atom nil)
                            terminate! (fn [phase limit]
                                         (when (.compareAndSet done? false true)
                                           (when-some [r @raw] (.cancel ^Future r true))
                                           (.completeExceptionally fut
                                             (transport/error
                                              (str (if (= :idle phase) "空闲" "首字节") "超时")
                                              :timeout-error nil
                                              {:phase phase :limit-ms limit})))
                                         (when-some [s @watchdog] (.cancel ^Future s true))
                                         nil)
                            consumer (line-consumer headers body status state req fut done? terminate!)]
                        ;; activity 在 consumer 每个字符 chunk 更新；seen-data 区分首字节/相邻两行。
                        (reset! watchdog
                                (when (and (or (:first-byte-ms req) (:idle-ms req)))
                                  (let [^java.util.concurrent.ScheduledExecutorService scheduler (new-scheduler)]
                                  (.scheduleAtFixedRate
                                   scheduler
                                   (fn []
                                     (when-not (.get done?)
                                       (let [elapsed (- (System/nanoTime)
                                                        (.get ^AtomicLong (:last-activity state)))]
                                         (when (>= elapsed (* 1000000 (long (or (:idle-ms req)
                                                                                (:first-byte-ms req)
                                                                                0))))
                                           (terminate! (if (.get seen-data?) :idle :first-byte)
                                                       (or (:idle-ms req) (:first-byte-ms req)))))))
                                   20 20 TimeUnit/MILLISECONDS))))
                        (let [request (apache-request req)
                              raw-future (.execute ^CloseableHttpAsyncClient @client
                                            (SimpleRequestProducer/create request)
                                            consumer
                                            nil
                                            nil
                                            (reify FutureCallback
                                              (completed [_ result]
                                                (complete-once done? fut result)
                                                (when-some [s @watchdog] (.cancel ^Future s true)))
                                              (failed [_ t]
                                                (complete-exception-once done? fut t)
                                                (when-some [s @watchdog] (.cancel ^Future s true)))
                                              (cancelled [_]
                                                (when-not (.get done?)
                                                  (.cancel fut false))
                                                (when-some [s @watchdog] (.cancel ^Future s true)))))]
                          (reset! raw raw-future)
                          {:future fut
                           :cancel (fn []
                                     (when (.compareAndSet cancelled? false true)
                                       (.cancel ^Future raw-future true)
                                       (.cancel ^CompletableFuture fut true)
                                       (when-some [s @watchdog] (.cancel ^Future s true))))} )))
      :close (fn []
               (when (.compareAndSet closed? false true)
                 (.close ^CloseableHttpAsyncClient @client
                         org.apache.hc.core5.io.CloseMode/IMMEDIATE)))})))
