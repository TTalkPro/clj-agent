# Provider 变体差异：一个标准 provider 如何承载多套方言

> **状态：🚧 进行中（2026-07-28 分析定稿；同日 P0 实验完成，结论改写了本文——
> P3 对 MiniMax 不立项，只剩 P1）。**
>
> 起因：MiniMax 走 Anthropic 兼容接口，但有自己的特征——`thinking` 参数语义、
> **thinking 内容块必须原样回传**（[官方 M3 function call 文档][mm-fc]），且
> M2.x 关不掉 thinking。问「如何用一个标准的 provider 同时支持多个差异化的方案」。
>
> **⚠ 本文初稿的核心推论被自己的实验推翻了**：初稿据代码推定「MiniMax 多轮工具调用
> 现在是降级运行」，`examples/minimax_thinking_replay_experiment.clj` 实测
> **三臂 12/12 无差别**——剥掉 thinking 块（＝当前框架行为）与官方要求的完整回传，
> 在机制上分不出来。**故 P3（回传契约）按 §1 判据不立项**，§3 的方案只作为
> 「真需求出现时的形状预案」留档。留下的实锤见 §7。
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
| **B** | **请求参数** | `thinking: {"type":"adaptive"}`；M3 缺省关、M2.x 关不掉 | **模型特征表**（数据，非代码） | ❌ 参数进不来（§1.2） |
| **C** | **会话状态** | thinking 块 + `signature` **必须原样回传**下一轮 | **可选协议 + 中立消息不透明载荷** | ⚠️ 块确实被丢弃（§1.3），但实测**不影响 MiniMax**（§7）→ **不立项** |

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

**这个推论被实验否掉了**（§7.1）。「文档要求」与「不照做会怎样」是两回事，
而只有后者能支撑立项。事实层面 §1.3 描述的抹平**确实存在**，变的是它的后果。

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
| 现在有人要用吗？ | 有：MiniMax M2.x thinking 关不掉 | **否**。thinking 确实关不掉（§7.3），但不回传**没有可观测后果**（§7.1）。「文档要求」不是「有人要用」 |
| 不建的话用户怎么办？ | 做不到（回传在 wire 内部，用户插不了手） | 不变——但这一问只在第一问为真时才有意义 |
| 换来的是什么？ | 能力：多轮工具调用不降级 | **什么也没换来**（在 MiniMax 上）。等于为一个不存在的症状加一个协议 + 一个中立消息字段 |
| 是不是三家共性？ | 是，但共性不构成立项理由 | 同上。Anthropic 官方 thinking+tool_use 需要签名回传是**另一个场景**，本实验没测；等它成为真实需求时再按 §1 重判 |

**结论：P3 不立项。**§3 的方案不删——它是形状预案，将来真需求出现时不必重新推导一遍
（这正是本文存在的价值）。但**现在动手就是违反 §1**。

---

## 6. 落地顺序（每步独立可发布）

| 台阶 | 内容 | 状态 |
|---|---|---|
| **P0** | 验证实验：`examples/minimax_thinking_replay_experiment.clj` | ✅ **已完成**（2026-07-28），结论见 §7 |
| **P1** | 打开 config 白名单：`build-kernel` 放行 provider 专属键（`:thinking` 等），或改为已知键排除法 | 🚧 **保留**（唯一被实验支持的台阶） |
| **P2** | `model-traits` 表 + thinking 缺省解析 | ⏸ **降级为可选**：M2.x 的 thinking 本来就关不掉、M3 缺省关闭，表里能写的只有「传什么都一样」——按 §1，一张不改变任何行为的表不建 |
| **P3** | `IReplayableResponse` + 中立 `:blocks` + wire 还原 | 🗑 **不立项**（§5 重判） |

P1 是纯粹的**既有承诺缺口**：`build-params` 早就认 `:thinking`，只是 agent 门面把它
挡在外面。修的是「说了能用却用不了」，不是长新抽象——不触发 §1 的立项判据。

---

## 7. P0 实测结论（2026-07-28，MiniMax-M2.7）

脚本：`examples/minimax_thinking_replay_experiment.clj`（三臂只差 assistant 回传内容：
A 完整回传 / B 剥掉 thinking＝**当前框架行为** / C 保留 thinking 但删 signature）。

### 7.1 降级是否真实存在 → **否**（核心结论）

强制串行的四轮工具链（城市名藏在代号后，代号只能从上一步工具结果里拿到，模型无法
并行），每臂重复 4 次：

| 臂 | 报错 | 走完轮次 | 产出 thinking | 重复问同一目标 |
|---|---|---|---|---|
| A 完整回传 | 0/4 | 5 5 5 5 | 5 5 5 5 | 0 |
| **B 剥 thinking（当前行为）** | 0/4 | **5 5 5 5** | 5 5 5 5 | 0 |
| C 删 signature | 0/4 | 5 5 5 5 | 5 5 5 5 | 0 |

**12/12 完全一致**，三臂都正确走完 decode→weather→decode→weather→作答。

两处方法学教训，比结论本身更该记住：

1. **第一版任务测不动这件事**。让模型「分别查北京和上海」，它第一轮就把两个
   `get-weather` **并行**发了，整条链只有一次工具轮——而 interleaved thinking 是
   「每次拿到工具结果后再想一次」。弱任务上的「无差别」是假阴性。故改成强制串行链。
2. **n=2 时 B 臂出现过一次 4 轮（vs A 的 5 轮）**，判读器据此报了「软降级」；
   n=4 复核后三臂 12/12 全 5 轮——那一次是**采样波动**。差分实验必须重复，
   这就是脚本里 `EXPERIMENT_REPEAT` 存在的理由。

### 7.2 MiniMax 是否校验 signature → **否**

C 臂（thinking 块保留、`signature` 字段删掉）4/4 通过，与 A 臂无差别。
（Anthropic 官方在 thinking + tool_use 同开时是否仍拒绝缺签名回传，**未测**——
不同端点不同厂商，不能拿本结论外推。）

### 7.3 M2.x 的 thinking 关得掉吗 → **关不掉，与文档一致**

三种传法（省略 / `{:type "disabled"}` / `{:type "adaptive"}`）响应里都有 1 个
thinking 块，块类型均为 `["thinking" "text"]`。**发与不发没有可观测差异。**
这也是 P2 降级为可选的直接原因：特征表里能写的只有「传什么都一样」。

> 顺带验证了另一件事：三次响应的 `model` 字段都如实回 `MiniMax-M2.7`。MiniMax 对
> 未知模型名会**静默回退不报错**，所以脚本每次都比对请求模型与响应模型——
> 不比对就可能拿另一个模型的结果当结论。

### 7.4 流式与非流式的块形状 → **同构**

两条路的块类型集合都是 `#{"text" "thinking"}`，thinking 块字段都是
`#{:type :thinking :signature}`。`stream/anthropic.clj` 那套累积器是对的。
（P3 不立项，这条暂时用不上，但它同时说明：真要做时这一层不必返工。）

### 7.5 仍未测的

- **Anthropic 官方端点** thinking + tool_use 的签名校验（见 7.2）。
- **MiniMax-M3**：本轮只跑了 M2.7。M3 缺省关闭 thinking，风险面更小，但没测就是没测
  （脚本支持 `MINIMAX_MODELS="MiniMax-M2.7,MiniMax-M3"`）。
- **超长对话下的行为**：本实验最长 5 轮。若 thinking 缺失的影响随上下文长度累积，
  短链看不出来——这是本结论最可能的失效边界。

---

## 参考

- MiniMax M3 Function Call（thinking 与工具调用的交互、`response.content` 必须完整返回）：<https://platform.minimaxi.com/docs/guides/text-m3-function-call>
- [`design-principles.md`](design-principles.md) §1 无真实需求不建（§5 自审的判据出处）
- [`response-path-consolidation.md`](response-path-consolidation.md) core 收回厂商 wire 知识（§2.2 的边界约束）
- [`memory-filter-refactor.md`](memory-filter-refactor.md) 中立消息 = 对话历史唯一真相
- [`agent-loop-concurrency-design.md`](agent-loop-concurrency-design.md) §12.4 `:writes` 只进历史（`:blocks` 的先例）
- [`filter-chain-design.md`](filter-chain-design.md) filter 三链的粒度与契约（§4 否决「filter 层做格式」的依据）
