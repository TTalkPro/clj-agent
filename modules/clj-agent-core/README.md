# clj-agent-core

核心模块 - Kernel 编排器、Tool 系统、Filter 中间件

[English](#english) | 中文

## 概述

`clj-agent-core` 是框架的基础模块，提供：

- **Kernel**：中央编排器，统一管理工具调用和 LLM 交互
- **deftool**：宏，同时定义函数和生成 LLM tool schema
- **Filter**：Ring-style 中间件（pre/post invocation、pre/post chat）
- **Context**：对话共享状态管理

## 依赖

```clojure
;; deps.edn
{:deps {im.ttalk/clj-agent-core {:local/root "../clj-agent-core"}}}
```

外部依赖：
- cheshire/cheshire 5.12.0
- com.taoensso/timbre 6.3.0
- http-kit/http-kit 2.8.0

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

(ctx/create)                         ;; 空 Context
(ctx/create {:user-id "u1"})         ;; 带变量
(ctx/get-var ctx :key)               ;; 获取变量
(ctx/set-var ctx :key val)           ;; 设置变量（返回新 ctx）
(ctx/get-messages ctx)               ;; 工作消息列表
(ctx/get-history ctx)                ;; 完整历史
(ctx/track-message ctx msg)          ;; 追踪消息（返回新 ctx）
```

---

<a name="english"></a>

## English

### Overview

`clj-agent-core` is the foundation module providing:

- **Kernel**: Central orchestrator for tool invocation and LLM interaction
- **deftool**: Macro that simultaneously defines functions and generates LLM tool schemas
- **Filter**: Ring-style middleware (pre/post invocation, pre/post chat)
- **Context**: Shared conversation state management

### Key APIs

- `kernel/create-kernel-builder` → `add-tools` → `add-service` → `add-filter` → `build-kernel`
- `kernel/invoke-tool` - Single tool invocation through the :tool filter chain
- `kernel/invoke-chat` - LLM call through the :chat filter chain
  (the tool-calling loop lives in `im.ttalk.agent.client`, not the kernel)
- `deftool` - Define tool with auto-generated schema
- `ctx/create`, `ctx/get-var`, `ctx/set-var`, `ctx/track-message` - Context management
