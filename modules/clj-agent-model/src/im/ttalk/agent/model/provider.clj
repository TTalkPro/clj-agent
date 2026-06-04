(ns im.ttalk.agent.model.provider
  "LLM Provider 协议定义

   定义所有 LLM 提供商必须实现的统一接口。
   第三方只需依赖 clj-agent-core 即可实现自定义 provider。

   使用示例：

   (defrecord MyProvider [api-key]
     ILLMProvider
     (provider-name [_] :my-provider)
     (call-llm [this config messages tools] ...)
     (extract-tool-calls [_ response] ...)
     (extract-text [_ response] ...)
     (build-tool-result [_ tool-id content] ...)
     (build-assistant-message [_ response] ...)
     (build-result-messages [_ assistant-msg tool-results] ...))"
  (:require [im.ttalk.agent.model.response :as response]))

;;; ============================================================
;;; LLM Provider 协议
;;; ============================================================

(defprotocol ILLMProvider
  "LLM Provider 统一接口

   必需方法：
   - provider-name     返回提供商名称
   - call-llm          调用 LLM API（同步）
   - extract-tool-calls 从响应中提取工具调用
   - extract-text       从响应中提取文本
   - build-tool-result  构建工具结果消息

   可选方法（有默认实现）：
   - call-llm-stream         流式调用
   - supports-function-calling? 是否支持 Function Call
   - supports-stream?         是否支持流式调用
   - tool->schema            工具转 Schema
   - build-assistant-message  从响应构建 assistant 消息
   - build-result-messages    构建工具结果消息列表"

  ;; 基本信息
  (provider-name [this]
    "返回提供商名称（关键字）

     示例：(provider-name provider) ; => :anthropic")

  ;; 核心 API
  (call-llm [this config messages tools]
    "调用 LLM API（同步）

     参数：
     - config:   配置 {:model \"...\" :max-tokens n :temperature f}
     - messages: 消息列表 [{:role \"user\" :content \"...\"}]
     - tools:    工具定义列表

     返回：原始 API 响应")

  (call-llm-stream [this config messages tools on-token]
    "流式调用 LLM API

     参数：
     - config:   配置（同 call-llm）
     - messages: 消息列表
     - tools:    工具定义列表
     - on-token: 回调函数 (fn [{:keys [token index accumulated]}] ...)

     返回：最终完整响应

     默认实现：回退到非流式调用")

  ;; 响应解析
  (extract-tool-calls [this response]
    "从响应中提取工具调用

     返回：工具调用列表 [{:id \"...\" :name :keyword :input {...}}]")

  (extract-text [this response]
    "从响应中提取文本内容

     返回：字符串")

  (build-tool-result [this tool-id content]
    "构建工具结果消息

     参数：
     - tool-id: 工具调用 ID
     - content: 工具执行结果（字符串）

     返回：提供商特定的消息格式")

  ;; 能力查询
  (supports-function-calling? [this]
    "是否支持 Function Call

     返回：boolean")

  (supports-stream? [this]
    "是否支持流式调用

     返回：boolean")

  ;; Schema 转换
  (tool->schema [this tool]
    "将工具定义转换为提供商特定格式

     参数：
     - tool: {:name :keyword :description \"...\" :parameters {...}}

     返回：提供商特定的 schema 格式")

  ;; === 新增方法 ===
  (build-assistant-message [this response]
    "从原始 API 响应构建 assistant 消息（用于对话历史）

     参数：
     - response: 原始 API 响应

     返回：
     {:role \"assistant\" :content ...}")

  (build-result-messages [this assistant-msg tool-results]
    "构建工具结果消息列表（用于追加到对话历史后再次调用 LLM）

     参数：
     - assistant-msg: build-assistant-message 的返回值
     - tool-results: [{:tool-id \"...\" :result \"...\" :error nil}]

     返回：
     [msg1 msg2 ...]"))

;;; ============================================================
;;; 默认实现
;;; ============================================================

(extend-type Object
  ILLMProvider

  ;; 默认流式调用：回退到非流式
  (call-llm-stream [this config messages tools on-token]
    (let [response (call-llm this config messages tools)
          text (extract-text this response)]
      (when (and on-token (seq text))
        (on-token {:token text :index 0 :accumulated text}))
      response))

  ;; 默认能力：不支持
  (supports-function-calling? [_] false)
  (supports-stream? [_] false)

  ;; 默认 schema 转换：原样返回
  (tool->schema [_ tool] tool)

  ;; 默认 build-assistant-message：简单文本消息
  (build-assistant-message [this response]
    {:role "assistant" :content (extract-text this response)})

  ;; 默认 build-result-messages：OpenAI 风格（每个 tool result 一条 tool 消息）
  (build-result-messages [_ assistant-msg tool-results]
    (into [assistant-msg]
          (mapv (fn [{:keys [tool-id result error]}]
                  {:role "tool"
                   :tool_call_id tool-id
                   :content (or result (str "Error: " error))})
                tool-results))))

;;; ============================================================
;;; 辅助函数
;;; ============================================================

(defn provider?
  "检查对象是否实现了 ILLMProvider 协议

   参数：
   - x: 任意对象

   返回：
   boolean"
  [x]
  (and (some? x)
       (satisfies? ILLMProvider x)))

(defn call-with-tools
  "调用 LLM 并返回统一响应格式

   参数：
   - provider: ILLMProvider 实例
   - config:   配置
   - messages: 消息列表
   - tools:    工具列表

   返回：
   统一响应格式：
   {:text \"...\"
    :tool-calls [{:id :name :input}]
    :usage {:input-tokens n :output-tokens m :total-tokens t}
    :finish-reason :stop | :tool-use | ...
    :provider :openai | :anthropic | ...
    :raw-response ...}"
  [provider config messages tools]
  (let [resp (call-llm provider config messages tools)
        text (extract-text provider resp)
        tool-calls (extract-tool-calls provider resp)
        ;; 获取 provider 特定的字段
        usage (or (:usage resp)
                  (get-in resp [:choices 0 :usage]))
        finish-reason (or (:stop_reason resp)
                          (get-in resp [:choices 0 :finish_reason]))]
    (response/make-response
      :id (:id resp)
      :model (:model resp)
      :text text
      :tool-calls tool-calls
      :usage usage
      :finish-reason finish-reason
      :provider (provider-name provider)
      :raw-response resp)))

(defn call-simple
  "简化的 LLM 调用（无工具）

   参数：
   - provider: ILLMProvider 实例
   - config:   配置
   - messages: 消息列表

   返回：
   文本响应字符串"
  [provider config messages]
  (let [resp (call-llm provider config messages nil)]
    (extract-text provider resp)))
