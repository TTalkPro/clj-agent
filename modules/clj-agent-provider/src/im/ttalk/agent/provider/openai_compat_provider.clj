(ns im.ttalk.agent.provider.openai-compat-provider
  "通用 OpenAI 兼容 Provider —— 面向 vLLM / LocalAI / LM Studio / 自建网关等
   任意暴露 OpenAI Chat Completions 协议的后端。

   与具体厂商 provider 不同，这里没有内置 base-url：**:base-url 必填**，
   端点默认 \"/chat/completions\"（可经 :endpoint 覆盖）。

   用法：
   (require '[im.ttalk.agent.provider.openai-compat-provider :as oc])
   (oc/create-provider {:base-url \"http://localhost:8000/v1\" :api-key \"key\"})

   也可经 factory：(factory/create-provider :openai-compat {:base-url ... :api-key ...})"
  (:require [clojure.string :as str]
            [im.ttalk.agent.provider.common.base :as base]))

(defn create-provider
  "创建通用 OpenAI 兼容 provider 实例。

   opts:
   - :base-url  必填，OpenAI 兼容根地址（如 http://localhost:8000/v1）
   - :api-key   可选（部分本地后端无需鉴权）
   - :endpoint  可选，默认 \"/chat/completions\"
   - :timeout   可选，默认 120000
   - :model     可选默认模型
   - :env-key   可选，api-key 的环境变量名（默认 OPENAI_COMPAT_API_KEY）"
  ([] (create-provider {}))
  ([opts]
   (when (str/blank? (:base-url opts))
     (throw (ex-info "openai-compat provider requires :base-url"
                     {:required :base-url})))
   (let [cfg (base/make-config :openai-compat
                               (:base-url opts)
                               (or (:env-key opts) "OPENAI_COMPAT_API_KEY")
                               :endpoint (or (:endpoint opts) "/chat/completions")
                               :timeout (or (:timeout opts) 120000)
                               :default-model (:model opts))]
     ;; create-provider-with-opts 会基于默认值快照 + opts 建独立 atom（多实例隔离）
     (base/create-provider-with-opts cfg opts))))
