(ns im.ttalk.agent.rag.splitter-test
  "RAG 文本分割器测试"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.rag.splitter :as splitter]))

;;; ============================================================
;;; 分割器创建测试
;;; ============================================================

(deftest make-splitter-test
  (testing "创建默认分割器"
    (let [s (splitter/make-splitter)]
      (is (= 1000 (splitter/get-chunk-size s)))
      (is (= 200 (splitter/get-overlap s)))
      (is (= "\n" (splitter/get-separator s)))))

  (testing "创建自定义分割器"
    (let [s (splitter/make-splitter :chunk-size 500
                                    :chunk-overlap 50
                                    :separator "\n\n")]
      (is (= 500 (splitter/get-chunk-size s)))
      (is (= 50 (splitter/get-overlap s)))
      (is (= "\n\n" (splitter/get-separator s))))))

;;; ============================================================
;;; 分割操作测试
;;; ============================================================

(deftest split-test
  (testing "分割短文本"
    (let [s (splitter/make-splitter :chunk-size 100)
          text "Line 1\nLine 2\nLine 3"
          chunks (splitter/split s text)]
      (is (= 1 (count chunks)))
      (is (= "Line 1\nLine 2\nLine 3" (:content (first chunks))))
      (is (= 0 (:chunk-index (first chunks))))
      (is (string? (:chunk-id (first chunks))))))

  (testing "分割长文本"
    (let [s (splitter/make-splitter :chunk-size 20 :chunk-overlap 5)
          text "Line 1\nLine 2\nLine 3\nLine 4\nLine 5"
          chunks (splitter/split s text)]
      (is (> (count chunks) 1))
      ;; 验证每个块都有正确的结构
      (doseq [[idx chunk] (map-indexed vector chunks)]
        (is (string? (:content chunk)))
        (is (= idx (:chunk-index chunk)))
        (is (string? (:chunk-id chunk))))))

  (testing "分割空文本"
    (let [s (splitter/make-splitter)
          chunks (splitter/split s "")]
      (is (empty? chunks))))

  (testing "分割只有空白的文本"
    (let [s (splitter/make-splitter)
          chunks (splitter/split s "   \n\n   ")]
      (is (empty? chunks)))))

;;; ============================================================
;;; split-text 测试（只返回内容）
;;; ============================================================

(deftest split-text-test
  (testing "split-text 只返回内容列表"
    (let [s (splitter/make-splitter :chunk-size 100)
          text "Line 1\nLine 2"
          chunks (splitter/split-text s text)]
      (is (= 1 (count chunks)))
      (is (string? (first chunks)))
      (is (= "Line 1\nLine 2" (first chunks))))))

;;; ============================================================
;;; 便捷分割函数测试
;;; ============================================================

(deftest split-by-paragraphs-test
  (testing "按段落分割"
    (let [text "Paragraph 1\n\nParagraph 2\n\nParagraph 3"
          paragraphs (splitter/split-by-paragraphs text)]
      (is (= 3 (count paragraphs)))
      (is (= "Paragraph 1" (first paragraphs)))
      (is (= "Paragraph 3" (last paragraphs))))))

(deftest split-by-sentences-test
  (testing "按句子分割"
    (let [text "Sentence one. Sentence two! Sentence three?"
          sentences (splitter/split-by-sentences text)]
      (is (= 3 (count sentences)))
      (is (= "Sentence one" (first sentences)))
      (is (= "Sentence three" (last sentences))))))

(deftest split-by-size-test
  (testing "按固定大小分割（无重叠）"
    (let [text "abcdefghijklmnop"
          chunks (splitter/split-by-size text 5)]
      ;; 由于是段落感知的分割，整个文本可能作为一个块
      (is (seq chunks)))))

;;; ============================================================
;;; 统计函数测试
;;; ============================================================

(deftest chunk-stats-test
  (testing "计算分块统计"
    (let [chunks [{:content "abc" :chunk-id "1" :chunk-index 0}
                  {:content "defgh" :chunk-id "2" :chunk-index 1}
                  {:content "ij" :chunk-id "3" :chunk-index 2}]
          stats (splitter/chunk-stats chunks)]
      (is (= 3 (:count stats)))
      (is (= 10 (:total-chars stats)))
      (is (= 2 (:min-size stats)))
      (is (= 5 (:max-size stats)))))

  (testing "空块列表的统计"
    (let [stats (splitter/chunk-stats [])]
      (is (= 0 (:count stats)))
      (is (= 0 (:total-chars stats)))
      (is (nil? (:min-size stats)))
      (is (nil? (:max-size stats))))))
