# 工具超时设计：借鉴 beamai ToolCallingManager，但不照搬

> **状态：✅ 已实施（2026-07-16 同日拍板并落地，P0 + P1 全部完成）。**
>
> 起因：beamai 的 `ToolCallingManager` 有一套完整的三层超时处理，问「我们能否也对
> tool 超时执行做处理」。本文的结论：**能，而且必须做——但要做的不是 beamai 那三层。**
>
> 先读 [`design-principles.md`](design-principles.md)（§1 无真实需求不建）。
> 执行引擎本身见 [`tool-calling-manager-design.md`](tool-calling-manager-design.md)。

---

## 0. TL;DR

beamai 的三层超时**全部建立在 `exit(Pid, kill)` 这一个原语上**——无条件、抢占式、
立即生效。**JVM 没有这个原语**（`Thread.stop` 已在 JDK 20+ 移除，调用即抛
`UnsupportedOperationException`）。只剩协作式中断。

**照搬三层，我们买到的是文档，不是强制力。** 一个 `while(true){}` 的工具，在 beamai
里 30 秒后必死；在 JVM 里，无论套几层超时，它都会一直跑到进程退出。

所以本文把 beamai 的方案拆成**可移植**与**不可移植**两半：

| beamai 的做法 | 能否移植 | 结论 |
|---|---|---|
| 超时**优先级链**：工具声明 > manager 缺省 > 内置缺省 | ✅ 纯策略，与进程模型无关 | **吸收**（§3.2） |
| 超时**归类 transient** → 声明 `:retry` 的工具自动重试 | ✅ 我们已有等价分类体系 | **吸收**（已具备，§1.3） |
| 层 1 每工具超时（spawn + kill） | ⚠️ 语义降级为「放弃等待」 | **吸收，但改语义并如实写明**（§2） |
| 层 2 并发收集截止（gather deadline） | ❌ 理由是 BEAM 特有 | **不建**（§3.4） |
| 层 3 批级隔离兜底（batch worker） | ❌ 理由是 BEAM 特有（link 传播崩溃） | **不建**（§3.4） |

**同时，分析暴露了两个我们自己的问题，与 beamai 无关，独立成立：**

1. **`:timeout` 是个死选项**（真 bug）。`deftool` 接受它、不报错、然后**完全忽略**。
   用户写 `{:timeout 5000}` 得到的是零。见 §1.2。
2. **`timeout-filter` 会悄悄毁掉 manager 的线程模型**（真 bug）。它用 `clojure.core/future`
   ＝ send-off 池 ＝ **平台线程**。于是 VT 引擎的工具其实跑在平台线程上。见 §4。

**建议落地：P0 修死选项 + P1 让 `timeout-filter` 读 `:tool/timeout` 并跑在虚拟线程上。**
两项都是**修既有承诺**，不是长新抽象——不触发 §1 的立项判据。

---

## 1. 现状盘点（证据）

### 1.1 唯一的时间防线是一个 opt-in filter

`advisor.clj:243` `timeout-filter` —— future + `deref` 带超时 + `future-cancel`，
超时归类 `:transient`、不带 `:writes`（事务性）。**这个 filter 是对的**：位置对
（`:tool` 洋葱链包住每次工具调用）、形状对（`{:result :error}`，经 `kernel.clj:226`
映射为 `:value`）、分类对（transient → 可被 `:retry` 重试）。

它是**唯一**的时间防线，而且是 opt-in 的：不挂就没有。

### 1.2 `deftool :timeout` 是死选项（真 bug）

```
tool.clj:210   (some #{:sensitive :timeout :category ...} (keys (first body)))
```

`:timeout` 出现在 opts 白名单里——**仅此一处**。它决定「首个 map 算不算 opts」，
然后就没有然后了：

- 生成的 var 元数据里**没有** `:tool/timeout`（`:tool/serial`、`:tool/retry`、
  `:tool/return-direct` 都有，独缺它）
- 全代码库 grep `tool/timeout` → **零命中**
- 没有 `timeout-spec` 读取函数（`retry-spec`、`serial-tool?` 都有）

**后果**：用户写 `(deftool foo "..." [] {:timeout 5000} ...)`，编译通过、无警告、
运行时**完全没有超时**。这是最坏的一种失败——**沉默的谎言**。白名单里列着它，
等于 API 文档承诺了它。

**已实测证实**（2026-07-16，非推断）：

```clojure
(tool/deftool slow-tool "睡 3 秒" [] {:timeout 500}
  (Thread/sleep 3000) "done")

(tool/invoke #'slow-tool {})
;; 声明 500ms 超时，实际耗时 3000ms，结果 {:success true, :result "done"}
;;                    ^^^^^^ 跑满全程，:success true —— 超时零效果
```

var 元数据实际产出（注意 `:tool/timeout` **不在其中**）：

```
:tool/category :tool/context :tool/function :tool/params :tool/retry
:tool/return-direct :tool/schema :tool/sensitive :tool/serial :tool/tags
```

对照 beamai：`tool_spec.timeout` 是**强制执行**的（`beamai_tool.erl:186` `resolve_timeout/2`）。
我们抄了字段名，没抄行为。

### 1.3 已经具备的（不用建）

- **错误分类体系**：`:transient | :semantic | :environment`，与 beamai 的
  `beamai_tool_error:classify/1` 同构。超时归 `:transient` 已是 `timeout-filter` 的现状行为。
- **重试**：`react.clj:75` `invoke-with-retry` 包在 `kernel/invoke-tool` **外面**，
  故 filter 链（含超时）在重试循环**内部**——超时 → transient → 重试。
  与 beamai 的 `invoke_with_retry/6` 语义一致。**这条链路已经是通的**，
  只要超时能真的发生。

### 1.4 真正的缺口：`(.get f)` 无限阻塞

```
react.clj:160   (mapv (fn [^Future f] (.get f)) futs)
```

无超时参数。一个卡死的工具 → 整个 agent 循环**永久冻结**，无外部逃生口。

且 `execute-batch-via`（`react.clj:205`）在三种情况退化为**内联**执行：

```clojure
(if (or (nil? executor) serial? (<= (count tool-calls) 1))
  (run-inline ...)      ; ← 连 Future 都没有，从外部无法施加任何超时
  (run-on-executor ...))
```

**注意 `(<= (count tool-calls) 1)`**：单工具调用走内联——而单工具调用是**最常见的
情形**。所以「批级超时」这类方案会恰好在绝大多数调用上失效。这一条直接否掉了
「在收集点加超时」的思路（§3.4 方案 B）。

---

## 2. 核心差异：`kill` vs `abandon`（本文的重点）

这一节是整份文档的支点。**如果只读一节，读这节。**

### 2.1 beamai 有的原语，我们没有

```erlang
%% beamai_tool.erl:427 call_handler/4
after Timeout ->
    exit(Pid, kill),          %% ← 无条件。进程立即死亡，内存回收，无需配合。
    {error, #{class => timeout, reason => tool_timeout, ...}}
```

`exit(Pid, kill)` 是**不可捕获、不可延迟、不需要目标配合**的。beamai 的注释说得很准：

> **为什么必须起进程**：BEAM 无法中断同进程内的内联调用——要让 `timeout' 声明
> 真正可执行，handler 就得跑在一个可以 kill 的进程里。

关键词是**「真正可执行」**（enforceable）。BEAM 的 timeout 声明是**强制力**。

### 2.2 JVM 只有协作式中断

| 手段 | 状态 |
|---|---|
| `Thread.stop()` | **JDK 20+ 直接抛 `UnsupportedOperationException`**。不存在了。 |
| `Future.cancel(true)` → `Thread.interrupt()` | 只设一个**标志位**。目标不看，就没事发生。 |

能被 interrupt 打断的：`Thread/sleep`、`Object.wait`、`BlockingQueue`、
`InterruptibleChannel`、`lockInterruptibly`。

**打不断的**：

- **CPU 密集循环** —— 正则回溯、大 JSON 解析、死循环。**只要代码自己不检查
  中断标志，interrupt 就完全无效**——这是残余风险的主体。
- `synchronized` 块上的等待、native/JNI 调用
- 吞掉 `InterruptedException` 的代码；工具自己 spawn 的**平台**线程

> ### ⚠️ 已修订（2026-07-16，MiniMax live 实测推翻）
>
> **本节原先还列了一条**：「`InputStream.read()` 读普通 socket——绝大多数 HTTP
> 客户端就长这样。这条最要命：『调用外部 API 的工具』正是最需要超时的那类，
> 也正是打不断的那类。」
>
> **实测（JDK 25.0.2）证明它对我们的实现不成立**：
>
> | 场景 | interrupt 后 |
> |---|---|
> | **虚拟线程** + socket `read()` | **抛 `SocketException: Closed by interrupt`** ✅ 真被取消 |
> | 平台线程 + socket `read()` | 读照常完成，interrupt 被完全忽略 |
> | CPU 忙循环（不检查标志） | 继续跑到自然结束 |
>
> 原因：JDK 13+ 把 `java.net.Socket` 重实现在 NIO 之上（JEP 353），**在虚拟线程
> 上**会响应 interrupt 并关闭 socket。而 §3.3 的 P1-2 恰好把工具搬上了虚拟线程
> ——于是「阻塞 IO 打不断」这条**对我们的实现是错的**。原判断是平台线程时代的
> 常识，写文档时没有实测，**是 live 测试逮到的**
> （`examples/tool_timeout_live_test.clj` 场景 1）。
>
> **后果：P1-2 的价值被本文低估了。**它不只让被放弃的执行变便宜（§2.4 的几 KB
> 栈），更把**最常见的工具形态（阻塞 IO / HTTP 调用）从「打不断」变成了「真能
> 取消」**——超时后 socket 立刻关闭，请求真的中止，副作用**不会**落地。
> 这条如今是选虚拟线程而非平台线程池的**首要**理由。
>
> **§2.3 的结论本身不变**（超时仍是「放弃等待」而非「终止执行」，仍无 kill
> 原语），但**残余风险的范围大幅收窄**：从「所有阻塞调用」缩到上面那三行——
> 主要是不检查中断标志的 CPU 密集代码。已由 live 场景 4 钉住（真机实测：
> 超时上报 t=2361ms，副作用落地 t=4059ms，晚 1.7 秒，其间模型已在作答）。

### 2.3 结论：我们的「超时」是**放弃等待**，不是**终止执行**

必须诚实命名。超时后：

- 调用方**停止等待**，向 LLM 合成一条 transient 错误 ✅
- 下游被 `interrupt`。**阻塞 IO（socket read 等）会真的中止**——虚拟线程上 JDK
  关闭 socket，请求作废、副作用不落地 ✅（§2.2 修订块，live 场景 1 实测）
- 但**不检查中断标志的代码**（CPU 密集循环为主）**会继续跑到自然结束** ⚠️
- 那种情况下，**它的副作用会在我们已经告诉 LLM「超时了」之后才落地** ⚠️⚠️
  （live 场景 4 实测：晚 1.7 秒）

最后一条是**语义**问题，不是实现瑕疵：`{:result "工具调用超时" :error {:class :transient}}`
不带 `:writes`（事务性，对的），但工具**函数体里的**外部副作用（写库、发消息、扣款）
框架管不着。beamai 的 kill 把这个窗口压到零；我们压不掉——**但也远没有本文初稿
以为的那么宽**：常见的「工具卡在外部 API 上」实际能干净取消，剩下的主要是纯计算
卡死。

**这必须写进 docstring**，不能让用户以为挂了超时就等于工具停了。
「声明 `:retry` 即承诺幂等」在我们这里比在 beamai 更重要——因为超时重试时，
**上一次调用很可能还在跑**。

> **文档纪律**：beamai 的文档可以写「到点 kill 执行进程」。我们的**不可以**。
> 照抄它的措辞会构成一个 API 谎言——比不做更糟。

### 2.4 泄漏成本因引擎而异（一个反直觉的结论）

既然超时 = 放弃，被放弃的线程就是**泄漏**。代价随引擎不同，差别很大：

| 引擎 | 被放弃的工具 | 代价 |
|---|---|---|
| `VirtualThread` | 泄漏一根虚拟线程 | **低**——几 KB 栈，不占 OS 线程 |
| `ThreadPool`（有界池） | **永久占用一个池槽** | **高**——舱壁逐格坏死，池最终整个死掉 |
| `Sequential` | 无 Future，压根无法放弃 | **不适用**——调用方陪着一起挂 |

**反直觉之处**：`ThreadPoolToolCallingManager` 的舱壁隔离，恰恰是**最经不起超时泄漏**的
引擎。虚拟线程引擎被放弃 100 个工具只是多几百 KB；有界池被放弃 `pool-size` 个工具就
**彻底死了**，且不会恢复。

> **§2.2 修订后，本表的差距还要再拉大一档**：虚拟线程不只让泄漏更便宜，还让
> **阻塞 IO 类的泄漏根本不发生**（socket 被关、线程正常结束）。同一个卡在外部
> API 上的工具，跑在虚拟线程上会干净消失，跑在平台线程上则连 interrupt 都无视、
> 池槽真的永久损失。这是 §4 那个 bug（`timeout-filter` 曾把工具搬去 send-off
> **平台**线程池）**比初判更严重**的地方——它同时毁掉了取消能力和线程模型。

这一条**加强了「超时逻辑要跑在虚拟线程上」的结论**（见 §4）。

---

## 3. 方案

### 3.1 目标与非目标

**目标**：让「工具声明超时」这件事**真的发生**，且在三个引擎、两条路径（内联 / executor）
上行为一致。

**非目标**：真正终止工具执行（JVM 做不到，§2.2）；复刻 beamai 的层数（§3.4）。

### 3.2 P0 —— 修掉 `:timeout` 死选项（真 bug，独立于 beamai 成立）

现状是沉默的谎言（§1.2）。**三条出路，只能选一条**：

| | 做法 | 评价 |
|---|---|---|
| a | 发出 `:tool/timeout` 元数据 + 加 `timeout-spec` 读取函数 + 由 `timeout-filter` 消费 | ✅ **推荐**。与 `:retry`/`:serial` 完全对称——它们都是「声明在 deftool，由上游消费」 |
| b | 从白名单删掉 `:timeout` | 可接受。诚实，但把已在 API 面上的字段拿走 |
| c | 保持现状 | ❌ **不可接受**。沉默 no-op |

选 a。`:retry` 就是现成的先例：`tool.clj:351` `retry-spec` 读元数据，
`kernel.clj:163` `retry-policy` 归一化，`react.clj:75` 消费。`:timeout` 照抄这条路径即可。

### 3.3 P1 —— `timeout-filter` 读 `:tool/timeout` + 跑在虚拟线程上

两处改动，都在 `advisor.clj:243`：

1. **每工具超时优先级**（移植 beamai `resolve_timeout/2`）：

   ```
   工具声明 :tool/timeout  >  filter 构造时的 timeout-ms  >  不超时
   ```

   工具声明最优先——理由与 beamai 完全一致，且**与进程模型无关，纯策略，可移植**：
   工具最清楚自己要跑多久。filter 的 `timeout-ms` 降级为**缺省值**，
   对应 beamai 的 manager 级 `tool_timeout`。

   `req` 里已有 `:function` → `{:name :schema :sensitive}`（`kernel.clj:176`
   `build-func-def`）。**注意**：`build-func-def` 目前不透传 timeout，需一并补上
   （或由 filter 自己查 var）。inline tools（`kernel.clj:210`）无 var、无元数据，
   只能吃 filter 缺省值——这是既有的 inline 盲点，与 `:sensitive` 现状一致，不新增。

2. **不要用 `clojure.core/future`**（§4 的 bug）：改用虚拟线程，
   使被放弃的执行代价维持在 §2.4 的「低」档，且不破坏引擎的线程模型。

改完后，「所有工具 30 秒缺省 + 个别工具自己声明」= 挂一个 filter + 各自 `deftool`
写 `:timeout`。**这就是 beamai 层 1 的全部价值**，且是 opt-in 的。

### 3.4 否决：不移植 beamai 的层 2 / 层 3

**层 2（gather deadline，`beamai_agent_utils.erl:200`）**——并发收集全局截止，
超时 kill 未完成 worker、**保住部分结果**。

**层 3（batch worker，`beamai_tool_batch_worker.erl`）**——批级兜底，
worker 崩溃或超时 → 整批合成 error + context 回滚。

**为什么不移植——它们的理由是 BEAM 特有的，到我们这里全部落空：**

1. **层 3 的主要理由是「防止工具带崩调用者」**，而这在 JVM 根本不存在。beamai 的原文：

   > 工具内 `spawn_link` 的子进程崩溃 → 退出信号绕过 try/catch 直接打死宿主进程

   **JVM 没有 link，没有退出信号传播。** 子线程抛异常打不死父线程。
   `invoke-one`（`react.clj:120`）的 try/catch **已经**是完备的错误边界。
   **层 3 在防一个我们没有的问题。**

2. **层 3 的另一半理由（批级时间兜底）在我们这里会打偏**。beamai 的注释说
   「串行路径没有内层防线，本层是其唯一的时间防线」——但我们的串行路径是
   **真·内联**（`run-inline`，压根不构造 Future）。要从外部给它加时间兜底，
   就得把它挪上线程，而「从不构造 Future」是 `SequentialToolCallingManager`
   **被明文写进文档的本质属性**（`react.clj:281`：「不背 ExecutionException 包装与
   Future.get 的中断语义——这是它与『给 VT 引擎塞一个 same-thread executor』的
   本质区别」）。为了一个打不断的超时，毁掉一个引擎的定义属性，不划算。

3. **层 2 的价值（保住部分结果）我们免费就有**。beamai 需要 gather deadline 来在
   全局截止时保住已完成工具的结果；而我们每工具的超时发生在 filter 里 ——
   超时的工具自己变成一条 transient 错误结果，**其它工具毫发无伤地正常返回**。
   部分结果是 §3.3 方案的**自然结果**，不需要专门一层。

4. **层 2 + 层 3 的宽限期（grace）纯属自找的复杂度**。beamai 必须小心让内层先响、
   外层后响，否则外层会把内层本可保住的部分结果整批冲掉——它为此专门写了一大段
   注释和一个 `tool_batch_grace` 配置。**这个问题是「有多层」制造的**。我们只做一层，
   问题不存在。

**四问判据**（`design-principles.md` §1.2）逐条过：

| 问题 | 层 2 / 层 3 |
|---|---|
| 现在有人要用吗？ | ❌ 没有具体场景。是「beamai 有，我们对称一下」 |
| 不建的话用户怎么办？ | 挂 `timeout-filter`（一行）即等价 → **§1.2 的「假想」列** |
| 换来的是什么？ | 只是「更像 beamai」。没有新能力 |
| 触发条件写得出来吗？ | 写不出。真出现「批级预算」需求时再谈 |

**四问全落在假想列 → 不立项。** 这与 `tool-calling-manager-design.md` 毙掉
`:backend` / PR2 / PR3 是同一条判据。

> **真需求长什么样**：若将来出现「整轮工具预算 ≤ N 秒，超了就把这轮全部作废」的
> 具体调用方——那是**批级预算**需求，不是超时兜底需求，届时按其真实形状设计。

### 3.5 也否决：把超时下沉进 `tool/invoke` 或 `run-on-executor`

为完整起见，记下另外两个被否的位置：

- **方案 B：`run-on-executor` 的 `(.get f)` 加超时**——只覆盖 executor 路径。
  §1.4 已证明**单工具调用走内联**，即最常见的情形完全不设防。
  「在最常见的路径上失效的保护」比没有保护更危险，因为它看起来像有。
- **方案 C：`tool/invoke` 内部恒定起线程**（对应 beamai `call_handler` 的位置）——
  这是 beamai 的选择，且能覆盖所有路径。但在 JVM 上：给**每个**工具调用无条件加一层
  线程 + Future 包装，换来的是一个**打不断的**超时。beamai 敢这么做是因为
  spawn 约等于免费**且 kill 真的有效**。两个前提我们都不满足。
  → 走 filter（opt-in），不进 `tool/invoke`（恒定）。

---

## 4. 顺带发现：`timeout-filter` 正在悄悄毁掉线程模型（真 bug）

`advisor.clj:252`：

```clojure
(let [f (future (chain req))          ; ← clojure.core/future
      r (deref f timeout-ms ::timeout)]
```

`clojure.core/future` 跑在 **`Agent/soloExecutor`（send-off 池）** —— 一个**无界的
平台线程**缓存池。而 `chain` 里包着**其余 filter + 工具函数体本身**。

**后果，两个都不轻：**

1. **VT 引擎名不副实**。`VirtualThreadToolCallingManager` 提交一根虚拟线程 →
   虚拟线程立刻阻塞在 `deref` 上 → **真正的工具函数体在 send-off 池的平台线程上跑**。
   挂了 `timeout-filter`，「每调用一根虚拟线程」这句 docstring 就不再成立。

2. **`ThreadPool` 引擎的舱壁对「被放弃的工具」失效**。存活任务的并发度仍受 pool-size
   约束（每根池线程阻塞在一个 `deref` 上，最多 pool-size 个在途），
   但**超时后池线程被释放、send-off 线程留下继续跑**——被放弃的工具于是在
   send-off 池**无界堆积**，且是**平台线程**（每根 ~1MB 栈）。
   舱壁 bound 不住它们。这与 §2.4 的结论叠加：本就最怕泄漏的引擎，泄漏得最不受控。

**修法**：`timeout-filter` 内部改用虚拟线程（`Thread/ofVirtual`）而非 `future`。
被放弃的执行退回 §2.4 的「低」档，且 VT 引擎的 docstring 重新成立。

---

## 5. 落地清单 ✅ 全部完成（2026-07-16）

| # | 改动 | 文件 | 性质 |
|---|---|---|---|
| ✅ P0-1 | `deftool` 发出 `:tool/timeout` 元数据（四个 `defn` 分支各一处） | `tool.clj` | 修死选项 |
| ✅ P0-2 | 加 `timeout-spec` 读取函数（对称 `retry-spec`） | `tool.clj` | 修死选项 |
| ✅ P0-3 | `build-func-def` 透传 `:timeout` 供 filter 读取 | `kernel.clj` | 修死选项 |
| ✅ P1-1 | `timeout-filter` 按 `:function :timeout` > `timeout-ms` 解析 | `advisor.clj` | 移植 beamai 优先级链 |
| ✅ P1-2 | `timeout-filter` 改用 `Thread/startVirtualThread`，弃 `clojure.core/future`；下游异常原样重抛（不再包 ExecutionException） | `advisor.clj` | 修 §4 的 bug |
| ✅ P1-3 | docstring 如实写明「放弃等待 ≠ 终止执行」+ 副作用窗口 + `:retry` 幂等前提（deftool 元数据说明、`timeout-spec`、`timeout-filter` 三处） | `advisor.clj`、`tool.clj` | **§2.3，不可省** |
| — | ~~gather deadline~~ | — | ❌ §3.4 否决 |
| — | ~~batch worker 兜底~~ | — | ❌ §3.4 否决 |

**测试（+9 tests / +29 assertions，全套 309/1256/0）**，逐条对应 §3/§2 的论点：
- `:timeout` 声明生效：元数据回归钉（`tool_test/timeout-option-emits-metadata`）+
  **端到端**穿 `build-func-def` → filter（`advisor_test/tool-declared-timeout-end-to-end-test`，
  修复前此用例睡满 60s）；另钉「声明本身无强制力，裸 `invoke` 不超时」防误解为内置
- 优先级三分支：声明更紧提前超时 / 声明更宽不被 filter 缺省打断 / 未声明回落缺省
  （`timeout-filter-priority-test`）
- 线程模型：下游跑在虚拟线程（`.isVirtual` 断言）+ 异常原样重抛
  （`timeout-filter-thread-model-test`）
- 超时 → `:transient` → `:retry` 重试整链（`react_test/timeout-transient-retry-test`）
- 超时不带 `:writes`（既有用例 + 端到端断言）
- 同批部分结果（`react_test/timeout-partial-batch-test`：慢者超时、快者原样返回、
  `:errors` 只含超时者）
- **诚实测试**：CPU 忙循环超时返回后计数器仍在跳（`timeout-abandons-not-kills-test`），
  把 §2.3 钉进测试，防止后人误以为有 kill 语义

### 5.1 Live 验证（MiniMax 真实循环，20 项断言，连跑 3 遍稳定）

`examples/tool_timeout_live_test.clj`。慢后端是**本地裸 TCP**（零依赖不联网），
只有 LLM 是真的——分工：live 验「真实模型 + 真实阻塞 IO」，单测验形状与分支。
四个场景各自只验单测证明不了的东西：

1. **超时 → 模型收到 transient 错误 → 循环存活作答**：模型确实理解「超时」并
   继续（2 次 LLM 调用），最终答案完整。单测只能证明 filter 返回了错误 map，
   证明不了模型拿它怎么办。**顺带钉住阻塞 IO 真被取消**（副作用不落地）。
2. **对照组：声明救活 vs 缺省杀死**：filter 缺省 800ms，同为 3s 的活儿——
   `get-price` 声明 `:timeout 6000` 拿到真实数据（`SKU-7 price: 70 CNY`），
   `get-stock-age` 未声明被杀（`工具调用超时（800ms）`）。**优先级在真实循环里
   的可观察后果**：模型的答案因此分成「一个报价 + 一个报超时」。
3. **超时 → `:retry` 透明重试**：工具实跑 2 次，模型只看到成功结果、只有 1 条
   tool result——**重试对模型透明**在真机成立。
4. **abandon 残余风险**：CPU 忙循环工具超时上报 t=2361ms、副作用落地 t=4059ms
   （晚 1.7 秒，其间模型已在作答）。**§2.3 的核心声明，真机时间戳钉死。**

**跑真机推翻了一个判断**（详见 §2.2 修订块）：初稿断言「socket read 打不断，
这条最要命」——实测虚拟线程上它**真被取消**。原判断是平台线程时代的常识，
写文档时没实测。P1-2 的价值因此被本文低估。

**一处 flake 及其教训**：场景 2 初版断言 `(= 2 (count tool-calls-made))`，
偶发失败——**模型看到超时后自行重试了 `get-stock-age`**，记录变 3 条。
重不重试是模型的自由，不是我们的机制；改为按名分组、断言「每次调用的结果形状」，
不钉次数。这正是「断言钉机制、不钉模型行为」这条规矩的又一个实例。

---

## 5.5 后记：beamai 同日的趋同修订（2026-07-16）

本文写作当天，beamai 侧把**三层的缺省全部改为 `infinity`**（工具级
`?DEFAULT_TOOL_TIMEOUT`、层 2 `tool_gather_timeout`、层 3 `batch_timeout` 均不再有
缺省截止），超时从「缺省 30s/120s/+5s grace」变为**纯 opt-in 声明**；层 3 的自我定位
明确退为「只管隔离，不替调用者决定何时放弃」。

这从 beamai 侧独立佐证了 §3.4 的判断：**层 2/层 3 的价值从来不在缺省截止时间，
而在 BEAM 特有的隔离与 kill**——前者（缺省值）连 beamai 自己都撤了；后者（隔离）
JVM 不需要、（kill）JVM 给不了。两个体系各自收敛到同一形态：
**超时是声明出来的策略，不是框架强加的缺省**。
（注意本文 §1–§3 引用的 beamai 缺省值描述的是分析时点的代码，已过时，判断不受影响。）

---

## 6. 一句话总结

**beamai 的三层超时是 `exit(Pid, kill)` 的三次运用；JVM 没有这个原语，照搬只能得到
三层文档。**真正可移植的是它的**策略**（优先级链、transient 分类）而非它的**结构**
（层数、隔离边界）——后者是 BEAM 进程模型的倒影。

而这次分析真正的收获，反倒不在 beamai：**我们的 `:timeout` 是个躺在 API 面上的死选项，
`timeout-filter` 在悄悄把虚拟线程换成平台线程。**这两个问题不需要任何对标就该修。

**而 live 实测又给这个总结补了一刀**：第二个 bug 比初判严重——它不只毁线程模型，
还毁**取消能力**。虚拟线程上 JDK 会为 socket read 响应 interrupt（§2.2 修订块），
所以「工具卡在外部 API 上」这个最常见的形态，跑在虚拟线程上能干净取消、跑在
send-off 平台线程上则连 interrupt 都无视。修 §4 那个 bug 的同时，我们顺手把
「超时对最常见工具形态真的有效」这件事一起买回来了——**这是写文档时没预见、
靠真机才发现的**。教训照旧：**没实测的性能/语义断言，就是一条没被证伪的猜想**。
