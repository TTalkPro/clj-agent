(ns im.ttalk.agent.llm.prompt.protocol
  "提示词模板协议（已迁移到 core 模块）— 向后兼容的重新导出层"
  (:require [im.ttalk.agent.core.llm.prompt.protocol :as core]))

;; ============================================================
;; 协议重新导出
;; ============================================================

(def IPromptTemplate core/IPromptTemplate)
(def IPartialTemplate core/IPartialTemplate)
(def IMessageTemplate core/IMessageTemplate)
(def IExampleSelector core/IExampleSelector)

;; ============================================================
;; 公共函数重新导出
;; ============================================================

(def extract-variables core/extract-variables)
(def validate-variables core/validate-variables)
(def format-safe core/format-safe)
