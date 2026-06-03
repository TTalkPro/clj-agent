(ns im.ttalk.agent.tools.security
  "安全策略与 Kernel Filter 工厂

   提供工具调用前的安全拦截：
   - 工具白名单/黑名单
   - 路径安全检查
   - 命令安全检查
   - 域名白名单"
  (:require [clojure.string :as str]
            [im.ttalk.agent.tools.helpers :as helpers]
            [im.ttalk.agent.core.kernel.filter :as kf])
  (:import [java.net URI]))

;; ============================================================
;; 安全策略
;; ============================================================

(defn create-security-policy
  "创建安全策略

   参数:
   - opts: 策略选项 map

   选项:
   - :allowed-tools     工具白名单（keyword 集合，nil 表示允许全部）
   - :blocked-tools     工具黑名单（keyword 集合）
   - :allowed-paths     允许的文件路径前缀列表
   - :blocked-commands  命令黑名单模式列表
   - :allowed-domains   允许的 HTTP 域名列表（nil 表示允许全部）"
  [{:keys [allowed-tools blocked-tools allowed-paths blocked-commands allowed-domains]
    :or {blocked-tools #{}
         allowed-paths []
         blocked-commands []
         allowed-domains nil}}]
  {:allowed-tools allowed-tools
   :blocked-tools (set blocked-tools)
   :allowed-paths (vec allowed-paths)
   :blocked-commands (vec blocked-commands)
   :allowed-domains allowed-domains})

(defn- check-tool-allowed
  "检查工具是否允许调用"
  [policy tool-name]
  (let [tn (keyword tool-name)]
    (cond
      ;; 黑名单优先
      (contains? (:blocked-tools policy) tn)
      {:allowed false :reason (str "工具在黑名单中: " (name tn))}

      ;; 白名单（nil 表示允许全部）
      (and (:allowed-tools policy)
           (not (contains? (:allowed-tools policy) tn)))
      {:allowed false :reason (str "工具不在白名单中: " (name tn))}

      :else
      {:allowed true})))

(defn- check-path-allowed
  "检查文件路径是否允许"
  [policy path]
  (if (or (nil? path) (empty? (:allowed-paths policy)))
    {:allowed true}
    (let [abs-path (try
                     (.getCanonicalPath (java.io.File. path))
                     (catch Exception _ path))]
      (if (some #(str/starts-with? abs-path %) (:allowed-paths policy))
        {:allowed true}
        {:allowed false :reason (str "路径不在允许范围内: " path)}))))

(defn- check-command-allowed
  "检查命令是否允许"
  [policy command]
  (if (nil? command)
    {:allowed true}
    (let [safety (helpers/check-command-safety command
                   {:block-patterns (:blocked-commands policy)})]
      (if (:safe? safety)
        {:allowed true}
        {:allowed false :reason (:reason safety)}))))

(defn- check-url-allowed
  "检查 URL 域名是否允许"
  [policy url]
  (if (or (nil? url) (nil? (:allowed-domains policy)))
    {:allowed true}
    (try
      (let [uri (URI. url)
            host (.getHost uri)]
        (if (some #(or (= % host)
                       (str/ends-with? host (str "." %)))
                  (:allowed-domains policy))
          {:allowed true}
          {:allowed false :reason (str "域名不在白名单中: " host)}))
      (catch Exception e
        {:allowed false :reason (str "无效 URL: " (.getMessage e))}))))

(defn- extract-security-args
  "从工具参数中提取安全相关参数"
  [tool-name args]
  (let [tn (name (keyword tool-name))]
    {:path (or (:path args) (:source args))
     :command (:command args)
     :url (:url args)}))

;; ============================================================
;; Kernel Filter 工厂
;; ============================================================

(defn create-security-advisor
  "创建安全 tool advisor

   调用工具前做权限检查；不通过则短路（不调下游），返回阻止原因结果。

   参数:
   - policy: 安全策略（由 create-security-policy 创建）
   - opts:   可选参数 {:order 10}（兼容旧 :priority）

   返回: advisor 定义 map（可直接传给 kernel/add-advisor）"
  ([policy] (create-security-advisor policy {}))
  ([policy opts]
   (kf/create-advisor :security :tool
     :order (or (:order opts) (:priority opts) 10)
     :advise-call
     (fn [req chain]
       (let [tool-name (get-in req [:function :name])
             args (:args req)
             tool-check (check-tool-allowed policy tool-name)
             block (fn [reason] {:result (str "安全策略阻止: " reason) :context (:context req)})]
         (if-not (:allowed tool-check)
           (block (:reason tool-check))
           (let [{:keys [path command url]} (extract-security-args tool-name args)
                 path-check (check-path-allowed policy path)
                 cmd-check (check-command-allowed policy command)
                 url-check (check-url-allowed policy url)]
             (cond
               (not (:allowed path-check)) (block (:reason path-check))
               (not (:allowed cmd-check))  (block (:reason cmd-check))
               (not (:allowed url-check))  (block (:reason url-check))
               :else (chain req)))))))))

;; ============================================================
;; 预设安全模式
;; ============================================================

(def strict-policy
  "严格模式策略：禁止 shell 和 HTTP，只允许文件读取"
  (create-security-policy
    {:blocked-tools #{:execute-command :execute-command-safe
                      :http-get :http-post :http-put :http-delete
                      :write-file :append-file :delete-file
                      :move-file :copy-file :create-directory}}))

(def sandbox-policy
  "沙箱模式策略：只允许只读命令，禁止网络"
  (create-security-policy
    {:blocked-tools #{:execute-command
                      :http-get :http-post :http-put :http-delete
                      :write-file :append-file :delete-file
                      :move-file :copy-file :create-directory}}))

(def development-policy
  "开发模式策略：允许大部分操作，仅阻止危险命令"
  (create-security-policy
    {:blocked-tools #{}}))
