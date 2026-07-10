(ns im.ttalk.agent.callbacks-integration-test
  "Callback 体系集成验证 —— 全程 MockProvider，不需要 API Key。

   覆盖场景：
   1. 基础 turn 回调（on-turn-start/end）
   2. LLM + 工具回调（on-llm-call/result/on-tool-call/on-tool-result）
   3. on-tool-call 中断（返回 {:interrupt ...} 触发暂停）
   4. 中断/恢复回调（on-interrupt/on-resume）
   5. on-turn-error 触发（max-iterations 超限）
   6. delegate-tool 子 Agent 委派"
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [im.ttalk.agent.client :as agent]
            [im.ttalk.agent.tool :refer [deftool]]
            [im.ttalk.agent.subagent.delegate :as delegate]
            [im.ttalk.agent.test-support :as ts]))

;;; ============================================================
;;; 本文件专用工具（带 :sensitive 标记，仅用于描述语义，不影响 gate）
;;; ============================================================

(deftool cb-send-message
  "发送消息（用于验证 on-tool-call 手动中断）"
  [[to :string "收件人"] [text :string "内容"]]
  (str "已发送给 " to ": " text))

;;; ============================================================
;;; 辅助：日志原子 + 断言
;;; ============================================================

(defn- event-count [log kw]
  (count (filter #(= kw (first %)) @log)))

(defn- first-event [log kw]
  (first (filter #(= kw (first %)) @log)))

;;; ============================================================
;;; 场景 1：基础 turn 回调
;;; ============================================================

(deftest test-turn-callbacks
  (testing "on-turn-start/end 各触发一次，turn-count 正确递增"
    (let [log (atom [])
          p (ts/create-mock-provider
              [{:text "你好！" :tool-calls nil}])
          a (agent/create-agent
              {:provider p :model "test"
               :callbacks {:on-turn-start (fn [m]
                                            (swap! log conj [:start
                                                             (:turn-count m)
                                                             (:agent-id m)]))
                           :on-turn-end   (fn [m]
                                            (swap! log conj [:end
                                                             (:turn-count m)]))}})]
      (let [r (agent/chat a "你好")]
        (is (= :completed (:status r)) "status :completed")
        (is (= 1 (event-count log :start)) "on-turn-start 触发 1 次")
        (is (= 1 (event-count log :end))   "on-turn-end 触发 1 次")
        ;; 在 on-turn-start 时 turn-count=0（尚未完成）
        (is (= 0 (second (first-event log :start))) "start 时 turn-count=0")
        ;; 在 on-turn-end 时 turn-count=1（完成后递增）
        (is (= 1 (second (first-event log :end))) "end 时 turn-count=1")
        ;; on-turn-start 携带 agent-id
        (is (some? (nth (first-event log :start) 2)) "start 携带 agent-id")
        ;; state-atom 里 turn-count 也已递增
        (is (= 1 (get @(:state-atom a) :turn-count)) "state-atom turn-count=1")))))

;;; ============================================================
;;; 场景 2：LLM + 工具回调
;;; ============================================================

(deftest test-llm-and-tool-callbacks
  (testing "on-llm-call/result 触发 2 次；on-tool-call/result 各触发 1 次且无重复"
    (let [log (atom [])
          p (ts/create-mock-provider
              [{:text nil
                :tool-calls [{:id "t1" :name "mock-get-weather" :args {:city "北京"}}]}
               {:text "北京晴天 25°C" :tool-calls nil}])
          a (agent/create-agent
              {:provider p :model "test"
               :tools ts/mock-tools
               :callbacks
               {:on-llm-call    (fn [msgs _m]
                                  (swap! log conj [:llm-call (count msgs)]))
                :on-llm-result  (fn [resp _m]
                                  (swap! log conj [:llm-result (some? resp)]))
                ;; on-tool-call 接收「关键字名」（来自 gate-of/(:name tc)）
                :on-tool-call   (fn [n args]
                                  (swap! log conj [:tool-call n args])
                                  nil)   ;; 返回 nil = 不中断
                :on-tool-result (fn [n result]
                                  (swap! log conj [:tool-result n result]))}})]

      (let [r (agent/chat a "北京天气？")]
        (is (= :completed (:status r)) "status :completed")

        (testing "on-llm-call 触发 2 次（工具轮 + 文本轮）"
          (is (= 2 (event-count log :llm-call))))

        (testing "on-llm-result 触发 2 次"
          (is (= 2 (event-count log :llm-result))))

        (testing "on-tool-call 恰好触发 1 次（gate 缓存修复，无双重触发）"
          (is (= 1 (event-count log :tool-call))))

        (testing "on-tool-call 收到正确的 tool 名（字符串，v0.2 统一形状）和 args"
          (let [[_ n args] (first-event log :tool-call)]
            (is (= "mock-get-weather" n) "name 是字符串")
            (is (= {:city "北京"} args) "args 包含城市")))

        (testing "on-tool-result 触发 1 次"
          (is (= 1 (event-count log :tool-result))))

        (testing "on-tool-result 收到 tool name（string）和结果字符串"
          (let [[_ n result] (first-event log :tool-result)]
            (is (= "mock-get-weather" n) "name 是 string")
            (is (clojure.string/includes? result "北京") "结果含城市名")))))))

;;; ============================================================
;;; 场景 3：on-tool-call 中断能力
;;; ============================================================

(deftest test-on-tool-call-interrupt
  (testing "on-tool-call 返回 {:interrupt ...} 使 chat 返回 :paused，resume 后完成"
    (let [log (atom [])
          p (ts/create-mock-provider
              [{:text nil
                :tool-calls [{:id "t2" :name "mock-get-weather" :args {:city "上海"}}]}
               {:text "审批通过后：上海晴天" :tool-calls nil}])
          blocked-tools (atom #{"mock-get-weather"})
          a (agent/create-agent
              {:provider p :model "test"
               :tools ts/mock-tools
               :callbacks
               {:on-tool-call  (fn [n _args]
                                  (when (contains? @blocked-tools n)
                                    {:interrupt (str n " 需要人工审批")}))
                :on-interrupt  (fn [info _m]
                                  (swap! log conj [:interrupt (:reason info)]))
                :on-resume     (fn [_info _m]
                                  (swap! log conj [:resume]))}})]

      ;; 第一次调用：应该被 on-tool-call 中断
      (let [r1 (agent/chat a "上海天气？")]
        (is (= :paused (:status r1)) "on-tool-call 中断 → :paused")
        (is (some #(= :interrupt (first %)) @log) "on-interrupt 被触发"))

      ;; 解除拦截后恢复
      (reset! blocked-tools #{})
      (let [r2 (agent/resume a "approved")]
        (is (= :completed (:status r2)) "resume 后 :completed")
        (is (some? (:text r2)) "恢复后有文本回复")
        (is (some #(= :resume (first %)) @log) "on-resume 被触发")))))

;;; ============================================================
;;; 场景 4：on-turn-error 触发（max-iterations 超限）
;;; ============================================================

(deftest test-on-turn-error
  (testing "超过 max-iterations → status :error，on-turn-error 被触发"
    (let [log (atom [])
          endless-calls (repeatedly #(hash-map
                                       :text nil
                                       :tool-calls [{:id "tx"
                                                     :name "mock-get-weather"
                                                     :args {:city "x"}}]))
          p (ts/create-mock-provider (take 20 endless-calls))
          a (agent/create-agent
              {:provider p :model "test"
               :tools ts/mock-tools
               :max-iterations 2
               :callbacks {:on-turn-error (fn [err _m]
                                            (swap! log conj [:error (:type err)]))}})]

      (let [r (agent/chat a "无限循环测试")]
        (is (= :error (:status r)) "超限返回 :error")
        (is (some #(= :error (first %)) @log) "on-turn-error 被触发")))))

;;; ============================================================
;;; 场景 5：子 Agent 委派工具（delegate-tool）
;;; ============================================================

(deftest test-delegate-tool
  (testing "delegate-tool 将任务委派给子 Agent，父 Agent 得到汇总结果"
    (let [log (atom [])
          subagent-factory (fn [args _ctx]
                             {:provider (ts/create-mock-provider
                                          [{:text (str "子agent完成: " (get args :task "任务"))
                                            :tool-calls nil}])
                              :model "test"})

          research-tool (delegate/delegate-tool
                          {:name        "do_research"
                           :description "委派研究任务给子 Agent"
                           :subagent-fn subagent-factory
                           :timeout     10000})

          p (ts/create-mock-provider
              [{:text nil
                :tool-calls [{:id "d1" :name "do_research" :args {:task "分析量子计算"}}]}
               {:text "综合子agent结论：量子计算前景广阔" :tool-calls nil}])

          a (agent/create-agent
              {:provider p :model "test"
               :tools    [research-tool]
               :callbacks {:on-tool-result (fn [n r]
                                             (swap! log conj [:tool-result n r]))}})]

      (let [r (agent/chat a "帮我研究量子计算")]
        (is (= :completed (:status r)) "status :completed")
        (is (some? (:text r)) "父 Agent 得到文本回复")
        (is (clojure.string/includes? (:text r) "量子计算") "文本含关键词")

        (testing "on-tool-result 触发了 do_research，收到子 Agent 结果"
          (let [[_ n result] (first-event log :tool-result)]
            (is (= "do_research" n) "tool name 是 do_research")
            (is (clojure.string/includes? result "子agent完成") "结果来自子 Agent")))))))

;;; ============================================================
;;; 运行
;;; ============================================================

(defn -main [& _]
  (println)
  (println "+--------------------------------------------------+")
  (println "|  Callback 体系集成验证（MockProvider，无需 API）  |")
  (println "+--------------------------------------------------+")
  (run-tests 'im.ttalk.agent.callbacks-integration-test))
