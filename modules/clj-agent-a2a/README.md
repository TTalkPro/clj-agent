# clj-agent-a2a

A2A (Agent-to-Agent Protocol) 服务器/客户端模块

[English](#english) | 中文

## 概述

`clj-agent-a2a` 实现 Agent-to-Agent Protocol 规范（版本 0.3.0）：

- **A2A Server**：将 Agent 暴露为 A2A 服务
- **A2A Client**：与远程 Agent 通信
- **任务管理**：完整的任务生命周期支持
- **流式响应**：SSE（Server-Sent Events）支持
- **Agent Card**：标准化 Agent 发现机制

## 架构

```
┌─────────────────────────────────────────────────────────────┐
│                        使用层                                │
├─────────────────┬─────────────────┬─────────────────────────┤
│  A2A Server     │  Ring Handler   │  用户自定义 HTTP 框架   │
│  (server/core)  │                 │                         │
└────────┬────────┴────────┬────────┴──────────┬──────────────┘
         │                 │                   │
         └─────────────────┼───────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    handler.clj                               │
│  状态管理 + 纯函数处理层 + Ring 适配器                        │
│  - A2ARegistry / create-registry / set-message-handler      │
│  - handle-message-send / handle-tasks-get / handle-tasks-cancel │
│  - route-request / ring-handler / combined-ring-handler     │
└────────────────────────────┬────────────────────────────────┘
                             │
         ┌───────────────────┼───────────────────┐
         ▼                   ▼                   ▼
┌─────────────┐    ┌─────────────────┐    ┌─────────────┐
│  types.clj  │    │    task.clj     │    │  card.clj   │
│  核心类型   │    │  任务生命周期   │    │  Agent Card │
└─────────────┘    └─────────────────┘    └─────────────┘
```

## 依赖

```clojure
;; deps.edn
{:deps {im.ttalk/clj-agent-a2a {:local/root "../clj-agent-a2a"}}}
```

外部依赖：
- cheshire/cheshire 5.13.0
- http-kit/http-kit 2.8.0
- danlentz/clj-uuid 0.1.9

## 命名空间

| 命名空间 | 说明 |
|---------|------|
| `im.ttalk.agent.a2a.types` | 核心类型（Message, Task, Artifact, AgentCard） |
| `im.ttalk.agent.a2a.json_rpc` | JSON-RPC 2.0 实现 |
| `im.ttalk.agent.a2a.task` | 任务生命周期管理 |
| `im.ttalk.agent.a2a.card` | Agent Card 生成与管理 |
| `im.ttalk.agent.a2a.handler` | 状态管理 + 纯函数处理层 + Ring 适配器 |
| `im.ttalk.agent.a2a.server` | A2A 服务器核心（生命周期管理） |
| `im.ttalk.agent.a2a.client` | A2A 客户端 |

## 使用方式

### 方式一：独立 A2A Server

```clojure
(require '[im.ttalk.agent.a2a.server :as a2a]
         '[im.ttalk.agent.a2a.types :as types])

;; 创建 A2A 服务器
(def server (a2a/create-server {:name "my-agent"
                                 :description "My AI Agent"
                                 :url "http://localhost:8080"
                                 :version "1.0.0"}))

;; 设置消息处理器
(a2a/set-message-handler server
  (fn [registry task message]
    ;; 返回字符串、artifact 列表或特殊指令
    (str "Response: " (types/message-text message))))

;; 启动（HTTP 服务器）
(a2a/start server {:port 8080})

;; 停止
;; (a2a/stop server)
```

### 方式二：快速启动

```clojure
(require '[im.ttalk.agent.a2a.server :as a2a])

;; 一行代码启动 A2A 服务器
(def server
  (a2a/create-and-start
    {:name "echo-agent"
     :description "Echo Agent"
     :url "http://localhost:8080"
     :port 8080
     :message-handler (fn [_ _ msg]
                        (str "Echo: " (types/message-text msg)))}))
```

### 方式三：在自定义 HTTP 服务器中使用 Handler

```clojure
(require '[im.ttalk.agent.a2a.handler :as handler])

;; 创建 registry
(def reg (handler/create-registry {:name "my-agent"
                                    :description "My Agent"
                                    :url "http://localhost:8080"}))

;; 设置消息处理器
(handler/set-message-handler reg
  (fn [reg task msg]
    "处理结果"))

;; 创建 Ring handler
(def a2a-handler (handler/ring-handler reg))
(def card-handler (handler/agent-card-handler reg))

;; 或使用组合 handler
(def combined (handler/combined-ring-handler reg))

;; 在你的 HTTP 框架中使用
;; (GET "/.well-known/agent.json" [] (card-handler req))
;; (POST "/a2a" req (a2a-handler req))
```

### 方式四：共享 Registry

```clojure
(require '[im.ttalk.agent.a2a.handler :as handler]
         '[im.ttalk.agent.a2a.server :as a2a])

;; 创建共享的 registry
(def shared-registry (handler/create-registry {:name "shared"
                                                :description "Shared Agent"
                                                :url "http://localhost:8080"}))
(handler/set-message-handler shared-registry my-handler)

;; A2A Server 使用
(def a2a-server (a2a/create-server-with-registry shared-registry))
(a2a/start a2a-server {:port 8080})

;; 同时在其他地方使用 handler
(def other-handler (handler/ring-handler shared-registry))
```

### A2A Client

```clojure
(require '[im.ttalk.agent.a2a.client :as client])

;; 创建客户端
(def c (client/create-client "http://localhost:8080"))

;; 发现 Agent
(client/discover! c)
(println (client/get-agent-card c))

;; 发送消息
(def task (client/send-text c "Hello, Agent!"))
(println "Task ID:" (:id task))

;; 等待任务完成
(def result (client/wait-for-completion c (:id task)))
(println "Result:" (client/get-task-result result))

;; 流式响应
(client/send-message-stream c "Hello"
  (fn [event data]
    (println "Event:" event "Data:" data)))
```

## 消息处理器返回值

消息处理器 `(fn [registry task message] -> result)` 的返回值：

| 返回类型 | 说明 |
|---------|------|
| `"string"` | 直接作为文本结果（自动包装为 artifact） |
| `[artifact1 artifact2]` | 多个产出物 |
| `{:type :input-required :prompt "..."}` | 请求用户输入 |
| `{:type :failed :error "..."}` | 标记任务失败 |

## 任务状态

```
submitted ──→ working ──→ completed
                  │          ↑
                  ├────────→ input-required ──→ working → completed
                  │
                  ├────────→ failed
                  │
                  └────────→ canceled
```

| 状态 | 说明 |
|------|------|
| `submitted` | 任务已提交 |
| `working` | 任务执行中 |
| `input-required` | 等待用户输入 |
| `completed` | 任务完成 |
| `failed` | 任务失败 |
| `canceled` | 任务取消 |

## 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/.well-known/agent.json` | GET | Agent Card 发现 |
| `/a2a` | POST | JSON-RPC 2.0 端点 |
| `/a2a/stream` | POST | SSE 流式端点 |

## JSON-RPC 方法

| 方法 | 说明 |
|------|------|
| `message/send` | 发送消息 |
| `tasks/get` | 获取任务状态 |
| `tasks/cancel` | 取消任务 |
| `tasks/pushNotificationConfig/set` | 设置推送配置 |
| `tasks/pushNotificationConfig/get` | 获取推送配置 |
| `tasks/pushNotificationConfig/delete` | 删除推送配置 |

---

<a name="english"></a>

## English

### Overview

`clj-agent-a2a` implements the Agent-to-Agent Protocol specification (version 0.3.0):

- **A2A Server**: Exposes an Agent as an A2A service
- **A2A Client**: Communicates with remote Agents
- **Task Management**: Full task lifecycle support
- **Streaming**: SSE (Server-Sent Events) support
- **Agent Card**: Standardized Agent discovery mechanism

### Architecture

The module is organized in layers:

- **handler.clj** - State management + Pure function handlers + Ring adapters
- **server/core.clj** - A2A Server lifecycle management
- **client.clj** - A2A Client for remote communication

### Quick Start

```clojure
;; Standalone A2A Server
(require '[im.ttalk.agent.a2a.server :as a2a])

(def server
  (a2a/create-and-start
    {:name "my-agent"
     :description "My Agent"
     :url "http://localhost:8080"
     :port 8080
     :message-handler (fn [_ _ msg] "Response")}))
```

```clojure
;; Use handler in custom HTTP server
(require '[im.ttalk.agent.a2a.handler :as handler])

(def reg (handler/create-registry {:name "api" :description "API" :url "http://localhost"}))
(handler/set-message-handler reg my-handler)
(def ring-handler (handler/combined-ring-handler reg))
```

```clojure
;; A2A Client
(require '[im.ttalk.agent.a2a.client :as client])

(def c (client/create-client "http://localhost:8080"))
(def task (client/send-text c "Hello!"))
(def result (client/wait-for-completion c (:id task)))
```

### Task States

- `submitted` → `working` → `completed`/`failed`
- `working` → `input-required` → `working` → `completed`
- Any state → `canceled`

### Endpoints

- `GET /.well-known/agent.json` - Agent Card discovery
- `POST /a2a` - JSON-RPC 2.0 endpoint
- `POST /a2a/stream` - SSE streaming endpoint
