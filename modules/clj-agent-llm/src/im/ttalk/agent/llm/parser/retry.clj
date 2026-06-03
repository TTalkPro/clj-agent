(ns im.ttalk.agent.llm.parser.retry
  "自动重试输出解析器（已迁移到 core 模块）— 向后兼容的重新导出层"
  (:require [im.ttalk.agent.core.llm.parser.retry :as core]))

;; ============================================================
;; defrecord 重新导出
;; ============================================================

(def RetryOutputParser core/RetryOutputParser)

;; ============================================================
;; 公共函数重新导出
;; ============================================================

(def with-retry core/with-retry)
(def parse-with-llm core/parse-with-llm)
