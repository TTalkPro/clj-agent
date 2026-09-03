# Filter 四链体系设计（:tool / :chat / :iteration / :turn）

> **状态：✅ 已全部实施（2026-07-11 三链落地；2026-08-25 补第四条链 `:iteration`
> 与装配期预编译，全套 376 tests / 1630 assertions / 0）。**
> 本文是 filter 体系的**整合权威参考**：洋葱机制、装配期预编译、四条链的粒度与
> 契约、递归重入模式、内置 filter、硬规则。演进推导散见
> `agent-loop-concurrency-design.md`（§4.6 tool 链契约收紧、§14 turn 链）；
> 与 HITL 的交互见 `hitl-timeline-design.md`。
>
> **2026-09-03 增补 §2.6 / §2.7**：`:turn` **不拆** `before`/`after`（决策记录 +
> async 前瞻，§2.6）；组合子 `flt/fmap` / `fbind` / `fcatch` + `IChainResult` 协议
> 与 CompletionStage 适配层已实现，内置 filter 响应侧已改写，`:turn` 终端已可异步
> ——`react/invoke-async`、`simple-agent/chat-async`，对接 Ring / Luminus 异步
> handler（§2.7）。全套 406 tests / 1820 assertions / 0。
>
> 实现：`core/filter.clj`（机制 + 内置 filter + `compile-hooks` + 组合子）、
> `core/async.clj`（CompletionStage 适配 + 虚拟线程入口 + Ring 回调 sink）、
> `core/chat_client.clj`（:chat/:tool 组装点、`hooks` 字段、`filter-hooks` /
> `with-filters`）、`client/react.clj`（:turn 与 :iteration 组装点）、
> `client/filter/memory.clj`（memory filter）。

---

## 0. 总览：一个机制，四个粒度

```
:turn  ──包 整个工具循环（每 turn 一次）──────────────────────┐
         │                                                  │
         │ :iteration ──包 单轮迭代（LLM 调用 + 本轮工具批次）─┐ │
         │   │                                             │ │
         │   │  :chat ──包 单次 LLM 调用──┐                  │ │
         │   │           │  Plan(LLM)   │                  │ │
         │   │           └─────────────┘                  │ │
         │   │  :tool ──包 单次工具执行（并行任务内各自生效）┐ │ │
         │   │           │   tool exec              │      │ │
         │   │           └─────────────────────────┘      │ │
         │   │        （gate 预判 → map → 屏障 → 折叠）      │ │
         │   └─────────────────────────────────────────────┘ │
         └──────────────────────────────────────────────────┘
```

| 链 | 包什么 | 频次 | 典型职责 |
|---|---|---|---|
| `:tool` | 单次工具执行 | 每个 tool call 一次（并行任务内） | 超时 / 审批短路 / 限流 / 日志 |
| `:chat` | 单次 LLM 调用 | 工具循环内每轮一次 | memory（历史拼接与落库）/ 请求改写 / 单轮重试 |
| `:iteration` | 单轮迭代（LLM + 本轮工具批次） | 每轮一次 | 单轮墙钟预算 / 单轮重试与回滚 / 基于本轮工具结果的收尾决策 |
| `:turn` | 整个工具循环 | 每 turn 一次 | RAG 注入 / 最终答案校验与 guardrail / turn 级预算 / evaluator 递归 |

**`:iteration` 与 `:chat` 同频，差别只在包住的范围**：`:chat` 的 resp 是 LLM
响应，看不到本轮工具跑出了什么（工具那一半被 `ToolCallingManager` 与 `:tool`
链接管）；`:iteration` 把两半合起来。此前想在一轮收尾时基于工具结果做事，只能
靠「下一轮 `:chat` 的 delta 就是上一轮的工具结果」回头看——而**末轮没有下一次
`:chat`**（return-direct 收尾、或工具执行后循环结束），那批结果永远不经过
`:chat` 链，靠这条路做「每轮必然一次」的逻辑会在末轮静默漏掉。

一个 filter 可同时携带四个钩子，各挂各的链。形态是 `Filter` record
（`create-filter` 直接产出；写普通 map 也行，`build-chat-client` 经 `as-filter`
归一化）——五个钩子是固定字段，其余键（memory filter 的 `:store`）进 ext-map
照常可读。**执行顺序 = `:filters` 向量注册顺序**（无 order/phase），
靠前者在最外层（最先见 req、最后见 resp）。

> **第五钩子 `:token-xform`（2026-07-14）**：流式专用、非 around 形状——值为
> **transducer**，变换 invoke-chat-stream 出站 token 流（1→N / 跨 chunk
> 状态 / 流末 flush）。只变换交付给 on-token 的流，不改最终 `:response`。
> 权威设计见专文 `token-stream-filter-design.md`，本文不重复。

---

## 1. 洋葱机制：6 行闭包折叠

```clojure
(defn build-chain [around-fns terminal]
  (reduce (fn [downstream f]
            (fn [req] (f req downstream)))
          terminal
          (reverse around-fns)))
```

所有 filter 同一形状 `(fn [req chain] -> resp)`，三种能力来自形状本身：
**改写 req** 后调 chain、**短路**（不调 chain）、**around**（前后做事/计时/重试）。
filter 私有状态走闭包（memory 的 store、缓存 atom）。

**关键性质：chain 参数天然"仅下游"**（每层闭包只包含更内层）。
filter 可多次 `(chain req)` 而绝不会重跑上游——Spring AI 2.0 为递归
advisor 专门新造的 `chain.copy(this)`，在闭包模型里是构造性的免费性质。

### 1.1 装配期预编译（2026-08）

链的**结构**在 `build-chat-client` 时就完全确定了——哪些 filter、什么顺序、
各挂哪条链，全是声明。只有 terminal 必须每次现做（它闭包住这一次要执行的
具体工具/LLM 调用）。于是把两者拆开：

```clojure
(compile-chain around-fns)   ;; 装配期 → (fn [terminal] -> chain)
```

`build-chat-client` 调 `compile-hooks` 把四条链各折一次，结果存进 ChatClient 的
`hooks` 字段（`CompiledHooks` record）：`:chat` / `:tool` / `:turn` 三个
chain-builder + 预 `comp` 好的 `:token-xform`。运行期只剩
`((:tool (filter-hooks chat-client)) terminal)`。

省掉的是每次 invoke 的 `keep` 全量扫描 + `reverse` + `reduce`——工具循环里
**每轮每个 tool call 一次**。额外收益：`compile-chain` 对空序列返回
`identity`，没挂 tool filter 的 chat-client 连一层包装都不加。

> **改 `:filters` 必须走 `chat-client/with-filters`**（它同步重编 hooks）。直接
> `(assoc chat-client :filters ...)` 会让 hooks 与 `:filters` 脱钩——`filter-hooks`
> 检测到不同源会**现场重编译**兜底（语义永远跟着 `:filters` 走，filter 静默
> 失效是这套机制最难查的一类 bug），但那是每次 invoke 都重编，白扔装配期成果。

**请求对象刻意不做 record**：filter 会 `assoc` / `update` / `dissoc`
ChatRequest 与 TurnRequest（`validation-turn-filter` 就 `dissoc` 掉
`:resume?`），而 record 一旦 `dissoc` 已声明字段就降级成普通 map——把可变形
的请求包进 record 只会换来一个随调用链漂移的类型。record 用在**声明**上
（Filter、CompiledHooks、ChatClient），不用在**流动的数据**上。

---

## 2. 四条链的契约

### 2.1 `:tool` 链（chat-client/invoke-tool 组装）

```
ToolRequest  {:function {:name :schema :sensitive} :args :context(只读)}
ToolResponse {:result (:writes) (:error {:class :message})}
```

- 可改写 `:args`/`:function`、短路、around；**`:context` 是请求侧只读字段**
  ——响应侧无 `:context`，短路分支不需要（也不应）回传（曾经的易错点已消除，
  演进见 agent-loop-concurrency-design.md §4.6）；
- 短路语义：不带 `:writes` 的响应 = 该调用的写意图不生效（事务性）；
  带 `:error {:class ...}` 可参与屏障处的分层路由（内建超时机制标
  `:transient` 即此用法——超时本身已非 filter，见 §3 表）；
- **警示**：`:tool` 链运行在并行任务内——交互式审批放 agent 的
  `:tool-gate`（批前串行预判），勿放 tool filter（会并发弹提示）。

### 2.2 `:chat` 链（chat-client/invoke-chat(-stream) 组装）

```
ChatRequest  {:messages :tools :tool-choice :system-prompt (:on-token) :context(只读)}
ChatResponse {:response :context}
```

- 可改写 `:messages`（memory 的 delta→全历史替换即此）、`:tools` 等；
- 同一条链同时服务同步与流式路径（invoke-chat / invoke-chat-stream）；
- memory filter 刻意放**循环内**（每轮落库完整 transcript，含工具往返）——
  heal-dangling、暂停恢复、timeline 都依赖完整历史；不跟 Spring AI
  "memory 放循环外"的做法。

### 2.3 `:iteration` 链（react/run-tool-loop 组装，2026-08-25）

```
IterationRequest {:messages 本轮 delta :context 本轮起始 ctx :index 轮序(0起) :remaining 剩余预算}
IterationResult  {:status :continue :messages 下一轮 delta :context 折叠后 ctx}
                 或既有终态 {:status :completed|:paused|:cancelled ...}
```

- terminal = 一轮迭代（LLM 调用 + 该轮工具批次）；外层 loop 只在 `:continue`
  时推进，其余状态原样返回给 `:turn` 链；
- 可改写 `:messages`（本轮 delta）/`:context`；可改写 `:continue` 结果的
  `:messages` 来影响**下一轮**的 delta；可不调 chain 短路成 `:completed`；
- **递归重入**：可多次 `(chain req)` 重跑这一轮（单轮重试）。重入 = 那一轮的
  LLM 调用与工具批次**真的又跑了一遍**，故 `remaining` 与 `records` 都如实
  计入——记「发生过什么」，不记「逻辑上算几轮」。`max-iterations` 因此对
  filter 重入仍是硬上限（有测试钉住：每轮都重入的 filter 会撞上限抛出）；
- **硬规则同 `:turn`**：`:paused` / `:cancelled` 结果**必须透传、不得重入**；
- **暂停照常出链**：暂停是终端的返回值而非异常，`:paused` 沿链回流、around
  后半段照常执行，filter 看得见暂停——单轮计时/预算记账在 HITL 下也能正常
  收尾。**但 resume 的那半批不算一轮**：resume 执行的是「暂停那一轮的下半截」
  （批次已定、无新 LLM 调用），不经过本链；续跑的循环从下一个完整轮重新进链，
  `:index` 从 0 重新计；
- **`remaining` / `records` 存 volatile 而非 loop 参数**，正是为了让 filter
  重入如实记账。扣减点在批次实际执行之后，与加这层之前
  `(recur … (dec remaining) …)` 的时机逐字相同；
- 没挂 `:iteration` filter 时链是 `identity`——终端即循环体本身。

### 2.4 `:turn` 链（react/invoke 组装）

```
TurnRequest  {:messages 本轮入口消息(delta) :context 初始 ctx}
TurnResult   react 循环结果 {:status :response :tool-context :tool-calls-made ...}
```

- terminal = 整个 run-tool-loop；可改写入口 `:messages`/`:context`；
- **递归重入**：可多次 `(chain req)`（校验重试 / evaluator-optimizer）。
  重入的 `:messages` 应为**新 delta**（如反馈消息），完整上下文由 memory
  filter 拼接——递归类 turn filter 须与 memory 同挂；每次重入获得全新
  max-iterations 预算。**这条能力正是「不把 `:turn` 拆成 before/after」的决定性
  理由**——见 §2.6；
- **硬规则**：`:paused` / `:cancelled` / `:error` 结果**必须透传、不得重入**
  （暂停态上重试会破坏 HITL 语义），有测试钉住；
- **resume 同样经过 turn 链**——语义与机制见 §2.5。

### 2.5 resume 与 turn 链（一次性分派终端）

**问题**：turn = 用户消息 → 循环（可能中途暂停）→ 最终答案。暂停发生时，
原来那次 turn 链调用已随 `:paused` 结果**退栈结束**（turn filter 按硬规则
透传了它）；之后的 `resume` 是一次全新调用。若 resume 不进 turn 链，
guardrail/校验类 filter 对"被人打断过的 turn"的最终答案就是盲区。

**机制**：`react/resume` 重新组同一条 turn 洋葱，但终端做**一次性分派**：

```clojure
terminal (fn [treq]
           (if (compare-and-set! consumed? false true)
             (continuation)            ;; 首次进入：延续暂停的 turn（消费 loop-state，
                                       ;;   审批批次执行 / env 重跑——原 resume 逻辑）
             (run-tool-loop chat-client (:messages treq) ...)))  ;; 递归重入：全新循环
```

两类进入的语义天然不同，且都正确：

| 进入 | 是什么 | :messages 来源 |
|---|---|---|
| 首次 | 暂停 turn 的**延续**——续跑点在 loop-state 里，不存在"入口消息" | 忽略（nil） |
| 递归重入 | turn 已完成一次后的**反馈轮**（校验不合格等）——就是普通新 delta | filter 提供（如反馈消息），上下文由 memory 拼接 |

**TurnRequest 契约扩展**：resume 进入时带 `:resume? true`、`:messages nil`
（invoke 进入无此标记）。filter 作者指引：

- **请求侧改写类**（RAG 注入、输入增强）：`(when-not (:resume? req) ...)`
  跳过首次改写——要改写的入口消息在延续场景不存在，改了也会被忽略。
  **递归重入方构造重入 req 时应 `(dissoc req :resume?)`**（重入是有真实
  入口消息的新循环，下游请求侧 filter 应照常工作）——内置
  validation-turn-filter 已如此实现；
- **响应侧类**（校验/guardrail/预算）：**零感知**——resume 完成的最终答案
  照常经过，`(chain req)` 重入照常工作；
- 短路（不调 chain）合法：延续不会发生，loop-state 保持未消费
  （暂停态仍在，可再次 resume）。

**测试锚点**（turn_filter_test.clj）：暂停 → resume → 不合格答案 → 校验
反馈重入 → 合格（恰好 3 次 LLM 调用）；`:resume?` 标记与 nil :messages
的探针断言。

### 2.6 为什么是 around，不拆 `before` / `after`（含 async 前瞻）

> 起因是一个合理的担心：**一旦 chat 走 Flux / 异步，around 的「后半段」还拆得
> 开吗？不如现在就把 `:turn` 拆成 `turn-before` + `turn-after`。**
> 结论：**不拆**。async 改的是链的**返回类型**，不是 around 的**形状**；而
> before/after 恰恰是那个在 async 下更难写、且今天就已经表达不了我们主用例的形状。

#### 2.6.1 before/after 是 around 的真子集

| 能力 | around `(fn [req chain])` | before + after |
|---|---|---|
| 改写请求 | ✅ | ✅ |
| 观察响应 | ✅ | ✅ |
| 短路（不进循环） | ✅ 不调 chain 即可 | ⚠️ 得另造 `{:abort result}` 返回协议——把 around 手工重新发明一遍，还更差 |
| 栈上持有状态（计时 / turn 级预算 / tracing span） | ✅ `let` + try/finally | ❌ 必须在 req 里挖 `:filter-state` 口袋自己传递 |
| after 的逆序执行 | ✅ 闭包折叠的构造性性质 | ⚠️ 手工保证 |
| **递归重入 `(chain req)` N 次** | ✅ | ❌ **根本表达不了** |

最后一行是决定性的：before/after 里压根没有 `chain` 这个可调用的东西，它是一个
被动通知点。而**递归重入正是 `:turn` 链存在的一半理由**（§2.4）——拆完之后
`validation-turn-filter`（调 chain 至多 3 次）与 evaluator-optimizer 直接作废，
`:turn` 只剩 RAG 注入那一类请求侧改写能活，退化成 §2.2 `:chat` 的一个低频版本。

**「只想在 turn 前后观察一下」这个需求已有归属**：`client/callbacks.clj` 的
`:on-turn-start` / `:on-turn-end` / `:on-turn-error`（与 kernel filter 刻意解耦的
九钩子体系）。再加一对 `turn-before` / `turn-after`，是在复制 callbacks 的同时
把 filter 削弱成 callbacks——两边都变差。

#### 2.6.2 async 改的是返回类型，不是形状

around 在 reactive 下照样成立，变的只是「后半段」从直线代码变成续延：

```clojure
;; 今天（同步）
(let [r (chain req)] (post r))
;; 异步化之后
(-> (chain req) (fmap post))
```

三条佐证：

- **Spring AI 自己**：1.0 的 `CallAroundAdvisor` / `StreamAroundAdvisor`、2.0 的
  `CallAdvisor` / `StreamAdvisor.adviseStream(req, chain) -> Flux`——一路 reactive
  化，around 形状一次没变过。反倒是 before/after 在 async 下更难写：**没有栈了**，
  before 攒的状态只能靠显式 context 传给 after。
- **turn 链天生是 Mono 不是 Flux**：TurnResult 是单值。Flux 性只存在于 token 层，
  而 token 层我们**故意没做成 around**——`:token-xform` 是 transducer（§0 第五钩子 /
  `token-stream-filter-design.md`）。「单值链用 around，流用 transducer」这个按
  形状分家的决定，本身就是这个担心的既有答案，且已经落地。
- **我们跑在 Loom 上**：`core/tool.clj` 的超时、`react/run-on-executor` 的并行工具
  批次、`subagent/manager.clj` 全是虚拟线程 + 阻塞调用。这个技术栈下的「async」
  大概率是结构化并发，around 一个字都不用改。Flux 那种控制反转，正是 Spring 因为
  Loom 在往回撤的东西。

#### 2.6.3 真正会被 async 咬到的（都不是钩子形状）

- `binding` 动态绑定不跨异步边界——同类问题已在 `run-on-executor` 用 `bound-fn*`
  处理过（§2.1 警示的同源问题）；
- 包着 `(chain req)` 的 `try/catch` 抓不到异步失败，得换成 error channel 组合
  （见下 `fcatch`）；
- filter 内部 `@` 阻塞等结果会把异步收益吃光——**契约上禁止**（见下）。

#### 2.6.4 前瞻性对冲：`flt/fmap` 契约（同步版已实现，2026-09-03）

不拆钩子，改为**把契约放宽成「返回 resp **或** deferred\<resp\>」**，并给 filter
作者一组组合子。今天在同步世界里它们是恒等展开，零成本；将来终端异步化，走组合子
的 filter 源码一行不用动。

```clojure
;; core/filter.clj —— 协议进 core，异步实现不进（C5）
(defprotocol IChainResult
  (fmap  [x f])        ;; 响应侧改写的唯一入口：(flt/fmap (chain req) (fn [result] ...))
  (fbind [x f])        ;; f 自身返回链结果（flatMap）；递归重入在 async 下的形状
  (on-error [x handler]))  ;; 挂 error channel；同步值原样返回。一般不直接调，用 fcatch

(extend-protocol IChainResult          ;; 同步路径 = 恒等展开
  nil    (fmap [_ f] (f nil)) (fbind [_ f] (f nil)) (on-error [x _] x)
  Object (fmap [x f] (f x))   (fbind [x f] (f x))   (on-error [x _] x))

(defmacro fcatch [expr handler]        ;; 同步 try/catch + 异步 error channel，二合一
  `(let [h# ~handler]
     (try (on-error ~expr h#) (catch Throwable t# (h# t#)))))
```

**`fcatch` 为什么是宏**：同步下异常在**实参求值**时就抛了，函数形态的
`(fcatch (chain req) h)` 根本接不住——h 还没拿到控制权。宏把表达式包进 try，
才能与 `fmap` 组成同一条 `->` 链，且顺带覆盖「异步终端在返回 deferred 之前
就同步抛」这一路。

**契约条款**

| # | 条款 | 说明 |
|---|---|---|
| C1 | **形态保持** | 同步值进 → 同步值出；deferred 进 → 同类 deferred 出。filter 因此对「链是否已异步化」**零感知**。`fbind` 是定义使然的例外：同步值上 f 返回 deferred，结果就是 deferred。 |
| C2 | **永不阻塞** | `fmap` / `fbind` 内部绝不 deref。想拿值就 `fmap`，**不要 `@`**——`@` 是异步化时唯一必须人工改写的写法。 |
| C3 | **异常语义不变** | `f` 抛异常：同步路径原样抛出（与今天逐字相同，现有测试不动）；异步路径落进 deferred 的 error channel，**不吞**。 |
| C4 | **组合律** | `(fmap (fmap x f) g) ≡ (fmap x (comp g f))`；`(fmap x identity)` ≡ `x`（同步路径下还是**同一个对象**）。另有 `fbind` 的左单位元与结合律。这条是「今天写的 filter 明天不用改」的形式保证，已由 `chain_result_test.clj` 钉住。 |
| C5 | **不绑定异步库** | 靠可扩展协议 `IChainResult`（不是类型判断）；CompletableFuture / manifold / core.async chan 由适配层 `extend-protocol` 注入——与 `IRetriever`（rag）、`IToolIndex`（tool-search）同一取舍：**协议进 core，实现不进**。参考实现见 `chain_result_test.clj` 顶部的 CompletableFuture 适配（约 8 行）。 |
| C6 | **`:token-xform` 不参与** | 它是 transducer 不是 around，流的异步化由 provider 的流实现负责，与本组合子无关。 |

**递归重入在 async 下的形状**（这是 async 化真正要重写的地方，也是又一条「不拆」
的理由——**只有 around 表达得了**）：

```clojure
;; 同步：loop/recur
(loop [attempt 0, req req]
  (let [result (chain req)]
    (if (retry? result) (recur (inc attempt) (feedback req result)) result)))

;; 异步：fbind 自递归（形状同构，chain 仍是那个 chain）
(letfn [(step [attempt req]
          (fbind (chain req)
                 (fn [result]
                   (if (retry? result) (step (inc attempt) (feedback req result)) result))))]
  (step 0 req))
```

before/after 版本这里连写都写不出来——没有 `chain` 可以再调一次。

**迁移路径（三步，前两步互不阻塞）**

1. ✅ **已完成**：加 `fmap` / `fbind` / `fcatch`（同步实现 = 恒等展开），内置
   filter 的响应侧逐个改写成组合子——`logging-filter`、`logging-chat-filter`、
   memory filter 走 `fmap`，`validation-turn-filter` 的 `loop/recur` 换成 `fbind`
   自递归。同步语义逐字不变，测试全绿。
2. ✅ **已完成（`:turn` 层）**：`react/invoke-async` 的 terminal 返回 deferred，
   四条链的契约实际已是 `-> resp | deferred<resp>`。链的折叠代码
   （`build-chain` / `compile-chain`）**一个字没改**——它只传递闭包，不看返回值
   类型，这正是当初的预期。`:chat` / `:tool` 终端仍同步（provider HTTP 仍阻塞），
   见 §2.7。
3. **第三方 filter**：用 `@` 或裸 `try/catch` 硬写的才需要改；走组合子的零改动。

### 2.7 异步入口：`invoke-async` / `chat-async`（2026-09-03 落地）

§2.6.4 的组合子不是纸面推演——第一个消费者已经在了：**Ring / Luminus 的异步
handler**。HTTP 工作线程不该被一次 LLM 往返占住。

**落地形态**

| 位置 | 东西 | 说明 |
|---|---|---|
| `core/async.clj` | `IChainResult` 的 CompletionStage 实现 | C5 说的适配层。JDK 自带、零依赖，故可住 core；manifold / core.async 照抄形状自写 |
| 同上 | `vthread` / `inline` | 两种「跑法」，同签名 `(fn [thunk])`：前者丢虚拟线程返回 `CompletableFuture`，后者当场执行返回普通值 |
| 同上 | `on-complete` / `join` / `unwrap-cause` | 出口：两回调 sink（就是 Ring 的 `respond`/`raise`）、阻塞取值、剥 JDK 包装 |
| `client/react.clj` | `invoke-async` / `resume-async` | turn 终端跑在虚拟线程上 |
| `client/simple_agent.clj` | `chat-async` / `resume-async` | agent 级同款 |

**一份代码两条路径**：`invoke` 与 `invoke-async` 是同一个 `invoke*`，只差一个
`run` 参数（`async/inline` vs `async/vthread`）；`resume*`、`run-loop(-async)`
同理。编排代码的响应侧全走 `flt/fmap` / `flt/fcatch`，于是**同步进同步出、
异步进异步出**——这就是契约 C1「形态保持」的直接兑现，也是「不拆
before/after」这个决定省下的钱：拆了的话这里得写两套。

**异步到哪一层（如实声明）**：只有 `:turn` 的终端异步化。`:chat` / `:tool`
两条内层链仍是同步的——provider 的 HTTP 客户端还是阻塞式，它们只是被整体搬到
了虚拟线程上。这在 Loom 下**就是正确姿势**（§2.6.2）：阻塞代码一行不用改写成
回调，而调用线程立刻拿到 future。真要把 provider 也换成异步 HTTP，那时
`:chat` 终端返回 deferred，链的代码依然不用动——那一层的设计（可选协议
`IAsyncChatModel` / `IAsyncLLMProvider` + 虚拟线程兜底 + `retry/run-async`，
以及**什么条件下才值得做**）见 [`async-chat-model-design.md`](async-chat-model-design.md)。

**turn filter 作者须知**：走异步入口时 `(chain req)` **真的**返回 deferred。
响应侧必须 `fmap` / `fbind`，`(let [r (chain req)] …)` 会拿到 deferred 本身
（计时 filter 会立刻「结束」、校验 filter 会对着 future 做判断——都不报错，
只是静默错）。硬规则不变：`:paused` / `:cancelled` / `:error` 透传不重入。

**Ring / Luminus 对接**

```clojure
(defn chat-handler [request respond raise]          ;; Ring 3 三参数异步 handler
  (-> (agent/chat-async (session-agent request) (message-of request))
      (flt/fmap ->ring-response)                    ;; 同步链上这行也成立
      (async/on-complete respond raise)))

;; reitit 路由按 arity 自动识别异步；服务器需开异步
;; （ring-jetty {:async? true} / immutant / http-kit 均可）
["/api/chat" {:post {:handler chat-handler}}]
```

`:paused` 渲染成 202 + pending-tool，人批准后打 `/resume` 走
`agent/resume-async`——HITL 两段都不占 HTTP 线程。完整可跑示例（含并发实测、
raise 路径、HITL、turn filter）：`examples/async_luminus_handler_example.clj`，
离线不需要 API Key。

**边界**

- **agent 的 state-atom 是单会话状态机**：别在同一个 agent 实例上并发多个
  `chat-async`（`start-turn!` 会互相踩 run-id / 暂停态）。web 场景一会话一实例；
- turn 级回调（`:on-turn-end` / `:on-interrupt`…）在**工作线程**上触发，不在
  调用线程——回调里要碰请求作用域的东西请自行切回；
- `heal-dangling-tool-calls!` 也走 `run`：它是 store IO，异步模式下不该占住调用线程；
- `async/join` 只给调用方（脚本 / 测试 / 同步 API 边界）。**filter 内部禁止**
  ——契约 C2。

**测试锚点**：`core/async_test.clj`（线程模型、绑定传导、异常解包、回调对接）、
`client/async_invoke_test.clj`（异步 ≡ 同步、turn filter 真拿到 deferred、
递归重入、暂停透传 + resume-async、error channel、8 会话并发墙钟）。


---

## 3. 内置 filter

| filter | 链 | 说明 |
|---|---|---|
| `logging-filter` | :tool | 调用前后日志 |
| ~~`(timeout-filter ms)`~~ | — | **已删除（2026-07-16）**：超时是内建机制，不是 filter——`deftool {:timeout ms}` > 引擎 `(…-tool-calling-manager {:timeout ms})` > 不超时，由 `chat-client/invoke-tool` 在 filter 链**之外**强制（机制本体 `tool/call-with-timeout`）。超时结果仍标 `:error {:class :transient}`（`:retry` 可自动重试）。见 `tool-timeout-design.md` |
| `(approval-filter fn?)` | :tool | 敏感工具审批，拒绝短路（交互式场景请改用 gate） |
| `(logging-chat-filter :log-fn f :preview n)` | :chat | LLM 请求/响应日志（对标 Spring `SimpleLoggerAdvisor`；`logging-filter` 的 LLM 侧对应物） |
| `(validation-turn-filter validate-fn :max-retries n)` | :turn | 最终答案校验：不合格把原因作反馈消息重入循环；耗尽原样返回；非 :completed 透传。对标 Spring AI `StructuredOutputValidationAdvisor`，配 provider 原生 json_schema 使用；判据可用 `advisor/structured-output` 生成 |
| `(safeguard-turn-filter 敏感词 :failure-response s)` | :turn | 入口消息命中敏感词 → 不进循环直接拒答（对标 `SafeGuardAdvisor`）。**挂 :turn 不挂 :chat**：:chat 会每轮重查累积历史 |
| `(re-reading-filter :template f)` | :turn | RE2 重读：入口用户问题重复一遍（对标 `ReReadingAdvisor`） |
| `(token-redact-filter re replacement)` | :token-xform | 流式出站 token 无状态正则脱敏（跨 chunk 限制见专文） |
| `(hold-release-filter check-fn)` | :token-xform | 先审后放：缓冲整流，完流 check-fn 全文，通过原序放行 / 不通过 emit 单个替换 token |
| `(memory-filter store)`（client） | :chat | 按 conversation-id 读写历史；应放 filters 首位 |
| `(ts/with-tool-search opts {:index ...})` | :chat + 工具 + 槽 | 渐进式工具披露（对标 `ToolSearchToolCallingAdvisor`）：初始只暴露 `search_tools`，检索到的工具下一轮才进列表。详见 `advisor-alignment-design.md` §2 |
| `(rag/qa-turn-filter retriever :top-k n)` | :turn | 检索增强注入（对标 `QuestionAnswerAdvisor`）：每 turn 检索一次并拼进用户问题。零依赖，向量库经 `IRetriever` 注入 |

工具侧另有两个 deftool/chat-client 选项对齐 ToolCallingAdvisor（非 filter）：
`{:return-direct true}`（结果即最终答案，不回灌 LLM；整批全声明才生效）与
`build-chat-client` 的 `:eligibility-fn`（续跑判据，对标
`ToolExecutionEligibilityChecker`）。

---

## 4. 与 Spring AI 2.0 advisor 的对照（吸收记录）

> **逐个 advisor 的完整对齐记录见专文 `advisor-alignment-design.md`（2026-07-15
> 全面对齐，292/1194）。本表为速览；其中 SafeGuard 与 QuestionAnswer 两项的
> 旧结论已被推翻，理由见专文 §5 / §7.1。**

| Spring AI 2.0 | 我们 | 结论 |
|---|---|---|
| ToolCallingAdvisor（循环进链，位置=粒度） | `:turn` 钩子（循环本体不动，只包一层）+ `:return-direct` + `:eligibility-fn` | **吸收能力**——不学大搬家：循环与 gate/HITL/暂停/持久化深度耦合，为优雅重构不值 |
| `ToolSearchToolCallingAdvisor` | `advisor/tool-search`（`IToolIndex` + `search_tools` + `:chat` filter） | **吸收**——零新增钩子：`:writes` 折叠进 context + `:chat` 改写 `:tools` 即成 |
| `chain.copy(this)` 递归 advisor（1.1 experimental） | 闭包链天然仅下游 | **免费拥有**，无需新 API |
| StructuredOutputValidationAdvisor | `validation-turn-filter`（机制）+ `advisor/structured-output`（判据） | 吸收（机制早有；本次补 schema 判据与人话报错） |
| `StreamAdvisor` 返回 `Flux<ChatClientResponse>`（流是一等值，Call/Stream 双接口） | `:token-xform` transducer 变换出站 token 流 | **吸收算子思想**（1→N/有状态/flush），不引 Reactor、不拆双接口——详见 `token-stream-filter-design.md` §5 |
| SafeGuardAdvisor | `safeguard-turn-filter`（:turn） | ~~不跟~~ **已吸收**（2026-07-15；旧结论「一个 chat filter 即可」写在 turn 链之前，挂点判断有误） |
| QuestionAnswerAdvisor（RAG） | `advisor/rag` `qa-turn-filter`（`IRetriever` 注入） | ~~不跟本体~~ **已吸收**（2026-07-15；仍不引 vector store——本体价值在提示词编排，不在检索） |
| memory advisor 放循环外（Spring） | memory 刻意放循环内 | 不跟（完整 transcript 是我们的契约） |
| RetrievalAugmentationAdvisor / VectorStoreChatMemoryAdvisor | — | 不跟（需 vector store 本体 / 与完整 transcript 契约冲突） |
| advisor context map（跨 advisor 状态） | 请求 map 透传 + 闭包（跨工具状态另有 `:writes`+`:state-slots`） | 不跟（已够） |
| getOrder 数值排序 | 注册顺序即层序 | 不跟（显式列表更直白） |

---

## 5. 组合示例

```clojure
(chat-client/build-chat-client
  {:chat-model cm
   :tools   [#'search #'save-note]
   :filters [(ma/memory-filter store)                        ;; :chat，首位
             {:name :rag                                     ;; :turn，每 turn 注入一次
              :turn (fn [req chain]
                      (chain (update req :messages
                                     #(into [(msg/system (retrieve (:messages req)))] %))))}
             (filters/validation-turn-filter must-be-json)   ;; :turn，答案校验重试
             filters/logging-filter]})                       ;; :tool，日志
;; turn 洋葱：rag → validation → [循环: memory → LLM；logging → 工具]
;; （单工具超时不走 filter：deftool {:timeout ms} / 引擎 {:timeout ms} 即生效）
```

## 相关文档

- `advisor-alignment-design.md`（**Spring AI 2.0 逐个 advisor 的对齐记录**：
  ToolSearch / SafeGuard / RAG / return-direct / 结构化输出判据）
- `agent-loop-concurrency-design.md` §4.6（tool 链契约收紧的动因与盘点）、
  §9（`:writes`/`:state-slots` MapReduce 契约）、
  §14（turn 链的实施记录与 Spring AI 调研）
- `hitl-timeline-design.md`（gate/暂停/resume 与 filter 链的边界分工）
- `token-stream-filter-design.md`（`:token-xform` token 流变换链权威设计）
- `async-chat-model-design.md`（🚧 `:chat` 终端异步化的下一层设计：可选异步协议、
  `retry/run-async`、分批判据。本文 §2.7 是它的上游）
