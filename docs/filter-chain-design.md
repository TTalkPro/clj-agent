# Filter 四链体系设计（:tool / :chat / :iteration / :turn）

> **状态：✅ 已全部实施（2026-07-11 三链落地；2026-08-25 补第四条链 `:iteration`
> 与装配期预编译，全套 376 tests / 1630 assertions / 0）。**
> 本文是 filter 体系的**整合权威参考**：洋葱机制、装配期预编译、四条链的粒度与
> 契约、递归重入模式、内置 filter、硬规则。演进推导散见
> `agent-loop-concurrency-design.md`（§4.6 tool 链契约收紧、§14 turn 链）；
> 与 HITL 的交互见 `hitl-timeline-design.md`。
>
> 实现：`core/filter.clj`（机制 + 内置 filter + `compile-hooks`）、
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
  max-iterations 预算；
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
