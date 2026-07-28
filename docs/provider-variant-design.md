# Provider 变体差异：一个标准 provider 如何承载多套方言

> **状态：✅ 已实施（2026-07-28）。P1（config 排除法）+ P3（thinking 回传契约）
> 全部落地；真机验收 20/20 = 100%，与 A 臂零方差形态一致（§6.2）。**
>
> 起因：MiniMax 走 Anthropic 兼容接口，但有自己的特征——`thinking` 参数语义、
> **thinking 内容块必须原样回传**（[官方 M3 function call 文档][mm-fc]），且
> M2.x 关不掉 thinking。问「如何用一个标准的 provider 同时支持多个差异化的方案」。
>
> **⚠ 本文初稿的核心推论被自己的实验大幅修正了**，两轮实验的账：
> - `minimax_thinking_replay_experiment.clj`（§7.1）：**M2.7 三臂完全无差别**；
>   M3 上剥掉 thinking 回传让模型**少思考**（4.50→3.33 次/链，两组不重叠），
>   但轮数、工具调用数、答案全同——**行为差异确认，质量损害未证明**。
> - `minimax_thinking_quality_experiment.clj`（§7.5.1，为回答上一条而设计的
>   难任务 + **预注册**判据）：n=20/臂，A 20/20、B 16/20，**p = 0.0530 未过线**
>   → 判为**功效不足**（不是「没差别」），按规矩当场维持不立项。
> - **确证实验**（§7.5.3，n=40/臂，主指标与脚本一字未改）：A **40/40**（方差为零）、
>   B **33/40**；**p = 0.0059 < 0.05** → **P3 立项**。正确率翻倍样本后几乎没动
>   （80.0%→82.5%），动的只有功效——印证了上一条的判断。
>
> 全程的方法论骨架：**判据先于数据**。它先挡住了「我早就觉得该做」（p=0.053 那次
> 没有放行，也没有切换到当时已显著的次指标），再在样本足够时**双向**地放行。
> 规则若只在支持自己时才作数，那它一开始就不是规则。
>
> 这正是本仓库那条惯例的价值：这种判断不能靠推导定案（README「Live 验证脚本」一节，
> 以及 `advisor-alignment-design.md` §2.3–2.5 里被真机推翻的三个单测判断）。
>
> 先读 [`design-principles.md`](design-principles.md)（§1 无真实需求不建）。
> 相关：[`response-path-consolidation.md`](response-path-consolidation.md)（core 收回
> 厂商 wire 知识——本文的方案必须不违反它）、
> [`memory-filter-refactor.md`](memory-filter-refactor.md)（中立消息是历史的唯一真相）、
> [`agent-loop-concurrency-design.md`](agent-loop-concurrency-design.md) §12.4
> （`:writes`：只进历史、不发给 LLM 的消息字段——本文 `:blocks` 的先例）。

[mm-fc]: https://platform.minimaxi.com/docs/guides/text-m3-function-call

---

## 0. TL;DR

**MiniMax 的差异不是一类，是三类。把它们合成一个 `MinimaxProvider` 是本题最容易
犯的错**——因为其中一类的变化轴根本不是 provider，是**模型**。

| # | 差异类型 | 例子 | 机制 | 现状 |
|---|---|---|---|---|
| **A** | **端点** | base-url / `/anthropic/v1/messages` / Bearer 鉴权 | config merge | ✅ 已解决，不要动 |
| **B** | **请求参数** | `thinking: {"type":"adaptive"}`；M3 缺省关、M2.x 关不掉 | 参数**透传**（模型特征表暂不建，§7.3） | ✅ 已修（§6.1 排除法），实测开关语义与文档一致 |
| **C** | **会话状态** | thinking 块 + `signature` **必须原样回传**下一轮 | **可选协议 + 中立消息不透明载荷** | 🚧 **已立项待实施**：M2.7 无影响；**M3 正确率 100%→82.5%、逐轮全对 100%→47.5%、思考 8.0→2.75**，确证实验 n=40/臂 p=0.0059（§7.5.3） |

**骨架结论**：不动 `ILLMProvider`。差异化能力放**独立可选协议**，调用点用
`satisfies?` **探测**——有则用、无则退化。能力是发现出来的，不是继承出来的。

**为什么 C 不能用 config 解决**：它要求把 provider 的不透明数据**穿过中立层**再送回去。
config 是单向下行的，穿不回来。

---

## 1. 事实核对（代码，非推测）

### 1.1 A 已经解决得很干净

`provider/minimax.clj` 全文 51 行，只有一张端点表 + 一次 merge：

```clojure
(def ^:private minimax-endpoint
  {:provider-name :minimax :base-url "https://api.minimaxi.com"
   :api-path "/anthropic/v1/messages" :auth-scheme :bearer :anthropic-version nil})
```

`anthropic/create-provider` 接住它，`AnthropicProvider` 的 `provider-name` 从 opts 读。
**这是 A 类差异的正确形状，本文不动它。**问题在于：它是**唯一**的挂载点——
minimax.clj 里没有任何地方能表达行为差异。

### 1.2 B：thinking 参数进不来（`build-kernel` 白名单）

`anthropic/build-params` 其实早就原样透传 `:thinking`（`anthropic.clj:372`）：

```clojure
thinking  (assoc :thinking thinking)
```

但 `common/build-kernel` 只把三个键放进 service config（`common.clj:33-36`）：

```clojure
(cond-> {:model (:model opts "glm-4") :max-tokens (:max-tokens opts 4096)}
  (:temperature opts) (assoc :temperature (:temperature opts)))
```

于是走 `create-agent` 的人**递不到** `:thinking`——只能自建 kernel/service 绕开
agent 门面。能力存在但不可达。

### 1.3 C：thinking 块回不去（真正的问题）

回传链上有两处把块结构抹平：

1. **`advisor/memory.clj:22-33` `response->neutral`**——把归一化响应重建成中立
   assistant 消息时，只用 `:text` 与 `:tool-calls`。`:reasoning` 字段（`model/response.clj:96`
   `extract-reasoning` 抽出来的**字符串**）在这里就没了，块结构与 `signature` 更是
   从未进入中立层。
2. **`wire/anthropic.clj:30-38` `assistant->wire`**——反向也只会造 `text` 与 `tool_use`
   两种块。即使中立层带了信息，也没有出口。

**数据其实早就攒好了**：流式层处理 `signature_delta` 时（`stream/anthropic.clj:180`）
把签名挂到对应块上，注释写的就是「便于多轮 thinking 回传」。攒完，然后在中立层被丢掉。

初稿的**推论**是：MiniMax M2.x（thinking 关不掉）走工具调用多轮时，我们回给模型的
assistant 消息缺少 thinking 块，interleaved thinking 的前提不成立——官方文档的原话
是 `response.content` 是个列表、**必须完整返回**。

**这个推论被实验修正了**：M2.x 上不成立（§7.1，thinking 关不掉，回传里有没有历史
thinking 都一样），但在 **M3 上成立且有质量后果**（§7.5.3，正确率 100%→82.5%）。
「文档要求」与「不照做会怎样」终究是两回事——**支撑立项的是后者，而它花了三轮
实验才拿到**。§1.3 描述的抹平自始至终都在，变的只是我们知道它值多少。

---

## 2. 分类学：为什么必须分三种机制

### 2.1 B 的变化轴是**模型**，不是 provider

这是最容易做错的一点。同一个 MiniMax provider 实例同时服务 M3 与 M2.7，而两者的
thinking 语义**相反**：

| 模型 | 省略 `thinking` | `{:type "adaptive"}` | `{:type "disabled"}` |
|---|---|---|---|
| MiniMax-M3 | 关闭 | 开启 | 保持关闭 |
| MiniMax-M2.x | **仍然开启**（关不掉） | 开启 | **仍然开启** |

所以**不能**把它做进 record、工厂函数或 provider 级 config——那等于宣称「这个
provider 只服务一种模型」。正确形状是一张**按模型名解析的特征表**：

```clojure
;; minimax.clj —— 数据，不是代码
(def model-traits
  [[#"(?i)^MiniMax-M3"  {:thinking-default nil :thinking-forced? false}]
   [#"(?i)^MiniMax-M2"  {:thinking-default nil :thinking-forced? true}]])
```

`:thinking-forced?` **不是**用来发参数的（它发不出去），是用来告诉下游
「这个模型一定会吐 thinking 块，C 类回传契约必须生效」。**它驱动 C，不驱动 A。**

### 2.2 C 是「不透明数据穿过中立层」，与厂商无关

这不是 MiniMax 特例，是**同一个问题的三个方言**：

| 厂商 | 必须原样带回的东西 |
|---|---|
| Anthropic（官方） | thinking 块 + `signature`（thinking + tool_use 同时启用时，缺签名会被拒） |
| MiniMax | 整个 `response.content` 列表（含 thinking 块）；OpenAI 口径下是 `reasoning_details` 或 `<think>` 原文 |
| Gemini | `thought_signature` |

**三家的共性**：有一段数据，**中立层看不懂、也不该看懂，但必须原封不动送回去**。
这正是 [`response-path-consolidation.md`](response-path-consolidation.md) 那条约束的
边界情形——core 收回了厂商 wire 知识，所以 core **不能**认识 thinking 块；但它
需要一个**不解释、只搬运**的口袋。

---

## 3. 方案

### 3.1 骨架：不动 `ILLMProvider`，用可选协议 + `satisfies?` 探测

`ILLMProvider` 是 DIP 的端口。往里加方法 = 所有 provider（含用户自己实现的）当场
破坏性变更。`supports-function-calling?` / `supports-stream?` 这两个布尔位是既成事实，
**但不该再沿着这条路加**——每来一个能力就改一次协议，不可持续。

```clojure
;; core：新增独立协议，老 provider 一行不改、不实现即无此能力
(defprotocol IReplayableResponse
  "响应里存在「必须原样带回下一轮」的不透明块时实现本协议。"
  (replay-blocks [this raw-response]
    "→ {:format <keyword> :data <原样载荷>}，无则 nil")
  (blocks->wire [this blocks]
    "把上面存下的载荷还原成本 provider 的 assistant content；format 不认识则返回 nil"))
```

调用点一律 `(when (satisfies? proto/IReplayableResponse p) ...)`，**不满足就走现有路径**。
这就是「一个标准 provider 同时支持多个差异化方案」的骨架：能力**发现**，而非继承。

### 3.2 中立消息：`:blocks`（与 `:writes` 同性质）

```clojure
{:role :assistant :content "..." :tool-calls [...]
 :blocks {:provider :minimax :format :anthropic-content :data [...]}}
```

先例是 `:writes`（[`agent-loop-concurrency-design.md`](agent-loop-concurrency-design.md) §12.4）：
**只进历史存储，不直接发给 LLM，wire 层构造时天然剥落**。`:blocks` 与它唯一的不同是
wire 层**会**消费它——但只由**产生它的那个 provider** 消费：`:provider` / `:format`
对不上就当没有。core 全程不解释 `:data`。

### 3.3 wire 层：原样吐 + 降级路径

`assistant->wire` 改为两条路：`:blocks` 认得 → 原样吐；否则 → 现有的 text+tool_use 重建。
**降级路径必须留**，且是缺省：存量历史没有 `:blocks`，跨 provider 复用同一份历史时
（换模型重跑、subagent 用别的 provider）`:format` 也对不上。

### 3.4 谁来填 `:blocks`

`advisor/memory.clj` 的 `response->neutral` 是唯一入口，但它在 client 模块、拿不到
provider 实例。两个选项，倾向后者：

| 选项 | 做法 | 问题 |
|---|---|---|
| a | 归一化响应里多带一个 `:replay-blocks` 字段，由 provider 在 `normalize-response` 时填 | `LLMResponse` record 加字段（改 core 的 `make-response`），但**不破坏协议** |
| b | `response->neutral` 拿到 provider 实例后 `satisfies?` 探测再调 `replay-blocks` | 要把 provider 递进 memory-filter，属于新的耦合 |

**倾向 a**：`raw-response` 本来就在响应里（`make-response` 已保留），provider 顺手
抽一次比让 client 认识 provider 更轻。a 的代价是 core 多一个字段——按 §1 判据，
这个字段换来的是「不建就做不到」的能力（见 §5），不是对称性。

---

## 4. 否决记录

| 方案 | 为什么不 |
|---|---|
| **新建 `MinimaxProvider` record 委托 Anthropic** | M2.x/M3 的差异是**模型级**，一个 record 装不下两套相反语义；且每加一个兼容端点就要复制 9 个协议方法的委托 |
| **在 advisor/filter 层做回传** | filter 站在**中立层**，看不见 wire 格式。让它认识 thinking 块 = 把 Anthropic 方言泄进 client 模块，直接违反 [`response-path-consolidation.md`](response-path-consolidation.md)。filter 适合做**策略**（这轮要不要开 thinking），不适合做**格式** |
| **provider 内部维护会话状态** | 破坏无状态 provider：多实例并存与并发调用当场失效（`create-provider` 的 opts-in-record 设计就是为了摆脱全局可变状态） |
| **往 `ILLMProvider` 加方法** | 所有实现方（含仓库外的）破坏性变更，换来的只是少一次 `satisfies?` |
| **中立消息直接存 Anthropic 块（不加 `:format` 标签）** | 历史一旦跨 provider 复用就会把方言喂给不认识它的厂商；`:format` 是降级判据，不是装饰 |

---

## 5. §1 四问判据自审

对 §3 提的两个新面（`IReplayableResponse` 协议 + 中立 `:blocks` 字段），**实验后重判**：

| 问题 | 初稿回答 | 实验后 |
|---|---|---|
| 现在有人要用吗？ | 有：MiniMax M2.x thinking 关不掉 | **是**（确证）。M3 上剥掉回传，正确率 100%→82.5%，p=0.0059（§7.5.3）。M2.7 不受影响，但一个模型受影响就够了 |
| 不建的话用户怎么办？ | 做不到（回传在 wire 内部，用户插不了手） | **做不到**：回传发生在 `wire/*` 与 `advisor/memory` 内部，agent 层连 filter 都不暴露。用户唯一的出路是绕开框架自己拼消息——那等于不用这个框架 |
| 换来的是什么？ | 能力：多轮工具调用不降级 | **正确率 + 行为可复现**：40 次里 7 次答错、21 次过程走歪 → 0；A 臂 40 次零方差。这是能力，不是「更声明式」 |
| 是不是三家共性？ | 是，但共性不构成立项理由 | 同上。Anthropic 官方 thinking+tool_use 需要签名回传是**另一个场景**，本实验没测；等它成为真实需求时再按 §1 重判 |

**结论：P3 立项**（§7.5.3，p=0.0059）。

这一条值得说透的不是结论，是路径：**我在写这份设计时就倾向做 P3**，而它先后被
自己的实验**否掉一次（M2.7 无差别）、卡住一次（p=0.053 未过线）**，第三轮才拿到
够格的证据。中间那次尤其关键——当时次指标已经显著（p=0.004），切过去就能「证明」
我本来的判断，但那是结果切换，不作数。

**规则双向才作数**：它挡住了想立项的我，也在证据足够时放行了同一个我。
如果它只在支持我的时候有效，那从一开始就不是规则，是修辞。

实施时验收**必须钉正确率差异本身**（§6 P3 行），而不是「thinking 块有没有回去」——
后者是手段，前者才是这件事被批准的全部理由。

---

## 6. 落地顺序（每步独立可发布）

| 台阶 | 内容 | 状态 |
|---|---|---|
| **P0** | 验证实验：`examples/minimax_thinking_replay_experiment.clj` | ✅ **已完成**（2026-07-28），结论见 §7 |
| **P1** | 打开 config 白名单：`common/service-config` 改为**排除法**（`orchestration-keys`），provider 专属键（`:thinking` / `:cache-strategy` / `:service-tier` / `:top-k` / `:beta` …）从 `create-agent` 直达 provider | ✅ **已完成**（2026-07-28，+3 tests / +17 assertions；真机 M2.7 冒烟通过） |
| **P2** | `model-traits` 表 + thinking 缺省解析 | ⏸ **不建**：M3 实测确实可开可关（§7.3），表有了真实基础，但用户显式传 `:thinking` 即可（P1 已打通）——框架代填只是「更省事」，按 §1 落在假想列 |
| **P3** | `IReplayableResponse` + 中立 `:blocks` + wire 还原 + 降级路径 | ✅ **已实施**（§6.2）。验收按当初定的方式钉正确率本身：走真实框架 20/20 = 100%，B 臂 82.5% 的行为消失 |

P1 是纯粹的**既有承诺缺口**：`build-params` 早就认 `:thinking`，只是 agent 门面把它
挡在外面。修的是「说了能用却用不了」，不是长新抽象——不触发 §1 的立项判据。

### 6.1 P1 实施记录（2026-07-28）

`common/service-config`（新提取的公开函数）把白名单换成排除法：

```clojure
(def ^:private orchestration-keys                 ;; 只属于编排层，绝不下沉
  #{:provider :tools :tool-vars :kernel :filters :memory :pause-store
    :callbacks :conversation-id :max-iterations :state-slots :tool-manager
    :eligibility-fn :system-prompt :on-pause :on-error :on-env-error
    :cancel-token :tool-choice :id})
```

三个判断值得记下来：

1. **排除法的风险面朝向反了才安全**。白名单漏一个键 = 一个能力静默失效（本次的
   `:thinking`）；排除法漏一个键 = 一个编排层的值被塞给 provider。后者**当场就炸**、
   而且炸在测试里，前者只在用户报「为什么不生效」时才被发现。
2. **`:tools` 是这份名单里最危险的一条**，因为它两边都存在但**含义不同**：service
   config 的 `:tools` 是已编译 schema，agent 的 `:tools` 是 tool var 向量。漏下去
   provider 会转出 `{:name nil}`——MiniMax 报 400「function name is empty」。
   这不是假想：P0 实验第一版就是这么挂的（见 §7.1 教训）。故单测**专门钉了这一条**。
3. **显式 `nil` 不下沉**。`{:temperature nil}` 若原样传下去，provider 侧
   `(some? temperature)` 这类判据会被 nil 骗过，行为与「没传」不同。故 `service-config`
   过滤 nil 值。

验收：`service_config_test.clj` 3 tests / 17 assertions（正向透传 + 反向不泄漏 +
`build-kernel → service → provider` 端到端）；全套 319 tests / 1324 assertions / 0 failures；
真机 M2.7 走 `create-agent {:thinking {:type "adaptive"} :top-k 20}` 冒烟通过。

---

### 6.2 P3 实施记录（2026-07-28）

改了 4 处源码，全部**加法**：

| 层 | 改动 |
|---|---|
| core `model.clj` | 新增可选协议 `IReplayableResponse`（单方法 `replay-blocks`） |
| core `response.clj` | `LLMResponse` 加 `:replay-blocks` 字段 + `response-replay-blocks` |
| core `service.clj` | 归一化时 `satisfies?` 探测，探到才取；core 不解释 `:data` |
| core `message.clj` | 中立消息 `:blocks` 契约 + `with-blocks` / `blocks` |
| client `advisor/memory.clj` | `response->neutral` 把载荷带进历史（§1.3 那个洞） |
| provider `anthropic.clj` | 实现协议：**只在真有 thinking 块时**捕获整段 content |
| provider `wire/anthropic.clj` | 认得的 `:blocks` 原样吐；否则走既有重建 |

**实施中改了三处设计判断**（都朝更小的方向）：

1. **协议只留一个方法**。§3.1 原设计有 `blocks->wire`，实施时发现还原发生在
   **各 provider 自己的 wire 转换器**里（它本就认识自家格式），走协议是绕一圈。
   缺了才加，不为对称性加。
2. **协议绝不能有 `extend-type Object` 兜底**。仓库自己踩过并记在 `model/provider?`
   的注释里：`ILLMProvider` 因为有兜底，`satisfies?` 对任意非 nil 对象恒为 true。
   本协议的整套机制建立在 `satisfies?` 上，加兜底 = 当场失效。**单测专钉这条。**
3. **抽取范围严格限定在有证据的那种情况**（响应真含 thinking 块才捕获）。
   无脑捕获所有响应会让「原样回放」盖过现有重建路径，连带绕过任何改写过历史的
   filter——那是没有证据支持的行为变更。

**§3.4 的选型也被现实推翻了**：那里写「provider 在自己的 `normalize-response` 里填」，
但 agent 实际走的是 **core 的 `service/normalize-response`**（通用代码经协议方法
取 text/tool-calls）。provider 自己那个 `normalize-response` 根本不在链路上。
于是探测点落在 core——**这恰恰是协议存在的理由**：通用代码需要问 provider
「你这个响应里有没有要原样带回的东西」，而它不能认识任何厂商格式。

**验收（按 §6 P3 行当初定死的方式：钉正确率本身）**

- 确定性部分（进 CI）：`replay_blocks_loop_test`（client，载荷穿过
  service→memory→下一轮抵达协议边界）、`replay_blocks_test`（provider，
  中立→wire 逐字还原 + 三条降级路径）、`replay_blocks_test`（core，可选性真的可选）。
  全套 **331 tests / 1369 assertions / 0 failures**（+12 / +45）。
- 真机（`examples/p3_replay_acceptance_live_test.clj`，走 `create-agent` 全链）：
  **20/20 = 100%**，且 20 次全部恰好 7 次工具调用——**与 A 臂零方差的形态一致**，
  B 臂 82.5% / 轮数 3–17 的行为消失。

**降级路径是缺省而非兜底**：存量历史没有 `:blocks`、跨 provider 的历史 `:format`
对不上（换模型重跑、subagent 用了别家）、`:data` 为空——三条都走原来的
text + tool_use 重建，各有单测。

---

## 7. P0 实测结论（2026-07-28，MiniMax-M2.7）

脚本：`examples/minimax_thinking_replay_experiment.clj`（三臂只差 assistant 回传内容：
A 完整回传 / B 剥掉 thinking＝**当前框架行为** / C 保留 thinking 但删 signature）。

### 7.1 降级是否真实存在 → **分模型：M2.7 否，M3 是（软降级）**

强制串行的四轮工具链（城市名藏在代号后，代号只能从上一步工具结果里拿到，模型无法
并行）。**探针 1/2 一律显式 `thinking: adaptive`**——M3 缺省关闭 thinking，不显式开
的话第一轮根本没有 thinking 块可剥，三臂等价，实验空转还会印出一个很像结论的「无差别」。

**MiniMax-M2.7（n=4，thinking 关不掉）→ 无差别**

| 臂 | 报错 | 走完轮次 | 产出 thinking | 重复问同一目标 |
|---|---|---|---|---|
| A 完整回传 | 0/4 | 5 5 5 5 | 5 5 5 5 | 0 |
| **B 剥 thinking（当前行为）** | 0/4 | **5 5 5 5** | **5 5 5 5** | 0 |
| C 删 signature | 0/4 | 5 5 5 5 | 5 5 5 5 | 0 |

**MiniMax-M3（n=6，thinking 可关，显式开）→ 思考频率退化**

| 臂 | 报错 | 走完轮次 | **产出 thinking** | 均值 | 重复 |
|---|---|---|---|---|---|
| A 完整回传 | 0/6 | 5×6 | 5 5 4 5 4 4 | **4.50** | 0 |
| **B 剥 thinking（当前行为）** | 0/6 | 5×6 | **3 3 4 3 3 4** | **3.33** | 0 |
| C 删 signature | 0/6 | 5×6 | 4 4 5 4 5 4 | 4.33 | 0 |

**两组不重叠**（B 的最大值 4 = A 的最小值），且与另一轮独立数据（n=4：A 4.50 / B 2.75）
**同向**。即：**剥掉 thinking 回传，M3 在后续轮次里重新思考的频率掉了约 1/4。**

**但这只是「行为差异」，不是「质量损害」**——两个模型、三条臂、全部 30 次运行的
轮数（5）、工具调用数（4）、重复率（0）、报错数（0）完全一致，末轮答案也都正确
（北京 32°C > 上海 28°C）。**「模型少想了几次」与「答得更差」之间没有本实验能给的桥。**

为什么 M2.7 看不到：它**关不掉** thinking（§7.3），每轮都必然思考，回传里有没有
历史 thinking 都不改变这一点。M3 是 adaptive——它会**根据上下文决定这轮要不要想**，
而历史里少了前几轮的思考，它就更倾向于不想。这条差异只可能在可关的模型上出现。

三处方法学教训，比结论本身更该记住：

1. **第一版任务测不动这件事**。让模型「分别查北京和上海」，它第一轮就把两个
   `get-weather` **并行**发了，整条链只有一次工具轮——而 interleaved thinking 是
   「每次拿到工具结果后再想一次」。弱任务上的「无差别」是假阴性。故改成强制串行链。
2. **n=2 时 B 臂出现过一次 4 轮（vs A 的 5 轮）**，判读器据此报了「软降级」；
   n=4 复核后三臂 12/12 全 5 轮——那一次是**采样波动**。差分实验必须重复，
   这就是脚本里 `EXPERIMENT_REPEAT` 存在的理由。
3. **判读器自己漏掉了唯一的真信号**。它把「产出 thinking」判成布尔（是否为零），
   而 M3 的差异全在**次数**上（4.50 vs 3.33，从不为零）——于是 M3 第一次跑完，
   判读器印的是「三臂无差别」。**先想清楚差异会长在哪个量上，再写判据**；
   写成布尔的判据，看不见连续量上的退化。同轮还修了另一处：判读按模型分组，
   两个模型的数据混在一起统计等于没统计（M2.7 关不掉 thinking，M3 可关，
   本就不该合并）。

### 7.2 MiniMax 是否校验 signature → **否**

C 臂（thinking 块保留、`signature` 字段删掉）4/4 通过，与 A 臂无差别。
（Anthropic 官方在 thinking + tool_use 同开时是否仍拒绝缺签名回传，**未测**——
不同端点不同厂商，不能拿本结论外推。）

### 7.3 thinking 开关语义 → **与官方文档完全一致**

| 模型 | 省略 `thinking` | `{:type "disabled"}` | `{:type "adaptive"}` |
|---|---|---|---|
| MiniMax-M2.7 | thinking 块 **1** | **1**（关不掉） | 1 |
| MiniMax-M3 | **0** | **0** | **1** |

M2.7 关不掉、M3 缺省关闭且能开能关，与文档一字不差。

对 P2（模型特征表）的影响：**它现在有真实基础了**——两个模型对同一个参数的响应
确实不同，不再是「传什么都一样」。但**用户显式传 `:thinking` 就能得到想要的行为**
（P1 已经把这条路打通），框架替他填一个缺省值换来的只是「更省事」，不是「不建就
做不到」——按 §1 判据仍落在假想列。**保持不建。**

> 顺带验证了另一件事：三次响应的 `model` 字段都如实回 `MiniMax-M2.7`。MiniMax 对
> 未知模型名会**静默回退不报错**，所以脚本每次都比对请求模型与响应模型——
> 不比对就可能拿另一个模型的结果当结论。

### 7.4 流式与非流式的块形状 → **同构**

两条路的块类型集合都是 `#{"text" "thinking"}`，thinking 块字段都是
`#{:type :thinking :signature}`。`stream/anthropic.clj` 那套累积器是对的。
（P3 不立项，这条暂时用不上，但它同时说明：真要做时这一层不必返工。）

### 7.5 质量对照实验（P3 立项与否的**唯一**分界）

M3 的思考频率退化已经确认且可复现，缺的是**它到底伤不伤结果**。
脚本：`examples/minimax_thinking_quality_experiment.clj`。

**判据先定死，免得看到数据再找理由**（这段写在跑之前，一个字没改过）：

| 观测 | 结论 |
|---|---|
| A 正确率 = 0 | ⚠ **地板效应，结论作废**——任务太难，测的是算术能力不是回传策略 |
| A、B 均 = 100% | ⚠ **天花板效应，结论作废**——任务太简单，判别不出来 |
| B 显著低于 A（单侧 Fisher `p < 0.05`） | **P3 立项**，验收钉正确率差异 |
| 其余 | **P3 永久不立项**——没有后果的行为差异，不值一个协议 + 一个中立消息字段 |

**任务设计的三条硬要求**（前一个实验测不出质量差异，原因就在任务太软）：

1. **答案唯一可自动判定**——一个具体数字，脚本用同一套规则独立算出真值比对；
2. **必须逐轮推理**——下一个要查的槽位由**本轮刚拿到的值**现算
   （各位数字之和 × 3 + 当前编号 mod 5），省掉中间步骤就只能靠猜；
3. **错误沉默复合**——工具对**任何**编号都照常返回值，从不提示「你查错了」。
   算错一步 → 后面全歪，过程里没有任何告警。真实任务里也没人会提醒模型想岔了。

于是**每一轮都可判定对错**（查的编号是否等于应查的），不只有最后一个数字。

**难度校准（跑之前做，不是看到结果再调）**：链长 5 步时 A/B 都答对——天花板守卫
当场触发，判别不出来，故正式实验用 7 步。**校准的是任务，判据一个字没动。**
两者的区别是这类实验诚信与否的分界：调难度是让实验有分辨力，调判据是给结论找理由。

#### 7.5.1 结果（2026-07-28，MiniMax-M3，20 次/臂，7 步链，真值 458）

| 臂 | 最终答案对 | **逐轮全对** | thinking 均值 | 轮数 |
|---|---|---|---|---|
| A 完整回传 | **20/20** | **20/20** | 8.0 | 8 8 8 …（20 次全是 8） |
| **B 剥 thinking（当前框架行为）** | **16/20**（80%） | **13/20**（65%） | 2.5 | 8–17（波动） |

- **主指标**（最终答案，**预注册**）：单侧 Fisher **p = 0.0530 ≥ 0.05** →
  **按判据：不显著，P3 不立项。**
- 次指标（逐轮过程全对，脚本里一并记录）：**p = 0.00416**。

**为什么不能拿次指标定案**：判据预注册的主指标就是最终答案。看到主指标差 0.003
就换到次指标上宣布显著，正是预注册要防的**结果切换（outcome switching）**——
一次都不能开这个口子，否则以后每个实验都能挑出一个显著的指标来。

**但主指标确实选偏了，这是设计时的失误（记账，不改本轮结论）**：最终答案是**更噪**
的度量——B 有三次（#1 / #16 / #20）**链只对了 2/7 步却仍然答出 458**，靠中途纠错或
凑巧回到正轨。「过程对不对」才是被 thinking 直接影响的量，「最后蒙没蒙对」隔了一层。
当初把最终答案定为主指标，是因为它更贴近用户关心的东西（答得对不对），
这个理由本身没错——错在没预料到它的噪声会淹掉效应。

**A 臂的确定性值得单独一提**：20 次全部 8 轮、7/7 步、答案 458，**方差为零**。
B 臂轮数在 8–17 之间跳。这个对比比 p 值更直观：**完整回传下模型的行为是可复现的，
剥掉之后不是。**

**结论与后续**：按预注册判据，本轮**不足以立项 P3**。p 贴线（0.053）且主指标偏噪，
意味着这是一次**功效不足**的实验，而不是「证明了没差别」。标准补救是**一次性**加大
样本复核——若做，须**跑前**声明：主指标与阈值**一律不变**（仍是最终答案、p<0.05），
n=40/臂，**结果为准，不再延长**（否则「跑到显著为止」就是 p-hacking）。
在此之前，P3 维持**不立项**。

#### 7.5.2 确证实验（n=40/臂）——跑前声明

用户拍板执行 §7.5.1 说的那次复核。**跑之前**把话说死，四条一条不能改：

1. **主指标不变**：最终答案正确率，单侧 Fisher，阈值 `p < 0.05`。
2. **样本量 n = 40/臂**，一次性；**结果为准，不再延长**——「不显著就再加 20 次」
   就是跑到显著为止，那比不做实验更坏。
3. **不与 §7.5.1 的 20 次合并**。合并从未预先声明过，事后再合是另一种事后灵活性
   （而且两轮之间还改过难度校准的上下文）。这一轮**独立成立**。
4. **任务、链长（7）、模型（M3）、脚本一字不改**，只动 `EXPERIMENT_N`。

地板/天花板作废守卫照旧生效。次指标（逐轮全对）继续记录，但**仍不参与定案**。

#### 7.5.3 确证结果（2026-07-28，M3，40 次/臂）→ **P3 立项**

| 臂 | 最终答案对 | 逐轮全对 | thinking 均值 | 轮数 |
|---|---|---|---|---|
| A 完整回传 | **40/40（100%）** | **40/40** | 8.0 | 全部 8（**方差为零**） |
| **B 剥 thinking（当前框架行为）** | **33/40（82.5%）** | **19/40（47.5%）** | 2.75 | 3–17 |

**主指标：单侧 Fisher `p = 0.0059 < 0.05` → 按预注册判据，P3 立项。**

样本翻倍前后**正确率几乎没动**（80.0% → 82.5%），动的只有功效——这正是
§7.5.1 判断「功效不足，不是没差别」的**验证**。n=20 那次差 0.003 未过线，
不是效应不存在，是样本不够。

**这不是 p-hacking，判据本身能证明**：主指标、阈值、任务、链长、模型、脚本全部
一字未改，只动了 `EXPERIMENT_N`；n 事前定死为 40 且声明「结果为准不再延长」；
两轮数据不合并。**如果这轮不显著，本条就该定案不立项**——规则是双向的，
这是它能作数的唯一理由。

次指标（未参与定案，但方向一致且幅度更大）：逐轮过程全对 **40/40 vs 19/40**。
B 臂有 21 次链条走歪，其中 7 次歪到最终答案也错。典型失败形态：`B#18` 只跑了
3 轮、链对 1/7 就交卷（提前收工），`B#23` 跑满 17 轮却越滚越偏（答案 40 vs 458）。

**A 臂 40 次零方差**——全部 8 轮、7/7 步、答案 458。这比 p 值更能说明问题：
**完整回传下模型行为可复现，剥掉之后不可复现。**

### 7.6 仍未测的

- **Anthropic 官方端点** thinking + tool_use 的签名校验（见 7.2）。MiniMax 宽松
  不代表官方宽松，**不能外推**。
- **超长对话**：本实验最长 5 轮。若影响随上下文累积，短链看不出来。
- **M2.1 / M2.5 等其余 M2.x**：只测了 M2.7。同代模型行为通常一致，但没测就是没测。

---

## 参考

- MiniMax M3 Function Call（thinking 与工具调用的交互、`response.content` 必须完整返回）：<https://platform.minimaxi.com/docs/guides/text-m3-function-call>
- [`design-principles.md`](design-principles.md) §1 无真实需求不建（§5 自审的判据出处）
- [`response-path-consolidation.md`](response-path-consolidation.md) core 收回厂商 wire 知识（§2.2 的边界约束）
- [`memory-filter-refactor.md`](memory-filter-refactor.md) 中立消息 = 对话历史唯一真相
- [`agent-loop-concurrency-design.md`](agent-loop-concurrency-design.md) §12.4 `:writes` 只进历史（`:blocks` 的先例）
- [`filter-chain-design.md`](filter-chain-design.md) filter 三链的粒度与契约（§4 否决「filter 层做格式」的依据）
