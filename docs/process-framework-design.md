# Process Framework 设计文档

## 核心概念对照

| 概念 | SK (C#) | BeamAI (Erlang) | Clojure 实现 |
|------|---------|-----------------|-------------|
| Process | `ProcessBuilder` → `KernelProcess` | `beamai_process_builder` → `beamai_process_runtime` | Builder map → process-spec → 执行引擎 |
| Step | `KernelProcessStep` + `[KernelFunction]` | 模块 callbacks: `init/1`, `can_activate/2`, `on_activate/3` | map 定义 + 3 个 fn |
| Event | `KernelProcessEvent` | tagged map `{name, type, source, data}` | 普通 map |
| Edge/Binding | `.OnEvent().SendEventTo()` | `event_binding()` map | 声明式 map |
| State | `KernelProcessStep<TState>` | `step_runtime_state` 中的 `:state` | 纯函数式传递 |
| Context | `Kernel` 注入 | `beamai_context:t()` | 已有的 `context.clj` |
| Pause/Resume | External event + parameter waiting | `{pause, reason, state}` + `on_resume/3` | step 返回 `{:pause reason}` |

## 三层架构

```
┌─────────────────────────────────────────────────────┐
│  Builder Layer (纯数据，无副作用)                      │
│  - process-spec: steps + bindings + initial-events    │
│  - 编译时验证                                        │
└─────────────────────────────────────────────────────┘
                        ↓ build
┌─────────────────────────────────────────────────────┐
│  Runtime Layer (驱动执行)                             │
│  - 事件队列 → 路由 → 激活判断 → 执行 → 产出事件       │
│  - 状态机: idle → running → paused/completed/failed  │
└─────────────────────────────────────────────────────┘
                        ↓ 调用
┌─────────────────────────────────────────────────────┐
│  Step Layer (业务逻辑)                               │
│  - init: 初始化 step 状态                            │
│  - can-activate?: 输入是否满足                       │
│  - on-activate: 执行逻辑，返回事件                   │
│  - (可选) on-resume: 恢复暂停                        │
│  - (可选) on-terminate: 资源清理                     │
└─────────────────────────────────────────────────────┘
```

## Step 生命周期函数执行顺序

```
Step 启动
    │
    ▼
init(config) → state₀           仅执行一次，创建初始状态
    │
    ▼
[等待输入] ←──────────────────────┐
    │                              │
    ▼ 输入到达，收集到 collected-inputs
    │                              │
    ▼ required-inputs 全部收齐？    │
    │  否 → 继续等待               │
    ▼ 是                           │
can-activate?(inputs, state) ──────┘
    │  false → 继续等待（不清空 inputs）
    ▼ true
on-activate(inputs, state, ctx)
    │
    ├─→ {:events [...]} → 产出事件，回到[等待输入]（可循环激活）
    ├─→ {:pause {...}}  → process 暂停
    │       │
    │       ▼ 外部 resume
    │   on-resume(data, state, ctx)
    │       ├─→ {:events [...]} → 继续执行
    │       └─→ {:pause {...}}  → 再次暂停
    └─→ {:error {...}}  → 报错终止

Process 结束（completed/failed/timeout）
    │
    ▼
on-terminate(state, ctx)         资源清理，异常被捕获忽略
```

### 调用次数

| 函数 | 调用时机 | 调用次数 |
|------|---------|---------|
| `init` | step worker 启动时 | 1 次 |
| `can-activate?` | required-inputs 收齐后 | 每次输入收齐都检查 |
| `on-activate` | can-activate? 通过后 | 循环场景可多次 |
| `on-resume` | 暂停后外部调用 resume | 每次 resume 1 次 |
| `on-terminate` | process 结束时 | 1 次 |

### 注意事项

- `init` 的返回值作为 `state₀` 传给后续所有函数
- `can-activate?` 返回 false 时**不清空** collected-inputs，下次新输入到达时再次检查
- `on-activate` 激活后**自动清空** collected-inputs，使 step 可被再次激活（循环模式）
- `on-resume` 接收的 `data` 是外部传入的恢复数据，不是 collected-inputs
- `on-terminate` 的异常会被捕获忽略，不影响其他 step 的清理

## 事件驱动执行模型

1. 维护一个事件队列
2. 每次取出一个事件 → 通过 bindings 路由 → 投递到目标 step 的 input 槽
3. 检查哪些 step 的 `required-inputs` 全部满足 → 执行
4. 执行结果是新的事件 → 入队 → 循环
5. 队列空 → completed

## Step 激活模型

- 每个 step 有 `required-inputs`（默认 `[:input]`）
- 有一个 `collected-inputs` map 收集已到达的输入
- `can-activate?(collected-inputs, state)` 自定义守卫
- 激活后 `clear-inputs` → 允许循环重新激活

## V1 设计决策

- **纯函数式同步循环** — 不引入 core.async 依赖
- **Step 状态在返回值传递** — 不用 atom，保持纯函数
- **只支持 sequential** — V1 简单可靠
- **不支持快照** — V1 先跑通
- **error-handler step** — 可选配置
- **与 Context 集成** — step 的 on-activate 接收和返回 context

## V1 文件清单

```
modules/clj-agent-core/src/im/ttalk/agent/core/kernel/process/
├── event.clj      ;; 事件创建 + 路由
├── step.clj       ;; Step 激活逻辑
├── builder.clj    ;; Builder API + 验证
└── runtime.clj    ;; 事件循环执行引擎
```

## 数据结构

### Step 定义

```clojure
{:id              :step-name
 :init            (fn [config] initial-state)
 :can-activate?   (fn [inputs state] boolean)       ;; 可选
 :on-activate     (fn [inputs state context] result)
 :on-resume       (fn [data state context] result)  ;; 可选
 :on-terminate    (fn [state context] nil)          ;; 可选，资源清理
 :required-inputs [:input]                          ;; 默认 [:input]
 :config          {}}
```

### on-activate 返回值

```clojure
{:events  [{:name :event-name :data any}]   ;; 产出的事件
 :state   new-state                          ;; 更新的 step 状态
 :context updated-context}                   ;; 可选，更新 context

;; 或暂停
{:pause {:reason "等待用户审批" :state new-state}}

;; 或错误
{:error {:reason "something failed"}}
```

### State vs Context

Process 中有两个层次的状态：

#### State — Step 私有状态

每个 step 独立拥有，其他 step 无法访问。由 `init` 创建，通过返回 `:state` 更新。

```clojure
{:id :counter-step
 :init (fn [_] {:count 0})                    ;; 创建初始 state
 :on-activate (fn [inputs state ctx]
                ;; state = {:count 0}，仅此 step 可见
                {:state {:count (inc (:count state))}  ;; 更新 state
                 :events [...]})}
```

典型用途：迭代计数、累积中间结果、step 内部缓存。

#### Context — 全局共享状态

所有 step 共享，通过 `ctx/get-var` / `ctx/set-var` 读写。包含 variables、messages、history 等。

```clojure
:on-activate (fn [inputs state ctx]
               (let [user-id (ctx/get-var ctx :user-id)]   ;; 读取共享变量
                 {:context (ctx/set-var ctx :result "done") ;; 写入供后续 step 使用
                  :events [...]}))
```

典型用途：step 间通信、对话历史、用户信息、全局配置。

#### 对比

| | State | Context |
|---|---|---|
| 归属 | 单个 step 私有 | 全 process 共享 |
| 可见性 | 仅自身 step | 所有 step |
| 更新方式 | 返回 `:state` | 返回 `:context` |
| 创建 | `:init` 函数 | `ctx/create` 或 opts 传入 |
| 快照位置 | `step-states` 中 | `context` 中 |

#### 快照中的体现

```clojure
{:step-states {:step-a {:state {:counter 3}        ;; step-a 的私有状态
                        :activation-count 3}
               :step-b {:state {:buffer []}        ;; step-b 的私有状态
                        :activation-count 1}}
 :context     {:variables {:user-id "u1"           ;; 共享变量
                           :result "done"}
               :messages [...]                     ;; 对话消息
               :history  [...]}}                   ;; 完整日志
```

### 事件

```clojure
{:name      :event-name
 :source    :step-id          ;; 自动填充
 :data      any
 :type      :public}          ;; :public | :internal | :error
```

### 绑定（Edge）

```clojure
{:event-name   :info-gathered
 :target-step  :generate-docs
 :target-input :product-info
 :transform    nil}            ;; 可选 (fn [data] -> data)
```

### Process 定义

```clojure
{:name           :process-name
 :steps          {:step-id step-spec ...}
 :bindings       [binding ...]
 :initial-events [{:name :start :data "..."}]
 :error-handler  nil}          ;; 可选 step-id
```

### Runtime State

```clojure
{:status          :running     ;; :idle | :running | :paused | :completed | :failed
 :process-spec     process-spec
 :steps-state     {:step-id {:state s :collected-inputs {} :activation-count 0} ...}
 :event-queue     [event ...]
 :context         context
 :paused-step     nil
 :pause-reason    nil
 :error           nil}
```

## Builder API

```clojure
(-> (process/builder :doc-generation)
    (process/add-step {:id :gather-info
                       :on-activate (fn [inputs state ctx] ...)
                       :required-inputs [:product-name]})
    (process/add-step {:id :generate-docs
                       :on-activate (fn [inputs state ctx] ...)
                       :required-inputs [:product-info]})
    (process/on-event :start :gather-info :product-name)
    (process/on-event :info-gathered :generate-docs :product-info)
    (process/on-event :doc-generated :publish :document)
    (process/set-initial-event :start "GlowBrew")
    (process/build))
```

## 执行流程

```
run-process(process-spec, opts)
├─ init-runtime: 初始化所有 step 状态, 填充事件队列
├─ event-loop:
│  ├─ dequeue event
│  ├─ route: event → bindings → deliveries
│  ├─ deliver: 投递到 step.collected-inputs
│  ├─ find-activated: 检查 required-inputs + can-activate?
│  ├─ execute: 逐个执行 on-activate
│  │  ├─ 正常: 产出事件入队, 更新 state, clear-inputs
│  │  ├─ 暂停: 设置 paused 状态, 返回
│  │  └─ 错误: 路由到 error-handler 或 failed
│  └─ loop
└─ 返回: {:status :context :steps-state :error}
```

## 与 Kernel 集成

Step 的 on-activate 中使用 Kernel：

```clojure
(fn [inputs state context]
  (let [kernel (ctx/get-kernel context)
        {:keys [value context]} (core/invoke-tool kernel :get-weather
                                  {:city (:city inputs)} context)]
    {:events [{:name :weather-fetched :data value}]
     :state  state
     :context context}))
```

## 支持的模式

- **线性流**: A → B → C
- **Fan-out**: A → B, A → C（同一事件多个 binding）
- **Fan-in**: A → C, B → C（C 有多个 required-inputs）
- **循环**: A → B → A（通过不同事件名）
- **Human-in-the-loop**: step 返回 {:pause ...}, 外部调用 resume

## 静止点回调（on-quiescent）

当所有并发执行的 step 全部完成或 process 暂停时，runtime 会触发 `on-quiescent` 回调。
这是保存 process 状态的安全时机——所有 step 状态一致，无进行中的执行。

### 触发条件

两种触发场景，通过 `:reason` 字段区分：

1. **`:quiescent`** — 并发 step 全部执行完毕
   - `active-count` 降为 0（无 step 正在执行 on-activate）
   - `in-flight` > 0（还有待处理的事件，process 未结束）
   - `status` = `:running`

2. **`:paused`** — step 返回 `{:pause ...}`
   - process 状态转为 `:paused`
   - snapshot 额外包含 `:paused-step` 和 `:pause-reason`

### Snapshot 格式

```clojure
;; reason = :quiescent
{:process-name  :my-process
 :reason        :quiescent
 :status        :running
 :step-states   {step-id {:state s :activation-count n} ...}
 :context       context-map
 :created-at    timestamp}

;; reason = :paused
{:process-name  :my-process
 :reason        :paused
 :status        :paused
 :paused-step   :step-id
 :pause-reason  "等待审批"
 :step-states   {step-id {:state s :activation-count n} ...}
 :context       context-map
 :created-at    timestamp}
```

### 使用

```clojure
(runtime/run-process process-spec
  {:on-quiescent
   (fn [snapshot]
     (case (:reason snapshot)
       :quiescent (println "静止点，可安全保存")
       :paused    (println "已暂停:" (:pause-reason snapshot)))
     (mem/snap-put store
       {:thread-id "session-1"}
       {:state snapshot}
       {:description (name (:reason snapshot))}))})
```

### 触发时机示例

```
线性 A → B → C:
  A 完成 → on-quiescent ✓ (reason: :quiescent)
  B 完成 → on-quiescent ✓ (reason: :quiescent)
  C 完成 → 不触发（process 即将结束）

Fan-out A → [B, C] → D:
  A 完成 → on-quiescent ✓ (reason: :quiescent)
  B 完成, C 未完成 → 不触发（active-count > 0）
  C 完成 → on-quiescent ✓ (reason: :quiescent)
  D 完成 → 不触发（process 即将结束）

暂停场景 A → B(pause):
  A 完成 → on-quiescent ✓ (reason: :quiescent)
  B 暂停 → on-quiescent ✓ (reason: :paused, paused-step: :B)
```

### 恢复

从 on-quiescent 保存的快照恢复：

```clojure
(let [saved (mem/snap-get store {:thread-id "session-1"})
      snapshot (get-in saved [:snapshot :state])]
  (runtime/run-process process-spec
    {:step-states (:step-states snapshot)
     :context     (:context snapshot)}))
```

注意：恢复时需要提供正确的 initial-events 来驱动后续步骤。
