# clj-http：把 HTTP 传输收成一个模块

> 改名：2026-11-10 起模块是 `modules/clj-http`（坐标 `im.ttalk/clj-http`，ns 前缀 `im.ttalk.http`），定位为与 agent 无关的通用 HTTP 客户端；下文按新名写。
> 状态：✅ 已实施（2026-11-09，H-01～H-06；施工与设计的差异见 §9）
> 出处：本仓库 `provider/http/{client,stream_client,retry}.clj`、`mcp/transport.clj`、`tools/web.clj` 的现状；
> 移植来源 Keelson `jvm/modules/clj-net`（`im.ttalk.net.*`，Keelson.Net 的 JVM 版）；API 形状参考 hato / http-kit。
> 任务：[H-01](../tasks/H-01.md) … [H-06](../tasks/H-06.md)。

## 0. 一句话

新增**零依赖**模块 `clj-http`（ns `im.ttalk.http.*`，只依赖 `org.clojure/clojure`），把现在散在
provider / mcp / tools 三个模块里的 4 套 `java.net.http` 用法收成一份：一个 client 构造器、一个请求入口
（同步 + `CompletableFuture` 异步）、一个按行真流式入口（带首字节 / 空闲 watchdog）、一组头工具与 SSE 分帧器。
头工具、网络错分类、HTTP 日期 / Retry-After 解析、SSE 分帧、环境变量代理**从 Keelson clj-net 移植**（去掉 clj-core 依赖）。
canonical error、业务码表、模型名核对、重试判定这些 **provider 语义留在 provider**。

## 1. 现状与问题

### 1.1 四套 HttpClient

| 位置 | 构造 | 行为 |
|---|---|---|
| `provider/http/client.clj` `default-client` | `delay`，connect 30s，**HTTP/1.1**，虚拟线程 executor | 非流式同步 / 异步 |
| `provider/http/stream_client.clj` | 复用上面那个 | `fromLineSubscriber` 真流式 + watchdog |
| `mcp/transport.clj` `http-transport` | **每个传输一个**，connect = timeout-ms，JDK 缺省（先试 HTTP/2，公共池 executor） | 同步 `ofString`，整体读完再 `parse-sse` |
| `tools/web.clj` `client-of` | 每个工作区一个，`Redirect/NEVER`，connect = timeout-ms | 同步 `ofInputStream`，手动跟随重定向 |

### 1.2 重复与已经付过的学费

- **请求构造写了三遍**（`client/build-request`、`stream_client/build-request`、`HttpTransport.send!`），
  **`headers->map` 两遍**、**`has-content-type?` 两遍**、**按名取头三遍**（`retry/header-get`、`rate_limit/header-get`、
  MCP 里的 `.firstValue`）。
- 头值形状不一致已经出过真 bug：`http-headers->map` 存 `List<String>`，`retry/header-get` 当串用，
  **Retry-After 在真实请求上从未生效**（provider-reliability.md §1.3）。修法是在每个取头的地方各打一遍补丁，根因还在。
- 缺 `Content-Type` 的坑踩过一次（MiniMax 报「缺 messages」，stream_client 注释），修在 stream_client，
  MCP 那份是自己记得加的——下一个新调用方未必记得。
- LiteGate 在 HTTP/2 握手阶段断连的坑只在 provider 那份 client 上修了（固定 HTTP/1.1）；MCP 的 client 仍走 JDK 缺省。
- `client/body-handler` 的 `:stream` 实际是 `ofString`——名不副实的潜伏问题（目前无调用方传 `:stream`）。
- 没有任何一份读 `HTTPS_PROXY` / `NO_PROXY`：JDK HttpClient 缺省不读环境变量代理，要走代理的部署只能自己造 client。

### 1.3 Keelson clj-net 能给什么、不能给什么

`clj-net` 分两层：与客户端无关的纯逻辑核心，以及 JDK 接线（处理器 = `(fn [req] resp)` 的中间件形）。

| clj-net | 取舍 | 理由 |
|---|---|---|
| `net.http` 头工具（`header` / `header-values` / `assoc-header` / `dissoc-header` / `header-seq`）、`media-type`、charset 解析 | ✅ 移植 | 大小写不敏感、单值 / 多值都收——正好根治 §1.2 的头值形状问题 |
| `net.failure` 网络错分类（`classify` / `socket-kind`） | ✅ 移植**分类**，不搬重试判定 | 给 canonical error 补 `:code`（`:name-resolution-error` / `:connection-error` / `:secure-connection-error` …）。**可重试判定仍用 clj-agent 现有口径**：clj-net 的 `transient?` 不把超时算 transient，我们的 `error.clj:132` 把 `HttpTimeoutException` 算 `:transient`——照搬就是静默改缺省行为 |
| `net.retry` 的 `parse-http-date`（RFC 1123 / RFC 850 / asctime）、`retry-after-ms`（含 `retry-after-ms` 头） | ✅ 移植**解析** | 我们只认 RFC 1123。封顶语义**不搬**：clj-net 是「≥ 上限就忽略」，我们是「取 min(值, :max-delay)」，保持现状 |
| `net.retry` 的会话（`session` / `on-response` / `on-failure` / 总截止） | ❌ | 与 `core/retry.clj`（ChatModel 层，canonical error）和 `provider/http/retry.clj` 职责重叠；第三套重试机器没有调用方 |
| `net.sse` 分帧核（`parser` / `feed!` / `take-event!`，WHATWG 9.2.6） | ✅ 移植 | MCP 的 `parse-sse` 是「取第一条 `data:`」的简化版（多行 data、`\r` 行尾都不对）；分帧核给它用 |
| `net.sse` 读者 / 断线续读循环（`connect` / `read-stream`） | ❌ | 阻塞读者；续读没有调用方（MCP 明确不做 `subscriptions/listen`，见 `transport.clj` `parse-sse` 注释） |
| `net.proxy`（`from-environment` / `web-proxy` / `->proxy-selector`） | ✅ 移植，**opt-in** | 能力缺口（§1.2 末条）。用户拍板：做成选项，缺省不变（§6） |
| `net.http` 处理器 / 中间件形、`http.jdk` 终端、`http.retry` `wrap-retry` | ❌ | **只有阻塞模型**（取消 = 中断线程）；clj-agent 的异步路径建在 `CompletableFuture` 上（`request-deferred`、`post-stream-deferred`、`fromLineSubscriber`）。套一层会把真流式退化回阻塞读 |
| `http.http1` 自带终端、`connect-ip` 钉地址（SSRF）、cookies | ❌ | 无调用方。web_fetch 的网络策略是 `:allow-url`（按 URI），DNS rebinding 那条缝要钉地址 + 自带终端才堵得上——体量大，等有真实需求另立项 |

**为什么移植而不依赖**（用户拍板 2026-11-09）：`clj-net` 依赖 `clj-core`（错误走 `im.ttalk.core.error` + i18n 文案键
`:keelson/code`），而 clj-agent 的错误是 canonical error；直接依赖要么多一次错误模型转换，要么让 clj-agent 的发布跟着
Keelson 走。移植的都是纯函数、体量小（预计 < 600 行），两边各自演进的代价可接受。移植文件在 ns docstring 里注明出处
（Keelson 文件路径 + 提交号），与 clj-agent-tools 移植 Keelson C# 的做法一致。

## 2. 立项判据（design-principles §1）

这件事有两部分，适用的判据不同：

**① 收拢已有的东西**（client 构造、请求构造、头转换、真流式 + watchdog 从 provider 挪到新模块）——按 §1.5
「本原则管该不该有这个东西，不管这个东西该叫什么、放哪」，不过四问。换来的是**不变量**：
「clj-agent 只有一处把 Clojure map 变成 `HttpRequest`」，Content-Type 缺省、HTTP/1.1、头值形状由它保证，不靠每个调用方记得。

**② 新长出来的 API 面**——逐条过四问：

| 新东西 | 现在谁用 | 不建怎么办 | 判定 |
|---|---|---|---|
| 头工具 `header` / `header-values` | retry、rate_limit、MCP、web（4 处） | 每处各写取头，形状问题已经出过 bug | ✅ |
| SSE 分帧核 | MCP `HttpTransport` | 手写的简化解析在多行 data 上是错的 | ✅ |
| 网络错分类 | provider（`errors/exception->error`）、web（错误文案） | 错误只剩一句 `.getMessage`，DNS 失败与连接拒绝分不开 | ✅ |
| HTTP 日期三格式 | provider retry | 自己写日期解析，几十行且容易错 | ✅ |
| 环境变量代理（opt-in） | 走代理部署的 provider / MCP / web 宿主 | 宿主自己造 `HttpClient` 塞进 `:client`——provider 的非流式路径**没有** `:client` 入口，做不到 | ✅ |
| hato 式 middleware 体系（`wrap-json` / `wrap-query-params` …） | 无 | 调用方一行 `json/generate-string` | ❌ 否决 |
| `:transport` 注入 seam | 无（provider 测试用 `with-redefs`，MCP 在协议层已经是「传输就是函数」） | 现状已够 | ❌ 否决 |
| 流式 `:on-event`（按 SSE 事件回调） | 无（provider 的流处理器吃行，MCP 不做长流） | — | ❌ 不做，分帧核足够 |
| 断线续读、cookies、钉地址 | 无 | — | ❌（§1.3） |

## 3. 模块边界

```
clj-http   （只依赖 clojure；无 cheshire、无 timbre、无 core）
   ▲        ▲          ▲
provider   mcp       tools
```

- **不依赖 core**：deferred 就是 `CompletableFuture`（`core/async.clj` 的约定），http 模块直接返回它，不需要 `flt/fmap`。
  取消令牌（`streaming/*register-cancel*`）的桥接留在 provider。
- **不依赖 cheshire**：http 模块只收 / 回字符串、字节、`InputStream`。JSON 编解码在调用方（provider 的门面 ns 里）。
- **不打日志**：传输层失败一律以异常 / 完成态交还调用方，日志由调用方决定。
- design-principles §2（框架无关）不受影响：这是出站客户端，不是 web 框架，且只用 JDK。

## 4. API

### 4.1 client

```clojure
(require '[im.ttalk.http :as http])

(http/client {:connect-timeout-ms 30000     ;; 缺省 30000
              :version :http-1.1            ;; 缺省 :http-1.1（LiteGate 教训）；:http-2 可选
              :executor :virtual            ;; 缺省 :virtual；也收 java.util.concurrent.Executor
              :redirect :never              ;; :never（缺省，同 JDK 缺省）| :normal | :always
              :proxy nil})                  ;; nil（缺省，不走代理）| :env | "http://h:p" | ProxySelector
;; => java.net.http.HttpClient
(http/default-client)                       ;; 进程共享的缺省实例（delay），即现在 provider 那个
```

**client 就是 `java.net.http.HttpClient` 本身**，不包一层 record：现有的 `:client` 选项（stream_client、web_fetch）收的就是它，
宿主手里已有的 `HttpClient` 不用改。

### 4.2 请求

```clojure
(http/request {:method :post                ;; :get :post :put :patch :delete :head
               :url "https://…"             ;; 串或 URI
               :query-params {:q "x"}       ;; 可选
               :headers {"Authorization" "…"}
               :body "…"                    ;; 串 | 字节 | nil
               :timeout-ms 30000            ;; 总时长（java.net.http 语义）；不给 ⇒ 不设
               :as :string                  ;; :string（缺省）| :bytes | :input-stream | :discard
               :client c})                  ;; 缺省 (default-client)
;; => {:status 200 :headers {"content-type" ["application/json"]} :body "…" :url "…"}

(http/request-async req)                    ;; => CompletableFuture<同形状响应 map>
```

- **响应头形状**：小写头名 ⇒ **串向量**（clj-net 同款）。取值一律走 `http/header` / `http/header-values`，
  不再有「这里是 List、那里是串」。现有 `rate_limit` / `retry` 的 `header-get` 收敛到它。
- **Content-Type**：有 body 且调用方没给 Content-Type ⇒ 补 `application/json`（现有两份 build-request 的行为，收成一处）。
- **受限头**（Host / Connection / Content-Length / Expect / Upgrade …）静默丢掉（clj-net `http.jdk` 的做法；JDK 会直接抛）。
- **失败**：同步版**原样抛**（`IOException` 一族、`HttpTimeoutException`、`IllegalArgumentException`），异步版异常完成
  （含请求构造失败——不同步抛）。
  「IO 失败 ⇒ `{:status 0 :error …}`」是 provider 的约定，由 provider 门面保留（§5），不进 http 模块。
- `:as :input-stream` 的调用方负责关正文（web_fetch 现状如此）。

### 4.3 按行真流式

```clojure
(http/stream-lines {:method :post :url … :headers … :body …
                    :timeout-ms 300000
                    :first-byte-ms 30000            ;; 可选：发出请求到第一行
                    :idle-ms 30000                  ;; 可选：相邻两行之间（SSE 注释心跳也算活动）
                    :on-response (fn [status headers] …)   ;; 响应头到达
                    :on-line (fn [line] …)                 ;; 仅 2xx：每行一次，单条流内串行
                    :client c})
;; => {:future CompletableFuture<{:status :headers}>   ;; 非 2xx 时带 :body（错误体字符串），不调 :on-line
;;     :cancel (fn [])}
```

- 这就是现在 `post-stream-async` 的**传输部分**原样搬下来：`fromLineSubscriber`、2xx / 非 2xx 分流、共享 watchdog 线程
  （「到点检查 → 未超时按剩余时间重排」，零空转）、`cancel` 同时取消 subscription 与 future。
- watchdog 超时 ⇒ 取消上游，future 以 `(ex-info … {::http/timeout :first-byte | :idle, :limit-ms n})` 异常完成。
  映射成 canonical `:timeout-error`（`:first-byte-timeout` / `:idle-timeout`）是 provider 的事。
- **终止只发生一次**（完成 / 非 2xx / 超时 / 取消 / IO 错竞争时先到者为准）——现有 `done?` CAS 的语义，一起搬下来。
- `:on-line` 里可以调 `cancel`（provider 的模型名核对就是这么中止的）。

### 4.4 头 / SSE / 代理 / 错误分类（移植自 clj-net）

| ns | 函数 | 来源 |
|---|---|---|
| `im.ttalk.http`（门面） | `header` `header-values` `assoc-header` `dissoc-header` `media-type` `charset` | `net.http` |
| `im.ttalk.http.sse` | `parser` `feed!` `take-event!` `parse-all`（字节 / 串 ⇒ 事件向量，MCP 用） | `net.sse` 分帧核 |
| `im.ttalk.http.proxy` | `from-environment` `web-proxy` `->proxy-selector` | `net.proxy` |
| `im.ttalk.http.failure` | `classify`（⇒ `:name-resolution-error` / `:connection-error` / `:secure-connection-error` / `:unknown`） | `net.failure` |
| `im.ttalk.http.date` | `parse-http-date` `retry-after-ms` | `net.retry` |

移植时的改动：`im.ttalk.core.error/error` ⇒ `ex-info`；文案键 `msg` ⇒ 直接写中文消息；`:keelson.net/*` 键 ⇒ `::http/*`；
其余逻辑与测试逐条照搬（测试是验收的一部分，见 H-02）。

## 5. 各调用方迁移后长什么样

- **provider**：`provider.http.client` 变成**门面**——公开函数（`get` / `post` / `request` / `request-deferred` /
  `post-deferred` / `post-json` / `get-json` / `build-url` / `response->error` / `success-body` / `check-model!` …）
  签名与返回形状**逐字不变**，内部改调 `http/request(-async)`，JSON 编解码与 `{:status 0 :error}` 约定留在这里。
  已发布到 0.4.0 的 `put` / `patch` / `delete` / `*-async` 回调版保留（无内部调用方，但它们是公开 API）。
  `stream_client` 的 `post-stream-async` 改建在 `http/stream-lines` 上，只剩 parse-fn / process-fn、canonical error、
  模型名核对、限流头；`post-stream-sync` / `-deferred` 不动。`retry.clj` 的 `header-get` 与 `parse-retry-after`
  改调 `http/header` 与 `http.date`。provider 测试里对 `http/get`、`http/post` 的 `with-redefs` 因门面保留而不受影响。
  网络错的细分 `:code` 在 provider 的 `stream-client/io-error` 里补（`errors/exception->error` 在 core，core 零依赖，
  不能反过来依赖 http 模块）。
- **mcp**：`HttpTransport` 改用 `http/request`，`parse-sse` 换成 `http.sse/parse-all` 后取第一条能解析的 JSON。
  `http-transport` 新增 `:client` / `:proxy` 选项。
- **tools**：`web.clj` 的 `client-of` 改成 `(http/client {:redirect :never :connect-timeout-ms … :proxy (:proxy o)})`，
  `:client` 选项照收 `HttpClient`；请求走 `http/request {:as :input-stream}`；IO 错误文案用 `http.failure/classify` 细分。
  手动跟随重定向、`:allow-url`、按实际字节限量读都不动。

## 6. 缺省行为变化

| 变化 | 影响 | 处理 |
|---|---|---|
| MCP HTTP 传输改为 HTTP/1.1 + 虚拟线程 executor | 不再先试 HTTP/2；对 LiteGate 一类网关是修复 | 写进 CHANGELOG；要 HTTP/2 传 `:client` |
| MCP HTTP 传输改用共享缺省 client（不再每传输一个连接池） | 连接复用；`timeout-ms` 仍是单次请求上限，但 connect 超时变成缺省 30s | 写进 CHANGELOG；要原行为传 `:client` |
| MCP SSE 响应改用 WHATWG 分帧 | 多行 `data:` 现在会正确拼接（原来只取第一行） | 视为修 bug |
| 环境变量代理 | **无变化**：缺省不读，`:proxy :env` 才读（用户拍板 2026-11-09） | — |
| provider 可重试判定 / 封顶语义 | **无变化**（§1.3） | — |

## 7. 不做的（否决记录）

- **换用 hato / 依赖 hato**：hato 本身就是 `java.net.http` 的封装，能力无增量；多一个传递依赖；它的 middleware 改写异常 /
  状态码语义，现有 retry / rate_limit / error 分类要重新适配。只借它的 API 形状（Ring 式请求 / 响应 map、client 即值）。
- **依赖 Keelson clj-net**：见 §1.3 末段。
- **middleware 体系、`:transport` seam、`:on-event` 流式、断线续读、cookies、钉地址**：见 §2 表，无调用方。
  重启时须重新设计（design-principles §1.3）。

## 8. 待核实项的结论（2026-11-09）

- **`core/retry.clj` 与 `provider/http/retry.clj`**：两份 `compute-backoff` 公式与缺省值逐字相同 ⇒ provider 版改为转调
  core 版（签名不变）。其余**不合并**：core 的 `run` / `run-async` 判 canonical error 的 `:retryable?`（ChatModel 层，
  一次逻辑调用）；provider 的 `with-retry` / `maybe-with-retry` 判 HTTP 响应 map 的状态码（HTTP 层，embeddings 与流式
  建链在用）——输入形状不同，不是重复。
- **mcp 不在 `bb.edn` / CI matrix / `build.clj` 里**：不是有意的。它的测试只经根 `tests.edn` 跑；更要紧的是 `build.clj`
  模块表里没有它，而 agui 依赖它 ⇒ agui 发布出去的 pom **缺 mcp 这条依赖**（消费方解析即断，与 build.clj 注释里记的
  `:override-libs` 那个坑同款）。H-01 一并补上：三处都加 mcp，agui 的 `:override-libs` 加 mcp。

## 9. 施工与设计的差异

1. **`:redirect` 缺省 `:never` 而不是 `:normal`**：现有 4 份 client 全是 JDK 缺省（不跟随）；缺省改成跟随就是静默改行为。
2. **`IllegalArgumentException` 不包成 ex-info**：原样抛，与 provider 现状一致（它也不包）。
3. **clj-net `timeout?` 的一处 bug，移植时修了**：沿父类链用 `some` 找类名，建连超时那一格回 `false`，`some` 把它当
   「没找到」继续走到父类 `HttpTimeoutException` ⇒ true——建连超时被误判成请求超时。改成取链上**第一个**认得的类名。
   Keelson 原版（`jvm/modules/clj-net/src/im/ttalk/net/failure.clj`）仍有此 bug，待那边修。
4. **顺带修的既有缺陷**（迁移时测出来的）：
   - JDK 的 `ConnectException` 常没有 message，`provider.http.client` 回 `{:status 0 :error nil}`，`response->error`
     靠 `(:error response)` 判连接失败、于是认不出，误当 HTTP 0 ⇒ `:error` 用异常类名兜底；
   - `provider.http.client/request-id` 回的是 java.net.http 的 List ⇒ 改回字符串。
5. **MCP 的 SSE 解析末尾补一个空行**：服务端写完最后一个事件就关连接、没写收尾空行时，WHATWG 分帧会丢掉那半个事件；
   原实现（逐行找 `data:`）不丢。补空行保住这一格，多余的空行在规范里是无害的空派发。
6. **`scripts/check_docs.clj` 不再把 `*-test` 当 API ns 检查**：mcp README 收进门禁后，它的跑法说明里点名了
   `im.ttalk.agent.mcp.server-test`（测试 ns 不在 src 里）⇒ 误报。按门禁「宁可漏报，不可误报」的取舍放行。

