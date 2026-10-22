(ns im.ttalk.agent.provider.wire.openai-responses
  "中立消息 ↔ OpenAI Responses API wire 格式转换。

  OpenAI Responses 与 Chat Completions 的差别在协议形状，不在语义：
  system -> instructions；消息 -> input items；工具定义扁平化；
  function_call / function_call_output 是顶层 item。"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [im.ttalk.agent.model.content :as content]
            [im.ttalk.agent.model.error :as errors]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.provider.schema.openai :as schema]))

(set! *warn-on-reflection* true)

(def replay-format
  "Responses 原始 output items 的回放载荷标识。"
  :openai-responses-output)

(defn- unsupported!
  [part]
  (errors/throw!
    (errors/error :validation-error
                  (str "OpenAI Responses 协议不支持该内容部件（"
                       (or (:media-type part) (:type part)) "）")
                  {:provider :openai-responses})))

(defn- part->wire
  [{:keys [url filename] :as part}]
  (cond
    (content/text-part? part)
    {:type "input_text" :text (:text part)}

    (content/image? part)
    {:type "input_image"
     :image_url (or url (content/data-uri part))}

    (= "application/pdf" (:media-type part))
    (if url
      (unsupported! part)
      {:type "input_file"
       :filename (or filename "document.pdf")
       :file_data (content/data-uri part)})

    :else (unsupported! part)))

(defn- content->wire
  [c]
  (cond
    (string? c) c
    (sequential? c) (mapv (fn [part]
                            (if (string? part)
                              {:type "input_text" :text part}
                              (part->wire part)))
                          c)
    (nil? c) nil
    :else c))

(defn- parse-args
  [args]
  (cond
    (map? args) args
    (string? args) (try (json/parse-string args true)
                        (catch Exception _ {}))
    :else {}))

(defn- tool-call->wire
  [{:keys [id name args]}]
  {:type "function_call"
   :call_id (str id)
   :name (str name)
   :arguments (json/generate-string (or args {}))})

(defn- assistant-items
  "把 assistant 中立消息转为 Responses input items。

  若带有本协议产生的 :blocks，则优先原样回放；这能保留 reasoning item 的
  id / encrypted_content / signature 等不透明字段。"
  [m]
  (let [{:keys [format data]} (msg/blocks m)]
    (if (= replay-format format)
      (mapv #(dissoc % :status) (vec data))
      (cond-> []
        (seq (msg/content m))
        (conj {:type "message"
               :role "assistant"
               :content (if (sequential? (msg/content m))
                          (content->wire (msg/content m))
                          [{:type "input_text" :text (str (msg/content m))}])})
        (msg/has-tool-calls? m)
        (into (mapv tool-call->wire (msg/tool-calls m)))))))

(defn- msg->items
  [m]
  (case (msg/role m)
    :system    nil
    :user      [{:type "message"
                 :role "user"
                 :content (content->wire (msg/content m))}]
    :assistant (assistant-items m)
    :tool      [{:type "function_call_output"
                 :call_id (str (msg/tool-call-id m))
                 :output (if (string? (msg/content m))
                           (msg/content m)
                           (pr-str (msg/content m)))}]
    ;; 未知 role：按 message 尽力透传，不让历史静默消失。
    [{:type "message"
      :role (name (msg/role m))
      :content (content->wire (msg/content m))}]))

(defn neutral->wire
  "中立消息 -> {:instructions ... :input ...}。

  config :system-prompt 在前，历史 system 消息在后；两者都存在时合并。"
  [system-prompt neutral-msgs]
  (let [from-history (->> neutral-msgs
                          (map msg/normalize)
                          (remove msg/system?)
                          (mapcat msg->items)
                          (vec))
        history-system (->> neutral-msgs
                            (map msg/normalize)
                            (filter msg/system?)
                            (map (comp content/text-of msg/content))
                            (remove str/blank?)
                            (str/join "\n\n"))
        instructions (str/join "\n\n" (remove str/blank? [(str system-prompt) history-system]))]
    (cond-> {:input (vec from-history)}
      (seq instructions) (assoc :instructions instructions))))

(defn tool->wire
  "把 OpenAI Chat 工具 schema / 已带 :type 的原生工具转成 Responses 扁平工具。"
  [tool]
  (cond
    (and (map? tool) (= "function" (:type tool)) (map? (:function tool)))
    (let [f (:function tool)]
      (cond-> {:type "function"
               :name (:name f)
               :parameters (or (:parameters f) {:type "object" :properties {}})}
        (:description f) (assoc :description (:description f))
        (contains? f :strict) (assoc :strict (:strict f))))

    (map? tool) tool
    :else (tool->wire (schema/tool->schema tool))))

(defn tools->wire
  [tools]
  (mapv tool->wire tools))

(defn wire-tool-call
  "Responses function_call item -> Chat Completions 形态的 tool_calls item。"
  [{:keys [call_id id name arguments] :as _item}]
  {:id (or call_id id)
   :type "function"
   :function {:name name
              :arguments (if (string? arguments)
                           arguments
                           (json/generate-string (or arguments {})))}})

(defn output-items
  "读取 Responses 响应中的 output items（兼容 output 为单个 map 的旧实现）。"
  [response]
  (let [output (:output response)]
    (cond
      (sequential? output) (vec output)
      (map? output) [output]
      :else [])))

(defn extract-text
  [response]
  (->> (output-items response)
       (filter #(= "message" (:type %)))
       (mapcat :content)
       (filter #(= "output_text" (:type %)))
       (map :text)
       (remove nil?)
       (str/join "\n")))

(defn extract-reasoning
  [response]
  (->> (output-items response)
       (filter #(= "reasoning" (:type %)))
       (mapcat (fn [item]
                 (concat
                  (map :text (:summary item))
                  (map :text (:content item)))))
       (remove nil?)
       (str/join "\n")))

(defn extract-tool-calls
  [response]
  (->> (output-items response)
       (filter #(= "function_call" (:type %)))
       (mapv (fn [item]
               (msg/tool-call (or (:call_id item) (:id item))
                              (:name item)
                              (parse-args (:arguments item)))))))

(defn replay-blocks
  [response]
  ;; 只回放 function_call：reasoning item 常带网关/模型专属的不透明字段，
  ;; 且部分兼容实现不接受回放；message 内容已由中立 assistant 消息保存。
  (when-let [items (->> (output-items response)
                        (filter #(= "function_call" (:type %)))
                        seq)]
    {:provider :openai-responses
     :format replay-format
     :data (vec items)}))
