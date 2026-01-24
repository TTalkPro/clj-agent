(ns im.ttalk.agent.core.kernel.service
  "LLM Service 工厂

   提供通用的 create-service，将任意 ILLMProvider 适配为 Kernel service map。

   使用示例：

   (require '[im.ttalk.agent.core.kernel.service :as service])
   (require '[im.ttalk.agent.core.kernel.provider :as provider])

   ;; 从 provider 创建 service
   (def svc (service/create-service my-provider
              {:model \"gpt-4\" :max-tokens 4096}))

   ;; service 是一个 map：
   ;; {:chat-fn           (fn [messages opts] -> normalized-response)
   ;;  :build-result-msgs (fn [assistant-msg tool-results] -> [msg ...])}"
  (:require [im.ttalk.agent.core.kernel.provider :as provider]))

;;; ============================================================
;;; 辅助函数
;;; ============================================================

(defn- build-call-config
  "合并基础配置和调用时的 opts

   参数：
   - config: 基础配置 {:model ... :max-tokens ...}
   - opts:   调用时选项 {:tools [...] :tool-choice ...}

   返回：
   合并后的调用配置"
  [config opts]
  (let [tool-choice (:tool-choice opts)
        ;; :none 时不传 tools，让 LLM 纯文本回复
        tools (when (not= tool-choice :none)
                (:tools opts))
        system-prompt (:system-prompt opts)]
    (cond-> config
      (seq tools)
      (assoc :tools tools)
      (and tool-choice (not= tool-choice :none))
      (assoc :tool-choice
             (case tool-choice
               :auto {:type "auto"}
               :required {:type "any"}
               tool-choice))
      system-prompt
      (assoc :system-prompt system-prompt))))

;;; ============================================================
;;; Service 工厂
;;; ============================================================

(defn create-service
  "从 ILLMProvider 创建 Kernel service

   参数：
   - provider: ILLMProvider 实例
   - config:   模型配置 {:model \"...\" :max-tokens n :system-prompt \"...\"}

   返回：
   {:chat-fn           (fn [messages opts] -> normalized-response)
    :build-result-msgs (fn [assistant-msg tool-results] -> [msg ...])}"
  [provider config]
  {:chat-fn
   (fn [messages opts]
     (let [call-config (build-call-config config opts)
           response    (provider/call-llm provider call-config messages nil)]
       {:text          (when-let [t (provider/extract-text provider response)]
                         (when (seq t) t))
        :tool-calls    (let [tcs (provider/extract-tool-calls provider response)]
                         (when (seq tcs) tcs))
        :assistant-msg (provider/build-assistant-message provider response)
        :raw-response  response}))
   :build-result-msgs
   (fn [assistant-msg tool-results]
     (provider/build-result-messages provider assistant-msg tool-results))})
