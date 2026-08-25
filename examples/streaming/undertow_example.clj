(ns streaming.undertow-example
  "Undertow（Luminus 默认 server）× clj-agent 流式：WebSocket + SSE。

   - WebSocket：ring-undertow-adapter 一等支持（响应里放 :undertow/websocket）。
   - SSE：ring-undertow-adapter 未一等暴露 SSE，故用 Undertow **原生**
     `io.undertow.server.handlers.sse`（ServerSentEventConnection）——全异步、不占线程，
     `.send` 正好对应 on-token。

   依赖（除 core 外）：
     luminus/ring-undertow-adapter {:mvn/version \"1.3.1\"}
     cheshire/cheshire             {:mvn/version \"5.12.0\"}

   运行（REPL）：(start! 3000)"
  (:require [ring.adapter.undertow :refer [run-undertow]]
            [ring.adapter.undertow.websocket :as ws]
            [cheshire.core :as json]
            [im.ttalk.agent.simple-agent :as agent]
            [im.ttalk.agent.streaming :as st]
            [im.ttalk.agent.provider.minimax :as minimax])
  (:import [io.undertow Handlers]
           [io.undertow.server.handlers.sse
            ServerSentEventConnectionCallback ServerSentEventConnection
            ServerSentEventConnection$EventCallback]))

(defn make-agent []
  (agent/create-agent
    {:provider (minimax/create-provider {:api-key (or (System/getenv "MINIMAX_API_KEY")
                                                  (System/getenv "MINIMAX_AUTH_TOKEN"))})
     :model "MiniMax-M2.7" :max-tokens 1024}))

;; 返回 cancel-token，供连接关闭时 request-cancel!
(defn- run-stream! [a message {:keys [emit! done! fail!]}]
  (let [token (st/make-cancel-token)]
    (future
      (try
        (agent/chat-stream a message
          (fn [t] (when-let [tok (:token t)] (emit! tok)))
          {:cancel-token token})
        (done!)
        (catch Exception e (fail! (.getMessage e)))))
    token))

;; ── WebSocket：ring-undertow-adapter 一等支持 ────────────────────
(defn ws-handler [a]
  (fn [_req]
    (let [token (atom nil)]
      {:undertow/websocket
       {:on-message
        (fn [{:keys [channel data]}]
          (reset! token
            (run-stream! a data
              {:emit! (fn [tok] (ws/send (json/encode {:token tok}) channel))
               :done! (fn []    (ws/send (json/encode {:done true}) channel))
               :fail! (fn [err] (ws/send (json/encode {:error err}) channel))})))
        ;; ⚠️ 是 :on-close-message 不是 :on-close —— 后者在 ring-undertow-adapter
        ;; 1.3.x 已废弃，且是 **assert 掉的**：写成 :on-close 时 WS 握手直接 500
        ;; （`AssertionError: :on-close has been deprecated`），而这只在**运行期**
        ;; 才炸，加载/编译一路绿灯。回调收 {:channel :message}。
        :on-close-message (fn [_] (some-> @token st/request-cancel!))}})))   ;; 断连即停

;; ── SSE：Undertow 原生 handler（挂进 Undertow handler 链 / 指定路径）──
;; (.send conn data) 异步推；(.addCloseTask conn ...) 在断连时触发（可接 cancel）
(defn sse-handler [a]
  (Handlers/serverSentEvents
    ;; ⚠️ reify 的**参数**上不能加类型 hint：`connected` 会匹配不上，报
    ;; "Can't find matching method: connected, leave off hints for auto match"。
    ;; hint 挪进 body 的 let 里——既能匹配，`.send`/`.close` 也不走反射。
    (reify ServerSentEventConnectionCallback
      (connected [_ conn _last-id]
        (let [^ServerSentEventConnection conn conn
              ;; 消息可从 query 取（此处简化为固定示例；真实从 conn 的 exchange 解析 query）
              ;; ⚠️ `.send` 是**异步**的：紧跟 `.close` 会把最后一帧冲掉——客户端
              ;; 永远收不到 done/error。收尾必须走带 EventCallback 的重载，在
              ;; 回调里再关。（这条只有端到端才抓得到：编译、加载全绿。）
              close-after (fn []
                            (reify ServerSentEventConnection$EventCallback
                              (done   [_ c _ _ _]   (.close ^ServerSentEventConnection c))
                              (failed [_ c _ _ _ _] (.close ^ServerSentEventConnection c))))
              token (run-stream! a "用三句话介绍杭州。"
                      {:emit! (fn [tok] (.send conn (json/encode {:token tok})))
                       :done! (fn []    (.send conn (json/encode {:done true})  (close-after)))
                       :fail! (fn [err] (.send conn (json/encode {:error err}) (close-after)))})]
          ;; 客户端断开 → Undertow 触发 close task（ChannelListener）→ 取消上游、不再烧 token
          (.addCloseTask conn
            (reify org.xnio.ChannelListener
              (handleEvent [_ _conn] (st/request-cancel! token)))))))))

;; ── 启动 ──────────────────────────────────────────────────────
;; 两条路分别用各自最合适的机制（都全异步）：
;;  - WebSocket：run-undertow + ring handler（:undertow/websocket，adapter 一等支持）。
;;  - SSE：Undertow 原生 (Handlers/serverSentEvents)。它是个 io.undertow HttpHandler，
;;    不经 ring adapter——挂进 Undertow 的 path/handler 链即可。
;;
;; 在 Luminus 里把 SSE 挂上去有两种常见方式：
;;  (a) 给 run-undertow 传 :configurator，往 Undertow.Builder 加一个 path handler；
;;  (b) 或直接用 io.undertow.Undertow 自建 server，把 ring handler 与 sse-handler
;;      用 (Handlers/path) 组合（addPrefixPath ring / addExactPath "/sse" sse）。
;; 下面演示最小的「纯 WS via ring-undertow」启动；SSE handler 见上方 sse-handler，
;; 按你的 Luminus 服务器配置挂到 "/sse" 路径。

(defn ring-app [a]
  (fn [req]
    (case (:uri req)
      "/ws" ((ws-handler a) req)
      {:status 404 :body "use /ws (WebSocket) or mount sse-handler at /sse"})))

(defonce server (atom nil))
(defn start! [port]
  (let [a (make-agent)]
    (reset! server (run-undertow (ring-app a) {:port port :host "0.0.0.0"}))
    (println "Undertow 启动于" port)
    (println "  WS  : ws://localhost:" port "/ws  （ring-undertow :undertow/websocket）")
    (println "  SSE : 把 (sse-handler agent) 挂到 Undertow 的 /sse 路径（见注释 a/b）")))
(defn stop! [] (when-let [s @server] (.stop s) (reset! server nil)))
