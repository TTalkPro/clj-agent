(ns process-test
  "Process Framework 功能测试 - 使用 GLM-4.7 OpenAI 兼容接口

   覆盖场景:
   1. 基本线性流程
   2. 单轮对话（Step 内调用 LLM）
   3. 多轮对话（多 Step 累积 context）
   4. 上下文传递
   5. 工具调用（Step 内使用 invoke-tool / invoke）
   6. Filter/Hook（各层级 pre/post filter）
   7. 暂停/恢复（human-in-the-loop）
   8. Fan-out/Fan-in 模式

   运行: clojure -M -e \"(load-file \\\"examples/process_test.clj\\\")\"

   注意: Zhipu 免费套餐有速率限制，测试间加了等待间隔。"
  (:require [im.ttalk.agent.core.kernel.tool :refer [deftool]]
            [im.ttalk.agent.core.kernel.plugin :as kp]
            [im.ttalk.agent.core.kernel.core :as kernel]
            [im.ttalk.agent.core.kernel.filter :as filters]
            [im.ttalk.agent.core.kernel.context :as ctx]
            [im.ttalk.agent.core.kernel.process.builder :as builder]
            [im.ttalk.agent.core.kernel.process.runtime :as runtime]
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

(deftool translate
  "将文本翻译为目标语言"
  [[text :string "要翻译的文本"]
   [target-lang :string "目标语言"]]
  (str "[翻译结果] " text " → (" target-lang ")"))

(deftool summarize-text
  "摘要生成（模拟）"
  [[text :string "要摘要的文本"]]
  (str "摘要: " (subs text 0 (min 50 (count text))) "..."))

;;; ============================================================
;;; Plugin & Kernel 构建
;;; ============================================================

(kp/defplugin process-tools "Process 测试工具集"
  get-weather get-time translate summarize-text)

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
      (kernel/add-plugin process-tools)
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
      (when-let [d (ex-data e)]
        (println (str "    详情: " (pr-str d))))
      nil)))

;;; ============================================================
;;; 测试 1: 基本线性流程（纯 step 编排，不调用 LLM）
;;; ============================================================

(defn test-linear-flow []
  (separator "测试 1: 基本线性流程")
  (safe-call "线性流程 A → B → C"
    (fn []
      (let [process-spec
            (-> (builder/builder :linear-demo)
                (builder/add-step
                  {:id :step-a
                   :on-activate (fn [inputs _state ctx]
                                  (let [data (str (:input inputs) " → 经过A")]
                                    (println (str "    [step-a] 处理: " data))
                                    {:events [{:name :a-done :data data}]
                                     :context (ctx/set-var ctx :step-a-output data)}))})
                (builder/add-step
                  {:id :step-b
                   :on-activate (fn [inputs _state ctx]
                                  (let [data (str (:input inputs) " → 经过B")]
                                    (println (str "    [step-b] 处理: " data))
                                    {:events [{:name :b-done :data data}]
                                     :context (ctx/set-var ctx :step-b-output data)}))})
                (builder/add-step
                  {:id :step-c
                   :on-activate (fn [inputs _state ctx]
                                  (println (str "    [step-c] 最终输出: " (:input inputs)))
                                  {:context (ctx/set-var ctx :final-result (:input inputs))})})
                (builder/on-event :start :step-a :input)
                (builder/on-event :a-done :step-b :input)
                (builder/on-event :b-done :step-c :input)
                (builder/set-initial-event :start "原始数据")
                (builder/build))
            result (runtime/run-process process-spec {:timeout-ms 30000})]
        (println (str "    状态: " (:status result)))
        (println (str "    结果: " (ctx/get-var (:context result) :final-result)))
        (assert (= :completed (:status result)))
        (assert (= "原始数据 → 经过A → 经过B"
                   (ctx/get-var (:context result) :final-result)))))))

;;; ============================================================
;;; 测试 2: 单轮对话（Step 内调用 invoke-chat）
;;; ============================================================

(defn test-single-turn-chat []
  (separator "测试 2: 单轮对话（Step 内调用 LLM）")
  (safe-call "单轮 LLM 对话"
    (fn []
      (let [process-spec
            (-> (builder/builder :single-chat)
                (builder/add-step
                  {:id :chat-step
                   :on-activate (fn [inputs _state ctx]
                                  (let [question (:input inputs)
                                        _ (println (str "    [chat-step] 提问: " question))
                                        {:keys [response context]}
                                        (kernel/invoke-chat app-kernel
                                          [{:role "user" :content question}]
                                          {:context ctx})]
                                    (println (str "    [chat-step] LLM 回复: " (:text response)))
                                    {:events [{:name :chat-done :data (:text response)}]
                                     :context context}))})
                (builder/add-step
                  {:id :result-step
                   :on-activate (fn [inputs _state ctx]
                                  {:context (ctx/set-var ctx :answer (:input inputs))})})
                (builder/on-event :start :chat-step :input)
                (builder/on-event :chat-done :result-step :input)
                (builder/set-initial-event :start "用一句话介绍中国的首都。")
                (builder/build))
            result (runtime/run-process process-spec {:timeout-ms 30000})]
        (println (str "    状态: " (:status result)))
        (println (str "    回答: " (ctx/get-var (:context result) :answer)))
        (assert (= :completed (:status result)))
        (assert (some? (ctx/get-var (:context result) :answer)))))))

;;; ============================================================
;;; 测试 3: 多轮对话（多 Step 累积 context）
;;; ============================================================

(defn test-multi-turn-chat []
  (separator "测试 3: 多轮对话（context 累积）")
  (safe-call "多轮对话 context 传递"
    (fn []
      (let [process-spec
            (-> (builder/builder :multi-turn)
                (builder/add-step
                  {:id :turn-1
                   :on-activate (fn [inputs _state ctx]
                                  (let [question (:input inputs)
                                        _ (println (str "    [turn-1] 提问: " question))
                                        ;; 使用 invoke 以确保消息被 track 到 context
                                        {:keys [response context]}
                                        (kernel/invoke app-kernel
                                          [{:role "user" :content question}]
                                          {:context ctx
                                           :tool-choice :none})
                                        answer (get-in response [:text])]
                                    (println (str "    [turn-1] 回复: " answer))
                                    {:events [{:name :turn-1-done :data answer}]
                                     :context context}))})
                (builder/add-step
                  {:id :turn-2
                   :on-activate (fn [inputs _state ctx]
                                  ;; 第二轮使用 context 中已有的 messages（由 invoke 自动拼接）
                                  (let [follow-up "用一个词概括你刚才的回答。"
                                        _ (println (str "    [turn-2] 追问: " follow-up))
                                        _ (println (str "    [turn-2] context.messages 已有: "
                                                        (count (ctx/get-messages ctx)) " 条"))
                                        ;; invoke 会自动读取 context.messages 拼接历史
                                        {:keys [response context]}
                                        (kernel/invoke app-kernel
                                          [{:role "user" :content follow-up}]
                                          {:context ctx})
                                        answer (get-in response [:text])]
                                    (println (str "    [turn-2] 回复: " answer))
                                    {:events [{:name :done :data answer}]
                                     :context context}))})
                (builder/add-step
                  {:id :final
                   :on-activate (fn [inputs _state ctx]
                                  {:context (ctx/set-var ctx :multi-turn-result (:input inputs))})})
                (builder/on-event :start :turn-1 :input)
                (builder/on-event :turn-1-done :turn-2 :input)
                (builder/on-event :done :final :input)
                (builder/set-initial-event :start "中国有多少个省份？简短回答。")
                (builder/build))
            result (runtime/run-process process-spec {:timeout-ms 30000})]
        (println (str "    状态: " (:status result)))
        (println (str "    最终结果: " (ctx/get-var (:context result) :multi-turn-result)))
        (println (str "    messages 数量: " (count (ctx/get-messages (:context result)))))
        (println (str "    history 数量: " (count (ctx/get-history (:context result)))))
        (assert (= :completed (:status result)))))))

;;; ============================================================
;;; 测试 4: 上下文传递（Step 间共享 context 变量）
;;; ============================================================

(defn test-context-passing []
  (separator "测试 4: 上下文传递")
  (safe-call "Step 间 context 变量传递"
    (fn []
      (let [process-spec
            (-> (builder/builder :ctx-passing)
                (builder/add-step
                  {:id :init-step
                   :on-activate (fn [inputs _state ctx]
                                  (let [ctx (-> ctx
                                                (ctx/set-var :user-name "小明")
                                                (ctx/set-var :user-city "深圳")
                                                (ctx/set-var :step-trace [:init]))]
                                    (println (str "    [init] 设置用户信息"))
                                    {:events [{:name :inited :data nil}]
                                     :context ctx}))})
                (builder/add-step
                  {:id :enrich-step
                   :on-activate (fn [inputs _state ctx]
                                  (let [city (ctx/get-var ctx :user-city)
                                        _ (println (str "    [enrich] 获取 " city " 天气"))
                                        {:keys [value context]}
                                        (kernel/invoke-tool app-kernel :get-weather
                                                            {:city city} ctx)
                                        ctx (-> context
                                                (ctx/set-var :weather value)
                                                (update-in [:variables :step-trace] conj :enrich))]
                                    {:events [{:name :enriched :data nil}]
                                     :context ctx}))})
                (builder/add-step
                  {:id :summary-step
                   :on-activate (fn [inputs _state ctx]
                                  (let [name (ctx/get-var ctx :user-name)
                                        weather (ctx/get-var ctx :weather)
                                        summary (str name " 在 " (ctx/get-var ctx :user-city)
                                                     " | 天气: " weather)
                                        ctx (-> ctx
                                                (ctx/set-var :summary summary)
                                                (update-in [:variables :step-trace] conj :summary))]
                                    (println (str "    [summary] " summary))
                                    {:context ctx}))})
                (builder/on-event :start :init-step :input)
                (builder/on-event :inited :enrich-step :input)
                (builder/on-event :enriched :summary-step :input)
                (builder/set-initial-event :start nil)
                (builder/build))
            result (runtime/run-process process-spec {:timeout-ms 30000})]
        (println (str "    状态: " (:status result)))
        (println (str "    trace: " (ctx/get-var (:context result) :step-trace)))
        (println (str "    summary: " (ctx/get-var (:context result) :summary)))
        (assert (= :completed (:status result)))
        (assert (= [:init :enrich :summary]
                   (ctx/get-var (:context result) :step-trace)))))))

;;; ============================================================
;;; 测试 5: 工具调用（Step 内使用 invoke 工具循环）
;;; ============================================================

(defn test-tool-usage []
  (separator "测试 5: 工具调用（Step 内使用 invoke）")
  (safe-call "Process 中使用工具"
    (fn []
      (let [process-spec
            (-> (builder/builder :tool-process)
                (builder/add-step
                  {:id :query-step
                   :on-activate (fn [inputs _state ctx]
                                  (let [question (:input inputs)
                                        _ (println (str "    [query] 问题: " question))
                                        ;; 使用 invoke 驱动 LLM + 工具调用循环
                                        result (kernel/invoke app-kernel
                                                 [{:role "user" :content question}]
                                                 {:context ctx})]
                                    (println (str "    [query] 使用工具: "
                                                  (mapv :name (:tool-calls-made result))))
                                    (println (str "    [query] 回答: "
                                                  (get-in result [:response :text])))
                                    {:events [{:name :answered
                                               :data {:answer (get-in result [:response :text])
                                                      :tools-used (mapv :name (:tool-calls-made result))}}]
                                     :context (:context result)}))})
                (builder/add-step
                  {:id :collect-step
                   :on-activate (fn [inputs _state ctx]
                                  (let [data (:input inputs)]
                                    {:context (-> ctx
                                                  (ctx/set-var :answer (:answer data))
                                                  (ctx/set-var :tools-used (:tools-used data)))}))})
                (builder/on-event :start :query-step :input)
                (builder/on-event :answered :collect-step :input)
                (builder/set-initial-event :start "现在几点了？")
                (builder/build))
            result (runtime/run-process process-spec {:timeout-ms 60000})]
        (println (str "    状态: " (:status result)))
        (when (= :failed (:status result))
          (println (str "    错误: " (:error result))))
        (println (str "    工具调用: " (ctx/get-var (:context result) :tools-used)))
        (assert (= :completed (:status result)))
        (assert (some? (ctx/get-var (:context result) :answer)))))))

;;; ============================================================
;;; 测试 6: Filter/Hook（各层级 filter 在 Process 中的效果）
;;; ============================================================

(defn test-filters-in-process []
  (separator "测试 6: Filter/Hook（pre/post invocation + pre/post chat）")
  (safe-call "Process 中 filter 生效"
    (fn []
      (let [;; 创建带自定义 filter 的 kernel
            call-log (atom [])

            pre-inv-filter
            (filters/create-filter :test-pre-inv :pre-invocation
              (fn [filter-ctx]
                (swap! call-log conj {:type :pre-invocation
                                      :fn (:name (:function filter-ctx))})
                {:action :continue :context filter-ctx}))

            post-inv-filter
            (filters/create-filter :test-post-inv :post-invocation
              (fn [filter-ctx]
                (swap! call-log conj {:type :post-invocation
                                      :fn (:name (:function filter-ctx))
                                      :result-len (count (str (:result filter-ctx)))})
                {:action :continue :context filter-ctx}))

            pre-chat-filter
            (filters/create-filter :test-pre-chat :pre-chat
              (fn [filter-ctx]
                (swap! call-log conj {:type :pre-chat
                                      :msg-count (count (:messages filter-ctx))})
                {:action :continue :context filter-ctx}))

            post-chat-filter
            (filters/create-filter :test-post-chat :post-chat
              (fn [filter-ctx]
                (swap! call-log conj {:type :post-chat
                                      :has-text (some? (:text (:response filter-ctx)))})
                {:action :continue :context filter-ctx}))

            filtered-kernel
            (-> (kernel/create-kernel-builder {:max-tool-iterations 5})
                (kernel/add-service service)
                (kernel/add-plugin process-tools)
                (kernel/add-filter pre-inv-filter)
                (kernel/add-filter post-inv-filter)
                (kernel/add-filter pre-chat-filter)
                (kernel/add-filter post-chat-filter)
                (kernel/build-kernel))

            process-spec
            (-> (builder/builder :filtered-process)
                (builder/add-step
                  {:id :tool-step
                   :on-activate (fn [inputs _state ctx]
                                  (let [{:keys [value context]}
                                        (kernel/invoke-tool filtered-kernel
                                          :get-weather {:city "上海"} ctx)]
                                    (println (str "    [tool-step] 工具结果: " value))
                                    {:events [{:name :tool-done :data value}]
                                     :context context}))})
                (builder/add-step
                  {:id :chat-step
                   :on-activate (fn [inputs _state ctx]
                                  (let [{:keys [response context]}
                                        (kernel/invoke-chat filtered-kernel
                                          [{:role "user" :content (str "天气信息: " (:input inputs)
                                                                       "。用一句话总结。")}]
                                          {:context ctx})]
                                    (println (str "    [chat-step] LLM: " (:text response)))
                                    {:context (ctx/set-var context :summary (:text response))}))})
                (builder/on-event :start :tool-step :input)
                (builder/on-event :tool-done :chat-step :input)
                (builder/set-initial-event :start nil)
                (builder/build))
            result (runtime/run-process process-spec {:timeout-ms 30000})]
        (println (str "    状态: " (:status result)))
        (println (str "    Filter 日志:"))
        (doseq [entry @call-log]
          (println (str "      " (pr-str entry))))
        (assert (= :completed (:status result)))
        ;; 验证 filter 被正确触发
        (assert (some #(= :pre-invocation (:type %)) @call-log)
                "pre-invocation filter 应被触发")
        (assert (some #(= :post-invocation (:type %)) @call-log)
                "post-invocation filter 应被触发")
        (assert (some #(= :pre-chat (:type %)) @call-log)
                "pre-chat filter 应被触发")
        (assert (some #(= :post-chat (:type %)) @call-log)
                "post-chat filter 应被触发")
        (println (str "    ✓ 所有 4 类型 filter 均已触发"))))))

;;; ============================================================
;;; 测试 7: 暂停/恢复（human-in-the-loop）
;;; ============================================================

(defn test-pause-resume []
  (separator "测试 7: 暂停/恢复（Human-in-the-loop）")
  (safe-call "暂停和恢复流程"
    (fn []
      (let [process-spec
            (-> (builder/builder :approval-process)
                (builder/add-step
                  {:id :prepare-step
                   :on-activate (fn [inputs _state ctx]
                                  (let [action (:input inputs)]
                                    (println (str "    [prepare] 准备操作: " action))
                                    {:events [{:name :need-approval
                                               :data {:action action :risk "high"}}]
                                     :context (ctx/set-var ctx :action action)}))})
                (builder/add-step
                  {:id :approval-step
                   :on-activate (fn [inputs _state _ctx]
                                  (let [data (:input inputs)]
                                    (println (str "    [approval] 等待审批: " (:action data)))
                                    {:pause {:reason (str "操作「" (:action data) "」需要审批")
                                             :state {:pending-action data}}}))
                   :on-resume (fn [decision state ctx]
                                (println (str "    [approval] 收到审批: " decision))
                                (if (= "approved" decision)
                                  {:events [{:name :approved
                                             :data (:pending-action state)}]
                                   :state nil}
                                  {:events [{:name :rejected
                                             :data {:reason decision}}]
                                   :state nil}))})
                (builder/add-step
                  {:id :execute-step
                   :on-activate (fn [inputs _state ctx]
                                  (println (str "    [execute] 执行操作: " (:action (:input inputs))))
                                  {:context (ctx/set-var ctx :executed true)})})
                (builder/add-step
                  {:id :reject-step
                   :on-activate (fn [inputs _state ctx]
                                  (println (str "    [reject] 操作被拒绝: " (:reason (:input inputs))))
                                  {:context (ctx/set-var ctx :rejected true)})})
                (builder/on-event :start :prepare-step :input)
                (builder/on-event :need-approval :approval-step :input)
                (builder/on-event :approved :execute-step :input)
                (builder/on-event :rejected :reject-step :input)
                (builder/set-initial-event :start "删除生产数据库")
                (builder/build))

            ;; 运行到暂停
            paused (runtime/run-process process-spec {:timeout-ms 30000})]
        (println (str "    --- 状态: " (:status paused)))
        (println (str "    --- 暂停原因: " (:pause-reason paused)))
        (assert (= :paused (:status paused)))
        (assert (= :approval-step (:paused-step paused)))

        ;; 模拟审批通过
        (println (str "    --- 模拟审批: approved"))
        (let [result (runtime/run-resume paused "approved")]
          (println (str "    --- 恢复后状态: " (:status result)))
          (assert (= :completed (:status result)))
          (assert (true? (ctx/get-var (:context result) :executed)))
          (println (str "    ✓ 审批通过，操作已执行")))

        ;; 再次运行，模拟审批拒绝
        (println)
        (println (str "    --- 再次运行，模拟审批拒绝 ---"))
        (let [paused2 (runtime/run-process process-spec {:timeout-ms 10000})
              result (runtime/run-resume paused2 "rejected")]
          (println (str "    --- 恢复后状态: " (:status result)))
          (assert (= :completed (:status result)))
          (assert (true? (ctx/get-var (:context result) :rejected)))
          (println (str "    ✓ 审批拒绝，操作未执行")))))))

;;; ============================================================
;;; 测试 8: Fan-out/Fan-in 模式
;;; ============================================================

(defn test-fan-out-fan-in []
  (separator "测试 8: Fan-out/Fan-in 模式")
  (safe-call "并行分支汇聚"
    (fn []
      (let [process-spec
            (-> (builder/builder :fan-pattern)
                ;; 分发 step
                (builder/add-step
                  {:id :dispatcher
                   :on-activate (fn [inputs _state ctx]
                                  (let [cities (:input inputs)]
                                    (println (str "    [dispatcher] 查询城市: " cities))
                                    ;; 产出多个事件触发各分支
                                    {:events [{:name :query-city-a :data (first cities)}
                                              {:name :query-city-b :data (second cities)}]
                                     :context ctx}))})
                ;; 分支 A
                (builder/add-step
                  {:id :branch-a
                   :on-activate (fn [inputs _state ctx]
                                  (let [city (:input inputs)
                                        {:keys [value context]}
                                        (kernel/invoke-tool app-kernel :get-weather
                                                            {:city city} ctx)]
                                    (println (str "    [branch-a] " city " → " value))
                                    {:events [{:name :a-result :data {:city city :weather value}}]
                                     :context context}))})
                ;; 分支 B
                (builder/add-step
                  {:id :branch-b
                   :on-activate (fn [inputs _state ctx]
                                  (let [city (:input inputs)
                                        {:keys [value context]}
                                        (kernel/invoke-tool app-kernel :get-weather
                                                            {:city city} ctx)]
                                    (println (str "    [branch-b] " city " → " value))
                                    {:events [{:name :b-result :data {:city city :weather value}}]
                                     :context context}))})
                ;; 汇聚 step（需要两个输入都到达）
                (builder/add-step
                  {:id :aggregator
                   :required-inputs [:from-a :from-b]
                   :on-activate (fn [inputs _state ctx]
                                  (let [a (:from-a inputs)
                                        b (:from-b inputs)
                                        combined {:cities [a b]}]
                                    (println (str "    [aggregator] 汇聚结果:"))
                                    (println (str "      " (:city a) ": " (:weather a)))
                                    (println (str "      " (:city b) ": " (:weather b)))
                                    {:context (ctx/set-var ctx :weather-report combined)}))})
                (builder/on-event :start :dispatcher :input)
                (builder/on-event :query-city-a :branch-a :input)
                (builder/on-event :query-city-b :branch-b :input)
                (builder/on-event :a-result :aggregator :from-a)
                (builder/on-event :b-result :aggregator :from-b)
                (builder/set-initial-event :start ["北京" "上海"])
                (builder/build))
            result (runtime/run-process process-spec {:timeout-ms 30000})]
        (println (str "    状态: " (:status result)))
        (let [report (ctx/get-var (:context result) :weather-report)]
          (println (str "    城市数: " (count (:cities report))))
          (assert (= :completed (:status result)))
          (assert (= 2 (count (:cities report)))))))))

;;; ============================================================
;;; 测试 9: Step 状态持久化 + 循环
;;; ============================================================

(defn test-stateful-loop []
  (separator "测试 9: Step 状态持久化 + 循环")
  (safe-call "带状态的循环 step"
    (fn []
      (let [process-spec
            (-> (builder/builder :stateful-loop)
                (builder/add-step
                  {:id :counter
                   :init (fn [_] {:iterations 0 :results []})
                   :on-activate (fn [inputs state ctx]
                                  (let [n (inc (:iterations state))
                                        city (:input inputs)
                                        {:keys [value context]}
                                        (kernel/invoke-tool app-kernel :get-weather
                                                            {:city (str city "-" n)} ctx)
                                        new-state {:iterations n
                                                   :results (conj (:results state) value)}]
                                    (println (str "    [counter] 第 " n " 次: " value))
                                    (if (>= n 3)
                                      {:events [{:name :loop-done :data new-state}]
                                       :state new-state
                                       :context context}
                                      {:events [{:name :loop-again :data city}]
                                       :state new-state
                                       :context context})))})
                (builder/add-step
                  {:id :final
                   :on-activate (fn [inputs _state ctx]
                                  (let [data (:input inputs)]
                                    (println (str "    [final] 循环完成, 共 "
                                                  (:iterations data) " 次"))
                                    {:context (ctx/set-var ctx :loop-result data)}))})
                (builder/on-event :start :counter :input)
                (builder/on-event :loop-again :counter :input)
                (builder/on-event :loop-done :final :input)
                (builder/set-initial-event :start "测试城市")
                (builder/build))
            result (runtime/run-process process-spec {:timeout-ms 30000})]
        (println (str "    状态: " (:status result)))
        (let [loop-result (ctx/get-var (:context result) :loop-result)]
          (println (str "    迭代次数: " (:iterations loop-result)))
          (println (str "    结果数: " (count (:results loop-result))))
          (assert (= :completed (:status result)))
          (assert (= 3 (:iterations loop-result))))))))

;;; ============================================================
;;; 测试 10: 错误处理（error-handler step）
;;; ============================================================

(defn test-error-handling []
  (separator "测试 10: 错误处理")
  (safe-call "error-handler 捕获 step 错误"
    (fn []
      (let [process-spec
            (-> (builder/builder :error-process)
                (builder/add-step
                  {:id :risky-step
                   :on-activate (fn [inputs _state _ctx]
                                  (println (str "    [risky] 执行高风险操作..."))
                                  {:error {:reason "模拟错误: 数据库连接超时"}})})
                (builder/add-step
                  {:id :error-handler
                   :required-inputs [:error]
                   :on-activate (fn [inputs _state ctx]
                                  (let [error-info (:error inputs)]
                                    (println (str "    [error-handler] 捕获错误: "
                                                  (:reason error-info)))
                                    {:context (ctx/set-var ctx :error-handled
                                                (:reason error-info))}))})
                (builder/on-event :start :risky-step :input)
                (builder/set-error-handler :error-handler)
                (builder/set-initial-event :start "go")
                (builder/build))
            result (runtime/run-process process-spec {:timeout-ms 30000})]
        (println (str "    状态: " (:status result)))
        (println (str "    错误处理: " (ctx/get-var (:context result) :error-handled)))
        (assert (= :completed (:status result)))
        (assert (= "模拟错误: 数据库连接超时"
                   (ctx/get-var (:context result) :error-handled)))))))

;;; ============================================================
;;; 测试 11: 带 Transform 的绑定
;;; ============================================================

(defn test-transform-binding []
  (separator "测试 11: 带 Transform 的事件绑定")
  (safe-call "绑定 transform 数据转换"
    (fn []
      (let [process-spec
            (-> (builder/builder :transform-demo)
                (builder/add-step
                  {:id :source
                   :on-activate (fn [inputs _state _ctx]
                                  (println (str "    [source] 输出: " (:input inputs)))
                                  {:events [{:name :raw-data
                                             :data {:name "张三"
                                                    :age 30
                                                    :city "广州"}}]})})
                (builder/add-step
                  {:id :name-consumer
                   :on-activate (fn [inputs _state ctx]
                                  (println (str "    [name] 收到: " (:input inputs)))
                                  {:context (ctx/set-var ctx :got-name (:input inputs))})})
                (builder/add-step
                  {:id :city-consumer
                   :on-activate (fn [inputs _state ctx]
                                  (println (str "    [city] 收到: " (:input inputs)))
                                  {:context (ctx/set-var ctx :got-city (:input inputs))})})
                (builder/on-event :start :source :input)
                ;; Transform: 从 raw-data 提取 name
                (builder/on-event :raw-data :name-consumer :input
                                  (fn [data] (:name data)))
                ;; Transform: 从 raw-data 提取 city 并加工
                (builder/on-event :raw-data :city-consumer :input
                                  (fn [data] (str "城市: " (:city data))))
                (builder/set-initial-event :start "go")
                (builder/build))
            result (runtime/run-process process-spec {:timeout-ms 30000})]
        (println (str "    状态: " (:status result)))
        (assert (= :completed (:status result)))
        (assert (= "张三" (ctx/get-var (:context result) :got-name)))
        (assert (= "城市: 广州" (ctx/get-var (:context result) :got-city)))))))

;;; ============================================================
;;; 运行所有测试
;;; ============================================================

(defn run-all []
  (println)
  (println "╔═══════════════════════════════════════════════════════════╗")
  (println "║   Process Framework 功能测试 (GLM-4.7 OpenAI 兼容)      ║")
  (println "╚═══════════════════════════════════════════════════════════╝")

  ;; 纯逻辑测试（无 LLM 调用）
  (test-linear-flow)
  (test-context-passing)
  (test-pause-resume)
  (test-fan-out-fan-in)
  (test-stateful-loop)
  (test-error-handling)
  (test-transform-binding)

  ;; 涉及 LLM 调用的测试（带等待间隔）
  (wait 3000)
  (test-single-turn-chat)
  (wait 5000)
  (test-tool-usage)
  (wait 5000)
  (test-filters-in-process)
  (wait 5000)
  (test-multi-turn-chat)

  (separator "全部完成"))

(run-all)
