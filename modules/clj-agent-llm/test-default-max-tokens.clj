(require '[im.ttalk.agent.llm.api :as llm])
(require '[im.ttalk.agent.llm.provider.anthropic :as anthropic])

(println "\n测试 max-tokens 默认值...")

;; 创建 Provider
(def provider
  (anthropic/create-provider
    {:api-key (System/getenv "ZHIPU_API_KEY")
     :base-url "https://open.bigmodel.cn/api/anthropic"}))

;; 测试不传 max-tokens 参数
(println "\n调用 API（不指定 max-tokens）...")
(def response
  (llm/call provider
            {:model "glm-4.7"}  ;; 故意不传 max-tokens
            [{:role "user" :content "请简单说一句话"}]))

(def text (llm/extract-text provider response))
(println "\n响应:")
(println "  文本:" text)
(println "  Token 使用:" (:usage response))

(println "\n✅ 测试完成！默认 max-tokens 应该为 4094")
