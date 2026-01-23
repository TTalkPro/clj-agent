(ns im.ttalk.agent.rag.pipeline
  "RAG 管道模块

   实现检索增强生成（Retrieval-Augmented Generation）完整管道：
   - 文档加载和分割
   - 嵌入和索引
   - 检索和生成

   主要功能：
   - index-document: 索引文档
   - index-file: 索引文件
   - retrieve: 检索相关文档（支持回调）
   - query: RAG 查询（检索 + 生成）

   回调支持：
   - :on-retriever-start (fn [query meta] ...)
   - :on-retriever-end   (fn [documents meta] ...)
   - :on-retriever-error (fn [error meta] ...)

   参考 Erlang agent_rag 设计。"
  (:require [im.ttalk.agent.rag.embeddings :as embeddings]
            [im.ttalk.agent.rag.vector-store :as vs]
            [im.ttalk.agent.rag.splitter :as splitter]
            [im.ttalk.agent.core.common :as common]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.util UUID]))

;;; ============================================================
;;; 配置
;;; ============================================================

(def ^:dynamic *verbose*
  "是否输出详细日志"
  false)

;;; ============================================================
;;; 常量定义
;;; ============================================================

(def ^:const default-top-k
  "默认检索数量"
  5)

(def ^:const default-max-context
  "默认最大上下文长度"
  2000)

;;; ============================================================
;;; 回调系统
;;; ============================================================

(defn- invoke-callback
  "调用回调函数

   参数：
   - callback-name 回调名称（关键字）
   - args          回调参数列表
   - callbacks     回调函数 map
   - meta          附加元数据

   返回：
   nil（回调结果被忽略）"
  [callback-name args callbacks meta]
  (when-let [handler (get callbacks callback-name)]
    (try
      (apply handler (conj (vec args) meta))
      (catch Exception e
        (log/warn "[RAG] Callback" callback-name "failed:" (.getMessage e))))))

;;; ============================================================
;;; 文档加载器
;;; ============================================================

(def supported-formats #{:txt :md :clj :edn :json :html :xml})

(defn load-document
  "加载文档

   参数：
   - filepath 文件路径

   返回：
   文档内容字符串

   支持格式：
   .txt, .md, .clj, .edn, .json, .html, .xml"
  [filepath]
  (let [ext (-> filepath (str/split #"\.") last str/lower-case keyword)]
    (when-not (contains? supported-formats ext)
      (throw (ex-info "Unsupported file format"
                      {:format ext :supported supported-formats})))
    (slurp filepath)))

(defn load-documents
  "批量加载文档

   参数：
   - filepaths 文件路径列表

   返回：
   {:path content} map"
  [filepaths]
  (into {} (map (fn [path] [path (load-document path)]) filepaths)))

;;; ============================================================
;;; RAG 管道
;;; ============================================================

(defrecord RAGPipeline [embeddings-model vector-store text-splitter llm-fn])

(defn make-rag-pipeline
  "创建 RAG 管道

   参数：
   - :embeddings-model 嵌入模型（可选）
   - :vector-store     向量存储（可选）
   - :text-splitter    文本分割器（可选）
   - :llm-fn           LLM 调用函数 (fn [prompt] -> response)

   返回：
   RAGPipeline 实例

   示例：
   (make-rag-pipeline
     :llm-fn (fn [prompt] (call-llm prompt)))"
  [& {:keys [embeddings-model vector-store text-splitter llm-fn]}]
  (->RAGPipeline (or embeddings-model (embeddings/get-default-embedding-model))
                 (or vector-store (vs/make-vector-store))
                 (or text-splitter (splitter/make-splitter))
                 llm-fn))

;;; ============================================================
;;; 管道状态访问
;;; ============================================================

(defn get-store
  "获取向量存储

   参数：
   - pipeline RAG 管道实例

   返回：
   向量存储实例"
  [pipeline]
  (:vector-store pipeline))

(defn get-document-count
  "获取已索引文档数量

   参数：
   - pipeline RAG 管道实例

   返回：
   文档数量"
  [pipeline]
  (vs/document-count (:vector-store pipeline)))

;;; ============================================================
;;; 索引操作
;;; ============================================================

(defn- store-chunks
  "存储所有分块

   参数：
   - pipeline  RAG 管道
   - chunks    分块列表
   - parent-id 父文档 ID
   - metadata  基础元数据

   返回：
   {:ok true :doc-id parent-id :pipeline updated-pipeline}
   或
   {:ok false :error reason}"
  [pipeline chunks parent-id metadata]
  (let [{:keys [embeddings-model vector-store]} pipeline
        texts (mapv :content chunks)]
    (try
      (let [embeddings-result (embeddings/embed-batch embeddings-model texts)]
        (doseq [[chunk embedding] (map vector chunks embeddings-result)]
          (let [chunk-meta (merge metadata
                                  {:parent-id parent-id
                                   :chunk-id (:chunk-id chunk)
                                   :chunk-index (:chunk-index chunk)})]
            (vs/add-document vector-store (:content chunk) embedding chunk-meta)))
        {:ok true :doc-id parent-id :pipeline pipeline})
      (catch Exception e
        {:ok false :error {:type :embedding-error :message (.getMessage e)}}))))

(defn index-document
  "索引文档到 RAG 管道

   参数：
   - pipeline RAG 管道实例
   - content  文档内容
   - metadata 元数据（可选）

   返回：
   {:ok true :doc-id parent-id :pipeline pipeline}
   或
   {:ok false :error error-info}

   说明：
   1. 分割文档为块
   2. 生成每个块的嵌入
   3. 添加到向量存储"
  [pipeline content & {:keys [metadata] :or {metadata {}}}]
  (let [{:keys [text-splitter]} pipeline
        chunks (splitter/split text-splitter content)
        parent-id (str (UUID/randomUUID))]
    (when *verbose*
      (log/info "[RAG] Indexing document:" parent-id "(" (count chunks) "chunks)"))
    (store-chunks pipeline chunks parent-id metadata)))

(defn index-documents
  "批量索引文档

   参数：
   - pipeline  RAG 管道实例
   - documents 文档列表 [{:content ... :metadata ...}]

   返回：
   {:ok true :doc-ids [ids...] :pipeline pipeline}
   或
   {:ok false :error error-info}"
  [pipeline documents]
  (loop [remaining documents
         doc-ids []
         current-pipeline pipeline]
    (if (empty? remaining)
      {:ok true :doc-ids doc-ids :pipeline current-pipeline}
      (let [{:keys [content metadata]} (first remaining)
            result (index-document current-pipeline content :metadata (or metadata {}))]
        (if (:ok result)
          (recur (rest remaining)
                 (conj doc-ids (:doc-id result))
                 (:pipeline result))
          result)))))

(defn index-file
  "索引文件到 RAG 管道

   参数：
   - pipeline RAG 管道实例
   - filepath 文件路径
   - metadata 元数据（可选）

   返回：
   {:ok true :doc-id doc-id :pipeline pipeline}
   或
   {:ok false :error error-info}"
  [pipeline filepath & {:keys [metadata] :or {metadata {}}}]
  (try
    (let [content (load-document filepath)
          file-metadata (assoc metadata
                               :source filepath
                               :filename (last (str/split filepath #"/")))]
      (index-document pipeline content :metadata file-metadata))
    (catch Exception e
      {:ok false :error {:type :file-read-error :message (.getMessage e)}})))

(defn index-files
  "批量索引文件

   参数：
   - pipeline  RAG 管道实例
   - filepaths 文件路径列表

   返回：
   {:ok true :doc-ids [ids...] :pipeline pipeline}"
  [pipeline filepaths]
  (loop [remaining filepaths
         doc-ids []
         current-pipeline pipeline]
    (if (empty? remaining)
      {:ok true :doc-ids doc-ids :pipeline current-pipeline}
      (let [result (index-file current-pipeline (first remaining))]
        (if (:ok result)
          (recur (rest remaining)
                 (conj doc-ids (:doc-id result))
                 (:pipeline result))
          ;; 跳过失败的文件，继续处理
          (recur (rest remaining)
                 doc-ids
                 current-pipeline))))))

;;; ============================================================
;;; 检索操作
;;; ============================================================

(defn- embed-and-search
  "嵌入查询并搜索

   参数：
   - model       嵌入模型
   - store       向量存储
   - query       查询文本
   - search-opts 搜索选项

   返回：
   {:ok true :results results}
   或
   {:ok false :error error-info}"
  [model store query search-opts]
  (try
    (let [query-vector (embeddings/embed-text model query)
          top-k (:top-k search-opts default-top-k)
          min-score (:min-score search-opts 0.0)
          results (vs/search store query-vector top-k min-score)]
      {:ok true :results results})
    (catch Exception e
      {:ok false :error {:type :embedding-error :message (.getMessage e)}})))

(defn retrieve
  "检索相关文档

   参数：
   - pipeline RAG 管道实例
   - query    查询文本
   - opts     选项 map：
     - :top-k         返回结果数量（默认 5）
     - :min-score     最低相似度分数（默认 0.0）
     - :callbacks     回调函数映射
       - :on-retriever-start (fn [query meta] ...)
       - :on-retriever-end   (fn [documents meta] ...)
       - :on-retriever-error (fn [error meta] ...)
     - :callback-meta 回调元数据

   返回：
   {:ok true :documents [docs...]}
   或
   {:ok false :error error-info}"
  [pipeline query & {:keys [top-k min-score callbacks callback-meta]
                     :or {top-k default-top-k min-score 0.0
                          callbacks {} callback-meta {}}}]
  (let [{:keys [embeddings-model vector-store]} pipeline
        search-opts {:top-k top-k :min-score min-score}]
    ;; 调用 on-retriever-start 回调
    (invoke-callback :on-retriever-start [query] callbacks callback-meta)

    (let [result (embed-and-search embeddings-model vector-store query search-opts)]
      (if (:ok result)
        (do
          ;; 调用 on-retriever-end 回调
          (invoke-callback :on-retriever-end [(:results result)] callbacks callback-meta)
          (when *verbose*
            (log/info "[RAG] Retrieved" (count (:results result)) "documents"))
          {:ok true :documents (:results result)})
        (do
          ;; 调用 on-retriever-error 回调
          (invoke-callback :on-retriever-error [(:error result)] callbacks callback-meta)
          result)))))

;;; ============================================================
;;; 上下文构建
;;; ============================================================

(defn- truncate-content
  "截断内容

   参数：
   - content    内容字符串
   - max-length 最大长度

   返回：
   截断后的字符串"
  [content max-length]
  (if (> (count content) max-length)
    (str (subs content 0 max-length) "...")
    content))

(defn- format-search-result
  "格式化搜索结果

   参数：
   - result 搜索结果 {:document ... :score ...}

   返回：
   格式化字符串"
  [result]
  (let [content (get-in result [:document :content] "")
        score (get result :score 0.0)]
    (format "[Score: %.3f]\n%s" score content)))

(defn build-context
  "构建 RAG 上下文

   参数：
   - documents     文档列表
   - max-length    最大上下文长度（可选，默认 2000）
   - content-limit 单文档内容限制（可选，默认 500）

   返回：
   格式化的上下文字符串"
  [documents & {:keys [max-length content-limit]
                :or {max-length default-max-context content-limit 500}}]
  (let [parts (map-indexed
                (fn [idx doc]
                  (let [content (truncate-content
                                  (or (:content doc)
                                      (get-in doc [:document :content])
                                      "")
                                  content-limit)]
                    (format "[%d] %s" (inc idx) content)))
                documents)
        context (str/join "\n\n---\n\n" parts)]
    (truncate-content context max-length)))

;;; ============================================================
;;; 生成操作
;;; ============================================================

(def ^:private rag-prompt-template
  "Based on the following context, please answer the question.

Context:
%s

Question: %s

Answer:")

(defn- generate-with-context
  "使用上下文生成回答

   参数：
   - pipeline RAG 管道
   - question 问题
   - context  上下文

   返回：
   {:ok true :answer answer}
   或
   {:ok false :error error-info}"
  [pipeline question context]
  (let [prompt (format rag-prompt-template context question)]
    (try
      (let [answer ((:llm-fn pipeline) prompt)]
        {:ok true :answer answer})
      (catch Exception e
        {:ok false :error {:type :llm-error :message (.getMessage e)}}))))

(defn generate
  "生成增强回复

   参数：
   - pipeline RAG 管道实例
   - query    查询文本
   - opts     选项 map：
     - :top-k            检索文档数量（默认 5）
     - :max-context      最大上下文长度（默认 2000）
     - :callbacks        回调函数映射
     - :callback-meta    回调元数据

   返回：
   {:ok true :answer answer :documents docs :context context}
   或
   {:ok false :error error-info}

   说明：
   1. 检索相关文档
   2. 构建增强提示
   3. 调用 LLM 生成回复"
  [pipeline query & {:keys [top-k max-context callbacks callback-meta]
                     :or {top-k default-top-k max-context default-max-context
                          callbacks {} callback-meta {}}}]
  (when-not (:llm-fn pipeline)
    (throw (ex-info "LLM function is required for generation"
                    {:type :missing-llm-fn})))

  (let [retrieve-result (retrieve pipeline query
                                   :top-k top-k
                                   :callbacks callbacks
                                   :callback-meta callback-meta)]
    (if (:ok retrieve-result)
      (let [documents (:documents retrieve-result)
            context (build-context documents :max-length max-context)
            gen-result (generate-with-context pipeline query context)]
        (if (:ok gen-result)
          {:ok true
           :answer (:answer gen-result)
           :documents documents
           :context context}
          gen-result))
      retrieve-result)))

(defn query
  "RAG 查询（检索 + 生成）

   参数：
   - pipeline RAG 管道实例
   - q        查询文本
   - opts     选项 map：
     - :top-k            检索数量（默认 5）
     - :max-context      最大上下文长度（默认 2000）
     - :callbacks        回调函数映射
     - :callback-meta    回调元数据

   返回：
   {:ok true :answer answer :documents docs :context context}
   或
   {:ok false :error error-info :documents docs :context context}"
  [pipeline q & {:keys [top-k max-context callbacks callback-meta]
                 :or {top-k default-top-k max-context default-max-context
                      callbacks {} callback-meta {}}}]
  (let [retrieve-result (retrieve pipeline q
                                   :top-k top-k
                                   :callbacks callbacks
                                   :callback-meta callback-meta)]
    (if (:ok retrieve-result)
      (let [documents (:documents retrieve-result)
            context (build-context documents :max-length max-context)]
        (if (:llm-fn pipeline)
          (let [gen-result (generate-with-context pipeline q context)]
            (if (:ok gen-result)
              {:ok true
               :answer (:answer gen-result)
               :documents documents
               :context context}
              (assoc gen-result
                     :documents documents
                     :context context)))
          ;; 没有 LLM，只返回检索结果
          {:ok true
           :answer nil
           :documents documents
           :context context}))
      retrieve-result)))

;;; ============================================================
;;; 默认管道管理（使用公共宏）
;;; ============================================================

(common/defdefault rag-pipeline
  :constructor make-rag-pipeline
  :doc "默认 RAG 管道")

;;; ============================================================
;;; 便捷函数（使用默认管道）
;;; ============================================================

(defn index-document*
  "索引文档到默认管道

   参数：
   - content  文档内容
   - metadata 元数据（可选）

   返回：
   索引结果"
  [content & {:keys [metadata]}]
  (index-document (get-default-rag-pipeline) content :metadata (or metadata {})))

(defn index-file*
  "索引文件到默认管道

   参数：
   - filepath 文件路径
   - metadata 元数据（可选）

   返回：
   索引结果"
  [filepath & {:keys [metadata]}]
  (index-file (get-default-rag-pipeline) filepath :metadata (or metadata {})))

(defn retrieve*
  "从默认管道检索文档

   参数：
   - query 查询文本
   - opts  选项

   返回：
   检索结果"
  [query & {:keys [top-k callbacks callback-meta]
            :or {top-k default-top-k callbacks {} callback-meta {}}}]
  (retrieve (get-default-rag-pipeline) query
            :top-k top-k
            :callbacks callbacks
            :callback-meta callback-meta))

(defn generate*
  "使用默认管道生成回复

   参数：
   - query 查询文本
   - opts  选项

   返回：
   生成结果"
  [query & {:keys [top-k callbacks callback-meta]
            :or {top-k default-top-k callbacks {} callback-meta {}}}]
  (generate (get-default-rag-pipeline) query
            :top-k top-k
            :callbacks callbacks
            :callback-meta callback-meta))

(defn query*
  "使用默认管道查询

   参数：
   - q    查询文本
   - opts 选项

   返回：
   查询结果"
  [q & {:keys [top-k callbacks callback-meta]
        :or {top-k default-top-k callbacks {} callback-meta {}}}]
  (query (get-default-rag-pipeline) q
         :top-k top-k
         :callbacks callbacks
         :callback-meta callback-meta))

;;; ============================================================
;;; 管道统计
;;; ============================================================

(defn pipeline-stats
  "获取管道统计信息

   参数：
   - pipeline RAG 管道实例

   返回：
   统计信息 map"
  [pipeline]
  (let [store-stats (vs/store-stats (:vector-store pipeline))]
    (assoc store-stats
           :embeddings-model (embeddings/model-name (:embeddings-model pipeline))
           :has-llm (boolean (:llm-fn pipeline)))))

;;; ============================================================
;;; 向后兼容的分割函数（委托给 splitter 模块）
;;; ============================================================

(defn make-text-splitter
  "创建文本分割器（向后兼容）

   请使用 im.ttalk.agent.rag.splitter/make-splitter"
  [& {:keys [chunk-size chunk-overlap separator]
      :or {chunk-size 1000 chunk-overlap 200 separator "\n"}}]
  (splitter/make-splitter :chunk-size chunk-size
                          :chunk-overlap chunk-overlap
                          :separator separator))

(defn split-text
  "分割文本（向后兼容）

   请使用 im.ttalk.agent.rag.splitter/split-text"
  [text splitter-or-opts]
  (if (instance? im.ttalk.agent.rag.splitter.Splitter splitter-or-opts)
    (splitter/split-text splitter-or-opts text)
    ;; 旧式调用：splitter 是 TextSplitter record
    (let [{:keys [chunk-size chunk-overlap]} splitter-or-opts
          new-splitter (splitter/make-splitter :chunk-size chunk-size
                                               :chunk-overlap chunk-overlap)]
      (splitter/split-text new-splitter text))))

(defn split-by-separator
  "按分隔符分割文本

   参数：
   - text      输入文本
   - separator 分隔符（默认双换行）

   返回：
   文本块列表"
  [text & {:keys [separator] :or {separator "\n\n"}}]
  (splitter/split-by-paragraphs text))

(defn split-by-sentences
  "按句子分割文本

   参数：
   - text 输入文本

   返回：
   句子列表"
  [text]
  (splitter/split-by-sentences text))
