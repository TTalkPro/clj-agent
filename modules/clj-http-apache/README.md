# clj-http-apache

**可选 Apache HttpClient 5 transport**（Maven 坐标 `im.ttalk/clj-http-apache`，ns
`im.ttalk.http.apache`）。它实现 `im.ttalk.http.transport` 的同一张契约：普通 request map 进，
普通 response map 出；底层主路径是 HC5 async client，SSE 在 response consumer 每个 chunk 解码后按行回调，
不先聚合完整正文。Apache / reactor 类型不穿过插件边界。

Apache 依赖只属于本模块。引入它不影响 `clj-http`、provider、core 的缺省依赖。

## 用法

```clojure
(require '[im.ttalk.http.transport :as transport]
         '[im.ttalk.http.apache :as apache])

(def t (apache/transport {:connect-timeout-ms 10000
                          :request-timeout-ms 120000
                          :pool {:max-total 64 :max-per-route 8}
                          :redirect :never}))

(transport/assert-transport! t)
(transport/request t {:method :get
                      :url "https://api.example.com/v1/models"
                      :timeout-ms 30000
                      :as :string})
@(transport/request-async t {:method :get :url "https://api.example.com/v1/models"})

;; 按行真流式（SSE 每行不含行尾；空行就是 ""）
(let [{:keys [future cancel]}
      (transport/stream-lines t {:method :post
                                 :url "https://api.example.com/v1/chat"
                                 :body "{\"model\":\"m\"}"
                                 :first-byte-ms 30000
                                 :idle-ms 30000
                                 :on-response (fn [status headers] nil)
                                 :on-line (fn [line] nil)})]
  @future)

;; 宿主显式创建的实例由宿主关闭；close 可重复调用
(transport/close t)
```

## 选项

| 选项 | 含义 | 缺省 |
|---|---|---|
| `:connect-timeout-ms` | 建立连接上限 | 30000 |
| `:request-timeout-ms` | client 级 response timeout；单次请求仍可用 request map 的 `:timeout-ms` | 不启用 |
| `:pool` | `{:max-total n :max-per-route n}`，只给出现的键设值 | HC5 pool 缺省 |
| `:redirect` | `:never` / `:normal` / `:always` / boolean | `:never` |
| `:proxy` | Apache `HttpHost`，或可被 `HttpHost/create` 解析的值 | 直连 |

transport capabilities 是 `#{:string :bytes :discard :stream-lines}`；本 adapter 不提供 `:input-stream`。
请求 / 响应头先由 `im.ttalk.http.transport` 归一成小写 key + string vector。

## 错误与生命周期

- 底层异常折成 transport error：DNS ⇒ `:name-resolution-error`，connect/reset ⇒ `:connection-error`，
  TLS ⇒ `:secure-connection-error`，超时 ⇒ `:timeout-error`，取消 ⇒ `:cancelled-error`，其他 IO ⇒ `:unknown-io`，
  请求构造 / 配置错误 ⇒ `:invalid-request`。原始异常留在 `::transport/cause`。
- 流式 `:first-byte-ms` / `:idle-ms` 超时取消 HC5 future；异常完成 future 的 ex-data 带
  `{:phase :first-byte | :idle :limit-ms n}`。完成、失败、超时、取消只发生一次。
- `:cancel` 会取消底层 response future 与契约 future；非 2xx 不调 `:on-line`，future 正常完成并带错误体。
- Apache 回调线程由 HC5 async I/O 层决定。`:on-line` / `:on-response` 不应阻塞或执行长 CPU 工作；
  provider 只做 SSE 行解析与状态累积，最终归一化仍在 deferred 语义里完成。
- provider 不拥有、也不自动关闭注入的 transport。进程长生命周期实例在宿主关闭时调一次
  `transport/close`；缺省 provider 路径仍使用 `clj-http` 的 JDK transport。

## 与 provider 的关系

把本 transport 放进 provider 配置即可覆盖非流式与流式路径；`:http-transport` 是唯一跨实现插件边界：

```clojure
(require '[im.ttalk.agent.provider.factory.builder :as factory]
         '[im.ttalk.http.apache :as apache])

(factory/create-provider :openai
  {:api-key "sk-…"
   :http-transport (apache/transport {:connect-timeout-ms 10000})})
```

旧 provider `:client` 不升级为任意 HTTP client 类型；迁移期它仍只接受 JDK `HttpClient`。
