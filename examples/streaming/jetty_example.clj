(ns streaming.jetty-example
  "Jetty（ring-jetty9-adapter）× clj-agent 流式：WebSocket + SSE。

   - WebSocket：ring-jetty9-adapter 的 :websockets 路由（Jetty 12/11，含 HTTP-2）。
   - SSE：Ring 标准 StreamableResponseBody（Jetty 支持；可移植到任何 Ring adapter）。

   依赖（除 core 外）：
     info.sunng/ring-jetty9-adapter {:mvn/version \"0.36.0\"}
     ring/ring-core                 {:mvn/version \"1.12.0\"}
     cheshire/cheshire              {:mvn/version \"5.12.0\"}

   注：ring-jetty9-adapter 各版本的 WebSocket API 略有差异（旧版 :websockets map /
   新版 ring.websocket 标准）。下面用广泛文档化的 :websockets map 形式；按你的版本对齐即可。

   运行（REPL）：(start! 3000)"
  (:require [ring.adapter.jetty9 :as jetty]
            [ring.core.protocols :as ring-proto]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [im.ttalk.agent.client :as agent]
            [im.ttalk.agent.streaming :as st]
            [im.ttalk.agent.provider.minimax :as minimax]))

(defn make-agent []
  (agent/create-agent
    {:provider (minimax/create-provider {:api-key (System/getenv "MINIMAX_AUTH_TOKEN")})
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

;; ── WebSocket：ring-jetty9 :websockets 路由 ──────────────────────
;; ws 对象的发送用 (jetty/send! ws msg)；客户端发文本 → on-text → 流式回
(defn ws-handler [a]
  (let [token (atom nil)]
    {:on-text
     (fn [ws message]
       (reset! token
         (run-stream! a message
           {:emit! (fn [tok] (jetty/send! ws (json/encode {:token tok})))
            :done! (fn []    (jetty/send! ws (json/encode {:done true})))
            :fail! (fn [err] (jetty/send! ws (json/encode {:error err})))})))
     :on-close (fn [_ws _status _reason] (some-> @token st/request-cancel!))}))   ;; 断连即停

;; ── SSE：Ring StreamableResponseBody（可移植）────────────────────
(defn sse-response [a message]
  {:status 200
   :headers {"Content-Type" "text/event-stream"
             "Cache-Control" "no-cache"
             "Connection" "keep-alive"}
   :body (reify ring-proto/StreamableResponseBody
           (write-body-to-stream [_ _ out]
             (let [w (io/writer out)
                   done (promise)
                   token (atom nil)
                   ;; 客户端断开 → 写抛 IOException → 取消上游、不再烧 token
                   write! (fn [s] (try (locking w (.write w s) (.flush w))
                                       (catch java.io.IOException _
                                         (some-> @token st/request-cancel!)
                                         (deliver done :closed))))]
               (reset! token
                 (run-stream! a message
                   {:emit! (fn [tok] (write! (sse-frame {:token tok})))
                    :done! (fn []    (write! (sse-frame {:done true})) (deliver done :ok))
                    :fail! (fn [err] (write! (sse-frame {:error err})) (deliver done :err))}))
               @done                       ;; 阻塞此写线程直到流结束（StreamableResponseBody 语义）
               (.close w))))})

;; ── 路由 + 启动 ────────────────────────────────────────────────
(defn ring-app [a]
  (fn [req]
    (case (:uri req)
      "/sse" (sse-response a (get-in req [:query-params "q"] "你好"))
      {:status 404 :body "GET /sse?q=... ; WS at /ws"})))

(defonce server (atom nil))
(defn start! [port]
  (let [a (make-agent)]
    (reset! server
            (jetty/run-jetty (ring-app a)
                             {:port port :join? false
                              :websockets {"/ws" (ws-handler a)}}))
    (println "Jetty 启动于" port "— ws://localhost:" port "/ws  |  /sse?q=...")))
(defn stop! [] (when-let [s @server] (.stop s) (reset! server nil)))
