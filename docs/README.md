# 设计文档索引

> **`design/` 已于 2026-07-16 并入本目录**，不再分两处。原因：那条界线是历史形成的
> （`design/` 早期放专题/重构笔记，`docs/` 放主设计文档），但两边都在长，且
> 「这份算专题还是主文档」没有可判定的标准——查文档的人只想知道**它在不在、
> 现在还算不算数**。跨目录相对链接（`../docs/…`）也是纯粹的税。
> 老路径 `design/xxx.md` 一律改到 `docs/xxx.md`，文件内容与 git 历史（rename）不变。

**先读**：[`design-principles.md`](design-principles.md) —— 项目级硬约束的唯一出处
（§1 无真实需求不建 / §2 框架无关 / §3 一个 Kernel 绑定一个 TCM，不跨边界）。
提新抽象、审 PR、写新设计文档时**援引它**，不要重新推导一遍。

**约定**：每份文档头部有 `状态：` 行，是该文档**当下是否算数**的唯一判据——
✅ 已实施 / 🚧 进行中 / 🗑️ 已废弃留档。**先看状态再读正文。**

---

## 权威参考（讲某个体系「现在长什么样」）

| 文档 | 讲什么 |
|---|---|
| [`filter-chain-design.md`](filter-chain-design.md) | Filter/Advisor 体系**整合权威参考**：洋葱机制、`:tool`/`:chat`/`:turn` 三链的粒度与契约、递归重入、内置 filter、硬规则 |
| [`token-stream-filter-design.md`](token-stream-filter-design.md) | 第四钩子 `:token-xform`（transducer 变换出站 token 流）的权威参考。三链见上一份，本文只讲 token 粒度 |
| [`hitl-timeline-design.md`](hitl-timeline-design.md) | HITL（暂停/resume/审批）与 Timeline 多分支的**整合权威参考** |
| [`agent-loop-concurrency-design.md`](agent-loop-concurrency-design.md) | Agent 并发模型：§9 工具批 MapReduce（`:writes` + `:state-slots` 屏障折叠）、§11 暂停持久化、§12 timeline |
| [`tool-calling-manager-design.md`](tool-calling-manager-design.md) | 工具**执行引擎**（线程模型 + 隔离边界 + 调度策略）；含 `deftool :backend` 的否决记录（§5） |
| [`advisor-alignment-design.md`](advisor-alignment-design.md) | 逐个对齐 Spring AI 2.0 Advisor：有什么、对应什么、吸收还是不跟、为什么 |

## 已实施（专题 / 重构记录）

| 文档 | 讲什么 |
|---|---|
| [`provider-variant-design.md`](provider-variant-design.md) | 一个标准 provider 如何承载多套厂商方言（MiniMax thinking）：**三类差异三种机制**，不动 `ILLMProvider` 而用**可选协议 + `satisfies?` 探测**。修了 config 白名单（provider 专属键递不到底）与 thinking 回传丢失（实测正确率 100%→82.5%）。**也是「预注册判据双向作数」的范例**：判据先否掉一次、卡住一次，最后才放行同一个结论 |
| [`tool-timeout-design.md`](tool-timeout-design.md) | 工具超时：借鉴 beamai 但**不照搬三层**（JVM 无 `exit(Pid,kill)`，超时=放弃等待≠终止执行）。修了 `:timeout` 死选项与 `timeout-filter` 平台线程两个真 bug |
| [`streaming-async-design.md`](streaming-async-design.md) | 真增量 SSE 传输选型 + 异步框架整合。**也是 §2《框架无关》的出处** |
| [`onion-filter.md`](onion-filter.md) | 洋葱式 filter + kernel 瘦身（loop/memory 下沉 client 模块） |
| [`memory-filter-refactor.md`](memory-filter-refactor.md) | 消除显式 Context，转向 Memory Filter + 中立消息 |
| [`unified-invoke-agent.md`](unified-invoke-agent.md) | 统一 invoke + 合并 Agent |
| [`response-path-consolidation.md`](response-path-consolidation.md) | D6/D7：响应路径整理、core 收回厂商 wire 知识、双消息体系统一 |
| [`error-model-unification.md`](error-model-unification.md) | D5：一个 canonical 错误值 + 四条边界信封规则（修了 401 被误标可重试的真 bug） |

## 已废弃留档（🗑️ 不代表现状，只留教训）

Process 框架（V1/V2/Timeline）于 2026-07-11 整体删除，重新思考的结论落在
`agent-loop-concurrency-design.md`。以下三份**只作教训留档**，不是现行设计：

| 文档 | 为什么留 |
|---|---|
| [`process-framework-design.md`](process-framework-design.md) | V2 曾完整落地并全绿，当天判定有结构性问题、整模块删除 |
| [`process-parallel-design.md`](process-parallel-design.md) | 并行化方案，随上者一并撤下 |
| [`timeline-snapshot-checkpoint.md`](timeline-snapshot-checkpoint.md) | 旧 Timeline；唯一消费者是 process 快照，一并撤下。**旧 Timeline 的教训来源**（现行 Timeline 见 `hitl-timeline-design.md`） |
