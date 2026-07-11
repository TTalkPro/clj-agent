# Process Framework 并行化设计

> **状态：✅ V2 已落地（2026-07-11）**——`process/parallel.clj`，方案 B（core.async），
> 与 V1 并存（同一 spec 两个引擎都能跑，event/step/builder 纯函数层直接复用）。
> core.async 依赖只进 clj-agent-process 模块。13 个行为测试
> （`parallel_test.clj`）覆盖 fan-out 真并行证明 / 外部事件 / 单步与全局超时 /
> stop / pause-resume / error-handler / max-events。实施与设计的偏差见文末
> [实施笔记](#实施笔记2026-07-11)。

## V1 回顾

V1 采用纯函数式同步循环，event-queue 是普通 vector，`execute-activated-steps` 中 reduce 顺序执行。Fan-out 场景下多个 step 无法并行。

## 方案对比

### 方案 B: core.async Channel

#### 架构

```
                    ┌──────────────┐
                    │  event-chan   │  ← buffered channel 替代 vector queue
                    └──────┬───────┘
                           │ <! 取事件
                    ┌──────▼───────┐
                    │   router     │  ← go-loop: 路由 + 投递
                    └──────┬───────┘
                           │ 投递到各 step 的 input-chan
              ┌────────────┼────────────┐
              ▼            ▼            ▼
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │ step-a   │ │ step-b   │ │ step-c   │  ← 各自 go-loop
        │ input-ch │ │ input-ch │ │ input-ch │
        └────┬─────┘ └────┬─────┘ └────┬─────┘
             │             │             │
             └─────────────┼─────────────┘
                           ▼
                    ┌──────────────┐
                    │  event-chan   │  ← >! 产出事件回流
                    └──────────────┘
```

#### 利

- 真正并行：Fan-out 的多个 step 在不同 go-block 中并发执行
- 背压控制：Channel buffer 天然限流
- 与 Erlang 模型对齐：接近 beamai 的 process 消息传递模型
- 非阻塞 I/O 友好：Step 内的 LLM 调用可用 async/thread
- 解耦：Step 间完全通过 channel 通信

#### 弊

- 引入 core.async 依赖
- Context 共享需要 merge 策略
- 完成判定复杂（需 in-flight counter）
- 调试相对困难
- 确定性丧失

---

### 方案 C: Atom + Future

#### 架构

```
┌──────────────────────────────────────────────────────────┐
│  runtime-atom                                            │
│  {:event-queue [...], :steps-state {...}, :context ctx}  │
│                                                          │
│    coordinator loop                                      │
│         │                                                │
│    find-activated                                        │
│         │                                                │
│    pmap / futures  ← 并行执行已激活的 steps               │
│         │                                                │
│    collect results → swap! 合并回 atom                   │
└──────────────────────────────────────────────────────────┘
```

#### 利

- 改动小，V1 结构几乎不变
- API 兼容，同步返回
- 无新依赖
- 调试简单
- 暂停/恢复逻辑与 V1 一致

#### 弊

- Context 冲突：并行 step 基于快照执行，合并时可能覆盖
- 非真正异步：pmap 阻塞线程
- 粒度有限：只有同批 activated steps 并行
- 无背压

---

## 核心差异对比

| 维度 | 方案 B (Channel) | 方案 C (Atom) |
|------|------------------|---------------|
| 并行粒度 | Step 级别持续并行 | 批次级别并行 |
| 通信模型 | 消息传递 | 共享状态 |
| Context 处理 | 各 step 持有副本，最终合并 | 快照读 + CAS 写回 |
| API 变更 | 破坏性（异步返回） | 兼容（同步返回） |
| I/O 密集场景 | 优（go-block 不阻塞） | 差（线程阻塞） |
| 实现复杂度 | 高 | 低 |
| 适合场景 | 长时 I/O step、流式处理 | 短计算 step |

---

## 选定方案: 方案 B (Channel + core.async)

无需向后兼容，完全重写 runtime 层。

### 设计详情

#### Runtime State

```clojure
{:status       (atom :running)         ;; :running | :paused | :completed | :failed
 :process-spec  process-spec
 :event-chan   (async/chan 256)          ;; 事件总线
 :control-chan (async/chan)              ;; 控制信号（pause/resume/stop）
 :steps        {:step-id step-runtime ...}
 :context      (atom context)           ;; 共享 context（原子引用）
 :in-flight    (atom 0)                 ;; 进行中的事件计数
 :result-promise (promise)              ;; 最终结果
 :error        (atom nil)}
```

#### Step Runtime

```clojure
{:step-spec    step-spec
 :input-chan  (async/chan 64)            ;; 接收投递的输入
 :state       (atom {:collected-inputs {}
                     :state nil
                     :activation-count 0})
 :worker      go-block-handle}           ;; step 的 go-loop
```

#### 执行流程

1. `start-process` 启动 router go-loop + 各 step 的 worker go-loop
2. 初始事件放入 event-chan
3. Router 取事件 → 路由 → 投递到目标 step 的 input-chan
4. Step worker 收到输入 → collect → check-activation → execute (async/thread)
5. 执行结果的事件 → 放回 event-chan
6. in-flight 计数器：路由时 inc，step 执行完 dec
7. 当 in-flight = 0 且 event-chan 空 → completed

#### Context 合并策略

- 各 step 执行时拿 context 快照
- 执行完后 swap! context-atom 做 deep-merge
- variables: 后写入的覆盖（last-writer-wins）
- messages/history: 追加（不会冲突）

#### 暂停/恢复

- 暂停：step 返回 {:pause ...} → 发送 :pause 到 control-chan → router 停止消费
- 恢复：外部调用 resume → 发送 :resume 到 control-chan → router 恢复

#### 完成判定

使用 in-flight 引用计数：
- Router 路由事件后 inc（每个 delivery 对应一次 inc）
- Step 执行完毕 dec
- Router 发现 event-chan 空时，启动 idle 检测：等待 in-flight = 0 → completed

#### 超时保护

- 全局超时：start-process 接受 :timeout-ms 选项
- 单步超时：step-spec 可配置 :timeout-ms

#### API

```clojure
;; 启动 process（返回 result channel）
(start-process process-spec opts)
;; -> 返回 channel，完成时放入 {:status :context :error}

;; 同步便利函数
(run-process process-spec opts)
;; -> 阻塞等待完成，返回 result map

;; 恢复
(resume runtime data)
;; -> 返回 channel
```

#### 外部事件 API

```clojure
;; 异步启动（返回 ProcessHandle）
(start-process-async process-spec opts)
;; -> ProcessHandle，用于外部交互

;; 发送外部事件
(send-event handle :event-name data)
;; -> true/false

;; 同步发送（带超时）
(send-event! handle :event-name data timeout-ms)
;; -> true/false

;; 状态查询
(get-status handle)
;; -> :running | :paused | :completed | :failed | :stopped

;; 等待完成
(wait-for-completion handle)
(wait-for-completion handle timeout-ms)
;; -> result map

;; 停止
(stop-process handle)
;; -> true
```

#### 外部事件架构

```
                    ┌──────────────┐
                    │  event-chan   │  ← 内部事件
                    └──────┬───────┘
                           │
                    ┌──────▼───────┐
                    │   router     │  ← alts! 多路复用
                    └──────┬───────┘
                           │
              ┌────────────┼────────────┐
              │            │            │
        event-chan   external-chan  control-chan
              │            │            │
              └────────────┼────────────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
        [step-a]      [step-b]      [step-c]
```

外部事件通过 `external-chan` 注入，与内部事件统一路由到目标 step。

---

## 实施笔记（2026-07-11）

落地 `im.ttalk.agent.process.parallel`（同模块新 ns，V1 不动）。与上文设计稿的偏差与决策：

1. **API 收敛为单一 handle**：`start-process` 直接返回 ProcessHandle（不再区分
   「返回 result channel 的 start-process」与 `start-process-async` 两个入口）；
   终态结果放 handle 内的 promise-chan，`wait-for-completion` 可重复取。
   `send-event!`（带超时的阻塞版）未做——`send-event` 走 `put!` + in-flight 预记账已足够。
2. **完成判定**：单一 in-flight 计数（事件入队 inc、每个投递 inc、路由完 dec、
   worker 处理完 dec），子项先 inc 后父项 dec，计数不会假归零；无需「event-chan
   空 + idle 检测」。归零且 `:running` 且无暂停 step → `:completed`。
3. **新增 `:auto-complete?`（缺省 true）**：设计稿没回答「等外部事件的常驻
   process 会在事件流干时被误判完成」——置 false 后只能由 `:terminate` /
   `stop-process` / 全局超时结束。
4. **Context 合并比设计稿更保守**：不是整包 deep-merge，而是只合并**相对该 step
   执行前快照有变化**的 key（否则并行 step 会用旧快照值覆盖别人的并发写入）；
   key 删除不传播。messages/history 不特判——V2 的 context 是扁平 ToolContext。
5. **暂停语义**：暂停是尽力而为的屏障——router 停止消费新事件，但已在执行中的
   step 会跑完（结果照常落账）。多 step 可同时暂停（`pause-info` 查全量，
   resume 三参形式指定 step-id）。同一 `:paused` 结果只能 resume 一次
   （活运行时，区别于 V1 的纯数据快照可反复 resume）。
6. **单步超时不强杀线程**：超时按 `:error {:reason :step-timeout}` 处理进入
   error-handler/失败流程；on-activate 的线程后台跑完但返回值被丢弃——step 只
   拿快照执行、所有共享状态写入都在 worker 侧 apply，超时后天然写不进任何状态。
7. **不提供 on-quiescent**：并行执行没有确定性静止点，快照/Timeline 仍走 V1。
   `run-process` 同步入口与 V1 同构（阻塞到终态或暂停），保险丝 `:max-events` 保留。
8. **control-chan 只承担两件事**：暂停时唤醒 router（`:resume`）与终态关闭退出；
   pause 信号不走 control-chan（worker 直接置 status atom，router 每轮循环检查）。
