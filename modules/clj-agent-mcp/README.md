# clj-agent-mcp

MCP (Model Context Protocol) 服务器/客户端模块

[English](#english) | 中文

## 概述

`clj-agent-mcp` 实现 Model Context Protocol 规范：

- **MCP Server**：将工具暴露为 MCP 服务
- **MCP Client**：连接外部 MCP 服务器
- **传输协议**：支持 Stdio 和 SSE（Server-Sent Events）
- **JSON-RPC**：标准 JSON-RPC 2.0 消息处理

## 架构

```
┌─────────────────────────────────────────────────────────────┐
│                        使用层                                │
├─────────────────┬─────────────────┬─────────────────────────┤
│  MCP Server     │  Ring Handler   │  用户自定义 HTTP 框架   │
│  (server/core)  │                 │                         │
└────────┬────────┴────────┬────────┴──────────┬──────────────┘
         │                 │                   │
         └─────────────────┼───────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    handler.clj                               │
│  纯函数处理层 - 无状态，可复用                                │
│  - handle-initialize / handle-tools-list / handle-tools-call │
│  - route-request / ring-handler / sse-handler               │
└────────────────────────────┬────────────────────────────────┘
                             │
┌────────────────────────────┴────────────────────────────────┐
│                    registry.clj                              │
│  状态管理层 - 工具/资源/提示词注册                            │
│  - create-registry / register-tool / list-tools             │
└─────────────────────────────────────────────────────────────┘
```

## 依赖

```clojure
;; deps.edn
{:deps {im.ttalk/clj-agent-mcp {:local/root "../clj-agent-mcp"}}}
```

外部依赖：
- cheshire/cheshire 5.13.0
- http-kit/http-kit 2.8.0

## 命名空间

| 命名空间 | 说明 |
|---------|------|
| `im.ttalk.agent.mcp.registry` | 状态管理（工具/资源/提示词注册） |
| `im.ttalk.agent.mcp.handler` | 纯函数处理层 + Ring 适配器 |
| `im.ttalk.agent.mcp.server.core` | MCP 服务器核心（生命周期管理） |
| `im.ttalk.agent.mcp.client.core` | MCP 客户端 |
| `im.ttalk.agent.mcp.transport.stdio` | Stdio 传输 |
| `im.ttalk.agent.mcp.transport.sse` | SSE 传输 |
| `im.ttalk.agent.mcp.protocol` | MCP 协议定义 |
| `im.ttalk.agent.mcp.json_rpc` | JSON-RPC 消息处理 |

## 使用方式

### 方式一：独立 MCP Server

```clojure
(require '[im.ttalk.agent.mcp.server.core :as mcp])

;; 创建 MCP 服务器
(def server (mcp/create-server {:name "my-tools"
                                 :version "1.0.0"
                                 :transport :stdio}))

;; 注册工具
(mcp/register-tool server
  {:name "echo"
   :description "Echo a message"
   :inputSchema {:type "object"
                 :properties {:msg {:type "string"}}}
   :handler (fn [{:keys [msg]}] (str "Echo: " msg))})

;; 启动
(mcp/start server)

;; 停止
;; (mcp/stop server)
```

### 方式二：在自定义 HTTP 服务器中使用 Handler

```clojure
(require '[im.ttalk.agent.mcp.registry :as registry]
         '[im.ttalk.agent.mcp.handler :as handler])

;; 创建 registry
(def reg (registry/create-registry {:name "my-api" :version "1.0.0"}))

;; 注册工具
(registry/register-tool reg
  {:name "calculate"
   :description "Calculator"
   :handler (fn [{:keys [expr]}] (str (eval (read-string expr))))})

;; 创建 Ring handler
(def mcp-handler (handler/ring-handler reg))

;; 在你的 HTTP 框架中使用
;; (POST "/mcp" req (mcp-handler req))
```

### 方式三：共享 Registry

```clojure
(require '[im.ttalk.agent.mcp.registry :as registry]
         '[im.ttalk.agent.mcp.handler :as handler]
         '[im.ttalk.agent.mcp.server.core :as mcp])

;; 创建共享的 registry
(def shared-registry (registry/create-registry {:name "shared" :version "1.0.0"}))
(registry/register-tool shared-registry my-tool)

;; MCP Server 使用（stdio 模式）
(def mcp-server (mcp/create-server-with-registry shared-registry :stdio))
(mcp/start mcp-server)

;; HTTP Server 同时使用同一 registry
(def http-handler (handler/ring-handler shared-registry))
(run-server http-handler {:port 8080})
```

### MCP Client

```clojure
(require '[im.ttalk.agent.mcp.client.core :as mcp-client])

;; Stdio 传输（启动子进程）
(def client (mcp-client/connect
              {:transport :stdio
               :command ["clj" "-M:mcp-server"]}))

;; SSE 传输（连接远程服务器）
(def client (mcp-client/connect
              {:transport :sse
               :url "http://localhost:8080/mcp"}))

;; 列出工具
(mcp-client/list-tools client)

;; 调用工具
(mcp-client/call-tool client "echo" {:msg "Hello"})
```

## 传输协议

| 协议 | 适用场景 | 说明 |
|------|---------|------|
| Stdio | 本地子进程 | 通过 stdin/stdout 通信 |
| SSE | 远程服务 | HTTP + Server-Sent Events |

---

<a name="english"></a>

## English

### Overview

`clj-agent-mcp` implements the Model Context Protocol specification:

- **MCP Server**: Exposes tools as MCP services
- **MCP Client**: Connects to external MCP servers
- **Transport**: Stdio and SSE (Server-Sent Events)
- **JSON-RPC**: Standard JSON-RPC 2.0 message handling

### Architecture

The module is organized in three layers:

- **registry.clj** - State management (tools/resources/prompts registration)
- **handler.clj** - Pure function handlers + Ring adapters
- **server/core.clj** - Server lifecycle management

### Quick Start

```clojure
;; Standalone MCP Server
(require '[im.ttalk.agent.mcp.server.core :as mcp])

(def server (mcp/create-server {:name "my-tools" :transport :stdio}))
(mcp/register-tool server {:name "echo" :handler (fn [args] (:msg args))})
(mcp/start server)
```

```clojure
;; Use handler in custom HTTP server
(require '[im.ttalk.agent.mcp.registry :as registry]
         '[im.ttalk.agent.mcp.handler :as handler])

(def reg (registry/create-registry {:name "api" :version "1.0"}))
(def ring-handler (handler/ring-handler reg))
```

### Transport Protocols

- **Stdio**: Local subprocess communication via stdin/stdout
- **SSE**: Remote service via HTTP + Server-Sent Events
