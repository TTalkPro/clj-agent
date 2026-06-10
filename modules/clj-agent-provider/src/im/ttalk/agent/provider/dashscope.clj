(ns im.ttalk.agent.provider.bailian
  "阿里云百炼 (DashScope) Provider 实现

   实现 ILLMProvider 协议，使用原生 DashScope API。

   API 文档：https://help.aliyun.com/zh/model-studio/dashscope-api-reference/

   支持的模型：
   - Qwen Max 系列: qwen3-max, qwen-max, qwen-max-latest
   - Qwen Plus 系列: qwen-plus, qwen-plus-latest
   - Qwen Turbo 系列: qwen-turbo, qwen-turbo-latest
   - Qwen Coder 系列: qwen3-coder-plus, qwen-coder-plus
   - QwQ 系列: qwq-plus

   使用示例：

   (require '[im.ttalk.agent.provider.bailian :as bailian])

   (def provider (bailian/create-provider))

   ;; 同步调用
   (bailian/call-bailian config messages tools)"
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [org.httpkit.client :as http]
            [im.ttalk.agent.model :as proto]
            [im.ttalk.agent.model.error :as errors]))

;;; ============================================================
;;; 配置
;;; ============================================================

(def ^:private default-base-url
  "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation")

(def ^:private default-timeout 120000)

(def ^:private default-model "qwen-plus")

(defn- get-api-key
  "获取 API Key

   优先级：opts 中的 :api-key > BAILIAN_API_KEY > DASHSCOPE_API_KEY"
  [opts]
  (or (:api-key opts)
      (System/getenv "BAILIAN_API_KEY")
      (System/getenv "DASHSCOPE_API_KEY")))

;;; ============================================================
;;; 请求/响应转换
;;; ============================================================

(defn- tool->dashscope-schema
  "将工具定义转换为 DashScope 格式"
  [tool]
  {:type "function"
   :function {:name (name (:name tool))
              :description (:description tool)
              :parameters (or (:parameters tool)
                              {:type "object"
                               :properties {}
                               :required []})}})

(defn- build-request
  "构建 DashScope API 请求体

   参数:
   - llm-config: {:model :max-tokens :temperature ...}
   - messages: [{:role :content} ...]
   - tools: 工具定义列表"
  [llm-config messages tools]
  (let [model (or (:model llm-config) default-model)
        params (cond-> {:result_format "message"}
                 (:max-tokens llm-config)
                 (assoc :max_tokens (:max-tokens llm-config))

                 (:temperature llm-config)
                 (assoc :temperature (:temperature llm-config))

                 (:top-p llm-config)
                 (assoc :top_p (:top-p llm-config))

                 (seq tools)
                 (assoc :tools (mapv tool->dashscope-schema tools)))]
    {:model model
     :input {:messages messages}
     :parameters params}))

(defn- parse-response
  "解析 DashScope API 响应为统一格式

   DashScope 响应格式:
   {:output {:choices [{:message {:role :content :tool_calls [...]}}]}
    :usage {...}
    :request_id ...}

   转换为 OpenAI 兼容格式供 kernel 使用:
   {:choices [{:message {:role :content :tool_calls [...]}}]
    :usage {...}}"
  [response]
  (if (:error response)
    response
    (let [output (:output response)
          choices (or (:choices output)
                      ;; 旧格式兼容
                      (when (:text output)
                        [{:message {:role "assistant" :content (:text output)}
                          :finish_reason (:finish_reason output)}]))]
      {:choices choices
       :usage (:usage response)
       :model (or (:model response) "unknown")
       :id (or (:request_id response) "unknown")})))

;;; ============================================================
;;; HTTP 调用
;;; ============================================================

(defn- do-request
  "执行 HTTP 请求

   参数:
   - url: API URL
   - api-key: API Key
   - body: 请求体
   - opts: {:timeout ...}"
  [url api-key body opts]
  (let [timeout (or (:timeout opts) default-timeout)
        response @(http/request
                    {:method :post
                     :url url
                     :headers {"Authorization" (str "Bearer " api-key)
                               "Content-Type" "application/json"}
                     :body (json/generate-string body)
                     :timeout timeout})]
    ;; D5：失败一律抛 canonical error（ex-info data 即 errors/error map）。
    ;; 连接级错误（DNS/超时/重置）：网络层失败，可重试。
    (when-let [err (:error response)]
      (errors/throw! (errors/error :network-error
                                   (str "连接失败: " err)
                                   {:provider :bailian})))
    (let [status (:status response)
          body-str (:body response)]
      ;; HTTP 4xx/5xx：DashScope 错误体形如 {:code "InvalidApiKey" :message ... :request_id ...}，
      ;; 没有 :error 键，绝不能当正常响应返回（否则鉴权/参数错误会静默变成空响应）。
      (when (>= status 400)
        (let [parsed (try (json/parse-string body-str true) (catch Exception _ nil))]
          ;; 用 canonical 的 http-response->error 分类（401/403→auth 不可重试；429→限流；5xx→可重试），
          ;; DashScope 的 :code/:message/:request_id 并入 :context 供排查。
          (errors/throw!
            (-> (errors/http-response->error {:status status :body (or parsed body-str)} :bailian)
                (assoc :context {:code (:code parsed)
                                 :request-id (:request_id parsed)
                                 :body (or parsed body-str)})))))
      (try
        (json/parse-string body-str true)
        (catch Exception e
          (errors/throw! (errors/error :parse-error
                                       (str "DashScope 响应 JSON 解析失败: " (.getMessage e))
                                       {:provider :bailian :cause e})))))))

;;; ============================================================
;;; 同步 API 调用
;;; ============================================================

(defn call-bailian
  "调用百炼 API（同步）

   参数：
   - config:   配置 map {:model \"qwen-plus\" :max-tokens 4096 ...}
   - messages: 消息列表 [{:role \"user\" :content \"...\"}]
   - tools:    工具列表

   返回：
   统一格式响应（OpenAI 兼容格式）

   示例：
   (call-bailian
     {:model \"qwen-plus\" :max-tokens 4096}
     [{:role \"user\" :content \"你好\"}]
     [])"
  ([config messages tools]
   (call-bailian config messages tools {}))
  ([config messages tools opts]
   (let [api-key (get-api-key opts)
         url (or (:base-url opts) default-base-url)
         body (build-request config messages tools)
         response (do-request url api-key body opts)]
     (parse-response response))))

;;; ============================================================
;;; 异步 API 调用
;;; ============================================================

(defn call-bailian-async
  "异步调用百炼 API

   参数：
   - config:   配置 map
   - messages: 消息列表
   - tools:    工具列表
   - callback: 回调函数 (fn [response] ...)
   - opts:     选项 map

   返回：
   nil（结果通过 callback 返回）"
  ([config messages tools callback]
   (call-bailian-async config messages tools callback {}))
  ([config messages tools callback opts]
   (future
     (try
       (let [result (call-bailian config messages tools opts)]
         (callback result))
       (catch Exception e
         (callback {:error {:message (.getMessage e)
                            :type "exception"}}))))
   nil))

;;; ============================================================
;;; Provider Record
;;; ============================================================

(defrecord BailianProvider [opts]
  proto/ILLMProvider

  (provider-name [_] :bailian)

  (call-llm [_ llm-config messages tools]
    (call-bailian llm-config messages tools opts))

  (call-llm-stream [_ _llm-config _messages _tools _on-token]
    ;; DashScope 原生流式（SSE / X-DashScope-SSE）尚未实现。显式抛出，
    ;; 避免静默回退到同步、on-token 永不触发导致上层 UI 误判（supports-stream? 已返回 false）。
    ;; D5：抛 canonical error（:validation-error，明确不可重试），纳入统一错误通道；
    ;; 不再裸抛 UnsupportedOperationException（无 :retryable? 会被误归为可重试）。
    (errors/throw! (errors/error :validation-error
                                 "bailian provider 暂不支持流式调用（supports-stream? => false），请用 call-llm 同步调用"
                                 {:provider :bailian :retryable? false :feature :stream})))

  (extract-tool-calls [_ response]
    (let [message (get-in response [:choices 0 :message])
          tool-calls (:tool_calls message)]
      (when (seq tool-calls)
        (mapv (fn [tc]
                {:id (:id tc)
                 :name (keyword (get-in tc [:function :name]))
                 :input (try
                          (json/parse-string (get-in tc [:function :arguments]) true)
                          (catch Exception _
                            {}))})
              tool-calls))))

  (extract-text [_ response]
    (get-in response [:choices 0 :message :content]))

  (build-tool-result [_ tool-id content]
    {:role "tool"
     :tool_call_id tool-id
     :content (if (string? content) content (json/generate-string content))})

  (supports-function-calling? [_] true)

  (supports-stream? [_] false)  ;; 暂不支持流式

  (tool->schema [_ tool]
    (tool->dashscope-schema tool)))

;;; ============================================================
;;; 工厂函数
;;; ============================================================

(defn create-provider
  "创建百炼 Provider 实例

   参数：
   - opts: API 选项（可选）
     {:api-key  \"...\"  ;; 覆盖环境变量
      :base-url \"...\"  ;; 覆盖默认 URL}

   环境变量（按优先级）：
   - BAILIAN_API_KEY
   - DASHSCOPE_API_KEY

   返回：
   BailianProvider record

   示例：
   (def provider (create-provider))
   (def provider (create-provider {:api-key \"sk-...\"}))"
  ([] (->BailianProvider {}))
  ([opts] (->BailianProvider opts)))
