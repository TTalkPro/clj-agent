(ns im.ttalk.agent.mcp.transport.stdio
  "Stdio 传输层实现

   通过标准输入/输出进行进程间通信。
   这是 MCP 最常用的本地传输方式。

   协议：
   - 每条消息占一行
   - 使用 JSON 编码
   - 以换行符分隔"
  (:require [im.ttalk.agent.mcp.protocol :as protocol]
            [im.ttalk.agent.mcp.json-rpc :as rpc]
            [clojure.java.io :as io])
  (:import [java.io
            BufferedReader BufferedWriter
            InputStreamReader OutputStreamWriter
            IOException]
           [java.lang ProcessBuilder ProcessBuilder$Redirect]))

;; =============================================================================
;; Stdio Transport（服务端模式）
;; =============================================================================

(defrecord StdioServerTransport [^BufferedReader reader
                                 ^BufferedWriter writer
                                 open?]
  protocol/ITransport

  (transport-send [_ message]
    (when @open?
      (try
        (let [json-line (rpc/encode-line message)]
          (.write writer json-line)
          (.flush writer)
          true)
        (catch IOException e
          (println "[Stdio] Send failed:" (.getMessage e))
          false))))

  (transport-receive [_]
    (when @open?
      (try
        (when-let [line (.readLine reader)]
          (rpc/decode line))
        (catch IOException _
          nil))))

  (transport-close [_]
    (when (compare-and-set! open? true false)
      (try
        (.close reader)
        (.close writer)
        true
        (catch IOException _
          false))))

  (transport-open? [_]
    @open?))

(defn create-stdio-server-transport
  "创建 Stdio 服务端传输（使用进程的标准输入/输出）

   返回: StdioServerTransport 实例

   示例:
   (def transport (create-stdio-server-transport))
   (protocol/transport-receive transport)"
  []
  (let [reader (BufferedReader. (InputStreamReader. System/in))
        writer (BufferedWriter. (OutputStreamWriter. System/out))]
    (->StdioServerTransport reader writer (atom true))))

;; =============================================================================
;; Stdio Transport（客户端模式）
;; =============================================================================

(defrecord StdioClientTransport [^Process process
                                 ^BufferedReader reader
                                 ^BufferedWriter writer
                                 open?]
  protocol/ITransport

  (transport-send [_ message]
    (when @open?
      (try
        (let [json-line (rpc/encode-line message)]
          (.write writer json-line)
          (.flush writer)
          true)
        (catch IOException e
          (println "[Stdio Client] Send failed:" (.getMessage e))
          false))))

  (transport-receive [_]
    (when @open?
      (try
        (when-let [line (.readLine reader)]
          (rpc/decode line))
        (catch IOException _
          nil))))

  (transport-close [_]
    (when (compare-and-set! open? true false)
      (try
        (.close reader)
        (.close writer)
        (.destroy process)
        true
        (catch IOException _
          false))))

  (transport-open? [_]
    @open?))

(defn create-stdio-client-transport
  "创建 Stdio 客户端传输（启动子进程）

   参数:
   - command: 命令和参数向量，如 [\"npx\" \"-y\" \"@anthropic-ai/mcp-server-memory\"]
   - env: 环境变量 map（可选）
   - working-dir: 工作目录（可选）

   返回: StdioClientTransport 实例

   示例:
   (def transport
     (create-stdio-client-transport
       [\"npx\" \"-y\" \"@anthropic-ai/mcp-server-filesystem\" \"/tmp\"]
       {\"PATH\" (System/getenv \"PATH\")}))

   (protocol/transport-send transport (rpc/make-request \"initialize\" {...}))"
  ([command]
   (create-stdio-client-transport command nil nil))
  ([command env]
   (create-stdio-client-transport command env nil))
  ([command env working-dir]
   (let [;; 构建进程
         builder (ProcessBuilder. ^java.util.List (vec command))

         ;; 设置环境变量
         _ (when env
             (let [process-env (.environment builder)]
               (doseq [[k v] env]
                 (.put process-env k v))))

         ;; 设置工作目录
         _ (when working-dir
             (.directory builder (io/file working-dir)))

         ;; 重定向 stderr 到父进程（用于调试）
         _ (.redirectError builder ProcessBuilder$Redirect/INHERIT)

         ;; 启动进程
         process (.start builder)

         ;; 获取输入输出流
         reader (BufferedReader. (InputStreamReader. (.getInputStream process)))
         writer (BufferedWriter. (OutputStreamWriter. (.getOutputStream process)))]

     (->StdioClientTransport process reader writer (atom true)))))

;; =============================================================================
;; 辅助函数
;; =============================================================================

(defn process-alive?
  "检查进程是否存活

   参数:
   - transport: StdioClientTransport 实例

   返回: true/false"
  [transport]
  (when-let [^Process process (:process transport)]
    (.isAlive process)))

(defn wait-for-process
  "等待进程结束

   参数:
   - transport: StdioClientTransport 实例
   - timeout-ms: 超时时间（毫秒）

   返回: 进程退出码或 nil（超时）"
  [transport timeout-ms]
  (when-let [^Process process (:process transport)]
    (let [completed (.waitFor process timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)]
      (when completed
        (.exitValue process)))))

(defn read-stderr
  "读取进程的 stderr 输出（用于调试）

   注意：如果使用了 INHERIT 重定向，stderr 会直接输出到控制台，
   此函数将无法读取。

   参数:
   - transport: StdioClientTransport 实例

   返回: stderr 内容字符串或 nil"
  [transport]
  (when-let [^Process process (:process transport)]
    (try
      (slurp (.getErrorStream process))
      (catch Exception _
        nil))))

;; =============================================================================
;; 消息循环
;; =============================================================================

(defn start-message-loop
  "启动消息处理循环

   参数:
   - transport: ITransport 实例
   - handler-fn: 消息处理函数 (fn [message] -> response-or-nil)
   - on-error: 错误处理函数 (fn [error]) （可选）

   返回: 消息循环 future

   示例:
   (start-message-loop transport
     (fn [msg]
       (case (:method msg)
         \"tools/list\" (make-response (:id msg) {:tools [...]})
         nil)))"
  ([transport handler-fn]
   (start-message-loop transport handler-fn nil))
  ([transport handler-fn on-error]
   (future
     (try
       (loop []
         (when (protocol/transport-open? transport)
           (when-let [message (protocol/transport-receive transport)]
             (try
               (when-let [response (handler-fn message)]
                 (protocol/transport-send transport response))
               (catch Exception e
                 (when on-error
                   (on-error e))
                 ;; 发送错误响应
                 (when (rpc/request? message)
                   (protocol/transport-send transport
                     (rpc/make-error (:id message)
                                     :internal-error
                                     (.getMessage e))))))
             (recur))))
       (catch Exception e
         (when on-error
           (on-error e)))))))

(defn send-and-receive
  "发送请求并等待响应（同步）

   参数:
   - transport: ITransport 实例
   - request: JSON-RPC 请求 map
   - timeout-ms: 超时时间（毫秒，默认 30000）

   返回: 响应 map 或抛出异常"
  ([transport request]
   (send-and-receive transport request 30000))
  ([transport request timeout-ms]
   (when (protocol/transport-send transport request)
     (let [start-time (System/currentTimeMillis)]
       (loop []
         (if (> (- (System/currentTimeMillis) start-time) timeout-ms)
           (throw (ex-info "Request timeout" {:request request}))
           (if-let [response (protocol/transport-receive transport)]
             (if (= (:id response) (:id request))
               response
               (recur))
             (recur))))))))
