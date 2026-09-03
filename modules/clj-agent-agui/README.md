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
| `im.ttalk.agent.agui.genui` | **可选插件**：Open Generative UI —— 模型直接生成沙箱 UI，参数翻译成 activity 事件（移植自 CopilotKit） |
| `im.ttalk.agent.agui.a2ui` | **可选插件**：A2UI —— 声明式生成式 UI，模型按 catalog 拼组件树（不执行任意代码） |
| `im.ttalk.agent.agui.mcp` | **可选插件**：MCP 接入 —— server 的工具进工具表；带 UI 资源的（MCP Apps）额外出 activity |

**零 web 依赖**（`design-principles.md` §2）：订阅是回调，本模块不认识
Request / Response / SSE 帧。示例见 [`examples/copilotkit/`](../../examples/copilotkit/)：

| 文件 | 干什么 |
|---|---|
| `agui_example.clj` | 六场景端到端，**离线可跑**（桩 provider + 假 SSE 连接） |
| `agui_live_test.clj` | 同样六场景，**真实 MiniMax 端点**（需 `MINIMAX_API_KEY`） |
| `http_kit_routes.clj` | 真路由：四条 CopilotKit v2 端点 + `/suggest` + SSE（`id:` 放 `:seq` = `Last-Event-ID`） |
| `mcp_server_example.clj` | 最小 MCP server（Streamable HTTP），验 `agui.mcp` 客户端那一半 |

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
{:type :reasoning/started :message-id}          ;; 思考块开——**独立的一条消息**
{:type :message/thinking  :message-id :text}   ;; 思维 token（message-id 是思考块的）
{:type :reasoning/ended   :message-id}
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

;; 下面两条只有装了 agui.genui 插件才会出现（核心路径不发）
{:type :activity/snapshot :message-id :activity-type :content}
{:type :activity/delta    :message-id :activity-type :patch}   ;; JSON Patch
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
4. **暂停走 AG-UI 的 interrupt 协议**：`:run/paused` 编码成
   `RUN_FINISHED` + `outcome:{type:"interrupt", interrupts:[…]}`——**收口与告知
   是同一条事件**（run 必须有终态，流才关得掉；outcome 又说清了不是跑完了）。
   `interrupt.id` 取 pending 工具的 tool-call id（`codec/interrupt-id`），客户端
   把人的决定放在**下一次 run 的 `resume[]`** 里送回来。`/info` 里报
   `humanInTheLoop.interrupts`，标准客户端据此才知道我们支持。

## 可选插件：Open Generative UI

移植自 CopilotKit 的 `open-generative-ui-middleware.ts`。模型调
`generateSandboxedUi` 生成一块 HTML/CSS/JS，后台**不执行**它——把参数翻译成
`ACTIVITY_SNAPSHOT` + 一串 `ACTIVITY_DELTA`（JSON Patch），前端在沙箱 iframe
里边收边渲染。**默认不装**，两个挂点：

```clojure
(require '[im.ttalk.agent.agui.genui :as genui]
         '[im.ttalk.agent.agui.runtime :as rt]
         '[im.ttalk.agent.agui.tools :as tools])

(rt/runtime {:agent-fn (tools/agent-fn (genui/with-tool spec))   ;; 工具 + 设计规范
             :event-transform (genui/event-transform)})          ;; 事件翻译
;; 再把 `/info` 的 openGenerativeUIEnabled 报 true（codec/run-info 的
;; :open-generative-ui? 选项），前端才会注册它那半边的 renderer
```

`/threads*` 是**只读面**：线程列表来自会话注册表、消息来自 ChatMemory、事件来自
环形缓冲——没有第二套存储。两条边界：会话空闲 30 分钟被驱逐后就不在列表里
（ChatMemory 协议没有「列出所有会话」）；线程名与归档标记只活在进程内。

另有 `rt/run-detached!`：在临时发射器上跑一次**不留痕**的 run（不进注册表、
不落库、没有订阅 / stop / resume）。`/suggest`（「猜用户下一句想说什么」）走的
就是它——一次性的建议不该在会话表里留下一堆废线程。

`:event-transform` 是 runtime 的**通用**插件挂点（底下是 `agui.event/emitter`
的 `:transform`）：可以改写、吞掉、或在一条事件前后插事件；每个产出各自取号，
所以 `:seq` 单调无洞的契约不受影响，**终态事件不过 transform**。

一处与上游的真实差异：CopilotKit 那边吃的是流式 tool-call 参数，我们的运行时
不增量流式工具参数（见下方「边界」），所以今天是**一口喂完**——事件的
形状与顺序一致，「边生成边长出来」要等参数流式落地。扫描器本身是增量的，那天
到了这个 ns 一个字都不用改。

## 可选插件：A2UI（声明式生成式 UI）

移植自 `@ag-ui/a2ui-middleware`。与上面那个的分工：

| | Open Generative UI | A2UI |
|---|---|---|
| 模型生成 | 一整块 HTML/CSS/JS | **组件树**（只能用 catalog 里的组件） |
| 谁渲染 | 沙箱 iframe 执行模型写的代码 | 前端按自己的组件库渲染 |
| 可控性 | 任意 JS（所以要沙箱） | 白名单：模型编不出 catalog 之外的东西 |

```clojure
(require '[im.ttalk.agent.agui.a2ui :as a2ui])

(rt/runtime {:agent-fn (tools/agent-fn (a2ui/with-tool spec))
             :event-transform (a2ui/event-transform)})
;; 用户点了生成出来的按钮 → forwardedProps.a2uiAction，用 (a2ui/input-transform) 接回来
```

`with-tool` 注入 `render_a2ui` 工具 + 用法提示词 + **catalog**（缺省是 A2UI v0.9
基础 catalog 的 18 个组件，摘自 `@a2ui/web_core`；**换成你前端真正注册的那份**
才渲染得出来）。事件是一条 `ACTIVITY_SNAPSHOT`（`activityType` `a2ui-surface`，
`replace: true`，内含 `createSurface` / `updateComponents` / `updateDataModel`
三条 op，顺序是协议要求的）。

## 可选插件：MCP

对标上游的 `MCPMiddleware` + `MCPAppsMiddleware`。客户端是 JSON-RPC 2.0 over
Streamable HTTP，走 JDK 自带的 `java.net.http`——**不加依赖**，而且 `:transport`
可注入（测试用纯函数，不起服务）。

```clojure
(require '[im.ttalk.agent.agui.mcp :as mcp])

(def servers [{:type :http :url "http://localhost:4100/mcp" :server-id "demo"}])

(rt/runtime {:agent-fn (tools/agent-fn (mcp/with-tools spec servers))
             :event-transform (mcp/event-transform {:apps (mcp/app-tools (:tools spec))
                                                    :servers servers})})
;; 前端那块 MCP UI 发起的调用（forwardedProps.__proxiedMCPRequest）
;; → (mcp/proxy-request servers req)，**方法有白名单**
```

**连接发生在装配期**（`with-tools` 会去 `tools/list`）：server 挂了宁可起服务时
就报，也不要每轮对话多一次网络往返。工具 `_meta` 带 `ui/resourceUri` 的就是
MCP App，结果之外还会发一条 `mcp-apps` activity 快照。

跑得起来的对端：`examples/copilotkit/mcp_server_example.clj`（最小 MCP server，
两个工具，其中一个带 UI 资源）。

## 边界

单进程（多实例要 sticky session）；不做 durable execution（run 事件不落库，
历史找 ChatMemory）；跨 run 共享状态还没有（客户端 state 按 turn 级注入）；
工具参数不增量流式。逐条理由见设计文档 §7 / §10。
