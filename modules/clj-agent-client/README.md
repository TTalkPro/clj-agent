# clj-agent-client

Agent 运行时模块（2026-07 自 clj-agent-core 下沉，命名空间不变）：

- **client**：高层 Agent（`create-agent`/`chat`/`chat-stream`/`resume`），内置按 conversation-id 的记忆
- **react**：ReAct 工具调用循环（invoke / resume / execute-batch / heal）
- **Memory**：ChatMemory 协议 + in-memory / windowed / SQLite store
- **advisor.memory**：按 conversation-id 串历史的记忆 advisor（挂进 kernel 洋葱链）
- **callbacks**：Agent 生命周期回调（on-llm-call / on-tool-call / on-interrupt / …）
- **subagent**：子 agent 委派（manager 注册表 + delegate 工具）
- **common**：共享 Kernel 构建逻辑

core 由此对「记忆 / 循环」零感知（onion-filter 设计验收标准：core 内零 ChatMemory）。

## 依赖

```clojure
;; deps.edn
{:deps {im.ttalk/clj-agent-client {:local/root "../clj-agent-client"}}}
```

内部依赖：`im.ttalk/clj-agent-core`（协议/契约 + kernel 原语）

外部依赖：
- com.taoensso/timbre 6.3.0（日志）
- com.github.seancorfield/next.jdbc 1.3.939（memory/sqlite，按需）
- org.xerial/sqlite-jdbc 3.45.1.0（memory/sqlite，按需）

## 命名空间

| 命名空间 | 职责 |
|---------|------|
| `im.ttalk.agent.client` | 高级 Agent API（create-agent / chat / chat-stream / resume） |
| `im.ttalk.agent.react` | ReAct 工具调用循环 |
| `im.ttalk.agent.memory` / `.memory.sqlite` | ChatMemory store |
| `im.ttalk.agent.advisor.memory` | 记忆 advisor（around-chat filter） |
| `im.ttalk.agent.callbacks` | 生命周期回调 |
| `im.ttalk.agent.subagent.manager` / `.delegate` | 子 agent 体系 |
| `im.ttalk.agent.common` | 共享 Kernel 构建逻辑 |

## 测试

```bash
clojure -M:test
```
