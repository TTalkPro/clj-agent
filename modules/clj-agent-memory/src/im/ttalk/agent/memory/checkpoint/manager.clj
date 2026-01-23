(ns im.ttalk.agent.memory.checkpoint.manager
  "CheckpointManager - 检查点管理器

   包含 state 协议验证逻辑。

   专注于 Checkpoint 的核心功能：
   ┌─────────────────────────────────────────────────────────┐
   │              CheckpointManager                          │
   │  ┌─────────────────┐      ┌─────────────────────────┐  │
   │  │     store       │      │      branches           │  │
   │  │  (IStore)       │      │  (branch metadata)      │  │
   │  │                 │      │                         │  │
   │  │ ← checkpoints   │      │ ← 分支追踪              │  │
   │  │ ← 当前会话状态  │      │ ← lineage 信息          │  │
   │  └─────────────────┘      └─────────────────────────┘  │
   │                                                         │
   │  职责：                                                  │
   │  • 保存/加载 checkpoint (save/load)                     │
   │  • 时间旅行 (go-back/go-forward/goto)                   │
   │  • 分支管理 (branch/switch-branch/list-branches)        │
   │  • 历史查询 (list/get-lineage)                          │
   │                                                         │
   │  设计原则：                                              │
   │  • 单一职责：只管理 checkpoint，不涉及归档              │
   │  • 无状态：依赖外部 Store，不持有持久化状态             │
   │  • 与 Lisp 版本对齐：类似 checkpoint-manager.lisp       │
   └─────────────────────────────────────────────────────────┘

   使用示例：

   (def cp (create-checkpoint-manager
            :store (create-in-memory-store)
            :initial-branch \"main\"))

   ;; 保存状态
   (cp-put! cp {:thread-id \"thread-123\"}
            {:state {:messages [...]}}
            {:step 1})

   ;; 加载状态
   (cp-get cp {:thread-id \"thread-123\"})

   ;; 时间旅行
   (go-back! cp {:thread-id \"thread-123\"} :steps 2)
   (go-forward! cp {:thread-id \"thread-123\"} :steps 1)

   ;; 分支管理
   (create-branch! cp {:thread-id \"thread-123\"} \"experiment\")

   ;; 列出历史
   (list-history cp {:thread-id \"thread-123\"} :limit 10)"
  (:require [im.ttalk.agent.memory.protocol :as proto]))

;; =============================================================================
;; 命名空间常量
;; =============================================================================

(def ^:private ns-checkpoints "checkpoints")
(def ^:private ns-threads "threads")
(def ^:private ns-branches "branches")

;; =============================================================================
;; 辅助函数
;; =============================================================================

(defn- generate-id []
  (str (java.util.UUID/randomUUID)))

(defn- now []
  (System/currentTimeMillis))

(defn- checkpoint-key
  "构建检查点 key: thread-id:checkpoint-id"
  [thread-id checkpoint-id]
  (str thread-id ":" checkpoint-id))

(defn- thread-key
  "构建线程元数据 key"
  [thread-id]
  (str "thread:" thread-id))

(defn- branch-key
  "构建分支 key"
  [thread-id branch-name]
  (str thread-id ":" branch-name))

;; 前向声明
(declare auto-prune!)

;; =============================================================================
;; CheckpointManager 实现
;; =============================================================================

(defrecord CheckpointManager [store current-branch branches
                               max-checkpoints auto-prune?]
  proto/ICheckpointer

  ;; -------------------------------------------------------------------------
  ;; 核心操作：保存/加载
  ;; -------------------------------------------------------------------------

  (cp-put [this cfg checkpoint metadata]
    "保存 checkpoint 到 store"
    (let [thread-id (:thread-id cfg)
          checkpoint-id (or (:checkpoint-id cfg) (generate-id))
          timestamp (now)
          ;; 获取当前分支信息
          branch-id @current-branch
          branch-info (get @branches branch-id)
          parent-id (:head-checkpoint-id branch-info)

          ;; 构建 checkpoint 记录
          record {:id checkpoint-id
                  :thread-id thread-id
                  :checkpoint checkpoint
                  :metadata (assoc metadata
                              :created-at timestamp
                              :parent-id parent-id
          ;; 验证 checkpoint 中的 state 格式 ✅
          (let [state (:checkpoint checkpoint)]
            (when (or (nil? state)
                      (not (map? state)))
                  (throw (ex-info "State 必须是一个 map"
                                     {:checkpoint-id checkpoint-id
                                      :thread-id thread-id
                                      :state (type state)})))
          
            (when-not (contains? state :messages)
              (throw (ex-info "State 必须包含 :messages 字段"
                                     {:checkpoint-id checkpoint-id
                                     :thread-id thread-id
                                     :state state})))
          
            (when-not (contains? state :scratchpad)
              (throw (ex-info "State 必须包含 :scratchpad 字段"
                                     {:checkpoint-id checkpoint-id
                                     :thread-id thread-id
                                     :state state}))))                              :branch-id branch-id)
                  :created-at timestamp}
          key (checkpoint-key thread-id checkpoint-id)]

      ;; 存储 checkpoint
      (proto/kv-put store ns-checkpoints key record)

      ;; 更新分支 head
      (swap! branches update branch-id
             (fn [branch]
               (-> branch
                   (assoc :head-checkpoint-id checkpoint-id)
                   (update :checkpoint-count (fnil inc 0)))))

      ;; 更新线程元数据
      (let [thread-meta (proto/kv-get store ns-threads (thread-key thread-id))
            checkpoint-ids (or (:checkpoint-ids thread-meta) [])]
        (proto/kv-put store ns-threads (thread-key thread-id)
                      {:thread-id thread-id
                       :latest-checkpoint-id checkpoint-id
                       :checkpoint-ids (conj checkpoint-ids checkpoint-id)
                       :updated-at timestamp}))

      ;; 自动清理（如果启用）
      (when (and auto-prune? max-checkpoints)
        (auto-prune! this thread-id))

      checkpoint-id))

  (cp-put-writes [this cfg writes task-id]
    "保存 pending writes（用于编译器）"
    (let [thread-id (:thread-id cfg)
          checkpoint-id (:checkpoint-id cfg)
          key (str thread-id ":" checkpoint-id ":writes")
          existing (or (proto/kv-get store "pending-writes" key) [])
          new-writes (conj existing {:task-id task-id
                                     :writes writes
                                     :created-at (now)})]
      (proto/kv-put store "pending-writes" key new-writes)
      true))

  (cp-get-tuple [this cfg]
    "获取 checkpoint 及相关信息"
    (let [thread-id (:thread-id cfg)
          ;; 获取 checkpoint-id（如果没指定，获取最新的）
          checkpoint-id (or (:checkpoint-id cfg)
                            (let [thread-meta (proto/kv-get store ns-threads (thread-key thread-id))]
                              (:latest-checkpoint-id thread-meta)))
          key (when checkpoint-id (checkpoint-key thread-id checkpoint-id))]
      (when-let [record (proto/kv-get store ns-checkpoints key)]
        (let [parent-id (get-in record [:metadata :parent-id])
              pending-writes (proto/kv-get store "pending-writes"
                                           (str thread-id ":" checkpoint-id ":writes"))]
          {:checkpoint (:checkpoint record)
           :metadata (:metadata record)
           :parent-config (when parent-id
                            {:thread-id thread-id
                             :checkpoint-id parent-id})
           :pending-writes pending-writes}))))

  (cp-list [this cfg opts]
    "列出 checkpoint"
    (let [thread-id (:thread-id cfg)
          {:keys [limit before]
           :or {limit 100}} opts
          thread-meta (proto/kv-get store ns-threads (thread-key thread-id))
          checkpoint-ids (or (:checkpoint-ids thread-meta) [])
          ;; 按时间倒序（最新的在前）
          sorted-ids (reverse checkpoint-ids)
          ;; 应用 before 过滤
          filtered-ids (if before
                         (drop-while #(not= % before) sorted-ids)
                         sorted-ids)]
      (->> filtered-ids
           (drop (if before 1 0))
           (take limit)
           (map (fn [cp-id]
                  (let [key (checkpoint-key thread-id cp-id)]
                    (when-let [record (proto/kv-get store ns-checkpoints key)]
                      {:id cp-id
                       :checkpoint (:checkpoint record)
                       :metadata (:metadata record)}))))
           (filter some?))))

  (cp-delete-thread [this thread-id]
    "删除线程的所有 checkpoint"
    (let [thread-meta (proto/kv-get store ns-threads (thread-key thread-id))
          checkpoint-ids (or (:checkpoint-ids thread-meta) [])
          count-deleted (count checkpoint-ids)]
      ;; 删除所有 checkpoint
      (doseq [cp-id checkpoint-ids]
        (proto/kv-delete store ns-checkpoints (checkpoint-key thread-id cp-id)))
      ;; 删除线程元数据
      (proto/kv-delete store ns-threads (thread-key thread-id))
      count-deleted))

  ;; -------------------------------------------------------------------------
  ;; 扩展操作：时间旅行
  ;; -------------------------------------------------------------------------

  (cp-get-next-version [this current channel]
    "获取下一个版本号"
    (if current (inc current) 1))

  (cp-restore-to-step [this thread-id step]
    "恢复到指定步骤"
    (let [thread-meta (proto/kv-get store ns-threads (thread-key thread-id))
          checkpoint-ids (or (:checkpoint-ids thread-meta) [])]
      (->> checkpoint-ids
           (map (fn [cp-id]
                  (proto/kv-get store ns-checkpoints (checkpoint-key thread-id cp-id))))
           (filter #(= (get-in % [:metadata :step]) step))
           first)))

  (cp-get-history [this thread-id]
    "获取历史记录"
    (let [thread-meta (proto/kv-get store ns-threads (thread-key thread-id))
          checkpoint-ids (or (:checkpoint-ids thread-meta) [])]
      (->> checkpoint-ids
           (map (fn [cp-id]
                  (when-let [record (proto/kv-get store ns-checkpoints (checkpoint-key thread-id cp-id))]
                    {:id cp-id
                     :step (get-in record [:metadata :step])
                     :node (get-in record [:metadata :node])
                     :created-at (:created-at record)})))
           (filter some?)
           vec)))

  ;; -------------------------------------------------------------------------
  ;; 分支管理
  ;; -------------------------------------------------------------------------

  (cp-create-branch [this thread-id checkpoint-id branch-name]
    "创建新分支"
    (when-let [source-record (proto/kv-get store ns-checkpoints (checkpoint-key thread-id checkpoint-id))]
      (let [new-checkpoint-id (generate-id)
            new-thread-id (str thread-id "-" branch-name)
            timestamp (now)
            new-branch-id (str current-branch "/" branch-name)
            new-record (-> source-record
                           (assoc :id new-checkpoint-id)
                           (assoc :thread-id new-thread-id)
                           (assoc-in [:metadata :branch-name] branch-name)
                           (assoc-in [:metadata :branch-id] new-branch-id)
                           (assoc-in [:metadata :source-checkpoint-id] checkpoint-id)
                           (assoc :created-at timestamp))]

        ;; 存储新 checkpoint
        (proto/kv-put store ns-checkpoints (checkpoint-key new-thread-id new-checkpoint-id) new-record)

        ;; 创建新分支元数据
        (swap! branches assoc new-branch-id
               {:branch-id new-branch-id
                :name branch-name
                :head-checkpoint-id new-checkpoint-id
                :checkpoint-count 1
                :created-at timestamp})

        ;; 创建新线程元数据
        (proto/kv-put store ns-threads (thread-key new-thread-id)
                      {:thread-id new-thread-id
                       :latest-checkpoint-id new-checkpoint-id
                       :checkpoint-ids [new-checkpoint-id]
                       :updated-at timestamp})

        ;; 记录分支索引
        (let [branches-list (or (proto/kv-get store ns-branches (branch-key thread-id "index")) [])
              branch-info {:branch-id new-branch-id
                           :name branch-name
                           :thread-id new-thread-id
                           :source-checkpoint-id checkpoint-id
                           :created-at timestamp}]
          (proto/kv-put store ns-branches (branch-key thread-id "index")
                       (conj branches-list branch-info)))

        {:branch-id new-branch-id
         :checkpoint-id new-checkpoint-id})))

  (cp-list-branches [this thread-id]
    "列出所有分支"
    (or (proto/kv-get store ns-branches (branch-key thread-id "index")) []))

  ;; -------------------------------------------------------------------------
  ;; 清理操作
  ;; -------------------------------------------------------------------------

  (cp-prune [this thread-id opts]
    "清理 checkpoint（保留指定数量或类型）"
    (let [{:keys [keep-count keep-types]
           :or {keep-count 10
                keep-types #{:initial :final :error}}} opts
          thread-meta (proto/kv-get store ns-threads (thread-key thread-id))
          checkpoint-ids (or (:checkpoint-ids thread-meta) [])
          ;; 获取所有记录
          records (map (fn [cp-id]
                         (proto/kv-get store ns-checkpoints (checkpoint-key thread-id cp-id)))
                       checkpoint-ids)
          ;; 按时间排序（最新在前）
          sorted-records (reverse records)
          ;; 保留策略
          to-keep (take keep-count sorted-records)
          keep-ids (set (map :id to-keep))
          ;; 额外保留特定类型
          type-keep-ids (->> sorted-records
                             (filter #(contains? keep-types (get-in % [:metadata :type])))
                             (map :id)
                             set)
          all-keep-ids (clojure.set/union keep-ids type-keep-ids)
          to-delete-ids (remove all-keep-ids checkpoint-ids)]

      ;; 执行删除
      (doseq [cp-id to-delete-ids]
        (proto/kv-delete store ns-checkpoints (checkpoint-key thread-id cp-id)))

      ;; 更新线程元数据
      (proto/kv-put store ns-threads (thread-key thread-id)
                    (assoc thread-meta :checkpoint-ids (vec (filter all-keep-ids checkpoint-ids))))

      {:kept (count all-keep-ids)
       :deleted (count to-delete-ids)}))

  (cp-clear-all [this]
    "清空所有 checkpoint"
    (let [thread-values (proto/kv-list-values store ns-threads {:limit 10000})
          total (atom 0)]
      (doseq [tv thread-values]
        (let [thread-id (:thread-id (:value tv))]
          (swap! total + (proto/cp-delete-thread this thread-id))))
      @total)))

;; =============================================================================
;; 自动清理功能
;; =============================================================================

(defn- auto-prune!
  "自动清理超过限制的 checkpoint

   保留策略：
   - 保留最新的 max-checkpoints 个
   - 额外保留重要类型（:initial, :final, :error）

   参数：
   - manager: CheckpointManager
   - thread-id: 线程 ID"
  [manager thread-id]
  (let [max-checkpoints (:max-checkpoints manager)
        history (proto/cp-list manager {:thread-id thread-id} {})
        count (count history)]
    (when (> count max-checkpoints)
      ;; 超过限制，执行清理
      (proto/cp-prune manager thread-id
                       {:keep-count max-checkpoints
                        :keep-types #{:initial :final :error}}))))

;; =============================================================================
;; 时间旅行操作（扩展功能）
;; =============================================================================

(defn go-back!
  "回退 N 步

   参数：
   - manager: CheckpointManager
   - cfg: 配置 {:thread-id ...}
   - opts: {:steps N}

   返回：目标 checkpoint 或 nil"
  [manager cfg & {:keys [steps] :or {steps 1}}]
  (let [thread-id (:thread-id cfg)
          history (proto/cp-list manager cfg {})
          branch-id (:current-branch manager)
          branch-info (get (:branches manager) branch-id)
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
        (swap! (:branches manager) update branch-id
               assoc :head-checkpoint-id target-id)
        target-cp))))

(defn go-forward!
  "前进 N 步

   参数：
   - manager: CheckpointManager
   - cfg: 配置 {:thread-id ...}
   - opts: {:steps N}

   返回：目标 checkpoint 或 nil"
  [manager cfg & {:keys [steps] :or {steps 1}}]
  (let [thread-id (:thread-id cfg)
          history (proto/cp-list manager cfg {})
          branch-id (:current-branch manager)
          branch-info (get (:branches manager) branch-id)
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
        (swap! (:branches manager) update branch-id
               assoc :head-checkpoint-id target-id)
        target-cp))))

(defn goto!
  "跳转到指定 checkpoint

   参数：
   - manager: CheckpointManager
   - cfg: 配置 {:thread-id ...}
   - checkpoint-id: 目标 checkpoint ID

   返回：目标 checkpoint 或 nil"
  [manager cfg checkpoint-id]
  (let [thread-id (:thread-id cfg)
          branch-id (:current-branch manager)
          key (checkpoint-key thread-id checkpoint-id)
          target-cp (proto/kv-get (:store manager) ns-checkpoints key)]

    (when target-cp
      ;; 更新分支 head
      (swap! (:branches manager) update branch-id
             assoc :head-checkpoint-id checkpoint-id)
      target-cp)))

(defn get-lineage
  "获取 checkpoint 的祖先链

   参数：
   - manager: CheckpointManager
   - cfg: 配置 {:thread-id ... :checkpoint-id ...}

   返回：祖先链（从最新到最早）"
  [manager cfg]
  (let [thread-id (:thread-id cfg)
          checkpoint-id (:checkpoint-id cfg)
          lineage (atom [])]

    ;; 追踪祖先
    (loop [current-id checkpoint-id]
      (when current-id
        (let [key (checkpoint-key thread-id current-id)
              record (proto/kv-get (:store manager) ns-checkpoints key)]
          (when record
            (swap! lineage conj record)
            (recur (get-in record [:metadata :parent-id]))))))

    @lineage))

(defn switch-branch!
  "切换到指定分支

   参数：
   - manager: CheckpointManager
   - cfg: 配置 {:thread-id ...}
   - branch-id: 目标分支 ID

   返回：目标分支的最新 checkpoint 或 nil"
  [manager cfg branch-id]
  (let [branches (:branches manager)
          branch-info (get branches branch-id)]

    (when branch-info
      ;; 切换当前分支
      (reset! (:current-branch manager) branch-id)

      ;; 返回该分支的 head
      (let [head-id (:head-checkpoint-id branch-info)]
        (when head-id
          (proto/cp-get-tuple manager (assoc cfg :checkpoint-id head-id)))))))

;; =============================================================================
;; 工厂函数
;; =============================================================================

(defn create-checkpoint-manager
  "创建 CheckpointManager

   参数：
   - store: IStore 实例（必需）
   - opts:
     - :initial-branch 初始分支名（默认 \"main\"）
     - :max-checkpoints 最大 checkpoint 数量（默认 nil，无限制）
     - :auto-prune? 是否自动清理（默认 true，当 max-checkpoints 存在时）

   返回：CheckpointManager 实例

   示例：
   ;; 无限制模式
   (create-checkpoint-manager
     (create-in-memory-store))

   ;; 限制 100 个，自动清理
   (create-checkpoint-manager
     (create-in-memory-store)
     :max-checkpoints 100
     :auto-prune? true)"
  [store & {:keys [initial-branch max-checkpoints auto-prune?]
             :or {initial-branch "main"
                  max-checkpoints 50
                  auto-prune? true}}]
  {:pre [(some? store)
         (proto/store? store)]}
  (let [branches (atom {initial-branch {:branch-id initial-branch
                                        :head-checkpoint-id nil
                                        :checkpoint-count 0
                                        :created-at (now)}})
        current-branch (atom initial-branch)]

    (->CheckpointManager store current-branch branches
                  max-checkpoints auto-prune?)))

(defn checkpoint-manager?
  "检查是否为 CheckpointManager"
  [x]
  (instance? CheckpointManager x))
