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
   **`call` 重试、`stream` 不重试**——token 已经投递给 sink，重跑会让下游看到
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
            [im.ttalk.agent.model :as provider]
            [im.ttalk.agent.model.request :as req]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.retry :as retry]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 辅助函数
;;; ============================================================

(defn- build-call-config
  "合并基础配置和调用时选项

   合并策略：
   - tool-choice 为 :none 时不传 tools（强制纯文本回复）
   - tool-choice 以**中立关键字** :auto/:required 下发，由各 provider 边界翻译为自身 wire 格式
     （OpenAI: \"auto\"/\"required\"；Anthropic: {:type \"auto\"}/{:type \"any\"}）。core 不做带
     provider 倾向的 wire 转换。
   - 仅在**确实有 tools** 时才下发 tool-choice：无 tools 还带 tool_choice，严格 OpenAI 端点会 400。
   - system-prompt 直接传递

   参数：
   - config: 基础配置 {:model ... :max-tokens ...}
   - opts:   调用时选项 {:tools [...] :tool-choice ... :system-prompt ...}

   返回：
   合并后的调用配置 map"
  [config opts]
  (let [tool-choice (:tool-choice opts)
        tools (when (not= tool-choice :none)
                (:tools opts))
        system-prompt (:system-prompt opts)]
    (cond-> config
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
                       (provider/replay-blocks provider raw-response)))))

(defprotocol IChatModel
  "一次 LLM 调用的契约。对标 Spring AI 的 `ChatModel` 接口。

   两个方法故意不对称：`call` 内建重试，`stream-call` 不重试（token 已出门，
   重跑即重复投递）。这不是遗漏，是流式语义的硬约束。"

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

     **不重试**：见本 ns 文档。")

  (model-options [this]
    "该 ChatModel 的缺省调用选项（{:model ... :max-tokens ...}）。
     单次请求的 `:options` 覆盖它。"))

;;; ============================================================
;;; DefaultChatModel —— 包一个 ILLMProvider
;;; ============================================================

(defrecord DefaultChatModel [provider config]
  IChatModel
  (call [_ request]
    (let [request     (req/as-chat-request request)
          call-config (build-call-config config (req/wire-options request))
          tools       (:tools call-config)
          messages    (:messages request)
          ;; 重试三级取值：单次 :options 的 :retry > provider config 的 :retry > 框架默认
          ropts       (merge (retry/resolve-opts config (req/wire-options request))
                             (select-keys (req/wire-options request) [:on-retry]))]
      (normalize-response
        provider
        (retry/run #(provider/call-llm provider call-config messages tools) ropts))))

  (stream-call [_ request on-token]
    (let [request     (req/as-chat-request request)
          call-config (build-call-config config (req/wire-options request))
          tools       (:tools call-config)
          messages    (:messages request)]
      (if (provider/supports-stream? provider)
        ;; 不包 retry/run：token 已投递给 sink，重跑 = 下游看到重复内容
        (normalize-response
          provider
          (provider/call-llm-stream provider call-config messages tools on-token))
        ;; 不支持流式：同步调用（这一条**走重试**——还没有任何 token 出门），
        ;; 再把全文作为单个 token emit，保证调用方契约一致
        (let [ropts (retry/resolve-opts config (req/wire-options request))
              resp  (normalize-response
                      provider
                      (retry/run #(provider/call-llm provider call-config messages tools) ropts))]
          (when-let [t (response/response-text resp)]
            (when on-token (on-token {:token t})))
          resp))))

  (model-options [_] config))

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
   - `stream-call` 流式调用，**不重试**（token 已投递，重跑即重复）

   说明：历史的 :build-result-msgs 已移除——对话历史由 filter/memory 的
   response->neutral 用中立消息统一构建，ChatModel 无需再提供该函数。"
  [provider config]
  (->DefaultChatModel provider (or config {})))
