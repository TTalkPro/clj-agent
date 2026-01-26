(ns im.ttalk.agent.core.graph.checkpoint
  "Graph Checkpoint - Graph/Pregel 框架检查点协议和数据结构

   本模块定义检查点的协议、数据结构和纯函数。
   实现（GraphCheckpointManager）在 clj-agent-memory 模块中。

   架构：
   ┌─────────────────────────────────────────────────────────────┐
   │                    clj-agent-core                           │
   │  • GraphCheckpoint 数据结构                                 │
   │  • IGraphCheckpointManager 协议                             │
   │  • Pregel/Executor 集成函数                                 │
   └─────────────────────────────────────────────────────────────┘
                              ↑
   ┌─────────────────────────────────────────────────────────────┐
   │                   clj-agent-memory                          │
   │  • GraphCheckpointManager 实现                              │
   │  • 依赖 TimelineManager                                     │
   └─────────────────────────────────────────────────────────────┘

   使用示例：

   ;; 在 memory 模块创建 CheckpointManager
   (require '[im.ttalk.agent.memory.manager.checkpoint :as cm])
   (def manager (cm/create-checkpoint-manager store))

   ;; 使用 core 模块的协议和函数
   (require '[im.ttalk.agent.core.graph.checkpoint :as cp])
   (cp/save-checkpoint manager \"run-1\" checkpoint)
   (def opts (cp/to-pregel-restore-opts checkpoint))")

;; =============================================================================
;; Checkpoint 类型常量
;; =============================================================================

(def CHECKPOINT-INITIAL :initial)
(def CHECKPOINT-SUPERSTEP :superstep)
(def CHECKPOINT-ERROR :error)
(def CHECKPOINT-INTERRUPT :interrupt)
(def CHECKPOINT-FINAL :final)

;; =============================================================================
;; GraphCheckpoint 数据结构
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

(defn graph-checkpoint?
  "检查是否为 GraphCheckpoint"
  [x]
  (instance? GraphCheckpoint x))

;; =============================================================================
;; 辅助函数
;; =============================================================================

(defn generate-checkpoint-id
  "生成唯一 Checkpoint ID"
  []
  (str "cp-" (java.util.UUID/randomUUID)))

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
;; Checkpoint 访问器（纯函数）
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
;; Pregel 集成（纯函数）
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
      {:id (generate-checkpoint-id)
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

;; =============================================================================
;; 比较函数（纯函数）
;; =============================================================================

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
;; 恢复选项转换（纯函数）
;; =============================================================================

(defn to-restore-opts
  "根据引擎类型转换恢复选项

   参数:
   - checkpoint: GraphCheckpoint
   - engine-type: :pregel 或 :executor

   返回: 恢复选项 map"
  [checkpoint engine-type]
  (case engine-type
    :pregel (to-pregel-restore-opts checkpoint)
    :executor (to-executor-restore-opts checkpoint)
    ;; 默认为 pregel
    (to-pregel-restore-opts checkpoint)))
