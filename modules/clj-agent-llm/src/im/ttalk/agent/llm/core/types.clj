(ns im.ttalk.agent.llm.core.types
  "LLM 核心类型定义

   定义 LLM 模块使用的基础数据类型：
   - ToolCall: 工具调用结构
   - Response: 统一响应结构
   - 辅助构造函数

   使用示例：

   (require '[im.ttalk.agent.llm.core.types :as types])

   ;; 创建工具调用
   (types/make-tool-call \"call_123\" :calculator {:expression \"2+2\"})

   ;; 创建响应
   (types/make-response :text \"你好\" :tool-calls [])"
  (:require [clojure.string :as str]))

;;; ============================================================
;;; 工具调用类型
;;; ============================================================

(defn make-tool-call
  "创建统一的工具调用结构

   参数：
   - id: 工具调用 ID（字符串）
   - name: 工具名称（关键字或字符串）
   - input: 输入参数（map）

   返回：
   工具调用 map {:id \"...\" :name :keyword :input {...}}

   示例：
   (make-tool-call \"call_123\" :calculator {:expression \"2+2\"})
   ; => {:id \"call_123\" :name :calculator :input {:expression \"2+2\"}}"
  [id name input]
  {:id id
   :name (if (keyword? name) name (keyword name))
   :input (or input {})})

(defn tool-call?
  "检查是否为有效的工具调用

   参数：
   - x: 任意值

   返回：
   boolean"
  [x]
  (and (map? x)
       (contains? x :id)
       (contains? x :name)
       (contains? x :input)))

(defn tool-call-id
  "获取工具调用 ID

   参数：
   - tc: 工具调用 map

   返回：
   字符串"
  [tc]
  (:id tc))

(defn tool-call-name
  "获取工具名称

   参数：
   - tc: 工具调用 map

   返回：
   关键字"
  [tc]
  (:name tc))

(defn tool-call-input
  "获取工具输入参数

   参数：
   - tc: 工具调用 map

   返回：
   map"
  [tc]
  (:input tc))

;;; ============================================================
;;; 响应类型
;;; ============================================================

(defn make-response
  "创建统一的响应结构

   参数（关键字参数）：
   - :text         文本内容（字符串）
   - :tool-calls   工具调用列表
   - :raw-response 原始响应（可选）
   - :usage        token 使用情况（可选）
   - :model        模型名称（可选）
   - :finish-reason 完成原因（可选）

   返回：
   响应 map

   示例：
   (make-response :text \"你好\" :tool-calls [])
   ; => {:text \"你好\" :tool-calls [] :raw-response nil}"
  [& {:keys [text tool-calls raw-response usage model finish-reason]}]
  (cond-> {:text (or text "")
           :tool-calls (or tool-calls [])}
    raw-response    (assoc :raw-response raw-response)
    usage           (assoc :usage usage)
    model           (assoc :model model)
    finish-reason   (assoc :finish-reason finish-reason)))

(defn response?
  "检查是否为有效的响应结构

   参数：
   - x: 任意值

   返回：
   boolean"
  [x]
  (and (map? x)
       (contains? x :text)
       (contains? x :tool-calls)))

(defn response-text
  "获取响应文本

   参数：
   - resp: 响应 map

   返回：
   字符串"
  [resp]
  (:text resp ""))

(defn response-tool-calls
  "获取响应中的工具调用

   参数：
   - resp: 响应 map

   返回：
   工具调用列表"
  [resp]
  (:tool-calls resp []))

(defn has-text?
  "检查响应是否包含文本

   参数：
   - resp: 响应 map

   返回：
   boolean"
  [resp]
  (not (str/blank? (response-text resp))))

(defn has-tool-calls?
  "检查响应是否包含工具调用

   参数：
   - resp: 响应 map

   返回：
   boolean"
  [resp]
  (seq (response-tool-calls resp)))

;;; ============================================================
;;; Provider 名称
;;; ============================================================

(def supported-providers
  "支持的 LLM 提供商列表"
  #{:anthropic :openai :zhipu :gemini :mistral :ollama})

(defn normalize-provider-name
  "标准化提供商名称

   参数：
   - name: 提供商名称（字符串或关键字）

   返回：
   关键字

   示例：
   (normalize-provider-name \"Claude\") ; => :anthropic
   (normalize-provider-name :openai)   ; => :openai"
  [name]
  (if (keyword? name)
    name
    (-> name
        str/lower-case
        (str/replace #" " "-")
        keyword)))

(defn valid-provider-name?
  "检查提供商名称是否有效

   参数：
   - name: 提供商名称

   返回：
   boolean

   示例：
   (valid-provider-name? :anthropic)   ; => true
   (valid-provider-name? :unknown)  ; => false"
  [name]
  (contains? supported-providers (normalize-provider-name name)))

;;; ============================================================
;;; 消息类型辅助
;;; ============================================================

(defn user-message
  "创建用户消息

   参数：
   - content: 消息内容

   返回：
   消息 map"
  [content]
  {:role "user" :content content})

(defn assistant-message
  "创建助手消息

   参数：
   - content: 消息内容
   - tool-calls: 工具调用列表（可选）

   返回：
   消息 map"
  ([content]
   {:role "assistant" :content content})
  ([content tool-calls]
   (cond-> {:role "assistant" :content content}
     (seq tool-calls) (assoc :tool_calls tool-calls))))

(defn system-message
  "创建系统消息

   参数：
   - content: 系统提示词

   返回：
   消息 map"
  [content]
  {:role "system" :content content})

(defn tool-message
  "创建工具结果消息

   参数：
   - tool-id: 工具调用 ID
   - content: 工具执行结果

   返回：
   消息 map"
  [tool-id content]
  {:role "tool"
   :tool_call_id tool-id
   :content (if (string? content) content (pr-str content))})
