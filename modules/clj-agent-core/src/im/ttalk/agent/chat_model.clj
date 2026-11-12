(ns im.ttalk.agent.chat-model
  "ChatModel —— 一次 LLM 调用的抽象

   对标 Spring AI 的 `ChatModel`：**Provider 只管「底层怎么发这一个请求」，
   ChatModel 管「这一次逻辑调用」**——选项解析、响应归一化、重试，都在这里。
   分工的判据是「换一家 provider 要不要重写」：wire 格式要，退避策略不要。

       ILLMProvider   一次 HTTP 往返，认识厂商 wire 格式
            ↑
        ChatModel     一次逻辑调用：选项合并 → 重试 → 响应归一化
            ↑
       ChatClient     filter 洋葱链 + 工具
            ↑
       SimpleAgent    工具循环 + 记忆 + HITL

   **重试在这里，位于整个 filter 栈之下**（与 beamai `beamai_chat_model` 同一
   取舍，理由见 `im.ttalk.agent.retry` 的 ns 文档）：filter 看到的是「一次逻辑
   调用」，重试重入碰不到任何 filter，memory/计时的副作用每轮只发生一次。
   **`call` 重试、`stream` 只在首个 token 之前重试**——token 已经投递给 sink 后重跑会让下游看到
   重复内容；流式要容错请在 turn 层重跑整轮。

   两个实现：
   - `DefaultChatModel`：包一个 ILLMProvider，日常用的就是它
   - `FnChatModel`：包 `{:chat-fn :stream-fn}` 两个闭包——历史写法与测试 stub
     照旧可用（`as-chat-model` 在装配期归一化），也是接入任意 LLM 的最短路径

   使用示例：

   (require '[im.ttalk.agent.chat-model :as chat-model])

   (def cm (chat-model/create-chat-model my-provider
             {:model \"gpt-4\" :max-tokens 4096
              :retry {:max-retries 3}}))       ;; 缺省 2 次；:retry false 关闭

   (chat-model/call cm (req/chat-request messages {:tools [...]}))"
  (:require [clojure.string]
            [im.ttalk.agent.admission :as admission]
            [im.ttalk.agent.async :as async]
            [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.model :as provider]
            [im.ttalk.agent.model.request :as req]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.retry :as retry]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 辅助函数
;;; ============================================================

(def non-wire-option-keys
  "单次选项里属于 ChatModel / 框架自己的键：重试配置与它的观测 / 测试钩子、准入、取消令牌、流式回调。
   它们由 ChatModel 消费，不盖到 provider config 上。"
  [:retry :on-retry :sleep-fn :rand-fn :admission :cancel-token :on-token])

(defn- build-call-config
  "合并基础配置和调用时选项

   合并策略：
   - tool-choice 为 :none 时不传 tools（强制纯文本回复）
   - tool-choice 以**中立关键字** :auto/:required 下发，由各 provider 边界翻译为自身 wire 格式
     （OpenAI: \"auto\"/\"required\"；Anthropic: {:type \"auto\"}/{:type \"any\"}）。core 不做带
     provider 倾向的 wire 转换。
   - 仅在**确实有 tools** 时才下发 tool-choice：无 tools 还带 tool_choice，严格 OpenAI 端点会 400。
   - system-prompt 直接传递
   - **其余单次选项盖在 config 上**（`:temperature` / `:max-tokens` / `:thinking-effort` / `:model` / provider
     私有参数……）：`wire-options` 的承诺是「递得到底」，曾经在这里只取三格、其余静默丢掉（锦书反馈
     2026-11-10 ③，观澜 2026-11-11 复述）。ChatModel 自己的装配与钩子（`non-wire-option-keys`）不下发。

   参数：
   - config: 基础配置 {:model ... :max-tokens ...}
   - opts:   调用时选项 {:tools [...] :tool-choice ... :system-prompt ... 及任意 provider 参数}

   返回：
   合并后的调用配置 map"
  [config opts]
  (let [tool-choice (:tool-choice opts)
        tools (when (not= tool-choice :none)
                (:tools opts))
        system-prompt (:system-prompt opts)
        per-call (apply dissoc opts :tools :tool-choice :system-prompt non-wire-option-keys)]
    ;; :admission 是 ChatModel 自己的装配（票据池），不下发给 provider
    (cond-> (merge (dissoc config :admission) per-call)
      (seq tools)
      (assoc :tools tools)
      ;; 只在有 tools 且非 :none 时透传中立 tool-choice（provider 侧翻译）
      (and tool-choice (not= tool-choice :none) (seq tools))
      (assoc :tool-choice tool-choice)
      system-prompt
      (assoc :system-prompt system-prompt))))

;;; ============================================================
;;; ChatModel 工厂
;;; ============================================================

(defn- normalize-response
  "将 Provider 原始响应规范化为 ChatClient 统一格式

   使用 response/make-response 创建 ChatResponse，自动归一化：
   - usage: 统一为 {:input-tokens :output-tokens :total-tokens}
   - finish-reason: 统一为关键字 :stop | :tool-use | :max-tokens 等

   参数：
   - provider: ILLMProvider 实例
   - raw-response: Provider 原始响应

   返回：
   ChatResponse record（实现 ILLMResponse 协议）"
  [provider raw-response]
  (let [text (provider/extract-text provider raw-response)
        tool-calls (provider/extract-tool-calls provider raw-response)
        ;; 中立层用「容许两种常见位置」的方式取 usage/finish-reason：
        ;; 顶层（Anthropic 风格）或 choices[0]（OpenAI 风格）。reasoning 同理走 core 提取。
        usage (or (:usage raw-response)
                  (get-in raw-response [:choices 0 :usage]))
        finish-reason (or (:stop_reason raw-response)
                          (get-in raw-response [:choices 0 :finish_reason]))]
    (cond->
     (response/make-response
      :id (:id raw-response)
      :model (:model raw-response)
      :text (when (seq text) text)
      :reasoning (response/extract-reasoning raw-response)
      :tool-calls (when (seq tool-calls) tool-calls)
      :usage usage
      :finish-reason finish-reason
      :provider (provider/provider-name provider)
      :raw-response raw-response
      ;; 不透明回放载荷：**可选**协议，探测到才取（见 model/IReplayableResponse）。
      ;; core 全程不解释 :data——它只负责把这段数据从响应搬到中立消息，
      ;; 让下一轮的 wire 转换器原样吐回去。厂商 wire 知识仍归 provider
      ;; （见 docs/response-path-consolidation.md）。
      :replay-blocks (when (satisfies? provider/IReplayableResponse provider)
                       (provider/replay-blocks provider raw-response)))
    ;; 限流快照（provider 边界从响应头折出来挂在原始响应上，见 provider.common.rate-limit）
    (and (map? raw-response) (:rate-limit raw-response))
    (assoc :rate-limit (:rate-limit raw-response)))))

(defprotocol IChatModel
  "一次 LLM 调用的契约。对标 Spring AI 的 `ChatModel` 接口。

   `call` 内建重试；`stream-call` 只在**首个 token 之前**重试（token 已出门之后
   重跑即重复投递——这条是流式语义的硬约束，designs/provider-reliability.md §2）。"

  (call [this request]
    "同步调用。

     参数: request —— `ChatRequest`（或可归一化为它的 map）
     返回: `ChatResponse`（实现 ILLMResponse）
     抛出: ex-info，data 即 canonical error（重试耗尽后原样重抛）")

  (stream-call [this request on-token]
    "流式调用。on-token 收 {:token ...} / {:reasoning-token ...} 增量。

     返回: 流结束时的最终 `ChatResponse`（与 `call` 同形）。
     provider 不支持流式时回退同步，并把全文作为单个 token emit——保证
     chat-stream 对任何 provider 都可用。

     **只在首个 token 之前重试**：见本 ns 文档。")

  (model-options [this]
    "该 ChatModel 的缺省调用选项（{:model ... :max-tokens ...}）。
     单次请求的 `:options` 覆盖它。"))

(defprotocol IAsyncChatModel
  "**可选**协议：ChatModel 能异步调用时实现它。语义与 `IChatModel` 逐字相同
   （含重试策略：`call-async` 重试、`stream-call-async` 只在首个 token 前重试），只是不阻塞调用线程。

   **不实现也能异步**：`call-async*` / `stream-call-async*` 探测不到会用虚拟线程包
   同步调用兜底——调用方永远拿得到 deferred。实现本协议只省掉那根线程。

   **不得 `extend-type Object` 兜底**（`satisfies?` 会恒真，探测机制当场失效）。"
  (call-async [this request]
    "-> deferred<ChatResponse>。语义同 `call`（含重试）。")
  (stream-call-async [this request on-token]
    "-> deferred<ChatResponse>。语义同 `stream-call`（**只在首个 token 前重试**）。

     on-token 的调用线程不保证——原生异步实现里 token 在传输层 executor 上派发。"))


;;; ============================================================
;;; 异步入口（探测 + 虚拟线程兜底）
;;; ============================================================
;;;
;;; 设计见 docs/async-chat-model-design.md §4。要害是**兜底**：探测不到原生
;;; 异步不等于不能异步——用虚拟线程包同步调用，调用方永远拿得到 deferred。
;;; 于是「异步入口」这件事对所有 provider（含仓库外自实现的 {:chat-fn …}）
;;; 一视同仁，原生异步实现可以逐家慢慢补，不构成阻塞。

(defn call-async*
  "任何 ChatModel 的异步调用入口 → deferred<ChatResponse>。

   实现了 `IAsyncChatModel` 就用原生异步；否则 `async/vthread` 包 `call`。
   两条路径的语义（重试、归一化、异常）逐字相同。"
  [chat-model request]
  (if (satisfies? IAsyncChatModel chat-model)
    (call-async chat-model request)
    (async/vthread #(call chat-model request))))

(defn stream-call-async*
  "`call-async*` 的流式版 → deferred<ChatResponse>。**只在首个 token 前重试**（同 `stream-call`）。

   ⚠️ on-token 的调用线程不保证：原生异步路径上 token 在传输层 executor 上派发，
   虚拟线程兜底路径上则在那根虚拟线程上。sink 自行保证线程安全。"
  [chat-model request on-token]
  (if (satisfies? IAsyncChatModel chat-model)
    (stream-call-async chat-model request on-token)
    (async/vthread #(stream-call chat-model request on-token))))

;;; ============================================================
;;; DefaultChatModel 的重试 / 准入辅助（designs/provider-reliability.md §2 / §5）
;;; ============================================================

(defn- retry-opts
  "三级合并后的重试配置 + 单次 :options 里的观测/测试钩子。"
  [config request]
  (let [wo (req/wire-options request)]
    (merge (retry/resolve-opts config wo)
           (select-keys wo [:on-retry :sleep-fn :rand-fn]))))

(defn- call-retry-opts
  "非流式：按 :max-retries 退避重试 + 空补全独立预算（:empty-retries 0 ⇒ 不判空，空响应原样返回）。"
  [config request provider]
  (let [ro (retry-opts config request)]
    (cond-> (assoc ro :record-attempts? true :provider (provider/provider-name provider))
      (pos? (long (or (:empty-retries ro) 0))) (assoc :empty? response/empty-completion?))))

(defn- stream-retry-opts
  "流式：预算换成 :stream-retries，且只在本次尝试**还没交付任何 token** 时重试（gate）。"
  [config request provider emitted]
  (assoc (call-retry-opts config request provider)
         :max-retries (long (or (:stream-retries (retry-opts config request)) 0))
         :retry-gate #(not @emitted)
         :retry-kind :stream))

(defn- delivered?
  "这条 token-data 交付给 sink 后，重发会不会让下游看到重复内容。
   全是空串 / nil 的（如 {:token \"\"}）不算交付。"
  [t]
  (if (map? t)
    (boolean (some (fn [[_ v]] (not (or (nil? v) (= "" v)))) t))
    (some? t)))

(defn- tracking-on-token
  "包一层 on-token：交付过非空 token 即把 emitted 置真。"
  [on-token emitted]
  (fn [t]
    (when (delivered? t) (reset! emitted true))
    (when on-token (on-token t))))

(defn- admission-key [config provider request]
  (let [{:keys [key key-fn]} (:admission config)]
    (cond
      key-fn (key-fn request)
      key    key
      :else  (str (some-> (provider/provider-name provider) name) "/"
                  (or (req/option request :model) (:model config) "model")))))

(defn- admitted
  "每次**尝试**取一张票（config 挂了 :admission 时）；退避等待因此发生在票外。"
  [config provider request thunk]
  (if-let [pool (get-in config [:admission :pool])]
    (admission/with-permit pool (admission-key config provider request) thunk)
    (thunk)))

(defn- admitted-async [config provider request thunk]
  (if-let [pool (get-in config [:admission :pool])]
    (admission/with-permit-async pool (admission-key config provider request) thunk)
    (thunk)))

;;; ============================================================
;;; DefaultChatModel —— 包一个 ILLMProvider
;;; ============================================================

(defrecord DefaultChatModel [provider config]
  IChatModel
  (call [_ request]
    (let [request     (req/as-chat-request request)
          call-config (build-call-config config (req/wire-options request))
          tools       (:tools call-config)
          messages    (:messages request)]
      ;; 归一化放进重试循环：空补全要在 ChatResponse 上判（designs/provider-reliability.md §2）
      (retry/run #(normalize-response
                    provider
                    (admitted config provider request
                              (fn [] (provider/call-llm provider call-config messages tools))))
                 (call-retry-opts config request provider))))

  (stream-call [this request on-token]
    (let [request     (req/as-chat-request request)
          ;; 传输层不再叠一层 :retry（stream-client 的 opt-in 重试）——ChatModel 统一做，免得 m×n
          call-config (dissoc (build-call-config config (req/wire-options request)) :retry)
          tools       (:tools call-config)
          messages    (:messages request)]
      (if (provider/supports-stream? provider)
        ;; 只在「首个 token 之前」重试：token 已投递给 sink 之后重跑 = 下游看到重复内容
        (let [emitted (atom false)
              tok     (tracking-on-token on-token emitted)]
          (retry/run (fn []
                       (reset! emitted false)
                       (normalize-response
                         provider
                         (admitted config provider request
                                   (fn [] (provider/call-llm-stream provider call-config messages tools tok)))))
                     (stream-retry-opts config request provider emitted)))
        ;; 不支持流式：同步调用（**走重试**——还没有任何 token 出门），
        ;; 再把全文作为单个 token emit，保证调用方契约一致
        (let [resp (call this request)]
          (when-let [t (response/response-text resp)]
            (when on-token (on-token {:token t})))
          resp))))

  (model-options [_] config)

  ;; ---- 异步孪生 ----
  ;; **归一化路径与同步版共用**（build-call-config / retry 判据 /
  ;; normalize-response 一份），异步只把「等 HTTP 结果」换成 fmap。任何
  ;; 「另写一条更短的异步路径」都会重蹈旧 `xxx-call-async` 旁路的覆辙：
  ;; 绕过 wire 转换与归一化，永远接不进 filter 链
  ;; （docs/async-chat-model-design.md §0 / §5）。
  IAsyncChatModel
  (call-async [this request]
    (if-not (satisfies? provider/IAsyncLLMProvider provider)
      (async/vthread #(call this request))     ;; provider 无原生异步：兜底
      (let [request     (req/as-chat-request request)
            call-config (build-call-config config (req/wire-options request))
            tools       (:tools call-config)
            messages    (:messages request)]
        (retry/run-async
          #(flt/fmap (admitted-async config provider request
                                     (fn [] (provider/call-llm-async provider call-config messages tools)))
                     (fn [raw] (normalize-response provider raw)))
          (call-retry-opts config request provider)))))

  (stream-call-async [this request on-token]
    (cond
      ;; 不支持流式：同步路径就是「走重试的非流式调用 + 全文单 token emit」，
      ;; 异步照抄这条（call-async 已含重试）
      (not (provider/supports-stream? provider))
      (flt/fmap (call-async this request)
                (fn [resp]
                  (when-let [t (response/response-text resp)]
                    (when on-token (on-token {:token t})))
                  resp))

      (not (satisfies? provider/IAsyncLLMProvider provider))
      (async/vthread #(stream-call this request on-token))

      :else
      ;; 只在首个 token 之前重试（硬约束不因异步而改）
      (let [request     (req/as-chat-request request)
            call-config (dissoc (build-call-config config (req/wire-options request)) :retry)
            tools       (:tools call-config)
            messages    (:messages request)
            emitted     (atom false)
            tok         (tracking-on-token on-token emitted)]
        (retry/run-async
          (fn []
            (reset! emitted false)
            (flt/fmap (admitted-async config provider request
                                      (fn [] (provider/call-llm-stream-async provider call-config messages tools tok)))
                      (fn [raw] (normalize-response provider raw))))
          (stream-retry-opts config request provider emitted))))))

;;; ============================================================
;;; FnChatModel —— 包 {:chat-fn :stream-fn} 两个闭包
;;; ============================================================

;; 历史上 chat-model 就是这个裸 map，READMEs 也教「可自行实现此 map 接入任意
;; LLM」。归一化成 record 之后那条路照旧走得通，只是多了一层类型。
(defrecord FnChatModel [chat-fn stream-fn options]
  IChatModel
  (call [_ request]
    (when-not chat-fn
      (throw (ex-info "ChatModel 缺少 :chat-fn" {:chat-model-keys [:stream-fn]})))
    (let [r (req/as-chat-request request)]
      (chat-fn (:messages r) (req/wire-options r))))

  (stream-call [_ request on-token]
    (when-not stream-fn
      (throw (ex-info "ChatModel 缺少 :stream-fn（不支持流式）" {:chat-model-keys [:chat-fn]})))
    (let [r (req/as-chat-request request)]
      (stream-fn (:messages r) (req/wire-options r) on-token)))

  (model-options [_] (or options {})))

(defn chat-model?
  "是否已是 ChatModel（实现 IChatModel 的任意类型）。"
  [x]
  (satisfies? IChatModel x))

(defn as-chat-model
  "归一化成 ChatModel：已实现 IChatModel 则原样返回，`{:chat-fn :stream-fn}`
   map 包成 `FnChatModel`。

   `build-chat-client` 对 `:chat-model` 调用本函数——所以用户侧传 record、
   传裸 map、传自定义 IChatModel 实现三种形态等价（与 `filter/as-filter`
   同一套手法）。"
  [x]
  (cond
    (nil? x) nil

    ;; 已实现 IChatModel 却又挂着 :chat-fn / :stream-fn —— 几乎必然是
    ;; `(assoc cm :chat-fn (fn [...] ...))` 这种旧写法：v0.3 之前 chat-model 就是
    ;; 那个裸 map，assoc 一下即可埋探针/换实现。现在 assoc 只是往 record 的
    ;; ext-map 塞了个键，协议分派**完全看不见它**——注入被无声丢弃，且没有任何
    ;; 运行期症状（探针不涨、替换不生效，程序照跑）。装配期当场抛，理由同
    ;; `build-chat-client` 拒绝同名工具：错配没有症状可查，就得在装配期拦住。
    (and (chat-model? x) (or (:chat-fn x) (:stream-fn x)))
    (throw (ex-info (str "ChatModel 已实现 IChatModel，却仍带 "
                         (clojure.string/join " / " (keep #(when (get x %) (str %))
                                                          [:chat-fn :stream-fn]))
                         "——协议分派看不见这些键，注入会被静默丢弃。"
                         "要包一层请实现 IChatModel（reify 或 FnChatModel），"
                         "在实现里转调 chat-model/call 与 chat-model/stream-call。")
                    {:type (type x)
                     :stray-keys (vec (keep #(when (get x %) %) [:chat-fn :stream-fn]))}))

    (chat-model? x) x
    (map? x) (->FnChatModel (:chat-fn x) (:stream-fn x) (dissoc x :chat-fn :stream-fn))
    :else (throw (ex-info "无法归一化为 ChatModel（需 IChatModel 实现或 {:chat-fn :stream-fn} map）"
                          {:value x :type (type x)}))))

(defn create-chat-model
  "从 ILLMProvider 创建 ChatModel。

   参数：
   - provider: ILLMProvider 实例
   - config:   模型配置 {:model \"...\" :max-tokens n :system-prompt \"...\"}
               另可带 :retry —— nil/true 用默认（2 次），false 关闭，
               map 作为配置（{:max-retries 3 :base-delay 1000 ...}）

   返回：`DefaultChatModel`（实现 IChatModel）

   - `call`        同步调用，**内建重试**（判据为 canonical error 的 :retryable?）
   - `stream-call` 流式调用，只在首个 token 前重试（token 已投递后重跑即重复）

   说明：历史的 :build-result-msgs 已移除——对话历史由 filter/memory 的
   response->neutral 用中立消息统一构建，ChatModel 无需再提供该函数。"
  [provider config]
  (->DefaultChatModel provider (or config {})))

;;; ============================================================
;;; 并发准入（designs/provider-reliability.md §5）
;;; ============================================================

(defrecord AdmittingChatModel [inner pool key-fn]
  IChatModel
  (call [_ request]
    (admission/with-permit pool (key-fn request) #(call inner request)))
  (stream-call [_ request on-token]
    (admission/with-permit pool (key-fn request) #(stream-call inner request on-token)))
  (model-options [_] (model-options inner))
  IAsyncChatModel
  (call-async [_ request]
    (admission/with-permit-async pool (key-fn request) #(call-async* inner request)))
  (stream-call-async [_ request on-token]
    (admission/with-permit-async pool (key-fn request) #(stream-call-async* inner request on-token))))

(defn with-admission
  "给 ChatModel 套上在途票据池（`im.ttalk.agent.admission`）。

   - `chat-model` 是 DefaultChatModel → 返回同一种 DefaultChatModel，config 挂 `:admission`：
     **每次尝试**（流式为整条流）取一张票、尝试结束即还，**退避等待不占票**。
   - 其它 ChatModel（FnChatModel / FallbackChatModel / 自定义）→ `AdmittingChatModel` 装饰器，
     票覆盖**整次调用**（它看不见内层的重试）。

   opts：
   - :key-fn (fn [request] key)  按请求算池的键；缺省 DefaultChatModel 为 \"<provider>/<model>\"，
                                  其它为 \"default\"
   - :key    固定键（与 :key-fn 二选一）"
  ([chat-model pool] (with-admission chat-model pool nil))
  ([chat-model pool {:keys [key key-fn]}]
   (let [cm (as-chat-model chat-model)]
     (if (instance? DefaultChatModel cm)
       (update cm :config assoc :admission (cond-> {:pool pool}
                                             key-fn (assoc :key-fn (fn [r] (key-fn (req/as-chat-request r))))
                                             key    (assoc :key key)))
       (->AdmittingChatModel cm pool
                             (cond
                               key-fn (fn [r] (key-fn (req/as-chat-request r)))
                               key    (constantly key)
                               :else  (constantly "default")))))))
