(ns im.ttalk.agent.memory.protocol
  "Memory 系统协议定义

   clj-agent Memory 系统的完整协议层，参考 LangChain/LangGraph 设计。

   协议层次：
   ═══════════════════════════════════════════════════════════════
   Layer 1 - Store (基础存储)
   ═══════════════════════════════════════════════════════════════
   - IKeyValueStore: 基础 KV 存储协议

   ═══════════════════════════════════════════════════════════════
   Layer 2 - SnapshotStore (状态快照)
   ═══════════════════════════════════════════════════════════════
   - ISnapshotStore: 快照管理协议，支持状态持久化和时间旅行

   ═══════════════════════════════════════════════════════════════
   短期记忆 - IConversationBuffer
   ═══════════════════════════════════════════════════════════════
   管理当前会话的消息历史

   ═══════════════════════════════════════════════════════════════
   长期记忆
   ═══════════════════════════════════════════════════════════════
   - ISemanticMemory: 语义/知识记忆
   - IEpisodicMemory: 情景/经验记忆
   - IProceduralMemory: 程序/规则记忆

   ═══════════════════════════════════════════════════════════════
   向量存储
   ═══════════════════════════════════════════════════════════════
   - IVectorStore: 向量存储
   - IEmbedding: 嵌入生成

   ═══════════════════════════════════════════════════════════════
   检索策略
   ═══════════════════════════════════════════════════════════════
   - IRetrievalStrategy: 记忆检索策略")

;; =============================================================================
;; Layer 1: IKeyValueStore - 基础存储协议
;; =============================================================================

(defprotocol IKeyValueStore
  "基础键值存储协议

   所有存储后端必须实现此协议，提供统一的 KV 存储接口。

   实现：
   - InMemoryStore: 内存存储（开发/测试）
   - SQLiteStore: SQLite 存储
   - RedisStore: Redis 存储
   - PostgresStore: PostgreSQL 存储

   命名空间：
   - 所有操作都基于 namespace 进行隔离
   - namespace 可以是 user-id, thread-id, org-id 等

   使用示例：
   (put store \"user-123\" \"preference\" {:lang \"zh\"})
   (get-value store \"user-123\" \"preference\")
   (search store \"user-123\" {:query \"语言\"} {})"

  ;; -------------------------------------------------------------------------
  ;; CRUD 操作
  ;; -------------------------------------------------------------------------

  (put [this namespace key value]
    "存储值
     namespace: 命名空间（字符串）
     key: 键（字符串）
     value: 值（任意 Clojure 数据）
     返回: 存储记录 {:namespace :key :value :created-at :updated-at}")

  (put-batch [this namespace items]
    "批量存储
     items: [{:key :value} ...]
     返回: 存储记录列表")

  (get-value [this namespace key]
    "获取值
     返回: 值或 nil")

  (get-batch [this namespace keys]
    "批量获取
     返回: {key value ...}")

  (delete [this namespace key]
    "删除值
     返回: boolean")

  (delete-batch [this namespace keys]
    "批量删除
     返回: 删除的数量")

  (exists? [this namespace key]
    "检查是否存在
     返回: boolean")

  ;; -------------------------------------------------------------------------
  ;; 查询操作
  ;; -------------------------------------------------------------------------

  (list-keys [this namespace opts]
    "列出 keys
     opts: {:prefix string  ; key 前缀过滤
            :limit int      ; 返回数量限制
            :offset int     ; 跳过数量}
     返回: key 列表")

  (list-values [this namespace opts]
    "列出值
     opts: 同 list-keys
     返回: [{:key :value :created-at :updated-at} ...]")

  (search [this namespace query opts]
    "搜索（支持语义搜索，如果后端支持）
     query: 查询条件 {:query string :filter map}
     opts: {:top-k int :threshold float}
     返回: [{:key :value :score} ...]")

  (count-keys [this namespace opts]
    "统计 key 数量
     opts: {:prefix string}
     返回: integer")

  ;; -------------------------------------------------------------------------
  ;; 生命周期
  ;; -------------------------------------------------------------------------

  (init! [this]
    "初始化存储
     返回: this")

  (close! [this]
    "关闭存储
     返回: nil")

  (healthy? [this]
    "健康检查
     返回: boolean"))

;; =============================================================================
;; Layer 2: ISnapshotStore - 快照存储协议
;; =============================================================================

(defprotocol ISnapshotStore
  "快照管理协议

   管理 Agent 执行的状态快照，支持：
   - 持久化：保存执行状态
   - 时间旅行：恢复到任意快照
   - 分支管理：从快照创建分支
   - Human-in-the-loop：暂停和恢复执行

   实现：
   - StoreBackedSnapshotStore: 基于 IKeyValueStore 的统一实现
     - 使用 InMemoryStore: 内存保存（开发/测试）
     - 使用 SQLiteStore: SQLite 持久化
     - 使用 PostgresStore: PostgreSQL 持久化

   核心概念：
   - thread-id: 线程/会话 ID
   - snapshot-id: 快照 ID
   - snapshot: {:id :thread-id :state :metadata :parent-id :created-at}

   使用示例：
   (snap-put store config snapshot metadata)
   (snap-get store config)
   (snap-list store config {})"

  ;; -------------------------------------------------------------------------
  ;; 核心操作
  ;; -------------------------------------------------------------------------

  (snap-put [this config snapshot metadata]
    "保存快照
     config: {:thread-id string :snapshot-id string (optional)}
     snapshot: {:state any :channel-values map}
     metadata: {:step int :node string :source string}
     返回: snapshot-id")

  (snap-put-writes [this config writes task-id]
    "保存中间写入（pending writes）
     config: {:thread-id :snapshot-id}
     writes: [{:channel :value} ...]
     task-id: 任务 ID
     返回: boolean")

  (snap-get [this config]
    "获取快照元组
     config: {:thread-id string :snapshot-id string (optional)}
     返回: {:snapshot :metadata :parent-config :pending-writes} 或 nil")

  (snap-list [this config opts]
    "列出快照
     config: {:thread-id string}
     opts: {:limit int :before string :filter map}
     返回: snapshot 序列")

  (snap-delete-thread [this thread-id]
    "删除线程的所有快照
     返回: 删除的数量")

  ;; -------------------------------------------------------------------------
  ;; 扩展操作
  ;; -------------------------------------------------------------------------

  (snap-get-next-version [this current channel]
    "获取下一个版本号
     current: 当前版本
     channel: 通道名
     返回: 新版本号")

  (snap-restore-to-step [this thread-id step]
    "恢复到指定步骤
     返回: snapshot 或 nil")

  (snap-get-history [this thread-id]
    "获取执行历史
     返回: [{:id :step :node :created-at} ...]")

  ;; -------------------------------------------------------------------------
  ;; 分支管理
  ;; -------------------------------------------------------------------------

  (snap-create-branch [this thread-id snapshot-id branch-name]
    "从快照创建分支
     返回: {:branch-id :snapshot-id}")

  (snap-list-branches [this thread-id]
    "列出分支
     返回: [{:branch-id :name :snapshot-count} ...]")

  ;; -------------------------------------------------------------------------
  ;; 清理操作
  ;; -------------------------------------------------------------------------

  (snap-prune [this thread-id opts]
    "清理旧快照
     opts: {:keep-count int :keep-types #{:initial :final}}
     返回: {:kept int :deleted int}")

  (snap-clear-all [this]
    "清除所有数据
     返回: 删除的数量"))

;; =============================================================================
;; 短期记忆协议
;; =============================================================================

(defprotocol IConversationBuffer
  "会话缓冲协议 - 管理当前会话的消息历史

   短期记忆的核心实现，负责：
   - 存储当前会话的消息
   - 提供多种消息检索方式（窗口、Token 限制）
   - 生成会话摘要

   使用示例：
   (add-message buffer {:role \"user\" :content \"你好\"})
   (get-messages-window buffer 10)
   (summarize buffer)"

  (add-message [this message]
    "添加消息
     message: {:role \"user\"|\"assistant\"|\"system\"|\"tool\"
               :content string
               :tool-call-id string (optional)
               :name string (optional)}")

  (add-messages [this messages]
    "批量添加消息")

  (get-messages [this]
    "获取所有消息")

  (get-messages-window [this n]
    "获取最近 n 条消息")

  (get-messages-by-tokens [this max-tokens]
    "获取消息直到达到 token 限制")

  (get-messages-by-role [this role]
    "按角色筛选消息")

  (get-last-n-turns [this n]
    "获取最近 n 轮对话")

  (count-messages [this]
    "获取消息数量")

  (count-tokens [this]
    "估算总 token 数")

  (clear-messages [this]
    "清空所有消息")

  (trim-to-window [this n]
    "裁剪到最近 n 条消息")

  (trim-to-tokens [this max-tokens]
    "裁剪到指定 token 数以内")

  (summarize [this]
    "生成会话摘要")

  (summarize-and-trim [this max-tokens]
    "生成摘要并用摘要替换历史消息"))

;; =============================================================================
;; 长期记忆协议
;; =============================================================================

(defprotocol ISemanticMemory
  "语义记忆协议 - 存储事实和知识

   用于存储：用户偏好、领域知识、实体信息、关系

   使用示例：
   (store-fact memory \"user-123\" {:type :preference :content \"喜欢中文\"})
   (query-facts memory \"user-123\" \"语言偏好\" {:top-k 5})"

  (store-fact [this namespace fact]
    "存储事实")

  (store-facts [this namespace facts]
    "批量存储事实")

  (get-fact [this namespace fact-id]
    "按 ID 获取事实")

  (query-facts [this namespace query opts]
    "查询事实（支持语义搜索）")

  (update-fact [this namespace fact-id updates]
    "更新事实")

  (delete-fact [this namespace fact-id]
    "删除事实")

  (delete-facts-by-filter [this namespace filter]
    "按条件批量删除")

  (list-facts [this namespace opts]
    "列出事实")

  (count-facts [this namespace]
    "统计事实数量")

  ;; Profile 模式
  (get-profile [this namespace profile-type]
    "获取 profile")

  (update-profile [this namespace profile-type updates]
    "更新 profile（深度合并）")

  (set-profile [this namespace profile-type data]
    "设置 profile（完全替换）"))


(defprotocol IEpisodicMemory
  "情景记忆协议 - 存储成功经验和案例

   每个 episode 包含：
   - situation: 情境描述
   - action: 采取的行动
   - outcome: 结果（成功/失败/部分成功）
   - reasoning: 为什么这样做有效

   使用示例：
   (store-episode memory {:situation \"用户问代码重构\"
                          :action \"分步骤解释\"
                          :outcome :success})"

  (store-episode [this episode]
    "存储情景")

  (store-episodes [this episodes]
    "批量存储情景")

  (get-episode [this episode-id]
    "按 ID 获取情景")

  (query-similar [this situation opts]
    "查询相似情境")

  (get-recent-episodes [this opts]
    "获取最近的情景")

  (get-successful-episodes [this opts]
    "获取成功案例")

  (delete-episode [this episode-id]
    "删除情景")

  (update-episode-outcome [this episode-id outcome reasoning]
    "更新情景结果"))


(defprotocol IProceduralMemory
  "程序记忆协议 - 存储行为规则和模式

   用于存储：系统提示词、行为规则、响应模式

   使用示例：
   (add-rule memory \"user-123\" {:condition \"用户说中文\" :action \"用中文回复\"})"

  (get-system-prompt [this namespace]
    "获取系统提示词")

  (set-system-prompt [this namespace prompt]
    "设置系统提示词")

  (append-to-system-prompt [this namespace addition]
    "追加到系统提示词")

  (add-rule [this namespace rule]
    "添加行为规则")

  (get-rules [this namespace opts]
    "获取规则")

  (get-active-rules [this namespace context]
    "获取当前上下文下激活的规则")

  (update-rule [this namespace rule-id updates]
    "更新规则")

  (deactivate-rule [this namespace rule-id]
    "禁用规则")

  (delete-rule [this namespace rule-id]
    "删除规则")

  (update-from-feedback [this namespace feedback]
    "从反馈中学习并更新规则")

  (get-learned-patterns [this namespace]
    "获取学习到的模式"))

;; =============================================================================
;; 向量存储协议
;; =============================================================================

(defprotocol IVectorStore
  "向量存储协议 - 支持语义搜索

   为 SemanticMemory 和 EpisodicMemory 提供向量搜索能力。

   使用示例：
   (upsert store \"doc-1\" embedding {:type :fact})
   (search store query-embedding {:top-k 5})"

  (upsert [this id vector metadata]
    "插入或更新向量")

  (upsert-batch [this items]
    "批量插入")

  (get-vector [this id]
    "按 ID 获取向量和元数据")

  (delete-vector [this id]
    "删除向量")

  (delete-by-filter [this filter]
    "按条件批量删除")

  (search [this query-vector opts]
    "向量搜索")

  (count-vectors [this opts]
    "统计向量数量"))


(defprotocol IEmbedding
  "嵌入生成协议

   将文本转换为向量表示。

   使用示例：
   (embed embedder \"这是一段文本\")"

  (embed [this text]
    "生成单个文本的嵌入向量")

  (embed-batch [this texts]
    "批量生成嵌入向量")

  (get-dimension [this]
    "获取嵌入向量维度"))

;; =============================================================================
;; 检索策略协议
;; =============================================================================

(defprotocol IRetrievalStrategy
  "记忆检索策略协议

   内置策略：:recent, :window, :summary, :semantic, :hybrid

   使用示例：
   (retrieve strategy memory-manager query context)"

  (retrieve [this store query context]
    "执行检索
     返回: {:messages [...] :facts [...] :episodes [...] :rules [...]}"))

;; =============================================================================
;; 辅助函数
;; =============================================================================

(defn store?
  "检查是否实现了 IKeyValueStore 协议"
  [x]
  (satisfies? IKeyValueStore x))

(defn snapshot-store?
  "检查是否实现了 ISnapshotStore 协议"
  [x]
  (satisfies? ISnapshotStore x))

(defn conversation-buffer?
  "检查是否实现了 IConversationBuffer 协议"
  [x]
  (satisfies? IConversationBuffer x))

(defn semantic-memory?
  "检查是否实现了 ISemanticMemory 协议"
  [x]
  (satisfies? ISemanticMemory x))

(defn episodic-memory?
  "检查是否实现了 IEpisodicMemory 协议"
  [x]
  (satisfies? IEpisodicMemory x))

(defn procedural-memory?
  "检查是否实现了 IProceduralMemory 协议"
  [x]
  (satisfies? IProceduralMemory x))

(defn vector-store?
  "检查是否实现了 IVectorStore 协议"
  [x]
  (satisfies? IVectorStore x))

(defn embedding?
  "检查是否实现了 IEmbedding 协议"
  [x]
  (satisfies? IEmbedding x))
