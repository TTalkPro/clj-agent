(ns im.ttalk.agent.plugin.file
  "文件操作工具集"
  (:require [im.ttalk.agent.core.kernel.tool :refer [deftool]]
            [im.ttalk.agent.plugin.helpers :as helpers]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.nio.file Files Paths StandardCopyOption]))

(deftool read-file
  "读取文件内容"
  [[path :string "文件的完整路径"]]
  (let [result (helpers/read-file-safe path)]
    (if (:success result)
      (:content result)
      (str "错误: " (:error result)))))

(deftool write-file
  "写入内容到文件（覆盖已有内容）"
  [[path :string "文件的完整路径"]
   [content :string "要写入的内容"]]
  {:sensitive true}
  (let [result (helpers/write-file-safe path content {:create-dirs? true})]
    (if (:success result)
      (str "已成功写入文件: " path " (" (:bytes-written result) " 字符)")
      (str "错误: " (:error result)))))

(deftool append-file
  "追加内容到文件末尾（文件不存在则创建）"
  [[path :string "文件的完整路径"]
   [content :string "要追加的内容"]]
  {:sensitive true}
  (try
    (spit path content :append true)
    (str "已成功追加到文件: " path)
    (catch Exception e
      (str "追加失败: " (.getMessage e)))))

(deftool list-directory
  "列出目录中的文件和子目录"
  [[path :string "目录路径"]
   [include-hidden :bool "是否包含隐藏文件" :default false]]
  (let [result (helpers/list-directory-safe path {:include-hidden? include-hidden})]
    (if (:success result)
      (->> (:entries result)
           (map #(str (if (= :dir (:type %)) "[DIR] " "      ")
                      (:name %)
                      (when (:size %) (str " (" (:size %) " bytes)"))))
           (str/join "\n"))
      (str "错误: " (:error result)))))

(deftool file-info
  "获取文件元信息（大小、修改时间等）"
  [[path :string "文件路径"]]
  (let [file (io/file path)]
    (if (.exists file)
      (str "路径: " (.getAbsolutePath file) "\n"
           "类型: " (if (.isDirectory file) "目录" "文件") "\n"
           "大小: " (.length file) " bytes\n"
           "可读: " (.canRead file) "\n"
           "可写: " (.canWrite file) "\n"
           "修改时间: " (java.time.Instant/ofEpochMilli (.lastModified file)))
      (str "文件不存在: " path))))

(deftool file-exists
  "检查文件或目录是否存在"
  [[path :string "文件或目录路径"]]
  (let [file (io/file path)]
    (if (.exists file)
      (str "存在 (" (if (.isDirectory file) "目录" "文件") ")")
      "不存在")))

(deftool create-directory
  "创建目录（包括父目录）"
  [[path :string "目录路径"]]
  {:sensitive true}
  (try
    (let [dir (io/file path)]
      (if (.mkdirs dir)
        (str "已创建目录: " path)
        (if (.exists dir)
          (str "目录已存在: " path)
          (str "创建目录失败: " path))))
    (catch Exception e
      (str "创建目录失败: " (.getMessage e)))))

(deftool delete-file
  "删除文件"
  [[path :string "文件路径"]]
  {:sensitive true}
  (try
    (let [file (io/file path)]
      (if (.exists file)
        (if (.delete file)
          (str "已删除: " path)
          (str "删除失败: " path))
        (str "文件不存在: " path)))
    (catch Exception e
      (str "删除失败: " (.getMessage e)))))

(deftool copy-file
  "复制文件"
  [[source :string "源文件路径"]
   [destination :string "目标文件路径"]]
  {:sensitive true}
  (try
    (let [src (Paths/get source (into-array String []))
          dst (Paths/get destination (into-array String []))]
      (Files/copy src dst (into-array [StandardCopyOption/REPLACE_EXISTING]))
      (str "已复制: " source " -> " destination))
    (catch Exception e
      (str "复制失败: " (.getMessage e)))))

(deftool move-file
  "移动/重命名文件"
  [[source :string "源文件路径"]
   [destination :string "目标文件路径"]]
  {:sensitive true}
  (try
    (let [src (Paths/get source (into-array String []))
          dst (Paths/get destination (into-array String []))]
      (Files/move src dst (into-array [StandardCopyOption/REPLACE_EXISTING]))
      (str "已移动: " source " -> " destination))
    (catch Exception e
      (str "移动失败: " (.getMessage e)))))

(def all-tools
  "文件操作工具集（tool vars 列表）"
  [#'read-file #'write-file #'append-file #'list-directory #'file-info
   #'file-exists #'create-directory #'delete-file #'copy-file #'move-file])
