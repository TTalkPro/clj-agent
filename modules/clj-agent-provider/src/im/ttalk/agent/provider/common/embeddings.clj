(ns im.ttalk.agent.provider.common.embeddings
  "Embedding HTTP 调用与响应归一（两种线上形态）

   1. **OpenAI 兼容** `POST {base}/embeddings`
      body  {:model .. :input [\"a\" \"b\"] :dimensions n? :encoding_format ..}
      resp  {:data [{:index 0 :embedding [..]} ..] :model .. :usage {:prompt_tokens ..}}
      覆盖：OpenAI / 智谱 / SiliconFlow / Ollama / Gemini(兼容端点) / Mistral / xAI …

   2. **DashScope 原生** `POST .../services/embeddings/text-embedding/text-embedding`
      body  {:model .. :input {:texts [..]} :parameters {:dimension n :text_type ..}}
      resp  {:output {:embeddings [{:text_index 0 :embedding [..]}]} :usage {:total_tokens n}}

   两条路的公共约定：
   - 返回向量与入参 texts **同序等长**（按服务端返回的 index 重排，不假定顺序）
   - 超批自动切片（`:batch-size`），usage 逐片累加
   - 失败一律抛规范错误（D5），与 chat 路径同一套词汇"
  (:require [im.ttalk.agent.model.error :as errors]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.provider.http.client :as http]
            [im.ttalk.agent.provider.http.retry :as retry]))

(set! *warn-on-reflection* true)

(def ^:private default-timeout 60000)

;;; ============================================================
;;; 入参校验
;;; ============================================================

(defn- validate!
  [provider model texts]
  (when-not (seq texts)
    (errors/throw! (errors/error :validation-error "embed 的 texts 不能为空"
                                 {:provider provider})))
  (when-let [bad (first (remove string? texts))]
    (errors/throw! (errors/error :validation-error "embed 的 texts 必须都是字符串"
                                 {:provider provider
                                  :context {:bad-type (str (class bad))}})))
  (when-not (and (string? model) (seq model))
    (errors/throw! (errors/error :validation-error
                                 "embed 需要 :model（provider 未配 :embedding-model 时须显式传）"
                                 {:provider provider}))))

;;; ============================================================
;;; 切片
;;; ============================================================

(defn- merge-usage
  [usages]
  (let [us (remove nil? usages)]
    (when (seq us)
      (let [input (reduce + 0 (keep :input-tokens us))
            total (reduce + 0 (keep :total-tokens us))]
        (cond-> {}
          (pos? input) (assoc :input-tokens input)
          (pos? total) (assoc :total-tokens total))))))

(defn batched
  "把 texts 按 batch-size 切片，逐片调 call-chunk，结果按原序拼回。

   参数：
   - batch-size: 每片最大条数（nil / <=0 视为不切）
   - call-chunk: (fn [chunk] {:embeddings [..] :usage {..} :model ..})"
  [batch-size texts call-chunk]
  (let [chunks (if (and (integer? batch-size) (pos? batch-size))
                 (partition-all batch-size texts)
                 [texts])
        results (mapv call-chunk chunks)]
    (cond-> {:embeddings (into [] (mapcat :embeddings) results)}
      (:model (first results)) (assoc :model (:model (first results)))
      (merge-usage (map :usage results)) (assoc :usage (merge-usage (map :usage results))))))

;;; ============================================================
;;; OpenAI 兼容
;;; ============================================================

(defn build-params
  "OpenAI 兼容 embeddings 请求体（字段一律「存在才发送」）

   - :dimensions       输出维度（text-embedding-3 系列 / bge 等支持降维）
   - :encoding-format  \"float\"（默认）| \"base64\"
   - :user             终端用户标识
   - :extra-body       私有字段逃生通道，直接 merge 进请求体"
  [{:keys [model dimensions encoding-format user extra-body]} texts]
  (cond-> {:model model
           :input (vec texts)}
    dimensions      (assoc :dimensions dimensions)
    encoding-format (assoc :encoding_format encoding-format)
    user            (assoc :user user)
    (map? extra-body) (merge extra-body)))

(defn- parse-openai-body
  "{:data [{:index i :embedding [..]}]} → 按 index 排序的向量列表。
   服务端不保证顺序，按 :index 重排；缺 :index 时退回原序。"
  [body provider expected-count]
  (let [data (:data body)]
    (when-not (sequential? data)
      (errors/throw! (errors/error :parse-error "embeddings 响应缺少 data 数组"
                                   {:provider provider :context {:body body}})))
    (let [ordered (if (every? #(integer? (:index %)) data)
                    (sort-by :index data)
                    data)
          vectors (mapv :embedding ordered)]
      (when (not= (count vectors) expected-count)
        (errors/throw! (errors/error :parse-error
                                     "embeddings 返回条数与请求条数不一致"
                                     {:provider provider
                                      :context {:expected expected-count
                                                :actual (count vectors)}})))
      {:embeddings vectors
       :model (:model body)
       :usage (response/normalize-usage (:usage body))})))

(defn call-embeddings
  "调用 OpenAI 兼容 embeddings 端点（自动切片）

   参数：
   - api-url: 完整端点 URL（如 https://api.openai.com/v1/embeddings）
   - api-key: API Key（Bearer）
   - config:  {:model .. :dimensions .. :batch-size .. :timeout .. :retry ..
               :extra-headers {..} :provider-name :openai}
   - texts:   字符串列表

   返回：{:embeddings [[..] ..] :model .. :usage {..} :provider ..}"
  [api-url api-key config texts]
  (let [provider (:provider-name config)
        model (:model config)]
    (validate! provider model texts)
    (assoc
      (batched
        (:batch-size config)
        texts
        (fn [chunk]
          (let [headers (merge {"Authorization" (str "Bearer " api-key)}
                               (:extra-headers config))
                resp (retry/maybe-with-retry
                       config
                       #(http/post api-url
                                   :headers headers
                                   :body (build-params config chunk)
                                   :timeout (or (:timeout config) default-timeout)))]
            (if (:success? resp)
              (parse-openai-body (:body resp) provider (count chunk))
              (errors/throw! (http/response->error resp provider))))))
      :provider provider)))

;;; ============================================================
;;; DashScope 原生
;;; ============================================================

(defn- parse-dashscope-body
  [body provider expected-count]
  (let [items (get-in body [:output :embeddings])]
    (when-not (sequential? items)
      (errors/throw! (errors/error :parse-error "DashScope embeddings 响应缺少 output.embeddings"
                                   {:provider provider :context {:body body}})))
    (let [ordered (if (every? #(integer? (:text_index %)) items)
                    (sort-by :text_index items)
                    items)
          vectors (mapv :embedding ordered)]
      (when (not= (count vectors) expected-count)
        (errors/throw! (errors/error :parse-error
                                     "embeddings 返回条数与请求条数不一致"
                                     {:provider provider
                                      :context {:expected expected-count
                                                :actual (count vectors)}})))
      {:embeddings vectors
       :model (:model body)
       :usage (response/normalize-usage (:usage body))})))

(defn call-dashscope-embeddings
  "调用 DashScope 原生 text-embedding 端点（自动切片；单次上限官方为 10 条）

   config 额外支持 :dimensions（→ parameters.dimension）与
   :text-type（\"query\" | \"document\"，→ parameters.text_type）。"
  [api-url api-key config texts]
  (let [provider (or (:provider-name config) :dashscope)
        model (:model config)]
    (validate! provider model texts)
    (assoc
      (batched
        (or (:batch-size config) 10)
        texts
        (fn [chunk]
          (let [params (cond-> {}
                         (:dimensions config) (assoc :dimension (:dimensions config))
                         (:text-type config)  (assoc :text_type (:text-type config)))
                body (cond-> {:model model
                              :input {:texts (vec chunk)}}
                       (seq params) (assoc :parameters params)
                       (map? (:extra-body config)) (merge (:extra-body config)))
                resp (retry/maybe-with-retry
                       config
                       #(http/post api-url
                                   :headers (merge {"Authorization" (str "Bearer " api-key)}
                                                   (:extra-headers config))
                                   :body body
                                   :timeout (or (:timeout config) default-timeout)))]
            (if (:success? resp)
              (parse-dashscope-body (:body resp) provider (count chunk))
              (errors/throw! (http/response->error resp provider))))))
      :provider provider)))
