(ns im.ttalk.agent.memory.agent-memory
  "AgentMemory - Agent 记忆管理

   双 Store 架构 + SnapshotManager 组合：
   ┌─────────────────────────────────────────────────────────────┐
   │                      AgentMemory                            │
   │  ┌─────────────────┐         ┌─────────────────────────┐    │
   │  │  context-store  │         │   persistent-store      │    │
   │  │  (IStore)       │         │   (IStore, 可选)        │    │
   │  │  (热数据)       │         │   (冷数据/归档)         │    │
   │  └────────┬────────┘         └──────────┬──────────────┘    │
   │           │                             │                   │
   │           ▼                             │                   │
   │  ┌─────────────────┐                   │                   │
   │  │ SnapshotMgr     │                   │                   │
   │  │ (snapshot管理)  │                   │                   │
   │  └─────────────────┘                   │                   │
   │                                       │                   │
   │  ┌────────────────────────────────────┴─────────────────┐ │
   │  │              AgentMemory 职责：                       │ │
   │  │  • 实现 ISnapshotStore 协议（委托给 SnapshotManager）│ │
   │  │  • 组合 SnapshotManager + 双 Store                   │ │
   │  │  • 归档功能 (archive-session!/load-archived/list...) │ │
   │  │  • 知识库 (remember/recall/forget)                   │ │
   │  │  • 消息管理 (add-message/get-messages)               │ │
   │  │  • 便捷方法 (save-state/go-back/etc)                 │ │
   │  └───────────────────────────────────────────────────────┘ │
   └─────────────────────────────────────────────────────────────┘

   设计原则：
   1. 实现 ISnapshotStore 协议，可直接用于 create-agent
   2. 双 Store 架构：context-store（热数据）+ persistent-store（冷数据）
   3. 当 persistent-store 为 nil 时，降级为单 Store 模式

   使用示例：

   ;; 单 Store 模式（简单）
   (def memory (create-agent-memory
                :context-store (create-in-memory-store)))

   (def agent (create-agent provider tools
               {:snapshot-store memory}))

   ;; 双 Store 模式（推荐）
   (def memory (create-agent-memory
                :context-store (create-in-memory-store)
                :persistent-store (create-sqlite-store \"archive.db\")))

   ;; 高级功能
   (save-state memory {:user \"Alice\" :count 0})
   (go-back memory :steps 2)
   (archive-session! memory)
   (remember memory :user \"Alice\" {:type :preference})"
  (:require [im.ttalk.agent.memory.protocol :as proto]
            [im.ttalk.agent.memory.snapshot.manager :as mgr]))

;; =============================================================================
;; 命名空间常量
;; =============================================================================

(def ^:private ns-snapshots "snapshots")
(def ^:private ns-threads "threads")
(def ^:private ns-archives "archives")
(def ^:private ns-knowledge "knowledge")

;; =============================================================================
;; 辅助函数
;; =============================================================================

(defn- now []
  (System/currentTimeMillis))

(defn- thread-key
  "构建线程元数据 key"
  [thread-id]
  (str "thread:" thread-id))

(defn- archive-key
  "构建归档 key"
  [thread-id]
  (str "archive:" thread-id))

(defn- knowledge-key
  "构建知识库 key"
  [type id]
  (str type ":" id))

;; =============================================================================
;; AgentMemory 实现
;; =============================================================================

(defrecord AgentMemory [context-store persistent-store snapshot-store
                       default-thread-id auto-archive config]

  ;; ==========================================================================
  ;; ISnapshotStore 协议实现（委托给内部的 SnapshotManager）
  ;; ==========================================================================
  proto/ISnapshotStore

  ;; 核心操作
  (snap-put [this cfg snapshot metadata]
    (proto/snap-put snapshot-store cfg snapshot metadata))

  (snap-put-writes [this cfg writes task-id]
    (proto/snap-put-writes snapshot-store cfg writes task-id))

  (snap-get [this cfg]
    (proto/snap-get snapshot-store cfg))

  (snap-list [this cfg opts]
    (proto/snap-list snapshot-store cfg opts))

  (snap-delete-thread [this thread-id]
    (proto/snap-delete-thread snapshot-store thread-id))

  ;; 扩展操作
  (snap-get-next-version [this current channel]
    (proto/snap-get-next-version snapshot-store current channel))

  (snap-restore-to-step [this thread-id step]
    (proto/snap-restore-to-step snapshot-store thread-id step))

  (snap-get-history [this thread-id]
    (proto/snap-get-history snapshot-store thread-id))

  ;; 分支管理
  (snap-create-branch [this thread-id snapshot-id branch-name]
    (proto/snap-create-branch snapshot-store thread-id snapshot-id branch-name))

  (snap-list-branches [this thread-id]
    (proto/snap-list-branches snapshot-store thread-id))

  ;; 清理操作
  (snap-prune [this thread-id opts]
    (proto/snap-prune snapshot-store thread-id opts))

  (snap-clear-all [this]
    (proto/snap-clear-all snapshot-store)))

;; =============================================================================
;; AgentMemory 扩展方法
;; =============================================================================

;; -------------------------------------------------------------------------
;; 便捷方法：状态保存/加载
;; -------------------------------------------------------------------------

(defn- get-thread-id
  "获取实际的 thread-id"
  [memory thread-id]
  (or thread-id (:default-thread-id memory)))

(defn save-state
  "保存状态为 snapshot（保存到 context-store）

   参数：
   - memory: AgentMemory 实例
   - state: 状态数据
   - opts: 可选参数
     :thread-id 线程 ID
     :metadata 元数据

   返回：snapshot-id"
  [memory state & {:keys [thread-id metadata]
                   :or {thread-id nil
                        metadata {}}}]
  (let [tid (get-thread-id memory thread-id)
        cfg {:thread-id tid}]
    (proto/snap-put memory cfg state metadata)))

(defn load-state
  "加载 snapshot

   参数：
   - memory: AgentMemory 实例
   - opts: 可选参数
     :thread-id 线程 ID
     :snapshot-id 快照 ID（可选，默认最新）

   返回：snapshot tuple"
  [memory & {:keys [thread-id snapshot-id]
             :or {thread-id nil
                  snapshot-id nil}}]
  (let [tid (get-thread-id memory thread-id)
        cfg {:thread-id tid
             :snapshot-id snapshot-id}]
    (proto/snap-get memory cfg)))

;; -------------------------------------------------------------------------
;; 便捷方法：时间旅行（使用 snapshot.manager 的方法）
;; -------------------------------------------------------------------------

(defn go-back
  "回退 N 步

   参数：
   - memory: AgentMemory 实例
   - opts: 可选参数
     :thread-id 线程 ID
     :steps 步数（默认 1）

   返回：目标 snapshot 或 nil"
  [memory & {:keys [thread-id steps]
             :or {thread-id nil
                  steps 1}}]
  (let [tid (get-thread-id memory thread-id)
        cfg {:thread-id tid}
        history (proto/snap-list memory cfg {})
        branch-id @(get-in memory [:snapshot-store :current-branch])
        branches @(get-in memory [:snapshot-store :branches])
        branch-info (get branches branch-id)
        current-id (:head-snapshot-id branch-info)
        current-index (when current-id
                        (first (keep-indexed
                                 (fn [i s]
                                   (when (= (:id s) current-id)
                                     i))
                                 history)))]

    (when (and current-index
               (< (+ current-index steps) (count history)))
      (let [target (nth history (+ current-index steps))
            target-id (:id target)]
        (swap! (get-in memory [:snapshot-store :branches])
               update branch-id assoc :head-snapshot-id target-id)
        target))))

(defn go-forward
  "前进 N 步

   参数：
   - memory: AgentMemory 实例
   - opts: 可选参数
     :thread-id 线程 ID
     :steps 步数（默认 1）

   返回：目标 snapshot 或 nil"
  [memory & {:keys [thread-id steps]
             :or {thread-id nil
                  steps 1}}]
  (let [tid (get-thread-id memory thread-id)
        cfg {:thread-id tid}
        history (proto/snap-list memory cfg {})
        branch-id @(get-in memory [:snapshot-store :current-branch])
        branches @(get-in memory [:snapshot-store :branches])
        branch-info (get branches branch-id)
        current-id (:head-snapshot-id branch-info)
        current-index (when current-id
                       (first (keep-indexed
                                (fn [i s]
                                  (when (= (:id s) current-id)
                                    i))
                                history)))]

    (when (and current-index
               (>= (- current-index steps) 0))
      (let [target (nth history (- current-index steps))
            target-id (:id target)]
        (swap! (get-in memory [:snapshot-store :branches])
               update branch-id assoc :head-snapshot-id target-id)
        target))))

(defn goto
  "跳转到指定 snapshot

   参数：
   - memory: AgentMemory 实例
   - snapshot-id: 目标 snapshot ID
   - opts: 可选参数
     :thread-id 线程 ID

   返回：目标 snapshot 或 nil"
  [memory snapshot-id & {:keys [thread-id]
                         :or {thread-id nil}}]
  (let [tid (get-thread-id memory thread-id)
        branch-id @(get-in memory [:snapshot-store :current-branch])
        cfg {:thread-id tid
             :snapshot-id snapshot-id}
        target (proto/snap-get memory cfg)]

    (when target
      (swap! (get-in memory [:snapshot-store :branches])
             update branch-id assoc :head-snapshot-id snapshot-id)
      target)))

(defn list-history
  "列出历史

   参数：
   - memory: AgentMemory 实例
   - opts: 可选参数
     :thread-id 线程 ID
     :limit 数量限制（默认 100）

   返回：snapshot 列表"
  [memory & {:keys [thread-id limit]
             :or {thread-id nil
                  limit 100}}]
  (let [tid (get-thread-id memory thread-id)
        cfg {:thread-id tid}]
    (proto/snap-list memory cfg {:limit limit})))

;; -------------------------------------------------------------------------
;; 便捷方法：分支管理
;; -------------------------------------------------------------------------

(defn create-branch
  "创建新分支

   参数：
   - memory: AgentMemory 实例
   - branch-id: 分支 ID
   - opts: 可选参数
     :thread-id 线程 ID
     :from-snapshot 源 snapshot ID（可选）

   返回：新分支信息"
  [memory branch-id & {:keys [thread-id from-snapshot]
                       :or {thread-id nil
                            from-snapshot nil}}]
  (let [tid (get-thread-id memory thread-id)]
    (proto/snap-create-branch memory tid from-snapshot branch-id)))

(defn switch-branch
  "切换分支

   参数：
   - memory: AgentMemory 实例
   - branch-id: 目标分支 ID
   - opts: 可选参数
     :thread-id 线程 ID

   返回：目标分支的最新 snapshot 或 nil"
  [memory branch-id & {:keys [thread-id]
                       :or {thread-id nil}}]
  (let [tid (get-thread-id memory thread-id)
        branches @(get-in memory [:snapshot-store :branches])
        branch-info (get branches branch-id)]

    (when branch-info
      (reset! (get-in memory [:snapshot-store :current-branch]) branch-id)
      (let [head-id (:head-snapshot-id branch-info)]
        (when head-id
          (proto/snap-get memory {:thread-id tid
                                  :snapshot-id head-id}))))))

(defn list-branches
  "列出所有分支

   参数：
   - memory: AgentMemory 实例
   - opts: 可选参数
     :thread-id 线程 ID

   返回：分支列表"
  [memory & {:keys [thread-id]
             :or {thread-id nil}}]
  (let [tid (get-thread-id memory thread-id)]
    (proto/snap-list-branches memory tid)))

;; -------------------------------------------------------------------------
;; 归档功能（context-store → persistent-store）
;; -------------------------------------------------------------------------

(defn archive-session!
  "将当前会话完整归档到 persistent-store

   归档所有 snapshot 历史到 persistent-store，并从 context-store 清理。

   参数：
   - memory: AgentMemory 实例
   - opts: 可选参数
     :thread-id 线程 ID
     :summarize-fn 总结函数 (fn [snapshots] -> summary-string)

   返回：归档的 session-id 或 nil（如果没有 persistent-store）

   示例：
   ;; 基本归档
   (archive-session! memory :thread-id \"user-123\")

   ;; 带 AI 摘要
   (archive-session! memory
     :thread-id \"user-123\"
     :summarize-fn (fn [snapshots]
                    (str \"共 \" (count snapshots) \" 个快照\")))"
  [memory & {:keys [thread-id summarize-fn]
             :or {thread-id nil
                  summarize-fn nil}}]
  (when-let [ps (:persistent-store memory)]
    (let [tid (get-thread-id memory thread-id)

          ;; 获取完整历史
          history (proto/snap-list memory {:thread-id tid} {:limit 10000})

          session-id (str "session-" tid "-" (now))

          ;; 构建归档数据
          archive-data {:session-id session-id
                        :thread-id tid
                        :archived-at (now)
                        :snapshot-count (count history)
                        :snapshots history
                        :first-snapshot-id (:id (first history))
                        :last-snapshot-id (:id (last history))
                        :summary (when summarize-fn
                                   (summarize-fn history))}]

      ;; 归档到 persistent-store
      (proto/put ps ns-archives (archive-key tid) archive-data)

      ;; 清理 context-store
      (proto/snap-delete-thread memory tid)

      session-id)))

(defn load-archived
  "从 persistent-store 加载归档的会话

   参数：
   - memory: AgentMemory 实例
   - session-id: 会话 ID（未使用，保留接口）
   - opts: 可选参数
     :thread-id 线程 ID

   返回：归档数据或 nil"
  [memory session-id & {:keys [thread-id]
                        :or {thread-id nil}}]
  (when-let [ps (:persistent-store memory)]
    (let [tid (get-thread-id memory thread-id)]
      (proto/get-value ps ns-archives (archive-key tid)))))

(defn list-archived
  "列出所有归档的会话

   参数：
   - memory: AgentMemory 实例
   - opts: 可选参数
     :limit 数量限制（默认 20）

   返回：归档会话列表"
  [memory & {:keys [limit]
             :or {limit 20}}]
  (when-let [ps (:persistent-store memory)]
    (->> (proto/list-values ps ns-archives {:limit limit})
         (map (fn [item]
                (let [data (:value item)]
                  {:session-id (:session-id data)
                   :thread-id (:thread-id data)
                   :archived-at (:archived-at data)
                   :summary (:summary data)}))))))

;; -------------------------------------------------------------------------
;; 知识库功能（persistent-store）
;; -------------------------------------------------------------------------

(defn remember
  "记住内容到知识库（存储到 persistent-store）

   参数：
   - memory: AgentMemory 实例
   - type: 类型（如 :user, :entity, :fact）
   - id: 唯一标识
   - content: 内容
   - opts: 可选参数
     :metadata 元数据
     :embedding 向量嵌入（可选）

   返回：id"
  [memory type id content & {:keys [metadata embedding]
                             :or {metadata {}
                                  embedding nil}}]
  (let [store (or (:persistent-store memory) (:context-store memory))
        key (knowledge-key (name type) id)]
    (proto/put store ns-knowledge key
                  {:type type
                   :id id
                   :content content
                   :metadata metadata
                   :embedding embedding
                   :created-at (now)})
    id))

(defn recall
  "从知识库回忆内容

   参数：
   - memory: AgentMemory 实例
   - type: 类型
   - id: 唯一标识
   - opts: 可选参数
     :thread-id 线程 ID（未使用，保留接口）

   返回：内容或 nil"
  [memory type id & {:keys [thread-id]
                     :or {thread-id nil}}]
  (let [store (or (:persistent-store memory) (:context-store memory))
        key (knowledge-key (name type) id)
        item (proto/get-value store ns-knowledge key)]
    (when item
      (:content (:value item)))))

(defn recall-by-type
  "按类型回忆所有内容

   参数：
   - memory: AgentMemory 实例
   - type: 类型
   - opts: 可选参数
     :limit 数量限制（默认 100）

   返回：内容列表"
  [memory type & {:keys [limit]
                  :or {limit 100}}]
  (let [store (or (:persistent-store memory) (:context-store memory))
        type-str (name type)
        all-items (proto/list-values store ns-knowledge {:limit limit})]
    (->> all-items
         (filter #(= type-str (get-in % [:value :type] "")))
         (map :content))))

(defn forget
  "从知识库遗忘内容

   参数：
   - memory: AgentMemory 实例
   - type: 类型
   - id: 唯一标识

   返回：boolean"
  [memory type id]
  (let [store (or (:persistent-store memory) (:context-store memory))
        key (knowledge-key (name type) id)]
    (proto/delete store ns-knowledge key)))

(defn search-knowledge
  "搜索知识库（基于元数据或内容）

   参数：
   - memory: AgentMemory 实例
   - query: 查询字符串
   - opts: 可选参数
     :limit 数量限制（默认 10）

   返回：内容列表

   TODO: 当有向量支持时，可以实现语义搜索"
  [memory query & {:keys [limit]
                    :or {limit 10}}]
  (let [store (or (:persistent-store memory) (:context-store memory))
        all-items (proto/list-values store ns-knowledge {:limit 1000})]
    (->> all-items
         (filter (fn [item]
                   (or (clojure.string/includes?
                        (str (:content (:value item)))
                        (str query))
                      (clojure.string/includes?
                        (str (:metadata (:value item)))
                        (str query)))))
         (take limit)
         (map :content))))

;; -------------------------------------------------------------------------
;; 消息管理（便捷函数）
;; -------------------------------------------------------------------------

(defn add-message
  "添加消息到当前会话

   参数：
   - memory: AgentMemory 实例
   - role: 角色（:user/:assistant/:system/:tool）
   - content: 内容
   - opts: 可选参数
     :thread-id 线程 ID
     :metadata 元数据

   返回：新消息"
  [memory role content & {:keys [thread-id metadata]
                         :or {thread-id nil
                              metadata {}}}]
  (let [tid (get-thread-id memory thread-id)
        cfg {:thread-id tid}
        tuple (proto/snap-get memory cfg)
        current-snapshot (:snapshot tuple)
        current-messages (or (:messages current-snapshot) [])
        new-message {:role role
                     :content content
                     :metadata metadata
                     :timestamp (now)}
        updated-snapshot (assoc current-snapshot
                                :messages (conj current-messages new-message))]
    (proto/snap-put memory cfg updated-snapshot {})
    new-message))

(defn get-messages
  "获取当前会话的消息

   参数：
   - memory: AgentMemory 实例
   - opts: 可选参数
     :thread-id 线程 ID
     :limit 数量限制

   返回：消息列表"
  [memory & {:keys [thread-id limit]
             :or {thread-id nil
                  limit nil}}]
  (let [tid (get-thread-id memory thread-id)
        cfg {:thread-id tid}
        tuple (proto/snap-get memory cfg)
        snapshot (:snapshot tuple)
        messages (or (:messages snapshot) [])]
    (if (and limit (> (count messages) limit))
      (take-last limit messages)
      messages)))

(defn clear-messages
  "清空当前会话的消息

   参数：
   - memory: AgentMemory 实例
   - opts: 可选参数
     :thread-id 线程 ID
     :archive 是否先归档（默认 false）

   返回：nil"
  [memory & {:keys [thread-id archive]
             :or {thread-id nil
                  archive false}}]
  (when archive
    (archive-session! memory :thread-id thread-id))

  (let [tid (get-thread-id memory thread-id)
        cfg {:thread-id tid}
        tuple (proto/snap-get memory cfg)
        snapshot (:snapshot tuple)
        updated-snapshot (assoc snapshot :messages [])]
    (proto/snap-put memory cfg updated-snapshot {})))

;; =============================================================================
;; 工厂函数
;; =============================================================================

(defn create-agent-memory
  "创建 AgentMemory

   参数：
   - context-store: 上下文存储（必需，用于当前会话）
   - opts:
     - :persistent-store 持久化存储（可选，用于归档和知识库）
     - :default-thread-id 默认线程 ID（默认 \"default\"）
     - :auto-archive 是否自动归档（默认 false）
     - :initial-branch 初始分支名（默认 \"main\"）
     - :config 额外配置

   返回：AgentMemory 实例（实现 ISnapshotStore 协议）

   示例：
   ;; 单 Store 模式
   (def memory (create-agent-memory
                 :context-store (create-in-memory-store)))

   ;; 双 Store 模式（推荐）
   (def memory (create-agent-memory
                 :context-store (create-in-memory-store)
                 :persistent-store (create-sqlite-store \"archive.db\")))"
  [context-store & {:keys [persistent-store
                         default-thread-id
                         auto-archive
                         initial-branch
                         config]
                  :or {default-thread-id "default"
                       auto-archive false
                       initial-branch "main"
                       config {}}}]
  {:pre [(some? context-store)
         (proto/store? context-store)
         (or (nil? persistent-store) (proto/store? persistent-store))]}
  (let [ss (mgr/create-snapshot-manager context-store
                                        :initial-branch initial-branch)]
    (->AgentMemory context-store
                  persistent-store
                  ss
                  default-thread-id
                  auto-archive
                  config)))

(defn agent-memory?
  "检查是否为 AgentMemory"
  [x]
  (instance? AgentMemory x))

(defn dual-store?
  "检查是否为双 Store 模式"
  [memory]
  (some? (:persistent-store memory)))

(defn get-context-store
  "获取 context-store"
  [memory]
  (:context-store memory))

(defn get-persistent-store
  "获取 persistent-store（可能为 nil）"
  [memory]
  (:persistent-store memory))
