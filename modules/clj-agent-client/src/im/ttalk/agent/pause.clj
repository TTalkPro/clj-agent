(ns im.ttalk.agent.pause
  "HITL 暂停态持久化 - PauseStore（设计见 docs/agent-loop-concurrency-design.md §11）

   Agent 暂停（审批 / :env-retry 环境失败）后，暂停快照按 conversation-id
   持久化；进程重启后用同一 conversation-id + 同一 store 重建 agent，
   resume 透明恢复（client/paused?、client/resume 自动回落到 store）。

   快照是纯 EDN 数据（loop-state/pending-tool 本就不含函数；chat-client/gate/
   callbacks 由代码侧在 resume 时重新提供）。tool-context 存档前剥离
   不可 EDN 序列化的 value（如 :chat-client），恢复时由调用方按需注回。

   **loop-state 与 pending-tool 不走剥离，必须自身可 EDN 往返**——尤其
   **不能放 record**：record 打印成 `#ns.Foo{...}`，`edn/read-string` 没有对应
   reader tag 会**抛**，于是 `sqlite-pause-store` 的 `pause-load` 整份快照读不
   回来。而 `in-memory-pause-store` 直接存对象、毫发无伤——「单进程测试全绿，
   重启后 resume 崩」正是这个组合的形态。护栏在 `pause-test` 的
   `loop-state-edn-roundtrip-test`（审批与 :env-retry 两个 phase 各钉一次
   `pr-str` → `read-string` → resume）。构造点见 react.clj 的 `env-pause`
   与 run-tool-loop 的审批暂停分支。

   边界：只持久化「暂停点」这个一致快照；批次执行中途的进程崩溃恢复
   （durable execution）不在此范围。

   用法：
     (def ps (sqlite-pause-store \"agent.db\"))   ;; 或 (in-memory-pause-store)
     (create-agent {:provider p :memory (sqlite/sqlite-store \"agent.db\")
                    :pause-store ps :conversation-id \"order-42\" ...})
     ;; 暂停自动落库；重启后同配置重建 agent → (resume agent \"approved\")"
  (:require [clojure.edn :as edn]
            [next.jdbc :as jdbc]
            [taoensso.timbre :as log]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 协议
;;; ============================================================

(defprotocol PauseStore
  "暂停快照的持久化（每 conversation-id 至多一份——同会话再次暂停覆盖）。"
  (pause-save!  [store conv-id snapshot] "保存/覆盖暂停快照。返回 nil。")
  (pause-load   [store conv-id]          "读取暂停快照；无则 nil。")
  (pause-clear! [store conv-id]          "删除暂停快照。返回 nil。"))

;;; ============================================================
;;; 快照构造
;;; ============================================================

(defn- edn-safe?
  "value 能否无损 EDN 往返（函数/连接/Java 对象等不能）。"
  [v]
  (try (= v (edn/read-string (pr-str v)))
       (catch Throwable _ false)))

(defn strip-unserializable
  "剥掉 map 中不可 EDN 序列化的 entry（如 context 里的 :chat-client），
   返回 [clean-map stripped-keys]。"
  [m]
  (reduce-kv (fn [[clean stripped] k v]
               (if (edn-safe? v)
                 [(assoc clean k v) stripped]
                 [clean (conj stripped k)]))
             [{} []]
             (or m {})))

(defn snapshot
  "由 react 层的 :paused 结果构造可持久化快照（纯 EDN 数据）。
   tool-context 中不可序列化的 key 被剥离并 warn（恢复时由代码侧注回）。

   **只有 tool-context 走剥离**：它装的是调用方的任意状态，混进 `:chat-client` 这类
   活对象是正常的。`loop-state` / `pending-tool` 则完全由 react 构造，形状是框架
   自己的责任——剥离它们只会得到一个字段残缺、resume 到一半失败的快照，比读取
   时当场抛更难查。所以这里不检查、不剥离，由 ns docstring 的约束 +
   `loop-state-edn-roundtrip-test` 在**构造侧**保证。"
  [conv-id paused-result]
  (let [[ctx stripped] (strip-unserializable (:tool-context paused-result))]
    (when (seq stripped)
      (log/warn "暂停快照剥离了不可序列化的 context key（恢复时请自行注回）:" stripped))
    {:version         1
     :conversation-id conv-id
     :paused-at       (System/currentTimeMillis)
     :pause-reason    (:pause-reason paused-result)
     :pending-tool    (:pending-tool paused-result)
     :loop-state      (:loop-state paused-result)
     :tool-context    ctx}))

;;; ============================================================
;;; In-memory 实现（测试 / 单进程）
;;; ============================================================

(defrecord InMemoryPauseStore [state]
  PauseStore
  (pause-save!  [_ conv-id snap] (swap! state assoc conv-id snap) nil)
  (pause-load   [_ conv-id]      (get @state conv-id))
  (pause-clear! [_ conv-id]      (swap! state dissoc conv-id) nil))

(defn in-memory-pause-store
  "进程内 PauseStore（跨重启恢复请用 sqlite-pause-store）。"
  []
  (->InMemoryPauseStore (atom {})))

;;; ============================================================
;;; SQLite 实现（EDN 一列；线程安全策略同 memory.sqlite：locking 串行化）
;;; ============================================================

(defn- ensure-schema! [ds]
  (jdbc/execute! ds
    ["CREATE TABLE IF NOT EXISTS pause_state (
        conversation_id TEXT PRIMARY KEY,
        snapshot TEXT NOT NULL,
        created_at INTEGER NOT NULL)"]))

(defrecord SqlitePauseStore [conn]
  PauseStore
  (pause-save! [_ conv-id snap]
    (locking conn
      (jdbc/execute! conn
        ["INSERT INTO pause_state (conversation_id, snapshot, created_at)
          VALUES (?, ?, ?)
          ON CONFLICT(conversation_id) DO UPDATE SET
            snapshot = excluded.snapshot, created_at = excluded.created_at"
         conv-id (pr-str snap) (System/currentTimeMillis)]))
    nil)
  (pause-load [_ conv-id]
    (locking conn
      (some-> (jdbc/execute-one! conn
                ["SELECT snapshot FROM pause_state WHERE conversation_id = ?" conv-id])
              :pause_state/snapshot
              edn/read-string)))
  (pause-clear! [_ conv-id]
    (locking conn
      (jdbc/execute! conn
        ["DELETE FROM pause_state WHERE conversation_id = ?" conv-id]))
    nil)

  java.io.Closeable
  (close [_] (.close ^java.sql.Connection conn)))

(defn sqlite-pause-store
  "创建 SQLite 后端的 PauseStore（快照 EDN 一列，同会话覆盖）。

   参数:
   - db-path: SQLite 文件路径（可与 ChatMemory 的 sqlite-store 同库不同表）

   返回: 实现 PauseStore + java.io.Closeable 的 store（首次调用自动建表）。"
  [db-path]
  (let [conn (jdbc/get-connection {:dbtype "sqlite" :dbname db-path})]
    (ensure-schema! conn)
    (->SqlitePauseStore conn)))
