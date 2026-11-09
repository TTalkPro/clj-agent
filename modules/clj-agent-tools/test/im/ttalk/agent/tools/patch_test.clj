(ns im.ttalk.agent.tools.patch-test
  "期望值参照 Codex apply-patch 的 parser / lib 测试（经 Keelson ApplyPatchToolTests 转写）。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.tools.patch :as patch]
            [im.ttalk.agent.tools.test-util :as u :refer [ok err]])
  (:import [java.nio.file Files LinkOption]))

(defn- patch-of [& ls] (str/join "\n" (concat ["*** Begin Patch"] ls ["*** End Patch"])))

(deftest parser
  (testing "三种块 + Move to + @@ 上下文 + EOF"
    (let [{:keys [hunks]} (patch/parse-patch
                           (patch-of "*** Add File: a.txt" "+hello" "+world"
                                     "*** Delete File: b.txt"
                                     "*** Update File: c.txt" "*** Move to: d.txt" "@@ def f():" " ctx" "-old" "+new"
                                     "*** End of File"))]
      (is (= [{:type :add :path "a.txt" :contents "hello\nworld\n"}
              {:type :delete :path "b.txt"}
              {:type :update :path "c.txt" :move-path "d.txt"
               :chunks [{:change-context "def f():" :old ["ctx" "old"] :new ["ctx" "new"] :ctx-idx [[0 0]] :eof? true}]}]
             hunks))))
  (testing "边界"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"第一行必须是" (patch/parse-patch "bad\n*** End Patch")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"最后一行必须是" (patch/parse-patch "*** Begin Patch\n*** Add File: a\n+x"))))
  (testing "宽松模式剥 heredoc"
    (is (= 1 (count (:hunks (patch/parse-patch (str "<<'EOF'\n" (patch-of "*** Add File: a" "+x") "\nEOF")))))))
  (testing "块头写错 / Update 空块"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"不是合法的块头" (patch/parse-patch (patch-of "*** Frobnicate: a"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"是空的" (patch/parse-patch (patch-of "*** Update File: a"))))))

(deftest derive-contents
  (testing "保行尾：CRLF 文件里插入的行也用 CRLF"
    (is (= "a\r\nB\r\nc\r\n"
           (patch/derive-new-contents "a\r\nb\r\nc\r\n" "f" [{:old ["a" "b"] :new ["a" "B"] :ctx-idx [[0 0]]}]))))
  (testing "@@ 定位 + 行尾空白宽容"
    (is (= "x\ny  \nZ\n"
           (patch/derive-new-contents "x\ny  \nz\n" "f" [{:change-context "x" :old ["y" "z"] :new ["y" "Z"] :ctx-idx [[0 0]]}]))))
  (testing "Unicode 标点归 ASCII 后匹配"
    (is (= "a - b\n" (patch/derive-new-contents "a — b\n" "f" [{:old ["a - b"] :new ["a - b"] :ctx-idx []}]))))
  (testing "纯追加（old 为空）补到末尾，并补结尾换行"
    (is (= "a\nb\n" (patch/derive-new-contents "a" "f" [{:old [] :new ["b"] :ctx-idx []}]))))
  (testing "对不上 ⇒ 抛"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"找不到预期的行"
                          (patch/derive-new-contents "a\n" "f" [{:old ["zz"] :new ["y"] :ctx-idx []}])))))

(deftest apply-patch-tool
  (let [root (u/tmp-dir)
        ts (u/ws-tools root)]
    (u/spit! (u/p root "src" "app.py") "def greet():\n    print(\"Hi\")\n")
    (u/spit! (u/p root "obsolete.txt") "bye\n")
    (testing "改已有文件前要读过（整份补丁一个也不写）"
      (is (re-find #"apply_patch 验证失败：.*还没有读过"
                   (err ts "apply_patch" {:input (patch-of "*** Add File: hello.txt" "+Hello"
                                                           "*** Update File: src/app.py" "@@ def greet():" "-    print(\"Hi\")" "+    print(\"Hello\")")})))
      (is (not (Files/exists (u/p root "hello.txt") (make-array LinkOption 0)))))
    (ok ts "read_file" {:file_path "src/app.py"})
    (ok ts "read_file" {:file_path "obsolete.txt"})
    (testing "任一文件对不上 ⇒ 一个也不写"
      (is (re-find #"找不到预期的行"
                   (err ts "apply_patch" {:input (patch-of "*** Add File: hello.txt" "+Hello"
                                                           "*** Update File: src/app.py" "-nonexistent line" "+x")})))
      (is (not (Files/exists (u/p root "hello.txt") (make-array LinkOption 0))))
      (is (= "def greet():\n    print(\"Hi\")\n" (u/slurp* (u/p root "src" "app.py")))))
    (testing "Add + Update(Move) + Delete 一次成功，回 Codex 摘要"
      (is (= "成功。改动了这些文件：\nA hello.txt\nM src/main.py\nD obsolete.txt\n"
             (ok ts "apply_patch" {:input (patch-of "*** Add File: hello.txt" "+Hello world"
                                                    "*** Update File: src/app.py" "*** Move to: src/main.py"
                                                    "@@ def greet():" "-    print(\"Hi\")" "+    print(\"Hello, world!\")"
                                                    "*** Delete File: obsolete.txt")})))
      (is (= "Hello world\n" (u/slurp* (u/p root "hello.txt"))))
      (is (= "def greet():\n    print(\"Hello, world!\")\n" (u/slurp* (u/p root "src" "main.py"))))
      (is (not (Files/exists (u/p root "src" "app.py") (make-array LinkOption 0))))
      (is (not (Files/exists (u/p root "obsolete.txt") (make-array LinkOption 0)))))
    (testing "写成功即登记：接着改不用再读"
      (is (re-find #"^成功" (ok ts "apply_patch" {:input (patch-of "*** Update File: hello.txt" "-Hello world" "+Hi")}))))
    (testing "同一路径多处操作 ⇒ 拒；根外 ⇒ 拒；删不存在的 ⇒ 拒"
      (is (re-find #"多个操作指向" (err ts "apply_patch" {:input (patch-of "*** Add File: x" "+1" "*** Delete File: x")})))
      (is (re-find #"工作区之外" (err ts "apply_patch" {:input (patch-of "*** Add File: ../evil" "+1")})))
      (is (re-find #"删除文件 .* 失败" (err ts "apply_patch" {:input (patch-of "*** Delete File: ghost.txt")}))))
    (testing "语法闸：任一文件改坏 ⇒ 整份不写"
      (ok ts "write_file" {:path "c.json" :content "{\"a\": 1}\n"})
      (is (re-find #"整份补丁一个文件也没写"
                   (err ts "apply_patch" {:input (patch-of "*** Add File: ok.txt" "+fine"
                                                           "*** Update File: c.json" "-{\"a\": 1}" "+{\"a\": ")})))
      (is (not (Files/exists (u/p root "ok.txt") (make-array LinkOption 0)))))
    (testing "解析错误带前缀"
      (is (re-find #"^apply_patch 验证失败：无效补丁" (err ts "apply_patch" {:input "nope"}))))
    (u/delete-tree! root)))
