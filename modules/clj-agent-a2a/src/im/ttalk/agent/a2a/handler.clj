(ns im.ttalk.agent.a2a.handler
  "A2A Handler - 纯函数处理层

   提供 A2A 请求的处理逻辑，所有函数都是纯函数（接收 registry 作为参数）。
   此模块可以独立于 A2A Server 使用，适用于各种 HTTP 框架集成。

   主要功能：
   - A2A 请求处理函数（handle-*）
   - 统一路由函数（route-request）
   - Ring handler 适配器
   - SSE handler 适配器"
  (:require [im.ttalk.agent.a2a.types :as types]
            [im.ttalk.agent.a2a.json-rpc :as rpc]
            [im.ttalk.agent.a2a.task :as task]
            [im.ttalk.agent.a2a.card :as card]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; =============================================================================
;; A2A Registry - 状态管理
;; =============================================================================

(defrecord A2ARegistry [card-registry
                        task-store
                        push-config-store
                        message-handler-atom
                        options])

(defn create-registry
  "创建 A2A Registry 实例

   参数:
   - opts: 配置选项
     {:name \"agent-name\"
      :description \"描述\"
      :url \"https://...\"
      :version \"1.0.0\"
      :capabilities {:streaming false ...}}

   返回: A2ARegistry 实例"
  [{:keys [name description url version capabilities]
    :or {version "1.0.0"
         capabilities {}}}]
  (let [card-reg (card/create-card-registry
                   {:name name
                    :description description
                    :url url
                    :version version
                    :capabilities capabilities})
        task-store (task/create-task-store)
        push-config-store (task/create-push-config-store)]
    (->A2ARegistry card-reg
                   task-store
                   push-config-store
                   (atom nil)
                   {:name name :version version})))

(defn set-message-handler
  "设置消息处理器

   参数:
   - registry: A2ARegistry 实例
   - handler: 消息处理函数 (fn [registry task message] -> result)

   返回: registry"
  [registry handler]
  (reset! (:message-handler-atom registry) handler)
  registry)

(defn get-message-handler
  "获取消息处理器

   参数:
   - registry: A2ARegistry 实例

   返回: 消息处理函数或 nil"
  [registry]
  @(:message-handler-atom registry))

;; =============================================================================
;; 工具注册
;; =============================================================================

(defn register-tool
  "注册工具

   参数:
   - registry: A2ARegistry 实例
   - tool: 工具定义

   返回: registry"
  [registry tool]
  (card/register-tool! (:card-registry registry) tool)
  registry)

(defn register-tools
  "批量注册工具

   参数:
   - registry: A2ARegistry 实例
   - tools: 工具列表

   返回: registry"
  [registry tools]
  (card/register-tools! (:card-registry registry) tools)
  registry)

;; =============================================================================
;; 消息处理执行
;; =============================================================================

(defn- execute-message-handler
  "异步执行消息处理器

   参数:
   - registry: A2ARegistry 实例
   - task-id: 任务 ID
   - message: 消息"
  [registry task-id message]
  (future
    (try
      (let [store (:task-store registry)]
        ;; 转换到 working 状态
        (task/transition-task! store task-id :working)

        ;; 调用消息处理器
        (let [current-task (task/get-task store task-id)
              handler (get-message-handler registry)
              result (when handler
                       (handler registry current-task message))]
          (cond
            ;; 没有处理器
            (nil? handler)
            (task/fail-task! store task-id "No message handler configured")

            ;; 返回 :input-required 表示需要用户输入
            (= :input-required (:type result))
            (task/request-input! store task-id
              (types/text-message (:prompt result) :agent))

            ;; 返回 :failed 表示处理失败
            (= :failed (:type result))
            (task/fail-task! store task-id (:error result))

            ;; 否则视为成功，result 应该是 artifacts 列表或单个文本
            :else
            (let [artifacts (cond
                              (vector? result) result
                              (string? result) [(types/text-artifact result)]
                              (map? result) [(types/create-artifact result)]
                              :else [])]
              (task/complete-task! store task-id artifacts)))))

      (catch Exception e
        (task/fail-task! (:task-store registry) task-id (.getMessage e))))))

;; =============================================================================
;; 核心处理函数（纯函数）
;; =============================================================================

(defn handle-message-send
  "处理 message/send 请求

   参数:
   - registry: A2ARegistry 实例
   - params: 请求参数
     {:message {...}
      :taskId \"可选\"
      :contextId \"可选\"}

   返回: Task map"
  [registry params]
  (let [message-json (:message params)
        task-id (:taskId params)
        context-id (:contextId params)
        message (task/json->message message-json)
        store (:task-store registry)]

    (cond
      ;; 继续指定任务
      task-id
      (let [existing-task (task/get-task store task-id)]
        (when-not existing-task
          (throw (ex-info "Task not found"
                          {:task-id task-id :code :task-not-found})))
        (when (types/terminal-state? existing-task)
          (throw (ex-info "Task already completed"
                          {:task-id task-id :code :task-already-complete})))

        ;; 添加消息并继续执行
        (task/add-message-to-task! store task-id message)
        (execute-message-handler registry task-id message)
        (task/task->json (task/get-task store task-id)))

      ;; 在上下文中查找活跃任务
      context-id
      (if-let [active-task (task/find-active-task-in-context store context-id)]
        ;; 继续活跃任务
        (let [tid (:id active-task)]
          (task/add-message-to-task! store tid message)
          (execute-message-handler registry tid message)
          (task/task->json (task/get-task store tid)))

        ;; 创建新任务
        (let [new-task (task/create-new-task! store
                         {:context-id context-id
                          :message message})]
          (execute-message-handler registry (:id new-task) message)
          (task/task->json (task/get-task store (:id new-task)))))

      ;; 创建新任务
      :else
      (let [new-task (task/create-new-task! store {:message message})]
        (execute-message-handler registry (:id new-task) message)
        (task/task->json (task/get-task store (:id new-task)))))))

(defn handle-tasks-get
  "处理 tasks/get 请求

   参数:
   - registry: A2ARegistry 实例
   - params: 请求参数 {:taskId \"...\"}

   返回: Task map"
  [registry params]
  (let [task-id (:taskId params)
        store (:task-store registry)]
    (when-not task-id
      (throw (ex-info "Missing taskId" {:code :invalid-params})))
    (let [t (task/get-task store task-id)]
      (when-not t
        (throw (ex-info "Task not found"
                        {:task-id task-id :code :task-not-found})))
      (task/task->json t))))

(defn handle-tasks-cancel
  "处理 tasks/cancel 请求

   参数:
   - registry: A2ARegistry 实例
   - params: 请求参数 {:taskId \"...\"}

   返回: Task map"
  [registry params]
  (let [task-id (:taskId params)
        store (:task-store registry)]
    (when-not task-id
      (throw (ex-info "Missing taskId" {:code :invalid-params})))
    (let [t (task/cancel-task! store task-id)]
      (task/task->json t))))

(defn handle-push-config-set
  "处理 tasks/pushNotificationConfig/set 请求

   参数:
   - registry: A2ARegistry 实例
   - params: 请求参数

   返回: 配置确认"
  [registry params]
  (let [task-id (:taskId params)
        config (:pushNotificationConfig params)
        task-store (:task-store registry)
        push-store (:push-config-store registry)]

    (when-not task-id
      (throw (ex-info "Missing taskId" {:code :invalid-params})))
    (when-not config
      (throw (ex-info "Missing pushNotificationConfig" {:code :invalid-params})))

    (when-not (task/get-task task-store task-id)
      (throw (ex-info "Task not found"
                      {:task-id task-id :code :task-not-found})))

    (let [push-config (types/create-push-config config)]
      (task/set-push-config! push-store task-id push-config)
      {:taskId task-id
       :pushNotificationConfig (update push-config :token #(when % "***"))})))

(defn handle-push-config-get
  "处理 tasks/pushNotificationConfig/get 请求

   参数:
   - registry: A2ARegistry 实例
   - params: 请求参数 {:taskId \"...\"}

   返回: 配置 map"
  [registry params]
  (let [task-id (:taskId params)
        push-store (:push-config-store registry)]

    (when-not task-id
      (throw (ex-info "Missing taskId" {:code :invalid-params})))

    (let [config (task/get-push-config push-store task-id)]
      (when-not config
        (throw (ex-info "Push notification config not found"
                        {:task-id task-id :code :push-config-not-found})))
      {:taskId task-id
       :pushNotificationConfig (update config :token #(when % "***"))})))

(defn handle-push-config-delete
  "处理 tasks/pushNotificationConfig/delete 请求

   参数:
   - registry: A2ARegistry 实例
   - params: 请求参数 {:taskId \"...\"}

   返回: 确认 map"
  [registry params]
  (let [task-id (:taskId params)
        push-store (:push-config-store registry)]

    (when-not task-id
      (throw (ex-info "Missing taskId" {:code :invalid-params})))

    (let [config (task/delete-push-config! push-store task-id)]
      (when-not config
        (throw (ex-info "Push notification config not found"
                        {:task-id task-id :code :push-config-not-found})))
      {:taskId task-id
       :deleted true})))

;; =============================================================================
;; 统一路由
;; =============================================================================

(defn route-request
  "路由 A2A 请求到对应的处理函数

   参数:
   - registry: A2ARegistry 实例
   - message: JSON-RPC 消息 {:method \"...\" :params {...} :id ...}

   返回: JSON-RPC 响应消息或 nil

   示例:
   (route-request registry {:jsonrpc \"2.0\"
                            :id 1
                            :method \"message/send\"
                            :params {:message {...}}})"
  [registry message]
  (let [method (:method message)
        params (:params message)
        id (:id message)]
    (try
      (let [result (case method
                     "message/send" (handle-message-send registry params)
                     "tasks/get" (handle-tasks-get registry params)
                     "tasks/cancel" (handle-tasks-cancel registry params)
                     "tasks/pushNotificationConfig/set" (handle-push-config-set registry params)
                     "tasks/pushNotificationConfig/get" (handle-push-config-get registry params)
                     "tasks/pushNotificationConfig/delete" (handle-push-config-delete registry params)
                     ;; 未知方法
                     (throw (ex-info "Method not found"
                                     {:method method :code :method-not-found})))]
        (when id
          (rpc/make-response id result)))
      (catch Exception e
        (when id
          (rpc/make-error-from-exception id e))))))

;; =============================================================================
;; 便捷函数
;; =============================================================================

(defn get-agent-card
  "获取 Agent Card

   参数:
   - registry: A2ARegistry 实例

   返回: AgentCard map"
  [registry]
  (card/get-card (:card-registry registry)))

(defn get-task
  "获取任务

   参数:
   - registry: A2ARegistry 实例
   - task-id: 任务 ID

   返回: Task map 或 nil"
  [registry task-id]
  (task/get-task (:task-store registry) task-id))

(defn list-tasks
  "列出所有任务

   参数:
   - registry: A2ARegistry 实例

   返回: Task 列表"
  [registry]
  (task/list-tasks (:task-store registry)))

;; =============================================================================
;; Ring Handler 适配器
;; =============================================================================

(defn- parse-json-body
  "解析请求体 JSON"
  [request]
  (let [body (:body request)
        body-str (if (string? body) body (slurp body))]
    (try
      (json/parse-string body-str true)
      (catch Exception e
        (throw (ex-info "Parse error" {:code :parse-error}))))))

(defn- json-response
  "创建 JSON 响应"
  [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

(defn ring-handler
  "创建 A2A Ring handler

   参数:
   - registry: A2ARegistry 实例

   返回: Ring handler 函数 (fn [request] response)

   使用示例:
   (def reg (create-registry {:name \"my-agent\" ...}))
   (set-message-handler reg (fn [reg task msg] \"response\"))
   (def handler (ring-handler reg))

   ;; 在 Ring 路由中使用
   (defroutes app
     (GET \"/.well-known/agent.json\" [] (agent-card-handler reg))
     (POST \"/a2a\" req (handler req)))"
  [registry]
  (fn [request]
    (try
      (let [body (parse-json-body request)
            response (if (rpc/batch-request? body)
                       (rpc/make-batch-response
                         (mapv #(route-request registry %) body))
                       (route-request registry body))]
        (json-response 200 (or response {})))
      (catch Exception e
        (let [data (ex-data e)
              code (or (:code data) :internal-error)]
          (json-response 200 (rpc/make-error nil code (.getMessage e))))))))

(defn agent-card-handler
  "创建 Agent Card Ring handler

   参数:
   - registry: A2ARegistry 实例

   返回: Ring handler 函数"
  [registry]
  (fn [_request]
    {:status 200
     :headers {"Content-Type" "application/json"
               "Cache-Control" "public, max-age=3600"}
     :body (json/generate-string (get-agent-card registry))}))

(defn combined-ring-handler
  "创建组合的 Ring handler（包含 Agent Card 和 A2A 端点）

   参数:
   - registry: A2ARegistry 实例
   - options: 选项
     {:agent-card-path \"/.well-known/agent.json\"
      :a2a-path \"/a2a\"}

   返回: Ring handler 函数"
  ([registry]
   (combined-ring-handler registry {}))
  ([registry {:keys [agent-card-path a2a-path]
              :or {agent-card-path "/.well-known/agent.json"
                   a2a-path "/a2a"}}]
   (let [card-handler (agent-card-handler registry)
         a2a-handler (ring-handler registry)]
     (fn [request]
       (let [uri (:uri request)
             method (:request-method request)]
         (cond
           (and (= method :get) (= uri agent-card-path))
           (card-handler request)

           (and (= method :post) (= uri a2a-path))
           (a2a-handler request)

           :else
           {:status 404
            :headers {"Content-Type" "text/plain"}
            :body "Not Found"}))))))

;; =============================================================================
;; CORS 中间件
;; =============================================================================

(defn wrap-cors
  "CORS 中间件

   参数:
   - handler: Ring handler
   - options: CORS 配置

   返回: 包装后的 handler"
  ([handler]
   (wrap-cors handler {}))
  ([handler {:keys [allowed-origins allowed-methods allowed-headers]
             :or {allowed-origins ["*"]
                  allowed-methods [:get :post :options]
                  allowed-headers ["Content-Type" "Authorization" "X-API-Key"]}}]
   (fn [request]
     (let [origin (get-in request [:headers "origin"] "*")
           cors-headers {"Access-Control-Allow-Origin"
                         (if (= allowed-origins ["*"]) "*" origin)
                         "Access-Control-Allow-Methods"
                         (->> allowed-methods
                              (map #(str/upper-case (name %)))
                              (str/join ", "))
                         "Access-Control-Allow-Headers"
                         (str/join ", " allowed-headers)
                         "Access-Control-Max-Age" "86400"}]
       (if (= (:request-method request) :options)
         {:status 204
          :headers cors-headers
          :body ""}
         (let [response (handler request)]
           (update response :headers merge cors-headers)))))))

;; =============================================================================
;; 认证中间件
;; =============================================================================

(defn wrap-auth
  "认证中间件

   参数:
   - handler: Ring handler
   - validate-fn: 认证验证函数 (fn [api-key] -> true/false)
   - options: 选项
     {:exclude-paths [\"/.well-known/agent.json\"]}

   返回: 包装后的 handler"
  ([handler validate-fn]
   (wrap-auth handler validate-fn {}))
  ([handler validate-fn {:keys [exclude-paths]
                          :or {exclude-paths [card/well-known-path]}}]
   (fn [request]
     (let [uri (:uri request)]
       (if (some #(= uri %) exclude-paths)
         (handler request)
         (let [auth-header (get-in request [:headers "authorization"])
               api-key-header (get-in request [:headers "x-api-key"])
               api-key (or api-key-header
                           (when (and auth-header
                                      (str/starts-with? auth-header "Bearer "))
                             (subs auth-header 7)))]
           (cond
             (nil? api-key)
             (json-response 401
               (rpc/make-error nil :missing-api-key "Missing API key"))

             (not (validate-fn api-key))
             (json-response 401
               (rpc/make-error nil :invalid-api-key "Invalid API key"))

             :else
             (handler (assoc request :api-key api-key)))))))))
