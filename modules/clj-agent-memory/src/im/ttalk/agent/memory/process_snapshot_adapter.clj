(ns im.ttalk.agent.memory.process-snapshot-adapter
  "Process Snapshot Adapter

   将 IProcessSnapshotManager 协议适配到 SnapshotManager。
   桥接 process framework 和 memory 模块的快照系统。

   使用示例：

   (require '[im.ttalk.agent.memory.process-snapshot-adapter :as psa])
   (require '[im.ttalk.agent.memory.manager.snapshot :as mgr])
   (require '[im.ttalk.agent.memory.store.memory :as mem-store])

   ;; 创建适配器
   (def adapter (psa/create-adapter
                  (mgr/create-snapshot-manager (mem-store/create-in-memory-store))))

   ;; 注入到 process runtime
   (run-process my-spec {:checkpointer adapter :thread-id \"session-1\"})"
  (:require [im.ttalk.agent.core.kernel.process.snapshot-manager :as sm]
            [im.ttalk.agent.memory.protocol :as proto]
            [im.ttalk.agent.memory.manager.snapshot :as mgr]))

;;; ============================================================
;;; ProcessSnapshotAdapter Record
;;; ============================================================

(defrecord ProcessSnapshotAdapter [snapshot-manager]
  sm/IProcessSnapshotManager

  (save-checkpoint [_ thread-id snapshot metadata]
    (proto/snap-put snapshot-manager
                    {:thread-id thread-id}
                    snapshot
                    metadata))

  (load-checkpoint [_ thread-id checkpoint-id]
    (proto/snap-get snapshot-manager
                    {:thread-id thread-id :snapshot-id checkpoint-id}))

  (load-latest [_ thread-id]
    (proto/snap-get snapshot-manager
                    {:thread-id thread-id}))

  (list-checkpoints [_ thread-id opts]
    (proto/snap-list snapshot-manager
                     {:thread-id thread-id}
                     opts))

  (go-back [_ thread-id steps]
    (let [result (mgr/go-back! snapshot-manager
                               {:thread-id thread-id}
                               :steps steps)]
      (when result
        {:snapshot (:snapshot result)
         :metadata (:metadata result)
         :id (:id result)})))

  (go-forward [_ thread-id steps]
    (let [result (mgr/go-forward! snapshot-manager
                                  {:thread-id thread-id}
                                  :steps steps)]
      (when result
        {:snapshot (:snapshot result)
         :metadata (:metadata result)
         :id (:id result)})))

  (goto-checkpoint [_ thread-id checkpoint-id]
    (let [result (mgr/goto! snapshot-manager
                            {:thread-id thread-id}
                            checkpoint-id)]
      (when result
        {:snapshot (:snapshot result)
         :metadata (:metadata result)
         :id (:id result)})))

  (create-branch [_ thread-id checkpoint-id branch-name]
    (proto/snap-create-branch snapshot-manager
                              thread-id checkpoint-id branch-name))

  (list-branches [_ thread-id]
    (proto/snap-list-branches snapshot-manager thread-id))

  (switch-branch [_ thread-id branch-id]
    (let [result (mgr/switch-branch! snapshot-manager
                                     {:thread-id thread-id}
                                     branch-id)]
      (when result
        {:snapshot (:snapshot result)
         :metadata (:metadata result)}))))

;;; ============================================================
;;; 工厂函数
;;; ============================================================

(defn create-adapter
  "创建 ProcessSnapshotAdapter

   参数:
   - snapshot-manager: SnapshotManager 实例

   返回: ProcessSnapshotAdapter 实例（实现 IProcessSnapshotManager）"
  [snapshot-manager]
  {:pre [(mgr/snapshot-manager? snapshot-manager)]}
  (->ProcessSnapshotAdapter snapshot-manager))
