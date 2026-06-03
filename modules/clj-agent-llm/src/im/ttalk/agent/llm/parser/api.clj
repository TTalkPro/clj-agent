(ns im.ttalk.agent.llm.parser.api
  "输出解析器 API（已迁移到 core 模块）— 向后兼容的重新导出层"
  (:require [im.ttalk.agent.core.llm.parser.api :as core]))

;; ============================================================
;; 协议和结果处理（重新导出）
;; ============================================================

(def success core/success)
(def failure core/failure)
(def success? core/success?)
(def get-data core/get-data)
(def get-error core/get-error)

;; ============================================================
;; 解析器创建（重新导出）
;; ============================================================

(def json-parser core/json-parser)
(def structured-parser core/structured-parser)
(def from-fields core/from-fields)

;; ============================================================
;; Schema 字段构建器（重新导出）
;; ============================================================

(def string-field core/string-field)
(def number-field core/number-field)
(def boolean-field core/boolean-field)
(def array-field core/array-field)
(def object-field core/object-field)

;; ============================================================
;; 重试与解析（重新导出）
;; ============================================================

(def with-retry core/with-retry)
(def parse core/parse)
(def format-instructions core/format-instructions)
(def parse-with-llm core/parse-with-llm)
(def validate core/validate)
(def get-schema core/get-schema)

;; ============================================================
;; 原生 JSON Schema（重新导出）
;; ============================================================

(def to-json-schema core/to-json-schema)
(def to-response-format core/to-response-format)
(def build-llm-config core/build-llm-config)
(def schema->json-schema core/schema->json-schema)
(def schema->provider-format core/schema->provider-format)
(def validate-schema-definition core/validate-schema-definition)

;; ============================================================
;; 便捷宏（重新定义为宏以保留宏语义）
;; ============================================================

(defmacro defparser
  "定义一个解析器

   示例:
   (defparser person-parser
     {:name (string-field :required true)
      :age (number-field)
      :email (string-field :description \"电子邮件\")})"
  [name schema]
  `(def ~name (structured-parser ~schema)))
