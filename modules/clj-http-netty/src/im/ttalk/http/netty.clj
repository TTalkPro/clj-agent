(ns im.ttalk.http.netty
  "Netty HTTP/1.1 transport adapter.

   v1 的生命周期刻意简单：每条请求单独建连，请求带 `Connection: close`；
   没有 channel pool，也不跟随 redirect。ByteBuf 与 channel 由本 adapter 释放，
   provider / 调用方只看到 transport 契约的 Ring 形状。"
  (:require [clojure.string :as str]
            [im.ttalk.http :as http]
            [im.ttalk.http.transport :as transport])
  (:import [io.netty.bootstrap Bootstrap]
           [io.netty.buffer ByteBuf Unpooled]
           [io.netty.channel Channel ChannelHandlerContext ChannelOption
            ChannelInitializer EventLoopGroup]
           [io.netty.channel.nio NioEventLoopGroup]
           [io.netty.channel.socket SocketChannel]
           [io.netty.channel.socket.nio NioSocketChannel]
           [io.netty.handler.codec.http DefaultFullHttpRequest FullHttpRequest
            HttpClientCodec HttpContent HttpUtil HttpMethod HttpResponse
            HttpVersion LastHttpContent]
           [io.netty.handler.ssl SslContext SslContextBuilder]
           [io.netty.util ReferenceCountUtil]
           [io.netty.util.concurrent GenericFutureListener]
           [java.net ConnectException URI UnknownHostException]
           [java.nio ByteBuffer CharBuffer]
           [java.nio.charset Charset CharsetDecoder CodingErrorAction
            StandardCharsets]
           [java.util.concurrent CompletableFuture CancellationException
            ExecutionException ScheduledExecutorService TimeUnit TimeoutException]
           [java.util.concurrent.atomic AtomicBoolean AtomicLong]
           [javax.net.ssl SSLException]))

(set! *warn-on-reflection* true)

;;; ---------- options ----------

(defn- invalid! [message]
  (transport/invalid-request! message))

(defn- new-nio-group ^NioEventLoopGroup [n]
  (NioEventLoopGroup. (int (or n 2))))

(defn- validate-options! [{:keys [event-loop-group io-threads connect-timeout-ms
                                  request-timeout-ms]}]
  (when (and event-loop-group (not (instance? EventLoopGroup event-loop-group)))
    (invalid! ":event-loop-group 必须是 io.netty.channel.EventLoopGroup"))
  (when (and io-threads (not (pos? (long io-threads))))
    (invalid! ":io-threads 必须是正整数"))
  (when (and connect-timeout-ms (not (pos? (long connect-timeout-ms))))
    (invalid! ":connect-timeout-ms 必须是正整数"))
  (when (and request-timeout-ms (not (pos? (long request-timeout-ms))))
    (invalid! ":request-timeout-ms 必须是正整数")))

(defn- validate-request! [{:keys [redirect pool timeout-ms]}]
  (when (some? redirect)
    (invalid! "clj-http-netty v1 不支持 :redirect；请用缺省 :never 或 Apache adapter"))
  (when (some? pool)
    (invalid! "clj-http-netty v1 不支持 :pool；v1 每条请求单独建连"))
  (when (and timeout-ms (not (pos? (long timeout-ms))))
    (invalid! ":timeout-ms 必须是正整数")))

;;; ---------- error mapping ----------

(defn- cause-chain [^Throwable t]
  (take-while some? (iterate #(.getCause ^Throwable %) t)))

(defn- has-cause? [^Class cls ^Throwable t]
  (boolean (some #(instance? cls %) (cause-chain t))))

(defn- unwrap-execution [^Throwable t]
  (loop [t t, seen #{}]
    (if (or (nil? t) (contains? seen t))
      t
      (if (instance? ExecutionException t)
        (recur (.getCause t) (conj seen t))
        t))))

(defn wrap-throwable [^Throwable t]
  (let [raw (unwrap-execution t)]
    (cond
      (transport/error? raw) raw
      (transport/error? t) t
      :else (let [kind (cond
                         (or (instance? UnknownHostException raw)
                             (has-cause? UnknownHostException raw))
                         :name-resolution-error

                         (or (instance? SSLException raw)
                             (has-cause? SSLException raw))
                         :secure-connection-error

                         (or (instance? TimeoutException raw)
                             (has-cause? TimeoutException raw))
                         :timeout-error

                         (instance? CancellationException raw)
                         :cancelled-error

                         (or (instance? ConnectException raw)
                             (has-cause? ConnectException raw))
                         :connection-error

                         (or (instance? java.io.IOException raw)
                             (has-cause? java.io.IOException raw))
                         :connection-error

                         (instance? IllegalArgumentException raw)
                         :invalid-request

                         :else :unknown-io)]
              (transport/error (or (.getMessage ^Throwable raw) (.getName (class raw)))
                               kind t {})))))

;;; ---------- state ----------

(defn- cancel-timer! [x]
  (when-some [^java.util.concurrent.ScheduledFuture f @x] (.cancel f false)))

(defn- finish! [state]
  (when (.compareAndSet ^AtomicBoolean (:done? state) false true)
    (let [{:keys [status headers]} @(:head state)
          bytes (byte-array @(:bytes state))
          {:keys [as url]} @(:request state)
          {:keys [mode]} state
          body (if (= :stream mode)
                 ;; 2xx 是逐行回调；非 2xx 缓冲错误体。成功正文不重复返回。
                 (when-not (<= 200 (long status) 299)
                   (String. ^bytes bytes
                            ^Charset (http/charset headers)))
                 (case as
                   :discard nil
                   :bytes bytes
                   :string (String. ^bytes bytes
                                    ^Charset (http/charset headers))))]
      (cancel-timer! (:watchdog state))
      (cancel-timer! (:request-timer state))
      (.complete ^CompletableFuture (:future state)
                 (transport/normalize-response
                  {:status status :headers headers :body body :url url}))))
  nil)

(defn- fail! [state ^Throwable raw]
  (when (.compareAndSet ^AtomicBoolean (:done? state) false true)
    (let [wrapped (if (transport/error? raw) raw (wrap-throwable raw))]
      (cancel-timer! (:watchdog state))
      (cancel-timer! (:request-timer state))
      (.completeExceptionally ^CompletableFuture (:future state) wrapped)))
  nil)

(defn- new-state [req ^CompletableFuture fut mode]
  {:request (atom req)
   :head (atom {:status 0 :headers {}})
   :bytes (atom [])
   :done? (AtomicBoolean. false)
   :cancelled? (AtomicBoolean. false)
   :seen-data? (AtomicBoolean. false)
   :last-activity (AtomicLong. (System/nanoTime))
   :future fut
   :mode mode
   :secure? (atom false)
   :line (StringBuilder.)
   :decoder (atom nil)
   :channel (atom nil)
   :watchdog (atom nil)
   :request-timer (atom nil)})

(defn- touch! [state]
  (.set ^AtomicBoolean (:seen-data? state) true)
  (.set ^AtomicLong (:last-activity state) (System/nanoTime)))

(defn- touch-head! [state]
  ;; first-byte watchdog 的「byte」是 body content，不是 response head。
  (.set ^AtomicLong (:last-activity state) (System/nanoTime)))

(defn- safe-callback! [state f & args]
  (try (apply f args)
       (catch Throwable t
         (fail! state t)
         nil)))

(defn- charset-decoder [^Charset charset]
  (doto (.newDecoder charset)
    (.onMalformedInput CodingErrorAction/REPLACE)
    (.onUnmappableCharacter CodingErrorAction/REPLACE)))

(defn- feed-lines! [state ^CharBuffer chars]
  (let [{:keys [on-line]} @(:request state)
        ^StringBuilder sb (:line state)]
    (loop []
      (when (.hasRemaining chars)
        (let [ch (.get chars)]
          (cond
            (= \newline ch)
            (let [line (str sb)]
              (.setLength sb 0)
              (safe-callback! state on-line line))

            (= \return ch) nil

            :else (.append sb ch))
          (recur))))))

(defn- stream-bytes! [state bytes]
  (let [{:keys [as]} @(:request state)
        {:keys [headers]} @(:head state)]
    (if (= :discard as)
      nil
      (do
        (when-not @(:decoder state)
          (reset! (:decoder state) (charset-decoder (http/charset headers))))
        (let [chars (CharBuffer/allocate (max 16 (* 2 (alength ^bytes bytes))))]
          (.decode ^CharsetDecoder @(:decoder state)
                   (ByteBuffer/wrap ^bytes bytes) chars false)
          (.flip chars)
          (feed-lines! state chars))))))

(defn- handle-head! [state ^HttpResponse response]
  (touch-head! state)
  (let [headers (persistent!
                 (let [netty-headers (.headers response)]
                   (reduce (fn [acc ^String name]
                             (let [n (str/lower-case name)]
                               (assoc! acc n (into [] (.getAll netty-headers name)))))
                           (transient {})
                           (.names netty-headers))))
        status (.code (.status response))]
    (reset! (:head state) {:status status :headers headers})
    (when-some [on-response (:on-response @(:request state))]
      (safe-callback! state on-response status headers))))

(defn- handle-content! [state ^HttpContent content]
  (touch! state)
  (let [buf (.content content)
        n (.readableBytes buf)
        bytes (byte-array n)]
    (try
      (.getBytes buf (.readerIndex buf) bytes 0 n)
      (let [{:keys [mode]} state
            {:keys [status]} @(:head state)]
        (swap! (:bytes state) (fn [old] (into old bytes)))
        (if (= :stream mode)
          (when (<= 200 (long status) 299)
            (stream-bytes! state bytes))
          nil))
      (finally (ReferenceCountUtil/release buf)))))

(defn- handler [state]
  (proxy [io.netty.channel.ChannelInboundHandlerAdapter] []
    (channelRead [^ChannelHandlerContext ctx msg]
      (try
        (cond
          (instance? HttpResponse msg)
          (handle-head! state msg)

          (instance? HttpContent msg)
          (handle-content! state msg)

          :else (ReferenceCountUtil/release msg))
        (when (instance? LastHttpContent msg)
          (finish! state)
          (.close (.channel ctx)))
        (catch Throwable t
          (fail! state t)
          (.close (.channel ctx)))))
    (exceptionCaught [^ChannelHandlerContext ctx t]
      (fail! state t)
      (.close (.channel ctx)))
    (channelInactive [^ChannelHandlerContext ctx]
      (when-not (.get ^AtomicBoolean (:done? state))
        (fail! state (if @(:secure? state)
                       (SSLException. "TLS connection closed before response completed")
                       (java.io.IOException. "connection closed before response completed"))))
      (.fireChannelInactive ctx))))

;;; ---------- request construction ----------

(defn- uri-for [req]
  (let [url (http/with-query (:url req) (:query-params req))]
    (try
      (if (instance? URI url) url (URI/create (str url)))
      (catch Throwable t
        (transport/invalid-request! ":url 不是合法 URI" t {:url url})))))

(defn- host-header [^URI uri]
  (let [host (.getHost uri)
        port (.getPort uri)]
    (when (str/blank? host)
      (invalid! ":url 缺 host"))
    (if (or (neg? port) (= 80 port) (= 443 port))
      host
      (str host ":" port))))

(defn- method-for [method]
  (try (HttpMethod/valueOf (str/upper-case (if (keyword? method) (name method) (str method))))
       (catch Throwable _ (invalid! (str ":method 不合法：" (pr-str method))))))

(defn- netty-request [req]
  (let [^URI uri (uri-for req)
        scheme (str/lower-case (or (.getScheme uri) "http"))
        _ (when-not (#{"http" "https"} scheme)
            (invalid! ":url scheme 只支持 http / https"))
        ^bytes body-bytes (if (some? (:body req))
                     (if (string? (:body req))
                       (.getBytes ^String (:body req) StandardCharsets/UTF_8)
                       (:body req))
                     (byte-array 0))
        ^ByteBuf content (Unpooled/wrappedBuffer body-bytes)
        ^HttpMethod method (method-for (:method req))
        hr (DefaultFullHttpRequest. HttpVersion/HTTP_1_1
                                    method
                                    (str uri)
                                    content)]
    (doseq [[k vs] (:headers req), v vs]
      (.add (.headers hr) ^String (str k) ^String (str v)))
    (HttpUtil/setContentLength hr (alength ^bytes body-bytes))
    (.set (.headers hr) "Host" (host-header uri))
    (.set (.headers hr) "Connection" "close")
    {:request hr :secure? (= "https" scheme) :uri uri}))

(defn- schedule-fixed! [^EventLoopGroup eg f delay-ms]
  (.scheduleAtFixedRate ^ScheduledExecutorService eg
                        (cast Runnable f)
                        (long delay-ms) (long delay-ms) TimeUnit/MILLISECONDS))

(defn- schedule-once! [^EventLoopGroup eg f delay-ms]
  (let [^Runnable runnable (cast Runnable f)]
    (.schedule ^ScheduledExecutorService eg
               runnable
               (long delay-ms) TimeUnit/MILLISECONDS)))

(defn- request-timer! [state ^EventLoopGroup eg limit-ms]
  (when (and limit-ms (pos? (long limit-ms)))
    (reset! (:request-timer state)
            (schedule-once! eg
                             (fn []
                               (when-not (.get ^AtomicBoolean (:done? state))
                                 (.set ^AtomicBoolean (:cancelled? state) true)
                                 (fail! state (transport/error "request timeout"
                                                               :timeout-error nil
                                                               {:phase :request :limit-ms limit-ms}))
                                 (when-some [^Channel ch @(:channel state)]
                                   (.close ch))))
                             limit-ms))))

(defn- stream-watchdog! [state ^EventLoopGroup eg]
  (let [{:keys [first-byte-ms idle-ms]} @(:request state)]
    (when (and (or first-byte-ms idle-ms)
               (pos? (long (or idle-ms first-byte-ms))))
      (reset! (:watchdog state)
              (schedule-fixed! eg
                               (fn []
                                 (when-not (.get ^AtomicBoolean (:done? state))
                                   (let [idle? (.get ^AtomicBoolean (:seen-data? state))
                                         threshold (if idle? idle-ms first-byte-ms)
                                         threshold (long (or threshold (or idle-ms first-byte-ms) 0))
                                         elapsed (- (System/nanoTime)
                                                    (.get ^AtomicLong (:last-activity state)))]
                                     (when (>= elapsed (* 1000000 threshold))
                                       (.set ^AtomicBoolean (:cancelled? state) true)
                                       (fail! state (transport/error
                                                     (if idle? "idle timeout" "first-byte timeout")
                                                     :timeout-error nil
                                                     {:phase (if idle? :idle :first-byte)
                                                      :limit-ms threshold}))
                                       (when-some [^Channel ch @(:channel state)]
                                         (.close ch))))))
                               20)))))

(defn- connect! [{:keys [event-loop-group io-threads connect-timeout-ms]} state wire]
  (let [{:keys [^FullHttpRequest request secure? ^URI uri]} wire
        own-group? (nil? event-loop-group)
        ^EventLoopGroup eg (or event-loop-group
                               (new-nio-group io-threads))
        ssl (when secure?
              (.build ^SslContextBuilder (SslContextBuilder/forClient)))
        b (doto (Bootstrap.)
            (.group eg)
            (.channel NioSocketChannel)
            (.option ChannelOption/CONNECT_TIMEOUT_MILLIS
                     (int (or connect-timeout-ms 30000)))
            (.handler (proxy [ChannelInitializer] []
                        (initChannel [^SocketChannel ch]
                          (let [p (.pipeline ch)]
                            (when secure?
                              (let [ssl-handler (.newHandler ^SslContext ssl (.alloc ch))]
	                                ;; TLS 握手沿用建连上限；TLS 失败不能等到总请求 timeout。
	                                (.setHandshakeTimeoutMillis ^io.netty.handler.ssl.SslHandler
	                                                            ssl-handler
	                                                            (long (or connect-timeout-ms 10000)))
                                (.addLast p "ssl" ssl-handler)))
                            (.addLast p "codec" (HttpClientCodec.))
                            (.addLast p "transport"
                                      ^io.netty.channel.ChannelHandler (handler state)))))))
        cf (.connect b (.getHost uri)
                     (int (let [port (.getPort uri)]
                            (if (>= port 0) port (if secure? 443 80)))))]
    (.addListener cf
                  (reify GenericFutureListener
                    (operationComplete [_ f]
                      (if (.isSuccess f)
                        (let [^Channel ch (.channel ^io.netty.channel.ChannelFuture f)]
                          (reset! (:channel state) ch)
                          (.writeAndFlush ch request))
                        (do
                          (fail! state (.cause f))
                          (when own-group? (.shutdownGracefully eg)))))))))

(defn- effective-timeout [req {:keys [request-timeout-ms]}]
  (or (:timeout-ms req) request-timeout-ms))

(defn- run-request! [mode opts req]
  (validate-request! req)
  ;; 提前解析 URL，避免先分配 event loop 才发现请求不可用。
  (let [wire (netty-request req)
        fut (CompletableFuture.)
        state (assoc (new-state req fut mode) :secure? (atom (:secure? wire)))
        ^EventLoopGroup eg (:event-loop-group opts)
        cancel-fn (fn []
                    (when (.compareAndSet ^AtomicBoolean (:cancelled? state) false true)
                      (.cancel ^CompletableFuture fut true)
                      (cancel-timer! (:watchdog state))
                      (cancel-timer! (:request-timer state))
                      (when-some [^Channel ch @(:channel state)] (.close ch))))
        cancel-on-future! (reify java.util.function.BiConsumer
                            (accept [_ _ error]
                              (when (instance? CancellationException error)
                                (cancel-fn))))]
    (.whenComplete fut cancel-on-future!)
    (request-timer! state eg (effective-timeout req opts))
    (when (= :stream mode)
      (stream-watchdog! state eg))
    (connect! opts state wire)
    (if (= :stream mode)
      {:future fut :cancel cancel-fn}
      fut)))

;;; ---------- public API ----------

(defn transport
  ([] (transport {}))
  ([opts]
   (validate-options! opts)
   (let [own-group? (nil? (:event-loop-group opts))
         ^EventLoopGroup eg (or (:event-loop-group opts)
                                (new-nio-group (:io-threads opts)))
         closed? (AtomicBoolean. false)
         opts (assoc opts :event-loop-group eg)
         close! (fn []
                  (when (.compareAndSet closed? false true)
                    (.shutdownGracefully eg)))]
     {:name :netty
      :capabilities #{:string :bytes :discard :stream-lines}
      :request (fn [req]
                 (when (.get closed?)
                   (invalid! "transport 已关闭"))
                 (try
                   (let [fut (run-request! :aggregate opts req)
                         timeout (or (:timeout-ms req) (:request-timeout-ms opts))]
                     (if timeout
                       (.get ^CompletableFuture fut (long timeout) TimeUnit/MILLISECONDS)
                       (.get ^CompletableFuture fut)))
                   (catch Throwable t
                     (throw (wrap-throwable t)))))
      :request-async (fn [req]
                       (when (.get closed?)
                         (invalid! "transport 已关闭"))
                       (run-request! :aggregate opts req))
      :stream-lines (fn [req]
                      (when (.get closed?)
                        (invalid! "transport 已关闭"))
                      (run-request! :stream opts req))
      :close close!})))

(defn close
  [t]
  (transport/close t))
