# Agent 并发模型设计：Tool 阶段 MapReduce 与编排层重新思考

> **状态：✅ 单 Agent 部分已全部实施（2026-07-11，全套 236/998/0）。**
> 来源：process framework 整体删除（见 `process-framework-design.md` /
> `process-parallel-design.md` 头部说明）之后的 rethink 系列讨论。
> §0–§8 为讨论定稿的设计推导与业界对照；§9–§13 为各阶段实施记录
> （S1 工具 MapReduce、S2 错误分层、HITL 持久化、Timeline、resume payload）。
> **HITL 与 Timeline 的整合权威参考见 `hitl-timeline-design.md`**；
> **filter 三链体系（:tool/:chat/:turn）的整合权威参考见 `filter-chain-design.md`**。
> 未实施仅剩 §6 编排层（多 Agent）——用户判定太复杂暂弃。

---

## 0. 结论速览

1. **Agent loop 的核心并发只发生在 Tool 阶段**；由于子 agent 也以工具形态暴露，
   多 agent fan-out / map-reduce / 评审面板同样归约到这里。
2. **Tool 阶段自带一个零成本屏障**：下一轮 Plan（LLM 调用）必须收齐全部工具结果
   才能发生。BSP 的全部好处（一致快照点、确定性合并）在这里免费。
3. **Tool 阶段建模为 MapReduce**：map on snapshot（工具拿轮初 context 快照）、
   写出即数据（`:writes`）、屏障处按 tool-call 原始序纯折叠（reduce）。
   竞态不是被"管理"，而是被这个执行模型**消灭**。
4. **合并语义落在状态槽上（槽级 reducer）**，不落在批次上——LangGraph
   channels/reducers 的工程学。绝大多数工具不写 context，零声明。
5. **工具失败按类别分层路由**，不是"重试还是人工"的单选题：
   语义类→回模型（errors are data，缺省）、瞬态类→工具级幂等重试、
   环境类→屏障处暂停等人、策略类（审批拒绝）→正常结果回模型。
6. **失败工具的 writes 不参与 reduce** = 免费的单工具事务性（穿线可变 context
   给不了这个）。
7. **破坏性变更**：ToolContext 从"批内穿线可变"回归 Spring AI 本义的
   "只读环境数据"+ 显式 reducer 状态槽。一个决策同时解开工具并行、
   批次语义、未来编排层状态合并三个问题。
8. **编排层（process）重新定位**：循环内 MapReduce 已覆盖绝大部分并行价值；
   编排框架只剩跨循环拓扑、长驻外部事件、HITL 持久化三类需求。第一个决策是
   "要不要全局快照语义"——要则 BSP（superstep），不要则 actor；**不再做双引擎**。

---

## 1. 背景：process framework 为什么被删

2026-07-10/11 曾完整落地 `clj-agent-process`（V1 纯函数同步 + V2 core.async
自由并发 + Timeline/Snapshot，45 tests 全绿），随即整体删除。结构性问题：

- **V2 自由并发没有确定性静止点**：任意时刻总有 step 在跑、状态散在多个
  并发写入的 atom/channel 里（在途事件不可枚举）、拍照时机不可复现。
  快照/Timeline 与并行不可兼得，被迫 V1（确定性/可快照）与 V2（并行/外部事件）
  **双引擎分裂**，用户按场景二选一。
- **教训**：先选了并发原语（channel 自由并发）再回头想要快照语义，两头不靠。
  正确顺序是先回答"要不要全局一致状态语义"，再选执行模型。

代码可从 git 历史找回：V1 baf1994/a2a541d、Timeline 7bc5d64..764285c、
V2 63dc926..fa13f2b。

---

## 2. 业界对照（证据基础）

| 框架 | 执行模型 | 快照/恢复 | 工具并行 | 状态合并 |
|------|---------|----------|---------|---------|
| LangGraph | Pregel/BSP superstep | superstep 边界 checkpoint（含待执行任务） | Send API 动态 fan-out | channel + 槽级 reducer，屏障处统一应用 |
| MS Agent Framework Workflows | BSP superstep（批内并行 + 屏障） | 每 superstep 末 checkpoint，**含在途消息** | superstep 内并发 executor | executor 状态 + 共享状态，屏障处 |
| SK Process Framework（前代） | Local: superstep 循环（checkpoint 未做完，enterprise 需求 closed as not planned）；Dapr: per-step actor | Dapr actor 各自持久化，**无全局快照** | actor 间天然并发 | 无全局合并（放弃） |
| Spring AI（2.0） | ToolCallingAdvisor 递归循环 | — | **默认串行**；#5195 提案 opt-in `CONCURRENT`（CompletableFuture + 原序排回 + collect-all） | 无共享状态可合并（ToolContext 只读） |

关键判读：

- **BSP 不是为 DAG，是为环**。LangGraph 官方设计博客明说：因为 agent loop
  需要循环，拓扑排序那套 DAG 算法直接出局，选 Pregel 是因为"确定性并发 +
  对环的完全支持"。真 DAG 用拓扑序数据流即可（且流水线最优），不需要 BSP。
- **BSP 买的是状态一致性，不是拓扑性质**：写冲突的确定性归并（屏障处过
  reducer）、屏障=一致快照点（checkpoint/HITL/time-travel 全定义在超步边界，
  Pregel 论文的容错本身就是这个）、激活判定良定义（fan-in 等不等得齐每轮
  末尾有确定答案）。
- **BSP 对串行图优雅退化**：每超步一个活跃节点时，屏障无人可等，
  退化为普通顺序循环，代价≈0。"过不过度"由框架承诺服务的最复杂拓扑决定，
  不由"环是不是单向"决定。
- **Spring AI 的 ToolContext 是只读环境数据**（调用方注入 tenantId 之类，
  不进模型、工具间不传状态）——所以它并行化没有语义之墙，纯粹是没做；
  我们的 ToolContext 批内穿线可变，语义上有墙。这个对比直接决定了 §4 的
  破坏性变更方向。

参考链接见文末。

---

## 3. 关键洞察链

### 3.1 Agent loop 的并发只在 Tool 阶段

Plan（LLM 调用）每轮天然串行；OB 是把结果折进历史。唯一的并发机会：
同一轮 LLM 返回的 N 个 tool calls（它们被模型假定无依赖）。

**乘数效应**：子 agent 以工具形态暴露（本仓库 subagent 体系、Claude Code 的
Agent tool 均如此）后，多 agent fan-out、map-reduce over documents、并行评审
全部归约为"同批多个 tool calls"。所以这不是小场景优化，而是把编排级并发的
大部分吸进循环内部。

### 3.2 Tool 阶段自带零成本屏障

BSP 的代价是"每轮等最慢者、无跨轮流水线"。但 tool 批次的屏障**不是人为加的，
是语义自带的**：下一次 Plan 必须看到全部工具结果。循环本来就无法在结果收齐前
推进。因此"superstep = 一次循环迭代"的 BSP 在这里屏障税为零，同时白拿：

- 每轮屏障处全图状态是一个良定义的不可变值 → checkpoint / HITL 的天然挂载点；
- 确定性合并的执行位置。

### 3.3 现状（execute-batch）与它的墙

`react.clj/execute-batch` 是严格串行的 `reduce`，且串行是**契约**：
ToolContext 在批内穿线（工具 N+1 看到工具 N 的 context 写入）。
要并行必须先动这个语义——这正是 process V2 撞过的同一堵墙的迷你版。

---

## 4. 设计：Tool 阶段 MapReduce

### 4.1 执行模型

```
       ┌────────── 轮初 context（不可变快照）──────────┐
       ▼                    ▼                        ▼
   [tool A]             [tool B]                 [tool C]     ← map：虚拟线程并行
   {:value :writes}     {:value :writes}         {:value :writes}
       └────────────────────┼────────────────────────┘
                            ▼
                     屏障（语义自带）                      ← 全部结果收齐
                            ▼
        按 tool-call 原始序折叠 writes → 新 context        ← reduce：纯折叠
        结果按原始序排回 → tool 结果消息                    ← 消息序/provider 契约不变
                            ▼
                      下一轮 Plan
```

三条规则消灭竞态（而非管理竞态）：

1. **map on snapshot**：每个工具拿轮初 context 的不可变快照，执行期间零共享写；
2. **写出即数据**：工具返回 `{:value 结果 :writes [[k v] ...]}`，写意图是纯数据，
   不返回"改好的 context"；
3. **reduce 是纯折叠**：屏障处把所有 writes 按 tool-call 原始 index 排序后
   顺序 fold 进 context。

确定性论证：折叠顺序钉死 ⇒ 即使 reducer 不满足交换律，同样的 LLM 输出 +
同样的工具结果 ⇒ 同样的新状态。重试/调度只影响耗时，不影响合并结果。

### 4.2 槽级 reducer（合并语义跟着 state key 走）

```clojure
;; 声明式：每个槽自带合并语义（示意 API，实施时定稿）
{:notes    {:init [] :reduce conj}        ;; 追加型：天然无冲突
 :budget   {:init 0  :reduce +}           ;; 交换律 reducer：并行免忧
 :approved {:reduce (fn [_old new] new)}} ;; last-writer（按原序，确定性）
```

- 绝大多数工具不写 context → 零声明；
- 默认 reducer = 确定性 last-writer + 冲突告警；
- 这套词汇**可升级**：未来编排层（§6）若做 BSP，节点级状态合并用同一个
  reducer 概念，循环内与编排层不用学两套。

### 4.3 事务性（白送的）

失败工具的 writes 不参与 reduce ⇒ 每个工具天然"要么全写入、要么零写入"。
穿线可变 context 给不了这个（工具改一半再抛异常，脏写已污染下游）。

### 4.4 副作用与串行 opt-out

reducer 管不了外部世界：两个工具并行写同一个文件，什么 reducer 都救不了。
保留 opt-out：工具可标 `:serial`（或策略上默认只并行只读/幂等工具），
批内含 serial 工具时该工具退化串行。参考 Claude Code 对只读工具的并行策略、
Spring AI #5195 的 opt-in `CONCURRENT` + 默认 sequential 向后兼容。

### 4.5 破坏性变更：ToolContext 语义回归

- **现状**：批内穿线可变（超出 Spring AI 同名概念的语义超集）；
- **目标**：只读环境数据（conversation-id、kernel、tenant 类）+ 显式 reducer
  状态槽承担工具间共享。
- **消费者盘点（2026-07-11 已完成）：可写总线在全仓库零真实写者。**
  内置 filter（logging/timeout/approval）全部只读透传 `:context`；memory
  advisor 只读 `:conversation-id`；`{:context true}` 工具在 src/examples 中
  **一个都不存在**（仅 tool.clj/context.clj 的 docstring 示例）。破坏面 ≈ 0。
- 这条无人使用的可写通道让每个边界支付了适配成本（见 §4.6）。
- 一个决策解三个问题：工具并行、批次语义、未来编排层状态合并。

### 4.6 可写 context 总线的全链路成本（收紧契约即消除）

| 位置 | 成本 |
|------|------|
| `invoke-tool` ↔ ToolResponse | 返回形状 `{:value :context}` vs `{:result :context}`，命名不一致 |
| inline handler（kernel.clj） | 返回值形状嗅探（`(contains? raw :result)` 判断是否携带 context） |
| `tool/invoke` | 第三种形状 `{:success :result :error :context}` |
| 每个 tool filter | 响应侧必须记得回传 `:context`（timeout/approval 手工 `(:context req)`），漏了即静默丢失——纯负担、易错点 |
| `deftool` 宏 | needs-context? 双 arity 编译分支 |
| `execute-batch` | 批内 reduce 穿线 = 工具并行的那堵墙（§3.3） |

收紧后：tool 链的 ToolRequest 保留 `:args`/`:function` 可改写 + 短路/around
（filter 的合法职责本就是**控制**——日志/超时/审批/限流，盘点显示没有一个
需要改状态）；ToolResponse 只剩 `:result`（+ 未来的 `:writes`）；`:context`
变为请求侧只读字段。上表六项成本全部消失。

---

## 5. 工具失败处理：按故障类别分层路由

不是"重试还是人工介入"的单选题。核心事实：**agent loop 自带一个错误处理器
——模型本身**。分类后每类归宿确定：

| 类别 | 例子 | 路由 | 位置 |
|------|------|------|------|
| 语义类（大多数） | 参数错、查无此人、业务拒绝 | 序列化为工具结果回给模型（errors are data），模型换参数/换工具/问用户 | reduce 处，**缺省路径，零配置** |
| 瞬态类 | 超时、429、网络抖动 | 工具级自动重试（指数退避），对模型透明；**硬前提：幂等工具** | map 任务内部 |
| 环境类 | 认证失效、配额烧光、磁盘满 | 模型修不了、重试无用 → 屏障处带一致快照**暂停等人**，恢复后从屏障续跑 | 屏障后策略钩子 |
| 策略类 | 审批 gate `:reject` | 不是错误：`"已拒绝执行"` 作为正常结果回模型 | reduce 处 |

配套约定：

- **collect-all 不 fail-fast**：单个工具失败不炸批次，成功者结果照常保留
  （Spring AI #5195 同款）；
- **错误分类判定复用 canonical error 词汇**（provider 层 D5 已有
  `:retryable?` / `:auth-error` 等分类，工具层沿用）；
- 重试永远 per-task，不存在"重试整批"。

**故障 ≠ 崩溃**：以上全部是"工具返回了失败"。进程崩溃（批次中途 JVM 死）属
durable execution 域——屏障 checkpoint + 整轮重放（又回到幂等要求；superstep
checkpoint 保护不了"步内"，见 Diagrid 对 MS Agent Framework 的批评）。
要不要做、对哪些流程做（如仅长驻流程），是 §7 的独立待决项，不与本节混谈。

---

## 6. 编排层（process）重新定位

循环内 MapReduce 落地后，编排框架的剩余需求只有三类：

1. **跨循环拓扑**：多个 agent loop 之间的流水线/汇聚（非"一个 loop 内并行"）；
2. **长驻 + 外部事件**：常驻流程、外部事件驱动（旧 V2 的 ProcessHandle 场景）；
3. **HITL 持久化**：暂停点跨进程重启续跑（旧 Timeline/Snapshot 场景）。

立项前必须先钉死的决策（按序）：

1. **要不要全局快照语义？**
   - 要 → **BSP superstep**（= 旧设计文档方案 C 路线，MS Agent Framework 已
     验证：批内并行 + 屏障 checkpoint 且快照含在途消息——补上旧 V1
     "快照不含事件队列"的缺口）；状态合并沿用 §4.2 的槽级 reducer 词汇；
   - 不要 → **actor per-step 持久化**（SK Dapr / beamai 路线），放弃全局快照，
     靠可靠消息 + 幂等达成最终可恢复。
   - **不再做双引擎**。
2. **需要动态实例化吗？** 旧 step 是单例（同名事件后到覆盖先到的输入槽），
   表达不了 LangGraph `Send` 式"运行时决定 N 份实例、每份私有输入"的
   map-reduce。若服务多 worker 负载，"动态实例化 + 每实例私有输入 +
   reducer 汇聚"是需求清单硬项（对 BSP 几乎白拿，对自由并发是又一轮苦战）。
3. **和 react/subagent 的职责边界**：单 loop 场景已由 react.clj + 子 agent
   覆盖，编排层不得与之重叠（旧 process 的部分问题正是重叠）。

---

## 7. 待决问题清单

- [ ] reducer 声明的落点与 API：context 槽注册表？kernel 配置？工具声明？
- [ ] 工具错误分类的判定约定：谁标注（工具作者 ex-info data？框架按异常类型推断？）
- [ ] `:serial` / 幂等标注的默认策略：默认全并行 + opt-out，还是默认串行 + opt-in
  （Spring AI 选后者求兼容；我们 v0.3 破坏性窗口可考虑前者）
- [x] ToolContext 穿线语义的消费者盘点 → **零写者**（2026-07-11，见 §4.5），
  破坏性变更实际无痛，实施台阶 1 的最大顾虑解除
- [ ] durable execution 的范围：不做 / 仅屏障 checkpoint / 完整重放
- [ ] 编排层是否立项（§6 三类需求是否有真实用户）

## 8. 实施台阶（若启动，建议顺序）

1. **execute-batch MapReduce 化**：虚拟线程 map + snapshot + writes + 槽级
   reducer + 原序排回 + collect-all；ToolContext 语义破坏性变更（v0.3）。
   **→ ✅ 已实施（2026-07-11），设计见 §9，CHANGELOG 0.3.0 段记录破坏面。**
2. **屏障策略钩子**：错误分类路由 + 环境类暂停/恢复（HITL 进循环屏障）。
   **→ ✅ 已实施（2026-07-11），见 §10。**
3. **编排层另行立项**：先答 §6 决策 1，再谈实现。

---

## 9. S1 实施设计（✅ 已实施，2026-07-11，全套 213/870/0）

> 实施与本节设计一致，一处措辞修正见 9.2 决策 4（serial 退化只改执行时序，
> 状态语义与并行路径统一为「快照 + 屏障折叠」，不恢复旧穿线）。
> 顺带迁移：subagent delegate 的 8 处 `{:result ...}` 包装返回改为直接返回值
> （新嗅探判据只认 `:writes`）。

### 9.1 执行流程

```
tool-calls [tc0 tc1 tc2 ...]（LLM 一轮返回）
     │
     ├─ ① gate 预判（串行、按原序）→ decisions        ;; 审批可能交互，绝不并发
     │     （run-tool-loop 现状已是此形态：cached-gate 批前算好）
     ├─ ② 批内含 :serial 工具？ → 整批退化为旧串行 reduce（旧路径保留）
     ├─ ③ map：非 reject 调用提交虚拟线程
     │      任务内：kernel/invoke-tool(kernel, tc, ctx-snapshot)
     │      （tool filter 洋葱在任务内完整生效；异常折错误结果不逃逸）
     │      任务返回 {:i idx :value str :writes {k v}}
     ├─ ④ 屏障：全部 deref（collect-all）
     ├─ ⑤ reduce：按原始 index 序折叠 writes → 新 context
     │      (ctx/apply-writes ctx-snapshot ordered-writes state-slots)
     └─ ⑥ messages/records 按原始 index 排回（形状不变）
```

跨轮语义不变：⑤ 的新 context 照旧穿线到下一轮；删除的只是**批内**穿线。

### 9.2 六个已拍板的决策

1. **writes 形状**：每工具一个 map `{k v}`。工具对同一 key 的多次写自己先合并；
   跨工具合并交给 reducer，fold 顺序 = tool-call index 序（确定性钉死）。
2. **reducer 声明落 `build-kernel`** 顶层 opt `:state-slots`
   （`{:notes {:init [] :reduce conj}}`），存入 kernel settings。未声明槽默认
   last-writer（按 index 序）；同批多工具写同一未声明槽 → conflict，client 侧
   warn。`apply-writes` 本体在 `context.clj`（纯函数，返回
   `{:context :conflicts}`，core 保持零依赖）。
3. **`{:context true}` 读写拆分**：读的一半保留（双 arity `[args ctx]`、
   照应变量 `ctx`，语义改为**只读环境**）；写的一半删除（返回 `:context`
   通道，零用户），写意图改走 `{:result ... :writes {k v}}`——嗅探判据从
   "含 `:result`"改为"含 `:writes`"（撞名概率更小），且**单 arity 普通工具
   也可返回 `:writes`**（读与写正交，写状态不再被迫声明 context）。
4. **`:serial` 策略**：deftool 选项（`:tool/serial` 元数据 / inline 工具
   `:serial` 键）。批内任一工具 serial → **整批退化为按序执行**（副作用要的
   是顺序，部分并行会改变相对时序；整批退化是唯一无需解释的语义）。
   **注意：退化只改执行时序**——状态语义与并行路径统一为「快照 + 屏障折叠」，
   不恢复旧的批内穿线（一个 API 只有一种状态语义）。默认全并行（v0.3 破坏
   窗口）。文档警示：交互式审批放 gate（批前串行），勿放 tool filter
   （会在并行任务里并发弹提示）。
5. **`on-tool-result` 回调**：任务完成时实时触发（进度 UX 实时性 > 顺序性），
   批内顺序不确定；需确定顺序的读 `:records`。
6. **错误处理只做 collect-all**，分层路由留 S2。异常照旧折 `"错误: ..."`
   结果；错误/超时/拒绝结果不带 `:writes` → reduce 自动跳过（事务性白拿）。

### 9.3 契约变更清单（v0.3 破坏面）

| 变更 | 旧 | 新 | 实际影响 |
|---|---|---|---|
| `invoke-tool` 返回 | `{:value :context}` | `{:value :writes}` | 直调用户需改 |
| tool filter 响应 | `{:result :context}` | `{:result}`（`:context` 忽略） | timeout/approval 删手工回传，变简单 |
| `{:context true}` 返回 | 可带 `:context` | 可带 `:writes`（任意工具均可） | 零真实用户 |
| 批内穿线 | 工具 N+1 见 N 的写 | 同批全看轮初快照 | 零真实用户 |
| `run-tools` / messages / records | — | 形状不变 | 无 |

### 9.4 逐文件计划

1. `core/context.clj`：+`apply-writes`（默认 last-writer + conflict 收集）
2. `core/tool.clj`：`invoke` 归一化 `:writes`、删 `:context` 返回拆解；
   deftool +`:serial` 选项；docstring 改只读语义
3. `core/kernel.clj`：`invoke-tool` 两条 terminal 改形状；`build-kernel` 收
   `:state-slots`；+`serial-tool?` 查询
4. `core/advisor.clj`：timeout/approval 删 `(:context req)` 回传
5. `client/react.clj`：`execute-batch` 重写 ①-⑥（共享
   `newVirtualThreadPerTaskExecutor`，同 subagent 先例）
6. `client/client.clj`：预计零改动（轮间流转不变）
7. 文档：README、CHANGELOG（0.3.0 段）、本文件状态

### 9.5 测试清单

并行性证明（互等）；快照隔离（B 看不到同批 A 的写，显式钉新语义）；
reducer 折叠（conj 槽两写全收）；last-writer 按 index 序而非完成序
（先完成的是 index 大者）；错误/timeout/reject 的 writes 归零；
messages/records 原序；`:serial` 整批退化；跨轮可见（本轮写下轮读）；
conflict 告警；`:writes` 单 arity 工具可用。

---

## 参考

- LangChain, [Building LangGraph: Designing an Agent Runtime from first principles](https://blog.langchain.com/building-langgraph)
- LangChain Docs, [LangGraph runtime (Pregel)](https://docs.langchain.com/oss/python/langgraph/pregel)
- LangChain Reference, [Send](https://reference.langchain.com/python/langgraph/types/Send)
- Microsoft Learn, [Agent Framework Workflows - Checkpoints](https://learn.microsoft.com/en-us/agent-framework/workflows/checkpoints)
- microsoft/semantic-kernel, [Issue #11959: Process Framework State Persistence, Checkpointing and Fail-over](https://github.com/microsoft/semantic-kernel/issues/11959)（closed as not planned）
- Spring AI Reference, [Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
- Spring Blog, [Tool Calling in Spring AI 2.0: A Composable, Agentic Architecture](https://spring.io/blog/2026/06/15/spring-ai-composable-tool-calling/)
- spring-projects/spring-ai, [Issue #5195: Support parallel execution of multiple tool calls](https://github.com/spring-projects/spring-ai/issues/5195)
- Diagrid, [Still Not Durable: MS Agent Framework & Strands](https://www.diagrid.io/blog/still-not-durable-how-microsoft-agent-framework-and-strands-agents-repeat-the-same-mistake)
- 本仓库（已废弃留档）：`docs/process-framework-design.md`、
  `docs/process-parallel-design.md`、`design/timeline-snapshot-checkpoint.md`

---

## 10. S2 实施设计（✅ 已实施，2026-07-11，全套 217/898/0）

§5 的四类分层落地为三件事：分类通道、瞬态重试、环境暂停。策略类（gate
:reject）与语义类（errors are data 缺省路径）S1 已就位，S2 无需改动。

### 10.1 分类通道

`model.error/classify-exception`，判定顺序（全数据驱动，无 instanceof 动物园）：

1. ex-data 显式 `:error-class`（工具作者标注，最高优先级）；
2. canonical error（D5 词汇）：`:retryable? true` → `:transient`；
   `:auth-error` → `:environment`；其余 → `:semantic`——工具内调 provider
   的失败自动获得正确路由，零标注成本；
3. 常见网络异常（SocketTimeout/Connect/HttpTimeout）→ `:transient`；
4. 缺省 `:semantic`。

传播链：tool/invoke 与 kernel 两条 terminal 折错误时附带
`:error {:class :message}` → `invoke-tool` 返回值透出 → `execute-batch`
返回新增 `:errors [{:id :name :class :message :tc}]`（重试耗尽仍失败的才在此）。
timeout-filter 的超时结果也标 `:transient`（超时正是重试有意义的类别）。

### 10.2 瞬态重试（map 任务内，对模型透明）

- `deftool {:retry true | {:max-retries n :initial-delay-ms ms}}`（inline 工具
  `:retry` 键）；**opt-in 即承诺幂等**。缺省 `{:max-retries 2 :initial-delay-ms 200}`。
- 仅 `:transient` 类错误触发；指数退避；重试**重跑整条 tool filter 链**
  （logging 会重复记录——审批类控制本就该放 gate，见 §9 警示）。
- 位置在 react `invoke-one` 内：per-task，永远不存在"重试整批"。

### 10.3 环境类屏障暂停（HITL）

- `execute-batch` 之后（屏障处）检查 `:errors` 中的 `:environment` 类：
  按策略 `:on-env-error` 暂停或继续。暂停发生在「结果交给模型之前」，
  批内其他工具的结果与写折叠均已落定（一致快照）。
- 暂停形态复用既有 `:paused` 契约，`:loop-state` 带 `:phase :env-retry` +
  `:batch-messages` + `:failed-calls`；client 层 finalize/on-interrupt
  回调等管道零改动。
- **resume 决策**：`:retry`（环境已修复）→ 只重跑失败调用，新结果按
  `:tool-call-id` **替换**进原批次消息（历史无重复 tool_result；若再遇环境类
  失败则再次暂停）；`:proceed` → 原错误结果照常交给模型。
- **缺省策略（对 §5 表的一处工程修正）**：react 层缺省 `:proceed`——
  环境类错误默认仍走 errors-are-data（无人值守调用方不会收到意外暂停态）；
  client 层对配置了 `:on-tool-call`（HITL 已启用、宿主必然处理 :paused）的
  agent 自动升为 `:pause`，也可经 `:on-env-error` 显式指定。
  client resume 决策映射：`:retry`/`:approved` → 重跑，其余 → `:proceed`。

### 10.4 破坏面

无。全部为增量：`:errors` 返回键、`:error` 响应键、`:retry`/`:on-env-error`
选项、resume 新 phase（旧审批 phase 行为不变）。

---

## 11. HITL 持久化（✅ 已实施，2026-07-11，全套 223/932/0）

来源：S3 讨论中的范围决策——只考虑单 Agent，多 Agent 编排是外层决策另议；
先把 HITL 做扎实。HITL 持久化从编排层需求中拆出（它不需要 BSP/actor 决策）：
S2 的 loop-state 本就是纯 EDN 数据，kernel/gate/callbacks 本就是 resume 时
重新提供的。

### 11.1 设计

- **暂停快照**（纯数据，`:version 1`）：`conversation-id / paused-at /
  pause-reason / pending-tool / loop-state / tool-context`。不存 kernel/
  tools/callbacks/gate/system-prompt（代码侧重建）、不存对话历史
  （ChatMemory 已管，跨重启配 SQLite store）。tool-context 存档前逐 key
  校验 EDN 往返，不可序列化的（如 :kernel）剥离并 warn。
- **PauseStore 协议**（`im.ttalk.agent.pause`，client 模块）：
  `pause-save!/pause-load/pause-clear!`，每 conversation-id 至多一份
  （再次暂停覆盖）。in-memory + SQLite（EDN 一列，locking 串行化，
  工程学同 memory.sqlite；可与 ChatMemory 同库不同表）。
- **Agent 集成（opt-in 全自动）**：`create-agent :pause-store`；暂停时
  finalize 自动落库；任何终态（completed/error/cancelled）、`reset!`、
  暂停态下开新 chat 均自动清除（store 始终镜像"是否有未决暂停"）；
  `paused?`/`resume` 在 state-atom 无暂停态时**透明回落 store**——
  跨重启的新 agent 实例 API 不变。
- **明确出界**：批次执行中途的进程崩溃恢复（durable execution）不做，
  只保证「暂停点」这个一致快照可跨进程存活。

### 11.2 顺带修复的缺口

`client/resume` 此前用裸 `(tctx agent)`（仅 conversation-id）作 resume
context——**暂停前各轮累积的 state slot（S1 writes 折叠结果）被静默丢弃**
（S1 之前 context 无内容故无感）。现 resume context 恢复自暂停态的
`:tool-context`（本进程与跨重启两条路径同修），有回归测试钉住
（暂停前 note-writer 的写，恢复后 note-reader 读得到）。

另：`create-agent`/`common/build-kernel` 补透传 `:state-slots`
（S1 的槽声明此前只能经预构建 kernel 传入）。

### 11.3 测试

loop-state EDN 往返（审批 + :env-retry 两 phase）；PauseStore 双实现
存/取/覆盖/清；跨"重启"端到端（新 agent 实例 + 共享 stores）：审批恢复、
env-retry 修复后 retry；context 累积恢复回归；暂停态下开新 chat 清快照。

---

## 12. Timeline 与多分支（✅ 已实施，2026-07-11，全套 230/971/0）

来源：process framework 删除后，Timeline 能力在"单 Agent 优先"定调下的转世。
与旧 Timeline（黑盒快照版本链）的根本差异：**对象变了**——不再给运行时状态
拍快照，而是直接把对话日志当 timeline。

### 12.1 前提判定：Agent 的持久状态 = 对话历史（唯一真相）

- 对话历史：ChatMemory 中 append-only 日志——**唯一持久状态**；
- context 状态槽：**turn 级**草稿（用户拍板确认）。每次 chat 从裸 context
  起步，turn 内跨轮累积（S1），暂停恢复是唯一跨越点（HITL 持久化已修）；
- 暂停态：PauseStore，至多一份，resume 即消费。

由此旧 timeline 的"快照版本链 + 独立 store"整套机制不再需要——日志本身
就是版本序列，也避免了"历史与快照两处真相要同步"的旧痛点。

### 12.2 一致性不变量（并发工具失败的封口）

> **合法的 fork/rollback 点只有两种：turn 边界、暂停点。**
> turn 进行中（批次执行到一半）不是合法分支点——屏障才是一致快照点。

三种失败场景在该不变量下的走查：

1. **批内语义失败**：collect-all 下成功结果 + 错误串全部进历史，失败工具
   writes 被丢弃（S1 事务性）。turn 边界处历史完整、slots 已死——fork 后
   分支与主线看到同一份历史，无不一致；
2. **事务性保证"历史与状态同真同假"**：不存在"历史记成功但 writes 缺失"
   或"writes 残留但历史记失败"的组合；
3. **环境暂停（唯一 mid-turn 逃逸点）**：批次结果在暂停快照的
   :batch-messages 里、尚未进历史，历史尾部有悬空 tool_use。约束：
   **fork 暂停中的会话必须一并复制 PauseStore 快照**（fork! 自动做）；
   不带快照硬 fork 后直接 chat 由 heal-dangling 兜底（"已取消"配平）。

### 12.3 分支模型：fork-as-new-conversation（用户拍板）

分支 = 前缀复制到新 conversation-id + 一条血缘记录：

- `fork!` 用现有 ChatMemory 协议即可实现（mem-get 前缀 + mem-add 新 id），
  SQLite/in-memory 实现零改动；
- **所有既有组件自动兼容**：memory advisor / PauseStore / react 循环全部
  按 conversation-id 工作，换分支 = 换 conv-id 建 agent，无组件感知"树"；
- 血缘存 BranchStore（工程学同 PauseStore：协议 + in-memory + SQLite EDN）；
- 对比树形 store（消息带 parent 指针）：表达力相同但要改 ChatMemory 契约；
  本框架量级下前缀复制成本可忽略，简单性胜。

操作集（对照旧 timeline，词汇减半）：

| 操作 | 语义 |
|------|------|
| `fork!` | 前缀复制 + 血缘记录；全量 fork 且源处于暂停时**连带复制暂停快照**（部分前缀 fork 不带——暂停属于日志尖端） |
| `rollback!` | 破坏性截断到前 n 条（"重新生成"用；clear+重写实现，无需协议扩展）；清除该会话暂停快照（尖端已变，未决暂停失效） |
| `lineage` / `ancestry` | 查血缘记录 / 沿 parent 回溯到根 |
| `prune!` | 删分支（历史 + 暂停快照 + 血缘）；有子分支时拒绝 |
| ~~switch/goto/back/forward~~ | 不需要——没有"当前位置"这个可变状态，换分支就是换 conv-id |

白送的两个场景：**HITL 决策分支**（暂停点 fork 两支，分别 resume
approved/rejected 对比后果——旧 V1"同一暂停快照多次 resume"以更干净的
形式回归）；**编辑重试**（fork at 消息 i + 替换 user 消息 + chat =
ChatGPT 式 regenerate 分支）。

### 12.4 writes 进历史（event-sourcing 伏笔，用户拍板：记）

tool-result 中立消息新增可选 `:writes` 元数据（该工具对状态槽的写意图）：

- **只进存储不进 LLM**：wire 层（openai/anthropic）显式构造 wire 消息，
  多余 key 天然剥落（有测试钉住）；
- 立即价值：审计——历史从"工具说了什么"升级为"+ 改了什么"；
- 升级路径：将来若 slots 要跨 turn（session 状态），context 可定义为
  fold(当前 reducers, 历史 writes)——历史仍唯一真相、时间旅行=截断重折。
  现在不埋，旧会话的 writes 永久丢失、该路径即断；
- v1 零消费（slots 维持 turn 级），纯伏笔。失败/超时/被拒的调用本就无
  writes，历史中自然缺席（与 12.2 的同真同假一致）。

### 12.5 实施轮廓

- `model.message/tool-result` 4-arity 带 writes；execute-batch 落消息时附带；
- 新 ns `im.ttalk.agent.timeline`（client 模块）：BranchStore 协议 +
  in-memory/SQLite + fork!/rollback!/lineage/ancestry/prune!，deps 显式传入
  `{:memory mem :pause-store ps :branch ls}`（后两者可选）；
- 测试：writes 落历史（错误工具无 writes）/ wire 剥除 / fork 前缀与血缘 /
  暂停 fork 带快照 + 双分支各自 resume 不同决策 / rollback 截断清暂停 /
  prune 拒绝有子 / ancestry。

---

## 13. resume 带 payload（✅ 已实施，2026-07-11，全套 236/998/0）

来源：HITL 讨论——resume 此前只接受决策枚举，"用户答复是一段话"表达不了
（旧 process 的 on-resume data 有此能力，删除后未补齐）。

### 13.1 三种 payload（审批 phase）

```clojure
(resume agent "rejected" {:message "订单有未结算金额，先退款"})
;;  → 结果「已拒绝执行：<理由>」——模型直接拿到原因，省一轮干猜
(resume agent "approved" {:args {:id 42 :mode :soft-delete}})
;;  → pending 工具以替换后的参数执行（Claude Code 式"编辑后批准"）
(resume agent "reply" {:message "用户选择了 B 方案"})
;;  → 工具不执行，答复直接作为其结果回模型（ask-user 语义）
```

### 13.2 实现要点

- **决策词汇扩展**（execute-batch gate 契约）：`:proceed | :reject |
  {:reject 理由} | {:reply 结果}`——gate 与 resume 共用同一词汇，
  reply/reject 均不执行工具、不触发 on-tool-result、无 :writes；
- loop-state 新增 `:pending-id`（暂停工具的 tool-call id），payload 的
  :args 替换与 :reply 定位靠它；旧版暂停快照无此字段时 :reply 显式抛错；
- **ask-user 模式解锁**：定义一个 body 永不执行的提问工具 + gate 拦截暂停，
  `resume "reply"` 把用户答案送回——模型侧看到一次普通的工具往返；
- 环境类暂停（:env-retry）不支持 :reply（显式拒收）；payload 与持久化
  正交（resume 时才提供，快照无需任何改动，跨重启同样可用）；
- 破坏面：零（3-arity 纯增量；on-resume 回调 meta 增加 :decision/:payload）。

---

## 14. Turn 级 filter 链（:turn 钩子）——✅ 已实施（2026-07-11，全套 241/1013/0）

> 来源：Spring AI 2.0 advisor 重构调研（ToolCallingAdvisor 循环进链 +
> `chain.copy` 递归 advisor）。结论：递归能力我们的闭包洋葱**天然拥有**
> （build-chain 折叠出的 chain 参数本就是"仅下游"，Spring AI 为此新造的
> `chain.copy(this)` 我们免费获得）；memory 放循环内是刻意契约不跟；
> **真缺口是 turn 级链**——用户无法在"整个 turn"外面包 around 逻辑。

### 14.1 设计

- filter 第三个钩子 `:turn`（与 `:chat`/`:tool` 并列，同一洋葱机制）：
  `(fn [turn-req chain] -> turn-result)`；
  TurnRequest `{:messages 本轮入口消息 :context 初始 ctx}`（可改写）；
  TurnResult = react 循环结果 `{:status :response :tool-context ...}`。
- `react/invoke` 把整个 run-tool-loop 作 terminal，`(keep :turn filters)`
  包洋葱——循环本体不动（不学"循环变 advisor"的大搬家：我们的循环与
  gate/HITL/暂停/持久化深度耦合，为优雅重构不值）。
- **递归重入**：turn filter 可多次 `(chain req)`（evaluator-optimizer /
  最终答案校验重试）。重入的 :messages 应是**新 delta**（如反馈消息）——
  完整上下文由 memory filter 拼接，故递归类 turn filter 依赖 memory 在位。
- **硬规则**：`:paused` / `:cancelled` / `:error` 结果必须透传，不得重入
  （在暂停态上重试会破坏 HITL 语义）。
- ~~边界：turn 链只包 invoke~~ **已补齐（2026-07-11，全套 243/1021/0）**：
  resume 同样经过 turn 链——TurnRequest 带 `:resume? true`、`:messages` nil；
  终端一次性分派（首调=暂停延续消费 loop-state，递归重入=全新循环）。
  请求侧改写类 filter 应在 :resume? 时跳过。每次重入获得全新
  max-iterations 预算。

### 14.2 内置示范：validation-turn-filter

`(validation-turn-filter validate-fn :max-retries 2)`——校验 `:completed`
结果，不合格把原因作为反馈消息重入循环，耗尽后原样返回。以 ~30 行 +
provider 原生 json_schema 取回已删 converter 子系统的核心价值
（结构化输出校验重试），对标 Spring AI `StructuredOutputValidationAdvisor`。

### 14.3 解锁的场景与明确不跟的

解锁：每 turn 一次的 RAG 注入（此前只能 chat filter → 工具循环每轮重复
检索）、最终答案 guardrail、turn 级预算/计时、episode 级 evaluator。
不跟：RAG advisor 本体（需 vector store，超出定位，留挂点）、
SafeGuardAdvisor（用户一个 chat filter 即可）、advisor context map
（请求 map 透传已够）。零破坏（纯新增钩子）。
