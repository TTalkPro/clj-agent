(ns im.ttalk.agent.provider.common.openai-compat
  "OpenAI 兼容 API 调用

   提供 OpenAI 风格 API 的通用调用代码，供多个 Provider 复用：
   - OpenAI
   - 智谱 AI (ZhiPu)
   - Ollama
   - 其他 OpenAI 兼容 API

   使用示例：

   (require '[im.ttalk.agent.provider.common.openai-compat :as compat])

   ;; 同步调用
   (compat/call-api api-url api-key config messages tools)

   ;; 流式调用
   (compat/call-api-stream api-url api-key config messages tools on-token)"
  (:require [im.ttalk.agent.provider.http.client :as http]
            [im.ttalk.agent.provider.http.stream-client :as stream-client]
            [im.ttalk.agent.provider.http.retry :as retry]
            [im.ttalk.agent.provider.schema.openai :as schema]
            [im.ttalk.agent.provider.stream.openai :as stream]
            [im.ttalk.agent.model.error :as errors]
            [im.ttalk.agent.streaming :as streaming]))

;;; ============================================================
;;; 参数构建
;;; ============================================================

(defn- ->wire-tool-choice
  "将 core 下发的中立 tool-choice 翻译为 OpenAI wire 形态。
   关键字 :auto/:required/:none → 字符串；其余（字符串 / {:type \"function\" ...} 指定工具）原样透传。"
  [tc]
  (case tc
    :auto "auto"
    :required "required"
    :none "none"
    tc))

(defn build-messages
  "构建消息列表（含系统提示）

   参数：
   - system-prompt: 系统提示（可选）
   - messages:      消息列表

   返回：
   完整消息列表"
  [system-prompt messages]
  (if system-prompt
    (into [{:role "system" :content system-prompt}] messages)
    (vec messages)))

(defn build-params
  "构建 API 请求参数

   参数：
   - config:       配置 map，支持：
     - 必需：:model
     - 采样/提示词控制：:max-tokens :temperature :top-p :stop
       :frequency-penalty :presence-penalty :seed :n :tool-choice :user
       :logprobs :top-logprobs :thinking（GLM 等支持思考开关的模型）
     - 工具调用：:tool-choice :parallel-tool-calls（并行工具调用开关）
     - 推理控制：:reasoning-effort（o 系列 / GPT-5）:verbosity（GPT-5 输出冗长度）
     - 多模态输出：:modalities（如 [text audio] 文本+语音）:audio（{:voice .. :format ..}）
     - 系统提示：:system-prompt
     - 结构化输出：:response-format（json_object 或 json_schema+strict 模式）
     - 专有参数逃生通道：:extra-body（map，直接 merge 进请求体，覆盖各家私有字段）
   - messages:     消息列表
   - tool-schemas: 工具 schema 列表

   返回：
   API 参数 map

   说明：
   - temperature/top_p 改为「存在才设」—— 不再强塞默认值（推理类模型对此敏感）。
   - OpenAI 兼容协议的 prompt caching 是自动的，无需 cache_control；命中情况见
     响应 usage 的 prompt_tokens_details.cached_tokens（OpenAI）/ prompt_cache_hit_tokens（DeepSeek）。"
  [{:keys [model max-tokens system-prompt temperature top-p stop response-format
           frequency-penalty presence-penalty seed n tool-choice user
           logprobs top-logprobs thinking parallel-tool-calls reasoning-effort verbosity
           modalities audio
           do-sample tool-stream request-id user-id stream-options extra-body]}
   messages tool-schemas]
  (let [msgs (build-messages system-prompt messages)]
    (cond-> {:model model
             :messages msgs}
      max-tokens                (assoc :max_tokens max-tokens)
      (some? temperature)       (assoc :temperature temperature)
      (some? top-p)             (assoc :top_p top-p)
      (seq tool-schemas)        (assoc :tools tool-schemas)
      tool-choice               (assoc :tool_choice (->wire-tool-choice tool-choice))
      ;; 并行工具调用开关（OpenAI：默认 true；置 false 强制一次一个工具）
      (some? parallel-tool-calls) (assoc :parallel_tool_calls parallel-tool-calls)
      (seq stop)                (assoc :stop stop)
      ;; 结构化输出：response-format 透传（{:type "json_object"} 或
      ;; {:type "json_schema" :json_schema {:name .. :strict true :schema {...}}}）
      response-format           (assoc :response_format response-format)
      ;; 推理档位（o 系列 / GPT-5：low|medium|high；仅显式提供时发送）
      reasoning-effort          (assoc :reasoning_effort reasoning-effort)
      ;; 输出冗长度（GPT-5：low|medium|high）
      verbosity                 (assoc :verbosity verbosity)
      ;; 多模态输出（gpt-4o-audio 等）：modalities ["text" "audio"]、audio {:voice .. :format ..}
      (seq modalities)          (assoc :modalities modalities)
      audio                     (assoc :audio audio)
      (some? frequency-penalty) (assoc :frequency_penalty frequency-penalty)
      (some? presence-penalty)  (assoc :presence_penalty presence-penalty)
      (some? seed)              (assoc :seed seed)
      (some? n)                 (assoc :n n)
      user                      (assoc :user user)
      (some? logprobs)          (assoc :logprobs logprobs)
      (some? top-logprobs)      (assoc :top_logprobs top-logprobs)
      ;; 思考/推理开关（GLM 系列：{:type "enabled"|"disabled" :clear_thinking bool}；
      ;; 仅在显式提供时发送，不影响其他 provider）
      thinking                  (assoc :thinking thinking)
      ;; GLM 对话补全文档字段（仅显式提供时发送）
      (some? do-sample)         (assoc :do_sample do-sample)
      (some? tool-stream)       (assoc :tool_stream tool-stream)
      request-id                (assoc :request_id request-id)
      user-id                   (assoc :user_id user-id)
      ;; 流式 usage 开关（OpenAI 标准 {:include_usage true}；DeepSeek 末块原生带 usage）
      stream-options            (assoc :stream_options stream-options)
      (map? extra-body)         (merge extra-body))))

;;; ============================================================
;;; 消息构建
;;; ============================================================

(defn build-tool-result
  "构建工具结果消息

   参数：
   - tool-id: 工具调用 ID
   - content: 结果内容

   返回：
   tool 消息 map"
  [tool-id content]
  {:role "tool"
   :tool_call_id tool-id
   :content (if (string? content) content (pr-str content))})

;;; ============================================================
;;; 错误归一化
;;; ============================================================

(defn- response->error
  "把失败的 HTTP 响应转为 canonical error（D5：抛出的 ex-info data 即 canonical error map，
   保留 body/headers/request-id 于 :context 供排查）。"
  [response provider]
  (let [status (or (:status response) 0)
        base (if (and (zero? status) (:error response))
               ;; 连接级失败（无 HTTP 状态码）：网络错误，可重试
               (errors/error :network-error
                             (str "连接失败: " (:error response))
                             {:provider provider})
               ;; HTTP 4xx/5xx：按状态码分类（401/403→auth 不可重试；429→限流；5xx→provider 可重试）
               (errors/http-response->error response provider))]
    (assoc base :context (select-keys response [:body :headers :request-id :error]))))

;;; ============================================================
;;; 同步调用
;;; ============================================================

(defn call-api
  "调用 OpenAI 兼容 API（同步）

   参数：
   - api-url:  API 端点 URL
   - api-key:  API 密钥
   - config:   请求配置
   - messages: 消息列表
   - tools:    工具列表
   - opts:     可选配置 {:timeout 120000, :extra-headers {}}

   返回：
   API 响应 map

   示例：
   (call-api \"https://api.openai.com/v1/chat/completions\"
             \"sk-xxx\"
             {:model \"gpt-4\" :max-tokens 4096}
             [{:role \"user\" :content \"你好\"}]
             [])"
  [api-url api-key config messages tools & [opts]]
  (let [tool-schemas (schema/tools->schemas tools)
        params (build-params config messages tool-schemas)
        timeout (or (:timeout opts) 120000)
        headers (merge {"Authorization" (str "Bearer " api-key)}
                       (:extra-headers config)
                       (:extra-headers opts))
        ;; opt-in 重试：config 含 :retry 时启用
        response (retry/maybe-with-retry
                   config
                   #(http/post api-url
                               :headers headers
                               :body params
                               :timeout timeout))]
    (if (:success? response)
      (:body response)
      (errors/throw! (response->error response (:provider-name config))))))

;;; ============================================================
;;; 流式调用
;;; ============================================================

(defn call-api-stream
  "调用 OpenAI 兼容 API（流式，同步）

   参数：
   - api-url:  API 端点 URL
   - api-key:  API 密钥
   - config:   请求配置
   - messages: 消息列表
   - tools:    工具列表
   - on-token: token 回调函数 (fn [{:keys [token index accumulated]}] ...)
   - opts:     可选配置 {:timeout 120000, :extra-headers {}}

   返回：
   与非流式调用兼容的响应格式

   示例：
   (call-api-stream url key config messages tools
     (fn [{:keys [token]}]
       (print token)
       (flush)))"
  [api-url api-key config messages tools on-token & [opts]]
  (let [tool-schemas (schema/tools->schemas tools)
        params (-> (build-params config messages tool-schemas)
                   (assoc :stream true))
        ;; 真流式：长生成给足超时（java.net.http 的 timeout 是 total）
        timeout (or (:timeout opts) 300000)
        headers (merge {"Authorization" (str "Bearer " api-key)}
                       (:extra-headers config)
                       (:extra-headers opts))
        {:keys [process-fn get-id get-model]} (stream/make-stream-processor)
        result (promise)
        err    (promise)
        ;; java.net.http 真增量传输：on-token 随每行 SSE 实时触发（不再是 http-kit 伪流式）
        {:keys [future cancel]}
        (stream-client/post-stream-async
          api-url
          {:headers headers :body params :timeout timeout
           :parse-fn stream/parse-sse-line
           :process-fn process-fn
           :initial-state (stream/make-initial-state)
           :on-token on-token
           :on-complete (fn [state]
                          (deliver result (stream/build-response state
                                                                 :id (get-id)
                                                                 :model (get-model))))
           :on-error (fn [e] (deliver err e))
           :provider (:provider-name config)})
        cancelled? (atom false)
        ;; 包装的 cancel：先标记本地 cancelled? 再取消上游；取消时 @future 各种异常都吞，
        ;; cond 里 cancelled? 优先于 err（取消会触发 onError 网络异常）。
        _ (streaming/register-cancel! (fn [] (reset! cancelled? true) (when cancel (cancel))))]
    (try @future                         ;; 阻塞直到流结束（保持同步签名）
         (catch Throwable _ nil))
    (cond
      @cancelled?        (stream/build-response (stream/make-initial-state))  ;; 取消：空响应（token 已流出），不抛错
      (realized? err)    (errors/throw! @err)
      (realized? result) @result
      :else (errors/throw! (errors/error :provider-error
                                         "流式响应未产出结果"
                                         {:provider (:provider-name config)})))))

;;; ============================================================
;;; 异步调用
;;; ============================================================

(defn call-api-async
  "调用 OpenAI 兼容 API（异步）

   参数：
   - api-url:  API 端点 URL
   - api-key:  API 密钥
   - config:   请求配置
   - messages: 消息列表
   - tools:    工具列表
   - callback: 回调函数 (fn [response] ...)
   - opts:     可选配置 {:timeout 120000, :extra-headers {}}

   返回：
   nil（结果通过 callback 返回）

   示例：
   (call-api-async url key config messages tools
     (fn [resp]
       (if (:success? resp)
         (println \"Response:\" (:body resp))
         (println \"Error:\" (:error resp)))))"
  [api-url api-key config messages tools callback & [opts]]
  (let [tool-schemas (schema/tools->schemas tools)
        params (build-params config messages tool-schemas)
        timeout (or (:timeout opts) 120000)
        headers (merge {"Authorization" (str "Bearer " api-key)}
                       (:extra-headers opts))]
    (http/post-async api-url
                     callback
                     :headers headers
                     :body params
                     :timeout timeout)))

(defn call-api-stream-async
  "调用 OpenAI 兼容 API（异步流式）

   完全异步处理，适合与异步服务器集成。

   参数：
   - api-url:     API 端点 URL
   - api-key:     API 密钥
   - config:      请求配置
   - messages:    消息列表
   - tools:       工具列表
   - on-token:    token 回调 (fn [{:keys [token]}] ...)
   - on-complete: 完成回调 (fn [response] ...)
   - on-error:    错误回调 (fn [error] ...)（可选）
   - opts:        可选配置 {:timeout 120000, :extra-headers {}}

   返回：
   nil（所有结果通过回调返回）

   示例：
   (call-api-stream-async url key config messages tools
     (fn [{:keys [token]}] (print token) (flush))
     (fn [response] (println \"\\nDone!\"))
     (fn [error] (println \"Error:\" error)))"
  [api-url api-key config messages tools on-token on-complete & [on-error opts]]
  (let [tool-schemas (schema/tools->schemas tools)
        params (-> (build-params config messages tool-schemas)
                   (assoc :stream true))
        timeout (or (:timeout opts) 300000)
        headers (merge {"Authorization" (str "Bearer " api-key)}
                       (:extra-headers config)
                       (:extra-headers opts))
        {:keys [process-fn get-id get-model]} (stream/make-stream-processor)]
    ;; 真增量、非阻塞；返回 {:future :cancel}，cancel 供客户端断连/停止时取消上游
    (stream-client/post-stream-async
      api-url
      {:headers headers :body params :timeout timeout
       :parse-fn stream/parse-sse-line
       :process-fn process-fn
       :initial-state (stream/make-initial-state)
       :on-token on-token
       :on-complete (fn [final-state]
                      (on-complete (stream/build-response final-state
                                                          :id (get-id)
                                                          :model (get-model))))
       :on-error (or on-error (fn [_] nil))
       :provider (:provider-name config)})))
