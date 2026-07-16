# Timeline / Snapshot 设计方案

> **状态：🗑️ 已废弃（2026-07-11）**——随 clj-agent-process 模块整体删除
> （Process 设计重新思考，见 `process-parallel-design.md` 头部说明；
> Timeline 唯一消费者是 process 快照，一并撤下）。代码见 git 历史
> 7bc5d64..764285c。以下为落地时的原状态记录，留档：
>
> **（原）状态：✅ 已实现（2026-07-10），落地位置与本文规划不同。**
> 实际落点：`modules/clj-agent-process/`（而非规划的 `clj-agent-memory` 模块）——
> `im.ttalk.agent.timeline`（通用层：版本链/时间旅行/分支/血缘/prune，
> TimelineStore 协议 + in-memory）、`im.ttalk.agent.timeline.sqlite`（EDN 持久化）、
> `im.ttalk.agent.process.snapshot`（框架层适配：checkpointer 挂 on-quiescent 自动存档、
> resume-checkpoint 跨重启续跑、branch! 分支实验）。
> 与本文差异：无 ITimelineEntry 协议（entry 为纯 map）；无独立 SnapshotManager/
> Adapter 类（函数式适配层）；Redis/Postgres store 未做（按需后补）；
> §五「实现现状」表格的路径为早期规划，作废。语义补充：goto!/switch-branch! 改变
> 当前分支，go-back!/go-forward! 只移位置不切分支（保证退过分叉点再前进的对称性）。

## 一、设计背景

clj-agent 的 **Process 框架**（事件驱动的步骤执行引擎）需要状态持久化和时间旅行能力：
保存执行进度、断点续聊、回溯到历史状态、以及分支实验。

为此分为两层：

- **通用层（Timeline）**：与具体框架无关的版本链 / 时间旅行 / 分支管理，位于 `clj-agent-memory`。
- **框架层（Snapshot）**：Process 框架的状态快照，位于 `clj-agent-core`，委托给 Timeline 做持久化。

> 注：早期版本还存在一个 BSP 图计算引擎（Graph/Pregel）及其 Checkpoint 机制，现已移除。
> 本文档只描述当前仍在使用的 Process Snapshot + Timeline。

## 二、核心概念

| 概念 | 说明 | 所属层 |
|------|------|--------|
| **Timeline** | 通用时间线管理，提供版本链、时间旅行、分支管理 | 通用层（memory） |
| **Snapshot** | Process 框架的状态快照 | Process（core） |

## 三、架构设计

```
┌─────────────────────────────────────────────────────────────────────┐
│                      clj-agent-core                                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  kernel/process/                                                    │
│  ┌─────────────────────────┐                                       │
│  │ snapshot_manager.clj    │                                       │
│  │                         │                                       │
│  │ ProcessSnapshot record  │                                       │
│  │ - step-states           │                                       │
│  │ - paused-step           │                                       │
│  │ - pause-reason          │                                       │
│  │ - context               │                                       │
│  │                         │                                       │
│  │ IProcessSnapshotManager │                                       │
│  │ 协议                    │                                       │
│  └───────────┬─────────────┘                                       │
│              │                                                      │
└──────────────┼──────────────────────────────────────────────────────┘
               │ 委托（通过 ProcessSnapshotAdapter）
               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      clj-agent-memory                                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  manager/timeline.clj                                               │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ ITimelineEntry 协议                                          │    │
│  │ - entry-id, entry-owner-id, entry-parent-id                 │    │
│  │ - entry-version, entry-branch-id, entry-created-at          │    │
│  │ - entry-data                                                 │    │
│  │                                                              │    │
│  │ TimelineManager                                              │    │
│  │ • 版本链管理 (parent-id 链)                                   │    │
│  │ • 位置追踪 (current position per owner)                      │    │
│  │ • 时间旅行 (go-back/go-forward/goto)                         │    │
│  │ • 分支管理 (create-branch/switch-branch/list-branches)       │    │
│  │ • 历史查询 (get-history/get-lineage)                         │    │
│  │ • 自动清理 (auto-prune)                                      │    │
│  └───────────────────────────────┬──────────────────────────────┘    │
│                                  │                                   │
│  manager/snapshot.clj（SnapshotManager，委托 TimelineManager）       │
│                                  │                                   │
│  store/*.clj                                                        │
│  ┌───────────────────────────────┴──────────────────────────────┐    │
│  │ IKeyValueStore                                                │    │
│  │ InMemoryStore │ SQLiteStore │ RedisStore │ PostgresStore     │    │
│  └──────────────────────────────────────────────────────────────┘    │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

## 四、Process Snapshot 元信息

```clojure
{;; 时间线通用字段
 :id              "snap-uuid"
 :thread-id       "session-001"      ; owner-id
 :parent-id       "snap-prev-uuid"
 :version         5
 :branch-id       "main"
 :created-at      1234567890

 ;; Process 专用字段
 :process-name    :my-process
 :status          :paused            ; :running/:paused/:completed/:error
 :paused-step     :wait-user-input   ; 暂停的步骤 ID (单一)
 :pause-reason    "等待用户确认"
 :context         {:user-id "u123"}  ; 共享上下文
 :step-states     {:step-a {:state {:count 1} :activation-count 3}
                   :step-b {:state {} :activation-count 0}}

 ;; 元数据
 :checkpoint-reason :paused}         ; :step-done/:paused/:quiescent/:completed
```

## 五、实现现状

| 组件 | 文件路径 |
|------|---------|
| TimelineManager | `clj-agent-memory/src/im/ttalk/agent/memory/manager/timeline.clj` |
| SnapshotManager | `clj-agent-memory/src/im/ttalk/agent/memory/manager/snapshot.clj` |
| ProcessSnapshotAdapter | `clj-agent-memory/src/im/ttalk/agent/memory/process_snapshot_adapter.clj` |
| IProcessSnapshotManager 协议 | `clj-agent-core/src/im/ttalk/agent/core/kernel/process/snapshot_manager.clj` |

## 六、API 概览

### TimelineManager API

```clojure
;; 核心操作
(save [manager entry])
(load-by-id [manager owner-id entry-id])
(load-latest [manager owner-id])
(list-entries [manager owner-id opts])
(delete [manager entry-id])

;; 时间旅行
(go-back [manager owner-id steps])
(go-forward [manager owner-id steps])
(goto [manager owner-id entry-id])
(get-position [manager owner-id])

;; 分支管理
(create-branch [manager owner-id entry-id branch-name])
(switch-branch [manager branch-id])
(list-branches [manager owner-id])

;; 历史查询
(get-history [manager owner-id opts])
(get-lineage [manager entry-id])

;; 清理
(prune [manager owner-id opts])
```

### IProcessSnapshotManager API

```clojure
(save-checkpoint [this thread-id snapshot metadata])
(load-checkpoint [this thread-id checkpoint-id])
(load-latest [this thread-id])
(list-checkpoints [this thread-id opts])
(goto-checkpoint [this thread-id checkpoint-id])
(create-branch [this thread-id checkpoint-id branch-name])
```
