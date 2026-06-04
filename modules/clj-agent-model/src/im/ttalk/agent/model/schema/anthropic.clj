(ns im.ttalk.agent.model.schema.anthropic
  "Anthropic Schema 转换

   将工具定义转换为 Anthropic API 格式。

   Anthropic 的工具格式与 OpenAI 不同：
   - 使用 input_schema 而非 parameters
   - 工具调用使用 content_block 机制

   使用示例：

   (require '[im.ttalk.agent.model.schema.anthropic :as schema])

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
  "将工具定义转换为 Anthropic tool 格式

   参数：
   - tool: 工具定义 map
     {:name :keyword
      :description \"...\"
      :parameters {...}}

   返回：
   Anthropic 格式的 tool schema
   {:name \"...\" :description \"...\" :input_schema {...}}

   示例：
   (tool->schema {:name :calculator
                  :description \"执行数学计算\"
                  :parameters {:type \"object\"
                               :properties {:expr {:type \"string\"}}}})"
  [{:keys [name description parameters]}]
  {:name (if (keyword? name) (clojure.core/name name) name)
   :description description
   :input_schema (or parameters {:type "object" :properties {}})})

(defn schema->tool
  "将 Anthropic schema 转换为工具定义

   参数：
   - schema: Anthropic schema map

   返回：
   标准工具定义

   示例：
   (schema->tool {:name \"calculator\"
                  :description \"执行计算\"
                  :input_schema {...}})"
  [{:keys [name description input_schema]}]
  {:name (keyword name)
   :description description
   :parameters input_schema})

(defn tools->schemas
  "批量转换工具定义为 Anthropic schemas

   参数：
   - tools: 工具定义列表

   返回：
   Anthropic schema 列表"
  [tools]
  (mapv tool->schema tools))

(defn schemas->tools
  "批量转换 Anthropic schemas 为工具定义

   参数：
   - schemas: Anthropic schema 列表

   返回：
   工具定义列表"
  [schemas]
  (mapv schema->tool schemas))

