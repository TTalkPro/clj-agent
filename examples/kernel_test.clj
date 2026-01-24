(ns kernel-test
  "Kernel 功能测试 - 使用 GLM-4.7 OpenAI 兼容接口

   运行: clojure -M -e \"(load-file \\\"examples/kernel_test.clj\\\")\"

   注意: Zhipu 免费套餐有严格速率限制，建议每次调用间隔 10+ 秒。"
  (:require [im.ttalk.agent.core.kernel.tool :refer [deftool]]
            [im.ttalk.agent.core.kernel.plugin :as kp]
            [im.ttalk.agent.core.kernel.core :as kernel]
            [im.ttalk.agent.core.kernel.filter :as filters]
            [im.ttalk.agent.core.kernel.context :as ctx]
            [im.ttalk.agent.llm.kernel.chat :as chat]
            [im.ttalk.agent.llm.provider.zhipu :as zhipu]))

;;; ============================================================
;;; 工具定义
;;; ============================================================

(deftool get-weather
  "获取指定城市的天气信息"
  [[city :string "城市名称"]]
  (str city "：晴天，气温 25°C，湿度 60%"))

(deftool get-time
  "获取当前时间"
  []
  (str (java.time.LocalDateTime/now)))

(deftool calculate
  "执行数学计算，表达式需要是合法的 Clojure 表达式"
  [[expression :string "Clojure 数学表达式，如 (+ 1 2)"]]
  (str (eval (read-string expression))))

;;; ============================================================
;;; Plugin & Kernel 构建
;;; ============================================================

(kp/defplugin test-tools "测试工具集" get-weather get-time calculate)

(def service
  (chat/create-service
    {:provider (zhipu/create-provider
                 {:api-key (System/getenv "ZHIPU_API_KEY")
                  :base-url "https://open.bigmodel.cn/api/coding/paas/v4"
                  :endpoint "/chat/completions"})
     :model "glm-4.7"
     :max-tokens 1024}))

(def app-kernel
  (-> (kernel/create-kernel-builder {:max-tool-iterations 5})
      (kernel/add-service service)
      (kernel/add-plugin test-tools)
      (kernel/add-filter filters/logging-pre-filter)
      (kernel/add-filter filters/logging-post-filter)
      (kernel/build-kernel)))

(def quiet-kernel
  (-> (kernel/create-kernel-builder {:max-tool-iterations 5})
      (kernel/add-service service)
      (kernel/add-plugin test-tools)
      (kernel/build-kernel)))

;;; ============================================================
;;; 测试辅助
;;; ============================================================

(defn separator [title]
  (println)
  (println (str "═══════════════════════════════════════════════════════════"))
  (println (str "  " title))
  (println (str "═══════════════════════════════════════════════════════════"))
  (println))

(defn wait [ms]
  (println (str "  [等待 " (/ ms 1000) "s...]"))
  (Thread/sleep ms))

(defn safe-call [label f]
  (try
    (let [result (f)]
      (println (str "  ✓ " label))
      result)
    (catch Throwable e
      (println (str "  ✗ " label " 失败: " (.getMessage e)))
      nil)))

;;; ============================================================
;;; 测试用例
;;; ============================================================

(defn test-query-api []
  (separator "测试 1: Query API")
  (println "  list-functions:" (kernel/list-functions app-kernel))
  (println "  get-tools count:" (count (kernel/get-tools app-kernel)))
  (println "  find-function :get-weather:" (boolean (kernel/find-function app-kernel :get-weather)))
  (println "  find-function :nonexistent:" (boolean (kernel/find-function app-kernel :nonexistent)))
  (println "  get-service:" (boolean (kernel/get-service app-kernel)))
  (println "  ✓ Query API 正常"))

(defn test-invoke-tool []
  (separator "测试 2: invoke-tool 函数调用（经过 Filter）")
  (let [result (kernel/invoke-tool app-kernel :get-weather {:city "深圳"} (ctx/create))]
    (println "  结果:" (:value result))
    (println "  ✓ 函数调用成功")))

(defn test-invoke-chat []
  (separator "测试 3: invoke-chat 单轮对话（无工具）")
  (safe-call "单轮对话"
    (fn []
      (let [{:keys [response]} (kernel/invoke-chat quiet-kernel
                                 [{:role "user" :content "你好，用一句话介绍自己"}] {})]
        (println "  回复:" (:text response))))))

(defn test-multi-turn-invoke-chat []
  (separator "测试 4: invoke-chat 多轮对话（无工具）")
  (safe-call "多轮对话"
    (fn []
      (let [{:keys [response]} (kernel/invoke-chat quiet-kernel
                                 [{:role "user" :content "我叫小明，记住。简短回复。"}] {})]
        (println "  轮次1:" (:text response))
        (wait 2000)
        (let [{:keys [response]} (kernel/invoke-chat quiet-kernel
                                   [{:role "user" :content "我叫小明，记住。简短回复。"}
                                    {:role "assistant" :content (:text response)}
                                    {:role "user" :content "我叫什么名字？直接回答。"}] {})]
          (println "  轮次2:" (:text response)))))))

(defn test-invoke-with-tools []
  (separator "测试 5: invoke 工具调用循环")
  (safe-call "工具调用"
    (fn []
      (let [result (kernel/invoke app-kernel
                     [{:role "user" :content "北京天气怎么样？"}] {})]
        (println "  text:" (get-in result [:response :text]))
        (println "  tools:" (mapv (fn [tc] [(:name tc) (:result tc)])
                                  (:tool-calls-made result)))))))

(defn test-invoke-with-context []
  (separator "测试 6: invoke 带 context 累积")
  (safe-call "带 context 的工具调用"
    (fn []
      (let [my-ctx (ctx/create {:user "test-user"})
            result (kernel/invoke quiet-kernel
                     [{:role "user" :content "现在几点了？"}]
                     {:context my-ctx})]
        (println "  text:" (get-in result [:response :text]))
        (println "  tools:" (mapv :name (:tool-calls-made result)))
        (println "  context.messages count:" (count (ctx/get-messages (:context result))))
        (println "  context.history count:" (count (ctx/get-history (:context result))))))))

(defn test-invoke-with-system-prompts []
  (separator "测试 7: invoke 带 system-prompts")
  (safe-call "system-prompts"
    (fn []
      (let [result (kernel/invoke quiet-kernel
                     [{:role "user" :content "你是谁？用一句话回答。"}]
                     {:system-prompts [{:role "system" :content "你是一个数学助手，只回答数学问题。"}]})]
        (println "  text:" (get-in result [:response :text]))))))

;;; ============================================================
;;; 运行
;;; ============================================================

(defn run-all []
  (println)
  (println "╔═══════════════════════════════════════════════════════════╗")
  (println "║       Kernel 功能测试 (GLM-4.7 OpenAI 兼容)             ║")
  (println "╚═══════════════════════════════════════════════════════════╝")

  (test-query-api)
  (test-invoke-tool)
  (test-invoke-chat)
  (wait 3000)
  (test-multi-turn-invoke-chat)
  (wait 3000)
  (test-invoke-with-tools)
  (wait 3000)
  (test-invoke-with-context)
  (wait 3000)
  (test-invoke-with-system-prompts)

  (separator "完成"))

(run-all)
