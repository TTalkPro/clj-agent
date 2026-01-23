(ns im.ttalk.agent.llm.core.errors
  "LLM 错误处理模块（向后兼容层）

   此命名空间从 im.ttalk.agent.core.kernel.errors re-export 所有定义。
   新代码建议直接使用 im.ttalk.agent.core.kernel.errors。

   错误类型：
   - :network-error    网络连接错误（可重试）
   - :timeout-error    请求超时（可重试）
   - :rate-limit-error 速率限制（可重试）
   - :auth-error       认证失败（不可重试）
   - :validation-error 参数验证失败（不可重试）
   - :parse-error      响应解析失败（不可重试）
   - :provider-error   Provider 特定错误

   使用示例：

   (require '[im.ttalk.agent.llm.core.errors :as errors])

   ;; 创建错误
   (errors/error :timeout-error \"请求超时\")

   ;; 检查是否可重试
   (errors/retryable? err) ; => true

   ;; 安全执行
   (errors/with-error-handling #(api-call))"
  (:require [im.ttalk.agent.core.kernel.errors :as errors]))

;;; ============================================================
;;; Re-export 错误创建
;;; ============================================================

(def error errors/error)

;;; ============================================================
;;; Re-export 错误判断
;;; ============================================================

(def error? errors/error?)
(def retryable? errors/retryable?)
(def error-type errors/error-type)
(def http-error? errors/http-error?)
(def auth-error? errors/auth-error?)
(def rate-limit-error? errors/rate-limit-error?)

;;; ============================================================
;;; Re-export 异常转换
;;; ============================================================

(def exception->error errors/exception->error)
(def http-response->error errors/http-response->error)

;;; ============================================================
;;; Re-export 异常抛出
;;; ============================================================

(def throw! errors/throw!)
(def throw-if-error! errors/throw-if-error!)

;;; ============================================================
;;; Re-export 错误格式化
;;; ============================================================

(def format-error errors/format-error)

;;; ============================================================
;;; Re-export 错误处理组合器
;;; ============================================================

(def with-error-handling errors/with-error-handling)
(def safe-execute errors/safe-execute)

;;; ============================================================
;;; Re-export Result 类型辅助
;;; ============================================================

(def ok errors/ok)
(def err errors/err)
(def ok? errors/ok?)
(def err? errors/err?)
