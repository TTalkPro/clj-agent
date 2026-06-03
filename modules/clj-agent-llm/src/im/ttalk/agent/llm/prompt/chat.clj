(ns im.ttalk.agent.llm.prompt.chat
  "聊天提示词模板实现（已迁移到 core 模块）— 向后兼容的重新导出层"
  (:require [im.ttalk.agent.core.llm.prompt.chat :as core]))

;; ============================================================
;; defrecord 重新导出（构造器、predicate、字段访问器）
;; ============================================================

(def MessagePromptTemplate core/MessagePromptTemplate)
(def ChatPromptTemplate core/ChatPromptTemplate)
(def MessagesPlaceholder core/MessagesPlaceholder)

;; ============================================================
;; 公共函数重新导出
;; ============================================================

(def system-message core/system-message)
(def human-message core/human-message)
(def ai-message core/ai-message)
(def create-chat-template core/create-chat-template)
(def from-messages core/from-messages)
(def append-messages core/append-messages)
(def prepend-system core/prepend-system)
(def format-messages core/format-messages)
(def messages-placeholder core/messages-placeholder)
(def format-chat-with-placeholders core/format-chat-with-placeholders)
