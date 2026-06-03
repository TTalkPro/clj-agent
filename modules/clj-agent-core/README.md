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

| 命名空间 | 说明 |
|---------|------|
| `im.ttalk.agent.core.kernel` | Kernel 构建、调用、查询 API |
| `im.ttalk.agent.core.kernel.tool` | `deftool` 宏定义 |
| `im.ttalk.agent.core.kernel.filter` | Filter 创建和内置 Filter |
| `im.ttalk.agent.core.kernel.context` | Context 状态管理 |
| `im.ttalk.agent.core.kernel.types` | ToolCall、Response 数据结构 |
| `im.ttalk.agent.core.kernel.service` | Service 抽象接口 |
| `im.ttalk.agent.core.http.client` | HTTP 客户端工具 |

## API 参考

### Kernel Build API

```clojure
(require '[im.ttalk.agent.core.kernel :as kernel])

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

kernel 只提供两个原语（均经 advisor 洋葱链）；**工具调用循环不在 kernel**，已下沉到
`im.ttalk.agent.simpleagent`（`create-agent` + `chat`，或 `simpleagent.loop/invoke`）。

```clojure
;; 调用工具函数（经过 :phase :tool advisor 链）
(kernel/invoke-tool kernel :fn-name {:arg "val"} context)
;; => {:value result :context updated-ctx}

;; LLM 调用（经过 :phase :chat advisor 链，不含工具循环）
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
(require '[im.ttalk.agent.core.kernel.tool :refer [deftool]])

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

### Advisor API（洋葱式 around，对标 Spring AI Advisor）

```clojure
(require '[im.ttalk.agent.core.kernel.filter :as filters])

;; 创建 Advisor —— 根抽象 advise-call(req, chain)；before/after 为糖
(filters/create-advisor :name :chat :order 10
  :advise-call (fn [req chain] ... (chain req') ...))   ;; 完整 around
(filters/create-advisor :name :tool :order 10
  :before (fn [req] req') :after (fn [resp] resp'))     ;; 改写糖

;; phase: :chat（invoke-chat，terminal 调 LLM）| :tool（invoke-tool，terminal 调函数）
;; order: 越小越靠外层（最先 before、最后 after）；不调 chain 即短路

;; 内置 tool advisor
filters/logging-tool-advisor
(filters/timeout-tool-advisor 5000)   ;; around：超时返回提示结果
(filters/approval-tool-advisor)       ;; around：敏感工具人工审批，拒绝则短路

;; 挂载 + 执行
(kernel/add-filter builder my-advisor) ;; 加入 builder
(filters/build-chain advisors terminal) ;; 折成洋葱，返回 (fn [req] -> resp)
```

### Context API

```clojure
(require '[im.ttalk.agent.core.kernel.context :as ctx])

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
- `kernel/invoke-tool` - Single tool invocation through the :tool advisor chain
- `kernel/invoke-chat` - LLM call through the :chat advisor chain
  (the tool-calling loop lives in `im.ttalk.agent.simpleagent`, not the kernel)
- `deftool` - Define tool with auto-generated schema
- `ctx/create`, `ctx/get-var`, `ctx/set-var`, `ctx/track-message` - Context management
