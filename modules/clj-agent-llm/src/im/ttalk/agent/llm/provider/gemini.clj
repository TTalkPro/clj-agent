(ns im.ttalk.agent.llm.provider.gemini
  "Google Gemini Provider 实现

   实现 ILLMProvider 协议，提供 Gemini API 的完整访问。

   模型列表：
   - gemini-2.0-flash-exp - 快速响应模型
   - gemini-1.5-pro       - 强大的通用模型
   - gemini-1.5-flash     - 快速模型

   使用示例：

   (require '[im.ttalk.agent.llm.provider.gemini :as gemini])

   (def provider (gemini/create-provider {:api-key \"AIza...\"}))"
  (:require [clojure.string :as str]
            [im.ttalk.agent.llm.provider.base :as base]))

;;; ============================================================
;;; 配置
;;; ============================================================

(def ^:private default-config
  "默认 Gemini API 配置"
  (base/make-config
    :gemini
    "https://generativelanguage.googleapis.com/v1beta/chat/completions"
    "GOOGLE_API_KEY"
    :timeout 120000
    :default-model "gemini-2.0-flash-exp"))

;;; ============================================================
;;; API 调用
;;; ============================================================

(defn call-gemini
  "调用 Gemini API（同步）

   参数：
   - config:   配置 map {:model \"gemini-2.0-flash-exp\" :max-tokens 4096 ...}
   - messages: 消息列表 [{:role \"user\" :content \"...\"}]
   - tools:    工具列表

   返回：
   Gemini API 响应"
  [config messages tools]
  (base/call-api default-config config messages tools))

(defn call-gemini-stream
  "流式调用 Gemini API（同步，阻塞当前线程）

   参数：
   - config:   配置 map
   - messages: 消息列表
   - tools:    工具列表
   - on-token: 回调函数 (fn [{:keys [token index accumulated]}] ...)

   返回：
   最终完整响应"
  [config messages tools on-token]
  (base/call-api-stream default-config config messages tools on-token))

(defn call-gemini-async
  "异步调用 Gemini API

   参数：
   - config:   配置 map
   - messages: 消息列表
   - tools:    工具列表
   - callback: 回调函数 (fn [response] ...)

   返回：
   nil（结果通过 callback 返回）"
  [config messages tools callback]
  (base/call-api-async default-config config messages tools callback))

(defn call-gemini-stream-async
  "异步流式调用 Gemini API（非阻塞）

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
  "创建 Gemini Provider 实例

   参数：
   - opts: API 选项（可选）{:api-key \"AIza...\" :base-url \"...\"}

   返回：
   OpenAICompatProvider record

   抛出：
   ExceptionInfo - 如果未设置 API Key

   示例：
   (def provider (create-provider {:api-key \"AIza...\"}))"
  ([]
   (when (str/blank? (base/get-api-key default-config))
     (throw (ex-info "Gemini provider requires :api-key or GOOGLE_API_KEY"
                     {:required :api-key})))
   (base/create-provider default-config))
  ([opts]
   (when (and (not (:api-key opts))
              (str/blank? (base/get-api-key default-config)))
     (throw (ex-info "Gemini provider requires :api-key"
                     {:required :api-key})))
   (base/create-provider-with-opts default-config opts)))

;;; ============================================================
;;; 模型信息
;;; ============================================================

(def ^:private model-info
  "Gemini 模型信息"
  {:gemini-2.0-flash-exp {:name "Gemini 2.0 Flash" :max-tokens 8192}
   :gemini-1.5-pro       {:name "Gemini 1.5 Pro" :max-tokens 2097152}
   :gemini-1.5-flash     {:name "Gemini 1.5 Flash" :max-tokens 1048576}})

(defn get-model-info
  "获取 Gemini 模型信息

   参数：
   - model-key: 模型标识符（关键字或字符串）

   返回：
   模型信息 map {:name \"...\" :max-tokens n} 或 nil"
  [model-key]
  (get model-info (keyword model-key)))

(defn list-models
  "列出 Gemini 可用模型

   返回：
   模型信息列表"
  []
  (vals model-info))

