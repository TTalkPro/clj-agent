(ns im.ttalk.agent.provider.embeddings
  "Embedding Provider 实现与工厂

   **与对话 provider 分开**：embedding 模型是另一种模型（另一个端点、另一套参数、
   另一份计费），所以它是独立实例而不是给 `ILLMProvider` 加方法——对齐 Vercel AI SDK
   的 `openai.embedding(...)` 与 `openai.chat(...)` 两条线。好处是**能力探测不撒谎**：
   `(emb/embedding-provider? p)` 为真，就是真能 embed；Anthropic 这种没有 embedding
   服务的厂商，这里根本没有条目。

   内置（`create-provider` 第一参数）：
   :openai / :zhipu / :siliconflow / :ollama / :gemini / :mistral / :dashscope /
   :openai-compat（任意兼容端点，:base-url 必填）/ :mock（确定性假向量，测试用）

   ```clojure
   (require '[im.ttalk.agent.provider.embeddings :as emb])

   (def e (emb/create-provider :openai))                      ;; 取 OPENAI_API_KEY
   (emb/embed e {} [\"你好\" \"世界\"])
   ;; => {:embeddings [[...] [...]] :model \"text-embedding-3-small\"
   ;;     :usage {:input-tokens 8 :total-tokens 8} :provider :openai}

   (emb/embed-one e {:dimensions 256} \"只要一条\")

   ;; 自建 / 私有兼容端点
   (emb/create-provider :openai-compat
     {:base-url \"https://my-gw.internal/v1\" :api-key \"...\" :model \"bge-m3\"})
   ```

   调用时的 config 覆盖实例 config：`{:model :dimensions :batch-size :timeout
   :retry :encoding-format :user :extra-headers :extra-body}`（DashScope 另有
   :text-type）。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.model.embedding :as emb]
            [im.ttalk.agent.model.error :as errors]
            [im.ttalk.agent.provider.common.embeddings :as common])
  (:import [java.util Random]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 内置端点表
;;; ============================================================

(def builtin-embeddings
  "内置 embedding 端点：{provider {:kind :base-url :endpoint :env-key :model :batch-size}}

   :kind —— :openai-compat（缺省）/ :dashscope（原生形状）/ :mock
   没有条目 = 该厂商没有（公开的）embedding 服务，别硬凑一个。"
  {:openai      {:base-url "https://api.openai.com/v1"
                 :env-key "OPENAI_API_KEY"
                 :model "text-embedding-3-small"
                 :batch-size 2048}
   :zhipu       {:base-url "https://open.bigmodel.cn/api/paas/v4"
                 :env-key "ZHIPU_API_KEY"
                 :model "embedding-3"
                 :batch-size 64}
   :siliconflow {:base-url "https://api.siliconflow.cn/v1"
                 :env-key "SILICONFLOW_API_KEY"
                 :model "BAAI/bge-m3"
                 :batch-size 32}
   :ollama      {:base-url "http://localhost:11434/v1"
                 :env-key "OLLAMA_API_KEY"
                 :api-key "ollama"                ;; 本地服务不校验 key
                 :model "nomic-embed-text"}
   :gemini      {:base-url "https://generativelanguage.googleapis.com/v1beta/openai"
                 :env-key "GOOGLE_API_KEY"
                 :model "text-embedding-004"}
   :mistral     {:base-url "https://api.mistral.ai/v1"
                 :env-key "MISTRAL_API_KEY"
                 :model "mistral-embed"
                 :batch-size 128}
   :dashscope   {:kind :dashscope
                 :base-url "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding"
                 :endpoint ""                      ;; base-url 即完整端点
                 :env-key "DASHSCOPE_API_KEY"
                 :model "text-embedding-v3"
                 :batch-size 10}
   :openai-compat {:env-key nil}                   ;; :base-url 必填
   :mock        {:kind :mock :model "mock-embedding"}})

(def ^:private default-endpoint "/embeddings")

;;; ============================================================
;;; 配置解析
;;; ============================================================

(defn- api-url
  [{:keys [base-url endpoint]}]
  (let [base (str/replace (or base-url "") #"/+$" "")
        ep (or endpoint default-endpoint)
        ep (if (or (str/blank? ep) (str/starts-with? ep "/")) ep (str "/" ep))]
    (str base ep)))

(defn- api-key
  [{:keys [api-key env-key]}]
  (or api-key (when env-key (System/getenv env-key))))

;;; ============================================================
;;; 实现
;;; ============================================================

(defrecord OpenAICompatEmbeddingProvider [config]
  emb/IEmbeddingProvider
  (embed [_ call-config texts]
    (let [cfg (merge config call-config)]
      (common/call-embeddings (api-url cfg) (api-key cfg) cfg texts))))

(defrecord DashScopeEmbeddingProvider [config]
  emb/IEmbeddingProvider
  (embed [_ call-config texts]
    (let [cfg (merge config call-config)]
      (common/call-dashscope-embeddings (api-url cfg) (api-key cfg) cfg texts))))

(defn- mock-vector
  "文本 → 确定性伪向量（同文本同向量、不同文本大概率不同）。
   仅供离线测试/示例：**没有语义**，别拿它评估检索质量。"
  [^String text dimensions]
  (let [rng (Random. (long (hash text)))]
    (vec (repeatedly dimensions #(- (* 2.0 (.nextDouble rng)) 1.0)))))

(defrecord MockEmbeddingProvider [config]
  emb/IEmbeddingProvider
  (embed [_ call-config texts]
    (let [cfg (merge config call-config)
          dims (or (:dimensions cfg) 8)]
      {:embeddings (mapv #(mock-vector % dims) texts)
       :model (:model cfg)
       :usage {:input-tokens (reduce + 0 (map count texts))
               :total-tokens (reduce + 0 (map count texts))}
       :provider :mock})))

;;; ============================================================
;;; 工厂
;;; ============================================================

(defn create-provider
  "创建 embedding provider

   参数：
   - provider-type: builtin-embeddings 的键（:openai / :dashscope / :mock / …）
   - opts: 覆盖项 {:api-key :base-url :endpoint :model :dimensions :batch-size
                   :timeout :retry :extra-headers :extra-body}

   校验（创建即失败，不拖到调用时）：未知类型、缺 :base-url、缺 api-key。"
  ([provider-type] (create-provider provider-type {}))
  ([provider-type opts]
   (let [builtin (get builtin-embeddings provider-type)]
     (when-not builtin
       (errors/throw!
         (errors/error :validation-error "未知的 embedding provider"
                       {:context {:provider provider-type
                                  :supported (vec (sort (keys builtin-embeddings)))}})))
     (let [cfg (merge builtin {:provider-name provider-type} opts)
           kind (:kind cfg :openai-compat)]
       (when (and (not= :mock kind) (str/blank? (:base-url cfg)))
         (errors/throw!
           (errors/error :validation-error
                         (str (name provider-type) " embedding provider 需要 :base-url")
                         {:provider provider-type})))
       (when (and (not= :mock kind) (str/blank? (api-key cfg)))
         (errors/throw!
           (errors/error :validation-error
                         (str (name provider-type) " embedding provider 需要 :api-key"
                              (when (:env-key cfg) (str " 或环境变量 " (:env-key cfg))))
                         {:provider provider-type})))
       (case kind
         :dashscope (->DashScopeEmbeddingProvider cfg)
         :mock      (->MockEmbeddingProvider cfg)
         (->OpenAICompatEmbeddingProvider cfg))))))

(defn supported-providers
  "支持的 embedding provider 列表"
  []
  (vec (sort (keys builtin-embeddings))))

;;; ============================================================
;;; 便捷调用（委托协议，省一次 require）
;;; ============================================================

(defn embed
  "批量向量化。config 缺省 {}（用实例自身配置）。"
  ([provider texts] (emb/embed provider {} texts))
  ([provider config texts] (emb/embed provider config texts)))

(defn embed-one
  "单条向量化 → 向量本身"
  ([provider text] (emb/embed-one provider {} text))
  ([provider config text] (emb/embed-one provider config text)))
