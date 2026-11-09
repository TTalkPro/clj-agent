# 借鉴 Keelson：Agent 运行时 / Provider / AG-UI / 编码工具（总览）

> 日期：2026-11-08。范围由用户拍板：把 Keelson（C#，`~/workspace/keelson`）
> `Keelson.Agent*` 与 `Keelson.Agui*` 里**适合直接放进 clj-agent 的能力**全部立项，
> 先设计、再开发，只开发不提交。
> 本文是总览与索引；每一块的「为什么、怎么做、不做什么」在分册里。
> 任务单在 `tasks/<编号>.md`，索引在 `TASK.md` 顶部一轮。

## 1. 为什么借 Keelson

Keelson 的 Agent 栈本身是借 Reactor、OpenAI Codex CLI、锦书等**生产级 agent** 长出来的
（见 keelson `designs/reactor-borrow.md`、`designs/codex-borrow.md`），每一项都有它自己
踩过的真实场景：长会话超窗、一次大 grep 撑爆上下文、欠费被当限流反复重试、
国产网关「200 但内容为空」、流卡死干等 5 分钟、模型对同一失败调用原样重试……
这些在 clj-agent 上同样会发生——这就是 `docs/design-principles.md` §1 要的「真实需求」。
立项时每个分册条目都写明它对应的真实场景；**找不出场景的条目不做**（见 §4）。

## 2. 分册

| 分册 | 范围 | 任务 |
|---|---|---|
| [provider-reliability.md](provider-reliability.md) | 错误细分与厂商业务码、重试增强、主备切换、流式死线、并发准入、限流快照、thinking 档位、ModelSpec、pause_turn 判定 | P-01 ～ P-10 |
| [runtime-borrow.md](runtime-borrow.md) | 上下文压缩、工具结果截断、同参失败保护 / 失败摘除、死循环提醒、声明式审批与审批链、模型审阅、参数 schema 校验、收尾闸门、分层指令、生命周期事件、消息规范化、pause_turn 续跑、子代理异步工具 | R-01 ～ R-15 |
| [agui-borrow.md](agui-borrow.md) | 审批改参数、一轮多审批、SSE 心跳、多 agent 注册、取消原因、线程元数据持久化、入参校验与 /threads 分页 | G-01 ～ G-08 |
| [tools-module.md](tools-module.md) | 新可选模块 `clj-agent-tools`：工作区读写 / 编辑 / apply_patch / exec / web_fetch / skill / notes / 语法守卫 | T-01 ～ T-07 |

编号前缀：`P` provider、`R` runtime（core + client）、`G` AG-UI、`T` tools 模块。

## 3. 分层落点（不破坏现有分层）

- **core**：错误类型与业务码契约（P-01）、重试（P-02）、ChatModel 装饰器（P-03 主备
  `chat-model.fallback`、P-05 准入 `admission`）、ModelSpec（P-08 `model.spec`）、
  pause_turn 判定（P-10）、token 估算 `tokens`、工具参数校验 `tool-args`（R-08）、
  可选 filter：`filter.tool-output`（R-02）、`filter.tool-guard`（R-03）、
  `filter.instructions`（R-10）、`filter.normalize`（R-12）、`completion-gate-filter`（R-09）。
- **client**：`filter.compaction`（R-01，要碰 memory）、`filter.dead-loop`（R-04）、
  `approval` / `approval.model`（R-05 ～ R-07）、react 的 `:on-event` + `events`（R-11）、
  pause_turn 续跑（R-13）、`subagent.tools`（R-14）；暂停 / 恢复扩成多条待审批（G-02 的底座）。
- **provider**：`common.business-codes`、`http.stream-client` 死线、`common.rate-limit`、
  `common.thinking`、`anthropic-server-tools`（P-01、P-04、P-06、P-07、P-09）。
- **agui**：`registry`、`thread-store`（+ 按需加载的 `.sqlite`）、取消原因、审批改参数等 G-*；
  HTTP 相关只进 `examples/copilotkit`（§2 框架无关）。
- **tools（新模块 `clj-agent-tools`）**：只依赖 core；**不进 core**，因为它是编码类 agent 的
  应用层工具，不是每个使用者都要（§1）。

所有新能力**缺省关闭或缺省行为不变**：以可选 filter / 装饰器 / 协议 / 选项出现，
已有调用方零改动。零新依赖。

## 4. 不搬的（及理由）

| Keelson | 理由 |
|---|---|
| `AgentJsonContext`、`ToolRegistryGeneration`、`*ItemsAttribute`、Keelson.Agent.Generators | .NET AOT / source generator 专用；`deftool` 宏已覆盖 |
| Keelson.Agent.Bitcask、Keelson.Agui.Bitcask | 依赖 C# 原生 Bitcask；clj-agent 有 timeline + sqlite |
| Keelson.Agent.Sandbox | 绑 bwrap / Landlock / Windows 原生调用；T-05 只留执行器协议让宿主接 |
| `BracketScan.cs`（1262 行按语言词法器） | 性价比低，T-07 只做 JSON / EDN / XML |
| AG-UI 事件日志持久化 | 与 `docs/agent-runtime-design.md` §8.1 / §8.2「不建第二真相源」冲突，**待用户拍板**；G-06 只持久化线程元数据 |
| `MessageSerializer` 逐字节 JSON、resx 文案、M.E.AI 适配、ASP.NET 宿主 | .NET 特有；EDN 本身稳定 |

## 5. 已持平或更好（不立项）

工具检索（中文二元组索引）、单工具超时、ToolCallingManager 可换执行器、
gate 拒绝 / 代答、return-direct 与续跑判据、暂停 / 恢复持久化、timeline fork / rollback、
MCP、A2UI、`/suggest`、AG-UI 断线续传（补不上先发 MESSAGES_SNAPSHOT）、
子 agent lane 归属与收尾、三协议栈。

## 6. 跨分册契约

- 新错误类型（P-01 定义，R-01 / P-03 消费）：`:quota-exhausted-error`（不可重试）、
  `:context-overflow-error`（不可重试，交给压缩恢复）、`:empty-completion-error`（可重试，独立预算）。
- resume 带替换参数、一次暂停多条待审批（R-05 / R-06 预留，G-01 / G-02 消费）。
- ModelSpec 的上下文窗口（P-08）供压缩预算（R-01）与截断预算（R-02）取缺省。

## 7. 缺省行为变化（升级须知）

绝大多数能力不配置就不生效，以下几处例外，已记入 CHANGELOG：

1. **P-02** 流式在首个增量之前失败，缺省重试 2 次（原 0 次）；config 里的 `:retry` 不再
   触发传输层重试，改由 ChatModel 统一做（避免 m×n 叠加）。空补全缺省补打 1 次，仍空则
   原样返回（`:empty-exhausted :throw` 才抛 `:empty-completion-error`）。
2. **P-01** 欠费类 429（`insufficient_quota` 等）不再当限流重试；普通 429 照旧重试。
   `classify-exception` 把欠费归为 `:environment`（工具循环里会暂停等人）。
3. **R-08** var 工具的参数按 schema 完整校验（类型 / enum / pattern / 范围 / items），
   错误一次报全并告诉模型怎么改；原先只查必需参数。
4. **R-13** Anthropic `pause_turn` 现在续跑（原来直接以 :pause 收尾）。
5. **G-01** AG-UI 审批答复 `{approved:false}`（不带 decision）现在一律当拒绝——原先被误判为批准。

## 8. 已拍板（2026-11-08）

- **AG-UI 事件持久化：不做。** 但事件存储做成可替换挂点 `agui.event-store/EventStore`（G-08），缺省仍是进程内环形缓冲；
  持久化由外部库实现（角色同 Keelson `Agui.Bitcask`），`docs/agent-runtime-design.md` §8.2 已加补注。
- **R-01 压缩：保持缺省不改写。** 摘要缓存与写回策略做成可替换挂点 `:store`（`CompactionStore`）/ `:write-back`（R-15）。

原则：**本库只定协议 + 带进程内缺省实现；持久化实现留给外部库**（G-06 的 `ThreadStore`、PauseStore、ChatMemory 同此）。
