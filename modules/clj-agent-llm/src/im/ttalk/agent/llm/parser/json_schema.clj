(ns im.ttalk.agent.llm.parser.json-schema
  "JSON Schema 生成模块（已迁移到 core 模块）— 向后兼容的重新导出层"
  (:require [im.ttalk.agent.core.llm.parser.json-schema :as core]))

;; ============================================================
;; 公共函数重新导出
;; ============================================================

(def to-json-schema core/to-json-schema)
(def to-openai-response-format core/to-openai-response-format)
(def to-claude-tool-schema core/to-claude-tool-schema)
(def to-claude-tool-choice core/to-claude-tool-choice)
(def to-provider-format core/to-provider-format)
(def validate-schema-definition core/validate-schema-definition)
(def schema->json-string core/schema->json-string)
(def from-json-schema core/from-json-schema)
