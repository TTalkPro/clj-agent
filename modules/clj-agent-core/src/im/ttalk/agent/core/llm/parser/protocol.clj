(ns im.ttalk.agent.core.llm.parser.protocol
  "输出解析器协议 - 定义 LLM 输出解析的统一接口

   提供类似 LangChain OutputParser 的功能：
   - 统一的解析接口
   - 格式说明生成
   - 解析失败处理

   使用示例：

   (def parser (json-parser))

   ;; 获取格式说明（嵌入到提示词）
   (format-instructions parser)

   ;; 解析 LLM 输出
   (parse parser llm-output)")

;; ============================================================
;; 输出解析器协议
;; ============================================================

(defprotocol IOutputParser
  "输出解析器协议 - 定义 LLM 输出解析的统一接口

   所有输出解析器必须实现此协议。

   必需方法：
   - parse: 解析 LLM 输出
   - format-instructions: 生成格式说明

   可选方法：
   - parser-type: 返回解析器类型
   - with-retry: 返回带重试功能的解析器"

  (parse
    [this text]
    "解析 LLM 输出文本

     参数:
     - text: LLM 输出的原始文本

     返回: {:success true :data ...} 或 {:success false :error ...}

     示例:
     (parse json-parser \"{\\\"name\\\": \\\"张三\\\"}\")
     ; => {:success true :data {:name \"张三\"}}")

  (format-instructions
    [this]
    "生成格式说明

     返回: 字符串，描述期望的输出格式

     用于嵌入到提示词中，指导 LLM 生成正确格式的输出。

     示例:
     (format-instructions json-parser)
     ; => \"请以 JSON 格式返回结果，格式如下：\\n{...}\""))

;; ============================================================
;; 可选协议扩展
;; ============================================================

(defprotocol IRetryableParser
  "可重试解析器协议 - 支持自动修复解析失败

   当初始解析失败时，使用 LLM 修复输出。"

  (parse-with-retry
    [this text llm-provider config]
    "使用重试逻辑解析

     参数:
     - text: LLM 输出的原始文本
     - llm-provider: LLM 提供商实例
     - config: LLM 配置

     返回: {:success true :data ...} 或 {:success false :error ...}

     失败时会调用 LLM 修复输出并重试。")

  (get-retry-prompt
    [this text error]
    "生成重试提示词

     参数:
     - text: 原始输出文本
     - error: 解析错误信息

     返回: 修复提示词字符串"))

;; ============================================================
;; 结构化输出协议
;; ============================================================

(defprotocol IStructuredParser
  "结构化输出解析器协议 - 支持 Schema 定义

   使用 Schema 定义期望的输出结构。"

  (get-schema
    [this]
    "获取输出 Schema

     返回: Schema 定义 map")

  (validate
    [this data]
    "验证数据是否符合 Schema

     参数:
     - data: 要验证的数据

     返回: {:valid true} 或 {:valid false :errors [...]}"))

;; ============================================================
;; 原生 JSON Schema 协议
;; ============================================================

(defprotocol INativeSchemaParser
  "原生 JSON Schema 解析器协议 - 支持 Provider 原生结构化输出

   用于生成 OpenAI/Claude 等 Provider 的原生 JSON Schema，
   实现更可靠的结构化输出。"

  (to-json-schema
    [this]
    [this name]
    "生成标准 JSON Schema

     参数:
     - name: Schema 名称（可选）

     返回: JSON Schema map

     示例:
     (to-json-schema parser \"Person\")
     ; => {:type \"object\"
     ;     :properties {...}
     ;     :required [...]}")

  (to-response-format
    [this provider-type name]
    "生成 Provider 特定的结构化输出格式

     参数:
     - provider-type: Provider 类型 (:openai, :anthropic, :zhipu, :gemini)
     - name: Schema/Tool 名称

     返回: Provider 特定的配置 map

     示例:
     ;; OpenAI
     (to-response-format parser :openai \"Person\")
     ; => {:response-format {:type \"json_schema\" :json_schema {...}}}

     ;; Claude
     (to-response-format parser :anthropic \"extract_person\")
     ; => {:tools [...] :tool-choice {:type \"tool\" :name \"extract_person\"}}"))

;; ============================================================
;; 解析结果辅助函数
;; ============================================================

(defn success
  "创建成功的解析结果

   参数:
   - data: 解析后的数据

   返回: {:success true :data data}"
  [data]
  {:success true :data data})

(defn failure
  "创建失败的解析结果

   参数:
   - error: 错误信息

   返回: {:success false :error error}"
  [error]
  {:success false :error error})

(defn success?
  "检查解析结果是否成功

   参数:
   - result: 解析结果

   返回: boolean"
  [result]
  (true? (:success result)))

(defn get-data
  "从解析结果中获取数据

   参数:
   - result: 解析结果

   返回: 数据或 nil"
  [result]
  (:data result))

(defn get-error
  "从解析结果中获取错误信息

   参数:
   - result: 解析结果

   返回: 错误字符串或 nil"
  [result]
  (:error result))
