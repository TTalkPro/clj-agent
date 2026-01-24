(ns im.ttalk.agent.memory.store.sqlite
  "SQLiteStore - 基于 SQLite 的持久化存储实现

   适用场景：
   - 生产环境单机部署
   - 需要持久化的场景
   - 无需外部服务的嵌入式存储

   特点：
   - 持久化存储，进程重启不丢失
   - 轻量级，无需额外服务
   - 支持事务和并发（WAL 模式）
   - 基于文件，易于备份

   使用示例：
   (def store (create-sqlite-store \"data.db\"))
   (proto/put store \"user-123\" \"preference\" {:lang \"zh\"})"
  (:require [im.ttalk.agent.memory.protocol :as proto]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [cheshire.core :as json]))

;; =============================================================================
;; 数据库 Schema
;; =============================================================================

(def ^:private schema-sql
  ["PRAGMA journal_mode=WAL"

   "CREATE TABLE IF NOT EXISTS kv_store (
      id TEXT PRIMARY KEY,
      namespace TEXT NOT NULL,
      key TEXT NOT NULL,
      value TEXT NOT NULL,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL,
      UNIQUE(namespace, key)
    )"

   "CREATE INDEX IF NOT EXISTS idx_kv_namespace ON kv_store(namespace)"
   "CREATE INDEX IF NOT EXISTS idx_kv_key ON kv_store(key)"
   "CREATE INDEX IF NOT EXISTS idx_kv_ns_key ON kv_store(namespace, key)"
   "CREATE INDEX IF NOT EXISTS idx_kv_updated ON kv_store(updated_at)"])

;; =============================================================================
;; 辅助函数
;; =============================================================================

(defn- generate-id []
  (str (java.util.UUID/randomUUID)))

(defn- now []
  (System/currentTimeMillis))

(defn- init-db! [ds]
  (doseq [sql schema-sql]
    (jdbc/execute! ds [sql]))
  ds)

(defn- serialize [value]
  (json/generate-string value))

(defn- deserialize [s]
  (when s
    (json/parse-string s true)))

(defn- row->record [row]
  (when row
    {:namespace (:namespace row)
     :key (:key row)
     :value (deserialize (:value row))
     :created-at (:created_at row)
     :updated-at (:updated_at row)}))

(defn- build-prefix-pattern [prefix]
  (str prefix "%"))

;; =============================================================================
;; SQLiteStore 实现
;; =============================================================================

(defrecord SQLiteStore [datasource config]
  proto/IKeyValueStore

  ;; -------------------------------------------------------------------------
  ;; CRUD 操作
  ;; -------------------------------------------------------------------------

  (put [this namespace key value]
    (let [id (generate-id)
          timestamp (now)
          value-json (serialize value)]
      (jdbc/execute! datasource
                     ["INSERT INTO kv_store (id, namespace, key, value, created_at, updated_at)
                       VALUES (?, ?, ?, ?, ?, ?)
                       ON CONFLICT(namespace, key) DO UPDATE SET
                         value = excluded.value,
                         updated_at = excluded.updated_at"
                      id namespace key value-json timestamp timestamp])
      {:namespace namespace
       :key key
       :value value
       :created-at timestamp
       :updated-at timestamp}))

  (put-batch [this namespace items]
    (mapv (fn [{:keys [key value]}]
            (proto/put this namespace key value))
          items))

  (get-value [this namespace key]
    (when-let [row (jdbc/execute-one! datasource
                                      ["SELECT value FROM kv_store WHERE namespace = ? AND key = ?"
                                       namespace key]
                                      {:builder-fn rs/as-unqualified-lower-maps})]
      (deserialize (:value row))))

  (get-batch [this namespace keys]
    (reduce (fn [result k]
              (if-let [v (proto/get-value this namespace k)]
                (assoc result k v)
                result))
            {}
            keys))

  (delete [this namespace key]
    (let [result (jdbc/execute! datasource
                                ["DELETE FROM kv_store WHERE namespace = ? AND key = ?"
                                 namespace key])]
      (pos? (::jdbc/update-count (first result)))))

  (delete-batch [this namespace keys]
    (if (empty? keys)
      0
      (let [placeholders (clojure.string/join "," (repeat (count keys) "?"))
            sql (format "DELETE FROM kv_store WHERE namespace = ? AND key IN (%s)" placeholders)
            result (jdbc/execute! datasource (into [sql namespace] keys))]
        (::jdbc/update-count (first result)))))

  (exists? [this namespace key]
    (some? (jdbc/execute-one! datasource
                              ["SELECT 1 FROM kv_store WHERE namespace = ? AND key = ? LIMIT 1"
                               namespace key])))

  ;; -------------------------------------------------------------------------
  ;; 查询操作
  ;; -------------------------------------------------------------------------

  (list-keys [this namespace opts]
    (let [{:keys [prefix limit offset]
           :or {limit 100 offset 0}} opts
          rows (if prefix
                 (jdbc/execute! datasource
                                ["SELECT key FROM kv_store
                                  WHERE namespace = ? AND key LIKE ?
                                  ORDER BY key
                                  LIMIT ? OFFSET ?"
                                 namespace (build-prefix-pattern prefix) limit offset]
                                {:builder-fn rs/as-unqualified-lower-maps})
                 (jdbc/execute! datasource
                                ["SELECT key FROM kv_store
                                  WHERE namespace = ?
                                  ORDER BY key
                                  LIMIT ? OFFSET ?"
                                 namespace limit offset]
                                {:builder-fn rs/as-unqualified-lower-maps}))]
      (mapv :key rows)))

  (list-values [this namespace opts]
    (let [{:keys [prefix limit offset]
           :or {limit 100 offset 0}} opts
          rows (if prefix
                 (jdbc/execute! datasource
                                ["SELECT * FROM kv_store
                                  WHERE namespace = ? AND key LIKE ?
                                  ORDER BY updated_at DESC
                                  LIMIT ? OFFSET ?"
                                 namespace (build-prefix-pattern prefix) limit offset]
                                {:builder-fn rs/as-unqualified-lower-maps})
                 (jdbc/execute! datasource
                                ["SELECT * FROM kv_store
                                  WHERE namespace = ?
                                  ORDER BY updated_at DESC
                                  LIMIT ? OFFSET ?"
                                 namespace limit offset]
                                {:builder-fn rs/as-unqualified-lower-maps}))]
      (mapv row->record rows)))

  (search [this namespace query opts]
    ;; SQLite 简单实现：LIKE 搜索
    (let [{:keys [top-k] :or {top-k 10}} opts
          query-str (:query query)
          rows (jdbc/execute! datasource
                              ["SELECT * FROM kv_store
                                WHERE namespace = ? AND value LIKE ?
                                ORDER BY updated_at DESC
                                LIMIT ?"
                               namespace (str "%" query-str "%") top-k]
                              {:builder-fn rs/as-unqualified-lower-maps})]
      (mapv (fn [row]
              {:key (:key row)
               :value (deserialize (:value row))
               :score 1.0})
            rows)))

  (count-keys [this namespace opts]
    (let [{:keys [prefix]} opts
          result (if prefix
                   (jdbc/execute-one! datasource
                                      ["SELECT COUNT(*) as cnt FROM kv_store
                                        WHERE namespace = ? AND key LIKE ?"
                                       namespace (build-prefix-pattern prefix)]
                                      {:builder-fn rs/as-unqualified-lower-maps})
                   (jdbc/execute-one! datasource
                                      ["SELECT COUNT(*) as cnt FROM kv_store WHERE namespace = ?"
                                       namespace]
                                      {:builder-fn rs/as-unqualified-lower-maps}))]
      (or (:cnt result) 0)))

  ;; -------------------------------------------------------------------------
  ;; 生命周期
  ;; -------------------------------------------------------------------------

  (init! [this]
    (init-db! datasource)
    this)

  (close! [this]
    nil)

  (healthy? [this]
    (try
      (jdbc/execute-one! datasource ["SELECT 1"])
      true
      (catch Exception _
        false))))

;; =============================================================================
;; 工厂函数
;; =============================================================================

(defn create-sqlite-store
  "创建 SQLiteStore 实例

   参数：
   - db-path: 数据库文件路径

   选项：
   - :auto-init? 是否自动初始化（默认 true）

   示例：
   (def store (create-sqlite-store \"data.db\"))"
  ([db-path]
   (create-sqlite-store db-path {}))
  ([db-path config]
   (let [ds (jdbc/get-datasource {:dbtype "sqlite" :dbname db-path})
         store (->SQLiteStore ds config)]
     (if (get config :auto-init? true)
       (proto/init! store)
       store))))
