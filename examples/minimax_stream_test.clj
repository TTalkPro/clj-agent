(ns minimax-stream-test
  "java.net.http 真流式传输 × MiniMax（Anthropic 兼容端点）端到端验证。

   验证点：
   1. 真增量：token 随时间逐个到达（带时间戳，对比 http-kit 全量缓冲）。
   2. 复用现有解析器：stream.anthropic/parse-sse-line + process-event 直接驱动。
   3. 归一化输出：on-complete 时 normalize-response 得到 text/reasoning/usage/finish-reason。
   4. MiniMax-M 推理模型：reasoning（thinking）与正文（text）分离。

   运行（需 MINIMAX_AUTH_TOKEN）：
     clojure -M examples/minimax_stream_test.clj"
  (:require [im.ttalk.agent.provider.http.stream-client :as sc]
            [im.ttalk.agent.provider.stream.anthropic :as as]))

(def token (System/getenv "MINIMAX_AUTH_TOKEN"))
(def url "https://api.minimaxi.com/anthropic/v1/messages")

(def t0 (System/currentTimeMillis))
(defn ms [] (- (System/currentTimeMillis) t0))

(defn run []
  (when-not token
    (println "需要 MINIMAX_AUTH_TOKEN") (System/exit 1))
  (let [reasoning-chunks (atom 0)
        text-chunks      (atom 0)
        text-sb          (StringBuilder.)
        done             (promise)
        {:keys [future cancel]}
        (sc/post-stream-async
          url
          {:headers   {"Authorization" (str "Bearer " token)
                       "Content-Type"  "application/json"}
           :body      {:model "MiniMax-M2.7"
                       :max_tokens 1024
                       :messages [{:role "user" :content "用三句话介绍杭州。"}]
                       :stream true}
           :provider  :minimax
           :parse-fn  as/parse-sse-line
           :process-fn as/process-event
           :initial-state (as/make-initial-state)
           :on-token  (fn [t]
                        (cond
                          (:reasoning? t)
                          (do (swap! reasoning-chunks inc)
                              (when (= 1 @reasoning-chunks)
                                (println (format "[t+%5dms] ⟪reasoning 开始流⟫" (ms)))))
                          (:token t)
                          (do (swap! text-chunks inc)
                              (.append text-sb (:token t))
                              (println (format "[t+%5dms] 正文token #%d: %s"
                                               (ms) @text-chunks (pr-str (:token t)))))))
           :on-complete (fn [state]
                          (let [r (as/normalize-response state)]
                            (println (format "\n[t+%5dms] -- 流结束 --" (ms)))
                            (println "reasoning 块数:" @reasoning-chunks
                                     "| 正文 token 数:" @text-chunks)
                            (println "归一化 text:" (pr-str (:text r)))
                            (println "归一化 reasoning(前60):"
                                     (some-> (:reasoning r) (subs 0 (min 60 (count (:reasoning r))))))
                            (println "usage:" (:usage r))
                            (println "finish-reason:" (:finish-reason r))
                            (println "provider:" (:provider r))
                            (deliver done :ok)))
           :on-error  (fn [e]
                        (println (format "[t+%5dms] ERROR: %s (retryable? %s)"
                                         (ms) (:message e) (:retryable? e)))
                        (deliver done :err))})]
    @future
    (deref done 5000 :timeout)
    (println "status:" (.statusCode (.join future)))))

(run)
