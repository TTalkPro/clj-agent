(ns im.ttalk.agent.provider.ollama
  "Ollama Provider —— 本地模型，OpenAI 兼容端点（统一由 base/defprovider 生成）。

   - :base-url 默认 http://localhost:11434/v1
   - 不需要 API key（预置假 key \"ollama\"）
   - create-provider 必须提供 :model

   (require '[im.ttalk.agent.provider.ollama :as ollama])
   (def provider (ollama/create-provider {:model \"llama2\"}))"
  (:require [im.ttalk.agent.provider.http.client :as http]
            [im.ttalk.agent.provider.common.base :as base]))

(base/defprovider ollama
  :base-url "http://localhost:11434/v1"
  :env-key "OLLAMA_API_KEY"
  :api-key "ollama"          ;; Ollama 无需 API key，预置假 key 统一接口
  :require-model? true)

;;; ============================================================
;;; Ollama 特有：模型管理
;;; ============================================================

(defn- ollama-host
  "从 base-url 提取 Ollama 服务地址（scheme://host:port）"
  []
  (let [url (or (:base-url @default-config) "http://localhost:11434")
        uri (java.net.URI. url)
        port (.getPort uri)]
    (str (.getScheme uri) "://" (.getHost uri)
         (when (pos? port) (str ":" port)))))

(defn list-models
  "列出 Ollama 上可用的模型。返回模型列表 map 或 {:error \"...\"}"
  []
  (let [host (ollama-host)]
    (try
      (let [response (http/get (str host "/api/tags"))]
        (if (:success? response)
          (:body response)
          {:error (str "HTTP " (:status response))}))
      (catch Exception e
        {:error (str "Failed to list models: " (.getMessage e))}))))

(defn pull-model
  "拉取 Ollama 模型。返回拉取结果 map 或 {:error \"...\"}"
  [model-name]
  (let [host (ollama-host)]
    (try
      (let [response (http/post (str host "/api/pull")
                                :body {:name model-name}
                                :timeout 600000)]
        (if (:success? response)
          (:body response)
          {:error (str "HTTP " (:status response))}))
      (catch Exception e
        {:error (str "Failed to pull model: " (.getMessage e))}))))
