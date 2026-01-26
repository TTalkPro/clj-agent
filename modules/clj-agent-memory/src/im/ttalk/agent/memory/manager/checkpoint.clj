(ns im.ttalk.agent.memory.manager.checkpoint
  "Graph Checkpoint Manager 实现

   基于 TimelineManager 实现 IGraphCheckpointManager 协议。

   使用示例：

   (require '[im.ttalk.agent.memory.manager.checkpoint :as cm])
   (require '[im.ttalk.agent.memory.store.memory :as mem])
   (require '[im.ttalk.agent.core.graph.checkpoint :as cp])

   ;; 创建 manager
   (def store (mem/create-memory-store))
   (def manager (cm/create-checkpoint-manager store))

   ;; 使用协议方法
   (cp/save-checkpoint manager \"run-1\" checkpoint)
   (cp/load-latest manager \"run-1\")

   ;; 时间旅行
   (cm/go-back-and-restore manager \"run-1\" 2 :pregel)"
  (:require [im.ttalk.agent.core.graph.checkpoint :as cp]
            [im.ttalk.agent.memory.manager.timeline :as timeline]
            [im.ttalk.agent.memory.protocol :as proto])
  (:import [im.ttalk.agent.core.graph.checkpoint GraphCheckpoint]))

;; =============================================================================
;; GraphCheckpoint 实现 ITimelineEntry
;; =============================================================================

(extend-type GraphCheckpoint
  timeline/ITimelineEntry

  (entry-id [this] (:id this))
  (entry-owner-id [this] (:run-id this))
  (entry-parent-id [this] (:parent-id this))
  (entry-version [this] (:version this))
  (entry-branch-id [this] (:branch-id this))
  (entry-created-at [this] (:created-at this))
  (entry-data [this]
    ;; 返回完整的 checkpoint 数据（除时间线字段外）
    {:graph-name (:graph-name this)
     :superstep (:superstep this)
     :iteration (:iteration this)
     :vertices (:vertices this)
     :pending-activations (:pending-activations this)
     :pending-deltas (:pending-deltas this)
     :global-state (:global-state this)
     :active-vertices (:active-vertices this)
     :completed-vertices (:completed-vertices this)
     :failed-vertices (:failed-vertices this)
     :interrupted-vertices (:interrupted-vertices this)
     :checkpoint-type (:checkpoint-type this)
     :resumable? (:resumable? this)
     :resume-data (:resume-data this)
     :retry-count (:retry-count this)
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
;; GraphCheckpointManager 实现
;; =============================================================================

(defrecord GraphCheckpointManager
  [timeline-manager    ; TimelineManager 实例
   config]             ; 配置

  cp/IGraphCheckpointManager

  ;; -------------------------------------------------------------------------
  ;; 核心操作
  ;; -------------------------------------------------------------------------

  (save-checkpoint [this run-id checkpoint]
    (let [entry (if (instance? GraphCheckpoint checkpoint)
                  checkpoint
                  (cp/map->GraphCheckpoint (assoc checkpoint :run-id run-id)))
          saved (timeline/save timeline-manager entry)]
      (timeline/entry-id saved)))

  (load-checkpoint [this run-id checkpoint-id]
    (timeline/load-by-id timeline-manager run-id checkpoint-id))

  (load-latest [this run-id]
    (timeline/load-latest timeline-manager run-id))

  (list-checkpoints [this run-id opts]
    (timeline/list-entries timeline-manager run-id opts))

  ;; -------------------------------------------------------------------------
  ;; 时间旅行
  ;; -------------------------------------------------------------------------

  (go-back [this run-id steps]
    (timeline/go-back timeline-manager run-id steps))

  (go-forward [this run-id steps]
    (timeline/go-forward timeline-manager run-id steps))

  (goto-checkpoint [this run-id checkpoint-id]
    (timeline/goto timeline-manager run-id checkpoint-id))

  (get-position [this run-id]
    (let [pos (timeline/get-position timeline-manager run-id)]
      {:current-checkpoint-id (:current-entry-id pos)
       :current-index (:current-index pos)
       :total-checkpoints (:total-entries pos)
       :branch-id (:branch-id pos)}))

  ;; -------------------------------------------------------------------------
  ;; 分支管理
  ;; -------------------------------------------------------------------------

  (create-branch [this run-id checkpoint-id branch-name]
    (timeline/create-branch timeline-manager run-id checkpoint-id branch-name))

  (list-branches [this run-id]
    (timeline/list-branches timeline-manager run-id))

  (switch-branch [this run-id branch-id]
    (timeline/switch-branch timeline-manager run-id branch-id))

  ;; -------------------------------------------------------------------------
  ;; Graph 专用操作
  ;; -------------------------------------------------------------------------

  (retry-vertex [this run-id vertex-id]
    (when-let [loaded (cp/load-latest this run-id)]
      (let [updated (-> loaded
                        (update :failed-vertices #(vec (remove #{vertex-id} %)))
                        (update :pending-activations #(vec (distinct (conj (or % []) vertex-id))))
                        (update :retry-count (fnil inc 0))
                        (assoc :checkpoint-type cp/CHECKPOINT-ERROR)
                        (assoc :resumable? true))]
        (cp/save-checkpoint this run-id updated))))

  (retry-all-failed [this run-id]
    (when-let [loaded (cp/load-latest this run-id)]
      (let [failed (:failed-vertices loaded)
            updated (-> loaded
                        (assoc :failed-vertices [])
                        (update :pending-activations #(vec (distinct (concat (or % []) failed))))
                        (update :retry-count (fnil inc 0))
                        (assoc :checkpoint-type cp/CHECKPOINT-ERROR)
                        (assoc :resumable? true))]
        (cp/save-checkpoint this run-id updated))))

  (inject-resume-data [this run-id vertex-id data]
    (when-let [loaded (cp/load-latest this run-id)]
      (let [updated (-> loaded
                        (update :resume-data assoc vertex-id data)
                        (assoc :resumable? true))]
        (cp/save-checkpoint this run-id updated)))))

;; =============================================================================
;; 工厂函数
;; =============================================================================

(defn create-checkpoint-manager
  "创建 GraphCheckpointManager

   参数:
   - store: IKeyValueStore 实例
   - opts:
     - :max-checkpoints 最大检查点数（默认 100）
     - :auto-prune? 是否自动清理（默认 true）

   返回: GraphCheckpointManager 实例"
  [store & {:keys [max-checkpoints auto-prune?]
            :or {max-checkpoints 100
                 auto-prune? true}}]
  {:pre [(some? store)
         (proto/store? store)]}
  (let [timeline-mgr (timeline/create-timeline-manager
                       store
                       :namespace "checkpoints"
                       :max-entries max-checkpoints
                       :auto-prune? auto-prune?)]
    (->GraphCheckpointManager timeline-mgr
                              {:max-checkpoints max-checkpoints
                               :auto-prune? auto-prune?})))

(defn checkpoint-manager?
  "检查是否为 GraphCheckpointManager"
  [x]
  (instance? GraphCheckpointManager x))

;; =============================================================================
;; 历史查询
;; =============================================================================

(defn get-history
  "获取检查点历史

   参数:
   - manager: GraphCheckpointManager
   - run-id: 执行 ID
   - opts: {:limit int :include-data? boolean}

   返回: 历史列表"
  [manager run-id opts]
  (let [{:keys [limit include-data?]
         :or {limit 100 include-data? false}} opts
        entries (cp/list-checkpoints manager run-id {:limit limit})]
    (mapv (fn [checkpoint]
            (cond-> {:id (timeline/entry-id checkpoint)
                     :superstep (:superstep checkpoint)
                     :checkpoint-type (:checkpoint-type checkpoint)
                     :created-at (timeline/entry-created-at checkpoint)
                     :branch-id (timeline/entry-branch-id checkpoint)}
              include-data? (assoc :data (timeline/entry-data checkpoint))))
          entries)))

(defn get-lineage
  "获取检查点的祖先链

   参数:
   - manager: GraphCheckpointManager
   - run-id: 执行 ID
   - checkpoint-id: 起始检查点 ID

   返回: 祖先链列表"
  [manager run-id checkpoint-id]
  (timeline/get-lineage (:timeline-manager manager) run-id checkpoint-id))

;; =============================================================================
;; 便利函数：时间旅行 + 恢复
;; =============================================================================

(defn go-back-and-restore
  "回退 N 步并返回恢复选项

   参数:
   - manager: GraphCheckpointManager
   - run-id: 执行 ID
   - steps: 回退步数
   - engine-type: 引擎类型 :pregel 或 :executor（默认 :pregel）

   返回: 恢复选项或 nil"
  ([manager run-id steps]
   (go-back-and-restore manager run-id steps :pregel))
  ([manager run-id steps engine-type]
   (when-let [checkpoint (cp/go-back manager run-id steps)]
     (cp/to-restore-opts checkpoint engine-type))))

(defn goto-and-restore
  "跳转到指定检查点并返回恢复选项

   参数:
   - manager: GraphCheckpointManager
   - run-id: 执行 ID
   - checkpoint-id: 目标检查点 ID
   - engine-type: 引擎类型 :pregel 或 :executor（默认 :pregel）

   返回: 恢复选项或 nil"
  ([manager run-id checkpoint-id]
   (goto-and-restore manager run-id checkpoint-id :pregel))
  ([manager run-id checkpoint-id engine-type]
   (when-let [checkpoint (cp/goto-checkpoint manager run-id checkpoint-id)]
     (cp/to-restore-opts checkpoint engine-type))))

(defn fork-and-restore
  "创建分支并返回恢复选项

   参数:
   - manager: GraphCheckpointManager
   - run-id: 执行 ID
   - checkpoint-id: 分支起点
   - branch-name: 分支名称
   - engine-type: 引擎类型 :pregel 或 :executor（默认 :pregel）

   返回: {:branch-info :restore-opts}"
  ([manager run-id checkpoint-id branch-name]
   (fork-and-restore manager run-id checkpoint-id branch-name :pregel))
  ([manager run-id checkpoint-id branch-name engine-type]
   (when-let [branch-info (cp/create-branch manager run-id checkpoint-id branch-name)]
     (when-let [checkpoint (cp/load-checkpoint manager run-id checkpoint-id)]
       {:branch-info branch-info
        :restore-opts (cp/to-restore-opts checkpoint engine-type)}))))

;; =============================================================================
;; Pregel 集成便利函数
;; =============================================================================

(defn save-from-pregel
  "从 Pregel 状态直接保存 Checkpoint

   参数:
   - manager: GraphCheckpointManager
   - run-id: 执行 ID
   - pregel-state: Pregel 引擎状态
   - opts: 选项

   返回: checkpoint-id"
  [manager run-id pregel-state opts]
  (let [checkpoint (cp/from-pregel-state pregel-state (assoc opts :run-id run-id))]
    (cp/save-checkpoint manager run-id checkpoint)))
