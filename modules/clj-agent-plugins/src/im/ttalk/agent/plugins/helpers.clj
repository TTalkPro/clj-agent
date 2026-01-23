(ns im.ttalk.agent.plugins.helpers
  "工具辅助函数

   职责：
   - 内容截断
   - 命令安全检查
   - Shell 执行
   - 文件操作辅助
   - HTTP 响应截断"
  (:require [clojure.string :as str]))

;; ============================================================
;; 常量定义
;; ============================================================

(def ^:private default-max-file-size
  "默认最大文件大小（字符数）"
  10000)

(def ^:private default-max-http-size
  "默认最大 HTTP 响应大小（字符数）"
  10000)

;; ============================================================
;; 内容截断
;; ============================================================

(defn truncate-content
  "截断过长内容

   参数:
   - content: 原始内容（字符串）
   - max-length: 最大长度
   - options: 选项 {:suffix \"...\" :ellipsis-position :end}"
  ([content max-length]
   (truncate-content content max-length {}))
  ([content max-length options]
   (let [suffix (get options :suffix "... (内容已截断)")
         position (get options :ellipsis-position :end)]
     (if (<= (count content) max-length)
       content
       (case position
         :end
         (let [available (- max-length (count suffix))]
           (if (pos? available)
             (str (subs content 0 available) suffix)
             (str (subs content 0 max-length))))
         :middle
         (let [half (quot max-length 2)
               suffix-len (count suffix)
               left-len (max 1 (- half suffix-len))]
           (str (subs content 0 left-len)
                suffix
                (subs content (- (count content) (- max-length left-len (count suffix))))))
         content)))))

(defn safe-truncate
  "安全截断（带类型检查）"
  [content max-length]
  (cond
    (string? content)
    (truncate-content content max-length)
    (number? content)
    (truncate-content (str content) max-length)
    :else
    (truncate-content (pr-str content) max-length)))

;; ============================================================
;; 命令安全检查
;; ============================================================

(def dangerous-patterns
  "危险命令模式列表"
  [#"(?i)rm\s+"
   #"(?i)sudo\s+"
   #"(?i)chmod\s+"
   #"(?i)chown\s+"
   #"(?i)mkfs"
   #"(?i)dd\s+"
   #">\s*/"
   #";\s*rm"
   #"\|\s*rm"
   #"(?i)curl.*\|.*sh"
   #"(?i)wget.*\|.*sh"
   #"(?i)format\s+"
   #"(?i)del\s+"
   #"(?i)rmdir\s+"])

(defn dangerous-command?
  "检查是否为危险命令"
  [command]
  (boolean (some #(re-find % command) dangerous-patterns)))

(defn check-command-safety
  "检查命令安全性（返回详细结果）

   返回: {:safe? bool :reason string :matched-pattern string}"
  ([command]
   (check-command-safety command {}))
  ([command options]
   (let [block-patterns (concat dangerous-patterns
                                (:block-patterns options))
         allow-patterns (:allow-patterns options)]
     (if (and (seq allow-patterns)
              (some #(re-find % command) allow-patterns))
       {:safe? true :reason "Allowed by whitelist" :matched-pattern nil}
       (if-let [matched (first (filter #(re-find % command) block-patterns))]
         {:safe? false
          :reason "Dangerous command detected"
          :matched-pattern (str matched)}
         {:safe? true :reason nil :matched-pattern nil})))))

;; ============================================================
;; Shell 执行
;; ============================================================

(defn execute-shell
  "执行 shell 命令

   返回: {:stdout string :stderr string :exit-code int}"
  ([command]
   (execute-shell command {}))
  ([command options]
   (let [timeout (:timeout options 30000)
         env (merge {} (:env options))
         process-builder (java.lang.ProcessBuilder. ["sh" "-c" command])]
     (when (seq env)
       (let [env-map (.environment process-builder)]
         (doseq [[k v] env]
           (.put env-map (name k) v))))
     (try
       (let [process (.start process-builder)
             stdout-future (future (slurp (.getInputStream process)))
             stderr-future (future (slurp (.getErrorStream process)))
             exit-code (.waitFor process)
             stdout (deref stdout-future timeout ::timeout)
             stderr (deref stderr-future timeout ::timeout)]
         (.destroy process)
         {:stdout (if (= stdout ::timeout)
                    "(stdout read timeout)"
                    stdout)
          :stderr (if (= stderr ::timeout)
                    "(stderr read timeout)"
                    stderr)
          :exit-code exit-code})
       (catch Exception e
         {:stdout ""
          :stderr (str "Execution error: " (.getMessage e))
          :exit-code -1})))))

(defn execute-shell-safe
  "安全执行 shell 命令（带危险检查）

   返回: {:success bool :result {:stdout ... :stderr ... :exit-code ...} :error string}"
  ([command]
   (execute-shell-safe command {}))
  ([command options]
   (let [safety-check (check-command-safety command options)]
     (if (:safe? safety-check)
       (let [result (execute-shell command options)]
         {:success true :result result :error nil})
       {:success false
        :result nil
        :error (:reason safety-check)}))))

;; ============================================================
;; 文件操作辅助
;; ============================================================

(defn file-exists?
  "检查文件是否存在"
  [path]
  (.exists (java.io.File. path)))

(defn read-file-safe
  "安全读取文件

   返回: {:success bool :content string :error string}"
  ([path]
   (read-file-safe path {}))
  ([path options]
   (try
     (if (file-exists? path)
       (let [max-length (:max-length options default-max-file-size)
             content (slurp path)]
         {:success true
          :content (truncate-content content max-length)
          :error nil})
       {:success false
        :content nil
        :error (str "File not found: " path)})
     (catch Exception e
       {:success false
        :content nil
        :error (str "Read error: " (.getMessage e))}))))

(defn write-file-safe
  "安全写入文件

   返回: {:success bool :bytes-written int :error string}"
  ([path content]
   (write-file-safe path content {}))
  ([path content options]
   (try
     (let [file (java.io.File. path)]
       (when (:create-dirs? options)
         (let [parent-dir (.getParentFile file)]
           (when (and parent-dir (not (.exists parent-dir)))
             (.mkdirs parent-dir))))
       (spit path content)
       {:success true
        :bytes-written (count content)
        :error nil})
     (catch Exception e
       {:success false
        :bytes-written 0
        :error (str "Write error: " (.getMessage e))}))))

(defn list-directory-safe
  "安全列出目录

   返回: {:success bool :entries [{:name string :type :dir/:file}] :error string}"
  ([path]
   (list-directory-safe path {}))
  ([path options]
   (try
     (let [dir (java.io.File. path)
           include-hidden? (:include-hidden? options false)]
       (if (.exists dir)
         (if (.isDirectory dir)
           (let [files (.listFiles dir)
                 entries (->> files
                             (filter #(or include-hidden?
                                        (not (.startsWith (.getName %) "."))))
                             (mapv (fn [f]
                                     {:name (.getName f)
                                      :type (if (.isDirectory f) :dir :file)
                                      :size (.length f)
                                      :last-modified (.lastModified f)})))]
             {:success true
              :entries entries
              :error nil})
           {:success false
            :entries nil
            :error "Not a directory"})
         {:success false
          :entries nil
          :error (str "Directory not found: " path)}))
     (catch Exception e
       {:success false
        :entries nil
        :error (str "List error: " (.getMessage e))}))))

;; ============================================================
;; HTTP 辅助
;; ============================================================

(defn truncate-http-response
  "截断 HTTP 响应"
  ([response max-length]
   (truncate-http-response response max-length {}))
  ([response max-length options]
   (if (:body response)
     (assoc response :body
            (truncate-content (str (:body response)) max-length options))
     response)))
