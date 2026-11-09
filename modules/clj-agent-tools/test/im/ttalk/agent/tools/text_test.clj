(ns im.ttalk.agent.tools.text-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.tools.syntax :as syntax]
            [im.ttalk.agent.tools.text :as text]))

(defn- bytes-of [& xs] (byte-array (map unchecked-byte xs)))

(deftest content-tag-vectors
  (is (= "4F9F2CAB" (text/content-tag "hello")) "coxswain / Keelson 的公开测试向量")
  (is (= "811C9DC5" (text/content-tag "")) "空内容 = offset basis")
  (is (= (text/content-tag "中文") (text/content-tag (.getBytes "中文" "UTF-8")))))

(deftest take-bytes
  (is (= "ab" (text/take-bytes-at-char-boundary "abc" 2)))
  (is (= "中" (text/take-bytes-at-char-boundary "中文" 4)) "不切半个字符")
  (is (= "中文" (text/take-bytes-at-char-boundary "中文" 6))))

(deftest editable-round-trip
  (testing "CRLF 文件：文本统一成 \\n，写回还是 CRLF"
    (let [f (text/decode-editable (.getBytes "a\r\nb\r\n" "UTF-8") "x")]
      (is (= :crlf (:eol f)))
      (is (= "a\nb\n" (:text f)))
      (is (= "a\r\nc\r\n" (String. (text/encode-editable f "a\nc\n" "x") "UTF-8")))
      (is (= "q\nr" (text/normalize-input f "q\r\nr")) "模型抄来的 CRLF 归一")))
  (testing "UTF-8 BOM 保留"
    (let [f (text/decode-editable (byte-array (concat [(unchecked-byte 0xEF) (unchecked-byte 0xBB) (unchecked-byte 0xBF)]
                                                      (.getBytes "hi" "UTF-8"))) "x")
          out (text/encode-editable f "yo" "x")]
      (is (= "hi" (:text f)))
      (is (= [-17 -69 -65] (take 3 (seq out))))))
  (testing "UTF-16LE BOM"
    (let [bs (byte-array (concat [(unchecked-byte 0xFF) (unchecked-byte 0xFE)] (.getBytes "ab" "UTF-16LE")))
          f (text/decode-editable bs "x")]
      (is (= "ab" (:text f)))
      (is (= (seq bs) (seq (text/encode-editable f "ab" "x"))))))
  (testing "GBK（编回去逐字节相同才认）"
    (let [bs (.getBytes "中文注释" "GBK")
          f (text/decode-editable bs "x")]
      (is (= "GBK" (:label f)))
      (is (= "中文注释" (:text f)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"写不出来" (text/encode-editable f "😀" "x")))))
  (testing "二进制拒绝"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"二进制" (text/decode-editable (bytes-of 0x61 0 0x62) "x"))))
  (testing "认不出的编码拒绝"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"编码认不出" (text/decode-editable (bytes-of 0xFF 0x61 0xFE 0xFE 0xC0) "x")))))

(deftest unified-diff-format
  (is (= "@@ -2 +2 @@\n-b\n+B\n" (text/unified-diff "a\nb\nc\n" "a\nB\nc\n" 0)))
  (is (= "@@ -1,3 +1,3 @@\n a\n-b\n+B\n c\n" (text/unified-diff "a\nb\nc\n" "a\nB\nc\n" 1)))
  (is (= "" (text/unified-diff "same\n" "same\n")))
  (is (str/includes? (text/unified-diff "a" "b") "\\ No newline at end of file")))

(deftest syntax-checkers
  (testing "JSON"
    (is (= [] (syntax/problems {} "a.json" "{\"a\": [1, 2]}")))
    (let [[p] (syntax/problems {} "a.json" "{\"a\": [1, 2}")]
      (is (= 1 (:line p)))
      (is (str/starts-with? (:message p) "JSON 解析不了")))
    (is (= 1 (count (syntax/problems {} "a.json" "{} {}"))) "顶层多余内容")
    (is (= [] (syntax/problems {} "tsconfig.json" "{// c\n\"a\": 1,}")) "JSONC 允许注释与尾逗号")
    (is (= 1 (count (syntax/problems {} "a.json" "{\"a\": 1,}")))))
  (testing "EDN / Clojure"
    (is (= [] (syntax/problems {} "deps.edn" "{:a #inst \"2020-01-01\" :b #my/tag [1]}")))
    (let [[p] (syntax/problems {} "core.clj" "(ns a)\n\n(defn f [x]\n  (inc x)\n")]
      (is (= 3 (:line p)) "未闭合的报起始行"))
    (is (= [] (syntax/problems {} "core.cljc" "(ns a) #?(:clj 1 :cljs 2) ::k #\"re\" `(a ~b)")))
    (is (nil? (syntax/problems {} "core.clj" "(ns a (:require [x.y :as z])) ::z/k")) "别名关键字读取器判不了 ⇒ nil"))
  (testing "XML"
    (is (= [] (syntax/problems {} "a.xml" "<a><b/></a>")))
    (is (= 1 (count (syntax/problems {} "a.csproj" "<a><b></a>"))))
    (is (nil? (syntax/problems {} "a.xml" "<!DOCTYPE a [<!ENTITY x \"y\">]><a>&x;</a>")) "带 DTD ⇒ 看不了"))
  (testing "不认识的扩展名 ⇒ nil；宿主检查器优先；抛异常 ⇒ nil"
    (is (nil? (syntax/problems {} "a.py" "def (")))
    (let [c (syntax/for-extensions (fn [_ t] (if (str/includes? t "BAD") [{:line 1 :column 1 :message "bad"}] [])) ".json")]
      (is (= 1 (count (syntax/problems {:checkers [c]} "a.json" "BAD")))))
    (is (nil? (syntax/problems {:checkers [(syntax/checker (constantly true) (fn [_ _] (throw (RuntimeException. "boom"))))]}
                               "a.json" "{}")))))

(deftest verdicts
  (let [g {:enabled? true}]
    (is (:worse? (syntax/judge g "a.json" "{}" "{")) "0→1 拒")
    (is (not (:worse? (syntax/judge g "a.json" "{" "[")))  "1→1 放（修不完也让改）")
    (is (not (:worse? (syntax/judge g "a.json" "{" "{}"))) "1→0 放")
    (is (not (:worse? (syntax/judge g "a.json" nil "{"))) "新文件不判")
    (is (nil? (syntax/judge {:enabled? false} "a.json" "{}" "{")) "闸关着")
    (is (nil? (syntax/judge g "a.txt" "x" "y")) "看不了 ⇒ 不出判断")
    (is (str/starts-with? (syntax/preview-note (syntax/judge g "a.json" "{}" "{")) "⚠️ 批准之后"))
    (is (= "✓ 改完之后语法没问题" (syntax/preview-note (syntax/judge g "a.json" "{" "{}"))))))
