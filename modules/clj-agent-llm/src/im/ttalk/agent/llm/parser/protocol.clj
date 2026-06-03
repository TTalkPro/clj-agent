(ns im.ttalk.agent.llm.parser.protocol
  "输出解析器协议（已迁移到 core 模块）— 向后兼容的重新导出层"
  (:require [im.ttalk.agent.core.llm.parser.protocol :as core]))

;; ============================================================
;; 协议重新导出
;; ============================================================

(def IOutputParser core/IOutputParser)
(def IRetryableParser core/IRetryableParser)
(def IStructuredParser core/IStructuredParser)
(def INativeSchemaParser core/INativeSchemaParser)

;; ============================================================
;; 公共函数重新导出
;; ============================================================

(def success core/success)
(def failure core/failure)
(def success? core/success?)
(def get-data core/get-data)
(def get-error core/get-error)
