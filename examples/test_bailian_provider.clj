(ns test-bailian-provider
  "测试阿里云百炼 (DashScope) Provider

   运行:
     clojure -M -e \"(load-file \\\"examples/test_bailian_provider.clj\\\")\"

   环境变量:
     BAILIAN_API_KEY - 阿里云百炼 API Key（必需）"
  (:require [im.ttalk.agent.core.kernel.tool :refer [deftool]]
            [im.ttalk.agent.core.kernel :as kernel]
            [im.ttalk.agent.core.kernel.context :as ctx]
            [im.ttalk.agent.llm.kernel.chat :as chat]
            [im.ttalk.agent.llm.provider.bailian :as bailian]
            [im.ttalk.agent.simpleagent :as ka]))

;;; ============================================================
;;; 工具定义
;;; ============================================================

(deftool get-weather
  "获取指定城市的天气信息"
  [[city :string "城市名称"]]
  {:tags [:weather :read-only]}
  (str city "：晴天，气温 22°C，湿度 55%"))

(deftool get-time
  "获取当前时间"
  []
  {:tags [:utility :read-only]}
  (str (java.time.LocalDateTime/now)))

(deftool calculate
  "执行数学计算"
  [[expression :string "数学表达式"]]
  {:tags [:utility :compute]}
  (str "结果: " (eval (read-string expression))))

(def test-tools
  "测试工具集"
  [#'get-weather #'get-time #'calculate])

;;; ============================================================
;;; 测试函数
;;; ============================================================

(defn separator [title]
  (println)
  (println "═══════════════════════════════════════════════════════════════")
  (println (str "  " title))
  (println "═══════════════════════════════════════════════════════════════")
  (println))

(defn test-case [name f]
  (print (str "  " name "... "))
  (flush)
  (try
    (let [result (f)]
      (println "[OK]")
      result)
    (catch AssertionError e
      (println (str "[FAILED] " (.getMessage e)))
      nil)
    (catch Throwable e
      (println (str "[FAILED] " (.getMessage e)))
      (when (System/getenv "DEBUG")
        (.printStackTrace e))
      nil)))

(def api-delay 3000)

(defn wait [ms]
  (Thread/sleep ms))

;;; ============================================================
;;; 测试用例
;;; ============================================================

(defn run-tests []
  (println)
  (println "╔══════════════════════════════════════════════════════════════════════════╗")
  (println "║               阿里云百炼 (DashScope) Provider 测试                        ║")
  (println "║                                                                          ║")
  (println "║  API: https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation║")
  (println "║  Model: qwen-plus                                                        ║")
  (println "╚══════════════════════════════════════════════════════════════════════════╝")

  (let [dashscope-key (System/getenv "DASHSCOPE_API_KEY")
        bailian-key (System/getenv "BAILIAN_API_KEY")
        api-key (or dashscope-key bailian-key)]
    (when-not api-key
      (println "\n  !! 未设置 DASHSCOPE_API_KEY 或 BAILIAN_API_KEY 环境变量")
      (println "  请设置阿里云百炼 API Key:")
      (println "    export DASHSCOPE_API_KEY=your-api-key")
      (System/exit 1))

    (println (str "\n  使用 API Key: "
                  (if dashscope-key "DASHSCOPE_API_KEY" "BAILIAN_API_KEY")
                  " (" (subs api-key 0 (min 10 (count api-key))) "...)")))

  (let [provider (bailian/create-provider)]

    ;; 测试 1: Provider 创建
    (separator "测试 1: Provider 创建")
    (test-case "创建默认 Provider"
      (fn []
        (assert (some? provider))
        (println (str "\n    Provider 类型: " (type provider)))
        true))

    (test-case "创建带选项的 Provider"
      (fn []
        (let [custom-provider (bailian/create-provider {:timeout 60000})]
          (assert (some? custom-provider))
          (println "\n    带自定义选项的 Provider 创建成功")
          true)))

    ;; 测试 2: 直接 API 调用
    (separator "测试 2: 直接 API 调用")
    (wait api-delay)
    (test-case "call-bailian 同步调用"
      (fn []
        (let [response (bailian/call-bailian
                         {:model "qwen-plus" :max-tokens 512}
                         [{:role "user" :content "用一句话介绍 Clojure 语言。"}]
                         nil)]
          (assert (some? response) "Response should not be nil")
          (when-not (:choices response)
            (println (str "\n    [DEBUG] 响应: " response)))
          (assert (some? (:choices response)) "Response should have :choices")
          (let [text (get-in response [:choices 0 :message :content])]
            (assert (some? text) "Response should have content")
            (println (str "\n    回复: " (if (> (count text) 80)
                                          (str (subs text 0 80) "...")
                                          text)))
            true))))

    (wait api-delay)

    ;; 测试 3: 通过 Kernel 调用
    (separator "测试 3: 通过 Kernel 调用")
    (let [service (chat/create-service
                    {:provider provider
                     :model "qwen-plus"
                     :max-tokens 1024})
          app-kernel (-> (kernel/create-kernel-builder {:max-tool-iterations 5})
                         (kernel/add-service service)
                         (kernel/add-tools test-tools)
                         (kernel/build-kernel))]

      (test-case "invoke-chat 单轮对话"
        (fn []
          (let [{:keys [response]} (kernel/invoke-chat app-kernel
                                     [{:role "user" :content "1+1=?"}]
                                     {:context (ctx/create)})]
            (assert (some? (:text response)))
            (println (str "\n    回复: " (:text response)))
            true)))

      (wait api-delay)

      (test-case "invoke 工具调用"
        (fn []
          (let [result (kernel/invoke app-kernel
                         [{:role "user" :content "北京天气怎么样？"}]
                         {:context (ctx/create)})
                text (get-in result [:response :text])]
            (assert (some? text) "Response should have text")
            (println (str "\n    工具: " (mapv :name (:tool-calls-made result))))
            (println (str "    回复: " (if (> (count text) 80)
                                         (str (subs text 0 80) "...")
                                         text)))
            true))))

    (wait api-delay)

    ;; 测试 4: 通过 SimpleAgent 调用
    (separator "测试 4: 通过 SimpleAgent 调用")
    (test-case "Kernel Agent 简单对话"
      (fn []
        (let [agent (ka/create-agent
                      {:provider provider
                       :model "qwen-plus"
                       :max-tokens 512
                       :system-prompt "回答简短。"})
              result (ka/chat agent "中国的首都是？")]
          (assert (some? (:text result)))
          (println (str "\n    回复: " (:text result)))
          true)))

    (wait api-delay)

    (test-case "Kernel Agent 多轮对话"
      (fn []
        (let [agent (ka/create-agent
                      {:provider provider
                       :model "qwen-plus"
                       :max-tokens 512
                       :system-prompt "回答简短。"})]
          (ka/chat agent "我叫小明。")
          (wait api-delay)
          (let [r2 (ka/chat agent "我叫什么？")]
            (assert (some? (:text r2)))
            (println (str "\n    回复: " (:text r2)))
            true))))

    (wait api-delay)

    (test-case "Kernel Agent 工具调用"
      (fn []
        (let [agent (ka/create-agent
                      {:provider provider
                       :model "qwen-plus"
                       :max-tokens 1024
                       :tools test-tools})
              result (ka/chat agent "现在几点了？")]
          (assert (some? (:text result)))
          (println (str "\n    工具: " (mapv :name (:tool-calls-made result))))
          (println (str "    回复: " (:text result)))
          true)))

    ;; 测试 5: 不同模型
    (separator "测试 5: 不同模型测试")
    (wait api-delay)

    (test-case "qwen-turbo 模型"
      (fn []
        (let [turbo-agent (ka/create-agent
                            {:provider provider
                             :model "qwen-turbo"
                             :max-tokens 256
                             :system-prompt "回答简短。"})
              result (ka/chat turbo-agent "2+2=?")]
          (assert (some? (:text result)))
          (println (str "\n    回复: " (:text result)))
          true)))

    (separator "测试完成")
    (println "  所有测试通过!")))

;; 运行测试
(run-tests)
