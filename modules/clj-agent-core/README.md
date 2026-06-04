# clj-agent-core

核心模块 - Kernel 编排器、Tool 系统、Filter 中间件

[English](#english) | 中文

## 概述

`clj-agent-core` 同时是**协议（端口）层**与 **Agent 运行时**：

- **协议 / 契约**：`ILLMProvider`、中立消息、统一响应、通用 Service —— 任何实现协议的 jar 都能作为 provider 注入
- **client**：高层 Agent（`create-agent`/`chat`/`resume`），内置按 conversation-id 的记忆
- **Kernel**：中央编排器，提供 `invoke-chat` / `invoke-tool` 原语（经 advisor 洋葱链）
- **deftool**：宏，同时定义函数和生成 LLM tool schema
- **Advisor**：洋葱式 around 中间件（对标 Spring AI Advisor），含记忆 advisor
- **Memory**：ChatMemory（in-memory / windowed / SQLite）
- **Context**：请求级共享状态
- **converter / prompt**：结构化输出解析（OutputConverter）与提示词模板（PromptTemplate），provider 无关

## 依赖

```clojure
;; deps.edn
{:deps {im.ttalk/clj-agent-core {:local/root "../clj-agent-core"}}}
```

内部依赖：无（core 定义协议/契约 + Agent 运行时；provider 反过来依赖 core）

外部依赖：
- cheshire/cheshire 5.12.0（converter）
- com.taoensso/timbre 6.3.0（日志）
- com.github.seancorfield/next.jdbc 1.3.939（memory/sqlite，按需）
- org.xerial/sqlite-jdbc 3.45.1.0（memory/sqlite，按需）

## 命名空间

**协议 / 契约（端口）**

| 命名空间 | 说明 |
|---------|------|
| `im.ttalk.agent.model` | `ILLMProvider` 协议（端口，中立消息边界） |
| `im.ttalk.agent.model.message` / `.response` / `.error` / `.types` | 中立消息、统一响应、错误、构造器 |
| `im.ttalk.agent.model.service` | 通用 create-service（仅凭协议包装任意 provider） |

**Agent 运行时**

| 命名空间 | 说明 |
|---------|------|
| `im.ttalk.agent.kernel` | Kernel 构建、调用、查询 API |
| `im.ttalk.agent.tool` | `deftool` 宏定义 |
| `im.ttalk.agent.advisor` | Advisor 洋葱链创建和内置 Advisor |
| `im.ttalk.agent.advisor.memory` | 按 conversation-id 串历史的记忆 advisor |
| `im.ttalk.agent.context` | Context 状态管理 |
| `im.ttalk.agent.converter.*` | 结构化输出解析（OutputConverter） |
| `im.ttalk.agent.prompt.*` | 提示词模板（PromptTemplate） |
| `im.ttalk.agent.memory` / `.memory.sqlite` | ChatMemory store |
| `im.ttalk.agent.react` | ReAct 工具调用循环 |
| `im.ttalk.agent.client` | 高级 Agent API（create-agent / chat / resume） |
| `im.ttalk.agent.common` | 共享 Kernel 构建逻辑 |

> 各厂商实现（`im.ttalk.agent.provider.*`）在 `clj-agent-provider`，依赖本模块的协议。

## API 参考

### 高层 Agent API（client，推荐入门）

```clojure
(require '[im.ttalk.agent.client :as agent])

;; 创建 Agent（默认带 in-memory 记忆，按 conversation-id 累积）
(def a (agent/create-agent
         {:provider provider          ;; 任意 ILLMProvider 实例（必需）
          :model "gpt-4"
          :system-prompt "你是助手"
          :tools [#'my-tool]          ;; 可选
          :memory store               ;; 可选，默认 (memory/in-memory-store)
          :conversation-id "u1"       ;; 可选，默认随机 UUID
          :on-pause (fn [info] ...)}))  ;; 可选，配置即启用敏感工具 pause/resume

(agent/chat a "你好")        ;; => {:status :completed :text "..." :tool-calls-made [...]}
(agent/resume a "approved")  ;; pause 后批准/拒绝
(agent/paused? a)
(agent/get-history a)        ;; 该会话中立消息历史
(agent/reset! a)             ;; 清空当前会话
```

### Kernel Build API

```clojure
(require '[im.ttalk.agent.kernel :as kernel])

;; 创建 Builder
(kernel/create-kernel-builder)
(kernel/create-kernel-builder {:max-tool-iterations 10})

;; 配置 Builder
(kernel/add-tools builder tools)        ;; 添加工具
(kernel/add-service builder service)    ;; 设置 LLM Service
(kernel/add-filter builder filter-def)  ;; 添加 Filter

;; 构建 Kernel
(kernel/build-kernel builder)           ;; 返回 Kernel record
```

### Kernel Invoke API

kernel 只提供两个原语（均经 filter 洋葱链）；**工具调用循环不在 kernel**，已下沉到
`im.ttalk.agent.client`（`create-agent` + `chat`，或 `im.ttalk.agent.react/invoke`）。

```clojure
;; 调用工具函数（经过 :phase :tool filter 链）
(kernel/invoke-tool kernel :fn-name {:arg "val"} context)
;; => {:value result :context updated-ctx}

;; LLM 调用（经过 :phase :chat filter 链，不含工具循环）
(kernel/invoke-chat kernel messages opts)
;; => {:response {:text "..." :tool-calls [...]} :context ctx}
```

### Kernel Query API

```clojure
(:tools kernel)                       ;; 所有 tool schema 列表
(:service kernel)                     ;; LLM Service map
(kernel/find-function kernel :name)   ;; => {:plugin p :tool-var v} 或 nil
(kernel/list-functions kernel)        ;; => [:fn1 :fn2 ...]
```

### deftool 宏

```clojure
(require '[im.ttalk.agent.tool :refer [deftool]])

(deftool get-weather
  "获取天气信息"
  [[city :string "城市名称"]
   [unit :string "温度单位" :default "C"]]
  {:sensitive true   ;; 可选：标记敏感操作
   :context true}    ;; 可选：需要 Context（多一个 ctx 参数）
  (str city ": 25°" unit))

;; 支持的参数类型: :string :int :float :boolean :array :object
;; 生成的 metadata: :tool/schema :tool/sensitive
```

### Filter API（洋葱式 around，对标 Spring AI Advisor）

```clojure
(require '[im.ttalk.agent.advisor :as filters])

;; 创建 Filter —— 根抽象 around(req, chain)；before/after 为糖
(filters/create-filter :name :chat :order 10
  :around (fn [req chain] ... (chain req') ...))   ;; 完整 around
(filters/create-filter :name :tool :order 10
  :before (fn [req] req') :after (fn [resp] resp'))     ;; 改写糖

;; phase: :chat（invoke-chat，terminal 调 LLM）| :tool（invoke-tool，terminal 调函数）
;; order: 越小越靠外层（最先 before、最后 after）；不调 chain 即短路

;; 内置 tool filter
filters/logging-filter
(filters/timeout-filter 5000)   ;; around：超时返回提示结果
(filters/approval-filter)       ;; around：敏感工具人工审批，拒绝则短路

;; 挂载 + 执行
(kernel/add-filter builder my-filter) ;; 加入 builder
(filters/build-chain filters terminal) ;; 折成洋葱，返回 (fn [req] -> resp)
```

### Context API

```clojure
(require '[im.ttalk.agent.context :as ctx])

(ctx/create)                          ;; 空 Context
(ctx/create {:user-id "u1"})          ;; 带变量
(ctx/context? x)                      ;; 谓词
(ctx/get-var ctx :key)                ;; 获取变量
(ctx/set-var ctx :key val)            ;; 设置单个变量（返回新 ctx）
(ctx/set-vars ctx {:a 1 :b 2})        ;; 批量设置
(ctx/conversation-id ctx)             ;; 取会话 id
(ctx/with-conversation-id ctx "u1")   ;; 设会话 id（返回新 ctx）
```

### Memory（ChatMemory）

```clojure
(require '[im.ttalk.agent.memory :as memory]
         '[im.ttalk.agent.memory.sqlite :as sqlite])

(memory/in-memory-store)                              ;; 进程内（默认）
(memory/windowed (memory/in-memory-store)
                 {:max-messages 20})                  ;; 滑动窗口（pairing-safe）
(sqlite/sqlite-store "agent.db")                      ;; SQLite 持久化（":memory:" 为进程内库）

;; ChatMemory 协议：mem-get / mem-add / mem-clear —— 自定义后端实现此协议即可
```

### 中立消息 / 通用 Service

```clojure
(require '[im.ttalk.agent.model.message :as msg]
         '[im.ttalk.agent.model.service :as service])

;; 中立消息构造
(msg/system "...") (msg/user "...") (msg/assistant "...")
(msg/assistant-tool-calls [(msg/tool-call "id" "name" {:arg 1})])
(msg/tool-result "id" "name" "result")

;; 通用 Service：仅凭协议把任意 provider 包成 kernel service
(service/create-service provider {:model "gpt-4" :max-tokens 4096})
;; => {:chat-fn ... :build-result-msgs ...}
```

### 结构化输出 / 提示词模板（provider 无关库）

```clojure
(require '[im.ttalk.agent.converter.api :as conv]   ;; OutputConverter：defparser/json-parser/parse/...
         '[im.ttalk.agent.prompt.api :as prompt])   ;; PromptTemplate：template/chat-template/render/...
```

---

<a name="english"></a>

## English

### Overview

`clj-agent-core` is both the **protocol (port) layer** and the **Agent runtime**:

- **Protocol / contract**: `ILLMProvider`, neutral messages, unified response, generic Service — any jar implementing the protocol can be injected as a provider
- **client**: High-level Agent (`create-agent`/`chat`/`resume`) with built-in per-conversation-id memory
- **Kernel**: Central orchestrator exposing `invoke-chat` / `invoke-tool` primitives (through the advisor onion chain)
- **deftool**: Macro that defines a function and generates its LLM tool schema
- **Advisor**: Onion-style around middleware (mirrors Spring AI Advisor), incl. memory advisor
- **Memory**: ChatMemory (in-memory / windowed / SQLite)
- **Context**: Per-request shared state
- **converter / prompt**: Structured output (OutputConverter) and prompt templates (PromptTemplate), provider-agnostic

### Key APIs

- `agent/create-agent` → `agent/chat` / `agent/resume` - High-level Agent with memory
- `kernel/create-kernel-builder` → `add-tools` → `add-service` → `add-filter` → `build-kernel`
- `kernel/invoke-tool` / `kernel/invoke-chat` - Primitives through the :tool / :chat advisor chains
  (the tool-calling loop lives in `im.ttalk.agent.react` / `client`, not the kernel)
- `deftool` - Define tool with auto-generated schema
- `service/create-service` - Wrap any `ILLMProvider` into a kernel service (protocol-only)
- `memory/in-memory-store` / `memory/windowed` / `sqlite/sqlite-store` - ChatMemory backends
- `ctx/create`, `ctx/get-var`, `ctx/set-var`, `ctx/set-vars`, `ctx/with-conversation-id` - Context
