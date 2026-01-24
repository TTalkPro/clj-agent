# clj-agent-memory

clj-agent 记忆系统模块，提供 Store、SnapshotStore 和 Memory 组件的统一实现。

## 架构概览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            clj-agent-core (协议定义)                         │
│                      IStore  │  ISnapshotStore                               │
└─────────────────────────────────────────────────────────────────────────────┘
                                    ▲
                                    │ 实现
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
│   │  UnifiedSnapshotStore (context-store + persistent-store)         │      │
│   └──────────────────────────────────────────────────────────────────┘      │
│                                    ▲                                         │
│   Layer 3: Memory 组件 ────────────┘                                         │
│   ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐               │
│   │ Buffer     │ │ Semantic   │ │ Episodic   │ │ Procedural │               │
│   │ (短期记忆) │ │ (事实)     │ │ (经验)     │ │ (规则)     │               │
│   └────────────┘ └────────────┘ └────────────┘ └────────────┘               │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 设计原则

1. **依赖倒置** - 协议定义在 clj-agent-core，实现在本模块
2. **可插拔后端** - Store 协议支持多种实现（内存、SQLite、Redis、PostgreSQL）
3. **组件复用** - SnapshotStore 和 Memory 组件都基于 Store 协议
4. **双 Store 架构** - 支持热/冷数据分离，可降级为单 Store

## 快速开始

```clojure
(require '[im.ttalk.memory.api :as mem])
(require '[im.ttalk.graph.state.protocol :as proto])

;; 创建存储
(def store (mem/create-in-memory-store))

;; 创建快照存储
(def snapshot-store (mem/create-snapshot-store store))

;; 保存快照
(proto/snap-put snapshot-store
              {:thread-id "thread-1"}
              {:state {:messages []}}
              {:step 1 :node "start"})

;; 获取快照
(proto/snap-get-tuple snapshot-store {:thread-id "thread-1"})
```

## Store 后端

### InMemoryStore（内存）

快速、非持久化，适用于开发测试。

```clojure
(def store (mem/create-in-memory-store))

;; 基本操作
(proto/kv-put store "namespace" "key" {:data "value"})
(proto/kv-get store "namespace" "key")
(proto/kv-delete store "namespace" "key")

;; 批量操作
(proto/kv-put-batch store "namespace" [{:key "k1" :value "v1"}
                                        {:key "k2" :value "v2"}])
(proto/kv-get-batch store "namespace" ["k1" "k2"])

;; 查询
(proto/kv-list-keys store "namespace" {:prefix "user:" :limit 100})
(proto/kv-count store "namespace" {:prefix "user:"})
```

### SQLiteStore（持久化）

基于 SQLite 的持久化存储，适用于单机部署。

```clojure
(def store (mem/create-sqlite-store "data.db"))

;; 使用方式与 InMemoryStore 相同
(proto/kv-put store "users" "user-123" {:name "Alice" :lang "zh"})
```

### RedisStore（分布式）

基于 Redis 的分布式存储。

```clojure
(def store (mem/create-redis-store {:host "localhost" :port 6379}))
```

### PostgresStore（生产）

基于 PostgreSQL 的生产级存储。

```clojure
(def store (mem/create-postgres-store {:jdbc-url "jdbc:postgresql://localhost/mydb"
                                        :username "user"
                                        :password "pass"}))
```

## SnapshotStore

基于 Store 协议的统一快照管理器，支持 Graph 状态持久化和时间旅行。

### 单 Store 模式

```clojure
;; 使用内存存储
(def cp (mem/create-snapshot-store (mem/create-in-memory-store)))

;; 使用 SQLite 存储
(def cp (mem/create-snapshot-store (mem/create-sqlite-store "snapshots.db")))
```

### 双 Store 模式

支持热/冷数据分离：
- **context-store**: 内存存储，用于当前会话（快速）
- **persistent-store**: 持久化存储，用于历史归档

```clojure
(def cp (mem/create-snapshot-store
          (mem/create-in-memory-store)           ; 热数据
          :persistent-store (mem/create-sqlite-store "archive.db")))  ; 冷数据

;; 检查是否为双 Store 模式
(mem/dual-store? cp)  ; => true
```

### 核心操作

```clojure
;; 保存快照
(proto/snap-put cp
              {:thread-id "t1"}
              {:state {:messages [{:role "user" :content "你好"}]}}
              {:step 1 :node "chat"})

;; 获取最新快照
(proto/snap-get-tuple cp {:thread-id "t1"})
;; => {:snapshot {:state ...}
;;     :metadata {:step 1 :node "chat" :created-at ...}
;;     :parent-config {:thread-id "t1" :snapshot-id "..."}
;;     :pending-writes [...]}

;; 获取指定快照
(proto/snap-get-tuple cp {:thread-id "t1" :snapshot-id "snap-123"})

;; 列出快照
(proto/snap-list cp {:thread-id "t1"} {:limit 10})

;; 获取执行历史
(proto/snap-get-history cp "t1")
;; => [{:id "snap-1" :step 1 :node "start" :created-at ...}
;;     {:id "snap-2" :step 2 :node "process" :created-at ...}]

;; 恢复到指定步骤
(proto/snap-restore-to-step cp "t1" 2)
```

### 分支管理

```clojure
;; 从快照创建分支
(proto/snap-create-branch cp "t1" "snap-123" "experiment")
;; => {:branch-id "t1-experiment" :snapshot-id "new-snap-id"}

;; 列出分支
(proto/snap-list-branches cp "t1")
```

### 归档功能（双 Store 模式）

```clojure
;; 归档线程到 persistent-store
(mem/archive-thread! cp "thread-1")
;; => {:archived-count 5 :thread-id "thread-1"}

;; 列出已归档线程
(mem/list-archived-threads cp)
;; => [{:thread-id "thread-1" :snapshot-count 5 :archived-at ...}]

;; 从归档加载线程
(mem/load-archived-thread! cp "thread-1")
;; => {:loaded-count 5 :thread-id "thread-1"}
```

### 清理操作

```clojure
;; 删除线程所有快照
(proto/snap-delete-thread cp "t1")

;; 清理旧快照（保留最新 10 个）
(proto/snap-prune cp "t1" {:keep-count 10})

;; 清除所有数据
(proto/snap-clear-all cp)
```

## Memory 组件

### ConversationBuffer（短期记忆）

管理当前对话消息。

```clojure
(def buffer (mem/create-conversation-buffer))

;; 添加消息
(mem/add-message buffer {:role "user" :content "你好"})
(mem/add-message buffer {:role "assistant" :content "你好！有什么可以帮你的？"})

;; 便捷方法
(mem/add-user-message buffer "你好")
(mem/add-assistant-message buffer "你好！")
(mem/add-system-message buffer "你是一个助手")
(mem/add-tool-message buffer "tool-call-id" "执行结果")

;; 获取消息
(mem/get-messages buffer)
(mem/get-messages-window buffer 10)  ; 最近 10 条
(mem/get-messages-by-tokens buffer 4000)  ; 按 token 限制

;; 统计
(mem/count-messages buffer)
(mem/count-tokens buffer)

;; 清空
(mem/clear-messages buffer)
```

### SemanticMemory（语义记忆）

存储事实和知识。

```clojure
(def semantic (mem/create-semantic-memory store))

;; 存储事实
(mem/store-fact semantic "user-123"
                {:type :preference
                 :content "用户偏好中文"})

;; 查询事实
(mem/query-facts semantic "user-123" "语言偏好" {:top-k 5})

;; 管理 Profile
(mem/set-profile semantic "user-123" :preferences {:lang "zh" :theme "dark"})
(mem/get-profile semantic "user-123" :preferences)
(mem/update-profile semantic "user-123" :preferences {:theme "light"})
```

### EpisodicMemory（情景记忆）

存储成功经验和案例。

```clojure
(def episodic (mem/create-episodic-memory store))

;; 存储情景
(mem/store-episode episodic
                   {:situation "用户询问如何重构代码"
                    :action "分步骤解释重构方法"
                    :outcome :success
                    :reasoning "降低复杂度，用户易于理解"})

;; 查询相似情景
(mem/query-similar episodic "代码重构" {:top-k 3 :outcome-filter :success})

;; 获取成功案例
(mem/get-successful-episodes episodic {:limit 10})
```

### ProceduralMemory（程序记忆）

存储行为规则和系统提示词。

```clojure
(def procedural (mem/create-procedural-memory store))

;; 系统提示词
(mem/set-system-prompt procedural "user-123" "你是一个友好的助手")
(mem/get-system-prompt procedural "user-123")

;; 添加规则
(mem/add-rule procedural "user-123"
              {:condition "用户说中文"
               :action "用中文回复"
               :priority 5})

;; 获取激活的规则
(mem/get-active-rules procedural "user-123" context)

;; 从反馈学习
(mem/update-from-feedback procedural "user-123"
                          {:type :positive
                           :context {:query "..."}
                           :suggestion "继续使用这种方式"})
```

## 检索策略

```clojure
;; 最近消息策略
(def strategy (mem/create-recent-strategy 10))

;; 滑动窗口策略
(def strategy (mem/create-window-strategy 20 :keep-system true))

;; Token 限制策略
(def strategy (mem/create-token-limit-strategy 4000))

;; 语义搜索策略
(def strategy (mem/create-semantic-strategy :top-k 5))

;; 混合策略（推荐）
(def strategy (mem/create-hybrid-strategy
                {:recent-n 5
                 :semantic-top-k 3
                 :include-episodic true}))

;; 执行检索
(mem/retrieve strategy
              {:buffer buffer :semantic semantic :episodic episodic}
              "用户查询"
              {:namespace "user-123"})
;; => {:messages [...] :facts [...] :episodes [...] :rules [...]}
```

## 便捷函数

### create-memory-system

一次性创建完整的记忆系统。

```clojure
;; 单 Store 模式
(def system (mem/create-memory-system))
(def system (mem/create-memory-system :store-type :sqlite
                                       :store-opts {:db-path "data.db"}))

;; 双 Store 模式
(def system (mem/create-memory-system
              :store-type :memory
              :archive-store-type :sqlite
              :archive-store-opts {:db-path "archive.db"}))

;; 返回
;; {:store ...          ; Store 实例
;;  :snapshot-store ... ; SnapshotStore 实例
;;  :vector-store ...   ; VectorStore 实例
;;  :embedder ...}      ; Embedder 实例
```

## 类型检查

```clojure
(mem/store? x)              ; 是否实现 IStore
(mem/snapshot-store? x)     ; 是否实现 ISnapshotStore
(mem/conversation-buffer? x)
(mem/semantic-memory? x)
(mem/episodic-memory? x)
(mem/procedural-memory? x)
(mem/vector-store? x)
```

## 文件结构

```
src/im/ttalk/memory/
├── api.clj                      # 统一 API 入口
├── protocol.clj                 # Memory 协议定义
├── store/
│   ├── in_memory.clj            # 内存存储
│   ├── sqlite.clj               # SQLite 存储
│   ├── redis.clj                # Redis 存储
│   ├── postgresql.clj           # PostgreSQL 存储
│   ├── vector_memory.clj        # 向量存储
│   └── embedding.clj            # 嵌入生成器
├── snapshot/
│   ├── unified.clj              # 统一 SnapshotStore（推荐）
│   ├── memory_saver.clj         # 内存 Saver（废弃）
│   └── sqlite_saver.clj         # SQLite Saver（废弃）
├── short_term/
│   └── buffer.clj               # 对话缓冲区
├── long_term/
│   ├── semantic.clj             # 语义记忆
│   ├── episodic.clj             # 情景记忆
│   └── procedural.clj           # 程序记忆
└── retrieval/
    └── strategies.clj           # 检索策略
```

## 依赖

```clojure
;; deps.edn
{:deps {im.ttalk/clj-agent-core {:local/root "../clj-agent-core"}
        org.clojure/clojure {:mvn/version "1.11.4"}
        com.github.seancorfield/next.jdbc {:mvn/version "1.3.894"}
        org.xerial/sqlite-jdbc {:mvn/version "3.43.0.0"}
        com.taoensso/carmine {:mvn/version "3.3.2"}
        cheshire/cheshire {:mvn/version "5.12.0"}}}
```

## 版本历史

- **2.5.0** - 实现双 Store 架构的 UnifiedSnapshotStore，简化 API
- **2.4.0** - 将 IStore/ISnapshotStore 协议移到 clj-agent-core，删除 IMemoryManager
- **2.3.0** - 重构目录结构
- **2.0.0** - 初始版本
