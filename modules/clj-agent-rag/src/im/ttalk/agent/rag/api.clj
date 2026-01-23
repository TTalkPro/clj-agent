(ns im.ttalk.agent.rag.api
  "RAG API - clj-agent-rag 统一入口

   检索增强生成（Retrieval-Augmented Generation）模块。

   ========================================
   快速开始
   ========================================

   (require '[im.ttalk.agent.rag.api :as rag])

   ;; 1. 创建 RAG 管道
   (def pipeline
     (rag/make-pipeline
       :llm-fn (fn [prompt] (call-your-llm prompt))))

   ;; 2. 索引文档
   (rag/index-document pipeline \"Erlang is a functional language...\")

   ;; 3. 查询
   (rag/query pipeline \"What is Erlang?\")
   ;; => {:ok true :answer \"...\" :documents [...] :context \"...\"}

   ========================================
   使用回调
   ========================================

   (rag/retrieve pipeline \"query\"
     :callbacks {:on-retriever-start (fn [query meta] (println \"Start:\", query))
                 :on-retriever-end   (fn [docs meta] (println \"Found:\", (count docs)))
                 :on-retriever-error (fn [err meta] (println \"Error:\", err))})

   ========================================
   自定义嵌入模型
   ========================================

   (require '[im.ttalk.agent.rag.embeddings :as emb])

   (def my-embeddings
     (emb/make-openai-embeddings
       :api-key \"your-key\"
       :model \"text-embedding-3-large\"))

   (def pipeline
     (rag/make-pipeline
       :embeddings-model my-embeddings
       :llm-fn my-llm-fn))

   ========================================
   文本分割
   ========================================

   (require '[im.ttalk.agent.rag.splitter :as splitter])

   (def my-splitter
     (splitter/make-splitter
       :chunk-size 500
       :chunk-overlap 100))

   (splitter/split my-splitter \"Long text...\")
   ;; => [{:content \"...\" :chunk-id \"...\" :chunk-index 0} ...]"
  (:require [im.ttalk.agent.rag.pipeline :as pipeline]
            [im.ttalk.agent.rag.embeddings :as embeddings]
            [im.ttalk.agent.rag.vector-store :as vs]
            [im.ttalk.agent.rag.splitter :as splitter]
            [im.ttalk.agent.rag.utils :as utils]))

;;; ============================================================
;;; 管道创建
;;; ============================================================

(def make-pipeline
  "创建 RAG 管道

   参数：
   - :embeddings-model 嵌入模型（可选）
   - :vector-store     向量存储（可选）
   - :text-splitter    文本分割器（可选）
   - :llm-fn           LLM 调用函数 (fn [prompt] -> response)

   返回：
   RAGPipeline 实例"
  pipeline/make-rag-pipeline)

;;; ============================================================
;;; 索引操作
;;; ============================================================

(def index-document
  "索引文档到 RAG 管道

   参数：
   - pipeline RAG 管道实例
   - content  文档内容
   - :metadata 元数据（可选）

   返回：
   {:ok true :doc-id parent-id :pipeline pipeline}"
  pipeline/index-document)

(def index-documents
  "批量索引文档

   参数：
   - pipeline  RAG 管道实例
   - documents 文档列表 [{:content ... :metadata ...}]

   返回：
   {:ok true :doc-ids [ids...] :pipeline pipeline}"
  pipeline/index-documents)

(def index-file
  "索引文件到 RAG 管道

   参数：
   - pipeline RAG 管道实例
   - filepath 文件路径
   - :metadata 元数据（可选）

   返回：
   {:ok true :doc-id doc-id :pipeline pipeline}"
  pipeline/index-file)

(def index-files
  "批量索引文件

   参数：
   - pipeline  RAG 管道实例
   - filepaths 文件路径列表

   返回：
   {:ok true :doc-ids [ids...] :pipeline pipeline}"
  pipeline/index-files)

;;; ============================================================
;;; 检索操作
;;; ============================================================

(def retrieve
  "检索相关文档

   参数：
   - pipeline RAG 管道实例
   - query    查询文本
   - :top-k         返回结果数量（默认 5）
   - :min-score     最低相似度分数（默认 0.0）
   - :callbacks     回调函数映射
   - :callback-meta 回调元数据

   返回：
   {:ok true :documents [docs...]}"
  pipeline/retrieve)

;;; ============================================================
;;; 生成操作
;;; ============================================================

(def generate
  "生成增强回复

   参数：
   - pipeline RAG 管道实例
   - query    查询文本
   - :top-k         检索文档数量（默认 5）
   - :max-context   最大上下文长度（默认 2000）
   - :callbacks     回调函数映射
   - :callback-meta 回调元数据

   返回：
   {:ok true :answer answer :documents docs :context context}"
  pipeline/generate)

(def query
  "RAG 查询（检索 + 生成）

   参数：
   - pipeline RAG 管道实例
   - q        查询文本
   - :top-k         检索数量（默认 5）
   - :max-context   最大上下文长度（默认 2000）
   - :callbacks     回调函数映射
   - :callback-meta 回调元数据

   返回：
   {:ok true :answer answer :documents docs :context context}"
  pipeline/query)

;;; ============================================================
;;; 管道状态
;;; ============================================================

(def get-store
  "获取向量存储"
  pipeline/get-store)

(def get-document-count
  "获取已索引文档数量"
  pipeline/get-document-count)

(def pipeline-stats
  "获取管道统计信息"
  pipeline/pipeline-stats)

;;; ============================================================
;;; 上下文构建
;;; ============================================================

(def build-context
  "构建 RAG 上下文

   参数：
   - documents     文档列表
   - :max-length    最大上下文长度（可选）
   - :content-limit 单文档内容限制（可选）

   返回：
   格式化的上下文字符串"
  pipeline/build-context)

;;; ============================================================
;;; 文档加载
;;; ============================================================

(def load-document
  "加载文档"
  pipeline/load-document)

(def load-documents
  "批量加载文档"
  pipeline/load-documents)

;;; ============================================================
;;; 嵌入模型
;;; ============================================================

(def make-openai-embeddings
  "创建 OpenAI 嵌入模型"
  embeddings/make-openai-embeddings)

(def make-local-embeddings
  "创建本地嵌入模型（简化实现）"
  embeddings/make-local-embeddings)

(def embed-text
  "生成单个文本的嵌入向量"
  embeddings/embed-text)

(def embed-batch
  "批量生成文本的嵌入向量"
  embeddings/embed-batch)

;;; ============================================================
;;; 向量存储
;;; ============================================================

(def make-vector-store
  "创建内存向量存储"
  vs/make-vector-store)

(def add-document-to-store
  "添加文档到向量存储"
  vs/add-document)

(def search-store
  "在向量存储中搜索"
  vs/search)

;;; ============================================================
;;; 文本分割
;;; ============================================================

(def make-splitter
  "创建文本分割器

   参数：
   - :chunk-size    每个块的最大字符数（默认 1000）
   - :chunk-overlap 相邻块的重叠字符数（默认 200）
   - :separator     切分文本的分隔符（默认换行符）"
  splitter/make-splitter)

(def split
  "分割文本为块列表（带元数据）"
  splitter/split)

(def split-text
  "分割文本为块列表（仅内容）"
  splitter/split-text)

(def split-by-paragraphs
  "按段落分割文本"
  splitter/split-by-paragraphs)

(def split-by-sentences
  "按句子分割文本"
  splitter/split-by-sentences)

;;; ============================================================
;;; 相似度计算
;;; ============================================================

(def cosine-similarity
  "计算余弦相似度"
  utils/cosine-similarity)

(def euclidean-distance
  "计算欧几里得距离"
  utils/euclidean-distance)

(def normalize-vector
  "向量归一化"
  utils/normalize-vector)

;;; ============================================================
;;; 工具函数
;;; ============================================================

(def truncate
  "截断文本"
  utils/truncate)

(def clean-text
  "清理文本"
  utils/clean-text)

(def generate-uuid
  "生成 UUID"
  utils/generate-uuid)
