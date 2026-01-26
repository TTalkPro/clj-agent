(ns im.ttalk.agent.core.graph.checkpoint
  "Graph Checkpoint - Graph/Pregel 框架检查点管理

   专门为 Graph/Pregel 框架设计的检查点管理。

   架构：
   ┌─────────────────────────────────────────────────────────────┐
   │              GraphCheckpointManager                         │
   │  ┌─────────────────────────────────────────────────────┐   │
   │  │     timeline-manager (TimelineManager)              │   │
   │  │     • 版本链管理                                     │   │
   │  │     • 时间旅行                                       │   │
   │  │     • 分支管理                                       │   │
   │  └─────────────────────────────────────────────────────┘   │
   │                                                             │
   │  Graph 专用能力：                                            │
   │  • superstep 级别的状态保存                                 │
   │  • 顶点状态追踪（active/completed/failed/interrupted）     │
   │  • 失败重试支持                                             │
   │  • 中断恢复支持                                             │
   │  • Pregel 引擎集成                                          │
   └─────────────────────────────────────────────────────────────┘

   使用示例：

   ;; 创建 CheckpointManager
   (def cm (create-checkpoint-manager store))

   ;; 从 Pregel 状态保存
   (save-from-pregel cm \"run-1\" pregel-state {:checkpoint-type :superstep})

   ;; 时间旅行
   (go-back cm \"run-1\" 2)

   ;; 失败重试
   (retry-vertex cm \"run-1\" :failed-vertex-id)

   ;; 恢复执行
   (def restore-opts (to-pregel-restore-opts checkpoint))"
  (:require [im.ttalk.agent.memory.timeline.core :as timeline]
            [im.ttalk.agent.memory.protocol :as proto]))

;; =============================================================================
;; Checkpoint 类型常量
;; =============================================================================

(def CHECKPOINT-INITIAL :initial)
(def CHECKPOINT-SUPERSTEP :superstep)
(def CHECKPOINT-ERROR :error)
(def CHECKPOINT-INTERRUPT :interrupt)
(def CHECKPOINT-FINAL :final)

;; =============================================================================
;; GraphCheckpoint - 实现 ITimelineEntry
;; =============================================================================

(defrecord GraphCheckpoint
  [;; 时间线通用字段
   id                      ; 条目 ID
   run-id                  ; 执行 ID (owner-id)
   parent-id               ; 父条目 ID
   version                 ; 版本号
   branch-id               ; 分支 ID
   created-at              ; 创建时间

   ;; Graph/Pregel 专用字段
   graph-name              ; 图名称
   superstep               ; 当前超步
   iteration               ; 迭代次数

   ;; 顶点状态
   vertices                ; {vertex-id {:value :active :messages :halt-voted}}
   pending-activations     ; 待激活顶点 [vertex-id ...]
   pending-deltas          ; 延迟提交 [{:vertex-id :delta} ...]
   global-state            ; 全局状态

   ;; 顶点分类
   active-vertices         ; 活跃顶点
   completed-vertices      ; 已完成顶点
   failed-vertices         ; 失败顶点（可重试）
   interrupted-vertices    ; 中断顶点（等待输入）

   ;; 恢复信息
   checkpoint-type         ; :initial/:superstep/:error/:interrupt/:final
   resumable?              ; 是否可恢复
   resume-data             ; {vertex-id data}
   retry-count             ; 重试计数

   ;; 扩展
   metadata])

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
;; 辅助函数
;; =============================================================================

(defn- generate-id
  "生成唯一 ID"
  []
  (str "cp-" (java.util.UUID/randomUUID)))

(defn- now
  "获取当前时间戳"
  []
  (System/currentTimeMillis))

;; =============================================================================
;; IGraphCheckpointManager 协议
;; =============================================================================

(defprotocol IGraphCheckpointManager
  "Graph Checkpoint 管理协议"

  ;; 核心操作
  (save-checkpoint [this run-id checkpoint]
    "保存检查点，返回 checkpoint-id")

  (load-checkpoint [this run-id checkpoint-id]
    "加载指定检查点")

  (load-latest [this run-id]
    "加载最新检查点")

  (list-checkpoints [this run-id opts]
    "列出检查点历史")

  ;; 时间旅行
  (go-back [this run-id steps]
    "回退 N 个超步")

  (go-forward [this run-id steps]
    "前进 N 个超步")

  (goto-checkpoint [this run-id checkpoint-id]
    "跳转到指定检查点")

  (get-position [this run-id]
    "获取当前位置")

  ;; 分支管理
  (create-branch [this run-id checkpoint-id branch-name]
    "从检查点创建分支")

  (list-branches [this run-id]
    "列出所有分支")

  (switch-branch [this run-id branch-id]
    "切换到指定分支")

  ;; Graph 专用操作
  (retry-vertex [this run-id vertex-id]
    "标记单个顶点重试（从失败移到待激活）")

  (retry-all-failed [this run-id]
    "标记所有失败顶点重试")

  (inject-resume-data [this run-id vertex-id data]
    "注入恢复数据"))

;; =============================================================================
;; GraphCheckpointManager 实现
;; =============================================================================

(defrecord GraphCheckpointManager
  [timeline-manager    ; TimelineManager 实例
   config]             ; 配置

  IGraphCheckpointManager

  ;; -------------------------------------------------------------------------
  ;; 核心操作
  ;; -------------------------------------------------------------------------

  (save-checkpoint [this run-id checkpoint]
    (let [entry (if (instance? GraphCheckpoint checkpoint)
                  checkpoint
                  (map->GraphCheckpoint (assoc checkpoint :run-id run-id)))
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
    (when-let [cp (load-latest this run-id)]
      (let [updated (-> cp
                        (update :failed-vertices #(vec (remove #{vertex-id} %)))
                        (update :pending-activations #(vec (distinct (conj (or % []) vertex-id))))
                        (update :retry-count (fnil inc 0))
                        (assoc :checkpoint-type CHECKPOINT-ERROR)
                        (assoc :resumable? true))]
        (save-checkpoint this run-id updated))))

  (retry-all-failed [this run-id]
    (when-let [cp (load-latest this run-id)]
      (let [failed (:failed-vertices cp)
            updated (-> cp
                        (assoc :failed-vertices [])
                        (update :pending-activations #(vec (distinct (concat (or % []) failed))))
                        (update :retry-count (fnil inc 0))
                        (assoc :checkpoint-type CHECKPOINT-ERROR)
                        (assoc :resumable? true))]
        (save-checkpoint this run-id updated))))

  (inject-resume-data [this run-id vertex-id data]
    (when-let [cp (load-latest this run-id)]
      (let [updated (-> cp
                        (update :resume-data assoc vertex-id data)
                        (assoc :resumable? true))]
        (save-checkpoint this run-id updated)))))

;; =============================================================================
;; Checkpoint 访问器
;; =============================================================================

(defn get-superstep
  "获取超步号"
  [checkpoint]
  (:superstep checkpoint))

(defn get-iteration
  "获取迭代次数"
  [checkpoint]
  (:iteration checkpoint))

(defn get-vertices
  "获取所有顶点"
  [checkpoint]
  (:vertices checkpoint))

(defn get-vertex-state
  "获取单个顶点状态"
  [checkpoint vertex-id]
  (get-in checkpoint [:vertices vertex-id]))

(defn get-global-state
  "获取全局状态"
  [checkpoint]
  (:global-state checkpoint))

(defn get-pending-activations
  "获取待激活顶点"
  [checkpoint]
  (:pending-activations checkpoint))

(defn get-pending-deltas
  "获取延迟提交的 delta"
  [checkpoint]
  (:pending-deltas checkpoint))

(defn get-active-vertices
  "获取活跃顶点"
  [checkpoint]
  (:active-vertices checkpoint))

(defn get-completed-vertices
  "获取已完成顶点"
  [checkpoint]
  (:completed-vertices checkpoint))

(defn get-failed-vertices
  "获取失败顶点"
  [checkpoint]
  (:failed-vertices checkpoint))

(defn get-interrupted-vertices
  "获取中断顶点"
  [checkpoint]
  (:interrupted-vertices checkpoint))

(defn get-checkpoint-type
  "获取检查点类型"
  [checkpoint]
  (:checkpoint-type checkpoint))

(defn resumable?
  "是否可恢复"
  [checkpoint]
  (:resumable? checkpoint))

(defn get-resume-data
  "获取恢复数据"
  [checkpoint]
  (:resume-data checkpoint))

(defn get-retry-count
  "获取重试计数"
  [checkpoint]
  (:retry-count checkpoint))

;; =============================================================================
;; Pregel 集成
;; =============================================================================

(defn from-pregel-state
  "从 Pregel 执行状态创建 Checkpoint

   参数:
   - pregel-state: Pregel 引擎的执行状态
     {:superstep :vertices :global-state :pending-messages :failed-vertices ...}
   - opts: 选项
     {:checkpoint-type :graph-name :run-id}

   返回: GraphCheckpoint"
  [pregel-state opts]
  (let [{:keys [superstep vertices global-state
                pending-messages active-vertices failed-vertices]} pregel-state
        {:keys [checkpoint-type graph-name run-id iteration]
         :or {checkpoint-type CHECKPOINT-SUPERSTEP
              iteration 0}} opts
        ;; 计算顶点分类
        failed-vtx-set (set (or failed-vertices []))
        active-vtx (or active-vertices
                       (vec (keys (filter (fn [[_ v]] (:active v)) vertices))))
        completed-vtx (vec (keys (filter (fn [[_ v]]
                                           (and (not (:active v))
                                                (:halt-voted v)
                                                (not (contains? failed-vtx-set _))))
                                         vertices)))
        ;; 待激活 = 有消息的顶点 + 失败顶点（用于重试）
        pending-activations (vec (distinct (concat (keys pending-messages)
                                                    (or failed-vertices []))))]
    (map->GraphCheckpoint
      {:id (generate-id)
       :run-id run-id
       :graph-name graph-name
       :superstep (or superstep 0)
       :iteration iteration
       :vertices vertices
       :pending-activations pending-activations
       :pending-deltas nil
       :global-state global-state
       :active-vertices active-vtx
       :completed-vertices completed-vtx
       :failed-vertices (vec (or failed-vertices []))
       :interrupted-vertices []
       :checkpoint-type checkpoint-type
       :resumable? (contains? #{CHECKPOINT-INTERRUPT CHECKPOINT-ERROR}
                              checkpoint-type)
       :resume-data {}
       :retry-count 0
       :metadata (:metadata opts)})))

(defn to-pregel-restore-opts
  "转换为 Pregel 恢复选项

   参数:
   - checkpoint: GraphCheckpoint
   - opts: 可选参数
     :retry-failed-only? - 是否只重试失败顶点（默认 false）

   返回: Pregel run/run-simple 的恢复参数
   使用方式:
   (let [opts (to-pregel-restore-opts checkpoint)]
     (pregel/run-simple (:vertices opts)
                        :initial-global-state (:initial-global-state opts)
                        :resume-superstep (:resume-superstep opts)
                        :initial-pending-messages (:initial-pending-messages opts)
                        :resume-data (:resume-data opts)))"
  ([checkpoint]
   (to-pregel-restore-opts checkpoint {}))
  ([checkpoint {:keys [retry-failed-only?] :or {retry-failed-only? false}}]
   (let [;; 决定要激活哪些顶点
         vertices-to-activate (if retry-failed-only?
                                (:failed-vertices checkpoint)
                                (:pending-activations checkpoint))]
     {:vertices (:vertices checkpoint)
      :initial-global-state (:global-state checkpoint)
      :resume-superstep (:superstep checkpoint)
      :initial-pending-messages (reduce
                                  (fn [acc vid]
                                    (assoc acc vid []))  ; 待激活转为空消息列表以激活
                                  {}
                                  vertices-to-activate)
      :resume-data (:resume-data checkpoint)
      :failed-vertices (:failed-vertices checkpoint)})))

(defn to-pregel-retry-failed-opts
  "转换为 Pregel 恢复选项（只重试失败顶点）

   这是 to-pregel-restore-opts 的便捷函数，专门用于重试失败顶点场景。

   参数:
   - checkpoint: GraphCheckpoint

   返回: Pregel run/run-simple 的恢复参数（只激活失败顶点）"
  [checkpoint]
  (to-pregel-restore-opts checkpoint {:retry-failed-only? true}))

(defn to-executor-restore-opts
  "转换为 Graph Executor 恢复选项

   参数:
   - checkpoint: GraphCheckpoint

   返回: Executor run 的恢复参数
   使用方式:
   (let [opts (to-executor-restore-opts checkpoint)]
     (executor/run graph-spec nil  ; initial-state 被 resume-state 覆盖
                   :resume-state (:resume-state opts)
                   :resume-iteration (:resume-iteration opts)
                   :initial-activations (:initial-activations opts)))"
  [checkpoint]
  {:resume-state (:global-state checkpoint)
   :resume-iteration (:iteration checkpoint)
   :initial-activations (:pending-activations checkpoint)})

(defn save-from-pregel
  "从 Pregel 状态直接保存 Checkpoint

   参数:
   - manager: GraphCheckpointManager
   - run-id: 执行 ID
   - pregel-state: Pregel 引擎状态
   - opts: 选项

   返回: checkpoint-id"
  [manager run-id pregel-state opts]
  (let [cp (from-pregel-state pregel-state (assoc opts :run-id run-id))]
    (save-checkpoint manager run-id cp)))

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
        entries (list-checkpoints manager run-id {:limit limit})]
    (mapv (fn [cp]
            (cond-> {:id (timeline/entry-id cp)
                     :superstep (:superstep cp)
                     :checkpoint-type (:checkpoint-type cp)
                     :created-at (timeline/entry-created-at cp)
                     :branch-id (timeline/entry-branch-id cp)}
              include-data? (assoc :data (timeline/entry-data cp))))
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

(defn compare-checkpoints
  "比较两个检查点

   参数:
   - cp1: 检查点 1
   - cp2: 检查点 2

   返回: {:superstep-diff :vertex-diff :state-diff}"
  [cp1 cp2]
  (let [v1 (:vertices cp1)
        v2 (:vertices cp2)
        all-vertex-ids (set (concat (keys v1) (keys v2)))
        changed-vertices (filter
                           (fn [vid]
                             (not= (get v1 vid) (get v2 vid)))
                           all-vertex-ids)]
    {:superstep-diff (- (or (:superstep cp2) 0)
                        (or (:superstep cp1) 0))
     :vertex-changes (count changed-vertices)
     :changed-vertex-ids (vec changed-vertices)
     :state-diff {:cp1-global (:global-state cp1)
                  :cp2-global (:global-state cp2)}}))

;; =============================================================================
;; 便利函数：时间旅行 + 恢复
;; =============================================================================

(defn- to-restore-opts
  "根据引擎类型转换恢复选项"
  [checkpoint engine-type]
  (case engine-type
    :pregel (to-pregel-restore-opts checkpoint)
    :executor (to-executor-restore-opts checkpoint)
    ;; 默认为 pregel
    (to-pregel-restore-opts checkpoint)))

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
   (when-let [cp (go-back manager run-id steps)]
     (to-restore-opts cp engine-type))))

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
   (when-let [cp (goto-checkpoint manager run-id checkpoint-id)]
     (to-restore-opts cp engine-type))))

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
   (when-let [branch-info (create-branch manager run-id checkpoint-id branch-name)]
     (when-let [cp (load-checkpoint manager run-id checkpoint-id)]
       {:branch-info branch-info
        :restore-opts (to-restore-opts cp engine-type)}))))

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

(defn graph-checkpoint?
  "检查是否为 GraphCheckpoint"
  [x]
  (instance? GraphCheckpoint x))
