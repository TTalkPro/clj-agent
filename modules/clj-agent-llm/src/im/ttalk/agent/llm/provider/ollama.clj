(ns im.ttalk.agent.llm.provider.ollama
  "Ollama Provider 实现

   实现 ILLMProvider 协议，提供本地 Ollama 模型访问。

   配置说明：
   - :base-url - Ollama 服务地址（默认 http://localhost:11434）
   - :model    - 模型名称（如 llama2, mistral, codellama）
   - 不需要 API 密钥（本地模型）

   使用示例：

   (require '[im.ttalk.agent.llm.provider.ollama :as ollama])

   (def provider (ollama/create-provider {:model \"llama2\"}))"
  (:require [im.ttalk.agent.core.http.client :as http]
            [im.ttalk.agent.llm.provider.base :as base]))

;;; ============================================================
;;; 配置
;;; ============================================================

(def ^:private default-config
  "默认 Ollama API 配置"
  (base/make-config
    :ollama
    "http://localhost:11434/v1/chat/completions"
    "OLLAMA_API_KEY"  ;; Ollama 不需要 API key，但为了统一接口保留
    :timeout 120000))

;; Ollama 使用假的 API key
(base/update-config! default-config {:api-key "ollama"})

;;; ============================================================
;;; API 调用
;;; ============================================================

(defn call-ollama
  "调用 Ollama API（同步）"
  [config messages tools]
  (base/call-api default-config config messages tools))

(defn call-ollama-stream
  "流式调用 Ollama API"
  [config messages tools on-token]
  (base/call-api-stream default-config config messages tools on-token))

(defn call-ollama-async
  "异步调用 Ollama API"
  [config messages tools callback]
  (base/call-api-async default-config config messages tools callback))

(defn call-ollama-stream-async
  "异步流式调用 Ollama API"
  [config messages tools on-token on-complete & [on-error]]
  (base/call-api-stream-async default-config config messages tools
                              on-token on-complete on-error))

;;; ============================================================
;;; 工厂函数
;;; ============================================================

(defn create-provider
  "创建 Ollama Provider 实例

   参数：
   - opts: {:base-url \"...\" :model \"...\" :timeout n}"
  ([]
   (throw (ex-info "Ollama provider requires :model option"
                   {:required :model})))
  ([opts]
   (when-not (:model opts)
     (throw (ex-info "Ollama provider requires :model option"
                     {:required :model})))
   (base/create-provider-with-opts default-config opts)))

;;; ============================================================
;;; 辅助函数
;;; ============================================================

(defn list-models
  "列出 Ollama 上可用的模型"
  []
  (let [base-url (or (:base-url @default-config) "http://localhost:11434")]
    (try
      (let [response (http/get (str base-url "/api/tags"))]
        (if (:success? response)
          (:body response)
          {:error (str "HTTP " (:status response))}))
      (catch Exception e
        {:error (str "Failed to list models: " (.getMessage e))}))))

(defn pull-model
  "拉取 Ollama 模型"
  [model-name]
  (let [base-url (or (:base-url @default-config) "http://localhost:11434")]
    (try
      (let [response (http/post (str base-url "/api/pull")
                                :body {:name model-name}
                                :timeout 600000)]
        (if (:success? response)
          (:body response)
          {:error (str "HTTP " (:status response))}))
      (catch Exception e
        {:error (str "Failed to pull model: " (.getMessage e))}))))

