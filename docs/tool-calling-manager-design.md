# ToolCallingManager 协议设计：统一工具执行管理

> **状态：📋 设计阶段（v3，待用户确认进入实施）。**
> 本文是对 `advisor-alignment-design.md` §1.3《ToolCallingManager —— 我们不长这个
> 抽象》的修订：从「不长」到「引入统一执行协议」。修订理由与边界见 §2、§3。
>
> **Review 修订记录（v2）**：基于 Oracle 评审反馈修订了 3 处 blocking 问题
> （B1 defmulti 模块归属、B2 PR1 零变化承诺、B3 inline tools 盲点）、
> 5 处重要问题、4 处缺失考量。修订点散落各节，关键决策汇总在 §11。
>
> **v3 修订（用户收敛协议形状）**：协议方法从 `execute-batch` 改为 Spring 对齐的
> `execute-tool-calls`（带 `response` 参数）；`execute-batch` 与 `execute-single`
> 退回成**每个 record 的内部 helper**（不再是协议方法）；明确多 impl 策略
> （Sequential / VirtualThread / ThreadPool）。PR1 已交付的 v2 形状需返工。
>
> 一旦实施，本文档顶部状态改为「已实施」并附 tests/assertions 计数，
> 同时在 §1.3 末尾补一条「见 `tool-calling-manager-design.md`（推翻本节）」。

---

## 0. TL;DR

引入 `ToolCallingManager` 协议（**core 模块**）作为**工具执行的统一管理 seam**。
协议只有一个方法 `execute-tool-calls`（Spring 对齐签名，带 `response` 参数）。

**多个实现**（v3 新增的核心思路）通过实例化不同 record 选择执行策略：
- `VirtualThreadToolCallingManager`（默认，现状行为）
- `SequentialToolCallingManager`（全串行，调试 / 严格副作用场景）
- `ThreadPoolToolCallingManager`（真实线程池，限流场景，可配 pool-size）

`execute-batch` 与 `execute-single` 退回成**每个 record 的内部 helper**——
**怎么实现完全是 record 自己的选择**：可以用 virtual thread、可以全串行、
可以用 ExecutorService。协议不规定，框架不强加。

同时扩展 `deftool` 加 `:backend` 元数据（`:local` / `:http` / `:mcp`），
让单个工具能声明执行路径；分派由 `tool/invoke` 内部的 `defmulti` 完成。
**现有所有 deftool 行为零改动**——`:backend` 缺省 `:local`，老代码不需迁移。

**关键边界（防止与现有抽象重叠）**：
- `:serial` 仍是声明级执行策略，**不由 manager 决定**
- `:tool` filter 仍是 around 链，**不被 manager 取代**
- `:writes` 屏障折叠仍走 `ctx/apply-writes`，**不被 manager 接管**
- manager 只把「执行入口」从 `react.clj` 私有函数升格为**可注入协议**

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

## 2. 为什么现在改

### 2.1 新需求：deftool 要支持多 backend

§1.3 写作时，工具只有一种执行路径——**进程内 Clojure 函数**（`(var-get v)`，
`tool.clj:426`）。今天的需求是让 agent 能挂 HTTP 工具、MCP 工具与本地工具
混合使用，对 LLM 透明。

`tool/invoke` 直接 `var-get` 拿函数调，**没有分派 seam**——这是真实痛点，
不是假设。Spring AI 用 `ToolCallback.call(input, ctx)` 协议统一 local / HTTP /
MCP 三种 transport，clj-agent 的等价物应落在 `deftool` 元数据 + `tool/invoke`
分派上（§5）。

### 2.2 §1.3 预测的「真实需求」兑现

§1.3 末尾写：「出现真实需求（分布式工具执行、需要优先级队列的池）再抽」——
HTTP / MCP backend 就是这种真实需求。

但 §1.3 推荐的形状（kernel `:settings` 加一个 `:tool-executor` 键）只能换
**线程池**，不能换 **transport**。Transport 是 per-tool 的（每个工具声明自己
的 backend），不是 per-kernel 的（一个 executor 决定不了 batch 里混合的
HTTP + 本地 + MCP 工具）。

**故形状选择「协议方法」而非「kernel :settings 注入 executor」**——
协议可以同时承载多 backend 分派（在 tool/invoke 层）和多执行 pattern
（在 manager 协议层），两个正交维度。

---

## 3. 与 §1.3 的对账（核心）

逐条回应 §1.3 的反对意见：

| §1.3 担心 | 本设计如何避免 |
|---|---|
| manager 与 `:serial` 重叠（执行策略两处决定） | `:serial` 仍是**工具声明级**的批内并行退化开关，由 `VirtualThreadToolCallingManager` 的内部 helper 在分派前读取；manager 协议本身**不知**:serial 存在。换 manager 实现（如 `SequentialToolCallingManager`）仍要尊重 `:serial`——这是契约，不是重叠 |
| manager 与 `:tool` filter 重叠（around 链两处插入） | `:tool` filter 仍包**单个工具执行**（在 `kernel/invoke-tool`），manager 包**整批调度**。两者层次不同：filter 是单工具的 around，manager 是「调度 N 个工具、收齐结果、折叠 writes」的批编排 |
| concurrent manager 半管线程池生命周期 | 虚拟线程 `tool-executor`（`react.clj:65` 的 `delay`）**写在 `VirtualThreadToolCallingManager` 内部**，**协议不感知**。换 manager 实现 = 换一个 record（如 `SequentialToolCallingManager` 不持任何池；`ThreadPoolToolCallingManager` 持有可关停的池，但生命周期由用户管理） |
| 两个可替换点（执行策略 / 错误策略）已有更好落点 | **同意**——本协议不是「可替换点容器」。**v3 价值**：把 `execute-batch` 从 `react.clj` 私有函数升格为**可注入的公开 seam**，并通过多 record 实现（VT / Sequential / ThreadPool）让执行策略成为**实例化时的显式选择**——这正是 §1.3 末尾预测的「executor 可注入」诉求的彻底兑现 |
| 形状应是 kernel `:settings` 注入 executor | 该形状只能换线程池（kernel 级），不能换 transport（per-tool）。本设计两个维度都解耦：transport 在 `deftool :backend` + `tool/invoke` 分派，执行 pattern 在 manager 协议 |
| **`kernel/invoke-tool` 已是更细一层的 seam，backend dispatch 落在 `tool/invoke` 即可，何必再上层 manager？** | **v3 回答**（取代 v2 的"承认较薄"）：transport dispatch 由 `deftool :backend` 独立承担（这条 §1.3 说得对）。**但 manager 的价值在 v3 通过多 impl 真实兑现了**——不同 record 提供 Sequential / VT / ThreadPool 三种执行策略，调用方一行换 manager 即换策略。这正是 §1.3 末尾预测的「executor 可注入」诉求，只是形状比 §1.3 建议的更彻底（多 impl 而非 kernel :settings 单键）。**不再是 v2 的"测试 seam + headroom"模糊定位** |

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

### 4.3 多个实现（client 模块）

```clojure
;; im.ttalk.agent.react（现有 ns，加三个 record）

;; 1) 虚拟线程（默认，现状行为；尊重 :serial 声明）
(defrecord VirtualThreadToolCallingManager []
  tool-calling-manager/ToolCallingManager
  (execute-tool-calls [_ kernel response opts]
    (let [calls (response/response-tool-calls response)]
      ;; 调本 record 的内部 execute-batch（用 virtual thread + :serial 退化）
      ...)))

(defn virtual-thread-tool-calling-manager
  "缺省 manager：虚拟线程并行，尊重 :serial 声明（现状行为）。"
  []
  (->VirtualThreadToolCallingManager))

;; 2) 全串行（调试 / 严格副作用场景；不尊重 :serial，因为本来就全串）
(defrecord SequentialToolCallingManager []
  tool-calling-manager/ToolCallingManager
  (execute-tool-calls [_ kernel response opts]
    (let [calls (response/response-tool-calls response)]
      ;; 调本 record 的内部 execute-batch（永远顺序）
      ...)))

(defn sequential-tool-calling-manager
  "全串行 manager：每个工具按序执行，无并发。适合调试或严格副作用场景。"
  []
  (->SequentialToolCallingManager))

;; 3) 真实线程池（限流场景；可配 pool-size）
(defrecord ThreadPoolToolCallingManager [^ExecutorService pool]
  tool-calling-manager/ToolCallingManager
  (execute-tool-calls [_ kernel response opts]
    (let [calls (response/response-tool-calls response)]
      ;; 调本 record 的内部 execute-batch（用 pool 调度）
      ...)))

(defn thread-pool-tool-calling-manager
  "线程池 manager：用固定大小 ExecutorService 调度，尊重 :serial 声明。
   pool-size 缺省 = (Runtime/.availableProcessors)。"
  ([]
   (thread-pool-tool-calling-manager (.availableProcessors (Runtime/getRuntime))))
  ([pool-size]
   (->ThreadPoolToolCallingManager (Executors/newFixedThreadPool pool-size))))
```

**调用方选 manager 即选策略**：

```clojure
(kernel/build-kernel
  {:service svc :tools [...]
   :tool-manager (react/virtual-thread-tool-calling-manager)})   ;; 默认
;; 或
   :tool-manager (react/sequential-tool-calling-manager)         ;; 全串行
;; 或
   :tool-manager (react/thread-pool-tool-calling-manager 8)      ;; 8 线程池
```

**首版交付范围**：v3 PR1 至少实现 `VirtualThreadToolCallingManager`（现状行为）
+ `SequentialToolCallingManager`（验证多 impl 真能换）。`ThreadPoolToolCallingManager`
可同期或后续加。

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

## 5. deftool 扩展：`:backend` 元数据

### 5.1 设计

`deftool` 的 opts map 增加 `:backend` 键（缺省 `:local`），及相关配置。
**严格模式**：声明非 `:local` backend 的工具**不允许写函数体**——
宏在展开期检查，body 不为空则报错。

```clojure
;; 1) 现状完全不变（:backend 缺省 :local）
(deftool get-weather
  "获取天气"
  [[city :string "城市名"]]
  {:tags [:weather]}
  (fetch-weather city))
;; 等价于显式 {:backend :local :tags [:weather]}

;; 2) HTTP backend（无 body）
(deftool search-web
  "搜索网页"
  [[query :string "查询词"]
   [limit :int "返回条数" :default 10]]
  {:backend  :http
   :endpoint "https://tools.internal/search"
   :method   :post})

;; 3) MCP backend（无 body）
(deftool fs-read
  "MCP 文件系统读"
  [[path :string "路径"]]
  {:backend     :mcp
   :server      :filesystem           ;; build-kernel 时注册的 mcp client 名
   :remote-name "read_file"})         ;; MCP 端真实工具名（缺省取 deftool 名）
```

### 5.2 元数据 schema

> **v2 修订（回应 Oracle M1）**：以下分两层——(a) 用户在 deftool opts 写的字段，
> (b) 宏展开后存进 var 元数据的字段。

**(a) deftool opts 字段（用户写的，平铺）**：

| 字段 | 类型 | 缺省 | 用于 backend |
|---|---|---|---|
| `:backend` | keyword | `:local` | 所有（dispatch 键） |
| `:endpoint` | string | — | `:http` |
| `:method` | keyword | `:post` | `:http` |
| `:server` | keyword | — | `:mcp` |
| `:remote-name` | string | 取 deftool 名 | `:mcp` |

**(b) 生成的 var 元数据（宏展开后的存储格式）**：

| 元数据键 | 类型 | 含义 |
|---|---|---|
| `:tool/backend` | keyword | dispatch 键（`defmulti` 用），缺省 `:local` |
| `:tool/transport` | map | backend 特定配置，由宏从 opts 收集打包 |

**两层分离的理由**：用户写平铺（与 `:sensitive`/`:tags` 同层风格一致），
但存储用嵌套（`:tool/transport` map），让 `invoke-backend` dispatch fn 只需读
`:tool/backend`，不被 transport 字段污染。

### 5.3 宏改动（tool.clj）

```clojure
;; deftool 宏的 opts 解析增加（伪代码）：
(let [backend (or (:backend opts) :local)
      transport (case backend
                  :http {:endpoint (:endpoint opts)
                         :method   (or (:method opts) :post)}
                  :mcp  {:server      (:server opts)
                         :remote-name (:remote-name opts)}
                  :local nil)
      ;; 严格模式检查（v2 加严，回应 Oracle I3）：
      ;;  1. 非 :local backend 且 body 非空 → 报错（transport 工具不执行本地代码）
      ;;  2. 非 :local backend 且声明 :context true → 报错（没有 fn 体，context 无所依附）
      ;;  3. 非 :local backend 且声明 :retry 无效？——不报错，retry 仍生效（HTTP/MCP 失败
      ;;     也可分 :transient 重试，是合法用法）
      _ (when (and (not= :local backend) (seq body))
          (throw (ex-info (str "deftool " fn-name
                               " 声明 :backend " backend
                               " 不允许写函数体（transport 工具不执行本地代码）")
                          {:backend backend})))
      _ (when (and (not= :local backend) (:context opts))
          (throw (ex-info (str "deftool " fn-name
                               " 声明 :backend " backend
                               " 与 :context true 互斥（remote 工具无 fn 体接收 ctx）")
                          {:backend backend})))
      ;; :local 时 body 是函数体；其他 backend 时 body 应为空
      fn-body (when (= :local backend) body)]
  ;; 生成 var，元数据带上 :tool/backend 与 :tool/transport
  ...)
```

**生成的 metadata 增加**：
```clojure
{:tool/backend :http
 :tool/transport {:endpoint "..." :method :post}}
```

### 5.4 分派实现（tool.clj）

> **v2 修订（回应 Oracle B1）**：定论 `invoke-backend` defmulti **放在 core 的 `tool.clj`**，
> `:http`/`:mcp` 方法由 client 通过 `defmethod` 注册（ns require 时挂上）。
> 理由：(1) `tool/invoke` 是公开入口，dispatch 应紧跟它；(2) 与 §6.1 option ii（放 client）
> 互斥，已选 option i；(3) "core-only consumer 误用 :http" 的风险通过缺省 `:local` +
> 报错消息（"`:http` backend 需要 client 模块加载"）控制。

```clojure
;; core/tool.clj —— defmulti 在 core，公开（v2 决策：去 ^:private，便于 client 注册方法）
(defmulti invoke-backend
  "按 :tool/backend 分派执行。default :local。
   :http / :mcp 方法由 client 模块通过 defmethod 注册（ns require 时挂上）。"
  (fn [v args context]
    (get (meta v) :tool/backend :local)))

;; :local 方法在 core 实现（现有逻辑搬过来）
(defmethod invoke-backend :local
  [v args context]
  ;; 现有 tool/invoke 的函数体（var-get + 调用 + writes 拆分）
  ...)

;; :http / :mcp 方法由 client 在 ns load 时注册：
;;   (defmethod tool/invoke-backend :http [v args context] ...)
;;   (defmethod tool/invoke-backend :mcp  [v args context] ...)

;; tool/invoke 改成简单委托
(defn invoke
  ([v args] (invoke v args nil))
  ([v args context]
   (let [{:keys [valid errors]} (validate-args v (or args {}))]
     (if-not valid
       {:success false :error (clojure.string/join "; " errors)}
       (try (invoke-backend v args context)
            (catch Exception e
              {:success false
               :error (or (not-empty (.getMessage e)) (.getName (class e)))
               :error-class (err/classify-exception e)}))))))
```

> **`mcp-clients` 访问的悬而未决问题（v2 标注，回应 Oracle I5）**：
> `:mcp` 方法签名 `(fn [v args context])` **拿不到 mcp-clients map**。三个候选方案：
> - (a) 加宽 defmulti dispatch fn 为 `(fn [kernel v args context])`，`tool/invoke` 加 `kernel` 参数——破坏现有公开 API，不可取
> - (b) mcp-clients 放进 `kernel/inline-handlers` 同款位置的 kernel 新字段 `:mcp-clients`，通过 `context` 注入（约定 context 携带 `:mcp-clients` key）
> - (c) 动态变量 `*mcp-clients*` 由 manager 在 execute-batch 入口 binding
>
> **PR3 落地 MCP 时再决定**。`tool/invoke` 签名不变（`(invoke v args context)`），
> 现有 caller 零影响。`:http` 方法不需要这种访问（endpoint 自包含于 metadata），
> 故 PR2 不受阻。

**关键性质**：`invoke-backend` defmulti **公开**（去 `^:private`，方便 client
注册 `:http`/`:mcp` 方法）；`tool/invoke` 仍是公开入口，签名/返回值不变——
所有现有调用方零迁移。

### 5.5 backend 与现有元数据的正交性

| 元数据 | `:local` | `:http` | `:mcp` | 说明 |
|---|---|---|---|---|
| `:tool/sensitive` | ✅ | ✅ | ✅ | gate 审批语义不变 |
| `:tool/serial` | ✅ | ✅ | ✅ | 批内退化按序语义不变 |
| `:tool/return-direct` | ✅ | ✅ | ✅ | 结果即最终答案语义不变（HTTP/MCP 返回值需符合 direct-response 期望的字符串） |
| `:tool/context` | ✅ | ❌ | ❌ | **严格禁止**（§5.3）：remote backend 无 fn 体，无处接收 ctx |
| `:tool/retry` | ✅ | ✅ | ✅ | invoke-with-retry 在 :transient 类失败时仍生效（HTTP 超时、MCP 网络错误归类为 :transient） |
| `:tool/tags` | ✅ | ✅ | ✅ | tag 过滤语义不变 |

### 5.6 inline tools（`:handler` maps）的边界（v2 新增，回应 Oracle B3）

**重要**：`kernel.clj:208-227` 有**两条独立的工具执行路径**——

1. **var 路径**（`kernel.clj:229-254`）：tool 通过 `deftool` 定义为 var，经 `kernel/invoke-tool` → `tool/invoke` → `invoke-backend` defmulti 分派
2. **inline-handler 路径**（`kernel.clj:208-227`）：tool 是带 `:handler` 的 map（如 `delegate-tool`、`fanout-tool`、`search_tools`），**绕过 `tool/invoke` 直接调 handler**

**结论**：
- inline tools **永远走 `:local` 执行路径**——它们没有 var、没有 metadata、无法声明 `:backend`
- §5 的 `:backend` 扩展**只对 `deftool` 定义的 var 生效**
- subagent 体系（`subagent/delegate.clj` 的 `delegate-tool`/`fanout-tool`）是 inline tools，**不支持 `:backend :http/:mcp`**——subagent 委托本质上就是进程内 agent-to-agent 调用，不需要 remote backend
- 若用户想让 subagent 工具走 remote：必须用 `deftool` 包装一层（var-based），声明 `:backend :http` 等

**这个边界是设计意图，不是缺陷**：inline tools 的设计目标是「程序化动态构建的工具」（如 `search_tools` 随 ToolSearch filter 动态生成 schema），它们天生在进程内执行。

### 5.7 ToolSearch × backend 元数据（v2 新增，回应 Oracle C1）

ToolSearch 索引的是 tool **schemas**（`advisor/tool_search.clj:286-295`），而 `:backend` 存在 var **metadata** 上，**不在 schema 里**。所以：

- ✅ **索引正常工作**：HTTP/MCP 工具的 schema 在 index 里和 local 工具的 schema 无差别
- ✅ **执行正确**：搜索发现的工具下一轮进入 `:tools` 列表后，执行时仍按 backing var 的 metadata 分派
- ⚠️ **耦合点**：若有人手工构造一个「无 backing var 的纯 schema」（如直接拼一个 tool schema map 喂给 LLM），它执行时会因为找不到 var 而**静默走 `:local` 路径或报错**。这是现有 `kernel/invoke-tool` 的 `find-function` 行为（找不到 var 时回退到 inline-handlers，仍找不到则抛错），不是新引入的问题，但值得记录。

---

## 6. Transport 实现细节

### 6.1 HTTP backend

**契约**：`POST {endpoint}` with JSON body `{:args args :context context}`，
期望响应 `{:result any :writes {k v}}` 或 `{:error str :error-class kw}`。

**首版范围**：
- 使用 JDK `java.net.http.HttpClient`（core 零依赖原则）
- 仅支持 JSON（cheshire 在 client / provider 已有，但 core 无——故 HTTP backend
  的实现**放在 client 模块**，core 只定义 `:http` 分派的多方法占位，方法体在
  client 重定向；或者把 `:tool/backend` 的 defmulti 放在 client 而非 core）
- 超时：默认 30s（可配），超时分类为 `:transient`（声明 `:retry` 可重试）
- 鉴权：header 配置通过 transport 配置传入（`{:headers {"Authorization" "..."}}`）

**模块归属（v2 定论，回应 Oracle B1）**：**`invoke-backend` defmulti 放 core 的 `tool.clj`**（公开，去 `^:private`）；`:http` 方法在 client 通过 `defmethod` 注册（ns require 时挂上）。

理由：
- `tool/invoke` 是公开入口，dispatch 应紧跟它（同文件维护一致性）
- core 零外部依赖原则不破坏——`:http` 方法注册依赖 client 加载，但 core 自身不引任何 jar
- 误用风险（core-only consumer 写了 `:backend :http` 但没 require client）通过缺省 `:local` + 明确报错控制

**JDK `java.net.http.HttpClient` 可在 core 使用**（JDK 内置，不算外部依赖），但 **cheshire（JSON 库）在 client/provider，core 没有**。两个选择：
- i：HTTP transport 的 JSON 序列化走 `java.util.Base64` + 手写简易 JSON（不推荐，易错）
- ii：HTTP transport 实现整体放 client，core 的 `:http` 方法 stub 抛 "需要 client 模块" 错误，client 覆盖该方法

**选 ii**——core 的 `:http` 方法只做错误兜底；client require 时通过 `defmethod` 覆盖挂上真实实现。这与 `:mcp` 方法同款（PR3 落地）。

**超时**：默认 30s（可配），超时分类为 `:transient`（声明 `:retry` 可重试）。
**鉴权**：header 配置通过 transport 配置传入（`{:headers {"Authorization" "..."}}`）。

### 6.2 MCP backend

**契约**：调用 `McpClient/callTool(server, tool-name, args)`，把结果归一化为
clj-agent 的 `{:success :result :writes}` 格式。

**首版范围**：
- 协议接口 `IMcpClient`（core 定义）+ 一个简单 STDIO/HTTP 实现（client 或独立模块）
- MCP server 在 `build-kernel` 时注册：`{:mcp-clients {:filesystem mcp-client-1}}`
- manager record 持有 mcp-clients 引用，MCP 分派时按 `:server` 查找

**首版可以只定义 `IMcpClient` 协议、不提供实现**——与 `IRetriever` / `IToolIndex`
的取舍一致（core 协议，实现由用户注入）。这样 core 零依赖不变。

### 6.3 错误归一化

所有 backend 的失败必须用 clj-agent 现有错误模型：
- 抛 `ex-info` 携带 `:error-class`（`:semantic` / `:transient` / `:environment`）
- 或返回 `{:success false :error str :error-class kw}`

`err/classify-exception`（`model/error.clj`）负责把 HTTP 状态码、MCP 错误码等
归一化到三分类——已有的 provider 错误分类逻辑可复用。

### 6.4 MCP client 注册

```clojure
;; build-kernel 增加 :mcp-clients（PR3 落地时）
(kernel/build-kernel
  {:service svc
   :tools [#'local-tool #'mcp-tool]
   :mcp-clients {:filesystem (mcp/stdio-mcp-client {:command "npx" :args [...]})}})
```

> **v2 修订（回应 Oracle I5）**：原写「manager 持有 mcp-clients」与 §5.4 的 defmulti
> 签名冲突（defmulti 拿不到 manager）。**改放 kernel 顶层字段**——与 `inline-handlers`
> 同款先例（kernel.clj:49-51）。Manager 不持有 mcp-clients；MCP 分派时通过
> `context` 携带的 `:mcp-clients` 引用，或 `kernel` 直接传给 `invoke-backend`
> （具体方案 PR3 决定，见 §5.4 末尾的悬而未决标注）。

```clojure
;; Kernel record（PR3 时 8 字段）：
;; (defrecord Kernel [service filters tools tool-vars inline-handlers settings
;;                    tool-manager mcp-clients])
```

**未注册的 server 名 → 报错**（`ex-info` 携带 `:error-class :semantic`）。

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

### PR2：deftool 扩展 `:backend`（仅 local + http，mcp 留接口）

**目标**：让 deftool 能声明 HTTP backend，跑通一个 HTTP 工具示例。

**改动文件**：
- `core/tool.clj`（宏改造 + `invoke-backend` defmulti + `:local` 方法）
- `client/tool_backend_http.clj`（新 ns，HTTP transport 实现 + `:http` 方法注册）
- `examples/http_backend_live_test.clj`（live 验证脚本）

**验收**：
- 全套 tests 通过
- 新增 `tool_backend_test.clj`（deftool 严格模式校验、:local 路径不变、:http 分派）
- live 脚本通过（用一个 mock HTTP server 验证 transport）

### PR3（可选）：MCP backend

**目标**：加 `IMcpClient` 协议 + MCP 分派，至少跑通一个真实 MCP server。

**何时做**：等具体 MCP 场景出现。首版只留 protocol + dispatch 占位。

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

首版 `VirtualThreadToolCallingManager` 与 `SequentialToolCallingManager` 都是无状态空 record。未来可能加：
- `:mcp-clients`（MCP 分派时查）
- `:exception-processor`（错误策略可注入）
- `:http-client`（HTTP transport 共享连接池）

**首版不加**——避免无需求先加复杂度。

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
- PR2：将 `run-tools` 与新代码迁移到协议方法；为 `react/execute-batch` 函数加 `^:deprecated` docstring 指向 manager。
- PR3+：考虑完全移除 5-arity 函数（breaking change 正式发生在此版本，PR1/PR2 不破坏）。

**这样 PR1 的"零变化"承诺才是可证伪的**——任何测试失败都说明契约真的变了。

---

## 10. 与现有文档的同步

实施时需同步更新：
- `docs/advisor-alignment-design.md` §1.3：末尾加「**已修订**（YYYY-MM-DD）：
  见 `tool-calling-manager-design.md`，方案 A 落地」
- `docs/filter-chain-design.md` §0 的架构图：加 ToolCallingManager 层
- `README.md` / `modules/clj-agent-core/README.md`：在 deftool 段加 `:backend` 说明
- `modules/clj-agent-client/README.md`：在 react 段提 ToolCallingManager

---

## 11. 决策记录（截至本文档 v3 定稿）

| # | 决策 | 选择 | 理由 |
|---|---|---|---|
| 1 | manager 协议 vs 仅扩展 deftool | **协议 + 扩展** | 用户明确：manager 统一管理执行；batch 是其中一种方法 |
| 2 | `:backend` 配置平铺 vs 嵌套 | **平铺**（用户层）/ 嵌套（元数据层） | 与 deftool 现有 opts 风格一致；dispatch fn 不被污染 |
| 3 | remote backend 是否允许 body | **严格禁止** | 防止本地代码与远端 transport 语义混淆 |
| 4 | ToolCallingManager 形态 | **protocol + record** | 可 mock、可换实现、与 Spring 对齐 |
| 5 | ToolExecutionResult 形态 | **plain map**（键名冻结） | 与 :writes/:records 现有约定一致；持久化兼容性要求键稳定 |
| 6 | MCP client 注册位置 | **kernel 顶层字段**（与 inline-handlers 同款先例） | deftool 只声明 :server，不持 client；defmulti 通过 kernel/context 访问 |
| 7 | writes / records 是否进 ToolExecutionResult | **保留** | clj-agent 特有，强行对齐 Spring 会丢功能 |
| 8 | HTTP transport 模块归属 | **core defmulti + client defmethod** | core 零依赖原则；client require 时挂方法 |
| 9 | `invoke-backend` defmulti 归属（v2） | **core `tool.clj`，去 ^:private** | tool/invoke 是公开入口，dispatch 应同文件维护；http/mcp 方法由 client 注册 |
| 10 | PR1 是否改 execute-batch 签名（v2） | **不改**（PR1 保持 5-arity 函数；protocol 方法内部委托） | 真正的零行为变化；signature breaking 推到 PR3+ |
| 11 | inline tools 是否支持 :backend（v2） | **不支持**（设计意图） | inline-handler 路径绕过 tool/invoke；subagent 委托天生进程内 |
| 12 | `:context true` + remote backend（v2） | **宏编译期拒绝** | remote 无 fn 体，:context 无所依附 |
| 13 | v1/v2 manager 协议的诚实定位（v2） | 承认较薄（测试 seam + headroom） | **v3 已修订**——见 #14、#15，价值已通过多 impl 真实兑现 |
| 14 | 协议方法签名（v3 新增） | **`execute-tool-calls [this kernel response opts]`** | Spring 对齐；内部抽 tool_calls；调用方不再手动抽 |
| 15 | `execute-batch` / `execute-single` 归属（v3 新增） | **每个 record 的内部 helper**（不是协议方法） | 协议只有一个方法；多 impl 通过不同 record 实现，不用多协议方法 |
| 16 | 多 impl 策略（v3 新增） | **VT（默认）+ Sequential + ThreadPool** | 对齐 cl-agent 双 impl；§1.3「executor 可注入」诉求完全兑现 |
| 17 | 多 impl 是否进 PR1（v3 新增） | **VT + Sequential 进 PR1**；ThreadPool 可选/后续 | 至少两个 impl 才能证明协议真能换策略 |

---

## 12. Review 反馈处置索引（v2）

Oracle review（`bg_41b9aad5`）共提出 3 blocking + 5 important + 4 minor + 4 missing。
处置分布：

| 编号 | 类型 | 处置位置 |
|---|---|---|
| B1（defmulti 归属矛盾） | blocking | §5.4、§6.1、§11 #9 |
| B2（PR1 零变化矛盾） | blocking | §8 PR1、§9.5、§11 #10 |
| B3（inline tools 盲点） | blocking | §5.6（新增）、§11 #11 |
| I1（§3 不诚实） | important | §3 表格新行 + 诚实声明 |
| I2（ToolExecutionResult 序列化） | important | §9.2 稳定性约束 |
| I3（`:context` + remote） | important | §5.3 严格检查 + §5.5 表格更新 |
| I4（kernel 签名不准） | important | §4.5 真实签名 |
| I5（mcp-clients 访问） | important | §5.4 悬而未决标注 + §6.4 改 kernel 字段 |
| M1（§5.2 表格层次混淆） | minor | §5.2 拆两层 |
| M2（execute-streaming 不靠谱） | minor | §4.3 删除（v3 进一步：execute-single 也退回内部 helper） |
| M3（run-tools 措辞） | minor | §7 迁移时机说明 |
| M4（PR1 缺边界测试） | minor | §8 PR1 验收加 3 条 |
| C1（ToolSearch × backend） | missing | §5.7（新增） |
| C2（LLM 流式 scope） | missing | §4.3 显式声明 |
| C3（subagent 委托） | missing | §5.6 |
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

---

## 相关文档

- `advisor-alignment-design.md` §1.3（被本文档修订的旧立场）
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
