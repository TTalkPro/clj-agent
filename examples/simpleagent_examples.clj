(ns simpleagent-examples
  "SimpleAgent 综合使用示例

   覆盖场景:
   1. 简单对话、多轮对话（Kernel Agent / Process Agent）
   2. 带工具的对话、多轮工具调用
   3. Memory 存储：长对话、HIL、保存恢复
   4. 双 Provider 验证（OpenAI 兼容 / Anthropic 兼容）

   运行:
     ./scripts/repl.sh simpleagent_examples

   或:
     clojure -M -e \"(load-file \\\"examples/simpleagent_examples.clj\\\")\"

   环境变量:
     ZHIPU_API_KEY - 智谱 AI API Key（必需）"
  (:require [im.ttalk.agent.core.kernel.tool :refer [deftool]]
            [im.ttalk.agent.core.kernel.context :as ctx]
            [im.ttalk.agent.simpleagent :as ka]
            [im.ttalk.agent.simpleagent :as pa]
            [im.ttalk.agent.llm.provider.zhipu :as zhipu]
            [im.ttalk.agent.llm.provider.anthropic :as anthropic]))

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
  "计算数学表达式"
  [[expression :string "数学表达式，如 2+3*4"]]
  (try
    (str "结果: " (eval (read-string expression)))
    (catch Exception e
      (str "计算错误: " (.getMessage e)))))

(deftool delete-file
  "删除指定文件（危险操作）"
  [[path :string "文件路径"]]
  {:sensitive true}
  (str "已删除: " path))

(deftool save-note
  "保存笔记到记事本"
  [[title :string "笔记标题"]
   [content :string "笔记内容"]]
  {:sensitive true}
  (str "已保存笔记 [" title "]: " content))

(def agent-tools
  "Agent 工具集"
  [#'get-weather #'get-time #'calculate #'delete-file #'save-note])

;;; ============================================================
;;; Provider 创建
;;; ============================================================

(defn make-openai-provider
  "创建 GLM-4.7 OpenAI 兼容 Provider"
  []
  (zhipu/create-provider
    {:api-key (System/getenv "ZHIPU_API_KEY")
     :base-url "https://open.bigmodel.cn/api/coding/paas/v4"
     :endpoint "/chat/completions"}))

(defn make-anthropic-provider
  "创建 GLM-4.7 Anthropic 兼容 Provider"
  []
  (anthropic/create-provider
    {:api-key (System/getenv "ZHIPU_API_KEY")
     :base-url "https://open.bigmodel.cn/api/anthropic"}))

;;; ============================================================
;;; 辅助函数
;;; ============================================================

(defn separator [title]
  (println)
  (println "===================================================================")
  (println (str "  " title))
  (println "===================================================================")
  (println))

(defn subsection [title]
  (println)
  (println (str "  --- " title " ---"))
  (println))

(defn wait [ms]
  (println (str "  [等待 " (/ ms 1000.0) "s...]"))
  (Thread/sleep ms))

(defn safe-call [label f]
  (try
    (let [result (f)]
      (println (str "  [OK] " label))
      result)
    (catch Throwable e
      (println (str "  [FAIL] " label ": " (.getMessage e)))
      (when-let [d (ex-data e)]
        (println (str "    详情: " (pr-str (select-keys d [:type :error])))))
      nil)))

(def api-delay 3000)

;;; ============================================================
;;; Part 1: 简单对话和多轮对话
;;; ============================================================

(defn test-kernel-simple-chat [provider]
  (subsection "Kernel Agent: 简单对话")
  (safe-call "单轮对话"
    (fn []
      (let [agent (ka/create-agent
                    {:provider provider
                     :model "glm-4.7"
                     :max-tokens 512
                     :system-prompt "你是一个简洁的助手，回答尽量用一句话。"})
            result (ka/chat agent "中国的首都是哪里？")]
        (println (str "    回复: " (:text result)))
        (assert (some? (:text result)))))))

(defn test-kernel-multi-turn [provider]
  (subsection "Kernel Agent: 多轮对话")
  (safe-call "多轮对话记忆验证"
    (fn []
      (let [agent (ka/create-agent
                    {:provider provider
                     :model "glm-4.7"
                     :max-tokens 512
                     :system-prompt "你是助手，回答简短。"})]
        ;; 轮次 1: 告知信息
        (let [r1 (ka/chat agent "我叫张三，我住在上海，请记住。")]
          (println (str "    轮次1: " (:text r1))))
        (wait api-delay)
        ;; 轮次 2: 追问
        (let [r2 (ka/chat agent "我住在哪里？直接回答城市名。")]
          (println (str "    轮次2: " (:text r2)))
          (println (str "    历史消息数: " (count (ka/get-history agent)))))
        (wait api-delay)
        ;; 轮次 3: 再追问
        (let [r3 (ka/chat agent "我叫什么名字？直接回答名字。")]
          (println (str "    轮次3: " (:text r3))))))))

(defn test-process-simple-chat [provider]
  (subsection "Process Agent: 简单对话")
  (safe-call "Process Agent 单轮对话"
    (fn []
      (let [agent (pa/create-agent
                    {:provider provider
                     :model "glm-4.7"
                     :max-tokens 512
                     :system-prompt "你是一个简洁的助手。"})
            result (pa/chat agent "1+1等于几？")]
        (println (str "    状态: " (:status result)))
        (println (str "    回复: " (:text result)))
        (assert (= :completed (:status result)))))))

(defn test-process-multi-turn [provider]
  (subsection "Process Agent: 多轮对话")
  (safe-call "Process Agent 多轮对话记忆"
    (fn []
      (let [agent (pa/create-agent
                    {:provider provider
                     :model "glm-4.7"
                     :max-tokens 512
                     :system-prompt "回答简短。"})]
        (let [r1 (pa/chat agent "我的宠物是一只叫阿黄的狗。")]
          (println (str "    轮次1: " (:text r1))))
        (wait api-delay)
        (let [r2 (pa/chat agent "我的宠物叫什么名字？")]
          (println (str "    轮次2: " (:text r2))))))))

(defn run-part1 [provider provider-name]
  (separator (str "Part 1: 简单对话和多轮对话 (" provider-name ")"))
  (test-kernel-simple-chat provider)
  (wait api-delay)
  (test-kernel-multi-turn provider)
  (wait api-delay)
  (test-process-simple-chat provider)
  (wait api-delay)
  (test-process-multi-turn provider))

;;; ============================================================
;;; Part 2: 带工具的对话
;;; ============================================================

(defn test-kernel-tool-call [provider]
  (subsection "Kernel Agent: 工具调用")
  (safe-call "单轮工具调用"
    (fn []
      (let [agent (ka/create-agent
                    {:provider provider
                     :model "glm-4.7"
                     :max-tokens 1024
                     :tools agent-tools
                     :system-prompt "你是助手，需要时调用工具获取信息。"})
            result (ka/chat agent "北京今天天气怎么样？")]
        (println (str "    回复: " (:text result)))
        (println (str "    工具调用: " (mapv (fn [tc] [(:name tc) (:result tc)])
                                             (:tool-calls-made result))))
        (assert (seq (:tool-calls-made result)))))))

(defn test-kernel-multi-turn-with-tools [provider]
  (subsection "Kernel Agent: 多轮工具对话")
  (safe-call "多轮带工具对话"
    (fn []
      (let [agent (ka/create-agent
                    {:provider provider
                     :model "glm-4.7"
                     :max-tokens 1024
                     :tools agent-tools
                     :system-prompt "你是助手，必要时使用工具。"})]
        ;; 轮次 1: 天气查询
        (let [r1 (ka/chat agent "上海天气怎么样？")]
          (println (str "    轮次1: " (:text r1)))
          (println (str "    工具: " (mapv :name (:tool-calls-made r1)))))
        (wait api-delay)
        ;; 轮次 2: 追问
        (let [r2 (ka/chat agent "现在几点了？")]
          (println (str "    轮次2: " (:text r2)))
          (println (str "    工具: " (mapv :name (:tool-calls-made r2)))))
        (wait api-delay)
        ;; 轮次 3: 综合
        (let [r3 (ka/chat agent "帮我算一下 123*456")]
          (println (str "    轮次3: " (:text r3)))
          (println (str "    总历史消息数: " (count (ka/get-history agent)))))))))

(defn test-process-tool-call [provider]
  (subsection "Process Agent: 工具调用")
  (safe-call "Process Agent 工具调用（非 sensitive）"
    (fn []
      (let [agent (pa/create-agent
                    {:provider provider
                     :model "glm-4.7"
                     :max-tokens 1024
                     :tools agent-tools})
            result (pa/chat agent "现在几点了？")]
        (println (str "    状态: " (:status result)))
        (println (str "    回复: " (:text result)))
        (println (str "    工具: " (mapv :name (:tool-calls-made result))))
        (assert (= :completed (:status result)))))))

(defn test-process-multi-turn-with-tools [provider]
  (subsection "Process Agent: 多轮工具对话")
  (safe-call "Process Agent 多轮工具调用"
    (fn []
      (let [agent (pa/create-agent
                    {:provider provider
                     :model "glm-4.7"
                     :max-tokens 1024
                     :tools agent-tools
                     :system-prompt "你是助手，需要时调用工具。"})]
        (let [r1 (pa/chat agent "杭州天气如何？")]
          (println (str "    轮次1 状态: " (:status r1)))
          (println (str "    轮次1 回复: " (:text r1))))
        (wait api-delay)
        (let [r2 (pa/chat agent "帮我算 2^10")]
          (println (str "    轮次2 状态: " (:status r2)))
          (println (str "    轮次2 回复: " (:text r2))))))))

(defn run-part2 [provider provider-name]
  (separator (str "Part 2: 带工具的对话 (" provider-name ")"))
  (test-kernel-tool-call provider)
  (wait api-delay)
  (test-kernel-multi-turn-with-tools provider)
  (wait api-delay)
  (test-process-tool-call provider)
  (wait api-delay)
  (test-process-multi-turn-with-tools provider))

;;; ============================================================
;;; Part 3: Memory 存储
;;; ============================================================

(defn test-long-conversation [provider]
  (subsection "Kernel Agent: 长多轮对话")
  (safe-call "5轮连续对话验证上下文保持"
    (fn []
      (let [agent (ka/create-agent
                    {:provider provider
                     :model "glm-4.7"
                     :max-tokens 512
                     :system-prompt "你是助手。回答简短。"})]
        ;; 5 轮对话
        (let [messages ["我叫李明，在北京工作。"
                        "我的公司叫星辰科技。"
                        "我负责后端开发，主要用 Clojure。"
                        "我们团队有10个人。"
                        "总结一下你知道的关于我的信息。"]]
          (doseq [[idx msg] (map-indexed vector messages)]
            (when (pos? idx) (wait api-delay))
            (let [result (ka/chat agent msg)]
              (println (str "    轮次" (inc idx) ": " (:text result))))))
        (println (str "    最终历史消息数: " (count (ka/get-history agent))))))))

(defn test-hil-approve [provider]
  (subsection "Process Agent: HIL 审批执行")
  (safe-call "Sensitive 工具暂停 -> 审批 -> 执行"
    (fn []
      (let [pause-log (atom nil)
            agent (pa/create-agent
                    {:provider provider
                     :model "glm-4.7"
                     :max-tokens 1024
                     :tools agent-tools
                     :on-pause (fn [info]
                                 (reset! pause-log info)
                                 (println (str "    [on-pause] " (:reason info))))})]
        (let [result (pa/chat agent "帮我删除 /tmp/test.txt 文件")]
          (println (str "    状态: " (:status result)))
          (if (= :paused (:status result))
            (do
              (println (str "    待审批: " (:name (:pending-tool result))))
              (println (str "    参数: " (:args (:pending-tool result))))
              ;; 审批
              (let [resumed (pa/resume agent "approved")]
                (println (str "    审批后状态: " (:status resumed)))
                (println (str "    审批后回复: " (:text resumed)))
                (assert (= :completed (:status resumed)))))
            (println (str "    直接完成: " (:text result)))))))))

(defn test-hil-reject [provider]
  (subsection "Process Agent: HIL 拒绝操作")
  (safe-call "Sensitive 工具暂停 -> 拒绝"
    (fn []
      (let [agent (pa/create-agent
                    {:provider provider
                     :model "glm-4.7"
                     :max-tokens 1024
                     :tools agent-tools
                     :on-pause (fn [_] nil)})]
        (let [result (pa/chat agent "请删除 /home/user/important.dat")]
          (when (= :paused (:status result))
            (let [rejected (pa/resume agent "rejected")]
              (println (str "    拒绝后状态: " (:status rejected)))
              (println (str "    拒绝后回复: " (:text rejected)))
              (assert (= :completed (:status rejected))))))))))

(defn test-hil-multi-sensitive [provider]
  (subsection "Process Agent: HIL 多轮 sensitive")
  (safe-call "多次 sensitive 暂停恢复"
    (fn []
      (let [agent (pa/create-agent
                    {:provider provider
                     :model "glm-4.7"
                     :max-tokens 1024
                     :tools agent-tools
                     :system-prompt "你是助手，用户要求时调用工具。"
                     :on-pause (fn [_] nil)})]
        ;; 第一次: 保存笔记（sensitive）
        (let [r1 (pa/chat agent "帮我保存一条笔记，标题是'TODO'，内容是'学习Clojure'")]
          (println (str "    第1次状态: " (:status r1)))
          (when (= :paused (:status r1))
            (println (str "    审批保存笔记..."))
            (let [resumed (pa/resume agent "approved")]
              (println (str "    执行后: " (:text resumed))))))
        (wait api-delay)
        ;; 第二次: 删除文件（sensitive）
        (let [r2 (pa/chat agent "删除 /tmp/old.log")]
          (println (str "    第2次状态: " (:status r2)))
          (when (= :paused (:status r2))
            (println (str "    拒绝删除..."))
            (let [rejected (pa/resume agent "rejected")]
              (println (str "    拒绝后: " (:text rejected))))))))))

(defn run-part3 [provider provider-name]
  (separator (str "Part 3: 多轮对话与人工审批 (" provider-name ")"))
  (test-long-conversation provider)
  (wait api-delay)
  (test-hil-approve provider)
  (wait api-delay)
  (test-hil-reject provider)
  (wait api-delay)
  (test-hil-multi-sensitive provider))

;;; ============================================================
;;; Part 4: 双 Provider 验证
;;; ============================================================

(defn run-provider-test [provider provider-name]
  (separator (str "Provider 验证: " provider-name))

  (subsection (str provider-name ": 简单对话"))
  (safe-call "单轮对话"
    (fn []
      (let [agent (ka/create-agent
                    {:provider provider
                     :model "glm-4.7"
                     :max-tokens 256
                     :system-prompt "回答简短。"})
            r (ka/chat agent "1+1=?")]
        (println (str "    回复: " (:text r)))
        (assert (some? (:text r))))))

  (wait api-delay)

  (subsection (str provider-name ": 工具调用"))
  (safe-call "工具调用验证"
    (fn []
      (let [agent (ka/create-agent
                    {:provider provider
                     :model "glm-4.7"
                     :max-tokens 1024
                     :tools agent-tools})
            r (ka/chat agent "北京天气怎么样？")]
        (println (str "    回复: " (:text r)))
        (println (str "    工具: " (mapv :name (:tool-calls-made r))))
        (assert (some? (:text r))))))

  (wait api-delay)

  (subsection (str provider-name ": 多轮验证"))
  (safe-call "多轮对话"
    (fn []
      (let [agent (ka/create-agent
                    {:provider provider
                     :model "glm-4.7"
                     :max-tokens 512
                     :system-prompt "回答简短。"})]
        (ka/chat agent "我叫小红。")
        (wait api-delay)
        (let [r (ka/chat agent "我叫什么？")]
          (println (str "    回复: " (:text r))))))))

;;; ============================================================
;;; 运行入口
;;; ============================================================

(defn run-all []
  (println)
  (println "+-------------------------------------------------------------------+")
  (println "|          SimpleAgent 综合使用示例 (GLM-4.7)                       |")
  (println "+-------------------------------------------------------------------+")

  (when-not (System/getenv "ZHIPU_API_KEY")
    (println "\n  !! 未设置 ZHIPU_API_KEY 环境变量，跳过测试")
    (throw (ex-info "ZHIPU_API_KEY not set" {})))

  (let [openai-provider (make-openai-provider)
        anthropic-provider (make-anthropic-provider)]

    ;; Part 1 & 2: 使用 OpenAI 兼容 Provider
    (run-part1 openai-provider "OpenAI 兼容")
    (wait api-delay)
    (run-part2 openai-provider "OpenAI 兼容")
    (wait api-delay)

    ;; Part 3: Memory 使用 OpenAI 兼容 Provider
    (run-part3 openai-provider "OpenAI 兼容")
    (wait api-delay)

    ;; Part 4: 双 Provider 对比验证
    (separator "Part 4: 双 Provider 对比验证")
    (run-provider-test openai-provider "OpenAI 兼容 Provider")
    (wait api-delay)
    (run-provider-test anthropic-provider "Anthropic 兼容 Provider")

    (separator "全部完成")))

(defn run-quick
  "快速验证: 仅运行核心场景（双 Provider）"
  []
  (println)
  (println "+-------------------------------------------------------------------+")
  (println "|          SimpleAgent 快速验证 (GLM-4.7 双 Provider)               |")
  (println "+-------------------------------------------------------------------+")

  (when-not (System/getenv "ZHIPU_API_KEY")
    (println "\n  !! 未设置 ZHIPU_API_KEY 环境变量，跳过测试")
    (throw (ex-info "ZHIPU_API_KEY not set" {})))

  (let [openai-provider (make-openai-provider)
        anthropic-provider (make-anthropic-provider)]
    (run-provider-test openai-provider "OpenAI 兼容 Provider")
    (wait api-delay)
    (run-provider-test anthropic-provider "Anthropic 兼容 Provider")
    (separator "快速验证完成")))

;; 默认运行全部
(run-all)
