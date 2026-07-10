# BUG2 真流式 + 异步框架整合方案

> 状态：✅ **全部落地**（2026-07-10 核对：传输层、anthropic + openai_compat 两条路径、
> kernel/client `chat-stream` 接入均完成，见下方「已落地」清单；唯一待续项为按需的
> Undertow WebSocket 示例）。
>
> **2026-07-10 优化轮补记**：
> - 三处流式同步编排（openai_compat / anthropic / dashscope 各自手写 promise 对 + cancel
>   包装 + cond 分派）统一收敛为 `stream_client/post-stream-sync`。
> - stream 处理器累积改 StringBuilder（消除逐 token O(n²)）；on-token 契约移除
>   `:accumulated`，只发增量。
> - stream_client 与 http/client 共享同一 HttpClient 实例（消除双连接池）。
>
> **已落地**：
> - `provider/http/stream_client.clj` —— java.net.http `BodyHandlers/fromLineSubscriber` 真流式传输，
>   回调契约 `on-token/on-complete/on-error` + `cancel`，executor 用虚拟线程，非 2xx 走 D5 canonical error，
>   默认补 `Content-Type: application/json`。
> - **两条流式路径全部迁移**：
>   - anthropic 同步/异步流式（影响 anthropic / **minimax** / zhipu Anthropic 协议）。
>   - openai_compat 同步/异步流式（影响 openai / deepseek / zhipu / gemini / mistral / ollama / **openai-compat**）。
>   - deepseek 专属流式测试改为 stub `stream-client/post-stream-async`（回放 SSE 行，驱动同一解析器）。
> - **真实端点验证（MiniMax-M2.7，两个端点都测）**：
>   - Anthropic 端点（api.minimaxi.com/anthropic）：reasoning 与正文分离，token 逐个到达。
>   - OpenAI 端点（api.minimaxi.com/v1）：token t+1.5s 起逐个到达，`extract-text` 完整（思考内联 `<think>`）。
>   - 对比 http-kit 伪流式会全部挤在结尾一次性爆出。验证脚本：`examples/minimax_stream_test.clj`。
> - **live 测试逮到并修了 2 个真 bug**（单测不可见）：(1) `http-response->error` 把嵌套
>   `{:error {:message}}` map 当 message → `throw!` ClassCastException（D5 低优先级项）；
>   (2) stream_client 缺默认 Content-Type → 部分服务端解析不到 body。两者均已修 + 回归测试。
> - 全套单测 **192/762/0**。
>
> **已落地（续，2026-06-10）**：
> - 移除 http-kit 流式死代码（`http/client.clj` 605→263 行，仅剩非流式）+ 废弃 with-retry。
> - `stream_client` 充分测试：本地 `com.sun.net.httpserver` SSE 服务，4 个集成测试（真增量时序 /
>   Content-Type / 非 2xx canonical / cancel）。
> - **kernel/SimpleAgent `chat-stream` 接入（step 4/5 完成）**：service `:stream-fn`（不支持流式回退同步）、
>   kernel `invoke-chat-stream`（复用 chat filter 链）、react loop 按 on-token 走流式、client `chat-stream`。
>   memory 每回合落库完整 assistant 消息，与同步不分叉。3 个单测 + MiniMax 端到端 live（多轮记忆正确）。
> - 全套单测 **197/769/0**。
>
> **待续**：Undertow WebSocket 适配器示例（用户已暂时忽略，按需再做）。

---

> 原始调研 / 设计（保留作背景）：
> 状态：📐 调研 / 设计
> 目标：(1) 修复 BUG2 —— 真正的增量流式；(2) **非阻塞、可整合异步 web 框架（Luminus / Ring）**；
> (3) 最小依赖。
> 环境：JDK 25（GraalVM CE）；本项目是**纯 agent 库**（无 web 框架依赖）；团队熟悉 core.async。

---

## 0. 一句话结论

**用 JDK 内置的 `java.net.http` + `BodyHandlers/fromLineSubscriber`（响应式 `Flow.Subscriber`）做传输层**，
保留现有 `on-token`/`on-complete`/`on-error` 回调作为**框架无关的底层原语**，
再提供**可选**适配器（core.async channel / Ring SSE / 阻塞 seq）把这条流接到任意上层。
Web 框架整合（Luminus/Undertow 等）只作 `examples/` 示例，**不进 core**。

---

## 0.5 设计原则：框架无关（硬约束，不可违反）

> **agent 框架是一个库，不是 web 应用。core 永远不依赖、不捆绑任何 web 框架
> （Luminus / Ring / Undertow / Jetty / Aleph / http-kit-server 一律不行）。**

理由：

1. **依赖方向**：web 应用**依赖**本库，不能反过来。core 一旦依赖某 web 框架，等于强迫所有
   使用者都用它——而 agent 的场景远不止 web（CLI、桌面、批处理、MQ 消费者、非 Luminus 的 web 栈…）。
2. **回调原语就是解耦边界**：`on-token / on-complete / on-error + cancel` 是最小的 push 契约，
   任何 sink 都能接（http-kit `send!` / Undertow `ServerSentEventConnection.send` / Aleph manifold
   stream / Jetty async body / 终端 print / core.async channel…）。**框架特定的东西全在适配那一层，
   core 一无所知。**
3. **越薄的边界越长寿**：web 框架会换（Luminus 默认 server 就在 http-kit→Immutant→Undertow 间换过几轮），
   JDK `java.net.http` + 一个回调契约不会。绑死某框架 = 跟着它的生命周期走。

落地约束：

| 层 | 约束 |
|---|---|
| **core**（clj-agent-core / clj-agent-provider 的 src） | 零 web 框架依赖。只暴露回调原语（`chat-stream` + on-token/cancel）。传输用 JDK `java.net.http`（非 web 框架） |
| **集成代码** | 只放 `examples/`（文档级、可跑）；**不进 src、不进核心 `:deps`**。web 依赖只在该 example 的 alias 里 |
| **不挑框架站队** | 不做"我们集成 Luminus"这种定位。要给示例就**覆盖多个**（http-kit / Undertow-SSE / Aleph）或讲**通用模式**（"拿到 token 往你的 sink 写"） |
| **若真要发适配器** | 单独的可选模块 + web 依赖标 `provided`/optional，绝不污染 core |

现状核对（已满足）：`chat-stream` 只认回调原语、`stream_client` 用 JDK `java.net.http`、
`deps.edn` 无任何 Ring/Luminus/Undertow/web-server 依赖。**本原则是"守住现状"，不是"待改造"。**

> 注：下文第 3–4 节给出的 Ring SSE / Undertow / core.async 适配器代码，全部属于"**示例 / 可选**"
> 范畴，**不是 core 的一部分**——按本原则它们只应出现在 `examples/` 或独立可选模块中。

---

## 1. 把问题拆成两个正交维度

选型乱，是因为把两件事混在一起。拆开后各自答案都清晰：

- **维度 A：HTTP 传输层** —— 谁能"边到边读"响应体（真增量）。这是 BUG2 的根。
- **维度 B：消费抽象** —— agent 把 token 以什么形态交给上层（回调 / channel / SSE 响应 / 序列）。这是"异步框架整合"的根。

关键认识：**现有的 `on-token`/`on-complete`/`on-error` 回调契约本身是好的**——它是 push 式、零依赖、框架无关的*底层原语*，所有高层抽象（channel、SSE 响应、惰性序列）都能在它之上构建。BUG2 不是回调契约的错，**纯粹是传输层（http-kit）填不进真增量数据**。所以维度 B 基本不用推倒，重点在维度 A + 加适配器。

---

## 2. 维度 A：HTTP 客户端评估

| | 真增量读 body | 原生异步/非阻塞 | 背压 | 依赖重量 | 非 2xx 错误体 | 超时粒度 |
|---|---|---|---|---|---|---|
| **http-kit**（现状） | ❌ **全量缓冲**（promise 在完整响应后才兑现，`:as :stream` 只改 body 形态不改时机） | 回调式，但同样等完整响应 | ❌ | 已在用 | 能拿但已缓冲 | 仅 total（掐断长生成） |
| **clj-http**（Apache HC） | ✅ 同步 `:as :stream` 是真增量 InputStream | 异步模式较 awkward（需自定义 response consumer 才增量） | ❌（同步阻塞线程读） | **重**（Apache httpclient 多 jar；且 D9 刚把它作为死依赖移除） | ✅ | total |
| **java.net.http**（JDK 内置） | ✅ `ofLines` / `fromLineSubscriber` 逐行增量 | ✅ **原生 `sendAsync` → `CompletableFuture`** | ✅ **`Flow.Subscriber` 原生背压** | ✅ **零新依赖**（JDK 11+，本机 JDK 25） | ✅ 可在 `BodyHandler.apply(responseInfo)` 按状态码分流 | request timeout 为 total，但可设大/无 + 连接级处理 |

**结论：java.net.http 全面胜出**——零依赖、原生异步、原生背压、与回调契约天然对齐。clj-http 能修同步流式但依赖重、异步差、且我们刚移除它；http-kit 根上做不了真流式。

### 2.1 java.net.http 真流式机制（推荐 `fromLineSubscriber`）

`Flow.Subscriber` 的 `onNext(line)` / `onComplete` / `onError` **正好一一映射**到
`on-token` / `on-complete` / `on-error`——这是最优雅的点：传输层与现有契约同构。

```clojure
(ns im.ttalk.agent.provider.http.stream-client
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse HttpResponse$BodyHandlers HttpResponse$BodySubscribers]
           [java.util.concurrent Flow$Subscriber]
           [java.nio.charset StandardCharsets]))

(def ^:private client (-> (HttpClient/newBuilder) (.connectTimeout (java.time.Duration/ofSeconds 30)) (.build)))

(defn- line-subscriber
  "Flow.Subscriber<String>：每行（SSE 一行）到达即 parse + on-token；映射 onComplete/onError。
   cancel-promise 用于客户端断连时取消上游。"
  [{:keys [parse-fn process-fn on-token on-complete on-error]} state-atom cancel-promise]
  (let [sub (atom nil)]
    (reify Flow$Subscriber
      (onSubscribe [_ s]
        (reset! sub s)
        (deliver cancel-promise #(.cancel s))   ;; 暴露取消句柄（断连/超时时调用）
        (.request s Long/MAX_VALUE))             ;; 或每条 request(1) 做精细背压
      (onNext [_ line]
        (when-let [ev (parse-fn line)]
          (let [[new-state token] (process-fn ev @state-atom)]
            (reset! state-atom new-state)
            (when (and token on-token) (on-token token)))))
      (onError [_ t] (when on-error (on-error {:error (.getMessage t) :cause t})))
      (onComplete [_] (when on-complete (on-complete @state-atom))))))

(defn post-stream-async
  "真流式 POST。返回 {:future CompletableFuture :cancel (fn [])}。token 边到边推。"
  [url {:keys [headers body timeout] :as opts}]
  (let [req (-> (HttpRequest/newBuilder (URI/create url))
                (.timeout (java.time.Duration/ofMillis (or timeout 300000)))   ;; 长生成给足
                (.POST (HttpRequest$BodyPublishers/ofString (json/generate-string body)))
                (add-headers headers)
                (.build))
        state (atom (:initial-state opts {}))
        cancel-p (promise)
        ;; BodyHandler：先看状态码——2xx 走流式 subscriber；非 2xx 收集 body 后按 D5 抛 canonical error
        handler (reify HttpResponse$BodyHandler
                  (apply [_ info]
                    (if (<= 200 (.statusCode info) 299)
                      (HttpResponse$BodySubscribers/fromLineSubscriber (line-subscriber opts state cancel-p))
                      (HttpResponse$BodySubscribers/ofString StandardCharsets/UTF_8))))
        cf (.sendAsync client req handler)]
    ;; 非 2xx：在 future 里转 canonical error（复用 D5 的 errors/http-response->error）
    {:future cf
     :cancel (fn [] (when (realized? cancel-p) (@cancel-p)) (.cancel cf true))}))
```

要点：
- **真增量**：`onNext` 在每行 SSE 生成的那一刻触发（修 BUG2）。
- **非阻塞**：由 HttpClient 的 I/O 线程驱动，不占调用方/服务器 worker 线程。
- **可取消**：返回 `:cancel`——客户端断连/超时时取消上游 HTTP，**不再继续烧 token**（重要：SSE 场景客户端关页面就该停）。
- **非 2xx 分流**：在 `BodyHandler.apply` 按状态码决定收错误体，再走 D5 的 `errors/http-response->error` 抛 canonical（顺手修了 http-kit 流式里"非 2xx 静默吞/不回调"的旧问题）。
- 简化版可先用 `BodyHandlers/ofLines` 拿 `Stream<String>`，在 `async/thread` 里消费——也是增量的，代码更短，作为过渡。

---

## 3. 维度 B：消费抽象 —— 回调原语 + 适配器（异步框架整合的核心）

底层只暴露一个 push 原语（`on-token`/`on-complete`/`on-error` + `cancel`）。
**框架整合 = 提供适配器把这个原语转成上层想要的形态**。每个适配器一个独立 ns，
依赖按需引入（core.async / ring 都不是核心硬依赖）。

```
                          ┌────────────────────────────────────────┐
 java.net.http (真增量) → │ on-token / on-complete / on-error (原语) │
                          └───────────────┬────────────────────────┘
                  ┌───────────────────────┼───────────────────────┐
            ->channel(core.async)    ->ring-sse(Luminus)      ->seq / reducible
            背压、可组合              text/event-stream 响应    阻塞惰性序列/transducer
```

### 3.1 适配器一览

| 适配器 | 形态 | 依赖 | 适用 |
|--------|------|------|------|
| 回调（原语） | `(fn [{:on-token :on-complete :on-error}] -> cancel)` | 无 | 任何场景的底座 |
| `stream->chan` | core.async channel（token 流） | core.async（可选） | 熟 core.async 的代码、需背压/组合 |
| `stream->seq` | 阻塞惰性 `seq` / `IReduceInit` | 无 | 脚本/REPL/简单消费 |
| `stream->ring-sse` | Ring `StreamableResponseBody` 响应 | ring-core（可选） | **Luminus / 任意 Ring 应用** |
| `stream->future` | 整段完成的 `CompletableFuture<完整响应>` | 无 | 只要最终结果但想异步 |

---

## 4. Web 框架整合示例（**仅 examples，不进 core**——见 §0.5）

> 以下是把回调原语接到具体 web 栈的**示例 glue**，按 §0.5 它们只应放在 `examples/`、
> web 依赖只在 example 的 alias 里。core 不依赖 Ring/Luminus/Undertow。
> 不只演示一种：SSE-over-Ring（可移植）/ Undertow 原生 SSE / http-kit / Aleph 都是同一个
> "把 token 往 sink 写"的模式。

以 Ring `StreamableResponseBody` 为例（最可移植，sync/async adapter 都支持）：

```clojure
;; 放 examples/，不是 core src
(ns example.ring-sse
  (:require [ring.core.protocols :as rp]
            [cheshire.core :as json]
            [clojure.java.io :as io]))

(defn sse-response
  "把 agent 的流式调用包成 SSE 响应。
   run-stream: (fn [{:keys [on-token on-complete on-error]}] -> cancel-fn)。"
  [run-stream]
  {:status 200
   :headers {"Content-Type"  "text/event-stream"
             "Cache-Control" "no-cache"
             "Connection"    "keep-alive"
             "X-Accel-Buffering" "no"}   ;; 关掉 nginx 缓冲，否则又变伪流式
   :body (reify rp/StreamableResponseBody
           (write-body-to-stream [_ _ out]
             (let [w    (io/writer out)
                   done (promise)
                   write! (fn [s] (locking w (.write w s) (.flush w)))
                   cancel (run-stream
                            {:on-token    (fn [t] (write! (str "data: " (json/encode t) "\n\n")))
                             :on-complete (fn [_] (write! "data: [DONE]\n\n") (deliver done :ok))
                             :on-error    (fn [e] (write! (str "event: error\ndata: "
                                                              (json/encode {:error (:error e)}) "\n\n"))
                                            (deliver done :err))})]
               (try @done                       ;; 上游异步推流；此线程等流结束
                    (finally (cancel) (.close w))))))})   ;; 客户端断开→write 抛异常→finally 取消上游
```

Luminus handler 里：

```clojure
(defn chat-stream-handler [req]
  (let [msg (get-in req [:params :message])]
    (sse-response
      (fn [callbacks]
        ;; agent 的流式 API（见 §5）；立即返回 cancel-fn，token 异步推进 callbacks
        (agent/chat-stream my-agent msg callbacks)))))
```

异步并发要点：
- `write-body-to-stream` 那一个线程在 SSE 期间被占住等 `@done`。token 生成本身是 java.net.http I/O 线程异步推的，所以**没有"每 token 占一个线程"**，但每条 SSE *连接* 占一个写线程。
- 要支撑**大量并发 SSE 连接**（不为每连接占线程），用服务器原生异步：
  - **http-kit-server**：`(hk/with-channel req ch ...)`，在 `on-token` 里 `(hk/send! ch (sse-frame t) false)`，`on-error/complete` 里 `(hk/close ch)`；`on-close` 回调里 `cancel`。**纯非阻塞、零写线程占用**——若 Luminus 用 http-kit-server，这是最优。
  - **Ring 3-arity async handler**（Undertow/Immutant）：用 async 响应 + StreamableResponseBody。
- 适配器对这两种都只是"把原语接到不同 sink"，agent 核心不变。

---

## 5. 把流式接进 kernel / SimpleAgent（现状：流式没进主链路）

传输修好后，还要把它接到 agent（否则只能绕过框架直调 provider，正是之前架构审查指出的断层）。

- **Service 加流式入口**：现 service 只有 `:chat-fn`（同步）。加 `:stream-fn`：
  `(fn [messages opts callbacks] -> cancel)`，内部走 provider 的真流式 + 把累积结果在 `on-complete` 交给 memory-filter 落库。
- **工具循环 × 流式的语义**（关键取舍）：
  - 流式只在**产出最终文本的回合**逐 token 推；**带工具调用的回合**不向用户流正文（可单独流 `reasoning`/思考），等工具执行完进入下一回合再流。
  - 即 `chat-stream` = ReAct 循环，循环内部对"文本回合"启用 token 流，对"工具回合"走同步累积。
- **memory / filter**：流式**结束**时（`on-complete`）把完整 assistant 消息经 `response->neutral` 落库——与同步路径一致，历史不分叉。filter 链对流式可先只支持 `:chat`（计时/日志），token 级 filter 后续再说。
- **错误**：流式失败走 D5 canonical error → `on-error`（已对齐）。

---

## 6. 超时语义（顺带修 http-kit 的掐断问题）

- http-kit 的 `:timeout` 是整段 total → 长生成（>120s）直接掐断且丢全部部分结果。
- java.net.http 的 `HttpRequest.timeout` 也是 total，但：
  - 设足够大（如 5–10 min）或不设；长连接靠 SSE 心跳（部分 provider 发 `: keep-alive` 注释行）维持。
  - 真流式下即便最终超时/断连，**已 emit 的 token 已经到手**（不像伪流式整段丢）。
  - 需要"块间空闲超时"（idle-between-chunks）可在 `onNext` 时刷新一个 watchdog 定时器，超时调 `cancel`。

---

## 7. 迁移范围、风险、回滚

- **新增（core）**：仅 `http/stream_client.clj`（java.net.http 真流式传输）。**适配器不进 core**——
  Ring SSE / Undertow / core.async 等只作 `examples/` 示例（见 §0.5）。
- **改**：provider 的 `call-api-stream` / `call-llm-stream` 改走新传输（anthropic / openai_compat 两处 + bailian 仍声明不支持）。SSE 解析（`parse-sse-line` / `process-event`）**复用现有**——它们是纯函数，与传输无关，本就测得好。
- **保留 http-kit** 用于非流式 `call-llm`（工作良好），不强行大改；后续若要统一可再评估全迁 java.net.http（能彻底去掉 http-kit 依赖）。
- **依赖**：core 零新增（java.net.http 是 JDK 内置）。ring-core / core.async / web 框架**一律不进核心 `:deps`**——只在 examples 的 alias 里（§0.5 硬约束）。
- **风险与验证**：流式的真问题（断流、非 2xx、连接生命周期、超时、背压、客户端断连取消）**本地 mock 验不出**，**必须对真实 SSE 端点**（如 GLM/DeepSeek/Anthropic）跑端到端：首 token 延迟、逐块时序、长生成不掐断、断页面后上游停止。
- **回滚**：传输层隔离在独立 ns，可用 config flag 在新旧传输间切，灰度。

---

## 8. 推荐落地顺序

1. ✅ **传输层 + 回调原语**（修 BUG2 本体）：`stream_client.clj`（java.net.http `fromLineSubscriber`），provider 流式切过去；真实端点验证逐块时序。**（已完成）**
2. ✅ **接 kernel / SimpleAgent**：service `:stream-fn` + `invoke-chat-stream` + `chat-stream`（ReAct 内"文本回合"流、`on-complete` 落库）。**（已完成）**
3. ⏳ **集成示例（examples，非 core）**：按需在 `examples/` 放框架无关的 glue 演示（"token → sink"模式），
   可选覆盖 http-kit / Undertow 原生 SSE / Aleph / core.async channel。**不挑框架站队，不进 core**（§0.5）。

> 第 1–2 步（核心能力）已落地：真流式 + 接入 agent 主链路、MiniMax 端到端验证。
> 第 3 步纯属对外集成的**文档/示例**，按"框架无关"原则只作 examples。
