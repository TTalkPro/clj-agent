# Provider 变体差异：一个标准 provider 如何承载多套方言

> **状态：✅ 已实施（2026-07-28）——落地的只有 P1（config 排除法）。
> P3（回传契约）**证据不足以立项**，判据与下一个实验见 §7.5。**
>
> 起因：MiniMax 走 Anthropic 兼容接口，但有自己的特征——`thinking` 参数语义、
> **thinking 内容块必须原样回传**（[官方 M3 function call 文档][mm-fc]），且
> M2.x 关不掉 thinking。问「如何用一个标准的 provider 同时支持多个差异化的方案」。
>
> **⚠ 本文初稿的核心推论被自己的实验大幅修正了**：初稿据代码推定「MiniMax 多轮
> 工具调用现在是降级运行」。`examples/minimax_thinking_replay_experiment.clj` 实测
> （§7.1）：**M2.7 三臂完全无差别**；**M3 上剥掉 thinking 回传确实让模型少思考
> （4.50 → 3.33 次/链，两组不重叠、两轮独立数据同向），但任务照样正确走完**——
> 轮数、工具调用数、答案全同。
> 即：**行为差异确认，质量损害未证明**。按 §1「不建的话用户怎么办」，
> 现在还答不上「用户受了什么损失」，**故 P3 暂不立项**；§7.5 定死了下一个实验
> 与立项判据。§3 的方案作为形状预案留档。
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
| **C** | **会话状态** | thinking 块 + `signature` **必须原样回传**下一轮 | **可选协议 + 中立消息不透明载荷** | ⚠️ 块确实被丢弃（§1.3）。实测：M2.7 无影响、**M3 思考频率降 26%** 但任务结果不变（§7.1）→ **证据不足，暂不立项**（§7.5 定了判据） |

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
| 现在有人要用吗？ | 有：MiniMax M2.x thinking 关不掉 | **半个**。M2.7 完全无影响；M3 有**可复现的行为差异**（思考频率 4.50→3.33，§7.1）——差异是真的，但它是不是「需求」取决于下一问 |
| 不建的话用户怎么办？ | 做不到（回传在 wire 内部，用户插不了手） | 「做不到」成立（用户确实插不了手），但**现在答不上「用户损失了什么」**：30 次运行里轮数、工具调用数、答案全同 |
| 换来的是什么？ | 能力：多轮工具调用不降级 | **目前只能说「模型多想几次」**。这不是能力，除非能证明它换来正确率——§7.5 的实验就是为了回答这个 |
| 是不是三家共性？ | 是，但共性不构成立项理由 | 同上。Anthropic 官方 thinking+tool_use 需要签名回传是**另一个场景**，本实验没测；等它成为真实需求时再按 §1 重判 |

**结论：P3 暂不立项**——不是「否决」，是**证据不足**。§1 的判据要的是「不建就做不到
的能力」，而现在手里只有「一个没有已知后果的行为差异」。为一个后果未知的差异去动
中立消息契约 + 加一个协议，正是 §1.1 说的**成本不对称**：建的代价永久，不建的代价
目前为零。

§7.5 定死了下一个实验和判据（正确率显著低 → 立项；无差别 → 永久不立项），
**先做实验再谈动手**。§3 的方案作为形状预案留档。

---

## 6. 落地顺序（每步独立可发布）

| 台阶 | 内容 | 状态 |
|---|---|---|
| **P0** | 验证实验：`examples/minimax_thinking_replay_experiment.clj` | ✅ **已完成**（2026-07-28），结论见 §7 |
| **P1** | 打开 config 白名单：`common/service-config` 改为**排除法**（`orchestration-keys`），provider 专属键（`:thinking` / `:cache-strategy` / `:service-tier` / `:top-k` / `:beta` …）从 `create-agent` 直达 provider | ✅ **已完成**（2026-07-28，+3 tests / +17 assertions；真机 M2.7 冒烟通过） |
| **P2** | `model-traits` 表 + thinking 缺省解析 | ⏸ **不建**：M3 实测确实可开可关（§7.3），表有了真实基础，但用户显式传 `:thinking` 即可（P1 已打通）——框架代填只是「更省事」，按 §1 落在假想列 |
| **P3** | `IReplayableResponse` + 中立 `:blocks` + wire 还原 | ⏸ **证据不足，暂不立项**（§5 重判）。M3 上已确认行为差异，但质量影响未证明——先做 §7.5 的实验 |

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

### 7.5 下一个实验（P3 立项与否的**唯一**分界）

M3 的思考频率退化已经确认且可复现，缺的是**它到底伤不伤结果**。要回答这个，
需要一个与现在完全不同的任务：**答案唯一、可自动判定对错、且难到必须靠逐轮思考**
（多步约束满足 / 多步算术，而不是「查两个城市再比大小」——后者太简单，
少想几次照样答对，这正是现在测不出质量差异的原因）。

判据先定死，免得看到数据再找理由：**A/B 两臂各跑 ≥20 次，B 的正确率显著低于 A
→ P3 立项**；**正确率无差别 → P3 永久不立项**（「模型少想了几次」本身不是损害，
框架不为一个没有后果的差异长协议和中立消息字段）。

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
