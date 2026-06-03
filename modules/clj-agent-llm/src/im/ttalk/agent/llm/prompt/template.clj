(ns im.ttalk.agent.llm.prompt.template
  "提示词模板实现（已迁移到 core 模块）— 向后兼容的重新导出层"
  (:require [im.ttalk.agent.core.llm.prompt.template :as core]))

;; ============================================================
;; defrecord 重新导出（构造器、predicate、字段访问器）
;; ============================================================

(def PromptTemplate core/PromptTemplate)
(def FewShotPromptTemplate core/FewShotPromptTemplate)
(def DynamicFewShotTemplate core/DynamicFewShotTemplate)

;; ============================================================
;; 公共函数重新导出
;; ============================================================

(def create-prompt-template core/create-prompt-template)
(def create-few-shot-template core/create-few-shot-template)
(def create-dynamic-few-shot-template core/create-dynamic-few-shot-template)
(def combine-templates core/combine-templates)
(def from-template-str core/from-template-str)
(def from-file core/from-file)
