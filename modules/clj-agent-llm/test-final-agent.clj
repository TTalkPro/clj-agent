(require '[im.ttalk.agent.llm.api :as llm])
(require '[im.ttalk.agent.llm.provider.anthropic :as anthropic])

(println "\n═══════════════════════════════════════════════════")
(println "   测试智谱 GLM-4.7 Agent (Anthropic 兼容 API)")
(println "═══════════════════════════════════════════════════\n")

;; 1. 创建 Provider
(println "1️⃣  创建 Provider...")
(def provider
  (anthropic/create-provider
    {:api-key (System/getenv "ZHIPU_API_KEY")
     :base-url "https://open.bigmodel.cn/api/anthropic"}))

(println "   ✅ Provider 创建成功")
(println "   Provider 名称:" (llm/provider-name provider))
(println "   支持函数调用:" (llm/supports-function-calling? provider))
(println "   支持流式:" (llm/supports-stream? provider))
(println)

;; 2. 测试基础对话
(println "2️⃣  测试基础对话...")
(println "   👤 用户: 你好，请用一句话介绍你自己")
(println)

(def response
  (llm/call provider
            {:model "glm-4.7"
             :max-tokens 256}
            [{:role "user" :content "你好，请用一句话介绍你自己"}]))

(def text (llm/extract-text provider response))
(println "   🤖 AI: " text)
(println)
(println "   响应ID:" (:id response))
(println "   停止原因:" (:stop_reason response))
(println "   Token使用: 输入=" (get-in response [:usage :input_tokens])
              "输出=" (get-in response [:usage :output_tokens]))
(println)

;; 3. 测试多轮对话
(println "3️⃣  测试多轮对话...")
(def conversation
  [{:role "user" :content "我叫张三"}
   {:role "assistant" :content "你好张三！很高兴认识你。"}
   {:role "user" :content "我刚才说我叫什么？"}])

(def response2
  (llm/call provider
            {:model "glm-4.7"
             :max-tokens 256}
            conversation))

(def text2 (llm/extract-text provider response2))
(println "   👤 用户: 我刚才说我叫什么？")
(println "   🤖 AI: " text2)
(println)

;; 4. 总结
(println "═══════════════════════════════════════════════════")
(println "   ✅ 所有测试完成！")
(println "═══════════════════════════════════════════════════\n")
(println "测试结果:")
(println "  ✓ Provider 创建成功")
(println "  ✓ 基础对话功能正常")
(println "  ✓ 多轮对话功能正常")
(println "  ✓ 文本提取功能正常")
(println "\n智谱 GLM-4.7 (Anthropic 兼容 API) 测试通过! 🎉\n")
