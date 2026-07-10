# clj-agent-core

核心模块 - 协议（端口）+ Kernel 原语

[English](#english) | 中文

## 概述

`clj-agent-core` 是**协议（端口）层**与 **kernel 原语**：

- **协议 / 契约**：`ILLMProvider`、中立消息、统一响应、通用 Service —— 任何实现协议的 jar 都能作为 provider 注入
- **Kernel**：中央编排器，提供 `invoke-chat` / `invoke-tool` 原语（经 advisor 洋葱链）
- **deftool**：宏，同时定义函数和生成 LLM tool schema
- **Advisor**：洋葱式 around 中间件执行器（对标 Spring AI Advisor）
- **Context**：请求级共享状态
- **streaming**：流式取消令牌

Agent 运行时（client / ReAct 循环 / ChatMemory / 记忆 advisor / callbacks / subagent）
已于 2026-07 下沉至 [`clj-agent-client`](../clj-agent-client/README.md)（命名空间不变）；
core 对记忆与循环零感知。

## 依赖

```clojure
;; deps.edn
{:deps {im.ttalk/clj-agent-core {:local/root "../clj-agent-core"}}}
```

内部依赖：无

外部依赖：无（纯 Clojure）

## 命名空间

**协议 / 契约（端口）**

| 命名空间 | 说明 |
|---------|------|
| `im.ttalk.agent.model` | `ILLMProvider` 协议（端口，中立消息边界） |
| `im.ttalk.agent.model.message` / `.response` / `.error` | 中立消息、统一响应、错误 |
| `im.ttalk.agent.model.service` | 通用 create-service（仅凭协议包装任意 provider） |

**Kernel 原语**

| 命名空间 | 说明 |
|---------|------|
| `im.ttalk.agent.kernel` | Kernel 构建、调用、查询 API |
| `im.ttalk.agent.tool` | `deftool` 宏定义 |
| `im.ttalk.agent.advisor` | Advisor 洋葱链执行器与内置 tool filter |
| `im.ttalk.agent.context` | Context 状态管理 |
| `im.ttalk.agent.streaming` | 流式取消令牌 |

> 各厂商实现（`im.ttalk.agent.provider.*`）在 `clj-agent-provider`；Agent 运行时
> （`client`/`react`/`memory`/`advisor.memory`/`callbacks`/`subagent`/`common`）在
> `clj-agent-client`——两者都依赖本模块。

## API 参考

### 高层 Agent API

`create-agent`/`chat`/`resume` 等高层 API 见
[`clj-agent-client`](../clj-agent-client/README.md)。

### Kernel Build API

```clojure
(require '[im.ttalk.agent.kernel :as kernel])

;; 声明式构建：一次性传入 service / tools / filters / settings
(kernel/build-kernel
  {:service  my-service
   :tools    [#'get-weather #'get-time]   ;; tool var 向量
   :filters  [memory-filter logging-filter]
   :settings {:max-tool-iterations 10}})  ;; 返回 Kernel record
```

### Kernel Invoke API

kernel 只提供两个原语（均经 filter 洋葱链）；**工具调用循环不在 kernel**，在
`clj-agent-client` 模块（`im.ttalk.agent.client` 的 `create-agent` + `chat`，
或 `im.ttalk.agent.react/invoke`）。

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
(kernel/build-kernel {:service svc :filters [my-filter]}) ;; 经 :filters 挂载
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

---

<a name="english"></a>

## English

### Overview

`clj-agent-core` is the **protocol (port) layer** plus **kernel primitives**:

- **Protocol / contract**: `ILLMProvider`, neutral messages, unified response, generic Service — any jar implementing the protocol can be injected as a provider
- **Kernel**: Central orchestrator exposing `invoke-chat` / `invoke-tool` primitives (through the advisor onion chain)
- **deftool**: Macro that defines a function and generates its LLM tool schema
- **Advisor**: Onion-style around middleware executor (mirrors Spring AI Advisor)
- **Context**: Per-request shared state

The Agent runtime (client / ReAct loop / ChatMemory / memory advisor / callbacks /
subagent) moved to [`clj-agent-client`](../clj-agent-client/README.md) in 2026-07
(namespaces unchanged); core knows nothing about memory or loops.

### Key APIs

- `kernel/build-kernel {:service :tools :filters :settings}` - Declarative kernel construction
- `kernel/invoke-tool` / `kernel/invoke-chat` - Primitives through the :tool / :chat advisor chains
  (the tool-calling loop lives in `clj-agent-client`, not the kernel)
- `deftool` - Define tool with auto-generated schema
- `service/create-service` - Wrap any `ILLMProvider` into a kernel service (protocol-only)
- `ctx/create`, `ctx/get-var`, `ctx/set-var`, `ctx/set-vars`, `ctx/with-conversation-id` - Context
