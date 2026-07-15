# Spring AI 2.0 Advisor 全面对齐（设计与吸收记录）

> **状态：✅ 已实施（2026-07-15，全套 292 tests / 1194 assertions / 0）。**
> 基线 253/1039 → 292/1194（+39 tests / +155 assertions），零回归。
>
> 本文是**逐个 advisor 的对齐记录**：Spring 有什么、我们对应什么、吸收还是
> 不跟、为什么。机制本体（洋葱、三链契约、递归重入）见
> `filter-chain-design.md`——本文不重复，只记差异与决策。
>
> 实现：`core/advisor.clj`（safeguard / logging-chat / re-reading / validation）、
> `core/advisor/tool_search.clj`、`core/advisor/structured_output.clj`、
> `core/advisor/rag.clj`、`core/kernel.clj`（return-direct / eligibility-fn）、
> `client/react.clj`（循环侧）。

---

## 0. 结论速览

Spring AI 2.0 GA（2026-06）把工具循环提进 advisor 链，成了一等可组合构件。
逐个对完之后，**真正的缺口只有一个半**：ToolSearch（全缺）与 ToolCalling 的
return-direct/eligibility（半缺）。其余要么早已等价拥有，要么是刻意不跟。

| Spring AI 2.0 | 我们 | 结论 |
|---|---|---|
| `ToolCallingAdvisor`（循环进链） | `:turn` 钩子包循环 + `:return-direct` + `:eligibility-fn` | **吸收能力，不学搬家**（§1） |
| `ToolCallingManager`（批执行抽象） | `react/execute-batch` + `:serial` 声明 + `:tool` filter 链 | **拆散，不长这个抽象**（§1.3） |
| `ToolSearchToolCallingAdvisor` | `advisor/tool-search`（`IToolIndex` + `search_tools` + `:chat` filter） | **新建**（§2）——本次唯一的架构级缺口 |
| `StructuredOutputValidationAdvisor` | `validation-turn-filter` + `advisor/structured-output` | 机制早有，**补判据**（§3） |
| `MessageChatMemoryAdvisor` | `advisor/memory` memory-filter | 等价，**放置刻意不同**（§4） |
| `SafeGuardAdvisor` | `safeguard-turn-filter` | **推翻旧决定**（§5） |
| `SimpleLoggerAdvisor` | `logging-chat-filter` | 补齐（§6） |
| `ReReadingAdvisor` (RE2) | `re-reading-filter` | 补齐（§6） |
| `QuestionAnswerAdvisor` | `advisor/rag` `qa-turn-filter`（`IRetriever`） | **推翻旧决定**（§7） |
| `RetrievalAugmentationAdvisor`（模块化 RAG） | — | 不跟（§7） |
| `VectorStoreChatMemoryAdvisor` | — | 不跟（§7） |
| `chain.copy(this)` 递归 advisor | 闭包链天然"仅下游" | **免费拥有**（§8） |
| advisor context map | 请求 map 透传 + 闭包 | 不跟（§8） |
| `getOrder` 数值排序 | 注册顺序即层序 | 不跟（§8） |
| Call/Stream 双接口（`Flux`） | `:token-xform` transducer | 吸收算子思想（见 `token-stream-filter-design.md`） |

---

## 1. ToolCallingAdvisor

Spring 2.0 的核心动作：把「调模型 → 执行工具 → 结果回灌 → 再调模型」这个循环
从各 ChatModel 内部搬进 advisor 链，用 `chain.copy(this)` 递归自己。好处是别的
advisor 能观察/介入每一轮迭代。

**我们不搬家**（沿用 §14 的判断）：我们的循环与 gate/HITL/暂停/持久化/
`:writes` 屏障折叠深度耦合，为优雅而重构不值。`:turn` 钩子包住循环本体，
已经拿到「在循环外面包 around」这个真实价值。

但对完之后发现 Spring 的 ToolCallingAdvisor 还有**两个我们没有的能力**，与
搬不搬家无关，是纯功能缺口——本次补齐：

### 1.1 return-direct

`{:return-direct true}`（deftool 选项）：**工具结果即最终答案，不再回灌 LLM**。
典型场景：转人工、升级工单、guardrail 拦截——答案已经定了，再问一次模型纯属
浪费且可能被改写。

- **全体语义**：整批 tool-call **都**声明才生效（对齐 Spring 的 allMatch）。
  混批继续正常回灌——「一半直接返回、一半交给模型」没有自洽解释；
- 多个 return-direct 工具的结果按原序换行拼接为最终答案；
- 与 `:sensitive`/gate/`:writes` 正交：屏障照常折叠，环境类失败照常暂停
  （env-pause 优先于 return-direct 判定）。

**落库这一刀（易漏）**：正常路径下，某轮工具结果是靠**下一轮 invoke-chat**
经 memory filter 落库的（每轮只向 invoke-chat 传 delta）。return-direct 没有
下一轮——不补落库，历史里就只剩 `assistant(tool_calls)` 而无对应结果，
下个 turn 的 `heal-dangling-tool-calls!` 会把它整条摘掉，于是「用户问了、
也答了」在历史里双双蒸发。故 `react/persist-direct-messages!` 在 turn 终端补
一刀，落库形状与正常路径一致（`user → assistant(tool_calls) → tool(results)`，
不额外造 assistant 消息——同 Spring returnDirect 的 history）。
store 取自 kernel 上 memory filter 暴露的 `:store`：**没挂 memory filter 就不落**
（此时正常路径本来也不落，语义一致），且 `resume` 无需新增 store 参数。
幂等靠落完即 `dissoc :direct-messages`（turn filter 递归重入不重复落）。

**live 实测**（`examples/return_direct_live_test.clj`，17 项断言）：**对照组是
重点**——同一句合规话术（转人工工单文案），走 return-direct 逐字送达、LLM 只调
1 次；走普通工具则被回灌改写（`【工单已创建】已转接人工客服…` 被模型重写成
`已为您创建转接工单…`，标记丢了）。两边一比才知道它在防什么：**合规文案要么
逐字送达，要么任由模型润色**，没有中间态。

补落库那一刀也用真实第二轮对话验了：第 1 轮 return-direct 转人工后，第 2 轮问
「工单号是多少」，模型答得出 `T-88123`——证明 transcript 完整落库、没被下个
turn 的 heal 摘掉。这是本次对齐里最容易漏的一刀，单测（`return_direct_test.clj`）
与 live 各钉一遍。

### 1.2 可插拔续跑判据（ToolExecutionEligibilityChecker）

`build-kernel` 新增 `:eligibility-fn (fn [response context] -> boolean)`：
响应带 tool-call 时**是否真的执行并续跑**。返回 false → 该响应按最终答案收尾，
工具一个都不执行。缺省恒真（行为不变）。

解锁：预算/配额闸门、按 context 关停工具、灰度。

### 1.3 ToolCallingManager —— 我们不长这个抽象

> 本节是**记录性补充**（2026-07-15，无代码变更，不计入本次 292/1194）。
> 起因：与 `cl-agent`（Common Lisp，同样对标 Spring AI 2.0，但**全面照抄**
> ChatClient + Advisor）对读时发现，它有 `ToolCallingManager` 而我们没有。
> 结论是「刻意拆散」而非疏漏，故留档——否则下一个人还会再问一遍。

Spring 把「执行一批 tool-call」抽成 `ToolCallingManager`，由 `ToolCallingAdvisor`
调用；cl-agent 照抄（`core/chat/tool.lisp`，default / concurrent 两个实现）。
**关键性质是循环不在它这里**：advisor 在 loop 里反复调模型，manager 只被调用
一次做完一轮就返回——不知道 max-iterations、不知道自己是第几轮、也不决定要不要
继续。它独立存在是为了两个可替换点：**执行策略**（顺序 / lparallel 并发，
语义一致可换实现）与**错误策略**（`process-tool-execution-error` 泛型函数，
默认转文本回传，可覆盖成 re-signal 冒泡）。

**我们的切法同性质，且多一层**：

| 职责 | Spring / cl-agent | 我们 |
|---|---|---|
| 循环 / max-iterations / 是否续跑 | `ToolCallingAdvisor` | `react/invoke` |
| **一批 tool-call** | `ToolCallingManager` | `react/execute-batch` |
| **单个工具执行** | （manager 内部） | `kernel/invoke-tool` |

`kernel/invoke-tool` 执行**一个**工具、**一次**，同样不知道 max-iterations、
不知道第几轮——与 manager 是同一种"不持有编排"的性质，只是粒度更细一层。
（外部若把「kernel 把 invoke-tool 和编排揉在一起」当成我们的切法，是误读：
循环早已下沉到 `client/react.clj`，见该 ns doc 首行。）

**那两个可替换点我们都有，只是都不落在 manager 上**：

- **执行策略 → 落在 tool 声明，不在实现选择**。缺省并行（虚拟线程），
  批内任一工具声明 `:serial` 则整批退化按序（`react/execute-batch`）。
  理由：manager 层「全局选顺序 or 并发」是个**假选择**——真实工具集是混的
  （多数只读可并行、少数有副作用）。全局选顺序＝为少数惩罚多数；全局选并发
  ＝那少数出事。粒度放在工具上，**声明者正是知情者**。
  （`(<= (count tool-calls) 1)` 时退化回顺序，与 cl-agent 并发版 ≤1 个工具
  `call-next-method` 退化回顺序版是同一个优化，各自独立得出。）
- **错误策略 → 落在数据 + 屏障路由，不在方法覆盖**。
  `err/classify-exception` 三分类 `:semantic` / `:transient` / `:environment`
  → `execute-batch` 把失败连同类别放进 `:errors` 交到屏障 → `:transient` 且
  工具声明 `:retry` 则指数退避；`:environment` 且 `:on-env-error :pause` 则
  暂停等人修好再 resume。缺省 `:proceed`（转文本回传，与 Spring/cl-agent
  默认一致）。
- **限流 / 熔断 / 超时 → 落在 `:tool` filter 链**（`timeout-filter` /
  `approval-filter`），不在 manager。

**更本质的一条：concurrent manager 有一半在管线程池生命周期**——`pool-size`、
双检锁懒创建 lparallel kernel、幂等 shutdown。我们用虚拟线程：

```clojure
(def ^:private tool-executor
  (delay (Executors/newVirtualThreadPerTaskExecutor)))
```

没有池要调、没有 size 要选、没有 shutdown 要幂等。**那个抽象有相当一部分是在
解一个我们不存在的问题**——这是"要不要抽"的判据被运行时改写的典型例子。

**代价（诚实记账）**：manager 是可替换的对象，能塞进一个完全不同的执行器
（优先级队列、限流池、分布式）而循环一行不动。我们的 `tool-executor` 是私有
`delay`，**写死的**——要换得改 `execute-batch`。缓解手段两个但都不完全等价：
`react/run-tools` 是公开的（docstring：「供外部手搓工具循环使用」），可自搓
循环；限流那类需求走 `:tool` filter。但「**保留内置循环、只换执行器**」这件事
我们确实做不到。

**立项判据**：出现真实需求（分布式工具执行、需要优先级队列的池）再抽，且抽的
形状应是 **`execute-batch` 的 executor 可注入**（kernel `:settings` 加一个键），
**而不是引入 manager 对象**——两个可替换点已各有更好的落点，manager 只会与
`:serial` / `:tool` filter 链重叠。

> **⚠️ 已修订（2026-07-15）**：本节判断**已被推翻**——见专文
> [`tool-calling-manager-design.md`](tool-calling-manager-design.md)。
>
> 推翻理由摘要：deftool 要支持 HTTP/MCP backend（多 transport）的需求兑现了
> §1.3 末尾的「真实需求」条件；而 §1.3 推荐的「executor 可注入」形状只能换
> 线程池（kernel 级），不能换 transport（per-tool）。新设计用 **`ToolCallingManager`
> 协议 + 多 record 实现**（`VirtualThreadToolCallingManager` / `SequentialToolCallingManager`
> / `ThreadPoolToolCallingManager`）——既是「executor 可注入」的彻底兑现（多 impl 而非
> 单键注入），又通过 `deftool :backend` 元数据独立承担 transport dispatch（不与
> manager 重叠）。**§1.3 当初担心的「manager 与 :serial / :tool filter 重叠」通过
> 严格边界契约避免**（详见专文 §3 对账表与 §11 决策 #14-#17）。

---

## 2. ToolSearchToolCallingAdvisor —— 唯一的架构级缺口

Spring 的动机（实测数据）：28 个工具 ≈ 5K–17K token 全量进 prompt，且模型在
30+ 同名工具间选择准确率下降；渐进式披露省 34–64% token。

### 2.1 机制：零新增钩子

**不需要新 advisor 类型**——现有三条契约天然拼出全部能力：

1. `run-tool-loop` 每轮把**当轮 tool-context** 塞进 ChatRequest 的 `:context`
   （react.clj）；
2. `invoke-chat` 的 terminal **由 request 当前字段重建** chat-opts
   （kernel.clj）→ `:chat` filter 改写 `:tools` 会抵达 provider；
3. v0.3 的 `:writes` + `:state-slots` 槽级 reducer 让工具写意图在屏障处按序
   折叠进 context。

于是三件套：

```
search_tools（普通内联工具）  ──返回 {:writes {::discovered #{名字}}}
        ↓ 屏障按槽 reducer 折叠（into = 集合并，跨轮累积）
tool-context ::discovered
        ↓ 每轮进 ChatRequest :context
:chat filter  ──把 :tools 重写为 [search_tools] + 已发现
```

**白拿的性质**：发现集合住在 tool-context 里 → 暂停/resume/持久化全自动正确
（tool-context 本就随快照走），无需任何额外状态。这是 v0.3 MapReduce 契约的
意外红利。

**三件套必须同装**（`with-tool-search` 一次装好，或 `tool-search` 手工接线）：
少 `:state-slots` → 发现集合退化 last-writer（每轮只剩最后一次检索结果，
不累积）；索引由 filter 在每次 LLM 调用时按当轮 `:tools` 建（tag 过滤可能改变
工具集，值变即重建）——真实循环里模型必先经 invoke-chat 才可能发 tool-call，
先后天然成立，但绕开 chat 直调 `invoke-tool` 会检索到空。

### 2.2 索引：零依赖 + 可插拔

Spring 提供 vector / Lucene / regex 三种 ToolIndex。我们只内置**零依赖**两种，
语义检索留给协议注入——与本仓一贯取舍一致（拒 Reactor、拒 vector store 本体、
只留挂点）：

- `(keyword-tool-index)` —— 名称/描述分词重叠打分（名称权重 2、描述 1）。
  **中文按二元组切分**：本仓工具描述以中文为主，空白分词对中文完全无效
  （同 Lucene CJKBigramFilter 的做法）；同时拆 snake_case 与 camelCase；
- `(regex-tool-index)` —— query 当正则匹配工具名（`get_.*_data` 之类）；
  非法正则退化为字面匹配而非抛异常（模型乱传 query 不该炸掉循环）。

自带向量库的用户 `(reify IToolIndex ...)` 注入即可。

### 2.3 live 实测（MiniMax `MiniMax-M2.7`，2026-07-15）

可复现脚本：`examples/toolsearch_live_test.clj`（11 项行为断言 + 对照报告）。
同一问题（需天气 + 服装店两种能力）跑对照，50 个工具的目录，冷缓存：

| | 轮数 | 真实 prompt token |
|---|---|---|
| 基线（50 工具全量进 prompt） | 2 | 12100 |
| ToolSearch（`limit` 3） | 3 | **2637（省 78%）** |

任务质量不掉：两种模式都正确调用了 get-weather + list-clothing-shops。

**拐点看的是 schema 总量，不是工具个数。** ToolSearch 的固定成本 ≈ 多一轮 LLM
往返 + 检索结果进历史（实测约 600–1000 token）；只要工具定义总量超过这个数就
开始赚。实测过两个 12 工具目录，结论相反——描述短的那个 ToolSearch 多花 13%
（2152 → 2439），描述长的那个反而省（3341 → 2676）。**故沿用 Spring 的度量
（工具定义 >5K token 才用），而不是「20+ 个工具」这种数工具的说法。**

### 2.4 ⚠️ 与 prompt cache 的相互作用（实测发现，Spring 未提）

**「省 token」未必「省钱」，取决于基线的工具前缀有没有被缓存预热。**
基线的工具定义前缀是**静态**的，天然适合 prompt cache；ToolSearch 的工具列表
**每轮都在变**，前缀一变缓存即失效。按缓存读 10% 计价折算，两种缓存状态下的
实测结论**相反**：

| 场景 | 基线等效成本 | ToolSearch 等效成本 | 结论 |
|---|---|---|---|
| **冷缓存**（单次会话首跑；基线只有第 2 轮起能命中，实测命中率 47%） | 7019 | 2450 | ToolSearch **省 65%** |
| **热缓存**（同一静态前缀在 TTL 内被反复使用，实测命中率 93%） | 1916 | 3175 | ToolSearch **贵 66%** |

差别**全在基线前缀的冷热**，与 ToolSearch 自身无关：基线的工具块一旦被缓存就
近乎白送，而 ToolSearch 每轮改写 `:tools` 恰好把可缓存的前缀打碎（命中率
0.6%–8%）。

> 方法论坑（本文档最初一版就栽在这里）：`response-usage` 的 `:input-tokens`
> **不含**命中缓存的部分。同一脚本跑第二遍，基线的 50 个工具会显示成「330
> token」，据此得出的任何对照都是错的。必须用
> `input + cache-read + cache-write` 算真实 prompt 规模。

**判断口径**：

- **值得用**：上下文窗口吃紧、工具选择准确率下降（30+ 同名工具）、provider 无
  prompt cache、工具集本就随会话变化（缓存本来也命中不了）、一次性/低频会话
  （前缀热不起来）；
- **可能亏**：工具集静态 + 高频会话 + provider 有廉价 prompt cache——此时基线的
  静态前缀几乎白送，ToolSearch 反而把它打碎。**此时省的是上下文窗口，不是钱**。

（本仓 provider 的 schema 转换另有 32 条 bounded memo（`schema/openai.clj`），
工具集动态变化会多占几条——那只是进程内缓存，正确性与成本均无影响。）

### 2.5 边界

- **发现集合的作用域 = tool-context 的作用域**。调用方把上一轮 `:tool-context`
  回传给下一轮 `:context` → 发现跨轮累积（≈ Spring 的 per-conversation）；
  不回传则每轮从零检索；
- **召回优先于精确**：关键词索引按 IDF 加权（普遍词如「查询」「获取」权重趋近
  0，出现在所有文档中的词恰为 0 = 天然停用词）。但**不设相对分数截断**——实测
  的真实失败是**召回**（模型只检索了一种能力就动手作答，另一半问题没答），
  而非精确（多召回两个无关工具，模型直接忽略，成本 ~100 token）。用截断换精确
  会把便宜的问题换成贵的问题。`:limit` 才是控制暴露量的旋钮；
- **检索工具的描述是 prompt 工程，不是文档**：实测中「任务需要多种能力时必须
  为每一种各检索一次」这条指令是**必需的**——没有它，模型检索一次拿到天气就
  直接作答，服装店那半个问题静默丢失（基线则正确调用了两个工具）。
  渐进式披露把「工具选择」变成了模型的一项**主动任务**，描述写不好就掉召回；
- `:always-include` 可让关键工具无需检索即常驻（Spring 无此项；没有它就无法
  把 tool-search 与必备工具组合）。

---

## 3. StructuredOutputValidationAdvisor

机制我们早有（`validation-turn-filter`，§14.2）：不合格 → 把原因作反馈消息
重入循环 → 重试上限 → 耗尽原样返回。Spring 2.0 把它升级为**自动注册**的
recursive advisor，并把 `.entity(T, spec -> spec.validateSchema())` 作为开关。

**真正的缺口是判据不是机制**：我们的 validate-fn 一直得用户自己写。Spring 的
价值恰在 validate-fn 本身——按 JSON Schema 校验，并把失败原因写成模型能据以
**自我修正**的人话（"missing required field 'actor'" / "expected 'array',
got 'string'"），而非干巴巴的「重试」。故补 `advisor/structured-output`：

- `validate-value` —— 纯函数，校验**已解析**的值，零依赖；
- `validate-fn` —— 生成喂给 `validation-turn-filter` 的判据（剥 markdown 围栏
  → `:parse-fn` 解析 → 校验）。

**零依赖的代价与切法**：core **无任何依赖**（deps.edn 明写），JSON 解析器不在
手边，故 JSON 解析由调用方经 `:parse-fn` 注入
（`#(cheshire.core/parse-string % true)`）。缺 `:parse-fn` 直接报错而非静默
不校验。

支持的 Schema 子集：`:type` / `:properties` / `:required` / `:items` / `:enum`，
路径可读（`films[1]` / `user.name`）、keyword 与字符串键都认、只报第一个问题
（一次给模型一个明确目标比糊一堆更容易改对）。不做 $ref/allOf/oneOf/pattern
——要全量 JSON Schema，换掉 validate-fn 即可，机制不变。

**刻意不对齐**：重试上限缺省仍是 2（Spring 为 3）。这是我们已记录的默认值，
Spring 的 3 亦无特别道理，不为对齐而改。

**live 实测**（`examples/structured_output_live_test.clj`，12 项断言）：校验器
本身有单测，live 唯一值得验的是单测证明不了的那件事——**把「缺少必填字段
birth_year」丢回给真实模型，它会不会真的把字段补上**。用「schema 要求 prompt 里
没提过的字段」触发**真实**失败（不作假），实测：第 1 轮确实漏 → 反馈点名该字段
→ 第 2 轮补上 → 校验通过。另钉死两条边界：合格输出只调 1 次 LLM（正常路径不被
校验拖慢）、不可满足的 schema + `:max-retries 1` → 恰好 2 次调用后原样返回
（不无限重试）。

> **自我修正的前提（实测教训）**：脚本最初要求的字段是
> `internal_review_code`——校验失败真发生了，但模型**修不好**：它无从得知这个
> 内部编号该填什么，连续两次反馈后仍旧漏掉，3 次调用耗尽后返回不合格结果。
> 换成 `birth_year`（模型答得上来）即稳定 2 次收敛。
>
> 这不只是测试用例的选型问题，而是**方法本身的边界**：Spring 那套「给诊断信息
> 而非干巴巴重试」隐含假设了**模型有能力照做**。schema 若要求模型无从得知的
> 信息，自我修正只会空转到耗尽——此时 `:max-retries` 是唯一的兜底，而
> 「耗尽后原样返回、由调用方判断合格与否」的设计恰好在这里救命（换成抛异常，
> 这类 schema 会把整个 turn 炸掉）。

---

## 4. MessageChatMemoryAdvisor

等价物是 `advisor/memory` 的 memory-filter（`:chat`，按 conversation-id 读写）。

**放置刻意不同、不跟**：Spring 的 memory advisor 在循环外（一个 turn 只落一次
用户消息与最终答案）；我们**刻意放循环内**——每轮落库完整 transcript（含工具
往返）。heal-dangling、暂停恢复、timeline、`:writes` 进历史全都依赖完整历史，
这是我们的契约，不是疏漏。

（Spring 2.0 的 ToolCallingAdvisor 有 `conversationHistoryEnabled` 开关，本质是
在补这件事——某种程度上是在往我们的方向走。）

---

## 5. SafeGuardAdvisor —— 推翻旧决定

**旧记录**：§14.3「不跟：SafeGuardAdvisor（用户一个 chat filter 即可）」。

**推翻理由（不是因为想做，而是因为旧结论错了）**：那句话写在 `:turn` 链落地
**之前**，而 `:chat` 是错误的挂点——`:chat` filter 会在工具循环内**每轮**重查，
且第 2 轮起 `:messages` 是 memory 拼出的**完整历史 + 工具往返**，既重复告警
又会因历史里的旧内容误伤。Spring 的 SafeGuard 查的是「用户这次的输入」，
在循环外只查一次——那是 `:turn`。

`(safeguard-turn-filter 敏感词 :failure-response "...")`：入口消息命中任一
敏感词 → 不进循环，直接返回 failure-response（结果带 `:blocked-by :safeguard`
供计数/告警）。匹配放宽为**大小写不敏感**（Spring 原版大小写敏感，大小写绕过
是显然的漏网）。

**语义后果（刻意）**：短路发生在 `:turn` 层，memory filter（`:chat`，循环内）
压根不会执行——被拦的输入与拒答**都不落库**。history 里不留有害内容，代价是
下一轮模型看不到「用户问过什么、被拒了」。

resume 天然安全：`:resume?` 进入时 `:messages` 为 nil，无文本可查即放行
（入口消息早在首次进入时查过）。

**边界：它是「入口」守卫，不是输出守卫。** 挂 `:turn` 只查这一轮的入口消息——
工具结果里、模型输出里出现敏感词**不会**被它拦（live 实测：一个返回含 "hack"
的工具照样跑完）。输出侧是另一类需求，用 `:token-xform` 的
`hold-release-filter`（流式先审后放）或 turn 链的校验 filter。别因为挂上它就
以为输出也保住了。

**live 实测**（`examples/safeguard_live_test.clj`，17 项断言）：拦截逻辑本身有
单测，live 验的是单测证明不了的两件事——
① **拦下时真的一次 LLM 都没调**（短路在 `:turn` 层，连接都没建：既省钱，也意味
着有害输入根本没离开本机）；
② **不落库的代价在真实多轮里长什么样**：第 1 轮被拦（不落库）后，第 2 轮问
「我刚才问了什么」，模型答「很抱歉，我没有之前的记录——这实际上是我们对话的
开始」。取舍是刻意的（history 里不留有害内容），但代价得看得见。

---

## 6. SimpleLoggerAdvisor / ReReadingAdvisor

- `(logging-chat-filter :log-fn f :preview n)` —— 既有 `logging-filter` 只覆盖
  工具侧，LLM 侧无对应日志。补 `:chat` 侧：请求记 messages/tools/tool-choice
  数，响应记 tool-calls 名或文本预览（截断）。
- `(re-reading-filter :template f)` —— RE2：把入口用户问题重复一遍附在其后。
  挂 `:turn` 且只改写**入口消息**（挂 `:chat` 会把循环内每轮的历史都重读一遍，
  既无意义又污染 transcript）；`:resume?` 跳过。论文有效性有争议，但对齐成本
  ~10 行。

---

## 7. RAG 三兄弟

### 7.1 QuestionAnswerAdvisor —— 推翻旧决定

**旧记录**：§4 / §14.3「不跟本体（需 vector store，超出定位），留 :turn 挂点」。

**推翻的是「不做本体」，不是「不引 vector store」**：`advisor/rag` **仍不引任何
检索依赖**，只定义 `IRetriever` 协议 + 注入 filter，向量库/embedding 由用户
注入——与 ToolSearch 的 `IToolIndex` 同一取舍。

之所以值得做：QuestionAnswerAdvisor 的实质是**提示词编排**（把问题、检索结果、
grounding 指令拼成一条增强消息）而非检索本身。这块编排每个用户都要重写一遍、
且容易写错——尤其「每 turn 只注入一次」这个点，正是 §14.3 自己点名的 turn 链
解锁场景。

`(qa-turn-filter retriever :top-k 4)`：取入口最后一条用户问题 → `retrieve` →
按模板拼进该条消息 → 进循环。

**刻意偏离 Spring**：检索为空时**不注入**（原样进循环）。Spring 照样注入空
上下文 + 「上下文里没有就说不知道」指令，于是检索一旦落空模型会拒答一切。
要 Spring 那种严格 grounding，传 `:inject-when-empty? true`。

只改写 content 为 **string** 的用户消息：增强的做法是把问题重写成「问题 +
上下文」，对多模态 content（向量）改写会丢掉图片片段——宁可不插手也不悄悄
毁数据（re-reading-filter 同款取舍）。

**live 实测**（`examples/rag_live_test.clj`，18 项断言）：语料全为**虚构事实**
（「Nimbus-7 咖啡机每 42 天除垢、用 pH 3.2 柠檬酸」——模型训练数据里不可能有），
故对照组（不挂 RAG）答不出 42、RAG 组答得出——**grounding 真的来自检索**这件事
才算被证明，拿真实世界知识提问是证不出来的。

上面那条「检索为空不注入」的偏离也被实跑印证了：同一个无关问题（「写一句关于
春天的短诗」），默认行为 → 模型正常作诗；`:inject-when-empty? true`（Spring
行为）→ 模型拒答「抱歉，我没有足够的上下文信息来写关于春天的短诗」。**检索一旦
落空，Spring 的语义会让模型拒答一个跟检索毫无关系的问题**——这就是不跟的理由。

### 7.2 不跟

- `RetrievalAugmentationAdvisor`（模块化 RAG：查询改写/扩展/压缩/重排）——
  一整套 `org.springframework.ai.rag` 构件的门面。这些环节在 `IRetriever`
  实现内部做，或自写 turn filter；机制都在。
- `VectorStoreChatMemoryAdvisor` —— 需 vector store 本体，且与我们
  「memory 放循环内、落完整 transcript」的契约冲突。

---

## 8. 机制层：早已拥有 / 明确不跟

- **`chain.copy(this)` 递归 advisor**：Spring 1.1 experimental、2.0 转正的新
  API，用于「重入下游链而不重跑上游」。我们的 `build-chain` 折叠出的 `chain`
  参数**本就只含更内层**——闭包模型里这是构造性的免费性质，无需任何 API。
  `validation-turn-filter` 的重试即多次 `(chain req)`。
- **advisor context map**（跨 advisor 共享状态）：不跟。请求 map 透传 + filter
  私有闭包已够；跨工具的共享状态另有 `:writes` + `:state-slots` 这条更强的路
  （有确定性折叠语义，advisor context map 没有）。
- **`getOrder` 数值排序**：不跟。注册顺序即层序，显式列表更直白；数值 order
  在多来源注册时是经典的坑。

---

## 9. 装配示例

```clojure
(kernel/build-kernel
  (ts/with-tool-search
    {:service svc
     :tools   [#'handoff #'search-docs ... #'t80]
     :filters [(ma/memory-filter store)                       ;; :chat，首位
               (flt/safeguard-turn-filter ["炸弹" "hack"])     ;; :turn，最外层守卫
               (rag/qa-turn-filter retriever :top-k 4)         ;; :turn，每 turn 注入一次
               (flt/validation-turn-filter                     ;; :turn，答案校验重试
                 (so/validate-fn schema :parse-fn #(json/parse-string % true)))
               (flt/timeout-filter 5000)                       ;; :tool
               flt/logging-filter]                             ;; :tool
     :eligibility-fn (fn [_resp ctx] (pos? (ctx/get-var ctx :budget 1)))}
    {:index (ts/keyword-tool-index) :limit 5}))
```

turn 洋葱：`safeguard → qa → validation → [循环: memory → tool-search → LLM；
timeout → logging → 工具]`。

---

## 相关文档

- `filter-chain-design.md` —— filter/advisor 机制的整合权威参考（洋葱、三链
  契约、递归重入、内置 filter 全表）
- `agent-loop-concurrency-design.md` §9（`:writes`/`:state-slots` MapReduce
  契约，ToolSearch 的地基）、§14（turn 链）
- `token-stream-filter-design.md` —— `:token-xform` 与 StreamAdvisor 的对照
- `hitl-timeline-design.md` —— gate/暂停/resume 与 filter 链的边界分工

## 参考

- Spring AI Reference, [Advisors API](https://docs.spring.io/spring-ai/reference/api/advisors.html)
- Spring AI Reference, [Recursive Advisors](https://docs.spring.io/spring-ai/reference/api/advisors-recursive.html)
- Spring AI Reference, [Dynamic Tool Discovery with Tool Search Tool](https://docs.spring.io/spring-ai/reference/guides/dynamic-tool-search.html)
- Spring Blog, [Spring AI 2.0.0 GA Available Now](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)
- Spring Blog, [Self-Correcting Structured Output in Spring AI 2.0](https://spring.io/blog/2026/06/23/spring-ai-self-correcting-structured-output/)
- Spring Blog, [Smart Tool Selection: 34-64% Token Savings with Dynamic Tool Discovery](https://spring.io/blog/2025/12/11/spring-ai-tool-search-tools-tzolov/)
