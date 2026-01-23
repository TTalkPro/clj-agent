(ns im.ttalk.agent.tools.security
  "工具安全控制

   参考 cl-agent-tools 设计，提供：
   - 文件路径白名单
   - Shell 命令白名单
   - HTTP 域名白名单
   - 危险命令检测

   使用示例：

   (require '[im.ttalk.agent.tools.security :as security])

   ;; 设置文件白名单
   (security/set-file-whitelist! [\"/home/user/data\" \"/tmp\"])

   ;; 设置命令白名单
   (security/set-shell-whitelist! [\"ls\" \"cat\" \"grep\"])

   ;; 设置域名白名单
   (security/set-http-whitelist! [\"api.example.com\"])"

  (:require [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.net URI]))

;;; ============================================================
;;; 全局状态
;;; ============================================================

(defonce ^:private config
  (atom {;; 文件安全
         :file-enabled true
         :file-whitelist []                    ; 空列表表示允许所有
         :file-max-size 10485760               ; 10MB

         ;; Shell 安全
         :shell-enabled true
         :shell-whitelist []                   ; 空列表表示允许所有
         :shell-timeout 30                     ; 秒

         ;; HTTP 安全
         :http-enabled true
         :http-whitelist []                    ; 空列表表示允许所有域名
         :http-timeout 30000                   ; 毫秒

         ;; 调试
         :verbose false}))

;;; ============================================================
;;; 危险命令模式
;;; ============================================================

(def ^:private dangerous-patterns
  "危险命令模式列表"
  [;; 删除
   #"(?i)\brm\s+-rf\b"
   #"(?i)\brm\s+-r\b"
   #"(?i)\brmdir\b"
   #"(?i)\bdel\s+/s\b"
   #"(?i)\bdd\s+if="
   #"(?i)\bmkfs\b"

   ;; 权限提升
   #"(?i)\bsudo\b"
   #"(?i)\bsu\s+"
   #"(?i)\bchmod\s+777\b"
   #"(?i)\bchown\b"

   ;; 危险管道
   #"(?i)\bcurl\s+.*\|\s*sh\b"
   #"(?i)\bwget\s+.*\|\s*sh\b"
   #"(?i)\bcurl\s+.*\|\s*bash\b"
   #"(?i)\bwget\s+.*\|\s*bash\b"

   ;; 系统修改
   #"(?i)\bshutdown\b"
   #"(?i)\breboot\b"
   #"(?i)\bhalt\b"
   #"(?i)\bkill\s+-9\b"
   #"(?i)\bkillall\b"

   ;; 网络
   #"(?i)\biptables\b"
   #"(?i)\bufw\b"

   ;; 危险重定向
   #">\s*/dev/"
   #">\s*/etc/"
   #">\s*/usr/"
   #">\s*/bin/"
   #">\s*/sbin/"])

;;; ============================================================
;;; 文件安全
;;; ============================================================

(defn set-file-whitelist!
  "设置允许访问的路径白名单

   参数:
   - paths: 路径列表

   说明:
   空列表表示允许所有路径

   示例:
   (set-file-whitelist! [\"/home/user/data\" \"/tmp\"])"
  [paths]
  (swap! config assoc :file-whitelist (vec paths))
  (when (:verbose @config)
    (println "[Security] File whitelist set to:" paths)))

(defn allow-file-path!
  "添加路径到白名单

   参数:
   - path: 路径字符串"
  [path]
  (swap! config update :file-whitelist conj path)
  (when (:verbose @config)
    (println "[Security] Allowed file path:" path)))

(defn set-file-max-size!
  "设置最大文件大小

   参数:
   - size: 字节数"
  [size]
  (swap! config assoc :file-max-size size))

(defn enable-file!
  "启用文件工具"
  []
  (swap! config assoc :file-enabled true))

(defn disable-file!
  "禁用文件工具"
  []
  (swap! config assoc :file-enabled false))

(defn file-enabled?
  "检查文件工具是否启用"
  []
  (:file-enabled @config))

(defn check-file-path
  "检查文件路径是否允许访问

   参数:
   - filepath: 文件路径

   返回: {:allowed true} 或 {:allowed false :reason string}

   示例:
   (check-file-path \"/home/user/data/file.txt\")"
  [filepath]
  (if-not (:file-enabled @config)
    {:allowed false :reason "File tools are disabled"}
    (let [whitelist (:file-whitelist @config)]
      (if (empty? whitelist)
        {:allowed true}
        (let [abs-path (try
                         (.getCanonicalPath (io/file filepath))
                         (catch Exception _ filepath))]
          (if (some #(str/starts-with? abs-path %) whitelist)
            {:allowed true}
            {:allowed false
             :reason (str "Path not in whitelist: " filepath)}))))))

(defn check-file-size
  "检查文件大小是否超限

   参数:
   - filepath: 文件路径

   返回: {:allowed true :size n} 或 {:allowed false :reason string}"
  [filepath]
  (let [file (io/file filepath)
        max-size (:file-max-size @config)]
    (if (.exists file)
      (let [size (.length file)]
        (if (<= size max-size)
          {:allowed true :size size}
          {:allowed false
           :reason (str "File too large: " size " bytes (max: " max-size ")")}))
      {:allowed true :size 0})))

;;; ============================================================
;;; Shell 安全
;;; ============================================================

(defn set-shell-whitelist!
  "设置允许的命令白名单

   参数:
   - commands: 命令名称列表

   说明:
   空列表表示允许所有命令

   示例:
   (set-shell-whitelist! [\"ls\" \"cat\" \"grep\" \"wc\"])"
  [commands]
  (swap! config assoc :shell-whitelist (vec commands))
  (when (:verbose @config)
    (println "[Security] Shell whitelist set to:" commands)))

(defn allow-shell-command!
  "添加命令到白名单

   参数:
   - command: 命令名称"
  [command]
  (swap! config update :shell-whitelist conj command)
  (when (:verbose @config)
    (println "[Security] Allowed shell command:" command)))

(defn set-shell-timeout!
  "设置命令执行超时

   参数:
   - timeout: 秒数"
  [timeout]
  (swap! config assoc :shell-timeout timeout))

(defn enable-shell!
  "启用 Shell 工具"
  []
  (swap! config assoc :shell-enabled true))

(defn disable-shell!
  "禁用 Shell 工具"
  []
  (swap! config assoc :shell-enabled false))

(defn shell-enabled?
  "检查 Shell 工具是否启用"
  []
  (:shell-enabled @config))

(defn dangerous-command?
  "检测命令是否危险

   参数:
   - command: 命令字符串

   返回: boolean"
  [command]
  (boolean (some #(re-find % command) dangerous-patterns)))

(defn check-shell-command
  "检查 Shell 命令是否允许

   参数:
   - command: 命令字符串

   返回: {:allowed true} 或 {:allowed false :reason string :dangerous? bool}

   示例:
   (check-shell-command \"ls -la\")"
  [command]
  (cond
    (not (:shell-enabled @config))
    {:allowed false :reason "Shell commands are disabled"}

    (dangerous-command? command)
    {:allowed false
     :reason "Dangerous command detected"
     :dangerous? true}

    :else
    (let [whitelist (:shell-whitelist @config)]
      (if (empty? whitelist)
        {:allowed true}
        (let [cmd-name (first (str/split (str/trim command) #"\s+"))]
          (if (some #(= % cmd-name) whitelist)
            {:allowed true}
            {:allowed false
             :reason (str "Command not in whitelist: " cmd-name)}))))))

(defn get-shell-timeout
  "获取 Shell 超时设置"
  []
  (:shell-timeout @config))

;;; ============================================================
;;; HTTP 安全
;;; ============================================================

(defn set-http-whitelist!
  "设置允许访问的域名白名单

   参数:
   - domains: 域名列表

   说明:
   空列表表示允许所有域名

   示例:
   (set-http-whitelist! [\"api.example.com\" \"data.example.org\"])"
  [domains]
  (swap! config assoc :http-whitelist (vec domains))
  (when (:verbose @config)
    (println "[Security] HTTP whitelist set to:" domains)))

(defn allow-http-domain!
  "添加域名到白名单

   参数:
   - domain: 域名字符串"
  [domain]
  (swap! config update :http-whitelist conj domain)
  (when (:verbose @config)
    (println "[Security] Allowed HTTP domain:" domain)))

(defn set-http-timeout!
  "设置 HTTP 请求超时

   参数:
   - timeout: 毫秒数"
  [timeout]
  (swap! config assoc :http-timeout timeout))

(defn enable-http!
  "启用 HTTP 工具"
  []
  (swap! config assoc :http-enabled true))

(defn disable-http!
  "禁用 HTTP 工具"
  []
  (swap! config assoc :http-enabled false))

(defn http-enabled?
  "检查 HTTP 工具是否启用"
  []
  (:http-enabled @config))

(defn check-http-url
  "检查 HTTP URL 是否允许

   参数:
   - url: URL 字符串

   返回: {:allowed true} 或 {:allowed false :reason string}

   示例:
   (check-http-url \"https://api.example.com/data\")"
  [url]
  (if-not (:http-enabled @config)
    {:allowed false :reason "HTTP requests are disabled"}
    (let [whitelist (:http-whitelist @config)]
      (if (empty? whitelist)
        {:allowed true}
        (try
          (let [uri (URI. url)
                host (.getHost uri)]
            (if (some #(or (= % host)
                           (str/ends-with? host (str "." %)))
                      whitelist)
              {:allowed true}
              {:allowed false
               :reason (str "Domain not in whitelist: " host)}))
          (catch Exception e
            {:allowed false
             :reason (str "Invalid URL: " (.getMessage e))}))))))

(defn get-http-timeout
  "获取 HTTP 超时设置"
  []
  (:http-timeout @config))

;;; ============================================================
;;; 通用配置
;;; ============================================================

(defn set-verbose!
  "设置详细输出

   参数:
   - enabled: boolean"
  [enabled]
  (swap! config assoc :verbose enabled))

(defn get-config
  "获取当前配置

   返回: 配置 map"
  []
  @config)

(defn reset-config!
  "重置为默认配置"
  []
  (reset! config
          {:file-enabled true
           :file-whitelist []
           :file-max-size 10485760
           :shell-enabled true
           :shell-whitelist []
           :shell-timeout 30
           :http-enabled true
           :http-whitelist []
           :http-timeout 30000
           :verbose false}))

;;; ============================================================
;;; 安全模式预设
;;; ============================================================

(defn enable-strict-mode!
  "启用严格模式

   - 禁用 Shell
   - 禁用 HTTP
   - 只允许读取文件"
  []
  (disable-shell!)
  (disable-http!)
  (println "[Security] Strict mode enabled"))

(defn enable-sandbox-mode!
  "启用沙箱模式

   - 只允许安全的只读命令
   - 只允许本地文件
   - 禁用网络"
  []
  (set-shell-whitelist! ["ls" "cat" "head" "tail" "wc" "grep" "find" "pwd" "echo"])
  (disable-http!)
  (println "[Security] Sandbox mode enabled"))

(defn enable-development-mode!
  "启用开发模式

   - 允许大部分操作
   - 仍然阻止危险命令"
  []
  (enable-shell!)
  (enable-http!)
  (enable-file!)
  (set-shell-whitelist! [])  ; 允许所有，但仍检测危险命令
  (set-http-whitelist! [])   ; 允许所有域名
  (set-file-whitelist! [])   ; 允许所有路径
  (println "[Security] Development mode enabled"))
