# clj-agent-memory

记忆与存储模块 - 多后端 Key-Value 存储、快照管理、长短期记忆

[English](#english) | 中文

## 架构概览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            clj-agent-memory                                  │
│                                                                              │
│   Layer 1: Store 后端                                                        │
│   ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐       │
│   │ InMemoryStore│ │ SQLiteStore  │ │ RedisStore   │ │PostgresStore │       │
│   └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘       │
│                                    ▲                                         │
│   Layer 2: SnapshotStore ──────────┘                                         │
│   ┌──────────────────────────────────────────────────────────────────┐      │
│   │  StoreBackedSnapshotStore (基于 Store 实现)                       │      │
│   │  SnapshotManager (时间旅行、分支管理)                             │      │
│   └──────────────────────────────────────────────────────────────────┘      │
│                                    ▲                                         │
│   Layer 3: Memory 组件 ────────────┘                                         │
│   ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐               │
│   │ Buffer     │ │ Semantic   │ │ Episodic   │ │ Procedural │               │
│   │ (短期记忆) │ │ (事实)     │ │ (经验)     │ │ (规则)     │               │
│   └────────────┘ └────────────┘ └────────────┘ └────────────┘               │
│                                    ▲                                         │
│   Layer 4: AgentMemory ────────────┘                                         │
│   ┌──────────────────────────────────────────────────────────────────┐      │
│   │  统一封装: save/load + 时间旅行 + 知识库 + 消息管理              │      │
│   └──────────────────────────────────────────────────────────────────┘      │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 设计原则

1. **独立模块** - 无内部模块依赖，可独立使用
2. **可插拔后端** - 统一协议支持多种 Store 实现
3. **分层复用** - SnapshotStore 和 Memory 组件都基于 Store 协议
4. **双 Store 架构** - 支持热/冷数据分离（context-store + persistent-store）

## 依赖

```clojure
;; deps.edn
{:deps {im.ttalk/clj-agent-memory {:local/root "../clj-agent-memory"}}}
```

> 本模块无内部模块依赖，可独立使用。

外部依赖：
- cheshire/cheshire 5.12.0
- com.github.seancorfield/next.jdbc 1.3.939
- org.xerial/sqlite-jdbc 3.45.1.0
- org.postgresql/postgresql 42.7.3
- com.taoensso/carmine 3.2.0

## 命名空间

| 命名空间 | 说明 |
|---------|------|
| `im.ttalk.agent.memory.api` | 统一 API 入口（re-export 所有公开函数） |
| `im.ttalk.agent.memory.protocol` | 核心协议定义 |
| `im.ttalk.agent.memory.store.in-memory` | 内存存储 |
| `im.ttalk.agent.memory.store.sqlite` | SQLite 存储 |
| `im.ttalk.agent.memory.store.postgresql` | PostgreSQL 存储 |
| `im.ttalk.agent.memory.store.redis` | Redis 存储 |
| `im.ttalk.agent.memory.store.embedding` | Embedding 生成 |
| `im.ttalk.agent.memory.store.vector-memory` | 向量存储 |
| `im.ttalk.agent.memory.timeline.core` | 时间线管理（通用底层） |
| `im.ttalk.agent.memory.snapshot.manager` | SnapshotManager（Process 框架） |
| `im.ttalk.agent.memory.checkpoint.manager` | CheckpointManager（Graph 框架） |
| `im.ttalk.agent.memory.agent-memory` | AgentMemory 封装 |
| `im.ttalk.agent.memory.short-term.buffer` | 对话缓冲 |
| `im.ttalk.agent.memory.long-term.semantic` | 语义记忆 |
| `im.ttalk.agent.memory.long-term.episodic` | 情景记忆 |
| `im.ttalk.agent.memory.long-term.procedural` | 程序记忆 |
| `im.ttalk.agent.memory.retrieval.strategies` | 检索策略 |

## 快速开始

```clojure
(require '[im.ttalk.agent.memory.api :as mem])

;; 创建存储
(def store (mem/create-in-memory-store))

;; Key-Value 操作
(mem/kv-put store "user-123" "preferences" {:lang "zh"})
(mem/kv-get store "user-123" "preferences")
;; => {:lang "zh"}

;; 创建快照存储
(def ss (mem/create-memory-snapshot-store))

;; 保存快照
(mem/snap-put ss {:thread-id "t1"} {:state {:messages []}} {:step 1})

;; 获取最新快照
(mem/snap-get ss {:thread-id "t1"})
```

## 与 Agent 集成使用

### 保存和恢复 Agent 对话状态

```clojure
(require '[im.ttalk.agent.simpleagent.kernel-agent :as ka])
(require '[im.ttalk.agent.memory.store.in-memory :as mem-store])
(require '[im.ttalk.agent.memory.snapshot.manager :as snap-mgr])
(require '[im.ttalk.agent.memory.protocol :as mem-proto])
(require '[im.ttalk.agent.core.kernel.context :as ctx])

;; 1. 创建存储和快照管理器
(def store (mem-store/create-in-memory-store))
(def snap-manager (snap-mgr/create-snapshot-manager store))
(def session-id "chat-session-001")

;; 2. 创建 Agent 并对话
(def agent (ka/create-agent
             {:provider provider
              :model "gpt-4"
              :system-prompt "你是助手。"}))

(ka/chat agent "我叫张三，在北京工作")
(ka/chat agent "我喜欢编程")

;; 3. 保存当前状态
(mem-proto/snap-put snap-manager
                    {:thread-id session-id}
                    {:context (ka/get-context agent)}
                    {:reason :user-save})

;; 4. 后续恢复（如重启应用后）
(let [loaded (mem-proto/snap-get snap-manager {:thread-id session-id})
      new-agent (ka/create-agent {:provider provider :model "gpt-4"})]
  ;; 恢复 context
  (reset! (:context-atom new-agent) (:context (:snapshot loaded)))
  ;; 继续对话
  (ka/chat new-agent "我叫什么名字？"))
;; => Agent 记得用户叫张三
```

### 使用 AgentMemory 统一管理

```clojure
;; AgentMemory 提供一站式记忆管理
(def am (mem/create-agent-memory
          {:context-store (mem/create-in-memory-store)
           :persistent-store (mem/create-sqlite-store "data.db")}))

;; 状态管理
(mem/save-state am {:messages [...] :variables {...}})
(mem/load-state am)

;; 时间旅行
(mem/go-back am)      ;; 回退
(mem/go-forward am)   ;; 前进
(mem/goto am 3)       ;; 跳转到版本 3

;; 知识库
(mem/remember am {:type :fact :content "用户偏好中文"})
(mem/recall am "用户偏好")

;; 消息管理
(mem/add-message-to-memory am {:role "user" :content "你好"})
(mem/get-messages-from-memory am)
```

## API 参考

### Store 创建

```clojure
(def store (mem/create-in-memory-store))                  ;; 内存（开发/测试）
(def store (mem/create-sqlite-store "agent.db"))          ;; SQLite
(def store (mem/create-postgres-store conn-opts))         ;; PostgreSQL
(def store (mem/create-redis-store {:host "localhost"}))   ;; Redis
```

### Key-Value 操作

```clojure
;; 单条操作
(mem/kv-put store "key" "namespace" {:data "value"})
(mem/kv-get store "key" "namespace")           ;; => {:data "value"}
(mem/kv-exists? store "key" "namespace")       ;; => true
(mem/kv-delete store "key" "namespace")

;; 批量操作
(mem/kv-put-batch store [["k1" "ns" v1] ["k2" "ns" v2]])
(mem/kv-get-batch store ["k1" "k2"] "ns")

;; 查询
(mem/kv-list-keys store)
(mem/kv-list-values store)
(mem/kv-count store)

;; 生命周期
(mem/store-init! store)
(mem/store-close! store)
(mem/store-healthy? store)
```

### SnapshotStore

```clojure
;; 创建
(def ss (mem/create-memory-snapshot-store))               ;; 内存快照
(def ss (mem/create-sqlite-snapshot-store "snap.db"))     ;; SQLite 快照
(def ss (mem/create-snapshot-store some-store))           ;; 基于任意 Store

;; 保存
(mem/snap-put ss {:thread-id "t1"} snapshot-data metadata)

;; 获取
(mem/snap-get ss {:thread-id "t1"})
(mem/snap-list ss)

;; 版本管理
(mem/snap-get-next-version ss {:thread-id "t1"})
(mem/snap-restore-to-step ss {:thread-id "t1"} 3)
(mem/snap-get-history ss {:thread-id "t1"})

;; 分支
(mem/snap-create-branch ss {:thread-id "t1"} "branch-name")
(mem/snap-list-branches ss {:thread-id "t1"})

;; 清理
(mem/snap-prune ss {:thread-id "t1"} {:keep-last 5})
(mem/snap-delete-thread ss {:thread-id "t1"})
(mem/snap-clear-all ss)
```

### SnapshotManager（时间旅行）

```clojure
(def sm (mem/create-snapshot-manager ss {:thread-id "t1"}))

(mem/go-back! sm)                     ;; 回退
(mem/go-forward! sm)                  ;; 前进
(mem/goto! sm 3)                      ;; 跳转
(mem/get-lineage sm)                  ;; 历史链
(mem/switch-branch! sm "branch")      ;; 切换分支
```

### ConversationBuffer（短期记忆）

```clojure
(def buffer (mem/create-conversation-buffer))

;; 添加消息
(mem/add-message buffer {:role "user" :content "你好"})
(mem/add-user-message buffer "你好")
(mem/add-assistant-message buffer "你好！")
(mem/add-system-message buffer "你是助手")
(mem/add-tool-message buffer "tool-call-id" "结果")

;; 查询
(mem/get-messages buffer)
(mem/get-messages-window buffer 10)        ;; 最近 N 条
(mem/get-messages-by-tokens buffer 4000)   ;; 按 token 限制
(mem/count-messages buffer)
(mem/count-tokens buffer)

;; 清空
(mem/clear-messages buffer)
```

### 长期记忆

```clojure
;; 语义记忆（事实/知识）
(def sem (mem/create-semantic-memory store))
(mem/store-fact sem {:key "k" :value "v" :category "cat"})
(mem/get-fact sem "k")
(mem/query-facts sem {:category "cat"})
(mem/set-profile sem "user-id" {:lang "zh"})
(mem/get-profile sem "user-id")

;; 情景记忆（事件/经历）
(def epi (mem/create-episodic-memory store))
(mem/store-episode epi {:action "search" :query "weather" :outcome :success})
(mem/get-recent-episodes epi 5)
(mem/get-successful-episodes epi)
(mem/query-similar epi "搜索天气")

;; 程序记忆（规则/技能）
(def proc (mem/create-procedural-memory store))
(mem/set-system-prompt proc "你是助手")
(mem/get-system-prompt proc)
(mem/add-rule proc (mem/create-rule {:id "r1" :content "用中文回答"}))
(mem/get-active-rules proc)
(mem/update-from-feedback proc {:type :positive :suggestion "继续这样"})
```

### AgentMemory（统一封装）

```clojure
(def am (mem/create-agent-memory
          {:context-store in-mem-store
           :persistent-store sqlite-store}))

;; 状态管理
(mem/save-state am {:context {...}})
(mem/load-state am)

;; 时间旅行
(mem/go-back am)
(mem/go-forward am)
(mem/goto am 3)
(mem/list-history am)

;; 分支
(mem/create-branch am "experiment")
(mem/switch-branch am "experiment")
(mem/list-branches am)

;; 知识库
(mem/remember am {:type :fact :content "北京是首都"})
(mem/recall am "首都")
(mem/recall-by-type am :fact)
(mem/search-knowledge am "首都" {:limit 5})
(mem/forget am "fact-id")

;; 消息管理
(mem/add-message-to-memory am {:role "user" :content "你好"})
(mem/get-messages-from-memory am)
(mem/clear-messages-from-memory am)

;; 归档
(mem/archive-session! am)
(mem/load-archived am "session-id")
(mem/list-archived am)
```

### 检索策略

```clojure
;; 最近消息
(def s (mem/create-recent-strategy 10))

;; 滑动窗口
(def s (mem/create-window-strategy 20))

;; Token 限制
(def s (mem/create-token-limit-strategy 4000))

;; 语义搜索
(def s (mem/create-semantic-strategy))

;; 混合策略
(def s (mem/create-hybrid-strategy {:recent-n 5 :semantic-top-k 3}))

;; 执行检索
(mem/retrieve s context query opts)
```

### 便捷创建

```clojure
;; 一键创建完整记忆系统
(def system (mem/create-memory-system))
(def system (mem/create-memory-system
              :store-type :sqlite
              :store-opts {:db-path "data.db"}))
;; => {:store ... :snapshot-store ... :vector-store ... :embedder ...}

;; 双 Store 模式（热+冷）
(def system (mem/create-memory-system
              :store-type :memory
              :archive-store-type :sqlite
              :archive-store-opts {:db-path "archive.db"}))
```

### 类型检查

```clojure
(mem/store? x)
(mem/snapshot-store? x)
(mem/conversation-buffer? x)
(mem/semantic-memory? x)
(mem/episodic-memory? x)
(mem/procedural-memory? x)
(mem/vector-store? x)
(mem/embedding? x)
(mem/snapshot-manager? x)
(mem/agent-memory? x)
```

---

<a name="english"></a>

## English

### Overview

`clj-agent-memory` is a standalone storage module (no internal module dependencies) providing:

- **IKeyValueStore**: Unified KV protocol (InMemory / SQLite / PostgreSQL / Redis)
- **ISnapshotStore**: State snapshot with versioning, branching, time-travel
- **ConversationBuffer**: Short-term conversation buffering
- **Long-term Memory**: Semantic (facts), Episodic (experiences), Procedural (rules)
- **Vector Storage**: Embedding generation and vector search
- **Retrieval Strategies**: Recent, Window, Token-limit, Semantic, Hybrid
- **AgentMemory**: Unified wrapper combining all memory components

### Architecture

```
Layer 1 (Store) → Layer 2 (Snapshot) → Layer 3 (Memory) → Layer 4 (AgentMemory)
```

### Key APIs

All accessed through `im.ttalk.agent.memory.api`:

- Store: `create-in-memory-store`, `create-sqlite-store`, `kv-put`, `kv-get`
- Snapshot: `create-memory-snapshot-store`, `snap-put`, `snap-get`, `snap-restore-to-step`
- Buffer: `create-conversation-buffer`, `add-message`, `get-messages`
- Long-term: `create-semantic-memory`, `store-fact`, `create-episodic-memory`, `store-episode`
- AgentMemory: `create-agent-memory`, `save-state`, `load-state`, `remember`, `recall`
- System: `create-memory-system` - One-call creation of complete memory system
