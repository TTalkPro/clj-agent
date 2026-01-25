(ns im.ttalk.agent.mcp.handler
  "MCP Handler - 纯函数处理层

   提供 MCP 请求的处理逻辑，所有函数都是纯函数（接收 registry 作为参数）。
   此模块可以独立于 MCP Server 使用，适用于各种 HTTP 框架集成。

   主要功能：
   - MCP 请求处理函数（handle-*）
   - 统一路由函数（route-request）
   - Ring handler 适配器
   - SSE handler 适配器"
  (:require [im.ttalk.agent.mcp.registry :as registry]
            [im.ttalk.agent.mcp.protocol :as protocol]
            [im.ttalk.agent.mcp.json-rpc :as rpc]
            [cheshire.core :as json]))

;; =============================================================================
;; 核心处理函数（纯函数）
;; =============================================================================

(defn handle-initialize
  "处理 initialize 请求

   参数:
   - registry: MCPRegistry 实例
   - params: 请求参数 {:clientInfo {...} :protocolVersion \"...\"}

   返回: 初始化响应结果"
  [registry params]
  (let [info (registry/registry-info registry)
        caps (registry/registry-capabilities registry)]
    {:protocolVersion protocol/protocol-version
     :capabilities (cond-> {}
                     (:tools caps) (assoc :tools {})
                     (:resources caps) (assoc :resources {})
                     (:prompts caps) (assoc :prompts {}))
     :serverInfo info}))

(defn handle-tools-list
  "处理 tools/list 请求

   参数:
   - registry: MCPRegistry 实例

   返回: 工具列表"
  [registry]
  {:tools (registry/list-tools registry)})

(defn handle-tools-call
  "处理 tools/call 请求

   参数:
   - registry: MCPRegistry 实例
   - params: 请求参数 {:name \"tool-name\" :arguments {...}}

   返回: 工具执行结果"
  [registry params]
  (let [tool-name (:name params)
        arguments (or (:arguments params) {})
        tool (registry/get-tool registry tool-name)]
    (if tool
      (try
        (let [handler (:handler tool)
              result (handler arguments)]
          (rpc/wrap-result result))
        (catch Exception e
          (rpc/wrap-error-result (.getMessage e))))
      (throw (ex-info "Tool not found" {:tool tool-name})))))

(defn handle-resources-list
  "处理 resources/list 请求

   参数:
   - registry: MCPRegistry 实例

   返回: 资源列表"
  [registry]
  {:resources (registry/list-resources registry)})

(defn handle-resources-read
  "处理 resources/read 请求

   参数:
   - registry: MCPRegistry 实例
   - params: 请求参数 {:uri \"...\"}

   返回: 资源内容"
  [registry params]
  (let [uri (:uri params)
        resource (registry/get-resource registry uri)]
    (if resource
      (let [reader (:reader resource)]
        {:contents [(reader uri)]})
      (throw (ex-info "Resource not found" {:uri uri})))))

(defn handle-prompts-list
  "处理 prompts/list 请求

   参数:
   - registry: MCPRegistry 实例

   返回: 提示词列表"
  [registry]
  {:prompts (registry/list-prompts registry)})

(defn handle-prompts-get
  "处理 prompts/get 请求

   参数:
   - registry: MCPRegistry 实例
   - params: 请求参数 {:name \"...\" :arguments {...}}

   返回: 提示词内容"
  [registry params]
  (let [prompt-name (:name params)
        arguments (or (:arguments params) {})
        prompt (registry/get-prompt registry prompt-name)]
    (if prompt
      (let [generator (:generator prompt)]
        (generator arguments))
      (throw (ex-info "Prompt not found" {:name prompt-name})))))

;; =============================================================================
;; 统一路由
;; =============================================================================

(defn route-request
  "路由 MCP 请求到对应的处理函数

   参数:
   - registry: MCPRegistry 实例
   - message: JSON-RPC 消息 {:method \"...\" :params {...} :id ...}

   返回: JSON-RPC 响应消息或 nil（通知不需要响应）

   示例:
   (route-request registry {:jsonrpc \"2.0\"
                            :id 1
                            :method \"tools/list\"
                            :params {}})"
  [registry message]
  (let [method (:method message)
        params (:params message)
        id (:id message)]
    (try
      (let [result (case method
                     "initialize" (handle-initialize registry params)
                     "initialized" nil  ;; 通知，无需响应
                     "ping" {}
                     "tools/list" (handle-tools-list registry)
                     "tools/call" (handle-tools-call registry params)
                     "resources/list" (handle-resources-list registry)
                     "resources/read" (handle-resources-read registry params)
                     "prompts/list" (handle-prompts-list registry)
                     "prompts/get" (handle-prompts-get registry params)
                     ;; 未知方法
                     (throw (ex-info "Method not found"
                                     {:method method
                                      :code :method-not-found})))]
        (when (and id result)
          (rpc/make-response id result)))
      (catch Exception e
        (let [data (ex-data e)
              code (or (:code data) :internal-error)]
          (when id
            (rpc/make-error id code (.getMessage e) data)))))))

(defn route-request-raw
  "路由 MCP 请求，返回原始结果（不包装为 JSON-RPC 响应）

   参数:
   - registry: MCPRegistry 实例
   - method: 方法名
   - params: 请求参数

   返回: 处理结果或抛出异常

   用于在不需要 JSON-RPC 包装的场景使用，例如直接 HTTP API。"
  [registry method params]
  (case method
    "initialize" (handle-initialize registry params)
    "initialized" nil
    "ping" {}
    "tools/list" (handle-tools-list registry)
    "tools/call" (handle-tools-call registry params)
    "resources/list" (handle-resources-list registry)
    "resources/read" (handle-resources-read registry params)
    "prompts/list" (handle-prompts-list registry)
    "prompts/get" (handle-prompts-get registry params)
    (throw (ex-info "Method not found" {:method method}))))

;; =============================================================================
;; Ring 适配器
;; =============================================================================

(defn ring-handler
  "创建 Ring handler

   参数:
   - registry: MCPRegistry 实例

   返回: Ring handler 函数 (fn [request] response)

   使用示例:
   (def handler (ring-handler registry))

   ;; 在 Ring 路由中使用
   (defroutes app
     (POST \"/mcp\" req (handler req)))

   ;; 或直接作为应用
   (run-server (ring-handler registry) {:port 8080})"
  [registry]
  (fn [request]
    (try
      (let [body (if (string? (:body request))
                   (:body request)
                   (slurp (:body request)))
            message (json/parse-string body true)
            response (route-request registry message)]
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (if response
                 (json/generate-string response)
                 "{}")})
      (catch Exception e
        {:status 400
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string
                 {:jsonrpc "2.0"
                  :id nil
                  :error {:code -32700
                          :message (.getMessage e)}})}))))

(defn wrap-mcp-cors
  "CORS 中间件

   参数:
   - handler: Ring handler
   - options: CORS 配置（可选）
     {:allowed-origins [\"*\"]
      :allowed-methods [:get :post :options]}

   返回: 包装后的 Ring handler"
  ([handler]
   (wrap-mcp-cors handler {}))
  ([handler {:keys [allowed-origins allowed-methods]
             :or {allowed-origins ["*"]
                  allowed-methods [:get :post :options]}}]
   (fn [request]
     (let [origin (get-in request [:headers "origin"] "*")
           cors-headers {"Access-Control-Allow-Origin" (if (= allowed-origins ["*"])
                                                         "*"
                                                         origin)
                         "Access-Control-Allow-Methods" (->> allowed-methods
                                                             (map name)
                                                             (clojure.string/join ", ")
                                                             clojure.string/upper-case)
                         "Access-Control-Allow-Headers" "Content-Type, Authorization"
                         "Access-Control-Max-Age" "86400"}]
       (if (= (:request-method request) :options)
         {:status 204
          :headers cors-headers
          :body ""}
         (let [response (handler request)]
           (update response :headers merge cors-headers)))))))

;; =============================================================================
;; SSE 适配器
;; =============================================================================

(defn sse-handler
  "创建 SSE Ring handler

   参数:
   - registry: MCPRegistry 实例
   - clients-atom: 客户端连接集合 atom (atom #{})
   - options: 配置选项（可选）
     {:sse-endpoint \"/sse\"
      :message-endpoint \"/message\"}

   返回: Ring handler 函数

   使用示例:
   (def clients (atom #{}))
   (def handler (sse-handler registry clients))

   ;; 广播消息给所有客户端
   (broadcast-to-clients clients {:type \"notification\" :data \"...\"})"
  ([registry clients-atom]
   (sse-handler registry clients-atom {}))
  ([registry clients-atom {:keys [sse-endpoint message-endpoint]
                           :or {sse-endpoint "/sse"
                                message-endpoint "/message"}}]
   (fn [request]
     (let [uri (:uri request)
           method (:request-method request)]
       (cond
         ;; SSE 端点 - 需要使用特定的 HTTP server 实现
         ;; 这里返回一个标记，实际 SSE 连接需要 http-kit 等支持
         (and (= method :get) (= uri sse-endpoint))
         {:status 200
          :headers {"Content-Type" "text/event-stream"
                    "Cache-Control" "no-cache"
                    "Connection" "keep-alive"}
          :body ""
          ::sse-request true
          ::clients-atom clients-atom}

         ;; POST 消息端点
         (and (= method :post) (= uri message-endpoint))
         (try
           (let [body (if (string? (:body request))
                        (:body request)
                        (slurp (:body request)))
                 message (json/parse-string body true)
                 response (route-request registry message)]
             ;; 如果有响应，广播给所有 SSE 客户端
             (when response
               (let [sse-data (str "data: " (json/generate-string response) "\n\n")]
                 (doseq [client @clients-atom]
                   (try
                     ;; 这里假设 client 是可以发送数据的 channel
                     (when (fn? (:send! client))
                       ((:send! client) sse-data))
                     (catch Exception _
                       (swap! clients-atom disj client))))))
             {:status 200
              :headers {"Content-Type" "application/json"}
              :body "{\"ok\":true}"})
           (catch Exception e
             {:status 400
              :headers {"Content-Type" "application/json"}
              :body (json/generate-string {:error (.getMessage e)})}))

         ;; 其他请求
         :else
         {:status 404
          :headers {"Content-Type" "text/plain"}
          :body "Not Found"})))))

(defn make-httpkit-sse-handler
  "创建 http-kit 专用的 SSE handler

   参数:
   - registry: MCPRegistry 实例
   - clients-atom: 客户端连接集合 atom
   - options: 配置选项

   返回: http-kit 兼容的 handler

   注意: 此函数返回一个需要与 org.httpkit.server/with-channel 配合使用的处理器"
  [registry clients-atom {:keys [sse-endpoint message-endpoint]
                          :or {sse-endpoint "/sse"
                               message-endpoint "/message"}}]
  {:sse-endpoint sse-endpoint
   :message-endpoint message-endpoint
   :registry registry
   :clients-atom clients-atom

   :handle-sse
   (fn [channel]
     (swap! clients-atom conj channel)
     {:on-close (fn [_] (swap! clients-atom disj channel))})

   :handle-message
   (fn [body]
     (let [message (json/parse-string body true)
           response (route-request registry message)]
       (when response
         (let [sse-data (str "data: " (json/generate-string response) "\n\n")]
           (doseq [client @clients-atom]
             (try
               ;; http-kit 的 send! 函数
               (when-let [send-fn (:send! (meta client))]
                 (send-fn sse-data false))
               (catch Exception _
                 (swap! clients-atom disj client))))))
       response))

   :broadcast
   (fn [message]
     (let [sse-data (str "data: " (json/generate-string message) "\n\n")]
       (doseq [client @clients-atom]
         (try
           (when-let [send-fn (:send! (meta client))]
             (send-fn sse-data false))
           (catch Exception _
             (swap! clients-atom disj client))))))})

;; =============================================================================
;; 便捷函数
;; =============================================================================

(defn create-combined-handler
  "创建组合 handler，同时支持普通 HTTP 和 SSE

   参数:
   - registry: MCPRegistry 实例
   - options: 配置选项
     {:mcp-endpoint \"/mcp\"
      :sse-endpoint \"/sse\"
      :message-endpoint \"/message\"}

   返回: Ring handler 函数

   使用示例:
   (def handler (create-combined-handler registry))
   (run-server handler {:port 8080})"
  ([registry]
   (create-combined-handler registry {}))
  ([registry {:keys [mcp-endpoint sse-endpoint message-endpoint]
              :or {mcp-endpoint "/mcp"
                   sse-endpoint "/sse"
                   message-endpoint "/message"}}]
   (let [clients-atom (atom #{})
         json-handler (ring-handler registry)
         sse-routes (sse-handler registry clients-atom
                      {:sse-endpoint sse-endpoint
                       :message-endpoint message-endpoint})]
     (fn [request]
       (let [uri (:uri request)]
         (cond
           (= uri mcp-endpoint)
           (json-handler request)

           (or (= uri sse-endpoint) (= uri message-endpoint))
           (sse-routes request)

           :else
           {:status 404
            :headers {"Content-Type" "text/plain"}
            :body "Not Found"}))))))

(defn handle-batch
  "处理批量请求

   参数:
   - registry: MCPRegistry 实例
   - messages: JSON-RPC 消息列表

   返回: 响应消息列表"
  [registry messages]
  (->> messages
       (map #(route-request registry %))
       (filter some?)
       vec))
