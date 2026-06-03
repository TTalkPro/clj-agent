(ns test-new-tool-api
  "测试新的 Tool API（直接添加 tools + tags 过滤）

   使用 GLM-4.7 通过 Anthropic 和 OpenAI 兼容接口验证

   运行: clojure -M -e \"(load-file \\\"examples/test_new_tool_api.clj\\\")\"

   需要设置环境变量: ZHIPU_API_KEY"
  (:require [im.ttalk.agent.core.kernel.tool :refer [deftool]]
            [im.ttalk.agent.core.kernel.tool :as tool]
            [im.ttalk.agent.core.kernel :as kernel]
            [im.ttalk.agent.core.kernel.context :as ctx]
            [im.ttalk.agent.llm.kernel.chat :as chat]
            [im.ttalk.agent.llm.provider.anthropic :as anthropic]
            [im.ttalk.agent.llm.provider.base :as base]))

;;; ============================================================
;;; 工具定义（使用新的 tags 功能）
;;; ============================================================

(deftool get-weather
  "获取指定城市的天气信息"
  [[city :string "城市名称"]]
  {:tags [:weather :external-api :read-only]}
  (str city "：晴天，气温 25°C，湿度 60%"))

(deftool get-time
  "获取当前时间"
  []
  {:tags [:utility :read-only]}
  (str (java.time.LocalDateTime/now)))

(deftool calculate
  "执行数学计算，表达式需要是合法的 Clojure 表达式"
  [[expression :string "Clojure 数学表达式，如 (+ 1 2)"]]
  {:tags [:utility :compute]}
  (str (eval (read-string expression))))

(deftool delete-data
  "删除数据（危险操作）"
  [[id :string "数据 ID"]]
  {:tags [:dangerous :write]
   :sensitive true}
  (str "已删除数据: " id))

;;; ============================================================
;;; 测试辅助
;;; ============================================================

(defn separator [title]
  (println)
  (println (str "═══════════════════════════════════════════════════════════"))
  (println (str "  " title))
  (println (str "═══════════════════════════════════════════════════════════"))
  (println))

(defn subsection [title]
  (println)
  (println (str "  --- " title " ---"))
  (println))

(defn safe-call [label f]
  (try
    (let [result (f)]
      (println (str "  ✓ " label))
      result)
    (catch Throwable e
      (println (str "  ✗ " label " 失败: " (.getMessage e)))
      nil)))

(defn wait [ms]
  (println (str "  [等待 " (/ ms 1000) "s...]"))
  (Thread/sleep ms))

;;; ============================================================
;;; 测试 1: Tool 元数据验证
;;; ============================================================

(defn test-tool-metadata []
  (separator "测试 1: Tool 元数据验证（tags 支持）")

  (subsection "get-weather 元数据")
  (println "  schema:" (:tool/schema (meta #'get-weather)))
  (println "  tags:" (tool/get-tags #'get-weather))
  (println "  has-tag? :weather:" (tool/has-tag? #'get-weather :weather))
  (println "  has-tag? :dangerous:" (tool/has-tag? #'get-weather :dangerous))
  (println "  has-any-tag? [:weather :utility]:" (tool/has-any-tag? #'get-weather #{:weather :utility}))

  (subsection "delete-data 元数据")
  (println "  tags:" (tool/get-tags #'delete-data))
  (println "  sensitive?:" (tool/sensitive? #'delete-data))
  (println "  has-tag? :dangerous:" (tool/has-tag? #'delete-data :dangerous))

  (println)
  (println "  ✓ Tool 元数据验证完成"))

;;; ============================================================
;;; 测试 2: Kernel 构建（新的 add-tools API）
;;; ============================================================

(def api-key (System/getenv "ZHIPU_API_KEY"))

(when-not api-key
  (println "错误: 请设置 ZHIPU_API_KEY 环境变量")
  (System/exit 1))

;; Anthropic 兼容服务
(def anthropic-service
  (chat/create-service
    {:provider (anthropic/create-provider
                 {:api-key api-key
                  :base-url "https://open.bigmodel.cn/api/anthropic"})
     :model "GLM-4.7"
     :max-tokens 1024}))

;; OpenAI 兼容服务
(def openai-config
  (base/make-config
    :zhipu-openai
    "https://open.bigmodel.cn/api/coding/paas/v4"
    "ZHIPU_API_KEY"
    :endpoint "/chat/completions"
    :timeout 120000))

(base/update-config! openai-config {:api-key api-key})

(def openai-service
  (chat/create-service
    {:provider (base/create-provider openai-config)
     :model "GLM-4.7"
     :max-tokens 1024}))

;; 使用新的 add-tools API 构建 Kernel
(def kernel-anthropic
  (-> (kernel/create-kernel-builder {:max-tool-iterations 5})
      (kernel/add-service anthropic-service)
      (kernel/add-tools [#'get-weather #'get-time #'calculate #'delete-data])
      (kernel/build-kernel)))

(def kernel-openai
  (-> (kernel/create-kernel-builder {:max-tool-iterations 5})
      (kernel/add-service openai-service)
      (kernel/add-tools [#'get-weather #'get-time #'calculate #'delete-data])
      (kernel/build-kernel)))

(defn test-kernel-build []
  (separator "测试 2: Kernel 构建（新的 add-tools API）")

  (println "  Anthropic Kernel:")
  (println "    list-functions:" (kernel/list-functions kernel-anthropic))
  (println "    tools count:" (count (:tools kernel-anthropic)))
  (println "    tool-vars keys:" (keys (:tool-vars kernel-anthropic)))

  (println)
  (println "  OpenAI Kernel:")
  (println "    list-functions:" (kernel/list-functions kernel-openai))
  (println "    tools count:" (count (:tools kernel-openai)))

  (println)
  (println "  ✓ Kernel 构建成功（使用 add-tools）"))

;;; ============================================================
;;; 测试 3: Tag 过滤查询
;;; ============================================================

(defn test-tag-queries []
  (separator "测试 3: Tag 过滤查询")

  (println "  list-functions-by-tag :weather:")
  (println "    " (kernel/list-functions-by-tag kernel-anthropic :weather))

  (println "  list-functions-by-tag :utility:")
  (println "    " (kernel/list-functions-by-tag kernel-anthropic :utility))

  (println "  list-functions-by-tag :read-only:")
  (println "    " (kernel/list-functions-by-tag kernel-anthropic :read-only))

  (println "  list-functions-by-tag :dangerous:")
  (println "    " (kernel/list-functions-by-tag kernel-anthropic :dangerous))

  (println "  list-functions-by-tags [:weather :utility] (OR):")
  (println "    " (kernel/list-functions-by-tags kernel-anthropic [:weather :utility]))

  (println "  list-functions-with-all-tags [:utility :read-only] (AND):")
  (println "    " (kernel/list-functions-with-all-tags kernel-anthropic [:utility :read-only]))

  (println)
  (println "  ✓ Tag 过滤查询正常"))

;;; ============================================================
;;; 测试 4: invoke-tool 直接调用
;;; ============================================================

(defn test-invoke-tool []
  (separator "测试 4: invoke-tool 直接调用")

  (let [result (kernel/invoke-tool kernel-anthropic :get-weather {:city "深圳"} (ctx/create))]
    (println "  get-weather 结果:" (:value result)))

  (let [result (kernel/invoke-tool kernel-anthropic :get-time {} (ctx/create))]
    (println "  get-time 结果:" (:value result)))

  (let [result (kernel/invoke-tool kernel-anthropic :calculate {:expression "(+ 1 2 3)"} (ctx/create))]
    (println "  calculate 结果:" (:value result)))

  (println)
  (println "  ✓ invoke-tool 调用正常"))

;;; ============================================================
;;; 测试 5: invoke 带 tag 过滤（Anthropic）
;;; ============================================================

(defn test-invoke-with-tags-anthropic []
  (separator "测试 5: invoke 带 tag 过滤（Anthropic 接口）")

  (subsection "5.1 使用所有工具")
  (safe-call "询问天气（所有工具可用）"
    (fn []
      (let [result (kernel/invoke kernel-anthropic
                     [{:role "user" :content "北京天气怎么样？"}]
                     {})]
        (println "    text:" (get-in result [:response :text]))
        (println "    tools used:" (mapv :name (:tool-calls-made result))))))

  (wait 3000)

  (subsection "5.2 只使用 :read-only 工具")
  (safe-call "询问天气（只用 read-only）"
    (fn []
      (let [result (kernel/invoke kernel-anthropic
                     [{:role "user" :content "北京天气怎么样？"}]
                     {:tags [:read-only]})]
        (println "    text:" (get-in result [:response :text]))
        (println "    tools used:" (mapv :name (:tool-calls-made result))))))

  (wait 3000)

  (subsection "5.3 排除 :dangerous 工具")
  (safe-call "询问时间（排除 dangerous）"
    (fn []
      (let [result (kernel/invoke kernel-anthropic
                     [{:role "user" :content "现在几点了？"}]
                     {:exclude-tags [:dangerous]})]
        (println "    text:" (get-in result [:response :text]))
        (println "    tools used:" (mapv :name (:tool-calls-made result))))))

  (wait 3000)

  (subsection "5.4 只使用 :utility 工具")
  (safe-call "计算数学（只用 utility）"
    (fn []
      (let [result (kernel/invoke kernel-anthropic
                     [{:role "user" :content "计算 100 + 200 + 300"}]
                     {:tags [:utility]})]
        (println "    text:" (get-in result [:response :text]))
        (println "    tools used:" (mapv :name (:tool-calls-made result)))))))

;;; ============================================================
;;; 测试 6: invoke 带 tag 过滤（OpenAI）
;;; ============================================================

(defn test-invoke-with-tags-openai []
  (separator "测试 6: invoke 带 tag 过滤（OpenAI 接口）")

  (subsection "6.1 使用所有工具")
  (safe-call "询问时间（所有工具可用）"
    (fn []
      (let [result (kernel/invoke kernel-openai
                     [{:role "user" :content "现在几点？"}]
                     {})]
        (println "    text:" (get-in result [:response :text]))
        (println "    tools used:" (mapv :name (:tool-calls-made result))))))

  (wait 3000)

  (subsection "6.2 只使用 :weather 工具")
  (safe-call "询问天气（只用 weather）"
    (fn []
      (let [result (kernel/invoke kernel-openai
                     [{:role "user" :content "上海天气如何？"}]
                     {:tags [:weather]})]
        (println "    text:" (get-in result [:response :text]))
        (println "    tools used:" (mapv :name (:tool-calls-made result))))))

  (wait 3000)

  (subsection "6.3 排除 :write 工具")
  (safe-call "询问时间（排除 write）"
    (fn []
      (let [result (kernel/invoke kernel-openai
                     [{:role "user" :content "帮我计算 50 * 20"}]
                     {:exclude-tags [:write]})]
        (println "    text:" (get-in result [:response :text]))
        (println "    tools used:" (mapv :name (:tool-calls-made result)))))))

;;; ============================================================
;;; 运行所有测试
;;; ============================================================

(defn run-all []
  (println)
  (println "╔═══════════════════════════════════════════════════════════╗")
  (println "║   新 Tool API 测试（add-tools + tags 过滤）               ║")
  (println "║   使用 GLM-4.7 Anthropic/OpenAI 兼容接口                 ║")
  (println "╚═══════════════════════════════════════════════════════════╝")

  (test-tool-metadata)
  (test-kernel-build)
  (test-tag-queries)
  (test-invoke-tool)

  (println)
  (println ">>> 开始 LLM 调用测试（Anthropic 接口）...")
  (test-invoke-with-tags-anthropic)

  (wait 5000)

  (println)
  (println ">>> 开始 LLM 调用测试（OpenAI 接口）...")
  (test-invoke-with-tags-openai)

  (separator "所有测试完成"))

(run-all)
