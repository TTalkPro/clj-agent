(ns im.ttalk.agent.llm.core.types
  "LLM 核心类型定义（向后兼容层）

   此命名空间从 im.ttalk.agent.core.kernel.types re-export 核心类型。
   新代码建议直接使用 im.ttalk.agent.core.kernel.types。

   保留 LLM 模块特有的 provider 管理功能：
   - supported-providers
   - normalize-provider-name
   - valid-provider-name?

   使用示例：

   (require '[im.ttalk.agent.llm.core.types :as types])

   ;; 创建工具调用
   (types/make-tool-call \"call_123\" :calculator {:expression \"2+2\"})

   ;; 创建响应
   (types/make-response :text \"你好\" :tool-calls [])"
  (:require [im.ttalk.agent.core.kernel.types :as types]
            [clojure.string :as str]))

;;; ============================================================
;;; Re-export 工具调用类型
;;; ============================================================

(def make-tool-call types/make-tool-call)
(def tool-call? types/tool-call?)

;; 内联实现（core 模块已删除单行访问器，推荐直接用关键字访问）
(defn tool-call-id [tc] (:id tc))
(defn tool-call-name [tc] (:name tc))
(defn tool-call-input [tc] (:input tc))

;;; ============================================================
;;; Re-export 响应类型
;;; ============================================================

(def make-response types/make-response)
(def response? types/response?)

;; 内联实现（core 模块已删除单行访问器，推荐直接用关键字访问）
(defn response-text [resp] (:text resp ""))
(defn response-tool-calls [resp] (:tool-calls resp []))

(def has-text? types/has-text?)
(def has-tool-calls? types/has-tool-calls?)

;;; ============================================================
;;; Re-export 消息类型辅助
;;; ============================================================

(def user-message types/user-message)
(def assistant-message types/assistant-message)
(def system-message types/system-message)
(def tool-message types/tool-message)

;;; ============================================================
;;; Provider 名称（LLM 模块特有）
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
