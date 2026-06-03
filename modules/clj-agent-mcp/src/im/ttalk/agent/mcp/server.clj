(ns im.ttalk.agent.mcp.server
  "MCP Server 核心实现

   提供 MCP Server 的主要功能：
   - 初始化握手
   - 消息路由
   - 能力注册
   - 生命周期管理

   本模块使用 registry 和 handler 模块实现核心逻辑，
   同时保持对外 API 的向后兼容性。"
  (:require [im.ttalk.agent.mcp.registry :as registry]
            [im.ttalk.agent.mcp.handler :as handler]
            [im.ttalk.agent.mcp.protocol :as protocol]
            [im.ttalk.agent.mcp.transport.stdio :as stdio]
            [im.ttalk.agent.mcp.transport.sse :as sse]))

;; =============================================================================
;; 服务器状态
;; =============================================================================

(defrecord MCPServer [registry transport running? message-loop])

;; =============================================================================
;; 内部函数
;; =============================================================================

(defn- handle-message
  "处理传入消息

   参数:
   - server: MCPServer 实例
   - message: JSON-RPC 消息

   返回: 响应消息或 nil（通知不需要响应）"
  [server message]
  (handler/route-request (:registry server) message))

;; =============================================================================
;; 服务器创建与管理
;; =============================================================================

(defn create-server
  "创建 MCP Server 实例

   参数:
   - config: 服务器配置
     {:name \"服务器名称\"
      :version \"版本号\"
      :transport :stdio 或 {:type :sse :port 3000}}

   返回: MCPServer 实例

   示例:
   (def server (create-server {:name \"my-tools\"
                               :version \"1.0.0\"
                               :transport :stdio}))"
  [{:keys [name version transport]
    :or {name "clj-agent-mcp-server"
         version "1.0.0"
         transport :stdio}}]
  (let [reg (registry/create-registry {:name name :version version})
        transport-instance (cond
                             (= transport :stdio)
                             (stdio/create-stdio-server-transport)

                             (and (map? transport) (= (:type transport) :sse))
                             (sse/create-sse-server-transport (:port transport))

                             :else
                             (throw (ex-info "Unsupported transport"
                                             {:transport transport})))]
    (->MCPServer reg transport-instance (atom false) (atom nil))))

(defn create-server-with-registry
  "使用已有的 registry 创建 MCP Server 实例

   参数:
   - registry: MCPRegistry 实例
   - transport-config: 传输配置
     :stdio 或 {:type :sse :port 3000}

   返回: MCPServer 实例

   使用场景:
   - 在 MCP Server 和 HTTP Server 之间共享 registry
   - 运行时动态切换传输方式

   示例:
   (def shared-registry (registry/create-registry {:name \"shared\" :version \"1.0.0\"}))
   (registry/register-tool shared-registry my-tool)

   ;; 创建 stdio MCP Server
   (def stdio-server (create-server-with-registry shared-registry :stdio))

   ;; 同时创建 HTTP handler 使用相同的 registry
   (def http-handler (handler/ring-handler shared-registry))"
  [registry transport-config]
  (let [transport-instance (cond
                             (= transport-config :stdio)
                             (stdio/create-stdio-server-transport)

                             (and (map? transport-config)
                                  (= (:type transport-config) :sse))
                             (sse/create-sse-server-transport (:port transport-config))

                             :else
                             (throw (ex-info "Unsupported transport"
                                             {:transport transport-config})))]
    (->MCPServer registry transport-instance (atom false) (atom nil))))

(defn get-registry
  "获取服务器的 registry

   参数:
   - server: MCPServer 实例

   返回: MCPRegistry 实例"
  [server]
  (:registry server))

(defn start
  "启动 MCP Server

   参数:
   - server: MCPServer 实例

   返回: server（启用链式调用）"
  [server]
  (when (compare-and-set! (:running? server) false true)
    (let [transport (:transport server)
          loop-future (stdio/start-message-loop
                        transport
                        (partial handle-message server)
                        (fn [e]
                          (println "[MCP Server] Error:" (.getMessage e))))]
      (reset! (:message-loop server) loop-future)))
  server)

(defn stop
  "停止 MCP Server

   参数:
   - server: MCPServer 实例

   返回: true"
  [server]
  (when (compare-and-set! (:running? server) true false)
    ;; 取消消息循环
    (when-let [loop-future @(:message-loop server)]
      (future-cancel loop-future))
    ;; 关闭传输
    (protocol/transport-close (:transport server)))
  true)

(defn running?
  "检查服务器是否运行中

   参数:
   - server: MCPServer 实例

   返回: true/false"
  [server]
  @(:running? server))

;; =============================================================================
;; 工具注册（委托给 registry）
;; =============================================================================

(defn register-tool
  "注册工具

   参数:
   - server: MCPServer 实例
   - tool: 工具定义
     {:name \"工具名\"
      :description \"描述\"
      :inputSchema {:type \"object\" :properties {...}}
      :handler (fn [args] ...)}

   返回: server（支持链式调用）

   示例:
   (register-tool server
     {:name \"calculator\"
      :description \"执行数学计算\"
      :inputSchema {:type \"object\"
                    :properties {:expression {:type \"string\"}}}
      :handler (fn [{:keys [expression]}]
                 (str (eval (read-string expression))))})"
  [server tool]
  (registry/register-tool (:registry server) tool)
  server)

(defn register-tools
  "批量注册工具

   参数:
   - server: MCPServer 实例
   - tools: 工具定义列表

   返回: server"
  [server tools]
  (registry/register-tools (:registry server) tools)
  server)

(defn unregister-tool
  "注销工具

   参数:
   - server: MCPServer 实例
   - tool-name: 工具名称

   返回: server"
  [server tool-name]
  (registry/unregister-tool (:registry server) tool-name)
  server)

;; =============================================================================
;; 资源注册（委托给 registry）
;; =============================================================================

(defn register-resource
  "注册资源

   参数:
   - server: MCPServer 实例
   - resource: 资源定义
     {:uri \"file:///path\"
      :name \"名称\"
      :description \"描述\"
      :mime-type \"text/plain\"
      :reader (fn [uri] {:uri uri :text \"内容\"})}

   返回: server"
  [server resource]
  (registry/register-resource (:registry server) resource)
  server)

(defn register-resources
  "批量注册资源

   参数:
   - server: MCPServer 实例
   - resources: 资源定义列表

   返回: server"
  [server resources]
  (registry/register-resources (:registry server) resources)
  server)

;; =============================================================================
;; 提示词注册（委托给 registry）
;; =============================================================================

(defn register-prompt
  "注册提示词模板

   参数:
   - server: MCPServer 实例
   - prompt: 提示词定义
     {:name \"模板名\"
      :description \"描述\"
      :arguments [{:name \"arg1\" :description \"...\" :required true}]
      :generator (fn [args] {:messages [...]})}

   返回: server"
  [server prompt]
  (registry/register-prompt (:registry server) prompt)
  server)

(defn register-prompts
  "批量注册提示词

   参数:
   - server: MCPServer 实例
   - prompts: 提示词定义列表

   返回: server"
  [server prompts]
  (registry/register-prompts (:registry server) prompts)
  server)

;; =============================================================================
;; clj-agent 工具集成（委托给 registry）
;; =============================================================================

(defn register-clj-agent-tool
  "将 clj-agent 工具注册到 MCP Server

   参数:
   - server: MCPServer 实例
   - tool: clj-agent 工具定义 {:name :description :parameters :handler}

   返回: server"
  [server tool]
  (registry/register-clj-agent-tool (:registry server) tool)
  server)

(defn register-clj-agent-tools
  "将 clj-agent 工具列表注册到 MCP Server

   参数:
   - server: MCPServer 实例
   - tools: clj-agent 工具列表

   返回: server

   示例:
   (require '[im.ttalk.agent.tools.api :as tools])
   (def tool-registry (tools/create-tool-registry))
   (register-clj-agent-tools server (tools/registry-list-tools tool-registry))"
  [server tools]
  (registry/register-clj-agent-tools (:registry server) tools)
  server)

