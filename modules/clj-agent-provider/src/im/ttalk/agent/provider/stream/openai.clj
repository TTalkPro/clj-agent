(ns im.ttalk.agent.provider.stream.openai
  "OpenAI 流式响应处理

   处理 OpenAI API 的 SSE 流式响应，累积文本和工具调用。
   构建的响应格式与非流式调用兼容，并支持统一响应转换。

   使用示例：

   (require '[im.ttalk.agent.provider.stream.openai :as stream])

   ;; 处理流式响应块
   (let [[new-state token-data] (stream/process-chunk chunk state)]
     (when token-data
       (print (:token token-data))))

   ;; 构建最终响应（支持统一格式）
   (stream/build-response final-state :id \"xxx\" :model \"gpt-4\")"
  (:require [cheshire.core :as json]
            [taoensso.timbre :as log]
            [im.ttalk.agent.model.types :as types]
            [im.ttalk.agent.model.response :as response]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 状态管理
;;; ============================================================

;; 累积器用 StringBuilder 而非字符串拼接：逐 token `(str acc t)` 对长度 n 的回复
;; 是 O(n²)（每个 token 重建整段），长回复下 CPU/GC 二次膨胀。SSE 行由
;; Flow.Subscriber 串行投递（onNext 不并发、state 走 reset! 无重试），原地 append 安全。

(defn make-initial-state
  "创建流式处理的初始状态

   返回：
   状态 map {:accumulated StringBuilder :index 0 :tool-calls-acc {} :role nil}"
  []
  {:accumulated (StringBuilder.)
   :reasoning-accumulated (StringBuilder.)
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
          ;; 不要静默吞掉：流中途截断 / 半截 JSON 时会无声丢内容（最终响应"看似正常但缺内容"）。
          ;; 记 warn 便于排障；返回 nil 让上层跳过该行。
          (catch Exception e
            (log/warn "OpenAI SSE 行 JSON 解析失败，已跳过该行"
                      {:data-preview (subs data-str 0 (min 200 (count data-str)))
                       :error (.getMessage e)})
            nil))))))

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
   {:token \"...\" :index n}

   示例：
   (let [[new-state token] (process-chunk chunk state)]
     (when token
       (print (:token token))))"
  [chunk state]
  (let [choice (first (:choices chunk))
        delta (:delta choice)
        ;; 捕获末块 usage（DeepSeek 原生返回 / OpenAI stream_options.include_usage）
        ;; 与 finish_reason（length/sensitive 等真实停止原因）；role 提前合并。
        state (cond-> state
                (:usage chunk)           (assoc :usage (:usage chunk))
                (:finish_reason choice)  (assoc :finish-reason (:finish_reason choice))
                (:role delta)            (assoc :role (:role delta)))
        ;; 工具调用增量：始终独立累积——即使同一 chunk 同时携带 content，也不丢工具调用。
        ;; :arguments 用 StringBuilder 原地 append（长 JSON 参数同样避免 O(n²)），
        ;; build/normalize 时再物化为字符串。
        state (if-let [tool-calls (:tool_calls delta)]
                (assoc state :tool-calls-acc
                       (reduce
                         (fn [acc tc]
                           ;; 累积 key 优先用 :index；部分网关 / 旧版 Qwen / GLM 增量不带 index，
                           ;; 退回 :id，再退回当前序号——避免多工具碎片全部并到 nil 一个 key 下串味。
                           (let [idx (or (:index tc) (:id tc) (count acc))
                                 existing (get acc idx {:id nil
                                                        :function {:name ""
                                                                   :arguments (StringBuilder.)}})
                                 ^StringBuilder args-sb (get-in existing [:function :arguments])]
                             (when-let [a (get-in tc [:function :arguments])]
                               (.append args-sb ^String a))
                             (assoc acc idx
                                    {:id (or (:id tc) (:id existing))
                                     :type "function"
                                     :function
                                     {:name (str (get-in existing [:function :name])
                                                 (get-in tc [:function :name] ""))
                                      :arguments args-sb}})))
                         (:tool-calls-acc state)
                         tool-calls))
                state)]
    (cond
      ;; 推理增量（deepseek-reasoner 等：reasoning_content 在 content 之前流式返回）
      ;; 单独 emit :reasoning-token，不混入 :token，保持答案流干净
      (:reasoning_content delta)
      (let [rtext (:reasoning_content delta)]
        (.append ^StringBuilder (:reasoning-accumulated state) ^String rtext)
        [state
         {:reasoning-token rtext
          :reasoning? true}])

      ;; 文本增量
      (:content delta)
      (let [text (:content delta)
            new-index (inc (:index state))]
        (.append ^StringBuilder (:accumulated state) ^String text)
        [(assoc state :index new-index)
         {:token text
          :index new-index}])

      ;; 仅工具调用 / 仅角色 / 其他：状态已在上面更新，无 token 下发
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
                          ;; 物化累积期的 StringBuilder → String
                          (mapv #(update-in (second %) [:function :arguments] str))))
        reasoning (str (:reasoning-accumulated state))]
    (cond-> {:id id
             :model model
             :choices [{:message (cond-> {:role (or (:role state) "assistant")
                                          :content (str (:accumulated state))}
                                   (seq reasoning)   (assoc :reasoning_content reasoning)
                                   (seq tool-calls)  (assoc :tool_calls tool-calls))
                        ;; 优先用流中捕获的真实 finish_reason
                        :finish_reason (or (:finish-reason state)
                                           (if (seq tool-calls) "tool_calls" "stop"))}]}
      (:usage state) (assoc :usage (:usage state)))))

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
        ;; 转换为统一的 tool-call 格式（:arguments 累积期为 StringBuilder，str 物化）
        tool-calls (when (seq tool-calls-raw)
                     (mapv (fn [tc]
                             (let [args (try
                                          (json/parse-string
                                            (str (get-in tc [:function :arguments])) true)
                                          (catch Exception _ {}))]
                               (types/make-tool-call
                                 (:id tc)
                                 (get-in tc [:function :name])
                                 args)))
                           tool-calls-raw))]
    (response/make-response
      :id id
      :model model
      :text (let [text (str (:accumulated state))]
              (when (seq text) text))
      :reasoning (let [r (str (:reasoning-accumulated state))]
                   (when (seq r) r))
      :tool-calls tool-calls
      :finish-reason (or (:finish-reason state)
                         (if (seq tool-calls) "tool_calls" "stop"))
      :usage (or usage (:usage state))
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
