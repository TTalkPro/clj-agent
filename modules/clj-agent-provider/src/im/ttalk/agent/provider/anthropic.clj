(ns im.ttalk.agent.provider.anthropic
  "Anthropic Provider 实现

   实现 ILLMProvider 协议，提供 Anthropic Claude API 的完整访问。

   支持功能：
   - 同步调用
   - 流式调用（同步阻塞）
   - 异步调用
   - 异步流式调用
   - 工具调用（Function Calling）
   - 结构化输出

   使用示例：

   (require '[im.ttalk.agent.provider.anthropic :as anthropic])

   (def provider (anthropic/create-provider))

   ;; 同步调用
   (anthropic/call-anthropic config messages tools)

   ;; 流式调用
   (anthropic/call-anthropic-stream config messages tools on-token)"
  (:require [im.ttalk.agent.provider.http.client :as http]
            [im.ttalk.agent.provider.http.retry :as retry]
            [im.ttalk.agent.model :as proto]
            [im.ttalk.agent.model.types :as types]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.provider.cache :as cache]
            [im.ttalk.agent.provider.schema.anthropic :as schema]
            [im.ttalk.agent.provider.wire.anthropic :as wire]
            [im.ttalk.agent.provider.stream.anthropic :as stream]))

;;; ============================================================
;;; 配置
;;; ============================================================

(def ^:private default-opts
  "默认 Anthropic API 选项"
  (atom {:api-key (System/getenv "ANTHROPIC_API_KEY")
         :base-url "https://api.anthropic.com"
         :impl :anthropic}))

(defn- get-api-url
  "获取 API URL（支持自定义 base-url）"
  []
  (str (:base-url @default-opts) "/v1/messages"))

;;; ============================================================
;;; 响应解析
;;; ============================================================

(defn- filter-by-type
  "按类型过滤内容块"
  [content type]
  (filter #(= (:type %) type) content))

(defn extract-tool-calls
  "从 Anthropic 响应中提取工具调用

   参数：
   - response: Anthropic API 响应

   返回：
   工具调用列表 [{:id \"...\" :name :keyword :input {...}}]"
  [response]
  (->> (:content response)
       (filter #(= (:type %) "tool_use"))
       (mapv (fn [{:keys [id name input]}]
               (types/make-tool-call id name input)))))

(defn extract-text
  "从 Anthropic 响应中提取文本

   参数：
   - response: Anthropic API 响应

   返回：
   文本字符串"
  [response]
  (->> (:content response)
       (filter #(= (:type %) "text"))
       (map :text)
       (clojure.string/join "\n")))

(defn has-tool-calls?
  "检查 Anthropic 响应是否包含工具调用

   参数：
   - response: Anthropic API 响应

   返回：
   boolean"
  [response]
  (boolean (seq (extract-tool-calls response))))

(defn valid-response?
  "检查 Anthropic 响应是否有效

   参数：
   - response: Anthropic API 响应

   返回：
   boolean"
  [response]
  (and (map? response)
       (contains? response :content)
       (coll? (:content response))))

(defn get-stop-reason
  "获取停止原因

   参数：
   - response: Anthropic API 响应

   返回：
   关键字 (:end_turn, :tool_use, :max_tokens, :stop_sequence)"
  [response]
  (keyword (:stop_reason response)))

(defn get-usage
  "获取令牌使用情况

   参数：
   - response: Anthropic API 响应

   返回：
   usage map {:input_tokens n :output_tokens m}"
  [response]
  (:usage response))

(defn normalize-response
  "将原始 Anthropic API 响应标准化为统一格式

   参数：
   - response: 原始 Anthropic API 响应

   返回：
   统一响应格式：
   {:text \"...\"
    :tool-calls [{:id :name :input}]
    :usage {:input-tokens n :output-tokens m :total-tokens t}
    :finish-reason :stop | :tool-use | :max-tokens | ...
    :model \"...\"
    :id \"...\"
    :provider :anthropic
    :raw-response {...}}

   示例：
   (normalize-response raw-response)
   ; => {:text \"你好\"
   ;     :tool-calls []
   ;     :usage {:input-tokens 100 :output-tokens 50 :total-tokens 150}
   ;     :finish-reason :stop
   ;     :provider :anthropic}"
  [raw-response]
  (response/make-response
    :id (:id raw-response)
    :model (:model raw-response)
    :text (extract-text raw-response)
    :tool-calls (let [tc (extract-tool-calls raw-response)]
                  (when (seq tc) tc))
    :usage (get-usage raw-response)
    :finish-reason (:stop_reason raw-response)
    :provider :anthropic
    :raw-response raw-response))

;;; ============================================================
;;; API 参数构建
;;; ============================================================

(defn- build-params
  "构建 Anthropic API 请求参数

   参数：
   - config:       配置 map，支持：
     - 必需：:model
     - 采样/提示词控制：:max-tokens :temperature :top-p :top-k
       :stop（=> stop_sequences）:metadata :thinking
     - 工具：:tool-choice :tools（预置 schema）
     - 系统提示：:system-prompt
     - 缓存：:cache-strategy（见 cache 命名空间）:cache-ttl
   - messages:     消息列表（Anthropic wire 形态）
   - tool-schemas: 工具 schema 列表

   返回：
   API 参数 map（已按 cache-strategy 注入 cache_control）"
  [{:keys [model max-tokens system-prompt tool-choice tools
           temperature top-p top-k stop metadata thinking
           cache-strategy cache-ttl]} messages tool-schemas]
  (let [all-tools (into (vec tools) tool-schemas)
        max-tokens (or max-tokens 4096)
        params (cond-> {:model model
                        :max_tokens max-tokens
                        :messages messages}
                 (seq all-tools)     (assoc :tools all-tools)
                 system-prompt       (assoc :system system-prompt)
                 tool-choice         (assoc :tool_choice tool-choice)
                 (some? temperature) (assoc :temperature temperature)
                 (some? top-p)       (assoc :top_p top-p)
                 (some? top-k)       (assoc :top_k top-k)
                 (seq stop)          (assoc :stop_sequences (vec stop))
                 metadata            (assoc :metadata metadata)
                 thinking            (assoc :thinking thinking))]
    (cache/apply-anthropic-cache params cache-strategy cache-ttl)))

;;; ============================================================
;;; 同步 API 调用
;;; ============================================================

(defn call-anthropic
  "调用 Anthropic API（同步）

   参数：
   - config:   配置 map {:model \"...\" :max-tokens 4096 ...}
   - messages: 消息列表
   - tools:    工具列表

   返回：
   Anthropic API 响应

   示例：
   (call-anthropic
     {:model \"claude-sonnet-4-20250514\" :max-tokens 4096}
     [{:role \"user\" :content \"你好\"}]
     [])"
  [config messages tools]
  (let [tool-schemas (schema/tools->schemas tools)
        params (build-params config messages tool-schemas)
        api-url (get-api-url)
        api-key (or (:api-key config) (:api-key @default-opts))
        timeout (or (:timeout config) 120000)
        headers {"Content-Type" "application/json"
                 "x-api-key" api-key
                 "anthropic-version" "2023-06-01"}
        ;; opt-in 重试：仅当 config 含 :retry 时启用
        response (retry/maybe-with-retry
                   config
                   #(http/post api-url
                               :headers headers
                               :body params
                               :timeout timeout))]
    (if (:success? response)
      (:body response)
      (throw (ex-info "Anthropic API call failed"
                      {:status (:status response)
                       :body (:body response)
                       :error (:error response)
                       :headers (:headers response)
                       :request-id (:request-id response)
                       :provider :anthropic
                       :retryable? (retry/transient-response? response)})))))

;;; ============================================================
;;; 异步 API 调用
;;; ============================================================

(defn call-anthropic-async
  "异步调用 Anthropic API

   参数：
   - config:   配置 map
   - messages: 消息列表
   - tools:    工具列表
   - callback: 回调函数 (fn [response] ...)

   返回：
   nil（结果通过 callback 返回）"
  [config messages tools callback]
  (let [tool-schemas (schema/tools->schemas tools)
        params (build-params config messages tool-schemas)
        api-url (get-api-url)
        api-key (or (:api-key config) (:api-key @default-opts))
        headers {"Content-Type" "application/json"
                 "x-api-key" api-key
                 "anthropic-version" "2023-06-01"}]
    (http/post-async api-url
                     callback
                     :headers headers
                     :body params
                     :timeout 120000)))

;;; ============================================================
;;; 流式 API 调用
;;; ============================================================

(defn call-anthropic-stream
  "流式调用 Anthropic API（同步，阻塞当前线程）

   参数：
   - config:   配置 map
   - messages: 消息列表
   - tools:    工具列表
   - on-token: 回调函数 (fn [{:keys [token index accumulated]}] ...)

   返回：
   最终完整响应"
  [config messages tools on-token]
  (let [tool-schemas (schema/tools->schemas tools)
        params (-> (build-params config messages tool-schemas)
                   (assoc :stream true))
        api-url (get-api-url)
        api-key (or (:api-key config) (:api-key @default-opts))
        headers {"Content-Type" "application/json"
                 "x-api-key" api-key
                 "anthropic-version" "2023-06-01"}
        response (http/post-stream api-url
                                   :headers headers
                                   :body params
                                   :timeout 120000)
        reader (clojure.java.io/reader (:body response))
        final-state (http/process-sse-stream
                      reader
                      stream/parse-sse-line
                      stream/process-event
                      (stream/make-initial-state)
                      on-token)]
    (stream/build-response final-state)))

(defn call-anthropic-stream-async
  "异步流式调用 Anthropic API（非阻塞）

   完全异步处理，适合与异步服务器集成。

   参数：
   - config:      配置 map
   - messages:    消息列表
   - tools:       工具列表
   - on-token:    token 回调 (fn [{:keys [token]}] ...)
   - on-complete: 完成回调 (fn [response] ...)
   - on-error:    错误回调 (fn [error] ...)（可选）

   返回：
   nil（所有结果通过回调返回）"
  [config messages tools on-token on-complete & [on-error]]
  (let [tool-schemas (schema/tools->schemas tools)
        params (-> (build-params config messages tool-schemas)
                   (assoc :stream true))
        api-url (get-api-url)
        api-key (or (:api-key config) (:api-key @default-opts))
        headers {"Content-Type" "application/json"
                 "x-api-key" api-key
                 "anthropic-version" "2023-06-01"}]
    (http/post-stream-async api-url
                            :headers headers
                            :body params
                            :timeout 120000
                            :parse-fn stream/parse-sse-line
                            :process-fn stream/process-event
                            :initial-state (stream/make-initial-state)
                            :on-token on-token
                            :on-complete (fn [final-state]
                                           (on-complete (stream/build-response final-state)))
                            :on-error on-error)))

;;; ============================================================
;;; AnthropicProvider 实现
;;; ============================================================

(defrecord AnthropicProvider []
  proto/ILLMProvider
  ;; 基本信息
  (provider-name [_] :anthropic)

  ;; 核心 API（协议以中立消息为边界：内部转 Anthropic wire，system 提至顶层）
  (call-llm [_ config messages tools]
    (let [{:keys [messages system]} (wire/neutral->wire messages)
          config (cond-> config system (assoc :system-prompt system))]
      (call-anthropic config messages tools)))

  ;; 流式 API
  (call-llm-stream [_ config messages tools on-token]
    (let [{:keys [messages system]} (wire/neutral->wire messages)
          config (cond-> config system (assoc :system-prompt system))]
      (call-anthropic-stream config messages tools on-token)))

  ;; 响应解析
  (extract-tool-calls [_ response]
    (extract-tool-calls response))

  (extract-text [_ response]
    (extract-text response))

  (build-tool-result [_ tool-id content]
    {:role "user"
     :content [{:type "tool_result"
                :tool_use_id tool-id
                :content (if (string? content) content (pr-str content))}]})

  ;; 功能支持
  (supports-function-calling? [_] true)
  (supports-stream? [_] true)

  ;; Schema 转换
  (tool->schema [_ tool]
    (schema/tool->schema tool))

  ;; 消息构建
  (build-assistant-message [_ response]
    {:role "assistant" :content (:content response)})

  (build-result-messages [_ assistant-msg tool-results]
    [assistant-msg
     {:role "user"
      :content (mapv (fn [{:keys [tool-id result error]}]
                       {:type "tool_result"
                        :tool_use_id tool-id
                        :content (or result (str "Error: " error))})
                     tool-results)}]))

;;; ============================================================
;;; 工厂函数
;;; ============================================================

(defn create-provider
  "创建 Anthropic Provider 实例

   参数：
   - opts: API 选项（可选）{:api-key \"...\"}

   返回：
   AnthropicProvider record

   示例：
   (def provider (create-provider))
   (def provider (create-provider {:api-key \"sk-ant-...\"}))"
  ([] (->AnthropicProvider))
  ([opts]
   (swap! default-opts merge opts)
   (->AnthropicProvider)))

