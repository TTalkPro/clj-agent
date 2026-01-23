(ns im.ttalk.agent.memory.store.in-memory
  "InMemoryStore - 基于 atom 的纯内存存储实现

   对应 LangChain InMemoryStore。

   适用场景：
   - 单元测试
   - 快速原型开发
   - 不需要持久化的场景

   特点：
   - 纯内存存储，速度极快
   - 进程重启后数据丢失
   - 无需外部依赖
   - 线程安全（基于 atom）

   使用示例：
   (def store (create-in-memory-store))
   (proto/put store \"user-123\" \"preference\" {:lang \"zh\"})
   (proto/get-value store \"user-123\" \"preference\")"
  (:require [im.ttalk.agent.memory.protocol :as proto]))

;; =============================================================================
;; 内部辅助函数
;; =============================================================================

(defn- generate-id []
  (str (java.util.UUID/randomUUID)))

(defn- now []
  (System/currentTimeMillis))

(defn- make-storage-key
  "构建内部存储 key: namespace:key"
  [namespace key]
  (str namespace ":" key))

(defn- parse-storage-key
  "解析内部存储 key，返回 [namespace key]"
  [storage-key]
  (let [idx (.indexOf ^String storage-key ":")]
    (when (pos? idx)
      [(.substring ^String storage-key 0 idx)
       (.substring ^String storage-key (inc idx))])))

(defn- matches-prefix?
  "检查 key 是否匹配前缀"
  [key prefix]
  (if (empty? prefix)
    true
    (.startsWith ^String key ^String prefix)))

;; =============================================================================
;; InMemoryStore 实现
;; =============================================================================

(defrecord InMemoryStore [data-atom config]
  proto/IKeyValueStore

  ;; -------------------------------------------------------------------------
  ;; CRUD 操作
  ;; -------------------------------------------------------------------------

  (put [this namespace key value]
    (let [storage-key (make-storage-key namespace key)
          timestamp (now)
          record {:namespace namespace
                  :key key
                  :value value
                  :created-at timestamp
                  :updated-at timestamp}]
      (swap! data-atom
             (fn [data]
               (let [existing (get-in data [:records storage-key])
                     final-record (if existing
                                    (assoc record :created-at (:created-at existing))
                                    record)]
                 (-> data
                     (assoc-in [:records storage-key] final-record)
                     (update-in [:namespaces namespace] (fnil conj #{}) key)))))
      record))

  (put-batch [this namespace items]
    (mapv (fn [{:keys [key value]}]
            (proto/put this namespace key value))
          items))

  (get-value [this namespace key]
    (let [storage-key (make-storage-key namespace key)
          record (get-in @data-atom [:records storage-key])]
      (:value record)))

  (get-batch [this namespace keys]
    (reduce (fn [result k]
              (if-let [v (proto/get-value this namespace k)]
                (assoc result k v)
                result))
            {}
            keys))

  (delete [this namespace key]
    (let [storage-key (make-storage-key namespace key)
          existed? (contains? (:records @data-atom) storage-key)]
      (when existed?
        (swap! data-atom
               (fn [data]
                 (-> data
                     (update :records dissoc storage-key)
                     (update-in [:namespaces namespace] disj key)))))
      existed?))

  (delete-batch [this namespace keys]
    (reduce (fn [count k]
              (if (proto/delete this namespace k)
                (inc count)
                count))
            0
            keys))

  (exists? [this namespace key]
    (let [storage-key (make-storage-key namespace key)]
      (contains? (:records @data-atom) storage-key)))

  ;; -------------------------------------------------------------------------
  ;; 查询操作
  ;; -------------------------------------------------------------------------

  (list-keys [this namespace opts]
    (let [{:keys [prefix limit offset]
           :or {limit 100 offset 0}} opts
          ns-keys (get-in @data-atom [:namespaces namespace] #{})
          filtered (if prefix
                     (filter #(matches-prefix? % prefix) ns-keys)
                     ns-keys)]
      (->> filtered
           (sort)
           (drop offset)
           (take limit)
           (vec))))

  (list-values [this namespace opts]
    (let [{:keys [prefix limit offset]
           :or {limit 100 offset 0}} opts
          ns-keys (get-in @data-atom [:namespaces namespace] #{})
          filtered (if prefix
                     (filter #(matches-prefix? % prefix) ns-keys)
                     ns-keys)]
      (->> filtered
           (map (fn [k]
                  (let [storage-key (make-storage-key namespace k)]
                    (get-in @data-atom [:records storage-key]))))
           (filter some?)
           (sort-by :updated-at >)
           (drop offset)
           (take limit)
           (vec))))

  (search [this namespace query opts]
    ;; 简单实现：基于字符串匹配
    ;; 高级实现需要向量搜索支持
    (let [{:keys [top-k] :or {top-k 10}} opts
          query-str (:query query)
          ns-keys (get-in @data-atom [:namespaces namespace] #{})]
      (->> ns-keys
           (map (fn [k]
                  (let [storage-key (make-storage-key namespace k)
                        record (get-in @data-atom [:records storage-key])]
                    (when record
                      (let [value-str (pr-str (:value record))
                            ;; 简单的包含匹配
                            score (if (and query-str
                                           (.contains ^String value-str ^String query-str))
                                    1.0
                                    0.0)]
                        {:key k
                         :value (:value record)
                         :score score})))))
           (filter some?)
           (filter #(pos? (:score %)))
           (sort-by :score >)
           (take top-k)
           (vec))))

  (count-keys [this namespace opts]
    (let [{:keys [prefix]} opts
          ns-keys (get-in @data-atom [:namespaces namespace] #{})]
      (if prefix
        (count (filter #(matches-prefix? % prefix) ns-keys))
        (count ns-keys))))

  ;; -------------------------------------------------------------------------
  ;; 生命周期
  ;; -------------------------------------------------------------------------

  (init! [this]
    this)

  (close! [this]
    nil)

  (healthy? [this]
    (some? @data-atom)))

;; =============================================================================
;; 工厂函数
;; =============================================================================

(defn create-in-memory-store
  "创建 InMemoryStore 实例

   对应 LangChain InMemoryStore。

   选项：
   - :initial-data 初始数据（用于测试）

   返回：InMemoryStore 实例

   示例：
   (def store (create-in-memory-store))
   (proto/put store \"user-123\" \"pref\" {:lang \"zh\"})"
  ([]
   (create-in-memory-store {}))
  ([config]
   (let [initial-data (or (:initial-data config)
                          {:records {}
                           :namespaces {}})]
     (->InMemoryStore (atom initial-data) config))))

;; 向后兼容别名
(def create-memory-store
  "创建内存存储（向后兼容别名）"
  create-in-memory-store)

;; =============================================================================
;; 便捷函数
;; =============================================================================

(defn snapshot
  "获取存储的快照（用于调试）"
  [store]
  @(:data-atom store))

(defn restore-snapshot!
  "从快照恢复存储（用于测试）"
  [store snapshot-data]
  (reset! (:data-atom store) snapshot-data))

(defn clear!
  "清空所有数据"
  [store]
  (reset! (:data-atom store) {:records {} :namespaces {}})
  nil)

(defn record-count
  "获取记录数量"
  [store]
  (count (:records @(:data-atom store))))

(defn namespace-count
  "获取命名空间数量"
  [store]
  (count (:namespaces @(:data-atom store))))
