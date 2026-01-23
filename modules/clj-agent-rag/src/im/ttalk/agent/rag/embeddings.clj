(ns im.ttalk.agent.rag.embeddings
  "文本嵌入模块

   提供文本到向量的转换功能，支持多种嵌入模型：
   - OpenAI Embeddings API
   - 本地嵌入（简化实现）

   主要功能：
   - embed-text: 单文本嵌入
   - embed-batch: 批量嵌入
   - cosine-similarity: 余弦相似度
   - euclidean-distance: 欧几里得距离"
  (:require [im.ttalk.agent.core.http.client :as http]
            [cheshire.core :as json]
            [taoensso.timbre :as log]
            [im.ttalk.agent.core.common :as common]))

;;; ============================================================
;;; 配置
;;; ============================================================

(def ^:dynamic *verbose*
  "是否输出详细日志"
  false)

;;; ============================================================
;;; 嵌入协议
;;; ============================================================

(defprotocol IEmbeddingModel
  "嵌入模型协议"
  (embed-text [this text] "生成单个文本的嵌入向量")
  (embed-batch [this texts] "批量生成文本的嵌入向量")
  (model-name [this] "返回模型名称")
  (dimension [this] "返回嵌入维度"))

;;; ============================================================
;;; 相似度计算
;;; ============================================================

(defn dot-product
  "计算点积

   参数：
     vec1 - 向量 1
     vec2 - 向量 2

   返回：
     点积值"
  [vec1 vec2]
  (reduce + (map * vec1 vec2)))

(defn vector-norm
  "计算向量的模长

   参数：
     vec - 输入向量

   返回：
     模长值"
  [vec]
  (Math/sqrt (reduce + (map #(* % %) vec))))

(defn cosine-similarity
  "计算余弦相似度

   参数：
     vec1 - 向量 1
     vec2 - 向量 2

   返回：
     相似度值（-1 到 1，1 表示完全相似）

   示例：
     (cosine-similarity [1 0 0] [1 0 0])  ; => 1.0
     (cosine-similarity [1 0 0] [0 1 0])  ; => 0.0"
  [vec1 vec2]
  (let [dp (dot-product vec1 vec2)
        norm1 (vector-norm vec1)
        norm2 (vector-norm vec2)]
    (if (or (zero? norm1) (zero? norm2))
      0.0
      (/ dp (* norm1 norm2)))))

(defn euclidean-distance
  "计算欧几里得距离

   参数：
     vec1 - 向量 1
     vec2 - 向量 2

   返回：
     距离值（越小越相似）

   示例：
     (euclidean-distance [0 0] [3 4])  ; => 5.0"
  [vec1 vec2]
  (let [diff-squared (map (fn [v1 v2]
                            (let [d (- v1 v2)]
                              (* d d)))
                          vec1 vec2)]
    (Math/sqrt (reduce + diff-squared))))

(defn normalize-vector
  "向量归一化

   参数：
     vec - 输入向量

   返回：
     归一化后的向量（模长为 1）"
  [vec]
  (let [norm (vector-norm vec)]
    (if (zero? norm)
      vec
      (mapv #(/ % norm) vec))))

;;; ============================================================
;;; OpenAI 嵌入
;;; ============================================================

;; OpenAI 模型维度映射
(def ^:private openai-dimensions
  {"text-embedding-3-small" 1536
   "text-embedding-3-large" 3072
   "text-embedding-ada-002" 1536})

(defn- call-openai-embedding-api
  "调用 OpenAI Embedding API

   参数：
     api-key  - API 密钥
     base-url - API 基础 URL
     model    - 模型名称
     text     - 输入文本

   返回：
     嵌入向量"
  [api-key base-url model text]
  (let [url (str base-url "/embeddings")
        payload {:model model :input text}
        response (http/post url
                   :headers {"Authorization" (str "Bearer " api-key)
                             "Content-Type" "application/json"}
                   :body payload)
        data (:body response)]
    (when *verbose*
      (log/info "[Embeddings] Generated embedding for text:"
                (subs text 0 (min 50 (count text)))))
    ;; 提取向量
    (when-let [embedding-data (first (get data :data))]
      (vec (get embedding-data :embedding)))))

(defrecord OpenAIEmbeddings [api-key model base-url]
  IEmbeddingModel

  (embed-text [_ text]
    (call-openai-embedding-api api-key base-url model text))

  (embed-batch [this texts]
    (mapv #(embed-text this %) texts))

  (model-name [_] model)

  (dimension [_]
    (get openai-dimensions model 1536)))

(defn make-openai-embeddings
  "创建 OpenAI 嵌入模型实例

   参数：
     :api-key  - API 密钥（可选，默认从环境变量读取）
     :model    - 模型名称（默认 text-embedding-3-small）
     :base-url - API 基础 URL（默认 OpenAI）

   返回：
     OpenAI 嵌入模型实例

   示例：
     (make-openai-embeddings)
     (make-openai-embeddings :model \"text-embedding-3-large\")"
  [& {:keys [api-key model base-url]
      :or {model "text-embedding-3-small"
           base-url "https://api.openai.com/v1"}}]
  (let [key (or api-key (System/getenv "OPENAI_API_KEY"))]
    (when-not key
      (throw (ex-info "OpenAI API key is required" {:type :missing-api-key})))
    (->OpenAIEmbeddings key model base-url)))

;;; ============================================================
;;; 本地嵌入（简化实现）
;;; ============================================================

(defn- generate-hash-embedding
  "使用文本 hash 生成伪嵌入向量

   参数：
     text - 输入文本
     dim  - 嵌入维度

   返回：
     伪嵌入向量"
  [text dim]
  (let [hash-val (hash text)]
    (vec (for [i (range dim)]
           (-> (* hash-val (inc i))
               (mod 1000)
               (/ 1000.0)
               (* 2)
               (- 1))))))

(defrecord LocalEmbeddings [model-path dim]
  IEmbeddingModel

  (embed-text [_ text]
    (generate-hash-embedding text dim))

  (embed-batch [this texts]
    (mapv #(embed-text this %) texts))

  (model-name [_] "local-hash-embeddings")

  (dimension [_] dim))

(defn make-local-embeddings
  "创建本地嵌入模型实例（简化实现）

   参数：
     :model-path - 模型路径（预留）
     :dimension  - 嵌入维度（默认 384）

   返回：
     本地嵌入模型实例

   说明：
     这是一个简化的实现，使用文本 hash 生成伪嵌入。
     实际应用中应该集成真正的嵌入模型。"
  [& {:keys [model-path dimension]
      :or {dimension 384}}]
  (->LocalEmbeddings model-path dimension))

;;; ============================================================
;;; 默认模型管理（使用公共宏）
;;; ============================================================

(common/defdefault embedding-model
  :constructor make-openai-embeddings
  :fallback make-local-embeddings
  :doc "默认嵌入模型")

;;; ============================================================
;;; 便捷函数
;;; ============================================================

(defn embed-text*
  "生成文本嵌入（使用默认模型）

   参数：
     text  - 输入文本
     model - 嵌入模型（可选）

   返回：
     嵌入向量"
  [text & {:keys [model]}]
  (let [embedding-model (or model (get-default-embedding-model))]
    (embed-text embedding-model text)))

(defn embed-batch*
  "批量生成嵌入（使用默认模型）

   参数：
     texts - 文本列表
     model - 嵌入模型（可选）

   返回：
     嵌入向量列表"
  [texts & {:keys [model]}]
  (let [embedding-model (or model (get-default-embedding-model))]
    (embed-batch embedding-model texts)))
