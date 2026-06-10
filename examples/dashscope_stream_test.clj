(ns dashscope-stream-test
  "DashScope 原生流式（X-DashScope-SSE + incremental_output）端到端 live 验证。

   验证点：
   1. 真增量：token 随时间逐个到达（带时间戳，对比 http-kit 全量缓冲会全挤在结尾）。
   2. provider 路径：经 model/call-llm-stream（dashscope/stream.dashscope 解析器）。
   3. 归一化输出：extract-text / usage / finish-reason 正确。
   4. agent 链路：client/chat-stream + 多轮记忆（与同步不分叉）。
   5. 取消：request-cancel! 中途停止上游、不再烧 token。

   运行（需 DASHSCOPE_API_KEY）：
     clojure -M examples/dashscope_stream_test.clj"
  (:require [im.ttalk.agent.client :as agent]
            [im.ttalk.agent.streaming :as st]
            [im.ttalk.agent.model :as model]
            [im.ttalk.agent.provider.dashscope :as dashscope]))

(def api-key (System/getenv "DASHSCOPE_API_KEY"))
(def t0 (System/currentTimeMillis))
(defn ms [] (- (System/currentTimeMillis) t0))

(defn- new-agent []
  (agent/create-agent
    {:provider (dashscope/create-provider {:api-key api-key})
     :model "qwen-plus" :max-tokens 800}))

;;; ── 1) provider 级真增量 + 归一化 ──────────────────────────────
(defn test-provider-stream []
  (println "\n=== 1) provider 级 call-llm-stream（真增量）===")
  (let [p (dashscope/create-provider {:api-key api-key})
        n (atom 0)]
    (println "supports-stream?" (model/supports-stream? p))
    (println (format "[t+%4dms] >> 发起流式" (ms)))
    (let [resp (model/call-llm-stream p
                 {:model "qwen-plus" :max-tokens 400}
                 [{:role "user" :content "用四句话介绍杭州，每句单独成行。"}]
                 nil
                 (fn [t] (when (:token t)
                           (swap! n inc)
                           (when (<= @n 10)
                             (println (format "[t+%4dms] token#%d %s" (ms) @n (pr-str (:token t))))))))]
      (println (format "[t+%4dms] -- 完成 -- 正文token=%d" (ms) @n))
      (println "extract-text:" (pr-str (model/extract-text p resp)))
      (println "finish_reason:" (get-in resp [:choices 0 :finish_reason])
               "| usage:" (:usage resp)))))

;;; ── 2) agent chat-stream + 多轮记忆 ────────────────────────────
(defn test-chat-stream []
  (println "\n=== 2) chat-stream + 多轮记忆 ===")
  (let [a (new-agent) n (atom 0)]
    (let [r (agent/chat-stream a "只用一句话介绍西湖。"
              (fn [t] (when (:token t) (swap! n inc))))]
      (println "第1轮 status=" (:status r) "text=" (pr-str (:text r)) "| token数=" @n
               "| 历史=" (count (agent/get-history a))))
    (let [r2 (agent/chat-stream a "我刚问的是哪个湖？只答名字。" (fn [_]))]
      (println "第2轮 text=" (pr-str (:text r2)) "(应含'西湖')| 历史=" (count (agent/get-history a))))))

;;; ── 3) 取消：中途停止 ──────────────────────────────────────────
(defn test-cancel []
  (println "\n=== 3) 取消（断连即停）===")
  (let [a (new-agent) n (atom 0)
        token (st/make-cancel-token)
        result (promise)]
    (future (deliver result
              (agent/chat-stream a "写一篇 500 字的杭州游记，分段详细描述。"
                (fn [t] (when (:token t) (swap! n inc)))
                {:cancel-token token})))
    ;; 收到 10 个 token 后取消
    (loop [i 0] (when (and (< @n 10) (< i 4000)) (Thread/sleep 5) (recur (inc i))))
    (let [at @n cancel-at (ms)]
      (st/request-cancel! token)
      (let [r (deref result 30000 :timeout)]
        (println (format "取消时已收 %d token；status=%s；取消后又收 %d token，耗时 %dms"
                         at (:status r) (- @n at) (- (ms) cancel-at)))))))

(defn -main []
  (when-not api-key (println "需要 DASHSCOPE_API_KEY") (System/exit 1))
  (test-provider-stream)
  (test-chat-stream)
  (test-cancel)
  (println "\n全部验证完成。")
  (shutdown-agents))

(-main)
