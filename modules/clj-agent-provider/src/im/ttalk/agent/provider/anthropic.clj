(ns im.ttalk.agent.provider.anthropic
  "Anthropic Provider 实现

   实现 ILLMProvider 协议，提供 Anthropic Claude API 的完整访问。

   支持功能：
   - 同步调用 / 流式调用（同步阻塞）/ 异步调用 / 异步流式调用
   - 工具调用（Function Calling）+ 结构化输出
   - 服务端内置工具：web_search（见 schema.anthropic/web-search-tool）
   - Citations 引用：可引用文档块（schema.anthropic/text-document）+ 响应引用提取（extract-citations）
   - Skills（beta）：技能容器 + code_execution 工具（schema.anthropic/skill 等，配合 config :beta）
   - prompt caching 策略层（见 common.cache）
   - 响应限流头解析（parse-rate-limit）

   调用 config 支持的 Anthropic 专属能力（均「存在才发送」，详见 build-params）：
   - 采样：:temperature :top-p :top-k :max-tokens :stop（=> stop_sequences）
   - 推理：:thinking（如 {:type \"adaptive\"} / {:type \"enabled\" :budget_tokens 2048}）
   - :metadata、:service-tier（\"auto\" | \"standard_only\"，容量路由）
   - 工具：:tools（含 web_search 等 wire 工具）、:tool-choice
   - 缓存：:cache-strategy + :cache-ttl（见下）

   prompt caching 策略（:cache-strategy）：
   - :none | :system | :tools | :system-and-tools | :conversation
   - :tool-results            缓存到最后一个 tool_result（多轮工具循环跨轮复用）
   - :system-and-conversation system + 对话历史双断点
   命中/创建 token 归一化到响应 usage 的 :cache-read-tokens / :cache-write-tokens。

   响应限流：同步调用成功时，响应体附加 :rate-limit
   {:requests-limit :requests-remaining :requests-reset
    :tokens-limit :tokens-remaining :tokens-reset :retry-after}（源自 anthropic-ratelimit-* 头）。

   使用示例：

   (require '[im.ttalk.agent.provider.anthropic :as anthropic]
            '[im.ttalk.agent.provider.schema.anthropic :as schema])

   (def provider (anthropic/create-provider))

   ;; 同步调用
   (anthropic/call-anthropic config messages tools)

   ;; 流式调用
   (anthropic/call-anthropic-stream config messages tools on-token)

   ;; web_search + 缓存 + 服务层级
   (anthropic/call-anthropic
     {:model \"claude-opus-4-8\" :max-tokens 4096
      :service-tier \"auto\"
      :cache-strategy :system-and-tools :cache-ttl \"1h\"}
     messages
     [(schema/web-search-tool {:max-uses 5})])

   ;; Citations：可引用文档 -> 响应带引用
   (let [resp (anthropic/call-anthropic
                {:model \"claude-opus-4-8\" :max-tokens 1024}
                [{:role \"user\"
                  :content [(schema/text-document \"地球绕太阳公转。\" {:title \"天文\"})
                            {:type \"text\" :text \"地球绕什么转？\"}]}]
                [])]
     (anthropic/extract-citations resp))

   ;; Skills（beta）：需开 :beta 头 + code_execution 工具
   (anthropic/call-anthropic
     {:model \"claude-opus-4-8\" :max-tokens 4096
      :beta schema/default-skills-beta
      :container (schema/skills-container [(schema/skill \"xlsx\")])}
     messages
     [(schema/code-execution-tool)])"
  (:require [im.ttalk.agent.provider.http.client :as http]
            [im.ttalk.agent.provider.http.stream-client :as stream-client]
            [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.model :as proto]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.model.error :as errors]
            [im.ttalk.agent.provider.common.cache :as cache]
            [im.ttalk.agent.provider.schema.anthropic :as schema]
            [im.ttalk.agent.provider.wire.anthropic :as wire]
            [im.ttalk.agent.provider.stream.anthropic :as stream]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 配置
;;; ============================================================

(def ^:private default-opts
  "默认 Anthropic API 选项（env API Key 回退）"
  (atom {:api-key (System/getenv "ANTHROPIC_API_KEY")
         :base-url "https://api.anthropic.com"
         :impl :anthropic}))

;;; --- Anthropic 兼容端点抽象 -------------------------------------
;;; 通过 config 可配置 base-url / 路径 / 鉴权方式 / 版本头，
;;; 使任何 “Anthropic Messages API 兼容” 的服务（如 MiniMax 的
;;; /anthropic/v1/messages, Bearer 鉴权）都能复用同一套请求/响应/流式机制。

(def default-endpoint
  "默认端点：官方 Anthropic API（x-api-key + anthropic-version 头）"
  {:base-url "https://api.anthropic.com"
   :api-path "/v1/messages"
   :auth-scheme :x-api-key            ;; :x-api-key | :bearer
   :anthropic-version "2023-06-01"})  ;; 设为 nil 则不发送该头

(defn- resolve-endpoint
  "从 config 解析端点配置（缺省回退官方 Anthropic）"
  [config]
  (merge default-endpoint
         (select-keys config [:base-url :api-path :auth-scheme :anthropic-version :beta])))

(defn- build-url
  [{:keys [base-url api-path]}]
  (str base-url api-path))

(defn- beta-header-value
  "把 :beta（字符串 / 字符串集合）规整为 anthropic-beta 头值（逗号分隔）。
   nil/空 -> nil。"
  [beta]
  (cond
    (string? beta)            (when-not (clojure.string/blank? beta) beta)
    (sequential? beta)        (let [v (->> beta (remove clojure.string/blank?) distinct)]
                                (when (seq v) (clojure.string/join "," v)))
    :else nil))

(defn- build-headers
  "按鉴权方式构造请求头：官方用 x-api-key，MiniMax 等兼容端点用 Bearer。
   :beta（如 \"skills-2025-10-02\" 或 [\"a\" \"b\"]）-> anthropic-beta 头，启用 beta 功能。"
  [{:keys [auth-scheme anthropic-version beta]} api-key]
  (cond-> {"Content-Type" "application/json"}
    (= :bearer auth-scheme)    (assoc "Authorization" (str "Bearer " api-key))
    (= :x-api-key auth-scheme) (assoc "x-api-key" api-key)
    anthropic-version          (assoc "anthropic-version" anthropic-version)
    (beta-header-value beta)   (assoc "anthropic-beta" (beta-header-value beta))))

(defn- resolve-api-key
  [config]
  (or (:api-key config) (:api-key @default-opts)))

;;; ============================================================
;;; 响应头：限流信息
;;; ============================================================

(defn- header-get
  "从 headers map 读取某个头（兼容 keyword / 字符串键，大小写不敏感）"
  [headers k]
  (when headers
    (or (get headers k)
        (get headers (keyword k))
        (get headers (clojure.string/lower-case k)))))

(defn parse-rate-limit
  "从 Anthropic 响应头解析限流信息（anthropic-ratelimit-*）。

   参数：
   - headers: HTTP 响应头 map

   返回：
   {:requests-limit n :requests-remaining n :requests-reset \"ISO时间\"
    :tokens-limit n :tokens-remaining n :tokens-reset \"ISO时间\"
    :retry-after n}
   仅包含响应实际携带的字段；全部缺失时返回 nil。

   说明：数值字段会尝试解析为 Long，解析失败则保留原字符串。"
  [headers]
  (let [->long (fn [s] (when s (try (Long/parseLong (str s)) (catch Exception _ s))))
        m (cond-> {}
            (header-get headers "anthropic-ratelimit-requests-limit")
            (assoc :requests-limit (->long (header-get headers "anthropic-ratelimit-requests-limit")))
            (header-get headers "anthropic-ratelimit-requests-remaining")
            (assoc :requests-remaining (->long (header-get headers "anthropic-ratelimit-requests-remaining")))
            (header-get headers "anthropic-ratelimit-requests-reset")
            (assoc :requests-reset (header-get headers "anthropic-ratelimit-requests-reset"))
            (header-get headers "anthropic-ratelimit-tokens-limit")
            (assoc :tokens-limit (->long (header-get headers "anthropic-ratelimit-tokens-limit")))
            (header-get headers "anthropic-ratelimit-tokens-remaining")
            (assoc :tokens-remaining (->long (header-get headers "anthropic-ratelimit-tokens-remaining")))
            (header-get headers "anthropic-ratelimit-tokens-reset")
            (assoc :tokens-reset (header-get headers "anthropic-ratelimit-tokens-reset"))
            (header-get headers "retry-after")
            (assoc :retry-after (->long (header-get headers "retry-after"))))]
    (when (seq m) m)))

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
   工具调用列表 [{:id \"...\" :name \"字符串\" :args {...}}]"
  [response]
  (->> (:content response)
       (filter #(= (:type %) "tool_use"))
       (mapv (fn [{:keys [id name input]}]
               ;; wire tool_use 块字段叫 :input；统一响应形状为 :args
               (msg/tool-call id name input)))))

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

(defn extract-citations
  "从 Anthropic 响应提取引用（Citations）。

   当请求带启用引用的 document 内容块（见 schema.anthropic/text-document）时，
   响应的 text 块会携带 :citations 数组，标注引用了哪个文档的哪段文本。

   参数：
   - response: Anthropic API 响应

   返回：
   引用列表（如 [{:type \"char_location\" :cited_text \"...\" :document_index 0
                  :document_title \"...\" :start_char_index 0 :end_char_index 12} ...]）
   无引用时返回 nil。"
  [response]
  (let [cits (->> (:content response)
                  (filter #(= (:type %) "text"))
                  (mapcat :citations)
                  (remove nil?)
                  vec)]
    (when (seq cits) cits)))

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
    :tool-calls [{:id :name :args}]
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
    :reasoning (response/extract-reasoning raw-response)
    :tool-calls (let [tc (extract-tool-calls raw-response)]
                  (when (seq tc) tc))
    :usage (get-usage raw-response)
    :finish-reason (:stop_reason raw-response)
    :provider :anthropic
    :raw-response raw-response))

;;; ============================================================
;;; API 参数构建
;;; ============================================================

(defn- ->wire-tool-choice
  "将 core 下发的中立 tool-choice 翻译为 Anthropic wire 形态。
   关键字 :auto/:required/:none → {:type ...}；其余（已是 map，如 {:type \"tool\" :name ..}）原样透传。"
  [tc]
  (case tc
    :auto {:type "auto"}
    :required {:type "any"}
    :none {:type "none"}
    tc))

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
     - Skills（beta）：:container（如 {:skills [...]}，需配合 config :beta 头）
   - messages:     消息列表（Anthropic wire 形态）
   - tool-schemas: 工具 schema 列表

   返回：
   API 参数 map（已按 cache-strategy 注入 cache_control）"
  [{:keys [model max-tokens system-prompt tool-choice tools
           temperature top-p top-k stop metadata thinking service-tier container
           cache-strategy cache-ttl]} messages tool-schemas]
  (let [all-tools (into (vec tools) tool-schemas)
        max-tokens (or max-tokens 4096)
        params (cond-> {:model model
                        :max_tokens max-tokens
                        :messages messages}
                 (seq all-tools)     (assoc :tools all-tools)
                 system-prompt       (assoc :system system-prompt)
                 tool-choice         (assoc :tool_choice (->wire-tool-choice tool-choice))
                 (some? temperature) (assoc :temperature temperature)
                 (some? top-p)       (assoc :top_p top-p)
                 (some? top-k)       (assoc :top_k top-k)
                 (seq stop)          (assoc :stop_sequences (vec stop))
                 metadata            (assoc :metadata metadata)
                 thinking            (assoc :thinking thinking)
                 ;; 服务层级（"auto" | "standard_only"）：容量路由，仅显式提供时发送
                 service-tier        (assoc :service_tier service-tier)
                 ;; Skills 容器（beta）：{:skills [{:type "anthropic" :skill_id "xlsx" ...}]}
                 container           (assoc :container container))]
    (cache/apply-anthropic-cache params cache-strategy cache-ttl)))

;;; ============================================================
;;; 错误归一化
;;; ============================================================

(def ^:private response->error
  "D5 错误归一化——统一实现见 http.client/response->error。"
  http/response->error)

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
        endpoint (resolve-endpoint config)
        api-url (build-url endpoint)
        headers (build-headers endpoint (resolve-api-key config))
        timeout (or (:timeout config) 120000)
        ;; 无重试：重试已上移到 ChatModel（`im.ttalk.agent.retry`）。provider
        ;; 只负责「发这一次请求 + 把失败翻成 canonical error」——两层各 retry
        ;; 一次会让实际次数变成 m×n，且下层重试对上层的 :on-retry 不可见。
        response (http/post api-url
                            :headers headers
                            :body params
                            :timeout timeout)]
    (if (:success? response)
      ;; 把限流头信息附加到响应体（内部字段，不影响 normalize-response 的标准解析）
      (let [rl (parse-rate-limit (:headers response))]
        (cond-> (:body response)
          rl (assoc :rate-limit rl)))
      (errors/throw! (response->error response (:provider-name config :anthropic))))))

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
        endpoint (resolve-endpoint config)
        api-url (build-url endpoint)
        headers (build-headers endpoint (resolve-api-key config))
        ;; 真流式：长生成给足超时（java.net.http 的 timeout 是 total）
        timeout (or (:timeout config) 300000)]
    ;; java.net.http 真增量传输：on-token 随每行 SSE 实时触发；
    ;; 同步编排（promise/cancel/分派）统一在 post-stream-sync。
    (stream-client/post-stream-sync
      api-url
      {:headers headers :body params :timeout timeout
       :parse-fn stream/parse-sse-line
       :process-fn stream/process-event
       :make-initial-state stream/make-initial-state
       :build-response stream/build-response
       :on-token on-token
       :retry (:retry config)            ;; opt-in 建链重试（未出 token 才重试）
       :provider (:provider-name config :anthropic)})))

(defn call-anthropic-deferred
  "调用 Anthropic API（异步，返回 deferred<响应体>）。

   参数与语义同 `call-anthropic`（含限流头附加、canonical error），只是不阻塞。
   **无重试**：重试在 ChatModel 层（`im.ttalk.agent.retry/run-async`）。

   与同步版**共用后处理**：`http/post-deferred` 的响应 map 形状与 `http/post`
   逐字相同，`:success?` 判定 / `parse-rate-limit` / `response->error` 只有一份
   （docs/async-chat-model-design.md §0 / §5）。"
  [config messages tools]
  (let [tool-schemas (schema/tools->schemas tools)
        params (build-params config messages tool-schemas)
        endpoint (resolve-endpoint config)
        api-url (build-url endpoint)
        headers (build-headers endpoint (resolve-api-key config))
        timeout (or (:timeout config) 120000)]
    (flt/fmap (http/post-deferred api-url
                                  :headers headers
                                  :body params
                                  :timeout timeout)
              (fn [response]
                (if (:success? response)
                  (let [rl (parse-rate-limit (:headers response))]
                    (cond-> (:body response)
                      rl (assoc :rate-limit rl)))
                  (errors/throw! (response->error response (:provider-name config :anthropic))))))))

(defn call-anthropic-stream-deferred
  "流式调用 Anthropic API（异步，返回 deferred<最终响应>）。

   参数与语义同 `call-anthropic-stream`（含 `:retry` 建链重试与取消），
   只是不阻塞。⚠️ on-token 在传输层 executor 上派发，不是调用线程。"
  [config messages tools on-token]
  (let [tool-schemas (schema/tools->schemas tools)
        params (-> (build-params config messages tool-schemas)
                   (assoc :stream true))
        endpoint (resolve-endpoint config)
        api-url (build-url endpoint)
        headers (build-headers endpoint (resolve-api-key config))
        timeout (or (:timeout config) 300000)]
    (stream-client/post-stream-deferred
      api-url
      {:headers headers :body params :timeout timeout
       :parse-fn stream/parse-sse-line
       :process-fn stream/process-event
       :make-initial-state stream/make-initial-state
       :build-response stream/build-response
       :on-token on-token
       :retry (:retry config)
       :provider (:provider-name config :anthropic)})))

;;; ============================================================
;;; AnthropicProvider 实现
;;; ============================================================

(defrecord AnthropicProvider [opts]
  proto/ILLMProvider
  ;; 基本信息（provider-name 可由 opts 覆盖，使 MiniMax 等复用本 record）
  (provider-name [_] (get opts :provider-name :anthropic))

  ;; 核心 API（协议以中立消息为边界：内部转 Anthropic wire，system 提至顶层）
  ;; 实例 opts（端点/鉴权/api-key）作为默认值并入每次调用的 config（config 优先）
  (call-llm [_ config messages tools]
    (let [{:keys [messages system]} (wire/neutral->wire messages)
          config (cond-> (merge opts config) system (assoc :system-prompt system))]
      (call-anthropic config messages tools)))

  ;; 流式 API
  (call-llm-stream [_ config messages tools on-token]
    (let [{:keys [messages system]} (wire/neutral->wire messages)
          config (cond-> (merge opts config) system (assoc :system-prompt system))]
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

  ;; 不透明回放载荷（可选协议）：thinking 块必须原样带回下一轮，
  ;; 否则模型后续轮次会显著少思考、正确率下降
  ;; （实测 M3 40 次/臂：100% → 82.5%，见 docs/provider-variant-design.md §7.5.3）
  proto/IReplayableResponse
  (replay-blocks [_ raw-response]
    (let [content (:content raw-response)]
      ;; **只在真有 thinking 块时才捕获**。范围严格限定在有实测证据的那一种情况：
      ;; 无脑捕获所有响应会让「原样回放」盖过现有的 text/tool_use 重建路径，
      ;; 连带绕过任何改写过历史的 filter——那是没有证据支持的行为变更。
      (when (and (sequential? content)
                 (some #(= "thinking" (:type %)) content))
        {:format :anthropic-content
         :data (vec content)})))

  ;; 原生异步（可选协议）：wire 转换与同步分支**逐字相同**，只换传输那一层。
  ;; ChatModel 侧探测到本协议就走这条，探测不到用虚拟线程兜底——两条路径的
  ;; 返回值形状必须一致（见 IAsyncLLMProvider 文档；旧 callback 旁路就是栽在这）。
  ;;
  ;; ⚠️ 一个 defrecord 里**同一个协议名只能出现一次**：重复写会让先前那组方法
  ;; 变成抽象方法（实测 AbstractMethodError）。故异步协议整块放在最后。
  proto/IAsyncLLMProvider
  (call-llm-async [_ config messages tools]
    (let [{:keys [messages system]} (wire/neutral->wire messages)
          config (cond-> (merge opts config) system (assoc :system-prompt system))]
      (call-anthropic-deferred config messages tools)))

  (call-llm-stream-async [_ config messages tools on-token]
    (let [{:keys [messages system]} (wire/neutral->wire messages)
          config (cond-> (merge opts config) system (assoc :system-prompt system))]
      (call-anthropic-stream-deferred config messages tools on-token))))

;;; ============================================================
;;; 工厂函数
;;; ============================================================

(defn create-provider
  "创建 Anthropic Provider 实例

   参数：
   - opts: 实例选项（可选），作为每次调用 config 的默认值并入：
     - :api-key            API Key
     - :provider-name      逻辑名（默认 :anthropic；MiniMax 等兼容端点可覆盖）
     - :base-url :api-path :auth-scheme :anthropic-version  端点配置（见 default-endpoint）

   返回：
   AnthropicProvider record（opts 存于实例，不再使用全局可变状态，支持多实例并存）

   示例：
   (create-provider)
   (create-provider {:api-key \"sk-ant-...\"})
   ;; Anthropic 兼容端点（如 MiniMax）：
   (create-provider {:provider-name :minimax
                     :base-url \"https://api.minimaxi.com\"
                     :api-path \"/anthropic/v1/messages\"
                     :auth-scheme :bearer
                     :anthropic-version nil
                     :api-key \"...\"})"
  ([] (->AnthropicProvider {}))
  ([opts] (->AnthropicProvider (or opts {}))))

