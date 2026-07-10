(ns im.ttalk.agent.model.types
  "LLM 核心类型定义

   定义 LLM 交互使用的基础数据类型：
   - ToolCall: 工具调用结构
   - 消息构建辅助函数

   响应相关类型请参见 response.clj：
   - ILLMResponse: 响应协议
   - LLMResponse: 响应 record
   - make-response: 工厂函数

   使用示例：

   (require '[im.ttalk.agent.model.types :as types])

   ;; 创建工具调用
   (types/make-tool-call \"call_123\" :calculator {:expression \"2+2\"})

   ;; 创建消息
   (types/user-message \"你好\")
   (types/assistant-message \"你好！\")"
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

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
