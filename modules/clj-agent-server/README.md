# clj-agent-server

AG-UI 与 MCP 的 HTTP 服务端。**服务端协议就是 [Ring SPEC](https://github.com/ring-clojure/ring)**：本模块全是普通的
Ring 处理器 / 中间件（请求 map → 响应 map；流式正文用 `ring.core.protocols/StreamableResponseBody`），不认识任何具体服务端。
缺省 adapter 是 [`clj-agent-server-jetty`](../clj-agent-server-jetty/README.md)（Jetty 12）；换 http-kit / Undertow 见下文「换服务端」。

依赖：ring-core（SPEC 的数据约定与协议，没有服务端）、cheshire、`im.ttalk/clj-http`（SSE 拼帧）、clj-agent-agui、clj-agent-mcp。
设计与取舍：`designs/server-module.md`。AG-UI 部分移植自 Keelson 的 clj-agui-server，通用部分移植自 clj-net-server。

## 用法

```clojure
(require '[im.ttalk.agent.agui.registry :as registry]
         '[im.ttalk.agent.mcp.server :as mcp]
         '[im.ttalk.agent.server.agui :as agui]
         '[im.ttalk.agent.server.agui.host :as host]
         '[im.ttalk.agent.server.agui.http :as ahttp]
         '[im.ttalk.agent.server.mcp :as mcp-http]
         '[im.ttalk.agent.server.routing :as routing])

(def h (host/host (registry/registry [entry])                ;; clj-agent-agui 的注册表条目
                  {:version "1.0.0"
                   :origin-allowed (ahttp/origin-allow "http://localhost:3000")}))

(def table (into (agui/routes "/api/copilotkit" h)            ;; AG-UI 那组路由
                 (mcp-http/routes "/mcp" (mcp/server {:name "demo" :version "1.0" :tools [...]}))))

(def handler (routing/router table))                        ;; 一个 Ring 处理器——交给任何 Ring 服务端
```

缺省 adapter（Jetty）直接收路由表，并从路由表元数据里登记停机钩子（停机时按停在飞的 run）：

```clojure
(require '[im.ttalk.agent.server.jetty :as jetty])
(def s (jetty/start table {:port 3000}))
(jetty/stop! s)
```

完整可跑的例子（mock 模型、三种服务端）：`examples/server/demo.clj`（`clojure -M:server`）。

## 换服务端

库代码只按 Ring SPEC 写。实测（2026-11-10）http-kit 2.8 与 Undertow（luminus adapter 1.4.1）**不实现** SPEC 的流式正文
（直接 500），惰性 seq / InputStream 正文也是攒满才发——Ring 里没有一种写法三家都真流式。所以给这两家各配一个 adapter 侧垫片：

```clojure
(require '[im.ttalk.agent.server.adapter.http-kit :as hk-shim]
         '[im.ttalk.agent.server.adapter.undertow :as ut-shim])

;; http-kit：中间件，把 SPEC 流式正文改走它的 as-channel
(org.httpkit.server/run-server (hk-shim/wrap-streaming handler) {:port 3000})

;; Undertow：给它的 RespondBody 协议补上 SPEC 流式正文的实现（进程里调一次）
(ut-shim/install!)
(ring.adapter.undertow/run-undertow handler {:port 3000})
```

垫片是通用的（任何合 SPEC 的流式响应都能用），不引硬依赖（`requiring-resolve`）。别的服务端上停机时请自己调
`host/cancel-all!`。⚠️ http-kit 上 SSE **发现不了断连**（它在客户端断开后 `send!` 仍回成功、`:on-close` 不触发）：流一直写到
run 结束，`:orphan-cancel-ms` 不生效——要断连检测用 Jetty 或 Undertow。

## AG-UI 路由

```
GET  {prefix}/info                       名录与能力位
POST {prefix}/agent/:id/run              纯 AG-UI 的 SSE
POST {prefix}/agent/:id/connect          只订阅、不起 run：重放在飞 / 刚跑完那一轮
POST {prefix}/agent/:id/suggest          「猜你想问」（条目 :suggest? + :agent-spec）
POST {prefix}/agent/:id/stop/:threadId   按停
POST {prefix}                            单路由退路：{"method": …, "params": {…}, "body": …}
GET/PATCH/POST/DELETE {prefix}/threads…  线程存储那七条（给了 :thread-store 才有）
```

CORS 缺省**拒**（带 Origin 的请求 403；`:origin-allowed` 用 `ahttp/origin-allow` 列白名单）。host 选项、线程存储的六槽契约、
与 C# 版的差别见各 ns 文档。

### 自带 run 驱动的 agent

不需要为它塞占位 runtime。注册表里只报名录，`routes` 的 host 选项按 agent 注入处理器；`/run` 与 `/stop` 可以是任意
Ring 处理器（Origin / CORS / 访问守卫仍在外层），单路由退路也走同一份注入。`/connect` 可交给外部源：它收到
`(thread-id after-seq deliver)`，`deliver` 接 AG-UI 事件 map 或已编好的 SSE 字节；事件先进帧环再送本连接，终态自动收流。

```clojure
(def reg (registry/registry [{:id "jinshu" :external? true :description "自带 ChatEngine"}]))
(def h (host/host reg {:agents {"jinshu"
                                {:run my-run-handler
                                 :stop my-stop-handler
                                 :connect-source (fn [thread-id after-seq deliver] ...my unsubscribe...)}}}))
```

外部条目缺省 `/info` 不继承内置 runtime 的能力位；它真实支持的能力由条目 `:capabilities` 显式宣告。

## MCP 端点

`(mcp-http/handler server opts)` / `(mcp-http/routes path server opts)`：`POST` 一条 JSON-RPC → JSON 响应（通知 202、校验类
错误 400）；`GET` / 其他 405；`OPTIONS` 预检；**Origin 缺省拒**（规范要求防 DNS rebinding）；请求体上限 `:max-body-bytes`。
协议本身在 clj-agent-mcp（`handle-message` / `check-headers`）。

## 命名空间

| ns | 内容 |
|---|---|
| `im.ttalk.agent.server.routing` | 路由表 ⇒ Ring 处理器（`router`）：按具体度选路、路径中方法不中 ⇒ 405 + Allow |
| `im.ttalk.agent.server.guard` | 本机访问守卫中间件：token（头 / Bearer / query）+ Host 只认回环（挡 DNS rebinding） |
| `im.ttalk.agent.server.http` | Ring 请求 / 响应小工具、读请求体与上限、`wrap-errors` / `wrap-body-limit` |
| `im.ttalk.agent.server.sse` | SSE 响应（SPEC 流式正文）：写入串行化、心跳、`gone?` / `aborted`、停机登记 |
| `im.ttalk.agent.server.strings` | 文案与错误（`msg` / `error` / `code`） |
| `im.ttalk.agent.server.agui` | AG-UI 路由（`routes` / `handler`） |
| `im.ttalk.agent.server.agui.{host,http,input,replay,resume,thread-store}` | 运行期（泵、帧环、心跳）/ CORS 与错误出口 / 输入校验 / `/connect` 帧环 / 续跑判定 / 线程存储六槽 |
| `im.ttalk.agent.server.mcp` | MCP Streamable HTTP 端点 |
| `im.ttalk.agent.server.adapter.{http-kit,undertow}` | 不实现 SPEC 流式正文的 adapter 的垫片 |
