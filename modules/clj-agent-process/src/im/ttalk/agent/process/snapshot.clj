(ns im.ttalk.agent.process.snapshot
  "Process 快照 × Timeline 的适配层：把 runtime 的 on-quiescent 快照
   存成 Timeline 存档（版本链/时间旅行/分支都可用），并提供恢复入口。

   用法：
     (def mgr (tl/manager (tl-sqlite/sqlite-store \"timeline.db\")))

     ;; 1. 自动存档：把 checkpointer 挂为 on-quiescent
     (rt/run-process spec {:context (ctx/create {:kernel k})
                           :on-quiescent (checkpointer mgr \"session-1\")})

     ;; 2. 断点续跑（跨进程重启）
     (let [cp (latest-checkpoint mgr \"session-1\")]
       (if (paused-checkpoint? cp)
         (resume-checkpoint spec cp \"approved\")                    ;; 暂停点恢复
         (rt/run-process spec (merge (restore-opts cp)              ;; 静止点恢复
                                     {:initial-events [...]}))))

     ;; 3. 时间旅行 / 分支实验
     (tl/go-back! mgr \"session-1\" 2)
     (branch! mgr \"session-1\" (:id (tl/get-position-entry mgr \"session-1\")) \"exp\")
     (rt/run-process spec (merge (restore-opts (tl/get-position-entry mgr \"session-1\"))
                                 {:on-quiescent (checkpointer mgr \"session-1\")
                                  :initial-events [...]}))   ;; 新存档落在 exp 分支

   序列化注意：SQLite store 走 EDN，context 里的不可读值须剥离——
   checkpointer 缺省剥 :kernel（step 取 kernel 的约定键）；恢复时经
   restore-opts 的 :context-extras 重新注入。"
  (:require [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.process.runtime :as rt]
            [im.ttalk.agent.timeline :as tl]))

(set! *warn-on-reflection* true)

(def default-strip-keys
  "存档前从 context 剥离的键（不可 EDN 序列化的运行期对象）。"
  [:kernel])

(defn sanitize-snapshot
  "剥离 context 中不可序列化的键（缺省 :kernel + strip-keys）。"
  [snapshot strip-keys]
  (update snapshot :context
          #(apply dissoc % (concat default-strip-keys strip-keys))))

(defn checkpointer
  "构造可挂为 run-process :on-quiescent 的存档函数。

   每个静止点/暂停点自动 (tl/save! mgr thread-id snapshot)，
   entry 顶层带 :checkpoint-reason / :process-name / :status 便于列表筛选。

   opts:
   - :strip-keys 追加剥离的 context 键（缺省已剥 :kernel）
   - :on-saved   (fn [entry]) 存档后回调（如打日志）"
  ([mgr thread-id] (checkpointer mgr thread-id nil))
  ([mgr thread-id {:keys [strip-keys on-saved]}]
   (fn [snapshot]
     (let [clean (sanitize-snapshot snapshot strip-keys)
           entry (tl/save! mgr thread-id clean
                           {:metadata {:checkpoint-reason (:reason clean)
                                       :process-name      (:process-name clean)
                                       :status            (:status clean)}})]
       (when on-saved (on-saved entry))
       entry))))

;;; ============================================================
;;; 查询
;;; ============================================================

(defn latest-checkpoint
  "当前分支最新存档 entry（:data 为快照）。"
  [mgr thread-id]
  (tl/load-latest mgr thread-id))

(defn list-checkpoints
  "存档列表（版本升序）。opts 同 tl/get-history（:branch/:limit）。"
  ([mgr thread-id] (list-checkpoints mgr thread-id nil))
  ([mgr thread-id opts] (tl/get-history mgr thread-id opts)))

(defn goto-checkpoint!
  "位置跳到指定存档（后续 save 从此分叉）。返回该 entry。"
  [mgr thread-id checkpoint-id]
  (tl/goto! mgr thread-id checkpoint-id))

(defn branch!
  "在存档处开分支并切换过去（create-branch! + switch-branch!）。"
  [mgr thread-id checkpoint-id branch-name]
  (tl/create-branch! mgr thread-id checkpoint-id branch-name)
  (tl/switch-branch! mgr thread-id branch-name))

(defn paused-checkpoint?
  "存档是否为暂停点（可走 resume-checkpoint）。"
  [entry]
  (= :paused (get-in entry [:data :reason])))

;;; ============================================================
;;; 恢复
;;; ============================================================

(defn restore-opts
  "把存档转成 run-process 的恢复 opts（{:step-states :context}）。

   opts:
   - :context-extras 并回 context 的键值（如重新注入 {:kernel k}）

   调用方自行补 :initial-events 驱动后续步骤（设计文档已知约定）。"
  ([entry] (restore-opts entry nil))
  ([entry {:keys [context-extras]}]
   (let [{:keys [step-states context]} (:data entry)]
     {:step-states step-states
      :context     (merge (or context (ctx/create)) context-extras)})))

(defn resume-checkpoint
  "从暂停点存档恢复并 resume（跨进程重启可用）。

   参数:
   - spec:  原 process-spec（快照不含函数，由代码侧提供）
   - entry: paused-checkpoint? 为真的存档
   - data:  交给暂停 step :on-resume 的数据
   - opts:  {:context-extras :on-quiescent :max-events}"
  ([spec entry data] (resume-checkpoint spec entry data nil))
  ([spec entry data {:keys [context-extras] :as opts}]
   (when-not (paused-checkpoint? entry)
     (throw (ex-info "存档不是暂停点（reason ≠ :paused）"
                     {:reason (get-in entry [:data :reason])})))
   (let [snapshot (update (:data entry) :context merge context-extras)]
     (rt/resume-from-snapshot spec snapshot data
                              (select-keys opts [:on-quiescent :max-events])))))
