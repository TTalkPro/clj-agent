(ns integration-test
  "集成测试 - GLM-4.7 OpenAI 兼容接口

   覆盖场景:
   1. 单轮对话
   2. 多轮对话
   3. 工具调用

   运行: clojure -M -e \"(load-file \\\"examples/integration_test.clj\\\")\"

   环境变量: ZHIPU_API_KEY"
  (:require [im.ttalk.agent.core.kernel.tool :refer [deftool]]
            [im.ttalk.agent.core.kernel :as kernel]
            [im.ttalk.agent.core.kernel.context :as ctx]
            [im.ttalk.agent.llm.kernel.chat :as chat]
            [im.ttalk.agent.llm.provider.openai :as openai]))

;;; ============================================================
;;; Provider & Kernel 配置
;;; ============================================================

(def provider
  (openai/create-provider
    {:api-key  (System/getenv "ZHIPU_API_KEY")
     :base-url "https://open.bigmodel.cn/api/coding/paas/v4"}))

(def service
  (chat/create-service
    {:provider   provider
     :model      "glm-4.7"
     :max-tokens 1024}))

;;; ============================================================
;;; 工具定义
;;; ============================================================

(deftool get-weather
  "获取指定城市的天气信息"
  [[city :string "城市名称"]]
  {:tags [:weather :read-only]}
  (str city "：晴天，气温 22°C，湿度 55%"))

(deftool get-stock-price
  "查询股票当前价格"
  [[symbol :string "股票代码"]]
  {:tags [:finance :read-only]}
  (str symbol " 当前价格: ¥" (+ 100 (rand-int 200)) ".00"))

(deftool calculate
  "计算数学表达式"
  [[expression :string "数学表达式，如 2+3*4"]]
  {:tags [:utility :compute]}
  (str "计算结果: " (eval (read-string expression))))

(def test-tools
  "测试工具集"
  [#'get-weather #'get-stock-price #'calculate])

(def app-kernel
  (-> (kernel/create-kernel-builder {:max-tool-iterations 5})
      (kernel/add-service service)
      (kernel/add-tools test-tools)
      (kernel/build-kernel)))

;;; ============================================================
;;; 辅助
;;; ============================================================

(defn separator [title]
  (println)
  (println "═══════════════════════════════════════════════════════════")
  (println (str "  " title))
  (println "═══════════════════════════════════════════════════════════")
  (println))

(defn wait [ms]
  (Thread/sleep ms))

;;; ============================================================
;;; 测试 1: 单轮对话
;;; ============================================================

(defn test-single-turn []
  (separator "测试 1: 单轮对话")
  (let [ctx (ctx/create)
        result (kernel/invoke-chat app-kernel
                 [{:role "user" :content "用一句话介绍 Clojure 语言。"}]
                 {:context ctx})]
    (println "  问: 用一句话介绍 Clojure 语言。")
    (println (str "  答: " (get-in result [:response :text])))
    (assert (some? (get-in result [:response :text])))
    (println "  ✓ 单轮对话成功")))

;;; ============================================================
;;; 测试 2: 多轮对话
;;; ============================================================

(defn test-multi-turn []
  (separator "测试 2: 多轮对话（context 累积）")
  (let [ctx (ctx/create)
        ;; 第一轮
        r1 (kernel/invoke app-kernel
             [{:role "user" :content "我叫小明，我住在深圳。请记住这些信息。"}]
             {:context ctx :tool-choice :none})
        ctx1 (:context r1)
        _ (println (str "  轮1 问: 我叫小明，我住在深圳。"))
        _ (println (str "  轮1 答: " (get-in r1 [:response :text])))

        ;; 第二轮（使用 ctx1 中的 messages 历史）
        _ (wait 2000)
        r2 (kernel/invoke app-kernel
             [{:role "user" :content "我叫什么名字？住在哪里？"}]
             {:context ctx1 :tool-choice :none})
        _ (println (str "  轮2 问: 我叫什么名字？住在哪里？"))
        _ (println (str "  轮2 答: " (get-in r2 [:response :text])))]
    (let [answer (get-in r2 [:response :text])]
      (assert (some? answer))
      ;; 验证模型能记住上下文中的信息
      (assert (or (clojure.string/includes? answer "小明")
                  (clojure.string/includes? answer "深圳"))
              "模型应能记住上一轮提到的信息")
      (println "  ✓ 多轮对话成功，模型记住了上下文"))))

;;; ============================================================
;;; 测试 3: 工具调用
;;; ============================================================

(defn test-tool-calling []
  (separator "测试 3: 工具调用")
  ;; GLM-4.7 coding endpoint 不支持 function calling
  ;; 使用标准 endpoint (glm-4-flash) 测试工具调用
  (let [tool-provider (openai/create-provider
                        {:api-key  (System/getenv "ZHIPU_API_KEY")
                         :base-url "https://open.bigmodel.cn/api/paas/v4"})
        tool-service (chat/create-service
                       {:provider   tool-provider
                        :model      "glm-4-flash"
                        :max-tokens 1024})
        tool-kernel (-> (kernel/create-kernel-builder {:max-tool-iterations 5})
                        (kernel/add-service tool-service)
                        (kernel/add-tools test-tools)
                        (kernel/build-kernel))
        result (kernel/invoke tool-kernel
                 [{:role "user" :content "帮我查一下北京的天气。"}]
                 {:context (ctx/create)})]
    (println (str "  问: 查北京天气"))
    (println (str "  答: " (get-in result [:response :text])))
    (println (str "  工具调用: " (mapv :name (:tool-calls-made result))))
    (assert (some? (get-in result [:response :text])) "应有响应文本")
    (assert (seq (:tool-calls-made result)) "应有工具调用")
    (println "  ✓ 工具调用成功")))

;;; ============================================================
;;; 运行
;;; ============================================================

(defn run-all []
  (println)
  (println "╔═══════════════════════════════════════════════════════════╗")
  (println "║  集成测试 - GLM-4.7 (OpenAI 兼容 Provider)               ║")
  (println "╚═══════════════════════════════════════════════════════════╝")

  (test-single-turn)
  (wait 2000)
  (test-multi-turn)
  (wait 2000)
  (test-tool-calling)

  (separator "全部测试通过"))

(run-all)
