(ns im.ttalk.agent.model.embedding
  "Embedding Provider 协议（端口）

   与 `ILLMProvider` **并列而非从属**：embedding 模型和对话模型是两种不同的模型，
   端点、参数、计费都不同，一个 provider 实例支持哪一种是它自己的事（对齐 Vercel
   AI SDK 把 `LanguageModel` 与 `EmbeddingModel` 分成两个规格）。

   所以这里**不给 `extend-type Object` 默认实现**——`satisfies?` 必须是可信的能力
   探测（`ILLMProvider` 就栽在有兜底上，见 `model/provider?` 注释）。
   不支持 embedding 的厂商（如 Anthropic）不实现本协议，调用方 `satisfies?`
   一问便知，不需要「调了才报不支持」。

   实现示例：

   (defrecord MyEmbeddings [config]
     IEmbeddingProvider
     (embed [_ call-config texts] {:embeddings [[0.1 0.2]] :model \"m\"}))"
  (:require [im.ttalk.agent.model.error :as errors]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 协议
;;; ============================================================

(defprotocol IEmbeddingProvider
  "文本向量化统一接口。只有一个方法——批量即全部（单条是 1 元批），
   维度/模型/批大小都是 config，不各立方法。"

  (embed [this config texts]
    "把文本列表向量化。

     参数：
     - config: 调用配置 {:model \"...\" :dimensions n :batch-size n ...}
               （缺省取 provider 实例自身配置）
     - texts:  字符串列表

     返回：
     {:embeddings [[float ...] ...]   ;; 与 texts **同序等长**
      :model      \"text-embedding-3-small\"
      :usage      {:input-tokens n :total-tokens n}
      :provider   :openai}

     失败：抛 ex-info（data 为规范错误 map，见 model/error）"))

;;; ============================================================
;;; 辅助
;;; ============================================================

(defn embedding-provider?
  "对象是否实现了 embedding 能力（可信探测：本协议无 Object 兜底）"
  [x]
  (and (some? x) (satisfies? IEmbeddingProvider x)))

(defn embed-one
  "单条文本 → 单个向量（内部仍是一次批量调用）"
  [provider config text]
  (-> (embed provider config [text]) :embeddings first))

(defn ensure-embedding-provider!
  "断言对象支持 embedding，否则抛规范错误（不可重试）。
   给那些「必须有 embedding 才能干活」的调用方用（如向量检索）。"
  [x]
  (when-not (embedding-provider? x)
    (errors/throw!
      (errors/error :validation-error
                    "该 provider 不支持 embedding（未实现 IEmbeddingProvider）"
                    {:context {:provider-class (str (class x))}})))
  x)
