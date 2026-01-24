# clj-agent-core

核心模块 - Kernel 编排器、Plugin 系统、Filter 中间件、Process 运行时

[English](#english) | 中文

## 概述

`clj-agent-core` 是框架的基础模块，提供：

- **Kernel**：中央编排器，统一管理工具调用和 LLM 交互
- **deftool**：宏，同时定义函数和生成 LLM tool schema
- **Plugin**：工具集组织和管理
- **Filter**：Ring-style 中间件（pre/post invocation、pre/post chat）
- **Context**：对话共享状态管理
- **Process Runtime**：基于 core.async 的事件驱动工作流引擎

## 依赖

```clojure
;; deps.edn
{:deps {im.ttalk/clj-agent-core {:local/root "../clj-agent-core"}}}
```

外部依赖：
- org.clojure/core.async 1.6.681
- cheshire/cheshire 5.12.0
- com.taoensso/timbre 6.3.0
- http-kit/http-kit 2.8.0

## 命名空间

| 命名空间 | 说明 |
|---------|------|
| `im.ttalk.agent.core.kernel.core` | Kernel 构建、调用、查询 API |
| `im.ttalk.agent.core.kernel.tool` | `deftool` 宏定义 |
| `im.ttalk.agent.core.kernel.plugin` | `defplugin` 宏和 Plugin 管理 |
| `im.ttalk.agent.core.kernel.filter` | Filter 创建和内置 Filter |
| `im.ttalk.agent.core.kernel.context` | Context 状态管理 |
| `im.ttalk.agent.core.kernel.types` | ToolCall、Response 数据结构 |
| `im.ttalk.agent.core.kernel.service` | Service 抽象接口 |
| `im.ttalk.agent.core.kernel.process.builder` | Process 规格构建器 |
| `im.ttalk.agent.core.kernel.process.runtime` | Process 事件循环运行时 |
| `im.ttalk.agent.core.kernel.process.event` | 事件创建和路由 |
| `im.ttalk.agent.core.kernel.process.step` | Step 激活和生命周期 |
| `im.ttalk.agent.core.kernel.process.snapshot_manager` | 快照管理 |
| `im.ttalk.agent.core.http.client` | HTTP 客户端工具 |

## API 参考

### Kernel Build API

```clojure
(require '[im.ttalk.agent.core.kernel.core :as kernel])

;; 创建 Builder
(kernel/create-kernel-builder)
(kernel/create-kernel-builder {:max-tool-iterations 10})

;; 配置 Builder
(kernel/add-plugin builder plugin)      ;; 添加 Plugin
(kernel/add-service builder service)    ;; 设置 LLM Service
(kernel/add-filter builder filter-def)  ;; 添加 Filter

;; 构建 Kernel
(kernel/build-kernel builder)           ;; 返回 Kernel record
```

### Kernel Invoke API

```clojure
;; 调用工具函数（经过 pre/post invocation Filter）
(kernel/invoke-tool kernel :fn-name {:arg "val"} context)
;; => {:value result :context updated-ctx}

;; LLM 调用（经过 pre/post chat Filter，不含工具循环）
(kernel/invoke-chat kernel messages opts)
;; => {:response {:text "..." :tool-calls [...]} :context ctx}

;; 工具调用循环（主入口，自动驱动 LLM + Tool 交互直到文本响应）
(kernel/invoke kernel messages opts)
;; => {:response final-response :context ctx :tool-calls-made [...]}
```

`invoke` opts 参数：

| Key | 说明 | 默认值 |
|-----|------|--------|
| `:context` | Context 对象 | 空 Context |
| `:system-prompts` | 系统提示消息列表 | `[]` |
| `:max-iterations` | 最大循环次数 | 10 |
| `:tool-choice` | `:auto`/`:none`/`:required` | `:auto` |

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

### Plugin API

```clojure
(require '[im.ttalk.agent.core.kernel.plugin :as kp])

;; 宏定义
(kp/defplugin weather-tools "天气工具" get-weather get-forecast)

;; 函数式 API
(kp/create-plugin :name "description" [#'get-weather #'get-forecast])

;; 查询
(kp/get-schemas plugin)           ;; 获取所有 tool schema
(kp/get-tool-var plugin :name)    ;; 获取 tool var
(kp/list-function-names plugin)   ;; 列出函数名
(kp/execute-tool plugin :name args ctx)  ;; 执行工具
```

### Filter API

```clojure
(require '[im.ttalk.agent.core.kernel.filter :as filters])

;; 创建 Filter
(filters/create-filter :name :pre-invocation handler-fn :priority 10)

;; 类型: :pre-invocation :post-invocation :pre-chat :post-chat

;; 内置 Filter
filters/logging-pre-filter
filters/logging-post-filter
filters/error-handling-filter
(filters/timeout-filter 5000)
filters/approval-filter

;; 批量应用
(filters/apply-pre-invocation-filters filters func-def args context)
(filters/apply-post-invocation-filters filters func-def args result context)
(filters/apply-pre-chat-filters filters messages context)
(filters/apply-post-chat-filters filters response context)
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

### Process Runtime API

```clojure
(require '[im.ttalk.agent.core.kernel.process.builder :as process])
(require '[im.ttalk.agent.core.kernel.process.runtime :as runtime])

;; 构建 Process 规格
(-> (process/builder :my-process)
    (process/add-step {:id :step1
                       :init (fn [] {:count 0})
                       :on-activate (fn [state ctx event] ...)
                       :on-terminate (fn [state ctx] ...)})
    (process/on-event :start :step1 :input)
    (process/on-quiescent (fn [{:keys [reason context]}] ...))
    (process/build))

;; 同步执行
(runtime/run-process spec {:input data})

;; 异步执行（返回 channel）
(runtime/start-process spec {:input data})

;; 恢复暂停的 Process
(runtime/resume runtime resume-data)
```

Step 生命周期函数：

| 函数 | 调用时机 | 调用次数 |
|------|---------|---------|
| `init` | 初始化 Step 状态 | 1 次 |
| `can-activate?` | 检查是否可激活 | 多次 |
| `on-activate` | 主业务逻辑 | 多次 |
| `on-resume` | 从暂停恢复 | 可选 |
| `on-terminate` | 清理资源 | 1 次 |

---

<a name="english"></a>

## English

### Overview

`clj-agent-core` is the foundation module providing:

- **Kernel**: Central orchestrator for tool invocation and LLM interaction
- **deftool**: Macro that simultaneously defines functions and generates LLM tool schemas
- **Plugin**: Tool collection organization and management
- **Filter**: Ring-style middleware (pre/post invocation, pre/post chat)
- **Context**: Shared conversation state management
- **Process Runtime**: core.async-based event-driven workflow engine

### Key APIs

- `kernel/create-kernel-builder` → `add-plugin` → `add-service` → `add-filter` → `build-kernel`
- `kernel/invoke-tool` - Single tool invocation through Filter pipeline
- `kernel/invoke-chat` - LLM call through chat Filters
- `kernel/invoke` - Tool-calling loop (main entry point)
- `deftool` - Define tool with auto-generated schema
- `defplugin` - Organize tools into named collections
- `ctx/create`, `ctx/get-var`, `ctx/set-var`, `ctx/track-message` - Context management
- `process/builder`, `runtime/run-process` - Event-driven workflows
