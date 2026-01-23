(ns im.ttalk.agent.mcp.json-rpc
  "JSON-RPC 2.0 实现

   MCP 使用 JSON-RPC 2.0 作为通信协议。
   本模块提供消息的编码、解码和验证功能。

   JSON-RPC 2.0 规范: https://www.jsonrpc.org/specification"
  (:require [cheshire.core :as json]))

;; =============================================================================
;; 常量定义
;; =============================================================================

(def ^:const jsonrpc-version "2.0")

;; 标准错误码
(def error-codes
  {:parse-error      -32700  ;; 解析错误
   :invalid-request  -32600  ;; 无效请求
   :method-not-found -32601  ;; 方法不存在
   :invalid-params   -32602  ;; 无效参数
   :internal-error   -32603  ;; 内部错误
   ;; MCP 特定错误码
   :resource-not-found -32001
   :tool-not-found     -32002
   :prompt-not-found   -32003})

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

   返回: JSON-RPC 请求 map

   示例:
   (make-request \"tools/call\" {:name \"calculator\" :arguments {:expr \"1+1\"}})
   => {:jsonrpc \"2.0\" :id 1 :method \"tools/call\" :params {...}}"
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

   返回: JSON-RPC 通知 map

   示例:
   (make-notification \"notifications/initialized\")
   => {:jsonrpc \"2.0\" :method \"notifications/initialized\"}"
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

   返回: JSON-RPC 响应 map

   示例:
   (make-response 1 {:content [{:type \"text\" :text \"2\"}]})
   => {:jsonrpc \"2.0\" :id 1 :result {...}}"
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

   返回: JSON-RPC 错误响应 map

   示例:
   (make-error 1 :method-not-found \"Unknown method: foo\")
   => {:jsonrpc \"2.0\" :id 1 :error {:code -32601 :message \"...\"}}"
  ([id code message]
   (make-error id code message nil))
  ([id code message data]
   (let [numeric-code (if (keyword? code)
                        (get error-codes code -32603)
                        code)]
     (cond-> {:jsonrpc jsonrpc-version
              :id id
              :error {:code numeric-code
                      :message message}}
       data (assoc-in [:error :data] data)))))

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

(defn encode-line
  "编码为单行 JSON（带换行符，用于 stdio）

   参数:
   - message: JSON-RPC 消息 map

   返回: JSON 字符串 + 换行符"
  [message]
  (str (encode message) "\n"))

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
  "检查是否为请求消息

   参数:
   - msg: 消息 map

   返回: true/false"
  [msg]
  (and (contains? msg :method)
       (contains? msg :id)))

(defn notification?
  "检查是否为通知消息

   参数:
   - msg: 消息 map

   返回: true/false"
  [msg]
  (and (contains? msg :method)
       (not (contains? msg :id))))

(defn response?
  "检查是否为响应消息

   参数:
   - msg: 消息 map

   返回: true/false"
  [msg]
  (and (not (contains? msg :method))
       (contains? msg :id)))

(defn error-response?
  "检查是否为错误响应

   参数:
   - msg: 消息 map

   返回: true/false"
  [msg]
  (and (response? msg)
       (contains? msg :error)))

(defn success-response?
  "检查是否为成功响应

   参数:
   - msg: 消息 map

   返回: true/false"
  [msg]
  (and (response? msg)
       (contains? msg :result)))

;; =============================================================================
;; 消息处理
;; =============================================================================

(defn get-error-message
  "从错误响应中提取错误消息

   参数:
   - response: 错误响应 map

   返回: 错误消息字符串"
  [response]
  (get-in response [:error :message] "Unknown error"))

(defn get-error-code
  "从错误响应中提取错误码

   参数:
   - response: 错误响应 map

   返回: 错误码（整数）"
  [response]
  (get-in response [:error :code] -32603))

(defn wrap-result
  "包装结果为标准 MCP 内容格式

   参数:
   - text: 文本内容

   返回: MCP 内容格式 map"
  [text]
  {:content [{:type "text" :text (str text)}]})

(defn wrap-error-result
  "包装错误为标准 MCP 错误内容格式

   参数:
   - error-text: 错误文本

   返回: MCP 错误内容格式 map"
  [error-text]
  {:content [{:type "text" :text (str error-text)}]
   :isError true})
