(ns integration-test
  "集成测试 - GLM-4.7 OpenAI 兼容接口

   覆盖场景:
   1. 单轮对话
   2. 多轮对话
   3. 工具调用
   4. Memory 多轮对话 + Snapshot/Restore

   运行: clojure -M -e \"(load-file \\\"examples/integration_test.clj\\\")\"

   环境变量: ZHIPU_API_KEY"
  (:require [im.ttalk.agent.core.kernel.tool :refer [deftool]]
            [im.ttalk.agent.core.kernel :as kernel]
            [im.ttalk.agent.core.kernel.context :as ctx]
            [im.ttalk.agent.core.kernel.process.builder :as builder]
            [im.ttalk.agent.core.kernel.process.runtime :as runtime]
            [im.ttalk.agent.llm.kernel.chat :as chat]
            [im.ttalk.agent.llm.provider.openai :as openai]
            [im.ttalk.agent.memory.api :as mem]))

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
;;; 测试 4: Memory 多轮对话 + Process Snapshot/Restore
;;; ============================================================

(defn test-memory-snapshot-restore []
  (separator "测试 4: Memory + Process Snapshot/Restore")

  ;; 创建记忆组件
  (let [buffer (mem/create-conversation-buffer)
        snapshot-store (mem/create-memory-snapshot-store)]

    ;; --- 4a: 使用 memory buffer 管理对话 ---
    (println "  --- 4a: Memory Buffer 管理对话 ---")

    ;; 第一轮对话
    (mem/add-message buffer {:role "user" :content "我最喜欢的编程语言是 Clojure。"})
    (let [msgs (mem/get-messages buffer)
          r1 (kernel/invoke-chat app-kernel msgs {:context (ctx/create)})]
      (mem/add-message buffer {:role "assistant" :content (get-in r1 [:response :text])})
      (println (str "  轮1: " (get-in r1 [:response :text]))))

    (wait 2000)

    ;; 第二轮对话（基于 buffer 积累的历史）
    (mem/add-message buffer {:role "user" :content "我最喜欢的语言是什么？"})
    (let [msgs (mem/get-messages buffer)
          r2 (kernel/invoke-chat app-kernel msgs {:context (ctx/create)})]
      (mem/add-message buffer {:role "assistant" :content (get-in r2 [:response :text])})
      (println (str "  轮2: " (get-in r2 [:response :text])))
      (assert (clojure.string/includes? (get-in r2 [:response :text]) "Clojure")
              "模型应从 buffer 历史中回忆出 Clojure"))

    (println (str "  Buffer 消息数: " (mem/count-messages buffer)))
    (println "  ✓ Memory Buffer 多轮对话成功")
    (println)

    ;; --- 4b: Snapshot 保存状态 ---
    (println "  --- 4b: SnapshotStore 保存/恢复 ---")

    (let [state-v1 {:messages (mem/get-messages buffer)
                    :turn-count 2
                    :user-pref "Clojure"}]
      ;; 保存快照
      (mem/snap-put snapshot-store
                    {:thread-id "session-1"}
                    {:state state-v1}
                    {:step 1 :description "2轮对话后"})
      (println "  已保存快照 step=1")

      ;; 继续对话（第三轮）
      (wait 2000)
      (mem/add-message buffer {:role "user" :content "帮我用 Clojure 写一个 hello world。"})
      (let [msgs (mem/get-messages buffer)
            r3 (kernel/invoke-chat app-kernel msgs {:context (ctx/create)})]
        (mem/add-message buffer {:role "assistant" :content (get-in r3 [:response :text])})
        (println (str "  轮3: " (subs (get-in r3 [:response :text]) 0
                                       (min 80 (count (get-in r3 [:response :text]))))
                      "...")))

      ;; 保存第二个快照
      (let [state-v2 {:messages (mem/get-messages buffer)
                      :turn-count 3
                      :user-pref "Clojure"}]
        (mem/snap-put snapshot-store
                      {:thread-id "session-1"}
                      {:state state-v2}
                      {:step 2 :description "3轮对话后"})
        (println "  已保存快照 step=2"))

      ;; 恢复到 step=1 的快照
      (let [restored (mem/snap-get snapshot-store {:thread-id "session-1"})
            history (mem/snap-get-history snapshot-store "session-1")]
        (println (str "  快照历史: " (count history) " 条"))
        (println (str "  最新快照 turn-count: "
                      (get-in restored [:snapshot :state :turn-count])))
        (assert (= 3 (get-in restored [:snapshot :state :turn-count]))))

      ;; 通过 step 恢复到早期状态
      (let [step1 (mem/snap-restore-to-step snapshot-store "session-1" 1)]
        (println (str "  恢复到 step=1, turn-count: "
                      (get-in step1 [:snapshot :state :turn-count])))
        (assert (= 2 (get-in step1 [:snapshot :state :turn-count])))))

    (println "  ✓ Snapshot 保存/恢复成功")
    (println)

    ;; --- 4c: Process Snapshot/Restore ---
    (println "  --- 4c: Process Snapshot + Restore ---")

    (let [;; 定义一个对话 process（带暂停）
          process-spec
          (-> (builder/builder :memory-chat-process)
              (builder/add-step
                {:id :chat-step
                 :init (fn [_] {:history []})
                 :on-activate
                 (fn [inputs state ctx]
                   (let [user-msg (:input inputs)
                         history (conj (:history state)
                                       {:role "user" :content user-msg})
                         r (kernel/invoke-chat app-kernel history
                             {:context ctx})
                         answer (get-in r [:response :text])
                         new-history (conj history
                                           {:role "assistant" :content answer})]
                     (println (str "    [chat] Q: " user-msg))
                     (println (str "    [chat] A: " (subs answer 0 (min 60 (count answer))) "..."))
                     ;; 暂停，等待下一轮输入
                     {:pause {:reason "等待用户输入"
                              :state {:history new-history}}
                      :state {:history new-history}
                      :context (ctx/set-var ctx :last-answer answer)}))
                 :on-resume
                 (fn [data state ctx]
                   ;; data 是用户的新消息
                   (let [history (:history state)
                         new-history (conj history {:role "user" :content data})
                         r (kernel/invoke-chat app-kernel new-history
                             {:context ctx})
                         answer (get-in r [:response :text])
                         final-history (conj new-history
                                             {:role "assistant" :content answer})]
                     (println (str "    [resume] Q: " data))
                     (println (str "    [resume] A: " (subs answer 0 (min 60 (count answer))) "..."))
                     ;; 完成（不再暂停）
                     {:state {:history final-history}
                      :context (ctx/set-var ctx :last-answer answer)}))})
              (builder/on-event :start :chat-step :input)
              (builder/set-initial-event :start "你好，我是小王。请记住我的名字。")
              (builder/build))

          ;; 运行到暂停
          paused (runtime/run-process process-spec {:timeout-ms 30000})]

      (assert (= :paused (:status paused)) "Process 应暂停")
      (println (str "    Process 暂停: " (:pause-reason paused)))

      ;; 生成纯数据快照
      (let [snapshot (:snapshot paused)]
        (println (str "    Snapshot keys: " (keys snapshot)))
        (assert (map? snapshot))
        (assert (= :paused (:status snapshot)))
        (assert (contains? snapshot :step-states))
        (assert (contains? snapshot :context))

        ;; 从快照恢复（跨进程 rehydration）
        (wait 2000)
        (let [result (runtime/run-restore snapshot process-spec
                       "我叫什么名字？")]
          (println (str "    Restore 状态: " (:status result)))
          (let [answer (ctx/get-var (:context result) :last-answer)]
            (println (str "    最终回答: " answer))
            (assert (= :completed (:status result)))
            (assert (some? answer))
            (println "  ✓ Process Snapshot/Restore 成功")))))))

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
  (wait 3000)
  (test-memory-snapshot-restore)

  (separator "全部测试通过"))

(run-all)
