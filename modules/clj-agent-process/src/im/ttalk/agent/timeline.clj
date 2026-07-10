(ns im.ttalk.agent.timeline
  "Timeline —— 通用版本链 / 时间旅行 / 分支管理（与具体框架无关）。

   概念（对标 git）：
   - entry：一次存档 {:id :owner-id :parent-id :version :branch-id :created-at :data}
     · :data 为任意 EDN 值（用 SQLite store 时须可 pr-str/read-string 往返）
     · :version 为血缘深度（parent.version + 1），同分支内连续
   - owner：一条时间线的归属（如会话/线程 id），各 owner 完全隔离
   - position：owner 的「当前所在 entry」——save 前进、go-back/goto 移动
   - branch：从任意 entry 开叉的平行时间线；缺省分支 \"main\"

   用法：
     (def mgr (manager (in-memory-store)))
     (save! mgr \"s1\" {:step 1})              ;; main v1
     (save! mgr \"s1\" {:step 2})              ;; main v2
     (go-back! mgr \"s1\" 1)                   ;; 位置回到 v1
     (create-branch! mgr \"s1\" (:id (get-position-entry mgr \"s1\")) \"exp\")
     (switch-branch! mgr \"s1\" \"exp\")
     (save! mgr \"s1\" {:step :2'})            ;; exp v2（与 main v2 平行）

   注意：不经 create-branch 直接在历史位置 save 会造成同分支同版本分叉，
   go-forward 遇分叉取 created-at 最新的子节点（文档化的宽松语义）。"
  )

(set! *warn-on-reflection* true)

;;; ============================================================
;;; Store 协议 + in-memory 实现
;;; ============================================================

(defprotocol TimelineStore
  "Timeline 持久化后端。entry 与 owner 级元数据（position/branch 登记）分开存。"
  (put-entry!    [store entry]        "写入一条 entry（幂等按 :id 覆盖）")
  (get-entry     [store owner-id id]  "按 id 取 entry，无则 nil")
  (entries       [store owner-id]     "owner 的全部 entry（无序）")
  (delete-entry! [store owner-id id]  "删除一条 entry")
  (put-meta!     [store owner-id k v] "写 owner 级元数据（EDN 值）")
  (get-meta      [store owner-id k]   "读 owner 级元数据，无则 nil"))

(defrecord InMemoryTimelineStore [state]
  TimelineStore
  (put-entry! [_ entry]
    (swap! state assoc-in [:entries (:owner-id entry) (:id entry)] entry)
    nil)
  (get-entry [_ owner-id id]
    (get-in @state [:entries owner-id id]))
  (entries [_ owner-id]
    (vals (get-in @state [:entries owner-id])))
  (delete-entry! [_ owner-id id]
    (swap! state update-in [:entries owner-id] dissoc id)
    nil)
  (put-meta! [_ owner-id k v]
    (swap! state assoc-in [:meta owner-id k] v)
    nil)
  (get-meta [_ owner-id k]
    (get-in @state [:meta owner-id k])))

(defn in-memory-store
  "进程内 Timeline store（atom 后端）。"
  []
  (->InMemoryTimelineStore (atom {:entries {} :meta {}})))

;;; ============================================================
;;; Manager
;;; ============================================================

(def default-branch "main")

(defn manager
  "包装 store 为 TimelineManager（本身无状态，位置/分支登记持久化在 store meta 里，
   跨进程重启仍有效）。"
  [store]
  {:store store})

(defn- gen-id [] (str "tl-" (java.util.UUID/randomUUID)))

(defn current-branch
  "owner 当前所在分支（缺省 main）。"
  [{:keys [store]} owner-id]
  (or (get-meta store owner-id :branch) default-branch))

(defn get-position
  "owner 当前位置的 entry id（尚无存档时 nil）。"
  [{:keys [store]} owner-id]
  (get-meta store owner-id :position))

(defn load-by-id
  "按 id 读取 entry。"
  [{:keys [store]} owner-id id]
  (get-entry store owner-id id))

(defn get-position-entry
  "owner 当前位置的完整 entry。"
  [mgr owner-id]
  (some->> (get-position mgr owner-id) (load-by-id mgr owner-id)))

(defn save!
  "在当前位置之后追加存档并前移位置。返回新 entry。

   opts:
   - :metadata  并入 entry 顶层的附加键（如 :checkpoint-reason）"
  ([mgr owner-id data] (save! mgr owner-id data nil))
  ([{:keys [store] :as mgr} owner-id data {:keys [metadata]}]
   (let [parent (get-position-entry mgr owner-id)
         entry  (merge metadata
                       {:id         (gen-id)
                        :owner-id   owner-id
                        :parent-id  (:id parent)
                        :version    (inc (or (:version parent) 0))
                        :branch-id  (current-branch mgr owner-id)
                        :created-at (System/currentTimeMillis)
                        :data       data})]
     (put-entry! store entry)
     (put-meta! store owner-id :position (:id entry))
     entry)))

(defn load-latest
  "当前分支的最新（版本最大）entry；分支为空时回退全 owner 最新。"
  [{:keys [store] :as mgr} owner-id]
  (let [all (entries store owner-id)
        b   (current-branch mgr owner-id)
        in-branch (filter #(= b (:branch-id %)) all)
        pick (fn [es] (last (sort-by (juxt :version :created-at) es)))]
    (or (pick in-branch) (pick all))))

(defn list-entries
  "owner 的 entry 列表，按 version/created-at 升序。opts :branch 过滤分支。"
  ([mgr owner-id] (list-entries mgr owner-id nil))
  ([{:keys [store]} owner-id {:keys [branch]}]
   (->> (entries store owner-id)
        (filter #(or (nil? branch) (= branch (:branch-id %))))
        (sort-by (juxt :version :created-at)))))

(defn delete!
  "删除一条 entry（不调整位置——位置指向被删 entry 时由调用方自行 goto）。"
  [{:keys [store]} owner-id id]
  (delete-entry! store owner-id id))

;;; ============================================================
;;; 时间旅行
;;; ============================================================

(defn goto!
  "位置跳到指定 entry，并把当前分支切为该 entry 的分支。返回该 entry。"
  [{:keys [store] :as mgr} owner-id id]
  (let [e (get-entry store owner-id id)]
    (when-not e
      (throw (ex-info (str "entry 不存在: " id) {:owner owner-id :id id})))
    (put-meta! store owner-id :position id)
    (put-meta! store owner-id :branch (:branch-id e))
    e))

(defn- move-to!
  "仅移动位置，不改当前分支（go-back/go-forward 的对称性依赖于此：
   从分支上退过分叉点再前进，仍回到本分支的节点）。"
  [{:keys [store]} owner-id entry]
  (put-meta! store owner-id :position (:id entry))
  entry)

(defn go-back!
  "沿 parent 链回退 n 步（跨分支锚点可达父分支的 entry，但**当前分支不变**）。
   链到头（无 parent 或 parent 已被 prune）则停在最早可达处。返回落点 entry。"
  [mgr owner-id n]
  (loop [e (get-position-entry mgr owner-id), i 0]
    (if (or (nil? e) (>= i n))
      (when e (move-to! mgr owner-id e))
      (if-let [p (some->> (:parent-id e) (load-by-id mgr owner-id))]
        (recur p (inc i))
        (move-to! mgr owner-id e)))))

(defn go-forward!
  "沿子节点前进 n 步，**当前分支不变**。优先当前分支上的子节点
   （分叉点处回到本分支的线）；同分支分叉时取 created-at 最新。
   无子节点则停。返回落点 entry。"
  [{:keys [store] :as mgr} owner-id n]
  (loop [e (get-position-entry mgr owner-id), i 0]
    (if (or (nil? e) (>= i n))
      (when e (move-to! mgr owner-id e))
      (let [b (current-branch mgr owner-id)
            children (filter #(= (:id e) (:parent-id %)) (entries store owner-id))
            same-branch (filter #(= b (:branch-id %)) children)
            next-e (when-let [cs (seq (or (seq same-branch) children))]
                     (apply max-key :created-at cs))]
        (if next-e
          (recur next-e (inc i))
          (move-to! mgr owner-id e))))))

;;; ============================================================
;;; 分支
;;; ============================================================

(defn create-branch!
  "在 entry-id 处登记新分支（不切换）。返回分支登记 map。"
  [{:keys [store]} owner-id entry-id branch-name]
  (when-not (get-entry store owner-id entry-id)
    (throw (ex-info (str "分支锚点不存在: " entry-id) {:anchor entry-id})))
  (let [branches (or (get-meta store owner-id :branches) {})]
    (when (contains? branches branch-name)
      (throw (ex-info (str "分支已存在: " branch-name) {:branch branch-name})))
    (let [b {:anchor entry-id :created-at (System/currentTimeMillis)}]
      (put-meta! store owner-id :branches (assoc branches branch-name b))
      (assoc b :branch-id branch-name))))

(defn switch-branch!
  "切到分支：位置 = 分支最新 entry（尚无则其锚点）。返回落点 entry。"
  [{:keys [store] :as mgr} owner-id branch-name]
  (let [branches (or (get-meta store owner-id :branches) {})]
    (when-not (or (= branch-name default-branch) (contains? branches branch-name))
      (throw (ex-info (str "未知分支: " branch-name) {:branch branch-name})))
    (put-meta! store owner-id :branch branch-name)
    (let [head (->> (entries store owner-id)
                    (filter #(= branch-name (:branch-id %)))
                    (sort-by (juxt :version :created-at))
                    last)
          target (or head
                     (some->> (get-in branches [branch-name :anchor])
                              (get-entry store owner-id)))]
      (when target
        (put-meta! store owner-id :position (:id target))
        ;; goto! 会把 :branch 改回锚点所在分支——切分支语义要保持目标分支
        (put-meta! store owner-id :branch branch-name)
        target))))

(defn list-branches
  "owner 的分支列表（含缺省 main）。"
  [{:keys [store]} owner-id]
  (let [named (or (get-meta store owner-id :branches) {})]
    (into [{:branch-id default-branch}]
          (map (fn [[k v]] (assoc v :branch-id k)))
          named)))

;;; ============================================================
;;; 历史 / 血缘 / 清理
;;; ============================================================

(defn get-history
  "owner 的历史（= list-entries；opts :branch/:limit）。"
  ([mgr owner-id] (get-history mgr owner-id nil))
  ([mgr owner-id {:keys [limit] :as opts}]
   (let [es (list-entries mgr owner-id opts)]
     (if limit (vec (take-last limit es)) (vec es)))))

(defn get-lineage
  "entry 的血缘：从自身沿 parent 到根（parent 被 prune 则止于断点），根在前。"
  [mgr owner-id entry-id]
  (loop [e (load-by-id mgr owner-id entry-id), acc ()]
    (if (nil? e)
      (vec acc)
      (recur (some->> (:parent-id e) (load-by-id mgr owner-id))
             (cons e acc)))))

(defn prune!
  "每分支保留最新 keep-last 条，其余删除（当前位置 entry 永不删）。
   被删 entry 的后代 lineage 走到断点即止（文档化语义）。返回删除数。"
  [{:keys [store] :as mgr} owner-id {:keys [keep-last] :or {keep-last 10}}]
  (let [pos (get-position mgr owner-id)
        victims (->> (entries store owner-id)
                     (group-by :branch-id)
                     vals
                     (mapcat (fn [es]
                               (->> es
                                    (sort-by (juxt :version :created-at))
                                    reverse
                                    (drop keep-last))))
                     (remove #(= pos (:id %))))]
    (doseq [e victims] (delete-entry! store owner-id (:id e)))
    (count victims)))
