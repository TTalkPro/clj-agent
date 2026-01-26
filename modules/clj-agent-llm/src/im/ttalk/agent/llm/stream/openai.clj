(ns im.ttalk.agent.llm.stream.openai
  "OpenAI 流式响应处理

   处理 OpenAI API 的 SSE 流式响应，累积文本和工具调用。
   构建的响应格式与非流式调用兼容，并支持统一响应转换。

   使用示例：

   (require '[im.ttalk.agent.llm.stream.openai :as stream])

   ;; 处理流式响应块
   (let [[new-state token-data] (stream/process-chunk chunk state)]
     (when token-data
       (print (:token token-data))))

   ;; 构建最终响应（支持统一格式）
   (stream/build-response final-state :id \"xxx\" :model \"gpt-4\")"
  (:require [cheshire.core :as json]
            [im.ttalk.agent.core.kernel.types :as types]
            [im.ttalk.agent.core.llm.response :as response]))

;;; ============================================================
;;; 状态管理
;;; ============================================================

(defn make-initial-state
  "创建流式处理的初始状态

   返回：
   状态 map {:accumulated \"\" :index 0 :tool-calls-acc {} :role nil}"
  []
  {:accumulated ""
   :index 0
   :tool-calls-acc {}
   :role nil})

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
   (parse-sse-line \"data: {\\\"id\\\": \\\"123\\\"}\")
   ; => {:id \"123\"}"
  [line]
  (when (and line (.startsWith ^String line "data: "))
    (let [data-str (subs line 6)]
      (when-not (= data-str "[DONE]")
        (try
          (json/parse-string data-str true)
          (catch Exception _ nil))))))

;;; ============================================================
;;; 流式处理
;;; ============================================================

(defn process-chunk
  "处理流式响应块

   参数：
   - chunk: 解析后的 SSE 块（JSON 对象）
   - state: 当前累积状态

   返回：
   [更新后的状态, token-data 或 nil]

   token-data 格式：
   {:token \"...\" :index n :accumulated \"...\"}

   示例：
   (let [[new-state token] (process-chunk chunk state)]
     (when token
       (print (:token token))))"
  [chunk state]
  (let [choice (first (:choices chunk))
        delta (:delta choice)]
    (cond
      ;; 文本增量
      (:content delta)
      (let [text (:content delta)
            new-accumulated (str (:accumulated state) text)
            new-index (inc (:index state))]
        [(assoc state
                :accumulated new-accumulated
                :index new-index
                :role (or (:role delta) (:role state)))
         {:token text
          :index new-index
          :accumulated new-accumulated}])

      ;; 工具调用增量
      (:tool_calls delta)
      (let [tool-calls (:tool_calls delta)
            new-acc (reduce
                      (fn [acc tc]
                        (let [idx (:index tc)
                              existing (get acc idx {:id nil
                                                      :function {:name "" :arguments ""}})]
                          (assoc acc idx
                                 {:id (or (:id tc) (:id existing))
                                  :type "function"
                                  :function
                                  {:name (str (get-in existing [:function :name])
                                              (get-in tc [:function :name] ""))
                                   :arguments (str (get-in existing [:function :arguments])
                                                   (get-in tc [:function :arguments] ""))}})))
                      (:tool-calls-acc state)
                      tool-calls)]
        [(assoc state
                :tool-calls-acc new-acc
                :role (or (:role delta) (:role state)))
         nil])

      ;; 角色信息
      (:role delta)
      [(assoc state :role (:role delta)) nil]

      ;; 其他情况
      :else
      [state nil])))

;;; ============================================================
;;; 响应构建
;;; ============================================================

(defn build-response
  "从流式状态构建最终响应（OpenAI 原始格式）

   参数：
   - state: 最终累积状态
   - opts:  可选参数
     - :id    响应 ID
     - :model 模型名称

   返回：
   与非流式调用兼容的 OpenAI 响应格式

   示例：
   (build-response final-state :id \"chatcmpl-xxx\" :model \"gpt-4\")"
  [state & {:keys [id model]}]
  (let [tool-calls (when (seq (:tool-calls-acc state))
                     (->> (:tool-calls-acc state)
                          (sort-by first)
                          (mapv second)))]
    {:id id
     :model model
     :choices [{:message (cond-> {:role (or (:role state) "assistant")
                                  :content (:accumulated state)}
                           (seq tool-calls) (assoc :tool_calls tool-calls))
                :finish_reason (if (seq tool-calls) "tool_calls" "stop")}]}))

(defn normalize-response
  "从流式状态构建统一格式响应

   参数：
   - state: 最终累积状态
   - opts:  可选参数
     - :id    响应 ID
     - :model 模型名称
     - :usage 使用情况（流式响应通常没有 usage）

   返回：
   统一响应格式：
   {:text \"...\"
    :tool-calls [{:id :name :input}]
    :finish-reason :stop | :tool-use
    :provider :openai
    ...}

   示例：
   (normalize-response final-state :id \"chatcmpl-xxx\" :model \"gpt-4\")"
  [state & {:keys [id model usage]}]
  (let [tool-calls-raw (when (seq (:tool-calls-acc state))
                         (->> (:tool-calls-acc state)
                              (sort-by first)
                              (mapv second)))
        ;; 转换为统一的 tool-call 格式
        tool-calls (when (seq tool-calls-raw)
                     (mapv (fn [tc]
                             (let [args (try
                                          (json/parse-string
                                            (get-in tc [:function :arguments]) true)
                                          (catch Exception _ {}))]
                               (types/make-tool-call
                                 (:id tc)
                                 (get-in tc [:function :name])
                                 args)))
                           tool-calls-raw))]
    (response/make-response
      :id id
      :model model
      :text (let [text (:accumulated state)]
              (when (seq text) text))
      :tool-calls tool-calls
      :finish-reason (if (seq tool-calls) "tool_calls" "stop")
      :usage usage
      :provider :openai)))

;;; ============================================================
;;; 流处理器工厂
;;; ============================================================

(defn make-stream-processor
  "创建流处理器

   返回：
   处理器 map，包含：
   - :process-fn   处理函数 (fn [chunk state] -> [new-state token-data])
   - :get-id       获取响应 ID 的函数
   - :get-model    获取模型名称的函数

   示例：
   (let [{:keys [process-fn get-id get-model]} (make-stream-processor)]
     ;; 处理流
     (let [final-state (reduce ...)]
       (build-response final-state :id (get-id) :model (get-model))))"
  []
  (let [response-id (atom nil)
        response-model (atom nil)]
    {:process-fn (fn [chunk state]
                   (when (:id chunk)
                     (reset! response-id (:id chunk)))
                   (when (:model chunk)
                     (reset! response-model (:model chunk)))
                   (process-chunk chunk state))
     :get-id #(deref response-id)
     :get-model #(deref response-model)}))
