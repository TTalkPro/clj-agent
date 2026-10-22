(ns im.ttalk.agent.provider.openai-responses
  "OpenAI Responses API provider。

  这是继 OpenAI Chat Completions 与 Anthropic Messages 之后的第三个内置协议栈。
  上层仍使用同一个 ILLMProvider / 中立消息边界；provider 只把协议形状翻译成
  Chat Completions 兼容的内部响应骨架，便于复用统一 usage / finish reason 归一化。"
  (:require [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.model :as proto]
            [im.ttalk.agent.model.error :as errors]
            [im.ttalk.agent.provider.common.response-parser :as parser]
            [im.ttalk.agent.provider.http.client :as http]
            [im.ttalk.agent.provider.http.stream-client :as stream-client]
            [im.ttalk.agent.provider.schema.openai :as schema]
            [im.ttalk.agent.provider.stream.openai-responses :as stream]
            [im.ttalk.agent.provider.wire.openai-responses :as wire]))

(set! *warn-on-reflection* true)

(def default-endpoint
  {:base-url "https://api.openai.com"
   :api-path "/v1/responses"})

(defn- api-url
  [config]
  (let [{:keys [base-url api-path]} (merge default-endpoint
                                           (select-keys config [:base-url :api-path]))]
    (str (clojure.string/replace (or base-url "") #"/+$" "")
         (if (clojure.string/starts-with? (or api-path "") "/") api-path (str "/" api-path)))))

(defn- ->responses-tool-choice
  [tc]
  (case tc
    :auto "auto"
    :required "required"
    :none "none"
    tc))

(defn- response-format->text
  [{:keys [type json_schema] :as fmt}]
  (case type
    "json_schema" (let [schema (if (map? json_schema)
                                 (merge {:name "response"
                                         :schema (:schema json_schema {})}
                                        (select-keys json_schema [:strict :description]))
                                 fmt)]
                    {:text {:format (merge {:type "json_schema"} schema)}})
    "json_object" {:text {:format {:type "json_object"}}}
    "text" {:text {:format {:type "text"}}}
    nil))

(defn build-params
  [{:keys [model system-prompt max-tokens max-output-tokens temperature top-p user
           store previous-response-id metadata parallel-tool-calls truncation include
           service-tier reasoning reasoning-effort reasoning-summary response-format
           tool-choice tools extra-body]}
   wire-input wire-tools]
  (cond-> {:model model
           :input (:input wire-input)}
    (:instructions wire-input)        (assoc :instructions (:instructions wire-input))
    (or max-output-tokens max-tokens) (assoc :max_output_tokens (or max-output-tokens max-tokens))
    (some? temperature)               (assoc :temperature temperature)
    (some? top-p)                     (assoc :top_p top-p)
    user                              (assoc :user user)
    (some? store)                     (assoc :store store)
    previous-response-id              (assoc :previous_response_id previous-response-id)
    metadata                          (assoc :metadata metadata)
    (some? parallel-tool-calls)       (assoc :parallel_tool_calls parallel-tool-calls)
    truncation                        (assoc :truncation truncation)
    include                           (assoc :include include)
    service-tier                      (assoc :service_tier service-tier)
    (map? reasoning)                  (assoc :reasoning reasoning)
    (and (not reasoning) reasoning-effort)
    (assoc :reasoning {:effort reasoning-effort})
    (and (not reasoning) reasoning-summary)
    (update :reasoning #(merge (or % {}) {:summary reasoning-summary}))
    response-format                   (merge (response-format->text response-format))
    (seq wire-tools)                  (assoc :tools wire-tools)
    tool-choice                       (assoc :tool_choice (->responses-tool-choice tool-choice))
    (map? extra-body)                 (merge extra-body)))

(defn- text
  [raw]
  (wire/extract-text raw))

(defn- reasoning
  [raw]
  (or (wire/extract-reasoning raw)
      (:reasoning_content (get-in raw [:choices 0 :message] {}))))

(defn- finish-reason
  [raw]
  (let [tool? (seq (wire/extract-tool-calls raw))
        status (:status raw)
        incomplete (:incomplete_details raw)]
    (cond
      tool? "tool_calls"
      (= "incomplete" status) (or (when (= "max_output_tokens" (:reason incomplete))
                                    "length")
                                  "content_filter")
      (= "failed" status) "content_filter"
      :else "stop")))

(defn provider-response
  "把 Responses 原始响应增强成 Chat Completions 兼容骨架。

  保留完整 :output / :usage / :id / :model；新增 choices 让 ChatModel 的通用
  usage、finish reason 与 reasoning 抽取路径不需要知道 Responses 方言。"
  [raw provider-name]
  (let [tc (mapv wire/wire-tool-call
                 (filter #(= "function_call" (:type %))
                         (wire/output-items raw)))]
    (assoc raw
           :provider provider-name
           :choices [{:message (cond-> {:role "assistant"
                                        :content (text raw)}
                                 (seq (reasoning raw)) (assoc :reasoning_content (reasoning raw))
                                 (seq tc) (assoc :tool_calls tc))
                      :finish_reason (finish-reason raw)}])))

(defn- headers
  [config]
  {"Authorization" (str "Bearer " (:api-key config))
   "Accept" "application/json"})

(defn- build-body
  [cfg messages tools]
  (let [tool-schemas (schema/tools->schemas tools)
        input (wire/neutral->wire (:system-prompt cfg) messages)]
    (build-params cfg input (wire/tools->wire tool-schemas))))

(defn- call!
  [cfg messages tools]
  (let [resp (http/post (api-url cfg)
                        :headers (headers cfg)
                        :body (build-body cfg messages tools)
                        :timeout (or (:timeout cfg) 60000))]
    (if (:success? resp)
      (provider-response (:body resp) (:provider-name cfg))
      (errors/throw! (http/response->error resp (:provider-name cfg))))))

(defn- call-stream!
  [cfg messages tools on-token]
  (let [body (assoc (build-body cfg messages tools) :stream true)
        processor (stream/make-stream-processor)]
    (stream-client/post-stream-sync
      (api-url cfg)
      {:headers (headers cfg)
       :body body
       :timeout (or (:timeout cfg) 300000)
       :parse-fn (:parse-fn processor)
       :process-fn (:process-fn processor)
       :make-initial-state (:make-initial-state processor)
       :build-response (fn [state]
                         (provider-response ((:build-response processor) state)
                                            (:provider-name cfg)))
       :on-token on-token
       :retry (:retry cfg)
       :provider (:provider-name cfg)})))

(defrecord OpenAIResponsesProvider [config]
  proto/ILLMProvider
  (provider-name [_] (:provider-name @config))
  (call-llm [_ llm-config messages tools]
    (call! (merge @config llm-config) messages tools))
  (call-llm-stream [_ llm-config messages tools on-token]
    (call-stream! (merge @config llm-config) messages tools on-token))
  (extract-tool-calls [_ raw] (parser/extract-tool-calls raw))
  (extract-text [_ raw] (parser/extract-text raw))
  (build-tool-result [_ tool-id content]
    {:role "tool" :tool_call_id tool-id
     :content (if (string? content) content (pr-str content))})
  (supports-function-calling? [_] true)
  (supports-stream? [_] true)
  (tool->schema [_ tool] (schema/tool->schema tool))

  proto/IReplayableResponse
  (replay-blocks [_ raw] (wire/replay-blocks raw))

  proto/IAsyncLLMProvider
  (call-llm-async [_ llm-config messages tools]
    (let [cfg (merge @config llm-config)]
      (flt/fmap (http/post-deferred (api-url cfg)
                                    :headers (headers cfg)
                                    :body (build-body cfg messages tools)
                                    :timeout (or (:timeout cfg) 60000))
                (fn [resp]
                  (if (:success? resp)
                    (provider-response (:body resp) (:provider-name cfg))
                    (errors/throw! (http/response->error resp (:provider-name cfg))))))))
  (call-llm-stream-async [_ llm-config messages tools on-token]
    (let [cfg (merge @config llm-config)
          processor (stream/make-stream-processor)]
      (stream-client/post-stream-deferred
        (api-url cfg)
        {:headers (headers cfg)
         :body (assoc (build-body cfg messages tools) :stream true)
         :timeout (or (:timeout cfg) 300000)
         :parse-fn (:parse-fn processor)
         :process-fn (:process-fn processor)
         :make-initial-state (:make-initial-state processor)
         :build-response (fn [state]
                           (provider-response ((:build-response processor) state)
                                              (:provider-name cfg)))
         :on-token on-token
         :retry (:retry cfg)
         :provider (:provider-name cfg)}))))

(defn create-provider
  ([opts]
   (->OpenAIResponsesProvider
    (atom (merge default-endpoint
                 {:provider-name :openai-responses
                  :model "gpt-5"
                  :timeout 60000}
                 opts))))
  ([] (create-provider {})))
