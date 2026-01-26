# clj-agent-simpleagent

高级 Agent 封装模块 - KernelAgent 和 ProcessAgent

[English](#english) | 中文

## 概述

`clj-agent-simpleagent` 提供两种开箱即用的 Agent 封装：

- **KernelAgent**：同步有状态对话，atom 管理 Context 自动累积
- **ProcessAgent**：支持敏感工具暂停/恢复审批的 Agent

两者都自动处理 Kernel 构建、Service 创建、工具调用循环和 Context 管理。

## 依赖

```clojure
;; deps.edn
{:deps {im.ttalk/clj-agent-simpleagent {:local/root "../clj-agent-simpleagent"}}}
```

内部依赖：`clj-agent-core`、`clj-agent-llm`

## 命名空间

| 命名空间 | 说明 |
|---------|------|
| `im.ttalk.agent.simpleagent.kernel-agent` | KernelAgent 实现 |
| `im.ttalk.agent.simpleagent.process-agent` | ProcessAgent 实现 |
| `im.ttalk.agent.simpleagent.common` | 共享构建逻辑 |

## API 参考

### KernelAgent

最简单的 Agent，自动维护对话状态：

```clojure
(require '[im.ttalk.agent.simpleagent.kernel-agent :as ka])

;; 创建 Agent
(def agent (ka/create-agent
             {:provider provider           ;; LLM Provider 实例
              :model "gpt-4"               ;; 模型名称（默认 "glm-4"）
              :max-tokens 4096             ;; 最大 token（默认 4096）
              :temperature 0.7             ;; 温度（可选）
              :system-prompt "你是助手"     ;; 系统提示（可选）
              :tools my-tools              ;; tool var 向量
              :filters [my-filter]         ;; Filter 列表（可选）
              :max-iterations 10           ;; 最大工具调用次数（默认 10）
              :kernel pre-built-kernel}))  ;; 或直接传入 Kernel（跳过构建）

;; 对话
(ka/chat agent "你好")
;; => {:text "你好！有什么..." :tool-calls-made []}

(ka/chat agent "北京天气？")
;; => {:text "北京今天..." :tool-calls-made [{:name :get-weather ...}]}

;; 带选项的对话
(ka/chat agent "消息" {:system-prompt "临时角色"
                       :max-iterations 5
                       :tool-choice :required})

;; 查询状态
(ka/get-context agent)    ;; 当前 Context
(ka/get-history agent)    ;; 完整对话历史
(ka/get-messages agent)   ;; 工作消息列表

;; 重置
(ka/reset! agent)         ;; 清空 Context，重新开始
```

### ProcessAgent

遇到 `:sensitive` 工具时自动暂停，等待审批后恢复：

```clojure
(require '[im.ttalk.agent.simpleagent.process-agent :as pa])

;; 创建 Agent（配置同 KernelAgent，额外支持 :on-pause 回调）
(def agent (pa/create-process-agent
             {:provider provider
              :model "gpt-4"
              :tools my-tools
              :on-pause (fn [{:keys [reason pending-tool]}]
                          (println "暂停:" reason)
                          (println "待审批:" (:name pending-tool)))}))

;; 对话
(let [result (pa/chat agent "删除 /tmp/test.txt")]
  (case (:status result)
    :completed (println "完成:" (:text result))
    :paused    (println "暂停:" (:pause-reason result))))
;; => {:status :paused
;;     :pause-reason "需要审批: delete-file"
;;     :pending-tool {:name "delete-file" :args {:path "/tmp/test.txt"} ...}
;;     :tool-calls-made [...]}

;; 检查是否暂停
(pa/paused? agent)  ;; => true

;; 恢复执行
(pa/resume agent "approved")   ;; 批准执行工具
(pa/resume agent "rejected")   ;; 拒绝（告知 LLM 用户拒绝了）

;; 查询和重置
(pa/get-context agent)
(pa/reset! agent)
```

### 共享构建逻辑

```clojure
(require '[im.ttalk.agent.simpleagent.common :as common])

;; 根据 opts 构建 Kernel（内部调用）
(common/build-kernel {:provider p :model "gpt-4" :tools my-tools})

;; 获取或构建 Kernel（有 :kernel 直接用，否则构建）
(common/ensure-kernel opts)

;; 批量执行工具调用
(common/execute-tools kernel tool-calls context)
;; => {:results [...] :context ctx :records [...]}
```

## 工具配置

`:tools` 参数接受 tool var 向量：

```clojure
;; 定义工具
(deftool get-weather "获取天气" [[city :string "城市"]] ...)
(deftool calculate "计算" [[expr :string "表达式"]] ...)

;; 创建工具集
(def my-tools [#'get-weather #'calculate])

;; 传给 Agent
(ka/create-agent {:provider p :tools my-tools ...})
```

## Sensitive 工具

使用 `{:sensitive true}` 标记的工具在 ProcessAgent 中触发审批：

```clojure
(deftool dangerous-action
  "执行危险操作"
  [[target :string "目标"]]
  {:sensitive true}
  (do-dangerous-thing target))
```

KernelAgent 不检查 sensitive 标记，所有工具直接执行。

---

<a name="english"></a>

## English

### Overview

`clj-agent-simpleagent` provides two ready-to-use Agent wrappers:

- **KernelAgent**: Synchronous stateful conversations with auto-accumulating context
- **ProcessAgent**: Agent with pause/resume for sensitive tool approval

### Key APIs

**KernelAgent**: `create-agent`, `chat`, `reset!`, `get-context`, `get-history`, `get-messages`

**ProcessAgent**: `create-process-agent`, `chat`, `resume`, `paused?`, `reset!`, `get-context`

### Differences

| Feature | KernelAgent | ProcessAgent |
|---------|-------------|--------------|
| State management | atom-based auto-accumulate | atom + pause state |
| Sensitive tools | Execute directly | Pause for approval |
| Response format | `{:text :tool-calls-made}` | `{:status :text :pause-reason :pending-tool ...}` |
| Resume support | No | Yes (`resume agent decision`) |
