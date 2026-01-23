(ns im.ttalk.agent.memory.agent-memory
  "AgentMemory - Agent 记忆管理

   双 Store 架构 + CheckpointManager 组合：
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
   │  │ CheckpointMgr   │                   │                   │
   │  │ (checkpoint管理)│                   │                   │
   │  └─────────────────┘                   │                   │
   │                                       │                   │
   │  ┌────────────────────────────────────┴─────────────────┐ │
   │  │              AgentMemory 职责：                       │ │
   │  │  • 实现 ICheckpointer 协议（委托给 CheckpointManager） │ │
   │  │  • 组合 CheckpointManager + 双 Store                 │ │
   │  │  • 归档功能 (archive-session!/load-archived/list...) │ │
   │  │  • 知识库 (remember/recall/forget)                   │ │
   │  │  • 消息管理 (add-message/get-messages)               │ │
   │  │  • 便捷方法 (save-state/go-back/etc)                 │ │
   │  └───────────────────────────────────────────────────────┘ │
   └─────────────────────────────────────────────────────────────┘

   设计原则：
   1. 实现 ICheckpointer 协议，可直接用于 create-agent
   2. 双 Store 架构：context-store（热数据）+ persistent-store（冷数据）
   3. 当 persistent-store 为 nil 时，降级为单 Store 模式
   4. 与 Lisp 版本 api/agent-memory.lisp 对齐

   使用示例：

   ;; 单 Store 模式（简单）
   (def memory (create-agent-memory
                :context-store (create-in-memory-store)))

   (def agent (create-agent provider tools
               {:checkpointer memory}))

   ;; 双 Store 模式（推荐）
   (def memory (create-agent-memory
                :context-store (create-in-memory-store)
                :persistent-store (create-sqlite-store \"archive.db\")))

   ;; 作为 checkpointer 使用
   (def agent (create-agent provider tools
               {:checkpointer memory}))

   ;; 高级功能
   (save-state memory {:user \"Alice\" :count 0})
   (go-back memory :steps 2)
   (archive-session! memory)
   (remember memory :user \"Alice\" {:type :preference})"
  (:require [im.ttalk.agent.memory.protocol :as proto]
            [im.ttalk.agent.memory.checkpoint.manager :as mgr]))

;; =============================================================================
;; 命名空间常量
;; =============================================================================

(def ^:private ns-checkpoints "checkpoints")
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

(defrecord AgentMemory [context-store persistent-store checkpointer
                       default-thread-id auto-archive config]

  ;; ==========================================================================
  ;; ICheckpointer 协议实现（委托给内部的 CheckpointManager）
  ;; ==========================================================================
  proto/ICheckpointer

  ;; 核心操作
  (cp-put [this cfg checkpoint metadata]
    (proto/cp-put checkpointer cfg checkpoint metadata))

  (cp-put-writes [this cfg writes task-id]
    (proto/cp-put-writes checkpointer cfg writes task-id))

  (cp-get-tuple [this cfg]
    (proto/cp-get-tuple checkpointer cfg))

  (cp-list [this cfg opts]
    (proto/cp-list checkpointer cfg opts))

  (cp-delete-thread [this thread-id]
    (proto/cp-delete-thread checkpointer thread-id))

  ;; 扩展操作
  (cp-get-next-version [this current channel]
    (proto/cp-get-next-version checkpointer current channel))

  (cp-restore-to-step [this thread-id step]
    (proto/cp-restore-to-step checkpointer thread-id step))

  (cp-get-history [this thread-id]
    (proto/cp-get-history checkpointer thread-id))

  ;; 分支管理
  (cp-create-branch [this thread-id checkpoint-id branch-name]
    (proto/cp-create-branch checkpointer thread-id checkpoint-id branch-name))

  (cp-list-branches [this thread-id]
    (proto/cp-list-branches checkpointer thread-id))

  ;; 清理操作
  (cp-prune [this thread-id opts]
    (proto/cp-prune checkpointer thread-id opts))

  (cp-clear-all [this]
    (proto/cp-clear-all checkpointer)))

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
  "保存状态为 checkpoint（保存到 context-store）

   参数：
   - memory: AgentMemory 实例
   - state: 状态数据
   - opts: 可选参数
     :thread-id 线程 ID
     :metadata 元数据

   返回：checkpoint-id"
  [memory state & {:keys [thread-id metadata]
                   :or {thread-id nil
                        metadata {}}}]
  (let [tid (get-thread-id memory thread-id)
        cfg {:thread-id tid}]
    (proto/cp-put memory cfg state metadata)))

(defn load-state
  "加载 checkpoint

   参数：
   - memory: AgentMemory 实例
   - opts: 可选参数
     :thread-id 线程 ID
     :checkpoint-id 检查点 ID（可选，默认最新）

   返回：checkpoint tuple"
  [memory & {:keys [thread-id checkpoint-id]
             :or {thread-id nil
                  checkpoint-id nil}}]
  (let [tid (get-thread-id memory thread-id)
        cfg {:thread-id tid
             :checkpoint-id checkpoint-id}]
    (proto/cp-get-tuple memory cfg)))

;; -------------------------------------------------------------------------
;; 便捷方法：时间旅行（使用 checkpoint.manager 的方法）
;; -------------------------------------------------------------------------

(defn go-back
  "回退 N 步

   参数：
   - memory: AgentMemory 实例
   - opts: 可选参数
     :thread-id 线程 ID
     :steps 步数（默认 1）

   返回：目标 checkpoint 或 nil"
  [memory & {:keys [thread-id steps]
             :or {thread-id nil
                  steps 1}}]
  (let [tid (get-thread-id memory thread-id)
        cfg {:thread-id tid}
        history (proto/cp-list memory cfg {})
        branch-id @(get-in memory [:checkpointer :current-branch])
        branches @(get-in memory [:checkpointer :branches])
        branch-info (get branches branch-id)
        current-id (:head-checkpoint-id branch-info)
        current-index (when current-id
                        (first (keep-indexed
                                 (fn [i cp]
                                   (when (= (:id cp) current-id)
                                     i))
                                 history)))]

    (when (and current-index
               (< (+ current-index steps) (count history)))
      (let [target-cp (nth history (+ current-index steps))
            target-id (:id target-cp)]
        ;; 更新分支 head
        (swap! branches assoc-in [branch-id :head-checkpoint-id] target-id)
        target-cp))))

(defn go-forward
  "前进 N 步

   参数：
   - memory: AgentMemory 实例
   - opts: 可选参数
     :thread-id 线程 ID
     :steps 步数（默认 1）

   返回：目标 checkpoint 或 nil"
  [memory & {:keys [thread-id steps]
             :or {thread-id nil
                  steps 1}}]
  (let [tid (get-thread-id memory thread-id)
        cfg {:thread-id tid}
        history (proto/cp-list memory cfg {})
        branch-id @(get-in memory [:checkpointer :current-branch])
        branches @(get-in memory [:checkpointer :branches])
        branch-info (get branches branch-id)
        current-id (:head-checkpoint-id branch-info)
        current-index (when current-id
                       (first (keep-indexed
                                (fn [i cp]
                                  (when (= (:id cp) current-id)
                                    i))
                                history)))]

    (when (and current-index
               (>= (- current-index steps) 0))
      (let [target-cp (nth history (- current-index steps))
            target-id (:id target-cp)]
        ;; 更新分支 head
        (swap! branches assoc-in [branch-id :head-checkpoint-id] target-id)
        target-cp))))

(defn goto
  "跳转到指定 checkpoint

   参数：
   - memory: AgentMemory 实例
   - checkpoint-id: 目标 checkpoint ID
   - opts: 可选参数
     :thread-id 线程 ID

   返回：目标 checkpoint 或 nil"
  [memory checkpoint-id & {:keys [thread-id]
                           :or {thread-id nil}}]
  (let [tid (get-thread-id memory thread-id)
        branch-id @(get-in memory [:checkpointer :current-branch])
        branches @(get-in memory [:checkpointer :branches])
        cfg {:thread-id tid
             :checkpoint-id checkpoint-id}
        target-cp (proto/cp-get-tuple memory cfg)]

    (when target-cp
      ;; 更新分支 head
      (swap! branches assoc-in [branch-id :head-checkpoint-id] checkpoint-id)
      target-cp)))

(defn list-history
  "列出历史

   参数：
   - memory: AgentMemory 实例
   - opts: 可选参数
     :thread-id 线程 ID
     :limit 数量限制（默认 100）

   返回：checkpoint 列表"
  [memory & {:keys [thread-id limit]
             :or {thread-id nil
                  limit 100}}]
  (let [tid (get-thread-id memory thread-id)
        cfg {:thread-id tid}]
    (proto/cp-list memory cfg {:limit limit})))

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
     :from-checkpoint 源 checkpoint ID（可选）

   返回：新分支信息"
  [memory branch-id & {:keys [thread-id from-checkpoint]
                       :or {thread-id nil
                            from-checkpoint nil}}]
  (let [tid (get-thread-id memory thread-id)]
    (proto/cp-create-branch memory tid from-checkpoint branch-id)))

(defn switch-branch
  "切换分支

   参数：
   - memory: AgentMemory 实例
   - branch-id: 目标分支 ID
   - opts: 可选参数
     :thread-id 线程 ID

   返回：目标分支的最新 checkpoint 或 nil"
  [memory branch-id & {:keys [thread-id]
                       :or {thread-id nil}}]
  (let [tid (get-thread-id memory thread-id)
        branches @(get-in memory [:checkpointer :branches])
        branch-info (get branches branch-id)]

    (when branch-info
      ;; 切换当前分支
      (reset! (get-in memory [:checkpointer :current-branch]) branch-id)

      ;; 返回该分支的 head
      (let [head-id (:head-checkpoint-id branch-info)]
        (when head-id
          (proto/cp-get-tuple memory {:thread-id tid
                                    :checkpoint-id head-id}))))))

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
    (proto/cp-list-branches memory tid)))

;; -------------------------------------------------------------------------
;; 归档功能（context-store → persistent-store）
;; -------------------------------------------------------------------------

(defn archive-session!
  "将当前会话完整归档到 persistent-store

   归档所有 checkpoint 历史到 persistent-store，并从 context-store 清理。

   参数：
   - memory: AgentMemory 实例
   - opts: 可选参数
     :thread-id 线程 ID
     :summarize-fn 总结函数 (fn [checkpoints] -> summary-string)

   返回：归档的 session-id 或 nil（如果没有 persistent-store）

   示例：
   ;; 基本归档
   (archive-session! memory :thread-id \"user-123\")

   带 AI 摘要
   (archive-session! memory
     :thread-id \"user-123\"
     :summarize-fn (fn [checkpoints]
                    (str \"共 \" (count checkpoints) \" 个检查点\")))"
  [memory & {:keys [thread-id summarize-fn]
             :or {thread-id nil
                  summarize-fn nil}}]
  (when-let [ps (:persistent-store memory)]
    (let [tid (get-thread-id memory thread-id)

          ;; 获取完整历史 ✅
          history (proto/cp-list memory {:thread-id tid} {:limit 10000})

          session-id (str "session-" tid "-" (now))

          ;; 构建归档数据
          archive-data {:session-id session-id
                        :thread-id tid
                        :archived-at (now)
                        :checkpoint-count (count history)
                        :checkpoints history      ; ← 完整历史！
                        :first-checkpoint-id (:id (first history))
                        :last-checkpoint-id (:id (last history))
                        :summary (when summarize-fn
                                   (summarize-fn history))}]

      ;; 归档到 persistent-store
      (proto/kv-put ps ns-archives (archive-key tid) archive-data)

      ;; 清理 context-store
      (proto/cp-delete-thread memory tid)

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
      (proto/kv-get ps ns-archives (archive-key tid)))))

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
    (->> (proto/kv-list-values ps ns-archives {:limit limit})
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
    (proto/kv-put store ns-knowledge key
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
        item (proto/kv-get store ns-knowledge key)]
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
        all-items (proto/kv-list-values store ns-knowledge {:limit limit})]
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
    (proto/kv-delete store ns-knowledge key)))

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
        all-items (proto/kv-list-values store ns-knowledge {:limit 1000})]
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
        tuple (proto/cp-get-tuple memory cfg)
        current-checkpoint (:checkpoint tuple)
        current-messages (or (:messages current-checkpoint) [])
        new-message {:role role
                     :content content
                     :metadata metadata
                     :timestamp (now)}
        updated-checkpoint (assoc current-checkpoint
                                  :messages (conj current-messages new-message))]
    (proto/cp-put memory cfg updated-checkpoint {})
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
        tuple (proto/cp-get-tuple memory cfg)
        checkpoint (:checkpoint tuple)
        messages (or (:messages checkpoint) [])]
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
        tuple (proto/cp-get-tuple memory cfg)
        checkpoint (:checkpoint tuple)
        updated-checkpoint (assoc checkpoint :messages [])]
    (proto/cp-put memory cfg updated-checkpoint {})))

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

   返回：AgentMemory 实例（实现 ICheckpointer 协议）

   示例：
   ;; 单 Store 模式
   (def memory (create-agent-memory
                 :context-store (create-in-memory-store)))

   ;; 双 Store 模式（推荐）
   (def memory (create-agent-memory
                 :context-store (create-in-memory-store)
                 :persistent-store (create-sqlite-store \"archive.db\")))

   ;; 直接用于 create-agent
   (def agent (create-agent provider tools
                 {:checkpointer memory}))"
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
  (let [cp (mgr/create-checkpoint-manager context-store
                                          :initial-branch initial-branch)]
    (->AgentMemory context-store
                  persistent-store
                  cp
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
