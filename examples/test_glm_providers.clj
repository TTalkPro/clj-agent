(ns test-glm-providers
  "测试智谱 GLM 双协议接口（Anthropic 兼容 + OpenAI 兼容）

   使用方法：
   1. 设置环境变量 ZHIPU_API_KEY
   2. 在 modules/clj-agent-provider 下运行:
      clojure -M -i ../../examples/test_glm_providers.clj"
  (:require [im.ttalk.agent.provider.zhipu :as zhipu]
            [im.ttalk.agent.model :as model]
            [im.ttalk.agent.model.response :as resp]))

(def glm-model "glm-4.7")

(when-not (System/getenv "ZHIPU_API_KEY")
  (println "错误: 请设置 ZHIPU_API_KEY 环境变量")
  (System/exit 1))

(println (apply str (repeat 60 "=")))
(println "测试 GLM (" glm-model ") 双协议接口")
(println (apply str (repeat 60 "=")))

;;; ============================================================
;;; 测试 1: Anthropic 兼容协议（coding/agent 推荐，Claude Code 同款端点）
;;; ============================================================

(println "\n[测试 1] Anthropic 兼容协议  open.bigmodel.cn/api/anthropic")
(def sa (zhipu/create-anthropic-provider {}))
(try
  (let [r (model/call-llm sa {:model glm-model :max-tokens 1024}
                          [{:role :user :content "用一句话介绍你自己。"}]
                          [])]
    (println "TEXT:" (resp/response-text r))
    (println "REASONING 字符数:" (count (or (resp/response-reasoning r) "")))
    (println "USAGE:" (resp/response-usage r))
    (println "[通过]"))
  (catch Exception e
    (println "[失败]" (.getMessage e) (ex-data e))))

;;; ============================================================
;;; 测试 2: OpenAI 兼容协议 + thinking 开关
;;; ============================================================

(println "\n[测试 2] OpenAI 兼容协议  open.bigmodel.cn/api/paas/v4（thinking enabled）")
(def po (zhipu/create-provider {}))
(try
  (let [r (model/call-llm po {:model glm-model :max-tokens 1024
                              :thinking {:type "enabled"}}
                          [{:role :user :content "9.11 和 9.9 哪个大？只答一个数。"}]
                          [])]
    (println "TEXT:" (resp/response-text r))
    (println "REASONING 字符数:" (count (or (resp/response-reasoning r) "")))
    (println "USAGE:" (resp/response-usage r))
    (println "[通过]"))
  (catch Exception e
    (println "[失败]" (.getMessage e) (ex-data e))))

;;; ============================================================
;;; 测试 3: 流式（Anthropic 协议，推理与答案分流）
;;; ============================================================

(println "\n[测试 3] 流式（Anthropic 协议）")
(def ans (StringBuilder.))
(def think (StringBuilder.))
(try
  (model/call-llm-stream sa {:model glm-model :max-tokens 512}
                         [{:role :user :content "数到三，只输出：1 2 3"}]
                         nil
                         (fn [{:keys [token reasoning-token]}]
                           (when token (.append ans token))
                           (when reasoning-token (.append think reasoning-token))))
  (println "答案流:" (.toString ans))
  (println "推理流字符数:" (.length think))
  (println "[通过]")
  (catch Exception e
    (println "[失败]" (.getMessage e))))

(println "\n完成。")
(System/exit 0)
