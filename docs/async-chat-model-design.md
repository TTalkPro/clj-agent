# 异步 ChatModel / Provider 设计（`:chat` 终端异步化）

> 状态：✅ **已实施（2026-09-03，全套 416 tests / 1876 assertions / 0）**——
> P0 / P1 / P2 三批全部落地，清单与逐条落点见 §9。
> **真实端点已验**：MiniMax-M2.7（`AnthropicProvider`）与 glm-5.3-flash
> （`OpenAICompatProvider`）各六个场景全过——`IAsyncLLMProvider` 的**两个实现方
> 都跑通了原生异步**。见 `examples/async_live_test.clj`。
>
> 上一层已经落地在先：`:turn` 终端异步（`react/invoke-async`、
> `simple-agent/chat-async`）与链结果组合子（`flt/fmap` / `fbind` / `fcatch` +
> `IChainResult`），见 [`filter-chain-design.md`](filter-chain-design.md) §2.6–§2.7。
> 本文这一层把 `:chat` 终端也变成 deferred，于是 `:turn` / `:iteration` / `:chat`
> 三条链全链路异步。传输层的既有事实见
> [`streaming-async-design.md`](streaming-async-design.md)。
>
> **⚠️ P2 的判据被越过了**：§2 给 P2（react 循环全链路 deferred）设的门槛是
> 「有真实高扇出需求才做」，本轮按明确指示提前实施。判据本身**不撤销**——它对
> 同类决策仍然作数，这里只是记一笔「知道门槛在哪、也知道这次跨过去了」。

---

## 0. 起点：现在到底哪些是异步的

盘点分三层，结论是**底子有、协议没有、中间那层长草**。

> **本节是落地前的盘点，保留原样作为动机记录**；第 2、3 层的「现状」已被本轮改掉
> （见 §9），第 1 层不变。

| 层 | 落地前的现状 | 证据 |
|---|---|---|
| HTTP 传输 | ✅ **真异步** | `provider/http/client.clj:219` 的 `:async? true` 走 `HttpClient.sendAsync` 返回 CompletableFuture；共享 client 的 executor 已是 `newVirtualThreadPerTaskExecutor`。SSE 更彻底：`provider/http/stream_client.clj:97` 的 `post-stream-async` = `sendAsync` + `BodyHandlers/fromLineSubscriber`（响应式 Flow.Subscriber），返回 `{:future :cancel}` |
| provider 函数 | ⚠️ **有，但是旁路** | `defprovider` 为每家生成 `xxx-call-async` / `xxx-call-stream-async`（`common/base.clj:150,173`，宏在 `:331,337`），转发到 `common/openai_compat.clj:274` / `anthropic.clj:448` |
| 协议 | ❌ **没有** | `ILLMProvider` 只有 `call-llm` / `call-llm-stream`；`IChatModel` 只有 `call` / `stream-call`（`core/chat_model.clj:122`）——全是同步返回 |

**中间那层为什么接不进主链路**（这是本设计要吸取的教训，不是要修的 bug）：

1. **回调式签名** `(config messages tools callback)`，不是「返回 deferred」；
2. **绕过了归一化**——同步路径的协议实现会做 `wire/neutral->wire`
   （`common/base.clj:212`）、`normalize-response`（ChatResponse 构造 + replay
   blocks 抽取）、`retry/run`；异步旁路一样都没有，callback 拿到的是裸 HTTP map
   `{:status :body :success?}`；
3. **全仓零调用点**（src / test / examples 都没有），无人验证；
4. docstring 写「返回 nil（结果通过 callback 返回）」，实际返回 `.thenApply` 出来的
   CompletableFuture——文档与实现不符。

所以第 3 层缺的**不是 HTTP 能力**，是协议与一条走同一套归一化的路。

---

## 1. 目标 / 非目标

**目标**

- `:chat` 终端可返回 `deferred<ChatResponse>`；`:chat` filter 链无需改代码
  （内置 filter 的响应侧已全部走 `flt/fmap`，见 §2.6.4 迁移路径第 1 步）；
- **任何 provider 都有异步入口**——原生异步的直接用，其余用虚拟线程兜底；
- 零破坏：不动 `ILLMProvider` / `IChatModel` 的既有方法。

**非目标**

- 不异步化 `:tool` 终端（理由见 §8）；
- 不引 manifold / core.async / Reactor（core 依赖仍为零）；
- 不改 `on-token` 的增量契约。

---

## 2. 先说值不值：收益评估

Loom 下把阻塞调用扔进虚拟线程，**并发收益已经拿到了**——
`examples/async_luminus_handler_example.clj` 实测 8 会话并发 213ms vs 串行 1600ms。
原生异步 HTTP 再省的只是「每个在途请求占一根虚拟线程」，而虚拟线程本就几 KB。

所以三批的性价比**差得很远**，别一锅端：

| 批次 | 内容 | 收益 | 成本 |
|---|---|---|---|
| **P0** | 流式：把 `post-stream-sync` 的 `@future` 让出去 | 高（底层本来就是异步的，`stream_client.clj:189` 那行阻塞是**纯粹的浪费**） | 低 |
| **P1** | 可选协议 + `DefaultChatModel` 异步实现 + `invoke-chat-async` | 中（`:chat` 链具备异步形状，应用层可直接用） | 中 |
| **P2** | `react/run-tool-loop` 与 `:iteration` 链全链路 deferred | 低（vthread 已经够；除非超高扇出） | **高**（要动循环本体） |

判据：**只有在「数千并发流」或「内存受限、不想每个在途请求占一根 vthread」的场景，
P2 才划算。** 没有这个场景之前，P2 只写设计不落地。

> **2026-09-03 后记**：P2 已按明确指示提前实施（无高扇出场景触发）。落地后回看，
> 成本比预估低——因为迭代体的响应侧改成 `flt/fmap` 之后，同步与异步能共用**同一份**
> 迭代体，真正分叉的只有两个注入项（`chat-fn` / `drive`，各 ~10 行）。这不推翻判据
> （收益仍未兑现），但把「成本」那一栏从**高**下修到**中**：组合子先行的架构让这类
> 改造便宜了一档。

---

## 3. 机制：可选协议 + `satisfies?` 探测

沿用仓库既有取舍（[`provider-variant-design.md`](provider-variant-design.md) §2.2、
`IReplayableResponse` 的 ns 注释）：**差异化能力一律走独立可选协议 + `satisfies?`
探测**，绝不给 `ILLMProvider` 加方法——那是所有实现方（含仓库外的）的破坏性变更。

```clojure
;; core/chat_model.clj
(defprotocol IAsyncChatModel
  "**可选**协议：ChatModel 能原生异步调用时实现它。"
  (call-async [this request]
    "-> deferred<ChatResponse>。语义同 `call`（含重试），只是不阻塞。")
  (stream-call-async [this request on-token]
    "-> deferred<ChatResponse>。语义同 `stream-call`（**不重试**）。"))

;; core/model.clj
(defprotocol IAsyncLLMProvider
  "**可选**协议：provider 有原生异步 HTTP 时实现它。"
  (call-llm-async [this llm-config messages tools]
    "-> deferred<原始响应>（形状同 `call-llm`）")
  (call-llm-stream-async [this llm-config messages tools on-token]
    "-> deferred<原始响应>（形状同 `call-llm-stream`）"))
```

**硬规则：两个协议都不得 `extend-type Object` 兜底。** `ILLMProvider` 就因为有兜底，
`satisfies?` 对任意非 nil 对象恒真（见 `model.clj` 里 `provider?` 的注释）；本设计
全部机制建立在 `satisfies?` 上，加了兜底等于当场失效。

---

## 4. 兜底：异步入口对所有 provider 一视同仁

探测失败不是「不支持」，而是「用虚拟线程兜底」——**调用方永远拿到 deferred**，
原生异步只是省一根线程：

```clojure
;; core/chat_model.clj —— 公共函数，不是协议方法
(defn call-async*
  "任何 ChatModel 的异步调用入口。原生异步直接用；否则虚拟线程包同步调用。"
  [chat-model request]
  (if (satisfies? IAsyncChatModel chat-model)
    (call-async chat-model request)
    (async/vthread #(call chat-model request))))
```

这条设计有个不显眼但重要的后果：**P1 落地当天，所有 provider（含仓库外自实现的
`{:chat-fn …}`）就都能异步**，原生异步实现可以逐家慢慢补，不构成阻塞。

> 依赖方向：`core/chat_model.clj` 要 require `core/async`。两者都在 core、
> async 只依赖 `filter`（协议）与 JDK，不成环。

---

## 5. 归一化路径必须共用（§0 教训的直接兑现）

**设计要求：异步实现只把「等 HTTP 结果」这一步换成 `fmap`，其余逐字复用同步路径的
函数。** 任何「另写一条更短的异步路径」都会重蹈 §0 第 2 条。

```clojure
;; 同步（现状，chat_model.clj:154）
(normalize-response provider (retry/run #(provider/call-llm provider cfg msgs tools) ropts))

;; 异步（目标形状——normalize-response 与 ropts 解析完全共用）
(-> (retry/run-async #(provider/call-llm-async provider cfg msgs tools) ropts)
    (flt/fmap #(normalize-response provider %)))
```

### 5.1 `retry/run-async`

同步 `retry/run`（`core/retry.clj:104`）是 `loop/recur` + `Thread/sleep`。异步版换成
**`fbind` 自递归**（与 `validation-turn-filter` 同一手法，§2.6.4 给过骨架）：

```clojure
(defn run-async
  "`run` 的异步孪生：f 返回 deferred；判据、次数、退避曲线与 `run` **共用**。"
  [f {:keys [max-retries] :as opts}]
  (letfn [(step [attempt]
            (flt/fcatch (f)
              (fn [t]
                (if (and (< attempt (long (or max-retries 0))) (retryable-ex? t))
                  (flt/fbind (sleep-async (delay-for t attempt opts rand))
                             (fn [_] (step (inc attempt))))
                  (throw t)))))]
    (step 0)))
```

两个必须守住的点：

- **退避不能 `Thread/sleep`**——用 `CompletableFuture/delayedExecutor`
  （`sleep-async`），否则在原生异步路径上会把 HttpClient 的 executor 线程睡掉；
- **判据函数（`retryable-ex?` / `delay-for` / `resolve-opts`）一份**，两条路径共用。
  重试次数与退避曲线在异步下必须与同步逐字相同，有测试钉住（§7）。

### 5.2 `DefaultChatModel` 的异步实现

`IAsyncChatModel` 的两个方法照抄同步版的骨架，只换 §5 那一处：`stream-call-async`
**同样不包重试**（token 已出门，重跑即重复投递——`chat_model.clj` ns 文档里的硬约束
不因异步而改变），不支持流式的 provider 仍回退到「同步调用 + 全文单 token emit」，
只是那次调用走 `call-async*`。

---

## 6. 流式：P0，几乎白捡

`post-stream-async`（`stream_client.clj:97`）本来就返回 `{:future :cancel}`，
`post-stream-sync` 只是在外面 `@future` 阻塞（`stream_client.clj:189`）。P0 要做的是
**把这个 future 交出去**，而不是自己等掉。两个坑必须在设计里写死：

1. **取消令牌的登记要在调用线程做。** `streaming/*register-cancel*` 是动态 var，
   靠调用链同线程可见（`core/streaming.clj` ns 文档写明了这一点）。异步化后回调跑在
   HttpClient 的 executor 上，**在回调里 register 会看不见调用方的绑定帧**。
   规则：`post-stream-async` 返回后**立刻在调用线程**把 `:cancel` 交给令牌；
   `async/vthread` 那条兜底路径靠 `bound-fn*` 已经天然正确。
2. **`on-token` 的调用线程不再是调用线程。** 现状是调用线程串行 emit；异步后 token
   在 executor 的虚拟线程上派发。`:token-xform` 的有状态 transducer 作用域仍是「单次
   流」（不变），但**契约要补一句：sink 的线程不保证，消费者自行保证线程安全**。
   这是**可观察语义变化**，必须写进 `token-stream-filter-design.md` 与 README，
   不能悄悄改。

`stream-call-async` 的重试策略与同步一致：**不重试**。

---

## 7. 与 `:chat` 链的对接（P1 / P2 的分界）

```
P1：chat-client/invoke-chat-async  → :chat 链（终端返回 deferred）→ call-async*
P2：react/run-tool-loop 全链路 deferred（loop/recur → fbind 自递归）
```

- **P1 新增 `invoke-chat-async`，不改 `invoke-chat`**。链的折叠代码
  （`build-chain` / `compile-chain`）**一个字不用改**——它只传闭包，不看返回值类型；
  `:chat` filter 也不用改（memory filter 等响应侧已是 `fmap`）。
  `invoke-chat-stream` 的 `:token-xform` 组装位置不变。
- **P2 是唯一要动循环本体的地方**：`run-tool-loop` 现在是同步 `loop`，异步化要改成
  `fbind` 自递归，`:iteration` 链、屏障折叠、`remaining`/`records` 的 volatile 记账
  都要跟着走一遍。**在有真实高扇出需求之前不做**（§2）。
- 过渡期的正确姿势：P1 落地后 `react` 仍走同步 `invoke-chat`。想要异步的应用层用
  `invoke-chat-async` 直连，或继续用已落地的 `invoke-async`（turn 级 vthread）——
  后者已经解决了 web 场景的线程占用问题。**不要**在 react 里用 `async/join` 把
  deferred 收敛回同步：那是白付一次调度还骗自己异步了。

---

## 8. 不做的，与理由

| 不做 | 理由 |
|---|---|
| `:tool` 终端异步化 | 工具是**用户代码**，阻塞是常态；超时机制 `tool/call-with-timeout` 本来就在虚拟线程上，批次并行也是。异步化会把 `:writes` 折叠与屏障语义搅乱，换不到东西 |
| 给 `ILLMProvider` / `IChatModel` 加方法 | 所有实现方（含仓库外）的破坏性变更。可选协议 + 探测是既有惯例 |
| 引 manifold / core.async / Reactor | core 外部依赖为零是硬约束（[`design-principles.md`](design-principles.md) §2）。`CompletionStage` 是 JDK 自带，适配层 `core/async.clj` 已在 |
| 改 `on-token` 契约 | 增量语义不变；变的只是**线程保证**（§6.2），那要显式声明 |
| 保留现有 `xxx-call-async` 旁路 | P1 落地时**删除 + 立墓碑**（`scripts/check_docs.clj` 的 tombstones）：零调用点、绕过归一化、docstring 与实现不符——留着是陷阱。要异步走新协议 |

---

## 9. 实施清单

| # | 批 | 文件 | 落点 | 状态 |
|---|---|---|---|---|
| 1 | P0 | `provider/http/stream_client.clj` | `post-stream-deferred`：不 `@future`；`register!` 在**进入时**取出（重试 attempt 跑在完成线程上也登记得上） | ✅ |
| 2 | P0 | `provider/common/openai_compat.clj`、`anthropic.clj` | `call-api-stream-deferred` / `call-anthropic-stream-deferred` | ✅ |
| 3 | P0 | `token-stream-filter-design.md` §2.1、中英 README | 「on-token 线程不保证」（单次流内仍串行、flush 时机不变） | ✅ |
| 4 | P1 | `core/model.clj` | `IAsyncLLMProvider`（无 Object 兜底） | ✅ |
| 5 | P1 | `core/retry.clj` + `core/async.clj` | `run-async`（`fbind` 自递归）+ `async/delayed`（`delayedExecutor`）；判据/曲线与 `run` 共用 | ✅ |
| 6 | P1 | `core/chat_model.clj` | `IAsyncChatModel` + `call-async*` / `stream-call-async*` 兜底 + `DefaultChatModel` 双实现 | ✅ |
| 7 | P1 | `core/chat_client.clj` | `invoke-chat-async` / `invoke-chat-stream-async`；抽出共用的 `chat-model-of` / `token-sink` | ✅ |
| 8 | P1 | `provider/http/client.clj`、`common/base.clj`、`anthropic.clj` | `request-deferred` / `post-deferred`（**响应 map 形状与同步版逐字相同**）；两个 record 实现 `IAsyncLLMProvider`；callback 式旁路改成 deferred 直通 | ✅ |
| 9 | P2 | `client/react.clj` | `run-tool-loop*` 一份迭代体 + `sync-chat-call`/`async-chat-call` × `drive-sync`/`drive-async`；`invoke*` / `resume*` 按跑法选 | ✅ |

**落地过程中值得记的三件事**（都是设计时没预见、被测试逮到的）：

1. **`on-error` 必须 compose 而不是 map**。重试的 handler 返回的是 deferred
   （退避后重跑一次），用 `.exceptionally` 会得到 `deferred<deferred>`。同步路径的
   `(try … (catch t (h t)))` 本来就原样返回 handler 的结果，异步这边补
   `exceptionallyCompose` 才算逐字同义——这是契约 C3 的一条隐含要求，
   已写进 `IChainResult/on-error` 的文档并有回归测试。
2. **一个 `defrecord` 里同一个协议名只能出现一次**。把 `IAsyncLLMProvider` 插在
   `ILLMProvider` 方法中间会让**先前那组方法变成抽象方法**（实测
   `AbstractMethodError: provider_name`）。故异步协议整块放在 record 末尾，
   两个 provider 的注释里都钉了这句。
3. **取消令牌的登记必须在调用线程取出函数**。`streaming/*register-cancel*` 是动态
   var；`post-stream-deferred` 的重试 attempt 跑在完成线程上，那里已经看不见绑定帧。
   同步版靠「整个 loop 都在调用线程」天然成立，异步版必须显式把登记函数闭包住。

**验收（全部有测试，见文末锚点）**

- **两条路径逐字同义**：同一场景 `invoke-chat` 与 `invoke-chat-async` 结果相等
  （手法照抄 `async_invoke_test.clj` 的「同一场景跑两遍」）；
- **兜底可用**：不实现 `IAsyncChatModel` 的 `{:chat-fn …}` 也能拿到 deferred；
- **重试等价**：异步重试次数、退避序列与同步逐字相同（注入 `sleep-fn` / `rand-fn`）；
- **归一化不跑偏**（§0 教训的反例锚点）：断言异步路径的出站消息经过
  `wire/neutral->wire`、响应是 `ChatResponse` 且 replay blocks 在位；
- **取消**：`cancel-token` 在异步流式上仍能中止上游（现有 streaming 测试的异步版）；
- **mock**：测试用 provider 实现 `IAsyncLLMProvider`，覆盖「原生异步」那条分支。

**测试锚点**

| 文件 | 钉住什么 |
|---|---|
| `core/async_chat_model_test.clj` | 探测与兜底（含 `FnChatModel`）、两条路径参数逐字相同、**归一化锚点**（异步返回的是 `ChatResponse` 且 replay blocks 在位）、重试次数/退避序列/`:on-retry` 观测等价、流式两条分支 |
| `core/chain_result_test.clj` | 组合子契约 C1–C5；`on-error` 返回 deferred **不嵌套**（第 1 条教训的回归） |
| `provider/http/stream_client_test.clj` | `post-stream-deferred`：不阻塞（派发 vs 流时长）、与 `post-stream-sync` 结果逐字相同、非 2xx 落 error channel、`:retry` 退避重试且 token 只流出一次、取消在调用线程登记 |
| `client/async_invoke_test.clj` | `invoke-async` ≡ `invoke`（含 memory 落库形状）、**全链路探针**（`:chat` / `:iteration` 链在异步入口下拿到的都是 deferred，同步入口下都是普通值）、`:iteration` 递归重入、暂停透传 + `resume-async`、error channel、8 会话并发墙钟、派发不阻塞 |
| `examples/async_luminus_handler_example.clj` | 端到端（离线）：并发、raise、HITL 202 → resume、turn filter |
| `examples/async_live_test.clj` | **真实端点**，`ASYNC_LIVE_PROVIDER` 切换，**两套 `IAsyncLLMProvider` 实现各验一遍**：`minimax` → `AnthropicProvider`、`zhipu`（glm-5.3-flash）→ `OpenAICompatProvider`。六场景：原生异步分支跑通、派发耗时占整轮 0.0%、流式 token 在虚拟线程上派发（§2.1 契约的实证）、全链路三条链探针 `{:chat 2 :iteration 2 :turn 1}`、4 会话并发（总墙钟 = 最慢那个，不是四者之和）、异步流中途取消 |

---

## 相关文档

- [`filter-chain-design.md`](filter-chain-design.md) §2.6（为什么是 around 不拆
  before/after）、§2.6.4（组合子契约 C1–C6）、§2.7（已落地的 `:turn` 级异步入口）
- [`streaming-async-design.md`](streaming-async-design.md)（SSE 真增量传输选型；
  本文 P0 的底子）
- [`token-stream-filter-design.md`](token-stream-filter-design.md)（`:token-xform`；
  §6.2 的线程契约要补在那里）
- [`provider-variant-design.md`](provider-variant-design.md) §2.2（可选协议 +
  `satisfies?` 探测的惯例出处）
- [`design-principles.md`](design-principles.md) §1（无真实需求不建——P2 的判据）、
  §2（框架无关 / core 零依赖）
