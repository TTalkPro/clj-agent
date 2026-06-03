(ns im.ttalk.agent.core.llm.prompt.api
  "提示词模板 API - 统一入口

   提供提示词模板的完整功能：
   - PromptTemplate: 变量替换模板
   - FewShotPromptTemplate: 少样本学习模板
   - ChatPromptTemplate: 多轮对话模板
   - Example 选择器: 动态示例选择

   使用示例：

   (require '[im.ttalk.agent.core.llm.prompt.api :as prompt])

   ;; 基础模板
   (def template (prompt/template \"你好，{name}！\"))
   (prompt/render template {:name \"张三\"})

   ;; 聊天模板
   (def chat (prompt/chat-template
               [(prompt/system \"你是一个翻译助手\")
                (prompt/human \"{input}\")]))
   (prompt/format-messages chat {:input \"Hello\"})

   ;; 少样本模板
   (def few-shot (prompt/few-shot-template
                   {:prefix \"翻译以下文本：\"
                    :examples [{:input \"Hello\" :output \"你好\"}]
                    :suffix \"输入：{input}\\n输出：\"}))"
  (:require [im.ttalk.agent.core.llm.prompt.protocol :as proto]
            [im.ttalk.agent.core.llm.prompt.template :as tpl]
            [im.ttalk.agent.core.llm.prompt.chat :as chat]
            [im.ttalk.agent.core.llm.prompt.selector :as sel]))

;; ============================================================
;; 协议函数（重新导出）
;; ============================================================

(def format-prompt proto/format-prompt)
(def get-input-variables proto/get-input-variables)
(def extract-variables proto/extract-variables)
(def validate-variables proto/validate-variables)
(def format-safe proto/format-safe)

;; ============================================================
;; PromptTemplate
;; ============================================================

(defn template
  "创建提示词模板

   参数:
   - template-str: 模板字符串，使用 {variable} 格式
   - opts: 可选配置

   返回: PromptTemplate 实例

   示例:
   (def t (template \"你好，{name}！\"))
   (format t {:name \"张三\"})"
  ([template-str]
   (tpl/create-prompt-template template-str))
  ([template-str opts]
   (tpl/create-prompt-template template-str opts)))

(defn from-file
  "从文件加载模板

   参数:
   - path: 文件路径

   返回: PromptTemplate 实例"
  [path]
  (tpl/from-file path))

;; ============================================================
;; FewShotPromptTemplate
;; ============================================================

(defn few-shot-template
  "创建少样本提示词模板

   参数:
   - opts: 配置选项
     - :prefix           前缀说明
     - :suffix           后缀模板
     - :examples         示例列表
     - :example-template 示例模板（可选）
     - :example-separator 示例分隔符

   返回: FewShotPromptTemplate 实例

   示例:
   (few-shot-template
     {:prefix \"翻译以下文本：\"
      :suffix \"输入：{input}\\n输出：\"
      :examples [{:input \"Hello\" :output \"你好\"}]})"
  [opts]
  (tpl/create-few-shot-template opts))

(defn dynamic-few-shot-template
  "创建动态少样本模板

   使用 Example 选择器动态选择示例。

   参数:
   - opts: 配置选项
     - :prefix           前缀说明
     - :suffix           后缀模板
     - :example-selector Example 选择器

   返回: DynamicFewShotTemplate 实例"
  [opts]
  (tpl/create-dynamic-few-shot-template opts))

;; ============================================================
;; ChatPromptTemplate
;; ============================================================

(defn chat-template
  "创建聊天提示词模板

   参数:
   - message-templates: 消息模板列表

   返回: ChatPromptTemplate 实例

   示例:
   (chat-template
     [(system \"你是一个助手\")
      (human \"{input}\")])"
  ([message-templates]
   (chat/create-chat-template message-templates))
  ([message-templates opts]
   (chat/create-chat-template message-templates opts)))

(defn from-messages
  "从消息描述创建聊天模板

   参数:
   - messages: 消息描述列表
     [[\"system\" \"...\"] [\"user\" \"{input}\"]]

   返回: ChatPromptTemplate 实例"
  [messages]
  (chat/from-messages messages))

;; ============================================================
;; 消息构建器
;; ============================================================

(def system chat/system-message)
(def human chat/human-message)
(def ai chat/ai-message)
(def messages-placeholder chat/messages-placeholder)

;; ============================================================
;; 格式化函数
;; ============================================================

(defn render
  "渲染模板（格式化）

   参数:
   - template: IPromptTemplate 实现
   - variables: 变量 map

   返回: 格式化后的字符串"
  [template variables]
  (proto/format-prompt template variables))

(defn format-messages
  "格式化聊天模板为消息列表

   参数:
   - chat-template: ChatPromptTemplate
   - variables: 变量 map

   返回: 消息列表 [{:role \"...\" :content \"...\"}]"
  [chat-template variables]
  (chat/format-messages chat-template variables))

(defn partial-format
  "部分格式化模板

   参数:
   - template: IPartialTemplate 实现
   - variables: 部分变量 map

   返回: 新的模板（带剩余变量）"
  [template variables]
  (if (satisfies? proto/IPartialTemplate template)
    (proto/partial-format template variables)
    (throw (ex-info "模板不支持部分格式化" {:template (type template)}))))

;; ============================================================
;; 模板组合
;; ============================================================

(defn combine
  "组合多个模板

   参数:
   - templates: 模板列表
   - opts: 可选配置 {:separator \"...\"}

   返回: 新的 PromptTemplate"
  [templates & {:as opts}]
  (apply tpl/combine-templates templates opts))

(defn append
  "向聊天模板追加消息

   参数:
   - chat-template: ChatPromptTemplate
   - new-messages: 新消息模板列表

   返回: 新的 ChatPromptTemplate"
  [chat-template new-messages]
  (chat/append-messages chat-template new-messages))

;; ============================================================
;; Example 选择器
;; ============================================================

(defn length-selector
  "创建基于长度的示例选择器

   参数:
   - opts: 配置选项 {:max-length 1000 :examples [...]}

   返回: LengthBasedSelector 实例"
  [opts]
  (sel/create-length-selector opts))

(defn similarity-selector
  "创建基于相似度的示例选择器

   参数:
   - opts: 配置选项 {:k 4 :examples [...]}

   返回: SimilaritySelector 实例"
  [opts]
  (sel/create-similarity-selector opts))

(defn mmr-selector
  "创建 MMR 选择器（最大边际相关性）

   参数:
   - opts: 配置选项 {:k 4 :lambda 0.5 :examples [...]}

   返回: MMRSelector 实例"
  [opts]
  (sel/create-mmr-selector opts))

(defn semantic-selector
  "创建语义相似度选择器

   参数:
   - opts: 配置选项 {:k 4 :embedding-fn fn :examples [...]}

   返回: SemanticSelector 实例"
  [opts]
  (sel/create-semantic-selector opts))

(defn select-examples
  "使用选择器选择示例

   参数:
   - selector: IExampleSelector 实现
   - input-variables: 输入变量 map

   返回: 示例列表"
  [selector input-variables]
  (proto/select-examples selector input-variables))

(defn add-example
  "向选择器添加示例

   参数:
   - selector: IExampleSelector 实现
   - example: 示例 map

   返回: 更新后的选择器"
  [selector example]
  (proto/add-example selector example))

;; ============================================================
;; 便捷宏
;; ============================================================

(defmacro deftemplate
  "定义一个模板

   示例:
   (deftemplate greeting \"你好，{name}！\")"
  [name template-str]
  `(def ~name (template ~template-str)))

(defmacro defchat
  "定义一个聊天模板

   示例:
   (defchat translator
     [(system \"你是一个翻译\")
      (human \"{input}\")])"
  [name messages]
  `(def ~name (chat-template ~messages)))
