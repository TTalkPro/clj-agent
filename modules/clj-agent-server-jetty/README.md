# clj-agent-server-jetty

[`clj-agent-server`](../clj-agent-server/README.md) 的**缺省 adapter**：Jetty 12（`ring/ring-jetty-adapter`）宿主——绑地址、
挂 Ring 处理器或路由表、起、停、优雅停机。只有这个模块认识 Jetty；用别的 Ring 服务端就不要引它。

```clojure
(require '[im.ttalk.agent.server.jetty :as jetty])

(def s (jetty/start routes-or-handler {:port 0}))   ;; 0 = 内核分配
(jetty/base-address s)                              ;; => "http://127.0.0.1:54321/"
(jetty/stop! s)                                     ;; 优雅停机：先跑停机钩子、中止开着的 SSE，再停 Jetty
```

`im.ttalk.agent.server.jetty` 的选项（详见 ns 文档）：`:host`（缺省 127.0.0.1；`localhost` = IPv4 + IPv6 两个连接器）
`:port` `:unix-socket-path` `:ssl-context` / `:key-store` `:max-request-body-size` `:max-request-headers-total-size`
`:max-request-line-size` `:idle-timeout-ms` `:shutdown-timeout-ms` `:guard`（本机访问守卫；绑非回环地址又没 token ⇒ 拒绝启动）
`:middleware` `:on-stopping`，以及给用户自己的 Ring WebSocket 路由用的 `:ws-max-message-bytes` / `:ws-idle-timeout-ms`。
线程池是 Jetty 的 VirtualThreadPool（每个请求一条虚拟线程）。

移植自 Keelson clj-net-server 的宿主（`designs/server-module.md`）。
