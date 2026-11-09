(ns im.ttalk.agent.tools.edit-test
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.tools.edit :as edit]
            [im.ttalk.agent.tools.test-util :as u :refer [ok err]]))

(deftest edit-file-basics
  (let [root (u/tmp-dir)
        ts (u/ws-tools root)
        f (u/p root "a.clj")]
    (u/spit! f "(defn a []\n  1)\n\n(defn b []\n  1)\n")
    (testing "没读过 ⇒ 拒（新鲜度在匹配之前）"
      (is (re-find #"还没有读过" (err ts "edit_file" {:path "a.clj" :old "(defn a" :new "(defn aa"}))))
    (ok ts "read_file" {:file_path "a.clj"})
    (testing "唯一匹配 ⇒ 改；回新 tag，可以接着改"
      (is (re-find #"^已改 a.clj：替换 1 处，首处在第 1 行。 新的 TAG 是 [0-9A-F]{8}。$"
                   (ok ts "edit_file" {:path "a.clj" :old "(defn a []" :new "(defn a [x]"})))
      (is (re-find #"首处在第 4 行" (ok ts "edit_file" {:path "a.clj" :old "(defn b" :new "(defn bb"}))))
    (testing "不唯一 ⇒ 拒并列出行号；all=true 全换"
      (is (re-find #"出现了 2 次（第 2, 5 行）" (err ts "edit_file" {:path "a.clj" :old "  1)" :new "  2)"})))
      (is (re-find #"替换 2 处" (ok ts "edit_file" {:path "a.clj" :old "  1)" :new "  2)" :all true})))
      (is (= "(defn a [x]\n  2)\n\n(defn bb []\n  2)\n" (u/slurp* f))))
    (testing "0 匹配的三种诊断"
      (is (re-find #"行号前缀" (err ts "edit_file" {:path "a.clj" :old "L1: (defn a [x]\nL2:   2)" :new "x"})))
      (is (re-find #"第 1 行有一处只在空白上不同" (err ts "edit_file" {:path "a.clj" :old "(defn  a [x]\n 2)" :new "x"})))
      (is (re-find #"先用 read_file 看一眼" (err ts "edit_file" {:path "a.clj" :old "nothing like it" :new "x"}))))
    (testing "参数错误"
      (is (re-find #"old 不能为空" (err ts "edit_file" {:path "a.clj" :old "" :new "x"})))
      (is (re-find #"没有任何效果" (err ts "edit_file" {:path "a.clj" :old "a" :new "a"})))
      (is (re-find #"不存在。新建文件用 write_file" (err ts "edit_file" {:path "zz.clj" :old "a" :new "b"}))))
    (testing "读后被外部改 ⇒ 拒"
      (u/spit! f "(defn a [x]\n  3)\n")
      (is (re-find #"读完之后变了" (err ts "edit_file" {:path "a.clj" :old "3" :new "4"}))))
    (testing "语法闸：把配平的 clj 改得不配平 ⇒ 拒"
      (ok ts "read_file" {:file_path "a.clj"})
      (is (re-find #"解析不了了" (err ts "edit_file" {:path "a.clj" :old "  3)" :new "  3"})))
      (is (= "(defn a [x]\n  3)\n" (u/slurp* f))))
    (u/delete-tree! root)))

(deftest edit-preserves-crlf-and-bom
  (let [root (u/tmp-dir)
        ts (u/ws-tools root)
        f (u/p root "w.txt")]
    (u/spit! f (byte-array (concat (map unchecked-byte [0xEF 0xBB 0xBF]) (.getBytes "one\r\ntwo\r\n" "UTF-8"))))
    (ok ts "read_file" {:file_path "w.txt"})
    (ok ts "edit_file" {:path "w.txt" :old "one\r\ntwo" :new "uno\ndos"})
    (is (= [-17 -69 -65] (take 3 (seq (java.nio.file.Files/readAllBytes f)))))
    (is (= "﻿uno\r\ndos\r\n" (u/slurp* f)))
    (u/delete-tree! root)))

(deftest helpers
  (is (= [0 3] (edit/occurrences "abcabc" "abc")))
  (is (= [0] (edit/occurrences "aaa" "aa")) "不重叠")
  (is (edit/line-numbered? "L1: a\n  L22: b\n"))
  (is (not (edit/line-numbered? "L1: a\nb"))))
