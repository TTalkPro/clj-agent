(ns im.ttalk.agent.llm.provider.openai-compat
  "OpenAI 兼容 API 调用

   提供 OpenAI 风格 API 的通用调用代码，供多个 Provider 复用：
   - OpenAI
   - 智谱 AI (ZhiPu)
   - Ollama
   - 其他 OpenAI 兼容 API

   使用示例：

   (require '[im.ttalk.agent.llm.provider.openai-compat :as compat])

   ;; 同步调用
   (compat/call-api api-url api-key config messages tools)

   ;; 流式调用
   (compat/call-api-stream api-url api-key config messages tools on-token)"
  (:require [im.ttalk.agent.core.http.client :as http]
            [im.ttalk.agent.llm.schema.openai :as schema]
            [im.ttalk.agent.llm.stream.openai :as stream]))

;;; ============================================================
;;; 参数构建
;;; ============================================================

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
   - config:       配置 map
     {:model \"...\", :max-tokens n, :temperature f, :top-p f,
      :system-prompt \"...\", :response-format {...}}
   - messages:     消息列表
   - tool-schemas: 工具 schema 列表

   返回：
   API 参数 map"
  [{:keys [model max-tokens system-prompt temperature top-p stop response-format]
    :or {temperature 0.7 top-p 0.9}}
   messages tool-schemas]
  (let [msgs (build-messages system-prompt messages)]
    (cond-> {:model model
             :max_tokens max-tokens
             :temperature temperature
             :top_p top-p
             :messages msgs}
      (seq tool-schemas) (assoc :tools tool-schemas)
      (seq stop) (assoc :stop stop)
      response-format (assoc :response_format response-format))))

;;; ============================================================
;;; 消息构建
;;; ============================================================

(defn build-assistant-message
  "构建 assistant 消息

   参数：
   - response: API 响应

   返回：
   assistant 消息 map"
  [response]
  (let [msg (get-in response [:choices 0 :message])]
    {:role "assistant"
     :content (:content msg)
     :tool_calls (:tool_calls msg)}))

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
                       (:extra-headers opts))]
    (:body (http/post api-url
                      :headers headers
                      :body params
                      :timeout timeout))))

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
        timeout (or (:timeout opts) 120000)
        headers (merge {"Authorization" (str "Bearer " api-key)}
                       (:extra-headers opts))
        ;; 创建流处理器
        {:keys [process-fn get-id get-model]} (stream/make-stream-processor)
        ;; 发起流式请求
        response (http/post-stream api-url
                                   :headers headers
                                   :body params
                                   :timeout timeout)]
    ;; 处理 SSE 流
    (let [final-state (http/process-sse-stream
                        (:body response)
                        stream/parse-sse-line
                        process-fn
                        (stream/make-initial-state)
                        on-token)]
      ;; 构建最终响应
      (stream/build-response final-state
                             :id (get-id)
                             :model (get-model)))))

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
        timeout (or (:timeout opts) 120000)
        headers (merge {"Authorization" (str "Bearer " api-key)}
                       (:extra-headers opts))
        ;; 用于收集 id 和 model
        response-id (atom nil)
        response-model (atom nil)
        ;; 包装 process-fn
        wrapped-process-fn (fn [chunk state]
                             (when (:id chunk)
                               (reset! response-id (:id chunk)))
                             (when (:model chunk)
                               (reset! response-model (:model chunk)))
                             (stream/process-chunk chunk state))]
    (http/post-stream-async api-url
                            :headers headers
                            :body params
                            :timeout timeout
                            :parse-fn stream/parse-sse-line
                            :process-fn wrapped-process-fn
                            :initial-state (stream/make-initial-state)
                            :on-token on-token
                            :on-complete (fn [final-state]
                                           (on-complete
                                             (stream/build-response final-state
                                                                    :id @response-id
                                                                    :model @response-model)))
                            :on-error on-error)))
