# Provider HTTP transport：Ring 风格的可插拔 client transport

> 状态：✅ 已实施（2026-11-18）
> 任务：[`tasks/H-07.md`](../tasks/H-07.md) … [`tasks/H-12.md`](../tasks/H-12.md)。
> 出处：provider 切换 Apache HttpClient / Netty 的需求；现状与历史取舍见
> [`http-module.md`](http-module.md)。
> 先读：[`docs/design-principles.md`](../docs/design-principles.md)、
> [`docs/async-chat-model-design.md`](../docs/async-chat-model-design.md)、
> [`designs/provider-reliability.md`](provider-reliability.md)。

## 0. 一句话

新增一个**Ring 风格的出站 HTTP transport 契约**：provider 只面向普通 request map /
response map / stream handle；JDK `java.net.http`、Apache HttpClient、Netty 都只是这个
契约的 adapter。provider 代码不再认识 `java.net.http.HttpClient`，也不再通过
`:client` 猜“传进来的到底是什么 HTTP client”。

这不是把 Ring SPEC 直接搬到客户端。Ring 面向服务端，没有出站请求的超时、代理、
重定向、取消、SSE 首字节 / 空闲超时等语义；这里借用的是 Ring 的**数据边界和函数
边界**：普通 map 进，普通 map 出，具体 HTTP 实现留在 adapter。

## 1. 动机与现状

### 1.1 现状

`clj-http` 已经把请求 / 响应收成 Ring 式 map，但底层实现硬绑定 JDK：

| 路径 | 现状 |
|---|---|
| `im.ttalk.http/request` | 接收 map，但 `:client` 必须是 `java.net.http.HttpClient` |
| `im.ttalk.http/request-async` | JDK `sendAsync`，返回 `CompletableFuture<response map>` |
| `im.ttalk.http/stream-lines` | JDK `BodyHandlers/fromLineSubscriber` + watchdog |
| provider 非流式 | `provider.http.client/->http-request` 固定传 `@default-client`，调用方 `:client` 被忽略 |
| provider 流式 | `post-stream-async` 可传 `:client`，但仍必须是 JDK `HttpClient` |

因此现在只能换“另一个 JDK client 实例”，不能换“另一种 HTTP 实现”。

### 1.2 为什么现在重启 `:transport` seam

`http-module.md` §2 曾否决 `:transport` seam，理由是当时没有真实调用方，且 Keelson
`http.jdk` 只有阻塞模型，套一层会把异步路径退化。这次重启的前提不同：

1. 有真实需求：宿主要求 provider 可切换 Apache HttpClient / Netty；
2. 契约包含三条原语：同步、`CompletableFuture` 异步、按行真流式，不把异步退化回阻塞；
3. seam 的换来的不是“更像 Ring”，而是能力：换连接池、TLS / 代理策略、HTTP 实现、
   企业网关兼容性，以及 provider 测试可注入 fake transport；
4. Apache / Netty adapter 单独发模块，不把外部 HTTP client 依赖塞进 `clj-http` /
   provider / core。

## 2. 模块边界

```text
clj-agent-provider
        │  只依赖 transport 契约
        ▼
modules/clj-http/src/im/ttalk/http/transport.clj     ; 契约、错误、头归一化
        ▲                        ▲                  ▲
        │                        │                  │
clj-http                    clj-http-apache     clj-http-netty
(JDK 缺省 adapter)          (可选)              (可选)
```

- `clj-http` 继续零第三方依赖；JDK adapter 仍放在它里面，作为缺省实现。
- Apache / Netty 各自是可选模块，只有宿主显式引入才进 classpath。
- provider 不 import Apache / Netty / JDK HTTP 类型，也不帮宿主关闭 transport。
- MCP、tools 的 HTTP 路径不在本轮范围内；它们可以继续走现有 JDK 实现。

## 3. Transport 契约

### 3.1 形状

transport 是普通 map，不是接口、不是 protocol、不是动态变量：

```clojure
{:name          :jdk                  ; 仅用于日志 / 诊断
 :capabilities  #{:string :bytes :discard :stream-lines}
 :request       (fn [req] resp)
 :request-async (fn [req] ^CompletableFuture resp)
 :stream-lines  (fn [req] {:future ^CompletableFuture ...
                           :cancel (fn [])})
 :close         (fn [])}              ; 可选；宿主负责调用
```

选 map 而不是 protocol：

- 和 Ring 的“处理器就是函数 / 数据就是 map”一致；
- adapter 与 middleware 更容易组合；
- fake transport 不需要 record 或类型系统仪式；
- provider 只需要三个稳定入口。

契约 ns 提供 facade 与校验：

```clojure
(require '[im.ttalk.http.transport :as transport])

(transport/request t req)
(transport/request-async t req)
(transport/stream-lines t req)

(transport/transport? t)              ; 三个函数都在，capabilities 是 set
(transport/assert-transport! t)       ; 配置期尽早失败
(transport/close t)                   ; 有 :close 才调
```

`request` / `request-async` / `stream-lines` 发现缺函数时立即抛
`:invalid-request` 类错误，不允许运行到网络阶段才暴露配置错误。

### 3.2 Request map

provider 内部构造的 request map 统一为：

```clojure
{:method      :post                   ; 关键字或字符串
 :url         "https://api.example.com/v1/chat/completions"
 :query-params {:api-version "preview"} ; 可选
 :headers     {"authorization" ["Bearer ..."]
               "content-type"  ["application/json"]}
 :body        "{...}"                 ; v1 只要求 string / bytes / nil
 :timeout-ms  30000
 :as          :string}                ; :string | :bytes | :discard
```

约定：

1. **header 规范形状是小写 key + string vector**。调用方仍可给大写、关键字、
   单个 string；契约 ns 先归一化，adapter 收到的已经是规范形状。
2. **有 body 且没有 `content-type` 时补 `application/json`**，保持现行为。
3. **v1 不要求 `:as :input-stream`**。provider 非流式只消费 string / bytes；
   需要真正响应流的能力留给 JDK transport 或后续 capability 扩展。
4. `:timeout-ms` 是单次请求超时，语义与现在一致；连接超时、连接池、代理、
   TLS、重定向都归 transport 构造配置，不进单次 request。
5. request map 不携带 `:client`。transport 就是被注入的实现。

### 3.3 Response map

同步与异步的响应形状一致：

```clojure
{:status      200
 :headers     {"content-type" ["application/json"]
               "retry-after"  ["5"]}
 :body        "..."                   ; 按 :as 返回 string / bytes；:discard 为 nil
 :url         "https://api.example.com/v1/chat/completions"}
```

要求：

- 状态码是整数；
- 所有响应头名小写，值是 string vector，多值不能折叠成串；
- `:url` 是最终请求 URL（跟随后重定向时是最终 URL；adapter 不支持重定向时就是原 URL）；
- adapter 不解析 JSON，不做 provider 业务错误解释；
- adapter 不吞异常：失败抛出，或让 `CompletableFuture` 异常完成。

`provider.http.client/success-body`、`response->error`、`Retry-After` 解析、限流头
解析继续只依赖这个规范形状。

### 3.4 Stream contract

流式 request map 在普通 request map 上追加：

```clojure
{:first-byte-ms 30000
 :idle-ms       30000
 :on-response   (fn [status headers] ...)
 :on-line       (fn [line] ...)}
```

返回：

```clojure
{:future ^CompletableFuture
 :cancel (fn [])}
```

语义保持现 `im.ttalk.http/stream-lines` 不变：

| 情况 | 行为 |
|---|---|
| 2xx | `:on-response` 先触发；每行到达调一次 `:on-line`；正常结束 future 完成为 `{:status :headers}` |
| 非 2xx | 调 `:on-response`；**不调 `:on-line`**；读错误体后 future 完成为 `{:status :headers :body string}` |
| 首字节 / 空闲超时 | 取消上游；future 以 ex-info 异常完成，ex-data 带 `::transport/timeout` 与 `::transport/limit-ms` |
| IO / 连接失败 | future 以归一化 transport exception 异常完成 |
| `:cancel` | 停止上游读取；future 以 `CancellationException` 异常完成 |
| `:on-line` 抛异常 | 终止流，future 以该异常异常完成；之后不再回调 |
| 终止竞争 | 完成 / 失败 / 超时 / 取消只发生一次，后续回调不再触发 |

`line` 不含行尾换行；SSE 的空行就是 `""`。transport 不解析 SSE 事件，SSE 解析仍归
provider。

### 3.5 异步原语

异步仍以 `java.util.concurrent.CompletableFuture` 为边界，这是本仓库现有 deferred
约定，不新造 deferred 类型：

```clojure
(transport/request-async t req)
;; => CompletableFuture<response map>
```

要求：

- 失败不能返回 `nil`，也不能返回已带着 `{:status 0 ...}` 的成功 future；
- `cancel(true)` 必须尽量传播到底层连接；
- adapter 不规定回调线程，但必须在文档中写清楚；provider 的回调不阻塞 executor；
- Netty 等 event-loop 实现不得把 provider 的 CPU 密集工作串在 I/O 线程上时还宣称
  非阻塞；必要时 adapter 用独立回调 executor。

## 4. Transport error

不同 client 的异常类型不同，provider 不能认识 Apache / Netty 的异常类。adapter 必须
把底层异常归一化成统一 ex-info：

```clojure
(ex-info message
         {::transport/error kind
          ::transport/cause original-exception
          ::transport/data  {...}})
```

`kind` 的第一组取值：

| kind | 含义 | provider 归一化方向 |
|---|---|---|
| `:name-resolution-error` | DNS / host 解析失败 | 网络错误，可重试 |
| `:connection-error` | connect refused / reset / 断连 | 网络错误，可重试 |
| `:secure-connection-error` | TLS / 证书失败 | 安全连接错误 |
| `:timeout-error` | 请求 / 响应 / connect 超时 | 超时错误；流式另有 first-byte / idle 细分 |
| `:cancelled-error` | 调用方取消 | 取消 |
| `:unknown-io` | 其他 IO 型失败 | 网络错误 |
| `:invalid-request` | URL、header、body、配置不合法 | 不转成网络错误，尽早暴露编程错误 |

provider 非流式仍保持外部行为：IO 型失败转成：

```clojure
{:status 0 :error "..." :success? false}
```

然后进入现有 `response->error` 与 ChatModel 层重试语义。流式则进入现有
canonical error 路径。也就是说：换 adapter 不改变“这次失败是否可重试、是否切备用、
如何解析 Retry-After”的公共语义，除非底层 client 真的提供了不同事实。

## 5. Ring 与 middleware 的关系

这里只借 Ring 的边界，不引入 `ring-core` 依赖：

```text
provider request map -> transport -> provider response map
```

transport middleware 可以组合，但只做通用 HTTP 事：

```clojure
(def t
  (-> (http-apache/transport {:pool pool})
      (transport/wrap-default-headers {"user-agent" "clj-agent"})
      (transport/wrap-request (fn [req]
                                (log/debug "provider http request" (select-keys req [:method :url]))
                                req))))
```

第一组 helper 只包括：

- `wrap-default-headers`；
- `wrap-request`；
- `wrap-response-async` 或等价的 response 后处理 helper。

不做的 middleware：

- 重试：留在 provider / ChatModel，避免两层重试相乘，也让上层观测 attempts；
- JSON 编解码：仍由 provider 门面负责；
- canonical error：仍由 provider 负责；
- 限流头、业务码表、模型核对：transport 一概不认识。

## 6. Provider 接入

### 6.1 配置入口

provider 统一新键 `:http-transport`：

```clojure
(require '[im.ttalk.http :as http]
         '[im.ttalk.http.apache :as http-apache])

(llm/create-provider
  :openai
  {:api-key        "..."
   :model          "gpt-..."
   :http-transport (http-apache/transport {:max-requests 128})})
```

缺省仍是 JDK：

```clojure
(defonce ^:private default-transport
  (delay (http/jdk-transport {:connect-timeout-ms 30000
                              :version :http-1.1
                              :executor :virtual})))
```

`http/default-client` 与 `provider.http.client/default-client` 暂时保留，避免破坏
已发布 API；新代码不应再依赖它们，文档标 deprecated。

### 6.2 非流式路径

`provider.http.client/->http-request` 不再固定传 `:client`：

```clojure
(defn- ->http-request
  [method url {:keys [headers body query-params timeout as http-transport]}]
  {:method method
   :url url
   :query-params query-params
   :headers (merge *default-headers* headers)
   :body (when (some? body)
           (if (string? body) body (json/generate-string body)))
   :timeout-ms (or timeout *default-timeout*)
   :as (if (= as :byte-array) :bytes :string)})
```

`request` / `request-deferred` 解出 transport：

```clojure
(let [t (or http-transport @default-transport)]
  (transport/request t req))
```

现有公开函数签名不变；新增 opt-in `:http-transport`。这同时修复当前非流式
`:client` 被默默忽略的问题。

### 6.3 流式路径

`provider.http.stream-client/post-stream-async` 把 `:client` 换成规范入口：

```clojure
(let [t (or http-transport @default-transport)]
  (transport/stream-lines t
    {:method :post
     :url url
     :headers headers
     :body body
     :timeout-ms timeout
     :first-byte-ms first-byte-ms
     :idle-ms idle-ms
     :on-response on-response
     :on-line on-line}))
```

provider 的 SSE 解析、`make-initial-state`、`build-response`、模型核对、限流头、
取消登记、建链重试全部保持不变。

### 6.4 配置传递

`:http-transport` 是 provider 级配置，必须在创建 provider 时显式传入，并贯通四条
调用路径：

```text
非流式同步
非流式 deferred
流式同步
流式 deferred
```

需要覆盖的实现包括：

- OpenAI compatible；
- Anthropic；
- OpenAI Responses；
- DashScope；
- embeddings。

不允许 provider 内部引入动态全局变量来临时切 transport：它会在异步回调与子调用
边界处隐式穿透，和本仓库的显式边界原则冲突。测试也优先传 fake transport，而不是
redef 全局缺省值。

### 6.5 兼容规则

| 旧入口 | 新行为 |
|---|---|
| 无配置 | 使用进程共享 JDK transport，缺省行为不变 |
| `:http-transport` | 优先生效，可为任意 adapter |
| 流式旧 `:client` | 迁移期保留：只接受 `java.net.http.HttpClient`，包成 JDK transport |
| 非流式旧 `:client` | 迁移期新增接受：同样包成 JDK transport；若同时给 `:http-transport`，后者优先 |
| `im.ttalk.http/request` 旧 API | 保留，内部仍可直接走 JDK |

`:client` 不升级成“任意 HTTP client”。它是 JDK adapter 的配置细节，不是插件边界。

## 7. Adapter 要求

### 7.1 JDK adapter

JDK adapter 基本是把现有 `im.ttalk.http` 的三条路径包进 contract：

```clojure
(http/jdk-transport
  {:connect-timeout-ms 30000
   :version :http-1.1
   :executor :virtual
   :redirect :never
   :proxy nil})
```

缺省构造参数、HTTP/1.1、虚拟线程、不读环境代理等行为全部保持不变。

### 7.2 Apache adapter

可选模块坐标建议为 `im.ttalk/clj-http-apache`，ns 为 `im.ttalk.http.apache`：

```clojure
(http-apache/transport
  {:connect-timeout-ms 30000
   :request-timeout-ms nil
   :pool {:max-total 128 :max-per-route 32}
   :redirect :never})
```

实现要求：

- 优先使用 Apache HC5 async client，同步入口也不需要另建阻塞 client；
- 请求头多值、Content-Type、禁止受限头的行为与契约一致；
- 失败包装成 `::transport/error`，不把 Apache exception 直接漏给 provider；
- `CompletableFuture` 与 Apache future 双向桥接，取消要传播；
- SSE 用 async response consumer 增量吐行，不得整包收完再回调；
- first-byte / idle watchdog 必须达到 JDK 版现有测试精度；
- adapter 持有的 `Closeable` 资源只由宿主 `transport/close`。

### 7.3 Netty adapter

可选模块坐标建议为 `im.ttalk/clj-http-netty`，ns 为 `im.ttalk.http.netty`：

```clojure
(http-netty/transport
  {:event-loop-group eg
   :connect-timeout-ms 30000
   :idle-timeout-ms nil
   :redirect :never})
```

实现要求：

- event loop group / channel pool 的生命周期归 adapter，宿主显式关闭；
- response / chunk handler 在 Netty pipeline 内归一成 response map 或行回调；
- SSE 不做整包聚合，必须逐 chunk 分帧后回调；
- 不在 event-loop 线程里执行 provider 的阻塞逻辑；
- connect / request / first-byte / idle 超时都能取消 channel；
- cancel 后必须释放 ByteBuf 与 channel 引用，避免泄漏。

Netty 适配成本高于 Apache：它不是普通 request/response client，需要自行维护
channel 生命周期、backpressure、chunk 聚合与资源释放。因此落地顺序先 Apache，
Netty 在有真实吞吐或协议需求时再做。

## 8. 测试

### 8.1 契约测试

在 `clj-http` 提供一个可复用 transport contract test：

```clojure
(transport/check! jdk-transport)
(transport/check! apache-transport)
(transport/check! netty-transport)
```

覆盖：

- method / URL / query；
- header 大小写归一；
- 多值响应头；
- body string / bytes；
- `:as :string` / `:bytes` / `:discard`；
- 非 2xx 响应形状；
- 异步响应与同步响应形状一致；
- IO、DNS、TLS、超时错误包装；
- `cancel` 传播；
- stream `on-response` / `on-line` 顺序；
- stream 非 2xx 不回调 `on-line`；
- first-byte timeout；
- idle timeout；
- `on-line` 抛异常终止流；
- 终止只发生一次。

测试可用本地 HTTP server 控制响应头、chunk 间隔、断连和取消。

### 8.2 provider fake transport

provider 测试新增 fake：

```clojure
(def fake-transport
  {:name :fake
   :capabilities #{:string :stream-lines}
   :request (fn [_] {:status 200 :headers {} :body "{\"model\":\"m\"}" :url "..."})
   :request-async (fn [_] (CompletableFuture/completedFuture ...))
   :stream-lines (fn [_] ...)})
```

用它验证：

- 四条 provider 调用路径都真的使用注入 transport；
- `:http-transport` 优先于缺省；
- 旧 `:client` 的兼容行为；
- transport 错误到 canonical error 的映射；
- `Retry-After` / 限流头在新 header 形状下仍能解析；
- 流式取消、超时、非 2xx 错误体语义不变。

### 8.3 缺省回归

不做行为变化：

- 缺省仍是 JDK；
- HTTP/1.1；
- 虚拟线程 executor；
- connect timeout 30s；
- redirect `:never`；
- provider 非 2xx、重试、限流、模型核对语义不变；
- 现有公开 API 签名不变。

## 9. 实施顺序

1. 新增 `im.ttalk.http.transport` 契约与错误 helper；
2. 增加 JDK transport wrapper，现有 `im.ttalk.http` API 保留；
3. provider 非流式与流式接入 `:http-transport`，补 fake transport 测试；
4. 落 transport contract test；
5. 发布可选 `clj-http-apache`；
6. Apache 在真实 provider 场景验证后再评估 Netty；
7. 文档更新 provider README，标注 `:client` deprecated。

## 10. 不做的

- 不引入 ring-core 作为客户端依赖；
- 不把 Apache / Netty 依赖放进 `clj-http`、provider、core；
- 不提供全局动态变量覆盖；
- 不让 transport 做 JSON、重试、业务码、模型核对、限流解释；
- 不承诺所有 adapter 支持 `:as :input-stream`；
- 不做 server-side Ring adapter 复用；
- 不在没有真实 Netty 场景验证前先发 Netty adapter。

## 11. 与设计原则

按 `design-principles.md` §1 四问：

| 问题 | 回答 |
|---|---|
| 现在有人要用吗？ | 有：宿主要求 provider 可切换 Apache / Netty |
| 不建怎么办？ | provider 只能换 JDK client 实例；换 Apache / Netty 必须改 provider 内部 |
| 换来什么？ | 真实能力：HTTP 实现可替换、连接池策略可控、企业网关兼容、测试可注入 |
| 触发条件写得出吗？ | 写得出：`:http-transport` 贯通 provider 四条调用路径，adapter 通过同一 contract test |

按 §2 框架无关：Ring 只是设计参照，不是依赖；Apache / Netty 是可选 adapter 模块；
core 与 provider 不新增 web 框架或重型 HTTP client 依赖。

## 12. 施工差异

H-07～H-12 已按本文实施。施工时确认的差异：

1. `im.ttalk.http.transport` 除 facade、归一化与错误 helper 外，另提供最小 `wrap-default-headers` /
   `wrap-request` middleware helper；无通用 middleware 生态。
2. JDK adapter 保留现有 `:input-stream` capability，供旧 `im.ttalk.http` API 与 JDK 调用方使用；
   Apache adapter v1 只覆盖 `:string` / `:bytes` / `:discard` 与 `:stream-lines`。
3. provider 流式旧 `:client` 与非流式旧 `:client` 均只接受 JDK `HttpClient` 并包装成 JDK transport；
   `:http-transport` 优先。非流式原先固定用共享 JDK client、忽略 `:client`，本轮修复。
4. Apache adapter 白名单暴露建连 / response 超时、`{:max-total :max-per-route}` 池、`:redirect` 与 `:proxy`。
5. Apache 流式 watchdog 用 adapter 私有 scheduler，`transport/close` 立即关闭 HC5 async client；
   provider 不自动关闭注入的 transport。回调线程由 HC5 I/O 层决定，文档要求回调不阻塞。
6. Netty adapter v1 每条请求单独建连并带 `Connection: close`，不实现 channel pool 与 redirect；
   收到 `:pool` / `:redirect` 抛 `:invalid-request`。支持宿主注入 `EventLoopGroup`（adapter close 不关闭它）、
   自建 Nio event loop、建连 / 总请求 / 首字节 / 空闲 watchdog、取消关闭 channel 与复制后释放 `ByteBuf`。
   `on-response` / `on-line` 在 Netty event-loop 线程执行，README 要求不得阻塞。
