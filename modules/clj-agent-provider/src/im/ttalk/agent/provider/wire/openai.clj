(ns im.ttalk.agent.provider.wire.openai
  "中立消息 ↔ OpenAI wire 格式 转换

   neutral->wire: 中立消息列表 → OpenAI chat messages
   response->neutral: OpenAI 响应 → 中立 assistant 消息

   OpenAI wire 形态：
   - system/user:  {:role \"system\"/\"user\" :content \"...\"}
   - assistant:    {:role \"assistant\" :content \"...\"}
   - assistant+tools: {:role \"assistant\" :content nil
                       :tool_calls [{:id .. :type \"function\"
                                     :function {:name .. :arguments \"{json}\"}}]}
   - tool result:  {:role \"tool\" :tool_call_id .. :content \"...\"}"
  (:require [cheshire.core :as json]
            [im.ttalk.agent.model.message :as msg]))

;;; ============================================================
;;; 中立 → OpenAI wire
;;; ============================================================

(defn- tool-call->wire
  [{:keys [id name args]}]
  {:id id
   :type "function"
   :function {:name name
              :arguments (json/generate-string (or args {}))}})

(defn- msg->wire
  [m]
  (case (msg/role m)
    :system    {:role "system" :content (msg/content m)}
    :user      {:role "user" :content (msg/content m)}
    :assistant (if (msg/has-tool-calls? m)
                 (cond-> {:role "assistant"
                          :content (msg/content m)            ; 可为 nil
                          :tool_calls (mapv tool-call->wire (msg/tool-calls m))})
                 {:role "assistant" :content (msg/content m)})
    :tool      {:role "tool"
                :tool_call_id (msg/tool-call-id m)
                :content (msg/content m)}
    ;; 未知 role：尽力透传
    {:role (clojure.core/name (msg/role m)) :content (msg/content m)}))

(defn neutral->wire
  "把中立消息列表转成 OpenAI chat messages 列表。

   返回：{:messages [wire-msg ...]}
   （system 作为普通消息内联，故无独立 :system 字段）"
  [neutral-msgs]
  {:messages (mapv (comp msg->wire msg/normalize) neutral-msgs)})

;;; ============================================================
;;; OpenAI 响应 → 中立
;;; ============================================================

(defn- parse-args
  "OpenAI function.arguments 是 JSON 字符串，解析为 map（keyword 键）。
   解析失败返回 {}。"
  [arguments]
  (cond
    (map? arguments) arguments
    (string? arguments) (try (json/parse-string arguments true)
                             (catch Exception _ {}))
    :else {}))

(defn- wire-tool-call->neutral
  [{:keys [id function]}]
  (msg/tool-call id (:name function) (parse-args (:arguments function))))

(defn response->neutral
  "OpenAI 响应 → 中立 assistant 消息。
   含 tool_calls 则返回 assistant-tool-calls，否则纯文本 assistant。"
  [response]
  (let [m (get-in response [:choices 0 :message])
        wire-calls (:tool_calls m)]
    (if (seq wire-calls)
      (msg/assistant-tool-calls (mapv wire-tool-call->neutral wire-calls)
                                (:content m))
      (msg/assistant (:content m)))))
