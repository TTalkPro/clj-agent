(ns im.ttalk.agent.llm.wire.anthropic
  "中立消息 ↔ Anthropic wire 格式 转换

   neutral->wire: 中立消息列表 → {:system .. :messages ..}
   response->neutral: Anthropic 响应 → 中立 assistant 消息

   Anthropic wire 形态：
   - system 是顶层参数（不在 messages 里）
   - user:        {:role \"user\" :content \"...\"}
   - assistant:   {:role \"assistant\" :content \"...\"}
   - assistant+tools: {:role \"assistant\"
                       :content [{:type \"text\" :text ..}?
                                 {:type \"tool_use\" :id .. :name .. :input {..}}]}
   - tool result: {:role \"user\"
                   :content [{:type \"tool_result\" :tool_use_id .. :content ..}]}
   连续的 tool 结果合并进同一条 user 消息的 content 数组。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.core.llm.message :as msg]))

;;; ============================================================
;;; 中立 → Anthropic wire
;;; ============================================================

(defn- tool-call->block
  [{:keys [id name args]}]
  {:type "tool_use" :id id :name name :input (or args {})})

(defn- assistant->wire
  [m]
  (if (msg/has-tool-calls? m)
    (let [text (msg/content m)
          blocks (cond-> []
                   (and (string? text) (seq text)) (conj {:type "text" :text text})
                   :always (into (mapv tool-call->block (msg/tool-calls m))))]
      {:role "assistant" :content blocks})
    {:role "assistant" :content (msg/content m)}))

(defn- tool-result-group?
  "上一条已是承载 tool_result 的 user 消息（content 为向量）"
  [wire-msg]
  (and (= "user" (:role wire-msg))
       (vector? (:content wire-msg))))

(defn neutral->wire
  "中立消息列表 → {:system <str|nil> :messages [wire-msg ...]}"
  [neutral-msgs]
  (let [norm (map msg/normalize neutral-msgs)
        system (let [sys (->> norm (filter msg/system?) (map msg/content) (remove nil?))]
                 (when (seq sys) (str/join "\n" sys)))
        body (remove msg/system? norm)
        messages
        (reduce
          (fn [acc m]
            (case (msg/role m)
              :user      (conj acc {:role "user" :content (msg/content m)})
              :assistant (conj acc (assistant->wire m))
              :tool      (let [block {:type "tool_result"
                                      :tool_use_id (msg/tool-call-id m)
                                      :content (msg/content m)}
                               prev (peek acc)]
                           (if (tool-result-group? prev)
                             (conj (pop acc) (update prev :content conj block))
                             (conj acc {:role "user" :content [block]})))
              ;; 未知 role 透传
              (conj acc {:role (clojure.core/name (msg/role m)) :content (msg/content m)})))
          []
          body)]
    {:system system :messages messages}))

;;; ============================================================
;;; Anthropic 响应 → 中立
;;; ============================================================

(defn response->neutral
  "Anthropic 响应 → 中立 assistant 消息。
   含 tool_use 块则 assistant-tool-calls，否则纯文本 assistant。"
  [response]
  (let [blocks (:content response)
        text (->> blocks
                  (filter #(= "text" (:type %)))
                  (map :text)
                  (str/join "\n"))
        text (when (seq text) text)
        calls (->> blocks
                   (filter #(= "tool_use" (:type %)))
                   (mapv (fn [{:keys [id name input]}]
                           (msg/tool-call id name input))))]
    (if (seq calls)
      (msg/assistant-tool-calls calls text)
      (msg/assistant text))))
