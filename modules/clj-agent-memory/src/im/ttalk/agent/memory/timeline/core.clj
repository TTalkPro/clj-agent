(ns im.ttalk.agent.memory.timeline.core
  "Timeline Core - 通用时间线管理

   提供版本链、时间旅行、分支管理等通用能力。
   被 ProcessSnapshotManager 和 GraphCheckpointManager 复用。

   架构：
   ┌─────────────────────────────────────────────────────────────┐
   │                    TimelineManager                          │
   │  ┌─────────────────┐      ┌─────────────────────────────┐  │
   │  │     store       │      │      branches               │  │
   │  │  (IKeyValue     │      │  (branch metadata)          │  │
   │  │   Store)        │      │                             │  │
   │  └─────────────────┘      └─────────────────────────────┘  │
   │                                                             │
   │  核心能力：                                                  │
   │  • 版本链管理 (parent-id 链)                                 │
   │  • 位置追踪 (current position per owner)                    │
   │  • 时间旅行 (go-back/go-forward/goto)                       │
   │  • 分支管理 (create-branch/switch-branch/list-branches)     │
   │  • 历史查询 (get-history/get-lineage)                       │
   │  • 自动清理 (auto-prune)                                    │
   └─────────────────────────────────────────────────────────────┘

   使用示例：

   ;; 定义一个实现 ITimelineEntry 的记录
   (defrecord MyEntry [id owner-id parent-id version branch-id created-at data])

   ;; 创建 TimelineManager
   (def tm (create-timeline-manager store :namespace \"my-entries\"))

   ;; 保存条目
   (save tm (->MyEntry nil \"owner-1\" nil nil \"main\" nil {:foo \"bar\"}))

   ;; 时间旅行
   (go-back tm \"owner-1\" 2)
   (go-forward tm \"owner-1\" 1)
   (goto tm \"owner-1\" \"entry-id\")

   ;; 分支管理
   (create-branch tm \"owner-1\" \"entry-id\" \"experiment\")
   (switch-branch tm \"owner-1\" \"experiment-branch-id\")"
  (:require [im.ttalk.agent.memory.protocol :as proto]
            [clojure.set]))

;; =============================================================================
;; ITimelineEntry 协议
;; =============================================================================

(defprotocol ITimelineEntry
  "时间线条目协议

   任何需要时间旅行能力的数据结构都必须实现此协议。
   TimelineManager 通过此协议操作条目。"

  ;; 访问器
  (entry-id [this]
    "获取条目 ID")

  (entry-owner-id [this]
    "获取所属者 ID（thread-id 或 run-id）")

  (entry-parent-id [this]
    "获取父条目 ID")

  (entry-version [this]
    "获取版本号")

  (entry-branch-id [this]
    "获取分支 ID")

  (entry-created-at [this]
    "获取创建时间")

  (entry-data [this]
    "获取条目数据（领域特定）")

  ;; 修改器（返回新实例）
  (with-entry-id [this id]
    "设置条目 ID，返回新实例")

  (with-parent-id [this parent-id]
    "设置父条目 ID，返回新实例")

  (with-version [this version]
    "设置版本号，返回新实例")

  (with-branch-id [this branch-id]
    "设置分支 ID，返回新实例")

  (with-created-at [this created-at]
    "设置创建时间，返回新实例"))

;; =============================================================================
;; 辅助函数
;; =============================================================================

(defn- generate-id
  "生成唯一 ID"
  []
  (str (java.util.UUID/randomUUID)))

(defn- now
  "获取当前时间戳"
  []
  (System/currentTimeMillis))

(defn- entry-key
  "构建条目存储 key: owner-id:entry-id"
  [owner-id entry-id]
  (str owner-id ":" entry-id))

(defn- owner-meta-key
  "构建 owner 元数据 key"
  [owner-id]
  (str "meta:" owner-id))

(defn- branch-index-key
  "构建分支索引 key"
  [owner-id]
  (str "branches:" owner-id))

;; =============================================================================
;; 命名空间常量
;; =============================================================================

(def ^:private ns-entries "entries")
(def ^:private ns-meta "timeline-meta")
(def ^:private ns-branches "timeline-branches")

;; 前向声明
(declare prune)

;; =============================================================================
;; TimelineManager 实现
;; =============================================================================

(defrecord TimelineManager
  [store              ; IKeyValueStore 实例
   namespace          ; 命名空间前缀
   positions          ; atom: {owner-id -> current-entry-id}
   branches           ; atom: {branch-id -> branch-info}
   current-branches   ; atom: {owner-id -> current-branch-id}
   config])           ; {:max-entries :auto-prune?}

;; =============================================================================
;; 核心操作：保存/加载
;; =============================================================================

(defn save
  "保存条目到时间线

   参数:
   - manager: TimelineManager 实例
   - entry: 实现 ITimelineEntry 的条目

   返回: 保存后的条目（包含生成的 id、version 等）"
  [manager entry]
  (let [{:keys [store namespace positions branches current-branches config]} manager
        owner-id (entry-owner-id entry)
        timestamp (now)
        ;; 获取当前分支
        branch-id (or (entry-branch-id entry)
                      (get @current-branches owner-id "main"))
        ;; 获取 owner 元数据
        owner-meta (proto/get-value store ns-meta (owner-meta-key owner-id))
        entry-ids (or (:entry-ids owner-meta) [])
        current-version (or (:current-version owner-meta) 0)
        ;; 获取父条目 ID
        parent-id (or (entry-parent-id entry)
                      (get @positions owner-id))
        ;; 生成新 ID 和版本
        new-id (or (entry-id entry) (generate-id))
        new-version (inc current-version)
        ;; 构建完整条目
        full-entry (-> entry
                       (with-entry-id new-id)
                       (with-parent-id parent-id)
                       (with-version new-version)
                       (with-branch-id branch-id)
                       (with-created-at timestamp))
        ;; 存储 key
        key (entry-key owner-id new-id)]

    ;; 存储条目
    (proto/put store (str namespace ":" ns-entries) key
               {:entry full-entry
                :created-at timestamp})

    ;; 更新 owner 元数据
    (proto/put store ns-meta (owner-meta-key owner-id)
               {:owner-id owner-id
                :latest-entry-id new-id
                :entry-ids (conj entry-ids new-id)
                :current-version new-version
                :updated-at timestamp})

    ;; 更新位置追踪
    (swap! positions assoc owner-id new-id)

    ;; 更新分支 head
    (swap! branches update branch-id
           (fn [branch]
             (-> (or branch {:branch-id branch-id
                             :name (or (:name branch) branch-id)
                             :created-at timestamp})
                 (assoc :head-entry-id new-id)
                 (update :entry-count (fnil inc 0)))))

    ;; 自动清理
    (when (and (:auto-prune? config) (:max-entries config))
      (let [max-entries (:max-entries config)
            entry-count (count (conj entry-ids new-id))]
        (when (> entry-count max-entries)
          (prune manager owner-id {:keep-count max-entries}))))

    full-entry))

(defn load-by-id
  "按 ID 加载条目

   参数:
   - manager: TimelineManager 实例
   - owner-id: 所属者 ID
   - entry-id: 条目 ID

   返回: 条目或 nil"
  [manager owner-id entry-id]
  (let [{:keys [store namespace]} manager
        key (entry-key owner-id entry-id)
        record (proto/get-value store (str namespace ":" ns-entries) key)]
    (:entry record)))

(defn load-latest
  "加载最新条目

   参数:
   - manager: TimelineManager 实例
   - owner-id: 所属者 ID

   返回: 条目或 nil"
  [manager owner-id]
  (let [{:keys [store positions]} manager
        entry-id (or (get @positions owner-id)
                     (let [owner-meta (proto/get-value store ns-meta (owner-meta-key owner-id))]
                       (:latest-entry-id owner-meta)))]
    (when entry-id
      (load-by-id manager owner-id entry-id))))

(defn list-entries
  "列出条目

   参数:
   - manager: TimelineManager 实例
   - owner-id: 所属者 ID
   - opts: {:limit int :before entry-id}

   返回: 条目列表（最新在前）"
  [manager owner-id opts]
  (let [{:keys [store namespace]} manager
        {:keys [limit before]
         :or {limit 100}} opts
        owner-meta (proto/get-value store ns-meta (owner-meta-key owner-id))
        entry-ids (or (:entry-ids owner-meta) [])
        ;; 按时间倒序（最新在前）
        sorted-ids (reverse entry-ids)
        ;; 应用 before 过滤
        filtered-ids (if before
                       (drop-while #(not= % before) sorted-ids)
                       sorted-ids)]
    (->> filtered-ids
         (drop (if before 1 0))
         (take limit)
         (map #(load-by-id manager owner-id %))
         (filter some?))))

(defn delete
  "删除条目

   参数:
   - manager: TimelineManager 实例
   - owner-id: 所属者 ID
   - entry-id: 条目 ID

   返回: boolean"
  [manager owner-id entry-id]
  (let [{:keys [store namespace]} manager
        key (entry-key owner-id entry-id)]
    (proto/delete store (str namespace ":" ns-entries) key)

    ;; 更新 owner 元数据
    (let [owner-meta (proto/get-value store ns-meta (owner-meta-key owner-id))
          entry-ids (or (:entry-ids owner-meta) [])]
      (proto/put store ns-meta (owner-meta-key owner-id)
                 (assoc owner-meta
                        :entry-ids (vec (remove #{entry-id} entry-ids))
                        :updated-at (now))))
    true))

(defn delete-owner
  "删除 owner 的所有条目

   参数:
   - manager: TimelineManager 实例
   - owner-id: 所属者 ID

   返回: 删除的数量"
  [manager owner-id]
  (let [{:keys [store namespace positions]} manager
        owner-meta (proto/get-value store ns-meta (owner-meta-key owner-id))
        entry-ids (or (:entry-ids owner-meta) [])
        count-deleted (count entry-ids)]
    ;; 删除所有条目
    (doseq [entry-id entry-ids]
      (proto/delete store (str namespace ":" ns-entries) (entry-key owner-id entry-id)))
    ;; 删除元数据
    (proto/delete store ns-meta (owner-meta-key owner-id))
    ;; 清除位置追踪
    (swap! positions dissoc owner-id)
    count-deleted))

;; =============================================================================
;; 时间旅行操作
;; =============================================================================

(defn get-position
  "获取当前位置

   参数:
   - manager: TimelineManager 实例
   - owner-id: 所属者 ID

   返回: {:current-entry-id :current-version :total-entries :branch-id}"
  [manager owner-id]
  (let [{:keys [store positions current-branches]} manager
        current-entry-id (get @positions owner-id)
        branch-id (get @current-branches owner-id "main")
        owner-meta (proto/get-value store ns-meta (owner-meta-key owner-id))
        entry-ids (or (:entry-ids owner-meta) [])
        current-index (when current-entry-id
                        (.indexOf (vec entry-ids) current-entry-id))]
    {:current-entry-id current-entry-id
     :current-index current-index
     :total-entries (count entry-ids)
     :branch-id branch-id}))

(defn go-back
  "回退 N 步

   参数:
   - manager: TimelineManager 实例
   - owner-id: 所属者 ID
   - steps: 回退步数

   返回: 目标条目或 nil（已到头）"
  [manager owner-id steps]
  (let [{:keys [store positions]} manager
        owner-meta (proto/get-value store ns-meta (owner-meta-key owner-id))
        entry-ids (vec (or (:entry-ids owner-meta) []))
        current-entry-id (get @positions owner-id)
        current-index (if current-entry-id
                        (.indexOf entry-ids current-entry-id)
                        (dec (count entry-ids)))
        target-index (- current-index steps)]
    (when (and (>= target-index 0)
               (< target-index (count entry-ids)))
      (let [target-id (nth entry-ids target-index)
            target-entry (load-by-id manager owner-id target-id)]
        (swap! positions assoc owner-id target-id)
        target-entry))))

(defn go-forward
  "前进 N 步

   参数:
   - manager: TimelineManager 实例
   - owner-id: 所属者 ID
   - steps: 前进步数

   返回: 目标条目或 nil（已到头）"
  [manager owner-id steps]
  (let [{:keys [store positions]} manager
        owner-meta (proto/get-value store ns-meta (owner-meta-key owner-id))
        entry-ids (vec (or (:entry-ids owner-meta) []))
        current-entry-id (get @positions owner-id)
        current-index (if current-entry-id
                        (.indexOf entry-ids current-entry-id)
                        -1)
        target-index (+ current-index steps)]
    (when (and (>= target-index 0)
               (< target-index (count entry-ids)))
      (let [target-id (nth entry-ids target-index)
            target-entry (load-by-id manager owner-id target-id)]
        (swap! positions assoc owner-id target-id)
        target-entry))))

(defn goto
  "跳转到指定条目

   参数:
   - manager: TimelineManager 实例
   - owner-id: 所属者 ID
   - entry-id: 目标条目 ID

   返回: 目标条目或 nil"
  [manager owner-id entry-id]
  (let [{:keys [positions]} manager
        target-entry (load-by-id manager owner-id entry-id)]
    (when target-entry
      (swap! positions assoc owner-id entry-id)
      target-entry)))

;; =============================================================================
;; 分支管理
;; =============================================================================

(defn create-branch
  "从条目创建分支

   参数:
   - manager: TimelineManager 实例
   - owner-id: 所属者 ID
   - entry-id: 分支起点的条目 ID
   - branch-name: 分支名称

   返回: {:branch-id :entry-id :owner-id}"
  [manager owner-id entry-id branch-name]
  (let [{:keys [store namespace branches current-branches]} manager
        source-entry (load-by-id manager owner-id entry-id)]
    (when source-entry
      (let [new-branch-id (str branch-name "-" (generate-id))
            timestamp (now)
            ;; 创建新分支元数据
            branch-info {:branch-id new-branch-id
                         :name branch-name
                         :source-entry-id entry-id
                         :source-owner-id owner-id
                         :head-entry-id entry-id
                         :entry-count 0
                         :created-at timestamp}]
        ;; 保存分支信息
        (swap! branches assoc new-branch-id branch-info)

        ;; 更新分支索引
        (let [branch-list (or (proto/get-value store ns-branches (branch-index-key owner-id)) [])]
          (proto/put store ns-branches (branch-index-key owner-id)
                     (conj branch-list branch-info)))

        {:branch-id new-branch-id
         :entry-id entry-id
         :owner-id owner-id}))))

(defn switch-branch
  "切换到指定分支

   参数:
   - manager: TimelineManager 实例
   - owner-id: 所属者 ID
   - branch-id: 目标分支 ID

   返回: 分支 head 条目或 nil"
  [manager owner-id branch-id]
  (let [{:keys [branches current-branches positions]} manager
        branch-info (get @branches branch-id)]
    (when branch-info
      (swap! current-branches assoc owner-id branch-id)
      (when-let [head-id (:head-entry-id branch-info)]
        (swap! positions assoc owner-id head-id)
        (load-by-id manager owner-id head-id)))))

(defn list-branches
  "列出所有分支

   参数:
   - manager: TimelineManager 实例
   - owner-id: 所属者 ID

   返回: 分支列表"
  [manager owner-id]
  (let [{:keys [store]} manager
        branch-list (proto/get-value store ns-branches (branch-index-key owner-id))]
    (or branch-list [])))

;; =============================================================================
;; 历史查询
;; =============================================================================

(defn get-history
  "获取历史记录

   参数:
   - manager: TimelineManager 实例
   - owner-id: 所属者 ID
   - opts: {:limit int :include-data? boolean}

   返回: [{:id :version :created-at :data?} ...]"
  [manager owner-id opts]
  (let [{:keys [limit include-data?]
         :or {limit 100 include-data? false}} opts
        entries (list-entries manager owner-id {:limit limit})]
    (mapv (fn [entry]
            (cond-> {:id (entry-id entry)
                     :version (entry-version entry)
                     :created-at (entry-created-at entry)
                     :branch-id (entry-branch-id entry)}
              include-data? (assoc :data (entry-data entry))))
          entries)))

(defn get-lineage
  "获取条目的祖先链

   参数:
   - manager: TimelineManager 实例
   - owner-id: 所属者 ID
   - entry-id: 起始条目 ID

   返回: 祖先链列表（从当前到根）"
  [manager owner-id entry-id]
  (loop [current-id entry-id
         lineage []]
    (if-let [entry (load-by-id manager owner-id current-id)]
      (let [parent-id (entry-parent-id entry)]
        (if parent-id
          (recur parent-id (conj lineage entry))
          (conj lineage entry)))
      lineage)))

;; =============================================================================
;; 清理操作
;; =============================================================================

(defn prune
  "清理旧条目

   参数:
   - manager: TimelineManager 实例
   - owner-id: 所属者 ID
   - opts: {:keep-count int :keep-versions #{version...}}

   返回: {:kept int :deleted int}"
  [manager owner-id opts]
  (let [{:keys [store namespace]} manager
        {:keys [keep-count keep-versions]
         :or {keep-count 50
              keep-versions #{}}} opts
        owner-meta (proto/get-value store ns-meta (owner-meta-key owner-id))
        entry-ids (vec (or (:entry-ids owner-meta) []))
        ;; 按时间排序（最新在前）
        sorted-ids (reverse entry-ids)
        ;; 要保留的 IDs
        keep-by-count (set (take keep-count sorted-ids))
        ;; 按版本保留
        keep-by-version (set
                          (filter
                            (fn [eid]
                              (when-let [entry (load-by-id manager owner-id eid)]
                                (contains? keep-versions (entry-version entry))))
                            entry-ids))
        all-keep-ids (clojure.set/union keep-by-count keep-by-version)
        to-delete-ids (remove all-keep-ids entry-ids)]
    ;; 执行删除
    (doseq [entry-id to-delete-ids]
      (proto/delete store (str namespace ":" ns-entries) (entry-key owner-id entry-id)))
    ;; 更新元数据
    (proto/put store ns-meta (owner-meta-key owner-id)
               (assoc owner-meta
                      :entry-ids (vec (filter all-keep-ids entry-ids))
                      :updated-at (now)))
    {:kept (count all-keep-ids)
     :deleted (count to-delete-ids)}))

;; =============================================================================
;; 工厂函数
;; =============================================================================

(defn create-timeline-manager
  "创建 TimelineManager

   参数:
   - store: IKeyValueStore 实例
   - opts:
     - :namespace      命名空间前缀（默认 \"timeline\"）
     - :max-entries    最大条目数（默认 100）
     - :auto-prune?    是否自动清理（默认 true）

   返回: TimelineManager 实例"
  [store & {:keys [namespace max-entries auto-prune?]
            :or {namespace "timeline"
                 max-entries 100
                 auto-prune? true}}]
  {:pre [(some? store)
         (proto/store? store)]}
  (->TimelineManager store
                     namespace
                     (atom {})           ; positions
                     (atom {"main" {:branch-id "main"
                                    :name "main"
                                    :head-entry-id nil
                                    :entry-count 0
                                    :created-at (now)}})
                     (atom {})           ; current-branches
                     {:max-entries max-entries
                      :auto-prune? auto-prune?}))

(defn timeline-manager?
  "检查是否为 TimelineManager"
  [x]
  (instance? TimelineManager x))

;; =============================================================================
;; 辅助谓词
;; =============================================================================

(defn timeline-entry?
  "检查是否实现了 ITimelineEntry 协议"
  [x]
  (satisfies? ITimelineEntry x))
