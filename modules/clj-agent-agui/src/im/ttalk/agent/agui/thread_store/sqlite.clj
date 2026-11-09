(ns im.ttalk.agent.agui.thread-store.sqlite
  "线程元数据存储 —— SQLite 后端（designs/agui-borrow.md §6）。**按需加载**：
   不 require 本 ns 就不碰 JDBC。

   **零新依赖**：`next.jdbc` / `sqlite-jdbc` 本来就是 `clj-agent-client` 的依赖
   （`im.ttalk.agent.memory.sqlite` 在用），本模块依赖 client，传递可得。

   可以与 `memory.sqlite` 共用一个库文件（表名不撞）——于是重启之后侧栏的线程
   列表与点进去看到的历史出自同一个文件。

   表结构：
     agui_threads(id TEXT PK, agent_id TEXT, name TEXT, archived INTEGER,
                  created_at INTEGER, updated_at INTEGER)

   ⛔ 只存元数据，不存消息与事件（见 `agui.thread-store` 的 ns docstring）。

   ```clojure
   (require '[im.ttalk.agent.agui.thread-store.sqlite :as ts-sqlite])
   (def store (ts-sqlite/sqlite-thread-store \"agent.db\"))
   ```"
  (:require [clojure.string :as str]
            [im.ttalk.agent.agui.thread-store :as ts]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(set! *warn-on-reflection* true)

(defn- ensure-schema! [ds]
  (jdbc/execute! ds
    ["CREATE TABLE IF NOT EXISTS agui_threads (
        id TEXT PRIMARY KEY,
        agent_id TEXT,
        name TEXT,
        archived INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL)"])
  (jdbc/execute! ds
    ["CREATE INDEX IF NOT EXISTS idx_agui_threads_updated
        ON agui_threads(updated_at DESC, id)"]))

(defn- row->record [row]
  (when row
    {:id (:agui_threads/id row)
     :agent-id (:agui_threads/agent_id row)
     :name (:agui_threads/name row)
     :archived? (= 1 (long (or (:agui_threads/archived row) 0)))
     :created-at (long (:agui_threads/created_at row))
     :updated-at (long (:agui_threads/updated_at row))}))

(defn- get* [conn thread-id]
  (row->record (jdbc/execute-one! conn ["SELECT * FROM agui_threads WHERE id = ?" thread-id])))

;; 常开连接 + `locking`：与 `memory.sqlite` 同一取舍（`:memory:` 库要常开才不丢表；
;; `java.sql.Connection` 不保证线程安全）
(defrecord SqliteThreadStore [conn now]
  ts/ThreadStore
  (ts-slots [_] ts/all-slots)
  (ts-touch! [_ thread-id {:keys [agent-id] :as opts}]
    (let [t (long (or (:now opts) (now)))]
      (locking conn
        ;; agent_id 只在建时写：ON CONFLICT 那支只刷 updated_at
        (jdbc/execute! conn
          ["INSERT INTO agui_threads (id, agent_id, name, archived, created_at, updated_at)
            VALUES (?, ?, NULL, 0, ?, ?)
            ON CONFLICT(id) DO UPDATE SET updated_at = excluded.updated_at"
           thread-id agent-id t t])
        (get* conn thread-id))))
  (ts-get [_ thread-id]
    (locking conn (get* conn thread-id)))
  (ts-list [_ query]
    (let [{:keys [agent-id include-archived? limit offset]} (ts/normalize-query query)
          where (cond-> []
                  agent-id (conj "agent_id = ?")
                  (not include-archived?) (conj "archived = 0"))
          where-sql (if (seq where) (str " WHERE " (str/join " AND " where)) "")
          params (cond-> [] agent-id (conj agent-id))]
      (locking conn
        (let [total (long (:n (jdbc/execute-one! conn (into [(str "SELECT COUNT(*) AS n FROM agui_threads" where-sql)] params)
                                                 {:builder-fn rs/as-unqualified-maps})))
              rows (jdbc/execute! conn
                                  (into [(str "SELECT * FROM agui_threads" where-sql
                                              " ORDER BY updated_at DESC, id ASC LIMIT ? OFFSET ?")]
                                        (conj params (or limit -1) offset)))
              page (mapv row->record rows)
              next-offset (+ offset (count page))]
          {:threads page
           :next-cursor (when (< next-offset total) (str next-offset))}))))
  (ts-update! [_ thread-id updates]
    (locking conn
      (when-let [r (get* conn thread-id)]
        (let [r' (ts/apply-updates r updates (long (now)))]
          (jdbc/execute! conn
            ["UPDATE agui_threads SET name = ?, archived = ?, updated_at = ? WHERE id = ?"
             (:name r') (if (:archived? r') 1 0) (:updated-at r') thread-id])
          r'))))
  (ts-delete! [_ thread-id]
    (locking conn
      (let [res (jdbc/execute-one! conn ["DELETE FROM agui_threads WHERE id = ?" thread-id])]
        (pos? (long (or (:next.jdbc/update-count res) 0))))))

  java.io.Closeable
  (close [_] (.close ^java.sql.Connection conn)))

(defn sqlite-thread-store
  "SQLite 后端的线程存储。`db-path` 如 `\"agent.db\"`（`\":memory:\"` 为进程内库）。

   持有一个常开连接，用完 `(.close store)` 或 `with-open`。

   - `:now` `(fn [] ms)`，可注入（测试）"
  ([db-path] (sqlite-thread-store db-path nil))
  ([db-path {:keys [now]}]
   (let [conn (jdbc/get-connection {:dbtype "sqlite" :dbname db-path})]
     (ensure-schema! conn)
     (->SqliteThreadStore conn (or now #(System/currentTimeMillis))))))
