(ns im.ttalk.agent.a2a.task
  "A2A 任务管理模块

   提供任务的创建、存储、状态转换和查询功能。
   使用 atom 存储任务状态，支持并发访问。"
  (:require [im.ttalk.agent.a2a.types :as types]))

;; =============================================================================
;; Task Store
;; =============================================================================

(defrecord TaskStore [tasks-atom contexts-atom])

(defn create-task-store
  "创建任务存储

   返回: TaskStore 实例"
  []
  (->TaskStore (atom {})      ;; task-id -> task
               (atom {})))    ;; context-id -> #{task-id}

(defn get-task
  "获取任务

   参数:
   - store: TaskStore 实例
   - task-id: 任务 ID

   返回: Task map 或 nil"
  [store task-id]
  (get @(:tasks-atom store) task-id))

(defn get-tasks-by-context
  "获取上下文中的所有任务

   参数:
   - store: TaskStore 实例
   - context-id: 上下文 ID

   返回: Task 列表"
  [store context-id]
  (let [task-ids (get @(:contexts-atom store) context-id #{})
        tasks @(:tasks-atom store)]
    (mapv #(get tasks %) task-ids)))

(defn find-active-task-in-context
  "在上下文中查找活跃的任务（input-required 状态）

   参数:
   - store: TaskStore 实例
   - context-id: 上下文 ID

   返回: Task map 或 nil"
  [store context-id]
  (->> (get-tasks-by-context store context-id)
       (filter #(= :input-required (types/task-state %)))
       first))

(defn store-task!
  "存储任务

   参数:
   - store: TaskStore 实例
   - task: Task map

   返回: Task map"
  [store task]
  (let [task-id (:id task)
        context-id (:contextId task)]
    (swap! (:tasks-atom store) assoc task-id task)
    (when context-id
      (swap! (:contexts-atom store) update context-id (fnil conj #{}) task-id))
    task))

(defn update-task!
  "更新任务

   参数:
   - store: TaskStore 实例
   - task-id: 任务 ID
   - update-fn: 更新函数 (fn [task] -> new-task)

   返回: 更新后的 Task map 或 nil"
  [store task-id update-fn]
  (let [result (atom nil)]
    (swap! (:tasks-atom store)
           (fn [tasks]
             (if-let [task (get tasks task-id)]
               (let [new-task (update-fn task)]
                 (reset! result new-task)
                 (assoc tasks task-id new-task))
               tasks)))
    @result))

(defn delete-task!
  "删除任务

   参数:
   - store: TaskStore 实例
   - task-id: 任务 ID

   返回: 被删除的 Task map 或 nil"
  [store task-id]
  (let [task (get-task store task-id)]
    (when task
      (swap! (:tasks-atom store) dissoc task-id)
      (when-let [context-id (:contextId task)]
        (swap! (:contexts-atom store) update context-id disj task-id))
      task)))

(defn list-tasks
  "列出所有任务

   参数:
   - store: TaskStore 实例

   返回: Task 列表"
  [store]
  (vals @(:tasks-atom store)))

(defn count-tasks
  "统计任务数量

   参数:
   - store: TaskStore 实例

   返回: 任务数量"
  [store]
  (count @(:tasks-atom store)))

(defn clear-tasks!
  "清除所有任务

   参数:
   - store: TaskStore 实例"
  [store]
  (reset! (:tasks-atom store) {})
  (reset! (:contexts-atom store) {}))

;; =============================================================================
;; Task 操作
;; =============================================================================

(defn create-new-task!
  "创建新任务

   参数:
   - store: TaskStore 实例
   - opts: 任务选项
     {:context-id \"可选上下文 ID\"
      :message 初始消息
      :metadata {...}}

   返回: 新创建的 Task map"
  [store {:keys [context-id message metadata]}]
  (let [task (types/create-task
               (cond-> {}
                 context-id (assoc :context-id context-id)
                 message (assoc :messages [message])
                 metadata (assoc :metadata metadata)))]
    (store-task! store task)))

(defn transition-task!
  "转换任务状态

   参数:
   - store: TaskStore 实例
   - task-id: 任务 ID
   - new-state: 新状态
   - opts: 可选配置 {:message Message}

   返回: 更新后的 Task map

   抛出: 异常（如果任务不存在或状态转换无效）"
  [store task-id new-state & [opts]]
  (let [task (get-task store task-id)]
    (when-not task
      (throw (ex-info "Task not found" {:task-id task-id :code :task-not-found})))
    (let [new-task (types/transition-task task new-state opts)]
      (update-task! store task-id (constantly new-task)))))

(defn add-message-to-task!
  "添加消息到任务

   参数:
   - store: TaskStore 实例
   - task-id: 任务 ID
   - message: Message map

   返回: 更新后的 Task map"
  [store task-id message]
  (update-task! store task-id #(types/add-message % message)))

(defn add-artifact-to-task!
  "添加产出物到任务

   参数:
   - store: TaskStore 实例
   - task-id: 任务 ID
   - artifact: Artifact map

   返回: 更新后的 Task map"
  [store task-id artifact]
  (update-task! store task-id #(types/add-artifact % artifact)))

(defn complete-task!
  "完成任务

   参数:
   - store: TaskStore 实例
   - task-id: 任务 ID
   - artifacts: 产出物列表（可选）

   返回: 更新后的 Task map"
  [store task-id & [artifacts]]
  (update-task! store task-id
    (fn [task]
      (let [task-with-artifacts (reduce types/add-artifact task (or artifacts []))]
        (types/transition-task task-with-artifacts :completed)))))

(defn fail-task!
  "标记任务失败

   参数:
   - store: TaskStore 实例
   - task-id: 任务 ID
   - error-message: 错误消息

   返回: 更新后的 Task map"
  [store task-id error-message]
  (transition-task! store task-id :failed
    {:message (types/text-message error-message :agent)}))

(defn cancel-task!
  "取消任务

   参数:
   - store: TaskStore 实例
   - task-id: 任务 ID

   返回: 更新后的 Task map"
  [store task-id]
  (let [task (get-task store task-id)]
    (when-not task
      (throw (ex-info "Task not found" {:task-id task-id :code :task-not-found})))
    (when (types/terminal-state? task)
      (throw (ex-info "Cannot cancel task in terminal state"
                      {:task-id task-id
                       :state (types/task-state task)
                       :code :invalid-transition})))
    (update-task! store task-id
      (fn [t]
        (let [now (System/currentTimeMillis)]
          (-> t
              (assoc :status {:state :canceled :timestamp now})
              (update :history conj {:state :canceled :timestamp now})
              (assoc :updatedAt now)))))))

(defn request-input!
  "请求用户输入

   参数:
   - store: TaskStore 实例
   - task-id: 任务 ID
   - prompt-message: 提示消息

   返回: 更新后的 Task map"
  [store task-id prompt-message]
  (transition-task! store task-id :input-required
    {:message prompt-message}))

;; =============================================================================
;; Push Notification Config
;; =============================================================================

(defrecord PushConfigStore [configs-atom])

(defn create-push-config-store
  "创建推送配置存储

   返回: PushConfigStore 实例"
  []
  (->PushConfigStore (atom {})))  ;; task-id -> push-config

(defn set-push-config!
  "设置推送配置

   参数:
   - store: PushConfigStore 实例
   - task-id: 任务 ID
   - config: 推送配置 map

   返回: 配置 map"
  [store task-id config]
  (swap! (:configs-atom store) assoc task-id config)
  config)

(defn get-push-config
  "获取推送配置

   参数:
   - store: PushConfigStore 实例
   - task-id: 任务 ID

   返回: 配置 map 或 nil"
  [store task-id]
  (get @(:configs-atom store) task-id))

(defn delete-push-config!
  "删除推送配置

   参数:
   - store: PushConfigStore 实例
   - task-id: 任务 ID

   返回: 被删除的配置 map 或 nil"
  [store task-id]
  (let [config (get-push-config store task-id)]
    (swap! (:configs-atom store) dissoc task-id)
    config))

;; =============================================================================
;; Task 序列化（用于 JSON 响应）
;; =============================================================================

(defn task->json
  "将 Task 转换为 JSON 友好格式

   参数:
   - task: Task map

   返回: JSON 友好的 map"
  [task]
  (-> task
      (update-in [:status :state] types/state-to-json)
      (update :history
              (fn [history]
                (mapv #(update % :state types/state-to-json) history)))))

(defn message->json
  "将 Message 转换为 JSON 友好格式

   参数:
   - message: Message map

   返回: JSON 友好的 map"
  [message]
  (-> message
      (update :role types/role-to-json)
      (update :parts
              (fn [parts]
                (mapv #(update % :kind types/kind-to-json) parts)))))

(defn json->message
  "将 JSON 格式转换为 Message

   参数:
   - json-msg: JSON 消息 map

   返回: Message map"
  [json-msg]
  (let [role (types/parse-role (or (:role json-msg) "user"))
        parts (mapv (fn [part]
                      (let [kind (types/parse-kind (:kind part))]
                        (assoc part :kind kind)))
                    (or (:parts json-msg)
                        ;; 简化格式支持
                        (when-let [text (:text json-msg)]
                          [{:kind :text :text text}])
                        []))]
    (types/create-message {:role role
                           :parts parts
                           :metadata (:metadata json-msg)
                           :message-id (:messageId json-msg)})))
