(ns im.ttalk.agent.model.response
  "LLM 响应协议和类型定义

   定义 Core 工具调用循环所需的响应接口契约。
   第三方只需依赖 clj-agent-core 即可实现自定义响应类型。

   核心概念：
   - ILLMResponse: 响应协议，定义 Core 所需的方法
   - LLMResponse: 默认实现，使用 record
   - make-response: 工厂函数，创建规范化的响应

   使用示例：

   (require '[im.ttalk.agent.model.response :as resp])

   ;; 创建响应
   (def r (resp/make-response
            :text \"你好\"
            :tool-calls [{:id \"1\" :name :calc :input {:x 1}}]
            :usage {:input_tokens 100 :output_tokens 50}))

   ;; 使用协议方法
   (resp/response-text r)        ; => \"你好\"
   (resp/has-tool-calls? r)      ; => true
   (resp/response-tool-calls r)  ; => [{:id \"1\" ...}]"
  (:require [clojure.string :as str]))

;;; ============================================================
;;; Token 使用情况归一化
;;; ============================================================

(defn normalize-usage
  "归一化 Token 使用情况

   将 OpenAI/Anthropic 的不同命名统一为标准格式。

   参数：
   - usage: 原始 usage map（可能来自不同 Provider）

   返回：
   统一格式 {:input-tokens n :output-tokens m :total-tokens t}

   示例：
   ;; OpenAI 格式
   (normalize-usage {:prompt_tokens 100 :completion_tokens 50 :total_tokens 150})
   ; => {:input-tokens 100 :output-tokens 50 :total-tokens 150}

   ;; Anthropic 格式
   (normalize-usage {:input_tokens 100 :output_tokens 50})
   ; => {:input-tokens 100 :output-tokens 50 :total-tokens 150}"
  [usage]
  (when usage
    (let [;; 支持多种命名格式
          input (or (:input-tokens usage)
                    (:input_tokens usage)
                    (:prompt_tokens usage)
                    (:prompt-tokens usage)
                    0)
          output (or (:output-tokens usage)
                     (:output_tokens usage)
                     (:completion_tokens usage)
                     (:completion-tokens usage)
                     0)
          total (or (:total-tokens usage)
                    (:total_tokens usage)
                    (+ input output))]
      {:input-tokens input
       :output-tokens output
       :total-tokens total})))

;;; ============================================================
;;; 完成原因归一化
;;; ============================================================

(def ^:private finish-reason-mapping
  "完成原因映射表"
  {;; OpenAI 格式
   "stop"           :stop
   "tool_calls"     :tool-use
   "length"         :max-tokens
   "content_filter" :content-filter
   ;; Anthropic 格式
   "end_turn"       :stop
   "tool_use"       :tool-use
   "max_tokens"     :max-tokens
   "stop_sequence"  :stop
   ;; 关键字格式
   :stop            :stop
   :tool_calls      :tool-use
   :length          :max-tokens
   :content_filter  :content-filter
   :end_turn        :stop
   :tool_use        :tool-use
   :max_tokens      :max-tokens
   :stop_sequence   :stop})

(defn normalize-finish-reason
  "归一化完成原因

   将不同 Provider 的完成原因统一为标准关键字。

   参数：
   - reason: 原始完成原因（字符串或关键字）

   返回：
   标准化关键字 :stop | :tool-use | :max-tokens | :content-filter | :unknown

   示例：
   (normalize-finish-reason \"tool_calls\")  ; => :tool-use
   (normalize-finish-reason \"end_turn\")    ; => :stop
   (normalize-finish-reason :tool_use)       ; => :tool-use"
  [reason]
  (if (nil? reason)
    nil
    (get finish-reason-mapping reason :unknown)))

;;; ============================================================
;;; ILLMResponse 协议
;;; ============================================================

(defprotocol ILLMResponse
  "LLM 响应统一接口

   定义 Core 工具调用循环所需的契约。
   所有 LLM 服务返回的响应都应实现此协议。

   核心方法（Core 必需）：
   - response-text        获取文本内容
   - response-tool-calls  获取工具调用列表
   - has-tool-calls?      是否包含工具调用
   - response-assistant-msg 获取 assistant 消息（用于对话历史）

   扩展方法：
   - response-usage         Token 使用情况
   - response-finish-reason 完成原因
   - response-raw           原始响应"

  ;; 核心方法
  (response-text [this]
    "获取文本内容

     返回：字符串或 nil")

  (response-tool-calls [this]
    "获取工具调用列表

     返回：工具调用列表 [{:id \"...\" :name :keyword :input {...}}] 或 nil")

  (has-tool-calls? [this]
    "是否包含工具调用

     返回：boolean")

  (response-assistant-msg [this]
    "获取 assistant 消息（用于对话历史）

     返回：消息 map {:role \"assistant\" :content ...}")

  ;; 扩展方法
  (response-usage [this]
    "获取 Token 使用情况

     返回：{:input-tokens n :output-tokens m :total-tokens t}")

  (response-finish-reason [this]
    "获取完成原因

     返回：:stop | :tool-use | :max-tokens | :content-filter | :unknown")

  (response-raw [this]
    "获取原始 API 响应

     返回：原始响应数据")

  (response-id [this]
    "获取响应 ID

     返回：字符串或 nil")

  (response-model [this]
    "获取模型名称

     返回：字符串或 nil")

  (response-provider [this]
    "获取 Provider 类型

     返回：关键字 :openai | :anthropic | ..."))

;;; ============================================================
;;; LLMResponse Record
;;; ============================================================

(defrecord LLMResponse
  [text tool-calls assistant-msg usage finish-reason
   id model provider raw-response]

  ILLMResponse
  (response-text [_] text)
  (response-tool-calls [_] tool-calls)
  (has-tool-calls? [_] (boolean (seq tool-calls)))
  (response-assistant-msg [_] assistant-msg)
  (response-usage [_] usage)
  (response-finish-reason [_] finish-reason)
  (response-raw [_] raw-response)
  (response-id [_] id)
  (response-model [_] model)
  (response-provider [_] provider))

;;; ============================================================
;;; 工厂函数
;;; ============================================================

(defn make-response
  "创建 LLM 响应

   自动归一化 usage 和 finish-reason。

   参数（关键字参数）：
   - :text           文本内容
   - :tool-calls     工具调用列表
   - :assistant-msg  assistant 消息（用于对话历史）
   - :usage          Token 使用情况（自动归一化）
   - :finish-reason  完成原因（自动归一化）
   - :id             响应 ID
   - :model          模型名称
   - :provider       Provider 类型
   - :raw-response   原始响应

   返回：
   LLMResponse record

   示例：
   (make-response
     :text \"你好\"
     :tool-calls nil
     :assistant-msg {:role \"assistant\" :content \"你好\"}
     :usage {:prompt_tokens 100 :completion_tokens 50}
     :finish-reason \"stop\")"
  [& {:keys [text tool-calls assistant-msg usage finish-reason
             id model provider raw-response]}]
  (->LLMResponse
    text
    tool-calls
    assistant-msg
    (normalize-usage usage)
    (normalize-finish-reason finish-reason)
    id
    model
    provider
    raw-response))

;;; ============================================================
;;; 谓词函数
;;; ============================================================

(defn response?
  "检查是否为 ILLMResponse 实现

   参数：
   - x: 任意值

   返回：
   boolean"
  [x]
  (satisfies? ILLMResponse x))

(defn has-text?
  "检查响应是否包含非空文本

   参数：
   - resp: ILLMResponse 实例

   返回：
   boolean"
  [resp]
  (not (str/blank? (response-text resp))))

;;; ============================================================
;;; 辅助函数
;;; ============================================================

(defn get-input-tokens
  "获取输入 token 数量"
  [resp]
  (get (response-usage resp) :input-tokens 0))

(defn get-output-tokens
  "获取输出 token 数量"
  [resp]
  (get (response-usage resp) :output-tokens 0))

(defn get-total-tokens
  "获取总 token 数量"
  [resp]
  (get (response-usage resp) :total-tokens 0))

(defn stopped-by-tool-use?
  "检查是否因工具调用而停止"
  [resp]
  (= :tool-use (response-finish-reason resp)))

(defn stopped-by-max-tokens?
  "检查是否因达到最大 token 而停止"
  [resp]
  (= :max-tokens (response-finish-reason resp)))

(defn stopped-normally?
  "检查是否正常停止"
  [resp]
  (= :stop (response-finish-reason resp)))
