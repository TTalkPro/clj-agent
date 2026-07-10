(ns im.ttalk.agent.timeline.sqlite
  "Timeline 的 SQLite 持久化 store（按需加载；EDN 序列化）。

   - entry :data 与 meta 值须可 EDN 往返（pr-str / clojure.edn/read-string）——
     含函数/record 等不可读值会在写入时抛错，调用方需先剥离
     （process.snapshot 的 checkpointer 已自动剥 context 里的 :kernel）。
   - 共享 Connection 用 locking 串行化（同 client 模块 memory/sqlite 的并发策略）。

   (def store (sqlite-store \"timeline.db\"))   ;; \":memory:\" 为进程内库
   (def mgr (tl/manager store))"
  (:require [clojure.edn :as edn]
            [next.jdbc :as jdbc]
            [im.ttalk.agent.timeline :as tl]))

(set! *warn-on-reflection* true)

(defn- init-schema! [conn]
  (jdbc/execute! conn
    ["CREATE TABLE IF NOT EXISTS timeline_entries (
        owner_id   TEXT NOT NULL,
        id         TEXT NOT NULL,
        parent_id  TEXT,
        version    INTEGER NOT NULL,
        branch_id  TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        entry_edn  TEXT NOT NULL,
        PRIMARY KEY (owner_id, id))"])
  (jdbc/execute! conn
    ["CREATE INDEX IF NOT EXISTS idx_timeline_owner
        ON timeline_entries (owner_id, branch_id, version)"])
  (jdbc/execute! conn
    ["CREATE TABLE IF NOT EXISTS timeline_meta (
        owner_id TEXT NOT NULL,
        k        TEXT NOT NULL,
        v_edn    TEXT NOT NULL,
        PRIMARY KEY (owner_id, k))"]))

(defn- row->entry [row]
  (edn/read-string (:timeline_entries/entry_edn row)))

(defrecord SQLiteTimelineStore [conn]
  tl/TimelineStore
  (put-entry! [_ entry]
    (locking conn
      (jdbc/execute! conn
        ["INSERT INTO timeline_entries
            (owner_id, id, parent_id, version, branch_id, created_at, entry_edn)
          VALUES (?,?,?,?,?,?,?)
          ON CONFLICT(owner_id, id) DO UPDATE SET entry_edn = excluded.entry_edn"
         (:owner-id entry) (:id entry) (:parent-id entry)
         (:version entry) (:branch-id entry) (:created-at entry)
         (pr-str entry)]))
    nil)
  (get-entry [_ owner-id id]
    (locking conn
      (some-> (jdbc/execute-one! conn
                ["SELECT entry_edn FROM timeline_entries WHERE owner_id = ? AND id = ?"
                 owner-id id])
              row->entry)))
  (entries [_ owner-id]
    (locking conn
      (mapv row->entry
            (jdbc/execute! conn
              ["SELECT entry_edn FROM timeline_entries WHERE owner_id = ?" owner-id]))))
  (delete-entry! [_ owner-id id]
    (locking conn
      (jdbc/execute! conn
        ["DELETE FROM timeline_entries WHERE owner_id = ? AND id = ?" owner-id id]))
    nil)
  (put-meta! [_ owner-id k v]
    (locking conn
      (jdbc/execute! conn
        ["INSERT INTO timeline_meta (owner_id, k, v_edn) VALUES (?,?,?)
          ON CONFLICT(owner_id, k) DO UPDATE SET v_edn = excluded.v_edn"
         owner-id (pr-str k) (pr-str v)]))
    nil)
  (get-meta [_ owner-id k]
    (locking conn
      (some-> (jdbc/execute-one! conn
                ["SELECT v_edn FROM timeline_meta WHERE owner_id = ? AND k = ?"
                 owner-id (pr-str k)])
              :timeline_meta/v_edn
              edn/read-string)))

  java.io.Closeable
  (close [_] (.close ^java.sql.Connection conn)))

(defn sqlite-store
  "创建 SQLite 后端的 Timeline store（首次调用自动建表）。

   参数:
   - db-path: SQLite 文件路径（\":memory:\" 为进程内库）

   返回: 实现 TimelineStore + java.io.Closeable 的 store。"
  [db-path]
  (let [conn (jdbc/get-connection {:dbtype "sqlite" :dbname db-path})]
    (init-schema! conn)
    (->SQLiteTimelineStore conn)))
