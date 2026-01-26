# Timeline / Snapshot / Checkpoint 设计方案

## 一、设计背景

clj-agent 有两个执行框架：
- **Process 框架**：事件驱动的步骤执行引擎
- **Graph 框架**：BSP 同步的图计算引擎（Pregel）

两者都需要状态持久化和时间旅行能力，但元信息结构不同：
- Process 需要 `step-states`、`paused-step` 等
- Graph 需要 `vertices`、`superstep`、`failed-vertices` 等

## 二、核心概念

| 概念 | 说明 | 所属框架 |
|------|------|---------|
| **Timeline** | 通用时间线管理，提供版本链、时间旅行、分支管理 | 通用层 |
| **Snapshot** | Process 框架的状态快照 | Process |
| **Checkpoint** | Graph 框架的检查点 | Graph |

## 三、架构设计

```
┌─────────────────────────────────────────────────────────────────────┐
│                      clj-agent-core                                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  kernel/process/              │    graph/                           │
│  ┌─────────────────────────┐  │  ┌─────────────────────────┐       │
│  │ snapshot.clj            │  │  │ checkpoint.clj          │       │
│  │                         │  │  │                         │       │
│  │ ProcessSnapshot record  │  │  │ GraphCheckpoint record  │       │
│  │ - step-states           │  │  │ - vertices              │       │
│  │ - paused-step           │  │  │ - superstep             │       │
│  │ - pause-reason          │  │  │ - pending-activations   │       │
│  │ - context               │  │  │ - failed-vertices       │       │
│  │                         │  │  │ - interrupted-vertices  │       │
│  │ ProcessSnapshotManager  │  │  │ - checkpoint-type       │       │
│  │ (实现 IProcessSnapshot  │  │  │                         │       │
│  │  Manager 协议)          │  │  │ GraphCheckpointManager  │       │
│  └───────────┬─────────────┘  │  └───────────┬─────────────┘       │
│              │                │              │                      │
└──────────────┼────────────────┼──────────────┼──────────────────────┘
               │                │              │
               └────────────────┴──────┬───────┘
                                       │ 委托
                                       ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      clj-agent-memory                                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  timeline/core.clj                                                  │
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
│  store/*.clj (现有，无需修改)                                        │
│  ┌───────────────────────────────┴──────────────────────────────┐    │
│  │ IKeyValueStore                                                │    │
│  │ InMemoryStore │ SQLiteStore │ RedisStore │ PostgresStore     │    │
│  └──────────────────────────────────────────────────────────────┘    │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

## 四、元信息对比

### Process Snapshot

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

### Graph Checkpoint

```clojure
{;; 时间线通用字段
 :id              "cp-uuid"
 :run-id          "run-001"          ; owner-id
 :parent-id       "cp-prev-uuid"
 :version         3
 :branch-id       "main"
 :created-at      1234567890

 ;; Graph/Pregel 专用字段
 :graph-name      :pagerank
 :superstep       3                  ; 当前超步
 :iteration       5                  ; 迭代次数

 ;; 顶点状态
 :vertices        {:v1 {:value 10 :active true :messages [] :halt-voted false}
                   :v2 {:value 20 :active false :messages [] :halt-voted true}}
 :pending-activations [:v3 :v4]      ; 待激活顶点
 :pending-deltas  [{:vertex-id :v1 :delta {:value 5}}]
 :global-state    {:total 100}       ; 全局状态

 ;; 顶点分类
 :active-vertices      [:v1]         ; 活跃顶点
 :completed-vertices   [:v5 :v6]     ; 已完成顶点
 :failed-vertices      [:v7]         ; 失败顶点（可重试）
 :interrupted-vertices [:v8]         ; 中断顶点（等待输入）

 ;; 恢复信息
 :checkpoint-type :superstep         ; :initial/:superstep/:error/:interrupt/:final
 :resumable?      true
 :resume-data     {:v8 {:user-input "confirmed"}}
 :retry-count     0}
```

## 五、关键差异

| 维度 | Process Snapshot | Graph Checkpoint |
|------|------------------|------------------|
| **执行模型** | 事件驱动，异步 | BSP 同步，超步 |
| **状态单位** | Step（步骤） | Vertex（顶点） |
| **状态组织** | `{step-id -> {:state :activation-count}}` | `{vertex-id -> {:value :active :messages :halt-voted}}` |
| **暂停机制** | 单一 `paused-step` | 多个 `interrupted-vertices` |
| **失败处理** | 无显式支持 | `failed-vertices` + `retry-count` |
| **延迟提交** | 无 | `pending-deltas` |
| **消息队列** | 通过事件 channel | `vertex.messages` |
| **全局状态** | `context` | `global-state` |
| **类型分类** | 通过 `checkpoint-reason` 区分 | 显式 `checkpoint-type` 枚举 |

## 六、实施计划

### 步骤 1: 新建 timeline/core.clj

位置: `clj-agent-memory/src/im/ttalk/agent/memory/timeline/core.clj`

提供：
- `ITimelineEntry` 协议
- `TimelineManager` record
- 通用时间线操作（save/load/go-back/go-forward/goto/branch 等）

### 步骤 2: 重构 snapshot/manager.clj

将现有的 `SnapshotManager` 重构为委托给 `TimelineManager`。

### 步骤 3: 新建 graph/checkpoint.clj

位置: `clj-agent-core/src/im/ttalk/agent/core/graph/checkpoint.clj`

提供：
- `GraphCheckpoint` record
- `IGraphCheckpointManager` 协议
- `GraphCheckpointManager` 实现
- Pregel 集成函数

### 步骤 4: 集成到 Pregel/Graph 引擎

修改 `pregel/core.clj` 和 `graph/executor.clj`，添加 checkpoint 回调支持。

## 七、文件变更清单

| 操作 | 文件路径 |
|------|---------|
| 新建 | `clj-agent-memory/src/im/ttalk/agent/memory/timeline/core.clj` |
| 重构 | `clj-agent-memory/src/im/ttalk/agent/memory/snapshot/manager.clj` |
| 新建 | `clj-agent-core/src/im/ttalk/agent/core/graph/checkpoint.clj` |
| 修改 | `clj-agent-core/src/im/ttalk/agent/core/pregel/core.clj` |
| 修改 | `clj-agent-core/src/im/ttalk/agent/core/graph/executor.clj` |

## 八、API 概览

### TimelineManager API

```clojure
;; 核心操作
(save [manager entry])
(load-by-id [manager entry-id])
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

### GraphCheckpointManager API

```clojure
;; 核心操作（委托给 timeline）
(save-checkpoint [this run-id checkpoint])
(load-checkpoint [this checkpoint-id])
(load-latest [this run-id])
(go-back [this run-id steps])
(go-forward [this run-id steps])
(goto-checkpoint [this run-id checkpoint-id])
(create-branch [this run-id checkpoint-id branch-name])

;; Graph 专用操作
(retry-vertex [this run-id vertex-id])
(inject-resume-data [this run-id vertex-id data])
(get-failed-vertices [this checkpoint])
(get-interrupted-vertices [this checkpoint])

;; Pregel 集成
(from-pregel-state [pregel-state opts])
(to-pregel-restore-opts [checkpoint])
```

## 九、参考

- beamai checkpoint: `~/workspace/beamai/apps/beamai_memory/src/checkpoint/`
- beamai timeline: `~/workspace/beamai/apps/beamai_memory/src/timeline/`
- 现有 snapshot manager: `clj-agent-memory/src/.../snapshot/manager.clj`
- Process snapshot 协议: `clj-agent-core/src/.../process/snapshot_manager.clj`
