# clj-agent-agui — Agent Runtime 后台机制 + AG-UI 协议

> **一句话**：让 **run 的寿命不再由 HTTP 请求的寿命决定**，并把事件流按 AG-UI
> 编码，于是 CopilotKit 之类的前端可以**直连 Clojure**——不需要中间那个 Node
> runtime，也不需要把我们当它的 remote agent。

设计与判据见 [`docs/agent-runtime-design.md`](../../docs/agent-runtime-design.md)。

## 它解决什么

`agent/chat-stream` 把 token 直推调用方的 sink——**sink 死了这轮的输出就没了**：
浏览器刷新、切网、锁屏，都只能取消重来。`chat-async` 的 run 不占请求线程，但没有
第二个入口能观察它、停它、或在断线后接回它。而「一个会话一个 agent、别并发」今天
是 `simple-agent` docstring 里的一句警告，不是结构上的不变量。

这个模块补的就是这三件事：

| 能力 | 怎么来的 |
|---|---|
| 断线重连不丢输出 | 事件进会话的**有界环形缓冲**，重连带 `:since`（会话级单调 `:seq`）只补缺口 |
| 跨请求 stop | 取消令牌存在注册表里，另一个请求凭 conversation-id 就能停 |
| 跨请求 HITL | `resume-run!` 凭 conversation-id 恢复——审批按钮本来就是另一个 HTTP 请求 |
| 一会话一 run | 注册表按会话上锁，`:reject`（缺省）或 `:supersede` |

## 命名空间

| ns | 干什么 |
|---|---|
| `im.ttalk.agent.agui.runtime` | 会话注册表 + run 生命周期 + 订阅（`runtime` / `start-run!` / `resume-run!` / `subscribe` / `stop!` / `run-status` / `shutdown!`）。**中立，无 AG-UI 概念** |
| `im.ttalk.agent.agui.event` | 中立事件模型 + 每 run 的发射器（`:seq` 无洞、开块补关、终态唯一） |
| `im.ttalk.agent.agui.emit` | 接线：`on-token` + `:iteration` filter + `:on-llm-result` → 事件 |
| `im.ttalk.agent.agui.codec` | 中立事件 ⇄ AG-UI 事件、中立消息 → AG-UI 消息、`RunAgentInput` 解析 |
| `im.ttalk.agent.agui.tools` | AG-UI 前端工具（client-side tool）→ 内联工具 + gate |

**零 web 依赖**（`design-principles.md` §2）：订阅是回调，本模块不认识
Request / Response / SSE 帧。示例见 [`examples/copilotkit/`](../../examples/copilotkit/)：

| 文件 | 干什么 |
|---|---|
| `agui_example.clj` | 六场景端到端，**离线可跑**（桩 provider + 假 SSE 连接） |
| `agui_live_test.clj` | 同样六场景，**真实 MiniMax 端点**（需 `MINIMAX_API_KEY`） |
| `http_kit_routes.clj` | 真路由：四条 CopilotKit v2 端点 + SSE（`id:` 放 `:seq` = `Last-Event-ID`） |

## 用

```clojure
(require '[im.ttalk.agent.agui.runtime :as rt]
         '[im.ttalk.agent.agui.tools :as agui-tools])

(def runtime
  (rt/runtime {:agent-fn (agui-tools/agent-fn
                          {:provider provider
                           :tools [#'get-weather]
                           :memory store            ;; 跨 run 共享
                           :pause-store pause-store ;; HITL 跨请求/跨重启
                           :on-pause (fn [_])})
               :on-concurrent :supersede}))         ;; 聊天 UX：新消息顶掉上一条

;; 订阅（你的 SSE / WebSocket 连接）
(def unsub (rt/subscribe runtime "conv-1" {:since 42 :on-event #(send! ch %)}))

;; 起 run —— 立刻返回，run 在后台跑
(rt/start-run! runtime "conv-1" "北京天气?")
;; => {:status :started :run-id "..." :since 41} | {:status :busy ...} | {:status :awaiting-resume ...}
;; ⚠️ HTTP 层拿 :since 去订阅：run 已经起跑并发了第一条事件，你才轮到订阅。
;;    传 nil 会漏掉 :run/started——AG-UI 客户端第一件事就是报
;;    `First event must be 'RUN_STARTED'`（联调实测）。

;; 另一个请求线程
(rt/stop! runtime "conv-1")
(rt/resume-run! runtime "conv-1" "approved")
```

### 事件

```clojure
{:type :run/started    :run-id :conversation-id :seq :ts}
{:type :message/started :message-id :role}
{:type :message/delta   :message-id :text}      ;; 流式 token
{:type :message/thinking :message-id :text}
{:type :message/ended   :message-id}
{:type :tool/started    :tool-call-id :name}
{:type :tool/args       :tool-call-id :args}
{:type :tool/ended      :tool-call-id}
{:type :tool/result     :tool-call-id :content}
{:type :state/snapshot  :state}
{:type :run/paused      :reason :pending-tool}  ;; 终态
{:type :run/finished    :text :tool-calls-made} ;; 终态
{:type :run/cancelled   :text}                  ;; 终态
{:type :run/error       :error}                 ;; 终态
{:type :run/resync      :messages}              ;; 落后太多时补的 ChatMemory 快照
```

三条契约：**`:seq` 在一个会话内单调无洞**（跨 run 连续）；**恰好一个终态事件**
且它是最后一条；错误值是 canonical error（不是字符串）。

## 三件要知道的事

1. **`stop!` 返回 true 只表示「取消已登记」**，不表示已经停了。JVM 上没有抢占
   原语，取消 = 放弃等待 + 协作式中断：**正在跑的工具会跑完**。停稳的信号是
   `:run/cancelled` 事件。口径同 [`docs/tool-timeout-design.md`](../../docs/tool-timeout-design.md)。
2. **历史取服务端权威**：AG-UI 的 `RunAgentInput.messages` 是客户端那份历史，
   `codec/parse-run-input` **只取最后一条 user 消息**。memory filter 在循环内落库，
   heal-dangling / 暂停恢复 / timeline 全依赖服务端历史。
3. **前端工具不是新机制**：它是既有 HITL 词汇的一个用法——内联工具 + gate 暂停 +
   `resume :reply`（载荷即工具结果）。见 `agui.tools` 的 docstring。

## 边界

单进程（多实例要 sticky session）；不做 durable execution（run 事件不落库，
历史找 ChatMemory）；跨 run 共享状态还没有（客户端 state 按 turn 级注入）；
工具参数不增量流式。逐条理由见设计文档 §7 / §10。
