(ns im.ttalk.agent.core.llm.parser.retry
  "自动重试输出解析器

   当输出解析失败时，使用 LLM 修复输出并重试。
   类似 LangChain 的 OutputFixingParser 和 RetryOutputParser。

   使用示例：

   ;; 包装现有解析器添加重试功能
   (def parser (create-json-parser))
   (def retry-parser (with-retry parser {:max-retries 3}))

   ;; 使用重试解析
   (parse-with-llm retry-parser text llm-provider config)"
  (:require [im.ttalk.agent.core.llm.parser.protocol :as proto]
            [im.ttalk.agent.core.llm.provider :as llm]
            [clojure.string :as str]))

;; ============================================================
;; 修复提示词模板
;; ============================================================

(def ^:private fix-prompt-template
  "修复输出的提示词模板"
  "以下是一段需要修复的输出。原始输出未能正确解析。

原始输出：
```
%s
```

解析错误：
%s

请修复这个输出，确保：
1. 输出是有效的 JSON 格式
2. 符合期望的结构
3. 保留原始输出中的有效信息

只返回修复后的 JSON，不要添加任何解释。")

(def ^:private retry-prompt-template
  "重试的提示词模板（用于重新生成）"
  "之前的输出格式不正确，请重新生成。

期望格式：
%s

请严格按照上述格式输出，只返回 JSON 内容。")

;; ============================================================
;; 重试配置
;; ============================================================

(defn- default-retry-config
  "默认重试配置"
  []
  {:max-retries 3
   :fix-mode :fix   ;; :fix（修复现有输出）或 :retry（重新生成）
   :verbose false})

(defn- merge-retry-config
  "合并重试配置"
  [opts]
  (merge (default-retry-config) opts))

;; ============================================================
;; LLM 调用辅助函数
;; ============================================================

(defn- call-llm-for-fix
  "调用 LLM 修复输出

   参数:
   - provider: LLM 提供商实例
   - config: LLM 配置
   - prompt: 修复提示词

   返回: 修复后的文本或 nil"
  [provider config prompt]
  (try
    (let [messages [{:role "user" :content prompt}]
          response (llm/call-llm provider config messages nil)]
      (llm/extract-text provider response))
    (catch Exception _
      nil)))

(defn- build-fix-prompt
  "构建修复提示词

   参数:
   - original-text: 原始输出文本
   - error: 解析错误信息

   返回: 修复提示词字符串"
  [original-text error]
  (format fix-prompt-template original-text error))

(defn- build-retry-prompt
  "构建重试提示词

   参数:
   - format-instructions: 格式说明

   返回: 重试提示词字符串"
  [format-instructions]
  (format retry-prompt-template format-instructions))

;; ============================================================
;; 重试逻辑
;; ============================================================

(defn- attempt-fix
  "尝试修复并重新解析

   参数:
   - parser: 原始解析器
   - text: 原始文本
   - error: 解析错误
   - provider: LLM 提供商
   - config: LLM 配置
   - verbose: 是否输出调试信息

   返回: 解析结果"
  [parser text error provider config verbose]
  (when verbose
    (println "[RetryParser] 尝试修复输出..."))
  (let [fix-prompt (build-fix-prompt text error)
        fixed-text (call-llm-for-fix provider config fix-prompt)]
    (if fixed-text
      (do
        (when verbose
          (println "[RetryParser] 收到修复后的输出"))
        (proto/parse parser fixed-text))
      (proto/failure "LLM 修复调用失败"))))

(defn- attempt-retry
  "尝试重新生成

   参数:
   - parser: 原始解析器
   - provider: LLM 提供商
   - config: LLM 配置
   - verbose: 是否输出调试信息

   返回: 解析结果"
  [parser provider config verbose]
  (when verbose
    (println "[RetryParser] 尝试重新生成输出..."))
  (let [format-instructions (proto/format-instructions parser)
        retry-prompt (build-retry-prompt format-instructions)
        new-text (call-llm-for-fix provider config retry-prompt)]
    (if new-text
      (do
        (when verbose
          (println "[RetryParser] 收到重新生成的输出"))
        (proto/parse parser new-text))
      (proto/failure "LLM 重试调用失败"))))

(defn- retry-loop
  "重试循环

   参数:
   - parser: 原始解析器
   - text: 原始文本
   - provider: LLM 提供商
   - config: LLM 配置
   - retry-config: 重试配置

   返回: 最终解析结果"
  [parser text provider config retry-config]
  (let [{:keys [max-retries fix-mode verbose]} retry-config]
    (loop [attempt 0
           current-text text
           last-error nil]
      (if (>= attempt max-retries)
        (proto/failure
          (str "达到最大重试次数 (" max-retries "). 最后错误: " last-error))
        (let [result (proto/parse parser current-text)]
          (if (proto/success? result)
            result
            (let [error (proto/get-error result)
                  _ (when verbose
                      (println (str "[RetryParser] 尝试 " (inc attempt) "/" max-retries
                                    " 失败: " error)))
                  fix-result (case fix-mode
                               :fix (attempt-fix parser current-text error
                                                 provider config verbose)
                               :retry (attempt-retry parser provider config verbose))]
              (if (proto/success? fix-result)
                fix-result
                (recur (inc attempt)
                       (or (:fixed-text fix-result) current-text)
                       (proto/get-error fix-result))))))))))

;; ============================================================
;; RetryOutputParser 实现
;; ============================================================

(defrecord RetryOutputParser [inner-parser retry-config]
  proto/IOutputParser

  (parse [_ text]
    ;; 普通解析直接委托给内部解析器
    (proto/parse inner-parser text))

  (format-instructions [_]
    (proto/format-instructions inner-parser))

  proto/IRetryableParser

  (parse-with-retry [_ text llm-provider config]
    (retry-loop inner-parser text llm-provider config retry-config))

  (get-retry-prompt [_ text error]
    (build-fix-prompt text error)))

;; ============================================================
;; 工厂函数
;; ============================================================

(defn with-retry
  "为解析器添加重试功能

   参数:
   - parser: IOutputParser 实现
   - opts: 重试配置
     - :max-retries  最大重试次数（默认 3）
     - :fix-mode     修复模式 :fix 或 :retry（默认 :fix）
     - :verbose      是否输出调试信息（默认 false）

   返回: RetryOutputParser 实例

   示例:
   (def retry-parser
     (with-retry (create-json-parser)
                 {:max-retries 3
                  :fix-mode :fix
                  :verbose true}))"
  ([parser] (with-retry parser {}))
  ([parser opts]
   (->RetryOutputParser parser (merge-retry-config opts))))

;; ============================================================
;; 便捷解析函数
;; ============================================================

(defn parse-with-llm
  "使用 LLM 辅助解析（支持重试）

   如果解析器支持重试，使用重试逻辑；否则直接解析。

   参数:
   - parser: IOutputParser 实现
   - text: 要解析的文本
   - llm-provider: LLM 提供商（可选）
   - config: LLM 配置（可选）

   返回: 解析结果"
  ([parser text]
   (proto/parse parser text))
  ([parser text llm-provider config]
   (if (satisfies? proto/IRetryableParser parser)
     (proto/parse-with-retry parser text llm-provider config)
     (proto/parse parser text))))
