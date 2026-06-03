(ns im.ttalk.agent.llm.prompt.selector
  "动态 Example 选择器实现（已迁移到 core 模块）— 向后兼容的重新导出层"
  (:require [im.ttalk.agent.core.llm.prompt.selector :as core]))

;; ============================================================
;; defrecord 重新导出（构造器、predicate、字段访问器）
;; ============================================================

(def LengthBasedSelector core/LengthBasedSelector)
(def SimilaritySelector core/SimilaritySelector)
(def MMRSelector core/MMRSelector)
(def SemanticSelector core/SemanticSelector)
(def CompositeSelector core/CompositeSelector)

;; ============================================================
;; 公共函数重新导出
;; ============================================================

(def create-length-selector core/create-length-selector)
(def create-similarity-selector core/create-similarity-selector)
(def create-mmr-selector core/create-mmr-selector)
(def create-semantic-selector core/create-semantic-selector)
(def create-composite-selector core/create-composite-selector)
