(ns im.ttalk.agent.a2a.client
  "A2A 客户端模块

   提供与远程 A2A Agent 通信的功能：
   - Agent 发现
   - 发送消息
   - 任务管理
   - 流式响应处理"
  (:require [im.ttalk.agent.a2a.types :as types]
            [im.ttalk.agent.a2a.json-rpc :as rpc]
            [im.ttalk.agent.a2a.card :as card]
            [im.ttalk.agent.a2a.task :as task]
            [org.httpkit.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader]))

;; 前向声明
(declare discover!)

;; =============================================================================
;; HTTP 请求辅助
;; =============================================================================

(defn- make-headers
  "创建请求头

   参数:
   - api-key: API Key（可选）

   返回: 请求头 map"
  [api-key]
  (cond-> {"Content-Type" "application/json"
           "Accept" "application/json"}
    api-key (assoc "Authorization" (str "Bearer " api-key))))

(defn- post-json
  "发送 JSON POST 请求

   参数:
   - url: 请求 URL
   - body: 请求体 map
   - options: 选项 {:api-key \"...\" :timeout 30000}

   返回: 响应 map"
  [url body {:keys [api-key timeout]
             :or {timeout 30000}}]
  (let [response @(http/post url
                    {:headers (make-headers api-key)
                     :body (json/generate-string body)
                     :timeout timeout})]
    (if (:error response)
      (throw (ex-info "HTTP request failed"
                      {:url url :error (:error response)}))
      (let [status (:status response)
            body-str (:body response)]
        (if (and (>= status 200) (< status 300))
          (json/parse-string body-str true)
          (throw (ex-info "HTTP error"
                          {:url url
                           :status status
                           :body body-str})))))))

(defn- get-json
  "发送 GET 请求获取 JSON

   参数:
   - url: 请求 URL
   - options: 选项 {:api-key \"...\" :timeout 30000}

   返回: 响应 map"
  [url {:keys [api-key timeout]
        :or {timeout 30000}}]
  (let [response @(http/get url
                    {:headers (make-headers api-key)
                     :timeout timeout})]
    (if (:error response)
      (throw (ex-info "HTTP request failed"
                      {:url url :error (:error response)}))
      (let [status (:status response)
            body-str (:body response)]
        (if (and (>= status 200) (< status 300))
          (json/parse-string body-str true)
          (throw (ex-info "HTTP error"
                          {:url url
                           :status status
                           :body body-str})))))))

;; =============================================================================
;; A2A Client
;; =============================================================================

(defrecord A2AClient [base-url api-key options agent-card])

(defn create-client
  "创建 A2A 客户端

   参数:
   - base-url: Agent 基础 URL（如 \"http://localhost:8080\"）
   - options: 选项
     {:api-key \"...\"
      :timeout 30000
      :auto-discover true}

   返回: A2AClient 实例"
  ([base-url]
   (create-client base-url {}))
  ([base-url {:keys [api-key timeout auto-discover]
              :or {timeout 30000
                   auto-discover true}
              :as opts}]
   (let [client (->A2AClient base-url api-key opts (atom nil))]
     (when auto-discover
       (try
         (discover! client)
         (catch Exception _
           nil)))
     client)))

;; =============================================================================
;; Agent 发现
;; =============================================================================

(defn discover!
  "发现远程 Agent 并获取 Agent Card

   参数:
   - client: A2AClient 实例

   返回: AgentCard map

   示例:
   (def client (create-client \"http://localhost:8080\"))
   (discover! client)"
  [client]
  (let [url (str (:base-url client) card/well-known-path)
        card (get-json url {:api-key (:api-key client)
                            :timeout (get-in client [:options :timeout] 30000)})]
    (reset! (:agent-card client) card)
    card))

(defn get-agent-card
  "获取已缓存的 Agent Card

   参数:
   - client: A2AClient 实例

   返回: AgentCard map 或 nil"
  [client]
  @(:agent-card client))

(defn refresh-agent-card!
  "刷新 Agent Card

   参数:
   - client: A2AClient 实例

   返回: 更新后的 AgentCard map"
  [client]
  (discover! client))

;; =============================================================================
;; JSON-RPC 请求
;; =============================================================================

(defn- send-rpc-request
  "发送 JSON-RPC 请求

   参数:
   - client: A2AClient 实例
   - method: 方法名
   - params: 参数 map

   返回: 响应结果"
  [client method params]
  (let [url (str (:base-url client) "/a2a")
        request (rpc/make-request method params)
        response (post-json url request
                   {:api-key (:api-key client)
                    :timeout (get-in client [:options :timeout] 30000)})]
    (cond
      (:error response)
      (throw (ex-info (get-in response [:error :message] "Unknown error")
                      {:code (get-in response [:error :code])
                       :data (get-in response [:error :data])}))

      (:result response)
      (:result response)

      :else
      response)))

;; =============================================================================
;; 消息发送
;; =============================================================================

(defn send-message
  "发送消息到远程 Agent

   参数:
   - client: A2AClient 实例
   - message: Message map 或文本字符串
   - options: 选项
     {:task-id \"可选任务 ID\"
      :context-id \"可选上下文 ID\"}

   返回: Task map

   示例:
   ;; 发送简单文本
   (send-message client \"Hello, Agent!\")

   ;; 发送到指定任务
   (send-message client \"Continue\" {:task-id \"task-xxx\"})

   ;; 发送到上下文
   (send-message client \"Follow up\" {:context-id \"ctx-xxx\"})"
  ([client message]
   (send-message client message {}))
  ([client message {:keys [task-id context-id]}]
   (let [msg (if (string? message)
               (types/text-message message)
               message)
         params (cond-> {:message (task/message->json msg)}
                  task-id (assoc :taskId task-id)
                  context-id (assoc :contextId context-id))]
     (send-rpc-request client "message/send" params))))

(defn send-text
  "发送简单文本消息

   参数:
   - client: A2AClient 实例
   - text: 文本内容

   返回: Task map"
  [client text]
  (send-message client text))

;; =============================================================================
;; 任务管理
;; =============================================================================

(defn get-task
  "获取任务状态

   参数:
   - client: A2AClient 实例
   - task-id: 任务 ID

   返回: Task map"
  [client task-id]
  (send-rpc-request client "tasks/get" {:taskId task-id}))

(defn cancel-task
  "取消任务

   参数:
   - client: A2AClient 实例
   - task-id: 任务 ID

   返回: Task map"
  [client task-id]
  (send-rpc-request client "tasks/cancel" {:taskId task-id}))

(defn wait-for-completion
  "等待任务完成

   参数:
   - client: A2AClient 实例
   - task-id: 任务 ID
   - options: 选项
     {:poll-interval 500
      :timeout 60000}

   返回: 最终的 Task map"
  ([client task-id]
   (wait-for-completion client task-id {}))
  ([client task-id {:keys [poll-interval timeout]
                    :or {poll-interval 500
                         timeout 60000}}]
   (let [start-time (System/currentTimeMillis)]
     (loop []
       (let [task (get-task client task-id)
             state (keyword (get-in task [:status :state]))]
         (if (contains? types/terminal-states state)
           task
           (if (> (- (System/currentTimeMillis) start-time) timeout)
             (throw (ex-info "Timeout waiting for task completion"
                             {:task-id task-id}))
             (do
               (Thread/sleep poll-interval)
               (recur)))))))))

;; =============================================================================
;; Push Notification Config
;; =============================================================================

(defn set-push-config
  "设置推送通知配置

   参数:
   - client: A2AClient 实例
   - task-id: 任务 ID
   - config: 推送配置
     {:url \"https://webhook.example.com\"
      :token \"bearer-token\"
      :events [\"completed\" \"failed\"]}

   返回: 配置确认"
  [client task-id config]
  (send-rpc-request client "tasks/pushNotificationConfig/set"
    {:taskId task-id
     :pushNotificationConfig config}))

(defn get-push-config
  "获取推送通知配置

   参数:
   - client: A2AClient 实例
   - task-id: 任务 ID

   返回: 配置 map"
  [client task-id]
  (send-rpc-request client "tasks/pushNotificationConfig/get"
    {:taskId task-id}))

(defn delete-push-config
  "删除推送通知配置

   参数:
   - client: A2AClient 实例
   - task-id: 任务 ID

   返回: 删除确认"
  [client task-id]
  (send-rpc-request client "tasks/pushNotificationConfig/delete"
    {:taskId task-id}))

;; =============================================================================
;; 流式响应
;; =============================================================================

(defn- parse-sse-event
  "解析 SSE 事件

   参数:
   - lines: 事件行列表

   返回: {:event \"...\" :data \"...\"}"
  [lines]
  (reduce
    (fn [event line]
      (cond
        (str/starts-with? line "event:")
        (assoc event :event (str/trim (subs line 6)))

        (str/starts-with? line "data:")
        (update event :data str (str/trim (subs line 5)))

        :else event))
    {:event "message" :data ""}
    lines))

(defn send-message-stream
  "发送消息并流式接收响应

   参数:
   - client: A2AClient 实例
   - message: Message map 或文本字符串
   - callback: 回调函数 (fn [event-type data] ...)
   - options: 选项
     {:task-id \"...\"}

   返回: 最终的 Task map

   示例:
   (send-message-stream client \"Hello\"
     (fn [event data]
       (println \"Event:\" event \"Data:\" data)))"
  ([client message callback]
   (send-message-stream client message callback {}))
  ([client message callback {:keys [task-id context-id]}]
   (let [msg (if (string? message)
               (types/text-message message)
               message)
         params (cond-> {:message (task/message->json msg)}
                  task-id (assoc :taskId task-id)
                  context-id (assoc :contextId context-id))
         url (str (:base-url client) "/a2a/stream")
         request (rpc/make-request "message/send" params)

         response @(http/post url
                     {:headers (merge (make-headers (:api-key client))
                                      {"Accept" "text/event-stream"})
                      :body (json/generate-string request)
                      :as :stream
                      :timeout 0})
         final-result (atom nil)]

     (when (:error response)
       (throw (ex-info "Stream request failed"
                       {:error (:error response)})))

     ;; 处理 SSE 流
     (let [reader (BufferedReader. (InputStreamReader. (:body response)))]
       (try
         (loop [lines []]
           (if-let [line (.readLine reader)]
             (if (str/blank? line)
               (when (seq lines)
                 (let [event (parse-sse-event lines)
                       event-type (:event event)
                       data (when (seq (:data event))
                              (json/parse-string (:data event) true))]
                   (callback event-type data)
                   (when (= event-type "task_status")
                     (reset! final-result data))
                   (when (= event-type "done")
                     (reset! final-result data))
                   (recur [])))
               (recur (conj lines line)))
             nil))
         (finally
           (.close reader))))

     @final-result)))

;; =============================================================================
;; 便捷函数
;; =============================================================================

(defn create-text-message
  "创建文本消息

   参数:
   - text: 文本内容
   - role: 角色 (:user 或 :agent)

   返回: Message map"
  ([text]
   (types/text-message text))
  ([text role]
   (types/text-message text role)))

(defn quick-send
  "快速发送消息并等待结果

   参数:
   - base-url: Agent URL
   - text: 消息文本
   - options: 选项

   返回: Task map（完成状态）

   示例:
   (quick-send \"http://localhost:8080\" \"What is 2+2?\")"
  ([base-url text]
   (quick-send base-url text {}))
  ([base-url text options]
   (let [client (create-client base-url options)
         task (send-text client text)]
     (wait-for-completion client (:id task) options))))

(defn get-task-result
  "从任务中提取文本结果

   参数:
   - task: Task map

   返回: 结果文本字符串"
  [task]
  (->> (:artifacts task)
       (mapcat :parts)
       (filter #(= "text" (:kind %)))
       (map :text)
       (str/join "\n")))
