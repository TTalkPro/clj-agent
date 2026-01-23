(ns im.ttalk.agent.llm.api
  "LLM 模块统一 API 入口

   提供所有 LLM 功能的统一访问入口。

   主要功能：
   - Provider 创建和管理
   - LLM 调用（同步、流式、异步）
   - 响应解析
   - 错误处理

   使用示例：

   (require '[im.ttalk.agent.llm.api :as llm])

   ;; 创建 Provider
   (def provider (llm/create-provider :openai))

   ;; 同步调用
   (llm/call provider config messages tools)

   ;; 流式调用
   (llm/call-stream provider config messages tools on-token)

   ;; 提取结果
   (llm/extract-text provider response)
   (llm/extract-tool-calls provider response)"
  (:require [im.ttalk.agent.llm.factory.builder :as builder]
            [im.ttalk.agent.llm.factory.registry :as registry]
            [im.ttalk.agent.llm.factory.config :as config]
            [im.ttalk.agent.llm.core.protocol :as proto]
            [im.ttalk.agent.llm.core.types :as types]
            [im.ttalk.agent.llm.core.errors :as errors]))

;;; ============================================================
;;; Provider 创建
;;; ============================================================

(defn create-provider
  "创建 LLM Provider

   参数：
   - provider-type: Provider 类型 (:openai :anthropic :zhipu :ollama :gemini :mistral :mock)
   - opts:          配置选项（可选）

   返回：
   Provider 实例

   示例：
   (create-provider :openai)
   (create-provider :anthropic {:api-key \"sk-...\"})"
  ([provider-type]
   (builder/create-provider provider-type))
  ([provider-type opts]
   (builder/create-provider provider-type opts)))

(defn create-provider-auto
  "自动创建 Provider（从环境变量和配置合并）

   参数：
   - provider-type: Provider 类型
   - opts:          用户配置（可选）

   返回：
   Provider 实例"
  ([provider-type]
   (builder/create-provider-auto provider-type))
  ([provider-type opts]
   (builder/create-provider-auto provider-type opts)))

(defn supported-providers
  "获取支持的 Provider 列表"
  []
  (registry/supported-providers))

;;; ============================================================
;;; LLM 调用
;;; ============================================================

(defn call
  "调用 LLM（同步）

   参数：
   - provider: Provider 实例
   - config:   配置 {:model \"...\" :max-tokens n ...}
   - messages: 消息列表 [{:role \"user\" :content \"...\"}]
   - tools:    工具列表（可选）

   返回：
   API 响应

   示例：
   (call provider
     {:model \"gpt-4\" :max-tokens 4096}
     [{:role \"user\" :content \"你好\"}])"
  ([provider config messages]
   (proto/call-llm provider config messages []))
  ([provider config messages tools]
   (proto/call-llm provider config messages tools)))

(defn call-stream
  "流式调用 LLM

   参数：
   - provider: Provider 实例
   - config:   配置
   - messages: 消息列表
   - tools:    工具列表
   - on-token: 回调函数 (fn [{:keys [token index accumulated]}] ...)

   返回：
   最终完整响应

   示例：
   (call-stream provider config messages []
     (fn [{:keys [token]}]
       (print token)
       (flush)))"
  [provider config messages tools on-token]
  (proto/call-llm-stream provider config messages tools on-token))

;;; ============================================================
;;; 响应处理
;;; ============================================================

(defn extract-text
  "从响应中提取文本

   参数：
   - provider: Provider 实例
   - response: API 响应

   返回：
   文本字符串"
  [provider response]
  (proto/extract-text provider response))

(defn extract-tool-calls
  "从响应中提取工具调用

   参数：
   - provider: Provider 实例
   - response: API 响应

   返回：
   工具调用列表 [{:id \"...\" :name :keyword :input {...}}]"
  [provider response]
  (proto/extract-tool-calls provider response))

(defn build-tool-result
  "构建工具结果消息

   参数：
   - provider: Provider 实例
   - tool-id:  工具调用 ID
   - content:  结果内容

   返回：
   工具结果消息"
  [provider tool-id content]
  (proto/build-tool-result provider tool-id content))

;;; ============================================================
;;; Provider 信息
;;; ============================================================

(defn provider-name
  "获取 Provider 名称"
  [provider]
  (proto/provider-name provider))

(defn supports-function-calling?
  "检查是否支持函数调用"
  [provider]
  (proto/supports-function-calling? provider))

(defn supports-stream?
  "检查是否支持流式调用"
  [provider]
  (proto/supports-stream? provider))

;;; ============================================================
;;; 类型构造
;;; ============================================================

(def make-tool-call
  "创建工具调用结构

   参数：
   - id:    工具调用 ID
   - name:  工具名称
   - input: 工具输入参数

   返回：
   {:id \"...\" :name :keyword :input {...}}"
  types/make-tool-call)

(def make-response
  "创建响应结构

   参数（关键字）：
   - :text         文本内容
   - :tool-calls   工具调用列表
   - :raw-response 原始响应
   - :usage        token 使用情况

   返回：
   {:text \"...\" :tool-calls [...] ...}"
  types/make-response)

;;; ============================================================
;;; 消息构建
;;; ============================================================

(def user-message
  "创建用户消息"
  types/user-message)

(def assistant-message
  "创建助手消息"
  types/assistant-message)

(def system-message
  "创建系统消息"
  types/system-message)

(def tool-message
  "创建工具结果消息"
  types/tool-message)

;;; ============================================================
;;; 错误处理
;;; ============================================================

(def error
  "创建错误"
  errors/error)

(def error?
  "检查是否为错误"
  errors/error?)

(def retryable?
  "检查错误是否可重试"
  errors/retryable?)

(def with-error-handling
  "安全执行函数"
  errors/with-error-handling)

