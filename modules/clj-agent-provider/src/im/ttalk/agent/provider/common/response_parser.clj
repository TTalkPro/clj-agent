(ns im.ttalk.agent.provider.common.response-parser
  "响应解析模块

   提供统一的 LLM API 响应解析功能，支持 OpenAI 格式的响应。
   将 OpenAI 原始响应转换为统一的内部格式。

   使用示例：

   (require '[im.ttalk.agent.provider.common.response-parser :as parser])

   ;; 提取工具调用
   (parser/extract-tool-calls response)

   ;; 提取文本
   (parser/extract-text response)

   ;; 标准化响应为统一格式
   (parser/normalize-response response)"
  (:require [cheshire.core :as json]
            [im.ttalk.agent.model.types :as types]
            [im.ttalk.agent.model.response :as response]))

;;; ============================================================
;;; 响应访问器
;;; ============================================================

(defn get-message
  "获取响应中的消息

   参数：
   - response: API 响应

   返回：
   消息 map {:role \"...\" :content \"...\" :tool_calls [...]}"
  [response]
  (get-in response [:choices 0 :message]))

(defn get-finish-reason
  "获取完成原因

   参数：
   - response: API 响应

   返回：
   字符串 (\"stop\", \"length\", \"tool_calls\", \"content_filter\")"
  [response]
  (get-in response [:choices 0 :finish_reason]))

(defn get-usage
  "获取 token 使用情况

   参数：
   - response: API 响应

   返回：
   usage map {:prompt_tokens n :completion_tokens m :total_tokens t}"
  [response]
  (:usage response))

;;; ============================================================
;;; 响应解析
;;; ============================================================

(defn extract-tool-calls
  "从响应中提取工具调用

   参数：
   - response: API 响应

   返回：
   工具调用列表 [{:id \"...\" :name :keyword :input {...}}] 或 nil

   示例：
   (extract-tool-calls response)
   ; => [{:id \"call_123\" :name :calculator :input {:expression \"2+2\"}}]"
  [response]
  (when-let [tool-calls (:tool_calls (get-message response))]
    (mapv (fn [tc]
            (let [args (try
                         (json/parse-string (get-in tc [:function :arguments]) true)
                         (catch Exception _ {}))]
              (types/make-tool-call
                (:id tc)
                (get-in tc [:function :name])
                args)))
          tool-calls)))

(defn extract-text
  "从响应中提取文本内容

   参数：
   - response: API 响应

   返回：
   字符串（空字符串如果无内容）"
  [response]
  (or (:content (get-message response)) ""))

(defn has-tool-calls?
  "检查响应是否包含工具调用

   参数：
   - response: API 响应

   返回：
   boolean"
  [response]
  (boolean (seq (extract-tool-calls response))))

(defn valid-response?
  "检查响应是否有效

   参数：
   - response: API 响应

   返回：
   boolean"
  [response]
  (and (map? response)
       (contains? response :choices)
       (sequential? (:choices response))
       (first (:choices response))))

;;; ============================================================
;;; 响应标准化
;;; ============================================================

(defn normalize-response
  "将原始 OpenAI API 响应标准化为统一格式

   参数：
   - response: 原始 OpenAI API 响应

   返回：
   统一响应格式：
   {:text \"...\"
    :tool-calls [{:id :name :input}]
    :usage {:input-tokens n :output-tokens m :total-tokens t}
    :finish-reason :stop | :tool-use | :max-tokens | ...
    :model \"...\"
    :id \"...\"
    :provider :openai
    :raw-response {...}}

   示例：
   (normalize-response raw-response)
   ; => {:text \"你好\"
   ;     :tool-calls []
   ;     :usage {:input-tokens 100 :output-tokens 50 :total-tokens 150}
   ;     :finish-reason :stop
   ;     :provider :openai}"
  [raw-response]
  (response/make-response
    :id (:id raw-response)
    :model (:model raw-response)
    :text (extract-text raw-response)
    ;; 推理内容（deepseek-reasoner 等返回 message.reasoning_content）
    :reasoning (response/extract-reasoning raw-response)
    :tool-calls (let [tc (extract-tool-calls raw-response)]
                  (when (seq tc) tc))
    :usage (get-usage raw-response)
    :finish-reason (get-finish-reason raw-response)
    :provider :openai
    :raw-response raw-response))

;;; ============================================================
;;; 调试辅助
;;; ============================================================

(defn format-response-summary
  "格式化响应摘要（用于调试）

   参数：
   - response: API 响应

   返回：
   格式化的字符串"
  [response]
  (let [finish-reason (get-finish-reason response)
        tool-calls (extract-tool-calls response)
        tool-count (count tool-calls)
        text-length (count (extract-text response))]
    (str "Finish: " (or finish-reason "unknown")
         ", Tools: " tool-count
         ", Text: " text-length " chars")))

(defn debug-response
  "打印响应调试信息

   参数：
   - provider-name: Provider 名称字符串
   - response:      API 响应
   - verbose:       是否详细信息（默认 false）"
  ([provider-name response]
   (debug-response provider-name response false))
  ([provider-name response verbose]
   (println (str "[" provider-name "]"))
   (println "  Summary:" (format-response-summary response))
   (when verbose
     (println "  Text:" (subs (extract-text response) 0 (min 100 (count (extract-text response)))))
     (when-let [tool-calls (extract-tool-calls response)]
       (println "  Tool calls:" (mapv :name tool-calls)))
     (when-let [usage (get-usage response)]
       (println "  Usage:" usage)))))
