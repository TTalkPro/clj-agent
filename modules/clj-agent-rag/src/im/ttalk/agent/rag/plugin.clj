(ns im.ttalk.agent.rag.plugin
  "RAG 检索增强生成工具集

   将 RAG 管道操作暴露为 Kernel Plugin，
   使 LLM Agent 可以通过工具调用进行知识索引和检索。"
  (:require [im.ttalk.agent.core.kernel.tool :refer [deftool]]
            [im.ttalk.agent.core.kernel.plugin :refer [defplugin]]
            [im.ttalk.agent.rag.pipeline :as pipeline]
            [clojure.string :as str]))

;;; ============================================================
;;; 辅助函数
;;; ============================================================

(defn- get-pipeline []
  (or (pipeline/get-default-rag-pipeline)
      (throw (ex-info "RAG pipeline 未初始化，请先调用 set-default-rag-pipeline!" {}))))

(defn- format-documents [documents max-chars]
  (let [parts (map-indexed
                (fn [idx doc]
                  (let [content (or (:content doc)
                                    (get-in doc [:document :content])
                                    "")
                        score (get doc :score)]
                    (str "[" (inc idx) "]"
                         (when score (format " (score: %.3f)" (double score)))
                         "\n" content)))
                documents)
        text (str/join "\n---\n" parts)]
    (if (> (count text) max-chars)
      (str (subs text 0 max-chars) "\n...(已截断)")
      text)))

(defn- format-index-result [result]
  (if (:ok result)
    (str "已索引，文档 ID: " (:doc-id result))
    (str "错误: " (get-in result [:error :message] "索引失败"))))

;;; ============================================================
;;; 工具定义
;;; ============================================================

(deftool rag-index-text
  "索引文本到 RAG 知识库"
  [[text :string "要索引的文本内容"]
   [source :string "文本来源标识" :default ""]]
  (try
    (let [p (get-pipeline)
          metadata (if (str/blank? source) {} {:source source})
          result (pipeline/index-document p text :metadata metadata)]
      (format-index-result result))
    (catch Exception e
      (str "错误: " (.getMessage e)))))

(deftool rag-index-file
  "索引文件到 RAG 知识库"
  [[path :string "文件的完整路径"]]
  {:sensitive true}
  (try
    (let [p (get-pipeline)
          result (pipeline/index-file p path)]
      (format-index-result result))
    (catch Exception e
      (str "错误: " (.getMessage e)))))

(deftool rag-retrieve
  "从知识库检索与查询相关的文档片段"
  [[query :string "查询文本"]
   [top-k :int "返回结果数量" :default 5]]
  (try
    (let [p (get-pipeline)
          result (pipeline/retrieve p query :top-k top-k)]
      (if (:ok result)
        (let [docs (:documents result)]
          (if (empty? docs)
            "未找到相关文档"
            (format-documents docs 4000)))
        (str "错误: " (get-in result [:error :message] "检索失败"))))
    (catch Exception e
      (str "错误: " (.getMessage e)))))

(deftool rag-query
  "RAG 查询：检索相关文档并生成回答"
  [[question :string "要回答的问题"]
   [top-k :int "检索文档数量" :default 5]]
  (try
    (let [p (get-pipeline)
          result (pipeline/query p question :top-k top-k)]
      (if (:ok result)
        (or (:answer result)
            (str "检索到 " (count (:documents result)) " 个文档，但未配置 LLM 生成回答:\n"
                 (format-documents (:documents result) 4000)))
        (str "错误: " (get-in result [:error :message] "查询失败"))))
    (catch Exception e
      (str "错误: " (.getMessage e)))))

(deftool rag-search
  "在知识库中进行带分数过滤的向量搜索"
  [[query :string "搜索查询"]
   [top-k :int "返回结果数量" :default 5]
   [min-score :float "最低相似度分数阈值" :default 0.0]]
  (try
    (let [p (get-pipeline)
          result (pipeline/retrieve p query :top-k top-k :min-score min-score)]
      (if (:ok result)
        (let [docs (:documents result)]
          (if (empty? docs)
            "未找到符合条件的文档"
            (format-documents docs 4000)))
        (str "错误: " (get-in result [:error :message] "搜索失败"))))
    (catch Exception e
      (str "错误: " (.getMessage e)))))

(deftool rag-stats
  "获取 RAG 知识库统计信息"
  []
  (try
    (let [p (get-pipeline)
          stats (pipeline/pipeline-stats p)]
      (str "嵌入模型: " (:embeddings-model stats) "\n"
           "文档数量: " (:document-count stats 0) "\n"
           "LLM 已配置: " (:has-llm stats)))
    (catch Exception e
      (str "错误: " (.getMessage e)))))

;;; ============================================================
;;; Plugin 定义
;;; ============================================================

(defplugin rag-tools "RAG 检索增强生成工具集"
  rag-index-text rag-index-file rag-retrieve rag-query rag-search rag-stats)
