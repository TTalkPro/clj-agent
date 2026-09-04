# feedbacks —— 下游宿主报上来的账

一条一个文件，`<日期>-<slug>.md`。**只记「clj-agent 这一侧该改」的**：下游自己
绕过去了但绕法很蠢、或者根本绕不过去的那些。下游自己的 bug 不进这里。

每份的格式（症状先行，病因其次，建议最后）：

| 段 | 写什么 |
|---|---|
| **症状** | 下游看到的**那一行**（报错原文 / 或者「一个字都不报」） |
| **位置** | `文件:行`，clj-agent 这一侧的 |
| **怎么撞上的** | 最短复现路径 |
| **影响面** | 谁会撞、撞了会怎样、**报不报错** |
| **建议** | 可选，下游不替仓主拍板 |

## 现有

| # | 标题 | 报的人 | 严重度 | 回没回 |
|---|---|---|---|---|
| [2026-09-04](2026-09-04-agui-drops-multimodal-content-parts.md) | AG-UI 层不认 `InputContent` 部件 —— 图片被 `(str content)` 压成 data URI 字符串喂给模型 | happy | 🔴 不报错，内容全丢 | ✅ 已修（`8977df4`，采纳报方随附的补丁）· **报方已复测**（glm-5.3-flash 上端到端验通）|
| [2026-09-04](2026-09-04-info-does-not-advertise-multimodal.md) | `/info` 不宣告多模态能力 ⇒ 客户端没法 gate 附件 UI，只能盲发 | happy | 🟡 能力位缺一格 | ✅ 已修：`run-info` 收 `:multimodal`，**装配方传**、库不猜；demo 缺省 `image=false`（M2.7 无视觉），`CLJ_AGENT_PROVIDER=zhipu CLJ_AGENT_MODEL=glm-5.3-flash CLJ_AGENT_VISION=1` 换有视觉的模型 · **报方已复测** |
| [2026-09-04](2026-09-04-threads-delete-blocked-by-cors.md) | `/threads/:id` 的 **DELETE 跨源过不去**（`Allow-Methods` 只有 GET, POST, OPTIONS）⇒ 写端点等于不存在 | happy | 🟡 配了却用不了，浏览器只说 Failed to fetch | ✅ 已修（`6d467b8`）：`Allow-Methods` 加 `DELETE`，并把 `:3000/:3002` 做成回声白名单 + `Allow-Credentials` · **报方已复测**（删除钮装回去了）|
| [2026-09-04](2026-09-04-run-finished-has-no-usage.md) | `RUN_FINISHED` 不带 `usage` ⇒ 客户端的 token 用量环恒空（keel 那边带） | happy | 🟢 不挡事，但每个接入方都要撞一次 | ✅ 已修：每次 LLM 往返记一笔累在根发射器，四条 run 终态都带 `usage[]`（含子 agent 的账）· **报方已复测**（`cachedInputTokens` 点亮了缓存段）|
| [2026-09-04](2026-09-04-agui-no-shared-state-tools-or-delta.md) | 共享状态只有**半条链** —— 模型写不了状态、没有 `STATE_DELTA`、`/info` 也不宣告 | happy | 🟡 不报错，状态页恒空 | ✅ 已修：新增 `AGUISendStateSnapshot` / `AGUISendStateDelta` 两把内联工具（闭包在本 run 的发射器上）、`STATE_DELTA` 出口、`capabilities.state`；两个静默坑按上游 `createStateEventNormalizer` 的口径在**服务端**解掉；顺带修了读面只看快照漏增量 · **报方已复测**（五点逐条，含「客户端真的打得上」）|
| [2026-09-04](2026-09-04-frontend-tool-pause-looks-like-an-approval.md) | **前端工具的暂停**被报成「需要审批」，与真审批逐字段同形 ⇒ 客户端分不出该回决策还是回结果，「拒绝」被吃成空结果、模型宣布成功 | happy | 🔴 不报错，拒绝往成功的方向被吃掉 | ✅ 已修：三处一起分开（`reason` 措辞、`responseSchema` 形状、`metadata.kind`），另修了路由里「取消 → 空字符串结果」那一刀 · **报方已复测**（三维度 + 两支各点一遍）|
| [2026-09-04](2026-09-04-reply-frontend-tool-interrupt-verified.md) | **回执**：两类 interrupt 三个维度都能判、拒绝语义回来了；报方改为**照 schema 拼载荷** | happy | — 回执 | ✅ 收到，**本轮无新账**；顺手补了自己的两处：取消时不再丢客户端给的理由、`payload` 按我们发布的 `{result}` 拆包（原先 map 会印成 Clojure 字面量） |
| [2026-09-04](2026-09-04-reply-shared-state-verified.md) | **回执**：共享状态全条链复测通过；点名记下「`state` 字段缺席时仍补 `{}` 快照」这一格**比上游与另一台参照实现都严**，别被「对齐上游」改回去 | happy | — 回执 | ✅ 收到，**本轮无新账**；该偏离已在 `event/emit-state-delta!` 的注释与 `event_test` 的断言里钉住 |
| [2026-09-04](2026-09-04-reply-what-changed-on-the-wire.md) | **回复（clj-agent → 下游）**：这一轮线上还变了哪些你们看得见的东西 —— `threadEndpoints` 恒发（那一枪可以撤了）、`capabilities` 补到六格、`TOOL_CALL_START.parentMessageId`、消息 id 跨快照稳定、SSE `id:` 到达有序、入站 `context` 真到模型 | clj-agent | — 回复 | 待下游确认 |
| [2026-09-04](2026-09-04-retest-all-four-green.md) | **回执**：四条全部复测通过（换 glm-5.3-flash 之后多模态那条才真验得出来）；顺带确认 `threadEndpoints.mutations` 与 `suggestions` 两格已可用 | happy | — 回执 | ✅ 收到，**本轮无新账** |

> 背景：happy（ClojureScript + UIx 的 AG-UI 客户端）2026-09-03/04 拿
> `examples/copilotkit/demo_server.clj`（`:4002`）做主力联调后端，逐页对着
> CopilotKit 的参照实现比。上面几条是那趟路上 **clj-agent 这一侧**留下的账。
