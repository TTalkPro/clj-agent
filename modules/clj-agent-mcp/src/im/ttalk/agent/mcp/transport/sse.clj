(ns im.ttalk.agent.mcp.transport.sse
  "SSE (Server-Sent Events) 传输层实现

   通过 HTTP SSE 进行远程通信。
   适用于跨网络的 MCP Server 连接。

   协议：
   - 服务端通过 SSE 推送消息
   - 客户端通过 HTTP POST 发送消息
   - 使用 JSON 编码"
  (:require [im.ttalk.agent.mcp.protocol :as protocol]
            [im.ttalk.agent.mcp.json-rpc :as rpc]
            [org.httpkit.client :as http]
            [org.httpkit.server :as server]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

;; =============================================================================
;; SSE 消息解析
;; =============================================================================

(defn- parse-sse-event
  "解析 SSE 事件

   参数:
   - lines: 事件行列表

   返回: {:event \"...\" :data \"...\" :id \"...\"} 或 nil"
  [lines]
  (reduce
    (fn [event line]
      (cond
        (str/starts-with? line "event:")
        (assoc event :event (str/trim (subs line 6)))

        (str/starts-with? line "data:")
        (update event :data str (str/trim (subs line 5)))

        (str/starts-with? line "id:")
        (assoc event :id (str/trim (subs line 3)))

        :else event))
    {:event "message" :data ""}
    lines))

(defn- parse-sse-stream
  "解析 SSE 流，返回事件序列

   参数:
   - body: SSE 响应体字符串

   返回: 事件序列"
  [body]
  (let [lines (str/split-lines body)]
    (->> lines
         (partition-by str/blank?)
         (filter #(not (every? str/blank? %)))
         (map parse-sse-event)
         (filter #(seq (:data %))))))

;; =============================================================================
;; SSE Client Transport
;; =============================================================================

(defrecord SSEClientTransport [sse-url
                               post-url
                               ^LinkedBlockingQueue message-queue
                               open?
                               sse-connection]
  protocol/ITransport

  (transport-send [_ message]
    (when @open?
      (try
        (let [response @(http/post post-url
                          {:headers {"Content-Type" "application/json"}
                           :body (rpc/encode message)})]
          (= 200 (:status response)))
        (catch Exception e
          (println "[SSE Client] Send failed:" (.getMessage e))
          false))))

  (transport-receive [_]
    (when @open?
      (try
        (.poll message-queue 100 TimeUnit/MILLISECONDS)
        (catch InterruptedException _
          nil))))

  (transport-close [_]
    (when (compare-and-set! open? true false)
      ;; 关闭 SSE 连接
      (when-let [conn @sse-connection]
        (when (fn? conn)
          (conn)))
      (.clear message-queue)
      true))

  (transport-open? [_]
    @open?))

(defn create-sse-client-transport
  "创建 SSE 客户端传输

   参数:
   - base-url: 服务器基础 URL，如 \"http://localhost:3000\"
   - sse-endpoint: SSE 端点路径（默认 \"/sse\"）
   - post-endpoint: POST 端点路径（默认 \"/message\"）

   返回: SSEClientTransport 实例

   示例:
   (def transport
     (create-sse-client-transport \"http://localhost:3000\"))"
  ([base-url]
   (create-sse-client-transport base-url "/sse" "/message"))
  ([base-url sse-endpoint post-endpoint]
   (let [sse-url (str base-url sse-endpoint)
         post-url (str base-url post-endpoint)
         message-queue (LinkedBlockingQueue. 1000)
         open? (atom true)
         sse-connection (atom nil)

         ;; 启动 SSE 连接
         on-sse-message (fn [event]
                          (when-let [data (:data event)]
                            (when-let [msg (rpc/decode data)]
                              (.offer message-queue msg))))

         ;; 处理 SSE 流的函数
         process-sse-stream
         (fn [body]
           (try
             (let [reader (java.io.BufferedReader.
                            (java.io.InputStreamReader. body))]
               (loop [lines []]
                 (when @open?
                   (if-let [line (.readLine reader)]
                     (if (str/blank? line)
                       (do
                         (when (seq lines)
                           (on-sse-message (parse-sse-event lines)))
                         (recur []))
                       (recur (conj lines line)))
                     nil))))
             (catch Exception e
               (when @open?
                 (println "[SSE Client] Stream error:" (.getMessage e))))))

         ;; 开始 SSE 流
         start-sse
         (fn []
           (http/get sse-url
             {:as :stream :timeout 0}
             (fn [{:keys [status body error]}]
               (when (and (= status 200) (not error))
                 (future (process-sse-stream body))))))]

     ;; 启动 SSE 连接
     (reset! sse-connection (start-sse))

     (->SSEClientTransport sse-url post-url message-queue open? sse-connection))))

;; =============================================================================
;; SSE Server Transport
;; =============================================================================

(defrecord SSEServerTransport [port
                               ^LinkedBlockingQueue message-queue
                               clients
                               open?
                               server]
  protocol/ITransport

  (transport-send [_ message]
    (when @open?
      (let [json-data (rpc/encode message)
            sse-message (str "data: " json-data "\n\n")]
        ;; 发送给所有连接的客户端
        (doseq [client @clients]
          (try
            (server/send! client sse-message false)
            (catch Exception _
              ;; 客户端可能已断开
              (swap! clients disj client))))
        true)))

  (transport-receive [_]
    (when @open?
      (try
        (.poll message-queue 100 TimeUnit/MILLISECONDS)
        (catch InterruptedException _
          nil))))

  (transport-close [_]
    (when (compare-and-set! open? true false)
      ;; 关闭所有客户端连接
      (doseq [client @clients]
        (server/close client))
      (reset! clients #{})
      ;; 停止服务器
      (when-let [stop-fn @server]
        (stop-fn :timeout 1000))
      (.clear message-queue)
      true))

  (transport-open? [_]
    @open?))

(defn create-sse-server-transport
  "创建 SSE 服务端传输

   参数:
   - port: 监听端口
   - sse-endpoint: SSE 端点路径（默认 \"/sse\"）
   - post-endpoint: POST 端点路径（默认 \"/message\"）

   返回: SSEServerTransport 实例

   示例:
   (def transport (create-sse-server-transport 3000))"
  ([port]
   (create-sse-server-transport port "/sse" "/message"))
  ([port sse-endpoint post-endpoint]
   (let [message-queue (LinkedBlockingQueue. 1000)
         clients (atom #{})
         open? (atom true)
         server-atom (atom nil)

         ;; HTTP 处理器
         handler (fn [req]
                   (cond
                     ;; SSE 端点
                     (and (= (:request-method req) :get)
                          (= (:uri req) sse-endpoint))
                     (server/with-channel req channel
                       (server/send! channel
                         {:status 200
                          :headers {"Content-Type" "text/event-stream"
                                    "Cache-Control" "no-cache"
                                    "Connection" "keep-alive"}}
                         false)
                       (swap! clients conj channel)
                       (server/on-close channel
                         (fn [_]
                           (swap! clients disj channel))))

                     ;; POST 端点
                     (and (= (:request-method req) :post)
                          (= (:uri req) post-endpoint))
                     (let [body (slurp (:body req))
                           msg (rpc/decode body)]
                       (when msg
                         (.offer message-queue msg))
                       {:status 200
                        :headers {"Content-Type" "application/json"}
                        :body "{\"ok\":true}"})

                     ;; 其他请求
                     :else
                     {:status 404
                      :body "Not Found"}))]

     ;; 启动服务器
     (reset! server-atom (server/run-server handler {:port port}))

     (->SSEServerTransport port message-queue clients open? server-atom))))

;; =============================================================================
;; 辅助函数
;; =============================================================================

(defn get-connected-clients-count
  "获取已连接的客户端数量

   参数:
   - transport: SSEServerTransport 实例

   返回: 客户端数量"
  [transport]
  (count @(:clients transport)))

(defn broadcast
  "广播消息给所有客户端

   参数:
   - transport: SSEServerTransport 实例
   - message: 消息 map

   返回: 发送成功的客户端数量"
  [transport message]
  (let [json-data (rpc/encode message)
        sse-message (str "data: " json-data "\n\n")
        clients @(:clients transport)
        sent (atom 0)]
    (doseq [client clients]
      (try
        (server/send! client sse-message false)
        (swap! sent inc)
        (catch Exception _
          (swap! (:clients transport) disj client))))
    @sent))
