# Provider 可靠性：错误细分、重试增强、主备切换、流式超时、并发准入、限流快照、thinking 档位、模型元数据

> 状态：✅ 已实施（2026-11-08，provider worktree，未提交）
> 来源：Keelson `src/Keelson.Agent/Providers/*`、`src/Keelson.Agent/Models/*`（A-20 / A-21 / A-27 / A-29 / A-35）。
> 任务：[`tasks/P-01.md`](../tasks/P-01.md) … [`tasks/P-10.md`](../tasks/P-10.md)。
> 先读：[`docs/design-principles.md`](../docs/design-principles.md)、[`docs/error-model-unification.md`](../docs/error-model-unification.md)（D5）、
> [`docs/async-chat-model-design.md`](../docs/async-chat-model-design.md)。

## 0. 总览与落点

| # | 能力 | 落点 ns | 缺省行为 |
|---|---|---|---|
| §1 P-01 | 错误细分 + 厂商业务码表 | core `model.error`；provider `provider.common.business-codes`、`provider.http.client/response->error` | 欠费 429 不再被重试（**这是修 bug**）；其余不变 |
| §2 P-02 | 空补全独立预算、流式首增量前重试、attempts 记录 | core `retry`、`chat-model`、`model.response` | 空补全补打 1 次（仍空则原样返回，`:empty-exhausted :throw` 才抛）；流式「首增量前」失败可重试（预算 `:stream-retries` 2） |
| §3 P-03 | 主备模型切换 `FallbackChatModel` | core `chat-model.fallback` | 装饰器，不挂不生效 |
| §4 P-04 | 流式首字节 / 空闲超时、回包模型名核对 | provider `http.stream-client`、`provider.http.client/success-body` | 均 opt-in（`:first-byte-ms` / `:idle-ms` / `:check-model?`） |
| §5 P-05 | 并发准入票据池 | core `admission`、`chat-model/with-admission` | 装饰器，不挂不生效 |
| §6 P-06 | 统一限流快照 | provider `provider.common.rate-limit`；core `model.response/response-rate-limit` | 只读元数据，自动挂 |
| §7 P-07 | thinking 档位统一 | provider `provider.common.thinking` | 只认新键 `:thinking-effort` / `:thinking-enabled`；显式 `:thinking` 照旧透传且优先 |
| §8 P-08 | ModelSpec 元数据与注册表 | core `model.spec` | 纯数据 + 查询 API，不内置模型表 |
| §9 P-09 | Anthropic 服务端工具块可读描述 | provider `provider.anthropic-server-tools` | 纯函数 |
| §10 P-10 | `pause_turn` 续跑判定 | core `model.response/pause-turn-resumable?`；provider anthropic 回放块扩展 | react 侧由 runtime agent 接（§10.4） |

**分层不变**（`chat_model.clj` ns 文档）：ILLMProvider 管「这一次 HTTP 往返 + 把失败翻成 canonical error」；
ChatModel 管「这一次逻辑调用」（选项、重试、归一化）；ChatClient 管 filter 洋葱。本轮新增的重试/空补全/准入
都在 ChatModel 内、filter 栈之下（理由同 `retry.clj` ns 文档：filter 只看到一次逻辑调用）；主备切换与准入是
**ChatModel 装饰器**（实现 `IChatModel` / `IAsyncChatModel`），同样在 filter 栈之下。

**组合次序**（与 Keelson 一致，外 → 内）：`FallbackChatModel` → 每站自己的 `DefaultChatModel`
（内含 重试 → 每次尝试取票 → provider）。即「重试耗尽才切换」「退避不占票」。

---

## 1. P-01 错误细分 + 厂商业务码表

### 1.1 动机

- OpenAI 兼容端点欠费回 **HTTP 429 + `error.code = insufficient_quota`**。现状按状态码判成 `:rate-limit-error`
  （可重试）→ ChatModel 退避重试 2 次、每次都失败，turn 层再重跑——「再等一会就有」被当成「再等一个月也没有」，
  白烧时间且触发风控。
- 智谱欠费回 400/429 + 顶层 `code: 1309`；MiniMax 在 **HTTP 200** 里回 `base_resp.status_code = 1039/2056`。
  前者被判成 `:validation-error` / `:rate-limit-error`，后者根本不是错误（空响应往下走）。
- 上下文超长（OpenAI `context_length_exceeded`、Anthropic `prompt is too long`）现在是 `:validation-error`，
  和「参数写错」混在一起，压缩恢复（runtime agent）无从识别。

### 1.2 Keelson 出处

`Providers/ChatProviderException.cs`（`ChatErrorKind`、`KindOfStatus`、`RefineKind`、`ClassifyFromRoot`、`ExtractMessage`）、
`Providers/ProviderBusinessCodes.cs`（码表 `Rules`、`QuotaMarkers`、`ContextOverflowMarkers`、provider 别名 `Normalized`）、
`Providers/SseChatClientBase.cs`（`ErrorFromBody` / `ThrowIfBaseRespError`：流内错误帧与 HTTP 200 业务错也过码表）。

### 1.3 clj-agent 现状

- `model/error.clj:255` `http-response->error`：只看状态码（401/403→auth，429→rate-limit，≥500→provider，其余 4xx→validation）。
- `provider/http/client.clj:145` `response->error`：在上面基础上挂 `:retry-after-ms`；**Retry-After 实际从未生效**——
  `client.clj:112` `http-headers->map` 的值是 `java.util.List`，`http/retry.clj:122` `parse-retry-after` 对它 `(str v)`
  得到 `"[120]"`，正则不中、日期解析失败 → nil。单测一直传字符串值所以没发现（本轮顺带修）。
- `provider/http/stream_client.clj:137` 流式非 2xx 直接调 core `http-response->error`，不带响应头。
- `provider/stream/anthropic.clj:216` 流内 `error` 事件 → `build-response` 抛非 canonical 的 `ex-info`（无 `:type`/`:retryable?`）。
  而且这个异常在 Flow subscriber 的 `onComplete` 里抛，被吞成「流式响应未产出结果」，原始错误丢失。
- `provider/common/base.clj:106` openai 兼容 provider 调 `compat/call-api` 时不带 `:provider-name`，错误里 `:provider` 为 nil。

### 1.4 方案

**core `model.error`**（契约见 brief）：

| 类型 | `:retryable?` | 含义 |
|---|---|---|
| `:quota-exhausted-error` | false | 欠费 / 额度用尽 / 402。**不是限流**，退避变不出额度；但**可切备用**（额度逐家） |
| `:context-overflow-error` | false | 超上下文窗口。不重试、**不切备用**（换一家照样超长），留给压缩恢复 |
| `:empty-completion-error` | true | HTTP 200 但无有效内容（§2）。可重试，但只走独立预算 |

- `error` 的缺省分类表加入三者；`http-response->error` 只加 **402 → `:quota-exhausted-error`**（状态码层面能下的唯一结论，
  core 不认识厂商码表）。
- 新谓词 `quota-exhausted?` / `context-overflow?` / `empty-completion?`；`rate-limit-error?` 收紧为「类型是限流，或
  429 且类型不是欠费」——欠费 429 不能再被当成限流。
- `classify-exception`（工具错误路由）：`:quota-exhausted-error` 归 `:environment`（模型修不了、要人）。
- 新工具函数 `with-ex-data`：给异常追加 ex-data 键（attempts / fallback 报告用），ExceptionInfo 换一个同消息、同 cause、
  同栈的新实例；非 ExceptionInfo 先 `exception->error` 再包——保证 D5「ex-data 即 canonical error」不破。

**provider `provider.common.business-codes`**（新 ns，码表照 Keelson 原样搬，数据而非 if 链）：

```clojure
(classify provider-name type code)        ;=> :quota-exhausted-error | :rate-limit-error | :context-overflow-error | nil
(classify-body provider-name body)        ;; 从 {:error {:type :code}} / 顶层 code/type / base_resp.status_code 挖码
(refine-error err body)                   ;; 码表优先 → 402 → 超长文案（只从 validation/provider 收紧）→ 欠费文案（只从 provider/5xx 收紧）
(body-error provider-name body)           ;; HTTP 200 业务错（base_resp.status_code ≠ 0）→ canonical error 或 nil
(stream-error provider-name error-obj)    ;; 流内错误帧 → canonical error（error.type 先按 KindOfType 分类再过码表）
(extract-message body)                    ;; {:error{:message}} / {:error "…"} / {:msg} / {:message} / base_resp.status_msg
(retry-delay-text s)                      ;; "try again in 1.5s / 200ms" → 毫秒（A-35）
```

判定次序（照 `RefineKind`）：码表命中即定；否则 402 → 欠费；否则原判是 `:validation-error`/`:provider-error` 且文案像超长 →
超长；否则原判是 auth/validation/rate-limit 则维持（**文案绝不凭空造出限流，也不把限流改成欠费**）；否则文案像欠费 → 欠费。
provider 别名归一照搬（deepseek/qwen/moonshot… → openai；glm/bigmodel → zhipu；hailuo → minimax）。

接入点：
- `provider.http.client/response->error`：`errors/http-response->error` 之后过 `refine-error`；Retry-After 用修好的
  `parse-retry-after`（值可为 List）；没给头时扫正文 `try again in …`；错误 map 增可选 `:code`（命中的业务码）。
- `provider.http.client/success-body`（新，§4/§6 共用）：成功响应先查 `body-error`（MiniMax/智谱 HTTP 200 业务错）。
  openai 兼容 / anthropic / openai-responses 的同步与异步路径都改走它。
- `stream-client` 非 2xx 改走 `http-client/response->error`（带响应头 → Retry-After / 业务码）。
- `stream/anthropic` 的 `error` 事件 → `business-codes/stream-error`（`overloaded_error` 可重试、`billing_error` 欠费…）；
  `stream-client` 的 `on-complete` 包 try，`build-response` 抛的错进 err 通道而不是被吞。
- `base.clj` 调 compat 时补 `:provider-name`（智谱码表要靠它认家）。

### 1.5 不做什么

- 不把 429 限流改成缺省不重试（Keelson 这么做；clj-agent 现有用户依赖 429 重试，且欠费已经剥离出去，剩下的 429 真是限流）。
- 不新增「Protocol」类型：协议错在 clj-agent 里是 `:parse-error` / `:provider-error`，够用。
- 码表不做成可注册的扩展点（§1：没有人要；要补码改数据即可）。

### 1.6 与设计原则

D5 错误形状不变（三个新 `:type` 值 + 可选 `:code`）；§1：每一行码表都有 Keelson 的真实样本或厂商文档出处；
新谓词替代调用方手写 `(= :x (:type e))` 不算新抽象。

### 1.7 测试

core：新类型缺省可重试性、402、`rate-limit-error?` 不认欠费 429、`classify-exception`、`with-ex-data`。
provider：码表（insufficient_quota@429、billing_error、context_length_exceeded、prompt is too long@400、智谱 1309@400、
MiniMax 1039@200、文案不造限流）、Retry-After 的 List 值、`try again in`、本地 HttpServer 打流式 429 欠费不重试 / 带 Retry-After。

---

## 2. P-02 重试增强

### 2.1 动机

- **空补全**：DeepSeek / MiniMax / Claude 偶发 HTTP 200 但一个字没有（feedback chartroom-empty-completion-retry）。
  现在原样交给上层，agent 以空文本收尾，用户看到「什么都没回」。补打一次常常就过。
- **流式断流**：现状 `stream-call` 一律不重试（`chat_model.clj:213`）。但「首个增量之前」断开（建连失败、503、首字节超时）
  没有任何 token 出门，重发没有重复风险。`stream-client` 里已有一个 opt-in 的传输层版本（`:retry`），只在用户显式配
  `:retry` 时生效，且它的预算与 ChatModel 的 `:retry` 是同一个键——一旦配上就是 m×n 次。
- **可观测性**：成功时不知道试了几次；失败时只剩最后一个错误。

### 2.2 Keelson 出处

`Providers/RetryingChatClient.cs`：三格预算（HTTP `MaxRetries` / `EmptyCompletionRetries`=1 / `StreamRetries`）各自记账；
`IsEmptyCompletion`（文本/推理/工具调用之外都不算内容；`content_filter`/`length`/`pause_turn` 是厂商结论、不算空）；
超预算抛 `EmptyCompletion` 且**绝不再进 HTTP 预算**；流式 `emitted` 之后的错误原样抛；`AttemptCountKey` / `AttemptsKey`。

### 2.3 clj-agent 现状

`retry.clj:112` `run` / `:151` `run-async`：单一预算、只认 `:retryable?`；`chat_model.clj:199` DefaultChatModel
`call` 走 `retry/run`，`stream-call` 不走；`stream_client.clj:207` 传输层 opt-in 重试。

### 2.4 方案

**`retry/resolve-opts`** 默认值增 `:empty-retries 1`、`:empty-exhausted :return`、`:stream-retries 2`；`:retry false` 三格全关。

**`retry/run` / `run-async`** 新增可选 opts（不给即旧行为，逐字不变）：

| 键 | 作用 |
|---|---|
| `:empty?` | `(fn [result] bool)`；真 → 按 `:empty-retries` 立即补打（不退避，空补全不是过载）；超预算按 `:empty-exhausted`：`:return`（缺省，空结果原样交回）/ `:throw`（抛 `:empty-completion-error`） |
| `:retry-gate` | `(fn [] bool)`；每次决定重试前问一次——流式用它表达「本次尝试已经吐过 token 就不重试」 |
| `:record-attempts?` | 真 → 成功结果（map/record）上 `assoc :attempts n`（含首次） |
| `:provider` | 空补全错误里的 `:provider` |

记账：空补全补打**不占** `:max-retries`、不抬高退避指数；`:empty-completion-error` 即使 `:retryable? true` 也**不进**
`:max-retries` 那条链（显式排除），防止「max-retries × empty-retries」放大。放弃时若试过不止一次，异常经
`errors/with-ex-data` 追加 `:attempts [每次失败的 canonical error（去掉 :cause）]`。`:on-retry` 事件增 `:kind`
（`:error` / `:empty-completion` / `:stream`）。

**`response/empty-completion?`**（core，纯函数）：文本非空白 / 推理非空白 / 有工具调用 / 有回放块 / 原始响应带 audio
任一成立即非空；finish-reason ∈ `#{:content-filter :max-tokens :pause :refusal}` 不算空。

**DefaultChatModel**：
- `call`：`#(normalize-response provider (call-llm …))` 整体放进 `retry/run`（归一化是纯函数，放进去才能判空）；
  `{:empty? response/empty-completion? :record-attempts? true}`。
- `stream-call`（provider 支持流式时）：每次尝试一个 `emitted` 标记，包一层 on-token（交付过非空 token 即置真）；
  `retry/run` 用 `:max-retries (:stream-retries ropts)`、`:retry-gate #(not @emitted)`、同一个 `:empty?`。
  **硬约束不变**：token 出门之后的失败原样抛。传给 provider 的 config **剥掉 `:retry`**，传输层不再叠一层。
- 异步两条（`call-async` / `stream-call-async`）同构，走 `retry/run-async`。
- 响应读法：`response/response-attempts`。

### 2.5 不做什么

- 不做 Keelson 的 `WaitForNetwork`（无限等网）：无具体调用方（§1）。
- 不改「token 出门后不重试」：那是流式语义硬约束。
- FnChatModel 不加重试（历史语义：用户闭包自管）。

### 2.6 行为变化（须写进 CHANGELOG）

1. 空补全缺省补打 1 次（多打一次 provider），仍空 → 空响应原样返回（与过去一致）。`{:retry {:empty-exhausted :throw}}`
   让它抛 `:empty-completion-error`（交主备切换 / 上层）；`{:retry {:empty-retries 0}}` 完全回到旧行为（不补打）。
   **偏离 Keelson 之处**：Keelson 超预算即抛。这里缺省不抛，是因为实测 client 模块已有用例依赖「provider 返回空 → 轮次正常
   结束」（`resume_opts_test` 的第二次 chat 拿到空响应），缺省抛错会把这类宿主的轮次变成 `:error`。主备切换不受影响：
   `FallbackChatModel` 自己识别空补全响应并切站（§3）。
2. 流式在首个 token 前的可重试失败缺省重试 2 次（过去 0 次）。
3. 用户在 config 里配的 `:retry` 不再让 `stream-client` 传输层重试（改由 ChatModel 统一做，语义相同）。

### 2.7 测试

retry 单测：空补全独立预算 / 超预算错误类型 / 不进 HTTP 预算 / gate / attempts 记录 / run-async 等价。
ChatModel：同步空补全补打、仍空原样返回 / `:throw` 抛错、流式首 token 前重试、token 后不重试（原有用例保留）、异步路径同上、
`:retry false` 全关、流式传给 provider 的 config 不含 `:retry`。

---

## 3. P-03 主备模型切换 FallbackChatModel

### 3.1 动机

主模型 provider 整体故障（5xx 风暴、欠费、key 失效）时，agent 整轮失败；用户希望「主模型重试耗尽后换备用模型接着答」。
这件事用户自己写要处理：错误分类、流式首 token 判定、跨家 thinking 签名、惰性构造、报账——不是「几行等价」（§1 四问通过：
有具体调用方 Keelson coxswain / chartroom；不建则每个宿主各写一套且容易把 Invalid / ContextOverflow 也切走）。

### 3.2 Keelson 出处

`Providers/FallbackChatClient.cs`（`IsSwitchable`、`FallbackTarget` 惰性 `Create`、`Send` 跨家剪 thinking、`WithModel`、
`FallbackReport`/`Trail`、`GiveUp` 把账挂异常上）、`Models/ModelRegistry.cs`（`FallbackChain`、`CreateChatClientWithFallback`）。

### 3.3 clj-agent 现状

无。`chat_model.clj` 只有 DefaultChatModel / FnChatModel 两个实现。

### 3.4 方案（core `im.ttalk.agent.chat-model.fallback`）

```clojure
(fallback/fallback-chat-model primary
  [{:ref "openai/gpt-4o" :create (fn [] (cm/create-chat-model openai-p {:model "gpt-4o"}))}
   {:ref "deepseek/deepseek-chat" :chat-model ds-cm}]          ;; :create 惰性 / :chat-model 现成二选一
  {:primary-ref "anthropic/claude-sonnet-4"                   ;; 缺省从 DefaultChatModel 的 provider-name + :model 推
   :switch?   (fn [err-map ex] bool)                           ;; 覆盖判据
   :on-switch (fn [{:keys [from to error]}])})
```

- 实现 `IChatModel` + `IAsyncChatModel`；`model-options` 委托主模型。
- **判据** `switchable?`（canonical error 的 `:type`）：切 `:provider-error`（仅 `:retryable? true`，即 5xx 类）/
  `:network-error` / `:timeout-error` / `:rate-limit-error`（重试已耗尽）/ `:auth-error` / `:quota-exhausted-error` /
  `:empty-completion-error` / `:parse-error`；**不切** `:validation-error`、`:context-overflow-error`、取消/中断、
  其它非 canonical 异常（IOException 例外：视作网络错）。
- **空补全响应**：本站返回 `response/empty-completion?` 为真的响应且还有下一站 → 也切（`:switch-on-empty?`，缺省真）；
  这样即使 §2 的 `:empty-exhausted` 保持缺省 `:return`，主备链照样能绕开「老吐空」的那一家。
- **流式**：只在本站第一个 token 之前切；吐过 token 后的失败原样抛。
- **跨 provider 剪 thinking**：上一站与本站 ref 的 provider 段不同 → 所有消息 `dissoc :blocks`（不透明回放载荷：Anthropic
  thinking 签名、Responses reasoning item；别家原样收到只会 400）。同家换模型不动。
- **换站去掉 `:options :model`**：备用站用它自己的 `model-options`，不拿主模型的名字问备胎。
- **惰性构造**：`:create` 轮到才调、每站只调一次（实例内缓存）；构造抛错 = 这一站失败，**不看判据**、有下一站就硬切。
- **报账**：成功响应 `assoc :model-ref ref :fallback report`；全链失败原样抛最后一个错误，ex-data 追加 `:fallback report`。
  `report = {:primary :model :switches :attempts [{:ref :error}] :trail ["a → b：msg" …]}`；读法 `fallback/report`。

**注册表接法**（§8）：`(fallback/from-registry reg ref {:create (fn [spec] chat-model)})` 按 `spec/fallback-chain` 展开，
每站惰性 `(create spec)`；没登记的 ref 留在链上，轮到才报错（不拖累主模型）。

### 3.5 不做什么

- 不做文案 marker 判据（Reactor 那套）——走错误类型。
- 不做「切过之后粘住备用」的会话级状态：每次调用从主模型开始（Keelson 同）。粘性是宿主策略，几行可做。
- 不在 ChatClient / filter 层做：切换要在重试之外、filter 之下（filter 只看到一次逻辑调用）。

### 3.6 测试

同步/异步/流式：主站 503 切备用并报账；Invalid / ContextOverflow 不切；流式 token 后不切；跨家剪 `:blocks`、同家不剪；
惰性构造（未轮到不调；构造抛错硬切）；全链失败 ex-data 带报告；`from-registry` 链展开。

---

## 4. P-04 流式首字节 / 空闲超时 + 回包模型名核对

### 4.1 动机

- 流式请求 java.net.http 的 `timeout` 只管到响应头；body 阶段服务端「挂住不吐字」时没有任何东西会醒（现状缺省 300s
  只是总超时的近似）。首字节迟迟不来 → 应尽快失败并（因未出 token）重试/切换；吐了一半静默 → 应失败而不是永远挂着。
- MiniMax 等网关会把请求静默路由到别的模型（keel `check-model?`，MiniMax 预设开）。

### 4.2 Keelson 出处

`Providers/SseChatClientBase.cs`（`IdleTimeoutStream`：第一片之前用 `FirstByteTimeout ?? IdleTimeout`、之后用 `IdleTimeout`；
`CheckModel` 在第一次见到模型名时核对，内容尚未吐出）、`Providers/ProviderOptions.cs`（`FirstByteTimeout`、`IdleTimeout`、`CheckModel`）。

### 4.3 clj-agent 现状

`stream_client.clj:99` `post-stream-async` 无 watchdog；`:timeout`（缺省 300000）是请求级超时。无模型名核对。

### 4.4 方案

`post-stream-async` 新 opts（provider 从 call config 透传 `:first-byte-ms` / `:idle-ms` / `:check-model?`）：

- `:first-byte-ms`：从发出请求到第一行 SSE 到达的上限（缺省 = `:idle-ms`）；`:idle-ms`：相邻两行之间的上限。都缺省 nil = 不限
  （向后兼容）。实现：一个共享单线程 daemon `ScheduledExecutorService`，每条流一个「到点检查 → 未超时则按剩余时间重排」的任务
  （不是固定频率轮询，零空转）；超时 → 取消 subscription 与 future、`on-error` 收
  `{:type :timeout-error :retryable? true :code :first-byte-timeout | :idle-timeout}`。首字节超时没有 token 出门，§2 的
  流式重试与 §3 的切换都会接住；空闲超时发生在 token 之后，原样抛。SSE 注释心跳行（`: ping`）计入活动。
- 终止回调**只触发一次**（watchdog 与取消引发的 onError 竞争时以先到者为准）。
- `:check-model {:requested "m" :model-fn (fn [state] model)}`：每行处理后若尚未核对且 `model-fn` 给出非空模型名 → 比较；
  不一致 → `on-error` 收 `{:type :provider-error :retryable? false :code :model-mismatch}`，**在该行 token 投递之前**取消流。
  openai 兼容（`get-model`）与 anthropic（`[:message :model]`）已接；同步路径在 `success-body` 里核对 `(:model body)`。
  Responses 流式与 DashScope 原生协议只接了 `:first-byte-ms` / `:idle-ms`，未接模型名核对（无调用方，MiniMax 走 anthropic 线）。
- `:on-headers (fn [status headers])`：供 §6 限流快照与 §1 非 2xx 错误带头。

### 4.5 不做什么

- 不改缺省超时（Keelson 缺省 idle 120s；clj-agent 维持「不配不限」以免改变长思考模型的现有行为）。
- 模型名只做精确比较（同 keel）；不做前缀/别名宽松匹配——opt-in 功能，宽松规则写不出判据。
- 不做 connect-ms（JDK HttpClient 的 connectTimeout 是 client 级，按请求换 client 代价不对称；无调用方）。

### 4.6 测试

本地 HttpServer：首字节前睡过 `:first-byte-ms` → `:timeout-error`；吐一行后静默过 `:idle-ms` → idle 超时；心跳行续命；
模型名不一致 → `:model-mismatch` 且无 token 投递；同步 `success-body` 核对。

---

## 5. P-05 并发准入票据池

### 5.1 动机

同一个 provider/模型上同时压太多请求（并行子 agent、批处理、工具里再调 LLM）会集体吃 429，退避后又一起醒来。需要一个
「同时在途 ≤ N」的闸门；且**退避等待不该占票**（否则一个在退避的请求把名额卡死）；异步路径**不能阻塞线程**等票。

### 5.2 Keelson 出处

`Providers/ChatAdmission.cs`（按键分池、`Waiting`/`InFlight` 观测、票据幂等归还）、`Providers/AdmittingChatClient.cs`
（缺省键 = provider/模型；套在重试层里面 ⇒ 退避不占票；流式票跟流的生命周期）。

### 5.3 clj-agent 现状

无。工具侧有 TCM 舱壁，LLM 调用侧没有。

### 5.4 方案

**core `im.ttalk.agent.admission`**（纯并发原语，零依赖）：

```clojure
(def pool (admission/admission {:permits 8}))      ;; 每个键 8 张票
(admission/acquire! pool key)                      ;; 阻塞取票（可被中断；中断时已到手的票自动归还）
(admission/acquire-async pool key)                 ;; -> CompletableFuture<ticket>，不占线程
(admission/release! ticket)                        ;; 幂等
(admission/with-permit pool key thunk)             ;; 同步：取票 → 跑 → finally 还
(admission/with-permit-async pool key thunk)       ;; thunk 返回链结果；完成/失败时还
(admission/in-flight pool [key]) (admission/waiting pool [key])
```

每个键一个池：`atom {:available n :waiters 队列}`，取票/还票都是一次 `swap-vals!`；还票时若有等待者直接**移交**（不经
available，防插队），移交在虚拟线程上完成（避免把等待者的后续阶段跑在还票线程上），被取消的等待者跳过并继续移交。

**ChatModel 接法** `chat-model/with-admission`：

```clojure
(cm/with-admission chat-model pool {:key-fn (fn [request] "anthropic/claude")})   ;; key-fn 可省
```

- 包的是 DefaultChatModel → 返回**同一个** DefaultChatModel，config 里挂 `:admission`：**每次尝试**（含流式整条流）取票、
  尝试结束还票，退避等待发生在票外——即「退避不占票」，与 Keelson 的组合次序等价。
- 包的是其它 ChatModel（FnChatModel、FallbackChatModel、自定义）→ `AdmittingChatModel` 装饰器，票覆盖整次调用
  （它看不见内层的重试；文档写明）。
- 缺省键：DefaultChatModel 为 `"<provider-name>/<model>"`，其它为 `"default"`。

### 5.5 不做什么

- 不做排队超时（限流是分钟尺度；要放弃用取消——同步路径中断线程，异步路径 cancel future）。
- 不做全局单例池（§3：执行边界不隐式共享；要共享就把同一个 pool 显式传给多个 ChatModel）。
- 不做自适应并发（AIMD 等）：无真实需求。

### 5.6 测试

票数上限、等待计数、移交顺序（FIFO）、release 幂等、异步取票不占线程（1 票时第二个 future 未完成）、中断归还、
DefaultChatModel 退避期间 in-flight = 0、流式整条流占票。

---

## 6. P-06 统一限流快照

### 6.1 动机

宿主要做「剩余额度展示 / 主动降速 / 选路」，需要读每次响应的限流头。现在只有 Anthropic 同步路径挂 `:rate-limit`，
OpenAI 的 `x-ratelimit-*`、流式、Responses 都没有；而且（§1.3）头的值是 List，Anthropic 那份解析其实拿到的是 List 原样。

### 6.2 Keelson 出处

`Providers/RateLimitInfo.cs`（OpenAI `x-ratelimit-{limit,remaining,reset}-{requests,tokens}` 与 `anthropic-ratelimit-*`
折成同一形状；全空不挂；同步挂响应、流式挂第一条增量）。

### 6.3 clj-agent 现状

`anthropic.clj:151` `parse-rate-limit`（只认 anthropic 头）；`anthropic.clj` 同步/异步 call 把它 `assoc :rate-limit` 到原始响应体。

### 6.4 方案

- provider `provider.common.rate-limit/parse-rate-limit headers` → `{:requests-limit :requests-remaining :requests-reset
  :tokens-limit :tokens-remaining :tokens-reset}`（+ 有 `retry-after` 头时 `:retry-after`，保持 anthropic 旧形状的超集）；
  数值转 long（小数四舍五入），reset 保留字符串；全空 nil；头值可为字符串或 List。`anthropic/parse-rate-limit` 委托它。
- 同步：`http.client/success-body` 统一 `assoc :rate-limit`（openai 兼容 / anthropic / responses，同步+异步）。
- 流式：`stream-client` 经 `:on-headers` 记下，`build-response` 之后挂到最终原始响应（map）上。
- core：`chat-model/normalize-response` 把原始响应的 `:rate-limit` 搬到 ChatResponse 的 `:rate-limit`；读法
  `response/response-rate-limit`。

### 6.5 不做什么

不在每个流式 token 上挂（Keelson 挂第一条增量是因为 M.E.AI 没有「最终响应」；clj-agent 有）。不做限流头驱动的自动降速。

### 6.6 测试

两家头折叠、List 值、全空 nil、数值/小数、同步 openai 兼容挂上、流式（本地服务器带头）挂上、ChatResponse 读到。

---

## 7. P-07 thinking 档位统一

### 7.1 动机

上层（agent 配置、UI 的「思考强度」滑块）想说一句「effort=high」或「关思考」，现在要按家写 wire：OpenAI `reasoning_effort`、
DeepSeek `thinking{type,reasoning_effort}`、GLM `thinking{type}` + 顶层、Qwen `enable_thinking`、Anthropic
`thinking{type:adaptive}` + `output_config.effort` 或旧式 `budget_tokens`。主备切换（§3）跨家时这份配置还得跟着改。

### 7.2 Keelson 出处

`Providers/ThinkingKeys.cs`（两格：`thinking_effort` 字符串原样上 wire / `thinking_enabled` 只开关；effort 压过 enabled）、
`OpenAICompatibleChatClient.cs` `ApplyThinkingCall` + `ThinkingStyle`（plain / nested / top-level / qwen / none）、
`AnthropicChatClient.cs` `ApplyThinkingCall`（`ThinkingBudgetTokens`、`ThinkingAlwaysOn`）、`ChatProviders.cs`（DeepSeek=nested、GLM=plain）。

### 7.3 clj-agent 现状

`common/openai_compat.clj:56` `build-params` 透传 `:thinking` / `:reasoning-effort`；`anthropic.clj:364` 透传 `:thinking`。

### 7.4 方案

provider `provider.common.thinking`：

```clojure
(thinking/resolve-call config)                 ;=> {:effort "high"} | {:enabled false} | nil
(thinking/openai-fields config stream?)        ;=> 要并进请求体的字段 map
(thinking/anthropic-fields config)             ;=> {:thinking {...} :output_config {:effort ..}}
```

- 新 config 键：`:thinking-effort`（字符串/关键字，原样上 wire，如 DeepSeek `"max"`）、`:thinking-enabled`（bool）、
  `:thinking-style`（`:plain`/`:nested`/`:top-level`/`:qwen`/`:none`；缺省按 `:provider-name` 预设：deepseek→nested，
  dashscope/qwen→qwen，其余 plain）、Anthropic 专属 `:thinking-budget-tokens`、`:thinking-always-on?`。
- **不破坏透传**：config 里显式给了 `:thinking`（或 openai 的 `:reasoning-effort`）→ 新键对应字段不生成，显式值原样发。
- 规则照 keel：effort 压过 enabled；openai 只开/关一律 `thinking{type}`；qwen 开且流式才 true（**2026-11-10 放开**：开即 true、不看流式，qwen3.8 真机非流式开思考不 400）；anthropic 关→`disabled`
  （always-on 时省略），预算压过档位，档位→`adaptive + display summarized` + `output_config.effort`，只开→裸 `adaptive`。
- Responses API：effort → `:reasoning {:effort e}`（显式 `:reasoning` 优先）。

### 7.5 不做什么

不做枚举化 effort（档位名就是 wire 值，厂商自定义档位才表达得了）；不做按模型能力自动降级（那是 §8 元数据的消费方的事）。

### 7.6 测试

各风格的 wire 快照、显式 `:thinking` 优先、effort 压过 enabled、qwen 非流式也 true（2026-11-10 起）、anthropic 三分支 + always-on + budget。

---

## 8. P-08 ModelSpec 元数据与注册表

### 8.1 动机

压缩（触发线 = 上下文窗口 × 百分比）、工具结果截断（单条上限）、thinking 预算上限（输出上限 − 回答余量）、主备链（§3）
都要「这个模型的窗口/上限/能力/备选是什么」。现在这些散在各处常量里或根本没有。

### 8.2 Keelson 出处

`Models/ModelSpec.cs`（ref = `provider/wire-id` 第一个斜杠拆分、`ModelOptionKeys`、`ThinkingAnswerHeadroom` 4096、
fallbacks 去重去自身）、`Models/ModelRegistry.cs`（实例而非全局、`Register`/`SetFallbacks`/`FallbackChain` 广度优先防环）。

### 8.3 clj-agent 现状

无。`provider/factory/registry.clj` 是 provider 工厂注册表（按名字造 provider），不含模型元数据。

### 8.4 方案（core `im.ttalk.agent.model.spec`，纯数据 + 查询 API）

```clojure
(spec/model-spec "anthropic/claude-sonnet-4"
  {:label "Sonnet" :context-limit 200000 :output-limit 64000
   :thinking-levels ["low" "medium" "high"] :vision? true :parallel-tools? true
   :tool-output-limit 20000 :auto-compact-percent 80 :fallbacks ["openai/gpt-4o"]})
;; => {:ref .. :provider "anthropic" :wire-id "claude-sonnet-4" :context-limit .. :fallbacks [..] :options {...}}
(spec/split-ref "openrouter/anthropic/claude") ;=> ["openrouter" "anthropic/claude"]
(def reg (spec/registry))                      ;; 实例（atom），无全局单例
(spec/register! reg spec-or-ref [opts]) (spec/unregister! reg ref) (spec/lookup reg ref) (spec/models reg)
(spec/set-fallbacks! reg ref [...]) (spec/fallback-chain reg ref)        ;; 广度优先、去重、去起点、防环
(spec/context-limit reg ref) (spec/output-limit reg ref)
(spec/thinking-budget-cap spec)               ;; output-limit − 4096（不足则 nil）
(spec/usable-context spec)                    ;; context-limit × usable-window-percent
```

校验：ref 形状、`:thinking-levels` 是字符串序列、fallbacks 去自身去重。未知键原样留在 `:options`（开放表）。

### 8.5 不做什么

不内置模型表（会过期、且是 §1 假想需求）；不做 provider allow/deny（clj-agent 无对应配置面）；不按 spec 自动造 ChatModel
（造法由宿主给 `:create`，见 §3.4）。

### 8.6 测试

ref 拆分、校验报错、注册替换、fallback 链（多层、环、未登记）、查询。

---

## 9. P-09（低优先）Anthropic 服务端工具块可读描述

### 9.1 动机 / 出处 / 现状

服务端工具（web_search、code_execution）的块在中立层是不透明回放载荷；UI/日志想显示「搜了什么、搜到哪些网址、代码输出」。
出处 `Providers/AnthropicServerTools.cs` `Describe`。现状无。

### 9.2 方案

provider `provider.anthropic-server-tools/describe block` → 纯数据视图或 nil：

| 块 | 视图 |
|---|---|
| `server_tool_use` name=web_search | `{:kind :web-search-call :id :queries [q]}` |
| `server_tool_use` name=*code_execution | `{:kind :code-execution-call :id :name :input "代码或命令"}` |
| 其它 `server_tool_use` | `{:kind :server-tool-call :id :name :input {...}}` |
| `web_search_tool_result` | `{:kind :web-search-result :tool-use-id :results [{:url :title :page-age}]}` 或 `:error` |
| `*code_execution_tool_result` | `{:kind :code-execution-result :tool-use-id :stdout :stderr :return-code}` 或 `:error` |

`describe-all content-blocks` 批量。不进历史、不改回放。

---

## 10. P-10 pause_turn 续跑判定

### 10.1 动机

Anthropic 服务端工具跑久了会以 `stop_reason: pause_turn` 先交回；正确做法是把这条 assistant 消息**原样**接到历史末尾、
不加 user 消息、再请求一次。现状 `finish-reason` 归一成 `:pause`（`response.clj:142`），react 循环当它是普通文本收尾。

### 10.2 出处

`Providers/PauseTurn.cs`（`IsPaused`、`CanResume`：pause_turn + 无本地工具调用 + assistant 有块 + 非最后一轮；`ResumeHistory`）。

### 10.3 方案（本 agent 侧）

- core `response/pause-turn?`、`response/pause-turn-resumable?`（`[resp]` / `[resp last-iteration?]`）：
  `:pause` 且无工具调用且**有回放块**（没有块就没有可原样回放的东西，续不了）且非最后一轮。
- provider anthropic `replay-blocks` 扩展：除 thinking 外，**有服务端工具块（`server_tool_use` / `*_tool_result`）或
  stop_reason 为 pause_turn** 时也捕获整段 content——否则续跑时 wire 重建会丢掉 server_tool_use 块，服务端无法从断点接续。

### 10.4 react 侧怎么接（交 runtime agent，本 agent 不改 react.clj）

在 react 的「本轮无工具调用 → 终止」判定**之前**插一条：

```clojure
(if (response/pause-turn-resumable? resp (zero? remaining))
  ;; 把 assistant 消息（含 :blocks，react.clj 现有的 response->assistant 构造即可）追加进本轮 delta，
  ;; 不加 user 消息，计一轮迭代（受 max-iterations 约束），continue 下一轮 LLM 调用
  (recur ...)
  ...现有收尾...)
```

- 计入迭代预算（防服务端反复 pause 形成无限循环）；最后一轮不续，以 `:pause` finish-reason 正常收尾。
- memory filter 照常写入这条 assistant 消息（下一轮 wire/anthropic 看到 `:blocks` 原样吐回）。
- 不需要新错误类型、不需要 HITL 暂停（这是服务端暂停，不是等人）。

---

## 附：跨 agent 接口

| 消费方 | 接口 |
|---|---|
| runtime（压缩恢复） | `(= :context-overflow-error (:type err))` 或 `errors/context-overflow?`；`spec/context-limit` |
| runtime（react） | `response/pause-turn-resumable?`（§10.4） |
| 任意 | `response/response-attempts`、`response/response-rate-limit`、`fallback/report`、ex-data `:attempts` / `:fallback` |
