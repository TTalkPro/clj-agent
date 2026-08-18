(ns im.ttalk.agent.model.content-test
  "中立多模态部件（model/content）单测——构造、来源归一、谓词、文本抽取。"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [im.ttalk.agent.model.content :as content]
            [im.ttalk.agent.model.message :as msg])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest text-part-test
  (is (= {:type :text :text "hi"} (content/text-part "hi")))
  (is (content/part? (content/text-part "hi")))
  (is (content/text-part? (content/text-part "hi")))
  (is (not (content/file-part? (content/text-part "hi")))))

(deftest image-part-source-coercion-test
  (testing "http(s) URL → :url，猜不出类型时兜底 image/*"
    (let [p (content/image-part "https://example.com/a")]
      (is (= "https://example.com/a" (:url p)))
      (is (= "image/*" (:media-type p)))
      (is (nil? (:data p)))))

  (testing "URL 带扩展名 → 猜出具体 media type"
    (is (= "image/png" (:media-type (content/image-part "https://example.com/a.png")))))

  (testing "data URI → 拆成 media-type + base64 数据"
    (let [p (content/image-part "data:image/jpeg;base64,QUJD")]
      (is (= "image/jpeg" (:media-type p)))
      (is (= "QUJD" (:data p)))
      (is (nil? (:url p)))))

  (testing "byte[] → base64（须显式 media-type）"
    (let [p (content/image-part (.getBytes "ABC" "UTF-8") {:media-type "image/png"})]
      (is (= "QUJD" (:data p)))
      (is (= "image/png" (:media-type p)))))

  (testing "内联数据缺 media-type 直接抛错，不猜"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"必须提供 :media-type"
                          (content/file-part "QUJD"))))

  (testing "明文（非 base64）data URI 不静默截断"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"base64"
                          (content/image-part "data:text/plain,hello"))))

  (testing "不认识的来源类型抛错"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"无法识别的内容来源"
                          (content/image-part 42 {:media-type "image/png"})))))

(deftest file-source-test
  (testing "java.io.File → base64 + 从扩展名猜 media type + 带上文件名"
    (let [dir (.toFile (Files/createTempDirectory "content-test" (make-array FileAttribute 0)))
          f (File. dir "hello.png")]
      (io/copy (.getBytes "ABC" "UTF-8") f)
      (let [p (content/image-part f)]
        (is (= "QUJD" (:data p)))
        (is (= "image/png" (:media-type p)))
        (is (= "hello.png" (:filename p))))
      (.delete f)
      (.delete dir))))

(deftest parts-predicate-test
  (testing "字符串 content 不是部件向量"
    (is (false? (content/parts? "纯文本"))))

  (testing "厂商原生块（:type 为字符串）不算中立部件——wire 层原样透传"
    (is (false? (content/parts? [{:type "text" :text "raw"}])))
    (is (false? (content/part? {:type "image" :source {}}))))

  (testing "混合向量：只要有一个中立部件就按多模态处理"
    (is (true? (content/parts? [{:type "text" :text "raw"}
                                (content/text-part "中立")])))))

(deftest accessors-test
  (let [img (content/image-part "data:image/png;base64,QUJD")
        audio (content/audio-part "QUJD" {:media-type "audio/wav"})]
    (is (= "image" (content/top-level-media-type img)))
    (is (content/image? img))
    (is (not (content/audio? img)))
    (is (content/audio? audio))
    (is (= "data:image/png;base64,QUJD" (content/data-uri img)))
    (is (nil? (content/data-uri (content/image-part "https://x/a.png"))))))

(deftest text-of-test
  (is (= "纯文本" (content/text-of "纯文本")))
  (is (= "" (content/text-of nil)))
  (testing "部件向量取文本部件；图片不参与"
    (is (= "看图 说话"
           (content/text-of [(content/text-part "看图")
                             (content/image-part "https://x/a.png")
                             (content/text-part "说话")]))))
  (testing "厂商原生块认 :text 键（safeguard/日志等按文本匹配的场景不瞎)"
    (is (= "raw" (content/text-of [{:type "text" :text "raw"}])))))

(deftest neutral-message-carries-parts-test
  (testing "中立消息直接装部件向量，role 与形状不变"
    (let [m (msg/user [(content/text-part "描述") (content/image-part "https://x/a.png")])]
      (is (= :user (msg/role m)))
      (is (content/parts? (msg/content m)))
      (is (= 2 (count (msg/content m)))))))
