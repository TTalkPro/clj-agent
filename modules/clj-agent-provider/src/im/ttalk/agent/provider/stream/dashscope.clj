(ns im.ttalk.agent.provider.stream.dashscope
  "DashScope 原生流式响应处理（SSE）。

   DashScope 流式：请求头 X-DashScope-SSE: enable + parameters.incremental_output true。
   响应是 SSE，每个事件含 id:/event:result/:HTTP_STATUS/data: 几行，data 为 JSON：
     {:output {:choices [{:finish_reason fr :message {:role :content delta}}]}
      :usage {...} :request_id ...}
   incremental_output=true 时 content 是增量（delta）。finish_reason 生成中为 \"null\"/null，
   结束为 \"stop\"/\"length\" 等。

   产出与同步 parse-response 同形的 OpenAI 兼容响应（extract-text 读 choices[0].message.content）。"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(set! *warn-on-reflection* true)

;; :accumulated 用 StringBuilder：逐 token `(str acc delta)` 是 O(n²)；
;; SSE 行由 Flow.Subscriber 串行投递，原地 append 安全，build-response 时物化。
(defn make-initial-state []
  {:accumulated (StringBuilder.) :tool-calls nil :id nil :model nil :finish-reason nil :usage nil})

(defn parse-sse-line
  "取 DashScope SSE 的 data 行（兼容 \"data:\" 有无空格），跳过 id:/event:/:HTTP_STATUS 等。"
  [line]
  (when (and line (str/starts-with? line "data:"))
    (let [data-str (str/trim (subs line 5))]   ;; 去 "data:" 前缀再 trim → 兼容有无空格
      (when (and (seq data-str) (not= data-str "[DONE]"))
        (try
          (json/parse-string data-str true)
          (catch Exception e
            (log/warn "DashScope SSE 行 JSON 解析失败，已跳过"
                      {:data-preview (subs data-str 0 (min 200 (count data-str)))
                       :error (.getMessage e)})
            nil))))))

(defn process-event
  "处理一个 DashScope SSE 事件。返回 [new-state token-data|nil]。
   content 增量 → emit {:token delta}；tool_calls / usage / finish_reason 累积到 state。"
  [event state]
  (let [choice (first (get-in event [:output :choices]))
        msg    (:message choice)
        delta  (:content msg)
        tcs    (:tool_calls msg)
        fr     (:finish_reason choice)
        usage  (:usage event)
        rid    (:request_id event)
        state  (cond-> state
                 rid                       (assoc :id rid)
                 usage                     (assoc :usage usage)
                 ;; 捕获 tool_calls（result_format message 下通常为完整调用；增量 args 不特殊处理）
                 (seq tcs)                 (assoc :tool-calls tcs)
                 ;; finish_reason 生成中为 "null"/null，仅在真正结束时记录
                 (and fr (not= fr "null")) (assoc :finish-reason fr))]
    (if (and delta (seq delta))
      (do (.append ^StringBuilder (:accumulated state) ^String delta)
          [state {:token delta}])
      [state nil])))

(defn build-response
  "从最终状态构建与同步 parse-response 同形的 OpenAI 兼容响应。"
  [state]
  {:choices [{:message (cond-> {:role "assistant" :content (str (:accumulated state))}
                         (:tool-calls state) (assoc :tool_calls (:tool-calls state)))
              :finish_reason (:finish-reason state)}]
   :usage (:usage state)
   :model (:model state)
   :id (or (:id state) "unknown")})
