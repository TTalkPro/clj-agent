(ns im.ttalk.agent.core.llm.prompt.template
  "提示词模板实现

   提供基础模板和少样本模板：
   - PromptTemplate: 变量替换模板
   - FewShotPromptTemplate: 少样本学习模板

   使用示例：

   ;; 基础模板
   (def template (create-prompt-template \"你好，{name}！\"))
   (format-prompt template {:name \"张三\"})

   ;; 少样本模板
   (def few-shot
     (create-few-shot-template
       {:prefix \"请翻译以下文本：\"
        :examples [{:input \"Hello\" :output \"你好\"}]
        :suffix \"输入：{input}\\n输出：\"}))"
  (:require [im.ttalk.agent.core.llm.prompt.protocol :as proto]
            [clojure.string :as str]))

;; ============================================================
;; 变量替换辅助函数
;; ============================================================

(defn- replace-variable
  "替换单个变量

   参数:
   - text: 文本
   - var-name: 变量名（关键字）
   - value: 变量值

   返回: 替换后的文本"
  [text var-name value]
  (let [pattern (re-pattern (str "\\{" (name var-name) "\\}"))]
    (str/replace text pattern (str value))))

(defn- replace-all-variables
  "替换所有变量

   参数:
   - template: 模板字符串
   - variables: 变量 map

   返回: 替换后的字符串"
  [template variables]
  (reduce-kv replace-variable template variables))

;; ============================================================
;; PromptTemplate 实现
;; ============================================================

(defrecord PromptTemplate [template-str input-variables]
  proto/IPromptTemplate

  (format-prompt [_ variables]
    (replace-all-variables template-str variables))

  (get-input-variables [_]
    input-variables)

  proto/IPartialTemplate

  (partial-format [_ variables]
    (let [new-template (replace-all-variables template-str variables)
          remaining-vars (remove (set (keys variables)) input-variables)]
      (->PromptTemplate new-template (vec remaining-vars)))))

(defn create-prompt-template
  "创建提示词模板

   参数:
   - template-str: 模板字符串，使用 {variable} 格式
   - opts: 可选配置
     - :input-variables 明确指定变量列表（可选，默认自动提取）

   返回: PromptTemplate 实例

   示例:
   (def template (create-prompt-template \"你好，{name}！今年 {age} 岁。\"))
   (format-prompt template {:name \"张三\" :age 25})
   ; => \"你好，张三！今年 25 岁。\""
  ([template-str]
   (create-prompt-template template-str {}))
  ([template-str opts]
   (let [vars (or (:input-variables opts)
                  (proto/extract-variables template-str))]
     (->PromptTemplate template-str vars))))

;; ============================================================
;; Example 格式化
;; ============================================================

(defn- format-example
  "格式化单个示例

   参数:
   - example: 示例 map {:input \"...\" :output \"...\"}
   - example-template: 示例模板

   返回: 格式化后的字符串"
  [example example-template]
  (if example-template
    (proto/format-prompt example-template example)
    (str "输入：" (:input example) "\n输出：" (:output example))))

(defn- format-examples
  "格式化示例列表

   参数:
   - examples: 示例列表
   - example-template: 示例模板
   - separator: 分隔符

   返回: 格式化后的字符串"
  [examples example-template separator]
  (str/join separator
            (map #(format-example % example-template) examples)))

;; ============================================================
;; FewShotPromptTemplate 实现
;; ============================================================

(defrecord FewShotPromptTemplate
  [prefix suffix examples example-template example-separator input-variables]

  proto/IPromptTemplate

  (format-prompt [_ variables]
    (let [examples-str (format-examples examples example-template example-separator)
          formatted-suffix (replace-all-variables suffix variables)]
      (str prefix "\n\n"
           examples-str "\n\n"
           formatted-suffix)))

  (get-input-variables [_]
    input-variables))

(defn create-few-shot-template
  "创建少样本提示词模板

   参数:
   - opts: 配置选项
     - :prefix           前缀说明
     - :suffix           后缀模板（包含输入变量）
     - :examples         示例列表 [{:input \"...\" :output \"...\"}]
     - :example-template 示例模板（可选，IPromptTemplate）
     - :example-separator 示例分隔符（默认 \"\\n\\n\"）
     - :input-variables  输入变量列表（可选，默认从 suffix 提取）

   返回: FewShotPromptTemplate 实例

   示例:
   (def template
     (create-few-shot-template
       {:prefix \"请将英文翻译成中文：\"
        :suffix \"输入：{input}\\n输出：\"
        :examples [{:input \"Hello\" :output \"你好\"}
                   {:input \"World\" :output \"世界\"}]}))"
  [{:keys [prefix suffix examples example-template example-separator input-variables]
    :or {example-separator "\n\n"}}]
  (let [vars (or input-variables
                 (proto/extract-variables suffix))]
    (->FewShotPromptTemplate
      prefix suffix examples example-template example-separator vars)))

;; ============================================================
;; 动态 FewShotPromptTemplate
;; ============================================================

(defrecord DynamicFewShotTemplate
  [prefix suffix example-selector example-template example-separator input-variables]

  proto/IPromptTemplate

  (format-prompt [_ variables]
    (let [;; 使用选择器动态选择示例
          selected-examples (proto/select-examples example-selector variables)
          examples-str (format-examples selected-examples example-template example-separator)
          formatted-suffix (replace-all-variables suffix variables)]
      (str prefix "\n\n"
           examples-str "\n\n"
           formatted-suffix)))

  (get-input-variables [_]
    input-variables))

(defn create-dynamic-few-shot-template
  "创建动态少样本模板（使用 Example 选择器）

   参数:
   - opts: 配置选项
     - :prefix           前缀说明
     - :suffix           后缀模板
     - :example-selector Example 选择器（实现 IExampleSelector）
     - :example-template 示例模板（可选）
     - :example-separator 示例分隔符（默认 \"\\n\\n\"）

   返回: DynamicFewShotTemplate 实例"
  [{:keys [prefix suffix example-selector example-template example-separator input-variables]
    :or {example-separator "\n\n"}}]
  (let [vars (or input-variables
                 (proto/extract-variables suffix))]
    (->DynamicFewShotTemplate
      prefix suffix example-selector example-template example-separator vars)))

;; ============================================================
;; 模板组合
;; ============================================================

(defn combine-templates
  "组合多个模板

   参数:
   - templates: 模板列表
   - separator: 分隔符（默认 \"\\n\\n\"）

   返回: 新的 PromptTemplate

   示例:
   (combine-templates [template1 template2] \"\\n---\\n\")"
  [templates & {:keys [separator] :or {separator "\n\n"}}]
  (let [combined-str (str/join separator
                               (map #(:template-str %) templates))
        all-vars (vec (distinct (mapcat proto/get-input-variables templates)))]
    (->PromptTemplate combined-str all-vars)))

;; ============================================================
;; 便捷构造函数
;; ============================================================

(defn from-template-str
  "从模板字符串创建模板（别名）

   参数:
   - s: 模板字符串

   返回: PromptTemplate 实例"
  [s]
  (create-prompt-template s))

(defn from-file
  "从文件加载模板

   参数:
   - path: 文件路径

   返回: PromptTemplate 实例"
  [path]
  (-> (slurp path)
      (create-prompt-template)))
