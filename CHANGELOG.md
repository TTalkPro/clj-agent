# Changelog

本项目版本号形如 `0.x.<git-count>`（各模块同步）。本文件按 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 组织。

## [0.3.0] - 未发布（2026-07-17 定稿；2026-07-11 启动）

v0.3 的主线：工具阶段 MapReduce 化（S1/S2）、HITL 持久化与 Timeline、
Spring AI 2.0 Advisor 全面对齐、ToolCallingManager 执行引擎、工具超时内建化、
缺省引擎串行化。设计与动机见 `docs/agent-loop-concurrency-design.md`（§9 为实施设计）。
从 v0.2 迁移请看文末[迁移指南](#从-v02-迁移到-v03)——**尤其第 1 节的升级陷阱**
（依赖批内并行的工具对在新的串行缺省下会**无错误、无日志地永久挂起**）。

### 💥 破坏性变更

- **缺省的 ToolCallingManager 改为串行**（2026-07-16 用户拍板）。不指定
  `:tool-manager` 时，同一轮的多个 tool-call **按调用序依次执行**。
  **要并发须显式注入** `(virtual-thread-tool-calling-manager)`（或 thread-pool 版）。
  *（这一条修正了本版早先的默认——见下条：并行曾是缺省。）*
  **理由**：并发要求同批工具的副作用彼此无序依赖——那是**调用方才知道**的性质，
  框架不该替它假定。串行是「无论工具长什么样都成立」的那个选择：顺序可预期、
  可调试、不会因为 LLM 某轮多发一个 tool-call 就把副作用交错起来。
  **状态语义不受影响**：三个引擎都是轮初快照 + `:writes` 屏障折叠。
- **工具超时缺省为「不超时」**，两个显式来源（2026-07-16 用户拍板）：

      工具声明 deftool {:timeout ms}  >  引擎缺省 (…-tool-calling-manager
      {:timeout ms})  >  不超时

  **时间上限属于执行策略，故随引擎构造**（三个引擎均接受 `:timeout`），
  而不是散落在 filter 里——与 beamai `manager_opts.tool_timeout` 同一立场。
  两者都由 `kernel/invoke-tool` 强制，**开箱即生效**；都没给则不起线程、
  零开销。框架不替调用方决定何时放弃。
- **`advisor/timeout-filter` 删除**（2026-07-16 用户拍板，紧随上条）：它的两个
  职责已分别被接管——强制力在 `kernel/invoke-tool`、整体缺省在引擎 `{:timeout ms}`；
  剩下的唯一辩护「按工具名/标签动态决定时限」按 §1 四问全落假想列（真要时自己写
  5 行 `:tool` filter 包 `tool/call-with-timeout` 即等价），且第三层优先级是纯粹的
  复杂度税（beamai 同样只有 tool_spec + manager opts 两个来源）。
  `build-func-def` 的 `:timeout` 字段随之删除（唯一读者就是该 filter——没有读者的
  字段就是下一个死选项）。机制本体 `tool/call-with-timeout` 保留并有直接单测。
  已入 `check_docs` 墓碑（经变异验证：README 里复活它会被 CI 逮住）。
  **迁移**：`(timeout-filter 5000)` → 引擎 `(…-tool-calling-manager {:timeout 5000})`。
- **同一轮的多个 tool-call 并行执行**（虚拟线程；批内任一工具声明
  `{:serial true}` 时整批退化为按序执行）。工具的执行环境从「批内穿线」
  改为「轮初快照」：同批工具互相看不到对方的写（此前语义全仓库零真实使用者）。
  **⚠️ 本条的「并行」部分已被上面第一条改回串行缺省**——并行机制仍在
  （`virtual-thread-tool-calling-manager`），只是不再是缺省；「轮初快照 + 屏障折叠」
  的状态语义**保持不变**，与选哪个引擎无关。
- **ToolContext 对工具/filter 只读**。工具写共享状态改走返回值
  `{:result r :writes {k v}}`（任意工具均可，不再要求 `{:context true}`）；
  屏障处按 tool-call 原始序经 `build-kernel` 新增的 `:state-slots` 槽级
  reducer 折叠（未声明槽默认 last-writer，冲突记 warn）。失败/超时/被拒的
  调用 `:writes` 不生效（单工具事务性）。
- **`kernel/invoke-tool` 返回 `{:value (:writes)}`**（原 `{:value :context}`）。
  跨轮折叠由调用方（react 循环）负责；直调方自行用 `context/apply-writes`。
- **tool filter 响应契约收窄为 `{:result (:writes)}`**：响应侧 `:context`
  移除——filter 短路分支不再需要手工回传 `(:context req)`（原易错点）。
- **工具/inline handler 返回值不再按「含 `:result` 的 map」拆包**，判据改为
  「含 `:writes`」；返回 `{:result ...}` 包装的旧 inline handler 需改为直接
  返回值（subagent delegate 已随迁）。
- `on-tool-result` 回调改为任务完成时实时触发，批内顺序不确定
   （确定顺序请读 `:tool-calls-made`）。
- **`LineageStore` 协议更名为 `BranchStore`**：与 beamai（Erlang 姐妹项目）命名对齐。方法名同步更新：
  `lineage-add!` → `record-branch!` / `lineage-get` → `get-branch` / `lineage-children` → `branch-children` / `lineage-remove!` → `delete-branch!`。
  实现类 `InMemoryLineageStore`/`SqliteLineageStore` → `InMemoryBranchStore`/`SqliteBranchStore`。
  工厂函数 `in-memory-lineage-store`/`sqlite-lineage-store` → `in-memory-branch-store`/`sqlite-branch-store`。
  deps map key `:lineage` → `:branch`（`timeline/fork!`/`rollback!`/`lineage`/`ancestry`/`prune!`）。
  SQL 表 `branch_lineage` → `branch_records`（跨版本需重建）；索引 `idx_lineage_parent` → `idx_branch_parent`。
  **用户-facing API 不变**：`lineage`（单记录查询）、`ancestry`、`fork!`、`rollback!`、`prune!`。
  中文描述词「血缘」/「分支血缘」不变。

### ✨ 新增

- **thinking 回传契约：可选协议 `IReplayableResponse` + 中立消息 `:blocks`**
  （2026-07-28，设计与三轮实验记录见 `docs/provider-variant-design.md`）。
  推理模型的 thinking 块（含 `signature`）此前在中立层被抹平——`response->neutral`
  只用 `:text` + `:tool-calls` 重建 assistant 消息，`wire/anthropic` 也只吐
  text/tool_use 两种块。于是**工具循环第二轮起，模型再也看不到自己前几轮的思考**。
  - **代价是实测出来的，不是从文档推的**：M3 上 A/B 对照（n=40/臂，7 步串行工具链，
    答案唯一可自动判定）——完整回传 **100%** 正确、逐轮全对 100%、40 次零方差；
    剥掉 thinking **82.5%** 正确、逐轮全对 47.5%、轮数 3–17。单侧 Fisher **p=0.0059**。
    （M2.x 不受影响：它关不掉 thinking，每轮必然思考。）
  - **机制**：新增**可选**协议 `im.ttalk.agent.model/IReplayableResponse`
    （单方法 `replay-blocks`），`service` 归一化时 `satisfies?` **探测**——
    不实现的 provider（含仓库外的实现）**一行不改**照常工作。
    **不往 `ILLMProvider` 加方法**：那是 DIP 的端口，加方法 = 所有实现方破坏性变更。
  - **core 只搬运不解释**：载荷带 `:format` 标签，core 全程不碰 `:data`，
    厂商 wire 知识仍归 provider（不违反 `response-path-consolidation.md`）。
  - **降级是缺省路径**：无 `:blocks` / `:format` 认不出（跨 provider 的历史）/
    `:data` 为空，一律走原来的 text+tool_use 重建，各有单测。
  - 陷阱记录：新协议**不得**加 `extend-type Object` 兜底——`ILLMProvider` 就因为有
    兜底，`satisfies?` 对任意非 nil 对象恒为 true（见 `model/provider?` 注释），
    而本机制全靠 `satisfies?`。单测专钉这条。
  - 验收：+12 tests / +45 assertions（全套 331/1369/0）；真机走 `create-agent`
    全链 **20/20 = 100%**，20 次全部恰好 7 次工具调用，与 A 臂零方差形态一致。

- `context/apply-writes`：批次写折叠纯函数（槽级 reducer + conflict 上报）。
- `build-kernel :state-slots`：状态槽合并语义声明。
- `deftool {:serial true}` / inline 工具 `:serial` 键 + `kernel/serial-tool?`。
- **工具失败分层路由（S2，纯增量）**：`model.error/classify-exception`
  （显式 `ex-data :error-class` > canonical `:retryable?`/`:auth-error` >
  常见网络异常 > 缺省 `:semantic`）；`invoke-tool`/`execute-batch` 透出
  `:error {:class :message}` / `:errors`。
- **瞬态类自动重试**：`deftool {:retry true|{:max-retries n :initial-delay-ms ms}}`
  （幂等工具 opt-in），仅 `:transient` 类错误指数退避重试，对模型透明；
  timeout-filter 的超时结果标 `:transient`。
- **环境类屏障暂停（HITL）**：react `:on-env-error :pause|:proceed`（缺省
  :proceed）——环境类失败在屏障处带一致快照暂停；resume 决策 `:retry`
  （重跑失败调用，结果按 tool-call-id 替换进原批次）| `:proceed`（错误交给
  模型）。client 层配置了 `:on-tool-call` 的 HITL agent 自动 `:pause`，
  `resume` 接受 `"retry"/"approved"` 表示环境已修复。
- **HITL 暂停态持久化（`im.ttalk.agent.pause`）**：PauseStore 协议 +
  in-memory / SQLite（EDN）实现；`create-agent :pause-store` 暂停快照自动
  落库、终态/`reset!`/新 chat 自动清除、`paused?`/`resume` 透明回落 store
  ——进程重启后同 conversation-id + 同 store 重建 agent 即可 resume
  （审批与 :env-retry 两种暂停均支持；对话历史配 SQLite ChatMemory）。
  `create-agent` 同时补透传 `:state-slots`。
- **Timeline 与多分支（`im.ttalk.agent.timeline`）**：对话日志即时间线，
  分支 = fork-as-new-conversation（前缀复制 + BranchStore 血缘记录，
  现有 ChatMemory 协议零改动）。`fork!`（暂停点全量 fork 连带复制暂停快照
  → HITL 决策分支：两支各自 resume 不同决策）/ `rollback!`（破坏性截断，
  "重新生成"）/ `lineage`/`ancestry` / `prune!`（有子分支拒绝）。
  合法 fork/rollback 点 = turn 边界 / 暂停点（一致性不变量）。
- **`:writes` 进历史（event-sourcing 伏笔）**：tool-result 中立消息可携带
  `:writes` 元数据（该工具对状态槽的写意图）——只进历史存储、不发给 LLM
  （wire 层剥除有测试钉住）；失败/被拒调用无 writes（与事务性同真同假）。
  立即价值为审计，为将来跨 turn 状态的 fold-from-history 留路。
- **Turn 级 filter 链（`:turn` 钩子）**：filter 第三个钩子，包整个工具循环
  （每 turn 一次），与 `:chat`（每轮 LLM 调用）/`:tool`（每次工具执行）并存。
  可改写入口 `:messages`/`:context`、可多次 `(chain req)` 递归重入
  （闭包洋葱天然"仅下游"）；`:paused`/`:cancelled`/`:error` 结果须透传。
  解锁每 turn 一次的 RAG 注入、最终答案 guardrail、turn 级预算。
  内置 `advisor/validation-turn-filter`（校验失败带反馈重入重试，
  对标 Spring AI 2.0 StructuredOutputValidationAdvisor）。`resume` 同样经过
  turn 链（TurnRequest 带 `:resume? true`；首调延续暂停 turn、递归重入走
  全新循环）——resume 完成的最终答案同样被校验/guardrail。
- **resume 带 payload（审批 phase，3-arity）**：
  `(resume agent "rejected" {:message 理由})` 结果「已拒绝执行：<理由>」；
  `(resume agent "approved" {:args 新参数})` pending 工具改参执行；
  `(resume agent "reply" {:message 答复})` 工具不执行、答复即结果——
  **ask-user 模式解锁**（提问工具 + gate 拦截 + reply 送回）。
  gate 决策词汇同步扩展（`{:reject 理由}`/`{:reply 结果}`）；loop-state
  新增 `:pending-id`；env 暂停显式拒收 reply。零破坏。
- **Token 流变换链（`:token-xform` 钩子，2026-07-14）**：filter 第四个钩子，
  值为 **transducer**，变换流式出站 token 流（1→N / 跨 chunk 状态 / 流末
  flush——completion arity 即 end-of-stream 信号）。组装在
  `invoke-chat-stream` terminal 内：provider 原始 token → xform 链（注册顺序）
  → on-token；正常完流 flush、stream-fn 异常不 flush（缓冲丢弃）、下游
  reduced 早停、无 xform 零开销退化。**硬边界**：只变换交付流，不改最终
  `:response`（memory/turn 用原文）。内置 `advisor/token-redact-filter`
  （无状态正则脱敏）与 `advisor/hold-release-filter`（先审后放：缓冲整流、
  完流 check-fn 全文，通过原序放行 / 不通过 emit 单个替换 token）。
  对标 Spring AI `StreamAdvisor`（Flux 一等流）——吸收算子思想，不引
  Reactor、不拆 Call/Stream 双接口。零破坏。
  设计见 `docs/token-stream-filter-design.md`。
- **Spring AI 2.0 Advisor 全面对齐（2026-07-15）**——逐个对完之后，真正的
  缺口只有一个半。设计与吸收记录见 `docs/advisor-alignment-design.md`。零破坏。
  - **ToolSearch（`advisor/tool-search`，对标 `ToolSearchToolCallingAdvisor`）**：
    渐进式工具披露——初始只暴露 `search_tools`，模型检索到的工具下一轮才进
    工具列表（Spring 实测省 34–64% token）。**零新增钩子**：`search_tools` 是
    普通内联工具，返回 `{:writes {::discovered #{名字}}}` → 屏障按槽 reducer
    折叠（`into` = 集合并，跨轮累积）→ 每轮进 ChatRequest `:context` →
    `:chat` filter 据此重写 `:tools`。发现集合住在 tool-context 里，故
    暂停/resume/持久化全自动正确。索引零依赖可插拔：内置
    `keyword-tool-index`（名称/描述分词 **× IDF** 打分，**中文二元组切分**，拆
    snake_case/camelCase）与 `regex-tool-index`（名称模式，非法正则退化字面
    匹配不抛异常）；向量检索经 `IToolIndex` 注入。`with-tool-search` 一次装好
    工具/filter/状态槽三处。
    **live 实测（MiniMax-M2.7，50 工具目录）省 78% prompt token，且任务质量
    与基线一致**（可复现：`examples/toolsearch_live_test.clj`，11 项行为断言）。
    赚不赚看**工具定义总量**而非工具个数——固定成本约 600–1000 token（多一轮
    LLM 往返），沿用 Spring 的度量「工具定义 >5K token 才用」。
    ⚠️ **省 token 未必省钱**：基线的静态工具前缀天然适合 prompt cache，本
    filter 每轮改写 `:tools` 会打碎可缓存前缀。按缓存读 10% 计价折算，实测
    **冷缓存下 ToolSearch 省 65%、热缓存下反贵 66%**——差别全在基线前缀的冷热。
    工具集静态 + 高频会话 + 廉价缓存时，省的是上下文窗口而非钱。适用判断见
    `docs/advisor-alignment-design.md` §2.4。
  - **return-direct（deftool `{:return-direct true}`）**：工具结果即最终答案，
    不再回灌 LLM（转人工/升级工单/guardrail）。整批全声明才生效（对齐 Spring
    allMatch）；多个结果按原序拼接。**补落库**：正常路径下工具结果靠下一次
    invoke-chat 经 memory filter 落库，return-direct 没有下一次——不补则历史
    留下悬空 tool_use 并被下个 turn 的 heal 整条摘掉。
  - **可插拔续跑判据（`build-kernel` `:eligibility-fn`，对标
    `ToolExecutionEligibilityChecker`）**：`(fn [response context] -> boolean)`，
    响应带 tool-call 时是否真的执行并续跑；返回 false 则按最终答案收尾。
    缺省恒真（行为不变）。
  - **`advisor/structured-output`（补 `StructuredOutputValidationAdvisor` 的
    判据侧）**：`validation-turn-filter` 早有机制，缺的是判据——按 JSON Schema
    子集校验并把失败写成模型能据以自我修正的人话（「缺少必填字段 films」/
    「字段 films[1] 期望 string，实为 integer」）。`validate-value` 纯函数零
    依赖；`validate-fn` 的 JSON 解析经 `:parse-fn` 注入（core 无依赖，不内置
    解析器）。
  - **`safeguard-turn-filter`（对标 `SafeGuardAdvisor`）**：敏感词命中即不进
    循环直接拒答。**推翻旧决定**（原记「用户一个 chat filter 即可」）——那句
    写在 turn 链之前且挂点有误：`:chat` 会每轮重查累积历史。大小写不敏感。
  - **`advisor/rag` `qa-turn-filter`（对标 `QuestionAnswerAdvisor`）**：每 turn
    检索一次并拼进用户问题。**推翻旧决定**（原记「不跟本体」）——推翻的是
    「不做本体」而非「不引 vector store」：仍零检索依赖，向量库经 `IRetriever`
    注入；本体价值在提示词编排不在检索。检索为空时不注入（刻意偏离 Spring 的
    空上下文 + 拒答指令，可用 `:inject-when-empty?` 恢复）。
  - **`logging-chat-filter`（对标 `SimpleLoggerAdvisor`）**：既有
    `logging-filter` 只覆盖工具侧，补 LLM 侧请求/响应日志。
  - **`re-reading-filter`（对标 `ReReadingAdvisor`，RE2）**：入口用户问题重复
    一遍附在其后。
- **ToolCallingManager 协议（`core/tool-calling-manager.clj`，2026-07-15）**：
  工具执行入口升格为可注入协议。单方法 `execute-tool-calls [this kernel response opts]`
  （Spring AI 2.0 `ToolCallingManager` 对齐签名，内部抽 tool_calls）。**多 impl 是
  核心特性**——不同 record 选不同执行策略：
  `VirtualThreadToolCallingManager`（默认，委托现有 5-arity `execute-batch`，尊重
  `:serial` 声明）+ `SequentialToolCallingManager`（独立顺序路径，全串行无并发）
  + `ThreadPoolToolCallingManager`（有界平台线程池，见下）。
  `build-kernel` 新增可选 `:tool-manager` 字段（`Kernel` record 第 7 字段，缺省 nil
  走原路径，**零行为变化**）。`execute-batch` / `execute-single` 退回成每个 record 的
  **内部 helper**（不是协议方法）——怎么实现完全是 record 自己的选择。
  **边界契约**（实证测试）：manager 不夺 `:serial` / `:tool` filter / `:writes` 屏障
  的权——注入 mock manager 仍走 kernel 原语，三条不变量各有测试钉住。
  **定位：manager 是 clj-agent 的工具执行引擎**——拿到一批已批准的 tool-call，
  决定怎么把它们跑完：**线程模型 + 隔离边界 + 调度策略**。换 manager = 换引擎。
  **修订 `advisor-alignment-design.md` §1.3**（《ToolCallingManager —— 我们不长
  这个抽象》旧立场推翻）：§1.3 的落点建议（kernel `:settings` 注入 executor）
  **低估了要换的东西**——单键换的是「线程从哪来」，manager 换的是引擎。
  三条能力级差异：(a) 调度决策（`react.clj:167` 的 `(if (or serial? (<= count 1)) ...)`）
  硬编码在框架里，`Executor` 接口够不着；(b) `SequentialToolCallingManager`
  **全程不构造 `Future`**（`react.clj:207` 独立路径），same-thread executor 模拟不出
  （仍会背上 `.submit`/`.get` 的包装与中断语义）；(c) 池的生命周期须与策略打包
  （Sequential 无资源、ThreadPool 持可关停的池），单键收下别人造的 executor 后
  生命周期无人认领。**隔离缺口**：`react.clj:66` 的虚拟线程 executor 是
  **进程全局 `def` + `delay`**，同 JVM 所有 kernel / agent / 子 agent 共享——
  无舱壁、无限流、无可关停边界；`ThreadPoolToolCallingManager`（每实例一个有界池）
  正是为此留的位置。
  设计见 `docs/tool-calling-manager-design.md`（v5，含 Oracle review 16 项处置 + 23 条
  决策记录 + §0.5 设计原则）。
- **文档体系整理：原则提取 + 目录合并 + 索引**（无代码变更）。
  - **新增 `docs/design-principles.md`——项目级设计原则的唯一出处**（硬约束）：
    原则散在个案里的下场是下一份设计文档再把它重新推导一遍，故抽出集中收录，
    各设计文档改为**回指不重述**。首批两条——**§1《无真实需求不建》**（新提）：
    抽象要由真实需求触发，不由对齐别人 / 对称性 / 「以后可能要」触发；用户几行
    代码就能等价做到的事，框架不长字段、不开 seam、不加协议。本是
    `advisor-alignment-design.md` §1.3 的「立项判据」，因在
    `tool-calling-manager-design.md` 里被反复重新发现（毙 `deftool :backend`、
    毙 PR2/PR3、拆掉一个防御性保护）而提为原则；含四问判据、落地约束、案例法
    （含 `ToolCallingManager` 自己险些踩中：立项两条腿里「多 transport」那条是
    假想的，且当时看起来比真实那条更有说服力）。**§2《框架无关》**：收录自
    `streaming-async-design.md` §0.5，内容不变。两条分工——§1 管纵向（抽象该不该
    存在），§2 管横向（依赖该不该跨进来）。
  - **`design/` 并入 `docs/`，不再分两处**：7 份文档经 `git mv` 迁移（rename 历史
    保留，内容不变），`design/` 删除。那条界线是历史形成的（`design/` 早期放专题/
    重构笔记，`docs/` 放主设计文档），但两边都在长，且「这份算专题还是主文档」没有
    可判定的标准；跨目录相对链接（`../docs/…`）也是纯粹的税。全仓 13 处路径引用
    同步更新（含 `provider/anthropic.clj`、`provider/http/client.clj` 两个 docstring
    与 `examples/streaming/README.md`）。老路径 `design/xxx.md` → `docs/xxx.md`
    一一对应，无重命名。
  - **新增 `docs/README.md` 索引**：按「权威参考 / 已实施专题 / 进行中 / 已废弃留档」
    分组，16 份全部收录；沿用各文档头部既有的 `状态：` 行作为「当下是否算数」的判据
    ——对 process V1/V2/Timeline 那三份废弃留档尤其要紧（讲得头头是道但不代表现状）。
- **新增 `ThreadPoolToolCallingManager`——有界池引擎（舱壁隔离）**：
  `(react/thread-pool-tool-calling-manager {:pool-size 8 :thread-name-prefix "..."})`，
  缺省 pool-size = `availableProcessors`。工具批在**本实例自己的池**里跑，
  并发上限 = pool-size，不与其他 kernel 互挤——这是默认 VT 引擎给不了的
  （后者用进程全局无界 executor）。线程为 daemon（用户忘记关停也不吊住 JVM 退出）。
  **池的生命周期归持有者**：record 实现 `java.io.Closeable`，可直接 `with-open`，
  或调 `react/shutdown-tool-calling-manager!`（对无资源的引擎是 no-op）；
  关停后再执行工具批抛 `ex-info`（`:error-class :environment`），不静默失败。
  **不变量：一个引擎属于一个 kernel，不跨 delegate 边界**——子 agent 自有引擎，
  这本来就是默认，框架里没有共享路径（`delegate` 的 subagent-config 全部来自用户的
  `:subagent-fn`，父 kernel 的 `:tool-manager` 流不进去）。⚠️ 别绕过默认去踩：
  亲手把同一个有界实例塞进 `subagent-config` 会死锁（`delegate-tool` 是
  spawn→await→drop，父批工具占着池线程等子 agent，子 agent 的批又要同一个池的线程，
  互等）；同理别在某工具函数体里再拿同一实例跑一批。**框架不为此设防**——跨 delegate
  的情形线程局部标记根本测不到，同线程的情形无真实需求，不变量本身就是答案。
  子 agent 要限流请给它自己的实例；不需要就留默认 VT 引擎。
- **重构（内部，行为不变）**：三个引擎的 map + 屏障骨架抽成 `execute-batch-via`——
  gate 预判、`:serial` 整批退化、`ctx/apply-writes` 折叠、messages/records/errors
  按原序排回，**一处实现**；引擎只负责挑 executor（nil = 全程内联，不构造 `Future`）。
  此前 VT 与 Sequential 各持一份拷贝，契约靠两边同步维持。公开 `react/execute-batch`
  的 5/6-arity 签名与行为不变。
- **`deftool :backend`（HTTP / MCP transport 声明）❌ 否决，不会实施**（2026-07-16
  用户拍板）：**工具走 HTTP 还是 MCP 是函数体内部实现**——既不影响 LLM 怎么用这个
  工具，也不影响框架怎么调度它（gate / 并行 / 重试 / writes 折叠待遇完全相同），
  框架不该知道。Clojure 里函数是一等值，Spring `ToolCallback` 用接口承担的 transport
  多态，在这里由**函数体本身**承担；再加一层 `:backend` defmulti 是把宿主语言已经
  免费提供的能力重新实现一遍。代价（`deftool` 宏多 5 个字段 + 2 条编译期检查、
  `invoke-backend` defmulti 成永久公开 API 面、core/client 跨模块耦合、kernel 加
  `:mcp-clients` 字段、inline tools 路径天生接不上、声明式配置表达力反而弱于手写）
  远超收益（省 5-10 行 HTTP 包装）。此前（2026-07-15）标记为「⏸️ 搁置待真实需求」，
  现改为**否决**——即使出现 HTTP/MCP 需求也不按此设计做，`deftool` 内直接写
  `(http/post ...)` / `(mcp/call-tool ...)` 即可。**`deftool` / `tool/invoke` /
  `Kernel` 均无改动**。否决理由与唯一可能重开的场景（远端动态工具发现，且届时
  应重新设计）见设计文档 §5。**对 `ToolCallingManager` 零影响**——manager 管
  「怎么执行」（线程模型 / 隔离 / 调度），`:backend` 想管「执行的是什么」
  （transport），两件事不相干。

### 🐛 修复

- **`create-agent` 递不到 provider 专属能力**（2026-07-28，见
  `docs/provider-variant-design.md` §6.1）：`common/build-kernel` 用白名单
  `{:model :max-tokens :temperature}` 组装 provider 调用 config，于是 provider 侧
  **明明实现了**的能力——Anthropic/MiniMax 的 `:thinking`、`:cache-strategy`、
  `:service-tier`、`:top-k`、`:beta`、`:retry` 等——走 agent 门面全部**静默丢弃**，
  只能自建 kernel/service 绕开。`anthropic/build-params` 早就认 `:thinking`，
  是门面把它挡在外面：**说了能用却用不了**。
  改为排除法（新增公开的 `common/service-config` + `orchestration-keys`）：
  编排层的键不下沉，其余一律透传。
  - **风险面朝向反了才安全**：白名单漏一个键 = 一个能力静默失效（只在用户报「为什么
    不生效」时才发现）；排除法漏一个键 = 编排层的值被塞给 provider，**当场炸在测试里**。
  - **`:tools` 是名单里最危险的一条**（两边都有、含义不同：service config 里是已编译
    schema，agent 里是 tool var 向量）。漏下去 provider 转出 `{:name nil}`，MiniMax
    报 400「function name is empty」——P0 实验第一版真撞过，故单测专钉这条。
  - 显式 `nil` 不下沉（否则 provider 侧 `(some? temperature)` 这类判据会被 nil 骗过）。
  - +3 tests / +17 assertions（`service_config_test.clj`）；真机 M2.7 冒烟通过。
- **resume 丢弃暂停前累积的 context 状态槽**：`client/resume` 此前用仅含
  conversation-id 的裸 context 续跑，S1 的 `:writes` 折叠结果跨 resume 即失
  （S1 之前 context 无内容故无感）。现 resume context 恢复自暂停态的
  `:tool-context`（本进程与跨重启两路径同修）。
- **`deftool :timeout` 曾是死选项**（2026-07-16，设计见
  `docs/tool-timeout-design.md`）：`:timeout` 在 opts 白名单里却从不生成
  `:tool/timeout` 元数据、全库零读取——声明 `{:timeout 500}` 编译通过、无警告、
  零效果（实测睡 3s 的工具跑满全程）。现经 `tool/timeout-spec` →
  `kernel/build-func-def` 透传，由 `timeout-filter` 消费，优先级：
  **工具声明 > filter 构造缺省**（对齐 beamai `resolve_timeout` 的策略层）。
  仍为 opt-in：不挂 timeout-filter 即无超时。
- **`timeout-filter` 把工具搬去平台线程**（同上设计文档 §4）：曾用
  `clojure.core/future`（send-off 无界平台线程池）包裹下游——挂了该 filter，
  VirtualThread 引擎的工具实际跑在平台线程上，且超时被弃的任务在 send-off 池
  无界堆积、ThreadPool 引擎的舱壁 bound 不住。现改 `Thread/startVirtualThread`；
  顺带下游异常**原样重抛**（不再包 `ExecutionException`）。
  docstring 同步如实声明语义：**JVM 上超时 = 放弃等待 ≠ 终止执行**（无 kill
  原语；CPU 循环/普通 socket read 打不断，副作用可能在超时结果返回后落地；
  声明 `:retry` 的超时工具必须幂等——重试时上一次调用可能仍在跑）。

### 🔧 内部 / 测试

- **构建/开发脚本重做：5 个 shell 脚本 → `bb.edn`，3 份 build.clj → 根 `build.clj`**
  （2026-07-28）。`scripts/{build,clean,install,test,repl}-all.sh` 与三个模块各自的
  `build.clj`（含各自的 `:build` alias）全部删除。
  - **任务入口** `bb.edn`：`bb test [module] / check-docs / check / jar / install /
    release / deploy / version / repl [example]`。CI 与本地从此跑**同一条命令**
    （workflow 改为 `bb test <module>` 与 `bb check-docs`）。
  - **构建本体** 根 `build.clj`：三份 95% 雷同的 build.clj 合成一张模块表 + 一套函数
    （同一 pom-data、同一 `0.3.<git-count>` 版本、同一段 override-deps 注释，
    连「`b/install` 必填 `:class-dir`」这条踩坑记录都曾抄三遍）。切模块靠
    `b/set-project-root!`——tools.build 全部路径相对 `*project-root*` 解析；
    deps-deploy 不吃它，故 `deploy` 的路径显式 `b/resolve-path`。
    三模块一个 JVM 跑完，不再 `cd` + 起 6 次 clojure。
  - **顺带修掉一个真 bug**：旧 `build-all.sh` 只 `clean`+`jar`，从不 `install`，而
    client/provider 的 basis 把 core 覆盖成同版本 `:mvn/version`——**fresh clone 上
    它必然解析失败**（且列表里根本没有 client，与 `test-all.sh` 早先漏测 client 同款
    毛病）。现在 `release` 逐模块 clean→jar→install，单独 `jar client` 也会自动补一次
    core；pom 里 core 依赖坐标已实测正确。
  - `scripts/check_docs.clj` **留在 JVM Clojure 上**（`bb check-docs` 只是入口）：
    门禁要 require 全部源码 ns 再 `ns-resolve`，而源码带 next.jdbc / sqlite，bb 跑不动。
- 新语义测试：S1 批次 9 个（真并行证明、快照隔离、last-writer 按调用序而非
  完成序、失败 writes 丢弃、messages/records 原序、serial 整批退化、reject、
  reducer 折叠与跨轮累积）+ S2 分类/重试/环境暂停 5 组 + HITL 持久化 6 个
  （EDN 往返、跨重启审批与 env-retry 恢复、context 恢复回归、清理时机）
  + Timeline 7 个（writes 落历史与 wire 剥除、fork/血缘/rollback/prune、
  HITL 决策分支、编辑重试）。全套 230 tests / 971 assertions / 0 failures。
- **Advisor 对齐批次测试（2026-07-15）**：253/1039 → **292 tests / 1194
  assertions / 0 failures**（+39 tests / +155 assertions，零回归）。
  - 先补钉子后动工：`:chat` filter 改写 `:tools` 抵达 provider 的契约此前
    **无测试覆盖**，而 ToolSearch 完全建立在它之上——先补 3 个断言钉住
    （含 context 驱动的动态工具集），再实现。
  - ToolSearch：索引单测 8 组（中文二元组/camelCase/snake_case/limit/权重/
    重建/非法正则）+ 端到端 3 组（真实 react 循环跑通「只见 search_tools →
    检索 → 下一轮见到工具 → 调用」完整往返、两次检索并集累积、未命中不污染
    context）。
  - return-direct 5 组（LLM 只调一次、**transcript 完整无悬空且下个 turn 的
    heal 不摘**、混批仍回灌、多结果拼接、tool-calls-made 记录）；
    eligibility-fn 3 组。
  - structured-output 10 组（必填/类型/枚举/嵌套路径/数组下标/布尔不算
    integer/字符串键/开放世界/围栏剥离/驱动重试反馈带具体问题）。
  - safeguard 7 组（短路不进循环、大小写、resume 放行、多模态 content）、
    re-reading 4 组、logging-chat 3 组、rag 9 组（每 turn 只检索一次、空检索
    不注入、多模态不改写、只改最后一条 user 消息）。
- **文档一致性门禁（`scripts/check_docs.clj`）+ 六个 README 的幽灵 API 清理**：
  一轮排查发现文档里积了一批**幽灵 API**——功能早被删/改，源码里甚至留了
  「已移除」的注释，文档却没跟：
  - `:build-result-msgs`（service map 的历史键）——`model/service.clj` 明写它已
    移除，**四个 README 却还在头部特性 bullet 里教人用**；真实契约是
    `:chat-fn` + `:stream-fn`，而 `:stream-fn` 一处没提；
  - `proto/call-with-tools`——协议里**根本没这个方法**（真实为 `call-llm` +
    `extract-tool-calls`/`extract-text`）；
  - `find-function` → `{:plugin p :tool-var v}`（`:plugin` 不存在）、
    `invoke-tool` → `{:value r :context ctx}`（`:context` v0.3 已删）、
    `chat-fn` → `:assistant-msg`（早已归一化为 ILLMResponse）、
    `im.ttalk.agent.model.types`（从未存在）、filter 的 `:order`/`:phase`/
    `:before`/`:after`（早已移除）；
  - **模块索引隐身**：`pause` / `timeline` 整块功能不在 client README 里；
    DashScope 在 provider README 出现 0 次（却已注册并有专属 SSE 解析器）；
    `modules/README.md` 的依赖图把 core 标成「协议 + Agent 运行时」且
    **整个 client 模块不在图里**（2026-07 拆分前的图）。
  全部修正，并加门禁挡住复发：**ns 存在 / ns 覆盖 / 符号 resolve / 墓碑**四项，
  接入 CI（新增 `docs` job）与 `scripts/test-all.sh`。设计取舍是**宁可漏报不可
  误报**（alias 未由同文件 require 绑定即跳过；注释与字符串字面量先剥掉）——
  会误报的门禁很快就会被 `|| true` 绕过。四项均已用变异测试验证真的会失败。
- **`scripts/test-all.sh` 漏测整个 client 模块**：MODULES 列表只有 core 与
  provider（CI matrix 有 client，本脚本没有）——本地 `test-all` 长期静默跳过
  Agent 运行时。已补。
- **live 验证脚本（真实 provider）**：五个新脚本，共 **78 项行为断言**，均已实跑
  通过（各自反复跑 2–3 遍稳定）。断言一律钉**机制**（发给 provider 的消息/工具集、
  LLM 调用次数、落库形状），不钉模型措辞——后者会波动，拿它当断言等于给 CI 埋雷；
  模型的实际回答只打印不断言。
  - 新增 `examples/safeguard_live_test.clj`（18 项）——拦截逻辑本身有单测，live
    验的是单测证明不了的：**拦下时真的一次 LLM 都没调**（短路在 :turn 层，连接
    都没建）、**不落库的语义后果在真实多轮里长什么样**（第 2 轮模型答「我没有
    之前的记录，这是对话的开始」——刻意取舍的代价看得见）。并跑出了语义边界：
    工具结果里含敏感词照样通过——**SafeGuard 是入口守卫，不是输出守卫**。
  - 新增 `examples/return_direct_live_test.clj`（19 项）——**对照组**是重点：
    同一句合规话术，return-direct → 逐字送达（LLM 只调 1 次）；普通工具 →
    被模型回灌改写（`【工单已创建】…` 变成 `已为您创建转接工单…`）。两边一比
    才知道 return-direct 在防什么。场景 3 用真实第二轮对话验证**补落库的修复**：
    模型答得出上一轮的工单号 T-88123，证明 transcript 没被 heal 摘掉。
    另含 `:eligibility-fn` 的放行/拦停对照。
  - 新增 `examples/rag_live_test.clj`（18 项）——语料全为**虚构事实**（模型训练
    数据里不可能有「每 42 天除垢」），故对照组答不出、RAG 组答得出，**grounding
    真的来自检索**这件事才算被证明。并实跑印证了对 Spring 的刻意偏离：同一个
    无关问题，默认（空检索不注入）→ 模型正常作诗；`:inject-when-empty? true`
    （Spring 行为）→ 模型拒答「没有足够的上下文信息」。
  - 新增 `examples/structured_output_live_test.clj`（12 项）——校验器本身有单测，
    live 唯一值得验的是单测证明不了的：**把「缺少必填字段 internal_review_code」
    丢回给真实模型，它会不会真把字段补上**。用「schema 要求 prompt 里没提过的
    字段」触发**真实**失败（不作假），实测第 1 轮漏、反馈点名、第 2 轮补上、
    校验通过。另钉死「合格只调 1 次 LLM」与「耗尽恰好 2 次、原样返回」。
  - 新增 `examples/toolsearch_live_test.clj`（11 项）——ToolSearch × MiniMax 端到端：
    11 项行为断言（渐进式披露成立 / 发现集合跨轮累积 / 任务质量不掉 / 大目录
    真省 token）+ 冷热缓存对照报告。**跑真机推翻了三个基于单测的判断**：
    检索工具描述缺「多能力须各检索一次」会静默掉召回、关键词索引缺 IDF、
    prompt cache 会让 token 对照得出反向结论。
  - 修复 `examples/minimax_agent_live_test.clj` 的**两处静默失效**（该脚本因
    环境变量改名 `MINIMAX_AUTH_TOKEN` → `MINIMAX_API_KEY` 早已启动即 exit 1，
    无人跑到，assertion 悄悄烂掉）：① 环境变量两个都接受；② `on-tool-call`
    的 `tool-name` 契约是**字符串**，脚本却拿 keyword 比较——scenario 1 断言
    直接失败，scenario 2 更严重：gate 的 `(= :send-email n)` 永不相等 → 永不
    中断 → 中断/恢复整条链路静默失效。`callbacks.clj` 文档补上类型说明防复发。
- **工具超时批次测试（2026-07-16）**：300/1227 → **307 tests / 1251 assertions /
  0 failures**（+9 tests / +29 assertions，含上条绑定修复的 2 个，零回归）。元数据回归钉 + 端到端穿
  `build-func-def`（修复前该用例睡满 60s）+「裸 `invoke` 不超时」语义钉；
  优先级三分支；虚拟线程 `.isVirtual` 断言 + 异常原样重抛；超时→`:transient`
  →`:retry` 重试整链；同批部分结果（慢者超时不殃及快者）；**诚实测试**——
  CPU 忙循环在超时结果返回后仍在跳（钉住「放弃等待 ≠ 终止执行」的真实语义，
  防后人误以为有 kill）。
- **工具抛 `Error` 会打死整个工具循环**（2026-07-16）：`tool/invoke`、
  `kernel/invoke-tool` 的两个 terminal、`react/invoke-one` 四处一律 `catch Exception`，
  于是 `Error` 全部逃逸——一个工具的深递归 `StackOverflowError` 整轮死，而分层错误
  路由（`:semantic`/`:transient`/`:environment`）的全部意义就是「一个工具坏了不牵连
  别人」。现四处改收 `Throwable`：**非致命 Error 收敛**为该工具的错误结果（经
  `classify-exception` 归类，缺省 `:semantic`），**致命的仍原样上抛**——判据见新增的
  `model.error/fatal-throwable?`：致命 = `VirtualMachineError` 中除
  `StackOverflowError` 外的那些（`OutOfMemoryError`/`InternalError`/`UnknownError`）
  + `ThreadDeath`。吞掉 OOM 只会掩盖真因，且收敛动作本身还要分配内存。
  与 Scala `NonFatal` 的**有意分歧**：它把整个 `VirtualMachineError` 划为致命，
  但这里的 Throwable 来自**用户工具函数体**——栈溢出是它最常见的自伤方式，
  栈一退就恢复，正是最该收敛成「这一个工具失败」的那类。
- **`deftool :timeout` 只有挂 filter 才生效 / 内联工具完全无效 / 值零校验**
  （2026-07-16 code review）——三条都是上一条 `:timeout` 修复没做完的部分：
  - **内联工具的 `:timeout` 仍被静默忽略**：根因是**两个 func-def 构造点**（var 走
    `build-func-def`、inline 另行硬编码），而 inline 的 `:serial`/`:retry` 一直生效。
    现新增 `kernel/tool-timeout`（var + inline 双分支，与 `serial-tool?`/`retry-policy`/
    `return-direct-tool?` 同款），**两个构造点合并为一**（新增字段只加一处）。
    `delegate-tool` 恰恰是内联且跑整个子 agent——最需要超时的正是拿不到超时的。
  - **`:timeout` 是唯一不「开箱即生效」的 `deftool` 选项**（`:serial`/`:retry`/
    `:return-direct` 都由 kernel/react 直接消费）：用户写下 `{:timeout 5000}` 不挂
    filter → 编译通过、无警告、零效果，**与死选项 bug 的症状逐字相同**。现强制力落到
    **`kernel/invoke-tool`**（单次调用的时间上限是它的职责；`:retry`/`:serial` 那些是
    循环/批次策略故属 react），**仅在声明时起线程**（未声明零开销）。
    `timeout-filter` 相应**退为纯缺省**：只管未声明的工具，**见到声明即让位**——
    优先级「声明 > filter 缺省」由让位实现，不比大小，同一 deadline 不套两层线程。
  - **`:timeout` 值零校验**：`"5s"` 曾每次调用抛 `ClassCastException`、`-1` 曾让工具
    每次**静默立刻超时**、`2.7` 被静默截断。现 `build-kernel` **装配期**校验
    （var 与 inline 汇合、尚未执行的最早时点），坏值在造 kernel 时就炸且报错点名。
- **工具执行丢失调用方的动态绑定**（2026-07-16，两处，均为**静默**给根值——
  无报错、只是悄悄读错）：
  - **`run-on-executor` 从不传导绑定帧**（既有 bug）：于是同一个工具会因
    「这批里有几个 tool-call」而看到不同的 `binding` 值——批内 1 个走 `run-inline`
    （调用方线程，绑定可见）、≥2 个走 executor（绑定丢失），**而发几个 tool-call
    是 LLM 临场决定的**；换引擎（Sequential 全程内联 → 可见 / VirtualThread →
    丢失）同样会变。这违反 `react.clj` 明写的引擎契约「引擎只决定『怎么把这批
    跑完』，不决定『跑的是什么』」。
  - **`timeout-filter` 改虚拟线程时丢了传导**（本次引入的回归）：原
    `clojure.core/future` 自带绑定传导，`Thread/startVirtualThread` 没有。
  两处均改用 `bound-fn*` 包装任务，与 `future` / `pmap` 的传导语义一致。修复后
  四条路径（内联 / executor / Sequential / VirtualThread）行为一致，挂不挂
  `timeout-filter` 也不再改变工具看到的 `binding`。回归测试钉住批次大小与引擎
  两个维度。
- **工具超时批次终态**：全套 **314 tests / 1295 assertions / 0 failures**
  （超时/绑定/Error 收敛/缺省机制/filter 删除各阶段的独立计数见 TASK P8 各节）。
- **工具超时 live 验证（`examples/tool_timeout_live_test.clj`，MiniMax 真实
  provider）**：20 项行为断言，**四遍稳定**（含 timeout-filter 删除后以
  「引擎缺省 + 声明」新机制的复验；场景 4 abandon 差值四次实测
  1701/1702/1700/1700ms，恒定 ≈ CPU 循环 2500ms − 超时 800ms）。慢后端是本地裸 TCP（零依赖不联网），
  只有 LLM 是真的。四场景：超时→模型理解错误→循环存活作答 / 对照组（同为 3s 的
  活儿，声明 6s 救活 vs 未声明被 800ms 缺省杀死）/ `:retry` 透明重试（实跑 2 次、
  模型只见成功结果）/ abandon 残余风险（CPU 忙循环副作用晚 1.7s 落地）。
  **跑真机推翻了一个基于常识的判断**：设计文档初稿断言「socket read 打不断」是
  残余风险主体——实测 JDK 25 上**虚拟线程 + socket read 会被 interrupt 打断**
  （`SocketException: Closed by interrupt`；JEP 353 把 Socket 重实现在 NIO 上），
  平台线程才无视 interrupt。故上面那条 `timeout-filter` 修复**比初判更有价值**：
  改虚拟线程不只修了线程模型，还把最常见的工具形态（阻塞 IO / HTTP 调用）从
  「打不断」变成了「真能取消」——超时后 socket 立刻关闭，副作用不落地。
  残余风险收窄为不检查中断标志的 CPU 密集代码、native 调用等。设计文档 §2.2
  已加修订块记录。
- **P9 code review 第二轮**（2026-07-16，10 条 CONFIRMED 全部修复）：
  - **R1/R3 超时包裹 filter 链而非工具本体**：`run-chain` 把整条 `:tool` filter 链
    （含 approval-filter 的阻塞审批）一起计时——操作员审批慢即超时，超时结果在链外
    合成（日志/审计 filter 看不到）。超时下沉到 terminal（只包工具本体），filter 链
    在计时区外。
  - **R2 超时起 VT 与引擎线程模型冲突**：声明超时的工具总是跑在 VT 上（`call-with-timeout`），
    Sequential「调用方线程」与 ThreadPool「舱壁」承诺在有超时不成立——诚实降级 docstring。
  - **R4 manager :timeout 读 kernel 反向指针**：instrumented wrapper / 独立传入的 manager
    会读错对象。改动态 var `*active-manager-timeout*`，各 record 入口 binding 自身 `:timeout`。
  - **R5 `:tool-manager` 的 `:timeout` 零校验**：`:tool-manager {:timeout 0}` 每次调用瞬时
    超时。`build-kernel` 装配期一并过 `valid-timeout?`（消息串统一走 `tool/check-timeout!`）。
  - **R6 delegate 被引擎超时打断 → kill!/drop! 跳过**：子 agent 泄漏且继续烧 token。
    `run-sync` 加 try/finally（`drop!` 内先 `kill!`，中断路径一条不漏）；
    delegate-tool / fanout-tool 写 `:timeout` 进 inline map（声明恒优先，引擎缺省
    砍不掉它）。**fanout-tool 的 try/finally 为事后自查补漏**（首轮只修了
    delegate 的 run-sync，同型的 fanout await 循环漏了——第二次 code review 逮到）。
  - **R7 executor 路径 OOM 被 Future.get 包成 ExecutionException**：`invoke-one` 重抛的
    OOM 被 EE 包装（普通 Exception），`fatal-throwable?` 认不出。`run-on-executor` 拆 cause
    原样重抛。
  - **R8-R10 docstring/测试陈述过时**：run-tools 恒串行但吃引擎 :timeout（docstring 如实写明）；
    ThreadPool :timeout 因果倒置（无超时卡死才占槽，非被超时放弃的占）；tool_test「声明无强制力」
    与「开箱即生效」矛盾（改为「裸 tool/invoke 不超时」）。
  - **次级 cleanup**（helper 提取，零行为变化）：`err/contain-throwable` 收口 4 处
    catch-Throwable 三元组；`tool/check-timeout!` 统一超时校验消息串；`:retry` 装配期校验
    （与 `:timeout` 对称）；`inline-meta` 预计算消除 4 处 by-name O(n) 扫描；live 脚本
    `cond->` 冗余 + test sleep 减半。
  - **收尾（2026-07-17）**：`tool-timeout-design.md` 补 **§5.6 后记**记录 P9 的两个
    架构事实变更（超时包裹点 链→terminal、引擎缺省读取 字段回读→动态 var），
    §3.5 旧修订块加指针防读旧停留；advisor_test 的 5 处裸 map 引擎桩
    `{:timeout ms}` 换 `StubManager` defrecord（协议 + 字段双契约；裸 map 进真实
    react 循环会抛 No implementation of method 的陷阱先例清除；注意 reify 不可用
    ——关键字查找拿不到 reify 的值，必须 defrecord）。
  - 全套 **316 tests / 1307 assertions / 0 failures**，MiniMax live 20 项**六遍稳定**
    （场景 4 abandon 差值六次实测 1701/1702/1700/1700/1700/1700ms，恒定 ≈ CPU 循环
    2500ms − 超时 800ms——修复前后跨越了架构重排，测量纹丝不动）。

## [0.2.0] - 未发布（2026-07-10 定稿）

v0.2 是一次**破坏性**版本：统一消息/tool-call 词汇、模块重组、删除无消费者的子系统。
从 v0.1 迁移请看文末[迁移指南](#从-v01-迁移到-v02)。

### 💥 破坏性变更

- **tool-call 全库统一为 `{:id, :name(字符串), :args}`**（原响应层为
  `{:id, :name(keyword), :input}`，与中立历史层两套词汇并存靠桥接互转）。
  影响：`on-tool-call` 回调收到的工具名从 keyword 变为**字符串**；读取响应
  `:tool-calls` 的代码须把 `:input` 改为 `:args`。
- **删除 `im.ttalk.agent.model.types`**：`make-tool-call` → 用
  `im.ttalk.agent.model.message/tool-call`；4 个字符串-role 消息构造器
  （`user-message` 等）→ 用 `model.message` 的中立构造器（keyword role）。
  `im.ttalk.agent.provider.api` 的同名 re-export 已重指向中立构造器（返回形状变化）。
- **流式 `on-token` 回调不再携带 `:accumulated` / `:reasoning-accumulated`**，
  只发增量 `{:token :index}` / `{:reasoning-token}`。需要全文请自行累积
  （每 token 物化全文是 O(n²) 的根源）。
- **模块重组：新增 `im.ttalk/clj-agent-client`**。agent 运行时
  （`client`/`react`/`memory`/`memory.sqlite`/`advisor.memory`/`callbacks`/
  `subagent.*`/`common` 共 8 个 ns）自 core 迁入；**命名空间不变**，仅依赖坐标变化。
  core 现为零依赖纯 Clojure（协议 + kernel 原语），timbre/next.jdbc/sqlite-jdbc
  依赖随迁 client。
- **删除 `converter.*` 与 `prompt.*` 两个子系统**（结构化输出解析 / LangChain 式
  提示词模板，共 10 个 ns，约 2900 行）：无任何 runtime 消费者。如需结构化输出，
  用 provider 侧原生 `:response-format`（`json_object` / `json_schema`+`strict`）。
- **死代码清理**（均无仓库内调用方）：kernel 的 `get-tool-var`/
  `list-functions-by-tag(-s)`/`list-functions-with-all-tags`；tool 的
  `get-params`/`sensitive?`/`get-category`/`has-tag?`；error 的
  `throw-if-error!`/`safe-execute`；common 的 `ensure-kernel`；
  `http/client.clj` 已废弃的 `with-retry`（此前已标 deprecated）。

### 🐛 修复

- **DashScope 同步调用必抛 `:parse-error`**：http-kit → java.net.http 迁移后
  body 被二次 JSON 解析（ClassCastException），成功响应也被误报；现按
  `:success?` 分派。
- **deftool 工具在 DashScope 下参数静默丢失**：其 schema 转换不识别
  `:input_schema`（deftool 宏生成的键）；现复用 `schema.openai`（两种键名都认）。
- **PATCH 请求必然运行时崩溃**：`HttpRequest.Builder` 没有 `.PATCH` 方法，
  反射解析失败；改走 `.method "PATCH"`。
- **factory 环境变量读取从未生效（`:api-key`/`:base-url`）**：生成的变量名带
  连字符（如 `OPENAI_API-KEY`），POSIX 不可设置；现连字符转下划线
  （`OPENAI_API_KEY`）。
- **发布构建链从未跑通**（三个叠加 bug，本次全修）：
  ① build.clj require 了不存在的 `deps-deploy.deploy-deps`（实为 `deps-deploy.deps-deploy`）
  → 加载即失败；② `b/install` 误传 `:src-dirs` 缺必填 `:class-dir` → install 必错；
  ③ `b/create-basis` 无顶层 `:override-deps` 参数（被静默忽略）→ client/provider 的
  pom 一直缺失 core 依赖，消费方解析即断；现经 alias 启用 override。
  已验证：三模块 jar + pom 正确产出并 install，纯 mvn 坐标的消费端项目可解析并运行。
- **子 agent 状态竞态**：`spawn!`/`restart!` 曾在注册表条目写入前启动 worker，
  秒完成的任务状态永远卡 `:running`；`kill!` 后 `await!`/`result` 曾返回 nil，
  且被中断的 worker 会把 `:killed` 覆盖成 `:failed`。现先登记后启动 +
  `finish!` 终态守卫 + `kill!` 写入明确 `{:error :killed}`。

### ⚡ 性能

- **流式累积 O(n²) → O(n)**：stream/{openai,anthropic,dashscope} 的所有累积器
  （正文/推理/块内文本/工具参数 JSON）改 StringBuilder 原地 append。
- **`tools->schemas` 有界缓存**：ReAct 循环每轮 LLM 调用不再对不变的工具列表
  重跑 wire 转换（openai/anthropic 两个 schema 命名空间）。
- **HttpClient 单例共享**：流式与非流式此前各持一个虚拟线程连接池，现共用。
- **子 agent 改虚拟线程**：`subagent/manager` 从 clojure `future`（无界平台
  线程池）改为 `newVirtualThreadPerTaskExecutor`（`kill!` 语义不变）。
- **反射清零**：全部 60 个 src 命名空间开启 `*warn-on-reflection*` 并修复
  暴露的反射/装箱点；新增反射会在编译期告警。

### ✨ 新增

- **流式建链重试（opt-in）**：`post-stream-sync` 支持 `:retry`（约定同
  `http.retry/maybe-with-retry`：`true`=默认配置 / map=合并），仅当错误可重试
  **且尚未流出任何 token** 时指数退避重试；provider config 传 `:retry` 即启用。
- **`stream_client/post-stream-sync`**：流式同步编排（promise 对 / cancel 登记 /
  结果分派）统一入口，openai-compat / anthropic / dashscope 三路共用。
- **`http/client.clj/response->error`**：HTTP 失败 → canonical error 的统一实现。

### 🔧 内部 / 测试

- 测试基线 196 → 204 tests / 840 assertions / 0 failures；新增覆盖：
  timeout/approval filter（含后台中断不泄漏线程）、factory 配置（env 名规范/
  三级合并/validate 全分支）、SQLite 与 InMemory 并发写、子 agent 生命周期、
  DashScope 同步回归、流式建链重试。
- Process Framework（V1 同步引擎 / V2 core.async 并行引擎 / Timeline 快照，
  曾以 `clj-agent-process` 第四模块形态完整落地并全绿）在发布前整体撤下——
  设计经评审判定需重新思考（V1/V2 快照与并行的语义撕裂，对照 SK Process /
  MS Agent Framework superstep 模型）；未进入任何发布版本，设计文档留档。
- `client_test` 的 httpbin.org 外网用例本地化（com.sun.net.httpserver），
  消除 CI flake。
- 设计文档状态与代码对齐：memory-filter-refactor（已完成）、onion-filter
  （全部实施，落地为 clj-agent-client）、streaming-async-design（全部落地）、
  response-path-consolidation（双消息体系统一完成）。

---

## 从 v0.2 迁移到 v0.3

### 1. 缺省执行引擎改为串行 ⚠️ 唯一可能静默挂起的变更，先读这节

不指定 `:tool-manager` 时，同一轮的多个 tool-call 从**并行**（v0.2 缺省，虚拟线程）
改为**按调用序依次执行**。绝大多数工具无感知；但有一类升级后会**永久挂起**：

```clojure
;; v0.2 下能工作的「批内互等」工具对——A 等 B 的信号，B 等 A 的信号，
;; 并行时双双就绪；串行缺省下 A 先跑、永远等不到 B，而缺省又无超时：
;; 无错误、无日志、循环永不返回。
```

**排查判据**：工具体内有 `deref` promise / 等锁 / 等队列，且对端是**同批**另一个
工具 → 中招。**迁移**：显式注入并发引擎（行为与 v0.2 逐字相同）：

```clojure
(kernel/build-kernel {:service svc :tools [...]
                      :tool-manager (react/virtual-thread-tool-calling-manager)})
```

顺带建议给引擎配 `:timeout` 兜底（见下节）——即使漏排查了一对互等工具，
也是「超时错误」而非「永久挂起」。

### 2. 超时：`timeout-filter` 已删除，超时是内建机制

```clojure
;; v0.2
{:filters [(filters/timeout-filter 5000)]}
;; v0.3 —— 两个来源，开箱即生效，无需任何 filter
(deftool slow-api "..." [...] {:timeout 5000} ...)                 ;; 工具声明（优先）
{:tool-manager (react/sequential-tool-calling-manager {:timeout 30000})} ;; 引擎缺省
```

- 缺省**不超时**（v0.2 的 filter 也是 opt-in，故仅迁移挂过 filter 的部署）
- 优先级：工具声明 > 引擎缺省 > 不超时；坏值（`0`/`"5s"`/负数）**装配期即抛**
- 语义不变：超时 = 放弃等待 ≠ 终止执行（阻塞 IO 会被真正取消；纯 CPU 循环
  打不断，副作用可能在超时后落地）——声明 `:retry` 的超时工具必须幂等

### 3. `invoke-tool` 返回形状

```clojure
;; v0.2                            ;; v0.3
{:value result :context ctx}       {:value result}            ;; 无写
                                   {:value result :writes {k v}}  ;; 有写意图
```

直调方自行用 `context/apply-writes` 折叠 writes（react 循环内已自动处理）。

### 4. tool filter 响应契约收窄

```clojure
;; v0.2 短路分支须回传 context      ;; v0.3 响应侧无 :context
{:result "blocked" :context (:context req)}   {:result "blocked"}
```

不带 `:writes` 的响应 = 该调用写意图不生效（事务性）；`:error {:class ...}`
参与屏障分层路由。

### 5. 工具 / inline handler 返回值拆包判据

```clojure
;; v0.2：含 :result 的 map 会被拆包   ;; v0.3：判据改为「含 :writes」
(fn [args ctx] {:result r})           (fn [args ctx] r)                    ;; 纯结果直接返回
                                      (fn [args ctx] {:result r :writes {k v}})  ;; 要写才包
```

### 6. ToolContext 只读 + 状态槽

工具/filter 不再改写 context；写走返回值 `:writes`，屏障处按 `build-kernel`
`:state-slots` 声明的槽级 reducer 折叠（未声明槽 last-writer）。同批工具互相
看不到对方的写（轮初快照）——此语义与选哪个引擎无关。

### 7. `on-tool-result` 时序

改为任务完成即实时触发，批内顺序不确定；需确定顺序读 `:tool-calls-made` /
`:records`。

### 8. `LineageStore` → `BranchStore`（含 SQLite 表重建）

| v0.2 | v0.3 |
|---|---|
| `lineage-add!` / `lineage-get` / `lineage-children` / `lineage-remove!` | `record-branch!` / `get-branch` / `branch-children` / `delete-branch!` |
| `in-memory-lineage-store` / `sqlite-lineage-store` | `in-memory-branch-store` / `sqlite-branch-store` |
| deps key `:lineage` | `:branch` |
| SQL 表 `branch_lineage` | `branch_records`（**跨版本需重建**，无自动迁移） |

用户侧查询 API（`lineage` / `ancestry` / `fork!` / `rollback!` / `prune!`）不变。

---

## 从 v0.1 迁移到 v0.2

### 1. 依赖坐标（若直接引用 core 且使用 agent API）

```clojure
;; v0.1
{:deps {im.ttalk/clj-agent-core {...}
        im.ttalk/clj-agent-provider {...}}}

;; v0.2 —— agent 运行时在新模块（命名空间不变，require 无需改动）
{:deps {im.ttalk/clj-agent-client {...}      ;; 传递引入 core
        im.ttalk/clj-agent-provider {...}}}
```

### 2. tool-call 形状

```clojure
;; v0.1：响应层 tool-call
{:id "c1" :name :get-weather :input {:city "北京"}}
;; v0.2：全库统一
{:id "c1" :name "get-weather" :args {:city "北京"}}
```

- 读 `(:input tc)` → 改 `(:args tc)`
- 匹配 `(= :get-weather (:name tc))` → 改 `(= "get-weather" (:name tc))`
- `on-tool-call` 回调：`(fn [name args] ...)` 中 name 现为字符串

### 3. 构造器

```clojure
;; v0.1                                    ;; v0.2
(types/make-tool-call id name input)       (msg/tool-call id name args)
(types/user-message "hi")                  (msg/user "hi")        ; {:role :user ...}
(types/assistant-message "ok")             (msg/assistant "ok")
(types/system-message "sys")               (msg/system "sys")
(types/tool-message id content)            (msg/tool-result id name content)
;; msg = im.ttalk.agent.model.message；注意中立消息 role 为 keyword
```

### 4. 流式 on-token

```clojure
;; v0.1：可以直接读全文
(fn [{:keys [token accumulated]}] (render! accumulated))
;; v0.2：自行累积
(let [acc (StringBuilder.)]
  (fn [{:keys [token]}] (when token (.append acc token)) (render! (str acc))))
```

### 5. 结构化输出（converter 已删除）

直接用 provider 原生能力（OpenAI 兼容系列）：

```clojure
{:model "..." :response-format {:type "json_schema"
                                :json_schema {:name "Person" :strict true
                                              :schema {...}}}}
```
