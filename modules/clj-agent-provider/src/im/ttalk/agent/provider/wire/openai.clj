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
   - tool result:  {:role \"tool\" :tool_call_id .. :content \"...\"}

   **多模态**：user 消息的 :content 为中立部件向量（见 model/content）时，
   按 media type 顶层类别翻译——image/* → image_url（内联走 data URI）、
   audio/* → input_audio（仅 wav/mp3，OpenAI 不收 URL 音频）、
   application/pdf → file.file_data。其余类型不静默丢，抛 :validation-error。
   :type 为**字符串**的元素视为 OpenAI 原生块，原样透传。"
  (:require [cheshire.core :as json]
            [im.ttalk.agent.model.content :as content]
            [im.ttalk.agent.model.error :as errors]
            [im.ttalk.agent.model.message :as msg]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 中立 → OpenAI wire
;;; ============================================================

(defn- unsupported!
  [what part]
  (errors/throw!
    (errors/error :validation-error
                  (str "OpenAI 兼容协议不支持" what)
                  {:provider :openai
                   :context {:media-type (:media-type part)
                             :source (if (:url part) :url :data)}})))

(defn- audio-format
  "OpenAI input_audio 只认 wav / mp3 两种 format"
  [part]
  (case (:media-type part)
    "audio/wav"  "wav"
    "audio/wave" "wav"
    "audio/x-wav" "wav"
    ("audio/mp3" "audio/mpeg") "mp3"
    (unsupported! (str "该音频格式（" (:media-type part) "，仅 wav/mp3）") part)))

(defn- file-part->wire
  [{:keys [url filename] :as part} idx]
  (cond
    (content/image? part)
    {:type "image_url"
     :image_url (cond-> {:url (or url (content/data-uri part))}
                  (:detail part) (assoc :detail (:detail part)))}

    (content/audio? part)
    (if url
      (unsupported! "URL 形态的音频（请传内联数据）" part)
      {:type "input_audio" :input_audio {:data (:data part) :format (audio-format part)}})

    (= "application/pdf" (:media-type part))
    (if url
      (unsupported! "URL 形态的 PDF（请传内联数据）" part)
      {:type "file" :file {:filename (or filename (str "part-" idx ".pdf"))
                           :file_data (content/data-uri part)}})

    :else
    (unsupported! (str "该文件类型（" (:media-type part) "）") part)))

(defn- part->wire
  "中立部件 → OpenAI content 部件；非中立部件（厂商原生块 / 字符串）原样透传。"
  [part idx]
  (cond
    (content/text-part? part) {:type "text" :text (:text part)}
    (content/file-part? part) (file-part->wire part idx)
    :else part))

(defn- content->wire
  "content 归一：字符串原样；中立部件向量逐个翻译；其余（厂商原生块向量）原样透传。"
  [c]
  (if (content/parts? c)
    (vec (map-indexed (fn [idx part] (part->wire part idx)) c))
    c))

(defn- tool-call->wire
  [{:keys [id name args]}]
  {:id id
   :type "function"
   :function {:name name
              :arguments (json/generate-string (or args {}))}})

(defn- msg->wire
  [m]
  (case (msg/role m)
    :system    {:role "system" :content (content->wire (msg/content m))}
    :user      {:role "user" :content (content->wire (msg/content m))}
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
