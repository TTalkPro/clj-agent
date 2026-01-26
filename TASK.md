# 待完成任务

## Graph/Pregel 模块

### 1. Pregel 并行执行 barrier 同步问题

**位置**: `modules/clj-agent-core/src/im/ttalk/agent/core/pregel/core.clj`

**问题描述**:
`pregel/run` 函数在使用多 Worker 并行执行时，barrier 同步存在问题，导致超时。

**现象**:
- `run-simple`（单线程）正常工作
- `run`（多 Worker）在 `barrier/await-all-reusable` 处超时

**可能原因**:
1. barrier reset 时机不对（在发送命令后 reset，Worker 可能已经 arrive 到旧 channel）
2. Worker go-loop 与 barrier 的协调问题

**修复方向**:
1. 在发送命令前 reset barrier
2. 确保 Worker 正确处理命令并 arrive
3. 考虑使用 promise 或其他同步机制替代 reusable barrier

**相关文件**:
- `pregel/core.clj` - 第 374-376 行
- `pregel/barrier.clj` - `reset-barrier`, `await-all-reusable`
- `pregel/worker.clj` - `start-worker-loop`

---

### 2. Graph Snapshot 快照与恢复

**位置**: `modules/clj-agent-core/src/im/ttalk/agent/core/graph/snapshot.clj`（待创建）

**功能需求**:
1. 捕获执行中间状态
2. 从快照恢复执行
3. 支持 `on-snapshot` 回调

**API 设计**:

```clojure
(ns im.ttalk.agent.core.graph.snapshot)

(defn capture
  "捕获当前快照"
  [executor-state]
  {:global-state (:state executor-state)
   :pending-activations (:activations executor-state)
   :iteration (:iteration executor-state)
   :timestamp (System/currentTimeMillis)})

(defn restore
  "从快照恢复执行"
  [graph-spec snapshot & opts]
  ...)

;; executor.clj 需要支持
(run graph-spec state
  :on-snapshot (fn [snapshot-data]
                 ;; 返回 :continue | :stop | {:retry [node-ids]}
                 :continue))
```

**参考**: beamai 的 `graph_snapshot.erl`

---

### 3. Pregel Snapshot 支持

**功能需求**:
1. 超步间快照
2. 错误恢复
3. 延迟提交（pending_deltas）

**参考**: beamai 的 pregel 延迟提交机制

---

## 优先级

| 任务 | 优先级 | 复杂度 |
|------|--------|--------|
| Pregel 并行执行修复 | 高 | 中 |
| Graph Snapshot | 中 | 中 |
| Pregel Snapshot | 低 | 高 |
