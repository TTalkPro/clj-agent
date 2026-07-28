(ns im.ttalk.agent.provider.wire.anthropic
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
   连续的 tool 结果合并进同一条 user 消息的 content 数组。

   assistant 消息带 :blocks {:format :anthropic-content :data [...]} 时**原样吐回**
   （thinking 块 + signature 必须逐字回传）；格式认不出或没有 :blocks 就走
   text + tool_use 重建。见 docs/provider-variant-design.md。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.model.message :as msg]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 中立 → Anthropic wire
;;; ============================================================

(defn- tool-call->block
  [{:keys [id name args]}]
  {:type "tool_use" :id id :name name :input (or args {})})

(def ^:private replay-format
  "本 wire 认得的载荷格式。认不出的一律当它不存在——历史可能来自别的 provider
   （换模型重跑、subagent 用了别家），把人家的方言喂给 Anthropic 端点必炸。"
  :anthropic-content)

(defn- replayable-content
  "中立消息里可原样吐回的 content（无 / 格式不认识 → nil，调用方走重建路径）。"
  [m]
  (let [{:keys [format data]} (msg/blocks m)]
    (when (and (= replay-format format) (sequential? data) (seq data))
      (vec data))))

(defn- assistant->wire
  "两条路：认得的 :blocks → **原样吐**（thinking 块与 signature 必须逐字回传，
   见 docs/provider-variant-design.md §7.5.3）；否则 → 用 text + tool_use 重建。

   **重建是缺省，不是回退凑合**：存量历史没有 :blocks，跨 provider 的历史 format
   也对不上，这条路必须一直能走。"
  [m]
  (if-let [content (replayable-content m)]
    {:role "assistant" :content content}
    (if (msg/has-tool-calls? m)
      (let [text (msg/content m)
            blocks (cond-> []
                     (and (string? text) (seq text)) (conj {:type "text" :text text})
                     :always (into (mapv tool-call->block (msg/tool-calls m))))]
        {:role "assistant" :content blocks})
      {:role "assistant" :content (msg/content m)})))

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
