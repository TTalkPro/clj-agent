(ns im.ttalk.agent.server.support
  "测试设施：JDK HttpClient（HTTP/1.1）、原始套接字请求（要改 Host 头时 —— JDK 客户端不让设 Host）、SSE 解析、等条件。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.server.jetty :as server]
            [im.ttalk.http.sse :as frame])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream InputStream)
           (java.net Socket URI)
           (java.net.http HttpClient HttpClient$Version HttpHeaders HttpRequest HttpRequest$BodyPublishers
                          HttpRequest$Builder HttpResponse HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)))

(set! *warn-on-reflection* true)

(defn new-client
  (^HttpClient [] (new-client nil))
  (^HttpClient [{:keys [ssl-context]}]
   (let [b (-> (HttpClient/newBuilder)
               (.version HttpClient$Version/HTTP_1_1)
               (.connectTimeout (Duration/ofSeconds 10)))]
     (when ssl-context (.sslContext b ssl-context))
     (.build b))))

(defonce ^HttpClient default-client (new-client))

(defn- build-request ^HttpRequest [method url {:keys [headers body timeout-ms]}]
  (let [^HttpRequest$Builder b (-> (HttpRequest/newBuilder (URI/create url))
                                   (.timeout (Duration/ofMillis (or timeout-ms 20000))))]
    (doseq [[k v] headers] (.header b (name k) (str v)))
    (.method b (str/upper-case (name method))
             (if (nil? body)
               (HttpRequest$BodyPublishers/noBody)
               (HttpRequest$BodyPublishers/ofString (str body) StandardCharsets/UTF_8)))
    (.build b)))

(defn headers-map [^HttpHeaders h]
  (into {} (map (fn [[k v]] [(str/lower-case k) (vec v)])) (.map h)))

(defn send!
  "发一个请求，读完正文 ⇒ {:status :headers {小写名 [值]} :body 串}。"
  [method url & {:keys [client] :as opts}]
  (let [^HttpClient c (or client default-client)
        ^HttpResponse r (.send c (build-request method url opts) (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))]
    {:status (.statusCode r) :headers (headers-map (.headers r)) :body (.body r)}))

(defn send-stream!
  "发一个请求，正文以流返回 ⇒ {:status :headers :stream InputStream}（关流 = 掐连接）。"
  [method url & {:keys [client] :as opts}]
  (let [^HttpClient c (or client default-client)
        ^HttpResponse r (.send c (build-request method url opts) (HttpResponse$BodyHandlers/ofInputStream))]
    {:status (.statusCode r) :headers (headers-map (.headers r)) :stream (.body r)}))

(defn header [resp name] (first (get (:headers resp) (str/lower-case name))))

;; ── 阻塞式 SSE 读者（仅测试用；src 里没有调用方，故不进 clj-http）──────────

(defprotocol EventReader
  (next-event [r] "下一个事件；流结束 ⇒ nil。"))

(defn read-stream
  "一条已经打开的流 ⇒ 读者（`next-event` 阻塞取下一个事件；`.close` 关流）。分帧走 im.ttalk.http.sse。"
  [^InputStream stream]
  (let [p (frame/parser) buf (byte-array 4096) eof (volatile! false)]
    (reify
      EventReader
      (next-event [_]
        (loop []
          (or (frame/take-event! p)
              (when-not @eof
                (let [n (.read stream buf)]
                  (if (neg? n)
                    (do (vreset! eof true) nil)
                    (do (frame/feed! p buf 0 n) (recur))))))))
      java.io.Closeable
      (close [_] (try (.close stream) (catch java.io.IOException _))))))

(defn event-seq [r] (lazy-seq (when-let [e (next-event r)] (cons e (event-seq r)))))

(defn events
  "SSE 原文 ⇒ [{:event-type :data} …]（经 im.ttalk.http.sse 的解析器，≙ EventSource）。"
  [^String raw]
  (with-open [^java.io.Closeable r (read-stream (ByteArrayInputStream. (.getBytes raw StandardCharsets/UTF_8)))]
    (mapv #(select-keys % [:event-type :data]) (doall (event-seq r)))))

(defn data-of [raw] (mapv :data (events raw)))

(defn count-of [^String hay ^String needle]
  (loop [i 0 n 0]
    (let [j (.indexOf hay needle (int i))]
      (if (neg? j) n (recur (+ j (count needle)) (inc n))))))

(defn wait-until
  "轮询到条件成立；超时（缺省 5 秒）⇒ false。"
  ([pred] (wait-until pred 5000))
  ([pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (cond (pred) true
             (> (System/currentTimeMillis) deadline) false
             :else (do (Thread/sleep 10) (recur)))))))

(defn raw-request
  "走原始套接字发一个 HTTP/1.1 请求（Host 头随便设）⇒ {:status :head 串 :body 串}。`headers` 里没有 Host 就补 127.0.0.1:port。"
  [port method path headers]
  (with-open [s (Socket. "127.0.0.1" (int port))]
    (.setSoTimeout s 10000)
    (let [hs (merge {"Host" (str "127.0.0.1:" port)} headers {"Connection" "close"})
          req (str (str/upper-case (name method)) " " path " HTTP/1.1\r\n"
                   (apply str (for [[k v] hs] (str k ": " v "\r\n")))
                   (if (= "POST" (str/upper-case (name method))) "Content-Length: 0\r\n" "")
                   "\r\n")
          out (.getOutputStream s)]
      (.write out (.getBytes req StandardCharsets/ISO_8859_1))
      (.flush out)
      (let [^InputStream in (.getInputStream s)
            buf (ByteArrayOutputStream.)]
        (.transferTo in buf)
        (let [text (String. (.toByteArray buf) StandardCharsets/UTF_8)
              [head body] (str/split text #"\r\n\r\n" 2)]
          {:status (parse-long (second (str/split head #" ")))
           :head head
           :body (or body "")})))))

(defn raw-status [port method path headers] (:status (raw-request port method path headers)))

(defn unix-request
  "经 Unix 域套接字发一个 HTTP/1.1 GET（JDK HttpClient 不会连 Unix 套接字）⇒ {:status :body}（分块正文已拼好）。"
  [^String socket-path ^String path]
  (with-open [ch (java.nio.channels.SocketChannel/open (java.net.UnixDomainSocketAddress/of socket-path))]
    (let [req (str "GET " path " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
          out (java.nio.channels.Channels/newOutputStream ch)
          in (java.nio.channels.Channels/newInputStream ch)]
      (.write out (.getBytes req StandardCharsets/ISO_8859_1))
      (.flush out)
      (let [text (String. (.readAllBytes in) StandardCharsets/UTF_8)
            [head body] (str/split text #"\r\n\r\n" 2)
            body (if (re-find #"(?i)transfer-encoding: *chunked" head)
                   (loop [b (or body "") acc ""]
                     (let [[size-line rest*] (str/split b #"\r\n" 2)
                           n (Long/parseLong (str/trim size-line) 16)]
                       (if (zero? n) acc (recur (subs rest* (+ n 2)) (str acc (subs rest* 0 n))))))
                   (or body ""))]
        {:status (parse-long (second (str/split head #" "))) :body body}))))

(defmacro with-server
  "`(with-server [s routes opts] …)`：起一台，结束时停。"
  [[sym routes opts] & body]
  `(let [~sym (server/start ~routes ~opts)]
     (try ~@body (finally (server/stop! ~sym)))))

(defn read-all ^String [^InputStream in]
  (String. (.readAllBytes in) StandardCharsets/UTF_8))

(defn deref-or [f ms] (deref f ms ::timeout))
