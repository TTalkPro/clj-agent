(ns im.ttalk.agent.a2a.server
  "A2A Server 核心实现

   提供 A2A Server 的主要功能：
   - 服务器创建与生命周期管理
   - 消息处理器注册
   - 工具注册
   - HTTP 服务器启动/停止

   本模块使用 handler 模块实现核心逻辑，
   同时保持简洁的 API。"
  (:require [im.ttalk.agent.a2a.handler :as handler]
            [im.ttalk.agent.a2a.types :as types]
            [im.ttalk.agent.a2a.task :as task]
            [im.ttalk.agent.a2a.card :as card]
            [im.ttalk.agent.a2a.json-rpc :as rpc]
            [org.httpkit.server :as http-kit]
            [cheshire.core :as json]))

;; =============================================================================
;; A2A Server 记录
;; =============================================================================

(defrecord A2AServer [registry http-server running?])

;; =============================================================================
;; 服务器创建
;; =============================================================================

(defn create-server
  "创建 A2A Server 实例

   参数:
   - config: 服务器配置
     {:name \"服务器名称\"
      :description \"描述\"
      :url \"https://...\"  ;; Agent 的公开 URL
      :version \"版本号\"
      :capabilities {:streaming false ...}}

   返回: A2AServer 实例

   示例:
   (def server (create-server {:name \"my-agent\"
                               :description \"My Agent\"
                               :url \"http://localhost:8080\"
                               :version \"1.0.0\"}))"
  [{:keys [name description url version capabilities]
    :or {version "1.0.0"
         capabilities {}}}]
  (let [registry (handler/create-registry
                   {:name name
                    :description description
                    :url url
                    :version version
                    :capabilities capabilities})]
    (->A2AServer registry (atom nil) (atom false))))

(defn create-server-with-registry
  "使用已有的 registry 创建 A2A Server 实例

   参数:
   - registry: A2ARegistry 实例

   返回: A2AServer 实例

   使用场景:
   - 在 A2A Server 和其他 HTTP Server 之间共享 registry

   示例:
   (def shared-registry (handler/create-registry {...}))
   (handler/set-message-handler shared-registry my-handler)

   ;; 创建 A2A Server
   (def a2a-server (create-server-with-registry shared-registry))

   ;; 同时在其他地方使用 handler
   (def my-ring-handler (handler/ring-handler shared-registry))"
  [registry]
  (->A2AServer registry (atom nil) (atom false)))

(defn get-registry
  "获取服务器的 registry

   参数:
   - server: A2AServer 实例

   返回: A2ARegistry 实例"
  [server]
  (:registry server))

;; =============================================================================
;; 消息处理器
;; =============================================================================

(defn set-message-handler
  "设置消息处理器

   参数:
   - server: A2AServer 实例
   - handler-fn: 消息处理函数
     (fn [registry task message] -> result)

     result 可以是:
     - 字符串: 直接作为文本结果
     - Artifact 列表: 多个产出物
     - {:type :input-required :prompt \"提示\"}: 请求用户输入
     - {:type :failed :error \"错误消息\"}: 标记失败

   返回: server（支持链式调用）

   示例:
   (set-message-handler server
     (fn [reg task msg]
       (str \"Echo: \" (types/message-text msg))))"
  [server handler-fn]
  (handler/set-message-handler (:registry server) handler-fn)
  server)

;; =============================================================================
;; 工具注册
;; =============================================================================

(defn register-tool
  "注册工具（用于生成 Agent Card 的 skills）

   参数:
   - server: A2AServer 实例
   - tool: 工具定义
     {:name \"工具名\"
      :description \"描述\"
      :parameters {...}}

   返回: server（支持链式调用）"
  [server tool]
  (handler/register-tool (:registry server) tool)
  server)

(defn register-tools
  "批量注册工具

   参数:
   - server: A2AServer 实例
   - tools: 工具定义列表

   返回: server"
  [server tools]
  (handler/register-tools (:registry server) tools)
  server)

;; =============================================================================
;; SSE 流式支持
;; =============================================================================

(defn- sse-format
  "格式化 SSE 消息"
  [event data]
  (str "event: " event "\n"
       "data: " (json/generate-string data) "\n\n"))

(defn- stream-task-updates
  "流式发送任务更新"
  [channel registry task-id poll-interval]
  (future
    (loop [last-state nil]
      (when (http-kit/open? channel)
        (when-let [t (task/get-task (:task-store registry) task-id)]
          (let [current-state (types/task-state t)]
            (when (not= last-state current-state)
              (http-kit/send! channel
                (sse-format "task_status" (task/task->json t))
                false))
            (if (types/terminal-state? t)
              (http-kit/send! channel
                (sse-format "done" {:taskId task-id})
                true)
              (do
                (Thread/sleep poll-interval)
                (recur current-state)))))))))

(defn- create-sse-handler
  "创建带 SSE 支持的 handler"
  [registry poll-interval]
  (fn [request]
    (let [uri (:uri request)
          method (:request-method request)]
      (cond
        ;; Agent Card 发现端点
        (and (= method :get) (= uri card/well-known-path))
        {:status 200
         :headers {"Content-Type" "application/json"
                   "Cache-Control" "public, max-age=3600"}
         :body (json/generate-string (handler/get-agent-card registry))}

        ;; 普通 A2A 端点
        (and (= method :post) (= uri "/a2a"))
        ((handler/ring-handler registry) request)

        ;; SSE 流式端点
        (and (= method :post) (= uri "/a2a/stream"))
        (http-kit/with-channel request channel
          (try
            (let [body-str (if (string? (:body request))
                             (:body request)
                             (slurp (:body request)))
                  body (json/parse-string body-str true)
                  response (handler/route-request registry body)]
              (http-kit/send! channel
                {:status 200
                 :headers {"Content-Type" "text/event-stream"
                           "Cache-Control" "no-cache"
                           "Connection" "keep-alive"}}
                false)
              (when response
                (http-kit/send! channel
                  (sse-format "task_status" (:result response))
                  false)
                (when-let [task-id (get-in response [:result :id])]
                  (stream-task-updates channel registry task-id poll-interval))))
            (catch Exception e
              (http-kit/send! channel
                (sse-format "error" {:message (.getMessage e)})
                true))))

        ;; 404
        :else
        {:status 404
         :headers {"Content-Type" "text/plain"}
         :body "Not Found"}))))

;; =============================================================================
;; 服务器生命周期
;; =============================================================================

(defn start
  "启动 A2A Server

   参数:
   - server: A2AServer 实例
   - options: 启动选项
     {:port 8080
      :streaming true
      :cors true
      :poll-interval 100}

   返回: server（支持链式调用）

   示例:
   (-> (create-server {...})
       (set-message-handler my-handler)
       (start {:port 8080}))"
  [server {:keys [port streaming cors poll-interval]
           :or {port 8080
                streaming true
                cors true
                poll-interval 100}}]
  (when (compare-and-set! (:running? server) false true)
    (let [registry (:registry server)
          base-handler (if streaming
                         (create-sse-handler registry poll-interval)
                         (handler/combined-ring-handler registry))
          final-handler (if cors
                          (handler/wrap-cors base-handler)
                          base-handler)
          http-server (http-kit/run-server final-handler {:port port})]
      (reset! (:http-server server) http-server)
      (println (str "[A2A Server] Started on port " port))))
  server)

(defn stop
  "停止 A2A Server

   参数:
   - server: A2AServer 实例

   返回: true"
  [server]
  (when (compare-and-set! (:running? server) true false)
    (when-let [http-server @(:http-server server)]
      (http-server :timeout 1000))
    (reset! (:http-server server) nil)
    (println "[A2A Server] Stopped"))
  true)

(defn running?
  "检查服务器是否运行中

   参数:
   - server: A2AServer 实例

   返回: true/false"
  [server]
  @(:running? server))

;; =============================================================================
;; 便捷函数
;; =============================================================================

(defn get-agent-card
  "获取 Agent Card

   参数:
   - server: A2AServer 实例

   返回: AgentCard map"
  [server]
  (handler/get-agent-card (:registry server)))

(defn get-task
  "获取任务

   参数:
   - server: A2AServer 实例
   - task-id: 任务 ID

   返回: Task map 或 nil"
  [server task-id]
  (handler/get-task (:registry server) task-id))

(defn list-tasks
  "列出所有任务

   参数:
   - server: A2AServer 实例

   返回: Task 列表"
  [server]
  (handler/list-tasks (:registry server)))

;; =============================================================================
;; 快速启动
;; =============================================================================

(defn create-and-start
  "创建并启动 A2A Server（便捷函数）

   参数:
   - opts: 配置选项
     {:name \"agent-name\"
      :description \"描述\"
      :url \"http://localhost:8080\"
      :port 8080
      :message-handler (fn [reg task msg] -> result)
      :tools [...]}

   返回: A2AServer 实例

   示例:
   (def server
     (create-and-start
       {:name \"echo-agent\"
        :description \"Echo Agent\"
        :url \"http://localhost:8080\"
        :port 8080
        :message-handler (fn [_ _ msg]
                           (str \"Echo: \" (types/message-text msg)))}))"
  [{:keys [port message-handler tools]
    :or {port 8080}
    :as opts}]
  (let [server-opts (dissoc opts :port :message-handler :tools :streaming :cors)
        server (create-server server-opts)]
    (when message-handler
      (set-message-handler server message-handler))
    (when tools
      (register-tools server tools))
    (start server {:port port
                   :streaming (:streaming opts true)
                   :cors (:cors opts true)})))
