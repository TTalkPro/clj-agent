(ns im.ttalk.agent.rag.vector-store
  "向量存储模块

   提供文档的向量化存储和相似度搜索功能：
   - 内存向量存储
   - 相似度搜索（余弦相似度）
   - 文档管理（增删查）

   主要功能：
   - add-document: 添加文档
   - search: 相似度搜索
   - get-document: 获取文档
   - delete-document: 删除文档"
  (:require [im.ttalk.agent.rag.embeddings :as embeddings]
            [im.ttalk.agent.core.common :as common]
            [taoensso.timbre :as log])
  (:import [java.util UUID]))

;;; ============================================================
;;; 配置
;;; ============================================================

(def ^:dynamic *verbose*
  "是否输出详细日志"
  false)

;;; ============================================================
;;; 文档结构
;;; ============================================================

(defrecord Document [id content metadata embedding])

(defn make-document
  "创建文档实例

   参数：
     :id        - 文档 ID（可选，自动生成）
     :content   - 文档内容
     :metadata  - 元数据（可选）
     :embedding - 嵌入向量（可选）

   返回：
     Document 实例"
  [& {:keys [id content metadata embedding]}]
  (->Document (or id (str (UUID/randomUUID)))
              content
              (or metadata {})
              embedding))

;;; ============================================================
;;; 向量存储协议
;;; ============================================================

(defprotocol IVectorStore
  "向量存储协议"
  (add-document [this content embedding] [this content embedding metadata]
    "添加文档到存储")
  (search [this query-embedding top-k] [this query-embedding top-k min-score]
    "搜索相似文档")
  (get-document [this id] "根据 ID 获取文档")
  (delete-document [this id] "删除文档")
  (list-documents [this] "列出所有文档")
  (document-count [this] "获取文档数量")
  (clear-store [this] "清空存储"))

;;; ============================================================
;;; 搜索辅助函数
;;; ============================================================

(defn- score-document
  "计算文档的相似度分数

   参数：
     query-embedding - 查询嵌入向量
     doc             - 文档

   返回：
     {:score 分数 :document 文档} 或 nil"
  [query-embedding doc]
  (when-let [doc-embedding (:embedding doc)]
    (let [score (embeddings/cosine-similarity query-embedding doc-embedding)]
      {:score score :document doc})))

(defn- filter-by-min-score
  "过滤低于最小分数的结果

   参数：
     scored-docs - 评分文档列表
     min-score   - 最小分数

   返回：
     过滤后的列表"
  [scored-docs min-score]
  (filter #(>= (:score %) min-score) scored-docs))

(defn- sort-and-take-top
  "排序并取前 N 个

   参数：
     scored-docs - 评分文档列表
     top-k       - 取前 K 个

   返回：
     文档列表"
  [scored-docs top-k]
  (->> scored-docs
       (sort-by :score >)
       (take top-k)
       (mapv :document)))

;;; ============================================================
;;; 内存向量存储
;;; ============================================================

(defrecord MemoryVectorStore [documents-atom index-atom]
  IVectorStore

  (add-document [this content embedding]
    (add-document this content embedding {}))

  (add-document [_ content embedding metadata]
    (let [id (str (UUID/randomUUID))
          doc (make-document :id id
                             :content content
                             :embedding embedding
                             :metadata metadata)]
      (swap! documents-atom assoc id doc)
      (when *verbose*
        (log/info "[VectorStore] Added document:" id))
      id))

  (search [this query-embedding top-k]
    (search this query-embedding top-k 0.0))

  (search [_ query-embedding top-k min-score]
    (let [documents (vals @documents-atom)
          scored-docs (keep #(score-document query-embedding %) documents)
          filtered (filter-by-min-score scored-docs min-score)]
      (sort-and-take-top filtered top-k)))

  (get-document [_ id]
    (get @documents-atom id))

  (delete-document [_ id]
    (let [exists? (contains? @documents-atom id)]
      (swap! documents-atom dissoc id)
      (when (and *verbose* exists?)
        (log/info "[VectorStore] Deleted document:" id))
      exists?))

  (list-documents [_]
    (vals @documents-atom))

  (document-count [_]
    (count @documents-atom))

  (clear-store [_]
    (reset! documents-atom {})
    (reset! index-atom {})
    (when *verbose*
      (log/info "[VectorStore] Cleared"))
    nil))

(defn make-vector-store
  "创建内存向量存储实例

   返回：
     MemoryVectorStore 实例

   示例：
     (def store (make-vector-store))
     (add-document store \"内容\" [0.1 0.2 0.3])"
  []
  (->MemoryVectorStore (atom {}) (atom {})))

;;; ============================================================
;;; 带元数据过滤的搜索
;;; ============================================================

(defn search-with-filter
  "带元数据过滤的搜索

   参数：
     store           - 向量存储实例
     query-embedding - 查询嵌入向量
     top-k           - 返回数量
     filter-fn       - 元数据过滤函数 (fn [metadata] -> boolean)

   返回：
     匹配的文档列表

   示例：
     (search-with-filter store query-vec 5
       (fn [meta] (= (:category meta) \"news\")))"
  [store query-embedding top-k filter-fn]
  (let [documents (list-documents store)
        matching (filter #(filter-fn (:metadata %)) documents)
        scored (keep #(score-document query-embedding %) matching)]
    (sort-and-take-top scored top-k)))

(defn search-by-id-prefix
  "按 ID 前缀搜索

   参数：
     store  - 向量存储实例
     prefix - ID 前缀

   返回：
     匹配的文档列表"
  [store prefix]
  (filter #(clojure.string/starts-with? (:id %) prefix)
          (list-documents store)))

;;; ============================================================
;;; 批量操作
;;; ============================================================

(defn add-documents-batch
  "批量添加文档

   参数：
     store      - 向量存储实例
     contents   - 内容列表
     embeddings - 嵌入向量列表

   返回：
     文档 ID 列表

   示例：
     (add-documents-batch store
       [\"文档1\" \"文档2\"]
       [[0.1 0.2] [0.3 0.4]])"
  [store contents embeddings]
  (mapv #(add-document store %1 %2) contents embeddings))

(defn add-documents-with-embeddings
  "自动生成嵌入并批量添加文档

   参数：
     store           - 向量存储实例
     contents        - 内容列表
     embedding-model - 嵌入模型

   返回：
     文档 ID 列表"
  [store contents embedding-model]
  (let [embeddings (embeddings/embed-batch embedding-model contents)]
    (add-documents-batch store contents embeddings)))

;;; ============================================================
;;; 默认存储管理（使用公共宏）
;;; ============================================================

(common/defdefault vector-store
  :constructor make-vector-store
  :doc "默认向量存储")

;;; ============================================================
;;; 便捷函数
;;; ============================================================

(defn add-document*
  "添加文档到默认存储

   参数：
     content   - 文档内容
     embedding - 嵌入向量
     metadata  - 元数据（可选）

   返回：
     文档 ID"
  [content embedding & {:keys [metadata] :or {metadata {}}}]
  (add-document (get-default-vector-store) content embedding metadata))

(defn search*
  "在默认存储中搜索

   参数：
     query - 查询文本
     top-k - 返回数量（默认 5）

   返回：
     匹配的文档列表"
  [query & {:keys [top-k] :or {top-k 5}}]
  (let [embedding-model (embeddings/get-default-embedding-model)
        query-embedding (embeddings/embed-text embedding-model query)]
    (search (get-default-vector-store) query-embedding top-k)))

;;; ============================================================
;;; 统计和调试
;;; ============================================================

(defn store-stats
  "获取存储统计信息

   参数：
     store - 向量存储实例

   返回：
     统计信息 map"
  [store]
  (let [documents (list-documents store)
        with-embedding (count (filter :embedding documents))
        total (count documents)]
    {:total-documents total
     :with-embeddings with-embedding
     :without-embeddings (- total with-embedding)}))

(defn sample-documents
  "随机采样文档

   参数：
     store - 向量存储实例
     n     - 采样数量

   返回：
     随机选择的 n 个文档"
  [store n]
  (take n (shuffle (list-documents store))))
