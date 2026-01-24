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
└─────────────────────────────────────────────────────┘
```

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
 :steps          {:step-id step-def ...}
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
