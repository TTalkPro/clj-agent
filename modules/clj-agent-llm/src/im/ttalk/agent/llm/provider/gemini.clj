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
  "调用 Gemini API（同步）"
  [config messages tools]
  (base/call-api default-config config messages tools))

(defn call-gemini-stream
  "流式调用 Gemini API"
  [config messages tools on-token]
  (base/call-api-stream default-config config messages tools on-token))

(defn call-gemini-async
  "异步调用 Gemini API"
  [config messages tools callback]
  (base/call-api-async default-config config messages tools callback))

(defn call-gemini-stream-async
  "异步流式调用 Gemini API"
  [config messages tools on-token on-complete & [on-error]]
  (base/call-api-stream-async default-config config messages tools
                              on-token on-complete on-error))

;;; ============================================================
;;; 工厂函数
;;; ============================================================

(defn create-provider
  "创建 Gemini Provider 实例"
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
  "获取模型信息"
  [model-key]
  (get model-info (keyword model-key)))

(defn list-models
  "列出可用模型"
  []
  (vals model-info))

