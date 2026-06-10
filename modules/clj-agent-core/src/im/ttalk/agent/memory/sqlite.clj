(ns im.ttalk.agent.memory.sqlite
  "持久化 ChatMemory - SQLite 后端

   把中立消息（见 core/llm/message）按 conversation-id 持久化到 SQLite，
   进程重启后仍可恢复历史（配合 create-agent 的 :conversation-id 实现按用户恢复）。

   消息以 EDN 字符串存储（中立消息是纯 Clojure 数据，EDN 可无损往返）。

   表结构：
     chat_messages(id INTEGER PK AUTOINCREMENT, conversation_id TEXT, content TEXT)

   用法：
     (require '[im.ttalk.agent.memory.sqlite :as sqlite])
     (def store (sqlite/sqlite-store \"agent.db\"))
     (create-agent {:provider p :memory store :conversation-id \"user-123\"})"
  (:require [clojure.edn :as edn]
            [next.jdbc :as jdbc]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.model.message :as msg]))

(defn- ensure-schema! [ds]
  (jdbc/execute! ds
    ["CREATE TABLE IF NOT EXISTS chat_messages (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        conversation_id TEXT NOT NULL,
        content TEXT NOT NULL)"])
  (jdbc/execute! ds
    ["CREATE INDEX IF NOT EXISTS idx_chat_conv
        ON chat_messages(conversation_id, id)"]))

;; conn 为单个常开 java.sql.Connection（而非每次操作开新连接的 datasource）：
;; - 修复 :memory: 库——datasource 模式下每次操作是独立内存库，建表后数据即丢失；
;;   常开连接让进程内库在 store 生命周期内持续可用。
;; - 文件型库避免每次 mem-get/mem-add 都开/关连接的开销。
;;
;; 线程安全：java.sql.Connection 不保证线程安全，且 with-transaction 会切换其
;; autocommit 全局状态——多线程对同一 store 并发操作会语句交叉 / 报错。故所有操作
;; 用 (locking conn ...) 串行化（同一 store 内互斥；SQLite 写入本就串行，开销可忽略）。
(defrecord SqliteStore [conn]
  memory/ChatMemory
  (mem-get [_ conv-id]
    (locking conn
      (->> (jdbc/execute! conn
             ["SELECT content FROM chat_messages WHERE conversation_id = ? ORDER BY id"
              conv-id])
           (mapv (fn [row] (edn/read-string (:chat_messages/content row)))))))
  (mem-add [_ conv-id new-msgs]
    (when (seq new-msgs)
      (locking conn
        (jdbc/with-transaction [tx conn]
          (doseq [m new-msgs]
            (jdbc/execute! tx
              ["INSERT INTO chat_messages (conversation_id, content) VALUES (?, ?)"
               conv-id (pr-str (msg/normalize m))])))))
    nil)
  (mem-clear [_ conv-id]
    (locking conn
      (jdbc/execute! conn
        ["DELETE FROM chat_messages WHERE conversation_id = ?" conv-id]))
    nil)

  java.io.Closeable
  (close [_] (.close conn)))

(defn sqlite-store
  "创建 SQLite 后端的 ChatMemory。

   参数:
   - db-path: SQLite 文件路径（如 \"agent.db\"；\":memory:\" 为进程内库）

   返回: 实现 ChatMemory + java.io.Closeable 的 store（首次调用自动建表）。
   持有单个常开连接，用完请 (close-store! store) 或用 (with-open [s ...] ...) 释放。"
  [db-path]
  (let [conn (jdbc/get-connection {:dbtype "sqlite" :dbname db-path})]
    (ensure-schema! conn)
    (->SqliteStore conn)))

(defn close-store!
  "关闭 store 持有的数据库连接，释放资源。"
  [store]
  (when-let [conn (:conn store)]
    (.close ^java.sql.Connection conn))
  nil)
