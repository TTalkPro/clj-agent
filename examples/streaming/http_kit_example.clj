(ns streaming.http-kit-example
  "http-kit server × clj-agent 流式：WebSocket + SSE。

   http-kit 的优势：WS 和 SSE 用同一套 `as-channel` + `send!` API，最省事。

   依赖（除 core 外）：
     http-kit/http-kit {:mvn/version \"2.8.0\"}
     cheshire/cheshire  {:mvn/version \"5.12.0\"}

   运行（REPL）：
     (start! 3000)  ;; 然后浏览器连 ws://localhost:3000/ws 或 EventSource('/sse?q=...')"
  (:require [org.httpkit.server :as hk]
            [cheshire.core :as json]
            [im.ttalk.agent.client :as agent]
            [im.ttalk.agent.streaming :as st]
            [im.ttalk.agent.provider.minimax :as minimax]))

;; ── agent（provider 可换；这里用 MiniMax 示例）──────────────────
(defn make-agent []
  (agent/create-agent
    {:provider (minimax/create-provider {:api-key (System/getenv "MINIMAX_AUTH_TOKEN")})
     :model "MiniMax-M2.7" :max-tokens 1024}))

(defn- sse-frame [m] (str "data: " (json/encode m) "\n\n"))

;; ── 共享：在后台线程跑 chat-stream，token 经 emit! 推给连接 ────────
;; emit! / done! / fail! 由各 transport 提供（WS 发帧 / SSE 写 data:）。
;; 返回 cancel-token，供连接关闭时 request-cancel!（断连即停、不再烧 token）。
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

;; ── WebSocket：客户端发一条消息 → 流式回 token 帧 ─────────────────
(defn ws-handler [a]
  (fn [req]
    (let [token (atom nil)]
      (hk/as-channel req
        {:on-receive
         (fn [ch message]
           (reset! token
             (run-stream! a message
               {:emit! (fn [tok] (hk/send! ch (json/encode {:token tok})))
                :done! (fn []    (hk/send! ch (json/encode {:done true})))
                :fail! (fn [err] (hk/send! ch (json/encode {:error err})))})))
         ;; 断连即停：取消上游、不再烧 token
         :on-close (fn [_ch _status] (some-> @token st/request-cancel!))}))))

;; ── SSE：?q=... 作为消息 → text/event-stream 逐 token ────────────
(defn sse-handler [a]
  (fn [req]
    (let [q (get-in req [:params :q] (get-in req [:query-params "q"]))
          token (atom nil)]
      (hk/as-channel req
        {:on-open
         (fn [ch]
           ;; 先发响应头（非 WS 升级 → http-kit 视作流式 HTTP）
           (hk/send! ch {:status 200
                         :headers {"Content-Type" "text/event-stream"
                                   "Cache-Control" "no-cache"}}
                     false)
           (reset! token
             (run-stream! a (or q "你好")
               {:emit! (fn [tok] (hk/send! ch (sse-frame {:token tok}) false))
                :done! (fn []    (hk/send! ch (sse-frame {:done true}) false) (hk/close ch))
                :fail! (fn [err] (hk/send! ch (sse-frame {:error err}) false) (hk/close ch))})))
         ;; 客户端断开 EventSource → 取消上游
         :on-close (fn [_ch _status] (some-> @token st/request-cancel!))}))))

;; ── 路由 + 启动 ────────────────────────────────────────────────
(defn app [a]
  (fn [req]
    (case (:uri req)
      "/ws"  ((ws-handler a) req)
      "/sse" ((sse-handler a) req)
      {:status 404 :body "not found"})))

(defonce server (atom nil))
(defn start! [port]
  (let [a (make-agent)]
    (reset! server (hk/run-server (app a) {:port port}))
    (println "http-kit 启动于" port "— ws://localhost:" port "/ws  |  /sse?q=...")))
(defn stop! [] (when-let [s @server] (s) (reset! server nil)))
