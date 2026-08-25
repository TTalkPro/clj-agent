(ns streaming.aleph-example
  "Aleph × clj-agent 流式：WebSocket + SSE。

   Aleph 基于 Netty + manifold，全异步。WS 和 SSE 都是 manifold stream：
   token 往 stream 里 put!，Aleph 负责异步发出，不占线程。

   依赖（除 core 外）：
     aleph/aleph       {:mvn/version \"0.8.3\"}
     cheshire/cheshire {:mvn/version \"5.12.0\"}

   运行（REPL）：(start! 3000)"
  (:require [aleph.http :as http]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [cheshire.core :as json]
            [im.ttalk.agent.simple-agent :as agent]
            [im.ttalk.agent.streaming :as st]
            [im.ttalk.agent.provider.minimax :as minimax]))

(defn make-agent []
  (agent/create-agent
    {:provider (minimax/create-provider {:api-key (or (System/getenv "MINIMAX_API_KEY")
                                                  (System/getenv "MINIMAX_AUTH_TOKEN"))})
     :model "MiniMax-M2.7" :max-tokens 1024}))

(defn- sse-frame [m] (str "data: " (json/encode m) "\n\n"))

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

;; ── WebSocket：duplex manifold stream ───────────────────────────
(defn ws-handler [a]
  (fn [req]
    (d/let-flow [conn (http/websocket-connection req)]
      (s/consume
        (fn [message]
          (let [token (run-stream! a message
                        {:emit! (fn [tok] (s/put! conn (json/encode {:token tok})))
                         :done! (fn []    (s/put! conn (json/encode {:done true})))
                         :fail! (fn [err] (s/put! conn (json/encode {:error err})))})]
            ;; 客户端断开 → stream 关闭 → 取消上游
            (s/on-closed conn (fn [] (st/request-cancel! token)))))
        conn)
      conn)))

;; ── SSE：响应体就是一个 manifold source stream ───────────────────
(defn sse-handler [a]
  (fn [req]
    (let [q    (get-in req [:query-params "q"] "你好")
          body (s/stream)
          token (run-stream! a q
                  {:emit! (fn [tok] (s/put! body (sse-frame {:token tok})))
                   :done! (fn []    (s/put! body (sse-frame {:done true}))  (s/close! body))
                   :fail! (fn [err] (s/put! body (sse-frame {:error err})) (s/close! body))})]
      ;; 客户端断开 EventSource → body stream 关闭 → 取消上游
      (s/on-closed body (fn [] (st/request-cancel! token)))
      {:status 200
       :headers {"content-type" "text/event-stream" "cache-control" "no-cache"}
       :body body})))

;; ── 路由 + 启动 ────────────────────────────────────────────────
(defn ring-app [a]
  (fn [req]
    (case (:uri req)
      "/ws"  ((ws-handler a) req)
      "/sse" ((sse-handler a) req)
      {:status 404 :body "GET /sse?q=... or WS /ws"})))

(defonce server (atom nil))
(defn start! [port]
  (let [a (make-agent)]
    (reset! server (http/start-server (ring-app a) {:port port}))
    (println "Aleph 启动于" port "— ws://localhost:" port "/ws  |  /sse?q=...")))
(defn stop! [] (when-let [s @server] (.close s) (reset! server nil)))
