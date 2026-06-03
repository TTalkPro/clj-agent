(ns im.ttalk.agent.llm.prompt.api
  "提示词模板 API（已迁移到 core 模块）— 向后兼容的重新导出层"
  (:require [im.ttalk.agent.core.llm.prompt.api :as core]))

;; ============================================================
;; 协议函数重新导出
;; ============================================================

(def format-prompt core/format-prompt)
(def get-input-variables core/get-input-variables)
(def extract-variables core/extract-variables)
(def validate-variables core/validate-variables)
(def format-safe core/format-safe)

;; ============================================================
;; 模板函数重新导出
;; ============================================================

(def template core/template)
(def from-file core/from-file)
(def few-shot-template core/few-shot-template)
(def dynamic-few-shot-template core/dynamic-few-shot-template)
(def chat-template core/chat-template)
(def from-messages core/from-messages)

;; ============================================================
;; 消息构建器重新导出
;; ============================================================

(def system core/system)
(def human core/human)
(def ai core/ai)
(def messages-placeholder core/messages-placeholder)

;; ============================================================
;; 格式化函数重新导出
;; ============================================================

(def render core/render)
(def format-messages core/format-messages)
(def partial-format core/partial-format)
(def combine core/combine)
(def append core/append)

;; ============================================================
;; Example 选择器重新导出
;; ============================================================

(def length-selector core/length-selector)
(def similarity-selector core/similarity-selector)
(def mmr-selector core/mmr-selector)
(def semantic-selector core/semantic-selector)
(def select-examples core/select-examples)
(def add-example core/add-example)

;; ============================================================
;; 便捷宏（重新定义以保留宏语义，body 委托给同 ns 的 re-exported 函数）
;; ============================================================

(defmacro deftemplate
  "定义一个模板

   示例:
   (deftemplate greeting \"你好，{name}！\")"
  [name template-str]
  `(def ~name (template ~template-str)))

(defmacro defchat
  "定义一个聊天模板

   示例:
   (defchat translator
     [(system \"你是一个翻译\")
      (human \"{input}\")])"
  [name messages]
  `(def ~name (chat-template ~messages)))
