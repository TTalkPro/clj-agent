# clj-http

**通用 HTTP 客户端**（Maven 坐标 `im.ttalk/clj-http`，ns 前缀 `im.ttalk.http`）：JDK `java.net.http` 之上的
Ring 式请求 / 响应 map、同步与 `CompletableFuture` 异步、按行真流式、SSE 分帧 / 拼帧、环境变量代理。
**零依赖**（只有 `org.clojure/clojure`）——不依赖 clj-agent 的任何模块、不依赖 cheshire、不打日志；JSON 编解码、
错误模型、重试判定都是调用方的事。与 agent 无关，可单独使用；clj-agent 的 provider / mcp / tools / server 都建在它上面。

> 与社区的 [dakrone/clj-http](https://github.com/dakrone/clj-http) 无关（同名，坐标不同：`im.ttalk/clj-http`）。

设计与取舍：`designs/http-module.md`。

## 用法

```clojure
(require '[im.ttalk.http :as http])

;; 同步
(http/request {:method :post
               :url "https://api.example.com/v1/chat"
               :headers {"Authorization" "Bearer sk-…"}
               :body "{\"model\":\"m\"}"          ;; 串 / 字节 / nil；有正文没给 Content-Type ⇒ 补 application/json
               :timeout-ms 30000})                ;; 总时长；不给 ⇒ 不设
;; => {:status 200 :headers {"content-type" ["application/json"]} :body "…" :url "…"}

;; 异步：CompletableFuture<同形状>，失败异常完成
(http/request-async {:url "https://api.example.com/v1/models"})

;; 取头：大小写不敏感；值是串 / 串向量 / java.util.List 都收
(http/header resp "Retry-After")
(http/header-values resp "set-cookie")
(http/media-type resp)                            ;; => "application/json"
```

请求键：`:method`（缺省 `:get`）`:url` `:query-params` `:headers` `:body` `:timeout-ms`
`:as`（`:string` 缺省 / `:bytes` / `:input-stream` 调用方负责关 / `:discard`）`:client`。
失败**原样抛**（`IOException` 一族、`HttpTimeoutException`、`IllegalArgumentException`），异步版异常完成。

### client

```clojure
(http/client {:connect-timeout-ms 30000   ;; 缺省 30000
              :version :http-1.1          ;; 缺省 1.1（LiteGate 一类网关在 HTTP/2 握手阶段断连）；:http-2 可选
              :executor :virtual          ;; 缺省虚拟线程；也收 Executor
              :redirect :never            ;; 缺省不跟（同 JDK）；:normal / :always
              :proxy :env})               ;; 缺省直连；:env 读 http(s)_proxy / all_proxy / no_proxy
(http/default-client)                     ;; 进程共享的缺省实例
```

client **就是 `java.net.http.HttpClient` 本身**，各处的 `:client` 选项收的都是它。
`:proxy` 还收代理地址串、`im.ttalk.http.proxy` 的 map（`proxy/from-env` / `proxy/web-proxy`）、`ProxySelector`。

### 按行真流式

```clojure
(let [{:keys [future cancel]}
      (http/stream-lines {:method :post :url url :body body
                          :first-byte-ms 30000 :idle-ms 30000   ;; 可选 watchdog
                          :on-response (fn [status headers] …)
                          :on-line (fn [line] …)})]             ;; 仅 2xx；单条流内串行
  @future)
;; 2xx ⇒ {:status :headers}；非 2xx ⇒ {:status :headers :body 错误体}（不调 :on-line）
;; 超时 ⇒ 异常完成，(http/timeout-error? e) 回 :first-byte / :idle；cancel ⇒ 被取消
```

`BodyHandlers/fromLineSubscriber`：每行到达即回调，真增量、可取消；watchdog 是共享的单个 daemon 线程，
「到点检查 → 按剩余时间重排」，零空转。终止只发生一次（完成 / 超时 / 取消 / 失败先到者为准）。

## 命名空间

| ns | 内容 |
|---|---|
| `im.ttalk.http` | `client` `default-client` `request` `request-async` `stream-lines` `build-request`；头工具 `header` `header-values` `assoc-header` `dissoc-header` `header-seq` `media-type` `charset`；`success?` `timeout-error?`；URL `url-encode` `query-string` `with-query` |
| `im.ttalk.http.sse` | SSE 分帧核（WHATWG）：`parser` `feed!` `take-event!` `parse-all` |
| `im.ttalk.http.proxy` | 代理选择：`from-environment` `from-env` `web-proxy` `select` `->proxy-selector` |
| `im.ttalk.http.failure` | 网络错分类：`classify`（`:name-resolution-error` / `:connection-error` / `:secure-connection-error` / `:unknown`）`socket-kind` `timeout?` |
| `im.ttalk.http.date` | `parse-http-date`（三种格式）`retry-after-ms`（`retry-after-ms` 头 + `Retry-After` 秒 / 日期；不判上限） |

```clojure
(require '[im.ttalk.http.sse :as sse]
         '[im.ttalk.http.date :as date]
         '[im.ttalk.http.failure :as failure])

(sse/parse-all "event: chat\ndata: a\ndata: b\n\n")    ;; => [{:event-type "chat" :data "a\nb" :last-event-id "" …}]
(date/retry-after-ms {"Retry-After" "Fri, 09 Oct 2026 12:00:30 GMT"})
(failure/classify e)                                  ;; => :name-resolution-error
```

## 与 hato / Keelson clj-net 的关系

- **hato**：只借 API 形状（Ring 式请求 / 响应 map、client 即值）；不依赖它——它本身就是 `java.net.http` 的封装，能力无增量。
- **Keelson `jvm/modules/clj-net`**：`sse` / `proxy` / `failure` / `date` 与头工具**移植**自它（各 ns 文档注明出处），
  不依赖它（它依赖 clj-core，错误模型不同；且只有阻塞模型）。

## 不做的

middleware 体系、`:transport` 注入、按 SSE 事件回调的流式、断线续读、cookies、钉地址（SSRF）——都没有调用方
（design-principles §1），理由与重启条件见 `designs/http-module.md` §2 §7。
