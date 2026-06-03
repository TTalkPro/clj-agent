(ns im.ttalk.agent.llm.parser.json
  "JSON 输出解析器实现（已迁移到 core 模块）— 向后兼容的重新导出层"
  (:require [im.ttalk.agent.core.llm.parser.json :as core]))

;; ============================================================
;; defrecord 重新导出
;; ============================================================

(def JsonOutputParser core/JsonOutputParser)
(def StructuredOutputParser core/StructuredOutputParser)

;; ============================================================
;; 公共函数重新导出
;; ============================================================

(def create-json-parser core/create-json-parser)
(def create-structured-parser core/create-structured-parser)
(def string-field core/string-field)
(def number-field core/number-field)
(def boolean-field core/boolean-field)
(def array-field core/array-field)
(def object-field core/object-field)
(def from-response-schema core/from-response-schema)
