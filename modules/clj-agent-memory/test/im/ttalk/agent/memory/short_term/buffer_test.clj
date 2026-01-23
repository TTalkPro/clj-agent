(ns im.ttalk.agent.memory.short-term.buffer-test
  "ConversationBuffer 单元测试

   测试消息验证、添加、检索等功能"
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [im.ttalk.agent.memory.short-term.buffer :as buffer]
            [im.ttalk.agent.memory.protocol :as proto]))

;; =============================================================================
;; 消息验证测试
;; =============================================================================

(deftest test-validate-valid-roles
  "测试有效的消息角色"
  (let [buf (buffer/create-conversation-buffer :validate-messages? true)]
    ;; 字符串形式
    (is (instance? im.ttalk.agent.memory.short_term.buffer.ConversationBuffer
                   (proto/add-message buf {:role "user" :content "Hello"})))
    (is (instance? im.ttalk.agent.memory.short_term.buffer.ConversationBuffer
                   (proto/add-message buf {:role "assistant" :content "Hi"})))
    (is (instance? im.ttalk.agent.memory.short_term.buffer.ConversationBuffer
                   (proto/add-message buf {:role "system" :content "Be helpful"})))
    (is (instance? im.ttalk.agent.memory.short_term.buffer.ConversationBuffer
                   (proto/add-message buf {:role "tool" :content "Result"}))))

  (let [buf (buffer/create-conversation-buffer :validate-messages? true)]
    ;; 关键字形式
    (is (instance? im.ttalk.agent.memory.short_term.buffer.ConversationBuffer
                   (proto/add-message buf {:role :user :content "Hello"})))
    (is (instance? im.ttalk.agent.memory.short_term.buffer.ConversationBuffer
                   (proto/add-message buf {:role :assistant :content "Hi"})))
    (is (instance? im.ttalk.agent.memory.short_term.buffer.ConversationBuffer
                   (proto/add-message buf {:role :system :content "Be helpful"})))
    (is (instance? im.ttalk.agent.memory.short_term.buffer.ConversationBuffer
                   (proto/add-message buf {:role :tool :content "Result"})))))

(deftest test-validate-invalid-role
  "测试无效的消息角色"
  (let [buf (buffer/create-conversation-buffer :validate-messages? true)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid message role"
         (proto/add-message buf {:role "invalid" :content "Hello"})))

    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid message role"
         (proto/add-message buf {:role :invalid :content "Hello"})))))

(deftest test-validate-missing-role
  "测试缺少 role 字段"
  (let [buf (buffer/create-conversation-buffer :validate-messages? true)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Message must have :role field"
         (proto/add-message buf {:content "Hello"})))))

(deftest test-validate-missing-content
  "测试缺少 content 字段"
  (let [buf (buffer/create-conversation-buffer :validate-messages? true)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Message must have :content field"
         (proto/add-message buf {:role "user"})))))

(deftest test-validate-non-map-message
  "测试非 map 类型的消息"
  (let [buf (buffer/create-conversation-buffer :validate-messages? true)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Message must be a map"
         (proto/add-message buf "not a map")))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Message must be a map"
         (proto/add-message buf [:role :content])))))

(deftest test-disable-validation
  "测试禁用消息验证"
  (let [buf (buffer/create-conversation-buffer :validate-messages? false)]
    ;; 无效消息也能通过
    (is (instance? im.ttalk.agent.memory.short_term.buffer.ConversationBuffer
                   (proto/add-message buf {:role "invalid" :content "Hello"})))
    (is (instance? im.ttalk.agent.memory.short_term.buffer.ConversationBuffer
                   (proto/add-message buf {:content "No role"})))))

;; =============================================================================
;; 消息添加测试
;; =============================================================================

(deftest test-add-message
  "测试添加单条消息"
  (let [buf (buffer/create-conversation-buffer)]
    (proto/add-message buf {:role "user" :content "Hello"})
    (is (= 1 (proto/count-messages buf)))
    (is (= "user" (-> (proto/get-messages buf) first :role)))))

(deftest test-add-messages
  "测试批量添加消息"
  (let [buf (buffer/create-conversation-buffer)
        msgs [{:role "user" :content "Hello"}
              {:role "assistant" :content "Hi"}
              {:role "user" :content "How are you?"}]]
    (proto/add-messages buf msgs)
    (is (= 3 (proto/count-messages buf)))))

(deftest test-message-id-and-timestamp
  "测试消息自动添加 ID 和时间戳"
  (let [buf (buffer/create-conversation-buffer)]
    (proto/add-message buf {:role "user" :content "Hello"})
    (let [msg (first (proto/get-messages buf))]
      (is (contains? msg :id))
      (is (contains? msg :timestamp))
      (is (string? (:id msg)))
      (is (number? (:timestamp msg))))))

(deftest test-custom-message-id
  "测试自定义消息 ID"
  (let [buf (buffer/create-conversation-buffer)
        custom-id "custom-123"]
    (proto/add-message buf {:role "user" :content "Hello" :id custom-id})
    (is (= custom-id (-> (proto/get-messages buf) first :id)))))

;; =============================================================================
;; 消息检索测试
;; =============================================================================

(deftest test-get-messages
  "测试获取所有消息"
  (let [buf (buffer/create-conversation-buffer)
        msgs [{:role "user" :content "Hello"}
              {:role "assistant" :content "Hi"}]]
    (proto/add-messages buf msgs)
    (is (= 2 (count (proto/get-messages buf))))))

(deftest test-get-messages-window
  "测试获取滑动窗口消息"
  (let [buf (buffer/create-conversation-buffer)]
    (dotimes [i 10]
      (proto/add-message buf {:role "user" :content (str "Message " i)}))
    (let [window (proto/get-messages-window buf 5)]
      (is (= 5 (count window)))
      (is (= "Message 5" (:content (first window))))
      (is (= "Message 9" (:content (last window)))))))

(deftest test-get-messages-by-role
  "测试按角色获取消息"
  (let [buf (buffer/create-conversation-buffer)]
    (proto/add-message buf {:role "user" :content "Hello"})
    (proto/add-message buf {:role "assistant" :content "Hi"})
    (proto/add-message buf {:role "user" :content "How are you?"})
    (let [user-msgs (proto/get-messages-by-role buf "user")]
      (is (= 2 (count user-msgs)))
      (is (every? #(= "user" (:role %)) user-msgs)))))

(deftest test-get-messages-by-tokens
  "测试按 token 数获取消息"
  (let [buf (buffer/create-conversation-buffer)]
    ;; 添加足够长的消息以确保 token 限制生效
    (dotimes [i 20]
      (proto/add-message buf {:role "user" :content (apply str (repeat 200 (char (+ 65 i))))}))
    (let [token-limited (proto/get-messages-by-tokens buf 500)]
      (is (< (count token-limited) 20))
      (is (> (count token-limited) 0)))))

(deftest test-get-last-n-turns
  "测试获取最后 N 轮对话"
  (let [buf (buffer/create-conversation-buffer)]
    ;; 添加 3 轮对话
    (proto/add-message buf {:role "system" :content "You are helpful"})
    (proto/add-message buf {:role "user" :content "Hello"})
    (proto/add-message buf {:role "assistant" :content "Hi"})
    (proto/add-message buf {:role "user" :content "How are you?"})
    (proto/add-message buf {:role "assistant" :content "I'm good"})
    (proto/add-message buf {:role "user" :content "Bye"})
    (proto/add-message buf {:role "assistant" :content "Goodbye"})

    ;; 获取最后 2 轮（从第 2 个 user 消息开始）
    (let [last-2-turns (proto/get-last-n-turns buf 2)]
      ;; 包含: "How are you?", "I'm good", "Bye", "Goodbye"
      ;; system 消息不会被包含，因为它在第一个 user 消息之前
      (is (= 4 (count last-2-turns)))
      (is (= "How are you?" (:content (first last-2-turns)))))))

;; =============================================================================
;; Token 计数测试
;; =============================================================================

(deftest test-count-tokens
  "测试 token 计数"
  (let [buf (buffer/create-conversation-buffer)]
    (proto/add-message buf {:role "user" :content "Hello"})
    (is (> (proto/count-tokens buf) 0))
    (proto/add-message buf {:role "assistant" :content "Hi there! This is a longer response."})
    (is (< (proto/count-tokens buf) 100)))) ; 简单估算

;; =============================================================================
;; 消息清理测试
;; =============================================================================

(deftest test-clear-messages
  "测试清空消息"
  (let [buf (buffer/create-conversation-buffer)]
    (proto/add-message buf {:role "user" :content "Hello"})
    (is (= 1 (proto/count-messages buf)))
    (proto/clear-messages buf)
    (is (= 0 (proto/count-messages buf)))))

(deftest test-trim-to-window
  "测试裁剪到指定数量"
  (let [buf (buffer/create-conversation-buffer)]
    (dotimes [i 10]
      (proto/add-message buf {:role "user" :content (str "Message " i)}))
    (proto/trim-to-window buf 5)
    (is (= 5 (proto/count-messages buf)))
    (is (= "Message 5" (-> (proto/get-messages buf) first :content)))))

(deftest test-trim-to-tokens
  "测试裁剪到指定 token 数"
  (let [buf (buffer/create-conversation-buffer)]
    ;; 添加足够长的消息以确保 token 限制生效
    (dotimes [i 20]
      (proto/add-message buf {:role "user" :content (apply str (repeat 200 (char (+ 65 i))))}))
    (proto/trim-to-tokens buf 500)
    (is (< (proto/count-messages buf) 20))
    (is (> (proto/count-messages buf) 0))))

;; =============================================================================
;; 自动裁剪测试
;; =============================================================================

(deftest test-auto-trim-enabled
  "测试启用自动裁剪"
  (let [buf (buffer/create-conversation-buffer :max-messages 5 :auto-trim true)]
    (dotimes [i 10]
      (proto/add-message buf {:role "user" :content (str "Message " i)}))
    (is (= 5 (proto/count-messages buf)))
    (is (= "Message 5" (-> (proto/get-messages buf) first :content)))))

(deftest test-auto-trim-disabled
  "测试禁用自动裁剪"
  (let [buf (buffer/create-conversation-buffer :max-messages 5 :auto-trim false)]
    (dotimes [i 10]
      (proto/add-message buf {:role "user" :content (str "Message " i)}))
    (is (= 10 (proto/count-messages buf)))))

;; =============================================================================
;; 便捷函数测试
;; =============================================================================

(deftest test-add-user-message
  "测试添加用户消息便捷函数"
  (let [buf (buffer/create-conversation-buffer)]
    (buffer/add-user-message buf "Hello")
    (is (= 1 (proto/count-messages buf)))
    (is (= "user" (-> (proto/get-messages buf) first :role)))
    (is (= "Hello" (-> (proto/get-messages buf) first :content)))))

(deftest test-add-assistant-message
  "测试添加助手消息便捷函数"
  (let [buf (buffer/create-conversation-buffer)]
    (buffer/add-assistant-message buf "Hi")
    (is (= 1 (proto/count-messages buf)))
    (is (= "assistant" (-> (proto/get-messages buf) first :role)))
    (is (= "Hi" (-> (proto/get-messages buf) first :content)))))

(deftest test-add-system-message
  "测试添加系统消息便捷函数"
  (let [buf (buffer/create-conversation-buffer)]
    (buffer/add-system-message buf "You are helpful")
    (is (= 1 (proto/count-messages buf)))
    (is (= "system" (-> (proto/get-messages buf) first :role)))
    (is (= "You are helpful" (-> (proto/get-messages buf) first :content)))))

(deftest test-add-tool-message
  "测试添加工具消息便捷函数"
  (let [buf (buffer/create-conversation-buffer)]
    (buffer/add-tool-message buf "call-123" "Result")
    (is (= 1 (proto/count-messages buf)))
    (is (= "tool" (-> (proto/get-messages buf) first :role)))
    (is (= "call-123" (-> (proto/get-messages buf) first :tool_call_id)))))

(deftest test-get-context-messages
  "测试获取上下文消息（不含元数据）"
  (let [buf (buffer/create-conversation-buffer :return-messages true)]
    (proto/add-message buf {:role "user" :content "Hello"})
    (let [context (buffer/get-context-messages buf)
          msg (first context)]
      (is (contains? msg :role))
      (is (contains? msg :content))
      (is (not (contains? msg :id)))
      (is (not (contains? msg :timestamp))))))

;; =============================================================================
;; 序列化测试
;; =============================================================================

(deftest test-to-map-and-from-map
  "测试序列化和反序列化"
  (let [buf1 (buffer/create-conversation-buffer)
        _ (proto/add-message buf1 {:role "user" :content "Hello"})
        data (buffer/to-map buf1)
        buf2 (buffer/from-map data)]
    (is (= (proto/count-messages buf1)
           (proto/count-messages buf2)))
    (is (= (-> (proto/get-messages buf1) first :content)
           (-> (proto/get-messages buf2) first :content)))))

;; =============================================================================
;; 摘要测试
;; =============================================================================

(deftest test-summarize
  "测试基础摘要功能"
  (let [buf (buffer/create-conversation-buffer)]
    (proto/add-message buf {:role "user" :content "Hello"})
    (proto/add-message buf {:role "assistant" :content "Hi"})
    (let [summary (proto/summarize buf)]
      (is (contains? summary :summary))
      (is (contains? summary :original-count))
      (is (contains? summary :token-count))
      (is (contains? summary :role-counts))
      (is (= 2 (:original-count summary))))))

;; =============================================================================
;; LLM 摘要测试
;; =============================================================================

(deftest test-summarize-with-llm
  "测试 LLM 摘要功能"
  (let [buf (buffer/create-conversation-buffer)
        ;; Mock LLM 摘要函数
        mock-llm (fn [messages]
                   (str "Summary of " (count messages) " messages"))]
    ;; 添加足够的消息
    (dotimes [i 10]
      (proto/add-message buf {:role "user" :content (str "Message " i)}))
    (let [before-count (proto/count-messages buf)]
      (buffer/summarize-with-llm! buf mock-llm 5)
      (let [after-count (proto/count-messages buf)
            messages (proto/get-messages buf)
            summary-msg (first (filter #(= (:type %) :summary) messages))]
        (is (< after-count before-count))
        (is (some? summary-msg))
        (is (= "system" (:role summary-msg)))
        (is (= :summary (:type summary-msg)))
        (is (string? (:content summary-msg)))))))

(deftest test-summarize-with-llm-preserves-system
  "测试 LLM 摘要保留系统消息"
  (let [buf (buffer/create-conversation-buffer)
        mock-llm (fn [messages]
                   "Summary")]
    ;; 添加系统消息
    (proto/add-message buf {:role "system" :content "You are helpful"})
    ;; 添加足够的其他消息
    (dotimes [i 10]
      (proto/add-message buf {:role "user" :content (str "Message " i)}))
    (buffer/summarize-with-llm! buf mock-llm 5)
    (let [messages (proto/get-messages buf)
          system-msgs (filter #(= (:role %) "system") messages)]
      ;; 应该有 2 个 system 消息：原始的 + 摘要
      (is (= 2 (count system-msgs)))
      (is (some #(= "You are helpful" (:content %)) system-msgs))
      (is (some #(= :summary (:type %)) system-msgs))))

(deftest test-summarize-with-llm-not-enough-messages
  "测试消息数量不足时不执行摘要"
  (let [buf (buffer/create-conversation-buffer)
        mock-llm (fn [messages]
                   (throw (ex-info "Should not be called" {})))]
    ;; 只添加少量消息
    (dotimes [i 3]
      (proto/add-message buf {:role "user" :content (str "Message " i)}))
    ;; keep-last 设置为 5，但只有 3 条消息，不应触发摘要
    (buffer/summarize-with-llm! buf mock-llm 5)
    (is (= 3 (proto/count-messages buf)))))

(deftest test-auto-summarize-enabled
  "测试启用自动摘要"
  (let [call-count (atom 0)
        mock-llm (fn [messages]
                   (swap! call-count inc)
                   (str "Summary " @call-count))
        buf (buffer/create-conversation-buffer :llm-summarizer mock-llm
                                              :auto-summarize true
                                              :summarize-threshold 10
                                              :keep-last 5)]
    ;; 添加 20 条消息
    (dotimes [i 20]
      (proto/add-message buf {:role "user" :content (str "Message " i)}))
    ;; 应该触发了 2 次摘要（在第 10 和第 20 条消息时）
    (is (= 2 @call-count))
    ;; 最终应该少于 20 条消息
    (is (< (proto/count-messages buf) 20))))

(deftest test-auto-summarize-disabled
  "测试禁用自动摘要"
  (let [call-count (atom 0)
        mock-llm (fn [messages]
                   (swap! call-count inc)
                   "Summary")
        buf (buffer/create-conversation-buffer :llm-summarizer mock-llm
                                              :auto-summarize false
                                              :summarize-threshold 10)]
    (dotimes [i 15]
      (proto/add-message buf {:role "user" :content (str "Message " i)}))
    ;; 不应该触发摘要
    (is (= 0 @call-count))
    (is (= 15 (proto/count-messages buf)))))

(deftest test-summarize-and-trim-with-llm
  "测试 LLM 摘要并裁剪到指定 token 数"
  (let [buf (buffer/create-conversation-buffer)
        mock-llm (fn [messages]
                   "Brief summary")]
    ;; 添加大量长消息
    (dotimes [i 30]
      (proto/add-message buf {:role "user" :content (apply str (repeat 200 (char (+ 65 i))))}))
    (let [before-tokens (proto/count-tokens buf)]
      (buffer/summarize-and-trim-with-llm! buf mock-llm 500 5)
      (let [after-tokens (proto/count-tokens buf)]
        ;; Token 数应该减少
        (is (< after-tokens before-tokens))
        ;; 应该有摘要消息
        (is (some #(= (:type %) :summary) (proto/get-messages buf)))))))

(deftest test-llm-summarizer-error-handling
  "测试 LLM 摘要函数错误处理"
  (let [buf (buffer/create-conversation-buffer)
        error-llm (fn [messages]
                    (throw (ex-info "LLM error" {:code :llm-failure})))]
    (dotimes [i 10]
      (proto/add-message buf {:role "user" :content (str "Message " i)}))
    ;; LLM 错误应该向上传播
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"LLM error"
                          (buffer/summarize-with-llm! buf error-llm 5)))))

(deftest test-summary-message-metadata
  "测试摘要消息的元数据"
  (let [buf (buffer/create-conversation-buffer)
        mock-llm (fn [messages]
                   "Test summary")]
    (dotimes [i 10]
      (proto/add-message buf {:role "user" :content (str "Message " i)}))
    (buffer/summarize-with-llm! buf mock-llm 5)
    (let [summary-msg (first (filter #(= (:type %) :summary) (proto/get-messages buf)))]
      (is (contains? summary-msg :id))
      (is (contains? summary-msg :timestamp))
      (is (contains? summary-msg :summarized-count))
      (is (= 5 (:summarized-count summary-msg))) ; 10 - 5 = 5 条被摘要
      (is (number? (:timestamp summary-msg)))
      (is (string? (:id summary-msg))))))

(deftest test-summary-with-system-messages
  "测试混合消息（系统消息 + 用户消息）的摘要"
  (let [buf (buffer/create-conversation-buffer)
        mock-llm (fn [messages] "Mixed summary")]
    (proto/add-message buf {:role "system" :content "System prompt"})
    (dotimes [i 12]
      (proto/add-message buf {:role "user" :content (str "User " i)}))
    (buffer/summarize-with-llm! buf mock-llm 5)
    (let [messages (proto/get-messages buf)
          system-msgs (filter #(= (:role %) "system") messages)
          summary-msg (first (filter #(= (:type %) :summary) messages))]
      ;; 应该有原始系统消息和摘要消息
      (is (= 2 (count system-msgs)))
      (is (some? summary-msg)))))
)

;; =============================================================================
;; 阶段 10: 高级功能测试
;; =============================================================================

(deftest test-return-messages-string
  "测试 return-messages=false 返回字符串格式"
  (let [buf (buffer/create-conversation-buffer :return-messages false)]
    (proto/add-message buf {:role "user" :content "Hello"})
    (proto/add-message buf {:role "assistant" :content "Hi there!"})
    (let [result (buffer/get-context-messages buf)]
      (is (string? result))
      (is (str/includes? result "User: Hello"))
      (is (str/includes? result "Assistant: Hi there!")))))

(deftest test-return-messages-objects
  "测试 return-messages=true 返回消息对象"
  (let [buf (buffer/create-conversation-buffer :return-messages true)]
    (proto/add-message buf {:role "user" :content "Hello"})
    (proto/add-message buf {:role "assistant" :content "Hi there!"})
    (let [result (buffer/get-context-messages buf)]
      (is (vector? result))
      (is (= 2 (count result)))
      (is (map? (first result)))
      (is (= "user" (:role (first result))))
      (is (= "Hello" (:content (first result)))))))

(deftest test-return-messages-default
  "测试默认 return-messages=false（字符串格式）"
  (let [buf (buffer/create-conversation-buffer)]
    (proto/add-message buf {:role "user" :content "Hello"})
    (let [result (buffer/get-context-messages buf)]
      (is (string? result))
      (is (str/includes? result "User: Hello")))))

(deftest test-save-context
  "测试保存上下文变量"
  (let [buf (buffer/create-conversation-buffer)]
    (buffer/save-context buf :user-id "user-123")
    (buffer/save-context buf :count 42)
    (let [ctx (buffer/get-all-context buf)]
      (is (= "user-123" (:user-id ctx)))
      (is (= 42 (:count ctx))))))

(deftest test-get-context
  "测试获取上下文变量"
  (let [buf (buffer/create-conversation-buffer)]
    (buffer/save-context buf :user-id "user-123")
    (is (= "user-123" (buffer/get-context buf :user-id)))
    (is (= nil (buffer/get-context buf :nonexistent)))
    (is (= "default" (buffer/get-context buf :nonexistent "default")))))

(deftest test-get-all-context
  "测试获取所有上下文变量"
  (let [buf (buffer/create-conversation-buffer)]
    (buffer/save-context buf :user-id "user-123")
    (buffer/save-context buf :preferences {:theme :dark})
    (let [ctx (buffer/get-all-context buf)]
      (is (map? ctx))
      (is (= 2 (count ctx)))
      (is (= "user-123" (:user-id ctx)))
      (is (= :dark (get-in ctx [:preferences :theme]))))))

(deftest test-clear-context
  "测试清空上下文"
  (let [buf (buffer/create-conversation-buffer)]
    (buffer/save-context buf :user-id "user-123")
    (buffer/save-context buf :count 42)
    (is (= 2 (count (buffer/get-all-context buf))))
    (buffer/clear-context buf)
    (is (= 0 (count (buffer/get-all-context buf))))))

(deftest test-remove-context
  "测试删除上下文变量"
  (let [buf (buffer/create-conversation-buffer)]
    (buffer/save-context buf :user-id "user-123")
    (buffer/save-context buf :count 42)
    (buffer/save-context buf :name "test")
    (is (= 3 (count (buffer/get-all-context buf))))
    (buffer/remove-context buf :user-id)
    (is (= 2 (count (buffer/get-all-context buf))))
    (is (nil? (buffer/get-context buf :user-id)))
    (buffer/remove-context buf :count :name)
    (is (= 0 (count (buffer/get-all-context buf))))))

(deftest test-context-persistence
  "测试上下文的序列化和反序列化"
  (let [buf1 (buffer/create-conversation-buffer)]
    (proto/add-message buf1 {:role "user" :content "Hello"})
    (buffer/save-context buf1 :user-id "user-123")
    (buffer/save-context buf1 :session-id "session-456")
    (let [data (buffer/to-map buf1)
          buf2 (buffer/from-map data)]
      (is (= 1 (proto/count-messages buf2)))
      (is (= "user-123" (buffer/get-context buf2 :user-id)))
      (is (= "session-456" (buffer/get-context buf2 :session-id))))))

(deftest test-context-with-messages
  "测试上下文与消息共存"
  (let [buf (buffer/create-conversation-buffer)]
    ;; 添加消息
    (proto/add-message buf {:role "user" :content "Hello"})
    ;; 保存上下文
    (buffer/save-context buf :user-id "user-123")
    ;; 继续添加消息
    (proto/add-message buf {:role "assistant" :content "Hi"})
    ;; 验证两者都正常工作
    (is (= 2 (proto/count-messages buf)))
    (is (= "user-123" (buffer/get-context buf :user-id)))))
