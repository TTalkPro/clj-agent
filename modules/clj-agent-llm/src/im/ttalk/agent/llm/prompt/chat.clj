(ns im.ttalk.agent.llm.prompt.chat
  "聊天提示词模板实现

   提供多轮对话格式的提示词模板：
   - ChatPromptTemplate: 多轮对话模板
   - MessagePromptTemplate: 单条消息模板

   使用示例：

   ;; 创建聊天模板
   (def chat-template
     (create-chat-template
       [(system-message \"你是一个翻译助手\")
        (human-message \"{input}\")]))

   ;; 格式化为消息列表
   (format-messages chat-template {:input \"Hello\"})"
  (:require [im.ttalk.agent.llm.prompt.protocol :as proto]
            [im.ttalk.agent.llm.prompt.template :as tpl]
            [clojure.string :as str]))

;; ============================================================
;; 消息模板
;; ============================================================

(defrecord MessagePromptTemplate [role template]
  proto/IPromptTemplate

  (format-prompt [_ variables]
    (proto/format-prompt template variables))

  (get-input-variables [_]
    (proto/get-input-variables template)))

(defn- create-message-template
  "创建消息模板

   参数:
   - role: 消息角色 (\"system\", \"user\", \"assistant\")
   - content: 内容（字符串或 IPromptTemplate）

   返回: MessagePromptTemplate 实例"
  [role content]
  (let [template (if (string? content)
                   (tpl/create-prompt-template content)
                   content)]
    (->MessagePromptTemplate role template)))

;; ============================================================
;; 消息构建器
;; ============================================================

(defn system-message
  "创建系统消息模板

   参数:
   - content: 消息内容（支持变量）

   返回: MessagePromptTemplate

   示例:
   (system-message \"你是一个{role}助手\")"
  [content]
  (create-message-template "system" content))

(defn human-message
  "创建用户消息模板

   参数:
   - content: 消息内容（支持变量）

   返回: MessagePromptTemplate

   示例:
   (human-message \"{input}\")"
  [content]
  (create-message-template "user" content))

(defn ai-message
  "创建 AI 消息模板

   参数:
   - content: 消息内容（支持变量）

   返回: MessagePromptTemplate

   示例:
   (ai-message \"好的，我来帮你{task}\")"
  [content]
  (create-message-template "assistant" content))

;; ============================================================
;; ChatPromptTemplate 实现
;; ============================================================

(defn- format-single-message
  "格式化单条消息

   参数:
   - msg-template: MessagePromptTemplate
   - variables: 变量 map

   返回: 消息 map {:role \"...\" :content \"...\"}"
  [msg-template variables]
  {:role (:role msg-template)
   :content (proto/format-prompt msg-template variables)})

(defn- collect-all-variables
  "收集所有消息模板的变量

   参数:
   - message-templates: 消息模板列表

   返回: 变量名列表"
  [message-templates]
  (->> message-templates
       (mapcat proto/get-input-variables)
       distinct
       vec))

(defrecord ChatPromptTemplate [message-templates input-variables]
  proto/IPromptTemplate

  (format-prompt [_ variables]
    ;; 将所有消息格式化为单个字符串（用于非聊天场景）
    (->> message-templates
         (map #(format-single-message % variables))
         (map (fn [{:keys [role content]}]
                (str "[" role "]\n" content)))
         (str/join "\n\n")))

  (get-input-variables [_]
    input-variables)

  proto/IMessageTemplate

  (format-messages [_ variables]
    (mapv #(format-single-message % variables) message-templates)))

(defn create-chat-template
  "创建聊天提示词模板

   参数:
   - message-templates: 消息模板列表（使用 system-message, human-message, ai-message 创建）
   - opts: 可选配置
     - :input-variables 明确指定变量列表

   返回: ChatPromptTemplate 实例

   示例:
   (def template
     (create-chat-template
       [(system-message \"你是一个{role}\")
        (human-message \"{input}\")]))"
  ([message-templates]
   (create-chat-template message-templates {}))
  ([message-templates opts]
   (let [vars (or (:input-variables opts)
                  (collect-all-variables message-templates))]
     (->ChatPromptTemplate message-templates vars))))

;; ============================================================
;; 从消息列表创建
;; ============================================================

(defn from-messages
  "从消息描述创建聊天模板

   参数:
   - messages: 消息描述列表
     每个元素可以是：
     - [role content] 形式的向量
     - {:role \"...\" :content \"...\"} 形式的 map

   返回: ChatPromptTemplate 实例

   示例:
   (from-messages
     [[\"system\" \"你是一个助手\"]
      [\"user\" \"{input}\"]])"
  [messages]
  (let [templates (mapv (fn [msg]
                          (cond
                            (vector? msg)
                            (create-message-template (first msg) (second msg))

                            (map? msg)
                            (create-message-template (:role msg) (:content msg))

                            :else
                            (throw (ex-info "无效的消息格式" {:message msg}))))
                        messages)]
    (create-chat-template templates)))

;; ============================================================
;; 模板组合
;; ============================================================

(defn append-messages
  "向聊天模板追加消息

   参数:
   - chat-template: ChatPromptTemplate
   - new-messages: 新消息模板列表

   返回: 新的 ChatPromptTemplate"
  [chat-template new-messages]
  (let [all-templates (concat (:message-templates chat-template) new-messages)
        all-vars (collect-all-variables all-templates)]
    (->ChatPromptTemplate (vec all-templates) all-vars)))

(defn prepend-system
  "在聊天模板前添加系统消息

   参数:
   - chat-template: ChatPromptTemplate
   - system-content: 系统消息内容

   返回: 新的 ChatPromptTemplate"
  [chat-template system-content]
  (let [system-msg (system-message system-content)
        all-templates (cons system-msg (:message-templates chat-template))
        all-vars (collect-all-variables all-templates)]
    (->ChatPromptTemplate (vec all-templates) all-vars)))

;; ============================================================
;; 便捷格式化函数
;; ============================================================

(defn format-messages
  "格式化聊天模板为消息列表

   参数:
   - chat-template: ChatPromptTemplate 或 IMessageTemplate
   - variables: 变量 map

   返回: 消息列表 [{:role \"...\" :content \"...\"}]"
  [chat-template variables]
  (if (satisfies? proto/IMessageTemplate chat-template)
    (proto/format-messages chat-template variables)
    (throw (ex-info "模板不支持消息格式化" {:template (type chat-template)}))))

;; ============================================================
;; 占位消息（用于动态插入）
;; ============================================================

(defrecord MessagesPlaceholder [variable-name]
  proto/IPromptTemplate

  (format-prompt [_ variables]
    ;; 占位符格式化为字符串形式
    (let [messages (get variables variable-name [])]
      (->> messages
           (map (fn [{:keys [role content]}]
                  (str "[" role "]\n" content)))
           (str/join "\n\n"))))

  (get-input-variables [_]
    [variable-name]))

(defn messages-placeholder
  "创建消息占位符

   用于在聊天模板中动态插入消息历史。

   参数:
   - variable-name: 变量名（关键字）

   返回: MessagesPlaceholder

   示例:
   (def template
     (create-chat-template
       [(system-message \"你是一个助手\")
        (messages-placeholder :history)
        (human-message \"{input}\")]))"
  [variable-name]
  (->MessagesPlaceholder variable-name))

;; 扩展 ChatPromptTemplate 以支持占位符
(defn- format-message-or-placeholder
  "格式化消息或占位符

   参数:
   - item: MessagePromptTemplate 或 MessagesPlaceholder
   - variables: 变量 map

   返回: 消息或消息列表"
  [item variables]
  (cond
    (instance? MessagesPlaceholder item)
    (get variables (:variable-name item) [])

    (instance? MessagePromptTemplate item)
    [(format-single-message item variables)]

    :else
    []))

(defn format-chat-with-placeholders
  "格式化包含占位符的聊天模板

   参数:
   - chat-template: ChatPromptTemplate
   - variables: 变量 map

   返回: 消息列表"
  [chat-template variables]
  (->> (:message-templates chat-template)
       (mapcat #(format-message-or-placeholder % variables))
       vec))
