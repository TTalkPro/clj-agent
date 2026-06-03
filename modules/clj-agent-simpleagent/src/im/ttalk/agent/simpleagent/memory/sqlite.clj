(ns im.ttalk.agent.simpleagent.memory.sqlite
  "持久化 ChatMemory - SQLite 后端

   把中立消息（见 core/llm/message）按 conversation-id 持久化到 SQLite，
   进程重启后仍可恢复历史（配合 create-agent 的 :conversation-id 实现按用户恢复）。

   消息以 EDN 字符串存储（中立消息是纯 Clojure 数据，EDN 可无损往返）。

   表结构：
     chat_messages(id INTEGER PK AUTOINCREMENT, conversation_id TEXT, content TEXT)

   用法：
     (require '[im.ttalk.agent.simpleagent.memory.sqlite :as sqlite])
     (def store (sqlite/sqlite-store \"agent.db\"))
     (create-agent {:provider p :memory store :conversation-id \"user-123\"})"
  (:require [clojure.edn :as edn]
            [next.jdbc :as jdbc]
            [im.ttalk.agent.simpleagent.memory :as memory]
            [im.ttalk.agent.core.llm.message :as msg]))

(defn- ensure-schema! [ds]
  (jdbc/execute! ds
    ["CREATE TABLE IF NOT EXISTS chat_messages (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        conversation_id TEXT NOT NULL,
        content TEXT NOT NULL)"])
  (jdbc/execute! ds
    ["CREATE INDEX IF NOT EXISTS idx_chat_conv
        ON chat_messages(conversation_id, id)"]))

(defrecord SqliteStore [ds]
  memory/ChatMemory
  (mem-get [_ conv-id]
    (->> (jdbc/execute! ds
           ["SELECT content FROM chat_messages WHERE conversation_id = ? ORDER BY id"
            conv-id])
         (mapv (fn [row] (edn/read-string (:chat_messages/content row))))))
  (mem-add [_ conv-id new-msgs]
    (when (seq new-msgs)
      (jdbc/with-transaction [tx ds]
        (doseq [m new-msgs]
          (jdbc/execute! tx
            ["INSERT INTO chat_messages (conversation_id, content) VALUES (?, ?)"
             conv-id (pr-str (msg/normalize m))]))))
    nil)
  (mem-clear [_ conv-id]
    (jdbc/execute! ds
      ["DELETE FROM chat_messages WHERE conversation_id = ?" conv-id])
    nil))

(defn sqlite-store
  "创建 SQLite 后端的 ChatMemory。

   参数:
   - db-path: SQLite 文件路径（如 \"agent.db\"；\":memory:\" 为进程内库）

   返回: 实现 ChatMemory 协议的 store（首次调用自动建表）"
  [db-path]
  (let [ds (jdbc/get-datasource {:dbtype "sqlite" :dbname db-path})]
    (ensure-schema! ds)
    (->SqliteStore ds)))
