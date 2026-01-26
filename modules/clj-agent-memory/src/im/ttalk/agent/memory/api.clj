(ns im.ttalk.agent.memory.api
  "Memory API - clj-agent 记忆系统统一入口

   参考 LangChain/LangGraph 设计，提供：
   - 基础存储（Store）
   - 快照管理（SnapshotStore）
   - 短期记忆（ConversationBuffer）
   - 长期记忆（Semantic/Episodic/Procedural）
   - 向量存储
   - 检索策略

   ========================================
   架构层次
   ========================================

   Layer 1 - Store (基础存储)
     IKeyValueStore: InMemoryStore | SQLiteStore | RedisStore | PostgresStore

   Layer 2 - SnapshotStore (状态快照)
     ISnapshotStore: StoreBackedSnapshotStore (基于 Store 实现)

   Layer 3 - Memory (记忆管理)
     ConversationBuffer + Semantic + Episodic + Procedural

   ========================================
   快速开始
   ========================================

   (require '[im.ttalk.agent.memory.api :as mem])

   ;; 创建存储
   (def store (mem/create-in-memory-store))
   (def ss (mem/create-memory-snapshot-store))

   ;; 创建会话缓冲
   (def buffer (mem/create-conversation-buffer))

   ;; 添加消息
   (mem/add-message buffer {:role \"user\" :content \"你好\"})
   (mem/add-message buffer {:role \"assistant\" :content \"你好！\"})

   ;; 获取消息
   (mem/get-messages buffer)

   ;; 使用存储
   (mem/kv-put store \"user-123\" \"preference\" {:lang \"zh\"})
   (mem/kv-get store \"user-123\" \"preference\")

   ;; 使用快照
   (mem/snap-put ss {:thread-id \"t1\"} {:state {}} {:step 1})"

  (:require
    ;; Memory 协议 (本模块)
    [im.ttalk.agent.memory.protocol :as proto]
    ;; Store 实现
    [im.ttalk.agent.memory.store.in-memory :as in-memory-store]
    [im.ttalk.agent.memory.store.sqlite :as sqlite-store]
    [im.ttalk.agent.memory.store.postgresql :as postgres-store]
    [im.ttalk.agent.memory.store.redis :as redis-store]
    ;; Snapshot 实现
    [im.ttalk.agent.memory.manager.snapshot :as snapshot-manager]
    [im.ttalk.agent.memory.agent-memory :as agent-memory]
    ;; Memory 组件
    [im.ttalk.agent.memory.short-term.buffer :as buffer]
    [im.ttalk.agent.memory.long-term.semantic :as semantic]
    [im.ttalk.agent.memory.long-term.episodic :as episodic]
    [im.ttalk.agent.memory.long-term.procedural :as procedural]
    [im.ttalk.agent.memory.store.vector-memory :as vector-memory]
    [im.ttalk.agent.memory.store.embedding :as embedding]
    [im.ttalk.agent.memory.retrieval.strategies :as strategies]))

;; =============================================================================
;; Store 协议函数 (来自 core)
;; =============================================================================

(def kv-put proto/put)
(def kv-put-batch proto/put-batch)
(def kv-get proto/get-value)
(def kv-get-batch proto/get-batch)
(def kv-delete proto/delete)
(def kv-delete-batch proto/delete-batch)
(def kv-exists? proto/exists?)
(def kv-list-keys proto/list-keys)
(def kv-list-values proto/list-values)
(def kv-count proto/count-keys)
(def store-init! proto/init!)
(def store-close! proto/close!)
(def store-healthy? proto/healthy?)

;; =============================================================================
;; SnapshotStore 协议函数
;; =============================================================================

(def snap-put proto/snap-put)
(def snap-put-writes proto/snap-put-writes)
(def snap-get proto/snap-get)
(def snap-list proto/snap-list)
(def snap-delete-thread proto/snap-delete-thread)
(def snap-get-next-version proto/snap-get-next-version)
(def snap-restore-to-step proto/snap-restore-to-step)
(def snap-get-history proto/snap-get-history)
(def snap-create-branch proto/snap-create-branch)
(def snap-list-branches proto/snap-list-branches)
(def snap-prune proto/snap-prune)
(def snap-clear-all proto/snap-clear-all)

;; =============================================================================
;; 短期记忆协议函数
;; =============================================================================

(def add-message proto/add-message)
(def add-messages proto/add-messages)
(def get-messages proto/get-messages)
(def get-messages-window proto/get-messages-window)
(def get-messages-by-tokens proto/get-messages-by-tokens)
(def count-messages proto/count-messages)
(def count-tokens proto/count-tokens)
(def clear-messages proto/clear-messages)
(def summarize proto/summarize)

;; =============================================================================
;; 长期记忆 - 语义记忆协议函数
;; =============================================================================

(def store-fact proto/store-fact)
(def store-facts proto/store-facts)
(def get-fact proto/get-fact)
(def query-facts proto/query-facts)
(def update-fact proto/update-fact)
(def delete-fact proto/delete-fact)
(def get-profile proto/get-profile)
(def update-profile proto/update-profile)
(def set-profile proto/set-profile)

;; =============================================================================
;; 长期记忆 - 情景记忆协议函数
;; =============================================================================

(def store-episode proto/store-episode)
(def store-episodes proto/store-episodes)
(def get-episode proto/get-episode)
(def query-similar proto/query-similar)
(def get-recent-episodes proto/get-recent-episodes)
(def get-successful-episodes proto/get-successful-episodes)
(def update-episode-outcome proto/update-episode-outcome)

;; =============================================================================
;; 长期记忆 - 程序记忆协议函数
;; =============================================================================

(def get-system-prompt proto/get-system-prompt)
(def set-system-prompt proto/set-system-prompt)
(def add-rule proto/add-rule)
(def get-rules proto/get-rules)
(def get-active-rules proto/get-active-rules)
(def update-from-feedback proto/update-from-feedback)

;; =============================================================================
;; 向量存储协议函数
;; =============================================================================

(def upsert proto/upsert)
(def search proto/search)
(def delete-vector proto/delete-vector)

;; =============================================================================
;; 嵌入协议函数
;; =============================================================================

(def embed proto/embed)
(def embed-batch proto/embed-batch)

;; =============================================================================
;; 检索协议函数
;; =============================================================================

(def retrieve proto/retrieve)

;; =============================================================================
;; Store 工厂函数
;; =============================================================================

(def create-in-memory-store in-memory-store/create-in-memory-store)
(def create-sqlite-store sqlite-store/create-sqlite-store)
(def create-postgres-store postgres-store/create-postgres-store)
(def create-redis-store redis-store/create-redis-store)

;; =============================================================================
;; SnapshotStore 工厂函数
;; =============================================================================

;; SnapshotManager - 基于 TimelineManager 的快照管理
(def create-snapshot-manager snapshot-manager/create-snapshot-manager)
(def snapshot-manager? snapshot-manager/snapshot-manager?)

;; 便捷函数：创建内存 SnapshotStore
(defn create-memory-snapshot-store
  "创建内存 SnapshotStore

   内部使用 InMemoryStore 作为后端。

   示例：
   (def ss (create-memory-snapshot-store))
   (snap-put ss {:thread-id \"t1\"} {:state {}} {:step 1})"
  []
  (create-snapshot-manager (create-in-memory-store)))

;; 便捷函数：创建 SQLite SnapshotStore
(defn create-sqlite-snapshot-store
  "创建 SQLite SnapshotStore

   内部使用 SQLiteStore 作为后端。

   参数：
   - db-path: 数据库文件路径
   - opts: 其他选项

   示例：
   (def ss (create-sqlite-snapshot-store \"snapshots.db\"))"
  [db-path & {:as opts}]
  (create-snapshot-manager (create-sqlite-store db-path opts)))

;; 时间旅行操作（SnapshotManager 扩展功能）
(def go-back! snapshot-manager/go-back!)
(def go-forward! snapshot-manager/go-forward!)
(def goto! snapshot-manager/goto!)
(def get-lineage snapshot-manager/get-lineage)
(def switch-branch! snapshot-manager/switch-branch!)

;; =============================================================================
;; AgentMemory 工厂函数
;; =============================================================================

(def create-agent-memory agent-memory/create-agent-memory)
(def agent-memory? agent-memory/agent-memory?)

;; AgentMemory 操作
(def save-state agent-memory/save-state)
(def load-state agent-memory/load-state)

;; 时间旅行（封装 CheckpointManager）
(def go-back agent-memory/go-back)
(def go-forward agent-memory/go-forward)
(def goto agent-memory/goto)
(def list-history agent-memory/list-history)

;; 分支管理（封装 CheckpointManager）
(def create-branch agent-memory/create-branch)
(def switch-branch agent-memory/switch-branch)
(def list-branches agent-memory/list-branches)

;; 归档功能
(def archive-session! agent-memory/archive-session!)
(def load-archived agent-memory/load-archived)
(def list-archived agent-memory/list-archived)

;; 知识库操作
(def remember agent-memory/remember)
(def recall agent-memory/recall)
(def recall-by-type agent-memory/recall-by-type)
(def forget agent-memory/forget)
(def search-knowledge agent-memory/search-knowledge)

;; 消息管理
(def add-message-to-memory agent-memory/add-message)
(def get-messages-from-memory agent-memory/get-messages)
(def clear-messages-from-memory agent-memory/clear-messages)

;; 便捷函数
(def get-context-store agent-memory/get-context-store)
(def get-persistent-store agent-memory/get-persistent-store)

;; =============================================================================
;; Memory 组件工厂函数
;; =============================================================================

;; Conversation Buffer
(def create-conversation-buffer buffer/create-conversation-buffer)

;; 长期记忆
(def create-semantic-memory semantic/create-semantic-memory)
(def create-episodic-memory episodic/create-episodic-memory)
(def create-procedural-memory procedural/create-procedural-memory)

;; 向量存储
(def create-memory-vector-store vector-memory/create-memory-vector-store)

;; 嵌入生成器
(def create-mock-embedder embedding/create-mock-embedder)
(def create-simple-text-embedder embedding/create-simple-text-embedder)

;; 检索策略
(def create-retrieval-strategy strategies/create-retrieval-strategy)
(def create-recent-strategy strategies/create-recent-strategy)
(def create-window-strategy strategies/create-window-strategy)
(def create-token-limit-strategy strategies/create-token-limit-strategy)
(def create-semantic-strategy strategies/create-semantic-strategy)
(def create-hybrid-strategy strategies/create-hybrid-strategy)

;; =============================================================================
;; 便捷函数 - Buffer
;; =============================================================================

(def add-user-message buffer/add-user-message)
(def add-assistant-message buffer/add-assistant-message)
(def add-system-message buffer/add-system-message)
(def add-tool-message buffer/add-tool-message)
(def get-context-messages buffer/get-context-messages)

;; =============================================================================
;; 便捷函数 - Episode/Rule 创建
;; =============================================================================

(def create-success-episode episodic/create-success-episode)
(def create-failure-episode episodic/create-failure-episode)
(def create-rule procedural/create-rule)
(def create-language-rule procedural/create-language-rule)
(def create-style-rule procedural/create-style-rule)

;; =============================================================================
;; 类型检查函数
;; =============================================================================

(def store? proto/store?)
(def snapshot-store? proto/snapshot-store?)
(def conversation-buffer? proto/conversation-buffer?)
(def semantic-memory? proto/semantic-memory?)
(def episodic-memory? proto/episodic-memory?)
(def procedural-memory? proto/procedural-memory?)
(def vector-store? proto/vector-store?)
(def embedding? proto/embedding?)

;; =============================================================================
;; 便捷函数 - 组合创建
;; =============================================================================

(defn create-memory-system
  "创建记忆系统组件

   参数：
   - opts:
     - :store-type          存储类型 :memory | :sqlite | :postgres | :redis
     - :store-opts          存储配置
     - :archive-store-type  归档存储类型（可选，用于双 Store 模式）
     - :archive-store-opts  归档存储配置
     - :embedding-dimension 嵌入维度（默认 384）

   返回：
   {:store ...
    :snapshot-store ...
    :vector-store ...
    :embedder ...}

   示例：
   ;; 单 Store 模式
   (create-memory-system)
   (create-memory-system :store-type :sqlite :store-opts {:db-path \"data.db\"})

   ;; 双 Store 模式（内存 + SQLite 归档）
   (create-memory-system
     :store-type :memory
     :archive-store-type :sqlite
     :archive-store-opts {:db-path \"archive.db\"})"
  [& {:keys [store-type store-opts
             archive-store-type archive-store-opts
             embedding-dimension]
      :or {store-type :memory
           embedding-dimension 384}}]
  (let [;; 创建主 Store
        store (case store-type
                :memory (create-in-memory-store)
                :sqlite (create-sqlite-store (:db-path store-opts "memory.db") store-opts)
                :postgres (create-postgres-store store-opts)
                :redis (create-redis-store store-opts)
                (throw (ex-info "Unknown store type" {:type store-type})))

        ;; 创建归档 Store（可选）
        archive-store (when archive-store-type
                        (case archive-store-type
                          :memory (create-in-memory-store)
                          :sqlite (create-sqlite-store (:db-path archive-store-opts "archive.db") archive-store-opts)
                          :postgres (create-postgres-store archive-store-opts)
                          :redis (create-redis-store archive-store-opts)
                          (throw (ex-info "Unknown archive store type" {:type archive-store-type}))))

        ;; 创建 SnapshotManager（使用 Store）
        ;; 注意：如果有 archive-store，优先使用它作为快照存储
        snapshot-store (create-snapshot-manager (or archive-store store))

        ;; 创建向量相关组件
        embedder (create-simple-text-embedder :dimension embedding-dimension)
        vector-store (create-memory-vector-store :dimension embedding-dimension)]

    {:store store
     :snapshot-store snapshot-store
     :vector-store vector-store
     :embedder embedder}))

(defn with-memory
  "为 Agent 配置添加记忆组件

   参数：
   - agent-opts: Agent 配置
   - memory-components: 记忆组件 map

   返回：更新后的配置

   示例：
   (with-memory {:provider-type :anthropic}
                {:store store :snapshot-store snapshot-store})"
  [agent-opts memory-components]
  (merge agent-opts memory-components))
