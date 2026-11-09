(ns im.ttalk.agent.provider.common.business-codes
  "厂商业务码 → canonical 错误类型的码表（designs/provider-reliability.md §1；
   照搬 Keelson `Providers/ProviderBusinessCodes.cs` + `ChatProviderException.RefineKind`）。

   **为什么要有这张表**：限流和欠费在 HTTP 上常常是同一个 429（OpenAI 兼容的
   `insufficient_quota` 就这么干），光看状态码会把「再等一会就有」当成「再等一个月也没有」，
   ChatModel 于是对欠费反复退避重试。反过来，欠费被回成 400 时只看状态码会判成参数错。
   **状态码只给初判，业务码说了算。**

   判定次序（`refine-error`，只会「收紧」）：
   1. 码表命中（`error.type` / `error.code` / 顶层 `code` / MiniMax `base_resp.status_code`）→ 定；
      provider 专属行优先于通用行；
   2. 402 → 欠费；
   3. 原判是 :validation-error / :provider-error 且文案像「上下文超长」→ :context-overflow-error；
   4. 原判是 auth / validation / rate-limit → 维持（**文案绝不凭空造出限流，也不把限流改成欠费**）；
   5. 其余（5xx / provider）文案像欠费 → :quota-exhausted-error。

   码表是数据（不是 if 链）：补新样本往 `rules` 加一行即可，判定次序不用动。"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [im.ttalk.agent.model.error :as errors]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; provider 别名
;;; ============================================================

(def ^:private provider-aliases
  (merge (zipmap ["openai" "azure" "azureopenai" "openai-compatible" "openai-responses" "deepseek" "qwen"
                  "dashscope" "moonshot" "kimi" "grok" "xai" "siliconflow" "volcengine" "ollama" "openrouter"
                  "mistral" "gemini"]
                 (repeat "openai"))
         (zipmap ["anthropic" "claude"] (repeat "anthropic"))
         (zipmap ["zhipu" "zhipuai" "glm" "bigmodel" "智谱"] (repeat "zhipu"))
         (zipmap ["minimax" "hailuo" "minimaxi"] (repeat "minimax"))))

(defn normalize-provider
  "provider 名 → 码表里的家族（各家把同一个服务写成不同名字）。nil / 空 → \"openai\"（OpenAI 兼容那套词）。"
  [provider]
  (let [p (some-> provider name str/trim str/lower-case)]
    (if (str/blank? p) "openai" (get provider-aliases p p))))

;;; ============================================================
;;; 码表
;;; ============================================================

(def rules
  "[provider-家族-或-nil code 错误类型]。provider 为 nil = 各家通用（OpenAI 兼容的那套词）。
   来源：Keelson ProviderBusinessCodes.cs（A-20 / A-29）。"
  [;; 通用：欠费 / 额度
   [nil "insufficient_quota" :quota-exhausted-error]
   [nil "billing_too_many_requests" :quota-exhausted-error]
   [nil "exceeded_current_quota_error" :quota-exhausted-error]
   [nil "credit_balance_too_low" :quota-exhausted-error]
   [nil "credit_balance_exhausted" :quota-exhausted-error]
   [nil "organization_has_expired" :quota-exhausted-error]
   [nil "organization_spend_limit_reached" :quota-exhausted-error]
   [nil "plan_uk_spend_limit_reached" :quota-exhausted-error]
   [nil "global_spend_limit_reached" :quota-exhausted-error]
   [nil "deactivated_spend_limit_reached" :quota-exhausted-error]
   [nil "spend_limit_exceeded" :quota-exhausted-error]
   ;; 通用：限流
   [nil "rate_limit_exceeded" :rate-limit-error]
   [nil "rate_limit_error" :rate-limit-error]
   [nil "requests_rate_limit_reached" :rate-limit-error]
   [nil "too_many_requests" :rate-limit-error]
   [nil "throttling_error" :rate-limit-error]
   ;; 通用：上下文超长（DeepSeek / Moonshot / Qwen 兼容口也回这个码）
   [nil "context_length_exceeded" :context-overflow-error]
   ;; Anthropic：error.type 那一族（429 是 TPM/RPM，402/billing 才是余额）
   ["anthropic" "billing_error" :quota-exhausted-error]
   ["anthropic" "not_enabled_account_error" :quota-exhausted-error]
   ;; 智谱：code 是数字串（顶层 code 或 error.code）
   ["zhipu" "1308" :quota-exhausted-error]      ;; API 已到期
   ["zhipu" "1309" :quota-exhausted-error]      ;; 账户欠费 / token 已用尽
   ["zhipu" "1310" :quota-exhausted-error]      ;; 账户已失效
   ["zhipu" "1311" :quota-exhausted-error]      ;; 余额不足
   ["zhipu" "1112" :rate-limit-error]
   ["zhipu" "1113" :rate-limit-error]
   ["zhipu" "1302" :rate-limit-error]
   ["zhipu" "1305" :rate-limit-error]
   ["zhipu" "1261" :context-overflow-error]     ;; Prompt 超长（按智谱错误码文档，暂定）
   ;; MiniMax：base_resp.status_code
   ["minimax" "1002" :rate-limit-error]
   ["minimax" "1039" :quota-exhausted-error]
   ["minimax" "2056" :quota-exhausted-error]])

(def ^:private quota-markers
  "只会「收紧」的欠费特征词（没命中码表时才轮到它）。"
  ["insufficient_quota" "insufficient balance" "credit balance" "spend limit"
   "billing" "payment required" "exceeded your current quota" "quota exceeded"
   "欠费" "余额不足" "额度不足"])

(def ^:private context-overflow-markers
  "上下文超长的特征句。⛔ 不收「too many tokens」：TPM 限流也这么说。"
  ["context_length_exceeded" "maximum context length" "prompt is too long" "context window"
   "exceeded model token limit" "range of input length should be" "exceeds the maximum number of tokens"
   "input is too long" "exceed context limit" "上下文长度" "超出最大长度"])

(defn- has-marker? [markers text]
  (when (and text (not (str/blank? (str text))))
    (let [s (str/lower-case (str text))]
      (boolean (some #(str/includes? s %) markers)))))

(defn looks-like-quota-text? [text] (has-marker? quota-markers text))
(defn looks-like-context-overflow-text? [text] (has-marker? context-overflow-markers text))

(defn- match [family code]
  (when-let [needle (some-> code str str/trim str/lower-case not-empty)]
    (let [hits (filter #(= needle (second %)) rules)]
      (or (some (fn [[p _ k]] (when (= p family) k)) hits)
          (some (fn [[p _ k]] (when (nil? p) k)) hits)))))

(defn classify
  "业务码 / error.type → 错误类型；不在表里 → nil。provider 专属行优先于通用行；type 先于 code。"
  [provider type code]
  (let [family (normalize-provider provider)]
    (or (match family type) (match family code))))

;;; ============================================================
;;; 从正文挖码
;;; ============================================================

(defn- ->body-map
  "正文 → map（字符串尝试 JSON 解析；解析不了 / 不是对象 → nil）。"
  [body]
  (cond
    (map? body) body
    (string? body) (try (let [m (json/parse-string body true)] (when (map? m) m))
                        (catch Exception _ nil))
    :else nil))

(defn- code-field [m k]
  (let [v (get m k)]
    (cond (string? v) v
          (number? v) (str v)
          :else nil)))

(defn body-codes
  "从错误正文挖出 {:type :code}：OpenAI 兼容 `{:error {:type :code}}`、`{:error \"code\"}`、
   智谱顶层 `code`、MiniMax `base_resp.status_code`。"
  [body]
  (when-let [m (->body-map body)]
    (let [err (:error m)
          [t c] (cond (map? err)    [(code-field err :type) (code-field err :code)]
                      (string? err) [nil err]
                      :else         [nil nil])
          c (or (not-empty c) (code-field m :code))
          t (or (not-empty t) (code-field m :type))
          br (:base_resp m)
          c (or (not-empty c) (when (map? br) (or (code-field br :status_code) (code-field br :status_code_i))))]
      {:type t :code c})))

(defn classify-body
  "正文里的业务码 → 错误类型（只看码；文案留给 `refine-error`）。"
  [provider body]
  (let [{:keys [type code]} (body-codes body)]
    (classify provider type code)))

(defn extract-message
  "从错误正文挖人话：`{:error {:message}}`、`{:error \"…\"}`、`{:msg}`（智谱）、`{:message}`、
   `{:base_resp {:status_msg}}`（MiniMax）。挖不到 → nil。"
  [body]
  (when-let [m (->body-map body)]
    (let [err (:error m)]
      (or (when (map? err) (not-empty (some-> (:message err) str)))
          (when (string? err) (not-empty err))
          (not-empty (some-> (:msg m) str))
          (when (string? (:message m)) (not-empty (:message m)))
          (when (map? (:base_resp m)) (not-empty (some-> (get-in m [:base_resp :status_msg]) str)))))))

(defn- body-text [body]
  (cond (string? body) body
        (nil? body) nil
        :else (try (json/generate-string body) (catch Exception _ (pr-str body)))))

;;; ============================================================
;;; 收紧 canonical error
;;; ============================================================

(def ^:private kind-retryable
  {:quota-exhausted-error false
   :context-overflow-error false
   :rate-limit-error true})

(defn- retype [err kind code]
  (cond-> (assoc err :type kind :retryable? (get kind-retryable kind (:retryable? err)))
    code (assoc :code code)))

(defn refine-error
  "按正文的业务码 / 文案收紧一个已按状态码分好类的 canonical error（判定次序见 ns 文档）。
   `body` 可以是 map 或字符串；provider 取 err 的 :provider。"
  [err body]
  (let [provider (:provider err)
        {:keys [type code]} (body-codes body)
        by-code (classify provider type code)
        text (str (:message err) " " (body-text body))
        t (:type err)]
    (cond
      by-code (retype err by-code (or (not-empty code) (not-empty type)))
      (= 402 (:status err)) (retype err :quota-exhausted-error nil)
      (and (#{:validation-error :provider-error} t)
           (not (:retryable? err))
           (looks-like-context-overflow-text? text))
      (retype err :context-overflow-error nil)
      (#{:auth-error :validation-error :rate-limit-error :network-error :timeout-error} t) err
      (looks-like-quota-text? text) (retype err :quota-exhausted-error nil)
      :else err)))

;;; ============================================================
;;; HTTP 200 业务错 / 流内错误帧
;;; ============================================================

(defn body-error
  "HTTP 200 里的业务错（MiniMax / 智谱风格 `base_resp.status_code ≠ 0`）→ canonical error；正常 → nil。"
  [provider body]
  (let [br (when (map? body) (:base_resp body))
        code (when (map? br) (:status_code br))
        code (if (string? code) (parse-long code) code)]
    (when (and (number? code) (not (zero? code)))
      (let [msg (or (some-> (:status_msg br) str not-empty) "provider 业务错误")
            base (errors/error :provider-error (str msg " (code=" code ")")
                               {:provider provider :status 200 :retryable? false
                                :code (str code)})]
        (refine-error base body)))))

(def ^:private stream-type-kind
  "流内错误帧的 error.type → 初判（Keelson KindOfType）。"
  {"authentication_error" :auth-error
   "permission_error" :auth-error
   "rate_limit_error" :rate-limit-error
   "rate_limit_exceeded" :rate-limit-error
   "invalid_request_error" :validation-error
   "not_found_error" :validation-error
   "request_too_large" :validation-error
   "context_length_exceeded" :context-overflow-error
   "overloaded_error" :provider-error
   "api_error" :provider-error
   "server_error" :provider-error})

(defn stream-error
  "流内错误帧（Anthropic `event: error` 的 `{:type :message}`、OpenAI 系 `{:error {...}}`）→ canonical error。
   先按 error.type 初判，再过码表 / 文案收紧。未知 type 按服务端错（可重试）。"
  [provider error-obj]
  (let [e (if (and (map? error-obj) (map? (:error error-obj))) (:error error-obj) error-obj)
        e (if (map? e) e {:message (str e)})
        type (some-> (or (:type e) (:code e)) str)
        msg (or (some-> (:message e) str not-empty) (pr-str e))
        kind (get stream-type-kind type :provider-error)
        base (errors/error kind (if type (str type ": " msg) msg)
                           (cond-> {:provider provider
                                    :context {:stream-error e}}
                             (= :provider-error kind) (assoc :retryable? true)))]
    (refine-error base {:error e})))

;;; ============================================================
;;; 正文里的「try again in …」
;;; ============================================================

(defn retry-delay-text
  "正文 / 消息里的 `try again in 1.5s` / `try again in 200ms` → 毫秒；没有 → nil（A-35）。"
  [text]
  (when (string? text)
    (when-let [[_ n unit] (re-find #"(?i)try again in\s*(\d+(?:\.\d+)?)\s*(ms|seconds?|s)\b" text)]
      (let [v (parse-double n)]
        (when (and v (Double/isFinite v) (>= v 0))
          (long (if (= "ms" (str/lower-case unit)) v (* v 1000))))))))
