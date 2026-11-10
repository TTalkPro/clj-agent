(ns im.ttalk.agent.server.jetty
  "宿主（C# Keelson.Net.Server 的 KeelsonServer，N-02）：绑地址、挂 Ring 处理器、起、停。**只有这个 ns 认识 Jetty** ——
   路由（routing）、访问守卫（guard）、SSE（sse）、WebSocket（websocket）、请求体上限（http）都是普通的 Ring 处理器 / 中间件，
   换一个 Ring 服务端（http-kit …）照样能挂。

   宿主是 Jetty 12（`ring/ring-jetty-adapter`，ee9 servlet + Ring 的 WebSocket 规范），线程池是 Jetty 的 VirtualThreadPool
   （每个请求一条虚拟线程）。Jetty 那边适配器没开口的（localhost 第二个连接器、URI 合规、请求行上限）经它的 :configurator 钩子
   或一层宿主自己的中间件补，都在这个 ns 里。

   ```clojure
   (def s (server/start [[\"GET\" \"/hello\" (fn [req] {:status 200 :body \"hi\"})]] {:port 0}))
   (server/base-address s)   ;; => \"http://127.0.0.1:54321/\"
   (server/stop! s)
   ```

   第一个参数是 Ring 处理器或路由表（im.ttalk.agent.server.routing）。选项：
     :host                 IP 字面量（缺省 \"127.0.0.1\"，只给本机）或 \"localhost\"（IPv4 + IPv6 回环两个连接器，端口不能是 0）
     :port                 0 = 内核分配（测试常用），启动后读 `port`
     :unix-socket-path     给了就只在这个 Unix 域套接字上监听（:host / :port 不用）。路径上已有文件：连得上 ⇒ 有人在服务，抛
                           `server/unix-socket-in-use`；连不上 ⇒ 上次的残留，删掉再绑。停机时删掉
     :shutdown-timeout-ms  优雅停机最多等多久（在飞的请求 —— 包括 SSE 长连接 —— 收尾的时间）。缺省 5000
     :ssl-context          javax.net.ssl.SSLContext ⇒ 走 HTTPS；`base-address` 随之是 https://
     :key-store / :key-password   代替 :ssl-context：java.security.KeyStore（要带私钥条目，否则 `server/certificate-no-private-key`）
     :max-request-body-size        请求体上限（字节）。Content-Length 超了 ⇒ 413；处理器里 `http/read-body` 也守这个数
     :max-request-headers-total-size  请求头总大小上限（缺省 32 KiB，同 Kestrel；超了 Jetty 回 431）
     :max-request-line-size        请求行（方法 + 目标 + 版本）上限；超了回 414（Jetty 没有单独这一格：宿主中间件判）
     :ws-max-message-bytes         服务端对一条 WebSocket 消息的总上限（缺省 64 MiB；端点自己的 :max-message-bytes 在它之内再判）
     :ws-idle-timeout-ms           WebSocket 空闲多久断（缺省 1 小时；端点的保活 ping 会刷新它）
     :idle-timeout-ms              HTTP 连接空闲多久断（缺省 200000；SSE 长连接靠心跳保活）
     :guard                本机访问守卫选项（im.ttalk.agent.server.guard）；包在最外层。给了而 :host 不是回环、:token 又为空
                           ⇒ `create` 抛 `server/bind-needs-token`（Unix 域套接字不算网络绑定，不查）
     :middleware           [(fn [handler] handler) …]，由内到外包在路由外面（守卫仍在最外层）
     :on-stopping          [(fn [] …) …]，停机开始时调（C# ApplicationStopping.Register）。路由表的元数据
                           `:im.ttalk.agent.server/on-stopping` 里的也会登记（im.ttalk.agent.server.agui 的路由靠它在停机时按停在飞的 run）

   处理器抛 `server/body-too-large` ⇒ 413；抛别的 ⇒ 500（正文为空）。

   与 C# 的差别：对端断开只在写的时候发现（见 im.ttalk.agent.server.sse）；不清空 / 不接管日志（Jetty 走 SLF4J）。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.server.strings :as err]
            [im.ttalk.agent.server.guard :as guard]
            [im.ttalk.agent.server.http :as http]
            [im.ttalk.agent.server.routing :as routing]
            [im.ttalk.agent.server.strings :refer [msg]]
            [ring.adapter.jetty :as jetty]
            [ring.core.protocols :as protocols]
            [ring.websocket :as ws])
  (:import (java.io File IOException)
           (java.net Inet6Address InetAddress UnixDomainSocketAddress)
           (java.nio.channels SocketChannel)
           (java.nio.file Files)
           (java.security KeyStore)
           (java.util.concurrent CompletableFuture ConcurrentHashMap)
           (java.util.concurrent.atomic AtomicBoolean AtomicLong)
           (javax.net.ssl KeyManagerFactory SSLContext)
           (org.eclipse.jetty.http UriCompliance)
           (org.eclipse.jetty.server ConnectionFactory Connector HttpConnectionFactory Server ServerConnector SslConnectionFactory)
           (org.eclipse.jetty.util.thread VirtualThreadPool)))

(set! *warn-on-reflection* true)

(def default-options
  {:host "127.0.0.1" :port 0 :shutdown-timeout-ms 5000
   :max-request-headers-total-size (* 32 1024)
   :ws-max-message-bytes (* 64 1024 1024)
   :ws-idle-timeout-ms (* 60 60 1000)
   :idle-timeout-ms 200000})

;; ── 校验 / 地址 ──────────────────────────────────────

(defn- argument-error [m] (err/error m {:im.ttalk.agent.server/kind :argument}))

(defn- bind-addresses
  "选项 ⇒ 要绑的地址（localhost ⇒ 127.0.0.1 与 ::1）。"
  [{:keys [host port]}]
  (if (.equalsIgnoreCase "localhost" (str host))
    (do (when (zero? (long port)) (throw (argument-error (msg :server/localhost-port-zero))))
        [(InetAddress/getByName "127.0.0.1") (InetAddress/getByName "::1")])
    (if-let [ip (guard/ip-literal host)]
      [ip]
      (throw (argument-error (msg :server/bad-host host))))))

(defn- ->chars ^chars [pw] (cond (nil? pw) (char-array 0) (string? pw) (.toCharArray ^String pw) :else pw))

(defn- ssl-context-of
  "选项 ⇒ SSLContext 或 nil。:key-store 里没有私钥条目 ⇒ `server/certificate-no-private-key`。"
  ^SSLContext [{:keys [ssl-context key-store key-password]}]
  (cond
    ssl-context ssl-context
    key-store
    (let [^KeyStore ks key-store]
      (when-not (some #(.isKeyEntry ks %) (enumeration-seq (.aliases ks)))
        (throw (argument-error (msg :server/certificate-no-private-key))))
      (let [kmf (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
                  (.init ks (->chars key-password)))]
        (doto (SSLContext/getInstance "TLS")
          (.init (.getKeyManagers kmf) nil nil))))
    :else nil))

(defn- clear-stale-socket!
  "路径上已有文件：连得上 ⇒ 抛（别抢别人的套接字）；连不上 ⇒ 残留，删掉。"
  [^String path]
  (let [f (File. path)]
    (when (.exists f)
      (let [in-use? (try (with-open [_ (SocketChannel/open (UnixDomainSocketAddress/of path))] true)
                         (catch IOException _ false))]
        (when in-use?
          (throw (err/error (msg :server/unix-socket-in-use path) {:im.ttalk.agent.server/kind :io})))
        (Files/deleteIfExists (.toPath f))))))

;; ── 宿主那几层中间件（只在这里：与服务端的生命周期绑在一起）──

(defn- track-body
  "在飞计数：流式正文写完才算收尾（SSE 长连接也在里面）。"
  [resp ^AtomicLong in-flight]
  (let [done! #(.decrementAndGet in-flight)
        b (:body resp)]
    (if (or (nil? resp) (nil? b) (ws/websocket-response? resp))
      (do (done!) resp)
      (assoc resp :body (reify protocols/StreamableResponseBody
                          (write-body-to-stream [_ r out]
                            (try (protocols/write-body-to-stream b r out) (finally (done!)))))))))

(defn- wrap-host
  "把停机信号与「开着的长连接」表放进请求（SSE / WebSocket 据此在停机时收尾），并数在飞的请求。"
  [handler server]
  (let [^AtomicLong in-flight (:in-flight server)
        extra {:im.ttalk.agent.server/stopping (:stopping server)
               :im.ttalk.agent.server/open-streams (:open-streams server)}]
    (fn [req]
      (.incrementAndGet in-flight)
      (let [resp (try (handler (merge req extra))
                      (catch Throwable t (.decrementAndGet in-flight) (throw t)))]
        (track-body resp in-flight)))))

(defn- wrap-request-line-limit
  "请求行（方法 SP 目标 SP 版本）超长 ⇒ 414（C# MaxRequestLineSize；Jetty 只有一格总的请求头上限）。"
  [handler limit]
  (if-not limit
    handler
    (fn [req]
      (let [n (+ (count (name (:request-method req))) 1 (count (:uri req))
                 (if-let [q (:query-string req)] (inc (count q)) 0)
                 1 (count (or (:protocol req) "HTTP/1.1")))]
        (if (> n (long limit)) (http/response 414) (handler req))))))

(defn- build-handler [handler-or-routes {:keys [middleware max-request-body-size max-request-line-size] g :guard}]
  (let [base (if (fn? handler-or-routes) handler-or-routes (routing/router handler-or-routes))]
    (cond-> (reduce (fn [h mw] (mw h)) base middleware)
      max-request-body-size (http/wrap-body-limit max-request-body-size)
      g (guard/wrap-access-guard g)
      true http/wrap-errors
      max-request-line-size (wrap-request-line-limit max-request-line-size))))

;; ── 生命周期 ─────────────────────────────────────────

(defn create
  "建一台、挂路由，但不启动（地址 / 证书 / 守卫 / 套接字路径在这里就校验）。见 ns 文档。"
  ([handler-or-routes] (create handler-or-routes nil))
  ([handler-or-routes options]
   (when (nil? handler-or-routes) (throw (NullPointerException. "handler-or-routes")))
   (let [o (merge default-options options)
         unix (:unix-socket-path o)]
     (when (neg? (long (:port o))) (throw (IllegalArgumentException. (str "port: " (:port o)))))
     (when-let [g (:guard o)]
       (when (and (nil? unix) (guard/ip-literal (:host o)) (guard/bind-needs-token? (:host o) (:token g)))
         (throw (err/error (msg :server/bind-needs-token (:host o)) {:im.ttalk.agent.server/kind :invalid-operation}))))
     (let [addrs (when-not unix (bind-addresses o))
           ssl (ssl-context-of o)
           server {::server true
                   :options o
                   :addresses addrs
                   :ssl-context ssl
                   :stopping (CompletableFuture.)
                   :open-streams (ConcurrentHashMap/newKeySet)
                   :in-flight (AtomicLong. 0)
                   :stopped (AtomicBoolean. false)
                   :hooks (atom (vec (concat (:on-stopping o) (:im.ttalk.agent.server/on-stopping (meta handler-or-routes)))))
                   :jetty (atom nil)
                   :port (atom 0)
                   :base-address (atom nil)}]
       (assoc server :handler (wrap-host (build-handler handler-or-routes o) server))))))

(defn- second-connector
  "localhost 的 ::1：照第一个连接器的协议栈再建一个（共享 HttpConfiguration / SslContextFactory）。"
  ^ServerConnector [^Server jetty ^ServerConnector first-conn ^String host port]
  (let [factories (mapv (fn [f]
                          (cond (instance? SslConnectionFactory f)
                                (SslConnectionFactory. (.getSslContextFactory ^SslConnectionFactory f)
                                                       (.getNextProtocol ^SslConnectionFactory f))
                                (instance? HttpConnectionFactory f)
                                (HttpConnectionFactory. (.getHttpConfiguration ^HttpConnectionFactory f))
                                :else f))
                        (.getConnectionFactories first-conn))]
    (doto (ServerConnector. jetty ^"[Lorg.eclipse.jetty.server.ConnectionFactory;" (into-array ConnectionFactory factories))
      (.setHost host)
      (.setPort (int port))
      (.setIdleTimeout (.getIdleTimeout first-conn)))))

(defn- configure
  "适配器没开口的那几样：URI 合规放宽到 LEGACY（%2F 留在路径段里，与 C# / 路由层的口径一致）、localhost 的第二个连接器、停机时限。"
  [server ^Server jetty]
  (doseq [^Connector c (.getConnectors jetty)
          ^ConnectionFactory f (.getConnectionFactories c)
          :when (instance? HttpConnectionFactory f)]
    (.setUriCompliance (.getHttpConfiguration ^HttpConnectionFactory f) UriCompliance/LEGACY))
  (when-let [[_ v6] (seq (:addresses server))]
    (when v6
      (let [^ServerConnector c1 (first (filter #(instance? ServerConnector %) (.getConnectors jetty)))]
        (.addConnector jetty (second-connector jetty c1 (.getHostAddress ^InetAddress v6) (get-in server [:options :port]))))))
  (.setStopTimeout jetty (long (get-in server [:options :shutdown-timeout-ms]))))

(defn- jetty-options [server]
  (let [o (:options server)
        ssl (:ssl-context server)
        unix (:unix-socket-path o)
        ^InetAddress a (first (:addresses server))
        msize (long (:ws-max-message-bytes o))]
    (cond-> {:join? false
             :thread-pool (doto (VirtualThreadPool.) (.setName "clj-agent-server"))
             :send-server-version? false
             :request-header-size (int (max (long (:max-request-headers-total-size o))
                                            (long (or (:max-request-line-size o) 0))))
             :max-idle-time (:idle-timeout-ms o)
             :ws-max-text-size msize
             :ws-max-binary-size msize
             :ws-idle-timeout (:ws-idle-timeout-ms o)
             :configurator #(configure server %)}
      unix (assoc :http? false :unix-socket unix)
      (and (not unix) (not ssl)) (assoc :host (.getHostAddress a) :port (:port o))
      (and (not unix) ssl) (assoc :http? false :ssl? true :ssl-port (:port o) :host (.getHostAddress a)
                                  :ssl-context ssl :sni-host-check? false)
      (and unix ssl) (assoc :ssl? false))))

(defn- url-host
  "基址里的主机：通配地址 ⇒ 127.0.0.1；IPv6 ⇒ 方括号包着宿主写的那个字面量（::1，不是 0:0:0:0:0:0:0:1）。"
  [^InetAddress a ^String host]
  (cond (.isAnyLocalAddress a) "127.0.0.1"
        (instance? Inet6Address a) (str "[" (str/trim host) "]")
        :else (.getHostAddress a)))

(defn start!
  "启动（bind 失败原样抛）。回来时端口已经绑好。返回 server。"
  [server]
  (when-let [p (get-in server [:options :unix-socket-path])] (clear-stale-socket! p))
  (let [^Server jetty (jetty/run-jetty (:handler server) (jetty-options server))
        o (:options server)
        scheme (if (:ssl-context server) "https" "http")]
    (reset! (:jetty server) jetty)
    (if (:unix-socket-path o)
      ;; HttpClient 经 Unix 套接字连，基址的主机名只进 Host 头
      (do (reset! (:port server) 0)
          (reset! (:base-address server) (str scheme "://localhost/")))
      (let [port (.getLocalPort ^ServerConnector (first (filter #(instance? ServerConnector %) (.getConnectors jetty))))
            host (if (.equalsIgnoreCase "localhost" (str (:host o)))
                   "localhost"
                   (url-host (first (:addresses server)) (str (:host o))))]
        (reset! (:port server) port)
        (reset! (:base-address server) (str scheme "://" host ":" port "/"))))
    server))

(defn start
  "建一台、挂路由、启动。"
  ([handler-or-routes] (start handler-or-routes nil))
  ([handler-or-routes options] (start! (create handler-or-routes options))))

(defn port "实际监听的端口（Unix 域套接字上为 0）。" [server] @(:port server))

(defn base-address "基址，如 \"http://127.0.0.1:54321/\"（Unix 域套接字上是 http://localhost/）。" [server] @(:base-address server))

(defn unix-socket-path "在 Unix 域套接字上监听时的路径（否则 nil）。" [server] (get-in server [:options :unix-socket-path]))

(defn url "基址 + 路径（路径开头的 / 可有可无）。" [server path]
  (str (base-address server) (str/replace (or path "") #"^/+" "")))

(defn stopping
  "开始停机时完成的 CompletableFuture（请求 map 的 :im.ttalk.agent.server/stopping 也是它）。"
  ^CompletableFuture [server]
  (:stopping server))

(defn stopping? [server] (.isDone (stopping server)))

(defn on-stopping!
  "登记停机钩子。"
  [server f]
  (swap! (:hooks server) conj f)
  server)

(defn stop!
  "优雅停机：先触发停机（钩子、长连接自己收尾），再等在飞的请求（含正在写的流式正文），最多 :shutdown-timeout-ms，
   然后停 Jetty（余下的连接、WebSocket 会话随之断开）。幂等。"
  [server]
  (when (.compareAndSet ^AtomicBoolean (:stopped server) false true)
    (doseq [f @(:hooks server)] (try (f) (catch Throwable _ nil)))
    (.complete (stopping server) true)
    (doseq [^CompletableFuture f (vec (:open-streams server))] (.complete f true))
    (let [deadline (+ (System/currentTimeMillis) (long (get-in server [:options :shutdown-timeout-ms])))
          ^AtomicLong in-flight (:in-flight server)]
      (loop []
        (when (and (pos? (.get in-flight)) (< (System/currentTimeMillis) deadline))
          (Thread/sleep 10)
          (recur))))
    (when-let [^Server jetty @(:jetty server)]
      (try (.stop jetty) (catch Exception _ nil)))
    ;; 套接字文件不留给下一个人当「残留」去猜
    (when-let [p (unix-socket-path server)]
      (try (Files/deleteIfExists (.toPath (File. ^String p))) (catch IOException _ nil))))
  server)

(defn close!
  "同 `stop!`（C# DisposeAsync）。"
  [server]
  (stop! server))
