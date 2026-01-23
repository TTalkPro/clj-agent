(ns im.ttalk.agent.memory.store.vector-memory
  "内存向量存储实现 - 用于测试和开发

   提供基于内存的向量存储，使用余弦相似度进行搜索。
   适用于：
   - 单元测试
   - 开发环境
   - 小规模应用

   生产环境建议使用 pgvector、Qdrant 等专用向量数据库。

   使用示例：
   (def store (create-memory-vector-store))
   (upsert store \"doc-1\" [0.1 0.2 0.3] {:type :fact})
   (search store [0.1 0.2 0.3] {:top-k 5})"
  (:require [im.ttalk.agent.memory.protocol :as proto]))

;; =============================================================================
;; 向量运算
;; =============================================================================

(defn- dot-product
  "计算点积"
  [v1 v2]
  (reduce + (map * v1 v2)))

(defn- magnitude
  "计算向量模"
  [v]
  (Math/sqrt (reduce + (map #(* % %) v))))

(defn- cosine-similarity
  "计算余弦相似度"
  [v1 v2]
  (let [dot (dot-product v1 v2)
        mag1 (magnitude v1)
        mag2 (magnitude v2)]
    (if (or (zero? mag1) (zero? mag2))
      0.0
      (/ dot (* mag1 mag2)))))

(defn- euclidean-distance
  "计算欧氏距离"
  [v1 v2]
  (Math/sqrt (reduce + (map #(Math/pow (- %1 %2) 2) v1 v2))))

;; =============================================================================
;; MemoryVectorStore 实现
;; =============================================================================

(defrecord MemoryVectorStore [store-atom config]
  proto/IVectorStore

  (upsert [_ id vector metadata]
    (swap! store-atom assoc id {:id id
                                :vector (vec vector)
                                :metadata (or metadata {})
                                :updated-at (System/currentTimeMillis)})
    id)

  (upsert-batch [this items]
    (doseq [{:keys [id vector metadata]} items]
      (proto/upsert this id vector metadata))
    (count items))

  (get-vector [_ id]
    (get @store-atom id))

  (delete-vector [_ id]
    (swap! store-atom dissoc id)
    true)

  (delete-by-filter [_ filter]
    (let [matching-ids (->> @store-atom
                            vals
                            (filter (fn [item]
                                      (every? (fn [[k v]]
                                                (= (get-in item [:metadata k]) v))
                                              filter)))
                            (map :id))]
      (doseq [id matching-ids]
        (swap! store-atom dissoc id))
      (count matching-ids)))

  (search [_ query-vector opts]
    (let [top-k (or (:top-k opts) 10)
          threshold (or (:threshold opts) 0.0)
          namespace-filter (:namespace opts)
          metadata-filter (:filter opts)
          include-vectors (:include-vectors opts false)
          include-metadata (:include-metadata opts true)
          similarity-fn (case (:metric config :cosine)
                          :cosine cosine-similarity
                          :euclidean #(- 1 (euclidean-distance %1 %2))
                          cosine-similarity)]

      (->> @store-atom
           vals
           ;; 过滤 namespace
           (filter (fn [item]
                     (if namespace-filter
                       (= (get-in item [:metadata :namespace]) namespace-filter)
                       true)))
           ;; 过滤元数据
           (filter (fn [item]
                     (if metadata-filter
                       (every? (fn [[k v]]
                                 (= (get-in item [:metadata k]) v))
                               metadata-filter)
                       true)))
           ;; 计算相似度
           (map (fn [item]
                  (let [score (similarity-fn query-vector (:vector item))]
                    (assoc item :score score))))
           ;; 过滤阈值
           (filter #(>= (:score %) threshold))
           ;; 排序
           (sort-by :score >)
           ;; 取 top-k
           (take top-k)
           ;; 格式化结果
           (map (fn [item]
                  (cond-> {:id (:id item)
                           :score (:score item)}
                    include-metadata (assoc :metadata (:metadata item))
                    include-vectors (assoc :vector (:vector item)))))
           vec)))

  (count-vectors [_ opts]
    (let [namespace-filter (:namespace opts)
          metadata-filter (:filter opts)]
      (->> @store-atom
           vals
           (filter (fn [item]
                     (and (if namespace-filter
                            (= (get-in item [:metadata :namespace]) namespace-filter)
                            true)
                          (if metadata-filter
                            (every? (fn [[k v]]
                                      (= (get-in item [:metadata k]) v))
                                    metadata-filter)
                            true))))
           count))))

;; =============================================================================
;; 工厂函数
;; =============================================================================

(defn create-memory-vector-store
  "创建内存向量存储实例

   参数（可选）：
   - :metric    相似度度量 :cosine | :euclidean（默认 :cosine）
   - :dimension 向量维度（用于验证，默认不验证）

   示例：
   (create-memory-vector-store)
   (create-memory-vector-store :metric :cosine :dimension 1536)"
  [& {:keys [metric dimension]
      :or {metric :cosine}}]
  (->MemoryVectorStore
    (atom {})
    {:metric metric
     :dimension dimension}))

;; =============================================================================
;; 辅助函数
;; =============================================================================

(defn clear-store
  "清空存储"
  [store]
  (reset! (:store-atom store) {})
  store)

(defn export-store
  "导出存储数据"
  [store]
  @(:store-atom store))

(defn import-store
  "导入存储数据"
  [store data]
  (reset! (:store-atom store) data)
  store)
