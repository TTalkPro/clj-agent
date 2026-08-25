# Token 流变换链设计（`:token-xform`）

> **状态：✅ 已实施（2026-07-14，全套 253 tests / 1039 assertions / 0）。**
> 本文是 filter 体系第五钩子 `:token-xform` 的权威参考。四链体系
> （:tool/:chat/:turn）见 `filter-chain-design.md`；本文只讲 token 粒度。
> 动机来自与 Spring AI `StreamAdvisor` 的对照（§5）。
>
> 实现：`core/filter.clj`（契约 + 内置 filter）、`core/kernel.clj`
> （invoke-chat-stream terminal 组装点）。

---

## 0. 动机：流式路径缺"逐 token 变换"

现状（见 filter-chain-design.md §2.2）：`invoke-chat-stream` 与同步路径
共用同一条 `:chat` 洋葱；`:on-token` 作为 request 普通字段透传进 terminal。
这给了三档能力：

1. **请求侧改写**——完全支持（与同步零差异）；
2. **对最终响应 around/后置**——支持（stream-fn 流末返回完整归一化响应）；
3. **逐 token 介入**——只能靠 chat filter 包 `:on-token` 做**副作用观测**。

第 3 档的缺口：`on-token` 是推入的回调（push/sink），不是可返回可变换的值。
逐 token 的**变换**（改写/吞掉/缓冲后批量放行）只能在包装回调里手写状态机，
框架不提供组合形式。对照 Spring AI：它的流是一等 `Flux<ChatClientResponse>`，
advisor 用 Reactor 算子对流做 `map/filter/buffer`，逐 chunk 变换是原生能力。

补齐这档能力需要满足三条（缺一不可，曾考虑的"on-token 返回值契约"因缺
后两条被否）：

- **1→N**：一个 token 进、0/1/N 个 token 出（缓冲-批量放行的形状）；
- **跨 chunk 状态**：敏感词被切在两片之间、按句重组，需要状态机；
- **流末 flush**：流结束时缓冲区残留必须有机会冲出——需要 end-of-stream 信号。

## 1. 结论：第五钩子 `:token-xform`，用 transducer 承接

Clojure 的 transducer 恰好原生满足全部三条：

| 需求 | transducer 对应物 |
|---|---|
| 1→N | step 二元 arity 可调下游 0/1/N 次（`mapcat`/`partition-all` 现成） |
| 跨 chunk 状态 | 有状态 transducer（`volatile!` 闭包） |
| 流末 flush | **completion 一元 arity 就是 end-of-stream 信号** |

这就是"不引 Reactor 拿到 Reactor 算子"的答案——吸收 Spring 的算子思想，
不搬它的响应式栈与 Call/Stream 双接口（一条 `:chat` 链服务同步与流式
是我们的既有优势，保持不动）。

## 2. 契约

filter map 增加可选键 `:token-xform`，与 `:tool/:chat/:iteration/:turn` 并存：

```clojure
{:name :redact
 :token-xform (map #(update % :token redact-secrets))}   ;; 无状态改写

{:name :sentence-guard
 :token-xform (my-stateful-xform)}                           ;; 有状态：缓冲/重组
```

- **值是一个 transducer**，作用于 token-data map 流。token-data 形状即
  `on-token` 现行契约：`{:token "..."}` / `{:reasoning-token "..."}`（可带
  `:index` 等），filter 应对不认识的键**透传**（`(if (:token %) (update ...) %)`）；
- **顺序 = `:filters` 注册顺序**：`(apply comp xforms)` 下靠前者最先见原始
  token——与三链"靠前者最先见 req"语义一致，不引入 order；
- **状态作用域 = 单次 LLM 流**：`(xform rf)` 在 terminal 每次调用现场实例化，
  工具循环每轮 LLM 调用各自新状态。存在 filter map 里的 transducer 值本身
  无状态，可安全复用（这是 transducer 的标准性质）；
- **同步路径（invoke-chat）完全忽略 `:token-xform`**；`:tool`/`:iteration`/`:turn` 链与
  token 流无关。

## 3. 组装点与数据流

组装在 `kernel/invoke-chat-stream` 的 **terminal 内**（chat 洋葱最内层）：

```
provider 原始 token → :token-xform 链（注册顺序） → 最终 on-token（sink）
```

要点：

- **chat filter 之后**：chat filter 若包/换了 `:on-token`，xform 链包裹的是
  链上存活下来的那个——语义自洽：**xform 链定义出站流，on-token 包装者观测
  交付物**（看到的是变换后的 token）。要观测原始 token，用带副作用的
  `:token-xform`（`(map #(do (log %) %))`）；
- **flush 只在正常完流**：`stream-fn` 正常返回后调一次 completion arity，
  缓冲残留冲给 sink；`stream-fn` 抛异常则不调（缓冲丢弃，半截答案不外泄）；
- **reduced? 早停**：下游 step 返回 `reduced`（如 `(take n)`）后不再喂
  token，但 completion 照常调一次（对齐 `transduce` 语义）；
- **无 `:token-xform` 时零开销退化**：`on-token` 原样直通，路径与现状 bit 级一致。

### 3.1 硬边界：token 链不改"答案"，只改"交付"

`:token-xform` 变换的是**送给 on-token 的出站流**；`stream-fn` 返回的最终
归一化响应**不经过它**——memory 落库、turn 结果、后续工具循环用的都是
原始完整答案。分工：

| 要改什么 | 用哪条链 |
|---|---|
| 用户实时看到什么（脱敏/吞半截/缓冲放行） | `:token-xform` |
| 这个 turn 的最终答案是什么（校验重试/改写） | `:turn`（validation-turn-filter） |

两者可组合：hold-release 管"没审完不外泄"，validation-turn-filter 管
"不合格重问"——同一 guardrail 的交付面与内容面。

## 4. 内置 filter

| filter | 说明 |
|---|---|
| `(token-redact-filter re replacement)` | 无状态逐 token 正则脱敏。**已知限制**：秘密被切在两个 chunk 之间时漏检（跨 chunk 检测需有状态缓冲，用户按需自写或用 hold-release） |
| `(hold-release-filter check-fn)` | 先审后放：缓冲整流不外泄；完流时 `check-fn` 收全文（`:token` 拼接），返回 nil=通过（缓冲原样放行）\| 字符串=不通过（只 emit 一个 `{:token 替换文本}`）。**代价即语义**：用户在流结束前看不到任何 token——"完整答案没成形就无法审"这一根本矛盾任何机制都消不掉（Spring 的 Flux buffer 同样毁流式 UX），本 filter 只是把缓冲逻辑标准化 |

## 5. 与 Spring AI StreamAdvisor 的对照（吸收记录）

| Spring AI | 我们 | 结论 |
|---|---|---|
| `StreamAdvisor.adviseStream` 返回 `Flux<ChatClientResponse>`，流是一等值 | `:token-xform` transducer 变换出站 token 流 | **吸收算子思想**（1→N/有状态/flush），不搬 Reactor |
| `CallAdvisor` / `StreamAdvisor` 双接口（`BaseAdvisor` 统一 before/after） | 同一条 `:chat` 链服务同步与流式；token 粒度独立成 `:token-xform` | 不跟双接口——粒度用钩子声明，不用接口拆分 |
| `MessageAggregator` 聚合 Flux 供 memory（**只读**） | `stream-fn` 流末返回完整归一化响应，memory 天然拿到全文 | 同构解法，我们免费 |
| guardrail-on-stream：`collectList` 缓冲再放 | `hold-release-filter` | **语义对称**——缓冲毁流式两边一样，Spring 只是表达顺手；我们标准化成内置 filter 后表达同样顺手 |
| 逐 chunk 有状态变换（跨 chunk 敏感词等） | 有状态 transducer | 两边都要手写状态机，同量级 |

## 6. 测试锚点

1. **1→N 与 flush**：`(partition-all 3)` 作 xform，7 个 token 进 → 正常完流
   sink 收到 3 批（2+1 的尾巴由 completion 冲出）；
2. **异常不 flush**：stream-fn 抛异常 → sink 只收到异常前已放行的，缓冲丢弃；
3. **组合顺序**：两个改写 xform 按注册顺序作用（先注册先见原始 token）；
4. **hold-release 两分支**：通过 → 全部缓冲在完流时按原序放行；不通过 →
   只收到一个替换 token；
5. **退化路径**：无 `:token-xform` 的 kernel，sink 收到原始 token（行为与现状
   一致）；同步 invoke-chat 带 `:token-xform` filter 不受影响；
6. **reasoning-token 透传**：`{:reasoning-token ...}` 不被 `:token` 类
   filter 误伤；
7. **最终响应不被变换**（§3.1）：hold-release 吞掉全部 token，返回的
   `:response` 仍是完整原文。

## 相关文档

- `filter-chain-design.md`——三链体系权威参考（本文是其 token 粒度扩展）
- `agent-loop-concurrency-design.md` §14——turn 链与 Spring AI 调研
