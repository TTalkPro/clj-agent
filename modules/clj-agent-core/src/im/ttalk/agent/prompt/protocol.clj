(ns im.ttalk.agent.prompt.protocol
  "提示词模板协议 - 定义提示词模板的统一接口

   提供类似 LangChain PromptTemplate 的功能：
   - 变量替换
   - 格式化输出
   - 模板验证

   使用示例：

   (def template (prompt-template \"你好，{name}！\"))

   ;; 格式化提示词
   (format-prompt template {:name \"张三\"})"
  ;; validate-variables / format-safe 用到这两个 ns；此前未 require，仅因其他
  ;; ns（如 tool.clj）先 require 了 clojure.set 才偶然能跑，单独 AOT 会编译失败。
  (:require [clojure.set]
            [clojure.string]))

;; ============================================================
;; 提示词模板协议
;; ============================================================

(defprotocol IPromptTemplate
  "提示词模板协议 - 定义提示词模板的统一接口

   所有提示词模板必须实现此协议。

   必需方法：
   - format-prompt: 格式化提示词
   - get-input-variables: 获取输入变量列表"

  (format-prompt
    [this variables]
    "格式化提示词

     参数:
     - variables: 变量 map {:name \"张三\" :age 25}

     返回: 格式化后的字符串

     示例:
     (format-prompt template {:name \"张三\"})
     ; => \"你好，张三！\"")

  (get-input-variables
    [this]
    "获取输入变量列表

     返回: 变量名列表 [:name :age]

     示例:
     (get-input-variables template)
     ; => [:name :age]"))

;; ============================================================
;; 可选协议扩展
;; ============================================================

(defprotocol IPartialTemplate
  "支持部分变量填充的模板协议"

  (partial-format
    [this variables]
    "部分格式化提示词

     只填充提供的变量，保留未提供的变量占位符。

     参数:
     - variables: 部分变量 map

     返回: IPromptTemplate 实例（带有剩余变量）"))

(defprotocol IMessageTemplate
  "消息模板协议 - 生成消息格式的输出"

  (format-messages
    [this variables]
    "格式化为消息列表

     参数:
     - variables: 变量 map

     返回: 消息列表 [{:role \"user\" :content \"...\"}]"))

;; ============================================================
;; Example 选择器协议
;; ============================================================

(defprotocol IExampleSelector
  "Example 选择器协议 - 动态选择示例

   用于 FewShotPromptTemplate 的动态示例选择。"

  (select-examples
    [this input-variables]
    "根据输入选择示例

     参数:
     - input-variables: 输入变量 map

     返回: 示例列表")

  (add-example
    [this example]
    "添加示例

     参数:
     - example: 示例 map

     返回: 更新后的选择器"))

;; ============================================================
;; 辅助函数
;; ============================================================

(defn extract-variables
  "从模板字符串中提取变量名

   参数:
   - template: 模板字符串，使用 {variable} 格式

   返回: 变量名列表

   示例:
   (extract-variables \"你好，{name}！你今年 {age} 岁。\")
   ; => [:name :age]"
  [template]
  (when template
    (->> (re-seq #"\{([^}]+)\}" template)
         (map second)
         (map keyword)
         (distinct)
         (vec))))

(defn validate-variables
  "验证变量是否完整

   参数:
   - template: IPromptTemplate 实现
   - variables: 提供的变量 map

   返回: {:valid true} 或 {:valid false :missing [...]}

   示例:
   (validate-variables template {:name \"张三\"})
   ; => {:valid false :missing [:age]}"
  [template variables]
  (let [required (set (get-input-variables template))
        provided (set (keys variables))
        missing (clojure.set/difference required provided)]
    (if (empty? missing)
      {:valid true}
      {:valid false :missing (vec missing)})))

(defn format-safe
  "安全格式化（验证变量后格式化）

   参数:
   - template: IPromptTemplate 实现
   - variables: 变量 map

   返回: {:success true :result \"...\"} 或 {:success false :error \"...\"}

   示例:
   (format-safe template {:name \"张三\"})"
  [template variables]
  (let [validation (validate-variables template variables)]
    (if (:valid validation)
      {:success true :result (format-prompt template variables)}
      {:success false
       :error (str "缺少变量: " (clojure.string/join ", " (map name (:missing validation))))})))
