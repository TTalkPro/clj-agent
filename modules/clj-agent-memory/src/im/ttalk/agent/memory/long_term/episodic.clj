(ns im.ttalk.agent.memory.long-term.episodic
  "情景记忆实现 - 存储成功经验和案例

   提供：
   - 情景存储（situation → action → outcome）
   - 相似情境查询
   - 成功案例检索
   - 反馈学习

   使用示例：
   (def memory (create-episodic-memory store))
   (store-episode memory {:situation \"用户问重构\"
                          :action \"分步解释\"
                          :outcome :success
                          :reasoning \"降低复杂度\"})"
  (:require [im.ttalk.agent.memory.protocol :as proto]))

;; =============================================================================
;; 辅助函数
;; =============================================================================

(defn- generate-episode-id []
  (str "ep-" (java.util.UUID/randomUUID)))

(defn- episode-key [episode-id]
  (str "episode:" episode-id))

(defn- index-key []
  "episode:index")

(defn- now []
  (System/currentTimeMillis))

;; =============================================================================
;; EpisodicMemory 实现
;; =============================================================================

(defrecord EpisodicMemory [store vector-store embedder config]
  proto/IEpisodicMemory

  (store-episode [this episode]
    (let [episode-id (or (:id episode) (generate-episode-id))
          namespace (or (:namespace episode) "default")
          timestamp (now)
          record {:id episode-id
                  :namespace namespace
                  :situation (:situation episode)
                  :action (:action episode)
                  :outcome (:outcome episode :unknown)
                  :reasoning (:reasoning episode)
                  :context (:context episode {})
                  :metadata (:metadata episode {})
                  :created-at timestamp
                  :updated-at timestamp}
          key (episode-key episode-id)]

      ;; 存储到 Store
      (proto/put store namespace key record)

      ;; 更新索引
      (let [idx (or (proto/get-value store namespace (index-key)) {:episode-ids []})
            updated-idx (update idx :episode-ids
                                #(conj (vec %) {:id episode-id :created-at timestamp}))]
        (proto/put store namespace (index-key) updated-idx))

      ;; 如果有向量存储，存储情境嵌入
      (when (and vector-store embedder)
        (let [embedding (proto/embed embedder (:situation episode))]
          (proto/upsert vector-store episode-id embedding
                        {:namespace namespace
                         :episode-id episode-id
                         :outcome (:outcome episode)})))
      episode-id))

  (store-episodes [this episodes]
    (mapv #(proto/store-episode this %) episodes))

  (get-episode [this episode-id]
    ;; 尝试在所有 namespace 查找
    (let [namespaces ["default"]]  ;; 可以扩展支持多 namespace
      (some (fn [ns]
              (proto/get-value store ns (episode-key episode-id)))
            namespaces)))

  (query-similar [this situation opts]
    (let [namespace (or (:namespace opts) "default")
          top-k (or (:top-k opts) 5)
          outcome-filter (:outcome-filter opts)]
      (if (and vector-store embedder)
        ;; 语义搜索
        (let [query-embedding (proto/embed embedder situation)
              search-opts (cond-> {:top-k (* top-k 2)  ; 多取一些以便过滤
                                   :namespace namespace}
                            (:threshold opts) (assoc :threshold (:threshold opts)))
              results (proto/search vector-store query-embedding search-opts)]
          (->> results
               (map #(proto/get-episode this (:episode-id (:metadata %))))
               (filter some?)
               (filter #(if outcome-filter
                          (= (:outcome %) outcome-filter)
                          true))
               (take top-k)))
        ;; 无向量存储时返回最近的
        (proto/get-recent-episodes this opts))))

  (get-recent-episodes [this opts]
    (let [namespace (or (:namespace opts) "default")
          limit (or (:limit opts) 10)
          outcome-filter (:outcome-filter opts)
          idx (proto/get-value store namespace (index-key))]
      (when idx
        (->> (:episode-ids idx)
             (sort-by :created-at >)  ; 按时间倒序
             (take (* limit 2))  ; 多取一些以便过滤
             (map #(proto/get-value store namespace (episode-key (:id %))))
             (filter some?)
             (filter #(if outcome-filter
                        (= (:outcome %) outcome-filter)
                        true))
             (take limit)))))

  (get-successful-episodes [this opts]
    (proto/get-recent-episodes this (assoc opts :outcome-filter :success)))

  (delete-episode [this episode-id]
    (when-let [episode (proto/get-episode this episode-id)]
      (let [namespace (:namespace episode)
            key (episode-key episode-id)]
        ;; 删除记录
        (proto/delete store namespace key)

        ;; 更新索引
        (let [idx (proto/get-value store namespace (index-key))]
          (when idx
            (let [updated-idx (update idx :episode-ids
                                      #(filterv (fn [e] (not= (:id e) episode-id)) %))]
              (proto/put store namespace (index-key) updated-idx))))

        ;; 删除向量
        (when vector-store
          (proto/delete-vector vector-store episode-id))
        true)))

  (update-episode-outcome [this episode-id outcome reasoning]
    (when-let [episode (proto/get-episode this episode-id)]
      (let [namespace (:namespace episode)
            key (episode-key episode-id)
            updated (assoc episode
                      :outcome outcome
                      :reasoning reasoning
                      :updated-at (now))]
        (proto/put store namespace key updated)

        ;; 更新向量元数据
        (when vector-store
          (when-let [vec-data (proto/get-vector vector-store episode-id)]
            (proto/upsert vector-store episode-id
                          (:vector vec-data)
                          (assoc (:metadata vec-data) :outcome outcome))))
        updated))))

;; =============================================================================
;; 工厂函数
;; =============================================================================

(defn create-episodic-memory
  "创建情景记忆实例

   参数：
   - store: IStore 实例
   - opts:
     - :vector-store   向量存储实例（可选）
     - :embedder       嵌入生成器（可选）

   示例：
   (create-episodic-memory store)
   (create-episodic-memory store :vector-store vs :embedder emb)"
  [store & {:keys [vector-store embedder]}]
  (->EpisodicMemory store vector-store embedder {}))

;; =============================================================================
;; 便捷函数
;; =============================================================================

(defn create-success-episode
  "创建成功案例"
  [situation action reasoning & {:keys [namespace context]}]
  {:situation situation
   :action action
   :outcome :success
   :reasoning reasoning
   :namespace namespace
   :context context})

(defn create-failure-episode
  "创建失败案例"
  [situation action reasoning & {:keys [namespace context]}]
  {:situation situation
   :action action
   :outcome :failure
   :reasoning reasoning
   :namespace namespace
   :context context})
