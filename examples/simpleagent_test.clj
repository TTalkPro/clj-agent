(ns simpleagent-test
  "SimpleAgent 集成测试 - 使用 GLM-4.7 真实 API

   运行: clojure -M -e \"(load-file \\\"examples/simpleagent_test.clj\\\")\"

   需要环境变量: ZHIPU_API_KEY"
  (:require [im.ttalk.agent.tool :refer [deftool]]
            [im.ttalk.agent.simple-agent :as sa]
            [im.ttalk.agent.simple-agent :as sa]
            [im.ttalk.agent.provider.zhipu :as zhipu]))

;;; ============================================================
;;; 工具定义
;;; ============================================================

(deftool get-weather
  "获取指定城市的天气信息"
  [[city :string "城市名称"]]
  {:tags [:weather :read-only]}
  (str city "：晴天，气温 25°C，湿度 60%"))

(deftool get-time
  "获取当前时间"
  []
  {:tags [:utility :read-only]}
  (str (java.time.LocalDateTime/now)))

(deftool delete-file
  "删除指定文件（危险操作）"
  [[path :string "文件路径"]]
  {:sensitive true
   :tags [:file :dangerous]}
  (str "已删除: " path))

(def agent-tools
  "Agent 工具集"
  [#'get-weather #'get-time #'delete-file])

;;; ============================================================
;;; Provider 设置
;;; ============================================================

(def openai-provider
  (zhipu/create-provider
    {:api-key (System/getenv "ZHIPU_API_KEY")
     :base-url "https://open.bigmodel.cn/api/coding/paas/v4"
     :endpoint "/chat/completions"}))

;;; ============================================================
;;; 测试辅助
;;; ============================================================

(defn separator [title]
  (println)
  (println "═══════════════════════════════════════════════════════════")
  (println (str "  " title))
  (println "═══════════════════════════════════════════════════════════")
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
;;; 测试 1: ChatClient Agent 简单对话
;;; ============================================================

(defn test-chat-client-simple-chat []
  (separator "测试 1: ChatClient Agent 简单对话")
  (safe-call "ChatClient Agent 创建与对话"
    (fn []
      (let [agent (sa/create-agent
                    {:provider openai-provider
                     :model "glm-4.7"
                     :max-tokens 1024
                     :system-prompt "你是一个简洁的助手，回答尽量简短。"})]
        (let [result (sa/chat agent "你好，用一句话介绍自己")]
          (println "    回复:" (:text result))
          (println "    messages count:" (count (sa/get-messages agent))))))))

;;; ============================================================
;;; 测试 2: ChatClient Agent 工具调用
;;; ============================================================

(defn test-chat-client-tool-call []
  (separator "测试 2: ChatClient Agent 工具调用")
  (safe-call "ChatClient Agent 工具调用"
    (fn []
      (let [agent (sa/create-agent
                    {:provider openai-provider
                     :model "glm-4.7"
                     :max-tokens 1024
                     :tools agent-tools})]
        (let [result (sa/chat agent "北京现在天气怎么样？")]
          (println "    回复:" (:text result))
          (println "    tool-calls:" (mapv (fn [tc] [(:name tc) (:result tc)])
                                           (:tool-calls-made result))))))))

;;; ============================================================
;;; 测试 3: ChatClient Agent 多轮对话
;;; ============================================================

(defn test-chat-client-multi-turn []
  (separator "测试 3: ChatClient Agent 多轮对话")
  (safe-call "多轮对话 context 累积"
    (fn []
      (let [agent (sa/create-agent
                    {:provider openai-provider
                     :model "glm-4.7"
                     :max-tokens 1024
                     :system-prompt "简短回复"})]
        (let [r1 (sa/chat agent "我叫小明，记住。")]
          (println "    轮次1:" (:text r1)))
        (wait 3000)
        (let [r2 (sa/chat agent "我叫什么名字？直接回答名字。")]
          (println "    轮次2:" (:text r2))
          (println "    history count:" (count (sa/get-history agent))))))))

;;; ============================================================
;;; 测试 4: Process Agent sensitive 工具暂停
;;; ============================================================

(defn test-process-pause-resume []
  (separator "测试 4: Process Agent pause/resume")
  (safe-call "Process Agent sensitive 工具暂停与恢复"
    (fn []
      (let [pause-log (atom nil)
            agent (sa/create-agent
                    {:provider openai-provider
                     :model "glm-4.7"
                     :max-tokens 1024
                     :tools agent-tools
                     :on-pause (fn [info]
                                 (reset! pause-log info)
                                 (println "    [on-pause] 原因:" (:reason info)))})]
        (let [result (sa/chat agent "请帮我删除 /tmp/test.txt 文件")]
          (println "    状态:" (:status result))
          (if (= :paused (:status result))
            (do
              (println "    暂停原因:" (:pause-reason result))
              (println "    待审批工具:" (:name (:pending-tool result)))
              ;; 批准执行
              (let [resume-result (sa/resume agent "approved")]
                (println "    恢复后状态:" (:status resume-result))
                (println "    恢复后回复:" (:text resume-result))))
            (println "    直接完成:" (:text result))))))))

;;; ============================================================
;;; 测试 5: Process Agent 拒绝 sensitive 工具
;;; ============================================================

(defn test-process-reject []
  (separator "测试 5: Process Agent 拒绝 sensitive 操作")
  (safe-call "拒绝 sensitive 工具"
    (fn []
      (let [agent (sa/create-agent
                    {:provider openai-provider
                     :model "glm-4.7"
                     :max-tokens 1024
                     :tools agent-tools
                     :on-pause (fn [_] nil)})]
        (let [result (sa/chat agent "请删除 /home/user/important.txt")]
          (when (= :paused (:status result))
            (let [reject-result (sa/resume agent "rejected")]
              (println "    拒绝后状态:" (:status reject-result))
              (println "    拒绝后回复:" (:text reject-result)))))))))

;;; ============================================================
;;; 运行
;;; ============================================================

(defn run-all []
  (println)
  (println "╔═══════════════════════════════════════════════════════════╗")
  (println "║     SimpleAgent 集成测试 (GLM-4.7 OpenAI 兼容)          ║")
  (println "╚═══════════════════════════════════════════════════════════╝")

  (when-not (System/getenv "ZHIPU_API_KEY")
    (println "\n  ⚠ 未设置 ZHIPU_API_KEY 环境变量，跳过集成测试")
    (System/exit 1))

  (test-chat-client-simple-chat)
  (wait 3000)
  (test-chat-client-tool-call)
  (wait 3000)
  (test-chat-client-multi-turn)
  (wait 3000)
  (test-process-pause-resume)
  (wait 3000)
  (test-process-reject)

  (separator "全部完成"))

(run-all)
