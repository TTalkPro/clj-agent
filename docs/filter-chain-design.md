# Filter 三链体系设计（:tool / :chat / :turn）

> **状态：✅ 已全部实施（2026-07-11，全套 241 tests / 1013 assertions / 0）。**
> 本文是 filter/advisor 体系的**整合权威参考**：洋葱机制、三条链的粒度与
> 契约、递归重入模式、内置 filter、硬规则。演进推导散见
> `agent-loop-concurrency-design.md`（§4.6 tool 链契约收紧、§14 turn 链）；
> 与 HITL 的交互见 `hitl-timeline-design.md`。
>
> 实现：`core/advisor.clj`（机制 + 内置 filter）、`core/kernel.clj`
> （:chat/:tool 组装点）、`client/react.clj`（:turn 组装点）、
> `client/advisor/memory.clj`（memory filter）。

---

## 0. 总览：一个机制，三个粒度

```
:turn  ──包 整个工具循环（每 turn 一次）────────────────────┐
         │                                                │
         │  :chat ──包 单次 LLM 调用（循环内每轮）──┐        │
         │           │        Plan(LLM)          │        │
         │           └──────────────────────────┘        │
         │  :tool ──包 单次工具执行（并行任务内各自生效）─┐   │
         │           │        tool exec           │      │
         │           └───────────────────────────┘      │
         │        （gate 预判 → map → 屏障 → 折叠）        │
         └────────────────────────────────────────────────┘
```

| 链 | 包什么 | 频次 | 典型职责 |
|---|---|---|---|
| `:tool` | 单次工具执行 | 每个 tool call 一次（并行任务内） | 超时 / 审批短路 / 限流 / 日志 |
| `:chat` | 单次 LLM 调用 | 工具循环内每轮一次 | memory（历史拼接与落库）/ 请求改写 / 单轮重试 |
| `:turn` | 整个工具循环 | 每 turn 一次 | RAG 注入 / 最终答案校验与 guardrail / turn 级预算 / evaluator 递归 |

一个 filter map 可同时携带三个钩子，各挂各的链；`create-filter` 或直接写
map 均可。**执行顺序 = `:filters` 向量注册顺序**（无 order/phase），
靠前者在最外层（最先见 req、最后见 resp）。

> **第四钩子 `:token-xform`（2026-07-14）**：流式专用、非 around 形状——值为
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

链不缓存、每次调用现场组装（闭包纳秒级；terminal 本就必须每次现做——
它闭包住这一次要执行的具体工具/LLM 调用）。

---

## 2. 三条链的契约

### 2.1 `:tool` 链（kernel/invoke-tool 组装）

```
ToolRequest  {:function {:name :schema :sensitive} :args :context(只读)}
ToolResponse {:result (:writes) (:error {:class :message})}
```

- 可改写 `:args`/`:function`、短路、around；**`:context` 是请求侧只读字段**
  ——响应侧无 `:context`，短路分支不需要（也不应）回传（曾经的易错点已消除，
  演进见 agent-loop-concurrency-design.md §4.6）；
- 短路语义：不带 `:writes` 的响应 = 该调用的写意图不生效（事务性）；
  带 `:error {:class ...}` 可参与屏障处的分层路由（timeout-filter 标
  `:transient` 即此用法）；
- **警示**：`:tool` 链运行在并行任务内——交互式审批放 agent 的
  `:tool-gate`（批前串行预判），勿放 tool filter（会并发弹提示）。

### 2.2 `:chat` 链（kernel/invoke-chat(-stream) 组装）

```
ChatRequest  {:messages :tools :tool-choice :system-prompt (:on-token) :context(只读)}
ChatResponse {:response :context}
```

- 可改写 `:messages`（memory 的 delta→全历史替换即此）、`:tools` 等；
- 同一条链同时服务同步与流式路径（invoke-chat / invoke-chat-stream）；
- memory filter 刻意放**循环内**（每轮落库完整 transcript，含工具往返）——
  heal-dangling、暂停恢复、timeline 都依赖完整历史；不跟 Spring AI
  "memory 放循环外"的做法。

### 2.3 `:turn` 链（react/invoke 组装）

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
- **resume 同样经过 turn 链**——语义与机制见 §2.4。

### 2.4 resume 与 turn 链（一次性分派终端）

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
             (run-tool-loop kernel (:messages treq) ...)))  ;; 递归重入：全新循环
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
| `(timeout-filter ms)` | :tool | 超时短路 + future-cancel 中断；超时结果标 `:error {:class :transient}`（声明 `:retry` 的幂等工具可自动重试） |
| `(approval-filter fn?)` | :tool | 敏感工具审批，拒绝短路（交互式场景请改用 gate） |
| `(validation-turn-filter validate-fn :max-retries n)` | :turn | 最终答案校验：不合格把原因作反馈消息重入循环；耗尽原样返回；非 :completed 透传。对标 Spring AI `StructuredOutputValidationAdvisor`，配 provider 原生 json_schema 使用 |
| `(token-redact-filter re replacement)` | :token-xform | 流式出站 token 无状态正则脱敏（跨 chunk 限制见专文） |
| `(hold-release-filter check-fn)` | :token-xform | 先审后放：缓冲整流，完流 check-fn 全文，通过原序放行 / 不通过 emit 单个替换 token |
| `(memory-filter store)`（client） | :chat | 按 conversation-id 读写历史；应放 filters 首位 |

---

## 4. 与 Spring AI 2.0 advisor 的对照（吸收记录）

| Spring AI 2.0 | 我们 | 结论 |
|---|---|---|
| ToolCallingAdvisor（循环进链，位置=粒度） | `:turn` 钩子（循环本体不动，只包一层） | **吸收**——不学大搬家：循环与 gate/HITL/暂停/持久化深度耦合，为优雅重构不值 |
| `chain.copy(this)` 递归 advisor（1.1 experimental） | 闭包链天然仅下游 | **免费拥有**，无需新 API |
| StructuredOutputValidationAdvisor | `validation-turn-filter` | 吸收（~30 行，取回已删 converter 子系统的核心价值） |
| `StreamAdvisor` 返回 `Flux<ChatClientResponse>`（流是一等值，Call/Stream 双接口） | `:token-xform` transducer 变换出站 token 流 | **吸收算子思想**（1→N/有状态/flush），不引 Reactor、不拆双接口——详见 `token-stream-filter-design.md` §5 |
| memory advisor 放循环外 | memory 刻意放循环内 | 不跟（完整 transcript 是我们的契约） |
| QuestionAnswerAdvisor（RAG） | 留 `:turn` 挂点 | 不跟本体（需 vector store，超出定位） |
| advisor context map（跨 advisor 状态） | 请求 map 透传 + 闭包 | 不跟（已够） |
| getOrder 数值排序 | 注册顺序即层序 | 不跟（显式列表更直白） |

---

## 5. 组合示例

```clojure
(kernel/build-kernel
  {:service svc
   :tools   [#'search #'save-note]
   :filters [(ma/memory-filter store)                        ;; :chat，首位
             {:name :rag                                     ;; :turn，每 turn 注入一次
              :turn (fn [req chain]
                      (chain (update req :messages
                                     #(into [(msg/system (retrieve (:messages req)))] %))))}
             (filters/validation-turn-filter must-be-json)   ;; :turn，答案校验重试
             (filters/timeout-filter 5000)                   ;; :tool，单工具超时
             filters/logging-filter]})                       ;; :tool，日志
;; turn 洋葱：rag → validation → [循环: memory → LLM；timeout → logging → 工具]
```

## 相关文档

- `agent-loop-concurrency-design.md` §4.6（tool 链契约收紧的动因与盘点）、
  §14（turn 链的实施记录与 Spring AI 调研）
- `hitl-timeline-design.md`（gate/暂停/resume 与 filter 链的边界分工）
- `token-stream-filter-design.md`（`:token-xform` token 流变换链权威设计）
