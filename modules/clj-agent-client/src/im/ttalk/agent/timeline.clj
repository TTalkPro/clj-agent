(ns im.ttalk.agent.timeline
  "对话时间线与多分支（设计见 docs/agent-loop-concurrency-design.md §12）

   Agent 的持久状态 = 对话历史（ChatMemory 中的 append-only 日志），
   日志本身就是 timeline；分支 = fork-as-new-conversation——前缀复制到
   新 conversation-id + 一条血缘记录。所有既有组件（memory advisor /
   PauseStore / react 循环）按 conversation-id 工作，换分支即换 conv-id
   建 agent，无组件感知\"树\"。

   一致性不变量：**合法的 fork/rollback 点只有 turn 边界与暂停点**
   （屏障才是一致快照点）。fork 暂停中的会话（全量前缀）自动连带复制
   PauseStore 快照——两个分支可各自 resume 不同决策（HITL 决策分支）。

   用法：
     (def deps {:memory mem :pause-store ps :lineage (in-memory-lineage-store)})
     (fork! deps \"main\" {})                ;; 全量分支（含暂停快照）
     (fork! deps \"main\" {:at 4 :as \"exp\"}) ;; 前 4 条消息处开分支
     (rollback! deps \"main\" 4)             ;; 破坏性截断（\"重新生成\"）
     (ancestry deps \"exp\")                 ;; 血缘回溯
     (prune! deps \"exp\")                   ;; 删分支（有子分支拒绝）"
  (:require [clojure.edn :as edn]
            [next.jdbc :as jdbc]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.pause :as pause]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; LineageStore：分支血缘
;;; ============================================================

(defprotocol LineageStore
  "分支血缘记录 {:id :parent :fork-point :created-at}。"
  (lineage-add!    [store record]  "登记一条血缘。返回 nil。")
  (lineage-get     [store conv-id] "查该会话的血缘记录；根会话（非 fork 产物）为 nil。")
  (lineage-children [store conv-id] "直接子分支的记录列表。")
  (lineage-remove! [store conv-id] "删除该会话的血缘记录。返回 nil。"))

(defrecord InMemoryLineageStore [state]
  LineageStore
  (lineage-add!    [_ rec] (swap! state assoc (:id rec) rec) nil)
  (lineage-get     [_ conv-id] (get @state conv-id))
  (lineage-children [_ conv-id] (filterv #(= conv-id (:parent %)) (vals @state)))
  (lineage-remove! [_ conv-id] (swap! state dissoc conv-id) nil))

(defn in-memory-lineage-store []
  (->InMemoryLineageStore (atom {})))

(defn- ensure-schema! [ds]
  (jdbc/execute! ds
    ["CREATE TABLE IF NOT EXISTS branch_lineage (
        conversation_id TEXT PRIMARY KEY,
        record TEXT NOT NULL)"])
  (jdbc/execute! ds
    ["CREATE INDEX IF NOT EXISTS idx_lineage_parent
        ON branch_lineage(conversation_id)"]))

(defrecord SqliteLineageStore [conn]
  LineageStore
  (lineage-add! [_ rec]
    (locking conn
      (jdbc/execute! conn
        ["INSERT INTO branch_lineage (conversation_id, record) VALUES (?, ?)
          ON CONFLICT(conversation_id) DO UPDATE SET record = excluded.record"
         (:id rec) (pr-str rec)]))
    nil)
  (lineage-get [_ conv-id]
    (locking conn
      (some-> (jdbc/execute-one! conn
                ["SELECT record FROM branch_lineage WHERE conversation_id = ?" conv-id])
              :branch_lineage/record
              edn/read-string)))
  (lineage-children [_ conv-id]
    (locking conn
      (->> (jdbc/execute! conn ["SELECT record FROM branch_lineage"])
           (mapv #(edn/read-string (:branch_lineage/record %)))
           (filterv #(= conv-id (:parent %))))))
  (lineage-remove! [_ conv-id]
    (locking conn
      (jdbc/execute! conn
        ["DELETE FROM branch_lineage WHERE conversation_id = ?" conv-id]))
    nil)

  java.io.Closeable
  (close [_] (.close ^java.sql.Connection conn)))

(defn sqlite-lineage-store
  "SQLite 后端的 LineageStore（可与 ChatMemory / PauseStore 同库不同表）。"
  [db-path]
  (let [conn (jdbc/get-connection {:dbtype "sqlite" :dbname db-path})]
    (ensure-schema! conn)
    (->SqliteLineageStore conn)))

;;; ============================================================
;;; 分支操作
;;; ============================================================

(defn fork!
  "在 src-conv-id 上开分支：前缀复制到新 conversation-id + 血缘记录。

   deps: {:memory ChatMemory必填 :pause-store 可选 :lineage 可选}
   opts:
   - :at  前缀长度（消息条数）；缺省全量。**合法 fork 点是 turn 边界/暂停点**
          （§12.2 不变量，:at 应落在完整 turn 的消息边界上，由调用方保证）
   - :as  新 conversation-id；缺省随机

   全量 fork 且源处于暂停时，PauseStore 快照连带复制（快照属于日志尖端；
   部分前缀 fork 不带）——两个分支可各自 resume 不同决策。

   返回新 conversation-id。"
  [{:keys [memory pause-store lineage]} src-conv-id
   & [{:keys [at as]}]]
  (let [msgs (memory/mem-get memory src-conv-id)
        _ (when (empty? msgs)
            (throw (ex-info (str "源会话无历史: " src-conv-id) {:id src-conv-id})))
        n (or at (count msgs))
        _ (when-not (<= 1 n (count msgs))
            (throw (ex-info (str ":at 越界: " at) {:at at :count (count msgs)})))
        new-id (or as (str "branch-" (java.util.UUID/randomUUID)))
        _ (when (seq (memory/mem-get memory new-id))
            (throw (ex-info (str "目标会话已存在: " new-id) {:id new-id})))
        full? (= n (count msgs))]
    (memory/mem-add memory new-id (vec (take n msgs)))
    ;; 暂停快照属于日志尖端：只有全量 fork 才连带
    (when (and full? pause-store)
      (when-let [snap (pause/pause-load pause-store src-conv-id)]
        (pause/pause-save! pause-store new-id
                           (assoc snap :conversation-id new-id))))
    (when lineage
      (lineage-add! lineage {:id new-id
                             :parent src-conv-id
                             :fork-point n
                             :created-at (System/currentTimeMillis)}))
    new-id))

(defn rollback!
  "破坏性截断：conv-id 的历史保留前 n 条（\"重新生成\"场景；要无损请用 fork!）。
   同时清除该会话的暂停快照（日志尖端已变，未决暂停失效）。
   实现为 clear + 重写（现有 ChatMemory 协议即可；非原子，勿并发使用）。"
  [{:keys [memory pause-store]} conv-id n]
  (let [msgs (memory/mem-get memory conv-id)]
    (when-not (<= 0 n (count msgs))
      (throw (ex-info (str "n 越界: " n) {:n n :count (count msgs)})))
    (memory/mem-clear memory conv-id)
    (when (pos? n)
      (memory/mem-add memory conv-id (vec (take n msgs))))
    (when pause-store
      (pause/pause-clear! pause-store conv-id))
    nil))

(defn lineage
  "该会话的血缘记录 {:id :parent :fork-point :created-at}；根会话为 nil。"
  [{:keys [lineage]} conv-id]
  (when lineage (lineage-get lineage conv-id)))

(defn ancestry
  "自身到根的血缘链 [自身记录 父记录 ...]（根会话无记录，链止于最老的 fork）。"
  [{:keys [lineage]} conv-id]
  (when lineage
    (loop [id conv-id, chain []]
      (if-let [rec (lineage-get lineage id)]
        (recur (:parent rec) (conj chain rec))
        chain))))

(defn prune!
  "删除分支：历史 + 暂停快照 + 血缘记录。有直接子分支时拒绝
   （先 prune 子分支，或改挂血缘后再删）。"
  [{:keys [memory pause-store lineage]} conv-id]
  (when lineage
    (let [children (lineage-children lineage conv-id)]
      (when (seq children)
        (throw (ex-info (str "分支有子分支，拒绝删除: " conv-id)
                        {:id conv-id :children (mapv :id children)})))))
  (memory/mem-clear memory conv-id)
  (when pause-store (pause/pause-clear! pause-store conv-id))
  (when lineage (lineage-remove! lineage conv-id))
  nil)
