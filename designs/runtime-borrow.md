# Agent 运行时借鉴 Keelson（runtime-borrow）

> 状态：✅ 已实施（2026-11-08；逐项落地情况见 `tasks/R-01.md` … `tasks/R-14.md`）
>
> 出处：Keelson `src/Keelson.Agent/Advisors/*`、`Tools/*`、`Instructions/*`、`AgentEvents.cs`、`AgentMetrics.cs`、
> `Providers/PauseTurn.cs`、`Subagent/SubagentTools.cs`；动机参照 keelson `designs/reactor-borrow.md`、`designs/codex-borrow.md`
> （两者又分别借自 Reactor 与 OpenAI Codex CLI）。
>
> 原则：全部按 [design-principles.md](../docs/design-principles.md) 过一遍。**缺省行为不变**——每项都是可选 filter / 可选
> opts / 纯函数，不挂就与今天逐字相同；**零新依赖**（core 仍零依赖，JSON 解析等自写最小实现）。

## 0. 总览

| # | 能力 | 落点 | 形态 | 缺省 |
|---|---|---|---|---|
| R-01 | 摘要式上下文压缩 | client `im.ttalk.agent.filter.compaction` | `:chat` filter | 不挂 |
| R-02 | 工具结果截断 + 全文另存 | core `im.ttalk.agent.filter.tool-output` | `:tool` filter + 纯函数 | 不挂 |
| R-03 | 同参失败保护 + 失败摘除 | core `im.ttalk.agent.filter.tool-guard` | `:tool` filter | 不挂 |
| R-04 | 死循环提醒 | client `im.ttalk.agent.filter.dead-loop` | `:iteration`+`:turn` filter；react 新增「临时提醒」挂点 | 不挂 |
| R-05 | 声明式审批策略 + 会话审批缓存 | client `im.ttalk.agent.approval` | 纯函数 → gate / `:tool` filter | 不配 |
| R-06 | 审批链 + 拒绝熔断 + 审批超时 | 同上 | 审批者组合子 | 不配 |
| R-07 | 模型审阅者 | client `im.ttalk.agent.approval.model` | 审批者 | 不配 |
| R-08 | 按 schema 完整校验工具参数 | core `im.ttalk.agent.tool-args` + `tool.clj` | 内建（var 工具） | **开**（见 §8 兼容性） |
| R-09 | 收尾闸门 | core `im.ttalk.agent.filter` | `:turn` filter（推广 validation） | 不挂 |
| R-10 | 分层指令文件 | core `im.ttalk.agent.filter.instructions` | `:chat` filter | 不挂 |
| R-11 | 生命周期事件 + 指标 | client `im.ttalk.agent.events` + react | `:on-event` 回调 | 不配 |
| R-12 | 消息规范化补全 | core `im.ttalk.agent.filter.normalize` | `:chat` filter + 纯函数 | 不挂 |
| R-13 | pause_turn 续跑 | client react | 内建 | **开**（原先直接收尾） |
| R-14 | 子代理异步工具组 | client `im.ttalk.agent.subagent.tools` | 内联工具组 | 不配 |

跨项新增的三个**框架挂点**（都在 react，归本 agent）：

1. **临时提醒**（R-04 / R-03 用）：`IterationRequest` 可带 `:notes [字符串…]`，只附在**本轮**发给模型的消息末尾
   （`<system-reminder>` 包装的一条 user 消息），**不进 ChatMemory**。实现：react 把 notes 钉进本次 chat 请求的
   context（`:loop/notes`），memory filter 展开历史**之后**追加；没挂 memory filter 时 react 直接拼进本轮 delta。
2. **工具侧请求停回合**：工具 / `:tool` filter 的 `:writes` 写 `{:loop/stop {:reason .. :by ..}}`，批次屏障折叠后
   react 看到即以 `:completed` 收尾（`:stopped-by` / `:stop-reason`），本批工具结果照 return-direct 的路子补落库。
3. **框架槽 reducer**：`ctx/framework-slots`——框架自己用的 context 槽（`:tool-guard/state`）带内建 reducer
   （两层 merge），不依赖用户的 `:state-slots`。这些键同时登记进 `ctx/framework-keys`，AG-UI 状态快照自动滤掉。

`IterationResult` 的 `:continue` 结果多带 `:calls`（本轮 tool-call）与 `:errors`（本轮失败），供 `:iteration` filter
在响应侧看到「这一轮做了什么」（R-04 要指纹）。只增键，drive 只读 `:messages`/`:context`，向后兼容。

## 1. R-01 摘要式上下文压缩

**动机**：长会话（AG-UI 多轮、工具结果大）迟早撞上窗口。今天只有 `memory/windowed`（按条数硬切、不看 token、不留摘要），
撞窗后 provider 抛错整轮失败。Keelson A-19/A-29 的做法：按预算比例触发，先「微压缩」（旧工具结果正文换占位），
不够再把前缀换成一条摘要；摘要失败退化为丢最老；provider 报超长时压一次重发。

**Keelson 出处**：`Advisors/CompactionAdvisor.cs`（`CompactAsync` / `MicroCompact` / `FindSafeCut` / `ForbiddenCuts` /
`DropOldestToFit` / `RecoverAsync` / `Calibrated`）、`Advisors/CompactionBudget.cs`（三段阈值规则）。

**现状**：`memory.clj:47-88` `windowed` 只按条数；`model/response.clj:150` 已有 `:context-window-exceeded`（GLM）；
`:context-overflow-error` 由 provider agent 在 `model/error.clj` 新增（契约）。无 token 估算。

**方案**：`(compaction/compaction-filter opts)` → `:chat` filter，**注册在 memory filter 之后**（看到的是展开后的完整历史）。

- token 估算（core `im.ttalk.agent.tokens`）：ASCII 每 4 字符 1 token、非 ASCII 每字符 1 token（CJK 偏保守）、每条消息
  +4 开销；工具调用算名字+参数 `pr-str`；图片/文件部件按固定值（1844 / 3000，同 Keelson）；system prompt 与工具 schema
  也计入。**校准**：记住上次送出的消息与 provider 报的 `:input-tokens`，本轮以它为前缀时取 `max(粗估, 上报+新增粗估)`。
- 阈值 = `CompactionBudget.Threshold` 同款三段规则（`:context-window` 缺省 100k，`:trigger-ratio` 0.5，小窗口抬到 0.75，
  地板 64k，顶出窗口取 0.85）。`(compaction/threshold budget)` 纯函数公开。
- 流程：超阈值 → **微压缩**（保留最近 `:micro-keep-recent` 5 条工具结果，更早的正文换 `[旧工具结果已清理]`，保留的截到
  `:micro-max-chars` 8000，头尾保留，复用 R-02 的 `truncate-middle`）→ 仍超 → 在**安全切点**（不劈开 assistant 调用与
  其结果）把 `[head, cut)` 换成一条摘要（user 角色、`:compaction/summary true` 标记，`<context-summary>` 包装）→ 摘要后
  仍超则继续丢最老。摘要器 `(fn [transcript] -> 字符串|nil)` 可注入；`(compaction/chat-model-summarizer cm)` 用一个
  ChatModel 生成。没有摘要器 / 无可摘前缀 / 摘要失败 → 微压缩 + **丢最老**（按安全段丢）。
- 连续失败：同一会话摘要器连续失败 `:max-consecutive-failures`（3）次 → **该会话停用摘要器**（仍做微压缩+丢最老，
  不会因为停用而撞窗）。成功一次清零。
- **超长恢复**：`(chain req)` 抛出/返回被判定为超长 → 强压（阈值 = min(正常阈值, 本次送出量×0.75)）后**重发一次**。
  判定函数 `:overflow?` 可注入，缺省：异常（含 cause 链）ex-data `:type` 为 `:context-overflow-error`，或响应
  `finish-reason` 为 `:context-window-exceeded`。压不动（消息面没变）则原错照抛。走 `flt/fcatch`/`fbind`，异步路径同样成立。
- **与 memory store 写回的关系**（核心取舍）：**缺省不写回**。store 仍是完整历史的唯一真相——AG-UI 的
  `MESSAGES_SNAPSHOT`、timeline、审计都读它，Keelson 那种就地改写 live history 在这里会让前端历史凭空少一截。
  压缩是**请求视图**，靠一份**摘要缓存**避免每轮重摘：filter 按 conversation-id 记 `{:prefix-n n :prefix-hash h :summary msg}`，
  下一轮历史前 n 条 hash 不变就直接换成缓存的摘要（O(n) 比对、零 LLM 调用）。再超阈值时在**视图上**继续压（旧摘要进
  新摘要的素材，逐次合并），缓存换成新的前缀长度。进程重启缓存丢失 → 首轮重摘一次。要「有界存储」的用户给
  `:write-back? true`：压缩后 `mem-clear` + `mem-add` 改写 store（消息 `:id` 保留）；代价自负（前端历史被改写）。
  缓存条目按最近使用淘汰（`:max-conversations` 1024）。
- 观察：`:on-compaction (fn [{:keys [conversation-id kind before after dropped]}])`，kind ∈ `:micro` `:summary` `:drop`
  `:overflow-recovery`。**不往 context 写统计**（会污染工具状态与 AG-UI 状态快照）。

**不做**：Keelson 的「被切前缀里的真人原话另外保留」（KeepUserMessages，后续有需求再加）；主备模型窗口切换（依赖
provider 层 fallback 信息，clj-agent 没有）；`BeforeCompaction` 钩子（`:on-compaction` 已够观察）。

**原则**：§1——撞窗是真实故障（长会话必现），用户自己写要重做估算/切点/恢复，代价高；§2 无关；§3——filter 只改它
声明要改的（发给模型的消息），不碰执行环境；缓存是 filter 私有闭包，不跨 ChatClient。

**测试**：估算单调性/CJK；阈值三段规则；安全切点不劈对；微压缩幂等；摘要替换 + 缓存命中第二轮不调摘要器；摘要失败→丢最老
→连续 3 次停用；超长异常→压缩重发一次、不可压时原错照抛；`:context-window-exceeded` 响应恢复；memory 中 store 不被改写
（缺省）与 `:write-back?` 改写；异步路径（`invoke-async`）同样恢复。

### 1.x 可替换挂点（R-15，2026-11-08 用户拍板）

缺省**不改写** ChatMemory、摘要缓存只在进程内——这两条不变；但两处逻辑都做成可替换，外部库可直接提供新实现：

- `:store` → `CompactionStore` 协议（`cs-get` / `cs-put!` / `cs-delete!`），存每会话的**可持久部分**
  `{:cache {:prefix-n :prefix-hash :summary-msg :head} :failures n}`（EDN 可往返）。缺省 `in-memory-store`
  （LRU，`:max-conversations`）。外部实现落盘后，重启不必重摘；缓存对不上（前缀 hash 变）只会重摘，不出错。
  进程内瞬时量（上次送出的视图、provider 报的 input token）不经过它。存储抛异常 → 当作无缓存 / 本次不缓存。
- `:write-back` → `(fn [conversation-id compacted original])`，摘要成功后决定如何处置历史（改写 / 归档原文再改写 /
  只记审计）。缺省 nil = 不写回；原 `:write-back-store` 保留为语法糖 `(store-write-back store)`。
- 无会话 id 的请求不进外部存储（走私有进程内存储）。

## 2. R-02 工具结果截断 + 全文另存

**动机**：一次 `cat` 大文件、一次大 JSON 查询，几十 KB 进历史，后面每轮都付费，且很快撞窗。Codex 缺省 10k token 上限、
头尾保留（错误信息常在尾部）。

**Keelson 出处**：`Tools/ToolOutputTruncation.cs`（`TruncateMiddleToChars` / `FormattedTruncateText`，「…N chars truncated…」
标记计入预算、幂等）、`Tools/ToolOutputSpill.cs`（`IToolOutputSpill` 存全文、回位置）、`ToolCallingAdvisor.cs:968-979`。

**现状**：`tool.clj:524-526` 工具结果 `pr-str` 原样进 `msg/tool-result`；`chat_client.clj:201`（内联工具同）。

**方案**：core `im.ttalk.agent.filter.tool-output`：
- 纯函数 `(truncate-middle s max-chars)`：头尾各半，中间放 `…N chars truncated…`，**结果连同标记 ≤ max-chars**、再截不变
  （幂等）、不劈 UTF-16 代理对；`(truncate-middle-tokens s max-tokens)` 按近似 token（`tokens/estimate-text`）二分到预算内。
- `(tool-output-filter opts)` → `:tool` filter：结果超预算（`:max-chars` 或 `:max-tokens`，缺省 `:max-tokens 10000`）→
  前置警告头（原 token 数 / 总行数）+ 中段截断。`:spill (fn [{:keys [call-id tool-name content]}] -> 位置字符串|nil)`
  可注入：截断时先存全文，结果末尾追加「完整输出已保存到：<位置>」。spill 抛异常/回 nil → 不提位置（截断照做）。
  `:per-tool {"tool-name" {:max-chars n}}` 覆盖单工具预算，`:exclude #{…}` 跳过。call-id 取 `(:tool/call-id ctx)`。
- 只截**成功结果**的字符串；带 `:error` 的结果同样截（错误也可能巨长），`:writes` 不动。

**不做**：块表（多模态工具结果）截断——clj-agent 工具结果是字符串；内置 spill 实现（存哪是宿主的事，同 Keelson）。

**原则**：§1 真实需求（大结果是常态）；作为 filter 可选，缺省不变。

**测试**：边界（恰好等于预算、预算小于标记）、幂等、中文、代理对；filter 对超长结果截断 + 警告头；spill 被调用且位置
进结果；spill 抛异常不影响；per-tool / exclude。

## 3. R-03 同参失败保护 + 失败摘除

**动机**：模型拿同一组错参数反复调用（「原样重试」），每次同样失败，烧光迭代预算；某工具后端挂了，模型连调 10 次。

**Keelson 出处**：`ToolCallingAdvisor.cs:66-76`（`RepeatLimit` / `RepeatStopLimit`）、`:1155-1185`（`Account`：规范化参数
指纹，第 N 次换成「别再原样重试」，N+1 次停，成功清零）；`Advisors/FailureDisableAdvisor.cs`（连续失败摘除，`Gate` 模式
「工具面不动、调用直接拒」保住提示缓存前缀）。

**现状**：无。`react.clj:81-99` 只有 `:transient` 的工具级重试。

**方案**：core `(tool-guard/tool-guard-filter opts)` → `:tool` filter。
- 指纹 = 工具名 + 参数的**规范化**（map 键递归排序后 `pr-str`）。
- `:repeat-limit`（3）：同指纹第 3 次失败 → 结果换成「这是第 3 次以相同参数失败，别再原样重试；改参数或换办法。原报错：…」；
  `:repeat-stop-limit`（缺省 limit+1）：到这一次 → 结果换成「到此为止」并写 `:loop/stop`，react 停回合（`:stopped-by :tool-guard`）。
  同指纹成功一次清零。
- `:disable-threshold`（3）：同一工具**连续**失败 3 次 → 本回合**门禁拒绝**：工具仍在工具列表里（提示缓存前缀不变），调用
  直接回「已拒绝执行：该工具本回合已停用（连续失败 3 次）…」而不执行。成功清零。
- 分类钩子 `:classify (fn [req resp] -> :success|:failure|:ignore)`，缺省：有 `:error` 且 class 不是 `:subagent-suspended`
  为失败，否则成功。
- **计数随暂停快照保存**：计数放在 context 槽 `:tool-guard/state`（经 filter 返回的 `:writes` 写入，框架 reducer 两层 merge——
  同批不同指纹互不覆盖）。context 本就进暂停快照（`pause/snapshot` 的 tool-context），resume 后计数原样接上；simple-agent
  每个 turn 新建 context，故作用域天然是**一个回合**（提醒文案里写明「本回合」）。该键登记 `ctx/framework-keys`，不进 AG-UI 状态。

**不做**：Keelson `Remove` 模式（改工具面会让剩余请求的缓存前缀全部 miss，Gate 模式严格更好）；同批同指纹两次失败
精确计数（快照隔离下按 +1 记，偏保守）。

**原则**：§1 真实（固着重试是 LLM 常见病，用户自己写要在 filter 里维护跨轮状态 + 快照持久化）；§3 只改它声明的（结果文本 /
是否执行），不碰执行环境。

**测试**：第 N 次提醒文案含原报错；N+1 停回合（react 返回 `:stopped-by`）；成功清零；连续失败 → 门禁拒绝且工具不执行；
计数在暂停→EDN 往返→resume 后保持；不同指纹同批不互相覆盖。

## 4. R-04 死循环提醒

**动机**：模型在两三个调用之间打转（读同一个文件、查同一个状态），每轮都成功、所以 R-03 管不到。

**Keelson 出处**：`Advisors/DeadLoopAdvisor.cs`（最近 `window`=6 轮指纹，最近 `recent`=4 轮 ≤ `distinct`=2 种且无进展 →
挂**临时**提醒；只提醒不阻断；提醒过清空窗口）。

**现状**：无；react 也没有「只对本轮生效、不落库」的消息挂点。

**方案**：client `(dead-loop/dead-loop-filter opts)` → `:iteration` + `:turn` filter。`:iteration` 响应侧读 `:calls` 算本轮指纹
（`名字:规范化参数` 截前 100 字符、排序拼接），进展判据 `:progress? (fn [calls errors] -> bool)`（缺省：有一个调用成功且其工具
不在 `:read-only` 集合里——缺省集合为空，即**全部视为只读**，纯按指纹重复判；业务方把写工具列进 `:writes` 集合更准）。
命中 → 下一轮请求带 `:notes [reminder]`（框架挂点 1）。状态按 conversation-id 存在 filter 闭包里，`:turn` 进入（非 resume）时
清零。

**不做**：阻断（误阻断的代价远大于多烧几轮）；跨进程持久化窗口（只是提醒，丢了无害）。

**原则**：§1 真实；临时提醒挂点本身也过四问：不建的话「本轮提醒」要么落库污染历史，要么用户得自己写一个排在 memory 之后的
`:chat` filter 再和 `:iteration` filter 共享状态——代价高且容易排错位置。

**测试**：连续 4 轮同指纹 → 第 5 轮请求末尾有提醒、store 里没有；有进展清空；新 turn 清零；提醒后窗口清空。

## 5. R-05 声明式审批策略 + 会话审批缓存

**动机**：今天审批只有 `:sensitive` 一个布尔（`simple_agent.clj:236-266` gate-of、`filter.clj:565` approval-filter）。真实需求：
「`shell` 里 `git status` 免问、`rm -rf` 一律禁止、其余要问」「用户点了『本会话都批准』后同参数别再问」「MCP 工具按
readOnly/destructive 注解缺省」。

**Keelson 出处**：`Tools/ApprovalPolicy.cs`（Allow/Prompt/Forbidden 取最严，`AllowSessionApproval`）、`Tools/RulePolicy.cs`
（工具名 glob + JSON path 谓词 equals/in/regex/exists/prefix，规则自带正反例构造时自检）、`Tools/ApprovalCache.cs`（会话
缓存、键 = 工具名 + 规范化参数、挂起时快照）、`Tools/ToolHintPolicy.cs`（MCP 注解：destructive⇒问、readOnly⇒免、缺省⇒问；
Prompt/Writes 模式不许会话批准）。

**方案**：client `im.ttalk.agent.approval`（全是纯函数/数据，产出现有挂点能吃的函数）：
- 决策形状：`{:decision :allow|:prompt|:forbid :reason 字符串? :allow-session? bool}`；`(strictest ds)` 取最严。
- 策略 = `(fn [req] -> 决策)`，req = `{:name :args :sensitive? :hints {:read-only? :destructive? :open-world?} :tool-call}`。
  `sensitive-policy`（缺省兜底）、`(rule-policy rules & {:keys [fallback]})`、`(hint-policy & {:keys [mode modes]})`、
  `(strictest-policy & ps)`。
- 规则数据：`{:tool "shell*" :when [{:path "$.argv" :prefix ["git" "status"]}] :decision :allow :justification ".." :match [..] :not-match [..]}`；
  路径 `$` / `$.a.b` / `a.b` / `$.argv[0]` / `$['k.x']`；谓词恰好一个运算（`:equals :in :regex :exists :prefix`，prefix 的元素可为
  候选向量）；构造时校验（未知运算、多个运算、空 justification、正则编译、正反例自检）→ 抛 `ex-info`。
- 会话缓存：`(session-cache)` → atom #{key}；key = `名字 \0 规范化参数`（`approval-key`）；`approved?` / `remember!` / `snapshot` / `restore!`。
- 出口一：`(gate chat-client {:policy :cache})` → react 的 `:tool-gate`：`:allow`→`:proceed`、`:forbid`→`{:reject 理由}`、
  `:prompt`→缓存命中 `:proceed` 否则 `{:pause {:reason .. :allow-session? ..}}`（react 新认的**带信息暂停**，见 §15）。
- 出口二：`(approval-filter {:policy :approver :cache …})` → `:tool` filter（同步审批，CLI 场景；R-06 的链/超时/熔断都接在这）；
  `(->approve-fn …)` 适配旧的 `flt/approval-filter`。
- simple-agent：`create-agent` 新增 `:approval {:policy :cache :timeout-ms :breaker}`；gate-of 合并（回调中断 / `:on-pause` 敏感 /
  策略三路取最严）。resume payload `{:remember :session}` → 把批准的那（几）件调用记进缓存（`:allow-session? false` 的不记）；
  暂停时缓存快照随 PauseStore 存（快照新键 `:session-approvals`），跨重启 resume 时恢复。

**不做**：「批准并记住」持久规则存储（Keelson `IApprovalRuleStore`）——缓存 + 用户自己的规则文件已覆盖；
工具自定义粗化审批键（`ApprovalKey`）——`:key-fn` 选项开着即可。

**原则**：§1 真实（三个具体场景）；§3 审批是 filter/gate 本职。

**测试**：取最严；规则各谓词与路径；自检失败抛；glob；hint 四模式；缓存键规范化；gate 三种出口；simple-agent 端到端：
forbid 不暂停直接拒、prompt 暂停 → `{:remember :session}` 批准 → 同参数第二轮不暂停；快照 EDN 往返后缓存恢复。

## 6. R-06 审批链 + 拒绝熔断 + 审批超时

**Keelson 出处**：`Advisors/ChainApprover.cs`（逐环问，nil=不表态，到底无人表态⇒拒，某环抛⇒拒）、`Advisors/DenialBreaker.cs`
（连拒 3 / 窗口 50 内拒 10 ⇒ 停回合、只跳一次、批准清零连拒）、`Advisors/Approvers.cs`（`TimeoutApprover`：超时是结果不是异常）。

**方案**（同 ns）：审批者 = `(fn [req] -> {:approved? bool :reason? :remember? :timed-out?} | nil)`。
- `(chain-approver & as)`：第一个非 nil 说了算；全 nil → `{:approved? false :reason "无人表态"}`；某环抛 → 拒（带异常信息）。
  `(chain-reviewer & as)` 同上但到底返回 nil（可嵌套进别的链）。
- `(timeout-approver a ms)`：虚拟线程上跑（复用 `tool/call-with-timeout`），超时 → `{:approved? false :timed-out? true}`。
- `(denial-breaker opts)` → 状态机（`:max-consecutive` 3 / `:max-recent` 10 / `:window` 50），`(record! b denied?)` → 本次跳闸
  返回 true（只跳一次）。`approval-filter` 里：审批者拒 → 记账；跳闸 → 写 `:loop/stop` 停回合。状态按会话存 filter 闭包，`:turn`
  进入时清零（作用域一个回合，同 Keelson）。simple-agent 的暂停路径：`:approval {:breaker opts}` 时 resume 的拒绝计入同一状态机，
  跳闸则该次 resume 直接以 `:completed`（`:stopped-by :denial-breaker`）收尾。
- 暂停路径的审批超时：`:approval {:timeout-ms n}`——resume 时暂停已超过 n ms 则按「拒绝：审批超时」处理（暂停快照带 `:paused-at`）。

**不做**：熔断计入策略 forbid / 缓存放行（同 Keelson，只数审批者的决定）。

**测试**：链各分支；超时；熔断连拒/窗口/只跳一次/批准清零；filter 跳闸停回合；暂停超时转拒绝。

## 7. R-07 模型审阅者

**Keelson 出处**：`Advisors/ModelApprover.cs`（严格 JSON `{risk_level,user_authorization,outcome,rationale}`；证据有 token 预算：
对话 6000、单条 900、参数 2000、理由 512；失败即拒；超时 90s）、resx `approval-review/system`。

**方案**：client `im.ttalk.agent.approval.model/model-approver`：`(model-approver chat-model {:history-fn :timeout-ms :transcript-tokens
:message-tokens :args-tokens :instructions :abstain-on-failure?})` → 审批者。证据：工具名、策略理由、参数（截到预算）、最近对话
（`:history-fn (fn [req] -> 中立消息)`，从新到旧取满预算，单条截断，system 不算证据）。回复解析：整段 JSON，不行取第一个 `{` 到最后
一个 `}`；`outcome` 必须 allow/deny；字段不在枚举 → 失败。**失败即拒**（`:abstain-on-failure? true` 时返回 nil 交给下一环，即「审阅不了
再问人」）。最小 JSON 读取器自写（core 零依赖）。

**测试**：allow/deny 映射；包散文的 JSON；非法枚举 → 拒；模型抛错/超时 → 拒或弃权；证据预算（长参数被截、旧消息被省略）。

## 8. R-08 按 schema 完整校验工具参数

**动机**：模型给枚举外的值、负数、错类型，工具函数体里炸成看不懂的异常，模型改不对。Keelson 一次报全错误、带 JSON 路径、
告诉模型怎么改。

**Keelson 出处**：`Tools/ToolArgsValidator.cs`（type/required/properties/items/enum/pattern/min/max/exclusive/minLength/maxLength/
minItems/maxItems；null 处理；未知键不报错）。

**现状**：`tool.clj:472-491` `validate-args` 只查必需参数；deftool 的参数声明只能写类型+描述。

**方案**：core `im.ttalk.agent.tool-args/validate (schema args) -> [{:path :message}]`，覆盖上述关键字，路径形如 `items[2].name`；
`(format-errors tool-name errors)` 生成「参数校验失败（N 处）：- path: 问题。期望 …。请按 schema 修正后重试」。
- deftool 参数声明支持约束：`[n :int "数量" :minimum 1 :maximum 10]`、`:enum [..]`、`:pattern ".."`、`:min-length`/`:max-length`、
  `:items {:type "string"}`、`:min-items`/`:max-items`——原样进 `input_schema`（模型也看得到约束）。
- 对象参数另认 `:properties` / `:required` / `:additional-properties`（→ `additionalProperties`），嵌套字段对模型可见、嵌套必填也校验。
  属性表可写成 `[[:字段 {…}] …]` 键值对 vector 以保序（map 字面量 >8 键会被 reader 变成 hash map）。
  `input_schema.properties` 按参数声明顺序写出；schema 在**命名空间加载期**构造而非常量——AOT 会把常量 map 重建（>8 键即
  PersistentHashMap），顺序会丢（见 `feedbacks/2026-11-11-deftool-order-lost-under-aot.md`）。
- `tool/validate-args` 改用完整校验（返回形状不变 `{:valid :errors}`）。
- **兼容性**：类型校验对 integer/number 接受数字字符串（模型常把数字带引号，原先函数体照样拿到字符串，不能因此变成报错）；
  可选参数给 `null` 视同未给；未知键不报错。今天的 deftool 只声明了类型，新增的失败面仅是「类型确实不符」（如对象给了字符串）。
- 内联工具（MCP 等）不在框架里校验：server 自己校验，翻译一遍只会引入我们的 bug（同 `mcp/tools.clj:83` 的取舍）；需要的可在 handler
  里调 `tool-args/validate`。

**测试**：每个关键字；一次报全；嵌套路径；数字字符串兼容；null 可选；deftool 约束进 schema；端到端错误文案回模型。

## 9. R-09 收尾闸门

**Keelson 出处**：`Advisors/CompletionGate.cs`（Accept / Continue(note) / Stop；按序问，第一个非 Accept 生效；`GateActive` 防自我逼迫）。

**现状**：`filter.clj:592` `validation-turn-filter` 只有「通过 / 不合格重入」两态。

**方案**：`(flt/completion-gate-filter gates & {:keys [max-continues note-fn]})` → `:turn` filter。gate = `(fn [{:keys [result attempt
gate-active?]}] -> :accept | {:continue 一句话} | :stop | {:stop 理由})`；Continue → 一句话作 user 消息重入（同 validation，新 delta），
`:max-continues`（2）耗尽原样返回；Stop → 结果加 `:stopped-by :completion-gate :stop-reason`。gate 抛异常照抛（宿主策略不替它吞）。
`validation-turn-filter` 改为它的特例（行为与测试不变）。

**测试**：多闸门顺序、continue 重入、stop、耗尽、暂停透传、validation 回归。

## 10. R-10 分层指令文件

**Keelson 出处**：`Instructions/HierarchicalFileInstructionSource.cs`（根→cwd 每层取首个候选文件，字节预算截断不劈 UTF-8，读不到跳过，
cwd 必须在根内）、`Advisors/InstructionsAdvisor.cs`（追加到 system instructions，不进历史）。

**方案**：core `im.ttalk.agent.filter.instructions`：`(hierarchical-file-source {:root :cwd :names ["AGENTS.md"] :max-bytes 32768})` →
`(fn [] -> {:text :origins :truncated?} | nil)`；`(instructions-filter source & {:keys [separator]})` → `:chat` filter，把正文接到
`:system-prompt` 选项之后。候选名由调用方给（库内不写死），缺省 `["AGENTS.md"]` 仅作为 opts 缺省值。每次 LLM 调用现读（文件小，且改了立刻生效）。

**测试**：多层拼接顺序、每层首个候选、预算截断不劈中文、cwd 越界抛、读不到跳过、filter 改 system 不改 messages、store 无痕。

## 11. R-11 回合生命周期事件 + 指标挂点

**Keelson 出处**：`AgentEvents.cs`（TurnStarted/RoundStarted/ToolStarted/ToolFinished/Usage/TurnFinished 的封闭集）、`AgentMetrics.cs`
（请求时长、TTFT、token、工具时长）。

**现状**：`callbacks.clj` 9 个零散回调，无耗时、无累计 usage、无 TTFT。

**方案**：callbacks 新增 `:on-event (fn [event])`。封闭事件集（client `im.ttalk.agent.events` 登记 `event-types` 与文档）：

| `:type` | 键 |
|---|---|
| `:turn/started` | `:resumed?` |
| `:iteration/started` | `:index` |
| `:llm/finished` | `:index :elapsed-ms :ttft-ms(流式) :usage :finish-reason` |
| `:tool/started` | `:index :call-id :name :args` |
| `:tool/finished` | `:index :call-id :name :status(:ok/:error/:rejected/:replied) :error-class :elapsed-ms` |
| `:usage` | `:index :round :total` |
| `:turn/finished` | `:status :elapsed-ms :usage :error(异常时)` |

每个事件带 `:ts`（毫秒）与回调 `:metadata` 里的 `:conversation-id`/`:run-id`。出口异常吞掉。工具事件在并发引擎下可能来自不同线程。
`(events/metrics-collector)` → `{:on-event f :snapshot (fn [])}` 现成聚合（请求数、TTFT、token、工具时长），零依赖。

**测试**：顺序与成对；usage 累计；流式 TTFT；工具 rejected 也有始末；异常吞掉；resume 的 `:resumed? true`。

## 12. R-12 消息规范化补全

**Keelson 出处**：`Advisors/MessageNormalizer.cs`（补缺结果、删孤儿结果、不支持媒体换占位，字节稳定）、`Advisors/MessagePruner.cs`
（`ThinkingPrune.BeforeCurrentTurn`：只裁本轮之前的思考块）。

**现状**：`react.clj:471` heal 只在 turn 开始给 store 补「已取消」；孤儿结果（窗口裁剪、手工历史）会被 provider 拒。

**方案**：core `im.ttalk.agent.filter.normalize`：纯函数 `(normalize-messages msgs opts)` + `(normalize-filter opts)` 的 `:chat` filter（**注册在
最内侧**，memory/compaction 之后）。① 删找不到调用的 tool 结果；② 非末条 assistant 的悬空调用补「已取消」结果（紧跟其后，内容固定
→ 字节稳定）；③ `:images? false` 时图片部件换 `[图片已省略]`、`:files? false` 同理；④ `:thinking :before-current-turn`（缺省 `:keep`）
去掉最后一条真 user 消息之前的 assistant `:blocks`。只改请求视图，store 不动。

**测试**：四项各自；什么都不动时原样；幂等。

## 13. R-13 pause_turn 续跑

**Keelson 出处**：`Providers/PauseTurn.cs`（`pause_turn` + 无本地工具调用 + 有内容 ⇒ 原样追加 assistant 消息、不加 user 消息、再请求；
最后一轮不续）。

**现状**：`model/response.clj:142` 已把 `pause_turn` 映射成 `:pause`；`react.clj:664` 无工具调用即 `:completed`——服务端工具跑一半就当最终答案。

**方案**：react 迭代体：响应 `finish-reason :pause`、无 tool-call、有文本或 replay-blocks、且预算未尽 → `:continue`，delta 为空（有 memory
filter：响应已落库，下轮展开历史即以该 assistant 消息结尾）或 `[该 assistant 消息]`（无 memory）。每次续跑扣一次 `remaining`；
预算耗尽则按当前响应 `:completed`（不抛）。判定可由 `:settings :pause-turn-fn (fn [response] -> bool)` 覆盖（provider agent 若提供判定函数可接入）。

**测试**：pause→续跑→最终文本；续跑请求没有新增 user 消息；预算耗尽收尾；无内容不续。

## 14. R-14 子代理异步工具组（低优先）

**Keelson 出处**：`Subagent/SubagentTools.cs`（spawn/send/wait/list/interrupt，深度与数量上限）。

**现状**：`subagent/delegate.clj:268` `management-tools` 有 spawn/list/result/kill/restart，无 wait（阻塞等多个）、无 send（续聊）、无深度/数量上限。

**方案**：client `im.ttalk.agent.subagent.tools/subagent-tools config` → 五个内联工具 `spawn_agent` / `send_agent` / `wait_agent` / `list_agents` /
`interrupt_agent`。`:max-agents`（同会话同时 running 上限，缺省 4）、`:max-depth`（缺省 2，经子 agent `:tool-context` 种子 `:subagent/depth`
传递——显式通道，符合 §3）。send = 用同一 conversation-id + 同一 memory store 起新一次运行（续上历史）。wait = 等一组 id 中任一/全部完成，带超时。

**测试**：spawn→wait 拿结果；数量上限拒绝；深度上限拒绝；send 续聊带历史；interrupt。

## 15. 预留：审批时改参数（approveWithEdits）与一轮多条审批

AG-UI 侧以后要做「审批时改参数」和「一次暂停里多条待审批」。本轮在 client 侧把**数据形状与 resume API**定下来并实现（不破坏现有行为）：

**暂停结果**（react `:paused`，simple-agent 同步透出）：
```clojure
{:status :paused
 :pending-tool  {:name :args :tool-call :approval {:reason .. :allow-session? ..}}   ;; 不变：第一件
 :pending-tools [{:name :args :tool-call :approval {..}} ...]                       ;; 新增：本批全部需审批的
 :loop-state {:tool-calls [...] :pending-id "c1" :pending-ids ["c1" "c3"] ...}}      ;; :pending-ids 新增
```

**resume payload**（`react/resume` 的 opts `:payload`、`simple-agent/resume` 的 payload）：
```clojure
{:args {...}                     ;; 旧：编辑后批准 pending-id 那一件（保留）
 :message "..."                  ;; 旧：拒绝理由 / reply 答复
 :remember :session              ;; 新：批准的调用记进会话缓存（R-05）
 :decisions {"c1" {:decision :approved :args {...}}       ;; 新：逐件决定
             "c3" {:decision :rejected :message "不行"}
             "c4" {:decision :reply :message "答复"}}}
```
语义：给了 `:decisions` 就按逐件决定执行，且必须**答全、答对**——点名了不在 `:pending-ids` 里的 id 抛
`ex-info`（`{:type :interrupt-unknown :unknown [..] :pending [..]}`），有待审批调用没答抛
`{:type :interrupt-incomplete :missing [..] :pending [..]}`（与 AG-UI agent 的 G-02 约定）。`:approved` 带 `:args` =
approveWithEdits，`:args` **整体替换、不合并**；`:rejected` 可带理由；`:reply` 答复即结果。不给 `:decisions` 时按顶层
`decision` 处理（与今天逐字相同）。非审批调用（gate 判 `:proceed` 的）照常执行。旧 loop-state 没有 `:pending-ids` 时按
`[:pending-id]` 处理。审批时改参数后的调用以新参数执行、进入 `:tool-calls-made`；assistant 消息里的原 tool-call 参数不改写
（它是模型说过的话）。simple-agent 的 `resume` payload 同形（另可带 `:remember :session`，顶层或逐件）。

AG-UI 映射建议（给 agui agent）：一次 `RUN_FINISHED.outcome.interrupts[]` 可以逐条对应 `:pending-tools`；前端回传
`{interruptId → {decision, editedArgs, message}}` 直接翻成 `:decisions`。

## 16. 与 docs/design-principles.md 的关系（汇总）

- §1：每项都给了具体场景与「不建用户怎么办」；被否决/推迟的（KeepUserMessages、Remove 模式、持久规则库、多模态截断）记在各节「不做」。
- §2：全部无 web 依赖；core 仍零依赖。
- §3：所有 filter 只改它声明要改的东西；状态要么在 filter 闭包（不跨 ChatClient），要么在 context 的框架槽（显式、可快照）；
  子代理深度经 `:tool-context` 显式传递，不走动态绑定。
