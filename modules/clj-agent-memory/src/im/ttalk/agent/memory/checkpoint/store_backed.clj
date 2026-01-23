(ns im.ttalk.agent.memory.checkpoint.store-backed
  "StoreBackedCheckpointer - 基于 Store 的检查点管理器

   使用统一的 IKeyValueStore 作为存储后端，实现 ICheckpointer 协议。

   设计理念：
   - Checkpointer 不再独立管理存储，而是委托给 Store
   - 同一个 Store 实例可以同时用于 KV 存储和检查点管理
   - 通过 namespace/prefix 隔离不同类型的数据

   数据模型：
   namespace: 'checkpoints'
   keys:
   - 'cp:{thread-id}:{checkpoint-id}' -> checkpoint 数据
   - 'latest:{thread-id}' -> 最新 checkpoint-id
   - 'thread:{thread-id}' -> checkpoint-id 列表
   - 'branch:{thread-id}' -> 分支信息列表
   - 'writes:{thread-id}:{checkpoint-id}' -> pending writes

   使用示例：
   (def store (create-in-memory-store))
   (def checkpointer (create-store-backed-checkpointer store))
   (proto/save checkpointer {:thread-id \"t1\"} {:state {:messages []}} {:step 1})"
  (:require [im.ttalk.agent.memory.protocol :as proto]
            [clojure.set :as set]))

;; =============================================================================
;; 常量和辅助函数
;; =============================================================================

(def ^:private default-namespace "checkpoints")

(defn- generate-id []
  (str (java.util.UUID/randomUUID)))

(defn- now []
  (System/currentTimeMillis))

;; Key 构建函数
(defn- cp-key
  "检查点数据 key: cp:{thread-id}:{checkpoint-id}"
  [thread-id checkpoint-id]
  (str "cp:" thread-id ":" checkpoint-id))

(defn- latest-key
  "最新检查点 key: latest:{thread-id}"
  [thread-id]
  (str "latest:" thread-id))

(defn- thread-key
  "线程检查点列表 key: thread:{thread-id}"
  [thread-id]
  (str "thread:" thread-id))

(defn- branch-key
  "分支信息 key: branch:{thread-id}"
  [thread-id]
  (str "branch:" thread-id))

(defn- writes-key
  "Pending writes key: writes:{thread-id}:{checkpoint-id}"
  [thread-id checkpoint-id]
  (str "writes:" thread-id ":" checkpoint-id))

;; =============================================================================
;; StoreBackedCheckpointer 实现
;; =============================================================================

(defrecord StoreBackedCheckpointer [store namespace config]
  proto/ICheckpointer

  ;; -------------------------------------------------------------------------
  ;; 核心操作
  ;; -------------------------------------------------------------------------

  (save [this cfg checkpoint metadata]
    (let [thread-id (:thread-id cfg)
          checkpoint-id (or (:checkpoint-id cfg) (generate-id))
          timestamp (now)
          ;; 获取父检查点 ID
          parent-id (proto/get-value store namespace (latest-key thread-id))
          ;; 构建完整记录
          record {:id checkpoint-id
                  :thread-id thread-id
                  :checkpoint checkpoint
                  :metadata (assoc metadata
                              :created-at timestamp
                              :parent-id parent-id)
                  :created-at timestamp}]

      ;; 存储检查点数据
      (proto/put store namespace (cp-key thread-id checkpoint-id) record)

      ;; 更新 latest 指针
      (proto/put store namespace (latest-key thread-id) checkpoint-id)

      ;; 更新线程检查点列表
      (let [current-list (or (proto/get-value store namespace (thread-key thread-id)) [])]
        (proto/put store namespace (thread-key thread-id)
                    (conj current-list checkpoint-id)))

      checkpoint-id))

  (save-writes [this cfg writes task-id]
    (let [thread-id (:thread-id cfg)
          checkpoint-id (:checkpoint-id cfg)
          key (writes-key thread-id checkpoint-id)]
      ;; 检查检查点是否存在
      (when (proto/exists? store namespace (cp-key thread-id checkpoint-id))
        (let [current-writes (or (proto/get-value store namespace key) [])]
          (proto/put store namespace key
                      (conj current-writes
                            {:task-id task-id
                             :writes writes
                             :created-at (now)}))
          true))))

  (get-checkpoint [this cfg]
    (let [thread-id (:thread-id cfg)
          checkpoint-id (or (:checkpoint-id cfg)
                            (proto/get-value store namespace (latest-key thread-id)))]
      (when checkpoint-id
        (when-let [record (proto/get-value store namespace (cp-key thread-id checkpoint-id))]
          (let [parent-id (get-in record [:metadata :parent-id])
                pending-writes (proto/get-value store namespace
                                           (writes-key thread-id checkpoint-id))]
            {:checkpoint (:checkpoint record)
             :metadata (:metadata record)
             :parent-config (when parent-id
                              {:thread-id thread-id
                               :checkpoint-id parent-id})
             :pending-writes pending-writes})))))

  (list-checkpoints [this cfg opts]
    (let [thread-id (:thread-id cfg)
          {:keys [limit before]
           :or {limit 100}} opts
          checkpoint-ids (or (proto/get-value store namespace (thread-key thread-id)) [])
          ;; 按时间倒序
          sorted-ids (reverse checkpoint-ids)
          ;; 应用 before 过滤
          filtered-ids (if before
                         (drop-while #(not= % before) sorted-ids)
                         sorted-ids)]
      (->> filtered-ids
           (drop (if before 1 0))
           (take limit)
           (map (fn [cp-id]
                  (when-let [record (proto/get-value store namespace (cp-key thread-id cp-id))]
                    {:id cp-id
                     :checkpoint (:checkpoint record)
                     :metadata (:metadata record)})))
           (filter some?))))

  (delete-thread [this thread-id]
    (let [checkpoint-ids (or (proto/get-value store namespace (thread-key thread-id)) [])
          count-deleted (count checkpoint-ids)]
      ;; 删除所有检查点数据
      (doseq [cp-id checkpoint-ids]
        (proto/delete store namespace (cp-key thread-id cp-id))
        (proto/delete store namespace (writes-key thread-id cp-id)))
      ;; 删除索引
      (proto/delete store namespace (thread-key thread-id))
      (proto/delete store namespace (latest-key thread-id))
      (proto/delete store namespace (branch-key thread-id))
      count-deleted))

  ;; -------------------------------------------------------------------------
  ;; 扩展操作
  ;; -------------------------------------------------------------------------

  (get-next-version [this current channel]
    (if current
      (inc current)
      1))

  (restore-to-step [this thread-id step]
    (let [checkpoint-ids (or (proto/get-value store namespace (thread-key thread-id)) [])]
      (->> checkpoint-ids
           (map (fn [cp-id]
                  (proto/get-value store namespace (cp-key thread-id cp-id))))
           (filter #(= (get-in % [:metadata :step]) step))
           first)))

  (get-history [this thread-id]
    (let [checkpoint-ids (or (proto/get-value store namespace (thread-key thread-id)) [])]
      (->> checkpoint-ids
           (map (fn [cp-id]
                  (when-let [record (proto/get-value store namespace (cp-key thread-id cp-id))]
                    {:id cp-id
                     :step (get-in record [:metadata :step])
                     :node (get-in record [:metadata :node])
                     :created-at (:created-at record)})))
           (filter some?)
           (vec))))

  ;; -------------------------------------------------------------------------
  ;; 分支管理
  ;; -------------------------------------------------------------------------

  (create-branch [this thread-id checkpoint-id branch-name]
    (when-let [source-record (proto/get-value store namespace (cp-key thread-id checkpoint-id))]
      (let [new-checkpoint-id (generate-id)
            new-thread-id (str thread-id "-" branch-name)
            new-record (-> source-record
                           (assoc :id new-checkpoint-id)
                           (assoc :thread-id new-thread-id)
                           (assoc-in [:metadata :branch-name] branch-name)
                           (assoc-in [:metadata :source-checkpoint-id] checkpoint-id)
                           (assoc :created-at (now)))]

        ;; 存储新分支的检查点
        (proto/put store namespace (cp-key new-thread-id new-checkpoint-id) new-record)
        (proto/put store namespace (latest-key new-thread-id) new-checkpoint-id)
        (proto/put store namespace (thread-key new-thread-id) [new-checkpoint-id])

        ;; 更新原线程的分支列表
        (let [branches (or (proto/get-value store namespace (branch-key thread-id)) [])]
          (proto/put store namespace (branch-key thread-id)
                      (conj branches
                            {:branch-id new-thread-id
                             :name branch-name
                             :source-checkpoint-id checkpoint-id})))

        {:branch-id new-thread-id
         :checkpoint-id new-checkpoint-id})))

  (list-branches [this thread-id]
    (or (proto/get-value store namespace (branch-key thread-id)) []))

  ;; -------------------------------------------------------------------------
  ;; 清理操作
  ;; -------------------------------------------------------------------------

  (prune [this thread-id opts]
    (let [{:keys [keep-count keep-types]
           :or {keep-count 10
                keep-types #{:initial :final :error}}} opts
          checkpoint-ids (or (proto/get-value store namespace (thread-key thread-id)) [])
          records (map (fn [cp-id]
                         (proto/get-value store namespace (cp-key thread-id cp-id)))
                       checkpoint-ids)
          ;; 按时间排序（最新在前）
          sorted-records (reverse records)
          ;; 保留策略
          to-keep (take keep-count sorted-records)
          keep-ids (set (map :id to-keep))
          ;; 额外保留特定类型
          type-keep-ids (->> sorted-records
                             (filter #(contains? keep-types
                                                 (get-in % [:metadata :type])))
                             (map :id)
                             set)
          all-keep-ids (set/union keep-ids type-keep-ids)
          to-delete-ids (remove all-keep-ids checkpoint-ids)]

      ;; 执行删除
      (doseq [cp-id to-delete-ids]
        (proto/delete store namespace (cp-key thread-id cp-id))
        (proto/delete store namespace (writes-key thread-id cp-id)))

      ;; 更新线程检查点列表
      (proto/put store namespace (thread-key thread-id)
                  (vec (filter all-keep-ids checkpoint-ids)))

      {:kept (count all-keep-ids)
       :deleted (count to-delete-ids)}))

  (clear-all [this]
    ;; 获取所有线程
    (let [all-keys (proto/list-keys store namespace {:prefix "thread:"})
          thread-ids (map #(subs % 7) all-keys)  ; 移除 "thread:" 前缀
          total-deleted (atom 0)]
      (doseq [thread-id thread-ids]
        (swap! total-deleted + (proto/delete-thread this thread-id)))
      @total-deleted)))

;; =============================================================================
;; 工厂函数
;; =============================================================================

(defn create-store-backed-checkpointer
  "创建基于 Store 的 Checkpointer

   参数：
   - store: IKeyValueStore 实例
   - opts:
     - :namespace 存储命名空间（默认 'checkpoints'）

   示例：
   (def store (create-in-memory-store))
   (def checkpointer (create-store-backed-checkpointer store))

   ;; 或指定命名空间
   (def checkpointer (create-store-backed-checkpointer store
                       :namespace \"my-app-checkpoints\"))"
  [store & {:keys [namespace]
            :or {namespace default-namespace}}]
  (->StoreBackedCheckpointer store namespace {}))

;; =============================================================================
;; 便捷函数
;; =============================================================================

(defn checkpoint-count
  "获取检查点总数"
  [checkpointer]
  (let [{:keys [store namespace]} checkpointer]
    (proto/count-keys store namespace {:prefix "cp:"})))

(defn thread-count
  "获取线程数量"
  [checkpointer]
  (let [{:keys [store namespace]} checkpointer]
    (proto/count-keys store namespace {:prefix "thread:"})))

(defn get-store
  "获取底层 Store 实例"
  [checkpointer]
  (:store checkpointer))
