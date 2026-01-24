(ns im.ttalk.agent.memory.snapshot.store-backed
  "StoreBackedSnapshotStore - 基于 Store 的快照管理器

   使用统一的 IKeyValueStore 作为存储后端，实现 ISnapshotStore 协议。

   设计理念：
   - SnapshotStore 不再独立管理存储，而是委托给 Store
   - 同一个 Store 实例可以同时用于 KV 存储和快照管理
   - 通过 namespace/prefix 隔离不同类型的数据

   数据模型：
   namespace: 'snapshots'
   keys:
   - 'snap:{thread-id}:{snapshot-id}' -> snapshot 数据
   - 'latest:{thread-id}' -> 最新 snapshot-id
   - 'thread:{thread-id}' -> snapshot-id 列表
   - 'branch:{thread-id}' -> 分支信息列表
   - 'writes:{thread-id}:{snapshot-id}' -> pending writes

   使用示例：
   (def store (create-in-memory-store))
   (def ss (create-store-backed-snapshot-store store))
   (proto/snap-put ss {:thread-id \"t1\"} {:state {:messages []}} {:step 1})"
  (:require [im.ttalk.agent.memory.protocol :as proto]
            [clojure.set :as set]))

;; =============================================================================
;; 常量和辅助函数
;; =============================================================================

(def ^:private default-namespace "snapshots")

(defn- generate-id []
  (str (java.util.UUID/randomUUID)))

(defn- now []
  (System/currentTimeMillis))

;; Key 构建函数
(defn- snap-key
  "快照数据 key: snap:{thread-id}:{snapshot-id}"
  [thread-id snapshot-id]
  (str "snap:" thread-id ":" snapshot-id))

(defn- latest-key
  "最新快照 key: latest:{thread-id}"
  [thread-id]
  (str "latest:" thread-id))

(defn- thread-key
  "线程快照列表 key: thread:{thread-id}"
  [thread-id]
  (str "thread:" thread-id))

(defn- branch-key
  "分支信息 key: branch:{thread-id}"
  [thread-id]
  (str "branch:" thread-id))

(defn- writes-key
  "Pending writes key: writes:{thread-id}:{snapshot-id}"
  [thread-id snapshot-id]
  (str "writes:" thread-id ":" snapshot-id))

;; =============================================================================
;; StoreBackedSnapshotStore 实现
;; =============================================================================

(defrecord StoreBackedSnapshotStore [store namespace config]
  proto/ISnapshotStore

  ;; -------------------------------------------------------------------------
  ;; 核心操作
  ;; -------------------------------------------------------------------------

  (snap-put [this cfg snapshot metadata]
    (let [thread-id (:thread-id cfg)
          snapshot-id (or (:snapshot-id cfg) (generate-id))
          timestamp (now)
          ;; 获取父快照 ID
          parent-id (proto/get-value store namespace (latest-key thread-id))
          ;; 构建完整记录
          record {:id snapshot-id
                  :thread-id thread-id
                  :snapshot snapshot
                  :metadata (assoc metadata
                              :created-at timestamp
                              :parent-id parent-id)
                  :created-at timestamp}]

      ;; 存储快照数据
      (proto/put store namespace (snap-key thread-id snapshot-id) record)

      ;; 更新 latest 指针
      (proto/put store namespace (latest-key thread-id) snapshot-id)

      ;; 更新线程快照列表
      (let [current-list (or (proto/get-value store namespace (thread-key thread-id)) [])]
        (proto/put store namespace (thread-key thread-id)
                    (conj current-list snapshot-id)))

      snapshot-id))

  (snap-put-writes [this cfg writes task-id]
    (let [thread-id (:thread-id cfg)
          snapshot-id (:snapshot-id cfg)
          key (writes-key thread-id snapshot-id)]
      ;; 检查快照是否存在
      (when (proto/exists? store namespace (snap-key thread-id snapshot-id))
        (let [current-writes (or (proto/get-value store namespace key) [])]
          (proto/put store namespace key
                      (conj current-writes
                            {:task-id task-id
                             :writes writes
                             :created-at (now)}))
          true))))

  (snap-get [this cfg]
    (let [thread-id (:thread-id cfg)
          snapshot-id (or (:snapshot-id cfg)
                          (proto/get-value store namespace (latest-key thread-id)))]
      (when snapshot-id
        (when-let [record (proto/get-value store namespace (snap-key thread-id snapshot-id))]
          (let [parent-id (get-in record [:metadata :parent-id])
                pending-writes (proto/get-value store namespace
                                           (writes-key thread-id snapshot-id))]
            {:snapshot (:snapshot record)
             :metadata (:metadata record)
             :parent-config (when parent-id
                              {:thread-id thread-id
                               :snapshot-id parent-id})
             :pending-writes pending-writes})))))

  (snap-list [this cfg opts]
    (let [thread-id (:thread-id cfg)
          {:keys [limit before]
           :or {limit 100}} opts
          snapshot-ids (or (proto/get-value store namespace (thread-key thread-id)) [])
          ;; 按时间倒序
          sorted-ids (reverse snapshot-ids)
          ;; 应用 before 过滤
          filtered-ids (if before
                         (drop-while #(not= % before) sorted-ids)
                         sorted-ids)]
      (->> filtered-ids
           (drop (if before 1 0))
           (take limit)
           (map (fn [s-id]
                  (when-let [record (proto/get-value store namespace (snap-key thread-id s-id))]
                    {:id s-id
                     :snapshot (:snapshot record)
                     :metadata (:metadata record)})))
           (filter some?))))

  (snap-delete-thread [this thread-id]
    (let [snapshot-ids (or (proto/get-value store namespace (thread-key thread-id)) [])
          count-deleted (count snapshot-ids)]
      ;; 删除所有快照数据
      (doseq [s-id snapshot-ids]
        (proto/delete store namespace (snap-key thread-id s-id))
        (proto/delete store namespace (writes-key thread-id s-id)))
      ;; 删除索引
      (proto/delete store namespace (thread-key thread-id))
      (proto/delete store namespace (latest-key thread-id))
      (proto/delete store namespace (branch-key thread-id))
      count-deleted))

  ;; -------------------------------------------------------------------------
  ;; 扩展操作
  ;; -------------------------------------------------------------------------

  (snap-get-next-version [this current channel]
    (if current
      (inc current)
      1))

  (snap-restore-to-step [this thread-id step]
    (let [snapshot-ids (or (proto/get-value store namespace (thread-key thread-id)) [])]
      (->> snapshot-ids
           (map (fn [s-id]
                  (proto/get-value store namespace (snap-key thread-id s-id))))
           (filter #(= (get-in % [:metadata :step]) step))
           first)))

  (snap-get-history [this thread-id]
    (let [snapshot-ids (or (proto/get-value store namespace (thread-key thread-id)) [])]
      (->> snapshot-ids
           (map (fn [s-id]
                  (when-let [record (proto/get-value store namespace (snap-key thread-id s-id))]
                    {:id s-id
                     :step (get-in record [:metadata :step])
                     :node (get-in record [:metadata :node])
                     :created-at (:created-at record)})))
           (filter some?)
           (vec))))

  ;; -------------------------------------------------------------------------
  ;; 分支管理
  ;; -------------------------------------------------------------------------

  (snap-create-branch [this thread-id snapshot-id branch-name]
    (when-let [source-record (proto/get-value store namespace (snap-key thread-id snapshot-id))]
      (let [new-snapshot-id (generate-id)
            new-thread-id (str thread-id "-" branch-name)
            new-record (-> source-record
                           (assoc :id new-snapshot-id)
                           (assoc :thread-id new-thread-id)
                           (assoc-in [:metadata :branch-name] branch-name)
                           (assoc-in [:metadata :source-snapshot-id] snapshot-id)
                           (assoc :created-at (now)))]

        ;; 存储新分支的快照
        (proto/put store namespace (snap-key new-thread-id new-snapshot-id) new-record)
        (proto/put store namespace (latest-key new-thread-id) new-snapshot-id)
        (proto/put store namespace (thread-key new-thread-id) [new-snapshot-id])

        ;; 更新原线程的分支列表
        (let [branches (or (proto/get-value store namespace (branch-key thread-id)) [])]
          (proto/put store namespace (branch-key thread-id)
                      (conj branches
                            {:branch-id new-thread-id
                             :name branch-name
                             :source-snapshot-id snapshot-id})))

        {:branch-id new-thread-id
         :snapshot-id new-snapshot-id})))

  (snap-list-branches [this thread-id]
    (or (proto/get-value store namespace (branch-key thread-id)) []))

  ;; -------------------------------------------------------------------------
  ;; 清理操作
  ;; -------------------------------------------------------------------------

  (snap-prune [this thread-id opts]
    (let [{:keys [keep-count keep-types]
           :or {keep-count 10
                keep-types #{:initial :final :error}}} opts
          snapshot-ids (or (proto/get-value store namespace (thread-key thread-id)) [])
          records (map (fn [s-id]
                         (proto/get-value store namespace (snap-key thread-id s-id)))
                       snapshot-ids)
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
          to-delete-ids (remove all-keep-ids snapshot-ids)]

      ;; 执行删除
      (doseq [s-id to-delete-ids]
        (proto/delete store namespace (snap-key thread-id s-id))
        (proto/delete store namespace (writes-key thread-id s-id)))

      ;; 更新线程快照列表
      (proto/put store namespace (thread-key thread-id)
                  (vec (filter all-keep-ids snapshot-ids)))

      {:kept (count all-keep-ids)
       :deleted (count to-delete-ids)}))

  (snap-clear-all [this]
    ;; 获取所有线程
    (let [all-keys (proto/list-keys store namespace {:prefix "thread:"})
          thread-ids (map #(subs % 7) all-keys)  ; 移除 "thread:" 前缀
          total-deleted (atom 0)]
      (doseq [thread-id thread-ids]
        (swap! total-deleted + (proto/snap-delete-thread this thread-id)))
      @total-deleted)))

;; =============================================================================
;; 工厂函数
;; =============================================================================

(defn create-store-backed-snapshot-store
  "创建基于 Store 的 SnapshotStore

   参数：
   - store: IKeyValueStore 实例
   - opts:
     - :namespace 存储命名空间（默认 'snapshots'）

   示例：
   (def store (create-in-memory-store))
   (def ss (create-store-backed-snapshot-store store))"
  [store & {:keys [namespace]
            :or {namespace default-namespace}}]
  (->StoreBackedSnapshotStore store namespace {}))

;; =============================================================================
;; 便捷函数
;; =============================================================================

(defn snapshot-count
  "获取快照总数"
  [snapshot-store]
  (let [{:keys [store namespace]} snapshot-store]
    (proto/count-keys store namespace {:prefix "snap:"})))

(defn thread-count
  "获取线程数量"
  [snapshot-store]
  (let [{:keys [store namespace]} snapshot-store]
    (proto/count-keys store namespace {:prefix "thread:"})))

(defn get-store
  "获取底层 Store 实例"
  [snapshot-store]
  (:store snapshot-store))
