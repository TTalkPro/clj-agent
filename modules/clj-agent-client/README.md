# clj-agent-client

Agent 运行时模块（2026-07 自 clj-agent-core 下沉，命名空间不变）：

- **client**：高层 Agent（`create-agent`/`chat`/`chat-stream`/`resume`），内置按 conversation-id 的记忆
- **react**：ReAct 工具调用循环（invoke / resume / execute-batch / heal），工具批次 MapReduce 化（并行 + 屏障折叠）
- **Memory**：ChatMemory 协议 + in-memory / windowed / SQLite store
- **advisor.memory**：按 conversation-id 串历史的记忆 advisor（挂进 kernel 洋葱链）
- **pause**：HITL 暂停态持久化（PauseStore 协议 + in-memory / SQLite）——进程重启后同 conversation-id 重建 agent 即可 resume
- **timeline**：对话时间线与多分支（BranchStore 协议；fork-as-new-conversation + 血缘记录）
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
| `im.ttalk.agent.memory` / `.memory.sqlite` | ChatMemory store（`ChatMemory` 协议） |
| `im.ttalk.agent.advisor.memory` | 记忆 advisor（around-chat filter） |
| `im.ttalk.agent.pause` | HITL 暂停态持久化（`PauseStore` 协议；`in-memory-pause-store` / `sqlite-pause-store`） |
| `im.ttalk.agent.timeline` | 时间线与多分支（`BranchStore` 协议；`fork!` / `rollback!` / `lineage` / `ancestry` / `prune!`） |
| `im.ttalk.agent.callbacks` | 生命周期回调 |
| `im.ttalk.agent.subagent.manager` / `.delegate` | 子 agent 体系 |
| `im.ttalk.agent.common` | 共享 Kernel 构建逻辑 |

设计文档：`docs/agent-loop-concurrency-design.md`（§9 工具批次 MapReduce、§11 暂停
持久化、§12 timeline）、`docs/hitl-timeline-design.md`、`docs/filter-chain-design.md`。

## Agent 层契约（易踩）

- **`create-agent` 不接受 `:filters`**——agent 层只暴露 `:callbacks`（传了会被
  忽略并 warn）。要挂 kernel filter，请自建 kernel 后以 `:kernel` 传入；此时
  memory store 会复用该 kernel 上 memory-filter 绑定的实例。
- **`on-tool-call` / `on-tool-result` 的 `tool-name` 是字符串**（不是 keyword）。
  拿 keyword 去 `=` 比较会永不相等——`on-tool-call` 的中断判断于是**静默失效**
  （曾真的踩过：`examples/minimax_agent_live_test.clj` 的中断场景因此假装通过）。
- **gate 只在配置了 `:on-tool-call` 时启用**；返回 `{:interrupt reason}` 触发暂停。
- **ToolContext 对工具只读**（v0.3）：工具写状态走返回值 `{:result r :writes {k v}}`，
  在批次屏障处按 tool-call 原序经 `:state-slots` 槽 reducer 折叠；失败/超时/被拒
  的调用 `:writes` 不生效。

## 测试

```bash
clojure -M:test
```
