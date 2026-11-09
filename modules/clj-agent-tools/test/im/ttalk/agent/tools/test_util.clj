(ns im.ttalk.agent.tools.test-util
  "测试小件：临时目录、按名字调工具（经 tool/invoke，与 chat-client 同一条执行路径）。"
  (:require [clojure.java.io :as io]
            [im.ttalk.agent.tool :as tool]
            [im.ttalk.agent.tools :as tools])
  (:import [java.nio.file Files LinkOption Path]
           [java.nio.file.attribute FileAttribute]))

(defn tmp-dir
  "新的临时目录（真实路径，避免 /tmp 本身是链接时干扰判定）。"
  ^Path []
  (.toRealPath (Files/createTempDirectory "clj-agent-tools" (make-array FileAttribute 0))
               (make-array LinkOption 0)))

(defn p ^Path [^Path root & segs] (reduce (fn [^Path a ^String s] (.resolve a s)) root segs))

(defn spit! [^Path f content]
  (Files/createDirectories (.getParent f) (make-array FileAttribute 0))
  (if (bytes? content)
    (Files/write f ^bytes content ^"[Ljava.nio.file.OpenOption;" (make-array java.nio.file.OpenOption 0))
    (spit (str f) content))
  f)

(defn slurp* [^Path f] (slurp (str f)))

(defn symlink! [^Path link ^Path target]
  (Files/createSymbolicLink link target (make-array FileAttribute 0)))

(defn find-tool [ts n]
  (or (first (filter #(= n (:name (:tool/schema (meta %)))) ts))
      (throw (ex-info (str "没有工具 " n) {:names (map #(:name (:tool/schema (meta %))) ts)}))))

(defn call
  "调工具 ⇒ {:success bool :result str :error str}。"
  [ts n args]
  (tool/invoke (find-tool ts n) args))

(defn ok [ts n args]
  (let [r (call ts n args)]
    (when-not (:success r) (throw (ex-info (str n " 失败：" (:error r)) r)))
    (:result r)))

(defn err [ts n args]
  (let [r (call ts n args)]
    (when (:success r) (throw (ex-info (str n " 本应失败却成功：" (:result r)) r)))
    (:error r)))

(defn ws-tools
  ([root] (ws-tools root nil))
  ([root opts] (tools/workspace-tools (merge {:root (str root)} opts) {:groups (or (:groups opts) #{:files :edit :delete})})))

(defn delete-tree! [^Path root]
  (doseq [f (reverse (file-seq (io/file (str root))))]
    (.delete ^java.io.File f)))
