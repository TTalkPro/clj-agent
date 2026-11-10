# clj-agent-server：AG-UI 与 MCP 的 HTTP 服务端（Ring）

> 状态：✅ 已实施（2026-11-10，S-01～S-07；施工差异见 §7）
> 出处：Keelson `agui-server` 分支（worktree `~/workspace/keelson-agui-server`，提交 5899776c）的
> `jvm/modules/clj-net-server`（Keelson.Net.Server 的 JVM 版）与 `jvm/modules/clj-agui-server`（Keelson.Agui.Server 的 JVM 版）；
> MCP 端点转正自本仓库 `examples/mcp/server_example.clj`。Keelson 没有 MCP server（C# 与 jvm 都没有）。
> 任务：[S-01](../tasks/S-01.md) … [S-07](../tasks/S-07.md)。

## 0. 一句话

**服务端协议就是 Ring**（https://github.com/ring-clojure/ring 的 SPEC：请求 map → 响应 map，流式正文用
`ring.core.protocols/StreamableResponseBody`）。新增两个模块：

- `clj-agent-server`：只依赖 ring-core（外加 cheshire 与本仓库的 http / agui / mcp）。路由、本机访问守卫、请求体上限、SSE、
  AG-UI 的那组路由、MCP 的 Streamable HTTP 端点——**全是普通的 Ring 处理器 / 中间件**，不认识任何具体服务端。
- `clj-agent-server-jetty`：缺省 adapter——Jetty 12（`ring/ring-jetty-adapter`）宿主：绑地址、挂处理器、起、停、优雅停机。

用户换服务端（http-kit、Undertow …）= 把同一个 Ring 处理器交给那个服务端的 adapter；`clj-agent-server-jetty` 不用引。

## 1. 为什么要 adapter 侧垫片（实测，2026-11-10）

Ring SPEC 给流式响应定的协议是 `StreamableResponseBody`。实测三个服务端对 SSE（3 帧、每帧间隔 700ms）的表现：

| 正文写法 | Jetty（ring-jetty-adapter 1.15.6） | http-kit 2.8.0 | Undertow（luminus 1.4.1） |
|---|---|---|---|
| `StreamableResponseBody` | ✅ 真流式 | ❌ 500「not understandable」 | ❌ 500「Body class not supported」 |
| 管道 `InputStream` | ❌ 攒满才发 | ❌ 攒满 | ❌ 攒满 |
| 惰性 seq | ✅ | ❌ 攒满 | ❌ 攒满 |

**Ring 规范里没有一种正文写法在三家都真流式**；http-kit 与 Undertow 的 adapter 没实现 SPEC 的流式正文。Keelson 文档
「换一个 Ring 服务端照样能挂」对 SSE 不成立（只对 Jetty 成立）。

做法：**库代码只按 SPEC 写**（SSE 返回 `StreamableResponseBody`），把「不合 SPEC 的 adapter」补齐放在 adapter 一侧：

- `im.ttalk.agent.server.adapter.http-kit/wrap-streaming`：Ring 中间件。响应正文**直接实现** `StreamableResponseBody`
  接口（reify / deftype；String、File 等经 `extend` 的不算）⇒ 改走 http-kit 的 `as-channel`，在虚拟线程上
  `write-body-to-stream` 到一个转发给 `send!` 的 OutputStream；对端断开（`:on-close`）⇒ 该流写失败 ⇒ SSE 记 `gone?`。
- `im.ttalk.agent.server.adapter.undertow/install!`：给 luminus adapter 的开放协议 `RespondBody` 补上
  `StreamableResponseBody` 接口的实现（`startBlocking` 后写 exchange 的 OutputStream）。

两个垫片是**通用的**（任何合 SPEC 的流式响应都能用，不只 SSE），用 `requiring-resolve` 取对方的库，不引硬依赖。
原型实测：两家都恢复真流式（帧间隔 ≈ 700ms），普通正文不受影响。

## 2. 模块边界与依赖

```
clj-agent-server-jetty ──► clj-agent-server ──► ring-core, cheshire
        │                         ├──► clj-http   （SSE 拼帧）
        └──► ring-jetty-adapter   ├──► clj-agent-agui   （runtime / registry / codec / thread-store；连带 core / client / mcp）
                                  └──► clj-agent-mcp    （server/handle-message、check-headers）
```

- design-principles §2.2：「若真要发适配器——单独的可选模块 + web 依赖不污染 core」。core / client / provider / agui / mcp
  依旧零 web 依赖；Ring 只进 `clj-agent-server`，Jetty 只进 `clj-agent-server-jetty`。
- ring-core 不是 web 框架：它是 SPEC 的数据约定 + 协议（`StreamableResponseBody`），没有服务端。
- clj-http 里补上 SSE **拼帧**（`event-frame` / `comment-frame` / `retry-frame`，移植自 clj-net；上一轮因「无调用方」
  没搬，现在服务端就是调用方）。

## 3. 移植范围

| Keelson | clj-agent | 取舍 |
|---|---|---|
| `clj-net-server` routing / guard / http / sse | `im.ttalk.agent.server.{routing,guard,http,sse}` | ✅ 逻辑照搬；`clj-core` 的 error / i18n / json 换成本模块的 `strings`（中文文案内联）与 cheshire |
| `clj-net-server` 宿主 `im.ttalk.net.server` | `clj-agent-server-jetty` 的 `im.ttalk.agent.server.jetty` | ✅ 照搬（地址 / TLS / Unix 套接字 / 守卫 / 中间件 / 停机钩子 / 开着的流）；去掉 WebSocket 相关选项 |
| `clj-net-server` websocket | — | ❌ AG-UI 与 MCP 都不用（design-principles §1：无调用方不建） |
| `clj-agui-server` 全部 | `im.ttalk.agent.server.agui` + `.agui.{host,http,input,replay,resume,thread-store}` | ✅ 照搬；它本就建在 clj-agent-agui 之上（resume 段原样出自本仓库 examples/copilotkit/http_kit_routes.clj） |
| — | `im.ttalk.agent.server.mcp` | ✅ 新：MCP Streamable HTTP 的 Ring 处理器（转正自 examples/mcp/server_example.clj） |
| — | `im.ttalk.agent.server.adapter.{http-kit,undertow}` | ✅ 新：§1 的垫片 |

错误 / 文案：Keelson 用 `{:keelson/code "区/事件" …}` + resx 生成的文案包。这里用 `im.ttalk.agent.server.strings`：
`(msg k & args)` ⇒ `{:code :text}`（`{0}` 占位），`(error k args data)` ⇒ ex-info，ex-data 带 `::server/code`。码沿用 Keelson 的
（`server/body-too-large`、`agui-input/bad-json` …），线上的错误种类码（`bad_json`、`thread_not_found` …）一字不改——它们是 AG-UI 的线协议。

## 4. MCP 端点

```clojure
(require '[im.ttalk.agent.server.mcp :as mcp-http] '[im.ttalk.agent.mcp.server :as srv])
(mcp-http/handler (srv/server {...}) {:origin-allowed nil :max-body-bytes (* 4 1024 1024)})
;; 或挂进路由表：(mcp-http/routes "/mcp" s opts)
```

- `POST`：读体（受上限）→ 解析 JSON（坏 ⇒ 400 `-32700`）→ `srv/check-headers`（不一致 ⇒ 400 `-32020`）→ `srv/handle-message`
  → 有响应 ⇒ JSON（校验类错误码 `-32602 -32020 -32021 -32022` ⇒ 400，其余 200——客户端的双时代探测看状态码）；通知 ⇒ 202 空体。
- `GET` ⇒ 405（本端点不开服务端推送流；规范允许）；`OPTIONS` ⇒ 预检 204；其他方法 ⇒ 405。
- **Origin 缺省拒**（带 Origin 且不在 `:origin-allowed` 里 ⇒ 403）：规范要求服务端校验 Origin 防 DNS rebinding。
  example 里的 `Access-Control-Allow-Origin: *` 不带进库。
- 只回 JSON，不回 SSE：`handle-message` 一条进一条出，没有要推的中间消息（规范允许服务端选 JSON）。

## 5. 测试

- 纯 Ring 的（routing / guard / http / strings / mcp 处理器直调）在 `clj-agent-server` 自己的测试里。
- 起真服务端的（SSE、AG-UI 路由、停机）沿用 Keelson 的做法：JDK HttpClient 打真实端口。服务端用 Jetty 宿主——
  `clj-agent-server` 的 `:test` alias 引 `clj-agent-server-jetty`（仅测试期）。
- 三服务端 SSE 互通（§1）：`:test` alias 另引 http-kit 与 Undertow（仅测试期），同一个 SSE 处理器在三家各跑一遍、断言帧间隔。

## 6. 不做的

- WebSocket（无调用方）。
- `/connect` 的 lastSeq 续读（Keelson 也没做，见 clj-agui-server ns 文档）。
- 删除 `examples/copilotkit/http_kit_routes.clj`：它与 `server.agui` 功能重叠，但它是 examples 门禁的一部分、且演示 http-kit
  直连写法；本轮保留，是否删由用户定（记在 S-07）。

## 7. 施工与设计的差异

1. **Jetty 宿主保留了 WebSocket 选项**（`:ws-max-message-bytes` / `:ws-idle-timeout-ms`）：它们只是 Jetty adapter 的配置、
   不带端点；保留后用户自己的 Ring WebSocket 路由在这台宿主上照样能用。本模块仍不提供 WebSocket 端点（§6）。
2. **http-kit 上 SSE 发现不了断连**（实测）：客户端断开后 http-kit 2.8 的 `send!` 仍回 true、`open?` 仍为 true、`:on-close`
   不触发（关套接字后连写 40 次、每次 8 KB 都「成功」）。垫片无从得知；后果是流写到 producer 自己结束，`:orphan-cancel-ms`
   在 http-kit 上不生效。已写进垫片 ns 文档与模块 README；断连测试只对 Jetty / Undertow 断言。
3. **Undertow 垫片走反射**：给 `HttpServerExchange` 加类型提示会让编译期要求 Undertow 在 classpath 上，不用 Undertow 的人（及
   check-docs 加载全部 ns）连这个 ns 都加载不了。
4. **一处移植 bug**：宿主用 `::on-stopping`（自动解析）读路由表元数据，ns 改名后解析成 `:im.ttalk.agent.server.jetty/on-stopping`，
   与 AG-UI 路由写的 `:im.ttalk.agent.server/on-stopping` 对不上，停机钩子丢失（测试 `shutdown-gives-shutdown` 等当场红）。改为显式键。
5. **文案**：Keelson 的中文文案里指名 C# API 的（`AguiRegistryOptions.OriginAllowed`、`AccessGuardOptions.Token`、Kestrel、
   `AguiThreadSlots.Events` …）改成 Clojure 这边的选项名（`:origin-allowed`、`:guard {:token …}`、`:events` …）；
   localization 测试（中英两份资源包一致性）改为 `strings_test`（代码里用到的键都在表里、渲染、码不变）。
6. **测试用的阻塞式 SSE 读者**（`read-stream` / `next-event`）放在测试支撑 ns 里，不进 clj-http——src 里没有调用方。
7. **clj-agent-http 改名 `im.ttalk/clj-http`**（用户拍板，2026-11-10）：本模块依赖它拼帧。
8. `examples/copilotkit/http_kit_routes.clj`（用户拍板：改成调用新模块、删掉重复的路由代码，2026-11-10）：1140 → 192 行，只剩
   装配、换 http-kit（垫片）与两条 demo 自己的应用端点（`/pending`、`/approve`，不属于 AG-UI 所以不进库）；`start!` /
   `start-registry!` / `stop!` 签名不变，demo_server 不用改。为此给 `server.agui` 补了两个旧路由有、新模块缺的挂点：
   条目的 `:mcp-proxy`（`forwardedProps.__proxiedMCPRequest` 不起 run，回 RUN_STARTED / RUN_FINISHED{result}）与
   `:input-transform` / `:mcp-proxy` 的 host 级缺省（`agui/plugins_test.clj`）。旧测试里 `resume-decision` / `resume-plan` 的
   用例搬进 `agui/resume_unit_test.clj`；`heartbeat!` 那组随旧实现删除（SSE 心跳在 `adapter_test` / `server_test` 里有）；其余
   端到端用例原样跑在新实现上，只按线协议改了这几处断言：错误码 `bad_input`、改动类带 `agentId`、`GET /threads/:id` ⇒ 405。
   **核对上游与 happy（2026-11-10）**：CopilotKit 上游（提交 f51697f4）`fetch-router.ts` / `fetch-handler.ts` 里 `/threads/:id` 只收
   PATCH / DELETE（GET ⇒ 405 `Allow: PATCH, DELETE`），没有「POST 同义 PATCH」；`handlers/intelligence/threads.ts` 的改动类要求体里
   带合法 `agentId`（缺 ⇒ 400），而上游前端 `core/src/threads.ts` 的改名 / 归档 / 取消归档 / 删除**都**带 `agentId`；`/run` 输入非法
   上游回 400 `{"error":"Invalid request body"}`（没有细分码，前端只看状态码）。happy（`modules/core/src/happy/agui`）只用
   `/info`、`/agent/:id/run`、`/threads` 与 `/threads/:id/{messages,state,events}` 的 GET、带 `agentId` 的 DELETE、`resume[]`；
   非 2xx 一律合成 RUN_ERROR（不解析错误码）；demo 页跑在 `localhost:3002`（在 `dev-origins` 白名单里）。⇒ 本轮的线协议变化对
   上游前端与 happy 都不构成破坏。与上游的两处差异：CORS 名单外上游只是不回 CORS 头、这里回 403（浏览器侧效果相同）；上游
   `/connect` 认 `lastSeenEventId` 续读，这里不做（§6）。
