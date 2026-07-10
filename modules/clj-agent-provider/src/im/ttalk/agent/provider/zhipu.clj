(ns im.ttalk.agent.provider.zhipu
  "智谱 GLM Provider —— 双协议支持。

   1. OpenAI 兼容（默认，create-provider）：
      https://open.bigmodel.cn/api/paas/v4/chat/completions（Bearer 鉴权）
      - 思考开关：config 传 :thinking {:type \"enabled\"|\"disabled\"
        :clear_thinking true}（GLM-4.5+ 思考系列）
      - 思维链经 message.reasoning_content 返回，已由统一响应层归一化到
        :reasoning 字段；流式推理 token 走回调 :reasoning-token

   2. Anthropic 兼容（create-anthropic-provider，官方推荐 coding/agent 场景，
      Claude Code 同款端点）：
      https://open.bigmodel.cn/api/anthropic/v1/messages（Bearer 鉴权）
      - 完整复用 anthropic provider 的请求/响应/流式/工具调用/缓存/重试机制
      - 思考内容为 thinking 内容块，自动归一化到 :reasoning

   3. 异步任务（GLM 对话补全异步方案）：
      submit-async（POST /async/chat/completions）→ query-async-result /
      await-async-result（GET /async-result/{id}，PROCESSING|SUCCESS|FAIL）

   对话补全文档字段（经 config 透传，存在才发送）：
   :temperature :top-p :max-tokens :stop :thinking :do-sample :tool-stream
   :request-id :user-id :response-format :tool-choice；
   tools 支持简单定义（自动包装为 function）与预置类型透传
   （{:type \"web_search\"/\"retrieval\"/\"mcp\" ...}）。
   finish_reason 的 sensitive/network_error/model_context_window_exceeded
   已归一化（:content-filter/:error/:context-window-exceeded）。

   模型：glm-5.1、glm-5-turbo、glm-5、glm-4.7、glm-4.7-flash、glm-4.6 等。
   需要 ZHIPU_API_KEY 或显式 :api-key。

   (require '[im.ttalk.agent.provider.zhipu :as zhipu])
   (def p (zhipu/create-provider {:api-key \"...\"}))            ;; OpenAI 协议
   (def pa (zhipu/create-anthropic-provider {:api-key \"...\"})) ;; Anthropic 协议
   ;; 异步：
   (let [{:keys [id]} (zhipu/submit-async {:model \"glm-4.7\"} msgs nil)]
     (zhipu/await-async-result {} id))"
  (:require [clojure.string :as str]
            [im.ttalk.agent.provider.common.base :as base]
            [im.ttalk.agent.provider.anthropic :as anthropic]
            [im.ttalk.agent.provider.common.openai-compat :as compat]
            [im.ttalk.agent.provider.http.client :as http]))

(set! *warn-on-reflection* true)

(def default-model "glm-4.7")

;;; ============================================================
;;; OpenAI 兼容（默认协议）
;;; ============================================================

(base/defprovider zhipu
  :base-url "https://open.bigmodel.cn/api/paas/v4"
  :env-key "ZHIPU_API_KEY"
  :default-model "glm-4.7"
  :require-api-key? true)

;;; ============================================================
;;; Anthropic 兼容（GLM coding/agent 推荐协议）
;;; ============================================================

(def ^:private zhipu-anthropic-endpoint
  "智谱 Anthropic 兼容端点配置"
  {:provider-name :zhipu
   :base-url "https://open.bigmodel.cn/api/anthropic"
   :api-path "/v1/messages"
   :auth-scheme :bearer
   :anthropic-version nil})

;;; ============================================================
;;; 异步对话补全（GLM 异步任务方案）
;;; ============================================================
;;; 文档：POST /paas/v4/async/chat/completions 提交任务，
;;;       GET  /paas/v4/async-result/{id}      查询结果。
;;; task_status: PROCESSING | SUCCESS | FAIL

(defn- base-url*
  [config]
  (-> (or (:base-url config) (:base-url @default-config) "")
      (str/replace #"/+$" "")))

(defn- api-key*
  [config]
  (or (:api-key config) (base/get-api-key default-config)))

(defn submit-async
  "提交异步对话补全任务（POST {base}/async/chat/completions）

   参数与同步 call-zhipu 一致（model/messages/thinking/tools 等全部字段可用，
   不支持 :stream）。

   返回任务回执：
   {:id \"...\" :request_id \"...\" :model \"...\" :task_status \"PROCESSING\"}"
  [config messages tools]
  (compat/call-api (str (base-url* config) "/async/chat/completions")
                   (api-key* config)
                   config messages tools
                   {:timeout (or (:timeout config) 120000)}))

(defn query-async-result
  "查询异步任务结果（GET {base}/async-result/{id}）

   返回：
   - PROCESSING -> {:task_status \"PROCESSING\" ...}
   - SUCCESS    -> 含 :choices/:usage 的完整结果（与同步响应同构，
                   可直接走 extract-text / normalize-response）
   - FAIL       -> {:task_status \"FAIL\" ...}

   HTTP 层失败抛 ex-info。"
  [config task-id]
  (let [resp (http/get (str (base-url* config) "/async-result/" task-id)
                       :headers {"Authorization" (str "Bearer " (api-key* config))}
                       :timeout (or (:timeout config) 30000))]
    (if (:success? resp)
      (:body resp)
      (throw (ex-info "zhipu async result query failed"
                      {:status (:status resp)
                       :body (:body resp)
                       :error (:error resp)
                       :request-id (:request-id resp)
                       :provider :zhipu})))))

(defn await-async-result
  "轮询异步任务直至完成

   选项：
   - :poll-interval-ms 轮询间隔（默认 2000）
   - :timeout-ms       总超时（默认 300000，5 分钟）

   返回：SUCCESS 时的完整结果。
   抛出：task_status=FAIL 或超时时抛 ex-info。"
  [config task-id & {:keys [poll-interval-ms timeout-ms]
                     :or {poll-interval-ms 2000 timeout-ms 300000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [result (query-async-result config task-id)
            status (:task_status result)]
        (cond
          (= "SUCCESS" status)
          result

          (= "FAIL" status)
          (throw (ex-info "zhipu async task failed"
                          {:task-id task-id :result result :provider :zhipu}))

          (>= (System/currentTimeMillis) deadline)
          (throw (ex-info "zhipu async task timeout"
                          {:task-id task-id :last-status status
                           :timeout-ms timeout-ms :provider :zhipu}))

          :else
          (do (Thread/sleep (long poll-interval-ms))
              (recur)))))))

(defn create-anthropic-provider
  "创建智谱 GLM Provider（Anthropic 兼容协议）。

   适用 GLM-4.6/4.7/5 系列的 coding/agent 场景（与 Claude Code 接入同一端点）。
   thinking 内容块、prompt-cache 策略、重试等能力与 anthropic provider 一致。

   参数：
   - opts: 可选 {:api-key \"...\" :base-url \"...\" ...}，作为调用 config 默认值。
           缺省从 ZHIPU_API_KEY 读取 api-key。

   返回：AnthropicProvider 实例（provider-name 为 :zhipu）。
   api-key 缺失（空白）时抛 ExceptionInfo。"
  ([] (create-anthropic-provider {}))
  ([opts]
   (let [api-key (or (:api-key opts) (System/getenv "ZHIPU_API_KEY"))]
     (when (str/blank? api-key)
       (throw (ex-info "zhipu provider requires :api-key or ZHIPU_API_KEY"
                       {:required :api-key})))
     (anthropic/create-provider (merge zhipu-anthropic-endpoint opts {:api-key api-key})))))
