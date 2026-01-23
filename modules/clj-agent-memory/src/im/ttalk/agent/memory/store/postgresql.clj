(ns im.ttalk.agent.memory.store.postgresql
  "PostgresStore - 基于 PostgreSQL 的企业级存储实现

   对应 LangChain PostgresStore。

   适用场景：
   - 生产环境分布式部署
   - 高可用要求
   - 大规模数据存储
   - 需要高级查询能力

   特点：
   - 企业级数据库，可靠性高
   - 支持 JSONB 类型，查询性能好
   - 支持连接池，适合高并发
   - 支持 GIN 索引，支持复杂查询

   使用示例：
   (def store (create-postgres-store
                {:dbtype \"postgresql\"
                 :dbname \"agent_db\"
                 :host \"localhost\"
                 :user \"postgres\"
                 :password \"secret\"}))"
  (:require [im.ttalk.agent.memory.protocol :as proto]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [cheshire.core :as json]))

;; =============================================================================
;; 数据库 Schema
;; =============================================================================

(def ^:private schema-sql
  ["CREATE TABLE IF NOT EXISTS kv_store (
      id TEXT PRIMARY KEY,
      namespace TEXT NOT NULL,
      key TEXT NOT NULL,
      value JSONB NOT NULL,
      created_at BIGINT NOT NULL,
      updated_at BIGINT NOT NULL,
      UNIQUE(namespace, key)
    )"

   "CREATE INDEX IF NOT EXISTS idx_kv_namespace ON kv_store(namespace)"
   "CREATE INDEX IF NOT EXISTS idx_kv_key ON kv_store(key)"
   "CREATE INDEX IF NOT EXISTS idx_kv_ns_key ON kv_store(namespace, key)"
   "CREATE INDEX IF NOT EXISTS idx_kv_updated ON kv_store(updated_at)"
   "CREATE INDEX IF NOT EXISTS idx_kv_value ON kv_store USING GIN (value)"])

;; =============================================================================
;; 辅助函数
;; =============================================================================

(defn- generate-id []
  (str (java.util.UUID/randomUUID)))

(defn- now []
  (System/currentTimeMillis))

(defn- init-db! [ds]
  (doseq [sql schema-sql]
    (try
      (jdbc/execute! ds [sql])
      (catch Exception e
        (when-not (re-find #"already exists" (.getMessage e))
          (throw e)))))
  ds)

(defn- serialize [value]
  (json/generate-string value))

(defn- deserialize [v]
  (cond
    (nil? v) nil
    (string? v) (json/parse-string v true)
    :else v))

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
;; PostgresStore 实现
;; =============================================================================

(defrecord PostgresStore [datasource config]
  proto/IStore

  (kv-put [this namespace key value]
    (let [id (generate-id)
          timestamp (now)
          value-json (serialize value)]
      (jdbc/execute! datasource
                     ["INSERT INTO kv_store (id, namespace, key, value, created_at, updated_at)
                       VALUES ($1, $2, $3, $4::jsonb, $5, $6)
                       ON CONFLICT(namespace, key) DO UPDATE SET
                         value = EXCLUDED.value,
                         updated_at = EXCLUDED.updated_at"
                      id namespace key value-json timestamp timestamp])
      {:namespace namespace
       :key key
       :value value
       :created-at timestamp
       :updated-at timestamp}))

  (kv-put-batch [this namespace items]
    (mapv (fn [{:keys [key value]}]
            (proto/kv-put this namespace key value))
          items))

  (kv-get [this namespace key]
    (when-let [row (jdbc/execute-one! datasource
                                      ["SELECT value FROM kv_store WHERE namespace = $1 AND key = $2"
                                       namespace key]
                                      {:builder-fn rs/as-unqualified-lower-maps})]
      (deserialize (:value row))))

  (kv-get-batch [this namespace keys]
    (reduce (fn [result k]
              (if-let [v (proto/kv-get this namespace k)]
                (assoc result k v)
                result))
            {}
            keys))

  (kv-delete [this namespace key]
    (let [result (jdbc/execute! datasource
                                ["DELETE FROM kv_store WHERE namespace = $1 AND key = $2"
                                 namespace key])]
      (pos? (::jdbc/update-count (first result)))))

  (kv-delete-batch [this namespace keys]
    (if (empty? keys)
      0
      (let [placeholders (clojure.string/join "," (map-indexed (fn [i _] (str "$" (+ i 2))) keys))
            sql (format "DELETE FROM kv_store WHERE namespace = $1 AND key IN (%s)" placeholders)
            result (jdbc/execute! datasource (into [sql namespace] keys))]
        (::jdbc/update-count (first result)))))

  (kv-exists? [this namespace key]
    (some? (jdbc/execute-one! datasource
                              ["SELECT 1 FROM kv_store WHERE namespace = $1 AND key = $2 LIMIT 1"
                               namespace key])))

  (kv-list-keys [this namespace opts]
    (let [{:keys [prefix limit offset]
           :or {limit 100 offset 0}} opts
          rows (if prefix
                 (jdbc/execute! datasource
                                ["SELECT key FROM kv_store
                                  WHERE namespace = $1 AND key LIKE $2
                                  ORDER BY key
                                  LIMIT $3 OFFSET $4"
                                 namespace (build-prefix-pattern prefix) limit offset]
                                {:builder-fn rs/as-unqualified-lower-maps})
                 (jdbc/execute! datasource
                                ["SELECT key FROM kv_store
                                  WHERE namespace = $1
                                  ORDER BY key
                                  LIMIT $2 OFFSET $3"
                                 namespace limit offset]
                                {:builder-fn rs/as-unqualified-lower-maps}))]
      (mapv :key rows)))

  (kv-list-values [this namespace opts]
    (let [{:keys [prefix limit offset]
           :or {limit 100 offset 0}} opts
          rows (if prefix
                 (jdbc/execute! datasource
                                ["SELECT * FROM kv_store
                                  WHERE namespace = $1 AND key LIKE $2
                                  ORDER BY updated_at DESC
                                  LIMIT $3 OFFSET $4"
                                 namespace (build-prefix-pattern prefix) limit offset]
                                {:builder-fn rs/as-unqualified-lower-maps})
                 (jdbc/execute! datasource
                                ["SELECT * FROM kv_store
                                  WHERE namespace = $1
                                  ORDER BY updated_at DESC
                                  LIMIT $2 OFFSET $3"
                                 namespace limit offset]
                                {:builder-fn rs/as-unqualified-lower-maps}))]
      (mapv row->record rows)))

  (kv-search [this namespace query opts]
    ;; PostgreSQL JSONB 搜索
    (let [{:keys [top-k] :or {top-k 10}} opts
          query-str (:query query)
          rows (jdbc/execute! datasource
                              ["SELECT * FROM kv_store
                                WHERE namespace = $1 AND value::text ILIKE $2
                                ORDER BY updated_at DESC
                                LIMIT $3"
                               namespace (str "%" query-str "%") top-k]
                              {:builder-fn rs/as-unqualified-lower-maps})]
      (mapv (fn [row]
              {:key (:key row)
               :value (deserialize (:value row))
               :score 1.0})
            rows)))

  (kv-count [this namespace opts]
    (let [{:keys [prefix]} opts
          result (if prefix
                   (jdbc/execute-one! datasource
                                      ["SELECT COUNT(*) as cnt FROM kv_store
                                        WHERE namespace = $1 AND key LIKE $2"
                                       namespace (build-prefix-pattern prefix)]
                                      {:builder-fn rs/as-unqualified-lower-maps})
                   (jdbc/execute-one! datasource
                                      ["SELECT COUNT(*) as cnt FROM kv_store WHERE namespace = $1"
                                       namespace]
                                      {:builder-fn rs/as-unqualified-lower-maps}))]
      (or (:cnt result) 0)))

  (store-init! [this]
    (init-db! datasource)
    this)

  (store-close! [this]
    (when (instance? java.io.Closeable datasource)
      (.close ^java.io.Closeable datasource))
    nil)

  (store-healthy? [this]
    (try
      (jdbc/execute-one! datasource ["SELECT 1"])
      true
      (catch Exception _
        false))))

;; =============================================================================
;; 工厂函数
;; =============================================================================

(defn create-postgres-store
  "创建 PostgresStore 实例

   对应 LangChain PostgresStore。

   参数：
   - db-spec: 数据库配置 map
     :dbtype   - \"postgresql\"
     :dbname   - 数据库名称
     :host     - 主机地址
     :port     - 端口（默认 5432）
     :user     - 用户名
     :password - 密码

   选项：
   - :auto-init? 是否自动初始化（默认 true）

   示例：
   (def store (create-postgres-store
                {:dbtype \"postgresql\"
                 :dbname \"agent_db\"
                 :host \"localhost\"
                 :user \"postgres\"
                 :password \"secret\"}))"
  ([db-spec]
   (create-postgres-store db-spec {}))
  ([db-spec config]
   (let [ds (jdbc/get-datasource db-spec)
         store (->PostgresStore ds config)]
     (if (get config :auto-init? true)
       (proto/store-init! store)
       store))))
