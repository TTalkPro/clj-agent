(ns im.ttalk.agent.a2a.types
  "A2A 协议类型定义

   定义 Agent-to-Agent 协议的核心数据类型：
   - Message: 消息结构（包含 parts）
   - Part: 消息部分（text/file/data）
   - Task: 任务结构
   - TaskStatus: 任务状态
   - Artifact: 任务产出物
   - AgentCard: Agent 发现卡片
   - Skill: Agent 能力描述"
  (:require [clj-uuid :as uuid]))

;; =============================================================================
;; 协议版本
;; =============================================================================

(def protocol-version "0.3.0")

;; =============================================================================
;; 任务状态定义
;; =============================================================================

(def task-states
  "所有有效的任务状态"
  #{:submitted :working :input-required :auth-required
    :completed :failed :canceled :rejected})

(def terminal-states
  "终态 - 任务一旦进入这些状态就不能再改变"
  #{:completed :failed :canceled :rejected})

(def valid-transitions
  "有效的状态转换"
  {:submitted     #{:working :canceled :rejected}
   :working       #{:completed :failed :canceled :input-required :auth-required}
   :input-required #{:working :canceled :failed}
   :auth-required  #{:working :canceled :failed}})

;; =============================================================================
;; ID 生成
;; =============================================================================

(defn generate-id
  "生成唯一 ID

   参数:
   - prefix: ID 前缀 (如 \"task\", \"msg\", \"artifact\")

   返回: 格式化的 ID 字符串"
  [prefix]
  (str prefix "-" (uuid/v4)))

(defn generate-task-id [] (generate-id "task"))
(defn generate-message-id [] (generate-id "msg"))
(defn generate-artifact-id [] (generate-id "artifact"))
(defn generate-context-id [] (generate-id "ctx"))

;; =============================================================================
;; Part 类型
;; =============================================================================

(defn text-part
  "创建文本 part

   参数:
   - text: 文本内容

   返回: text part map"
  [text]
  {:kind :text
   :text text})

(defn file-part
  "创建文件 part

   参数:
   - opts: 文件选项
     {:name \"filename\"
      :mime-type \"text/plain\"
      :bytes \"content\" 或 :uri \"https://...\"}

   返回: file part map"
  [{:keys [name mime-type bytes uri]}]
  {:kind :file
   :file (cond-> {:name name}
           mime-type (assoc :mimeType mime-type)
           bytes (assoc :bytes bytes)
           uri (assoc :uri uri))})

(defn data-part
  "创建数据 part

   参数:
   - data: 任意数据结构

   返回: data part map"
  [data]
  {:kind :data
   :data data})

(defn part-kind
  "获取 part 类型

   参数:
   - part: part map

   返回: :text, :file, 或 :data"
  [part]
  (:kind part))

(defn text-part? [part] (= :text (part-kind part)))
(defn file-part? [part] (= :file (part-kind part)))
(defn data-part? [part] (= :data (part-kind part)))

;; =============================================================================
;; Message 类型
;; =============================================================================

(defn create-message
  "创建消息

   参数:
   - opts: 消息选项
     {:role :user 或 :agent
      :parts [part1 part2 ...]
      :metadata {...}}

   返回: Message map"
  [{:keys [role parts metadata message-id]
    :or {role :user
         metadata {}}}]
  (cond-> {:role role
           :parts (vec parts)}
    message-id (assoc :messageId message-id)
    (seq metadata) (assoc :metadata metadata)))

(defn text-message
  "创建简单文本消息

   参数:
   - text: 文本内容
   - role: 角色 (:user 或 :agent)，默认 :user

   返回: Message map"
  ([text]
   (text-message text :user))
  ([text role]
   (create-message {:role role
                    :parts [(text-part text)]})))

(defn message-role [msg] (:role msg))
(defn message-parts [msg] (:parts msg))

(defn message-text
  "提取消息中的所有文本内容

   参数:
   - msg: Message map

   返回: 合并的文本字符串"
  [msg]
  (->> (:parts msg)
       (filter text-part?)
       (map :text)
       (clojure.string/join "\n")))

;; =============================================================================
;; TaskStatus 类型
;; =============================================================================

(defn create-status
  "创建任务状态

   参数:
   - state: 状态关键字
   - opts: 可选配置
     {:message Message
      :timestamp 毫秒时间戳}

   返回: TaskStatus map"
  ([state]
   (create-status state {}))
  ([state {:keys [message timestamp]
           :or {timestamp (System/currentTimeMillis)}}]
   (cond-> {:state state
            :timestamp timestamp}
     message (assoc :message message))))

;; =============================================================================
;; Artifact 类型
;; =============================================================================

(defn create-artifact
  "创建产出物

   参数:
   - opts: 产出物选项
     {:name \"artifact-name\"
      :description \"描述\"
      :parts [part1 part2 ...]
      :metadata {...}}

   返回: Artifact map"
  [{:keys [artifact-id name description parts metadata]
    :or {artifact-id (generate-artifact-id)
         name "output"
         parts []
         metadata {}}}]
  (cond-> {:artifactId artifact-id
           :name name
           :parts (vec parts)}
    description (assoc :description description)
    (seq metadata) (assoc :metadata metadata)))

(defn text-artifact
  "创建文本产出物

   参数:
   - text: 文本内容
   - name: 产出物名称

   返回: Artifact map"
  ([text]
   (text-artifact text "output"))
  ([text name]
   (create-artifact {:name name
                     :parts [(text-part text)]})))

;; =============================================================================
;; Task 类型
;; =============================================================================

(defn create-task
  "创建任务

   参数:
   - opts: 任务选项
     {:id \"task-id\"
      :context-id \"context-id\"
      :status TaskStatus
      :messages [msg1 msg2 ...]
      :artifacts [artifact1 ...]
      :metadata {...}}

   返回: Task map"
  [{:keys [id context-id status messages artifacts history metadata]
    :or {id (generate-task-id)
         status (create-status :submitted)
         messages []
         artifacts []
         history []
         metadata {}}}]
  (let [now (System/currentTimeMillis)]
    (cond-> {:id id
             :status status
             :messages (vec messages)
             :artifacts (vec artifacts)
             :history (if (empty? history)
                        [{:state (:state status)
                          :timestamp (:timestamp status)}]
                        (vec history))
             :createdAt now
             :updatedAt now}
      context-id (assoc :contextId context-id)
      (seq metadata) (assoc :metadata metadata))))

(defn task-id [task] (:id task))
(defn task-state [task] (get-in task [:status :state]))
(defn task-messages [task] (:messages task))
(defn task-artifacts [task] (:artifacts task))

(defn terminal-state?
  "检查任务是否处于终态

   参数:
   - task: Task map

   返回: true/false"
  [task]
  (contains? terminal-states (task-state task)))

(defn valid-transition?
  "检查状态转换是否有效

   参数:
   - from-state: 当前状态
   - to-state: 目标状态

   返回: true/false"
  [from-state to-state]
  (contains? (get valid-transitions from-state #{}) to-state))

(defn transition-task
  "转换任务状态

   参数:
   - task: Task map
   - new-state: 新状态
   - opts: 可选配置 {:message Message}

   返回: 更新后的 Task map 或抛出异常"
  [task new-state & [{:keys [message]}]]
  (let [current-state (task-state task)]
    (when (terminal-state? task)
      (throw (ex-info "Cannot transition from terminal state"
                      {:current-state current-state
                       :target-state new-state
                       :code :invalid-transition})))
    (when-not (valid-transition? current-state new-state)
      (throw (ex-info "Invalid state transition"
                      {:current-state current-state
                       :target-state new-state
                       :code :invalid-transition})))
    (let [now (System/currentTimeMillis)
          new-status (create-status new-state {:message message :timestamp now})]
      (-> task
          (assoc :status new-status)
          (update :history conj {:state new-state :timestamp now})
          (assoc :updatedAt now)))))

(defn add-message
  "添加消息到任务

   参数:
   - task: Task map
   - message: Message map

   返回: 更新后的 Task map"
  [task message]
  (-> task
      (update :messages conj message)
      (assoc :updatedAt (System/currentTimeMillis))))

(defn add-artifact
  "添加产出物到任务

   参数:
   - task: Task map
   - artifact: Artifact map

   返回: 更新后的 Task map"
  [task artifact]
  (-> task
      (update :artifacts conj artifact)
      (assoc :updatedAt (System/currentTimeMillis))))

;; =============================================================================
;; Skill 类型
;; =============================================================================

(defn create-skill
  "创建技能描述

   参数:
   - opts: 技能选项
     {:id \"skill-id\"
      :name \"Skill Name\"
      :description \"描述\"
      :tags [\"tag1\" \"tag2\"]
      :examples [\"example1\" ...]
      :input-modes [\"text/plain\"]
      :output-modes [\"text/plain\"]}

   返回: Skill map"
  [{:keys [id name description tags examples input-modes output-modes]
    :or {tags []
         examples []
         input-modes ["text/plain"]
         output-modes ["text/plain"]}}]
  {:id id
   :name name
   :description description
   :tags (vec tags)
   :examples (vec examples)
   :inputModes (vec input-modes)
   :outputModes (vec output-modes)})

;; =============================================================================
;; AgentCard 类型
;; =============================================================================

(defn create-agent-card
  "创建 Agent Card

   参数:
   - opts: Agent Card 选项
     {:name \"agent-name\"
      :description \"Agent 描述\"
      :url \"https://agent.example.com\"
      :version \"1.0.0\"
      :provider {:name \"Org\" :url \"https://...\"}
      :capabilities {:streaming true ...}
      :skills [skill1 skill2 ...]
      :default-input-modes [\"text/plain\"]
      :default-output-modes [\"text/plain\"]}

   返回: AgentCard map"
  [{:keys [name description url version provider capabilities skills
           default-input-modes default-output-modes]
    :or {version "1.0.0"
         capabilities {}
         skills []
         default-input-modes ["text/plain" "application/json"]
         default-output-modes ["text/plain" "application/json"]}}]
  (cond-> {:name name
           :description description
           :url url
           :version version
           :protocolVersion protocol-version
           :capabilities (merge {:streaming false
                                 :pushNotifications false
                                 :stateTransitionHistory true}
                                capabilities)
           :defaultInputModes (vec default-input-modes)
           :defaultOutputModes (vec default-output-modes)
           :skills (vec skills)}
    provider (assoc :provider provider)))

;; =============================================================================
;; 类型转换 (内部 <-> JSON)
;; =============================================================================

(def state-to-json
  "状态关键字到 JSON 字符串的映射"
  {:submitted "submitted"
   :working "working"
   :input-required "input-required"
   :auth-required "auth-required"
   :completed "completed"
   :failed "failed"
   :canceled "canceled"
   :rejected "rejected"})

(def json-to-state
  "JSON 字符串到状态关键字的映射（白名单）"
  {"submitted" :submitted
   "working" :working
   "input-required" :input-required
   "auth-required" :auth-required
   "completed" :completed
   "failed" :failed
   "canceled" :canceled
   "rejected" :rejected})

(def role-to-json
  {:user "user"
   :agent "agent"})

(def json-to-role
  {"user" :user
   "agent" :agent})

(def kind-to-json
  {:text "text"
   :file "file"
   :data "data"})

(def json-to-kind
  {"text" :text
   "file" :file
   "data" :data})

(defn parse-state
  "安全解析状态字符串

   参数:
   - s: 状态字符串

   返回: 状态关键字或 nil"
  [s]
  (get json-to-state s))

(defn parse-role
  "安全解析角色字符串

   参数:
   - s: 角色字符串

   返回: 角色关键字或 nil"
  [s]
  (get json-to-role s))

(defn parse-kind
  "安全解析 part 类型字符串

   参数:
   - s: 类型字符串

   返回: 类型关键字或 nil"
  [s]
  (get json-to-kind s))

;; =============================================================================
;; Push Notification 事件
;; =============================================================================

(def valid-push-events
  "有效的推送事件（白名单）"
  #{"submitted" "working" "input-required" "auth-required"
    "completed" "failed" "canceled" "rejected" "all"})

(defn valid-push-event?
  "检查推送事件是否有效

   参数:
   - event: 事件字符串

   返回: true/false"
  [event]
  (contains? valid-push-events event))

;; =============================================================================
;; PushNotificationConfig 类型
;; =============================================================================

(defn create-push-config
  "创建推送通知配置

   参数:
   - opts: 配置选项
     {:url \"https://webhook.example.com\"
      :token \"bearer-token\"
      :events [\"completed\" \"failed\"]
      :retry-count 3}

   返回: PushNotificationConfig map"
  [{:keys [url token events retry-count]
    :or {events ["all"]
         retry-count 3}}]
  (cond-> {:url url
           :events (->> events
                        (filter valid-push-event?)
                        vec)
           :retryCount retry-count}
    token (assoc :token token)))
