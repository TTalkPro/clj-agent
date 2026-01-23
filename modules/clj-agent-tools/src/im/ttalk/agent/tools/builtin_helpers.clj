(ns im.ttalk.agent.tools.builtin-helpers
  "内置工具辅助函数

   职责：
   - 内容截断
   - 命令安全检查
   - Shell 执行
   - 文件操作辅助

   使用示例：

   (require '[im.ttalk.agent.tools.builtin-helpers :as helpers])

   (helpers/truncate-content long-text 1000)

   (helpers/dangerous-command? \"rm -rf /\")"
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
   - options: 选项 {:suffix \"...\" :ellipsis-position :end}

   返回: 截断后的内容

   选项:
   - :suffix 截断后缀（默认 \"... (内容已截断)\"）
   - :ellipsis-position 省略号位置（:end, :middle，默认 :end）

   示例:
   (truncate-content \"very long content...\" 10)
   ; => \"very long... (内容已截断)\"

   (truncate-content \"ABCDEFGHIJ\" 5 {:suffix \"...\" :ellipsis-position :middle})
   ; => \"AB...IJ\""
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
  "安全截断（带类型检查）

   参数:
   - content: 内容（字符串或其他类型）
   - max-length: 最大长度

   返回: 截断后的内容（字符串）

   示例:
   (safe-truncate 12345 3)
   ; => \"123\""
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
  "危险命令模式列表

   包含：
   - 删除命令（rm, dd, mkfs）
   - 权限提升（sudo, chmod, chown）
   - 重定向到系统目录
   - 管道到删除命令"
  [#"(?i)rm\s+"                  ; rm 命令
   #"(?i)sudo\s+"                ; sudo
   #"(?i)chmod\s+"               ; chmod
   #"(?i)chown\s+"               ; chown
   #"(?i)mkfs"                   ; mkfs（文件系统格式化）
   #"(?i)dd\s+"                  ; dd（磁盘复制）
   #">\s*/"                      ; 重定向到根目录
   #";\s*rm"                     ; 分号 + rm
   #"\|\s*rm"                    ; 管道 + rm
   #"(?i)curl.*\|.*sh"           ; curl | sh
   #"(?i)wget.*\|.*sh"           ; wget | sh
   #"(?i)format\s+"              ; format（Windows）
   #"(?i)del\s+"                 ; del（Windows）
   #"(?i)rmdir\s+"               ; rmdir（Windows）
   ])

(defn dangerous-command?
  "检查是否为危险命令

   参数:
   - command: 命令字符串

   返回: boolean

   示例:
   (dangerous-command? \"ls -la\")
   ; => false

   (dangerous-command? \"rm -rf /\")
   ; => true"
  [command]
  (some #(re-find % command) dangerous-patterns))

(defn check-command-safety
  "检查命令安全性（返回详细结果）

   参数:
   - command: 命令字符串
   - options: 选项 {:allow-patterns [...], :block-patterns [...]}

   返回: {:safe? bool :reason string :matched-pattern string}

   示例:
   (check-command-safety \"ls -la\")
   ; => {:safe? true :reason nil :matched-pattern nil}

   (check-command-safety \"rm -rf /\")
   ; => {:safe? false :reason \"Dangerous command\" :matched-pattern \"(?i)rm\\s+\"}"
  ([command]
   (check-command-safety command {}))
  ([command options]
   (let [block-patterns (concat dangerous-patterns
                                (:block-patterns options))
         allow-patterns (:allow-patterns options)]
     ;; 首先检查白名单
     (if (and (seq allow-patterns)
              (some #(re-find % command) allow-patterns))
       {:safe? true :reason "Allowed by whitelist" :matched-pattern nil}
       ;; 然后检查黑名单
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

   参数:
   - command: 命令字符串
   - options: 选项 {:timeout 30000 :env {...}}

   返回: {:stdout string :stderr string :exit-code int}

   示例:
   (execute-shell \"ls -la\")
   ; => {:stdout \"file1.txt\\nfile2.txt\" :stderr \"\" :exit-code 0}"
  ([command]
   (execute-shell command {}))
  ([command options]
   (let [timeout (:timeout options 30000)
         env (merge {} (:env options))
         ;; 构建进程
         process-builder (java.lang.ProcessBuilder. ["sh" "-c" command])]
     ;; 设置环境变量
     (when (seq env)
       (let [env-map (.environment process-builder)]
         (doseq [[k v] env]
           (.put env-map (name k) v))))
     ;; 启动进程
     (try
       (let [process (.start process-builder)
             ;; 读取输出（带超时）
             stdout-future (future (slurp (.getInputStream process)))
             stderr-future (future (slurp (.getErrorStream process)))
             exit-code (.waitFor process)
             stdout (deref stdout-future timeout ::timeout)
             stderr (deref stderr-future timeout ::timeout)]
         ;; 清理资源
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

   参数:
   - command: 命令字符串
   - options: 选项（传递给 execute-shell 和 check-command-safety）

   返回: {:success bool :result {:stdout ... :stderr ... :exit-code ...} :error string}

   示例:
   (execute-shell-safe \"ls -la\")
   ; => {:success true :result {...} :error nil}

   (execute-shell-safe \"rm -rf /\")
   ; => {:success false :result nil :error \"Dangerous command\"}"
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
  "检查文件是否存在

   参数:
   - path: 文件路径

   返回: boolean

   示例:
   (file-exists? \"/tmp/test.txt\")
   ; => true"
  [path]
  (.exists (java.io.File. path)))

(defn read-file-safe
  "安全读取文件

   参数:
   - path: 文件路径
   - options: 选项 {:max-length 10000}

   返回: {:success bool :content string :error string}

   示例:
   (read-file-safe \"/tmp/test.txt\" {:max-length 1000})"
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

   参数:
   - path: 文件路径
   - content: 内容
   - options: 选项 {:create-dirs? false}

   返回: {:success bool :bytes-written int :error string}

   示例:
   (write-file-safe \"/tmp/test.txt\" \"Hello World\")"
  ([path content]
   (write-file-safe path content {}))
  ([path content options]
   (try
     (let [file (java.io.File. path)]
       ;; 创建目录（如果需要）
       (when (:create-dirs? options)
         (let [parent-dir (.getParentFile file)]
           (when (and parent-dir (not (.exists parent-dir)))
             (.mkdirs parent-dir))))
       ;; 写入文件
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

   参数:
   - path: 目录路径
   - options: 选项 {:include-hidden? false}

   返回: {:success bool :entries [{:name string :type :dir/:file}] :error string}

   示例:
   (list-directory-safe \"/tmp\" {:include-hidden? true})"
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
  "截断 HTTP 响应

   参数:
   - response: HTTP 响应 map
   - max-length: 最大长度

   返回: 截断后的响应

   示例:
   (truncate-http-response {:body \"very long content...\"} 1000)
   ; => {:body \"very long... (内容已截断)\"}"
  ([response max-length]
   (truncate-http-response response max-length {}))
  ([response max-length options]
   (if (:body response)
     (assoc response :body
            (truncate-content (:body response) max-length options))
     response)))
