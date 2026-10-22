(ns im.ttalk.agent.provider.stream.openai-responses
  "OpenAI Responses SSE 流式处理。

  流式只负责实时发射 delta；最终响应优先使用 response.completed 携带的完整
  对象，避免自行重建 reasoning/tool call 的不透明字段。"
  (:require [im.ttalk.agent.provider.stream.openai :as openai-stream]))

(set! *warn-on-reflection* true)

(defn make-initial-state
  []
  {:text (StringBuilder.)
   :reasoning (StringBuilder.)
   :tool-calls {}
   :completed nil})

(def parse-sse-line
  "Responses 与 Chat Completions 的 SSE data 行格式相同。"
  openai-stream/parse-sse-line)

(defn- append!
  [^StringBuilder sb s]
  (when (some? s) (.append sb (str s)))
  sb)

(defn process-event
  [event state]
  (let [type (:type event)
        state (cond-> state (:response event) (assoc :completed (:response event)))]
    (cond
      (= "response.output_text.delta" type)
      (let [s (str (:delta event))
            state' (update state :text append! s)]
        [state' {:token s}])

      (contains? #{"response.reasoning_text.delta"
                   "response.reasoning_summary_text.delta"} type)
      (let [s (str (:delta event))
            state' (update state :reasoning append! s)]
        [state' {:reasoning-token s :reasoning? true}])

      (= "response.output_item.added" type)
      (let [item (:item event)]
        (if (= "function_call" (:type item))
          (let [idx (or (:output_index event) (count (:tool-calls state)))
                existing (get (:tool-calls state) idx)
                args-sb (or (get existing :arguments-sb) (StringBuilder.))
                state' (assoc-in state [:tool-calls idx]
                                 {:id (or (:call_id item) (:id item))
                                  :name (:name item)
                                  :arguments-sb args-sb})]
            [state' nil])
          [state nil]))

      (= "response.function_call_arguments.delta" type)
      (let [idx (:output_index event)
            existing (get (:tool-calls state) idx)
            args-sb (or (get existing :arguments-sb) (StringBuilder.))
            s (str (:delta event))]
        (.append ^StringBuilder args-sb s)
        [(assoc-in state [:tool-calls idx :arguments-sb] args-sb) nil])

      :else [state nil])))

(defn build-response
  "优先返回 completed 原始响应；无 completed 时从 delta 累积出兼容响应。"
  [state]
  (or (:completed state)
      (let [tool-calls (->> (:tool-calls state)
                            (sort-by first)
                            (mapv (fn [[idx tc]]
                                    {:type "function_call"
                                     :call_id (:id tc)
                                     :name (:name tc)
                                     :arguments (str (get tc :arguments-sb ""))
                                     :output_index idx})))
            text (str (:text state))
            reasoning (str (:reasoning state))
            items (cond-> []
                    (seq reasoning) (conj {:type "reasoning"
                                           :content [{:type "reasoning_text" :text reasoning}]})
                    (seq text) (conj {:type "message"
                                      :content [{:type "output_text" :text text}]})
                    (seq tool-calls) (into tool-calls))]
        {:id nil :model nil
         :status (if (seq tool-calls) "in_progress" "completed")
         :output items})))

(defn make-stream-processor
  []
  {:parse-fn parse-sse-line
   :process-fn process-event
   :make-initial-state make-initial-state
   :build-response build-response})
