# clj-agent-mcp

MCP (Model Context Protocol) 服务器/客户端模块

[English](#english) | 中文

## 概述

`clj-agent-mcp` 实现 Model Context Protocol 规范：

- **MCP Server**：将 Kernel 工具暴露为 MCP 服务
- **MCP Client**：连接外部 MCP 服务器
- **传输协议**：支持 Stdio 和 SSE（Server-Sent Events）
- **JSON-RPC**：标准 JSON-RPC 2.0 消息处理

## 依赖

```clojure
;; deps.edn
{:deps {im.ttalk/clj-agent-mcp {:local/root "../clj-agent-mcp"}}}
```

内部依赖：`clj-agent-core`

外部依赖：
- cheshire/cheshire 5.13.0
- http-kit/http-kit 2.8.0

## 命名空间

| 命名空间 | 说明 |
|---------|------|
| `im.ttalk.agent.mcp.server.main` | 服务器 CLI 入口 |
| `im.ttalk.agent.mcp.server.core` | 服务器核心实现 |
| `im.ttalk.agent.mcp.server.protocol` | 服务端协议处理 |
| `im.ttalk.agent.mcp.client.core` | 客户端实现 |
| `im.ttalk.agent.mcp.transport.stdio` | Stdio 传输 |
| `im.ttalk.agent.mcp.transport.sse` | SSE 传输 |
| `im.ttalk.agent.mcp.protocol` | MCP 协议定义 |
| `im.ttalk.agent.mcp.json_rpc` | JSON-RPC 消息处理 |

## 使用方式

### 启动 MCP 服务器

```bash
# 使用 deps.edn alias 启动
clj -M:mcp-server
```

### 客户端连接

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
```

### 服务器配置

```clojure
(require '[im.ttalk.agent.mcp.server.core :as mcp-server])

;; 创建带自定义工具的 MCP 服务器
(def server (mcp-server/create-server
              {:kernel app-kernel    ;; 使用 Kernel 的工具
               :transport :stdio}))

(mcp-server/start! server)
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

- **MCP Server**: Exposes Kernel tools as MCP services
- **MCP Client**: Connects to external MCP servers
- **Transport**: Stdio and SSE (Server-Sent Events)
- **JSON-RPC**: Standard JSON-RPC 2.0 message handling

### Quick Start

```bash
# Start MCP server
clj -M:mcp-server
```

```clojure
;; Connect client
(def client (mcp-client/connect {:transport :stdio :command ["clj" "-M:mcp-server"]}))
```

### Transport Protocols

- **Stdio**: Local subprocess communication via stdin/stdout
- **SSE**: Remote service via HTTP + Server-Sent Events
