(ns im.ttalk.agent.mcp.client.core
  "MCP Client 核心实现

   提供 MCP Client 的主要功能：
   - 连接管理（支持多服务器）
   - 初始化握手
   - 工具/资源/提示词调用
   - 与 clj-agent 集成"
  (:require [im.ttalk.agent.mcp.protocol :as protocol]
            [im.ttalk.agent.mcp.json-rpc :as rpc]
            [im.ttalk.agent.mcp.transport.stdio :as stdio]
            [im.ttalk.agent.mcp.transport.sse :as sse]))

;; =============================================================================
;; 客户端状态
;; =============================================================================

(defrecord ServerConnection [name
                             transport
                             server-info
                             capabilities
                             status])

(defrecord MCPClient [connections    ;; atom: {server-name -> ServerConnection}
                      client-info])

;; =============================================================================
;; 连接管理
;; =============================================================================

(defn- create-transport
  "根据配置创建传输层

   参数:
   - config: 服务器配置

   返回: ITransport 实例"
  [config]
  (case (:transport config)
    :stdio
    (stdio/create-stdio-client-transport
      (:command config)
      (:env config)
      (:working-dir config))

    :sse
    (sse/create-sse-client-transport
      (:url config)
      (or (:sse-endpoint config) "/sse")
      (or (:post-endpoint config) "/message"))

    ;; 默认 stdio
    (stdio/create-stdio-client-transport
      (:command config)
      (:env config))))

(defn- initialize-connection
  "初始化与服务器的连接

   参数:
   - transport: ITransport 实例
   - client-info: 客户端信息

   返回: {:server-info ... :capabilities ...}"
  [transport client-info]
  (let [init-request (rpc/make-request "initialize"
                       {:protocolVersion protocol/protocol-version
                        :capabilities {:tools {}
                                       :resources {}
                                       :prompts {}}
                        :clientInfo client-info})
        response (stdio/send-and-receive transport init-request 10000)]
    (if (rpc/error-response? response)
      (throw (ex-info "Initialize failed"
                      {:error (:error response)}))
      (let [result (:result response)]
        ;; 发送 initialized 通知
        (protocol/transport-send transport
          (rpc/make-notification "initialized"))
        {:server-info (:serverInfo result)
         :capabilities (:capabilities result)}))))

;; =============================================================================
;; MCPClient 实现
;; =============================================================================

(defn create-client
  "创建 MCP Client 实例

   参数:
   - config: 客户端配置（可选）
     {:name \"客户端名称\"
      :version \"版本号\"}

   返回: MCPClient 实例

   示例:
   (def client (create-client {:name \"my-agent\" :version \"1.0.0\"}))"
  ([]
   (create-client {}))
  ([config]
   (->MCPClient
     (atom {})
     {:name (or (:name config) "clj-agent-mcp-client")
      :version (or (:version config) "1.0.0")})))

(defn connect
  "连接到 MCP Server

   参数:
   - client: MCPClient 实例
   - server-config: 服务器配置
     {:name \"服务器标识\"
      :transport :stdio 或 :sse
      :command [\"npx\" \"...\"]  ;; stdio 模式
      :url \"http://...\"        ;; sse 模式
      :env {\"KEY\" \"value\"}}

   返回: 连接的服务器名称

   示例:
   (connect client
     {:name \"filesystem\"
      :transport :stdio
      :command [\"npx\" \"-y\" \"@anthropic-ai/mcp-server-filesystem\" \"/tmp\"]})"
  [client server-config]
  (let [server-name (:name server-config)
        transport (create-transport server-config)
        {:keys [server-info capabilities]}
        (initialize-connection transport (:client-info client))
        connection (->ServerConnection
                     server-name
                     transport
                     server-info
                     capabilities
                     :connected)]
    (swap! (:connections client) assoc server-name connection)
    server-name))

(defn disconnect
  "断开服务器连接

   参数:
   - client: MCPClient 实例
   - server-name: 服务器名称

   返回: true/false"
  [client server-name]
  (when-let [conn (get @(:connections client) server-name)]
    (protocol/transport-close (:transport conn))
    (swap! (:connections client) dissoc server-name)
    true))

(defn disconnect-all
  "断开所有服务器连接

   参数:
   - client: MCPClient 实例

   返回: 断开的连接数"
  [client]
  (let [conns @(:connections client)
        count (count conns)]
    (doseq [[_ conn] conns]
      (protocol/transport-close (:transport conn)))
    (reset! (:connections client) {})
    count))

(defn list-servers
  "列出已连接的服务器

   参数:
   - client: MCPClient 实例

   返回: 服务器信息列表"
  [client]
  (map (fn [[name conn]]
         {:name name
          :status (:status conn)
          :server-info (:server-info conn)
          :capabilities (:capabilities conn)})
       @(:connections client)))

(defn get-connection
  "获取服务器连接

   参数:
   - client: MCPClient 实例
   - server-name: 服务器名称

   返回: ServerConnection 或 nil"
  [client server-name]
  (get @(:connections client) server-name))

;; =============================================================================
;; 工具操作
;; =============================================================================

(defn get-tools
  "获取服务器的工具列表

   参数:
   - client: MCPClient 实例
   - server-name: 服务器名称

   返回: 工具列表"
  [client server-name]
  (when-let [conn (get-connection client server-name)]
    (let [request (rpc/make-request "tools/list")
          response (stdio/send-and-receive (:transport conn) request)]
      (if (rpc/error-response? response)
        (throw (ex-info "Failed to get tools"
                        {:server server-name
                         :error (:error response)}))
        (get-in response [:result :tools])))))

(defn get-all-tools
  "获取所有服务器的工具列表（带服务器前缀）

   参数:
   - client: MCPClient 实例

   返回: 工具列表"
  [client]
  (mapcat
    (fn [[server-name _]]
      (map (fn [tool]
             (assoc tool :server server-name))
           (get-tools client server-name)))
    @(:connections client)))

(defn invoke-tool
  "调用远程工具

   参数:
   - client: MCPClient 实例
   - server-name: 服务器名称
   - tool-name: 工具名称
   - arguments: 工具参数

   返回: 工具执行结果"
  [client server-name tool-name arguments]
  (when-let [conn (get-connection client server-name)]
    (let [request (rpc/make-request "tools/call"
                    {:name tool-name
                     :arguments (or arguments {})})
          response (stdio/send-and-receive (:transport conn) request)]
      (if (rpc/error-response? response)
        (throw (ex-info "Tool call failed"
                        {:server server-name
                         :tool tool-name
                         :error (:error response)}))
        (:result response)))))

;; =============================================================================
;; 资源操作
;; =============================================================================

(defn get-resources
  "获取服务器的资源列表

   参数:
   - client: MCPClient 实例
   - server-name: 服务器名称

   返回: 资源列表"
  [client server-name]
  (when-let [conn (get-connection client server-name)]
    (let [request (rpc/make-request "resources/list")
          response (stdio/send-and-receive (:transport conn) request)]
      (if (rpc/error-response? response)
        (throw (ex-info "Failed to get resources"
                        {:server server-name
                         :error (:error response)}))
        (get-in response [:result :resources])))))

(defn fetch-resource
  "获取资源内容

   参数:
   - client: MCPClient 实例
   - server-name: 服务器名称
   - uri: 资源 URI

   返回: 资源内容"
  [client server-name uri]
  (when-let [conn (get-connection client server-name)]
    (let [request (rpc/make-request "resources/read" {:uri uri})
          response (stdio/send-and-receive (:transport conn) request)]
      (if (rpc/error-response? response)
        (throw (ex-info "Failed to read resource"
                        {:server server-name
                         :uri uri
                         :error (:error response)}))
        (:result response)))))

;; =============================================================================
;; 提示词操作
;; =============================================================================

(defn get-prompts
  "获取服务器的提示词列表

   参数:
   - client: MCPClient 实例
   - server-name: 服务器名称

   返回: 提示词列表"
  [client server-name]
  (when-let [conn (get-connection client server-name)]
    (let [request (rpc/make-request "prompts/list")
          response (stdio/send-and-receive (:transport conn) request)]
      (if (rpc/error-response? response)
        (throw (ex-info "Failed to get prompts"
                        {:server server-name
                         :error (:error response)}))
        (get-in response [:result :prompts])))))

(defn fetch-prompt
  "获取提示词内容

   参数:
   - client: MCPClient 实例
   - server-name: 服务器名称
   - prompt-name: 提示词名称
   - arguments: 模板参数

   返回: 提示词内容"
  [client server-name prompt-name arguments]
  (when-let [conn (get-connection client server-name)]
    (let [request (rpc/make-request "prompts/get"
                    {:name prompt-name
                     :arguments (or arguments {})})
          response (stdio/send-and-receive (:transport conn) request)]
      (if (rpc/error-response? response)
        (throw (ex-info "Failed to get prompt"
                        {:server server-name
                         :prompt prompt-name
                         :error (:error response)}))
        (:result response)))))

;; =============================================================================
;; clj-agent 集成
;; =============================================================================

(defn mcp-tool->clj-tool
  "将 MCP 工具转换为 clj-agent 工具格式

   参数:
   - client: MCPClient 实例
   - server-name: 服务器名称
   - mcp-tool: MCP 工具定义

   返回: clj-agent 工具定义"
  [client server-name mcp-tool]
  {:name (keyword (str server-name "/" (:name mcp-tool)))
   :description (or (:description mcp-tool) "")
   :parameters (or (:inputSchema mcp-tool)
                   {:type "object" :properties {}})
   :handler (fn [args]
              (let [result (invoke-tool client server-name (:name mcp-tool) args)]
                ;; 提取文本内容
                (if-let [content (:content result)]
                  (->> content
                       (filter #(= (:type %) "text"))
                       (map :text)
                       (clojure.string/join "\n"))
                  (str result))))})

(defn mcp-tools->clj-tools
  "将服务器的所有 MCP 工具转换为 clj-agent 工具

   参数:
   - client: MCPClient 实例
   - server-name: 服务器名称

   返回: clj-agent 工具列表"
  [client server-name]
  (let [tools (get-tools client server-name)]
    (map #(mcp-tool->clj-tool client server-name %) tools)))

(defn get-all-clj-tools
  "获取所有服务器的工具，转换为 clj-agent 格式

   参数:
   - client: MCPClient 实例

   返回: clj-agent 工具列表"
  [client]
  (mapcat
    (fn [[server-name _]]
      (mcp-tools->clj-tools client server-name))
    @(:connections client)))

;; =============================================================================
;; 便捷函数
;; =============================================================================

(defn create-and-connect
  "创建客户端并连接到多个服务器

   参数:
   - servers: 服务器配置列表

   返回: MCPClient 实例

   示例:
   (def client
     (create-and-connect
       [{:name \"fs\"
         :transport :stdio
         :command [\"npx\" \"-y\" \"@anthropic-ai/mcp-server-filesystem\" \"/tmp\"]}
        {:name \"memory\"
         :transport :stdio
         :command [\"npx\" \"-y\" \"@anthropic-ai/mcp-server-memory\"]}]))"
  [servers]
  (let [client (create-client)]
    (doseq [server-config servers]
      (try
        (connect client server-config)
        (catch Exception e
          (println "[MCP Client] Failed to connect to"
                   (:name server-config) ":" (.getMessage e)))))
    client))

(defn ping
  "Ping 服务器

   参数:
   - client: MCPClient 实例
   - server-name: 服务器名称

   返回: true（成功）或抛出异常"
  [client server-name]
  (when-let [conn (get-connection client server-name)]
    (let [request (rpc/make-request "ping")
          response (stdio/send-and-receive (:transport conn) request 5000)]
      (not (rpc/error-response? response)))))
