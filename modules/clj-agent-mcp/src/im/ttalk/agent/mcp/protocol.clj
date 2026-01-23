(ns im.ttalk.agent.mcp.protocol
  "MCP 协议定义

   Model Context Protocol (MCP) 是一种用于 LLM 应用与外部工具/资源
   通信的开放协议。本模块定义了 MCP Server 和 Client 的核心协议。

   协议版本: 2024-11-05")

;; =============================================================================
;; 常量定义
;; =============================================================================

(def protocol-version
  "MCP 协议版本"
  "2024-11-05")

(def jsonrpc-version
  "JSON-RPC 版本"
  "2.0")

;; =============================================================================
;; MCP Server 协议
;; =============================================================================

(defprotocol IMCPServer
  "MCP 服务器协议

   实现此协议以创建可被 MCP 客户端连接的服务器。
   服务器提供三种能力：Resources、Tools、Prompts。"

  (server-info [this]
    "获取服务器信息

     返回:
     {:name \"服务器名称\"
      :version \"版本号\"
      :capabilities {:resources true/false
                     :tools true/false
                     :prompts true/false}}")

  (start-server [this]
    "启动服务器

     返回: 启动成功返回 true，否则抛出异常")

  (stop-server [this]
    "停止服务器

     返回: 停止成功返回 true")

  ;; Resources 能力
  (list-resources [this]
    "列出可用资源

     返回: 资源列表
     [{:uri \"file:///path/to/file\"
       :name \"文件名\"
       :description \"描述\"
       :mime-type \"text/plain\"}]")

  (read-resource [this uri]
    "读取资源内容

     参数:
     - uri: 资源 URI

     返回:
     {:contents [{:uri \"...\"
                  :mime-type \"...\"
                  :text \"内容\" 或 :blob \"base64\"}]}")

  ;; Tools 能力
  (list-tools [this]
    "列出可用工具

     返回: 工具列表
     [{:name \"工具名\"
       :description \"描述\"
       :inputSchema {:type \"object\"
                     :properties {...}
                     :required [...]}}]")

  (call-tool [this name arguments]
    "调用工具

     参数:
     - name: 工具名称
     - arguments: 工具参数 map

     返回:
     {:content [{:type \"text\" :text \"结果\"}]
      :isError false}")

  ;; Prompts 能力
  (list-prompts [this]
    "列出提示词模板

     返回: 提示词列表
     [{:name \"模板名\"
       :description \"描述\"
       :arguments [{:name \"参数名\"
                    :description \"描述\"
                    :required true/false}]}]")

  (get-prompt [this name arguments]
    "获取提示词

     参数:
     - name: 模板名称
     - arguments: 模板参数 map

     返回:
     {:description \"描述\"
      :messages [{:role \"user\"
                  :content {:type \"text\" :text \"内容\"}}]}"))

;; =============================================================================
;; MCP Client 协议
;; =============================================================================

(defprotocol IMCPClient
  "MCP 客户端协议

   实现此协议以创建可连接 MCP 服务器的客户端。
   客户端可以同时管理多个服务器连接。"

  (connect [this server-config]
    "连接到服务器

     参数:
     - server-config: 服务器配置
       {:name \"服务器标识\"
        :transport :stdio 或 :sse
        :command [\"命令\" \"参数\"]  ;; stdio 模式
        :url \"http://...\"           ;; sse 模式
        :env {\"KEY\" \"value\"}}     ;; 环境变量

     返回: 连接成功返回 server-name，否则抛出异常")

  (disconnect [this server-name]
    "断开服务器连接

     参数:
     - server-name: 服务器标识

     返回: 断开成功返回 true")

  (disconnect-all [this]
    "断开所有服务器连接

     返回: 断开的服务器数量")

  (list-servers [this]
    "列出已连接的服务器

     返回: 服务器信息列表
     [{:name \"服务器名\"
       :status :connected/:disconnected
       :capabilities {...}}]")

  ;; Resources
  (get-resources [this server-name]
    "获取服务器的资源列表

     参数:
     - server-name: 服务器标识

     返回: 资源列表")

  (fetch-resource [this server-name uri]
    "获取资源内容

     参数:
     - server-name: 服务器标识
     - uri: 资源 URI

     返回: 资源内容")

  ;; Tools
  (get-tools [this server-name]
    "获取服务器的工具列表

     参数:
     - server-name: 服务器标识

     返回: 工具列表")

  (get-all-tools [this]
    "获取所有服务器的工具列表

     返回: 工具列表（带服务器前缀）")

  (invoke-tool [this server-name tool-name arguments]
    "调用远程工具

     参数:
     - server-name: 服务器标识
     - tool-name: 工具名称
     - arguments: 工具参数

     返回: 工具执行结果")

  ;; Prompts
  (get-prompts [this server-name]
    "获取服务器的提示词列表

     参数:
     - server-name: 服务器标识

     返回: 提示词列表")

  (fetch-prompt [this server-name prompt-name arguments]
    "获取提示词内容

     参数:
     - server-name: 服务器标识
     - prompt-name: 提示词名称
     - arguments: 模板参数

     返回: 提示词内容"))

;; =============================================================================
;; Transport 协议
;; =============================================================================

(defprotocol ITransport
  "传输层协议

   定义底层通信机制，支持 Stdio 和 SSE 两种传输方式。"

  (transport-send [this message]
    "发送消息

     参数:
     - message: JSON-RPC 消息 map

     返回: 发送成功返回 true")

  (transport-receive [this]
    "接收消息（阻塞）

     返回: JSON-RPC 消息 map 或 nil（连接关闭）")

  (transport-close [this]
    "关闭传输

     返回: 关闭成功返回 true")

  (transport-open? [this]
    "检查传输是否打开

     返回: true/false"))

;; =============================================================================
;; 辅助函数
;; =============================================================================

(defn tool->mcp-schema
  "将 clj-agent 工具定义转换为 MCP 工具 schema

   参数:
   - tool: clj-agent 工具定义 {:name :description :parameters}

   返回: MCP 工具 schema"
  [tool]
  {:name (name (:name tool))
   :description (:description tool)
   :inputSchema (or (:parameters tool)
                    {:type "object"
                     :properties {}})})

(defn mcp-tool->clj-tool
  "将 MCP 工具 schema 转换为 clj-agent 工具定义

   参数:
   - mcp-tool: MCP 工具 schema
   - server-name: 服务器名称（用于前缀）

   返回: clj-agent 工具定义"
  [mcp-tool server-name]
  {:name (keyword (str server-name "/" (:name mcp-tool)))
   :description (:description mcp-tool)
   :parameters (:inputSchema mcp-tool)})
