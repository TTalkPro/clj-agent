(ns im.ttalk.agent.converter.json-schema
  "JSON Schema 生成模块

   将 clj-agent 的 Schema 定义转换为标准 JSON Schema 格式，
   支持 OpenAI 和 Claude 的原生 Structured Output 功能。

   JSON Schema 规范: https://json-schema.org/

   使用示例：

   ;; 从 clj-agent schema 生成 JSON Schema
   (def schema {:name {:type :string :required true}
                :age {:type :number}}
   (to-json-schema schema \"Person\")

   ;; 生成 OpenAI response_format
   (to-openai-response-format schema \"Person\")"
  (:require [cheshire.core :as json]))

;; ============================================================
;; 类型映射
;; ============================================================

(def ^:private type-mapping
  "clj-agent 类型到 JSON Schema 类型的映射"
  {:string  "string"
   :number  "number"
   :integer "integer"
   :boolean "boolean"
   :array   "array"
   :object  "object"
   :any     {}})  ;; any 不指定类型

(defn- clj-type->json-type
  "转换 clj-agent 类型到 JSON Schema 类型

   参数:
   - clj-type: clj-agent 类型关键字

   返回: JSON Schema 类型字符串或 nil"
  [clj-type]
  (get type-mapping clj-type "string"))

;; ============================================================
;; Schema 属性生成
;; ============================================================

(defn- field-schema->json-schema
  "将单个字段 schema 转换为 JSON Schema 格式

   参数:
   - field-schema: 字段定义 {:type :string :description \"...\" :items {...}}

   返回: JSON Schema 属性定义"
  [{:keys [type description items enum default]}]
  (let [json-type (clj-type->json-type type)]
    (cond-> {}
      ;; 基础类型
      (and json-type (string? json-type))
      (assoc :type json-type)

      ;; 描述
      description
      (assoc :description description)

      ;; 数组元素类型
      (and (= type :array) items)
      (assoc :items (field-schema->json-schema items))

      ;; 枚举值
      enum
      (assoc :enum enum)

      ;; 默认值
      (some? default)
      (assoc :default default))))

(defn- collect-required-fields
  "收集必填字段列表

   参数:
   - schema: clj-agent schema 定义

   返回: 必填字段名列表"
  [schema]
  (->> schema
       (filter (fn [[_ field-def]]
                 (:required field-def)))
       (map (fn [[field-name _]]
              (name field-name)))
       vec))

(defn- schema->properties
  "将 clj-agent schema 转换为 JSON Schema properties

   参数:
   - schema: clj-agent schema 定义

   返回: JSON Schema properties map"
  [schema]
  (into {}
        (map (fn [[field-name field-def]]
               [(name field-name) (field-schema->json-schema field-def)])
             schema)))

;; ============================================================
;; JSON Schema 生成
;; ============================================================

(defn to-json-schema
  "将 clj-agent schema 转换为标准 JSON Schema

   参数:
   - schema: clj-agent schema 定义
   - name: Schema 名称（可选）
   - opts: 可选配置
     - :strict        是否严格模式（默认 true）
     - :additional-properties 是否允许额外属性（默认 false）

   返回: JSON Schema map

   示例:
   (to-json-schema
     {:name {:type :string :required true :description \"用户名\"}
      :age {:type :number}}
     \"Person\")
   ;; => {:type \"object\"
   ;;     :properties {:name {:type \"string\" :description \"用户名\"}
   ;;                  :age {:type \"number\"}}
   ;;     :required [\"name\"]
   ;;     :additionalProperties false}"
  ([schema]
   (to-json-schema schema nil {}))
  ([schema name]
   (to-json-schema schema name {}))
  ([schema name opts]
   (let [{:keys [strict additional-properties]
          :or {strict true additional-properties false}} opts
         properties (schema->properties schema)
         required (collect-required-fields schema)]
     (cond-> {:type "object"
              :properties properties}
       ;; 必填字段
       (seq required)
       (assoc :required required)

       ;; 严格模式：不允许额外属性
       strict
       (assoc :additionalProperties additional-properties)

       ;; Schema 名称（用于某些 Provider）
       name
       (assoc :title name)))))

;; ============================================================
;; OpenAI 格式
;; ============================================================

(defn to-openai-response-format
  "生成 OpenAI response_format 参数

   用于 OpenAI API 的 response_format.json_schema 格式。

   参数:
   - schema: clj-agent schema 定义
   - name: Schema 名称（必需）
   - opts: 可选配置
     - :strict 是否严格模式（默认 true）

   返回: OpenAI response_format map

   示例:
   (to-openai-response-format schema \"Person\")
   ;; => {:type \"json_schema\"
   ;;     :json_schema {:name \"Person\"
   ;;                   :strict true
   ;;                   :schema {...}}}"
  ([schema name]
   (to-openai-response-format schema name {}))
  ([schema name opts]
   (let [{:keys [strict] :or {strict true}} opts
         json-schema (to-json-schema schema name opts)]
     {:type "json_schema"
      :json_schema {:name name
                    :strict strict
                    :schema json-schema}})))

;; ============================================================
;; Claude 格式（使用 Tool）
;; ============================================================

(defn to-claude-tool-schema
  "生成 Claude Tool Schema（用于强制结构化输出）

   Claude 通过 tool_choice 强制使用工具来实现结构化输出。

   参数:
   - schema: clj-agent schema 定义
   - name: 工具名称
   - description: 工具描述

   返回: Claude tool 定义

   示例:
   (to-claude-tool-schema schema \"extract_info\" \"提取结构化信息\")"
  [schema name description]
  {:name name
   :description description
   :input_schema (to-json-schema schema nil {:strict true})})

(defn to-claude-tool-choice
  "生成 Claude tool_choice 参数（强制使用指定工具）

   参数:
   - tool-name: 工具名称

   返回: tool_choice 参数"
  [tool-name]
  {:type "tool"
   :name tool-name})

;; ============================================================
;; 通用 Provider 格式
;; ============================================================

(defn to-provider-format
  "根据 Provider 类型生成对应的结构化输出格式

   参数:
   - provider-type: Provider 类型 (:openai, :anthropic, :zhipu, :gemini)
   - schema: clj-agent schema 定义
   - name: Schema/Tool 名称
   - opts: 可选配置

   返回: Provider 特定的配置 map"
  [provider-type schema name & [opts]]
  (case provider-type
    :openai
    {:response-format (to-openai-response-format schema name opts)}

    :anthropic
    {:tools [(to-claude-tool-schema schema name (or (:description opts) "提取结构化数据"))]
     :tool-choice (to-claude-tool-choice name)}

    :zhipu
    ;; 智谱 AI 兼容 OpenAI 格式
    {:response-format (to-openai-response-format schema name opts)}

    :gemini
    ;; Gemini 使用 generationConfig.responseSchema
    {:generation-config {:response-mime-type "application/json"
                         :response-schema (to-json-schema schema name opts)}}

    ;; 默认：返回 JSON Schema
    {:json-schema (to-json-schema schema name opts)}))

;; ============================================================
;; Schema 验证
;; ============================================================

(defn validate-schema-definition
  "验证 clj-agent schema 定义是否有效

   参数:
   - schema: clj-agent schema 定义

   返回: {:valid true} 或 {:valid false :errors [...]}"
  [schema]
  (let [errors (atom [])]
    ;; 检查是否为 map
    (when-not (map? schema)
      (swap! errors conj "Schema 必须是 map"))

    ;; 检查每个字段
    (doseq [[field-name field-def] schema]
      (when-not (keyword? field-name)
        (swap! errors conj (str "字段名必须是关键字: " field-name)))

      (when-not (map? field-def)
        (swap! errors conj (str "字段定义必须是 map: " field-name)))

      (when (and (map? field-def) (:type field-def))
        (when-not (contains? type-mapping (:type field-def))
          (swap! errors conj (str "未知类型: " (:type field-def) " (字段: " field-name ")")))))

    (if (empty? @errors)
      {:valid true}
      {:valid false :errors @errors})))

;; ============================================================
;; JSON 序列化
;; ============================================================

(defn schema->json-string
  "将 schema 转换为 JSON 字符串

   参数:
   - schema: clj-agent schema 定义
   - name: Schema 名称
   - opts: 可选配置
     - :pretty 是否格式化输出

   返回: JSON 字符串"
  ([schema name]
   (schema->json-string schema name {}))
  ([schema name opts]
   (let [json-schema (to-json-schema schema name opts)]
     (if (:pretty opts)
       (json/generate-string json-schema {:pretty true})
       (json/generate-string json-schema)))))

;; ============================================================
;; 从 JSON Schema 反向转换
;; ============================================================

(defn- json-type->clj-type
  "将 JSON Schema 类型转换为 clj-agent 类型"
  [json-type]
  (case json-type
    "string" :string
    "number" :number
    "integer" :integer
    "boolean" :boolean
    "array" :array
    "object" :object
    :any))

(defn from-json-schema
  "从 JSON Schema 转换为 clj-agent schema

   参数:
   - json-schema: JSON Schema 定义

   返回: clj-agent schema"
  [json-schema]
  (let [properties (get json-schema :properties {})
        required-set (set (get json-schema :required []))]
    (into {}
          (map (fn [[prop-name prop-def]]
                 (let [field-name (keyword prop-name)]
                   [field-name
                    (cond-> {:type (json-type->clj-type (:type prop-def))}
                      (contains? required-set (name prop-name))
                      (assoc :required true)

                      (:description prop-def)
                      (assoc :description (:description prop-def))

                      (:enum prop-def)
                      (assoc :enum (:enum prop-def))

                      (:default prop-def)
                      (assoc :default (:default prop-def)))]))
               properties))))
