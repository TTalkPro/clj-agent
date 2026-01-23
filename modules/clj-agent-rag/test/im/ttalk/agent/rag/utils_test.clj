(ns im.ttalk.agent.rag.utils-test
  "RAG 工具函数测试"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.rag.utils :as utils]))

;;; ============================================================
;;; UUID 生成测试
;;; ============================================================

(deftest generate-uuid-test
  (testing "生成 UUID"
    (let [uuid (utils/generate-uuid)]
      (is (string? uuid))
      (is (= 36 (count uuid)))  ; UUID 格式: 8-4-4-4-12
      (is (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" uuid))))

  (testing "每次生成的 UUID 都不同"
    (let [uuids (repeatedly 10 utils/generate-uuid)]
      (is (= 10 (count (distinct uuids)))))))

;;; ============================================================
;;; 类型转换测试
;;; ============================================================

(deftest ensure-string-test
  (testing "字符串保持不变"
    (is (= "hello" (utils/ensure-string "hello"))))

  (testing "nil 转换为空字符串"
    (is (= "" (utils/ensure-string nil))))

  (testing "数字转换为字符串"
    (is (= "123" (utils/ensure-string 123)))
    (is (= "3.14" (utils/ensure-string 3.14)))))

(deftest to-float-test
  (testing "float 保持不变"
    (is (= 3.14 (utils/to-float 3.14))))

  (testing "integer 转换为 float"
    (is (= 42.0 (utils/to-float 42))))

  (testing "字符串转换为 float"
    (is (= 3.14 (utils/to-float "3.14"))))

  (testing "无效输入返回 0.0"
    (is (= 0.0 (utils/to-float "invalid")))
    (is (= 0.0 (utils/to-float nil)))))

(deftest to-int-test
  (testing "integer 保持不变"
    (is (= 42 (utils/to-int 42))))

  (testing "float 转换为 int"
    (is (= 3 (utils/to-int 3.14))))

  (testing "字符串转换为 int"
    (is (= 42 (utils/to-int "42"))))

  (testing "无效输入返回 0"
    (is (= 0 (utils/to-int "invalid")))
    (is (= 0 (utils/to-int nil)))))

;;; ============================================================
;;; 向量运算测试
;;; ============================================================

(deftest dot-product-test
  (testing "点积计算"
    (is (= 32 (utils/dot-product [1 2 3] [4 5 6])))
    (is (= 0 (utils/dot-product [1 0 0] [0 1 0])))))

(deftest vector-norm-test
  (testing "向量模计算"
    (is (= 5.0 (utils/vector-norm [3 4])))
    (is (= 1.0 (utils/vector-norm [1 0 0])))))

(deftest safe-divide-test
  (testing "正常除法"
    (is (= 2 (utils/safe-divide 4 2))))

  (testing "除零返回默认值"
    (is (= 0.0 (utils/safe-divide 4 0)))
    (is (= -1.0 (utils/safe-divide 4 0 -1.0)))))

(deftest cosine-similarity-test
  (testing "相同向量相似度为 1"
    (is (= 1.0 (utils/cosine-similarity [1 0 0] [1 0 0]))))

  (testing "正交向量相似度为 0"
    (is (= 0.0 (utils/cosine-similarity [1 0 0] [0 1 0]))))

  (testing "相反向量相似度为 -1"
    (is (= -1.0 (utils/cosine-similarity [1 0] [-1 0])))))

(deftest euclidean-distance-test
  (testing "3-4-5 三角形"
    (is (= 5.0 (utils/euclidean-distance [0 0] [3 4]))))

  (testing "相同点距离为 0"
    (is (= 0.0 (utils/euclidean-distance [1 2 3] [1 2 3])))))

(deftest normalize-vector-test
  (testing "归一化向量"
    (let [normalized (utils/normalize-vector [3 4])]
      (is (= 0.6 (first normalized)))
      (is (= 0.8 (second normalized)))))

  (testing "零向量保持不变"
    (is (= [0 0 0] (utils/normalize-vector [0 0 0])))))

;;; ============================================================
;;; 文本处理测试
;;; ============================================================

(deftest truncate-test
  (testing "短文本不截断"
    (is (= "hello" (utils/truncate "hello" 10))))

  (testing "长文本截断"
    (is (= "hello w..." (utils/truncate "hello world" 10))))

  (testing "自定义后缀"
    (is (= "hel[MORE]" (utils/truncate "hello world" 9 "[MORE]")))))

(deftest clean-text-test
  (testing "规范化换行符"
    (is (= "line1\nline2" (utils/clean-text "line1\r\nline2"))))

  (testing "移除多余空白"
    (is (= "a b c" (utils/clean-text "a  b   c"))))

  (testing "压缩多余换行"
    (is (= "a\n\nb" (utils/clean-text "a\n\n\n\nb")))))

(deftest word-count-test
  (testing "计算单词数"
    (is (= 3 (utils/word-count "hello world test")))
    (is (= 1 (utils/word-count "hello")))))

(deftest char-count-test
  (testing "计算字符数（不含空白）"
    (is (= 10 (utils/char-count "hello world")))))

;;; ============================================================
;;; 集合操作测试
;;; ============================================================

(deftest zip-with-index-test
  (testing "添加索引"
    (is (= [[0 "a"] [1 "b"] [2 "c"]]
           (vec (utils/zip-with-index ["a" "b" "c"]))))))

(deftest take-safe-test
  (testing "取前 N 个"
    (is (= [1 2 3] (vec (utils/take-safe 3 [1 2 3 4 5])))))

  (testing "列表长度不足"
    (is (= [1 2] (vec (utils/take-safe 5 [1 2]))))))

;;; ============================================================
;;; Map 操作测试
;;; ============================================================

(deftest get-opt-test
  (testing "获取存在的值"
    (is (= 1 (utils/get-opt {:a 1} :a 0))))

  (testing "获取不存在的值返回默认值"
    (is (= 0 (utils/get-opt {:a 1} :b 0)))))

(deftest deep-merge-test
  (testing "深度合并"
    (is (= {:a {:b 2 :c 3}}
           (utils/deep-merge {:a {:b 1}} {:a {:b 2 :c 3}})))))

;;; ============================================================
;;; 错误处理测试
;;; ============================================================

(deftest try-or-test
  (testing "成功执行返回结果"
    (is (= 42 (utils/try-or #(+ 40 2) 0))))

  (testing "失败返回默认值"
    (is (= 0 (utils/try-or #(/ 1 0) 0)))))

(deftest result-functions-test
  (testing "result-ok"
    (let [r (utils/result-ok 42)]
      (is (true? (:ok r)))
      (is (= 42 (:value r)))))

  (testing "result-error"
    (let [r (utils/result-error "failed")]
      (is (false? (:ok r)))
      (is (= "failed" (:error r)))))

  (testing "ok?"
    (is (true? (utils/ok? {:ok true})))
    (is (false? (utils/ok? {:ok false})))
    (is (false? (utils/ok? {})))))
