(ns im.ttalk.agent.converter.json
  "JSON 输出解析器实现

   提供 JSON 格式的 LLM 输出解析：
   - JsonOutputParser: 通用 JSON 解析
   - StructuredOutputParser: 带 Schema 验证的 JSON 解析
   - 原生 JSON Schema 支持（OpenAI/Claude Structured Output）

   使用示例：

   ;; 基础 JSON 解析
   (def parser (create-json-parser))
   (parse parser \"{\\\"name\\\": \\\"张三\\\"}\")

   ;; 带 Schema 的结构化解析
   (def schema {:name {:type :string :required true}
                :age {:type :number}})
   (def structured-parser (create-structured-parser schema))
   (parse structured-parser llm-output)

   ;; 获取原生 JSON Schema
   (to-json-schema structured-parser \"Person\")
   (to-response-format structured-parser :openai \"Person\")"
  (:require [im.ttalk.agent.converter.protocol :as proto]
            [im.ttalk.agent.converter.json-schema :as js]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; ============================================================
;; JSON 提取辅助函数
;; ============================================================

(defn- find-balanced-json
  "查找平衡的 JSON 字符串

   从起始字符 { 或 [ 开始，找到匹配的闭合字符。"
  [text start-char end-char]
  (when-let [start-idx (clojure.string/index-of text (str start-char))]
    (loop [idx (inc start-idx)
           depth 1]
      (if (>= idx (count text))
        nil
        (let [ch (nth text idx)]
          (cond
            (= ch end-char)
            (if (= depth 1)
              (subs text start-idx (inc idx))
              (recur (inc idx) (dec depth)))
            (= ch start-char)
            (recur (inc idx) (inc depth))
            :else
            (recur (inc idx) depth)))))))

(defn- extract-json-string
  "从文本中提取 JSON 字符串

   支持格式：
   - ```json {...} ```
   - ``` {...} ```
   - 纯 JSON {...}
   - 纯 JSON [...]"
  [text]
  (when text
    (let [;; 尝试匹配 Markdown 代码块
          markdown-match (or (re-find #"(?s)```json\s*(\{.*\})\s*```" text)
                             (re-find #"(?s)```json\s*(\[.*\])\s*```" text)
                             (re-find #"(?s)```\s*(\{.*\})\s*```" text)
                             (re-find #"(?s)```\s*(\[.*\])\s*```" text))]
      (if markdown-match
        (if (vector? markdown-match) (second markdown-match) markdown-match)
        ;; 尝试查找平衡的 JSON
        (or (find-balanced-json text \{ \})
            (find-balanced-json text \[ \]))))))

(defn- parse-json-safely
  "安全解析 JSON 字符串

   返回: {:success true :data ...} 或 {:success false :error ...}"
  [json-str]
  (try
    (proto/success (json/parse-string json-str true))
    (catch Exception e
      (proto/failure (str "JSON 解析失败: " (.getMessage e))))))

;; ============================================================
;; JsonOutputParser 实现
;; ============================================================

(defrecord JsonOutputParser [opts]
  proto/IOutputParser

  (parse [_ text]
    (if-let [json-str (extract-json-string text)]
      (parse-json-safely json-str)
      (proto/failure "未找到有效的 JSON 内容")))

  (format-instructions [_]
    (str "请以 JSON 格式返回结果。\n"
         "格式要求：\n"
         "- 返回有效的 JSON 对象或数组\n"
         "- 可以使用 Markdown 代码块包裹\n"
         "- 确保 JSON 语法正确\n\n"
         "示例：\n"
         "```json\n"
         "{\"key\": \"value\"}\n"
         "```")))

(defn create-json-parser
  "创建 JSON 输出解析器

   参数:
   - opts: 可选配置
     - :strict 是否严格模式（默认 false）

   返回: JsonOutputParser 实例

   示例:
   (def parser (create-json-parser))
   (parse parser llm-output)"
  ([] (create-json-parser {}))
  ([opts] (->JsonOutputParser opts)))

;; ============================================================
;; Schema 验证辅助函数
;; ============================================================

(defn- validate-type
  "验证值类型

   参数:
   - value: 要验证的值
   - expected-type: 期望类型 (:string, :number, :boolean, :array, :object)

   返回: boolean"
  [value expected-type]
  (case expected-type
    :string (string? value)
    :number (number? value)
    :boolean (boolean? value)
    :array (vector? value)
    :object (map? value)
    :any true
    true))

(defn- validate-field
  "验证单个字段

   参数:
   - data: 数据 map
   - field-name: 字段名
   - field-schema: 字段 schema {:type :string :required true}

   返回: 错误列表或空列表"
  [data field-name field-schema]
  (let [value (get data field-name)
        ;; 不要把 :type 解构成 `type`：会遮蔽 clojure.core/type，
        ;; 导致下面错误信息里的 (type value) 变成 (:string value) -> nil
        {field-type :type :keys [required]} field-schema]
    (cond
      ;; 检查必填字段
      (and required (nil? value))
      [(str "缺少必填字段: " (name field-name))]

      ;; 检查类型（仅当值存在时）
      (and value field-type (not (validate-type value field-type)))
      [(str "字段 " (name field-name) " 类型错误: 期望 " (name field-type)
            ", 实际 " (clojure.core/type value))]

      :else
      [])))

(defn- validate-schema
  "验证数据是否符合 schema

   参数:
   - data: 要验证的数据
   - schema: schema 定义

   返回: {:valid true} 或 {:valid false :errors [...]}"
  [data schema]
  (let [errors (mapcat (fn [[field-name field-schema]]
                         (validate-field data field-name field-schema))
                       schema)]
    (if (empty? errors)
      {:valid true}
      {:valid false :errors (vec errors)})))

(defn- schema->format-instructions
  "从 schema 生成格式说明

   参数:
   - schema: schema 定义

   返回: 格式说明字符串"
  [schema]
  (let [fields (map (fn [[field-name field-schema]]
                      (let [{:keys [type required description]} field-schema]
                        (str "  \"" (name field-name) "\": "
                             (or (name type) "any")
                             (when required " (必填)")
                             (when description (str " - " description)))))
                    schema)]
    (str "请以 JSON 格式返回结果，包含以下字段：\n\n"
         "```json\n{\n"
         (str/join ",\n" fields)
         "\n}\n```")))

;; ============================================================
;; StructuredOutputParser 实现
;; ============================================================

(defrecord StructuredOutputParser [schema opts]
  proto/IOutputParser

  (parse [_ text]
    (if-let [json-str (extract-json-string text)]
      (let [parse-result (parse-json-safely json-str)]
        (if (proto/success? parse-result)
          (let [data (proto/get-data parse-result)
                validation (validate-schema data schema)]
            (if (:valid validation)
              parse-result
              (proto/failure
                (str "Schema 验证失败: " (str/join ", " (:errors validation))))))
          parse-result))
      (proto/failure "未找到有效的 JSON 内容")))

  (format-instructions [_]
    (schema->format-instructions schema))

  proto/IStructuredParser

  (get-schema [_]
    schema)

  (validate [_ data]
    (validate-schema data schema))

  proto/INativeSchemaParser

  (to-json-schema [_]
    (js/to-json-schema schema))

  (to-json-schema [_ name]
    (js/to-json-schema schema name opts))

  (to-response-format [_ provider-type name]
    (js/to-provider-format provider-type schema name opts)))

(defn create-structured-parser
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
   (def schema {:name {:type :string :required true :description \"用户名\"}
                :age {:type :number}})
   (def parser (create-structured-parser schema))"
  ([schema] (create-structured-parser schema {}))
  ([schema opts] (->StructuredOutputParser schema opts)))

;; ============================================================
;; 预定义 Schema 构建器
;; ============================================================

(defn string-field
  "创建字符串字段 schema

   参数:
   - opts: 可选配置 {:required, :description}

   返回: 字段 schema"
  [& {:keys [required description] :or {required false}}]
  (cond-> {:type :string}
    required (assoc :required true)
    description (assoc :description description)))

(defn number-field
  "创建数字字段 schema"
  [& {:keys [required description] :or {required false}}]
  (cond-> {:type :number}
    required (assoc :required true)
    description (assoc :description description)))

(defn boolean-field
  "创建布尔字段 schema"
  [& {:keys [required description] :or {required false}}]
  (cond-> {:type :boolean}
    required (assoc :required true)
    description (assoc :description description)))

(defn array-field
  "创建数组字段 schema"
  [& {:keys [required description] :or {required false}}]
  (cond-> {:type :array}
    required (assoc :required true)
    description (assoc :description description)))

(defn object-field
  "创建对象字段 schema"
  [& {:keys [required description] :or {required false}}]
  (cond-> {:type :object}
    required (assoc :required true)
    description (assoc :description description)))

;; ============================================================
;; 便捷工厂函数
;; ============================================================

(defn from-response-schema
  "从响应字段列表创建 StructuredOutputParser

   参数:
   - fields: 字段定义列表
     [{:name \"field1\" :type :string :required true :description \"...\"}]

   返回: StructuredOutputParser 实例

   示例:
   (from-response-schema
     [{:name \"summary\" :type :string :required true}
      {:name \"keywords\" :type :array}])"
  [fields]
  (let [schema (into {}
                     (map (fn [{:keys [name type required description]}]
                            [(keyword name)
                             (cond-> {:type (or type :any)}
                               required (assoc :required true)
                               description (assoc :description description))])
                          fields))]
    (create-structured-parser schema)))
