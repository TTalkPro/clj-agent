(ns im.ttalk.agent.plugin.file-test
  (:require [clojure.test :refer :all]
            [im.ttalk.agent.plugin.file :as file]
            [im.ttalk.agent.core.kernel.tool :as tool]
            [im.ttalk.agent.core.kernel.plugin :as kp]
            [clojure.java.io :as io])
  (:import [java.io File]))

(def ^:private test-dir (str (System/getProperty "java.io.tmpdir") "/clj-agent-plugin-test"))

(defn- setup-test-dir []
  (.mkdirs (io/file test-dir)))

(defn- cleanup-test-dir []
  (let [dir (io/file test-dir)]
    (when (.exists dir)
      (doseq [f (reverse (file-seq dir))]
        (.delete f)))))

(use-fixtures :each
  (fn [f]
    (setup-test-dir)
    (try (f) (finally (cleanup-test-dir)))))

(deftest write-and-read-file-test
  (testing "write then read file"
    (let [path (str test-dir "/test.txt")
          write-result (file/write-file {:path path :content "Hello World"})]
      (is (clojure.string/includes? write-result "已成功写入"))
      (let [read-result (file/read-file {:path path})]
        (is (= "Hello World" read-result))))))

(deftest append-file-test
  (testing "append to file"
    (let [path (str test-dir "/append.txt")]
      (file/write-file {:path path :content "Hello"})
      (file/append-file {:path path :content " World"})
      (is (= "Hello World" (file/read-file {:path path}))))))

(deftest list-directory-test
  (testing "list directory contents"
    (spit (str test-dir "/a.txt") "a")
    (spit (str test-dir "/b.txt") "b")
    (.mkdirs (io/file (str test-dir "/subdir")))
    (let [result (file/list-directory {:path test-dir})]
      (is (clojure.string/includes? result "a.txt"))
      (is (clojure.string/includes? result "b.txt"))
      (is (clojure.string/includes? result "[DIR]")))))

(deftest file-info-test
  (testing "get file info"
    (let [path (str test-dir "/info.txt")]
      (spit path "test content")
      (let [result (file/file-info {:path path})]
        (is (clojure.string/includes? result "文件"))
        (is (clojure.string/includes? result "bytes"))))))

(deftest file-exists-test
  (testing "existing file"
    (let [path (str test-dir "/exists.txt")]
      (spit path "")
      (is (clojure.string/includes? (file/file-exists {:path path}) "存在"))))

  (testing "non-existing file"
    (is (= "不存在" (file/file-exists {:path (str test-dir "/nope.txt")})))))

(deftest create-directory-test
  (testing "create new directory"
    (let [path (str test-dir "/newdir/subdir")]
      (let [result (file/create-directory {:path path})]
        (is (clojure.string/includes? result "已创建"))
        (is (.exists (io/file path)))))))

(deftest delete-file-test
  (testing "delete existing file"
    (let [path (str test-dir "/todelete.txt")]
      (spit path "delete me")
      (let [result (file/delete-file {:path path})]
        (is (clojure.string/includes? result "已删除"))
        (is (not (.exists (io/file path)))))))

  (testing "delete non-existing file"
    (let [result (file/delete-file {:path (str test-dir "/nope.txt")})]
      (is (clojure.string/includes? result "不存在")))))

(deftest copy-file-test
  (testing "copy file"
    (let [src (str test-dir "/src.txt")
          dst (str test-dir "/dst.txt")]
      (spit src "copy me")
      (let [result (file/copy-file {:source src :destination dst})]
        (is (clojure.string/includes? result "已复制"))
        (is (= "copy me" (slurp dst)))))))

(deftest move-file-test
  (testing "move file"
    (let [src (str test-dir "/move-src.txt")
          dst (str test-dir "/move-dst.txt")]
      (spit src "move me")
      (let [result (file/move-file {:source src :destination dst})]
        (is (clojure.string/includes? result "已移动"))
        (is (= "move me" (slurp dst)))
        (is (not (.exists (io/file src))))))))

(deftest file-tools-plugin-test
  (testing "plugin structure"
    (is (instance? im.ttalk.agent.core.kernel.plugin.KernelPlugin file/file-tools))
    (is (= 10 (kp/function-count file/file-tools))))

  (testing "sensitive tools are marked"
    (is (kp/has-sensitive? file/file-tools))
    (let [sensitive (set (kp/get-sensitive-functions file/file-tools))]
      (is (contains? sensitive :write-file))
      (is (contains? sensitive :delete-file))
      (is (contains? sensitive :move-file))
      (is (not (contains? sensitive :read-file)))))

  (testing "schemas are generated"
    (let [schemas (kp/get-schemas file/file-tools)]
      (is (= 10 (count schemas)))
      (is (every? #(contains? % :name) schemas)))))
