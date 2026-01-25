(ns test-external-events
  "测试 Process Framework 外部事件支持

   使用方法：
   1. 设置环境变量 ZHIPU_API_KEY
   2. 运行: clj -M examples/test_external_events.clj"
  (:require [im.ttalk.agent.llm.provider.anthropic :as anthropic]
            [im.ttalk.agent.core.kernel.provider :as proto]
            [im.ttalk.agent.core.kernel.process.builder :as builder]
            [im.ttalk.agent.core.kernel.process.runtime :as runtime]
            [im.ttalk.agent.core.kernel.context :as ctx]))

(def api-key (System/getenv "ZHIPU_API_KEY"))

(when-not api-key
  (println "错误: 请设置 ZHIPU_API_KEY 环境变量")
  (System/exit 1))

(println "=" (apply str (repeat 60 "=")))
(println "测试 Process Framework 外部事件支持")
(println "=" (apply str (repeat 60 "=")))

;; 创建 Anthropic Provider（使用 GLM-4.7）
(def provider
  (anthropic/create-provider
    {:api-key api-key
     :base-url "https://open.bigmodel.cn/api/anthropic"}))

;;; ============================================================
;;; 测试 1: 简单的外部事件处理
;;; ============================================================

(println "\n[测试 1] 简单外部事件")
(println "-" (apply str (repeat 50 "-")))

(def simple-process
  (-> (builder/builder :simple-external)
      (builder/add-step
        {:id :echo
         :on-activate (fn [inputs _state ctx]
                        (let [msg (:input inputs)]
                          (println "收到消息:" msg)
                          {:context (ctx/set-var ctx :last-msg msg)
                           :terminate true}))})
      (builder/on-external-event :message :echo :input)
      (builder/build)))

(let [handle (runtime/start-process-async simple-process {:timeout-ms 5000})]
  (println "Process 已启动，状态:" (runtime/get-status handle))

  ;; 发送外部事件
  (runtime/send-event handle :message "Hello from external!")

  ;; 等待完成
  (let [result (runtime/wait-for-completion handle 3000)]
    (println "结果状态:" (:status result))
    (println "收到的消息:" (ctx/get-var (:context result) :last-msg))
    (if (= :completed (:status result))
      (println "[测试 1 通过]")
      (println "[测试 1 失败]"))))

;;; ============================================================
;;; 测试 2: 多轮对话（模拟交互式 Agent）
;;; ============================================================

(println "\n[测试 2] 交互式对话 Agent")
(println "-" (apply str (repeat 50 "-")))

(def chat-process
  (-> (builder/builder :chat-agent)
      (builder/add-step
        {:id :chat-handler
         :init (fn [_] {:history []})
         :on-activate (fn [inputs state ctx]
                        (let [user-msg (:input inputs)
                              history (:history state)]
                          (println "用户:" user-msg)

                          (if (= user-msg "/quit")
                            ;; 退出
                            {:context (ctx/set-var ctx :conversation-ended true)
                             :state {:history (conj history {:role "user" :content user-msg})}
                             :terminate true}

                            ;; 调用 LLM 生成回复
                            (let [messages (conj history {:role "user" :content user-msg})
                                  response (proto/call-llm
                                             provider
                                             {:model "GLM-4.7"
                                              :max-tokens 256}
                                             messages
                                             [])
                                  reply (proto/extract-text provider response)]
                              (println "AI:" reply)
                              {:state {:history (conj messages {:role "assistant" :content reply})}
                               :events []}))))})
      (builder/on-external-event :user-input :chat-handler :input)
      (builder/build)))

(let [handle (runtime/start-process-async chat-process {:timeout-ms 60000})]
  (println "对话 Agent 已启动")
  (println "发送消息...")

  ;; 第一轮对话
  (runtime/send-event! handle :user-input "你好！请问1+1等于多少？" 10000)
  (Thread/sleep 2000)

  ;; 第二轮对话
  (runtime/send-event! handle :user-input "那2+2呢？" 10000)
  (Thread/sleep 2000)

  ;; 退出
  (runtime/send-event! handle :user-input "/quit" 1000)

  ;; 等待完成
  (let [result (runtime/wait-for-completion handle 5000)]
    (println "\n对话结束")
    (println "状态:" (:status result))
    (if (= :completed (:status result))
      (println "[测试 2 通过]")
      (println "[测试 2 失败]"))))

;;; ============================================================
;;; 测试 3: 内部事件和外部事件混合
;;; ============================================================

(println "\n[测试 3] 内部和外部事件混合")
(println "-" (apply str (repeat 50 "-")))

(def mixed-process
  (-> (builder/builder :mixed)
      (builder/add-step
        {:id :initializer
         :on-activate (fn [_ _ _]
                        (println "初始化完成，等待外部输入...")
                        {:events [{:name :initialized :data "ready"}]})})
      (builder/add-step
        {:id :processor
         :required-inputs [:init-signal :external-data]
         :on-activate (fn [inputs _ ctx]
                        (println "处理数据:" inputs)
                        {:context (ctx/set-var ctx :processed true)
                         :terminate true})})
      (builder/on-event :start :initializer :input)
      (builder/on-event :initialized :processor :init-signal)
      (builder/on-external-event :data :processor :external-data)
      (builder/set-initial-event :start "go")
      (builder/build)))

(let [handle (runtime/start-process-async mixed-process {:timeout-ms 5000})]
  (println "混合 Process 已启动")
  (Thread/sleep 500)  ;; 等待初始化

  ;; 发送外部数据
  (println "发送外部数据...")
  (runtime/send-event! handle :data {:value 42} 1000)

  ;; 等待完成
  (let [result (runtime/wait-for-completion handle 3000)]
    (println "状态:" (:status result))
    (println "已处理:" (ctx/get-var (:context result) :processed))
    (if (and (= :completed (:status result))
             (ctx/get-var (:context result) :processed))
      (println "[测试 3 通过]")
      (println "[测试 3 失败]"))))

;;; ============================================================
;;; 测试总结
;;; ============================================================

(println "\n" (apply str (repeat 60 "=")))
(println "外部事件测试完成")
(println (apply str (repeat 60 "=")))
