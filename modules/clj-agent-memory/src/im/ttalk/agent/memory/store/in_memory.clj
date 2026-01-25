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
  (:require [im.ttalk.agent.memory.protocol :as proto]
            [im.ttalk.agent.memory.store.base :as base]))

;; =============================================================================
;; InMemoryStore 实现
;; =============================================================================

(defrecord InMemoryStore [data-atom config]
  proto/IKeyValueStore

  ;; -------------------------------------------------------------------------
  ;; CRUD 操作
  ;; -------------------------------------------------------------------------

  (put [this namespace key value]
    (let [storage-key (base/make-storage-key namespace key)
          record (base/make-record namespace key value)]
      (swap! data-atom
             (fn [data]
               (let [existing (get-in data [:records storage-key])
                     final-record (if existing
                                    (base/update-record existing value)
                                    record)]
                 (-> data
                     (assoc-in [:records storage-key] final-record)
                     (update-in [:namespaces namespace] (fnil conj #{}) key)))))
      record))

  (put-batch [this namespace items]
    (base/default-put-batch this namespace items))

  (get-value [this namespace key]
    (let [storage-key (base/make-storage-key namespace key)
          record (get-in @data-atom [:records storage-key])]
      (:value record)))

  (get-batch [this namespace keys]
    (base/default-get-batch this namespace keys))

  (delete [this namespace key]
    (let [storage-key (base/make-storage-key namespace key)
          existed? (contains? (:records @data-atom) storage-key)]
      (when existed?
        (swap! data-atom
               (fn [data]
                 (-> data
                     (update :records dissoc storage-key)
                     (update-in [:namespaces namespace] disj key)))))
      existed?))

  (delete-batch [this namespace keys]
    (base/default-delete-batch this namespace keys))

  (exists? [this namespace key]
    (let [storage-key (base/make-storage-key namespace key)]
      (contains? (:records @data-atom) storage-key)))

  ;; -------------------------------------------------------------------------
  ;; 查询操作
  ;; -------------------------------------------------------------------------

  (list-keys [this namespace opts]
    (let [{:keys [prefix limit offset]
           :or {limit 100 offset 0}} opts
          ns-keys (get-in @data-atom [:namespaces namespace] #{})
          filtered (if prefix
                     (filter #(base/matches-prefix? % prefix) ns-keys)
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
                     (filter #(base/matches-prefix? % prefix) ns-keys)
                     ns-keys)]
      (->> filtered
           (map (fn [k]
                  (let [storage-key (base/make-storage-key namespace k)]
                    (get-in @data-atom [:records storage-key]))))
           (filter some?)
           (sort-by :updated-at >)
           (drop offset)
           (take limit)
           (vec))))

  (search [this namespace query opts]
    (base/default-search this namespace query opts
      (fn [] (get-in @data-atom [:namespaces namespace] #{}))))

  (count-keys [this namespace opts]
    (let [{:keys [prefix]} opts
          ns-keys (get-in @data-atom [:namespaces namespace] #{})]
      (if prefix
        (count (filter #(base/matches-prefix? % prefix) ns-keys))
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
