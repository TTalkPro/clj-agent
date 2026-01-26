(ns im.ttalk.agent.llm.provider.base
  "OpenAI 兼容 Provider 基础模块

   为所有 OpenAI 兼容的 LLM Provider 提供统一的基础实现。
   支持: OpenAI, ZhiPu, Ollama, Gemini, Mistral 等。

   主要功能：
   - 统一的 API 调用包装
   - 通用的 Provider Record 实现
   - 配置管理

   使用示例：

   (require '[im.ttalk.agent.llm.provider.base :as base])

   ;; 创建配置
   (def config (base/make-config
                 :openai
                 \"https://api.openai.com/v1\"
                 \"OPENAI_API_KEY\"))

   ;; 创建 Provider
   (def provider (base/create-provider config))"
  (:require [clojure.string :as str]
            [im.ttalk.agent.core.llm.provider :as proto]
            [im.ttalk.agent.llm.schema.openai :as schema]
            [im.ttalk.agent.llm.response.parser :as parser]
            [im.ttalk.agent.llm.provider.openai-compat :as compat]))

;;; ============================================================
;;; 配置构建
;;; ============================================================

(defn make-config
  "创建 Provider 配置

   参数：
   - provider-name: Provider 名称 (keyword)
   - base-url:      API 基础 URL（不含 endpoint 路径）
   - env-key:       API Key 的环境变量名

   可选参数：
   - :endpoint      API 端点路径 (默认 \"/chat/completions\")
   - :timeout       请求超时 (毫秒, 默认 120000)
   - :default-model 默认模型名称

   返回：
   配置 atom"
  [provider-name base-url env-key & {:keys [endpoint timeout default-model]
                                      :or {endpoint "/chat/completions"
                                           timeout 120000}}]
  (atom {:provider-name provider-name
         :api-key (System/getenv env-key)
         :env-key env-key
         :base-url base-url
         :endpoint endpoint
         :timeout timeout
         :default-model default-model}))

(defn update-config!
  "更新配置

   参数：
   - config: 配置 atom
   - opts:   要合并的选项

   返回：
   更新后的配置值"
  [config opts]
  (swap! config merge opts))

(defn get-api-key
  "获取 API Key

   优先使用配置中的值，否则从环境变量读取"
  [config]
  (or (:api-key @config)
      (System/getenv (:env-key @config))))

(defn get-api-url
  "构建完整 API URL

   将 base-url 和 endpoint 拼接为完整的 API 请求地址。
   去除 base-url 尾部斜线以避免重复。

   参数：
   - config: Provider 配置 atom

   返回：
   完整的 API URL 字符串"
  [config]
  (let [{:keys [base-url endpoint]} @config
        base (str/replace (or base-url "") #"/+$" "")
        ep (if (str/starts-with? (or endpoint "") "/")
             endpoint
             (str "/" endpoint))]
    (str base ep)))

;;; ============================================================
;;; 通用 API 调用
;;; ============================================================

(defn call-api
  "调用 API（同步）

   参数：
   - config:   Provider 配置 atom
   - llm-config: LLM 调用配置 {:model ... :max-tokens ...}
   - messages: 消息列表
   - tools:    工具列表

   返回：
   API 响应"
  [config llm-config messages tools]
  (let [cfg @config]
    (compat/call-api
      (get-api-url config)
      (get-api-key config)
      llm-config
      messages
      tools
      {:timeout (:timeout cfg)})))

(defn call-api-stream
  "流式调用 API（同步）

   参数：
   - config:    Provider 配置 atom
   - llm-config: LLM 调用配置
   - messages:  消息列表
   - tools:     工具列表
   - on-token:  Token 回调函数

   返回：
   最终完整响应"
  [config llm-config messages tools on-token]
  (let [cfg @config]
    (compat/call-api-stream
      (get-api-url config)
      (get-api-key config)
      llm-config
      messages
      tools
      on-token
      {:timeout (:timeout cfg)})))

(defn call-api-async
  "异步调用 API

   参数：
   - config:    Provider 配置 atom
   - llm-config: LLM 调用配置
   - messages:  消息列表
   - tools:     工具列表
   - callback:  完成回调函数

   返回：
   nil（结果通过回调返回）"
  [config llm-config messages tools callback]
  (let [cfg @config]
    (compat/call-api-async
      (get-api-url config)
      (get-api-key config)
      llm-config
      messages
      tools
      callback
      {:timeout (:timeout cfg)})))

(defn call-api-stream-async
  "异步流式调用 API

   参数：
   - config:      Provider 配置 atom
   - llm-config:  LLM 调用配置
   - messages:    消息列表
   - tools:       工具列表
   - on-token:    Token 回调
   - on-complete: 完成回调
   - on-error:    错误回调（可选）

   返回：
   nil（结果通过回调返回）"
  [config llm-config messages tools on-token on-complete & [on-error]]
  (let [cfg @config]
    (compat/call-api-stream-async
      (get-api-url config)
      (get-api-key config)
      llm-config
      messages
      tools
      on-token
      on-complete
      on-error
      {:timeout (:timeout cfg)})))

;;; ============================================================
;;; 通用 Provider Record
;;; ============================================================

(defrecord OpenAICompatProvider [config]
  proto/ILLMProvider

  (provider-name [_]
    (:provider-name @config))

  (call-llm [_ llm-config messages tools]
    (call-api config llm-config messages tools))

  (call-llm-stream [_ llm-config messages tools on-token]
    (call-api-stream config llm-config messages tools on-token))

  (extract-tool-calls [_ response]
    (parser/extract-tool-calls response))

  (extract-text [_ response]
    (parser/extract-text response))

  (build-tool-result [_ tool-id content]
    (compat/build-tool-result tool-id content))

  (supports-function-calling? [_] true)

  (supports-stream? [_] true)

  (tool->schema [_ tool]
    (schema/tool->schema tool))

  (build-assistant-message [_ response]
    (get-in response [:choices 0 :message]))

  (build-result-messages [_ assistant-msg tool-results]
    (into [assistant-msg]
          (mapv (fn [{:keys [tool-id result error]}]
                  {:role "tool"
                   :tool_call_id tool-id
                   :content (or result (str "Error: " error))})
                tool-results))))

;;; ============================================================
;;; 工厂函数
;;; ============================================================

(defn create-provider
  "从配置创建 Provider

   参数：
   - config: Provider 配置 atom

   返回：
   OpenAICompatProvider 实例"
  [config]
  (->OpenAICompatProvider config))

(defn create-provider-with-opts
  "创建 Provider 并应用额外选项

   参数：
   - config: Provider 配置 atom
   - opts:   额外选项

   返回：
   OpenAICompatProvider 实例"
  [config opts]
  (when opts
    (update-config! config opts))
  (->OpenAICompatProvider config))

;;; ============================================================
;;; 便捷宏：定义 Provider
;;; ============================================================

(defmacro defprovider
  "定义 OpenAI 兼容的 Provider

   用法：
   (defprovider openai
     :base-url \"https://api.openai.com/v1\"
     :env-key \"OPENAI_API_KEY\"
     :endpoint \"/chat/completions\"
     :timeout 120000)

   将生成：
   - default-config atom
   - call-openai, call-openai-stream 等函数
   - create-provider 函数"
  [provider-name & {:keys [base-url env-key endpoint timeout default-model]
                    :or {endpoint "/chat/completions"
                         timeout 120000}}]
  (let [name-str (name provider-name)
        config-sym (symbol "default-config")
        call-fn (symbol (str "call-" name-str))
        call-stream-fn (symbol (str "call-" name-str "-stream"))
        call-async-fn (symbol (str "call-" name-str "-async"))
        call-stream-async-fn (symbol (str "call-" name-str "-stream-async"))]
    `(do
       ;; 配置
       (def ~config-sym
         ~(str "默认 " name-str " 配置")
         (make-config ~(keyword provider-name) ~base-url ~env-key
                      :endpoint ~endpoint
                      :timeout ~timeout
                      :default-model ~default-model))

       ;; 同步调用
       (defn ~call-fn
         ~(str "调用 " name-str " API（同步）")
         [config# messages# tools#]
         (call-api ~config-sym config# messages# tools#))

       ;; 流式调用
       (defn ~call-stream-fn
         ~(str "流式调用 " name-str " API")
         [config# messages# tools# on-token#]
         (call-api-stream ~config-sym config# messages# tools# on-token#))

       ;; 异步调用
       (defn ~call-async-fn
         ~(str "异步调用 " name-str " API")
         [config# messages# tools# callback#]
         (call-api-async ~config-sym config# messages# tools# callback#))

       ;; 异步流式调用
       (defn ~call-stream-async-fn
         ~(str "异步流式调用 " name-str " API")
         [config# messages# tools# on-token# on-complete# & [on-error#]]
         (call-api-stream-async ~config-sym config# messages# tools#
                                on-token# on-complete# on-error#))

       ;; 工厂函数
       (defn ~'create-provider
         ~(str "创建 " name-str " Provider")
         ([] (create-provider ~config-sym))
         ([opts#] (create-provider-with-opts ~config-sym opts#))))))

