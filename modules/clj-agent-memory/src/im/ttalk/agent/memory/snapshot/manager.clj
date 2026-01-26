(ns im.ttalk.agent.memory.snapshot.manager
  "SnapshotManager - 快照管理器

   基于 TimelineManager 实现快照管理，专门服务于 Process 框架。

   架构：
   ┌─────────────────────────────────────────────────────────────┐
   │              SnapshotManager                                │
   │  ┌─────────────────────────────────────────────────────┐   │
   │  │     timeline-manager (TimelineManager)              │   │
   │  │     • 版本链管理                                     │   │
   │  │     • 时间旅行                                       │   │
   │  │     • 分支管理                                       │   │
   │  └─────────────────────────────────────────────────────┘   │
   │                                                             │
   │  职责：                                                      │
   │  • 包装 TimelineManager 提供 ISnapshotStore 接口           │
   │  • Process 专用的 snapshot 数据结构                        │
   └─────────────────────────────────────────────────────────────┘

   使用示例：

   (def sm (create-snapshot-manager
            :store (create-in-memory-store)
            :max-snapshots 50))

   ;; 保存快照
   (snap-put sm {:thread-id \"thread-123\"}
            {:state {:messages [...]}}
            {:step 1})

   ;; 加载快照
   (snap-get sm {:thread-id \"thread-123\"})

   ;; 时间旅行
   (go-back! sm {:thread-id \"thread-123\"} :steps 2)
   (go-forward! sm {:thread-id \"thread-123\"} :steps 1)"
  (:require [im.ttalk.agent.memory.protocol :as proto]
            [im.ttalk.agent.memory.timeline.core :as timeline]))

;; =============================================================================
;; ProcessSnapshot - 实现 ITimelineEntry
;; =============================================================================

(defrecord ProcessSnapshot
  [;; 时间线通用字段
   id                    ; 条目 ID
   thread-id             ; 所属者 ID (owner-id)
   parent-id             ; 父条目 ID
   version               ; 版本号
   branch-id             ; 分支 ID
   created-at            ; 创建时间

   ;; Process 专用字段
   snapshot              ; 实际快照数据 {:state ... :channel-values ...}
   metadata])            ; 元数据 {:step :node :source :reason ...}

(extend-type ProcessSnapshot
  timeline/ITimelineEntry

  (entry-id [this] (:id this))
  (entry-owner-id [this] (:thread-id this))
  (entry-parent-id [this] (:parent-id this))
  (entry-version [this] (:version this))
  (entry-branch-id [this] (:branch-id this))
  (entry-created-at [this] (:created-at this))
  (entry-data [this] {:snapshot (:snapshot this)
                      :metadata (:metadata this)})

  (with-entry-id [this id]
    (assoc this :id id))
  (with-parent-id [this parent-id]
    (assoc this :parent-id parent-id))
  (with-version [this version]
    (assoc this :version version))
  (with-branch-id [this branch-id]
    (assoc this :branch-id branch-id))
  (with-created-at [this created-at]
    (assoc this :created-at created-at)))

;; =============================================================================
;; 辅助函数
;; =============================================================================

(defn- generate-id []
  (str (java.util.UUID/randomUUID)))

(defn- now []
  (System/currentTimeMillis))

;; =============================================================================
;; SnapshotManager 实现
;; =============================================================================

(defrecord SnapshotManager
  [timeline-manager      ; TimelineManager 实例
   store                 ; 底层存储（用于 pending-writes）
   max-snapshots         ; 最大快照数
   auto-prune?]          ; 是否自动清理

  proto/ISnapshotStore

  ;; -------------------------------------------------------------------------
  ;; 核心操作：保存/加载
  ;; -------------------------------------------------------------------------

  (snap-put [this cfg snapshot metadata]
    (let [thread-id (:thread-id cfg)
          entry (map->ProcessSnapshot
                  {:id (or (:snapshot-id cfg) (generate-id))
                   :thread-id thread-id
                   :snapshot snapshot
                   :metadata metadata})
          saved-entry (timeline/save timeline-manager entry)]
      (timeline/entry-id saved-entry)))

  (snap-put-writes [this cfg writes task-id]
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
    (let [thread-id (:thread-id cfg)
          snapshot-id (:snapshot-id cfg)
          entry (if snapshot-id
                  (timeline/load-by-id timeline-manager thread-id snapshot-id)
                  (timeline/load-latest timeline-manager thread-id))]
      (when entry
        (let [parent-id (timeline/entry-parent-id entry)
              pending-writes (proto/get-value store "pending-writes"
                                              (str thread-id ":" (timeline/entry-id entry) ":writes"))]
          {:snapshot (:snapshot entry)
           :metadata (:metadata entry)
           :parent-config (when parent-id
                            {:thread-id thread-id
                             :snapshot-id parent-id})
           :pending-writes pending-writes}))))

  (snap-list [this cfg opts]
    (let [thread-id (:thread-id cfg)
          entries (timeline/list-entries timeline-manager thread-id opts)]
      (mapv (fn [entry]
              {:id (timeline/entry-id entry)
               :snapshot (:snapshot entry)
               :metadata (:metadata entry)})
            entries)))

  (snap-delete-thread [this thread-id]
    (timeline/delete-owner timeline-manager thread-id))

  ;; -------------------------------------------------------------------------
  ;; 扩展操作：时间旅行
  ;; -------------------------------------------------------------------------

  (snap-get-next-version [this current channel]
    (if current (inc current) 1))

  (snap-restore-to-step [this thread-id step]
    (let [entries (timeline/list-entries timeline-manager thread-id {:limit 1000})]
      (->> entries
           (filter #(= (get-in % [:metadata :step]) step))
           first)))

  (snap-get-history [this thread-id]
    (let [history (timeline/get-history timeline-manager thread-id {:include-data? true})]
      (mapv (fn [h]
              {:id (:id h)
               :step (get-in h [:data :metadata :step])
               :node (get-in h [:data :metadata :node])
               :created-at (:created-at h)})
            history)))

  ;; -------------------------------------------------------------------------
  ;; 分支管理
  ;; -------------------------------------------------------------------------

  (snap-create-branch [this thread-id snapshot-id branch-name]
    (timeline/create-branch timeline-manager thread-id snapshot-id branch-name))

  (snap-list-branches [this thread-id]
    (timeline/list-branches timeline-manager thread-id))

  ;; -------------------------------------------------------------------------
  ;; 清理操作
  ;; -------------------------------------------------------------------------

  (snap-prune [this thread-id opts]
    (timeline/prune timeline-manager thread-id opts))

  (snap-clear-all [this]
    ;; TODO: 实现清空所有
    0))

;; =============================================================================
;; 时间旅行操作（扩展功能）
;; =============================================================================

(defn go-back!
  "回退 N 步

   参数:
   - manager: SnapshotManager 实例
   - cfg: {:thread-id string}
   - :steps N

   返回: 目标快照或 nil"
  [manager cfg & {:keys [steps] :or {steps 1}}]
  (let [thread-id (:thread-id cfg)
        entry (timeline/go-back (:timeline-manager manager) thread-id steps)]
    (when entry
      {:id (timeline/entry-id entry)
       :snapshot (:snapshot entry)
       :metadata (:metadata entry)})))

(defn go-forward!
  "前进 N 步

   参数:
   - manager: SnapshotManager 实例
   - cfg: {:thread-id string}
   - :steps N

   返回: 目标快照或 nil"
  [manager cfg & {:keys [steps] :or {steps 1}}]
  (let [thread-id (:thread-id cfg)
        entry (timeline/go-forward (:timeline-manager manager) thread-id steps)]
    (when entry
      {:id (timeline/entry-id entry)
       :snapshot (:snapshot entry)
       :metadata (:metadata entry)})))

(defn goto!
  "跳转到指定快照

   参数:
   - manager: SnapshotManager 实例
   - cfg: {:thread-id string}
   - snapshot-id: 目标快照 ID

   返回: 目标快照或 nil"
  [manager cfg snapshot-id]
  (let [thread-id (:thread-id cfg)
        entry (timeline/goto (:timeline-manager manager) thread-id snapshot-id)]
    (when entry
      {:id (timeline/entry-id entry)
       :snapshot (:snapshot entry)
       :metadata (:metadata entry)})))

(defn get-lineage
  "获取快照的祖先链

   参数:
   - manager: SnapshotManager 实例
   - cfg: {:thread-id :snapshot-id}

   返回: 祖先链列表"
  [manager cfg]
  (let [thread-id (:thread-id cfg)
        snapshot-id (:snapshot-id cfg)
        lineage (timeline/get-lineage (:timeline-manager manager) thread-id snapshot-id)]
    (mapv (fn [entry]
            {:id (timeline/entry-id entry)
             :snapshot (:snapshot entry)
             :metadata (:metadata entry)})
          lineage)))

(defn switch-branch!
  "切换到指定分支

   参数:
   - manager: SnapshotManager 实例
   - cfg: {:thread-id string}
   - branch-id: 目标分支 ID

   返回: 分支 head 快照或 nil"
  [manager cfg branch-id]
  (let [thread-id (:thread-id cfg)
        entry (timeline/switch-branch (:timeline-manager manager) thread-id branch-id)]
    (when entry
      {:id (timeline/entry-id entry)
       :snapshot (:snapshot entry)
       :metadata (:metadata entry)})))

(defn get-position
  "获取当前位置

   参数:
   - manager: SnapshotManager 实例
   - cfg: {:thread-id string}

   返回: {:current-snapshot-id :current-version :total-snapshots :branch-id}"
  [manager cfg]
  (let [thread-id (:thread-id cfg)
        pos (timeline/get-position (:timeline-manager manager) thread-id)]
    {:current-snapshot-id (:current-entry-id pos)
     :current-index (:current-index pos)
     :total-snapshots (:total-entries pos)
     :branch-id (:branch-id pos)}))

;; =============================================================================
;; 工厂函数
;; =============================================================================

(defn create-snapshot-manager
  "创建 SnapshotManager

   参数：
   - store: IKeyValueStore 实例（必需）
   - opts:
     - :max-snapshots 最大快照数量（默认 50）
     - :auto-prune? 是否自动清理（默认 true）

   返回：SnapshotManager 实例"
  [store & {:keys [max-snapshots auto-prune?]
            :or {max-snapshots 50
                 auto-prune? true}}]
  {:pre [(some? store)
         (proto/store? store)]}
  (let [timeline-mgr (timeline/create-timeline-manager
                       store
                       :namespace "snapshots"
                       :max-entries max-snapshots
                       :auto-prune? auto-prune?)]
    (->SnapshotManager timeline-mgr store max-snapshots auto-prune?)))

(defn snapshot-manager?
  "检查是否为 SnapshotManager"
  [x]
  (instance? SnapshotManager x))
