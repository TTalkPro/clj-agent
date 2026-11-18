# clj-http-netty

**可选 Netty HTTP/1.1 transport**（Maven 坐标 `im.ttalk/clj-http-netty`，ns
`im.ttalk.http.netty`）。它实现 `im.ttalk.http.transport` 的同一张契约：普通 request map 进，
普通 response map 出；流式按 Netty 到达的 HTTP chunk 增量解码并按行回调。Netty 的
`ByteBuf`、channel、pipeline 不穿过插件边界。

v1 生命周期刻意简单：**每条请求单独建连，发送 `Connection: close`**。因此没有
channel pool，也不跟随 redirect；Netty 依赖只属于本模块，缺省 provider / `clj-http`
依赖不变。

## 用法

```clojure
(require '[im.ttalk.http.transport :as transport]
         '[im.ttalk.http.netty :as http-netty])

(def t (http-netty/transport {:io-threads 2
                              :connect-timeout-ms 10000
                              :request-timeout-ms 120000}))

(transport/assert-transport! t)
(transport/request t {:method :get
                      :url "https://api.example.com/v1/models"
                      :timeout-ms 30000
                      :as :string})
@(transport/request-async t {:method :get :url "https://api.example.com/v1/models"})

;; 按行真流式（行不含行尾；空行就是 ""）
(let [{:keys [future cancel]}
      (transport/stream-lines t {:method :post
                                 :url "https://api.example.com/v1/chat"
                                 :body "{\"model\":\"m\"}"
                                 :first-byte-ms 30000
                                 :idle-ms 30000
                                 :on-response (fn [status headers] nil)
                                 :on-line (fn [line] nil)})]
  ((:cancel)))

;; 宿主显式创建的实例由宿主关闭；close 可重复调用
(transport/close t)
```

## 选项

| 选项 | 含义 | 缺省 |
|---|---|---|
| `:event-loop-group` | 注入宿主已有的 Netty `EventLoopGroup`；adapter close 不会关闭它 | adapter 自建 |
| `:io-threads` | 自建 `NioEventLoopGroup` 的线程数 | 2 |
| `:connect-timeout-ms` | TCP 建连上限 | 30000 |
| `:request-timeout-ms` | adapter 级总请求上限；单次请求仍可用 request map 的 `:timeout-ms` | 不启用 |

流式请求继续支持 `:first-byte-ms` / `:idle-ms` watchdog。v1 收到 `:redirect` 或
`:pool` 会抛 `:invalid-request`；需要池或 redirect 时请用 Apache adapter。
capabilities 是 `#{:string :bytes :discard :stream-lines}`，不提供 `:input-stream`。

## 错误、取消与回调线程

- 底层异常折成 transport error：DNS ⇒ `:name-resolution-error`，connect/reset ⇒
  `:connection-error`，TLS（含未完成 TLS 握手）⇒ `:secure-connection-error`，超时 ⇒
  `:timeout-error`，取消 ⇒ `:cancelled-error`，其他 IO ⇒ `:unknown-io`，请求构造 /
  配置错误 ⇒ `:invalid-request`。原始异常留在 `::transport/cause`。
- 总请求超时 future 的 data 带 `{:phase :request :limit-ms n}`；流式 watchdog 带
  `{:phase :first-byte | :idle :limit-ms n}`。完成、失败、超时、取消只发生一次。
- `:cancel` 取消契约 future 并关闭当前 channel；`ByteBuf` 在读取时复制后立即释放。
- 非 2xx 不调 `:on-line`，future 正常完成并带错误体。
- **`:on-response` / `:on-line` 在 Netty event-loop 线程执行**。不要阻塞、长 CPU 工作
  或在这里等待 future；provider 只做 SSE 行解析与状态累积，最终归一化仍在 deferred
  语义里完成。
- provider 不拥有、也不自动关闭注入的 transport。宿主关闭时调一次 `transport/close`；
  注入的 event-loop-group 归宿主所有。

## 与 provider 的关系

`:http-transport` 是唯一跨实现插件边界：

```clojure
(require '[im.ttalk.agent.provider.factory.builder :as factory]
         '[im.ttalk.http.netty :as http-netty])

(factory/create-provider :openai
  {:api-key "sk-…"
   :http-transport (http-netty/transport {:connect-timeout-ms 10000})})
```

旧 provider `:client` 不升级为任意 HTTP client 类型；迁移期它仍只接受 JDK `HttpClient`。
