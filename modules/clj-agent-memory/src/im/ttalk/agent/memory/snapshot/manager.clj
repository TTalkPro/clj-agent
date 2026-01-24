(ns im.ttalk.agent.memory.snapshot.manager
  "SnapshotManager - 快照管理器

   包含 state 协议验证逻辑。

   专注于 Snapshot 的核心功能：
   ┌─────────────────────────────────────────────────────────┐
   │              SnapshotManager                            │
   │  ┌─────────────────┐      ┌─────────────────────────┐  │
   │  │     store       │      │      branches           │  │
   │  │  (IStore)       │      │  (branch metadata)      │  │
   │  │                 │      │                         │  │
   │  │ <- snapshots    │      │ <- 分支追踪              │  │
   │  │ <- 当前会话状态  │      │ <- lineage 信息          │  │
   │  └─────────────────┘      └─────────────────────────┘  │
   │                                                         │
   │  职责：                                                  │
   │  • 保存/加载 snapshot (save/load)                       │
   │  • 时间旅行 (go-back/go-forward/goto)                   │
   │  • 分支管理 (branch/switch-branch/list-branches)        │
   │  • 历史查询 (list/get-lineage)                          │
   │                                                         │
   │  设计原则：                                              │
   │  • 单一职责：只管理 snapshot，不涉及归档                │
   │  • 无状态：依赖外部 Store，不持有持久化状态             │
   └─────────────────────────────────────────────────────────┘

   使用示例：

   (def ss (create-snapshot-manager
            :store (create-in-memory-store)
            :initial-branch \"main\"))

   ;; 保存状态
   (snap-put ss {:thread-id \"thread-123\"}
            {:state {:messages [...]}}
            {:step 1})

   ;; 加载状态
   (snap-get ss {:thread-id \"thread-123\"})

   ;; 时间旅行
   (go-back! ss {:thread-id \"thread-123\"} :steps 2)
   (go-forward! ss {:thread-id \"thread-123\"} :steps 1)

   ;; 分支管理
   (create-branch! ss {:thread-id \"thread-123\"} \"experiment\")

   ;; 列出历史
   (list-history ss {:thread-id \"thread-123\"} :limit 10)"
  (:require [im.ttalk.agent.memory.protocol :as proto]))

;; =============================================================================
;; 命名空间常量
;; =============================================================================

(def ^:private ns-snapshots "snapshots")
(def ^:private ns-threads "threads")
(def ^:private ns-branches "branches")

;; =============================================================================
;; 辅助函数
;; =============================================================================

(defn- generate-id []
  (str (java.util.UUID/randomUUID)))

(defn- now []
  (System/currentTimeMillis))

(defn- snapshot-key
  "构建快照 key: thread-id:snapshot-id"
  [thread-id snapshot-id]
  (str thread-id ":" snapshot-id))

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
;; SnapshotManager 实现
;; =============================================================================

(defrecord SnapshotManager [store current-branch branches
                             max-snapshots auto-prune?]
  proto/ISnapshotStore

  ;; -------------------------------------------------------------------------
  ;; 核心操作：保存/加载
  ;; -------------------------------------------------------------------------

  (snap-put [this cfg snapshot metadata]
    "保存 snapshot 到 store"
    (let [thread-id (:thread-id cfg)
          snapshot-id (or (:snapshot-id cfg) (generate-id))
          timestamp (now)
          ;; 获取当前分支信息
          branch-id @current-branch
          branch-info (get @branches branch-id)
          parent-id (:head-snapshot-id branch-info)

          ;; 构建 snapshot 记录
          record {:id snapshot-id
                  :thread-id thread-id
                  :snapshot snapshot
                  :metadata (assoc metadata
                              :created-at timestamp
                              :parent-id parent-id
                              :branch-id branch-id)
                  :created-at timestamp}
          key (snapshot-key thread-id snapshot-id)]

      ;; 存储 snapshot
      (proto/put store ns-snapshots key record)

      ;; 更新分支 head
      (swap! branches update branch-id
             (fn [branch]
               (-> branch
                   (assoc :head-snapshot-id snapshot-id)
                   (update :snapshot-count (fnil inc 0)))))

      ;; 更新线程元数据
      (let [thread-meta (proto/get-value store ns-threads (thread-key thread-id))
            snapshot-ids (or (:snapshot-ids thread-meta) [])]
        (proto/put store ns-threads (thread-key thread-id)
                    {:thread-id thread-id
                     :latest-snapshot-id snapshot-id
                     :snapshot-ids (conj snapshot-ids snapshot-id)
                     :updated-at timestamp}))

      ;; 自动清理（如果启用）
      (when (and auto-prune? max-snapshots)
        (auto-prune! this thread-id))

      snapshot-id))

  (snap-put-writes [this cfg writes task-id]
    "保存 pending writes"
    (let [thread-id (:thread-id cfg)
          snapshot-id (:snapshot-id cfg)
          key (str thread-id ":" snapshot-id ":writes")
          existing (or (proto/get-value store "pending-writes" key) [])
          new-writes (conj existing {:task-id task-id
                                     :writes writes
                                     :created-at (now)})]
      (proto/put store "pending-writes" key new-writes)
      true))

  (snap-get [this cfg]
    "获取 snapshot 及相关信息"
    (let [thread-id (:thread-id cfg)
          ;; 获取 snapshot-id（如果没指定，获取最新的）
          snapshot-id (or (:snapshot-id cfg)
                          (let [thread-meta (proto/get-value store ns-threads (thread-key thread-id))]
                            (:latest-snapshot-id thread-meta)))
          key (when snapshot-id (snapshot-key thread-id snapshot-id))]
      (when-let [record (proto/get-value store ns-snapshots key)]
        (let [parent-id (get-in record [:metadata :parent-id])
              pending-writes (proto/get-value store "pending-writes"
                                             (str thread-id ":" snapshot-id ":writes"))]
          {:snapshot (:snapshot record)
           :metadata (:metadata record)
           :parent-config (when parent-id
                            {:thread-id thread-id
                             :snapshot-id parent-id})
           :pending-writes pending-writes}))))

  (snap-list [this cfg opts]
    "列出 snapshot"
    (let [thread-id (:thread-id cfg)
          {:keys [limit before]
           :or {limit 100}} opts
          thread-meta (proto/get-value store ns-threads (thread-key thread-id))
          snapshot-ids (or (:snapshot-ids thread-meta) [])
          ;; 按时间倒序（最新的在前）
          sorted-ids (reverse snapshot-ids)
          ;; 应用 before 过滤
          filtered-ids (if before
                         (drop-while #(not= % before) sorted-ids)
                         sorted-ids)]
      (->> filtered-ids
           (drop (if before 1 0))
           (take limit)
           (map (fn [s-id]
                  (let [key (snapshot-key thread-id s-id)]
                    (when-let [record (proto/get-value store ns-snapshots key)]
                      {:id s-id
                       :snapshot (:snapshot record)
                       :metadata (:metadata record)}))))
           (filter some?))))

  (snap-delete-thread [this thread-id]
    "删除线程的所有 snapshot"
    (let [thread-meta (proto/get-value store ns-threads (thread-key thread-id))
          snapshot-ids (or (:snapshot-ids thread-meta) [])
          count-deleted (count snapshot-ids)]
      ;; 删除所有 snapshot
      (doseq [s-id snapshot-ids]
        (proto/delete store ns-snapshots (snapshot-key thread-id s-id)))
      ;; 删除线程元数据
      (proto/delete store ns-threads (thread-key thread-id))
      count-deleted))

  ;; -------------------------------------------------------------------------
  ;; 扩展操作：时间旅行
  ;; -------------------------------------------------------------------------

  (snap-get-next-version [this current channel]
    "获取下一个版本号"
    (if current (inc current) 1))

  (snap-restore-to-step [this thread-id step]
    "恢复到指定步骤"
    (let [thread-meta (proto/get-value store ns-threads (thread-key thread-id))
          snapshot-ids (or (:snapshot-ids thread-meta) [])]
      (->> snapshot-ids
           (map (fn [s-id]
                  (proto/get-value store ns-snapshots (snapshot-key thread-id s-id))))
           (filter #(= (get-in % [:metadata :step]) step))
           first)))

  (snap-get-history [this thread-id]
    "获取历史记录"
    (let [thread-meta (proto/get-value store ns-threads (thread-key thread-id))
          snapshot-ids (or (:snapshot-ids thread-meta) [])]
      (->> snapshot-ids
           (map (fn [s-id]
                  (when-let [record (proto/get-value store ns-snapshots (snapshot-key thread-id s-id))]
                    {:id s-id
                     :step (get-in record [:metadata :step])
                     :node (get-in record [:metadata :node])
                     :created-at (:created-at record)})))
           (filter some?)
           vec)))

  ;; -------------------------------------------------------------------------
  ;; 分支管理
  ;; -------------------------------------------------------------------------

  (snap-create-branch [this thread-id snapshot-id branch-name]
    "创建新分支"
    (when-let [source-record (proto/get-value store ns-snapshots (snapshot-key thread-id snapshot-id))]
      (let [new-snapshot-id (generate-id)
            new-thread-id (str thread-id "-" branch-name)
            timestamp (now)
            new-branch-id (str current-branch "/" branch-name)
            new-record (-> source-record
                           (assoc :id new-snapshot-id)
                           (assoc :thread-id new-thread-id)
                           (assoc-in [:metadata :branch-name] branch-name)
                           (assoc-in [:metadata :branch-id] new-branch-id)
                           (assoc-in [:metadata :source-snapshot-id] snapshot-id)
                           (assoc :created-at timestamp))]

        ;; 存储新 snapshot
        (proto/put store ns-snapshots (snapshot-key new-thread-id new-snapshot-id) new-record)

        ;; 创建新分支元数据
        (swap! branches assoc new-branch-id
               {:branch-id new-branch-id
                :name branch-name
                :head-snapshot-id new-snapshot-id
                :snapshot-count 1
                :created-at timestamp})

        ;; 创建新线程元数据
        (proto/put store ns-threads (thread-key new-thread-id)
                    {:thread-id new-thread-id
                     :latest-snapshot-id new-snapshot-id
                     :snapshot-ids [new-snapshot-id]
                     :updated-at timestamp})

        ;; 记录分支索引
        (let [branches-list (or (proto/get-value store ns-branches (branch-key thread-id "index")) [])
              branch-info {:branch-id new-branch-id
                           :name branch-name
                           :thread-id new-thread-id
                           :source-snapshot-id snapshot-id
                           :created-at timestamp}]
          (proto/put store ns-branches (branch-key thread-id "index")
                     (conj branches-list branch-info)))

        {:branch-id new-branch-id
         :snapshot-id new-snapshot-id})))

  (snap-list-branches [this thread-id]
    "列出所有分支"
    (or (proto/get-value store ns-branches (branch-key thread-id "index")) []))

  ;; -------------------------------------------------------------------------
  ;; 清理操作
  ;; -------------------------------------------------------------------------

  (snap-prune [this thread-id opts]
    "清理 snapshot（保留指定数量或类型）"
    (let [{:keys [keep-count keep-types]
           :or {keep-count 10
                keep-types #{:initial :final :error}}} opts
          thread-meta (proto/get-value store ns-threads (thread-key thread-id))
          snapshot-ids (or (:snapshot-ids thread-meta) [])
          ;; 获取所有记录
          records (map (fn [s-id]
                         (proto/get-value store ns-snapshots (snapshot-key thread-id s-id)))
                       snapshot-ids)
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
          to-delete-ids (remove all-keep-ids snapshot-ids)]

      ;; 执行删除
      (doseq [s-id to-delete-ids]
        (proto/delete store ns-snapshots (snapshot-key thread-id s-id)))

      ;; 更新线程元数据
      (proto/put store ns-threads (thread-key thread-id)
                  (assoc thread-meta :snapshot-ids (vec (filter all-keep-ids snapshot-ids))))

      {:kept (count all-keep-ids)
       :deleted (count to-delete-ids)}))

  (snap-clear-all [this]
    "清空所有 snapshot"
    (let [thread-values (proto/list-values store ns-threads {:limit 10000})
          total (atom 0)]
      (doseq [tv thread-values]
        (let [thread-id (:thread-id (:value tv))]
          (swap! total + (proto/snap-delete-thread this thread-id))))
      @total)))

;; =============================================================================
;; 自动清理功能
;; =============================================================================

(defn- auto-prune!
  "自动清理超过限制的 snapshot"
  [manager thread-id]
  (let [max-snapshots (:max-snapshots manager)
        history (proto/snap-list manager {:thread-id thread-id} {})
        cnt (count history)]
    (when (> cnt max-snapshots)
      (proto/snap-prune manager thread-id
                       {:keep-count max-snapshots
                        :keep-types #{:initial :final :error}}))))

;; =============================================================================
;; 时间旅行操作（扩展功能）
;; =============================================================================

(defn go-back!
  "回退 N 步"
  [manager cfg & {:keys [steps] :or {steps 1}}]
  (let [history (proto/snap-list manager cfg {})
        branch-id @(:current-branch manager)
        branch-info (get @(:branches manager) branch-id)
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
        (swap! (:branches manager) update branch-id
               assoc :head-snapshot-id target-id)
        target))))

(defn go-forward!
  "前进 N 步"
  [manager cfg & {:keys [steps] :or {steps 1}}]
  (let [history (proto/snap-list manager cfg {})
        branch-id @(:current-branch manager)
        branch-info (get @(:branches manager) branch-id)
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
        (swap! (:branches manager) update branch-id
               assoc :head-snapshot-id target-id)
        target))))

(defn goto!
  "跳转到指定 snapshot"
  [manager cfg snapshot-id]
  (let [thread-id (:thread-id cfg)
        branch-id @(:current-branch manager)
        key (snapshot-key thread-id snapshot-id)
        target (proto/get-value (:store manager) ns-snapshots key)]

    (when target
      (swap! (:branches manager) update branch-id
             assoc :head-snapshot-id snapshot-id)
      target)))

(defn get-lineage
  "获取 snapshot 的祖先链"
  [manager cfg]
  (let [thread-id (:thread-id cfg)
        snapshot-id (:snapshot-id cfg)
        lineage (atom [])]

    (loop [current-id snapshot-id]
      (when current-id
        (let [key (snapshot-key thread-id current-id)
              record (proto/get-value (:store manager) ns-snapshots key)]
          (when record
            (swap! lineage conj record)
            (recur (get-in record [:metadata :parent-id]))))))

    @lineage))

(defn switch-branch!
  "切换到指定分支"
  [manager cfg branch-id]
  (let [branches @(:branches manager)
        branch-info (get branches branch-id)]

    (when branch-info
      (reset! (:current-branch manager) branch-id)
      (let [head-id (:head-snapshot-id branch-info)]
        (when head-id
          (proto/snap-get manager (assoc cfg :snapshot-id head-id)))))))

;; =============================================================================
;; 工厂函数
;; =============================================================================

(defn create-snapshot-manager
  "创建 SnapshotManager

   参数：
   - store: IStore 实例（必需）
   - opts:
     - :initial-branch 初始分支名（默认 \"main\"）
     - :max-snapshots 最大 snapshot 数量（默认 50）
     - :auto-prune? 是否自动清理（默认 true）

   返回：SnapshotManager 实例"
  [store & {:keys [initial-branch max-snapshots auto-prune?]
             :or {initial-branch "main"
                  max-snapshots 50
                  auto-prune? true}}]
  {:pre [(some? store)
         (proto/store? store)]}
  (let [branches (atom {initial-branch {:branch-id initial-branch
                                        :head-snapshot-id nil
                                        :snapshot-count 0
                                        :created-at (now)}})
        current-branch (atom initial-branch)]

    (->SnapshotManager store current-branch branches
                max-snapshots auto-prune?)))

(defn snapshot-manager?
  "检查是否为 SnapshotManager"
  [x]
  (instance? SnapshotManager x))
