(ns im.ttalk.agent.llm.schema.openai
  "OpenAI Schema 转换

   将工具定义转换为 OpenAI Function Call 格式。

   使用示例：

   (require '[im.ttalk.agent.llm.schema.openai :as schema])

   ;; 转换单个工具
   (schema/tool->schema {:name :calculator
                         :description \"计算器\"
                         :parameters {...}})

   ;; 批量转换
   (schema/tools->schemas tools)")

;;; ============================================================
;;; Schema 转换
;;; ============================================================

(defn tool->schema
  "将工具定义转换为 OpenAI function 格式

   参数：
   - tool: 工具定义 map
     {:name :keyword
      :description \"...\"
      :parameters {...}}

   返回：
   OpenAI 格式的 function schema
   {:type \"function\"
    :function {:name \"...\" :description \"...\" :parameters {...}}}

   示例：
   (tool->schema {:name :calculator
                  :description \"执行数学计算\"
                  :parameters {:type \"object\"
                               :properties {:expr {:type \"string\"}}}})"
  [{:keys [name description parameters]}]
  {:type "function"
   :function {:name (if (keyword? name) (clojure.core/name name) name)
              :description description
              :parameters (or parameters {:type "object" :properties {}})}})

(defn schema->tool
  "将 OpenAI function schema 转换为工具定义

   参数：
   - schema: OpenAI schema map

   返回：
   标准工具定义

   示例：
   (schema->tool {:type \"function\"
                  :function {:name \"calculator\" ...}})"
  [{:keys [function]}]
  {:name (keyword (:name function))
   :description (:description function)
   :parameters (:parameters function)})

(defn tools->schemas
  "批量转换工具定义为 OpenAI schemas

   参数：
   - tools: 工具定义列表

   返回：
   OpenAI schema 列表"
  [tools]
  (mapv tool->schema tools))

(defn schemas->tools
  "批量转换 OpenAI schemas 为工具定义

   参数：
   - schemas: OpenAI schema 列表

   返回：
   工具定义列表"
  [schemas]
  (mapv schema->tool schemas))
