(ns test-glm-providers
  "测试 GLM-4.7 通过 Anthropic 和 OpenAI 兼容接口

   使用方法：
   1. 设置环境变量 ZHIPU_API_KEY
   2. 运行: clj -M examples/test_glm_providers.clj"
  (:require [im.ttalk.agent.model.provider.anthropic :as anthropic]
            [im.ttalk.agent.model.provider.base :as base]
            [im.ttalk.agent.kernel.provider :as proto]))

(def api-key (System/getenv "ZHIPU_API_KEY"))

(when-not api-key
  (println "错误: 请设置 ZHIPU_API_KEY 环境变量")
  (System/exit 1))

(println "=" (apply str (repeat 60 "=")))
(println "测试 GLM-4.7 通过不同协议接口")
(println "=" (apply str (repeat 60 "=")))

;;; ============================================================
;;; 测试 1: Anthropic 兼容接口
;;; ============================================================

(println "\n[测试 1] Anthropic 兼容接口")
(println "-" (apply str (repeat 50 "-")))
(println "Base URL: https://open.bigmodel.cn/api/anthropic")
(println "Model: GLM-4.7")

(def anthropic-provider
  (anthropic/create-provider
    {:api-key api-key
     :base-url "https://open.bigmodel.cn/api/anthropic"}))

(println "Provider 名称:" (proto/provider-name anthropic-provider))
(println "支持 Function Calling:" (proto/supports-function-calling? anthropic-provider))
(println "支持 Stream:" (proto/supports-stream? anthropic-provider))

(println "\n发送测试消息...")
(try
  (let [response (proto/call-llm
                   anthropic-provider
                   {:model "GLM-4.7"
                    :max-tokens 256}
                   [{:role "user" :content "你好！请用一句话介绍一下你自己。"}]
                   [])
        text (proto/extract-text anthropic-provider response)]
    (println "AI 回复:" text)
    (println "Usage:" (:usage response))
    (println "[Anthropic 接口测试通过]"))
  (catch Exception e
    (println "[Anthropic 接口测试失败]")
    (println "错误:" (.getMessage e))
    (when-let [data (ex-data e)]
      (println "详细:" data))))

;;; ============================================================
;;; 测试 2: OpenAI 兼容接口
;;; ============================================================

(println "\n[测试 2] OpenAI 兼容接口")
(println "-" (apply str (repeat 50 "-")))
(println "Base URL: https://open.bigmodel.cn/api/coding/paas/v4")
(println "Model: GLM-4.7")

(def openai-config
  (base/make-config
    :zhipu-openai
    "https://open.bigmodel.cn/api/coding/paas/v4"
    "ZHIPU_API_KEY"
    :endpoint "/chat/completions"
    :timeout 120000))

;; 更新 API key（因为环境变量名可能不同）
(base/update-config! openai-config {:api-key api-key})

(def openai-provider (base/create-provider openai-config))

(println "Provider 名称:" (proto/provider-name openai-provider))
(println "支持 Function Calling:" (proto/supports-function-calling? openai-provider))
(println "支持 Stream:" (proto/supports-stream? openai-provider))

(println "\n发送测试消息...")
(try
  (let [response (proto/call-llm
                   openai-provider
                   {:model "GLM-4.7"
                    :max-tokens 256}
                   [{:role "user" :content "你好！请用一句话介绍一下你自己。"}]
                   [])
        text (proto/extract-text openai-provider response)]
    (println "AI 回复:" text)
    (println "Usage:" (get-in response [:usage]))
    (println "[OpenAI 兼容接口测试通过]"))
  (catch Exception e
    (println "[OpenAI 兼容接口测试失败]")
    (println "错误:" (.getMessage e))
    (when-let [data (ex-data e)]
      (println "详细:" data))))

;;; ============================================================
;;; 测试 3: 流式调用（Anthropic）
;;; ============================================================

(println "\n[测试 3] 流式调用 (Anthropic)")
(println "-" (apply str (repeat 50 "-")))

(println "流式输出:")
(try
  (let [response (proto/call-llm-stream
                   anthropic-provider
                   {:model "GLM-4.7"
                    :max-tokens 256}
                   [{:role "user" :content "请用3个要点解释什么是人工智能。"}]
                   []
                   (fn [{:keys [token]}]
                     (when token
                       (print token)
                       (flush))))]
    (println)
    (println "\n[Anthropic 流式测试通过]"))
  (catch Exception e
    (println "\n[Anthropic 流式测试失败]")
    (println "错误:" (.getMessage e))))

;;; ============================================================
;;; 测试 4: 流式调用（OpenAI）
;;; ============================================================

(println "\n[测试 4] 流式调用 (OpenAI)")
(println "-" (apply str (repeat 50 "-")))

(println "流式输出:")
(try
  (let [response (proto/call-llm-stream
                   openai-provider
                   {:model "GLM-4.7"
                    :max-tokens 256}
                   [{:role "user" :content "请用3个要点解释什么是机器学习。"}]
                   []
                   (fn [{:keys [token]}]
                     (when token
                       (print token)
                       (flush))))]
    (println)
    (println "\n[OpenAI 流式测试通过]"))
  (catch Exception e
    (println "\n[OpenAI 流式测试失败]")
    (println "错误:" (.getMessage e))))

;;; ============================================================
;;; 测试总结
;;; ============================================================

(println "\n" (apply str (repeat 60 "=")))
(println "测试完成")
(println (apply str (repeat 60 "=")))
