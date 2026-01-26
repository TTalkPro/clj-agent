(ns im.ttalk.agent.llm.stream.anthropic
  "Anthropic 流式响应处理

   处理 Anthropic API 的 SSE 流式响应。
   构建的响应格式与非流式调用兼容，并支持统一响应转换。

   Anthropic 的流式事件类型：
   - message_start:       消息开始
   - content_block_start: 内容块开始
   - content_block_delta: 内容块增量（文本或工具输入）
   - content_block_stop:  内容块结束
   - message_delta:       消息增量
   - message_stop:        消息结束

   使用示例：

   (require '[im.ttalk.agent.llm.stream.anthropic :as stream])

   ;; 处理流式响应块
   (let [[new-state token-data] (stream/process-event event state)]
     (when token-data
       (print (:token token-data))))

   ;; 构建统一格式响应
   (stream/normalize-response final-state)"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [im.ttalk.agent.core.kernel.types :as types]
            [im.ttalk.agent.core.llm.response :as response]))

;;; ============================================================
;;; 状态管理
;;; ============================================================

(defn make-initial-state
  "创建流式处理的初始状态

   返回：
   状态 map {:accumulated \"\" :index 0 :content-blocks {} :message nil}"
  []
  {:accumulated ""
   :index 0
   :content-blocks {}
   :message nil})

;;; ============================================================
;;; SSE 解析
;;; ============================================================

(defn parse-sse-line
  "解析 SSE 数据行

   参数：
   - line: SSE 行字符串（如 \"data: {...}\"）

   返回：
   解析后的 JSON 对象或 nil

   示例：
   (parse-sse-line \"data: {\\\"type\\\": \\\"message_start\\\"}\")
   ; => {:type \"message_start\" ...}"
  [line]
  (when (and line (str/starts-with? line "data: "))
    (let [data-str (subs line 6)]
      (when-not (= data-str "[DONE]")
        (try
          (json/parse-string data-str true)
          (catch Exception _ nil))))))

;;; ============================================================
;;; 流式处理
;;; ============================================================

(defn process-event
  "处理流式事件

   参数：
   - event: 解析后的 SSE 事件（JSON 对象）
   - state: 当前累积状态

   返回：
   [更新后的状态, token-data 或 nil]

   token-data 格式：
   {:token \"...\" :index n :accumulated \"...\"}

   示例：
   (let [[new-state token] (process-event event state)]
     (when token
       (print (:token token))))"
  [event state]
  (let [event-type (:type event)]
    (case event-type
      ;; 消息开始 - 保存消息元数据
      "message_start"
      [(assoc state :message (:message event)) nil]

      ;; 内容块开始 - 记录块信息
      "content_block_start"
      (let [block (:content_block event)
            block-index (:index event)]
        [(update state :content-blocks assoc block-index block) nil])

      ;; 内容块增量更新 - 处理文本流
      "content_block_delta"
      (let [delta (:delta event)
            delta-type (:type delta)]
        (cond
          ;; 文本增量
          (= delta-type "text_delta")
          (let [text (:text delta)
                new-accumulated (str (:accumulated state) text)
                new-index (inc (:index state))]
            [(assoc state
                    :accumulated new-accumulated
                    :index new-index)
             {:token text
              :index new-index
              :accumulated new-accumulated}])

          ;; 工具输入增量（JSON 片段）
          (= delta-type "input_json_delta")
          (let [block-index (:index event)
                partial-json (:partial_json delta)
                existing (get-in state [:content-blocks block-index :input] "")]
            [(assoc-in state [:content-blocks block-index :input]
                       (str existing partial-json))
             nil])

          ;; 其他增量类型
          :else
          [state nil]))

      ;; 内容块结束 - 解析工具输入
      "content_block_stop"
      (let [block-index (:index event)
            block (get-in state [:content-blocks block-index])]
        (if (and (= (:type block) "tool_use")
                 (string? (:input block)))
          ;; 解析累积的 JSON 字符串
          (let [parsed-input (try
                               (json/parse-string (:input block) true)
                               (catch Exception _ {}))]
            [(assoc-in state [:content-blocks block-index :input] parsed-input)
             nil])
          [state nil]))

      ;; 消息增量更新
      "message_delta"
      [(update state :message merge (:delta event)) nil]

      ;; 消息结束
      "message_stop"
      [state nil]

      ;; 其他事件类型
      [state nil])))

;;; ============================================================
;;; 响应构建
;;; ============================================================

(defn build-response
  "从流式状态构建最终响应（Anthropic 原始格式）

   参数：
   - state: 最终累积状态

   返回：
   与非流式调用兼容的 Anthropic 响应格式

   示例：
   (build-response final-state)
   ; => {:id \"...\" :content [...] :stop_reason \"end_turn\" ...}"
  [state]
  (let [message (:message state)
        accumulated (:accumulated state)
        content-blocks (:content-blocks state)]
    ;; 构建与同步调用返回格式兼容的响应
    (-> message
        (assoc :content
               (if (seq content-blocks)
                 ;; 有内容块时，按索引排序后返回
                 (->> content-blocks
                      (sort-by first)
                      (mapv second))
                 ;; 只有累积文本时，构造文本块
                 [{:type "text" :text accumulated}])))))

(defn- extract-text-from-blocks
  "从内容块中提取文本"
  [content-blocks accumulated]
  (if (seq content-blocks)
    (->> content-blocks
         vals
         (filter #(= (:type %) "text"))
         (map :text)
         (str/join "\n"))
    accumulated))

(defn- extract-tool-calls-from-blocks
  "从内容块中提取工具调用"
  [content-blocks]
  (when (seq content-blocks)
    (->> content-blocks
         vals
         (filter #(= (:type %) "tool_use"))
         (mapv (fn [{:keys [id name input]}]
                 (types/make-tool-call id name input))))))

(defn normalize-response
  "从流式状态构建统一格式响应

   参数：
   - state: 最终累积状态

   返回：
   统一响应格式：
   {:text \"...\"
    :tool-calls [{:id :name :input}]
    :usage {:input-tokens n :output-tokens m :total-tokens t}
    :finish-reason :stop | :tool-use | :max-tokens
    :provider :anthropic
    ...}

   示例：
   (normalize-response final-state)
   ; => {:text \"你好\"
   ;     :tool-calls []
   ;     :finish-reason :stop
   ;     :provider :anthropic}"
  [state]
  (let [message (:message state)
        accumulated (:accumulated state)
        content-blocks (:content-blocks state)
        text (extract-text-from-blocks content-blocks accumulated)
        tool-calls (extract-tool-calls-from-blocks content-blocks)]
    (response/make-response
      :id (:id message)
      :model (:model message)
      :text (when (seq text) text)
      :tool-calls (when (seq tool-calls) tool-calls)
      :usage (:usage message)
      :finish-reason (:stop_reason message)
      :provider :anthropic)))

;;; ============================================================
;;; 流处理器工厂
;;; ============================================================

(defn make-stream-processor
  "创建流处理器

   返回：
   处理器 map，包含：
   - :process-fn  处理函数 (fn [event state] -> [new-state token-data])
   - :initial-state 初始状态

   示例：
   (let [{:keys [process-fn initial-state]} (make-stream-processor)]
     ;; 处理流
     (let [final-state (reduce ...)]
       (build-response final-state)))"
  []
  {:process-fn process-event
   :initial-state (make-initial-state)})

