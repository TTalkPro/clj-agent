(ns im.ttalk.agent.converter.api
  "输出解析器 API - 统一入口

   提供 LLM 输出解析的完整功能：
   - JsonOutputParser: 通用 JSON 解析
   - StructuredOutputParser: 带 Schema 验证的结构化解析
   - RetryOutputParser: 自动重试/修复解析
   - 原生 JSON Schema: OpenAI/Claude 结构化输出

   使用示例：

   (require '[im.ttalk.agent.converter.api :as parser])

   ;; 基础 JSON 解析
   (def p (parser/json-parser))
   (parser/parse p llm-output)

   ;; 结构化解析
   (def schema {:name (parser/string-field :required true)
                :age (parser/number-field)})
   (def p (parser/structured-parser schema))
   (parser/parse p llm-output)

   ;; 带重试的解析
   (def retry-parser (parser/with-retry p))
   (parser/parse-with-llm retry-parser text llm-provider config)

   ;; 原生 JSON Schema（推荐用于 OpenAI/Claude）
   (def p (parser/structured-parser schema))
   (parser/to-json-schema p \"Person\")
   (parser/to-response-format p :openai \"Person\")"
  (:require [im.ttalk.agent.converter.protocol :as proto]
            [im.ttalk.agent.converter.json :as json-impl]
            [im.ttalk.agent.converter.json-schema :as js]
            [im.ttalk.agent.converter.retry :as retry-impl]))

;; ============================================================
;; 协议和结果处理（重新导出）
;; ============================================================

(def success proto/success)
(def failure proto/failure)
(def success? proto/success?)
(def get-data proto/get-data)
(def get-error proto/get-error)

;; ============================================================
;; 解析器创建
;; ============================================================

(defn json-parser
  "创建 JSON 输出解析器

   参数:
   - opts: 可选配置

   返回: JsonOutputParser 实例

   示例:
   (def parser (json-parser))
   (parse parser \"{\\\"key\\\": \\\"value\\\"}\")"
  ([] (json-impl/create-json-parser))
  ([opts] (json-impl/create-json-parser opts)))

(defn structured-parser
  "创建结构化输出解析器

   参数:
   - schema: 输出 schema 定义
   - opts: 可选配置

   Schema 格式:
   {:field-name {:type :string/:number/:boolean/:array/:object
                 :required true/false
                 :description \"字段描述\"}}

   返回: StructuredOutputParser 实例

   示例:
   (def schema {:name {:type :string :required true}
                :age {:type :number}})
   (def parser (structured-parser schema))"
  ([schema] (json-impl/create-structured-parser schema))
  ([schema opts] (json-impl/create-structured-parser schema opts)))

(defn from-fields
  "从字段定义列表创建结构化解析器

   参数:
   - fields: 字段定义列表

   返回: StructuredOutputParser 实例

   示例:
   (from-fields
     [{:name \"summary\" :type :string :required true}
      {:name \"keywords\" :type :array}])"
  [fields]
  (json-impl/from-response-schema fields))

;; ============================================================
;; Schema 字段构建器
;; ============================================================

(def string-field json-impl/string-field)
(def number-field json-impl/number-field)
(def boolean-field json-impl/boolean-field)
(def array-field json-impl/array-field)
(def object-field json-impl/object-field)

;; ============================================================
;; 重试功能
;; ============================================================

(defn with-retry
  "为解析器添加重试功能

   参数:
   - parser: IOutputParser 实现
   - opts: 重试配置
     - :max-retries  最大重试次数（默认 3）
     - :fix-mode     修复模式 :fix 或 :retry（默认 :fix）
     - :verbose      是否输出调试信息

   返回: RetryOutputParser 实例"
  ([parser] (retry-impl/with-retry parser))
  ([parser opts] (retry-impl/with-retry parser opts)))

;; ============================================================
;; 解析函数
;; ============================================================

(defn parse
  "解析 LLM 输出

   参数:
   - parser: IOutputParser 实现
   - text: 要解析的文本

   返回: {:success true :data ...} 或 {:success false :error ...}"
  [parser text]
  (proto/parse parser text))

(defn format-instructions
  "获取格式说明

   参数:
   - parser: IOutputParser 实现

   返回: 格式说明字符串"
  [parser]
  (proto/format-instructions parser))

(defn parse-with-llm
  "使用 LLM 辅助解析（支持重试）

   参数:
   - parser: IOutputParser 实现
   - text: 要解析的文本
   - llm-provider: LLM 提供商
   - config: LLM 配置

   返回: 解析结果"
  ([parser text]
   (retry-impl/parse-with-llm parser text))
  ([parser text llm-provider config]
   (retry-impl/parse-with-llm parser text llm-provider config)))

;; ============================================================
;; Schema 验证
;; ============================================================

(defn validate
  "验证数据是否符合解析器的 Schema

   参数:
   - parser: IStructuredParser 实现
   - data: 要验证的数据

   返回: {:valid true} 或 {:valid false :errors [...]}"
  [parser data]
  (if (satisfies? proto/IStructuredParser parser)
    (proto/validate parser data)
    {:valid true}))

(defn get-schema
  "获取解析器的 Schema

   参数:
   - parser: IStructuredParser 实现

   返回: Schema 定义 map 或 nil"
  [parser]
  (when (satisfies? proto/IStructuredParser parser)
    (proto/get-schema parser)))

;; ============================================================
;; 便捷宏
;; ============================================================

(defmacro defparser
  "定义一个解析器

   示例:
   (defparser person-parser
     {:name (string-field :required true)
      :age (number-field)
      :email (string-field :description \"电子邮件\")})"
  [name schema]
  `(def ~name (structured-parser ~schema)))

;; ============================================================
;; 原生 JSON Schema 支持
;; ============================================================

(defn to-json-schema
  "获取标准 JSON Schema

   参数:
   - parser: INativeSchemaParser 实现（StructuredOutputParser）
   - name: Schema 名称（可选）

   返回: JSON Schema map

   示例:
   (to-json-schema parser \"Person\")
   ; => {:type \"object\"
   ;     :properties {:name {:type \"string\"} ...}
   ;     :required [\"name\"]}"
  ([parser]
   (if (satisfies? proto/INativeSchemaParser parser)
     (proto/to-json-schema parser)
     (throw (ex-info "解析器不支持 JSON Schema" {:parser (type parser)}))))
  ([parser name]
   (if (satisfies? proto/INativeSchemaParser parser)
     (proto/to-json-schema parser name)
     (throw (ex-info "解析器不支持 JSON Schema" {:parser (type parser)})))))

(defn to-response-format
  "获取 Provider 特定的结构化输出格式

   参数:
   - parser: INativeSchemaParser 实现（StructuredOutputParser）
   - provider-type: Provider 类型 (:openai, :anthropic, :zhipu, :gemini)
   - name: Schema/Tool 名称

   返回: Provider 特定的配置 map

   示例:
   ;; OpenAI - 返回 response_format 配置
   (to-response-format parser :openai \"Person\")
   ; => {:response-format {:type \"json_schema\" :json_schema {...}}}

   ;; Claude - 返回 tools + tool_choice 配置
   (to-response-format parser :anthropic \"extract_person\")
   ; => {:tools [...] :tool-choice {:type \"tool\" :name \"extract_person\"}}"
  [parser provider-type name]
  (if (satisfies? proto/INativeSchemaParser parser)
    (proto/to-response-format parser provider-type name)
    (throw (ex-info "解析器不支持 Provider 格式" {:parser (type parser)}))))

(defn build-llm-config
  "从解析器构建 LLM 调用配置

   将结构化输出格式合并到 LLM 配置中，便于直接调用 LLM。

   参数:
   - parser: INativeSchemaParser 实现
   - provider-type: Provider 类型 (:openai, :anthropic, :zhipu, :gemini)
   - name: Schema/Tool 名称
   - base-config: 基础 LLM 配置 {:model \"...\" :max-tokens ...}

   返回: 合并了结构化输出的完整配置

   示例:
   (def config (build-llm-config parser :openai \"Person\"
                                 {:model \"gpt-4o\" :max-tokens 1000}))
   (llm/call-llm provider config messages [])"
  [parser provider-type name base-config]
  (let [format-config (to-response-format parser provider-type name)]
    (merge base-config format-config)))

;; ============================================================
;; 直接 Schema 操作（无需解析器）
;; ============================================================

(defn schema->json-schema
  "从 clj-agent schema 直接生成 JSON Schema

   参数:
   - schema: clj-agent schema 定义
   - name: Schema 名称（可选）
   - opts: 可选配置 {:strict true :additional-properties false}

   返回: JSON Schema map

   示例:
   (schema->json-schema
     {:name {:type :string :required true}
      :age {:type :number}}
     \"Person\")"
  ([schema]
   (js/to-json-schema schema))
  ([schema name]
   (js/to-json-schema schema name))
  ([schema name opts]
   (js/to-json-schema schema name opts)))

(defn schema->provider-format
  "从 clj-agent schema 直接生成 Provider 格式

   参数:
   - provider-type: Provider 类型
   - schema: clj-agent schema 定义
   - name: Schema/Tool 名称
   - opts: 可选配置

   返回: Provider 特定的配置 map

   示例:
   (schema->provider-format :openai
     {:name {:type :string :required true}}
     \"Person\")"
  ([provider-type schema name]
   (js/to-provider-format provider-type schema name))
  ([provider-type schema name opts]
   (js/to-provider-format provider-type schema name opts)))

(defn validate-schema-definition
  "验证 schema 定义是否有效

   参数:
   - schema: clj-agent schema 定义

   返回: {:valid true} 或 {:valid false :errors [...]}"
  [schema]
  (js/validate-schema-definition schema))
