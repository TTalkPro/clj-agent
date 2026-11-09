(ns im.ttalk.agent.tools.files-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.tools.test-util :as u :refer [ok err]]
            [im.ttalk.agent.tools.text :as text])
  (:import [java.nio.file Files LinkOption]
           [java.nio.file.attribute FileTime]))

(defn- body-lines [s] (rest (str/split-lines s)))   ;; 去掉 [路径#TAG] 头

(deftest read-file-slice
  (let [root (u/tmp-dir)
        ts (u/ws-tools root)]
    (u/spit! (u/p root "a.txt") "one\ntwo\r\nthree\n")
    (testing "行号前缀 + 头"
      (let [r (ok ts "read_file" {:file_path "a.txt"})]
        (is (= (str "[a.txt#" (text/content-tag "one\ntwo\r\nthree\n") "]") (first (str/split-lines r))))
        (is (= ["L1: one" "L2: two" "L3: three"] (body-lines r)) "\\r 去掉")))
    (testing "分页"
      (is (= ["L2: two"] (body-lines (ok ts "read_file" {:file_path "a.txt" :offset 2 :limit 1})))))
    (testing "offset 越界 ⇒ 失败但带头（也算读过）"
      (is (re-find #"(?s)^\[a.txt#.*offset 超出" (err ts "read_file" {:file_path "a.txt" :offset 9}))))
    (testing "参数校验"
      (is (re-find #"offset" (err ts "read_file" {:file_path "a.txt" :offset 0})))
      (is (re-find #"limit" (err ts "read_file" {:file_path "a.txt" :limit 0})))
      (is (re-find #"不存在" (err ts "read_file" {:file_path "nope.txt"})))
      (is (re-find #"目录" (err ts "read_file" {:file_path "."}))))
    (testing "单行超 500 字节在字符边界截"
      (u/spit! (u/p root "long.txt") (apply str (repeat 300 "中")))
      (let [[l] (body-lines (ok ts "read_file" {:file_path "long.txt"}))]
        (is (= (str "L1: " (apply str (repeat 166 "中"))) l))))
    (testing "大文件只交窗口、tag 仍按整篇"
      (let [big (str/join "\n" (map #(str "line " %) (range 1 50001)))]
        (u/spit! (u/p root "big.txt") big)
        (let [r (ok ts "read_file" {:file_path "big.txt" :offset 49999 :limit 5})]
          (is (= ["L49999: line 49999" "L50000: line 50000"] (body-lines r)))
          (is (str/includes? (first (str/split-lines r)) (text/content-tag big))))))
    (testing "根外拒绝"
      (is (re-find #"工作区之外" (err ts "read_file" {:file_path "/etc/hostname"}))))
    (u/delete-tree! root)))

(deftest read-file-indentation
  (let [root (u/tmp-dir)
        ts (u/ws-tools root)
        src (str "class A:\n"
                 "    # doc\n"
                 "    def f(self):\n"
                 "        x = 1\n"
                 "        return x\n"
                 "\n"
                 "    def g(self):\n"
                 "        pass\n")]
    (u/spit! (u/p root "a.py") src)
    ;; Codex 口径：往下遇到的第一个同级（min-indent）行照收，第二个才撤掉；往上同理（注释行在 include_header 时放行）
    (is (= ["L3:     def f(self):" "L4:         x = 1" "L5:         return x" "L6: " "L7:     def g(self):" "L8:         pass"]
           (body-lines (ok ts "read_file" {:file_path "a.py" :mode "indentation" :anchor_line 4 :max_levels 1
                                           :include_header false}))))
    (is (= "L2:     # doc"
           (first (body-lines (ok ts "read_file" {:file_path "a.py" :mode "indentation" :anchor_line 4 :max_levels 1})))))
    (is (= ["L4:         x = 1"]
           (body-lines (ok ts "read_file" {:file_path "a.py" :mode "indentation" :anchor_line 4 :max_lines 1}))))
    (is (re-find #"anchor_line 超出" (err ts "read_file" {:file_path "a.py" :mode "indentation" :anchor_line 99})))
    (u/delete-tree! root)))

(deftest list-dir
  (let [root (u/tmp-dir)
        ts (u/ws-tools root)]
    (u/spit! (u/p root "b.txt") "")
    (u/spit! (u/p root "a" "x.txt") "")
    (u/spit! (u/p root "a" "deep" "y.txt") "")
    (u/symlink! (u/p root "ln") (u/p root "b.txt"))
    (let [r (ok ts "list_dir" {:dir_path ""})]
      (is (= [(str "绝对路径：" root) "a/" "  deep/" "  x.txt" "b.txt" "ln@"] (str/split-lines r))))
    (testing "depth 3 进到更深；分页尾注"
      (is (some #{"    y.txt"} (str/split-lines (ok ts "list_dir" {:dir_path "" :depth 3}))))
      (let [r (str/split-lines (ok ts "list_dir" {:dir_path "" :limit 2}))]
        (is (= "超过 2 条" (last r)))))
    (is (re-find #"offset 超出" (err ts "list_dir" {:dir_path "" :offset 99})))
    (is (re-find #"不是目录" (err ts "list_dir" {:dir_path "b.txt"})))
    (u/delete-tree! root)))

(defn- touch! [f ms] (Files/setLastModifiedTime f (FileTime/fromMillis ms)))

(deftest glob-and-ignore
  (let [root (u/tmp-dir)
        ts (u/ws-tools root)]
    (touch! (u/spit! (u/p root "src" "a.clj") "") 1000)
    (touch! (u/spit! (u/p root "src" "sub" "b.clj") "") 3000)
    (touch! (u/spit! (u/p root "top.clj") "") 2000)
    (u/spit! (u/p root "build" "gen.clj") "")
    (u/spit! (u/p root "keep.log") "")
    (u/spit! (u/p root ".gitignore") "build/\n*.log\n!keep.log\n")
    (u/spit! (u/p root ".git" "x.clj") "")
    (testing "** 也匹配零级目录；新改的在前；忽略规则与 .git 生效"
      (is (= (map #(str (u/p root %)) ["src/sub/b.clj" "top.clj" "src/a.clj"])
             (str/split-lines (ok ts "glob" {:pattern "**/*.clj"})))))
    (testing "{a,b} 与 path"
      (is (= [(str (u/p root "src" "a.clj"))] (str/split-lines (ok ts "glob" {:pattern "{a,z}.clj" :path "src"})))))
    (testing "! 取反"
      (is (= [(str (u/p root "keep.log"))] (str/split-lines (ok ts "glob" {:pattern "*.log"})))))
    (is (= "没有找到文件。" (ok ts "glob" {:pattern "*.rs"})))
    (is (re-find #"pattern 不能为空" (err ts "glob" {:pattern " "})))
    (u/delete-tree! root)))

(deftest grep-both-engines
  (doseq [rg [false :auto]]
    (let [root (u/tmp-dir)
          ts (u/ws-tools root {:grep {:rg rg}})]
      (touch! (u/spit! (u/p root "a.clj") "(defn foo [])\n") 1000)
      (touch! (u/spit! (u/p root "b.clj") "(defn foo-bar [])\n") 2000)
      (u/spit! (u/p root "c.txt") "foo\n")
      (u/spit! (u/p root ".hidden.clj") "foo\n")
      (u/spit! (u/p root "bin.dat") (byte-array (map unchecked-byte [0x66 0x6f 0x6f 0 1 2])))
      (u/spit! (u/p root "ignored" "x.clj") "foo\n")
      (u/spit! (u/p root ".ignore") "ignored/\n")
      (testing (str "rg=" rg)
        (is (= (map #(str (u/p root %)) ["b.clj" "a.clj"])
               (str/split-lines (ok ts "grep" {:pattern "defn foo" :include "*.clj"}))))
        (is (= #{(str (u/p root "c.txt")) (str (u/p root "a.clj")) (str (u/p root "b.clj"))}
               (set (str/split-lines (ok ts "grep" {:pattern "foo"}))))
            "隐藏 / 二进制 / 被忽略的不进")
        (is (= [(str (u/p root "b.clj"))] (str/split-lines (ok ts "grep" {:pattern "foo-bar" :path "b.clj"}))))
        (is (= "没有匹配。" (ok ts "grep" {:pattern "zzz"})))
        (is (re-find #"正则写错了" (err ts "grep" {:pattern "(unclosed"}))))
      (u/delete-tree! root))))

(deftest write-file
  (let [root (u/tmp-dir)
        ts (u/ws-tools root)]
    (testing "新建：不要求读过，中间目录自动建"
      (is (re-find #"^已新建 d/new.txt（2 个字符）。 新的 TAG 是 " (ok ts "write_file" {:path "d/new.txt" :content "hi"})))
      (is (= "hi" (u/slurp* (u/p root "d" "new.txt")))))
    (testing "写后已登记：可以直接覆盖"
      (is (re-find #"^已覆盖" (ok ts "write_file" {:path "d/new.txt" :content "again"}))))
    (testing "覆盖没读过的文件 ⇒ 拒"
      (u/spit! (u/p root "old.txt") "x\r\ny\r\n")
      (is (re-find #"还没有读过" (err ts "write_file" {:path "old.txt" :content "z\n"}))))
    (testing "读过 ⇒ 可覆盖，CRLF 保留"
      (ok ts "read_file" {:file_path "old.txt"})
      (ok ts "write_file" {:path "old.txt" :content "z\nw\n"})
      (is (= "z\r\nw\r\n" (u/slurp* (u/p root "old.txt")))))
    (testing "读后被外部改过 ⇒ 拒"
      (ok ts "read_file" {:file_path "old.txt"})
      (u/spit! (u/p root "old.txt") "changed")
      (is (re-find #"读完之后变了" (err ts "write_file" {:path "old.txt" :content "q"}))))
    (testing "目录 / 受保护 / 根外"
      (is (re-find #"是一个目录" (err ts "write_file" {:path "d" :content "q"})))
      (is (re-find #"受保护" (err ts "write_file" {:path ".git/config" :content "q"})))
      (is (re-find #"工作区之外" (err ts "write_file" {:path "../x" :content "q"}))))
    (testing "语法闸：把合法 JSON 改坏 ⇒ 拒，文件不动"
      (ok ts "write_file" {:path "c.json" :content "{\"a\": 1}"})
      (is (re-find #"解析不了了" (err ts "write_file" {:path "c.json" :content "{\"a\": "})))
      (is (= "{\"a\": 1}" (u/slurp* (u/p root "c.json")))))
    (testing "关掉新鲜度闸"
      (let [ts2 (u/ws-tools root {:read-tracking? false})]
        (is (re-find #"^已覆盖 old.txt（1 个字符）。$" (ok ts2 "write_file" {:path "old.txt" :content "q"})))))
    (u/delete-tree! root)))

(deftest delete-path
  (let [root (u/tmp-dir)
        outside (u/tmp-dir)
        ts (u/ws-tools root)]
    (u/spit! (u/p root "f.txt") "x")
    (u/spit! (u/p root "dir" "a.txt") "x")
    (Files/createDirectories (u/p root "empty") (make-array java.nio.file.attribute.FileAttribute 0))
    (u/spit! (u/p outside "keep.txt") "k")
    (u/symlink! (u/p root "out") outside)
    (is (re-find #"^已删除 " (ok ts "delete_path" {:path "f.txt"})))
    (is (not (Files/exists (u/p root "f.txt") (make-array LinkOption 0))))
    (is (re-find #"本来就不存在" (ok ts "delete_path" {:path "f.txt"})) "幂等")
    (is (re-find #"非空目录" (err ts "delete_path" {:path "dir"})))
    (is (re-find #"连同里面的全部内容" (ok ts "delete_path" {:path "dir" :recursive true})))
    (is (re-find #"已删除目录" (ok ts "delete_path" {:path "empty"})))
    (testing "指向根外的链接：只删链接本身，目标不动"
      (is (re-find #"只删了符号链接本身" (ok ts "delete_path" {:path "out"})))
      (is (= "k" (u/slurp* (u/p outside "keep.txt")))))
    (testing "根与其祖先拒删、根外拒删"
      (is (re-find #"工作区根目录" (err ts "delete_path" {:path "."})))
      (is (re-find #"工作区之外" (err ts "delete_path" {:path (str (u/p outside "keep.txt"))}))))
    (u/delete-tree! root)
    (u/delete-tree! outside)))
