# 子 Agent 事件归属设计：让委派的那几分钟在事件流里有形状

> 对标 AG-UI 0.0.59 的 `SUBAGENT_*` 事件族与 CopilotKit `emit_subagent_events`
> （证据见 §1.2）。本文是 [`agent-runtime-design.md`](agent-runtime-design.md) 的增量，
> 不推翻它的任何一条契约——**恰恰相反，本文一半的篇幅在论证怎么不破它们**。

---

## 0. TL;DR

| 项 | 结论 |
|---|---|
| 缺什么 | 一次子 agent 委派在事件流里只有 `tool/started → tool/result` 两端，中间几分钟是黑洞（§1.1） |
| 加什么 | 三个中立事件 `:subagent/started|finished|error` + **一个字段** `:subagent-run-id` 打在该 lane 的所有既有事件上 |
| 怎么传 | `agent-fn` 多收一个 `:subagent-observer` → 用户显式塞进 `delegate-tool` → 随 spec 进 `manager/spawn!` → worker 线程上现造 lane 发射器。**每一跳都是显式值，没有 binding、没有注册表反查** |
| 分层 | `clj-agent-client`（manager/delegate）**零 AG-UI 知识**：spec 上只多一个协议无关的 `:observer` 钩子；协议映射照旧全在 `agui.codec` |
| 不破的契约 | `:seq` 会话内无洞（lane 共用会话取号器）、恰好一个终态且在最后（lane 永不发 `:run/*`，父 run 终态后 lane 静音）、发射器永不抛 |
| 兼容 | `subagentRunId` 加在既有事件上对老客户端**安全**（AG-UI 事件 schema 是 `passthrough`）；三个新**事件类型**不安全（≤0.0.57 在 transport 层校验 union 会掐断整条流）→ 一个 runtime 开关，缺省关 |
| 不做 | `delegations` 共享状态槽、线程隔离、lane 级插件、子 agent HITL（§7 逐条记否决理由与重启条件） |

---

## 1. 现状（证据）

### 1.1 今天一次委派在事件流里长什么样

`delegate-tool` 产出的是一个**普通内联工具**（`subagent/delegate.clj:85`），
所以它在事件流里跟 `get_weather` 没有区别：

```
tool/started {:tool-call-id "tc1" :name "deep_research"}
tool/args    {:tool-call-id "tc1" :args {:task "..."}}
tool/ended   {:tool-call-id "tc1"}
        ← 这里是 40 秒到几分钟，子 agent 跑了 N 次 LLM 往返、M 次工具调用
tool/result  {:tool-call-id "tc1" :content "……最终一段文本"}
```

三处放大它：

| 放大项 | 出处 |
|---|---|
| 子 agent 连 token 出口都没有 | `manager.clj` 的 `do-run` 调的是 `(chat-fn agent prompt)`——**不传 opts**，所以 `:on-token` 无从谈起 |
| fanout 是 N 个子 agent 一条结果 | `delegate.clj` 的 `fanout-tool` 顺序 `await!` 后 `join` 成一个字符串；10 路并发在前端就是一张卡片转几分钟 |
| 异步管理工具更黑 | `spawn_subagent` 只回一句「已启动，id: …」，之后要靠 LLM 自己再调 `subagent_result` 才知道死活 |

对照 `agent-runtime-design.md` §4.9 立的那张「事件从哪来」的表：三个采集点
（`:on-token` / `:on-llm-result` / `:iteration` 链）**全部挂在父 agent 的
ChatClient 上**。子 agent 是**另一个** ChatClient（§3 边界），一个采集点都不在那儿。
这不是接线漏了，是边界的必然结果——所以要的是一条**显式**跨边界的出口，不是把
采集点做成全局的。

### 1.2 上游对照（CopilotKit / AG-UI）

两条路线并存，都在仓库里能读到：

| 路线 | 出处 | 做法 |
|---|---|---|
| **隔离**（把子 agent 事件挡住） | `examples/showcases/deep-agents-finance-erp/agent/isolated_subagents.py` | 子 agent 丢进 `ThreadPoolExecutor`，**用 OS 线程边界切断 LangChain callback 传播**，子 agent 事件根本不进父流 |
| **归属**（标记后由消费方分流） | `@ag-ui/core@0.0.59` + `emit_subagent_events=True` | 事件带 `subagentRunId`，消费方自己决定进对话流还是进 console |

选归属的理由，上游用实测数字写在
`examples/showcases/reskinnable-demo/src/shell/subagents/subagent-activity.tsx:20`：

- 一次 run 发了 **3064 条事件，只有 2 条 `MESSAGES_SNAPSHOT`** → 读 messages 的消费者
  会枯坐几分钟再一次性刷出来；
- **只有事件带归属**：2924/3064 条事件有 `subagentRunId`（含 877/879 条 text delta），
  而**持久化后的 55 条 message 一条都没有**；
- 没有 per-lane 状态时，10 个并发子 agent 的 prose 会 **token 级交错**成一坨不可读的
  文本（注释里贴了实例）。以前的 workaround 是给子 agent 关流式，有了 lane 归属就能
  重新打开。

协议面（`@ag-ui/core@0.0.59` 的 `.d.ts`，逐字段抄）：

```
SUBAGENT_STARTED  { subagentRunId, name, description?,
                    parentSubagentRunId?, parentToolCallId?, parentMessageId? }
SUBAGENT_FINISHED { subagentRunId, result?, outcome?: {type:"success"}
                                                     | {type:"suspended", interruptIds?} }
SUBAGENT_ERROR    { subagentRunId, message, code? }
```
外加**所有** `TEXT_MESSAGE_*` / `TOOL_CALL_*` / … 事件都多一个可选 `subagentRunId`。

**兼容性有个硬结论，直接决定了本文的开关设计**：AG-UI 的事件 schema 是
`z.object(...).passthrough()`（0.0.51 / 0.0.57 / 0.0.59 都查过），所以
**在既有事件上多一个 `subagentRunId` 字段，老客户端原样忽略，安全**；
但**新的事件类型不安全**——`@ag-ui/client` ≤ 0.0.57 在 **HTTP transport 里**就拿
discriminated union 校验每一条事件，一条 `SUBAGENT_STARTED` 掐断整条流，客户端侧
任何 middleware 都救不回来（上游为此在 `reskinnable-demo/agent/main.py:171` 写了
一整段注释，并把那个 demo 单独 pin 到 `0.0.59-canary`、退出了 root workspace）。

### 1.3 为什么这不推翻 §3.3 那一行

`agent-runtime-design.md` §3.3 白纸黑字写着：

> | 子 agent | runtime **完全不感知**。子 agent 走 `subagent-config`，不因为父 run 在注册表里就有了什么新通道 |

**这一行继续成立，本文不申请例外**：

| 担心 | 本方案 |
|---|---|
| runtime 反查子 agent？ | 不。runtime 不认识任何子 agent，注册表里没有它们，`stop!` 也管不到它们（§3.7 如实说明） |
| ambient 传导？ | 不。`spawn-worker!` **仍然不用 `bound-fn*`**（那条有测试钉住），出口是 spec 里的一个**值**，跟 `:prompt` 同级 |
| 隐式通道？ | 不。链路上每一跳都要用户亲手接：`agent-fn` 收到 `:subagent-observer`，**不塞进 `delegate-tool` 就什么都不会发生** |

变的只有一件事：§3.3 说「想让某状态穿到子 Agent，走 `subagent-config` 显式传」——
本文**用的正是这条**，只是那个状态叫「事件出口」。

---

## 2. 四问判据（design-principles §1.2）

| 问题 | 答 |
|---|---|
| 现在有人要用吗？ | 有。`fanout-tool` 已经在跑并发委派，前端现在只能看到一张转几分钟的工具卡片；AG-UI 前端（我们已经对接的那个）0.0.59 起原生认这三个事件 |
| 不建的话用户怎么办？ | **做不到**。三个采集点全在父 ChatClient 上，子 agent 是另一个边界；用户在库外唯一能做的是「自己在 `subagent-fn` 里塞回调再自己拼 SSE」——那等于把 `agui.event` 的 seq/终态/永不抛三条契约重写一遍 |
| 换来的是什么？ | **能力**：委派期间的 token / 工具调用 / 失败原因第一次可见，且**可归属到 lane**。不是「更对称」 |
| 触发条件写得出吗？ | 写得出且已发生：fanout 的多路并发在同一条消息里交错（上游实测过同款现象），没有 lane id 就无法分流 |

---

## 3. 设计

### 3.1 词汇：lane

一个子 agent 的一次运行 = 一条 **lane**，id 叫 `:subagent-run-id`（`sa-<uuid>`，
沿用 `manager/spawn!` 已经生成的那个 id，不新造第二套标识）。
lane **不是 run**：它没有自己的 `:run/*` 终态，寄生在父 run 的事件流里。
嵌套 lane（子 agent 的子 agent）靠 `:parent-subagent-run-id` 串成树。

不引入 "thread"，理由同 §4.1；不叫 "subrun"，因为它不满足 run 的两条契约。

### 3.2 事件模型增量

三个新中立事件（进 `agent-runtime-design.md` §4.2 的清单）：

```clojure
{:type :subagent/started  :subagent-run-id "sa-1" :name "research_agent"
                          :task "查一下冷暴露训练的证据"        ; → description
                          :parent-subagent-run-id nil           ; 嵌套时非 nil
                          :parent-tool-call-id nil}             ; §3.7 说明为什么常为 nil
{:type :subagent/finished :subagent-run-id "sa-1" :outcome :success}
{:type :subagent/error    :subagent-run-id "sa-1" :error <canonical error>}
```

一个新字段，打在**该 lane 发出的所有既有事件**上：

```clojure
{:type :message/delta :message-id "sa-1-m0" :text "…" :subagent-run-id "sa-1"}
{:type :tool/started  :tool-call-id "…"     :name "search" :subagent-run-id "sa-1"}
```

**不新增事件类型给 lane 的文本/工具**——lane 里的消息就是消息、工具就是工具，
只是多一个归属字段。这与上游一致，也是消费方能复用同一套折叠逻辑的前提。

### 3.3 四条契约的对账（本方案的核心风险都在这一节）

#### 契约 1：`:seq` 在**会话**内单调递增、无洞

lane 发射器**共用父发射器的 `:next-seq`**（那是会话级取号器，`runtime/next-seq-fn`
在会话锁里 `swap!`）。lane 跑在子 agent 的虚拟线程上，与父 run 线程并发取号 ——
锁保证了唯一性，交错顺序即真实发生顺序。

> **一个容易写错的地方**：父 run 终态后到达的 lane 事件必须**在取号之前**被丢弃。
> `event/deliver!` 是「先取号 → 记账 → 出口」，所以**不能**在 sink 里丢——那会
> 留下一个永远补不回来的 seq 洞，`:since` 续传从此对不上（正是 §6.3 立
> 「发射器永不吞」时担心的同一类故障）。
> 正确的挂点是 `:transform`：它在 `expand` 里、**取号之前**跑，返回 `[]` 即整条吞掉
> 且不占号。lane 发射器的 transform 就是这个守卫（见 §3.4 代码）。

#### 契约 2：恰好一个终态事件，且它是该 run 的最后一个

两条硬规则：

1. **lane 发射器永远不发 `:run/*`**。它的收尾走新函数 `event/finish-subagent!`，
   发的是 `:subagent/finished|error`，这三个类型**不进 `terminal-types`**；
2. **父 run 终态后 lane 静音**（上面那个 transform 守卫）。同步委派下这不可能发生
   （工具阻塞着，run 走不到终态）；异步 `spawn_subagent` 下必然发生 —— 如实说明见 §3.7。

`finish-subagent!` 复用 `close-open!`：lane 自己的开着的消息/工具由它补关
（lane 发射器有**独立的 state atom**，所以补关的是自己的，碰不到父 run 的）。

#### 契约 3：发射器永不抛（§6.3）

lane sink 是父 sink 的一层薄包装，异常照旧在 `deliver!` 里兜住并计 drop。
子 agent 那边**不能**因为「事件发不出去」而影响它自己的结果——`manager` 的 worker
已经是 `try/catch Throwable` 全包，观察钩子再包一层。

#### 契约 4（本文新立）：lane 隔离

> **每条 lane 一个发射器实例，各自的 `:open-messages` / `:open-tools` / `:current-message`。**

这是 §1.2 那条实测教训的结构解：10 路并发共用一个发射器就会共用
`:current-message`，token 于是交错进同一条消息。message-id 一律以 lane id 打头
（`sa-1-m0`），保证跨 lane 不撞。

### 3.4 传递路径（每一跳都是显式值）

```
runtime/launch!  ── em（父发射器，本来就有）
      │
      │ agent-fn 的入参多一个 key：:subagent-observer
      ▼
用户的 agent-fn ── (delegate/delegate-tool {:name "research"
                                            :subagent-fn …
                                            :observer subagent-observer})   ← 不塞就没有任何行为变化
      │
      │ handler 组 spec 时把 :observer / :subagent-name 带上
      ▼
manager/spawn! ── spec {:subagent-config … :prompt … :observer <factory>}
      │
      │ spawn-worker! 在**子线程上**调 factory 一次
      ▼
agui.subagent/observer ── {:decorate (fn [agent] …)  ;; emit/attach lane 发射器
                           :chat-opts {:on-token …}
                           :settle!  (fn [outcome] …)}
```

`agui.subagent` 是新的一个小 ns（`clj-agent-agui` 模块内），负责把 lane 发射器
造出来并翻译成上面那三个协议无关的钩子：

```clojure
(ns im.ttalk.agent.agui.subagent
  (:require [im.ttalk.agent.agui.emit :as emit]
            [im.ttalk.agent.agui.event :as event]))

(defn observer-factory
  "给一个父发射器造 observer 工厂。runtime 把它作为 :subagent-observer 交给 agent-fn。
   `parent` 既可以是 run 的发射器，也可以是另一条 lane 的发射器——嵌套由此自然成立。"
  [parent]
  (fn [{:keys [id name task]}]
    (let [lane (event/subagent-emitter parent {:subagent-run-id id :name name})]
      {:decorate  (fn [agent] (emit/attach agent lane))
       :chat-opts {:on-token (emit/token-fn lane)}
       :start!    (fn [] (event/start-subagent! lane {:name name :task task}))
       :settle!   (fn [outcome] (event/finish-subagent! lane (finish-of outcome)))})))
```

> **嵌套（子 agent 再委派）：事件层支持，接线不做**（施工时才看清的一条）。
> `subagent-emitter` 认父 lane、`parent-subagent-run-id` 自动串起来，S1 有测试钉住；
> 但要让**子 agent 自己的** `delegate-tool` 拿到这条 lane 的工厂，得让
> `:subagent-fn`（`(fn [args ctx] -> subagent-config)`）收得到它 —— 那是 `delegate`
> 的签名变更。`:decorate` 帮不上忙：它拿到的是**已经建好**的 agent，工具集在
> `create-agent` 里就定死了。
> **重启条件**：出现真实的两层委派场景（如上游那个 analyst → 十路 researcher 的
> beat）时，一并设计 `:subagent-fn` 的签名扩展。

`event/subagent-emitter`（新，`agui.event` 内约 15 行）：

```clojure
(defn subagent-emitter
  [parent {:keys [subagent-run-id]}]
  (assoc (emitter {:run-id          (:run-id parent)           ; 仍属于父 run
                   :conversation-id (:conversation-id parent)
                   :next-seq        (:next-seq parent)          ; 契约 1：共用取号器
                   :sink            (:sink parent)
                   :now             (:now parent)
                   ;; 契约 2 的守卫：父 run 一旦终态，本 lane 整条吞掉且**不占号**。
                   ;; 挂 :transform 而不是包 sink，是因为 transform 在取号之前跑。
                   :transform       (fn [ev] (if (terminal-of parent) [] [ev]))})
         :tag {:subagent-run-id        subagent-run-id
               :parent-subagent-run-id (get-in parent [:tag :subagent-run-id])}))
```

`:tag` 由 `emit!` 在组 base 时 merge 进每条事件（`event.clj` 的 `emit!` 改一行）：

```clojure
(let [base (merge {:type type :run-id … :conversation-id … :ts …}
                  (:tag em)          ; ← 新增这一行，lane 归属就全打上了
                  m)]
```

**为什么 lane 不接插件 transform**：`:transform` 槽被终态守卫占了。插件
（`agui.genui`）的 transform 是**按 run 现造的有状态对象**，多条 lane 共用一个
实例，它记的「这一轮见过哪些 tool-call」就会被交错的 lane 污染。v1 明确不接。
**重启条件**：出现「子 agent 的工具调用也要渲染成 activity 卡片」的真实场景时，
把守卫与插件组合起来，并给每条 lane 现造一个插件实例。

### 3.5 分层：`clj-agent-client` 保持零 AG-UI 知识

`manager` / `delegate` 在 client 模块，`agui` 依赖 client——**反向依赖不存在**，
所以 manager 不能认识发射器。spec 上加的这一个键因此是**协议无关**的：

```clojure
;; manager spec 新增键（全可选，缺省 = 今天的行为逐字不变）
:observer   (fn [{:keys [id attempt name task owner]}]
              {:decorate  (fn [agent] agent)   ;; 可选：给子 agent 挂采集
               :chat-opts {}                   ;; 可选：并进 chat 的 opts
               :start!    (fn [] …)            ;; 可选：worker 线程上、建 agent 前
               :settle!   (fn [outcome] …)})   ;; 可选：worker 线程上、finally 里
:subagent-name "research_agent"                ;; 只是给 observer 看的标签
:task          "查一下冷暴露"                    ;; 同上，缺省取 :prompt
```

`:attempt` 是**代号**：`restart!` 复用同一个 registry id，观察方拿它把两次尝试分开。
没有它，同一个 lane id 会被开两次，消费方只能把两次尝试叠在一起看。
（施工时才浮出来的一条——`restart_subagent` 是 `management-tools` 的既有能力。）

`:task` 给的是**原始任务串，不是拼好的 prompt**：prompt 里还有 seed 与背景，动辄
几 KB，塞进 `SUBAGENT_STARTED.description` 没人读得下去。

契约写死三条，进 manager 的 docstring：

1. **工厂在 worker 线程上调用，每次 spawn 一次**（fanout N 路 = N 个 observer，
   契约 4 的来源）；
2. **四个钩子都可选、都各自吞异常**——观察钩子不许影响子 agent 的结果；
3. **`:settle!` 在 `finally` 里调**，因此 kill / 超时 / 崩溃三条路径都覆盖得到：
   `kill!` 会 `deliver` 一个 `{:error :killed}` 并中断 worker，worker 的 finally
   仍然跑，读到的就是那个 outcome。

`delegate-tool` / `fanout-tool` / `management-tools` 只是把 config 里的
`:observer` 原样塞进 spec，外加 `:subagent-name`（缺省取工具名，fanout 取
`"<tool-name>#<idx>"`）。

### 3.6 codec 映射与开关

`agui.codec/->agui` 加三个 case，外加一个统一的 tag 直通：

| 中立 | AG-UI |
|---|---|
| `:subagent/started` | `SUBAGENT_STARTED {subagentRunId, name, description=task, parentSubagentRunId?, parentToolCallId?}` |
| `:subagent/finished` `:outcome :success` | `SUBAGENT_FINISHED {subagentRunId, outcome:{type:"success"}}` |
| `:subagent/error` | `SUBAGENT_ERROR {subagentRunId, message, code=(name (:class error))}` |
| `:subagent/finished` `:outcome :killed|:timeout` | **也发 `SUBAGENT_ERROR`**，`code` = `"killed"` / `"timeout"` ——AG-UI 没有这两种收尾，同 `:run/cancelled → RUN_FINISHED + result.status` 的既有取舍：宁可换个类型说清楚，不要造协议外的类型 |
| 其余所有事件 | `(cond-> agui (:subagent-run-id ev) (assoc :subagentRunId …))` |

**一个差点漏掉的地方**：`->agui-events` 里思考块的**外层括号**（`REASONING_START` /
`REASONING_END`）是就地合成的、不走 `->agui`，所以归属得单独打一遍。漏了的表现是
子 agent 的思考块「括号留在对话里、内容进了 console」。有测试钉住。

`:parentSubagentRunId` **只打在 `SUBAGENT_STARTED` 上**（协议里只有它有这个字段；
虽然 passthrough 不会报错，但没必要多发）。

**开关**：`runtime` 新增 `:subagent-events?`，**缺省 `false`**。

- 缺省关的理由不是保守，是 §1.2 那条实测：`@ag-ui/client` ≤ 0.0.57 会因为一条未知
  事件类型掐断整条 SSE 流，而这**发生在 transport 层**，前端写什么都救不回来。
  上游自己也是 opt-in，且为此 pin 了 canary；
- 一个开关而不是两个（tag / lifecycle 分开）：`subagentRunId` 单独发对老客户端是安全的
  （passthrough），但**单独发它没有用**——没有 `SUBAGENT_STARTED` 就没有 name、
  没有树形，消费方只拿到一串陌生 id。分成两个旋钮是为对称而对称（§1 四问第三问）；
- 关的时候**一条都不发**：`observer-factory` 返回 `nil`，`agent-fn` 收到的
  `:subagent-observer` 就是 `nil`，`delegate-tool` 走今天的老路径，**零开销**。

### 3.7 两处诚实边界（照 §4.8「取消有多大力」的写法）

**(a) `parentToolCallId`：S4 已解决，但解法值得记下来。**
工具 handler 本来拿不到自己的 tool-call id：`chat-client/invoke-tool` 的入参是
`(chat-client fn-key args tool-context)`，ToolContext 里没有它（`agui.emit` 的 ns
docstring 早就记过：「`:tool` 链的 ToolRequest 两个都没有 id」）。

- **不猜**：父发射器的 `:open-tools` 里「已 `ended?` 但还没 result」的那条**通常**
  就是委派工具——但同一批里两个委派并发时有两条都开着，猜必然挂错一条。
  空着比挂错好，挂错的表现是「结果贴在了另一张卡片上」，没人会怀疑是框架的错；
- **正解是 `react/invoke-one` 里的一行**：那一层手里就有 `tc`，把
  `:tool/call-id` 钉进**这一次调用的** context 快照。钉在 per-call 快照上而不是
  轮初那份，所以它既不随 `:writes` 折叠外泄，也不进暂停快照；
  发状态快照那一侧的过滤（§3.8）是第二道。

**(b) 异步子 agent（`management-tools`）越过父 run 终态之后就静音。**
`spawn_subagent` 立刻返回，父 run 可能几秒后就 `:run/finished`；此后该 lane 的事件
被 §3.3 的守卫吞掉（计 drop，不占号）。用户看到的是：一条 `SUBAGENT_STARTED`，
然后没有下文，结果要等 LLM 下一轮调 `subagent_result` 才回到对话里。

- 这**不是 bug，是契约 2 的直接后果**：run 的终态必须是它的最后一条事件。
  要让 lane 活过父 run，得先改契约 2（引入「不属于任何 run 的会话级事件」），
  那是另一份文档的事；
- **重启条件**：出现「异步子 agent 要在前端持续显示进度」的真实场景时重开，
  且届时按 §1.3「重启须重新设计」，不得直接把这里的 lane 概念套上去。

### 3.8 顺带修一个既有 bug：`emit-state!` 把整个 tool-context 当业务状态发

`agui/emit.clj` 的 `emit-state!` 是 `(dissoc context :conversation-id)` 之后整块发
`STATE_SNAPSHOT`。也就是说 **tool-context 里任何一个非业务键都会漏给前端**——
而 tool-context 本来就允许装活对象（`pause/strip-unserializable` 存在的全部理由
就是「混进 `:chat-client` 这类活对象是正常的」）。今天塞一个 `:chat-client`
进 context，SSE 编码就会当场抛。

改法（S1 一并做，两行）：在 `context` 里立一个显式的框架键集合

```clojure
(def framework-keys "不属于业务状态、不进 STATE_SNAPSHOT 的键" #{:conversation-id :tool/call-id})
```

`emit-state!` 改成 `(apply dissoc context framework-keys)`，并额外滤掉
`(complement pause/edn-safe?)` 的值——该谓词今天是 `defn-`，一并放开为公开（它已经是两处要用的同一条判断，不是为本文新造的）。
**这与子 agent 无关也该做**，但 §3.7(a) 的那行改动会让它变成必须做。

---

## 4. 改动清单（逐文件）

| 文件 | 改动 | 量 |
|---|---|---|
| `agui/event.clj` | `emit!` merge `:tag`；新增 `silenced?` / `subagent-emitter` / `lane-id` / `silenced-count` / `start-subagent!` / `finish-subagent!`；`close-open!` 合成结果的措辞分 run / 子 agent | ~70 行 |
| `agui/subagent.clj` **（新）** | `observer-factory` + `finish-of`（manager outcome → 事件层形状）+ `lane-id`（`:attempt` 分代） | ~85 行 |
| `agui/codec.clj` | 三个新 case + `with-lane` 直通（`->agui` 与 `->agui-events` 合成的括号各打一次） | ~45 行 |
| `agui/runtime.clj` | `:subagent-events?` 配置；`launch!` 里把 `:subagent-observer` 加进 `agent-fn` 的入参 | ~6 行 |
| `agui/emit.clj` | `emit-state!` 滤框架键 + 非 EDN 值（§3.8）；`iteration-filter` 的 message-id 按 lane 分道（契约 4——lane 共用父 run 的 run-id，拿它打头会让两条并发 lane 的第 0 轮撞成同一个 id） | ~8 行 |
| `subagent/manager.clj` | spec 认 `:observer` / `:subagent-name` / `:task`；`do-run` 改双参并接 `:decorate` + `:chat-opts`；`spawn-worker!` 的 try/finally 里调 `:start!` / `:settle!`；entry 加 `:attempt`，`restart!` 递增 | ~60 行 |
| `subagent/delegate.clj` | 三个入口把 `:observer` / `:subagent-name` / `:task` / `:parent-tool-call-id` 透进 spec（fanout 每路一个名字、共享 tool-call id） | ~25 行 |
| `context.clj`（core） | `framework-keys` 常量 | ~3 行 |
| `pause.clj` | `edn-safe?` 由 `defn-` 放开为公开（同一条判断两处用，不复制第二份） | 1 行 |
| `react.clj` | `invoke-one` 把 `:tool/call-id` 钉进 per-call 的 context 快照 | ~10 行 |

**破坏性**：无。`:observer` 缺省 nil 时，manager/delegate 的行为逐字不变；
`:subagent-events?` 缺省 false 时，事件流一个字节都不变。

---

## 5. 施工台阶与验收

| 阶 | 内容 | 独立验收标准 |
|---|---|---|
| **S1** ✅ | `agui.event` 的 `:tag` + `subagent-emitter` + `finish-subagent!`；§3.8 的 state 过滤 | 纯单测：两条 lane 与父 run 交错发事件，`:seq` 连续无洞、无重号；父 run 终态后 lane 事件被吞且**不占号**；lane 的 message 开合互不干扰 |
| **S2** ✅ | manager/delegate 的 `:observer` 钩子（**不碰 agui**） | 用一个纯记录用的假 observer 验证：sync / fanout / kill / 超时 / 崩溃五条路径上 `:start!` 与 `:settle!` **各恰好一次**，且 `:settle!` 拿到正确 outcome；observer 抛异常不影响子 agent 结果 |
| **S3** ✅ | `agui.subagent` + codec + runtime 开关 | 端到端：一次 `delegate_tool` 委派产出 `SUBAGENT_STARTED → (lane 内的 TEXT_MESSAGE_* / TOOL_CALL_*，全带 subagentRunId) → SUBAGENT_FINISHED`，且 `RUN_FINISHED` 仍是最后一条；开关关掉时事件流与今天逐字节相同 |
| **S4** ✅ | `:tool/call-id` 进 tool-context → `parentToolCallId` 落位 | **同一批里两个委派 tool-call 并发**时，两条 `SUBAGENT_STARTED` 的 `parentToolCallId` 各不相同且与各自的 `TOOL_CALL_START` 对得上（fanout 的 N 路共享同一个 id——它们本就是一次调用的扇出，不是这条要防的场景）；折叠回去的 context 与 `STATE_SNAPSHOT` 里都看不到 `:tool/call-id`；**内联与 executor 两条批内调度路径逐字段相同** |

嵌套（子 agent 再委派）在 S3 顺带验一个两层用例即可——它是
`observer-factory` 自递归的自然结果，不单独立阶。

## 6. 测试清单

- `seq` 无洞：父 run + 3 条并发 lane，共 N 条事件，`(= (range 0 N) (map :seq …))`
- 终态良构：lane 在父 run 终态**之后**再发 5 条，最终事件序列的最后一条仍是终态，
  且 `drops` 计数 = 5、`:seq` 最大值不变（这条钉的是「守卫在取号之前」）
- lane 隔离：两条 lane 交替发 token，各自的 `TEXT_MESSAGE_START/END` 配对，
  `messageId` 不互串
- 嵌套：两层委派，内层 `SUBAGENT_STARTED.parentSubagentRunId` = 外层 lane id
- observer 生命周期：kill / timeout / crash 三条路径 `:settle!` 各一次
- observer 抛异常：子 agent 结果与不挂 observer 时逐字相同
- 开关关闭：事件序列与改动前的黄金样本逐字节相等（回归防线）
- 老客户端兼容：给既有事件加 `subagentRunId` 后，codec 输出仍能被
  现有 `codec_test` 的断言接受（passthrough 语义在我们这侧的体现）

## 7. 明确不做（否决记录）

| # | 方案 | 否决理由 | 重启条件 |
|---|---|---|---|
| 7.1 | **`delegations` 共享状态槽**（CopilotKit 文档主线的做法：委派日志写进 shared state，前端渲染那个数组） | 那是**为没有事件归属的框架准备的补偿**——上游自己在 subagent 事件落地后就改用事件流了。我们有事件流，再建一个数组就是 §8.1「第二真相店」。用户真想要一份可回放的委派日志，用 `:writes` + `:state-slots` 自己攒一个，几行的事 | 出现「委派日志必须跨 run 存活且被 LLM 读回」的场景（那属于 §7.1 跨 run 共享状态，不属于本文） |
| 7.2 | **线程隔离**（deep-agents-finance-erp 的做法：靠 OS 线程边界挡住子 agent 事件） | 我们**天然就是隔离的**（子 agent 是新 ChatClient + 新虚拟线程，压根没有传导）。上游需要那一招是因为 LangChain callback 会自动传播；对我们它解决的是一个不存在的问题 | 永不（前提在我们这儿反了） |
| 7.3 | **manager 直接认识发射器** | 反向依赖（client → agui）不存在，且会把 AG-UI 知识漏进 client 模块，违反「协议知识全在 codec 层」 | 永不 |
| 7.4 | **子 agent 的 HITL**（AG-UI `SUBAGENT_FINISHED.outcome = suspended + interruptIds` 已经留好了位置） | 四问第一问就落在「假想」列：子 agent 缺省 `:memory false` + 独立 conversation-id，既没有 PauseStore 也没有 resume 入口；真要做，是「子 agent 的暂停如何在父 run 的 resume 里被答复」这一整套语义，不是加一个字段 | 出现「子 agent 内的工具需要人工审批」的具体场景。届时先设计 resume 路由，再谈事件 |
| 7.5 | **lane 级插件 transform** | `:transform` 槽被终态守卫占用；插件实例有状态，多 lane 共用会被交错污染（§3.4） | 「子 agent 的工具调用也要渲染成 activity 卡片」的真实需求出现时 |
| 7.6 | **让 `stop!` 连带 kill 子 agent** | runtime 不认识子 agent（§1.3），要连带就得建一张 run → lane 的反查表，那正是 §3.3 禁的「新通道」。今天的答案是：子 agent 的生杀在 `manager`，由委派工具自己的 timeout / `kill_subagent` 管 | 出现「一次 stop 必须止血所有在跑的子 agent」的具体场景；届时的正解多半是让 `delegate-tool` 认 `:cancel-token`，而不是让 runtime 认子 agent |

---

## 相关文档

- [`agent-runtime-design.md`](agent-runtime-design.md) —— 事件模型与三条契约的出处（§4.2 / §4.6 / §6.3）
- [`design-principles.md`](design-principles.md) —— §1 四问、§3 边界与「没有隐式通道」
- [`filter-chain-design.md`](filter-chain-design.md) —— `emit/attach` 挂 `:iteration` filter 的依据
