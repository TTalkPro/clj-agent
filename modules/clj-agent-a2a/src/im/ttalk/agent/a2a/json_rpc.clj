(ns im.ttalk.agent.a2a.json-rpc
  "A2A JSON-RPC 2.0 实现

   提供 JSON-RPC 2.0 协议的编解码和消息处理功能。
   A2A 协议基于 JSON-RPC 2.0 进行通信。"
  (:require [cheshire.core :as json]
            [im.ttalk.agent.a2a.types :as types]))

;; =============================================================================
;; 常量定义
;; =============================================================================

(def ^:const jsonrpc-version "2.0")

;; 标准 JSON-RPC 错误码
(def error-codes
  {:parse-error      -32700  ;; 解析错误
   :invalid-request  -32600  ;; 无效请求
   :method-not-found -32601  ;; 方法不存在
   :invalid-params   -32602  ;; 无效参数
   :internal-error   -32603  ;; 内部错误
   ;; A2A 特定错误码
   :task-not-found        -32001  ;; 任务不存在
   :task-already-complete -32002  ;; 任务已完成
   :invalid-transition    -32003  ;; 无效状态转换
   :auth-required         -32004  ;; 需要认证
   :missing-api-key       -32010  ;; 缺少 API Key
   :invalid-api-key       -32011  ;; 无效 API Key
   :api-key-expired       -32012  ;; API Key 已过期
   :insufficient-perms    -32013  ;; 权限不足
   :rate-limited          -32020  ;; 请求限流
   :context-not-found     -32021  ;; 上下文不存在
   :push-config-not-found -32022}) ;; 推送配置不存在

(def error-messages
  {:parse-error      "Parse error"
   :invalid-request  "Invalid Request"
   :method-not-found "Method not found"
   :invalid-params   "Invalid params"
   :internal-error   "Internal error"
   :task-not-found        "Task not found"
   :task-already-complete "Task already completed"
   :invalid-transition    "Invalid state transition"
   :auth-required         "Authentication required"
   :missing-api-key       "Missing API key"
   :invalid-api-key       "Invalid API key"
   :api-key-expired       "API key expired"
   :insufficient-perms    "Insufficient permissions"
   :rate-limited          "Rate limit exceeded"
   :context-not-found     "Context not found"
   :push-config-not-found "Push notification config not found"})

;; =============================================================================
;; ID 生成
;; =============================================================================

(def ^:private request-id-counter (atom 0))

(defn next-id
  "生成下一个请求 ID

   返回: 唯一的请求 ID（整数）"
  []
  (swap! request-id-counter inc))

(defn reset-id-counter!
  "重置 ID 计数器（用于测试）"
  []
  (reset! request-id-counter 0))

;; =============================================================================
;; 消息构建
;; =============================================================================

(defn make-request
  "构建 JSON-RPC 请求

   参数:
   - method: 方法名（字符串）
   - params: 参数 map（可选）
   - id: 请求 ID（可选，默认自动生成）

   返回: JSON-RPC 请求 map"
  ([method]
   (make-request method nil))
  ([method params]
   (make-request method params (next-id)))
  ([method params id]
   (cond-> {:jsonrpc jsonrpc-version
            :id id
            :method method}
     params (assoc :params params))))

(defn make-notification
  "构建 JSON-RPC 通知（无 ID，无响应）

   参数:
   - method: 方法名（字符串）
   - params: 参数 map（可选）

   返回: JSON-RPC 通知 map"
  ([method]
   (make-notification method nil))
  ([method params]
   (cond-> {:jsonrpc jsonrpc-version
            :method method}
     params (assoc :params params))))

(defn make-response
  "构建 JSON-RPC 响应

   参数:
   - id: 请求 ID
   - result: 结果数据

   返回: JSON-RPC 响应 map"
  [id result]
  {:jsonrpc jsonrpc-version
   :id id
   :result result})

(defn make-error
  "构建 JSON-RPC 错误响应

   参数:
   - id: 请求 ID（可以是 nil）
   - code: 错误码（整数或关键字）
   - message: 错误消息
   - data: 附加数据（可选）

   返回: JSON-RPC 错误响应 map"
  ([id code message]
   (make-error id code message nil))
  ([id code message data]
   (let [numeric-code (if (keyword? code)
                        (get error-codes code -32603)
                        code)
         msg (if (keyword? code)
               (or message (get error-messages code "Unknown error"))
               message)]
     (cond-> {:jsonrpc jsonrpc-version
              :id id
              :error {:code numeric-code
                      :message msg}}
       data (assoc-in [:error :data] data)))))

(defn make-error-from-exception
  "从异常构建错误响应

   参数:
   - id: 请求 ID
   - e: 异常

   返回: JSON-RPC 错误响应 map"
  [id e]
  (let [data (ex-data e)
        code (or (:code data) :internal-error)
        message (.getMessage e)]
    (make-error id code message (dissoc data :code))))

;; =============================================================================
;; 消息序列化
;; =============================================================================

(defn encode
  "将消息编码为 JSON 字符串

   参数:
   - message: JSON-RPC 消息 map

   返回: JSON 字符串"
  [message]
  (json/generate-string message))

(defn decode
  "将 JSON 字符串解码为消息

   参数:
   - json-str: JSON 字符串

   返回: JSON-RPC 消息 map，解析失败返回 nil"
  [json-str]
  (try
    (json/parse-string json-str true)
    (catch Exception _
      nil)))

;; =============================================================================
;; 消息验证
;; =============================================================================

(defn valid-request?
  "验证是否为有效的 JSON-RPC 请求

   参数:
   - msg: 消息 map

   返回: true/false"
  [msg]
  (and (map? msg)
       (= (:jsonrpc msg) jsonrpc-version)
       (string? (:method msg))
       (contains? msg :id)))

(defn valid-notification?
  "验证是否为有效的 JSON-RPC 通知

   参数:
   - msg: 消息 map

   返回: true/false"
  [msg]
  (and (map? msg)
       (= (:jsonrpc msg) jsonrpc-version)
       (string? (:method msg))
       (not (contains? msg :id))))

(defn valid-response?
  "验证是否为有效的 JSON-RPC 响应

   参数:
   - msg: 消息 map

   返回: true/false"
  [msg]
  (and (map? msg)
       (= (:jsonrpc msg) jsonrpc-version)
       (contains? msg :id)
       (or (contains? msg :result)
           (contains? msg :error))))

(defn request?
  "检查是否为请求消息"
  [msg]
  (and (contains? msg :method)
       (contains? msg :id)))

(defn notification?
  "检查是否为通知消息"
  [msg]
  (and (contains? msg :method)
       (not (contains? msg :id))))

(defn response?
  "检查是否为响应消息"
  [msg]
  (and (not (contains? msg :method))
       (contains? msg :id)))

(defn error-response?
  "检查是否为错误响应"
  [msg]
  (and (response? msg)
       (contains? msg :error)))

(defn success-response?
  "检查是否为成功响应"
  [msg]
  (and (response? msg)
       (contains? msg :result)))

;; =============================================================================
;; 错误处理辅助
;; =============================================================================

(defn get-error-message
  "从错误响应中提取错误消息"
  [response]
  (get-in response [:error :message] "Unknown error"))

(defn get-error-code
  "从错误响应中提取错误码"
  [response]
  (get-in response [:error :code] -32603))

(defn get-error-data
  "从错误响应中提取错误数据"
  [response]
  (get-in response [:error :data]))

;; =============================================================================
;; A2A 专用方法
;; =============================================================================

(def a2a-methods
  "A2A 协议支持的方法列表"
  #{"message/send"
    "message/stream"
    "tasks/get"
    "tasks/cancel"
    "tasks/pushNotificationConfig/set"
    "tasks/pushNotificationConfig/get"
    "tasks/pushNotificationConfig/delete"})

(defn valid-a2a-method?
  "检查是否为有效的 A2A 方法

   参数:
   - method: 方法名

   返回: true/false"
  [method]
  (contains? a2a-methods method))

;; =============================================================================
;; 批量请求处理
;; =============================================================================

(defn batch-request?
  "检查是否为批量请求

   参数:
   - msg: 解码后的消息

   返回: true/false"
  [msg]
  (vector? msg))

(defn make-batch-response
  "构建批量响应

   参数:
   - responses: 响应列表

   返回: 响应数组"
  [responses]
  (vec (filter some? responses)))
