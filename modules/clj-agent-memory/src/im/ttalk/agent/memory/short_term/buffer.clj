(ns im.ttalk.agent.memory.short-term.buffer
  "会话缓冲实现 - 短期记忆核心组件

   提供当前会话的消息管理功能：
   - 消息添加与检索
   - 滑动窗口
   - Token 限制
   - 摘要生成

   使用示例：
   (def buffer (create-conversation-buffer))
   (-> buffer
       (add-message {:role \"user\" :content \"你好\"})
       (add-message {:role \"assistant\" :content \"你好！\"})
       (get-messages-window 10))"
  (:require [im.ttalk.agent.memory.protocol :as proto]))

;; =============================================================================
;; 消息验证
;; =============================================================================

(def ^:private valid-roles
  "有效的消息角色"
  #{"user" "assistant" "system" "tool"})

(defn- normalize-role
  "标准化角色为字符串形式"
  [role]
  (cond
    (string? role) role
    (keyword? role) (name role)
    :else (throw (ex-info "Invalid role type"
                          {:role role
                           :expected-type "string or keyword"}))))

(defn- validate-message
  "验证消息格式

   参数:
   - message: 待验证的消息 map

   返回: 验证通过返回 true，否则抛出异常"
  [message]
  (when-not (map? message)
    (throw (ex-info "Message must be a map"
                    {:message message
                     :type (type message)})))
  (when-not (contains? message :role)
    (throw (ex-info "Message must have :role field"
                    {:message message})))
  (when-not (contains? message :content)
    (throw (ex-info "Message must have :content field"
                    {:message message})))
  (let [role (normalize-role (:role message))]
    (when-not (contains? valid-roles role)
      (throw (ex-info "Invalid message role"
                      {:role role
                       :valid-roles valid-roles
                       :message message}))))
  true)

;; =============================================================================
;; LLM 摘要辅助函数
;; =============================================================================

(defn- summarize-with-llm-internal!
  "内部函数：使用 LLM 生成摘要并更新消息列表

   参数:
   - buffer: ConversationBuffer 实例
   - llm-fn: LLM 摘要函数，接受消息列表，返回摘要字符串
   - keep-last: 摘要后保留的消息数量（默认从 config 读取）

   返回: 更新后的 buffer"
  ([buffer llm-fn]
   (summarize-with-llm-internal! buffer llm-fn (:keep-last (:config buffer) 5)))
  ([buffer llm-fn keep-last]
   (let [messages (-> buffer :messages-atom)
         current-msgs @messages
         msg-count (count current-msgs)]

     ;; 只有当消息数量足够时才执行摘要
     (when (> msg-count keep-last)
       (let [;; 分离系统消息和其他消息
             system-msgs (filterv #(= (:role %) "system") current-msgs)
             other-msgs (filterv #(not= (:role %) "system") current-msgs)

             ;; 需要被摘要的消息（排除保留的消息）
             msgs-to-summarize (vec (drop-last keep-last other-msgs))
             msgs-to-keep (vec (take-last keep-last other-msgs))

             ;; 调用 LLM 生成摘要
             summary-text (llm-fn msgs-to-summarize)

             ;; 创建摘要消息
             summary-msg {:role "system"
                          :content summary-text
                          :type :summary
                          :id (str (java.util.UUID/randomUUID))
                          :timestamp (System/currentTimeMillis)
                          :summarized-count (count msgs-to-summarize)}

             ;; 重组消息: 系统消息 + 摘要 + 保留的消息
             new-messages (vec (concat system-msgs [summary-msg] msgs-to-keep))]

         ;; 更新消息列表
         (reset! messages new-messages)))
     buffer)))

;; =============================================================================
;; Token 估算
;; =============================================================================

(defn- estimate-tokens
  "估算文本的 token 数（简单估算：字符数 / 4）"
  [text]
  (if (string? text)
    (int (Math/ceil (/ (count text) 4.0)))
    0))

(defn- message-tokens
  "估算单条消息的 token 数"
  [message]
  (+ (estimate-tokens (:content message))
     (estimate-tokens (:role message))
     4)) ; 消息格式开销

;; =============================================================================
;; ConversationBuffer 实现
;; =============================================================================

(defrecord ConversationBuffer [messages-atom config context-atom]
  proto/IConversationBuffer

  (add-message [this message]
    ;; 验证消息格式（如果启用）
    (when (:validate-messages? config true)
      (validate-message message))
    (let [msg (assoc message
                :id (or (:id message) (str (java.util.UUID/randomUUID)))
                :timestamp (or (:timestamp message) (System/currentTimeMillis)))]
      (swap! messages-atom conj msg)
      ;; 应用自动摘要（在添加消息后）
      (when-let [llm-fn (:llm-summarizer config)]
        (when (:auto-summarize config)
          (when-let [threshold (:summarize-threshold config)]
            (when (>= (count @messages-atom) threshold)
              (summarize-with-llm-internal! this llm-fn)))))
      ;; 应用自动裁剪
      (when (:auto-trim config)
        (when-let [max-msgs (:max-messages config)]
          (when (> (count @messages-atom) max-msgs)
            (swap! messages-atom #(vec (take-last max-msgs %))))))
      this))

  (add-messages [this messages]
    (doseq [msg messages]
      (proto/add-message this msg))
    this)

  (get-messages [_]
    @messages-atom)

  (get-messages-window [_ n]
    (vec (take-last n @messages-atom)))

  (get-messages-by-tokens [_ max-tokens]
    (loop [msgs (reverse @messages-atom)
           result []
           tokens 0]
      (if (empty? msgs)
        (vec (reverse result))
        (let [msg (first msgs)
              msg-tokens (message-tokens msg)
              new-tokens (+ tokens msg-tokens)]
          (if (> new-tokens max-tokens)
            (vec (reverse result))
            (recur (rest msgs)
                   (conj result msg)
                   new-tokens))))))

  (get-messages-by-role [_ role]
    (filterv #(= (:role %) role) @messages-atom))

  (get-last-n-turns [_ n]
    (let [msgs @messages-atom
          ;; 找到最后 n 个 user 消息的位置
          user-indices (->> msgs
                            (map-indexed vector)
                            (filter #(= (:role (second %)) "user"))
                            (map first)
                            (take-last n))
          start-idx (or (first user-indices) 0)]
      (vec (drop start-idx msgs))))

  (count-messages [_]
    (count @messages-atom))

  (count-tokens [_]
    (reduce + 0 (map message-tokens @messages-atom)))

  (clear-messages [this]
    (reset! messages-atom [])
    this)

  (trim-to-window [this n]
    (swap! messages-atom #(vec (take-last n %)))
    this)

  (trim-to-tokens [this max-tokens]
    (let [new-msgs (proto/get-messages-by-tokens this max-tokens)]
      (reset! messages-atom new-msgs)
      this))

  (summarize [this]
    ;; 基础实现：返回消息统计
    ;; 高级实现需要 LLM 支持
    (let [msgs @messages-atom
          token-count (proto/count-tokens this)]
      {:summary (str "对话包含 " (count msgs) " 条消息")
       :original-count (count msgs)
       :token-count token-count
       :role-counts (frequencies (map :role msgs))}))

  (summarize-and-trim [this max-tokens]
    ;; 基础实现：保留系统消息和最近消息
    ;; 高级实现需要 LLM 生成摘要
    (let [msgs @messages-atom
          system-msgs (filterv #(= (:role %) "system") msgs)
          other-msgs (filterv #(not= (:role %) "system") msgs)
          system-tokens (reduce + 0 (map message-tokens system-msgs))
          remaining-tokens (- max-tokens system-tokens 100) ; 保留 100 tokens 余量
          recent-msgs (loop [m (reverse other-msgs)
                             result []
                             tokens 0]
                        (if (or (empty? m) (> tokens remaining-tokens))
                          (vec (reverse result))
                          (let [msg (first m)
                                msg-tokens (message-tokens msg)]
                            (recur (rest m)
                                   (conj result msg)
                                   (+ tokens msg-tokens)))))]
      (reset! messages-atom (vec (concat system-msgs recent-msgs)))
      this)))

;; =============================================================================
;; 工厂函数
;; =============================================================================

(defn create-conversation-buffer
  "创建会话缓冲实例

   参数（可选）：
   - :max-messages       最大消息数（默认无限制）
   - :max-tokens         最大 token 数（默认无限制）
   - :auto-trim          是否自动裁剪（默认 false）
   - :validate-messages? 是否验证消息格式（默认 true）
   - :llm-summarizer     LLM 摘要函数 (fn [messages] -> summary-string)
   - :auto-summarize     是否自动摘要（默认 false，需要 llm-summarizer）
   - :summarize-threshold 自动摘要触发阈值（消息数，默认 20）
   - :keep-last          摘要后保留的消息数（默认 5）
   - :return-messages    返回消息对象（true）或字符串（false，默认）
   - :input-key          输入消息的字段名（默认 :input）
   - :output-key         输出消息的字段名（默认 :output）
   - :initial-messages   初始消息列表

   示例：
   (create-conversation-buffer)
   (create-conversation-buffer :max-messages 100 :auto-trim true)
   (create-conversation-buffer :validate-messages? true)
   (create-conversation-buffer :llm-summarizer my-llm-fn
                              :auto-summarize true
                              :summarize-threshold 20)
   (create-conversation-buffer :return-messages true)"
  [& {:keys [max-messages max-tokens auto-trim validate-messages?
             llm-summarizer auto-summarize summarize-threshold keep-last
             return-messages input-key output-key initial-messages]
      :or {max-messages nil
           max-tokens nil
           auto-trim false
           validate-messages? true
           llm-summarizer nil
           auto-summarize false
           summarize-threshold 20
           keep-last 5
           return-messages false
           input-key :input
           output-key :output
           initial-messages []}}]
  (->ConversationBuffer
    (atom (vec initial-messages))
    {:max-messages max-messages
     :max-tokens max-tokens
     :auto-trim auto-trim
     :validate-messages? validate-messages?
     :llm-summarizer llm-summarizer
     :auto-summarize auto-summarize
     :summarize-threshold summarize-threshold
     :keep-last keep-last
     :return-messages return-messages
     :input-key input-key
     :output-key output-key}
    (atom {})))  ; context-atom: 存储额外的上下文变量

;; =============================================================================
;; LLM 摘要便捷函数
;; =============================================================================

(defn summarize-with-llm!
  "使用 LLM 生成摘要并压缩会话历史

   参数:
   - buffer: ConversationBuffer 实例
   - llm-fn: LLM 摘要函数 (fn [messages] -> summary-string)
   - keep-last: 摘要后保留的消息数量（默认 5）

   返回: 更新后的 buffer

   示例:
   (defn my-llm-summarizer [messages]
     (call-llm \\\"请总结以下对话:\\\\n\\\" (pr-str messages)))

   (summarize-with-llm! buffer my-llm-summarizer 10)"
  ([buffer llm-fn]
   (summarize-with-llm-internal! buffer llm-fn))
  ([buffer llm-fn keep-last]
   (summarize-with-llm-internal! buffer llm-fn keep-last)))

(defn summarize-and-trim-with-llm!
  "使用 LLM 生成摘要并裁剪到指定 token 数

   参数:
   - buffer: ConversationBuffer 实例
   - llm-fn: LLM 摘要函数
   - max-tokens: 最大 token 数
   - keep-last: 摘要后保留的消息数量

   返回: 更新后的 buffer"
  ([buffer llm-fn max-tokens]
   (summarize-and-trim-with-llm! buffer llm-fn max-tokens (:keep-last (:config buffer) 5)))
  ([buffer llm-fn max-tokens keep-last]
   ;; 先执行摘要
   (summarize-with-llm-internal! buffer llm-fn keep-last)
   ;; 再裁剪到指定 token 数
   (proto/trim-to-tokens buffer max-tokens)))

;; =============================================================================
;; 便捷函数
;; =============================================================================

(defn add-user-message
  "添加用户消息"
  [buffer content]
  (proto/add-message buffer {:role "user" :content content}))

(defn add-assistant-message
  "添加助手消息"
  [buffer content]
  (proto/add-message buffer {:role "assistant" :content content}))

(defn add-system-message
  "添加系统消息"
  [buffer content]
  (proto/add-message buffer {:role "system" :content content}))

(defn add-tool-message
  "添加工具消息"
  [buffer tool-call-id content]
  (proto/add-message buffer {:role "tool"
                             :tool_call_id tool-call-id
                             :content content}))

(defn get-context-messages
  "获取用于 LLM 调用的消息

   根据 :return-messages 配置返回：
   - false (默认): 返回拼接的字符串格式
   - true: 返回消息对象列表（不含内部元数据）

   示例：
   ;; 返回字符串
   (get-context-messages buffer)
   ;; => \"User: Hello\\nAssistant: Hi there!\"

   ;; 返回消息对象
   (get-context-messages (create-conversation-buffer :return-messages true))
   ;; => [{:role \\\"user\\\" :content \\\"Hello\\\"}
   ;;     {:role \\\"assistant\\\" :content \\\"Hi there!\\\"}]"
  [buffer]
  (let [messages (proto/get-messages buffer)
        config (:config buffer)
        return-messages (get config :return-messages false)]
    (if return-messages
      ;; 返回消息对象列表（不含内部元数据）
      (mapv #(select-keys % [:role :content :tool_call_id :name :tool_calls]) messages)
      ;; 返回字符串格式
      (->> messages
           (map (fn [msg]
                  (let [role (:role msg)
                        content (:content msg)]
                    (str (clojure.string/capitalize role)
                         ": "
                         content))))
           (clojure.string/join "\n")))))

(defn to-map
  "序列化为 map（用于持久化）"
  [buffer]
  {:messages @(:messages-atom buffer)
   :context @(:context-atom buffer)
   :config (:config buffer)})

(defn from-map
  "从 map 恢复（用于持久化）"
  [data]
  (->ConversationBuffer
    (atom (vec (:messages data [])))
    (:config data {})
    (atom (:context data {}))))

;; =============================================================================
;; 上下文管理便捷函数
;; =============================================================================

(defn save-context
  "保存额外的上下文变量到会话

   参数:
   - buffer: ConversationBuffer 实例
   - key: 上下文变量的键
   - value: 上下文变量的值

   返回: buffer

   示例：
   (save-context buffer :user-id \"user-123\")
   (save-context buffer :preferences {:theme :dark})"
  [buffer key value]
  (when-let [ctx (:context-atom buffer)]
    (swap! ctx assoc key value))
  buffer)

(defn get-context
  "获取上下文变量的值

   参数:
   - buffer: ConversationBuffer 实例
   - key: 上下文变量的键
   - default: 默认值（可选）

   返回: 上下文变量的值或默认值

   示例：
   (get-context buffer :user-id)
   (get-context buffer :preferences {})"
  ([buffer key]
   (get-context buffer key nil))
  ([buffer key default]
   (if-let [ctx (:context-atom buffer)]
     (get @ctx key default)
     default)))

(defn get-all-context
  "获取所有上下文变量

   参数:
   - buffer: ConversationBuffer 实例

   返回: 包含所有上下文变量的 map

   示例：
   (get-all-context buffer)
   ;; => {:user-id \"user-123\" :preferences {:theme :dark}}"
  [buffer]
  (if-let [ctx (:context-atom buffer)]
    @ctx
    {}))

(defn clear-context
  "清空所有上下文变量

   参数:
   - buffer: ConversationBuffer 实例

   返回: buffer

   示例：
   (clear-context buffer)"
  [buffer]
  (when-let [ctx (:context-atom buffer)]
    (reset! ctx {}))
  buffer)

(defn remove-context
  "删除指定的上下文变量

   参数:
   - buffer: ConversationBuffer 实例
   - keys: 要删除的键（一个或多个）

   返回: buffer

   示例：
   (remove-context buffer :user-id)
   (remove-context buffer :user-id :preferences)"
  [buffer & keys]
  (when-let [ctx (:context-atom buffer)]
    (swap! ctx #(apply dissoc % keys)))
  buffer)
