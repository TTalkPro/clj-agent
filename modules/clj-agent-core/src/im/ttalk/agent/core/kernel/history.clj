(ns im.ttalk.agent.core.kernel.history
  "ChatHistory 管理

   对话历史使用 atom 管理，内部是 messages vector。
   Anthropic 格式中 system-prompt 不在 messages 中，
   而是通过 Kernel config 传入。

   使用示例：

   (def history (create-history))
   (add-user-message history \"你好\")
   (get-messages history)
   ;; => [{:role \"user\" :content \"你好\"}]")

;;; ============================================================
;;; ChatHistory 管理
;;; ============================================================

(defn create-history
  "创建对话历史

   对话历史使用 atom 管理，内部是 messages vector。
   Anthropic 格式中 system-prompt 不在 messages 中，
   而是通过 Kernel config 传入。

   参数:
   - initial-messages: 初始消息列表（可选）

   返回:
   atom 包含 messages vector"
  ([]
   (atom []))
  ([initial-messages]
   (atom (vec initial-messages))))

(defn add-user-message
  "添加用户消息到历史

   参数:
   - history: 对话历史 atom
   - content: 消息内容（字符串）

   返回:
   更新后的 messages vector"
  [history content]
  (swap! history conj {:role "user" :content content}))

(defn add-system-context
  "添加系统上下文消息（以 user 角色注入）

   某些模型不支持 system 角色，此函数以 user 消息方式
   注入上下文信息。

   参数:
   - history: 对话历史 atom
   - content: 上下文内容"
  [history content]
  (swap! history conj {:role "user" :content (str "[系统] " content)}))

(defn reset-history
  "重置对话历史

   参数:
   - history: 对话历史 atom"
  [history]
  (reset! history []))

(defn get-messages
  "获取当前对话历史消息列表"
  [history]
  @history)

(defn message-count
  "获取对话历史中的消息数量"
  [history]
  (count @history))

(defn last-message
  "获取最后一条消息"
  [history]
  (peek @history))

(defn trim-history
  "裁剪历史到指定条数（保留最新的 n 条）

   参数:
   - history: 对话历史 atom
   - n:       保留条数"
  [history n]
  (swap! history (fn [msgs]
                   (if (> (count msgs) n)
                     (vec (take-last n msgs))
                     msgs))))
