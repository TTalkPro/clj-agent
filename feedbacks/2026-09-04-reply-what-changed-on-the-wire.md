# 回复：这一轮线上还变了哪些**你们看得见**的东西

> 方向与其它几份相反：这份是 **clj-agent → 下游**。四条账的修法各自记在对应文件里，
> 这里只列**没被那四条覆盖、但会改变你们收到的报文**的部分 —— 免得你们靠观察去发现。
> 全部实测过，逐项写了怎么验。

## ⚠️ 一条**你们可以立刻删掉的绕法**

happy 的 `TASK.md` 里那句：

> `/threads` 那一枪**故意保留**：不是所有运行时都有 `threadEndpoints` 这个声明
> （clj-agent 就没有），404 → `:unsupported` 正是我们设计好的降级路径。

**前提没了。`threadEndpoints` 现在无条件发**，线程面关掉时发的是一份**全 false**，
而不是省略：

```bash
# 缺省
curl -s :4002/api/copilotkit/info | jq .threadEndpoints
# {"list":true,"inspect":true,"mutations":true,"realtimeMetadata":false}

# CLJ_AGENT_THREADS=0 起
# {"list":false,"inspect":false,"mutations":false,"realtimeMetadata":false}
```

理由与你们在 usage 那份里写的是同一条：**「探测式能力发现」正是 `/info` 该消灭的
东西**。省略与「明确说没有」在客户端那儿是两码事 —— `undefined` 只能理解成「这台
没说」，于是只好盲发一枪拿 404 当答案。现在两种情况都有确定答案，那一枪可以撤。

（这也不违反我们自己那条「不谎报能力位」：那条禁的是**报了没有的**，不是禁
「如实说没有」。）

## `capabilities` 补齐到六格

对着你们贴的 keel 那份逐格补的。分两类，界线是**「这是 AG-UI 这一层的事实」还是
「取决于装配/模型」**：

| 格 | 现在报什么 | 谁说了算 |
|---|---|---|
| `transport` | `{streaming:true}` | 本层事实：出口就是 SSE，token 逐个发 |
| `tools.supported` / `clientProvided` | `true` / `true` | 本层事实：`RunAgentInput.tools` 里的前端工具我们认 |
| `tools.parallelCalls` | demo 上是 **false** | 装配方：缺省引擎（Sequential）全程内联、串行；要并行得注入 `virtual-thread-tool-calling-manager` |
| `reasoning` | `{supported:true, streaming:true}` | 本层事实。⚠️ 读作**「模型出思考我们就送到」，不是「模型一定有思考」** |
| `execution.maxIterations` | demo 上是 `10` | 装配方传；库里不抄缺省值（提成了 `simple-agent/default-max-iterations`） |
| `multimodal` | 见那份反馈 | 装配方 |

`humanInTheLoop` 不变。装配方整份传 `:capabilities` 仍然整份接管（既有语义没动）。

## 会改变报文的四处

### 1. `TOOL_CALL_START.parentMessageId` —— 以前恒为 nil，现在有了

映射一直在 codec 里，但发射器压根没填这个键。现在补上，锚的是**发起这次调用的
那一轮**的消息 id：

```json
{"type":"TOOL_CALL_START","toolCallId":"call_…","toolCallName":"get-weather",
 "parentMessageId":"13e0601a-…-m0"}
```

⚠️ **那个 id 上通常没有 `TEXT_MESSAGE_*`** —— 模型不说话直接调工具是常态，那条
assistant 消息在历史里存在（带 tool_calls 没有 content），只是没有文本事件。
这与上游参考实现一致（`packages/demo-agents/src/openai.ts:81` 无条件锚）。
如果你们按「先有 TEXT_MESSAGE_START 才建消息」来挂卡片，这里要留意。

### 2. `/threads/:id/messages` 的消息 id 变成**跨快照稳定**的了

以前只能按下标合成 `m-0`/`m-1`，而服务端的 `heal-dangling` / `replace-tool-results`
会让位置漂 —— 同一条消息两次快照两个 id，你们据此做的增量更新会错位。现在消息
落 ChatMemory 时补一个稳定 id：

```
第一次读: ['msg-8e633f9e-…', 'msg-61087a6b-…']
第二次读: ['msg-8e633f9e-…', 'msg-61087a6b-…']   ← 同一批
```

⚠️ 它与**事件流**里的 message-id 不是一个 id 空间（那边是 `<run-id>-mN`，发射器
在流式过程中现给）。要合成一个得让发射器的 id 流进落库那一步，那是 core 依赖
agui 的方向，不做。

### 3. SSE 的 `id:` 现在保证**到达顺序 = 取号顺序**

修了一个真 bug：取号与投递原来分两次拿锁，中间有窗口，并发（子 agent lane 与父
run）时能出现「A 取到 9、B 取到 10、B 先投递」。抓到过的现场里，一条
`SUBAGENT_STARTED` 拿着 seq 18 **落在 seq 17 的 `RUN_FINISHED` 后面**。

对你们的实际影响是**断线重连会丢事件**：`id:` 回传成 `Last-Event-ID` → `:since`，
到达乱序会把水位记高，后到的小号事件被当成已收、续传时直接跳过。现在三件事在同
一把锁里做完，并发回归测试钉着（8 线程 × 60 条断言顺序一致）。

### 4. `RunAgentInput.context` 现在真的会到模型

以前 `RunAgentInput` 九个顶层字段只解了七个，`context`（你们 `useAgentContext`
注册的那些）**落地即丢**。
现在渲染成一段带出处抬头的 system 段随本轮发出去，**turn 级、不落 ChatMemory**。
A/B 实测同一句「我现在在看哪一页？」：带 context 回 `/orders/42`，不带回「我无法
知道您当前正在浏览的页面」。

`parentRunId` 也一并解了（原样回到 `RUN_STARTED.parentRunId`）。⚠️ 但 CopilotKit 的
JS 客户端在发出去之前会把这个字段解构丢掉，所以走那条路它恒为 nil —— 留着是给别的
SDK 与自建客户端的。

## 子 agent 那几格（你们暂时用不上，先记着）

`SUBAGENT_STARTED` 补了 `parentMessageId`（与同轮 `TOOL_CALL_START` 锚同一条消息，
协议要求两者归属一致）；`SUBAGENT_FINISHED` 补了 `result`（以前把子 agent 的产出
扔了）。另外父 run 收口前会**主动关掉还开着的 lane** —— 以前靠静音守卫，`:supersede`
掐掉一轮时你们会收到一条**永远不闭合的** `SUBAGENT_STARTED`。

⚠️ 这三个事件类型仍**缺省关**（`:subagent-events? false`）：`@ag-ui/client` ≤ 0.0.57
在 HTTP transport 里校验 discriminated union，一条未知类型掐断整条流。

## 换有视觉的模型怎么起

多模态那条端到端只有在有视觉的模型上才验得完。demo 加了 provider/model 开关：

```bash
CLJ_AGENT_PROVIDER=zhipu CLJ_AGENT_MODEL=glm-5.3-flash CLJ_AGENT_VISION=1 \
  clojure -M:copilotkit -e '(load-file "examples/copilotkit/demo_server.clj")'
```

⚠️ 模型名是 **`glm-5.3-flash`**（`glm` 后面有连字符）；`glm5.3-flash` 智谱直接回
`modelCode：不存在`。缺省仍是 `minimax / MiniMax-M2.7`（无视觉，`image:false`）。

⛔ `CLJ_AGENT_VISION` 刻意做成**显式开关，不按模型名推断** —— 同一个 provider 下有
视觉的没视觉的都有，猜错了就是谎报能力位。
