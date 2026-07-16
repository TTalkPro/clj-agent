# ToolCallingManager 协议设计：统一工具执行管理

> **状态：✅ PR1 已实施（v5）。`:backend` 扩展已否决，不会实施。**
> 本文是对 `advisor-alignment-design.md` §1.3《ToolCallingManager —— 我们不长这个
> 抽象》的修订：从「不长」到「引入统一执行协议」。修订理由与边界见 §2、§3。
>
> **v5 修订（2026-07-16，用户拍板）**：**定位校准——`ToolCallingManager` 是
> clj-agent 的工具执行引擎**：隔离边界 + 线程模型 + 调度策略。这一条理由本身
> 完全站得住，**与 `:backend` 无关**（`:backend` 管"执行的是什么"，manager 管
> "怎么执行"）。§2 整章按此重写；v4 因 `:backend` 否决而对 §1.3「`:settings` 注入
> executor」作出的让步是**误判，已在 v5 更正**（差异是能力级不是偏好级，
> 三条代码证据见 §2.2）。据此补齐 `ThreadPoolToolCallingManager`（§4.3）——
> **隔离既是 manager 的本职，就不能只停在文档里**。协议签名与既有引擎行为不变。
>
> **v4 修订（2026-07-16，用户拍板）**：**`deftool :backend` 元数据整条否决**——
> HTTP / MCP 是工具**函数体内部**怎么实现的问题，框架不该知道工具走什么 transport。
> 原 §5（`:backend` 元数据）、§6（transport 实现细节）已删除，替换为 §5《为什么
> 不长 `:backend`》记录否决理由；§8 的 PR2/PR3 从「⏸️ 搁置」改为「❌ 否决」。
> **本文档现在只讲一件事：manager 协议本身**（§4 / §7 / PR1，均已实施且不受影响）。
>
> **`:backend` 的一句话立场**：工具走 HTTP 还是 MCP，是它**函数体内部**的事——
> 框架不该知道，`deftool` 不该有这个字段。
>
> **Review 修订记录（v2）**：基于 Oracle 评审反馈修订了 3 处 blocking 问题
> （B1 defmulti 模块归属、B2 PR1 零变化承诺、B3 inline tools 盲点）、
> 5 处重要问题、4 处缺失考量。修订点散落各节，关键决策汇总在 §11。
> **注**：B1 / B3 / I3 / I5 / C1 / C3 处置的都是 `:backend` 相关问题，随 v4 一并作废。
>
> **v3 修订（用户收敛协议形状）**：协议方法从 `execute-batch` 改为 Spring 对齐的
> `execute-tool-calls`（带 `response` 参数）；`execute-batch` 与 `execute-single`
> 退回成**每个 record 的内部 helper**（不再是协议方法）；明确多 impl 策略
> （Sequential / VirtualThread / ThreadPool）。

---

## 0. TL;DR

`ToolCallingManager`（**core 模块**）是 clj-agent 的**工具执行引擎**：
拿到一批已批准的 tool-call，决定**怎么把它们跑完**——线程模型、隔离边界、调度策略。
协议只有一个方法 `execute-tool-calls`（Spring 对齐签名，带 `response` 参数）。

**换 manager = 换执行引擎**（v3 引入，v5 明确为核心定位）：
- `VirtualThreadToolCallingManager`（每调用一根虚拟线程。**2026-07-16 起不再是缺省**
  ——缺省改为 Sequential，见下）
- `SequentialToolCallingManager`（**缺省**，全串行，**不构造 Future**）

> **⚠️ 缺省与超时的定调（2026-07-16 用户拍板，两条 💥）**：
> ① **缺省引擎改为 Sequential**——并发要求同批工具的副作用彼此无序依赖，那是
> **调用方才知道**的性质，框架不替它假定；要并发是显式决定（注入 VT / 池引擎）。
> 状态语义与引擎无关（都是轮初快照 + 屏障折叠），故这条只改调度、不改语义。
> ② **超时缺省为「不超时」**，两个显式来源：`工具声明 deftool {:timeout ms} >
> 引擎缺省 (…-tool-calling-manager {:timeout ms}) > 不超时`。**时间上限属于执行
> 策略，故随引擎构造**（三个引擎均接受 `:timeout`），与 beamai
> `manager_opts.tool_timeout` 同一立场。详见 [`tool-timeout-design.md`](tool-timeout-design.md)。
- `ThreadPoolToolCallingManager`（有界平台线程池，限流 / **舱壁隔离**，可配 pool-size）

`execute-batch` 与 `execute-single` 退回成**每个 record 的内部 helper**——
**怎么实现完全是 record 自己的选择**：可以用 virtual thread、可以全串行、
可以用 ExecutorService。协议不规定，框架不强加。

**`deftool` 不动**（v4）——工具怎么拿到结果（进程内计算、HTTP 调用、MCP 调用）
是**函数体内部的实现细节**，框架不感知。详见 §5。
manager 管「**怎么执行**」，`:backend` 曾想管「**执行的是什么**」——
后者不是框架的事，故否决；**前者与后者无关，manager 不受影响**。

**关键边界（防止与现有抽象重叠）**：
- `:serial` 仍是声明级执行策略，**不由 manager 决定**
- `:tool` filter 仍是 around 链，**不被 manager 取代**
- `:writes` 屏障折叠仍走 `ctx/apply-writes`，**不被 manager 接管**
- manager 只把「执行入口」从 `react.clj` 私有函数升格为**可注入协议**

---

## 0.5 适用的设计原则

本文遵循 **[`design-principles.md`](design-principles.md) §1《无真实需求不建》**
（硬约束）：

> **抽象要由真实需求触发，不由对齐别人、对称性、或「以后可能要」触发。
> 用户在自己代码里几行就能等价做到的事，框架不长字段、不开 seam、不加协议。**

**本文档是该原则的判例现场**——四条判例全部出自这里，含一次自打脸
（详见 `design-principles.md` §1.4）：

| 案例 | 判定 | 本文位置 |
|---|---|---|
| `deftool :backend` | ❌ 否决 | §5 |
| PR2 / PR3 | ❌ 否决 | §8 |
| `*active-pools*` 自锁保护 | ❌ 拆除 | §4.3.1 |
| **`ToolCallingManager` 自己** | ⚠️ 险些踩中（假想的 transport 腿 + 真实的隔离腿） | §2 |

四问判据、落地约束与判例依据**不在本文重述**，见 `design-principles.md` §1。

---

## 1. 背景：§1.3 之前的判断

`advisor-alignment-design.md` §1.3（2026-07-15）做过明确判断：**不引入
`ToolCallingManager`**。核心论点三条：

1. **现有切法同性质且更细一层**：

   | 职责 | Spring / cl-agent | clj-agent |
   |---|---|---|
   | 循环 / max-iterations | `ToolCallingAdvisor` | `react/invoke` |
   | 一批 tool-call | `ToolCallingManager` | `react/execute-batch` |
   | 单个工具执行 | （manager 内部） | `kernel/invoke-tool` |

2. **两个可替换点（执行策略 / 错误策略）已有更好落点**——`:serial` 声明、
   `:tool` filter 链、`:writes` + `:state-slots` 屏障路由。

3. **立项判据**：出现真实需求时再抽，**形状应是 `execute-batch` 的 executor
   可注入（kernel `:settings` 加一个键），而不是引入 manager 对象**。

---

## 2. 定位：ToolCallingManager 是执行引擎（v5 重写）

> **v5 修订（2026-07-16，用户拍板）**：v4 因 `:backend` 否决而把本节写成
> 「立项理由只剩一条腿、比原来细、对 §1.3 大幅让步」。**这个让步过头了**——
> 它默认了「manager ≈ executor 的容器」，而代码不支持这个默认（§2.2 三条证据）。
> 本节按正确定位重写：**manager 是工具执行引擎——隔离边界 + 线程模型是它的本职，
> 这一条腿本身就站得住**。

### 2.1 一句话定位

**`ToolCallingManager` 是 clj-agent 的工具执行引擎**：拿到一批已批准的 tool-call，
决定**怎么把它们跑完**——用什么线程模型、在什么隔离边界内、按什么调度策略。

换 manager = 换执行引擎：

| 实现 | 线程模型 | 隔离边界 |
|---|---|---|
| `VirtualThreadToolCallingManager`（默认） | 每调用一根虚拟线程 | 现状：**进程全局共享**（`react.clj:66` 是 `def` + `delay`） |
| `SequentialToolCallingManager` | 调用方线程，无并发 | 无额外线程，无资源可争 |
| `ThreadPoolToolCallingManager` | 固定平台线程池 | **每实例一个有界池**——舱壁隔离，工具执行不会饿死其他工作 |

**这就是全部理由，且足够。**`:backend`（工具走 HTTP 还是 MCP）与本职无关——
那是工具函数体的事（§5）；manager 只管**怎么执行**，不管**执行的是什么**。

### 2.2 为什么「`:settings` 注入 executor」做不到这件事

§1.3 推荐的形状是 kernel `:settings` 加一个 `:tool-executor` 键。
**它换的是线程从哪来，不是执行引擎。**三条代码证据：

**(a) 调度策略是硬编码的，Executor 接口够不着**

```clojure
;; react.clj:167 —— 这个决策与 executor 无关，注入什么 executor 都改不了它
(if (or serial? (<= (count tool-calls) 1))
  (mapv  ...)                                 ;; 内联执行
  (mapv #(.submit ^ExecutorService @tool-executor ...) ...))  ;; 提交
```

`Executor` 只回答一个问题：「把这个 Runnable 跑掉」。它回答不了
「这批 5 个调用**要不要**并行 / 要不要分块 / 要不要按优先级排序」——
**那是引擎的决策，不是线程池的决策**。

**(b) Sequential 不是「换了个 executor」，是另一条代码路径**

`execute-batch-sequential`（`react.clj:207`）**从头到尾不构造 `Future`**。
用 same-thread executor 模拟不出它：那样每个调用仍被 `.submit` + `.get` 包着，
仍要吃 `ExecutionException` 的包装语义、`Future.get` 的中断语义、以及
future 携带的线程上下文。**「不用 future」本身是一种执行策略，
而 Executor 接口的存在前提恰恰是「你要提交任务」。**

**(c) 池的生命周期属于策略，不属于配置键**

`SequentialToolCallingManager` 不持任何资源；`ThreadPoolToolCallingManager`
持有一个需要 `.shutdown` 的池。**策略与它的资源必须打包**——
`:settings` 单键做不到「换策略时资源跟着换」，它只能收下一个别人造好的 executor，
生命周期无人认领。

### 2.3 隔离：现状的真实缺口

`react.clj:66` 的 `tool-executor` 是 **`def` + `delay`——进程全局单例**。
今天同一个 JVM 里**所有 kernel、所有 agent、所有子 agent 的工具执行共享它**。
虚拟线程无界，所以短期不会「耗尽」，但也意味着：

- 没有**舱壁**：一个 agent 的慢工具批与另一个 agent 的工具批在同一个 executor 里
- 没有**限流**：想给某个 kernel 的工具执行设并发上限，今天无处可设
- 没有**可关停边界**：executor 随进程生灭，不随 kernel

`ThreadPoolToolCallingManager`（每实例一个有界池）**正是给这个缺口留的位置**——
需要隔离的 kernel 注入它，不需要的继续用默认 VT。**这是 manager 作为引擎的
本职收益，与 `:backend` 无关，`:backend` 被否决不影响它分毫。**

### 2.4 与 §1.3 的关系（v5 校准）

§1.3 末尾预测：「出现真实需求（分布式工具执行、需要优先级队列的池）再抽」——
**隔离与线程模型可换就是这个需求**，且 §1.3 自己把「需要优先级队列的池」
列为触发条件之一，说明它预见到了这个方向。

§1.3 判断失准的地方，是把落点写成了「executor 可注入（`:settings` 加一个键）」——
**低估了要换的东西**：要换的是引擎（调度 + 线程 + 资源 + 隔离），不是线程池。
protocol + 多 record 是引擎的正确形状；`:settings` 单键只是线程池的形状。

> **v5 对 v4 的自我更正**：v4 写「§1.3 这条批评基本成立，剩余差异只是偏好级」——
> **错了**。差异不是偏好级的，是 (a)(b)(c) 三条**能力级**的。v4 犯错的原因是
> `:backend` 被否决后过度收缩，默认了「manager 只剩执行策略这条细腿」，
> 却没回头看这条腿到底有多粗。**它不细。**

## 3. 与 §1.3 的对账（核心）

逐条回应 §1.3 的反对意见：

| §1.3 担心 | 本设计如何避免 |
|---|---|
| manager 与 `:serial` 重叠（执行策略两处决定） | `:serial` 仍是**工具声明级**的批内并行退化开关，由 `VirtualThreadToolCallingManager` 的内部 helper 在分派前读取；manager 协议本身**不知**:serial 存在。换 manager 实现（如 `SequentialToolCallingManager`）仍要尊重 `:serial`——这是契约，不是重叠 |
| manager 与 `:tool` filter 重叠（around 链两处插入） | `:tool` filter 仍包**单个工具执行**（在 `kernel/invoke-tool`），manager 包**整批调度**。两者层次不同：filter 是单工具的 around，manager 是「调度 N 个工具、收齐结果、折叠 writes」的批编排 |
| concurrent manager 半管线程池生命周期 | 虚拟线程 `tool-executor`（`react.clj:65` 的 `delay`）**写在 `VirtualThreadToolCallingManager` 内部**，**协议不感知**。换 manager 实现 = 换一个 record（如 `SequentialToolCallingManager` 不持任何池；`ThreadPoolToolCallingManager` 持有可关停的池，但生命周期由用户管理） |
| 两个可替换点（执行策略 / 错误策略）已有更好落点 | **错误策略：同意**（仍走 `:tool` filter + `:writes` 屏障三分类路由，不进 manager）。**执行策略：不同意**——`:serial` 是**单个工具的声明**（"我不能与人并行"），它决定不了**整批用什么线程模型、在什么隔离边界内跑**。这两件事不是同一个可替换点：前者是工具作者的声明，后者是**部署方的引擎选型**（§2.1） |
| 形状应是 kernel `:settings` 注入 executor | **v5 回答（推翻 v4 的过度让步）**：v3 的理由（"换不了 per-tool transport"）确实随 `:backend` 否决而作废，但**结论不变，换了更硬的理由**：`:settings` 单键换的是**线程从哪来**，manager 换的是**执行引擎**。三条能力级差异（§2.2）：(a) 调度策略 `(if (or serial? (<= count 1)) ...)`（`react.clj:167`）硬编码在框架里，`Executor` 接口够不着；(b) `SequentialToolCallingManager` **不构造 Future**（`react.clj:207`），same-thread executor 模拟不出——"不用 future"本身是策略，而 Executor 的前提恰是"你要提交任务"；(c) 池的生命周期必须与策略打包。**§1.3 这条批评不成立**——它低估了要换的东西 |
| **`kernel/invoke-tool` 已是更细一层的 seam，backend dispatch 落在 `tool/invoke` 即可，何必再上层 manager？** | **v4/v5 回答**：`:backend` 已否决（§5），故「dispatch 落在 `tool/invoke`」这个前提消失——**没有 dispatch 要落**，工具的 transport 是它自己函数体的事。manager 与 `kernel/invoke-tool` 层次不同：后者是**单工具原语**（执行一个），manager 是**执行引擎**（一批怎么跑完：线程模型 + 隔离边界 + 调度策略）。`invoke-tool` 再细也回答不了"这批要不要并行"——不构成重叠，更不构成替代 |

### 3.1 不变的契约

下列契约**本设计完全保留**，是边界，不是要重构的对象：

- `:serial` 工具声明 → 批内整批退化按序（`react.clj:164`）
- `:tool` filter 链 → 单工具 around（短路 / 重试 / 限流 / 审批）
- `:writes` + `:state-slots` 屏障 → 槽级 reducer 折叠（`ctx/apply-writes`）
- 错误三分类 `:semantic` / `:transient` / `:environment` → 屏障处策略路由
- `:retry` 声明 → `invoke-with-retry` 指数退避（`:transient` 类）
- gate → 批前串行预判（HITL），**仍在 `run-tool-loop`，不进 manager**
- `:eligibility-fn` → 续跑判据，**仍在 `run-tool-loop`，不进 manager**
- return-direct → 整批 allMatch 短路，**仍在 `run-tool-loop`，不进 manager**

**简言之**：manager 只负责「拿到批准的 calls 后，执行 + 收结果」这一段。
循环控制、gate、eligibility、return-direct 判定都不归它。

---

## 4. 协议设计

> **v3 重写**：协议方法从 `execute-batch` 改为 `execute-tool-calls`（Spring 对齐，
> 带 `response` 参数）。原 `execute-batch` / `execute-single` 不再是协议方法，
> 退回成每个 record 的内部 helper。**多 impl 是核心**：不同 record 选不同执行策略。

### 4.1 Protocol 定义（core 模块）

```clojure
;; im.ttalk.agent.tool-calling-manager（新 ns，core 模块）
(defprotocol ToolCallingManager
  "工具执行统一管理 seam——所有 tool 执行路径经此协议。

   定位（vs 现有抽象）：
   - 不夺 :serial 的权（声明级执行策略仍由工具声明者决定）
   - 不夺 :tool filter 链的权（单工具 around 链继续工作）
   - 不夺 :writes 屏障的权（状态折叠仍走 ctx/apply-writes）
   - 只把「执行入口」从 react.clj 私有函数升格为可注入协议

   协议只有一个方法（execute-tool-calls），与 Spring AI 2.0 对齐。
   多种执行策略通过不同 record 实现（不是多个协议方法）。"
  (execute-tool-calls [this kernel response opts]
    "从 LLM response 抽 tool_calls + 调度执行 + 返回 ToolExecutionResult。

     参数：
     - this     manager 实例（决定执行策略：顺序 / VT / 线程池）
     - kernel   Kernel 实例（manager 通过它访问 invoke-tool / serial-tool? 等）
     - response ILLMResponse（含 tool_calls，由协议方法内部用 response/response-tool-calls 抽取）
     - opts     {:gate (fn [tc] -> decision) | nil
                 :tool-context ctx
                 :records init-records
                 :on-tool-result (fn [name result])}

     返回 ToolExecutionResult（键名冻结，见 §9.2）：
     {:messages  [tool-result-msg ...]    ;; 按原 call 序（喂回 LLM 的 tool results）
      :records   [...]                    ;; :tool-calls-made 报告累积
      :context   ctx'                     ;; 应用 writes 后（屏障折叠后）
      :errors    [{:id :name :class :message :tc} ...]}"))
```

**v3 关键决策**：
- 协议只有**一个方法** `execute-tool-calls`，签名 Spring 对齐（`response` 入参）
- 内部用 `response/response-tool-calls` 抽 tool_calls——调用方不再手动抽
- manager 是**值对象**（record），不同 record 用不同执行策略

### 4.2 内部 helpers（每个 record 自己的私有 fn）

`execute-batch` 和 `execute-single` 是**每个 record 的内部辅助函数**，不是协议方法：

- **`execute-batch`**——怎么调度一批（顺序 / VT / 线程池，**record 自己选**）
- **`execute-single`**——怎么执行一个工具

这两个 helper 的具体实现完全由 record 决定。可以是：
- record 的私有 `defn-`
- record ns 内共享的 helper fn
- 不同 record 各自重复（如果策略差异大）

**协议不规定怎么实现，框架不强加**——只规定协议方法的契约。

### 4.3 三个引擎（client 模块，✅ 均已实施）

三个 record 都在 `im.ttalk.agent.react`，共用 `execute-batch-via` 的
map + 屏障骨架——**引擎只决定「怎么把这批跑完」**，返回形状与 `:serial` /
`:tool` filter / `:writes` 折叠三条契约由骨架统一保证，换引擎不会改变可观察结果
（`thread-pool-manager-contract-parity-test` 实证）。

| 引擎 | 线程模型 | 隔离边界 | 资源 |
|---|---|---|---|
| `VirtualThreadToolCallingManager`（默认） | 每调用一根虚拟线程 | **无**——进程全局共享 executor（`react.clj` 的 `tool-executor`） | 不持有 |
| `SequentialToolCallingManager` | 调用方线程，**全程不构造 `Future`** | 不持资源，无可争之物 | 不持有 |
| `ThreadPoolToolCallingManager` | 固定大小平台线程池（daemon） | **每实例一个池**：并发上限 = pool-size，不与其他 kernel 互挤 | **持有池，需关停** |

```clojure
;; 默认（不注入 :tool-manager 时走 nil 兼容路径，行为等同）
(kernel/build-kernel {:service svc :tools [...]
                      :tool-manager (react/virtual-thread-tool-calling-manager)})

;; 全串行：调试 / 严格副作用排查
(kernel/build-kernel {:service svc :tools [...]
                      :tool-manager (react/sequential-tool-calling-manager)})

;; 有界池：限流 / 舱壁隔离。池的生命周期归持有者——record 实现 java.io.Closeable
(with-open [m (react/thread-pool-tool-calling-manager
                {:pool-size 8 :thread-name-prefix "my-tools-"})]
  (let [k (kernel/build-kernel {:service svc :tools [...] :tool-manager m})]
    ...))
;; 或显式：(react/shutdown-tool-calling-manager! m) —— 对无资源的引擎是 no-op
```

**`ThreadPoolToolCallingManager` 的边界**：

1. **关停后再执行抛 `ex-info`**（`:error-class :environment`），不静默失败。
2. **不变量：一个引擎属于一个 kernel，不跨 delegate 边界。**见 §4.3.1。

#### 4.3.1 不变量：引擎属于单个 kernel

> **⚠️ 已提为项目级硬约束（2026-07-16，用户拍板）**：本节立的不变量现为
> [`design-principles.md`](design-principles.md) **§3《一个 Kernel 绑定一个 TCM，
> 不跨边界》**——**那里是唯一出处，本节是判例现场**（同 §0.5 之于 §1 的关系）。
>
> 提级时补齐了本节没写透的一面：不变量有**两个方向**——本节只讲了「边界外不
> 流通」（引擎不跨 delegate），而「**边界内一致**」（同一 Kernel/TCM 内，工具
> 可见的一切不得因批次大小 / 引擎选型 / filter 挂载而变）同样是它的推论。
> 2026-07-16 逮到的两个动态绑定 bug（`run-on-executor` / `timeout-filter` 丢
> `binding` 传导）正是**违反后者**——而当时无人想到援引本节，因为它只以括号
> 举例的形式躺在 §1.3 表格里。详见 `design-principles.md` §3.4 的复盘。
>
> **v5 二次修订（用户拍板）**：本节取代 v5 初版的「同线程自锁保护」——
> 那个保护（动态变量 `*active-pools*` + 命中退化内联）**已删除**，理由见末尾。

**子 agent 自有引擎，这本来就是默认，框架里没有共享路径**：
`delegate-tool` 的 `subagent-config` 全部来自用户自己的 `:subagent-fn`
（`delegate.clj:87`，不含任何父 kernel 引用），`do-run` 据此
`(create-agent (merge {:memory false ...} subagent-config))` **全新造一个 kernel**。
父 kernel 的 `:tool-manager` **没有渠道流进子 agent**。

要共享，用户得在自己的 `:subagent-fn` 里**亲手把同一个实例塞回去**——
那是绕过默认专门去踩，不是框架的洞。踩了会死锁：

1. 父批的 delegate 工具占满池 P（`delegate-tool` 是 spawn→await→drop，**阻塞等**）
2. 子 agent 在 VT 线程上跑起来，它的批提交给 P
3. P 的线程全在第 1 步等第 2 步；第 2 步在排队等 P —— 互等，永久挂起

同理 **一个引擎的批不嵌套自己**（在本引擎某工具的函数体里再拿同一实例跑一批）。

**框架不为这些设防**：

- 跨 delegate 的那种**根本测不到**——子 agent 换了线程，任何线程局部的因果标记都
  跨不过去；要传播就得改 `subagent/manager` 去携带执行上下文，**那是在花力气让
  「违反不变量」的用法能跑**，而且跑出来的隔离还是软的（内层退化内联 = 跑在池外，
  实际并发 > pool-size，用户想要的「总量封顶」并没实现）。
- 同线程的那种**测得到，但没有真实需求**：`run-tools` 走的是全局 VT executor 而非池，
  要碰到得**专门**拿同一个 ThreadPool manager 去调 `execute-tool-calls`。
  按 [`design-principles.md`](design-principles.md) §1.3（落地约束：**防御性机器
  同样适用**），这是无真实需求的建造。
- **换信号量也一样**：这不是线程池的毛病，是「持有资源的同时等待需要同一资源的
  嵌套工作」这个结构的毛病。真要支持「父子总量封顶」，得让框架知道「这个工具会
  阻塞在嵌套 agent 上、别占名额」——那是工具声明级的新设计（类似 `:serial`），
  不是补丁。**没有真实需求前不做。**

**顺带**：有界平台池是给**真正干活**的工具封顶用的。delegate 这类只阻塞等网络的
工具，占一根平台线程停几秒到几分钟，本就不该走有界池——即使没有死锁，
「父子共用有界池」也不是想要的东西。

### 4.4 与 §1.3「cl-agent 双 impl」的对齐

cl-agent（Common Lisp，全面照抄 Spring AI）已有 `default` / `concurrent` 两个
ToolCallingManager 实现——本设计与之**完全对齐**：

| cl-agent | clj-agent v3 | 含义 |
|---|---|---|
| `default` | `SequentialToolCallingManager` | 全串行 |
| `concurrent` | `VirtualThreadToolCallingManager` | 并发（cl-agent 用 lparallel，我们用虚拟线程） |
| （cl-agent 没有） | `ThreadPoolToolCallingManager` | 真实线程池（我们的扩展） |

§1.3 当初指出「concurrent manager 有一半在管线程池生命周期——pool-size、双检锁懒
创建 lparallel kernel、幂等 shutdown」。**我们的虚拟线程没这个问题**（`delay`
+ `newVirtualThreadPerTaskExecutor`，无池可调）。而 `ThreadPoolToolCallingManager`
确实要管生命周期——但它是一个**可选 impl**，不是协议强加的负担。

### 4.5 kernel 上的 manager 引用

> **v2 修订（回应 Oracle I4）**，v3 沿用：以下是 `kernel.clj` 当前的真实签名：
>
> ```clojure
> ;; kernel.clj:51（现状）
> (defrecord Kernel [service filters tools tool-vars inline-handlers settings])
>
> ;; kernel.clj:80（现状）—— state-slots / eligibility-fn 都进 settings
> (defn build-kernel
>   [{:keys [service tools tool-vars filters settings state-slots eligibility-fn]
>     :or {tools [] filters [] settings {}}}]
>   ...)
> ```
>
> **`tool-manager` 加在哪**：作为新顶层字段（与 service/filters 同级，不走 settings），
> 因为它是行为注入而非配置数据。新增字段后 `Kernel` 变 7 字段：
>
> ```clojure
> (defrecord Kernel [service filters tools tool-vars inline-handlers settings tool-manager])
> ```

**注意**：core 不能依赖 client，故 `virtual-thread-tool-calling-manager` 等构造器
不能在 core 调用。**调用方显式传入 manager**（`build-kernel` 缺省为 nil，运行时
nil-check 回退到直调现有 `execute-batch` 函数）。

**react.clj 在调用点 nil-check（v3 形状）**：

```clojure
;; react.clj 调用点（run-tool-loop 内）
(let [tm (:tool-manager kernel)]
  (if tm
    (tool-calling-manager/execute-tool-calls tm kernel response opts)
    (execute-batch kernel calls cached-gate tctx records on-tool-result)))  ;; nil = 原路径
```

注意：nil-check 走原路径时仍需要 `response → calls` 抽取——这一行在 `run-tool-loop`
里早就做过了（`response/response-tool-calls`），保持原样；只有走 manager 时
让协议方法内部抽。**双路径形状一致**是 PR1 零行为变化的保证。

---

## 5. 为什么不长 `:backend`（v4 否决记录）

> **状态：❌ 已否决（2026-07-16，用户拍板）。**
> 本节取代 v1-v3 的 §5《deftool 扩展：`:backend` 元数据》与 §6《Transport 实现细节》。
> 那两节设计了 `:backend :local/:http/:mcp` 元数据、`invoke-backend` defmulti、
> HTTP/MCP transport 实现、`IMcpClient` 协议、kernel `:mcp-clients` 字段——
> **整条否决，不实施**。保留本节是为了记录否决理由，避免重新提案。

### 5.1 否决理由（核心）

> 本节是 **[`design-principles.md`](design-principles.md) §1《无真实需求不建》**
> 的第一个案例。该原则的四问判据在这里全部落在「假想」列——下文是逐条展开。

**工具怎么拿到结果，是工具函数体内部的事，框架不该知道。**

`deftool` 的职责是：向 LLM 描述**这个工具是什么**（名字、描述、参数 schema），
以及向框架描述**这个工具怎么被调度**（`:sensitive` 要审批、`:serial` 不能并行、
`:retry` 可重试、`:return-direct` 结果即答案）。

「这个工具是 HTTP 调用还是 MCP 调用还是本地计算」**不属于这两类中的任何一类**——
它既不影响 LLM 怎么用这个工具（LLM 根本不该知道），也不影响框架怎么调度它
（HTTP 工具和本地工具在 gate / 并行 / 重试 / writes 折叠上待遇完全相同）。
它只是函数体第一行写 `(http/post ...)` 还是 `(+ a b)` 的区别。

```clojure
;; v4 立场：这就是「HTTP 工具」的正确写法，框架无需任何扩展
(deftool search-web
  "搜索网页"
  [[query :string "查询词"]
   [limit :int "返回条数" :default 10]]
  {:retry {:max 3}}                       ;; 调度语义——框架该知道
  (-> (http/post "https://tools.internal/search"
                 {:body (json/encode {:query query :limit limit})})
      :body json/decode))                 ;; transport——框架不该知道

;; MCP 同理：函数体内调 mcp client，框架看到的仍然只是一个普通工具
(deftool fs-read
  "MCP 文件系统读"
  [[path :string "路径"]]
  (mcp/call-tool my-client "read_file" {:path path}))
```

### 5.2 被否决方案的具体代价

原 §5/§6 方案为了让框架知道 transport，要付出：

| 代价 | 说明 |
|---|---|
| `deftool` 宏复杂度 | opts 多 5 个字段（`:backend`/`:endpoint`/`:method`/`:server`/`:remote-name`），宏展开期要做两条严格模式检查（非 local 不许写 body、不许 `:context true`） |
| 新的公开 seam | `invoke-backend` defmulti 必须去 `^:private` 公开出去，供 client 模块注册方法——**永久 API 面**，以后想改就是 breaking |
| 跨模块耦合 | defmulti 在 core、`:http`/`:mcp` 方法在 client；core-only 用户写 `:backend :http` 会拿到运行期报错而非编译期错误 |
| kernel 加字段 | `:mcp-clients` 进 Kernel record（7 → 8 字段），且 §5.4 遗留一个**未解的**问题：defmulti 签名 `(fn [v args context])` 根本拿不到 mcp-clients，三个候选方案没一个干净 |
| 两条路径分裂 | inline tools（`:handler` maps，如 `delegate-tool`/`fanout-tool`/`search_tools`）绕过 `tool/invoke`，**永远无法声明 `:backend`**——同一个概念在两条工具路径上不一致 |
| 表达力反而更弱 | `:backend :http` 只能表达「POST 一个固定 endpoint，body 是 `{:args :context}`」这一种形状。真实 HTTP 工具要自定义 header / 路径参数 / 非 JSON body / 响应字段映射时，声明式配置立刻不够用，还得退回手写函数体 |

**换来的收益**：函数体里少写 5-10 行 HTTP 包装。

**这笔交易不划算**——用户在 `deftool` 内手写 transport 完全等价，而且更灵活
（想换 http client、加自定义鉴权、做响应转换，都是普通 Clojure 代码，
不需要框架开新的配置字段）。

### 5.3 与 Spring AI 的分歧（有意为之）

Spring AI 用 `ToolCallback.call(input, ctx)` 接口统一 local / HTTP / MCP 三种
transport，`:backend` 方案是它的对标物。**clj-agent 在这一点上有意不对齐**：

- Spring 需要这层接口，是因为 Java 里「一个函数」不是一等值——它必须用对象/接口
  包装可调用物，于是顺手让不同实现类承担不同 transport
- Clojure 里函数是一等值，`deftool` 生成的 var 本身就是那个「可调用物」。
  **函数体里想调什么就调什么**——不需要框架给 transport 开接口

**换句话说**：Spring 的 `ToolCallback` 多态在 clj-agent 里由**函数体本身**承担了。
再加一层 `:backend` defmulti，是把宿主语言已经免费提供的能力重新实现一遍。

### 5.4 什么情况下会重新考虑

> 按 [`design-principles.md`](design-principles.md) §1.3 落地约束：
> **已否决的方案重启时须重新设计，不得直接捡回本文档 v3 的 §5/§6。**

**不是「出现 HTTP/MCP 工具需求」**——那个需求手写函数体就解决了，
不构成重开理由。真正的重开条件是出现**框架必须知道 transport 才能做的事**，
例如：

- 需要按 transport 做**统一策略**：如「所有 remote 工具自动加 30s 超时 +
  熔断」，而这个策略无法由用户在函数体内自行表达（存疑——`:tool` filter
  按 `:tags` 过滤即可等价实现，仍不构成理由）
- 需要**从远端动态发现工具**：如 MCP `listTools` 在运行期返回工具列表，
  框架要凭 schema 直接生成可调用工具——**此时没有函数体可写**，
  这是目前唯一看得见的、真正需要框架介入的场景

> **注意第二条**：即使这个场景出现，正确形状也**未必是 `deftool :backend`**——
> 更可能是「MCP 工具发现器把远端 schema 转成 inline handler 注册进 kernel」
> （复用 `search_tools` 同款的 inline-handler 路径），仍然不碰 `deftool` 宏。
> **重开时请重新设计，不要直接捡回 v3 的 §5/§6。**

---

## 6.（已删除）Transport 实现细节

随 §5 `:backend` 否决一并移除（原内容：HTTP transport 契约、MCP `IMcpClient`
协议、错误归一化、kernel `:mcp-clients` 注册）。**章节号保留不复用**，避免
打乱 §7-§13 的既有交叉引用。

---

## 7. 接入点（react.clj）

`run-tool-loop` 的关键调用从直调函数改为经 manager（v3 形状：传 `response`）：

```clojure
;; react.clj:331（现状）
(let [{:keys [messages records context errors]}
      (execute-batch kernel calls cached-gate tctx records on-tool-result)
  ...]

;; v3 改造后
(let [tm (:tool-manager kernel)]  ;; nil = 走原路径；非 nil = 走协议方法
  (if tm
    (let [{:keys [messages records context errors]}
          (tool-calling-manager/execute-tool-calls
            tm kernel response                          ;; v3: 传 response，不是 tool-calls
            {:gate cached-gate :tool-context tctx
             :records records :on-tool-result on-tool-result})]
      ...)
    (let [{:keys [messages records context errors]}
          (execute-batch kernel calls cached-gate tctx records on-tool-result)]
      ...)))
```

注意：现状 `run-tool-loop` 在调 `execute-batch` 之前已经抽过 `calls`
（`response/response-tool-calls`）——nil 分支继续用已抽好的 `calls`，
manager 分支让协议方法内部再抽一次。**双路径形状一致**是 PR1 零行为变化的保证。

**`resume-env`（`react.clj:451`）、`resume-approval`（`react.clj:552`）同步改造**。

> **`run-tools`（`react.clj:206`）的迁移时机（沿用 v2 决定）**：
> PR1 **保持原样**（继续调 5-arity `execute-batch` 函数）；PR2 迁移到 manager；
> PR3+ 才考虑完全移除 5-arity 函数。与 §9.5 的 phased 策略一致。

### 7.1 不变的部分

下列代码**完全不动**：
- `gate` 评估 + `paused-call` 检测（`run-tool-loop:305-322`）
- `eligibility-fn` 检查（`run-tool-loop:296`）
- `return-direct-batch?` 判定 + `direct-response` 收尾（`run-tool-loop:344`）
- `env-pause` 屏障路由（`run-tool-loop:333-338`）
- `invoke-with-retry`（被 manager 内部的 `execute-single` helper 调用，不变）
- `heal-dangling-tool-calls!`、`persist-direct-messages!`

这些是 `run-tool-loop` 的循环控制职责，**不属于 manager**。

### 7.2 resume / loop-state 的兼容性（沿用 v2）

`execute-batch` 的返回值（`ToolExecutionResult` map）流入 `env-pause` 的 `loop-state`，
而 `loop-state` 经 `pause-store` 持久化用于跨进程 resume。

**PR1 的保证**：协议方法的返回值形状与现有 `execute-batch` 函数**逐键相同**
（`:messages :records :context :errors`）——故 `env-pause` / `resume-env` /
`resume-approval` 完全无需修改，已持久化的 pause 快照也能正常恢复。

**契约链接**：本节依赖 §9.2 的「键名冻结」约束。任何未来对 `ToolExecutionResult`
形状的修改都必须考虑向后兼容已持久化的 pause 快照。

---

## 8. 迁移路径

### PR1：抽出 ToolCallingManager + 多 impl（行为零变化）

**目标**：协议方法 `execute-tool-calls` 落地，至少两个 impl（VT + Sequential）。
**所有现有测试原样通过**（v3 PR1 仍保持零行为变化承诺）。

**关键策略（v3）**：
- 保留现有 5-arity `execute-batch` 函数作为**唯一实现**（与 v2 一致）
- `VirtualThreadToolCallingManager` 内部委托给原函数
- `SequentialToolCallingManager` 是**新增的第二个 impl**，证明多 impl 真能换
- `run-tools` 和现有测试代码零修改（沿用 v2 决定）
- `^:deprecated` 标记推迟到 PR2

**改动文件**：
- `core/src/im/ttalk/agent/tool_calling_manager.clj`（已存在，**改名方法**：
  `execute-batch` → `execute-tool-calls`，签名加 `response`）
- `client/src/im/ttalk/agent/react.clj`：
  - 重命名 `DefaultToolCallingManager` → `VirtualThreadToolCallingManager`
  - 加 `SequentialToolCallingManager`（新第二个 impl）
  - 三个调用点（run-tool-loop / resume-env / resume-approval）改成传 `response`
- `core/src/im/ttalk/agent/kernel.clj`（不变，已是 7 字段）

**验收**：
- 全套 tests 通过（基线 293/1198），**零代码修改**
- 新增测试：
  - mock manager 注入（验证 `execute-tool-calls` 签名正确）
  - **边界验证测试**（沿用 v2 M4）：注入 mock manager，验证 `:tool` filter 仍触发、
    `:serial` 仍让整批退化、`:writes` 仍在屏障折叠
  - **多 impl 切换测试**（v3 新增）：注入 `SequentialToolCallingManager`，验证
    调用顺序严格串行（即使工具没声明 `:serial`）；对比 `VirtualThreadToolCallingManager`
    在多工具批时可观察并发

### PR2 / PR3：❌ 已否决（2026-07-16）

> **PR2（`deftool :backend` 扩展）与 PR3（MCP backend）整条否决，不会实施。**
> 理由见 §5——工具的 transport 是函数体内部实现，框架不该知道。
> 此前（2026-07-15）这两个 PR 标记为「⏸️ 搁置待真实需求」，**v4 改为否决**：
> 差别是「搁置」意味着有 HTTP/MCP 需求时就照图施工，而「否决」意味着
> **即使出现 HTTP/MCP 需求，也不按这个设计做**（手写函数体即可，§5.1）。
>
> §5.4 记录了唯一可能重开的场景（远端动态工具发现），且明确指出
> **届时也应重新设计，不要捡回原方案**。

**故本设计的实施范围到 PR1 为止**——manager 协议 + 多 impl，已交付。

---

## 9. 未决问题

### 9.1 invoke-tool 也升格为 manager 方法吗？（v3 定论）

**不升格。** `kernel/invoke-tool` 是单工具执行原语，签名 `(invoke-tool kernel fn-key args ctx)`，
被多处直调（kernel 是公开 API）。

v3 后 `execute-single` 是**每个 manager record 的内部 helper**（不是协议方法），
与 `kernel/invoke-tool` 的关系：
- `execute-single` helper 在 record 内部，可能调 `kernel/invoke-tool`（现状如此）
- `kernel/invoke-tool` 是更低一层的公开 API（不经 manager）

**两者不冲突**：协议只有一个方法 `execute-tool-calls`，`execute-single` 是它的
内部 building block，`kernel/invoke-tool` 是更底层的 kernel 原语。

### 9.2 ToolExecutionResult 是 protocol 还是 plain map？

Spring 用接口（`ToolExecutionResult`）。clj-agent 倾向 plain map（与
`:writes` / `:records` / `:errors` 等现有约定一致）。

**首版用 plain map**。若未来需要行为（如 lazy 计算），再升格 record。

> **v2 重要约束（回应 Oracle I2）**：`ToolExecutionResult` 的 map 形状
> （`{:messages :records :context :errors}`）**不只是返回值，是序列化格式**——
> `env-pause` 把它部分塞进 `loop-state`（`react.clj:237-250`），`loop-state`
> 又被 `pause-store`（SQLite）持久化用于跨进程 resume。**键名冻结**：
> - **可以**：增加新键（向前兼容）
> - **不可以**：重命名 / 移除现有键（破坏已持久化的 pause 快照）
>
> PR1 验收测试应包含一条「旧版 pause 快照在新版能 resume」的回归（如有 SQLite fixture）。

### 9.3 manager 持有什么状态？

`VirtualThreadToolCallingManager` 与 `SequentialToolCallingManager` 都是无状态空 record；
`ThreadPoolToolCallingManager` 持有它自己的池（策略与资源打包，见 §2.2），
实现 `java.io.Closeable`，生命周期归持有者——**这正是「策略与其资源打包」的实证**：
换引擎时资源跟着换，`:settings` 单键做不到。

**除此之外不加**——避免无需求先加复杂度。

> **v4 修订**：原列出的 `:mcp-clients`（MCP 分派时查）与 `:http-client`
> （HTTP transport 共享连接池）**随 `:backend` 否决一并移除**——manager 不碰
> transport，这两个字段没有归属它的理由。`:exception-processor`（错误策略可注入）
> 也不加：错误策略已有更好落点（`:tool` filter 链 + `:writes` 屏障处的三分类路由，
> §3.1），这是 §1.3 的原始判断，v4 仍然认同。

### 9.4 命名：ToolCallingManager 还是 ToolManager？

Spring 用 `ToolCallingManager`。clj-agent 已有 `kernel` 概念（中央编排），
再加 `ToolCallingManager` 可能在术语上撞。

**首版用 `ToolCallingManager`**——与 Spring 对齐，迁移期文档对照方便。
未来若有歧义再改。

### 9.5 execute-batch 的 arity 与 deprecation 路径（v3 修订）

**v3 形状澄清**：
- 协议方法 `execute-tool-calls` 用 opts map：`(execute-tool-calls manager kernel response opts)`
- 内部 helper `execute-batch`（v3 退回成 record 私有 fn）保持现状 5-arity：`(execute-batch kernel tool-calls gate tool-context init-records on-tool-result)`
- 现有公开 `react/exeute-batch` 函数（5-arity）保持不动，作为 nil-check fallback 路径

**v3 定论（沿用 v2 #10 决策）**：
- PR1：`react/execute-batch` 5-arity 函数**完全不动**；`VirtualThreadToolCallingManager/execute-tool-calls` 内部抽 tool_calls 后委托给原函数；`SequentialToolCallingManager/execute-tool-calls` 走自己的顺序路径。`run-tools` 调用方零修改。

**这样 PR1 的"零变化"承诺才是可证伪的**——任何测试失败都说明契约真的变了。

> **v4 修订**：原计划的废弃路径挂在 PR2（加 `^:deprecated`）/ PR3+（移除 5-arity）
> 上，**这两个 PR 已否决（§8），路径随之落空**。现状定论：
> **`react/execute-batch` 5-arity 函数长期保留，不标 deprecated、不移除**——
> 它同时是 nil-manager 的 fallback 路径和 VT manager 的内部委托目标，
> 是活代码而非过渡遗留。若未来真要收敛这个双入口，需要单独立项。

---

## 10. 与现有文档的同步

- [x] `docs/advisor-alignment-design.md` §1.3：末尾已加「⚠️ 已修订」block
      （v4 已按新理由重写——只剩「多 impl 执行策略可注入」一条腿）
- [x] `README.md` / `modules/clj-agent-core/README.md`：**v4 改为无需改动**——
      原计划「在 deftool 段加 `:backend` 说明」随否决取消。`deftool` 文档保持现状
- [ ] `docs/filter-chain-design.md` §0 的架构图：加 ToolCallingManager 层（未做）
- [x] `modules/clj-agent-client/README.md`：已加《工具执行引擎》段（三引擎对照表 + 有界池生命周期与父/子共用的自锁警告）

---

## 11. 决策记录（截至本文档 v5 定稿）

> **v4 作废声明**：#2 / #3 / #6 / #8 / #9 / #11 / #12 全部是 `:backend` 方案的
> 内部决策，**随 §5 否决一并作废**——保留在表里只为存档，**不代表现行立场**。
> 现行的 `:backend` 立场是**唯一一条**：#19「不长这个抽象」。

| # | 决策 | 选择 | 理由 |
|---|---|---|---|
| 1 | manager 协议 vs 仅扩展 deftool | ~~**协议 + 扩展**~~ → **v4：只有协议** | v4 否决 deftool 扩展（§5）；manager 协议本身保留 |
| 2 | ~~`:backend` 配置平铺 vs 嵌套~~ | ❌ **v4 作废** | 方案已否决，无配置可谈 |
| 3 | ~~remote backend 是否允许 body~~ | ❌ **v4 作废** | 方案已否决；v4 立场恰恰相反：body 就是 transport 该待的地方 |
| 4 | ToolCallingManager 形态 | **protocol + record** | 可 mock、可换实现、与 Spring 对齐 |
| 5 | ToolExecutionResult 形态 | **plain map**（键名冻结） | 与 :writes/:records 现有约定一致；持久化兼容性要求键稳定 |
| 6 | ~~MCP client 注册位置~~ | ❌ **v4 作废** | 方案已否决；kernel 不加 `:mcp-clients` 字段，保持 7 字段 |
| 7 | writes / records 是否进 ToolExecutionResult | **保留** | clj-agent 特有，强行对齐 Spring 会丢功能 |
| 8 | ~~HTTP transport 模块归属~~ | ❌ **v4 作废** | 方案已否决；框架无 HTTP transport 代码 |
| 9 | ~~`invoke-backend` defmulti 归属（v2）~~ | ❌ **v4 作废** | 方案已否决；`tool/invoke` 不加 dispatch，保持直接 `var-get` 调用 |
| 10 | PR1 是否改 execute-batch 签名（v2） | **不改**（PR1 保持 5-arity 函数；protocol 方法内部委托） | 真正的零行为变化。**v4**：原「breaking 推到 PR3+」随 PR2/PR3 否决落空——5-arity 函数长期保留（§9.5） |
| 11 | ~~inline tools 是否支持 :backend（v2）~~ | ❌ **v4 作废** | 方案已否决；「两条工具路径对 `:backend` 不一致」这个尴尬本身也是 v4 否决理由之一（§5.2） |
| 12 | ~~`:context true` + remote backend（v2）~~ | ❌ **v4 作废** | 方案已否决；`:context` 与任何工具都不冲突（工具永远有 fn 体） |
| 13 | v1/v2 manager 协议的诚实定位（v2） | 承认较薄（测试 seam + headroom） | **v3 已修订**——见 #14、#15，价值已通过多 impl 真实兑现 |
| 14 | 协议方法签名（v3 新增） | **`execute-tool-calls [this kernel response opts]`** | Spring 对齐；内部抽 tool_calls；调用方不再手动抽 |
| 15 | `execute-batch` / `execute-single` 归属（v3 新增） | **每个 record 的内部 helper**（不是协议方法） | 协议只有一个方法；多 impl 通过不同 record 实现，不用多协议方法 |
| 16 | 多 impl 策略（v3 新增） | **VT（默认）+ Sequential + ThreadPool** | 对齐 cl-agent 双 impl；§1.3「executor 可注入」诉求完全兑现 |
| 17 | 多 impl 是否进 PR1（v3 新增） | **VT + Sequential 进 PR1**；~~ThreadPool 可选/后续~~ → **v5：ThreadPool 已实施** | 至少两个 impl 才能证明协议真能换策略；ThreadPool 是隔离诉求的正主，v5 补齐（§4.3） |
| 18 | PR2/PR3 是否立项（2026-07-15） | ~~⏸️ 搁置，待真实需求触发~~ → **v4：❌ 否决** | 被 #19 取代 |
| 19 | **`deftool` 是否长 `:backend`（v4，2026-07-16，用户拍板）** | **❌ 不长——整条否决** | 工具走 HTTP 还是 MCP 是**函数体内部实现**，既不影响 LLM 怎么用它，也不影响框架怎么调度它，框架不该知道。Clojure 里函数是一等值，Spring `ToolCallback` 的 transport 多态在这里由函数体本身承担。代价（宏复杂度、公开 defmulti 永久 API 面、跨模块耦合、kernel 加字段、inline-tools 路径分裂、声明式配置表达力反而弱于手写）远超收益（省 5-10 行 HTTP 包装）。详见 §5 |
| 20 | ~~manager 在 #19 之后是否回滚（v4）~~ | ~~不回滚，但降低宣称~~ → **v5 更正** | v4 认为「两条腿断一条，剩下的腿细，故对 §1.3 让步」。**v5 推翻**：见 #21 |
| 23 | **「无真实需求不建」的地位（v5 三次修订，用户拍板）** | **提为硬约束，并抽到项目级 `docs/design-principles.md` §1** | 它本是 §1.3 的「立项判据」，却在本文档里被反复重新发现（毙 `:backend`、毙 PR2/PR3、拆 `*active-pools*`），且**作者一边援引它一边违反它**。散在案例里 = 下次开新文档还得重新推导。抽成项目级原则并配四问判据 + 落地约束 + 案例法（含 manager 自己险些踩中），让它可援引、可对照、可自查。本文 §0.5 退为指针，判例现场仍在本文（§5 / §8 / §4.3.1 / §2） |
| 22 | **有界池嵌套自锁怎么处置（v5 二次修订，用户拍板）** | **不设防——立不变量：「一个引擎属于一个 kernel，不跨 delegate 边界」** | 曾实现「动态变量 `*active-pools*` + 命中退化内联」的保护，**已删除**。理由：(a) 子 agent 本就不共享引擎（无继承路径，`delegate.clj:87` + `do-run` 全新造 kernel），要共享得用户亲手塞——是踩坑不是漏洞；(b) 跨 delegate 的情形线程局部标记根本测不到，要修得改 subagent/manager 传播上下文，等于花力气让违反不变量的用法能跑，且隔离仍是软的；(c) 同线程情形测得到但无真实需求（`run-tools` 走全局 VT 不碰池）。**为假想需求建机器 = §5 毙 `:backend` 的同一条标准，不能只对别人用**。详见 §4.3.1 |
| 21 | **manager 的定位（v5，2026-07-16 用户拍板）** | **执行引擎：隔离边界 + 线程模型 + 调度策略，这一条腿本身足够** | `:backend` 与 manager 本职**从来无关**（那是"执行的是什么"，manager 管"怎么执行"），故 #19 否决**不影响 manager 分毫**——v4 的「腿变细」是误判。manager 与 `:settings` 注入 executor 的差异是**能力级不是偏好级**：(a) 调度决策（`react.clj:167`）硬编码，`Executor` 接口够不着；(b) Sequential 路径不构造 Future（`react.clj:207`），same-thread executor 模拟不出；(c) 池生命周期须与策略打包。**§1.3 判断失准处**：把落点写成「executor 可注入」，低估了要换的是引擎不是线程池。详见 §2 |

---

## 12. Review 反馈处置索引（v2）

Oracle review（`bg_41b9aad5`）共提出 3 blocking + 5 important + 4 minor + 4 missing。
处置分布：

> **v4 修订**：标 ❌ 的 6 条处置的都是 `:backend` 方案的问题，**随 §5 否决一并消失**
> （原处置位置 §5.2-§5.7 / §6.1 / §6.4 已删除，此处不再指向）。
> **值得记一笔**：B1 / B3 / I5 都是「`:backend` 方案自身长得别扭」的信号——
> defmulti 模块归属反复摇摆、inline tools 天生接不上、mcp-clients 拿不到且三个
> 候选方案没一个干净。**v4 的否决某种程度上是这些 review 信号的最终结算**。

| 编号 | 类型 | 处置位置 |
|---|---|---|
| B1（defmulti 归属矛盾） | blocking | ❌ v4 消失（`:backend` 已否决，无 defmulti） |
| B2（PR1 零变化矛盾） | blocking | §8 PR1、§9.5、§11 #10 |
| B3（inline tools 盲点） | blocking | ❌ v4 消失（反过来成了 §5.2 的否决理由之一） |
| I1（§3 不诚实） | important | §3 表格 + §2 诚实声明（**v4 进一步让步**，见 §2.2） |
| I2（ToolExecutionResult 序列化） | important | §9.2 稳定性约束 |
| I3（`:context` + remote） | important | ❌ v4 消失（无 remote backend 概念） |
| I4（kernel 签名不准） | important | §4.5 真实签名 |
| I5（mcp-clients 访问） | important | ❌ v4 消失（kernel 不加 `:mcp-clients`） |
| M1（§5.2 表格层次混淆） | minor | ❌ v4 消失（表格已删） |
| M2（execute-streaming 不靠谱） | minor | §4.3 删除（v3 进一步：execute-single 也退回内部 helper） |
| M3（run-tools 措辞） | minor | §7 迁移时机说明 |
| M4（PR1 缺边界测试） | minor | §8 PR1 验收加 3 条 |
| C1（ToolSearch × backend） | missing | ❌ v4 消失（schema 与 backend 的耦合点不存在了） |
| C2（LLM 流式 scope） | missing | §4.3 显式声明 |
| C3（subagent 委托） | missing | ❌ v4 消失（同 B3） |
| C4（resume/loop-state） | missing | §7.2（新增） |

## 13. v3 用户收敛修订索引（新增）

v3 是基于用户进一步收敛协议形状的修订，与 v2 的差异：

| 维度 | v2 | v3 |
|---|---|---|
| 协议方法 | `execute-batch [this kernel tool-calls opts]` | `execute-tool-calls [this kernel response opts]`（Spring 对齐） |
| 协议方法数 | 1（execute-batch） | 1（execute-tool-calls，**改名 + 签名升级**） |
| `execute-batch` / `execute-single` | 协议方法的命名 / 未来扩展点 | **退回成 record 内部 helper** |
| 多 impl | 隐含（"未来可换"）但未落地 | **核心特性**：VT / Sequential / ThreadPool |
| §1.3 「executor 可注入」诉求 | 部分兑现 | **完全兑现**（多 impl = 多 executor 策略） |
| §3 诚实回答 | 承认价值较薄 | **价值已真实兑现**（多 impl 不是 headroom） |
| PR1 已交付代码 | v2 形状（execute-batch 协议方法） | **需要返工**为 v3 形状 |

**v3 改动散落各节**：状态头、§0、§3 表格末行、§4 整章重写、§7 接入点、§8 PR1 scope、
§9.1 execute-single 定论、§11 决策记录加 #14-#17。

## 14. v4 修订索引（`:backend` 否决）

用户拍板（2026-07-16）：「deftool 在定义 tool 时候，不需要考虑 backend，
因为我们不管是 MCP，还是 HTTP，都是 tools 内部逻辑」。

| 维度 | v3 | v4 |
|---|---|---|
| `deftool :backend` | 核心扩展（PR2/PR3） | **❌ 整条否决**，不实施 |
| transport 分派（`invoke-backend` defmulti） | core `tool.clj` 公开 defmulti | **不存在**——`tool/invoke` 保持直接 `var-get` |
| HTTP / MCP 工具怎么写 | 声明式 `:backend :http` + endpoint 配置 | **函数体里自己调**（普通 Clojure 代码） |
| kernel 字段数 | PR3 时 8（加 `:mcp-clients`） | **保持 7** |
| 立项理由 | 两条腿：多 transport + 多 impl | **一条腿：执行引擎（隔离 + 线程模型）**——v5 更正：这条腿不细，且从不依赖 transport |
| §3 对「`:settings` 注入更简单」的回应 | 「换不了 transport」——理由失效 | **换硬理由**：`:settings` 换线程池，manager 换引擎（v4 曾误让步，v5 已更正，§2.2） |
| PR1 | 已交付 | **不回滚**（已验证零行为变化；回滚成本 > 收益） |
| `react/execute-batch` 5-arity | PR2 标 deprecated，PR3+ 移除 | **长期保留**（废弃路径随 PR2/PR3 落空，§9.5） |

**v4 改动位置**：状态头、§0（删 backend 段）、§2 整章重写、§3 表格末两行、
§5 整章替换为否决记录、§6 删除（留墓碑）、§8 PR2/PR3 改否决、§9.3、§9.5、
§10 同步清单、§11 决策记录（#2/#3/#6/#8/#9/#11/#12 作废，新增 #19/#20）、§12 索引。

## 15. v5 修订索引（manager 定位校准）

用户拍板（2026-07-16）：「ToolCallingManager 是我们的执行引擎，可以做隔离，
使用不同线程模型就可以了」。

**v5 分两步**：先校准定位与论证（不改代码），再按定位把 `ThreadPoolToolCallingManager`
补齐——**隔离是 manager 的本职，而这个引擎正是隔离的正主，缺了它定位就只是嘴上说说**。
`:backend` 仍否决（#19 不变），协议签名 / 契约 / 既有两个引擎行为全部不变。

| 维度 | v4 | v5 |
|---|---|---|
| manager 定位 | 「多 impl 执行策略可注入」（模糊） | **执行引擎**：线程模型 + 隔离边界 + 调度策略 |
| `:backend` 否决对 manager 的影响 | 「立项少一条腿，理由变细」 | **零影响**——`:backend` 管"执行的是什么"，manager 管"怎么执行"，两件事从不相干 |
| vs `:settings` 注入 executor | 「偏好级差异，§1.3 批评基本成立」 | **能力级差异，§1.3 批评不成立**（§2.2 三条代码证据） |
| 隔离 | 未提 | **§2.3 新增**：`react.clj` 的 VT executor 是进程全局 `def`，无舱壁 / 无限流 / 无可关停边界 |
| `ThreadPoolToolCallingManager` | 「可选 / 后续」，只在文档里 | **✅ 已实施**（§4.3）：有界 daemon 池 + `Closeable` 生命周期；`:serial` / writes 折叠 / 返回形状与另两个引擎实证一致。嵌套自锁**不设防，改立不变量**（§4.3.1） |
| 三引擎的公共骨架 | 无（VT 与 Sequential 各自复制 map+reduce） | **抽出 `execute-batch-via`**：gate 预判 / 屏障折叠 / 结果排序一处实现，引擎只挑 executor（nil = 内联）。契约不再靠三份拷贝各自维持 |

**v5 犯错复盘**：v4 因 `:backend` 被否决而过度收缩，未经核实就默认
「manager ≈ executor 容器」，据此对 §1.3 让步。**代码不支持这个默认**——
Sequential 路径根本不碰 Future，调度决策也不在 executor 手上。
**教训**：一条论据倒了，要重新称剩下的论据有多重，而不是假定它跟着变轻。

**v5 验收**：全套 300 tests / 1227 assertions 通过（基线 296/1211，+4 tests 全部是
新引擎的机制断言：舱壁上限、独占命名线程、契约对等、生命周期）。

**v5 三次修订（用户拍板）**：把「无真实需求不建」从 §5 的个案理由提为硬约束，
并**抽到项目级 [`docs/design-principles.md`](design-principles.md) §1**（连同
`streaming-async-design.md` §0.5 的《框架无关》一并收录为 §2）。
配四问判据、落地约束、案例法——判例全部出自本文档：`:backend` / PR2·PR3 /
`*active-pools*` 三次援引，外加 **`ToolCallingManager` 自己险些踩中**（立项两条腿里
「多 transport」那条是假想的，且当时看起来比真实那条更有说服力）。
本文 §0.5 退为指针，不重述原则；判例现场仍在本文（§5 / §8 / §4.3.1 / §2）。

**v5 二次修订（用户拍板）**：初版为有界池加的「同线程自锁保护」**已删除**，
改立不变量（§4.3.1、决策 #22）。**复盘**：我用「子 agent 共用有界池会死锁」论证
这个保护，但子 agent 根本不共享引擎——没有继承路径，要共享得用户亲手塞回去。
主场景被不变量划走后，剩下的是假想需求；而我刚在 §5 用「没有真实需求就不建」
毙掉 `:backend`，转头自己建了一个。**标准要对自己也用。**

---

## 相关文档

- [`design-principles.md`](design-principles.md) §1《无真实需求不建》——本文遵循的
  硬约束；本文是它的判例现场（§5 / §8 / §4.3.1 / §2）
- `advisor-alignment-design.md` §1.3（被本文档修订的旧立场；也是 §1 原则的出处）
- `filter-chain-design.md`（`:tool` filter 链契约——manager 不与之重叠）
- `agent-loop-concurrency-design.md` §9（`:writes` / `:state-slots` MapReduce 契约——
  manager 内部仍走这套）
- `hitl-timeline-design.md`（gate / 暂停 / resume 与 manager 的边界分工）

## 参考

- Spring AI 2.0 `ToolCallingManager` 源码：
  [`spring-ai-model/.../tool/ToolCallingManager.java`](https://github.com/spring-projects/spring-ai/blob/main/spring-ai-model/src/main/java/org/springframework/ai/model/tool/ToolCallingManager.java)
- Spring AI 2.0 `DefaultToolCallingManager`：
  [`spring-ai-model/.../tool/DefaultToolCallingManager.java`](https://github.com/spring-projects/spring-ai/blob/main/spring-ai-model/src/main/java/org/springframework/ai/model/tool/DefaultToolCallingManager.java)
- Spring Blog：[Spring AI 2.0.0 GA Available Now](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)
