(ns im.ttalk.agent.model.provider.openai
  "OpenAI Provider 实现

   实现 ILLMProvider 协议，提供 OpenAI API 的完整访问。

   支持功能：
   - 同步调用
   - 流式调用
   - 异步调用
   - 异步流式调用
   - 工具调用（Function Calling）

   使用示例：

   (require '[im.ttalk.agent.model.provider.openai :as openai])

   (def provider (openai/create-provider))

   ;; 同步调用
   (openai/call-openai config messages tools)

   ;; 流式调用
   (openai/call-openai-stream config messages tools on-token)"
  (:require [im.ttalk.agent.model.provider.base :as base]))

;;; ============================================================
;;; 配置
;;; ============================================================

(def ^:private default-config
  "默认 OpenAI API 配置"
  (base/make-config
    :openai
    "https://api.openai.com/v1"
    "OPENAI_API_KEY"
    :timeout 120000
    :default-model "gpt-4"))

;;; ============================================================
;;; 同步 API 调用
;;; ============================================================

(defn call-openai
  "调用 OpenAI API（同步）

   参数：
   - config:   配置 map {:model \"...\" :max-tokens 4096 ...}
   - messages: 消息列表
   - tools:    工具列表

   返回：
   OpenAI API 响应

   示例：
   (call-openai
     {:model \"gpt-4\" :max-tokens 4096}
     [{:role \"user\" :content \"你好\"}]
     [])"
  [config messages tools]
  (base/call-api default-config config messages tools))

;;; ============================================================
;;; 流式 API 调用
;;; ============================================================

(defn call-openai-stream
  "流式调用 OpenAI API（同步，阻塞当前线程）

   参数：
   - config:   配置 map
   - messages: 消息列表
   - tools:    工具列表
   - on-token: 回调函数 (fn [{:keys [token index accumulated]}] ...)

   返回：
   最终完整响应

   示例：
   (call-openai-stream
     {:model \"gpt-4\" :max-tokens 4096}
     [{:role \"user\" :content \"你好\"}]
     []
     (fn [{:keys [token]}] (print token) (flush)))"
  [config messages tools on-token]
  (base/call-api-stream default-config config messages tools on-token))

;;; ============================================================
;;; 异步 API 调用
;;; ============================================================

(defn call-openai-async
  "异步调用 OpenAI API

   参数：
   - config:   配置 map
   - messages: 消息列表
   - tools:    工具列表
   - callback: 回调函数 (fn [response] ...)

   返回：
   nil（结果通过 callback 返回）"
  [config messages tools callback]
  (base/call-api-async default-config config messages tools callback))

(defn call-openai-stream-async
  "异步流式调用 OpenAI API（非阻塞）

   完全异步处理，适合与异步服务器集成。

   参数：
   - config:      配置 map
   - messages:    消息列表
   - tools:       工具列表
   - on-token:    token 回调 (fn [{:keys [token]}] ...)
   - on-complete: 完成回调 (fn [response] ...)
   - on-error:    错误回调 (fn [error] ...)（可选）

   返回：
   nil（所有结果通过回调返回）"
  [config messages tools on-token on-complete & [on-error]]
  (base/call-api-stream-async default-config config messages tools
                              on-token on-complete on-error))

;;; ============================================================
;;; 工厂函数
;;; ============================================================

(defn create-provider
  "创建 OpenAI Provider 实例

   参数：
   - opts: API 选项（可选）{:api-key \"...\" :base-url \"...\"}

   返回：
   OpenAICompatProvider record

   示例：
   (def provider (create-provider))
   (def provider (create-provider {:api-key \"sk-...\"}))"
  ([] (base/create-provider default-config))
  ([opts] (base/create-provider-with-opts default-config opts)))

