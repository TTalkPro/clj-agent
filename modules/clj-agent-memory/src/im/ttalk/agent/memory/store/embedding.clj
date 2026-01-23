(ns im.ttalk.agent.memory.store.embedding
  "嵌入生成器实现

   提供：
   - Mock 嵌入器（测试用）
   - LLM 嵌入器（通过 LLM Provider 生成）

   使用示例：
   (def embedder (create-mock-embedder 384))
   (embed embedder \"这是一段文本\")"
  (:require [im.ttalk.agent.memory.protocol :as proto]))

;; =============================================================================
;; Mock 嵌入器（测试用）
;; =============================================================================

(defrecord MockEmbedder [dimension seed]
  proto/IEmbedding

  (embed [_ text]
    ;; 基于文本哈希生成伪随机向量
    (let [hash-code (.hashCode (str text seed))
          rng (java.util.Random. hash-code)]
      (vec (repeatedly dimension #(- (* 2 (.nextDouble rng)) 1)))))

  (embed-batch [this texts]
    (mapv #(proto/embed this %) texts))

  (get-dimension [_]
    dimension))

(defn create-mock-embedder
  "创建 Mock 嵌入器（测试用）

   参数：
   - dimension: 向量维度（默认 384）
   - seed: 随机种子（默认 42）

   示例：
   (create-mock-embedder)
   (create-mock-embedder 1536)"
  [& {:keys [dimension seed]
      :or {dimension 384 seed 42}}]
  (->MockEmbedder dimension seed))

;; =============================================================================
;; LLM 嵌入器
;; =============================================================================

(defrecord LLMEmbedder [provider model dimension]
  proto/IEmbedding

  (embed [_ text]
    ;; 调用 LLM Provider 的嵌入 API
    ;; 需要 Provider 实现 embedding 方法
    (if-let [embed-fn (some-> provider
                               (.-embedding))]
      (embed-fn text model)
      ;; 如果 Provider 不支持，抛出异常
      (throw (ex-info "Provider does not support embeddings"
                      {:provider (type provider)
                       :model model}))))

  (embed-batch [this texts]
    (mapv #(proto/embed this %) texts))

  (get-dimension [_]
    dimension))

(defn create-llm-embedder
  "创建 LLM 嵌入器

   参数：
   - provider: LLM Provider 实例
   - model: 嵌入模型名称
   - dimension: 向量维度

   示例：
   (create-llm-embedder provider \"text-embedding-3-small\" 1536)"
  [provider model dimension]
  (->LLMEmbedder provider model dimension))

;; =============================================================================
;; 简单文本嵌入（基于 TF-IDF 思想）
;; =============================================================================

(defrecord SimpleTextEmbedder [dimension vocab-atom]
  proto/IEmbedding

  (embed [_ text]
    ;; 简单的词袋模型 + 哈希技巧
    (let [words (clojure.string/split (clojure.string/lower-case text) #"\s+")
          vec (double-array dimension 0.0)]
      (doseq [word words]
        (let [idx (mod (Math/abs (.hashCode word)) dimension)]
          (aset vec idx (+ (aget vec idx) 1.0))))
      ;; L2 归一化
      (let [norm (Math/sqrt (reduce + (map #(* % %) vec)))]
        (if (zero? norm)
          (vec (repeat dimension 0.0))
          (vec (map #(/ % norm) vec))))))

  (embed-batch [this texts]
    (mapv #(proto/embed this %) texts))

  (get-dimension [_]
    dimension))

(defn create-simple-text-embedder
  "创建简单文本嵌入器（基于词袋模型）

   参数：
   - dimension: 向量维度（默认 256）

   示例：
   (create-simple-text-embedder)
   (create-simple-text-embedder 512)"
  [& {:keys [dimension]
      :or {dimension 256}}]
  (->SimpleTextEmbedder dimension (atom {})))
