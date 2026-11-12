# 观澜（chartroom）移植 Agent 层时撞到的四处缺口（2026-11-11）

> 来源：chartroom 后端 C# → Clojure（D57 / tasks/AK-01.md 的 AK-08）。观澜侧都已**本仓最小实现顶上**，这里只报告、不催。

## ① 没有「无状态续跑」形态的 AG-UI 成品 agent（Keelson ChatAguiAgent 的对位件）

观澜前端（happy 的 agui 客户端）按 Keelson.Agui 的线协议写：
- interrupt id = `"tc:" + toolCallId`，`reason = "tool_call"`，`responseSchema = {approved, editedArgs}`（required `approved`）；
- 判停时**先** `MESSAGES_SNAPSHOT`（输入原样 + 这一趟的消息）**再** `RUN_FINISHED{outcome:interrupt}`；
- **续跑无状态**：未决调用 = messages 里没有 tool 结果的 toolCalls；`resume[]` 对着它们办（批准 / editedArgs 整体替换 / 拒绝文案
  「用户拒绝了这次调用：…」），错误码 `INTERRUPT_REQUIRED` / `INTERRUPT_INCOMPLETE` / `INTERRUPT_UNKNOWN` / `INTERRUPT_BAD_RESUME`。

clj-agent-agui 的 `runtime` 是有状态暂停（state-atom / PauseStore）+ interrupt id 是裸 tool-call id —— 两者线上不兼容。
观澜的做法：`chartroom.agent.agui.{chat-agent,run,convert,events}` 照 Keelson 移植驱动与 run state，循环本体用 `react/invoke` +
`chat-client` + filter。**建议**：clj-agent-agui 增加一个 Keelson 兼容的无状态 agent（或在 codec 里可选 `tc:` 前缀 + MESSAGES_SNAPSHOT）。

## ② `react/invoke` 不带 memory 时每轮只发 delta

没挂 memory-filter 时，下一轮请求只有「assistant + 工具结果」这一截（`next-delta`），历史丢了。观澜每个 run 现造一个 `in-memory-store`
顶上。**建议**：无 memory 时循环自己累积完整 transcript（或在 docstring 写明「必须挂 memory」）。

## ③ 内联工具拿不到 `:sensitive`（`build-func-def` 只看 var 的元数据）

`tool-registry/build-func-def` 对内联工具恒给 `:sensitive false`，`:on-pause` 的声明式暂停对内联工具失效。观澜改用自己的 gate
（`:tool-gate` + 判停表）。**建议**：内联 map 的 `:sensitive` 也汇进 func-def（与 `:serial` / `:timeout` 同一处）。

## ④ `tool-guard` 只有一套计数

Keelson 的 FailureDisableAdvisor 把**业务失败**（reported：连续 5 次提醒「别原样重试」）与**系统错误**（raised：连续 3 次本回合门禁）
分开；clj-agent 的 tool-guard 一个槽（`:tool-guard/state`）一套阈值。观澜只让系统错误计数（`:classify` 把业务失败判 `:ignore`），
业务失败的提醒缺席。**建议**：classify 允许回 `:failure/:reported` 两类，各自阈值。

另：单次调用的 `:temperature` 等选项仍被 DefaultChatModel 丢掉（锦书反馈 ③，未修）—— 观澜的判据只能靠提示词认出 A-19 的摘要调用。

## clj-agent 回复（2026-11-12，未提交）

②③④ 与「另」已修；① 不照做：clj-agent 坚持有状态续跑，请观澜像锦书一样接它（见下）。

- **② 不挂 memory 时带完整对话**：`react/invoke` 没有 memory filter 时，第二轮起请求 = 本轮发出的消息（不含临时提醒）
  + assistant(tool_calls) + 工具结果；pause_turn 续跑同理。观澜不必再为每个 run 现造 `in-memory-store`。
  暂停后的 resume 仍靠 memory 拼历史（loop-state 里不存对话），已写进 `invoke` 的 docstring。
- **③ 内联工具的 `:sensitive`**：`build-func-def` 两个来源都认（var 的 `:tool/sensitive` / 内联 map 的 `:sensitive`），
  `:on-pause` 声明式暂停与审批策略的 `:sensitive?` 对内联工具生效。
- **④ tool-guard 两本账**（同 Keelson FailureDisableAdvisor）：`:classify` 可回
  - `:raised`：系统错误，计入门禁（`:disable-threshold`，缺省 3），同时记「任意失败」；
  - `:reported`：业务失败，只记「任意失败」与同参保护，不进门禁。`:report-threshold`（缺省 5）：连续任意失败到 n 次、
    碰上业务失败时结果换成「别原样重试」提醒，然后清零；文案可经 `:report-note` 覆盖；
  - 成功两本账都清零；缺省 classify（只回 `:failure` / `:success`）行为不变。
- **另（单次 `:temperature` 等被丢）**：`DefaultChatModel` 的 `build-call-config` 改成 config ⊕ 单次选项，ChatModel 自己的键
  （`chat-model/non-wire-option-keys`：`:retry`、重试钩子、`:admission`、`:cancel-token`、`:on-token`）不下发；同步 / 异步 /
  流式同一处。观澜判 A-19 摘要调用不必再靠提示词。
- **① 无状态续跑的 AG-UI agent：不做，clj-agent 保持有状态**。理由：
  - 续跑要接上的不只是对话：剩余轮数、已调用记录、tool-guard 计数、工具上下文都在暂停快照里；无状态续跑只能从
    messages 推「哪几件没办」，其余全部从零开始。
  - 以服务端为准：续跑不依赖前端带回来的历史是否完整、是否被改过。
  - 重启不丢：暂停快照经 `PauseStore` 挂点落盘即可（观澜已有 Bitcask，Keelson 的 `clj-bitcask-agent` 有现成的 PauseStore）。

  **请观澜像锦书一样，直接接 clj-agent-agui 的有状态 runtime**，删掉 `chartroom.agent.agui.{chat-agent,run,convert,events}`
  这份自维护的 Keelson 移植，不再两套并行。前端侧要改的：interrupt id 按 clj-agent 发的原样回送（裸 tool-call id，不拼
  `tc:`）；续跑的请求体里服务端只读 `resume[]` 与最后一条 user 消息（历史在服务端），前端要对齐历史走 `connect`
  （`MESSAGES_SNAPSHOT`），不依赖 RUN_FINISHED 之前的快照。接的过程中如果发现 clj-agent 的有状态协议缺什么（字段、事件次序、错误码），按缺口单独报，在 clj-agent 里补。
- 测试：core 加了单次选项递到底（同步 / 异步、`:tool-choice :none`）、内联 `:sensitive`、两本账三组；client 原
  「无 memory 只带 assistant + tool」那条改成完整 transcript 的断言。全量 9 个模块 0 失败，`bb check-docs` 通过。
