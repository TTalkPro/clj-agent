# Memory、State 和 Snapshot 架构说明

本文档详细说明 clj-agent 记忆系统中 Memory、State 和 Snapshot 三个核心概念的关系。

## 概念关系图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              Agent 执行                                  │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
            ┌───────────┐   ┌───────────┐   ┌───────────────┐
            │   State   │   │  Memory   │   │   Snapshot    │
            │ (运行状态) │   │ (记忆系统) │   │  (状态快照)   │
            └───────────┘   └───────────┘   └───────────────┘
                 │               │                  │
                 │    ┌─────────┴─────────┐        │
                 │    ▼                   ▼        │
                 │ ┌────────┐      ┌──────────┐   │
                 │ │短期记忆│      │ 长期记忆  │   │
                 │ │(Buffer)│      │Semantic/ │   │
                 │ │        │      │Episodic/ │   │
                 │ │        │      │Procedural│   │
                 │ └────────┘      └──────────┘   │
                 │    │                 │          │
                 └────┴────────┬────────┴──────────┘
                               ▼
                    ┌─────────────────────┐
                    │       IStore        │
                    │   (统一持久化层)     │
                    └─────────────────────┘
```

## 1. State（运行状态）

### 定义

State 是 Graph 执行过程中的当前数据状态，是节点之间传递信息的载体。

### 数据结构

```clojure
;; State 示例
{:messages [{:role "user" :content "你好"}
            {:role "assistant" :content "你好！"}]
 :current-step 3
 :tool-results {:search-result "..."}
 :custom-data {:user-intent "greeting"}}
```

### 特点

| 特性 | 说明 |
|------|------|
| **瞬时性** | 只存在于执行过程中，执行结束后消失 |
| **可变性** | 每个节点都可以读取和修改 State |
| **结构化** | 由 StateSchema 定义，有明确的类型约束 |
| **内存驻留** | 不自动持久化，需要通过 Snapshot 保存 |

### 生命周期

```
Graph 开始 → 初始化 State → 节点1修改 → 节点2修改 → ... → Graph 结束 → State 消失
```

---

## 2. Snapshot（状态快照）

### 定义

Snapshot 是 State 在某个时刻的持久化快照，用于保存执行进度、支持恢复和分支。

### 数据结构

```clojure
;; Snapshot 保存的内容
{:snapshot
  {:state state-data           ;; 完整的 State 快照
   :channel-values {...}}      ;; Channel 值

 :metadata
  {:step 3                     ;; 执行步骤号
   :node "chat"                ;; 当前节点名
   :created-at 1705000000      ;; 创建时间戳
   :parent-id "snap-xxx"}      ;; 父快照 ID（形成链表）

 :parent-config                ;; 父快照配置
  {:thread-id "t1"
   :snapshot-id "snap-xxx"}

 :pending-writes [...]}        ;; 待处理的写入
```

### 核心概念

- **thread-id**: 线程/会话 ID，标识一个执行序列
- **snapshot-id**: 快照 ID，标识某个具体的状态快照
- **parent-id**: 父快照，形成快照链，支持历史追溯

### 用途

| 用途 | 说明 |
|------|------|
| **持久化** | 保存执行进度，进程重启后可恢复 |
| **时间旅行** | 恢复到任意历史快照继续执行 |
| **分支执行** | 从某个快照创建新分支，探索不同路径 |
| **Human-in-the-loop** | 暂停执行等待人工干预，之后继续 |
| **调试** | 重放执行过程，定位问题 |

### 与 State 的关系

```
              保存
State ──────────────────► Snapshot
  │                           │
  │                           │ 恢复
  │                           ▼
  └─────────────────────── State'
```

```clojure
;; 保存 State 到 Snapshot
(proto/snap-put snapshot-store
  {:thread-id "thread-1"}
  {:state current-state}
  {:step 1 :node "chat"})

;; 从 Snapshot 恢复 State
(let [tuple (proto/snap-get-tuple snapshot-store {:thread-id "thread-1"})]
  (get-in tuple [:snapshot :state]))
```

---

## 3. Memory（记忆系统）

### 定义

Memory 是 Agent 的认知记忆系统，存储跨会话的知识、经验和规则。

### 记忆类型

```
Memory
├── 短期记忆 (Short-term)
│   └── ConversationBuffer: 当前会话的消息历史
│
└── 长期记忆 (Long-term)
    ├── Semantic Memory:   语义记忆 - 事实、知识、用户偏好
    ├── Episodic Memory:   情景记忆 - 经验、案例、对话历史
    └── Procedural Memory: 程序记忆 - 规则、模式、系统提示词
```

### 数据结构

```clojure
;; 短期记忆 - 会话缓冲
{:messages
  [{:role "user" :content "帮我写代码" :timestamp 1705000000}
   {:role "assistant" :content "好的..." :timestamp 1705000001}]}

;; 语义记忆 - 事实/知识
{:id "fact-xxx"
 :type :preference
 :content "用户偏好使用 Clojure"
 :confidence 0.9
 :created-at 1705000000}

;; 情景记忆 - 经验/案例
{:id "ep-xxx"
 :situation "用户请求排序算法"
 :action "提供快速排序实现并解释"
 :outcome :success
 :reasoning "用户表示满意"
 :created-at 1705000000}

;; 程序记忆 - 规则
{:id "rule-xxx"
 :condition "用户使用中文"
 :action "使用中文回复"
 :priority 1
 :enabled true}
```

### 与 State/Snapshot 的区别

| 维度 | State | Snapshot | Memory |
|------|-------|----------|--------|
| **生命周期** | 单次执行 | 单个 Thread | 跨 Thread/会话 |
| **存储内容** | 执行时数据 | State 快照 | 知识/经验/规则 |
| **主要用途** | 节点间通信 | 恢复/分支 | 个性化/学习 |
| **数据粒度** | 完整状态 | 按步骤快照 | 按概念组织 |
| **更新频率** | 每个节点 | 重要步骤 | 会话结束时 |
| **持久化** | 否 | 是 | 是 |

---

## 4. 三者协作流程

### 完整执行流程

```
┌─────────────────────────────────────────────────────────────────┐
│ 1. 会话开始 - 初始化                                             │
├─────────────────────────────────────────────────────────────────┤
│  • 从 Memory 加载用户偏好（语义记忆）                            │
│  • 加载相关历史经验（情景记忆）                                   │
│  • 加载系统规则（程序记忆）                                       │
│  • 构建初始 State                                                │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ 2. Graph 执行 - 运行时                                           │
├─────────────────────────────────────────────────────────────────┤
│  • State 在节点间传递和更新                                       │
│  • 消息添加到短期记忆 (Buffer)                                    │
│  • 重要步骤保存 Snapshot                                          │
│  • 需要时可从 Snapshot 恢复                                       │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ 3. 会话结束 - 记忆形成                                           │
├─────────────────────────────────────────────────────────────────┤
│  • 从对话提取事实 → 存入语义记忆                                  │
│  • 成功案例 → 存入情景记忆                                        │
│  • 学到的模式 → 存入程序记忆                                      │
│  • 清理过期 Snapshot                                             │
└─────────────────────────────────────────────────────────────────┘
```

### 时序图

```
用户      Agent       State      Snapshot      Memory
 │          │           │            │           │
 │─请求────►│           │            │           │
 │          │──加载记忆─┼────────────┼──────────►│
 │          │◄─返回偏好─┼────────────┼───────────│
 │          │──初始化──►│            │           │
 │          │           │            │           │
 │          │◄─执行节点─│            │           │
 │          │──更新────►│            │           │
 │          │           │──保存─────►│           │
 │          │◄─执行节点─│            │           │
 │          │──更新────►│            │           │
 │          │           │──保存─────►│           │
 │          │           │            │           │
 │◄─响应────│           │            │           │
 │          │──提取事实─┼────────────┼──────────►│
 │          │──存储经验─┼────────────┼──────────►│
 │          │           │            │           │
```

---

## 5. 代码示例

### 完整使用示例

```clojure
(require '[im.ttalk.memory.api :as mem])
(require '[im.ttalk.memory.protocol :as proto])

;; ============================================
;; 创建记忆系统
;; ============================================

(def manager (mem/create-full-memory-system))

;; 或者手动配置
(def store (mem/create-in-memory-store))
(def snapshot-store (mem/create-memory-snapshot-store))
(def manager (mem/create-memory-manager store
               :snapshot-store snapshot-store))

;; ============================================
;; 1. 会话开始 - 加载记忆构建上下文
;; ============================================

;; 加载用户相关的长期记忆
(def context (mem/build-context manager "user-123" "帮我写代码"))
;; => {:messages [...]
;;     :context {:facts [...] :episodes [...] :system-prompt "..."}
;;     :rules [...]}

;; ============================================
;; 2. 执行过程 - 操作 State 和 Snapshot
;; ============================================

;; 添加消息到短期记忆
(mem/add-user-message manager "thread-1" "帮我写一个快速排序")
(mem/add-assistant-message manager "thread-1" "好的，这是 Clojure 实现...")

;; 获取当前消息（短期记忆）
(def messages (mem/get-messages* manager "thread-1"))

;; 保存 Snapshot（State 快照）
(mem/snapshot! manager "thread-1"
  {:messages messages
   :current-step 1
   :tool-results nil}
  :step 1
  :node "chat")

;; 继续执行...
(mem/add-user-message manager "thread-1" "能解释下原理吗？")
(mem/add-assistant-message manager "thread-1" "快速排序的核心思想...")

;; 保存另一个 Snapshot
(mem/snapshot! manager "thread-1"
  {:messages (mem/get-messages* manager "thread-1")
   :current-step 2}
  :step 2
  :node "explain")

;; 查看快照历史
(mem/get-snapshot-history manager "thread-1")
;; => [{:id "snap-1" :step 1 :node "chat" :created-at ...}
;;     {:id "snap-2" :step 2 :node "explain" :created-at ...}]

;; 恢复到之前的快照（时间旅行）
(def old-state (mem/restore-to-snapshot! manager "thread-1" "snap-1"))

;; ============================================
;; 3. 会话结束 - 存储长期记忆
;; ============================================

;; 存储学到的用户偏好（语义记忆）
(mem/store-fact* manager "user-123"
  {:type :preference
   :content "用户偏好 Clojure 语言"
   :confidence 0.9})

(mem/store-fact* manager "user-123"
  {:type :skill-level
   :content "用户熟悉函数式编程"
   :confidence 0.8})

;; 存储成功案例（情景记忆）
(mem/store-episode* manager "user-123"
  {:situation "用户请求排序算法实现"
   :action "提供快速排序 Clojure 实现并详细解释"
   :outcome :success
   :reasoning "用户表示理解并感谢"})

;; 添加响应规则（程序记忆）
(mem/add-rule* manager "user-123"
  {:condition "用户请求代码"
   :action "优先使用 Clojure 示例"
   :priority 1})

;; ============================================
;; 4. 下次会话 - 利用记忆
;; ============================================

;; 检索相关记忆
(mem/recall-all manager "排序算法"
  :namespace "user-123"
  :types [:semantic :episodic]
  :top-k 5)
;; => {:facts [{:type :preference :content "用户偏好 Clojure"}]
;;     :episodes [{:situation "用户请求排序算法..." :outcome :success}]
;;     ...}
```

---

## 6. 类比理解

可以用人类记忆系统来类比理解这三个概念：

| 概念 | 人类类比 | 说明 |
|------|---------|------|
| **State** | 工作记忆 | 当前正在处理的信息，容量有限，易丢失 |
| **Snapshot** | 笔记/书签 | 记录重要节点，方便回顾和恢复 |
| **短期记忆** | 对话记忆 | 记住刚才说了什么 |
| **语义记忆** | 知识/常识 | "这个用户喜欢中文" |
| **情景记忆** | 经验/回忆 | "上次这样做成功了" |
| **程序记忆** | 习惯/技能 | "遇到这种情况应该这样处理" |

---

## 7. 最佳实践

### Snapshot 使用建议

```clojure
;; 1. 在重要步骤保存 Snapshot
(mem/snapshot! manager thread-id state
  :step step-num
  :node node-name)

;; 2. 定期清理旧快照
(proto/snap-prune snapshot-store thread-id
  {:keep-count 10
   :keep-types #{:initial :final :error}})

;; 3. 使用分支探索不同路径
(proto/snap-create-branch snapshot-store thread-id snapshot-id "experiment-1")
```

### Memory 使用建议

```clojure
;; 1. 会话结束时提取并存储记忆
(mem/process-and-store manager thread-id
  :store-facts true
  :store-episodes true)

;; 2. 使用检索策略获取相关记忆
(mem/recall-all manager query
  :namespace user-id
  :types [:semantic :episodic :procedural]
  :top-k 5)

;; 3. 构建完整上下文
(mem/build-context manager thread-id query)
```

---

## 8. 术语对照

| clj-agent | LangChain | LangGraph | 说明 |
|-----------|-----------|-----------|------|
| IStore | BaseStore | - | 基础 KV 存储 |
| ISnapshotStore | - | BaseCheckpointSaver | 快照管理 |
| StoreBackedSnapshotStore | - | MemorySaver/SqliteSaver | 基于 Store 的快照（统一实现） |
| ConversationBuffer | ConversationBufferMemory | - | 会话缓冲 |
| SemanticMemory | - | - | 语义/知识记忆 |
| EpisodicMemory | - | - | 情景/经验记忆 |
| ProceduralMemory | - | - | 程序/规则记忆 |
| MemoryManager | - | - | 统一管理器 |
