(ns im.ttalk.agent.tools.workspace-test
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.tools.test-util :as u]
            [im.ttalk.agent.tools.workspace :as ws])
  (:import [java.nio.file Files LinkOption]
           [java.nio.file.attribute PosixFilePermissions]))

(defn- code [w path access]
  (:code (ws/deny w (.normalize (ws/->path path)) access)))

(deftest policy-roots-and-dotdot
  (let [root (u/tmp-dir)
        w (ws/workspace {:root (str root)})]
    (testing "根内可读可写"
      (is (nil? (code w (u/p root "a" "b.txt") :write)))
      (is (nil? (code w root :read))))
    (testing "根外与 .. 逃逸"
      (is (= "workspace/outside-roots" (code w (.getParent root) :read)))
      (is (= "workspace/outside-roots" (code w (str root "/../x") :read)))
      (is (= "workspace/outside-roots" (code w "/etc/passwd" :read))))
    (testing "前缀相同的兄弟目录不算在根内"
      (is (= "workspace/outside-roots" (code w (str root "-sibling/x") :read))))
    (testing "受保护名：顶层 .git 只读"
      (is (nil? (code w (u/p root ".git" "config") :read)))
      (is (= "workspace/protected" (code w (u/p root ".git" "config") :write)))
      (is (nil? (code w (u/p root "sub" ".git" "config") :write)) "只管根下第一段"))
    (u/delete-tree! root)))

(deftest policy-read-only-roots
  (let [root (u/tmp-dir)
        w (ws/workspace {:root (str root) :read-only-roots [(str (u/p root "vendor"))]})]
    (is (nil? (code w (u/p root "vendor" "x.clj") :read)))
    (is (= "workspace/read-only" (code w (u/p root "vendor" "x.clj") :write)))
    (is (nil? (code w (u/p root "src" "x.clj") :write)))
    (u/delete-tree! root)))

(deftest policy-symlinks
  (let [root (u/tmp-dir)
        outside (u/tmp-dir)
        w (ws/workspace {:root (str root)})]
    (u/spit! (u/p outside "secret.txt") "s")
    (u/symlink! (u/p root "out") outside)
    (u/symlink! (u/p root "dangling") (u/p outside "new.txt"))
    (u/spit! (u/p root "real.txt") "r")
    (u/symlink! (u/p root "inside") (u/p root "real.txt"))
    (u/symlink! (u/p root "git-link") (u/p root ".git"))
    (u/symlink! (u/p root "loop-a") (u/p root "loop-b"))
    (u/symlink! (u/p root "loop-b") (u/p root "loop-a"))
    (testing "经链接逃到根外"
      (is (= "workspace/symlink-escape" (code w (u/p root "out" "secret.txt") :read)))
      (is (= "workspace/symlink-escape" (code w (u/p root "out" "fresh.txt") :write)) "不存在的尾段也按链接目标判"))
    (testing "悬空链接指向根外：写它等于在根外建文件"
      (is (= "workspace/symlink-escape" (code w (u/p root "dangling") :write))))
    (testing "根内链接放行"
      (is (nil? (code w (u/p root "inside") :write))))
    (testing "经链接写进受保护目录"
      (is (= "workspace/protected" (code w (u/p root "git-link" "config") :write))))
    (testing "链接成环"
      (is (= "workspace/link-loop" (code w (u/p root "loop-a") :read))))
    (u/delete-tree! root)
    (u/delete-tree! outside)))

(deftest resolve-paths
  (let [root (u/tmp-dir)
        w (ws/workspace {:root (str root)})]
    (is (= (u/p root "a" "b") (ws/resolve! w "a/./b" "path" :read)))
    (is (= root (ws/resolve! w "" "path" :read true)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"工作区之外" (ws/resolve! w "../x" "path" :read)))
    (testing "没有工作目录 ⇒ 只收绝对路径"
      (let [w2 (ws/workspace {:root (str root) :cwd nil})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"绝对路径" (ws/resolve! w2 "a" "path" :read)))
        (is (= (u/p root "a") (ws/resolve! w2 (str (u/p root "a")) "path" :read)))))
    (testing "缺 :root 也缺 :policy ⇒ 装配期抛"
      (is (thrown? clojure.lang.ExceptionInfo (ws/workspace {}))))
    (testing "显式不限"
      (let [w3 (ws/workspace {:policy (ws/unrestricted-policy) :cwd (str root)})]
        (is (= "/etc/hosts" (str (ws/resolve! w3 "/etc/hosts" "path" :read))))))
    (u/delete-tree! root)))

(deftest atomic-write-keeps-links-and-perms
  (let [root (u/tmp-dir)
        target (u/spit! (u/p root "t.sh") "old")
        link (u/p root "l.sh")]
    (Files/setPosixFilePermissions target (PosixFilePermissions/fromString "rwxr-x---"))
    (u/symlink! link target)
    (ws/atomic-write! link (.getBytes "new" "UTF-8"))
    (is (Files/isSymbolicLink link) "链接本身保留")
    (is (= "new" (u/slurp* target)) "写进链接指向的文件")
    (is (= "rwxr-x---" (PosixFilePermissions/toString (Files/getPosixFilePermissions target (make-array LinkOption 0)))))
    (ws/atomic-write! (u/p root "deep" "er" "f.txt") (.getBytes "x" "UTF-8"))
    (is (= "x" (u/slurp* (u/p root "deep" "er" "f.txt"))) "父目录自动建")
    (is (empty? (filter #(.endsWith (str %) ".tmp") (file-seq (java.io.File. (str root))))) "不留临时文件")
    (u/delete-tree! root)))

(deftest unbound-tool-call-is-explicit
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"未绑定工作区" (ws/current))))
