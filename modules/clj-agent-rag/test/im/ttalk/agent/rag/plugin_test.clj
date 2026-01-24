(ns im.ttalk.agent.rag.plugin-test
  (:require [clojure.test :refer :all]
            [im.ttalk.agent.rag.plugin :as plugin]
            [im.ttalk.agent.rag.pipeline :as pipeline]
            [im.ttalk.agent.rag.embeddings :as embeddings]
            [im.ttalk.agent.core.kernel.tool :as tool]
            [im.ttalk.agent.core.kernel.plugin :as kp]))

(use-fixtures :each
  (fn [f]
    (pipeline/set-default-rag-pipeline!
      (pipeline/make-rag-pipeline
        :embeddings-model (embeddings/make-local-embeddings :dimension 64)))
    (try (f) (finally (pipeline/reset-default-rag-pipeline!)))))

;;; ============================================================
;;; 元数据测试
;;; ============================================================

(deftest tool-metadata-test
  (testing "rag-index-text schema"
    (let [schema (tool/get-schema #'plugin/rag-index-text)]
      (is (= "rag-index-text" (:name schema)))
      (is (string? (:description schema)))
      (is (= 2 (count (get-in schema [:input_schema :properties]))))))

  (testing "rag-index-file is sensitive"
    (is (tool/sensitive? #'plugin/rag-index-file)))

  (testing "rag-retrieve has default top-k"
    (let [schema (tool/get-schema #'plugin/rag-retrieve)]
      (is (= 2 (count (get-in schema [:input_schema :properties]))))
      (is (= 1 (count (get-in schema [:input_schema :required]))))))

  (testing "rag-stats has no parameters"
    (let [schema (tool/get-schema #'plugin/rag-stats)]
      (is (empty? (get-in schema [:input_schema :properties]))))))

;;; ============================================================
;;; 插件结构测试
;;; ============================================================

(deftest plugin-structure-test
  (testing "rag-tools is a valid KernelPlugin"
    (is (instance? im.ttalk.agent.core.kernel.plugin.KernelPlugin plugin/rag-tools)))

  (testing "plugin contains 6 tools"
    (is (= 6 (kp/function-count plugin/rag-tools))))

  (testing "plugin schemas are generated"
    (let [schemas (kp/get-schemas plugin/rag-tools)]
      (is (= 6 (count schemas)))
      (is (every? #(contains? % :name) schemas))))

  (testing "sensitive tools are marked"
    (is (kp/has-sensitive? plugin/rag-tools))
    (let [sensitive (set (kp/get-sensitive-functions plugin/rag-tools))]
      (is (contains? sensitive :rag-index-file))
      (is (not (contains? sensitive :rag-index-text)))
      (is (not (contains? sensitive :rag-retrieve))))))

;;; ============================================================
;;; 工具调用测试
;;; ============================================================

(deftest rag-index-text-test
  (testing "index text successfully"
    (let [result (plugin/rag-index-text {:text "Clojure is a functional language" :source "test"})]
      (is (clojure.string/includes? result "已索引"))
      (is (clojure.string/includes? result "文档 ID"))))

  (testing "index text without source"
    (let [result (plugin/rag-index-text {:text "Hello world" :source ""})]
      (is (clojure.string/includes? result "已索引")))))

(deftest rag-retrieve-test
  (testing "retrieve from indexed documents"
    (plugin/rag-index-text {:text "Erlang is a concurrent programming language" :source "doc1"})
    (plugin/rag-index-text {:text "Clojure runs on the JVM" :source "doc2"})
    (let [result (plugin/rag-retrieve {:query "programming language" :top-k 5})]
      (is (not (clojure.string/includes? result "错误")))
      (is (not= "未找到相关文档" result)))))

(deftest rag-search-test
  (testing "search with min-score filter"
    (plugin/rag-index-text {:text "Vector databases store embeddings" :source "test"})
    (let [result (plugin/rag-search {:query "vector database" :top-k 3 :min-score 0.0})]
      (is (not (clojure.string/includes? result "错误"))))))

(deftest rag-stats-test
  (testing "stats on empty pipeline"
    (let [result (plugin/rag-stats {})]
      (is (clojure.string/includes? result "嵌入模型"))
      (is (clojure.string/includes? result "文档数量"))
      (is (clojure.string/includes? result "LLM 已配置"))))

  (testing "stats after indexing"
    (plugin/rag-index-text {:text "Test document" :source ""})
    (let [result (plugin/rag-stats {})]
      (is (clojure.string/includes? result "嵌入模型")))))

;;; ============================================================
;;; 错误处理测试
;;; ============================================================

(deftest error-handling-test
  (testing "error when indexing non-existent file"
    (let [result (plugin/rag-index-file {:path "/nonexistent/path/file.txt"})]
      (is (clojure.string/includes? result "错误"))))

  (testing "rag-query without llm-fn returns documents"
    (plugin/rag-index-text {:text "Test document content" :source "test"})
    (let [result (plugin/rag-query {:question "test" :top-k 5})]
      ;; No LLM configured, so it should return retrieved documents
      (is (not (clojure.string/includes? result "错误"))))))
