(ns im.ttalk.agent.core.kernel.process.snapshot-manager
  "Process Snapshot Manager 协议

   定义 process framework 对快照管理的完整能力需求：
   - 持久化：保存/加载 process 快照
   - 时间旅行：回退/前进/跳转
   - 分支管理：创建/列出/切换分支

   运行时通过 opts 注入实现实例，process framework 仅面向此协议编程。
   实现者可基于内存、SQLite、Redis 等任意后端。

   使用示例：

   ;; 注入到 process runtime
   (run-process my-process-spec
     {:checkpointer my-snapshot-manager
      :thread-id    \"session-001\"})

   ;; 时间旅行
   (go-back-process checkpointer \"session-001\" 2
                    my-process-spec resume-data {})

   ;; 分支
   (branch-process checkpointer \"session-001\" \"snap-id\" \"experiment\")")

;;; ============================================================
;;; IProcessSnapshotManager 协议
;;; ============================================================

(defprotocol IProcessSnapshotManager
  "Process 快照管理协议

   快照格式（由 runtime 生成）：
   {:process-name   keyword
    :status         :paused/:completed/:running
    :paused-step    keyword|nil
    :pause-reason   string|nil
    :context        context-map
    :step-states    {step-id {:state any :activation-count int}}
    :created-at     long}

   metadata 格式：
   {:step       step-id
    :reason     :step-done/:paused/:quiescent/:completed
    :created-at long}"

  ;; -------------------------------------------------------------------------
  ;; 核心操作：保存/加载
  ;; -------------------------------------------------------------------------

  (save-checkpoint [this thread-id snapshot metadata]
    "保存检查点

     参数:
     - thread-id: 执行线程标识（字符串）
     - snapshot:  process snapshot（纯数据 map）
     - metadata:  元数据 {:step :reason :created-at}

     返回: checkpoint-id（字符串）")

  (load-checkpoint [this thread-id checkpoint-id]
    "加载指定检查点

     参数:
     - thread-id:      线程标识
     - checkpoint-id:  检查点 ID

     返回: {:snapshot process-snapshot :metadata {...}} 或 nil")

  (load-latest [this thread-id]
    "加载最新检查点

     参数:
     - thread-id: 线程标识

     返回: {:snapshot process-snapshot :metadata {...}} 或 nil")

  ;; -------------------------------------------------------------------------
  ;; 历史导航
  ;; -------------------------------------------------------------------------

  (list-checkpoints [this thread-id opts]
    "列出检查点历史

     参数:
     - thread-id: 线程标识
     - opts:      {:limit int}（默认 100）

     返回: [{:id :metadata :created-at} ...]（最新在前）")

  (go-back [this thread-id steps]
    "回退 N 步

     参数:
     - thread-id: 线程标识
     - steps:     回退步数

     返回: {:snapshot ... :metadata ...} 或 nil（已到头）")

  (go-forward [this thread-id steps]
    "前进 N 步

     参数:
     - thread-id: 线程标识
     - steps:     前进步数

     返回: {:snapshot ... :metadata ...} 或 nil（已到头）")

  (goto-checkpoint [this thread-id checkpoint-id]
    "跳转到指定检查点

     参数:
     - thread-id:     线程标识
     - checkpoint-id: 目标检查点 ID

     返回: {:snapshot ... :metadata ...} 或 nil")

  ;; -------------------------------------------------------------------------
  ;; 分支管理
  ;; -------------------------------------------------------------------------

  (create-branch [this thread-id checkpoint-id branch-name]
    "从检查点创建分支

     参数:
     - thread-id:     源线程标识
     - checkpoint-id: 分支起点的检查点 ID
     - branch-name:   分支名称

     返回: {:branch-id :thread-id :checkpoint-id}")

  (list-branches [this thread-id]
    "列出所有分支

     参数:
     - thread-id: 线程标识

     返回: [{:branch-id :name :source-checkpoint-id :created-at} ...]")

  (switch-branch [this thread-id branch-id]
    "切换到指定分支

     参数:
     - thread-id: 线程标识
     - branch-id: 目标分支 ID

     返回: {:snapshot ... :metadata ...}（该分支最新检查点）或 nil"))

;;; ============================================================
;;; 辅助谓词
;;; ============================================================

(defn process-snapshot-manager?
  "检查对象是否实现了 IProcessSnapshotManager 协议

   参数:
   - x: 任意对象

   返回: boolean"
  [x]
  (and (some? x) (satisfies? IProcessSnapshotManager x)))

;;; ============================================================
;;; Process 级别的便利函数（组合 checkpointer + restore）
;;; ============================================================

(defn goto-and-restore
  "跳转到指定检查点并恢复执行

   参数:
   - checkpointer:  IProcessSnapshotManager 实例
   - thread-id:     线程标识
   - checkpoint-id: 目标检查点 ID
   - process-spec:  编译后的 process 定义
   - resume-data:   传给 paused step 的 on-resume 数据
   - opts:          运行选项

   返回: runtime map（含 result-chan）或 nil"
  [checkpointer thread-id checkpoint-id process-spec resume-data opts]
  (when-let [cp (goto-checkpoint checkpointer thread-id checkpoint-id)]
    (let [restore-fn (requiring-resolve
                       'im.ttalk.agent.core.kernel.process.runtime/restore-from-snapshot)]
      (restore-fn (:snapshot cp) process-spec resume-data opts))))

(defn go-back-and-restore
  "回退 N 步并恢复执行

   参数:
   - checkpointer:  IProcessSnapshotManager 实例
   - thread-id:     线程标识
   - steps:         回退步数
   - process-spec:  编译后的 process 定义
   - resume-data:   恢复数据
   - opts:          运行选项

   返回: runtime map 或 nil"
  [checkpointer thread-id steps process-spec resume-data opts]
  (when-let [cp (go-back checkpointer thread-id steps)]
    (let [restore-fn (requiring-resolve
                       'im.ttalk.agent.core.kernel.process.runtime/restore-from-snapshot)]
      (restore-fn (:snapshot cp) process-spec resume-data opts))))

(defn go-forward-and-restore
  "前进 N 步并恢复执行

   参数:
   - checkpointer:  IProcessSnapshotManager 实例
   - thread-id:     线程标识
   - steps:         前进步数
   - process-spec:  编译后的 process 定义
   - resume-data:   恢复数据
   - opts:          运行选项

   返回: runtime map 或 nil"
  [checkpointer thread-id steps process-spec resume-data opts]
  (when-let [cp (go-forward checkpointer thread-id steps)]
    (let [restore-fn (requiring-resolve
                       'im.ttalk.agent.core.kernel.process.runtime/restore-from-snapshot)]
      (restore-fn (:snapshot cp) process-spec resume-data opts))))

(defn branch-and-restore
  "从检查点创建分支并在新分支上恢复执行

   参数:
   - checkpointer:  IProcessSnapshotManager 实例
   - thread-id:     源线程标识
   - checkpoint-id: 分支起点
   - branch-name:   分支名称
   - process-spec:  编译后的 process 定义
   - resume-data:   恢复数据
   - opts:          运行选项

   返回: {:branch-info {:branch-id :thread-id :checkpoint-id}
          :runtime runtime-map} 或 nil"
  [checkpointer thread-id checkpoint-id branch-name process-spec resume-data opts]
  (when-let [branch-info (create-branch checkpointer thread-id checkpoint-id branch-name)]
    (when-let [cp (load-checkpoint checkpointer thread-id checkpoint-id)]
      (let [restore-fn (requiring-resolve
                         'im.ttalk.agent.core.kernel.process.runtime/restore-from-snapshot)]
        {:branch-info branch-info
         :runtime (restore-fn (:snapshot cp) process-spec resume-data
                              (assoc opts :thread-id (:thread-id branch-info)))}))))
