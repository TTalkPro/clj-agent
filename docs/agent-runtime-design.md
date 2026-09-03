# Agent Runtime 设计：借鉴 CopilotKit runtime 的「后台机制」，原生实现

> **状态：✅ 已实施并**真机验证**（2026-09-03 设计定稿、当日落地 S0–S3 并跑通
> MiniMax 真实端点六场景）。全套 455 tests / 2042 assertions / 0 failures
> （基线 416/1876）。**
> 落地形态：新建模块 `modules/clj-agent-agui/`（5 个 ns）+ 框架侧一处改动
> （§6.1）+ `examples/copilotkit/` 两份示例。施工与设计的差异记在 §9.8。
>
> 起因：`~/workspace/CopilotKit` 的 `packages/runtime`（v2）把「agent run 与 HTTP
> 请求解耦」这件事做成了一套完整机制。问题是**能否原生实现**，从而不需要
> CopilotKit 的 remote agent（不需要在 Node 侧起一个 runtime 再把 Clojure 当远端
> agent 挂上去）。本文结论：**能，而且缺口是真的——但要抄的只有一半。**
>
> 先读 [`design-principles.md`](design-principles.md)：§1 无真实需求不建、
> §2 框架无关、§3 边界。本文对三条逐条对账（§3 章）。
> HITL / 暂停语义见 [`hitl-timeline-design.md`](hitl-timeline-design.md)，
> filter 四链契约见 [`filter-chain-design.md`](filter-chain-design.md)，
> 异步入口见 [`async-chat-model-design.md`](async-chat-model-design.md)。

---

## 0. TL;DR

CopilotKit runtime 的价值**不在它的 HTTP 层**（那是 express/hono/CORS/telemetry，
与我们无关，且 §2 明令不进库），而在中间那个东西：
**一个「会话 → 正在跑的 run → 事件流」的注册表**。它让 run 的生命周期不再由
HTTP 请求的生命周期决定。

我们缺的正是这一块。现状是：`chat-stream` 的 token 直接推给调用方的 sink，
**sink 死了这轮的输出就没了**；`chat-async` 的 run 不占请求线程，但**没有第二个
入口能观察它、停它、或在断线后接回它**；「一个会话一个 agent、别并发」目前是
README 里的一句警告，不是结构上的不变量。

| CopilotKit 的机制 | 判定 | 去处 |
|---|---|---|
| run 与请求解耦（立即起跑，响应只是订阅者之一） | ✅ **吸收** | §4.3 `start-run!` |
| 每会话一个事件缓冲 + `connect()` 断线重连 | ✅ **吸收，但改形状** | §4.5：**有界**缓冲 + `:seq` 偏移续传，不是无界 ReplaySubject |
| 单会话单 in-flight run + `throw`/`supersede` 策略 | ✅ **吸收** | §4.4 |
| 跨请求 `stop(threadId, runId?)` | ✅ **吸收** | §4.3 `stop!` |
| 终态良构保证（`finalizeRunEvents`：补关未闭合的块 + 必有终态） | ✅ **吸收** | §4.6 |
| 每 run 独立的「停止意图」holder（不是共享 flag） | ✅ **吸收（这是他们踩过的坑）** | §4.6 |
| `compactEvents`（delta 折叠成 snapshot 供重放） | ❌ **不建** | 消息真相在 ChatMemory，不建第二个真相店（§8.1） |
| 事件日志持久化（sqlite-runner / Intelligence） | ❌ **不建** | durable execution 已在 `hitl-timeline-design.md` §3.4 出界（§8.2） |
| `AgentRunner` 抽象协议（可换后端） | ❌ **不建**（只有一个实现） | §8.3 记否决理由与重启条件 |
| 进程全局单例 store | ❌ **反着做** | runtime 是显式值；他们自己在文档里警告 limits clobber（§8.4） |
| `maxBytes` 字节记账 + clamp-and-warn 归一化 | ❌ **不建** | 防御性机器（§1.3）；且他们自己注明它管不住 in-flight run（§8.4） |
| hooks / middleware / CORS / express·hono·node 适配 | ❌ **不建** | Ring middleware 几行等价（§1 四问全落假想列）；且 §2 直接否 |
| threads / memories / suggest / transcribe / telemetry 路由 | ❌ **不建** | 那是产品功能，不是机制 |

**外加两件他们没做、我们白送的事**：
1. **HITL 跨请求可用**。我们已有暂停/resume + PauseStore，但今天 `resume` 必须
   拿到**同一个进程内的同一个 agent 对象**。有了注册表，任意请求线程凭
   conversation-id 就能 resume——审批按钮是另一个 HTTP 请求，这才成立。
   CopilotKit 没有一等的暂停态。
2. **重连按偏移续传**。事件带单调 `:seq`，重连传 `:since`，只补缺口。
   CopilotKit 的 `connect()` 每次把整个历史重放一遍（`in-memory.ts:859`）。

**落地形态（用户拍板 2026-09-03）**：**新建模块 `clj-agent-agui`，不改既有 agent**。
框架侧**只有一处必须动**——`resume` 收不到 `:on-token` / `:cancel-token`，导致 HITL
第二段既不能流式也不能取消（§6.1，P0）；其余八项逐条判为「agui 内绕开」或「建议但
非前置」（§6.9 一览）。能力缺口另有八条，其中真缺的只有「跨 run 共享状态」，
且升级路径早已写死（§7.9 一览）。

---

## 1. 现状盘点（证据）

### 1.1 run 的寿命 = 调用方的寿命

```clojure
;; examples/streaming/http_kit_example.clj
(defn- run-stream! [a message {:keys [emit! done! fail!]}]
  (let [token (st/make-cancel-token)]
    (future (agent/chat-stream a message (fn [t] (emit! (:token t))) {:cancel-token token}))
    token))                       ;; 连接关闭 → (st/request-cancel! token)
```

四个 streaming 示例都是这个形状，而且**只能是**这个形状：`on-token` 是
push 契约，token 推给谁由调用方决定，框架不留副本。于是：

- 浏览器刷新 / 切网 / 手机锁屏 → 这轮的输出**没有任何地方拿得回来**，
  只能取消重来（示例里就是这么写的）；
- 想让第二个页签旁观同一会话 → 没有入口；
- 想在另一个请求里按「停止」→ cancel-token 在起跑那个闭包里，别人拿不到。

### 1.2 「一个会话一个 agent、别并发」是警告，不是不变量

`simple-agent` 的 docstring 明写：

> 线程安全：单个 agent 实例不可被多线程并发 chat/resume。……并发请按会话各建一个 agent

`chat-async` 的 docstring 再写一遍：

> ⚠️ agent 的 state-atom 是单会话状态机：**同一个 agent 上不要并发多个 chat-async**

这是对的诊断，但**责任全甩给了用户**：会话→agent 的映射表、串行化、超时清理、
进程退出时的收尾，每个 web 接入方各写一遍。`design-principles.md` §1.3 那句
「能立不变量的，不建机器」在这里指向的恰恰是**建这个东西**——注册表是唯一能把
「一个会话一个 agent，且同时只有一个 run」变成结构事实的地方。

### 1.3 HITL 有全套语义，但没有跨请求的入口

`resume` 需要 `agent` 对象本身（`(resume agent decision payload)`）。PauseStore
解决的是**跨进程重启**（重建 agent 再 resume），没解决**跨请求**：审批 UI 的
「同意」是另一个 HTTP 请求，它手上只有 conversation-id。今天的接法是应用层自己
维护一张 conv-id → agent 的表——又是 1.2 的那张表。

**三条缺口指向同一个缺失物**，这就是本文要建的东西。

---

## 2. CopilotKit runtime 到底是什么

去掉 telemetry / Intelligence / channels / 三种 web 框架适配之后，`packages/runtime`
的骨头只有三个文件：

| 文件 | 是什么 |
|---|---|
| `runner/agent-runner.ts`（68 行） | 接口：`run` / `connect` / `isRunning` / `stop` |
| `runner/in-memory.ts`（1066 行） | 唯一的开源实现：会话表 + 事件缓冲 + 生命周期 |
| `handlers/sse/*.ts`（各 ~50 行） | 把上面那个 Observable 编码成 SSE |

第三层薄得可以忽略（真正的机制在第二层），这本身就是结论：**HTTP 是最外面
那一层壳，可以整个不要**。

### 2.1 机制拆解

```
POST /agent/:id/run ──► runner.run() ──┬─► runAgent() 立即起跑（不 lazy）
                                       │      │
                                       │      └─► onEvent ─┬─► runSubject  ─► 本次 HTTP 响应
                                       │                   └─► nextSubject ─► store（供 connect）
                                       └─► 返回 Observable（响应订阅它）

GET  /agent/:id/connect ─► runner.connect() ─► 历史事件重放 ─► 桥接到 in-flight subject
POST /agent/:id/stop    ─► runner.stop()    ─► agent.abortRun() + 标记本 run 的停止意图
```

要点四条，每条都对应我们的一个缺口：

1. **`runAgent()` 同步起跑，返回值只是订阅句柄**（`in-memory.ts:849`
   注释明写 "Start the agent execution immediately (not lazily)"）。
   HTTP 响应挂了，run 照跑，事件照进 store。
2. **两个 subject**：一个给本次响应，一个给 store/后来者。**这是解耦的全部秘密**——
   输出有副本，所以断线不等于丢失。
3. **`connect()` = 重放 + 桥接**：先把历史事件推给新订阅者，再把 in-flight
   的 subject 接上去（`in-memory.ts:859-908`）。
4. **停止意图按 run 存**（`RunFinalizeControl`，`in-memory.ts:153`）：
   `stop()` 与 supersede 都改**那个 run 自己**的 holder，而不是 store 上的共享
   flag——否则后来的 run 一重置 flag，被替换掉的那个 run 就会被标成
   `RUN_ERROR` 而不是干净的结束。**这是他们修过的 bug，注释里写着**，我们直接
   继承结论（§4.6）。

### 2.2 他们做得不好、我们不跟的四处

| 处 | 问题 | 证据 |
|---|---|---|
| `sharedStore` 是**进程全局单例** | 两个 runner 用不同 limits 构造 → 后构造的覆盖全局，只能打一行 warn 提醒「你把所有会话的 bound 改了」 | `LIMITS_CLOBBER_GUIDANCE`，`in-memory.ts:126` |
| 每 run 一个 `ReplaySubject(Infinity)`（两个） | 无界。`maxBytes` **管不住 in-flight run**，他们自己在 `InMemoryLimits` 的注释里写明了 | `in-memory.ts:28-36` |
| `connect()` 全量重放 | 没有偏移概念，重连一次就把整个会话的事件重推一遍 + 一次 `compactEvents` | `in-memory.ts:866-880` |
| `getThreadMessages` 只做数组层浅拷贝 | 返回的 message 对象与 store 内部共享引用，改一个字段就污染快照——注释里挂着「known limitation」 | `in-memory.ts:986` |

前三处我们有更好的做法（§4.5）；第四处在 Clojure 里根本不存在（不可变值）。

---

## 3. 与三条原则的对账

### 3.1 §1 四问（立项判据）

| 问题 | 回答 |
|---|---|
| 现在有人要用吗？ | 有。本轮的直接需求（把 CopilotKit 前端直接接到 Clojure 侧）；且 `examples/streaming/` 四个示例 + `async_luminus_handler_example.clj` 全部在手写会话表与取消接线 |
| 不建的话用户怎么办？ | **做不到**「断线重连接回同一轮」——这不是几行能补的：要事件副本、要缓冲、要扇出、要终态良构、要并发策略。CopilotKit 为此写了 1000 行，且过程中踩过 §2.1 第 4 条那个 bug |
| 换来的是什么？ | **能力**：断线重连、旁观、跨请求 stop、跨请求 resume。不是「更声明式」也不是「更像谁」 |
| 触发条件写得出吗？ | 写得出：「用户刷新页面后要看到这一轮已经产生的输出」「审批按钮是另一个请求」「两个页签看同一个会话」 |

四问全落「真实」列，**立项成立**。

但**范围要卡死**：立项成立的是「run 生命周期与事件扇出」，**不是**
「一个 web 服务」。凡是 CopilotKit 里属于 HTTP/产品的部分，逐条按四问再问一遍，
结论全是不建（§8）。

### 3.2 §2 框架无关：这个东西凭什么能进库

§2 的原文举过一个反例：「给 core 加个 SSE 适配器——§2 直接否」。所以必须先答：
**runtime 是不是 web 应用？不是。** 判据是它依赖什么、输出什么：

- **零 web 依赖**：注册表 = map + 锁 + 环形缓冲；run = 虚拟线程（`async/vthread`，
  已有）；订阅 = **回调**（`on-event` + 返回退订函数）——与 §2.1 认定的
  「最小 push 契约」`on-token / on-complete / on-error + cancel` 同一族；
- **不产出 HTTP 任何东西**：不认识 Request/Response、状态码、header、SSE 帧格式；
- **SSE / WebSocket / 路由表全部在 `examples/`**（已有四个 server 示例，照着扩）。

一句话判据：**runtime 里出现任何「HTTP 概念」即违反 §2**。AG-UI 编解码是
纯数据函数（event map → JSON-able map），不含 HTTP 概念，故不违反——但它是
**外部协议知识**，放置见 §5.3。

### 3.3 §3 边界：注册表持有 agent，会不会变成隐式通道

不会，但要写死三条：

| 项 | 约束 |
|---|---|
| agent 从哪来 | 用户给 `:agent-fn`（`(fn [conversation-id] agent)`）。runtime **不构造** ChatClient、不选 TCM、不挂 filter——那是 §3 的边界内事务，runtime 不插手 |
| 会话之间 | 各自一个 agent 实例、各自一把锁。runtime 不在会话间共享除**缓存的 agent 实例**以外的任何东西 |
| 子 agent | runtime 完全不感知。子 agent 走 `subagent-config`（§3.3），不因为父 run 在注册表里就有了什么新通道 |
| ambient 状态 | run 跑在虚拟线程上，**不做 `bound-fn*` 传导**——与 `spawn-worker!` 的既有决定一致（§3.3「ambient 状态跨 delegate 不传导」）。调用方要传东西走 `opts` 显式传 |

**runtime 立的新不变量**（这是它存在的一半理由）：

> **一个 conversation-id 在同一 runtime 内恰好对应一个 agent 实例，
> 且同时至多有一个 in-flight run。**

这条今天靠 README 警告维持（§1.2），建完之后靠结构维持。

---

## 4. 设计

### 4.1 词汇（不引入 "thread"）

CopilotKit 叫 thread，我们**不跟**——Clojure 代码里 thread 已经是 JVM 线程，
再叠一个含义是自找的。沿用既有词汇：

| 我们 | CopilotKit | 说明 |
|---|---|---|
| **conversation-id** | threadId | 已是 ChatMemory / PauseStore / timeline 的主键，不新造 |
| **run** | run | 一次 `chat` 或一次 `resume` 的完整执行。turn 内可能暂停，暂停即 run 的终态（§4.7） |
| **runtime** | CopilotRuntime + AgentRunner | 显式值，用户可建多个 |
| **event** | AG-UI BaseEvent | 中立 EDN，见 §4.2 |

### 4.2 事件模型

中立事件，**不是** AG-UI（AG-UI 是 §5 的一层编码）。每个事件必带
`:type` / `:run-id` / `:seq` / `:ts`：

```clojure
{:type :run/started      :input {:message "..."} :conversation-id "c1"}
{:type :message/started  :message-id "m1" :role :assistant}
{:type :message/delta    :message-id "m1" :text "北"}          ; ← on-token :token
{:type :reasoning/started :message-id "m1-reasoning"}          ; 思考块开（独立消息）
{:type :message/thinking :message-id "m1-reasoning" :text "…"} ; ← on-token :reasoning-token
{:type :reasoning/ended  :message-id "m1-reasoning"}          ; 思考块合
{:type :message/ended    :message-id "m1"}
{:type :tool/started     :tool-call-id "tc1" :name "get_weather"}
{:type :tool/args        :tool-call-id "tc1" :args {:city "北京"}}
{:type :tool/ended       :tool-call-id "tc1"}
{:type :tool/result      :tool-call-id "tc1" :content "晴" :error nil}
{:type :state/snapshot   :state {...}}                          ; tool-context 状态槽
{:type :run/paused       :reason "需要审批" :pending-tool {...}} ; 终态·clj-agent 独有
{:type :run/finished     :text "..." :tool-calls-made [...]}     ; 终态
{:type :run/cancelled    :text "..."}                            ; 终态
{:type :run/error        :error <canonical error>}               ; 终态
```

三条契约：

1. **`:seq` 在一个<u>会话</u>内单调递增，无洞**（不是每 run 重新计数）。这是重连
   续传的全部依据（CopilotKit 没有这个概念，所以只能全量重放）。
   **为什么按会话而不按 run**：HITL 下一次对话由多个 run 组成（run → `:run/paused`
   → resume 起 run'），订阅挂在会话上跨 run 连续；若 seq 按 run 重置，`:since`
   就得是 `{run-id, seq}` 二元组，重连时还要先问「上一个 run 是哪个」。
   会话级单调让 `:since` 退化成一个数，正好对上 SSE 的 `Last-Event-ID`；
   事件自带 `:run-id`，前端要分段照样分得出来；
2. **恰好一个终态事件**，且它是该 run 的最后一个事件（§4.6）；
3. **错误值是 canonical error**（`model.error`），不是字符串——
   [`error-model-unification.md`](error-model-unification.md) 的四条边界规则照旧适用。

### 4.3 API（`im.ttalk.agent.agui.runtime`）

```clojure
(defn runtime
  "构造运行时。显式值，无全局单例。
   :agent-fn         (fn [conversation-id] agent) —— 会话→agent 装配（必填）
   :on-concurrent    :reject（缺省）| :supersede            —— 见 §4.4
   :buffer-size      每 run 事件环形缓冲上限（缺省 512）      —— 见 §4.5
   :idle-ttl-ms      会话空闲多久驱逐（缺省 30min；有 run / 有订阅者 / 暂停中的不驱逐）
   :supersede-wait-ms supersede 时等旧 run 收尾的上限（缺省 5s）
   :executor         run 跑在哪（缺省虚拟线程）"
  [opts])

(defn start-run!  [rt conv-id message]        [rt conv-id message opts])
;; => {:run-id "r1" :status :started} | {:status :busy :run-id <在跑的那个>}
;; 立即返回，run 在后台跑；opts 直通 agent/chat-stream 的 opts

(defn resume-run! [rt conv-id decision]       [rt conv-id decision payload])
;; 跨请求 HITL：凭 conv-id 恢复，不需要持有 agent 对象

(defn subscribe   [rt conv-id {:keys [since on-event on-close]}])
;; => (fn unsubscribe! []) —— :since 是上次收到的 :seq，缺省 = 只订阅新事件

(defn stop!       [rt conv-id] [rt conv-id run-id])   ;; => true/false
(defn run-status  [rt conv-id])   ;; => {:state :run-id :stopping? :pending-tool :seq :subscribers}
(defn awaiting    [rt conv-id])   ;; 暂停中？=> {:run-id :reason :pending-tool}
(defn shutdown!   [rt])                                ;; 取消全部 in-flight，等收尾，清表
```

**没有 `run-and-wait` 之类的阻塞入口**——要阻塞的场景本来就该直接用
`agent/chat`，不必经过 runtime（§1：能几行做到的不长 API 面）。

### 4.4 并发策略：一个会话同时一个 run

`start-run!` 拿会话锁后判：

| 策略 | 已有 in-flight run 时 | 用途 |
|---|---|---|
| `:reject`（缺省） | 返回 `{:status :busy}`，不起新 run | 后端语义严格；UI 自己置灰输入框 |
| `:supersede` | 取消旧 run（旧 run 落 **`:run/cancelled`**，不是 `:error`），等它收尾，再起新 run | 聊天 UX：用户又发了一条 |

两条硬规则：

- **旧 run 的终态是 `:cancelled`**——这就是 §2.1 第 4 条那个坑：停止意图必须挂在
  **被停的那个 run** 上，不能是会话上的共享 flag，否则新 run 一重置，旧 run 的收尾
  就把自己标成 error（详见 §4.6）；
- **暂停中的会话不是 in-flight**。`:paused` 是终态，会话回到「无 run」但带
  `:awaiting-resume` 标记。此时 `start-run!` 的行为：**拒绝**，提示先 `resume-run!`
  或 `agent/reset!`——与 `simple-agent` 现有的 `cancel-pending!` 语义（新 turn 会取消
  未 resume 的暂停）**故意不同**，因为 web 场景下「用户没点审批就又发了一句」
  最好显式暴露，而不是静默丢弃一次审批。**此处标记为待拍板项（§9.4）。**

### 4.5 缓冲：有界 + 落后即 resync

CopilotKit：每 run 两个 `ReplaySubject(Infinity)`，外加完成后整份 events 进
historicRuns。我们不跟——**无界缓冲在服务端就是个内存炸弹**（他们自己承认
`maxBytes` 管不住 in-flight run）。

```
run 事件 ──► 环形缓冲（buffer-size，缺省 512 条）──┬─► 订阅者 A 的 on-event（同步调用）
                                                  └─► 订阅者 B ...
```

- **订阅者是回调，不是队列**。`on-event` 慢会拖慢 run？不会——emit 在 run 线程上
  同步调用回调，但**回调的契约是「立刻交出去」**（往 SSE channel 一 put 就返回），
  这与 `on-token` 的既有契约逐字一致（见
  [`token-stream-filter-design.md`](token-stream-filter-design.md) §2.1）。
  回调抛异常 → 该订阅者被摘除，run 不受影响（同 callbacks 的吞异常策略）；
- **`:since` 早于缓冲起点** → 发一个 `{:type :run/resync :messages <ChatMemory 快照>}`
  再接 live tail。**这就是为什么我们不需要 `compactEvents`**：折叠后的真相
  ChatMemory 里本来就有（`hitl-timeline-design.md` §4.1「对话历史 = 唯一持久状态」），
  不必在事件侧再造一份（§8.1）；
- **缓冲是会话级的**（不按 run 分桶），所以「run 结束后保留多久」这个旋钮
  在实现时并入了 `buffer-size`：最近 N 条一直在，正好封住「终态事件发出 ↔
  客户端重连」那个竞态窗口。挤出去的就没了——历史找 ChatMemory 要（§9.8）。

### 4.6 终态良构（借 `finalizeRunEvents` 的结论）

emitter 记着两个开集合：未 `:message/ended` 的 message-id、未 `:tool/result` 的
tool-call-id。run 无论怎么结束（正常 / 抛异常 / 被取消 / 暂停），收尾时：

1. 若已有终态事件 → **什么都不补**（AG-UI 侧的硬约束：终态之后不得再有事件，
   补出来的 closer 会被前端 verifier 判非法——他们踩过，见 shared 的注释与 issue #5812）；
2. 否则：先补关所有开着的块（`:message/ended` / `:tool/ended` + 一条说明性
   `:tool/result`），再发终态；
3. **终态类型取自「本 run 自己的停止意图 holder」**，不是会话上的共享状态：
   被 supersede / 被 `stop!` → `:run/cancelled`；抛异常 → `:run/error`；正常 → `:run/finished`。

> 这一条整个是从 CopilotKit 的注释里读来的教训，不是我们自己推的：
> `in-memory.ts:153` 的 `RunFinalizeControl` 就是为修「后来的 run 重置了共享 flag，
> 导致被替换的 run 被标成 RUN_ERROR」而引入的。**不重新踩一遍。**

对应的既有机器不动：消息侧的悬空 tool_use 由 `heal-dangling-tool-calls!` 兜底，
本节管的是**事件流**的良构，两者层次不同、互不替代。

### 4.7 HITL：暂停是 run 的终态，resume 是新 run

```
start-run! ──► run r1 ──► :run/paused（终态，缓冲保留）──► 会话进入 :awaiting-resume
                                                              │
              另一个 HTTP 请求（审批按钮）──► resume-run! ─────┘
                                                              ▼
                                                    run r2（续跑）──► :run/finished
```

- 订阅者**不需要重新订阅**：`subscribe` 挂在 conversation 上，跨 run 连续，
  `:seq` 也**跨 run 连续**（§4.2 契约 1）；事件带 `:run-id`，前端按 run 分段；
- `resume-run!` 内部就是 `agent/resume-async`，暂停快照的持久化、决策×载荷的
  五种形态、环境类暂停的 `:retry/:proceed` **全部照旧**
  （[`hitl-timeline-design.md`](hitl-timeline-design.md) §2）——runtime 只提供
  「凭 conv-id 找到那个 agent」这一件事；
- 进程重启后：`:agent-fn` 用同一个 `:pause-store` + `:memory` 重建 agent，
  `resume-run!` 照常工作（跨重启 HITL 是既有能力，runtime 不改它）。

### 4.8 取消有多大力（诚实说明）

沿用 [`tool-timeout-design.md`](tool-timeout-design.md) §2 的口径：**JVM 上取消 =
放弃等待 + 协作式中断，不是抢占式终止**。`stop!` 的实际效果：

1. `streaming/request-cancel!` 令牌置位 → provider 流式循环下次检查时退出；
2. react 循环在轮边界检查取消 → 不再发起下一轮；
3. **已经在跑的工具不会被杀死**——它跑完为止（写副作用照样发生）。

`stop!` 返回 `true` 的含义是「取消已登记」，**不是**「已经停了」。终态事件
（`:run/cancelled`）才是停稳的信号。文档与 API docstring 都要这么写，
不许含糊——含糊的取消语义比没有取消更坏。

### 4.9 事件从哪来（接线）与两个缺口

**不新增 callback**。事件由 runtime 在装配 run 时挂的三处采集：

| 事件 | 来源 | 现成？ |
|---|---|---|
| `:run/started` `:run/finished` `:run/error` | runtime 自己（包住 `chat-async`/`chat-stream` 的返回值） | ✅ |
| `:message/delta` `:message/thinking` | `on-token` 的 `{:token}` / `{:reasoning-token}` | ✅ |
| `:message/started` `:message/ended` | runtime 按 delta 的首尾合成（非流式 run 则由终态文本合成一整条） | ✅ |
| `:tool/started` `:args` `:ended` `:result` | **`:iteration` 链**：请求侧 `:messages` 是本轮 delta，结果侧 `:messages` 含带 `:tool-call-id` 的 tool 结果消息 | ✅（见下） |
| `:state/snapshot` | `:iteration` 结果的 `:context`（状态槽折叠后） | ✅ |
| `:run/paused` `:run/resumed` | agent 结果的 `:paused` 状态 + `:on-interrupt` / `:on-resume` | ✅ |

> **为什么工具事件走 `:iteration` 链而不是 `:on-tool-call` 回调**：回调签名是
> `(fn [tool-name args])`，**拿不到 tool-call-id**，而事件流要靠 id 把
> start/args/result 串起来。`:tool` 链也不行——它的 ToolRequest 是
> `{:function :args :context}`，同样没有 id，且**跑在并行任务里**
> （`filter-chain-design.md` §2.1 的警示）。`:iteration` 链每轮一次、在循环线程上、
> 手里有完整的本轮消息，是唯一对的采集点。

**两个已知缺口，先写明再决定**：

1. **工具参数无法增量流式**（AG-UI 有 `TOOL_CALL_ARGS` 的分片语义）。我们的
   `on-token` 只吐文本/思维 token，工具参数在 provider 侧就已经聚合完了。
   → **本轮不做**：前端表现为「工具调用整块出现」，功能不缺，只是没有
   「参数正在生成」的动效。要做得动 provider 流式解析，成本与收益不成比例；
2. **中立消息没有 message-id**。事件里的 `:message-id` 由 runtime 按
   `<run-id>-<n>` 合成，**不落库、不进 ChatMemory**——事件是传输层的东西，
   不往真相店里加字段（§8.1 的同一条理由）。

---

## 5. AG-UI 互操作：「不需要 remote agent」的那一步

### 5.1 今天要接 CopilotKit 前端，链路是什么样

```
React（@copilotkit/react-core）──HTTP──► Node runtime（CopilotRuntime）
                                              │
                                              └─HttpAgent(AG-UI over SSE)──► 我们的 Clojure 服务
```

中间那个 Node 进程存在的唯一理由，就是 §2 拆解的那三层骨头。**我们把第二层
原生实现之后，它就没有理由了**：

```
React（@copilotkit/react-core）──HTTP(AG-UI over SSE)──► Clojure（runtime + 编解码 + 你的 web 栈）
```

### 5.2 需要对上的最小面

CopilotKit v2 前端只用四条路由（其余 threads/memories/suggest/transcribe 属产品
功能，缺席即降级，见 `core/fetch-router.ts`）：

| 路由 | 映射到 |
|---|---|
| `GET  {base}/info` | 静态 JSON：**`agents` 是以 id 为键的字典**（不是数组）+ 能力位 |
| `POST {base}/agent/:id/run` | `start-run!` + 把事件流编码成 SSE |
| `POST {base}/agent/:id/connect` | `subscribe`（带 `:since`）+ SSE |
| `POST {base}/agent/:id/stop/:threadId` | `stop!`（**threadId 是路径段，请求没有 body**） |

事件映射（一一对应，机械翻译）：

| 我们 | AG-UI |
|---|---|
| `:run/started` / `:run/finished` / `:run/error` | `RUN_STARTED` / `RUN_FINISHED` / `RUN_ERROR` |
| `:message/started` `:delta` `:ended` | `TEXT_MESSAGE_START` `TEXT_MESSAGE_CONTENT` `TEXT_MESSAGE_END` |
| `:reasoning/started` `:message/thinking` `:reasoning/ended` | `REASONING_START`+`REASONING_MESSAGE_START` / `REASONING_MESSAGE_CONTENT` / `REASONING_MESSAGE_END`+`REASONING_END`。**思考是一条独立的 `role:"reasoning"` 消息**（自己的 message-id），前端有折叠面板原生渲染；`THINKING_*` 在 0.0.59 已 deprecated |
| `:tool/started` `:args` `:ended` `:result` | `TOOL_CALL_START` `TOOL_CALL_ARGS` `TOOL_CALL_END` `TOOL_CALL_RESULT` |
| `:state/snapshot` | `STATE_SNAPSHOT` |
| `:run/cancelled` | `RUN_FINISHED`（带 reason）——AG-UI 没有 cancelled |
| **`:run/paused`** | `RUN_FINISHED` + `outcome:{type:"interrupt", interrupts:[{id, reason, toolCallId, responseSchema, metadata}]}`（AG-UI interrupt 协议）。答复走**下一次 run** 的 `RunAgentInput.resume[]`，不是新端点。`/info` 报 `capabilities.humanInTheLoop.interrupts` |

### 5.3 落在哪：新建 `clj-agent-agui` 模块（用户拍板 2026-09-03）

**不改既有 agent，整块新建一个模块**（模块名 `agui`）。既有三模块的依赖方向
（Provider → Core ← Client）不动，agui 挂在最外层：

```
modules/clj-agent-agui/                       ;; 依赖 core + client；零 web 依赖
  src/im/ttalk/agent/agui/
    runtime.clj   ;; 会话注册表 + run 生命周期 + 订阅（中立：不含 AG-UI 概念）
    event.clj     ;; 事件模型 + 发射器（:seq 分配 / 开集合跟踪 / 终态良构 §4.6）
    emit.clj      ;; 接线：on-token + :iteration filter + callbacks → event（§4.9）
    codec.clj     ;; 中立事件 ⇄ AG-UI 事件；中立消息 ⇄ AG-UI 消息
    tools.clj     ;; AG-UI 前端 action → inline tool（见 §7.2）
  test/im/ttalk/agent/agui/…
examples/copilotkit/                          ;; SSE 帧 / 四条路由 / CORS / 鉴权（§2）
```

| 层 | 放哪 | 理由 |
|---|---|---|
| runtime / event / emit（中立机制） | `agui` 模块 | 今天只有一个消费者。**若将来第二个消费者出现**再下沉到 `clj-agent-client`——那是**移动**不是新建，不受 §1 管辖（§1.5「本原则管该不该有，不管叫什么、放哪」） |
| codec（AG-UI 协议知识） | `agui` 模块，**独立 ns** | 外部协议知识不渗进 runtime 本体：runtime 发中立事件，codec 是可替换的一层（对称于「core 收回厂商 wire 知识」，见 [`response-path-consolidation.md`](response-path-consolidation.md)） |
| SSE / 路由 / CORS / 鉴权 | **`examples/copilotkit/`** | §2。照 `examples/streaming/http_kit_example.clj` 那份扩 |

**同批要动的登记处**（照 `modules/README.md` 的既有约定，漏一处就静默失效——
`bb.edn` 的注释里记着 client 曾长期缺席测试列表这件事）：
`deps.edn` 的 `:paths` 与 `:test :extra-paths`、`bb.edn` 的 `modules` 表、
`build.clj` 的 modules 表、`modules/README.md` 的模块说明、CI matrix。

---

## 6. 对现有框架的强化清单

前提：**agui 是新模块，默认不改 agent**。所以每一条都先问「agui 能不能在模块内
绕开」——绕得开的按 §1 一律不改框架，只有绕不开的才动。

### 6.1 必须改：`resume` 收不到 `:on-token` / `:cancel-token`（P0，唯一的施工前置）

证据链三段：

1. `react/build-chat-opts`（react.clj:58-70）**支持**这两个键，会原样带进循环；
2. `simple-agent/chat-stream`（simple_agent.clj:440）在 `build-invoke-opts` **之后手动**
   `assoc` 它们进去；
3. `resume-prep`（simple_agent.clj:453）构造的 opts 是
   `{:context :tool-gate :on-env-error :callbacks (+payload +system-prompts)}`
   ——**两个键一个都没有**，且 `resume` 的签名 `[agent decision (payload)]` 没有
   opts 位可以塞。

后果：HITL 的第二段——**审批之后那段续跑，往往正是最终答案**——不流式、不可取消。
AG-UI 上的表现：点「同意」后界面静止若干秒，然后整段文字啪地出现；期间点「停止」无效。

**绕不开**：`resume-prep` 是 private，agui 只能调 `agent/resume`；绕过 simple-agent
直接调 `react/resume` 意味着 agui 自己管 pause-store、state-atom、callbacks、finalize
——等于重写半个 simple-agent，而那正是 §3 的边界内事务，新模块不该接管。

四问：调用方具体（agui）；不建的话**做不到**（不是几行）；换来的是**能力**
（HITL 第二段的流式与取消）；触发条件写得出（「审批完之后的回答要边生成边显示」）。
**立项成立。** 破坏面：新增 arity，旧调用一字不动。

### 6.2 建议同批做：`chat-async` 丢的是同两个键（同一个根）

`build-invoke-opts`（simple_agent.clj:386）只挑 `:tool-choice` / `:system-prompts`；
`chat-stream` 事后补两个键，`chat-async` 没补 → 异步入口既不流式也不可取消。

**绕得开**：agui 在虚拟线程上跑同步 `chat-stream`，两样都有；阻塞一根虚拟线程
正是虚拟线程的用法，代价可忽略。

判定：**建议改，但不是阻塞项**。理由不是性能而是**一致性**——四个入口
（`chat` / `chat-stream` / `chat-async` / `resume*`）对同一组 opts 各补各的，
6.1 那个洞就是这么漏出来的。改法：把「循环认识的键」一次性收进 `build-invoke-opts`，
四个入口共用一份，`chat-stream` 那两行手动 assoc 随之删掉。

### 6.3 不改框架（但 agui 内必须硬性保证）：事件发射不能走 callbacks 的吞异常语义

`callbacks/invoke` 对任何 Throwable **静默吞掉并返回 nil**（callbacks.clj:30-41）。
对观察类回调这是对的（「回调抛异常不该中断主流程」），对**事件流**是错的：
吞掉一次发射 = `:seq` 出洞 = 断线续传（§4.5）从此对不上，而且**不会报错**。

判定：**不改框架**（改了会伤到它本来保护的东西）。改由 agui 承担不变量：

> 发射器自身永不抛：`:seq` 由发射器分配（不依赖回调返回值），入缓冲与扇出
> 各自 try/catch，抛异常的订阅者被摘除，run 不受影响。

用测试钉住（§9.5 第 11 条）。

### 6.4 不改：`:iteration` filter 的挂载要绕两下

- `create-agent` **拒绝** `:filters`（warn + dissoc，simple_agent.clj:134）；
- `common/build-chat-client` 只挂 memory-filter，**不透传** `:filters`（common.clj:66-80）。

于是 agui 挂事件采集用的 `:iteration` filter，只能用 `chat-client/build-chat-client`
自己拼 `[memory-filter agui-iteration-filter]`，再经 `create-agent :chat-client` 传进去。
**两行，绕得开** → 不改。记一笔：若第二个模块也要这么绕，再提
「`common/build-chat-client` 透传 `:filters`」。

### 6.5 不改：callbacks 只有一个槽

agui 要占 `:on-llm-result`（取 tool-call id）与 `:on-interrupt`；用户也想挂自己的。
合成器就是几行 → **agui 内部私有函数**，不进框架（§1：用户自己几行就等价的不长 API 面）。

### 6.6 不改（先绕）：每个 run 的工具集固定在装配期

AG-UI 的 `RunAgentInput.tools` 是**每个请求**带的前端 action 列表，每次可能不同；
而 `react/build-chat-opts` 只能按 tags 过滤**装配期就定好**的 registry（react.clj:40-56），
没有 per-invoke 注入。

**绕得开**：每个 run 现建一个 ChatClient（memory store / pause-store / conversation-id
共享）。这恰好是 §3「一个 ChatClient 绑定一个 TCM，这个绑定就是执行边界」的正用法
——每 run 一个边界，语义干净。

判定：**先绕，不改**。重启条件记在这里：**当 per-run 重建被实测证明是瓶颈**
（filter 预编译 + registry 建表出现在火焰图上），再提 `:extra-tools` 的 per-invoke 注入；
届时按 §1.3「重启时须重新设计」，不得直接捡回本节的形状。

### 6.7 建议（对 agui 之外也有价值）：中立消息缺 `:id`

中立消息是 `{:role :content :tool-calls :tool-call-id :blocks :writes}`（message.clj:42-120）
——**没有 id**。而 AG-UI 每条消息必须有 id，前端按 id 做 diff 与去重。

agui 的绕法是「按 conv-id + 位置合成」，但**位置会漂**：`replace-tool-results`
（react.clj:807）按 id 替换历史里的 tool 结果，`heal-dangling-tool-calls!` 会补消息。
漂了的后果是同一条消息在两次快照里 id 不同 → 前端渲染出重复气泡。

判定：**建议在框架侧加可选 `:id`**（构造函数生成，wire 层天然剥落——与 `:writes`
同款做法，`hitl-timeline-design.md` §4.5 有先例），对 timeline / 审计同样有用。
**但先用合成 id 跑通 S3，把「重复气泡」列为验收项去撞一次**：撞得到再改，
撞不到说明前端没那么敏感。（这条是 `provider-variant-design.md` 记的那种
「判据先卡一次再放行」的用法，不是拖延。）

### 6.8 建议（零破坏）：`chat` 只吃字符串

`(chat agent message)` 直接 `(msg/user message)`（simple_agent.clj:410）。AG-UI 的
用户消息可带图片等多模态内容，而中立层本来就支持（`msg/with-blocks` + provider 侧
multimodal 测试）。→ `chat` 同时接受**已构造的中立消息 map**，一个 `map?` 分支的事。

### 6.9 一览

| # | 项 | 判定 | 破坏面 |
|---|---|---|---|
| 6.1 | `resume` / `resume-async` 接 `:on-token` / `:cancel-token` | ✅ **必须改（P0，施工前置）** | 新增 arity |
| 6.2 | `chat-async` 同上；四入口共用 `build-invoke-opts` | ⚠️ 建议同批做 | 无（内部整理） |
| 6.3 | 事件发射不走 callbacks 吞异常语义 | ⛔ 不改框架，agui 立不变量 | — |
| 6.4 | `:iteration` filter 挂载绕两下 | ⛔ 不改 | — |
| 6.5 | callbacks 单槽 → 合成器 | ⛔ 不改（agui 私有） | — |
| 6.6 | per-run 动态工具集 | ⛔ 先绕（每 run 重建 ChatClient），记重启条件 | — |
| 6.7 | 中立消息可选 `:id` | ⚠️ 建议，先撞一次验收再定 | 新增可选键 |
| 6.8 | `chat` 接受中立消息 map | ⚠️ 建议 | 无 |

**只有 6.1 是施工前置**，其余都能在 agui 落地过程中按需推进。

---

## 7. 还缺什么（能力缺口，不是接线问题）

§6 讲的是「接线要动几处」；本节讲的是「有些东西我们压根没有」。

### 7.1 跨 run 的共享状态（AG-UI `STATE_SNAPSHOT` / CopilotKit `useCoAgent`）

**现状**：tool-context 的状态槽是 **turn 级草稿**——`hitl-timeline-design.md` §4.1
用户拍板：「每次 chat 从裸 context 起步，turn 内跨轮累积，暂停恢复是唯一跨越点」。
**AG-UI 要的是会话级且双向**：state 随每个 run 下发给前端，前端也能改了带回来。

缺口是真的，但**升级路径早就写死了**（同文 §4.5）：`:writes` 已经进历史，
将来跨 turn 就 `fold(reducers, 历史 writes)`，**不建独立快照店**（两处真相的老路）。

- **本轮 agui 怎么做**：把客户端带上来的 state 当作该 run 的**初始 context** 注入
  （`react/invoke` 的 opts 已支持 `:context`）——turn 级、不落库，与现有语义
  **逐字一致**；`:state/snapshot` 事件取自每轮 `:iteration` 结果的 `:context`。
  前端能看到 state 变化，只是它不跨 run 存活。
- **缺的那一半**：「从历史 `:writes` 折叠出当前状态」的函数还没人写。它**独立于
  agui 成立**，只在真出现「刷新页面后 state 要还在」的需求时才做。写进本文，
  是为了让下一个人别自己发明第二条路。

### 7.2 前端工具（client-side tools）——**不缺，是既有词汇的一个用法**

AG-UI 的核心玩法之一：agent 发出一个 tool call，**由浏览器执行**，结果随下一个
run 送回。看起来像要新建一套机制，其实三样都现成：

| AG-UI 要什么 | 我们已有的 |
|---|---|
| 前端 action 的 JSON Schema 进工具列表 | **inline tool map**：`{:name :description :parameters :handler}`，`:handler` 之外的键**原样就是**发给模型的 schema（chat_client.clj:93-108） |
| agent 发出 tool call 后挂起、等前端 | gate 判 `:pause` → 暂停快照自动落 PauseStore（§4.7） |
| 前端执行完把结果送回，模型继续 | `resume-run! :reply {:message 结果}`——「pending 工具不执行、载荷即其结果」正是 ask-user 语义（`hitl-timeline-design.md` §2.1） |

**零框架改动。** 唯一毛刺：`:reply` 的 payload 校验要求 `:message` 是**字符串**
（react.clj:948），而前端工具的结果通常是 JSON → agui 侧 encode 一次即可。

把这条单独写出来，是因为它把「AG-UI 前端工具」从一个看似要新建的机制，
降级成了**既有 HITL 词汇的一个用法**——这正是 §1 想要的结果。

### 7.3 客户端历史 vs 服务端历史：缺的是一次拍板

AG-UI 的 `RunAgentInput.messages` 是**客户端持有的完整历史**（CopilotKit 前端就是
这么发的）。我们的 memory filter 是**服务端权威**，而且**循环内落库、heal-dangling、
暂停恢复、timeline 四样全依赖它**（`filter-chain-design.md` §2.2 明写「memory filter
刻意放循环内」）。

→ **agui 取服务端权威**：只取客户端最后一条 user 消息，其余忽略；首次 `connect`
时用 `agent/get-history` 转 `MESSAGES_SNAPSHOT` 发给前端对齐。接受客户端历史等于
同时推翻上面那四样——那不是一个协议适配层该做的决定。

**顺带缺的**：`ChatMemory` 协议只有 `mem-get/mem-add/mem-clear`，**没有 list**，
所以 CopilotKit 的 `/threads` 列表路由没法直接由 store 支撑。判定：`/threads`
是产品功能（§8 已判不建），agui 在模块内自己维护会话索引即可，不动 ChatMemory 协议。

### 7.4 取消的粒度：工具批次执行期间没有检查点

证据：取消只在两处检查——迭代入口与 LLM 返回后（react.clj:578 / 593）。一批长工具
正在跑时 `stop!` 要等整批跑完。

判定：**不改**（与 §4.8 的诚实口径、`tool-timeout-design.md` 的结论一致：JVM 上
没有抢占原语）。但 agui 必须把这条写进 `stop!` 的 docstring 与前端提示——
显示「正在停止…」，而不是「已停止」。

### 7.5 run 级墙钟超时

框架有工具级 `:timeout`，**没有 run 级**；AG-UI 客户端会一直吊在 SSE 上。
判定：**agui 模块内解决**（起个定时器到点调 `stop!`），不进框架（§1：几行等价）。

### 7.6 工具参数不增量流式

AG-UI 的 `TOOL_CALL_ARGS` 有分片语义；我们的 `on-token` 只吐文本 / 思维 token，
工具参数在 provider 侧就已聚合完。→ **本轮不做**：要动 provider 的流式解析，
换来的只是「参数正在生成」的动效。

### 7.7 usage / 成本

`ChatResponse` 带 usage（provider 侧有 usage 测试），AG-UI 的 `RUN_FINISHED` 可带
usage。→ 不缺能力，只是接线，S3 顺手带上。

### 7.8 多进程 / 鉴权 / 限流 / 多租户

一律**不在库内**（§2、§10）。agui 只负责「凭 conv-id 找到那个 run」；
路由粘性、鉴权、配额属于 web 层，本来就该在那里。

### 7.9 一览

| # | 缺什么 | 判定 |
|---|---|---|
| 7.1 | 跨 run 共享状态 | **真缺**；路径已定（fold-from-history，不建快照店），本轮用 turn 级注入顶上 |
| 7.2 | 前端工具 | **不缺**——inline tool + gate 暂停 + `:reply` resume 三件现成 |
| 7.3 | 客户端历史权威 | 不缺能力，**缺一次拍板**（拍服务端） |
| 7.4 | 批中取消 | 不做（JVM 无抢占），但要如实说 |
| 7.5 | run 级超时 | agui 模块内 |
| 7.6 | 工具参数流式 | 本轮不做 |
| 7.7 | usage | 接线即可 |
| 7.8 | 多进程 / 鉴权 / 限流 | 不在库内 |

---

## 8. 明确不建（否决记录）

### 8.1 事件折叠 / 事件日志作为第二真相店

`compactEvents` + `historicRuns` + `messagesSnapshot` 三件套的存在，是因为
CopilotKit 的 runtime **没有 ChatMemory**——它的事件日志就是它的历史。我们有。

`hitl-timeline-design.md` §4.5 已经拍过这个板：**「不要再造独立快照店」**（两处
真相的老路）。所以：**事件缓冲是传输设施，不是存储**。挤出环形缓冲就丢，
要历史找 ChatMemory，要状态找 tool-context，要暂停找 PauseStore。

### 8.2 事件日志持久化 / durable execution

sqlite-runner 那套（`agent_runs` + `run_state` 表）是 durable execution 的雏形。
`hitl-timeline-design.md` §3.4 **已经明确出界**：只保证「暂停点」这个一致快照跨
进程存活，批次执行中途的崩溃恢复不做。本文不推翻那条结论。

### 8.3 `AgentRunner` 抽象协议

四问：现在有第二个实现吗？**没有**（in-memory 一个）。不建的话怎么办？
**照样能跑**。换来什么？**只是对称性 + 「以后可能换后端」**。触发条件写得出吗？
写不出——因为 6.2 已经把最可能的那个后端（持久化）判出界了。

→ **不建**。重启条件写在这里：**当出现第二个真实后端**（例如多进程部署需要
共享会话状态，走 Redis/PG），**再抽协议**，且届时按 §1.3「重启时须重新设计」，
不得直接把 CopilotKit 的 `run/connect/isRunning/stop` 四方法捡回来。

### 8.4 全局单例 store、字节记账、clamp-and-warn

- **全局单例**：CopilotKit 自己为它写了一段 `LIMITS_CLOBBER_GUIDANCE` warning。
  一个需要靠 warning 解释的设计不值得抄。runtime 是显式值，用户想建几个建几个；
- **`maxBytes` + `ɵnormalizeLimits`**（校验 → 钳位 → warn → 再校验）：典型的
  §1.3「防御性机器」。我们的边界只有一条 `buffer-size`（条数），非法值直接抛
  ——**装配期抛，不是运行期兜**，与 `deftool` 同名工具装配期拒绝的做法一致。

### 8.5 hooks / middleware

四问：不建的话用户怎么办？——**在 Ring middleware 里写**，那是他们本来就在写的
地方，且比我们的 hook 更强（能碰 session/cookie/路由）。换来什么？只是
「不用碰 web 栈」。→ **不建**，一条也不建。

---

## 9. 施工台阶与验收

### 9.1 S0 —— 前置（框架侧唯一的改动 + 模块骨架）✅

0a. **`resume` / `resume-async` 接 `:on-token` / `:cancel-token`**（§6.1，P0）；
    顺带把四个入口的 opts 透传收进 `build-invoke-opts`（§6.2）。
0b. 建 `modules/clj-agent-agui/` 骨架并登记六处（`deps.edn` / `tests.edn` /
    `bb.edn` / `build.clj` / `modules/README.md` / CI matrix，另加
    `scripts/check_docs.clj` 的 README 名单，见 §5.3）。

### 9.2 S1 —— 骨架（无 AG-UI，可独立验收）✅

1. `agui/runtime.clj`：`runtime` / `start-run!` / `subscribe` / `stop!` / `run-status` / `shutdown!`；
2. 事件模型（§4.2）+ emitter（`:seq` 自增、开集合跟踪、终态良构 §4.6）；
3. 会话表：conv-id → `{:agent :lock :run :buffer :last-active}`；空闲驱逐；
4. 接线（§4.9）：`on-token` + `:iteration` filter + agent 结果；
5. 断线重连示例（落在 `examples/copilotkit/agui_example.clj` 场景 3，离线可跑：
   关掉「连接」再带 `Last-Event-ID` 连回来，token 一个不丢）。

### 9.3 S2 —— 生命周期完备 ✅

6. `:supersede` 策略 + 每 run 停止意图 holder；
7. `resume-run!`（跨请求 HITL）+ `:awaiting-resume` 会话态；
8. `:since` 续传 + 落后 `:run/resync`（走 ChatMemory 快照）；
9. `shutdown!` 收尾语义（取消全部 + 等终态 + 超时强退，超时值可配）。

### 9.4 S3 —— AG-UI 端到端 ✅（真前端那步待用户侧验证）

10. `agui/codec.clj` 编解码 + `agui/tools.clj`（前端 action → inline tool，§7.2）；
11. 四条路由 + SSE 编码（`examples/copilotkit/http_kit_routes.clj`）；
12. **端到端六场景**（`examples/copilotkit/agui_example.clj`，离线可跑，已跑通）：
    流式 / 工具 / 断线重连 / 停止 / 审批 HITL / 前端工具。
    **真 CopilotKit React 前端的联调仍待做**——它要一个前端工程，不在本仓库里。

### 9.5 三项待拍板 —— 已定（施工时）

| # | 问题 | 定了什么 |
|---|---|---|
| 1 | 暂停中的会话收到新 `start-run!` | **(a) 拒绝**，返回 `{:status :awaiting-resume :pending-tool …}`。刻意不沿用 `simple-agent` 的 `cancel-pending!`（新 turn 静默丢弃未 resume 的暂停）——web 场景下「用户没点审批就又发了一句」应当显式暴露 |
| 2 | `:run/paused` 怎么映射到 AG-UI | **(c) AG-UI 的 interrupt 协议**（2026-09-03 改定，原定 (a) `CUSTOM` + 补一条 `RUN_FINISHED`）：`RUN_FINISHED` + `outcome:{type:"interrupt", interrupts:[…]}`——收口与告知合成**一条**事件。改的理由见 §9.11：`@ag-ui/core` 0.0.59 起 interrupt 是一等协议，CopilotKit 的 `useInterrupt` 明写着 `CUSTOM` 那条是 **legacy** 分支。(b) 那条「建模成待前端返回结果的 tool call」的路，**前端工具仍在走**（§7.2）——审批不必硬塞进 tool-call 形状，它现在以 `interrupt.toolCallId` 挂回同一张工具卡片，比塞进去更准 |
| 3 | §6.1 的 opts 怎么进 `resume` | **(a) 新增 4-arity** `[agent decision payload opts]`。payload 是**用户答复**的载荷，传输选项混进去是两件事挤一个槽 |

### 9.6 测试清单

| # | 测试 | 钉住什么 |
|---|---|---|
| 1 | 订阅者中途退订，run 照常跑完，历史进 ChatMemory | run ⊥ 请求（§1.1 的缺口） |
| 2 | 断线后带 `:since` 重连，收到的事件序列与不断线时**逐字相同** | 续传无洞（§4.5） |
| 3 | `:since` 早于缓冲起点 → 收到 `:run/resync` + 完整消息快照 | 落后路径 |
| 4 | run 抛异常时，开着的 message/tool 块被补关，且**只有一个**终态事件 | §4.6 |
| 5 | `:supersede` 下旧 run 落 `:run/cancelled`（**不是 `:error`**），新 run 正常完成 | CopilotKit 那个 finalize-intent 坑 |
| 6 | 已有终态后不再追加任何事件（含 closer） | AG-UI 硬约束（issue #5812 的等价物） |
| 7 | 并发 100 个 `start-run!` 打同一 conv-id，恰好 1 个 `:started`，99 个 `:busy` | §3.3 的新不变量 |
| 8 | 暂停 → 另一个"请求"（另一个线程）`resume-run!` → 续跑完成，订阅者跨两个 run 连续收事件 | §4.7 |
| 9 | `stop!` 后：终态是 `:cancelled`，且**正在跑的工具仍然跑完**（断言副作用发生） | §4.8 的诚实语义 |
| 10 | `shutdown!` 后所有 run 有终态、所有订阅者收到 `on-close` | 收尾 |
| 11 | 慢订阅者（回调里 sleep）不影响其他订阅者的事件顺序；抛异常的订阅者被摘除且 run 不受影响 | §4.5 |
| 12 | **HITL 第二段流式**：审批后的续跑逐 token 出事件（钉住 §6.1 改完了） | §6.1 |
| 13 | 前端工具全程：inline tool → gate 暂停 → `resume-run! :reply` → 模型拿到结果续跑 | §7.2 |
| 14 | 事件流与 `agent/chat` 的返回值**语义一致**：`:run/finished` 的 `:text` ≡ `(:text (chat ...))` | 不长出第二套语义 |

### 9.7 验收 ✅

- `clojure -M:test`：**454 tests / 2032 assertions / 0 failures**（基线 416/1876，
  新增 38 tests / 156 assertions；连跑三遍稳定，见 §9.8 第 5 条）；
  `bb test` 四模块分别全绿（agui 34 tests / 137 assertions）；
- `bb check-docs` 全绿（7 个 README / 73 个 ns / 21 份 docs）；
- `examples/copilotkit/agui_example.clj` 六场景全过（离线，已并入 `run_all_examples`）；
- **真机验证**（`examples/copilotkit/agui_live_test.clj`，MiniMax-M2.7 真实端点）
  六场景全过：流式（54 块真 token）/ 工具调用（id 全程串得起来）/ 断线重连
  （6 字处断开，重连补齐 424 字，seq 连续无洞）/ 停止（100 字处停住，落
  `:run/cancelled`）/ 审批 HITL（续跑 20 块，**流式**）/ 前端工具（`:reply` 回灌）。
  **它顺带挖出一个老 bug，见 §9.8 第 6 条。**
- **联调**（CopilotKit 官方 `examples/v2/react/demo` + 真浏览器 + 真 MiniMax，
  全程无 Node runtime）：聊天 / 工具卡片 / 敏感工具暂停 / 跨请求审批续跑 /
  suggestions 全部跑通，**挖出五个问题并修掉**——见 §9.10。

### 9.8 施工记录：实现与设计不一样的五处

设计是隔着一层写的，落地时有五处按实际改了——记在这里，免得下一个人拿设计稿
去对代码时以为是漂移：

| # | 设计里怎么写的 | 实现成了什么 | 为什么 |
|---|---|---|---|
| 1 | `:seq` 每 run 从 0 递增 | **会话级单调**（§4.2 已就地改） | HITL 一次对话跨多个 run，订阅挂在会话上。按 run 重置会让 `:since` 变成 `{run-id, seq}` 二元组，重连还得先问「上一个 run 是哪个」；会话级正好对上 SSE 的 `Last-Event-ID` |
| 2 | `:retain-ms`（run 结束后缓冲保留多久） | **并入 `:buffer-size`** | 缓冲改成会话级之后就不按 run 分桶了，「保留多久」退化成「最近 N 条」。少一个旋钮 |
| 3 | `run-status` 返回 `{:run-id :state :seq :since}` | 加了 **`:stopping?`** | `stop!` 之后到停稳之间有个真实的窗口（工具还在跑）。UI 要显示「正在停止…」而不是「已停止」，这个位得读得到——§4.8 说的诚实语义，不给读就只能靠猜 |
| 4 | build.clj 照旧 | `:override-core?` 布尔 → **`:override-libs` 列表** | agui 同时依赖 core 与 client。只覆盖 core 会让发布出去的 pom **只缺 client 那一条**——正是那个坑换个依赖再踩一遍 |
| 5 | 测试清单第 5/7/9 条（supersede / 并发 / 取消） | mock provider 加了 **`:hold` 闸门**，测试 deliver 之前那一轮卡住 | 初版用 `:delay-ms` + `sleep` 赌时序，聚合跑（454 个测试同一个 JVM）时**当场间歇性红两条**。「等 40ms 应该够了」这种阈值在负载高的机器上必然翻车，改成闸门后连跑三遍稳定 |
| 6 | （没预料到）| **修了 Anthropic 路径上一个老 bug**：`anthropic/build-params` 把 `(:tools config)` 与 `tool-schemas` 参数直接 `into` 在一起，而 `chat-model/build-call-config` 会把本次调用的 tools **同时**塞进这两处——于是每个工具**发两遍**。新增 `merge-tools`：两边都归一化、按 `:name` 去重 | 真机验证第 6 场景当场 400：`invalid params, function parameters is empty (2013)`。详见下方「§9.9 真机挖出来的老 bug」 |

### 9.10 联调记录：接 CopilotKit 官方 demo（2026-09-03）

`~/workspace/CopilotKit/examples/v2/react/demo` 自带一个 dev-only 逃生口
（`src/app/runtime-url.ts`）：设 `NEXT_PUBLIC_COPILOTKIT_RUNTIME_URL` 就把 AG-UI
流量打到**另一个进程**。于是联调不需要改它一行代码：

```
NEXT_PUBLIC_COPILOTKIT_RUNTIME_URL=http://localhost:4002/api/copilotkit pnpm dev
```

**跑通了什么**（真浏览器 + 真 MiniMax，全程没有 Node runtime）：
聊天流式渲染 / `get-weather` 工具卡片（`Status: complete` + 参数）/ 敏感工具
暂停（卡片停在 `inProgress`）/ **另一个请求**凭 conversation-id 审批 → 工具真的
执行 → `/connect` 上看到续跑那个 run 的 `TOOL_CALL_RESULT` 与最终回答。
CopilotKit 的 suggestions 也自动工作了（它把 `copilotkitSuggest` 当前端工具塞进
`tools`，我们的前端工具通道原样承接，前端渲染出三个中文建议按钮）。

**五个只有联调才照得出来的问题**（单测与 live 脚本全绿也照不到）：

| # | 症状 | 真因 | 修在哪 |
|---|---|---|---|
| 1 | 前端拿 `/info` 后去请求 `/agent/0/run` | `agents` 必须是**以 id 为键的字典**，我们发的是数组——客户端 `Object.entries` 把下标当成了 agent id | `codec/run-info` |
| 2 | `/stop` 404 | threadId 是**路径段** `/stop/:threadId`，不是请求体 | 路由示例 |
| 3 | `AGUIError: First event must be 'RUN_STARTED'` | **起 run 与订阅之间有真空**：run 立刻起跑并发出 `:run/started`，HTTP 层却要等 `start-run!` 返回才能 `subscribe`，那一条就漏了 | `start-run!` 增返回 `:since`（起跑前的水位），订阅时带上——**这正是会话级 `:seq` 的用处** |
| 4 | 工具卡片永远停在 `inProgress`，HTTP 请求不结束 | 暂停的 run 在 AG-UI 侧**没有终态**，流也不关。AG-UI 的一条 run 必须以终态收口 | `codec/->agui-events`（`:run/paused` 补一条 `RUN_FINISHED`；§9.11 之后这两条合成了一条带 `outcome` 的终态）；`/run` 终态即关流，**`/connect` 不关**（它跨 run） |
| 5 | 输入框敲了字发不出去，会话卡死 | CopilotKit 的 suggestions 把 `copilotkitSuggest` 当前端工具塞进 `tools`，**只读 `TOOL_CALL_ARGS`、从不回结果**——会话于是永远停在 `:awaiting-resume`，挡住后续所有消息 | `start-run!` 增 `:discard-pause?`（**缺省仍是拒绝**，§4.4 的取舍不变；要丢就显式说）。前端工具的暂停由路由侧判定「这次没带结果 → 它不会回了」 |

第 3、5 两条是**运行时 API 的真实缺口**（各加了一个返回值 / 一个选项），
第 1、2、4 是协议对接细节，全在示例与 codec 里。

**顺带产出的两条「不属于 AG-UI」的端点**：`POST …/approve`
（`{threadId, decision, payload}` → `resume-run!`）与 `GET …/pending`
（待审批列表 = `conversations` + `awaiting` 两个调用）。当时以为「人工审批是
你的应用的事，协议里没有它」——**§9.11 更正了前半句**：协议里有 `resume[]`。
这两条留下来做**带外审批**（审批台 / Slack 按钮 / 运维脚本：手上只有
conversation-id，不发聊天消息，也不该被迫伪造一次 run）。

**没做**：给暂停写前端 renderer（那是前端工程的活）。所以当时 stock demo 上
「同意」按钮是用 curl 打 `/approve` 代替的——§9.11 之后这一条也不必了：
`useInterrupt` / `useHumanInTheLoop` 是现成的。

---

### 9.9 真机挖出来的老 bug：Anthropic 路径每个工具发两遍

**症状**：AG-UI 前端工具（本轮第一个**内联**工具）一上真实端点就 400——
`invalid params, function parameters is empty (2013)`。同一份 schema 直接调
`provider/call-llm` 却完全正常，走 `create-agent` 就必炸。

**根因**（两处各自都说得通，凑在一起才出事）：

1. `chat-model/build-call-config` 把本次调用的 tools **同时** `assoc` 进 config
   **并**作为第三个参数传给 `call-llm`——这是它给自己留的回读通道
   （`tools (:tools call-config)`）；
2. `anthropic/build-params` 把 `(:tools config)` 当作「预置的 wire 工具」
   （`web_search` 一类），与归一化后的 `tool-schemas` 直接 `into` 在一起。

于是这两个「来源」拿到的其实是**同一批工具**，每个工具被发了两遍。

**为什么两年没人发现**：`deftool` 产出的 schema 自带 `:input_schema`，两份都是
合法 wire 形态，服务端照收——只是白烧 token、且模型看见重复工具。**内联工具是
第一个不是 wire 形态的**（schema 走 `:parameters`），config 那份没经过归一化，
当场被判「function parameters is empty」。

**为什么 OpenAI 那条路没事**：`common/openai_compat` 的 build-params **只读第三个
参数**（`(seq tool-schemas) (assoc :tools tool-schemas)`），从来没有第二个通道。
本次修的就是把 Anthropic 拉回同一个语义。

**修法**：`anthropic/merge-tools`——两个来源都过一遍 `schema/tools->schemas`，
再按 `:name` 去重（config 侧优先，`web_search` 这类没有 `:name` 的照收）。
`params_test.clj` 四条断言钉住：同一批只发一份、`:parameters` 两边都归一化、
预置 wire 工具与本次 schema 并存、`merge-tools` 自身的去重语义。

**教训**：一个「多留一条通道」的便利（config 回读 tools）和一个「多接受一种来源」
的宽容（build-params 合并两处），各自看都无害；**合起来就是同一份数据走两条路**，
而且只有在两条路的归一化程度不同时才暴露。

---

### 9.11 改用 AG-UI 的 interrupt 协议（2026-09-03，晚于 §9.10）

**结论先说**：`:run/paused` 不再发 `CUSTOM/cljagent.run.paused`，改成

```json
{"type":"RUN_FINISHED","threadId":"…","runId":"…",
 "outcome":{"type":"interrupt",
            "interrupts":[{"id":"tc9","reason":"approval-required",
                           "toolCallId":"tc9",
                           "responseSchema":{"type":"object",
                                             "properties":{"decision":{"type":"string",
                                                                       "enum":["approved","rejected"]}},
                                             "required":["decision"]},
                           "metadata":{"pendingTool":{"name":"wipe-database","args":{…}}}}]}}
```

答复走**下一次 run 的请求体**：`RunAgentInput.resume = [{interruptId, status, payload}]`。
`/info` 每个 agent 报 `capabilities.humanInTheLoop = {supported, approvals, interrupts}`。

**为什么原来没走这条**：§5.2 列待拍板项时只摆了两条路（`CUSTOM` / 建模成 tool
call），`outcome:"interrupt"` **根本没进候选**——不是权衡后否掉的，是没看见。
联调对的那个 demo 自己 pin 的是 `@ag-ui/client 0.0.51`，**那个版本里没有
interrupt 协议**（`interruptId` 出现 0 次），demo 也没用 `useInterrupt` /
`useHumanInTheLoop`。于是「联调跑通了」这件事恰好绕过了缺口。

**为什么现在要改**：真正跑的前端不是 0.0.51——demo 依赖的 `@copilotkit/core` /
`react-core` 是 workspace 包，pin 的是 `@ag-ui/core 0.0.59`，那里 interrupt 是
一等协议（`RunFinishedInterruptOutcomeSchema` / `ResumeEntrySchema` /
`HumanInTheLoopCapabilitiesSchema.interrupts`）。而 `use-interrupt.tsx` 里写得
很直白：

> `legacy` carries the custom-event payload; `standard` carries the AG-UI
> `outcome:"interrupt"` interrupts array.

我们那套 `CUSTOM` 正好落在人家**显式标成 legacy** 的分支上。四条实际代价：

| | 走 `CUSTOM` | 走 interrupt |
|---|---|---|
| 渲染 | 前端自己写 renderer（所以 §9.10 那条「没做」） | `useInterrupt` / `useHumanInTheLoop` 接管 |
| 答复 | 自造 `/approve` 端点 | 下一轮 `/run` 带 `resume[]`，零新端点 |
| 白丢的字段 | 无 | `interruptId`（并发审批对得上）、`responseSchema`（前端自动渲审批控件）、`expiresAt`、`editedArgs` |
| 语义 | `RUN_FINISHED{result:{status:"paused"}}`——`result` 是自由字段，标准客户端看 `outcome` 缺省 **= success**，它以为这轮正常结束了 | 明确是 interrupt |

最后一行是真问题，不只是风格问题。

**改动落点**（框架侧两处、示例侧一处）：

| 在哪 | 改了什么 |
|---|---|
| `codec/->agui` | `:run/paused` → `RUN_FINISHED` + `outcome`；新增公开的 `codec/interrupt-id`（暂停事件与 `runtime/awaiting` 同形，两侧算出同一个 id） |
| `codec/->agui-events` | 不再一对多（终态与告知合成一条），函数留着——留住下一个一对多场景的口子 |
| `codec/parse-run-input` | 多解析一个 `resume` |
| `codec/run-info` | 每个 agent 加 `capabilities.humanInTheLoop`；**`approveWithEdits` 不报**（改参数再执行还没实现） |
| `examples/copilotkit/http_kit_routes.clj` | `handle-run` 认 `resume[]`，且**排在所有推断之前**（tool-call 型 interrupt 被 resolve 时客户端会同时补一条 tool 消息，先认 `resume` 才不会误当成前端工具结果）；`interruptId` 对不上就当没有（过期重放）；`/pending` 多带 `interruptId` |

**没动**：`:run/cancelled` 仍是 `RUN_FINISHED` + `result.status`——标准 outcome
只有 `success` / `interrupt` 两种，没有 cancelled 可对。
（`:message/thinking` 当时也留着走 `CUSTOM`，**已在 §9.13 改掉**。）

---

### 9.12 移植 CopilotKit 的 Open Generative UI（可选插件）

上游是 `packages/runtime/src/v2/runtime/open-generative-ui-middleware.ts`：模型调
`generateSandboxedUi` 生成一块 HTML/CSS/JS，运行时**不执行**它，把参数翻译成
`ACTIVITY_SNAPSHOT` + 一串 `ACTIVITY_DELTA`（JSON Patch），前端在沙箱 iframe 里
边收边渲染。落成 `examples/copilotkit/genui.clj`，**默认不装**。
（原在 `modules/clj-agent-agui/src/…/agui/genui.clj`，2026-09-03 移出模块：
它是 CopilotKit Runtime 一个可选中间件的约定，不是 AG-UI 的核心能力——
协议层只认通用的 `ACTIVITY_SNAPSHOT` / `ACTIVITY_DELTA`。A2UI 与 MCP 留在模块内。）

**为此新增的唯一运行时 API**：`event/emitter` 的 `:transform` +
`runtime` 的 `:event-transform`（每 run 现造一个有状态的 transform）。
它是**通用的事件流插件挂点**——可以改写、吞掉、或在一条事件前后插事件：

| 契约 | 为什么仍成立 |
|---|---|
| `:seq` 单调无洞 | 号是**发射时**分配的，transform 产出几条就取几个号 |
| 恰好一个终态、且在最后 | **终态不过 transform**（`event/expand` 里挡掉）——让插件有机会吞掉终态，这条保证就没了 |
| 发射器永不抛 | transform 抛异常 = 原样放行 + 一条 warn，与 sink 抛异常同款兜法 |

**与上游的一处真实差异**：那边吃的是**流式 tool-call 参数**（`TOOL_CALL_ARGS`
一片片来，用 clarinet 增量解析），而我们不增量流式工具参数（§10 第 4 条），
`:tool/args` 一次给出完整参数。所以扫描器（自己写的，约 100 行，不引依赖——
要的不是「解析出一个值」而是「字符串还没结束但已经攒了这么多」这个出口）同样是
增量的，只是今天**一口喂完**：事件的形状与顺序与上游逐条一致，「边生成边长
出来」要等参数流式落地。那天到了，`genui.clj` 一个字都不用改。

顺带修的一处：参数按约定顺序**重新序列化**后再喂扫描器。模型的原始 key 顺序在
provider 解析 JSON 时就丢了，而这个特性的提示词恰恰要求按顺序生成
（`initialHeight` → `css` → `html` → …）——按约定重排比听天由命诚实。

三个「猜错了前端不报错、只会静默少渲染」的形状（全部有测试钉住，断言逐条对着
上游的 e2e 测试写）：snapshot 必须**先于**任何 delta 且只发一条（惰性发射，
`initialHeight` 迟到就改发 delta）；`add` 操作**必须带 `value`**（模型常给
`jsFunctions: null`，发一条没有 value 的 patch 会让前端把**整批**判非法丢掉）；
`html` 是**数组**不是字符串（`/html` 先建空数组，之后 `/html/-` 一块块追加）。

---

### 9.13 思考块改走 `REASONING_*`（2026-09-03）

原来 `:message/thinking` 编码成 `CUSTOM/cljagent.thinking`，理由是「AG-UI 的
`THINKING_*` 不是所有前端都认」。查下来这个判断只对了一半：`THINKING_*` 确实
在 0.0.59 全部 deprecated（1.0 移除），但取代它的 `REASONING_*` 是一等公民，而且
**CopilotKit 前端有原生的折叠面板渲染它**（`CopilotChatMessageView` 的
`CopilotChatReasoningMessage`）。发 `CUSTOM` = 把现成的渲染让掉了。

改动不止 codec 一行——思考在 AG-UI 里是**一条独立的消息**（`role: "reasoning"`），
而我们原来把思维 token 挂在**正文消息的 id** 上：

| 改哪 | 改成什么 |
|---|---|
| `event.clj` | 新增 `:reasoning/started` / `:reasoning/ended` 两个中立事件与 `end-reasoning!`；思考块的 id 是正文 id 加 `-reasoning` 后缀。**正文 token 一到就把思考块收口**，反之亦然（一轮里来回切换是常态）；`end-message!` 与 `close-open!` 都补一次收口 |
| `event.clj` 的 `track!` | `:message/thinking` **不再进正文消息的开集合**——否则 `close-open!` 会给一条从没开过的正文消息补 `:message/ended` |
| `codec.clj` | 开合各展开成**一对**：`REASONING_START` + `REASONING_MESSAGE_START` / `REASONING_MESSAGE_END` + `REASONING_END`；中间是 `REASONING_MESSAGE_CONTENT`。这正是 §9.11 里说的「留着 `->agui-events` 的一对多口子」派上用场 |

一条容易忽略的约束：**思考块的 message-id 必须与正文不同**。共用 id 的话，同一个
id 上既有 `TEXT_MESSAGE_START` 又有 `REASONING_MESSAGE_START`，客户端只认先到的
那一种，另一半静默消失。

---

### 9.14 A2UI 插件（2026-09-03）

第二条生成式 UI 的路，移植自 `@ag-ui/a2ui-middleware`。落成
`agui/a2ui.clj`，与 `genui` 同一个插件形状（`with-tool` + `event-transform`），
另加一个**入站**挂点。

**两条路的分工**（上游把它们挂在 `agent-utils.ts` 的同一个位置，是并列关系不是
替代关系）：genui 让模型写 HTML/CSS/JS，自由但要沙箱执行；A2UI 让模型按
**catalog 白名单**拼组件树，前端用自己的组件库渲染，**不执行任意代码**。

**wire 形状**（一条快照，不需要 delta）：

```json
{"type":"ACTIVITY_SNAPSHOT","messageId":"a2ui-surface-<toolCallId>",
 "activityType":"a2ui-surface","replace":true,
 "content":{"a2ui_operations":[
   {"version":"v0.9","createSurface":{"surfaceId":"s1","catalogId":"…basic/catalog.json"}},
   {"version":"v0.9","updateComponents":{"surfaceId":"s1","components":[…]}},
   {"version":"v0.9","updateDataModel":{"surfaceId":"s1","path":"/","value":{…}}}]}}
```

三条 op 的顺序是协议要求的（建面 → 给组件 → 灌数据）；`replace: true` 是
「整块换掉」——**所以 A2UI 根本不需要 delta**，与 genui 那条 snapshot + 一串
delta 的路正好相反。codec 的 `:activity/snapshot` 因此多认一个 `:replace`。

**catalog 是这条路的要害**：它既是给模型的词汇表，也是「模型编不出前端没注册的
组件」的保证。缺省内置 A2UI v0.9 基础 catalog 的 18 个组件（**生成的**：由
`@a2ui/web_core` 的 `catalogs/basic/catalog.json` 摘出组件名 + 属性 + 描述 +
枚举 + 必填，丢掉 `$ref` 那些给校验器看的东西）。要用自己的组件库就传
`:catalog`——传错了不会报错，只会渲染空白。

**新增的入站挂点** `:input-transform`（`routes/start!`，对称于 `:event-transform`）：
用户在生成出来的界面上点按钮，前端发 `forwardedProps.a2uiAction`。

**与上游的两处差异**：

| # | 上游 | 我们 | 为什么 |
|---|---|---|---|
| 1 | 从**流式** tool-call 参数里增量抠出「已经完整的那几个组件」（`extractCompleteItems`），边生成边贴 | 参数一次到齐，**一条快照发完** | 我们不增量流式工具参数（§10 第 4 条）。形状与顺序一致，少的是中间态 |
| 2 | 用户动作合成成 `log_a2ui_event` 的一次**工具调用 + 工具结果**塞进历史 | 翻成一条**用户消息**（措辞与上游 `formatUserActionResult` 逐字相同） | 历史取服务端权威（§7.3），客户端塞不进消息 |

**没做**：组件校验与 retry 生命周期（上游的 `recovery` / `status: "retrying"`）——
那是「模型拼错了自动重试」的产品逻辑，得先有真实前端反馈才知道值不值得。

---

### 9.15 `/suggest`：无状态建议端点（2026-09-03）

**为什么值得单开一条路**：不实现它，前端就把 `copilotkitSuggest` 塞进 `/run` 的
`tools` 里自己凑合——那正是 §9.10 第 5 条「输入框敲了字发不出去」的来源。
客户端只有在 `/info` 报 `suggestions: true` 时才走这条路（`suggestion-engine.ts`
的 `useStateless`），所以那个能力位也一起改成可配。

这条路与 `/run` 的每一处不同都是刻意的（对齐上游 `handle-suggest`）：

| | `/run` | `/suggest` |
|---|---|---|
| 历史 | **服务端权威**，只取最后一条 user 消息（§7.3） | **客户端权威**——没有服务端线程，它发上来的就是全部上下文 |
| 会话 | 进注册表：缓冲、订阅、stop / resume | **不进**——一次性的建议不该留下一堆废会话 |
| 落库 | memory filter 循环内落库 | 临时 store，跑完就没了 |
| 工具 | 服务端工具 + 前端工具（会暂停） | 只有客户端这次声明的，都不执行 |
| 插件 | 挂 | **不挂**：工具选择已被强制，注入的工具是白费 |

**库侧新增两样**：

- `runtime/run-detached!`——在临时发射器上跑一次 agent 调用，事件直接给
  `on-event`，不进注册表。上游那句「the runner's event pipeline minus
  persistence」在我们这儿就是这个函数；
- `codec/agui->messages`——入站消息解析（`message->agui` 的反面）。主路径用不着
  （历史取服务端权威），**只有无状态 run 用得着**。

**一处只有真机才照得出来的事**：`:max-iterations 1` 拦不住第二轮 LLM。它限制的是
**工具轮**的次数，工具跑完之后那次「总结一下」的调用照发——实测白花了一整轮
reasoning + 29 块正文 token，而那轮产出没有任何人读（建议的载体是
`TOOL_CALL_ARGS`，不是模型的话）。挡它的正确姿势是工具的 **`:return-direct`**：
工具结果即最终答案，不再回灌 LLM。

**一个容易踩的坑**：建议工具**不能**用 `agui-tools/frontend-tool` 建。那个会让
gate 把 run 暂停下来等前端回结果，而 `copilotkitSuggest` **从来不回结果**——
会话于是永远停在 `:awaiting-resume`。这正是 §9.10 第 5 条那个 bug 的同一个根，
换个位置又长出来一次。

---

### 9.16 MCP 接入（2026-09-03）

上游是两个中间件（`agent-utils.ts`）：`MCPMiddleware` 把 MCP server 的工具接进
工具表，`MCPAppsMiddleware` 处理**带 UI 资源**的工具。落成 `agui/mcp.clj` 一个 ns。

**客户端不引依赖**：JSON-RPC 2.0 over Streamable HTTP，传输走 JDK 自带的
`java.net.http`。而且 `:transport` 是可注入的 `(fn [payload] response)`——
单测拿一个纯函数就把「握手 → 列工具 → 调工具 → 出 activity」整条链跑完了，
不起服务也不上网。真传输那一半另起一个最小 MCP server 验
（`examples/copilotkit/mcp_server_example.clj`）：**只有真打一次 HTTP 才知道**
`Mcp-Session-Id` 有没有回传、SSE 形态的响应认不认。

**与上游的一处真实差异——谁执行 UI 工具**：上游把 UI 工具注入进 `tools`，但
agent 框架并不执行它们，所以中间件在 run 收尾时自己扫「没有结果的 tool call」
再补执行、补 `TOOL_CALL_RESULT`。我们的内联工具本来就由循环执行（`:handler`
就是 `tools/call`），于是：

- 不需要那套「扫悬空 tool call」的补偿逻辑；
- 工具结果**在轮内**回灌给模型（上游是 run 末尾才补，模型根本看不到）——
  对「查完再答」这种用法反而更对；
- activity 消息由 `event-transform` 在 `:tool/result` 时发。

**三处照抄的协议细节**（猜错了就是不通）：`initialize` 之后**必须**补一条
`notifications/initialized`；客户端能力里要声明 `io.modelcontextprotocol/ui`
扩展，否则 server 不把带界面的工具给你；`server-hash` 是 md5 of
`{type,url,headers}`——**前端不知道 url**，它引用 server 只有 serverId / hash。

**代理通道**：前端那块 MCP App 界面跑在 iframe 里，它要调 server 时把请求塞在
`forwardedProps.__proxiedMCPRequest` 里走 `/run` 发上来。这时候**不起 agent**，
回一对 `RUN_STARTED` / `RUN_FINISHED{result}` 就完（路由里的 `sse-frames`）。
方法有**白名单**（`tools/call` / `resources/read` / `notifications/message` /
`ping`）——那块界面是 server 给的 HTML，不能让它随便调。

**没做**：SSE 传输（上游支持 `type: "sse"` 的老式双通道；Streamable HTTP 是现在
的默认，等真碰到只支持老传输的 server 再说）、OAuth 授权流、`resources/subscribe`。

---

### 9.17 `/threads*` 线程只读面（2026-09-03）

上游由 `AgentRunner` 的 `LocalThreadEndpointRunner` 支撑（`listThreads` /
`getThreadMessages` / `getThreadEvents` / `getThreadState` / `clearThreads`）。
我们**该有的东西全都有**，只是没按这几条路由暴露过：

| 端点 | 数据从哪来 |
|---|---|
| `GET /threads` | 会话注册表（§3.3 那张表） |
| `GET /threads/:id/messages` | **ChatMemory**——服务端权威的那份历史（§7.3） |
| `GET /threads/:id/events` | 会话的环形缓冲（`rt/buffered-events`） |
| `GET /threads/:id/state` | 缓冲里最后一条 `:state/snapshot` |
| `POST /threads/clear`、`DELETE /threads/:id` | 摘会话 + 清 ChatMemory |
| `POST /threads/:id`（改名）、`…/archive` | 进程内的一小张元数据表 |

**所以库侧只加了三样只读出口**：`rt/buffered-events`、`rt/forget!`（摘会话，
**不动 ChatMemory**——历史归历史）、`run-status` 多露一个 `:last-active`。
外加 `codec/messages->thread-messages`：线程列表那套 `toolCalls` 是**扁的**
`{id, name, args}`，与事件流那套 OpenAI 风格的
`{id, type, function:{name, arguments}}` **不是一个形状**（上游也分两套，
照抄的是 `handleGetThreadMessages` 的本地分支）。

**三条如实说的边界**（宁可让前端少显示，也不假装有）：

1. 会话空闲 30 分钟会被驱逐（`:idle-ttl-ms`），驱逐后它就不在列表里了——
   历史还在 ChatMemory，但 `ChatMemory` 协议**没有「列出所有会话」**，
   所以列不出来。真要完整的线程列表，得给 ChatMemory 加一张线程表；
2. 事件缓冲是**有界**的（缺省 512 条）且不落库——它是为断线续传服务的，不是
   事件日志（§8.2「不做 durable execution」）。`/events` 照实返回现存的部分；
3. 线程名与归档标记只活在**进程内**，重启即失。

`/threads/subscribe` **如实 404**：那是 Intelligence（云产品）的实时通道，
我们没有。客户端收到 404 会降级成轮询——比假装订阅成功然后永远不推送强。

---

## 10. 已知边界

1. **单进程**。多实例部署时同一 conv-id 必须路由到同一进程（sticky session）。
   跨进程共享是 §8.3 的重启条件，不是本轮范围；
2. **不做 durable execution**（§8.2）。进程崩溃丢失的是未完成的 run，
   恢复靠重发；暂停点照旧跨重启存活；
3. **取消不是抢占**（§4.8）；
4. **工具参数不增量流式**（§4.9 缺口 1）；
5. **state 不跨 run**（§7.1）：客户端 state 按 turn 级注入，刷新页面即回到裸 context。
   要跨 run 走 fold-from-history，不建快照店；
6. **runtime 不管鉴权、限流、多租户**——那些在 web 层，且本来就该在那里。

---

## 相关文档

- [`design-principles.md`](design-principles.md)——§1 立项判据、§2 框架无关（本文 §3 逐条对账）
- [`hitl-timeline-design.md`](hitl-timeline-design.md)——暂停/resume 语义与 §3.4 的出界声明（本文 §4.7 / §8.2 依赖它）
- [`filter-chain-design.md`](filter-chain-design.md)——`:iteration` 链契约（本文 §4.9 的采集点）
- [`token-stream-filter-design.md`](token-stream-filter-design.md)——`on-token` 的线程契约（本文 §4.5 的回调契约同源）
- [`async-chat-model-design.md`](async-chat-model-design.md)——`chat-async` / 虚拟线程（本文 §4.3 的执行底座）
- [`streaming-async-design.md`](streaming-async-design.md)——§0.5 是 §2《框架无关》的出处
- [`tool-timeout-design.md`](tool-timeout-design.md)——「借鉴但不照搬」的先例；§2 的 JVM 取消语义口径
