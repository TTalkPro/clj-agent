(ns im.ttalk.agent.llm.provider.mistral
  "Mistral AI Provider 实现

   实现 ILLMProvider 协议，提供 Mistral API 的完整访问。

   模型列表：
   - mistral-large-latest  - 最强大的模型
   - mistral-medium-latest - 平衡性能和速度
   - mistral-small-latest  - 快速轻量级模型
   - codestral-latest      - 代码专用模型

   使用示例：

   (require '[im.ttalk.agent.llm.provider.mistral :as mistral])

   (def provider (mistral/create-provider {:api-key \"...\"}))"
  (:require [clojure.string :as str]
            [im.ttalk.agent.llm.provider.base :as base]))

;;; ============================================================
;;; 配置
;;; ============================================================

(def ^:private default-config
  "默认 Mistral API 配置"
  (base/make-config
    :mistral
    "https://api.mistral.ai/v1/chat/completions"
    "MISTRAL_API_KEY"
    :timeout 120000
    :default-model "mistral-large-latest"))

;;; ============================================================
;;; API 调用
;;; ============================================================

(defn call-mistral
  "调用 Mistral API（同步）"
  [config messages tools]
  (base/call-api default-config config messages tools))

(defn call-mistral-stream
  "流式调用 Mistral API"
  [config messages tools on-token]
  (base/call-api-stream default-config config messages tools on-token))

(defn call-mistral-async
  "异步调用 Mistral API"
  [config messages tools callback]
  (base/call-api-async default-config config messages tools callback))

(defn call-mistral-stream-async
  "异步流式调用 Mistral API"
  [config messages tools on-token on-complete & [on-error]]
  (base/call-api-stream-async default-config config messages tools
                              on-token on-complete on-error))

;;; ============================================================
;;; 工厂函数
;;; ============================================================

(defn create-provider
  "创建 Mistral Provider 实例"
  ([]
   (when (str/blank? (base/get-api-key default-config))
     (throw (ex-info "Mistral provider requires :api-key or MISTRAL_API_KEY"
                     {:required :api-key})))
   (base/create-provider default-config))
  ([opts]
   (when (and (not (:api-key opts))
              (str/blank? (base/get-api-key default-config)))
     (throw (ex-info "Mistral provider requires :api-key"
                     {:required :api-key})))
   (base/create-provider-with-opts default-config opts)))

;;; ============================================================
;;; 模型信息
;;; ============================================================

(def ^:private model-info
  "Mistral 模型信息"
  {:mistral-large-latest  {:name "Mistral Large" :max-tokens 128000}
   :mistral-medium-latest {:name "Mistral Medium" :max-tokens 128000}
   :mistral-small-latest  {:name "Mistral Small" :max-tokens 32000}
   :codestral-latest      {:name "Codestral" :max-tokens 32000}})

(defn get-model-info
  "获取模型信息"
  [model-key]
  (get model-info (keyword model-key)))

(defn list-models
  "列出可用模型"
  []
  (vals model-info))

