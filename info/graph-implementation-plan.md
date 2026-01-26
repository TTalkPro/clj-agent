# Graph 模块渐进式开发方案

基于 beamai graph/pregel 系统分析，设计 clj-agent 独立的 graph 模块实现方案。

## 实现状态

| Phase | 模块 | 状态 |
|-------|------|------|
| 1 | `graph/state.clj` | ✅ 完成 |
| 2 | `graph/node.clj`, `graph/edge.clj` | ✅ 完成 |
| 3 | `graph/builder.clj` | ✅ 完成 |
| 4 | `graph/reducer.clj` | ✅ 完成 |
| 5 | `graph/dispatch.clj` | ✅ 完成 |
| 6 | `graph/executor.clj` | ✅ 完成 |
| 7 | `pregel/vertex.clj`, `pregel/barrier.clj`, `pregel/worker.clj`, `pregel/core.clj` | ✅ 完成（简化版） |
| 8 | `snapshot.clj` | 待实现 |
| 9 | `graph/api.clj`, `pregel/api.clj` | ✅ 完成 |

**注意**: Pregel 并行执行（`run`）有 barrier 同步问题待修复，`run-simple` 可正常使用。

## 系统对比

| 特性 | beamai (Erlang) | clj-agent (Clojure) |
|------|-----------------|---------------------|
| 并发模型 | Erlang 进程 | core.async channel + go blocks |
| 状态管理 | 全局 global_state | 不可变 state map |
| 同步机制 | BSP 屏障（超步） | channel 协调 |
| 并行执行 | Master-Worker | 多 go-loop 或 async/thread |
| 增量更新 | Delta + Field Reducer | 函数式合并 |

## 模块结构设计

```
modules/clj-agent-graph/
├── src/im/ttalk/agent/graph/
│   ├── state.clj           # Phase 1: 状态管理
│   ├── node.clj            # Phase 2: 节点定义
│   ├── edge.clj            # Phase 2: 边定义（直接/扇出/条件）
│   ├── builder.clj         # Phase 3: Graph Builder API
│   ├── compiler.clj        # Phase 3: 编译为可执行结构
│   ├── reducer.clj         # Phase 4: Field Reducer
│   ├── dispatch.clj        # Phase 5: 动态并行分发
│   ├── executor.clj        # Phase 6: 执行引擎（channel 驱动）
│   ├── pregel/
│   │   ├── core.clj        # Phase 7: Pregel 核心
│   │   ├── master.clj      # Phase 7: Master 协调
│   │   ├── worker.clj      # Phase 7: Worker 计算
│   │   └── barrier.clj     # Phase 7: 同步屏障
│   ├── snapshot.clj        # Phase 8: 快照与恢复
│   └── api.clj             # Phase 9: 统一 API 门面
└── test/
```

---

## Phase 1: 状态管理 (state.clj)

**目标**: 实现不可变状态容器，支持键规范化和用户上下文隔离。

### 核心 API

```clojure
(ns im.ttalk.agent.graph.state)

;; 创建状态
(defn create
  "创建 graph state，键自动规范化为 keyword"
  [initial-map]
  {...})

;; 基础操作
(defn get-val [state key] ...)
(defn get-val [state key default] ...)
(defn set-val [state key value] ...)
(defn set-many [state kvs] ...)
(defn update-val [state key f] ...)
(defn delete-val [state key] ...)

;; 用户上下文（隔离存储）
(defn set-context [state ctx-map] ...)
(defn get-context [state key] ...)
(defn update-context [state key f] ...)

;; 工具函数
(defn keys-of [state] ...)
(defn to-map [state] ...)
```

### 设计要点

- 内部使用 keyword 键（Clojure 惯例）
- 用户上下文存储在 `::user-context` 下
- 所有操作返回新状态（不可变）

---

## Phase 2: 节点与边 (node.clj, edge.clj)

**目标**: 定义节点和边的数据结构及执行语义。

### 节点 (node.clj)

```clojure
(ns im.ttalk.agent.graph.node)

;; 节点定义
(defn create-node
  "创建节点
   - id: keyword
   - handler: (fn [state vertex-input] -> result)
   - opts: {:metadata {} :timeout nil}"
  [id handler & {:as opts}]
  {:id id
   :handler handler
   :metadata (:metadata opts {})
   :timeout (:timeout opts)})

;; 特殊节点
(def START :__start__)
(def END :__end__)

;; 节点返回值类型
;; {:ok state}                    - 成功，返回新状态
;; {:error reason}                - 失败
;; {:interrupt reason state}      - 暂停（human-in-the-loop）
;; {:command {:update delta :goto targets}} - Command 模式
```

### 边 (edge.clj)

```clojure
(ns im.ttalk.agent.graph.edge)

;; 直接边（无条件）
(defn direct
  "创建直接边: from -> to"
  [from to]
  {:type :direct :from from :to to})

;; 扇出边（静态并行）
(defn fanout
  "创建扇出边: from -> [to1 to2 ...]"
  [from targets]
  {:type :fanout :from from :to targets})

;; 条件边（动态路由）
(defn conditional
  "创建条件边
   router-fn: (fn [state] -> target | [targets] | [dispatches])
   route-map: {key -> target} 可选映射"
  [from router-fn & {:keys [route-map]}]
  {:type :conditional
   :from from
   :router router-fn
   :route-map route-map})

;; 解析边，返回目标节点
(defn resolve-edge
  "解析边，返回 {:targets [...] :dispatches [...]}"
  [edge state]
  ...)
```

---

## Phase 3: Builder 与编译器 (builder.clj, compiler.clj)

**目标**: 提供 DSL 构建图，编译为扁平化可执行结构。

### Builder API

```clojure
(ns im.ttalk.agent.graph.builder)

(defn graph
  "创建 graph builder"
  [name]
  {:name name :nodes {} :edges []})

(defn add-node
  "添加节点"
  [g id handler & opts]
  ...)

(defn add-edge
  "添加边（自动推断类型）"
  [g from to]
  ...)

(defn add-conditional-edge
  "添加条件边"
  [g from router-fn & opts]
  ...)

(defn set-entry
  "设置入口节点"
  [g node-id]
  ...)

(defn compile
  "编译为可执行 graph-spec"
  [g]
  ...)
```

### 使用示例

```clojure
(def my-graph
  (-> (graph :my-workflow)
      (add-node :process-a (fn [state _] {:ok (assoc state :a 1)}))
      (add-node :process-b (fn [state _] {:ok (assoc state :b 2)}))
      (add-edge START :process-a)
      (add-conditional-edge :process-a
        (fn [state]
          (if (:need-b state) :process-b END)))
      (add-edge :process-b END)
      (set-entry :process-a)
      (compile)))
```

### 编译输出（扁平化顶点）

```clojure
{:name :my-workflow
 :vertices {:process-a {:id :process-a
                        :handler fn
                        :edges [...]     ;; 直接存储出边
                        :halted false}
            :process-b {...}}
 :entry :process-a
 :max-iterations 100}
```

---

## Phase 4: Field Reducer (reducer.clj)

**目标**: 实现字段级增量合并策略。

```clojure
(ns im.ttalk.agent.graph.reducer)

;; 内置 Reducer
(defn last-write-wins
  "后值覆盖（默认）"
  [_old new] new)

(defn append
  "列表追加"
  [old new]
  (into (or old []) new))

(defn deep-merge
  "Map 深度合并"
  [old new]
  (merge-with deep-merge old new))

(defn increment
  "数值增量"
  [old new]
  (+ (or old 0) new))

;; 应用 reducer
(defn apply-delta
  "应用 delta 到 state，使用 field-reducers"
  [state delta field-reducers]
  (reduce-kv
    (fn [s k v]
      (let [reducer (get field-reducers k last-write-wins)
            old-val (get s k)]
        (assoc s k (reducer old-val v))))
    state
    delta))

;; 计算 delta
(defn compute-delta
  "计算 old-state 到 new-state 的增量"
  [old-state new-state]
  ...)
```

---

## Phase 5: Dispatch 动态并行 (dispatch.clj)

**目标**: 实现动态并行分发机制。

```clojure
(ns im.ttalk.agent.graph.dispatch)

(defn dispatch
  "创建 dispatch（动态并行分支）"
  ([node-id] (dispatch node-id {}))
  ([node-id input] (dispatch node-id input {}))
  ([node-id input opts]
   {:__dispatch__ true
    :node node-id
    :input input
    :id (or (:id opts) (random-uuid))
    :metadata (:metadata opts {})}))

(defn dispatch?
  "检查是否为 dispatch 对象"
  [x]
  (and (map? x) (:__dispatch__ x)))

(defn fan-out
  "批量创建 dispatch
   (fan-out :process-node [item1 item2] (fn [item] {:data item}))"
  [node-id items transform-fn]
  (mapv #(dispatch node-id (transform-fn %)) items))

(defn fan-out-indexed
  "带索引的批量创建
   (fan-out-indexed :node items (fn [idx item] {:index idx :data item}))"
  [node-id items transform-fn]
  (mapv-indexed #(dispatch node-id (transform-fn %1 %2)) items))
```

---

## Phase 6: 执行引擎 (executor.clj)

**目标**: 使用 channel 实现图执行引擎。

### 架构设计

```
┌─────────────────────────────────────────────────────┐
│  Executor (协调者)                                   │
│  - 管理全局状态                                       │
│  - 调度节点执行                                       │
│  - 收集结果                                          │
└──────────────┬──────────────────────────────────────┘
               │ activation-chan (激活请求)
               ▼
┌─────────────────────────────────────────────────────┐
│  Worker Pool (go-loop 或 async/thread)              │
│  - 执行节点 handler                                  │
│  - 返回 delta + 下一步激活                           │
└──────────────┬──────────────────────────────────────┘
               │ result-chan (执行结果)
               ▼
┌─────────────────────────────────────────────────────┐
│  Executor                                            │
│  - 合并 delta（使用 field-reducers）                 │
│  - 发送下一轮激活                                     │
│  - 检测终止条件                                       │
└─────────────────────────────────────────────────────┘
```

### 核心 API

```clojure
(ns im.ttalk.agent.graph.executor
  (:require [clojure.core.async :as async :refer [go go-loop <! >! chan close!]]))

(defn run
  "同步执行图（阻塞直到完成）
   返回 {:status :completed/:error/:interrupted
         :state final-state
         :iterations n}"
  [graph-spec initial-state & {:keys [field-reducers max-iterations]}]
  ...)

(defn run-async
  "异步执行，返回 ExecutorHandle"
  [graph-spec initial-state & opts]
  ...)

(defn stream
  "流式执行，返回迭代器函数
   每次调用返回 {:yield state :next fn} 或 {:done result}"
  [graph-spec initial-state & opts]
  ...)
```

### Channel 结构

```clojure
;; 激活请求
{:node-id :process-a
 :vertex-input {...}  ;; dispatch 输入（可选）
 :iteration n}

;; 执行结果
{:node-id :process-a
 :status :ok/:error/:interrupt
 :delta {...}              ;; 状态增量
 :activations [...]        ;; 下一步激活的节点/dispatch
 :error nil}               ;; 错误信息（如有）
```

---

## Phase 7: Pregel 核心 (pregel/)

**目标**: 实现 BSP 同步模型（可选，用于需要严格超步同步的场景）。

### 与 Executor 的区别

| Executor（Phase 6） | Pregel（Phase 7） |
|---------------------|-------------------|
| 事件驱动，异步 | BSP 超步同步 |
| 节点完成即可继续 | 等待所有节点完成再进入下一步 |
| 适合流水线 | 适合迭代算法（PageRank 等）|

### 核心组件

```clojure
;; pregel/core.clj - 主 API
(defn run-pregel
  "BSP 模式执行
   每个超步：1. 广播状态 2. 并行计算 3. 屏障同步 4. 合并 delta"
  [graph-spec initial-state & opts]
  ...)

;; pregel/master.clj - Master 协调
(defn start-master [graph-spec opts] ...)

;; pregel/worker.clj - Worker 计算
(defn start-worker [worker-id vertices master-chan] ...)

;; pregel/barrier.clj - 同步屏障
(defn create-barrier [n] ...)
(defn await-barrier [barrier] ...)  ;; 所有 worker 完成
```

### Channel 架构（BSP）

```
       ┌──────────────────────────────────┐
       │  Master                           │
       │  global-state-atom                │
       │  pending-deltas                   │
       └──────────┬───────────────────────┘
                  │
    ┌─────────────┼─────────────┐
    │ broadcast   │  collect    │
    ▼             │             ▼
┌───────┐    ┌───────┐    ┌───────┐
│Worker1│    │Worker2│    │Worker3│
│go-loop│    │go-loop│    │go-loop│
└───┬───┘    └───┬───┘    └───┬───┘
    │            │            │
    └────────────┼────────────┘
                 │ result-chan
                 ▼
         ┌───────────────┐
         │   Barrier     │
         │ (等待所有完成) │
         └───────────────┘
```

---

## Phase 8: 快照与恢复 (snapshot.clj)

**目标**: 支持执行中间状态保存和恢复。

```clojure
(ns im.ttalk.agent.graph.snapshot)

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

;; 执行时配置快照回调
(run graph-spec state
  :on-snapshot (fn [snapshot-data]
                 ;; 保存到持久化存储
                 ;; 返回 :continue | :stop | {:retry [node-ids]}
                 :continue))
```

---

## Phase 9: 统一 API (api.clj)

**目标**: 提供统一的门面 API。

```clojure
(ns im.ttalk.agent.graph.api
  (:require [im.ttalk.agent.graph.state :as state]
            [im.ttalk.agent.graph.node :as node]
            [im.ttalk.agent.graph.edge :as edge]
            [im.ttalk.agent.graph.builder :as builder]
            [im.ttalk.agent.graph.executor :as executor]
            [im.ttalk.agent.graph.dispatch :as dispatch]))

;; 重导出常用 API
(def graph builder/graph)
(def add-node builder/add-node)
(def add-edge builder/add-edge)
(def compile builder/compile)

(def state state/create)
(def run executor/run)
(def stream executor/stream)

(def dispatch dispatch/dispatch)
(def fan-out dispatch/fan-out)

(def START node/START)
(def END node/END)
```

### 完整使用示例

```clojure
(require '[im.ttalk.agent.graph.api :as g])

;; 1. 定义节点处理函数
(defn fetch-data [state _]
  {:ok (assoc state :data (fetch-from-api))})

(defn process-items [state vertex-input]
  ;; vertex-input 来自 dispatch
  (let [item (:item vertex-input)
        result (process item)]
    {:ok (update state :results conj result)}))

(defn aggregate [state _]
  {:ok (assoc state :summary (summarize (:results state)))})

;; 2. 构建图
(def workflow
  (-> (g/graph :data-pipeline)
      (g/add-node :fetch fetch-data)
      (g/add-node :process process-items)
      (g/add-node :aggregate aggregate)
      (g/add-edge g/START :fetch)
      (g/add-conditional-edge :fetch
        (fn [state]
          ;; 动态并行：为每个 item 创建 dispatch
          (g/fan-out :process (:items state) (fn [item] {:item item}))))
      (g/add-edge :process :aggregate)  ;; 所有 dispatch 完成后汇聚
      (g/add-edge :aggregate g/END)
      (g/set-entry :fetch)
      (g/compile)))

;; 3. 执行
(def result
  (g/run workflow
         (g/state {:items []})
         :field-reducers {:results g/append}  ;; 并行结果追加合并
         :max-iterations 100))

;; 4. 流式执行
(def iter-fn (g/stream workflow (g/state {})))
(loop [f iter-fn]
  (let [r (f)]
    (if (:done r)
      (println "完成:" (:result r))
      (do
        (println "中间状态:" (:state r))
        (recur (:next r))))))
```

---

## 开发顺序与时间建议

| Phase | 模块 | 依赖 | 复杂度 |
|-------|------|------|--------|
| 1 | state.clj | 无 | 低 |
| 2 | node.clj, edge.clj | Phase 1 | 低 |
| 3 | builder.clj, compiler.clj | Phase 2 | 中 |
| 4 | reducer.clj | Phase 1 | 低 |
| 5 | dispatch.clj | Phase 2 | 低 |
| 6 | executor.clj | Phase 1-5 | 高 |
| 7 | pregel/* | Phase 6 | 高 |
| 8 | snapshot.clj | Phase 6 | 中 |
| 9 | api.clj | Phase 1-8 | 低 |

**建议**：Phase 1-5 可快速完成，Phase 6 是核心难点，Phase 7 可根据需求决定是否实现。

---

## Channel 使用模式总结

```clojure
;; 1. Executor 内部
activation-chan  ;; 激活请求队列
result-chan      ;; 执行结果队列
control-chan     ;; 控制信号（stop/pause）

;; 2. 并行执行
;; 方案 A: 多个 go-loop worker（轻量，适合 I/O 密集）
(dotimes [_ num-workers]
  (go-loop []
    (when-let [task (<! activation-chan)]
      (let [result (execute-node task)]
        (>! result-chan result))
      (recur))))

;; 方案 B: async/thread（适合 CPU 密集）
(go-loop []
  (when-let [task (<! activation-chan)]
    (let [result-ch (async/thread (execute-node task))]
      (>! result-chan (<! result-ch)))
    (recur)))

;; 3. 汇聚（Fan-in）
;; 使用 async/merge 或计数器等待所有 dispatch 完成
```
