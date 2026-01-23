(ns im.ttalk.agent.memory.long-term.semantic
  "语义记忆实现 - 存储事实和知识

   提供：
   - 事实存储与检索
   - Profile 管理（用户偏好等结构化数据）
   - 语义搜索（需要 VectorStore）

   使用示例：
   (def memory (create-semantic-memory store))
   (store-fact memory \"user-123\" {:type :preference :content \"喜欢中文\"})
   (query-facts memory \"user-123\" \"语言偏好\" {:top-k 5})"
  (:require [im.ttalk.agent.memory.protocol :as proto]))

;; =============================================================================
;; 辅助函数
;; =============================================================================

(defn- generate-fact-id []
  (str "fact-" (java.util.UUID/randomUUID)))

(defn- fact-key [fact-id]
  (str "fact:" fact-id))

(defn- profile-key [profile-type]
  (str "profile:" (name profile-type)))

(defn- now []
  (System/currentTimeMillis))

;; =============================================================================
;; SemanticMemory 实现
;; =============================================================================

(defrecord SemanticMemory [store vector-store embedder config]
  proto/ISemanticMemory

  (store-fact [this namespace fact]
    (let [fact-id (or (:id fact) (generate-fact-id))
          key (fact-key fact-id)
          timestamp (now)
          record {:id fact-id
                  :type (:type fact :general)
                  :content (:content fact)
                  :metadata (merge (:metadata fact {})
                                   {:created-at timestamp
                                    :updated-at timestamp})}]
      ;; 存储到 Store
      (proto/kv-put store namespace key record)

      ;; 如果有向量存储，生成并存储嵌入
      (when (and vector-store embedder)
        (let [embedding (or (:embedding fact)
                            (proto/embed embedder (:content fact)))]
          (proto/upsert vector-store fact-id embedding
                        {:namespace namespace
                         :type (:type fact :general)
                         :fact-id fact-id})))
      fact-id))

  (store-facts [this namespace facts]
    (mapv #(proto/store-fact this namespace %) facts))

  (get-fact [this namespace fact-id]
    (let [key (fact-key fact-id)]
      (proto/kv-get store namespace key)))

  (query-facts [this namespace query opts]
    (if (and vector-store embedder)
      ;; 语义搜索
      (let [query-embedding (proto/embed embedder query)
            results (proto/search vector-store query-embedding
                                  (merge {:top-k 10
                                          :namespace namespace}
                                         opts))]
        ;; 获取完整的事实记录
        (->> results
             (map #(proto/get-fact this namespace (:fact-id (:metadata %))))
             (filter some?)))
      ;; 无向量存储时的简单搜索
      (proto/list-facts this namespace opts)))

  (update-fact [this namespace fact-id updates]
    (let [key (fact-key fact-id)
          existing (proto/kv-get store namespace key)]
      (when existing
        (let [updated (-> existing
                          (merge updates)
                          (assoc-in [:metadata :updated-at] (now)))]
          (proto/kv-put store namespace key updated)
          updated))))

  (delete-fact [this namespace fact-id]
    (let [key (fact-key fact-id)]
      (proto/kv-delete store namespace key)
      (when vector-store
        (proto/delete-vector vector-store fact-id))
      true))

  (delete-facts-by-filter [this namespace filter]
    (let [facts (proto/list-facts this namespace {:filter filter})]
      (doseq [fact facts]
        (proto/delete-fact this namespace (:id fact)))
      (count facts)))

  (list-facts [this namespace opts]
    (let [all-values (proto/kv-list-values store namespace {:prefix "fact:"
                                                            :limit (or (:limit opts) 100)})]
      (->> all-values
           (map :value)
           (filter some?)
           (filter #(if-let [type-filter (:type opts)]
                      (= (:type %) type-filter)
                      true)))))

  (count-facts [this namespace]
    (proto/kv-count store namespace {:prefix "fact:"}))

  ;; Profile 操作
  (get-profile [this namespace profile-type]
    (let [key (profile-key profile-type)]
      (proto/kv-get store namespace key)))

  (update-profile [this namespace profile-type updates]
    (let [key (profile-key profile-type)
          existing (or (proto/kv-get store namespace key) {})
          updated (merge-with (fn [old new]
                                (if (and (map? old) (map? new))
                                  (merge old new)
                                  new))
                              existing
                              updates
                              {:updated-at (now)})]
      (proto/kv-put store namespace key updated)
      updated))

  (set-profile [this namespace profile-type data]
    (let [key (profile-key profile-type)
          record (assoc data
                   :profile-type profile-type
                   :updated-at (now))]
      (proto/kv-put store namespace key record)
      record)))

;; =============================================================================
;; 工厂函数
;; =============================================================================

(defn create-semantic-memory
  "创建语义记忆实例

   参数：
   - store: IStore 实例（必需）
   - opts:
     - :vector-store   向量存储实例（可选，用于语义搜索）
     - :embedder       嵌入生成器（可选，与 vector-store 配合）

   示例：
   (create-semantic-memory store)
   (create-semantic-memory store :vector-store vs :embedder emb)"
  [store & {:keys [vector-store embedder]}]
  (->SemanticMemory store vector-store embedder {}))
