# 流式 Agent × Web Server 集成示例

> 这些是 **examples**，不是 core。按 `design/streaming-async-design.md` §0.5 的「框架无关」硬约束：
> **clj-agent 的 core 不依赖任何 web 框架**；这里展示如何把 agent 的流式能力接到你自己的 web 栈。
> web 依赖只在你跑对应示例时引入（见各文件顶部的 deps 说明）。

## 一个模式，四个 server

clj-agent 只暴露一个**框架无关的回调原语**：

```clojure
(agent/chat-stream my-agent "用户消息"
  (fn [token-data]
    (when-let [t (:token token-data)]   ;; 最终文本回合逐 token；工具回合通常无 :token
      (write-to-your-sink! t))))         ;; ← 你把 token 写到你的连接
;; chat-stream 同步阻塞直到整轮结束并返回最终结果；通常放后台线程跑，让连接保持打开
```

**集成 = 把 `on-token` 里的 `write-to-your-sink!` 换成你 server 的发送 API。** 就这一行的差别：

| Server | WebSocket 发送 | SSE 发送 |
|--------|---------------|----------|
| **http-kit** | `(hk/send! ch frame)` | `(hk/send! ch (sse-frame t) false)` |
| **Undertow** | `(ws/send frame channel)` | `(.send sse-conn data)`（原生 `ServerSentEventConnection`） |
| **Jetty**（ring-jetty9） | `(jetty9/send! ws frame)` | `StreamableResponseBody` 写 `out` |
| **Aleph** | `(s/put! conn frame)` | `(s/put! body-stream (sse-frame t))`（manifold stream 作响应体） |

`sse-frame` = `(str "data: " (json/encode {:token t}) "\n\n")`。

> **agent core 对上面这些一无所知**——它只调你的 `on-token`。换 server 不动 agent 一行。

## 各 server 依赖（只加你要用的那个）

```clojure
;; http-kit
http-kit/http-kit {:mvn/version "2.8.0"}
;; Undertow（Luminus 默认）
luminus/ring-undertow-adapter {:mvn/version "1.3.1"}
;; Jetty（WebSocket/HTTP-2）
info.sunng/ring-jetty9-adapter {:mvn/version "0.36.0"}
;; Aleph
aleph/aleph {:mvn/version "0.8.3"}
;; 公共：JSON
cheshire/cheshire {:mvn/version "5.12.0"}
```

## 文件

- `http_kit_example.clj` —— WS + SSE（一套 `send!` API，最省事）
- `undertow_example.clj` —— WS（adapter 一等）+ SSE（Undertow 原生 `ServerSentEventConnection`，全异步）
- `jetty_example.clj` —— WS（ring-jetty9）+ SSE（`StreamableResponseBody`）
- `aleph_example.clj` —— WS + SSE（manifold stream，全异步）

## 客户端断连 → 立即停止生成（已支持）

`chat-stream` 支持**取消令牌**：在连接关闭时 `request-cancel!`，会取消上游 HTTP
（停止烧 token）并让循环停在当前回合，返回 `{:status :cancelled}`。已对 MiniMax 实测：
取消后 ~7ms 内停止、不再有 token。

```clojure
(require '[im.ttalk.agent.streaming :as st])

;; 连接建立时：建令牌，后台线程跑 chat-stream，token 推给连接
(let [token (st/make-cancel-token)]
  (future
    (agent/chat-stream agent message
      (fn [t] (when (:token t) (write-to-your-sink! (:token t))))
      {:cancel-token token}))     ;; ← 传令牌
  ;; 把 token 存到这条连接的上下文；on-close / on-disconnect 时：
  (st/request-cancel! token))     ;; ← 断连即停，取消上游、不再烧 token
```

各示例的 WS `on-close` / SSE 连接关闭回调里调 `request-cancel!` 即可。
