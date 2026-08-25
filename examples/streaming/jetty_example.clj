(ns streaming.jetty-example
  "Jetty（ring-jetty9-adapter）× clj-agent 流式：WebSocket + SSE。

   - WebSocket：ring-jetty9-adapter 的 :websockets 路由（Jetty 12/11，含 HTTP-2）。
   - SSE：Ring 标准 StreamableResponseBody（Jetty 支持；可移植到任何 Ring adapter）。

   依赖（除 core 外）：
     info.sunng/ring-jetty9-adapter {:mvn/version \"0.36.0\"}
     ring/ring-core                 {:mvn/version \"1.12.0\"}
     cheshire/cheshire              {:mvn/version \"5.12.0\"}

   注：ring-jetty9-adapter 的 WebSocket API 换过代——**0.30+ 走 Ring 标准**
   （handler 返回 `{:ring.websocket/listener …}`，用 `ring.websocket/send`），
   旧版那套 `run-jetty :websockets {…}` + `jetty/send!` 在 0.36.0 已不存在
   （`jetty/send!` 连 var 都没有，加载即报 No such var）。下面用标准那套。

   运行（REPL）：(start! 3000)"
  (:require [ring.adapter.jetty9 :as jetty]
            [ring.websocket :as ws]
            [ring.core.protocols :as ring-proto]
            [cheshire.core :as json]
            [clojure.java.io :as io]
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

;; ── WebSocket：Ring 标准（ring.websocket）────────────────────────
;; handler 对 upgrade 请求返回 {:ring.websocket/listener listener}；
;; listener 可以就是个 map（Ring 已把 Listener 协议 extend 到 map），
;; 各 fn 收 socket 作首参；发送用 (ws/send socket msg)。
(defn ws-listener [a]
  (let [token (atom nil)]
    {:on-message
     (fn [socket message]
       (reset! token
         (run-stream! a (str message)
           {:emit! (fn [tok] (ws/send socket (json/encode {:token tok})))
            :done! (fn []    (ws/send socket (json/encode {:done true})))
            :fail! (fn [err] (ws/send socket (json/encode {:error err})))})))
     :on-close (fn [_socket _code _reason] (some-> @token st/request-cancel!))}))   ;; 断连即停

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
    (cond
      ;; WS 升级请求走同一个 ring handler（标准 API 不再有单独的 :websockets 路由表）
      (and (= "/ws" (:uri req)) (ws/upgrade-request? req))
      {:ring.websocket/listener (ws-listener a)}

      (= "/sse" (:uri req))
      (sse-response a (or (some-> (:query-string req)
                                  (->> (re-find #"q=([^&]*)"))
                                  second
                                  (java.net.URLDecoder/decode "UTF-8"))
                          "你好"))

      :else {:status 404 :body "GET /sse?q=... ; WS at /ws"})))

(defonce server (atom nil))
(defn start! [port]
  (let [a (make-agent)]
    (reset! server (jetty/run-jetty (ring-app a) {:port port :join? false}))
    (println "Jetty 启动于" port "— ws://localhost:" port "/ws  |  /sse?q=...")))
(defn stop! [] (when-let [s @server] (.stop s) (reset! server nil)))
