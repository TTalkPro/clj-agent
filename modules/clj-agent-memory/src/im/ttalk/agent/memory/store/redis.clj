(ns im.ttalk.agent.memory.store.redis
  "RedisStore - 基于 Redis 的高性能分布式存储实现

   适用场景：
   - 分布式系统
   - 需要高性能读写的场景
   - 需要数据过期（TTL）的场景
   - 多实例部署

   特点：
   - 高性能读写
   - 支持数据过期（TTL）
   - 支持分布式部署
   - 原子操作支持

   使用示例：
   (def store (create-redis-store {:host \"localhost\" :port 6379}))
   (proto/kv-put store \"user-123\" \"preference\" {:lang \"zh\"})"
  (:require [im.ttalk.agent.memory.protocol :as proto]
            [taoensso.carmine :as car]
            [cheshire.core :as json]))

;; =============================================================================
;; Redis Key 构建
;; =============================================================================

(def ^:private key-prefix "memory")

(defn- redis-key
  "构建 Redis key: memory:namespace:key"
  [namespace key]
  (str key-prefix ":" namespace ":" key))

(defn- redis-ns-pattern
  "构建命名空间匹配模式"
  [namespace]
  (str key-prefix ":" namespace ":*"))

(defn- index-key
  "构建索引 key"
  [namespace]
  (str key-prefix ":index:" namespace))

;; =============================================================================
;; Redis 命令宏
;; =============================================================================

(defmacro wcar*
  "Redis 命令执行宏"
  [conn-spec & body]
  `(car/wcar ~conn-spec ~@body))

;; =============================================================================
;; 辅助函数
;; =============================================================================

(defn- now []
  (System/currentTimeMillis))

(defn- serialize [value]
  (json/generate-string value))

(defn- deserialize [s]
  (when s
    (json/parse-string s true)))

(defn- parse-redis-key
  "从 Redis key 解析出 namespace 和 key"
  [redis-key]
  (let [parts (clojure.string/split redis-key #":" 3)]
    (when (= (count parts) 3)
      {:namespace (nth parts 1)
       :key (nth parts 2)})))

;; =============================================================================
;; RedisStore 实现
;; =============================================================================

(defrecord RedisStore [conn-spec config]
  proto/IStore

  (kv-put [this namespace key value]
    (let [rkey (redis-key namespace key)
          timestamp (now)
          record {:namespace namespace
                  :key key
                  :value value
                  :created-at timestamp
                  :updated-at timestamp}
          record-json (serialize record)
          idx-key (index-key namespace)
          ttl (or (:default-ttl config) 604800)]  ;; 默认 7 天

      (wcar* conn-spec
        ;; 保存记录
        (car/set rkey record-json)
        ;; 设置 TTL
        (car/expire rkey ttl)
        ;; 添加到索引（有序集合，按时间排序）
        (car/zadd idx-key timestamp key)
        (car/expire idx-key ttl))

      record))

  (kv-put-batch [this namespace items]
    (mapv (fn [{:keys [key value]}]
            (proto/kv-put this namespace key value))
          items))

  (kv-get [this namespace key]
    (when-let [data (wcar* conn-spec
                      (car/get (redis-key namespace key)))]
      (:value (deserialize data))))

  (kv-get-batch [this namespace keys]
    (if (empty? keys)
      {}
      (let [rkeys (map #(redis-key namespace %) keys)
            values (wcar* conn-spec
                     (apply car/mget rkeys))]
        (reduce (fn [result [k v]]
                  (if v
                    (assoc result k (:value (deserialize v)))
                    result))
                {}
                (map vector keys values)))))

  (kv-delete [this namespace key]
    (let [rkey (redis-key namespace key)
          idx-key (index-key namespace)
          existed? (= 1 (wcar* conn-spec (car/exists rkey)))]
      (when existed?
        (wcar* conn-spec
          (car/del rkey)
          (car/zrem idx-key key)))
      existed?))

  (kv-delete-batch [this namespace keys]
    (if (empty? keys)
      0
      (let [rkeys (map #(redis-key namespace %) keys)
            idx-key (index-key namespace)]
        (wcar* conn-spec
          (apply car/del rkeys)
          (apply car/zrem idx-key keys))
        (count keys))))

  (kv-exists? [this namespace key]
    (= 1 (wcar* conn-spec
           (car/exists (redis-key namespace key)))))

  (kv-list-keys [this namespace opts]
    (let [{:keys [prefix limit offset]
           :or {limit 100 offset 0}} opts
          idx-key (index-key namespace)
          ;; 从索引中获取所有 keys
          all-keys (wcar* conn-spec
                     (car/zrevrange idx-key 0 -1))
          ;; 过滤前缀
          filtered (if prefix
                     (filter #(.startsWith ^String % ^String prefix) all-keys)
                     all-keys)]
      (->> filtered
           (drop offset)
           (take limit)
           (vec))))

  (kv-list-values [this namespace opts]
    (let [keys (proto/kv-list-keys this namespace opts)]
      (when (seq keys)
        (->> keys
             (map (fn [k]
                    (when-let [data (wcar* conn-spec
                                      (car/get (redis-key namespace k)))]
                      (let [record (deserialize data)]
                        {:namespace (:namespace record)
                         :key (:key record)
                         :value (:value record)
                         :created-at (:created-at record)
                         :updated-at (:updated-at record)}))))
             (remove nil?)
             (vec)))))

  (kv-search [this namespace query opts]
    ;; Redis 简单实现：遍历所有值进行字符串匹配
    (let [{:keys [top-k] :or {top-k 10}} opts
          query-str (:query query)
          all-keys (proto/kv-list-keys this namespace {:limit 1000})]
      (->> all-keys
           (map (fn [k]
                  (when-let [v (proto/kv-get this namespace k)]
                    (let [value-str (pr-str v)
                          score (if (and query-str
                                         (.contains ^String value-str ^String query-str))
                                  1.0
                                  0.0)]
                      {:key k :value v :score score}))))
           (filter some?)
           (filter #(pos? (:score %)))
           (take top-k)
           (vec))))

  (kv-count [this namespace opts]
    (let [{:keys [prefix]} opts
          idx-key (index-key namespace)]
      (if prefix
        (let [all-keys (wcar* conn-spec (car/zrange idx-key 0 -1))]
          (count (filter #(.startsWith ^String % ^String prefix) all-keys)))
        (or (wcar* conn-spec (car/zcard idx-key)) 0))))

  (store-init! [this]
    this)

  (store-close! [this]
    nil)

  (store-healthy? [this]
    (try
      (= "PONG" (wcar* conn-spec (car/ping)))
      (catch Exception _
        false))))

;; =============================================================================
;; 工厂函数
;; =============================================================================

(defn create-redis-store
  "创建 RedisStore 实例

   参数：
   - conn-spec: Redis 连接配置
     :host     - 主机地址（默认 localhost）
     :port     - 端口（默认 6379）
     :db       - 数据库编号（默认 0）
     :password - 密码（可选）

   选项：
   - :default-ttl 默认过期时间（秒，默认 604800 = 7 天）

   示例：
   (def store (create-redis-store {:host \"localhost\" :port 6379}))
   (def store (create-redis-store
                {:host \"redis.example.com\" :port 6379 :password \"secret\"}
                {:default-ttl 86400}))"
  ([conn-spec]
   (create-redis-store conn-spec {}))
  ([conn-spec config]
   (let [full-conn-spec {:pool {}
                         :spec conn-spec}]
     (->RedisStore full-conn-spec config))))
