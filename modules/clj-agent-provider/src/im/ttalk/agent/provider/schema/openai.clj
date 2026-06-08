(ns im.ttalk.agent.provider.schema.openai
  "OpenAI Schema 转换

   将工具定义转换为 OpenAI Function Call 格式。

   使用示例：

   (require '[im.ttalk.agent.provider.schema.openai :as schema])

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

   说明：
   - 兼容两种参数键名：OpenAI 风格 :parameters 与 Anthropic/deftool 风格
     :input_schema（deftool 宏生成的 schema 用后者）—— 二者均归一化到
     OpenAI function 的 :parameters，避免 OpenAI 路径下工具参数被静默丢成空对象。

   示例：
   (tool->schema {:name :calculator
                  :description \"执行数学计算\"
                  :parameters {:type \"object\"
                               :properties {:expr {:type \"string\"}}}})"
  [{:keys [name description parameters input_schema]}]
  {:type "function"
   :function {:name (if (keyword? name) (clojure.core/name name) name)
              :description description
              :parameters (or parameters input_schema {:type "object" :properties {}})}})

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

(defn wire-tool?
  "工具是否已是 wire 格式（带 :type 的预置/原生工具），无需转换

   覆盖 GLM 的 web_search / retrieval / mcp、OpenAI 原生 function wire 格式等。"
  [tool]
  (and (map? tool) (contains? tool :type)))

(defn tools->schemas
  "批量转换工具定义为 OpenAI schemas

   - 简单定义 {:name :description :parameters} -> 包装为 function 格式
   - 已带 :type 的 wire 格式（如 GLM {:type \"web_search\" ...}、
     {:type \"retrieval\" ...}、{:type \"mcp\" ...}）-> 原样透传

   参数：
   - tools: 工具定义列表

   返回：
   OpenAI schema 列表"
  [tools]
  (mapv (fn [t] (if (wire-tool? t) t (tool->schema t))) tools))

(defn schemas->tools
  "批量转换 OpenAI schemas 为工具定义

   参数：
   - schemas: OpenAI schema 列表

   返回：
   工具定义列表"
  [schemas]
  (mapv schema->tool schemas))
