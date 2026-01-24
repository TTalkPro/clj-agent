# Process Framework 并行化设计

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
