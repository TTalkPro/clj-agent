(ns im.ttalk.agent.tools.builtin
  "内置工具定义

   提供预定义的常用工具：
   - 计算器
   - 时间
   - 文件操作（读、写、列表、复制、移动、删除）
   - HTTP 请求（GET、POST、PUT、DELETE）
   - Shell 命令

   使用示例：

   (require '[im.ttalk.agent.tools.api :as tools])

   ;; 方式 1: 注册到 Registry
   (def registry
     (-> (tools/create-tool-registry)
         (tools/register-builtin-tools)))

   ;; 方式 2: 获取工具定义
   (tools/builtin-tools)
   (tools/get-builtin-tool :calculator)"
  (:require [im.ttalk.agent.tools.tool-registry :as tool-registry]
            [im.ttalk.agent.tools.builtin-helpers :as helpers]
            [im.ttalk.agent.core.http.client :as http]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.nio.file Files Paths StandardCopyOption]))

;;; ============================================================
;;; 计算工具
;;; ============================================================

(def ^:private calculator-tool
  {:name :calculator
   :description "执行数学计算。支持基本运算：+, -, *, /, 以及括号。"
   :parameters {:type "object"
                :properties {:expression {:type "string"
                                          :description "数学表达式，如 \"2 + 3 * 4\" 或 \"(10 + 5) / 3\""}}
                :required ["expression"]}
   :category :math
   :permissions #{:compute}
   :handler (fn [{:keys [expression]}]
              (try
                (str (eval (read-string expression)))
                (catch Exception e
                  (str "计算错误: " (.getMessage e)))))})

;;; ============================================================
;;; 时间工具
;;; ============================================================

(def ^:private current-time-tool
  {:name :current_time
   :description "获取当前日期和时间"
   :parameters {:type "object"
                :properties {}
                :required []}
   :category :utility
   :permissions #{}
   :handler (fn [_]
              (str (java.time.LocalDateTime/now)))})

;;; ============================================================
;;; 文件操作工具
;;; ============================================================

(def ^:private read-file-tool
  {:name :read_file
   :description "读取文件内容。只能读取文本文件。"
   :parameters {:type "object"
                :properties {:path {:type "string"
                                    :description "文件的完整路径"}}
                :required ["path"]}
   :category :file
   :permissions #{:file-read}
   :handler (fn [{:keys [path]}]
              (let [result (helpers/read-file-safe path)]
                (if (:success result)
                  (:content result)
                  (:error result))))})

(def ^:private write-file-tool
  {:name :write_file
   :description "写入内容到文件。如果文件存在会被覆盖。"
   :parameters {:type "object"
                :properties {:path {:type "string"
                                    :description "文件的完整路径"}
                             :content {:type "string"
                                       :description "要写入的内容"}}
                :required ["path" "content"]}
   :category :file
   :permissions #{:file-write}
   :handler (fn [{:keys [path content]}]
              (let [result (helpers/write-file-safe path content)]
                (if (:success result)
                  (str "已成功写入文件: " path " (" (:bytes-written result) " 字符)")
                  (:error result))))})

(def ^:private append-file-tool
  {:name :append_file
   :description "追加内容到文件末尾。如果文件不存在则创建。"
   :parameters {:type "object"
                :properties {:path {:type "string"
                                    :description "文件的完整路径"}
                             :content {:type "string"
                                       :description "要追加的内容"}}
                :required ["path" "content"]}
   :category :file
   :permissions #{:file-write}
   :handler (fn [{:keys [path content]}]
              (try
                (spit path content :append true)
                (str "已成功追加到文件: " path)
                (catch Exception e
                  (str "追加失败: " (.getMessage e)))))})

(def ^:private list-directory-tool
  {:name :list_directory
   :description "列出目录中的文件和子目录"
   :parameters {:type "object"
                :properties {:path {:type "string"
                                    :description "目录路径"}}
                :required ["path"]}
   :category :file
   :permissions #{:file-read}
   :handler (fn [{:keys [path]}]
              (let [result (helpers/list-directory-safe path)]
                (if (:success result)
                  (->> (:entries result)
                       (map #(str (if (= :dir (:type %)) "[DIR] " "      ")
                                  (:name %)
                                  (when (:size %) (str " (" (:size %) " bytes)"))))
                       (str/join "\n"))
                  (:error result))))})

(def ^:private file-exists-tool
  {:name :file_exists
   :description "检查文件或目录是否存在"
   :parameters {:type "object"
                :properties {:path {:type "string"
                                    :description "文件或目录路径"}}
                :required ["path"]}
   :category :file
   :permissions #{:file-read}
   :handler (fn [{:keys [path]}]
              (let [file (io/file path)]
                (if (.exists file)
                  (str "存在 (" (if (.isDirectory file) "目录" "文件") ")")
                  "不存在")))})

(def ^:private file-info-tool
  {:name :file_info
   :description "获取文件信息（大小、修改时间等）"
   :parameters {:type "object"
                :properties {:path {:type "string"
                                    :description "文件路径"}}
                :required ["path"]}
   :category :file
   :permissions #{:file-read}
   :handler (fn [{:keys [path]}]
              (let [file (io/file path)]
                (if (.exists file)
                  (str "路径: " (.getAbsolutePath file) "\n"
                       "类型: " (if (.isDirectory file) "目录" "文件") "\n"
                       "大小: " (.length file) " bytes\n"
                       "可读: " (.canRead file) "\n"
                       "可写: " (.canWrite file) "\n"
                       "修改时间: " (java.time.Instant/ofEpochMilli (.lastModified file)))
                  (str "文件不存在: " path))))})

(def ^:private copy-file-tool
  {:name :copy_file
   :description "复制文件"
   :parameters {:type "object"
                :properties {:source {:type "string"
                                      :description "源文件路径"}
                             :destination {:type "string"
                                           :description "目标文件路径"}}
                :required ["source" "destination"]}
   :category :file
   :permissions #{:file-read :file-write}
   :handler (fn [{:keys [source destination]}]
              (try
                (let [src (Paths/get source (into-array String []))
                      dst (Paths/get destination (into-array String []))]
                  (Files/copy src dst (into-array [StandardCopyOption/REPLACE_EXISTING]))
                  (str "已复制: " source " -> " destination))
                (catch Exception e
                  (str "复制失败: " (.getMessage e)))))})

(def ^:private move-file-tool
  {:name :move_file
   :description "移动/重命名文件"
   :parameters {:type "object"
                :properties {:source {:type "string"
                                      :description "源文件路径"}
                             :destination {:type "string"
                                           :description "目标文件路径"}}
                :required ["source" "destination"]}
   :category :file
   :permissions #{:file-read :file-write}
   :handler (fn [{:keys [source destination]}]
              (try
                (let [src (Paths/get source (into-array String []))
                      dst (Paths/get destination (into-array String []))]
                  (Files/move src dst (into-array [StandardCopyOption/REPLACE_EXISTING]))
                  (str "已移动: " source " -> " destination))
                (catch Exception e
                  (str "移动失败: " (.getMessage e)))))})

(def ^:private delete-file-tool
  {:name :delete_file
   :description "删除文件"
   :parameters {:type "object"
                :properties {:path {:type "string"
                                    :description "文件路径"}}
                :required ["path"]}
   :category :file
   :permissions #{:file-write}
   :handler (fn [{:keys [path]}]
              (try
                (let [file (io/file path)]
                  (if (.exists file)
                    (if (.delete file)
                      (str "已删除: " path)
                      (str "删除失败: " path))
                    (str "文件不存在: " path)))
                (catch Exception e
                  (str "删除失败: " (.getMessage e)))))})

(def ^:private create-directory-tool
  {:name :create_directory
   :description "创建目录（包括父目录）"
   :parameters {:type "object"
                :properties {:path {:type "string"
                                    :description "目录路径"}}
                :required ["path"]}
   :category :file
   :permissions #{:file-write}
   :handler (fn [{:keys [path]}]
              (try
                (let [dir (io/file path)]
                  (if (.mkdirs dir)
                    (str "已创建目录: " path)
                    (if (.exists dir)
                      (str "目录已存在: " path)
                      (str "创建目录失败: " path))))
                (catch Exception e
                  (str "创建目录失败: " (.getMessage e)))))})

;;; ============================================================
;;; 网络工具
;;; ============================================================

(def ^:private http-get-tool
  {:name :http_get
   :description "发送 HTTP GET 请求获取网页内容"
   :parameters {:type "object"
                :properties {:url {:type "string"
                                   :description "完整的 URL 地址"}
                             :timeout {:type "integer"
                                       :description "超时时间（毫秒），默认 10000"}}
                :required ["url"]}
   :category :http
   :permissions #{:network-access}
   :handler (fn [{:keys [url timeout]}]
              (try
                (let [response (http/get url :timeout (or timeout 10000))
                      truncated (helpers/truncate-http-response response 10000)]
                  (:body truncated))
                (catch Exception e
                  (str "请求失败: " (.getMessage e)))))})

(def ^:private http-post-tool
  {:name :http_post
   :description "发送 HTTP POST 请求"
   :parameters {:type "object"
                :properties {:url {:type "string"
                                   :description "完整的 URL 地址"}
                             :body {:type "string"
                                    :description "请求体内容"}
                             :content-type {:type "string"
                                            :description "内容类型，默认 application/json"}
                             :timeout {:type "integer"
                                       :description "超时时间（毫秒），默认 10000"}}
                :required ["url"]}
   :category :http
   :permissions #{:network-access}
   :handler (fn [{:keys [url body content-type timeout]}]
              (try
                (let [response (http/post url
                                          :body body
                                          :content-type (or content-type "application/json")
                                          :timeout (or timeout 10000))
                      truncated (helpers/truncate-http-response response 10000)]
                  (str "状态: " (:status truncated) "\n"
                       "响应: " (:body truncated)))
                (catch Exception e
                  (str "请求失败: " (.getMessage e)))))})

(def ^:private http-put-tool
  {:name :http_put
   :description "发送 HTTP PUT 请求"
   :parameters {:type "object"
                :properties {:url {:type "string"
                                   :description "完整的 URL 地址"}
                             :body {:type "string"
                                    :description "请求体内容"}
                             :content-type {:type "string"
                                            :description "内容类型，默认 application/json"}
                             :timeout {:type "integer"
                                       :description "超时时间（毫秒），默认 10000"}}
                :required ["url"]}
   :category :http
   :permissions #{:network-access}
   :handler (fn [{:keys [url body content-type timeout]}]
              (try
                (let [response (http/put url
                                         :body body
                                         :content-type (or content-type "application/json")
                                         :timeout (or timeout 10000))
                      truncated (helpers/truncate-http-response response 10000)]
                  (str "状态: " (:status truncated) "\n"
                       "响应: " (:body truncated)))
                (catch Exception e
                  (str "请求失败: " (.getMessage e)))))})

(def ^:private http-delete-tool
  {:name :http_delete
   :description "发送 HTTP DELETE 请求"
   :parameters {:type "object"
                :properties {:url {:type "string"
                                   :description "完整的 URL 地址"}
                             :timeout {:type "integer"
                                       :description "超时时间（毫秒），默认 10000"}}
                :required ["url"]}
   :category :http
   :permissions #{:network-access}
   :handler (fn [{:keys [url timeout]}]
              (try
                (let [response (http/delete url :timeout (or timeout 10000))
                      truncated (helpers/truncate-http-response response 10000)]
                  (str "状态: " (:status truncated) "\n"
                       "响应: " (:body truncated)))
                (catch Exception e
                  (str "请求失败: " (.getMessage e)))))})

;;; ============================================================
;;; Shell 工具
;;; ============================================================

(def ^:private shell-command-tool
  {:name :shell_command
   :description "执行 shell 命令。仅限安全的只读命令，如 ls, cat, grep 等。"
   :parameters {:type "object"
                :properties {:command {:type "string"
                                       :description "要执行的命令"}
                             :timeout {:type "integer"
                                       :description "超时时间（秒），默认 30"}}
                :required ["command"]}
   :category :shell
   :permissions #{:shell-access}
   :handler (fn [{:keys [command timeout]}]
              (let [result (helpers/execute-shell-safe command)]
                (if (:success result)
                  (let [{:keys [stdout stderr exit-code]} (:result result)]
                    (str (when (seq stdout) stdout)
                         (when (seq stderr) (str "\nSTDERR: " stderr))
                         "\n[退出码: " exit-code "]"))
                  (:error result))))})

(def ^:private pwd-tool
  {:name :pwd
   :description "获取当前工作目录"
   :parameters {:type "object"
                :properties {}
                :required []}
   :category :shell
   :permissions #{:shell-access}
   :handler (fn [_]
              (System/getProperty "user.dir"))})

;;; ============================================================
;;; 工具集合
;;; ============================================================

(def builtin-tools
  "所有内置工具

   分类:
   - :math - 计算器
   - :utility - 通用工具（时间等）
   - :file - 文件操作
   - :http - HTTP 请求
   - :shell - Shell 命令"
  [;; 计算
   calculator-tool
   ;; 时间
   current-time-tool
   ;; 文件操作
   read-file-tool
   write-file-tool
   append-file-tool
   list-directory-tool
   file-exists-tool
   file-info-tool
   copy-file-tool
   move-file-tool
   delete-file-tool
   create-directory-tool
   ;; HTTP
   http-get-tool
   http-post-tool
   http-put-tool
   http-delete-tool
   ;; Shell
   shell-command-tool
   pwd-tool])

(def builtin-tool-names
  "所有内置工具名称列表"
  (mapv :name builtin-tools))

;;; ============================================================
;;; 注册函数
;;; ============================================================

(defn register-builtin-tools
  "注册所有内置工具到 Registry

   参数:
   - registry: ToolRegistry 实例

   返回: registry

   示例:
   (def registry
     (-> (tools/create-tool-registry)
         (register-builtin-tools)))"
  [registry]
  (tool-registry/register-tools! registry builtin-tools))

(defn list-builtin-tools
  "列出所有内置工具

   返回: 内置工具定义列表

   示例:
   (list-builtin-tools)"
  []
  builtin-tools)

(defn get-builtin-tool
  "获取内置工具定义

   参数:
   - name: 工具名称

   返回: 工具定义或 nil

   示例:
   (get-builtin-tool :calculator)"
  [tool-name]
  (first (filter #(= (:name %) tool-name) builtin-tools)))

(defn list-builtin-tools-by-category
  "按分类列出内置工具

   参数:
   - category: 分类关键字

   返回: 工具定义列表

   示例:
   (list-builtin-tools-by-category :file)"
  [category]
  (filter #(= (:category %) category) builtin-tools))

(defn describe-builtin-tools
  "描述所有内置工具

   返回: 描述字符串

   示例:
   (describe-builtin-tools)"
  []
  (str "Built-in Tools:\n\n"
       (str/join "\n\n"
                 (for [[cat tools] (group-by :category builtin-tools)]
                   (str "=== " (name cat) " ===\n"
                        (str/join "\n"
                                  (map #(str "  " (name (:name %)) ": " (:description %))
                                       tools)))))))
