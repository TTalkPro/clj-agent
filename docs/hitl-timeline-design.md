# HITL 与 Timeline 设计（单 Agent）

> **状态：✅ 已全部实施（2026-07-11），全套 243 tests / 1021 assertions / 0。**
> 本文是 HITL（human-in-the-loop）与 Timeline/多分支能力的**整合权威参考**，
> 由 `agent-loop-concurrency-design.md` §5 / §9–§13 的实施记录整合而成；
> 循环执行模型（Tool 阶段 MapReduce、屏障、错误分层）的推导与业界对照见该文。
> 范围定调（用户拍板）：**只考虑单 Agent**，多 Agent 编排是更外层的决策另议。
>
> 实现分布：`react.clj`（循环/暂停/resume）、`client.clj`（agent 集成）、
> `pause.clj`（暂停持久化）、`timeline.clj`（分支）。

---

## 0. 总览

```
chat ──► Plan(LLM) ──► gate 预判 ──:pause──► 暂停①（审批，工具未执行）
              ▲              │                     │
              │              ▼                     │
              │      Tool 批次（虚拟线程并行）        │ resume + payload
              │              │                     │  approved / approved+args
              │              ▼                     │  rejected(+理由) / reply
              │       屏障（收齐+折叠）◄─────────────┘
              │              │
              │   ├─ 环境类失败 ──► 暂停②（:env-retry，批已执行）
              │   │                    │ resume: retry / proceed
              │   └─ 正常 ─────────────┘
              └──────────────┘（直到文本响应 → 完成）

任一暂停 ──► PauseStore 自动落库 ──►（进程可退出）──► 重建 agent ──► resume 透明恢复
turn 边界 / 暂停点 ──► timeline/fork! ──► 分支会话（HITL 决策分支 / 编辑重试）
```

**能力矩阵**：5 种用户响应（批 / 批+改参 / 拒 / 拒+理由 / 自由答复）
× 2 种暂停源（审批 / 环境失败）× 2 种生命周期（本进程 / 跨重启）
× 分支（fork 后两条时间线各自 resume）。

---

## 1. 暂停源

### 1.1 审批暂停（gate，工具未执行）

- 触发：agent 配置 callbacks `:on-tool-call`（返回 `{:interrupt reason}`）即
  启用 gate；react 在**批前串行预判**（审批可交互，绝不并发），任一调用判
  `:pause` → 整批挂起，返回 `{:status :paused :pending-tool ... :loop-state ...}`。
- 此时**没有任何工具执行过**；历史尾部是悬空的 assistant(tool_calls)
  （若用户放弃 resume 开新 chat，`heal-dangling` 自动补「已取消」配平）。
- `loop-state`（纯 EDN 数据）：`{:tool-calls :remaining :records :pending-id}`。

### 1.2 环境类失败暂停（`:env-retry`，批已执行）

来自错误分层路由（详见 agent-loop-concurrency-design.md §5/§10）：

| 类别 | 路由 |
|------|------|
| 语义类（缺省） | 序列化为结果回模型（errors are data） |
| 瞬态类 | 工具级幂等重试（`deftool {:retry ...}`，对模型透明） |
| **环境类** | **屏障处带一致快照暂停等人**（本节） |
| 策略类（gate 拒绝） | 「已拒绝执行」正常结果回模型 |

- 触发：工具抛 `(ex-info "..." {:error-class :environment})`（或 canonical
  `:auth-error` 自动归类），且策略 `:on-env-error :pause`。
  **缺省策略**：react 层 `:proceed`（无人值守不收意外暂停态）；client 层对
  配置了 `:on-tool-call` 的 HITL agent 自动升为 `:pause`，可显式覆盖。
- 此时**批次已执行完**：其他工具的结果与 writes 折叠均已落定（一致快照）；
  批次结果在 `loop-state :batch-messages` 中、尚未交给模型。
- `loop-state`：`{:phase :env-retry :batch-messages :failed-calls :remaining :records}`。

---

## 2. resume：用户答复的全部形态

```clojure
(resume agent decision)            ;; 2-arity
(resume agent decision payload)    ;; 3-arity，payload 携带用户答复
```

### 2.1 审批暂停的决策 × 载荷

| decision | payload | 语义 |
|---|---|---|
| `"approved"` | — | 原参数执行整批 |
| `"approved"` | `{:args 新参数}` | **编辑后批准**：pending 工具以替换后的参数执行 |
| 其他（拒绝） | — | 结果「已拒绝执行」回模型 |
| 其他（拒绝） | `{:message 理由}` | 结果「已拒绝执行：<理由>」——模型直接拿到原因，省一轮干猜 |
| `"reply"` | `{:message 答复}`（必填） | **答复即结果**：pending 工具不执行，答复直接作为其工具结果回模型 |

**完整用法走查**（宿主应用视角，五种响应）：

```clojure
;; 1) 触发暂停，把待决信息呈给用户
(let [r (chat agent "删除订单 42 并通知客户")]
  (when (= :paused (:status r))
    (:pause-reason r)                          ;; "需要审批: delete-order"
    (get-in r [:pending-tool :name])           ;; "delete-order"
    (get-in r [:pending-tool :args])))         ;; {:id 42}——呈给用户看/编辑

;; 2) 按用户答复选择 resume 形态（返回值即最终结果，:completed + :text）
(resume agent "approved")                              ;; 原样批准
(resume agent "approved" {:args {:id 42 :mode :soft}}) ;; 用户编辑了参数后批准
(resume agent "rejected")                              ;; 拒绝（模型收到"已拒绝执行"）
(resume agent "rejected" {:message "该订单有未结算金额，先退款"})
;;   → 工具结果「已拒绝执行：该订单有未结算金额，先退款」——模型直接
;;     基于理由调整策略（如先调 refund 工具），不用追问
(resume agent "reply" {:message "客户已电话确认，跳过通知"})
;;   → pending 工具不执行，这句话就是它的"结果"——模型视角是一次普通工具往返
```

**ask-user 模式**（`"reply"` 解锁的能力）：定义一个 body 永不执行的提问工具，
gate 拦截暂停，用户答案经 reply 送回：

```clojure
(deftool ask-user "向用户提问" [[question :string "问题"]] "不会执行到")
;; callbacks {:on-tool-call (fn [n _] (when (= "ask-user" n) {:interrupt "等用户"}))}
(let [r (chat agent "帮我选方案")]                 ;; 模型调 ask-user("要 A 还是 B？")
  (get-in r [:pending-tool :args :question]))     ;; 呈给用户的问题
(resume agent "reply" {:message "B"})             ;; 答案即工具结果，循环继续
```

自由文本答复的兜底路径（无需 payload）：`rejected` 后模型收到拒绝结果并回应，
用户意见走下一条 chat——LLM-native，但多绕一轮；带理由拒绝/reply 是捷径。

### 2.2 环境暂停的决策

| decision | 语义 |
|---|---|
| `"retry"` / `"approved"` | 环境已修复：**只重跑失败调用**，新结果按 tool-call-id **替换**进原批次消息（历史无重复 tool_result）；若仍环境失败则再次暂停 |
| 其他 | `:proceed`：原错误结果照常交给模型 |
| `"reply"` | **显式拒收**（抛错）——答复语义只属于审批暂停 |

### 2.3 机制细节

- 决策词汇下沉到 `execute-batch` 的 gate 契约：
  `:proceed | :reject | {:reject 理由} | {:reply 结果}`——resume 与 gate 共用，
  未来交互式 gate 可直接返回富决策；
- reply / 拒绝均不执行工具、不触发 `on-tool-result`、无 `:writes`；
- `loop-state :pending-id` 定位暂停工具（`:args` 替换与 reply 靠它）；
  旧版暂停态缺该字段时 `:reply` 显式抛错；
- resume 的 ToolContext 恢复自暂停态 `:tool-context`——**暂停前各轮 writes
  的累积折叠结果保留**（曾是缺口：裸 context 续跑会静默丢槽，已修）；
- **payload 与持久化正交**：payload 在 resume 时才提供、不进暂停快照——
  跨重启恢复的暂停同样可带任意 payload resume（`(resume 重建的agent
  "reply" {:message ...})` 照常工作），快照格式零改动；
- **payload 与 turn 链正交**：resume 同样经过 `:turn` filter 洋葱（一次性
  分派终端）——无论用哪种 payload 恢复，turn 完成的最终答案都会经过
  校验/guardrail 类 filter，可触发反馈重试。机制详见
  `filter-chain-design.md` §2.4。

---

## 3. 暂停态持久化（跨进程 HITL）

### 3.1 快照（纯 EDN 数据，version 1）

```clojure
{:version 1 :conversation-id "..." :paused-at <ms>
 :pause-reason "..." :pending-tool {...}
 :loop-state {...}          ;; react resume 的完整载荷（本就无函数）
 :tool-context {...}}       ;; 累积 context；不可序列化 key（如 :kernel）剥离并 warn
```

**不存**：kernel / tools / callbacks / gate / system-prompt（代码侧 resume 时
重建）、对话历史（ChatMemory 已管——跨重启请配 SQLite store）。
payload 在 resume 时才提供，与快照正交。

### 3.2 PauseStore（`im.ttalk.agent.pause`）

```clojure
(defprotocol PauseStore
  (pause-save!  [store conv-id snapshot])   ;; 每会话至多一份，再暂停覆盖
  (pause-load   [store conv-id])
  (pause-clear! [store conv-id]))
;; in-memory-pause-store / sqlite-pause-store（EDN 一列，locking 串行化，
;; 可与 ChatMemory / BranchStore 同库不同表）
```

### 3.3 Agent 集成（opt-in 全自动）

- `create-agent :pause-store`（缺省 nil 行为不变）；
- 暂停自动 `pause-save!`；任何终态（completed/error/cancelled）、`reset!`、
  暂停态下开新 chat 自动 `pause-clear!`——**store 始终镜像"是否有未决暂停"**；
- `paused?` / `resume` 在本进程无暂停态时**透明回落 store**：重启后同
  conversation-id + 同 stores 重建 agent，API 一行不改。

```clojure
;; 进程 1
(def a (create-agent {:provider p :tools [...]
                      :memory (sqlite/sqlite-store "agent.db")
                      :pause-store (pause/sqlite-pause-store "agent.db")
                      :conversation-id "order-42"
                      :callbacks {:on-tool-call ...}}))
(chat a "删除订单")            ;; :paused，快照已落库 → 进程退出
;; 进程 2：同配置重建
(paused? a2)                   ;; true（经 store 发现）
(resume a2 "approved")
```

### 3.4 明确出界

批次执行**中途**的进程崩溃恢复（durable execution）不做——只保证「暂停点」
这个一致快照跨进程存活。崩溃丢失的是未完成的轮次，恢复靠重发该轮
（幂等要求回到工具作者侧）。

---

## 4. Timeline 与多分支

### 4.1 前提判定：Agent 持久状态 = 对话历史（唯一真相）

- 对话历史：ChatMemory 里的 append-only 日志——**唯一持久状态**；
- context 状态槽：**turn 级**草稿（用户拍板）——每次 chat 从裸 context 起步，
  turn 内跨轮累积，暂停恢复是唯一跨越点；
- 暂停态：至多一份，resume 即消费。

推论：**日志本身就是 timeline**，无需快照版本链，也不存在
"历史与快照两处真相要同步"的问题（旧 process Timeline 的痛点）。

### 4.2 一致性不变量

> **合法的 fork/rollback 点只有两种：turn 边界、暂停点。**

并发工具失败在该不变量下的封口：

1. 批内语义失败：collect-all 下成功结果 + 错误串全部进历史，失败工具 writes
   被丢弃——turn 边界处历史完整、slots 已死，fork 分支与主线看到同一份历史；
2. 事务性保证**历史与状态同真同假**：不存在"历史记成功但 writes 缺失"
   或反之的组合；
3. 环境暂停是唯一 mid-turn 逃逸点，整个被封在暂停快照里：
   **fork 暂停中的会话（全量前缀）自动连带复制 PauseStore 快照**；
   不带快照硬 fork 后直接 chat 由 heal-dangling 兜底。

### 4.3 分支模型：fork-as-new-conversation

分支 = 前缀复制到新 conversation-id + 一条血缘记录（`BranchStore`，
in-memory + SQLite EDN）。现有 ChatMemory 协议零改动；memory advisor /
PauseStore / react 循环全部按 conversation-id 工作——**没有组件感知"树"**，
换分支就是换 conv-id 建 agent。

```clojure
(def deps {:memory mem :pause-store ps :branch (tl/in-memory-branch-store)})
(tl/fork! deps "main" {:as "exp"})   ;; 全量分支（源暂停中 → 连带暂停快照）
(tl/fork! deps "main" {:at 4})       ;; 前 4 条消息处开分支
(tl/rollback! deps "main" 4)         ;; 破坏性截断（"重新生成"；清暂停快照）
(tl/lineage deps "exp")              ;; {:parent "main" :fork-point n ...}
(tl/ancestry deps "exp")             ;; 沿 parent 回溯到根
(tl/prune! deps "exp")               ;; 删分支（有子分支拒绝）
```

对照旧 process Timeline，词汇减半：没有"当前位置"这个可变状态，
switch/goto/back/forward 全部退化为"换 conv-id"。

### 4.4 两个白送的场景

- **HITL 决策分支**：暂停点 `fork!` → 两支各自 `resume` 不同决策
  （approved vs rejected / 不同 payload），对比后果，互不污染——
  旧 V1"同一暂停快照多次 resume"以更干净的形式回归；
- **编辑重试（regenerate）**：fork 前缀不含要改写的 user 消息 + 分支上重发
  = ChatGPT 式编辑分支，主线原样保留。

### 4.5 writes 进历史（event-sourcing 伏笔，用户拍板：记）

tool-result 中立消息携带可选 `:writes` 元数据（该工具对状态槽的写意图）：

- **只进历史存储、不发给 LLM**（openai/anthropic wire 层显式构造，
  多余 key 天然剥落，有测试钉住）；
- 失败/超时/被拒的调用无 writes——历史中自然缺席，与 4.2 的同真同假一致；
- 立即价值：审计（"这个槽的值哪来的"不再需要复现现场）；
- 升级路径：将来若 slots 要跨 turn（session 状态），context 可定义为
  `fold(当前 reducers, 历史 writes)`——历史仍唯一真相、时间旅行=截断重折。
  **不要再造独立快照店**（两处真相的老路）。

---

## 5. API 速查

| 层 | API |
|---|---|
| client | `create-agent {:pause-store :on-env-error :state-slots ...}`；`chat`；`paused?`；`resume [agent decision (payload)]`；`reset!` |
| react | `invoke {... :tool-gate :on-env-error}`；`resume [kernel loop-state decision opts]`（opts `:payload`）；`execute-batch`（gate 决策：`:proceed/:reject/{:reject 理由}/{:reply 结果}`） |
| pause | `in-memory-pause-store` / `sqlite-pause-store`；`pause-save!/load/clear!`；`snapshot`/`strip-unserializable` |
| timeline | `in-memory-branch-store` / `sqlite-branch-store`；`fork!`/`rollback!`/`lineage`/`ancestry`/`prune!` |
| tool 侧约定 | `(throw (ex-info "..." {:error-class :transient/:environment}))`；`deftool {:retry ... :serial ... :sensitive ...}` |

---

## 6. 已知边界与未来方向

1. **durable execution**（批中崩溃恢复）：明确不做（§3.4）；
2. **跨 turn 状态槽**：如需要，走 §4.5 的 fold-from-history，勿建快照店；
3. **多 Agent 编排**：外层决策，另议（届时先答"要不要全局快照语义"：
   BSP 或 actor，不做双引擎——见 agent-loop-concurrency-design.md §6）；
4. resume payload 目前只服务审批 phase；env phase 若将来需要
  "proceed 带补充说明"，同一词汇可扩展。

## 相关文档

- `agent-loop-concurrency-design.md`——循环执行模型（MapReduce/屏障/错误分层）
  的推导、业界对照与各阶段实施记录（§9 S1、§10 S2、§11 持久化、§12 Timeline、
  §13 resume payload、§14 turn 链）
- `filter-chain-design.md`——filter 三链体系（:tool/:chat/:turn 契约、
  递归重入、resume × turn 链的一次性分派）
- 已废弃留档：`process-framework-design.md`、`process-parallel-design.md`、
  `timeline-snapshot-checkpoint.md`（旧 Timeline 的教训来源）
