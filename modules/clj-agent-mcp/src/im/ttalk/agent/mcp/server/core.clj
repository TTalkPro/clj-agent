(ns im.ttalk.agent.mcp.server.core
  "MCP Server 核心实现

   提供 MCP Server 的主要功能：
   - 初始化握手
   - 消息路由
   - 能力注册
   - 生命周期管理"
  (:require [im.ttalk.agent.mcp.protocol :as protocol]
            [im.ttalk.agent.mcp.json-rpc :as rpc]
            [im.ttalk.agent.mcp.transport.stdio :as stdio]
            [im.ttalk.agent.mcp.transport.sse :as sse]))

;; =============================================================================
;; 服务器状态
;; =============================================================================

(defrecord MCPServer [name
                      version
                      transport
                      tools-atom       ;; atom: {tool-name -> tool-def}
                      resources-atom   ;; atom: {uri -> resource-def}
                      prompts-atom     ;; atom: {prompt-name -> prompt-def}
                      running?
                      message-loop])

;; =============================================================================
;; 消息处理
;; =============================================================================

(defn- handle-initialize
  "处理 initialize 请求

   参数:
   - server: MCPServer 实例
   - params: 请求参数

   返回: 响应结果"
  [server params]
  (let [client-info (:clientInfo params)
        capabilities {:tools (seq @(:tools-atom server))
                      :resources (seq @(:resources-atom server))
                      :prompts (seq @(:prompts-atom server))}]
    {:protocolVersion protocol/protocol-version
     :capabilities (cond-> {}
                     (:tools capabilities) (assoc :tools {})
                     (:resources capabilities) (assoc :resources {})
                     (:prompts capabilities) (assoc :prompts {}))
     :serverInfo {:name (:name server)
                  :version (:version server)}}))

(defn- handle-tools-list
  "处理 tools/list 请求

   参数:
   - server: MCPServer 实例

   返回: 工具列表"
  [server]
  {:tools (vec (vals @(:tools-atom server)))})

(defn- handle-tools-call
  "处理 tools/call 请求

   参数:
   - server: MCPServer 实例
   - params: 请求参数 {:name \"tool-name\" :arguments {...}}

   返回: 工具执行结果"
  [server params]
  (let [tool-name (:name params)
        arguments (or (:arguments params) {})
        tools @(:tools-atom server)
        tool (get tools tool-name)]
    (if tool
      (try
        (let [handler (:handler tool)
              result (handler arguments)]
          (rpc/wrap-result result))
        (catch Exception e
          (rpc/wrap-error-result (.getMessage e))))
      (throw (ex-info "Tool not found" {:tool tool-name})))))

(defn- handle-resources-list
  "处理 resources/list 请求

   参数:
   - server: MCPServer 实例

   返回: 资源列表"
  [server]
  {:resources (vec (vals @(:resources-atom server)))})

(defn- handle-resources-read
  "处理 resources/read 请求

   参数:
   - server: MCPServer 实例
   - params: 请求参数 {:uri \"...\"}

   返回: 资源内容"
  [server params]
  (let [uri (:uri params)
        resources @(:resources-atom server)
        resource (get resources uri)]
    (if resource
      (let [reader (:reader resource)]
        {:contents [(reader uri)]})
      (throw (ex-info "Resource not found" {:uri uri})))))

(defn- handle-prompts-list
  "处理 prompts/list 请求

   参数:
   - server: MCPServer 实例

   返回: 提示词列表"
  [server]
  {:prompts (vec (vals @(:prompts-atom server)))})

(defn- handle-prompts-get
  "处理 prompts/get 请求

   参数:
   - server: MCPServer 实例
   - params: 请求参数 {:name \"...\" :arguments {...}}

   返回: 提示词内容"
  [server params]
  (let [prompt-name (:name params)
        arguments (or (:arguments params) {})
        prompts @(:prompts-atom server)
        prompt (get prompts prompt-name)]
    (if prompt
      (let [generator (:generator prompt)]
        (generator arguments))
      (throw (ex-info "Prompt not found" {:name prompt-name})))))

(defn- handle-message
  "处理传入消息

   参数:
   - server: MCPServer 实例
   - message: JSON-RPC 消息

   返回: 响应消息或 nil（通知不需要响应）"
  [server message]
  (let [method (:method message)
        params (:params message)
        id (:id message)]
    (try
      (let [result (case method
                     "initialize" (handle-initialize server params)
                     "initialized" nil  ;; 通知，无需响应
                     "ping" {}
                     "tools/list" (handle-tools-list server)
                     "tools/call" (handle-tools-call server params)
                     "resources/list" (handle-resources-list server)
                     "resources/read" (handle-resources-read server params)
                     "prompts/list" (handle-prompts-list server)
                     "prompts/get" (handle-prompts-get server params)
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
  (let [transport-instance (cond
                             (= transport :stdio)
                             (stdio/create-stdio-server-transport)

                             (and (map? transport) (= (:type transport) :sse))
                             (sse/create-sse-server-transport (:port transport))

                             :else
                             (throw (ex-info "Unsupported transport"
                                             {:transport transport})))]
    (->MCPServer name
                 version
                 transport-instance
                 (atom {})     ;; tools
                 (atom {})     ;; resources
                 (atom {})     ;; prompts
                 (atom false)
                 (atom nil))))

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
;; 工具注册
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
  (let [tool-name (:name tool)
        mcp-tool {:name tool-name
                  :description (:description tool)
                  :inputSchema (or (:inputSchema tool)
                                   (:parameters tool)
                                   {:type "object" :properties {}})}]
    (swap! (:tools-atom server)
           assoc tool-name (assoc mcp-tool :handler (:handler tool))))
  server)

(defn register-tools
  "批量注册工具

   参数:
   - server: MCPServer 实例
   - tools: 工具定义列表

   返回: server"
  [server tools]
  (doseq [tool tools]
    (register-tool server tool))
  server)

(defn unregister-tool
  "注销工具

   参数:
   - server: MCPServer 实例
   - tool-name: 工具名称

   返回: server"
  [server tool-name]
  (swap! (:tools-atom server) dissoc tool-name)
  server)

;; =============================================================================
;; 资源注册
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
  (let [uri (:uri resource)
        mcp-resource {:uri uri
                      :name (:name resource)
                      :description (:description resource)
                      :mimeType (or (:mime-type resource) "text/plain")}]
    (swap! (:resources-atom server)
           assoc uri (assoc mcp-resource :reader (:reader resource))))
  server)

(defn register-resources
  "批量注册资源

   参数:
   - server: MCPServer 实例
   - resources: 资源定义列表

   返回: server"
  [server resources]
  (doseq [resource resources]
    (register-resource server resource))
  server)

;; =============================================================================
;; 提示词注册
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
  (let [prompt-name (:name prompt)
        mcp-prompt {:name prompt-name
                    :description (:description prompt)
                    :arguments (or (:arguments prompt) [])}]
    (swap! (:prompts-atom server)
           assoc prompt-name (assoc mcp-prompt :generator (:generator prompt))))
  server)

(defn register-prompts
  "批量注册提示词

   参数:
   - server: MCPServer 实例
   - prompts: 提示词定义列表

   返回: server"
  [server prompts]
  (doseq [prompt prompts]
    (register-prompt server prompt))
  server)

;; =============================================================================
;; clj-agent 工具集成
;; =============================================================================

(defn register-clj-agent-tool
  "将 clj-agent 工具注册到 MCP Server

   参数:
   - server: MCPServer 实例
   - tool: clj-agent 工具定义 {:name :description :parameters :handler}

   返回: server"
  [server tool]
  (register-tool server
    {:name (name (:name tool))
     :description (:description tool)
     :inputSchema (or (:parameters tool)
                      {:type "object" :properties {}})
     :handler (:handler tool)}))

(defn register-clj-agent-tools
  "将 clj-agent 工具列表注册到 MCP Server

   参数:
   - server: MCPServer 实例
   - tools: clj-agent 工具列表

   返回: server

   示例:
   (require '[im.ttalk.agent.tools.api :as tools])
   (def registry (tools/create-tool-registry))
   (register-clj-agent-tools server (tools/registry-list-tools registry))"
  [server tools]
  (doseq [tool tools]
    (register-clj-agent-tool server tool))
  server)
