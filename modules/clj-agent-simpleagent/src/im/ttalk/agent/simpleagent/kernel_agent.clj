(ns im.ttalk.agent.simpleagent.kernel-agent
  "Kernel Agent - 简单同步模式（Memory Filter 版）

   包装 kernel/invoke。对话历史由 Kernel 的 ChatMemory store 按
   conversation-id 自管，Agent 自身只持 conversation-id。

   使用示例：

   (def agent (create-agent
                {:provider my-provider
                 :model \"glm-4.7\"
                 :system-prompt \"你是一个助手\"
                 :tools [#'get-weather]}))

   (chat agent \"你好\")
   ;; => {:text \"...\" :tool-calls-made [...]}"
  (:refer-clojure :exclude [reset!])
  (:require [im.ttalk.agent.core.kernel :as kernel]
            [im.ttalk.agent.core.kernel.context :as ctx]
            [im.ttalk.agent.core.llm.message :as msg]
            [im.ttalk.agent.core.memory :as memory]
            [im.ttalk.agent.simpleagent.common :as common]))

;;; ============================================================
;;; 公开 API
;;; ============================================================

(defn create-agent
  "创建 Kernel Agent

   参数:
   - opts: 配置 map
     {:kernel        预构建 Kernel（提供时跳过自动构建）
      :provider      ILLMProvider 实例
      :model         模型名称（默认 \"glm-4\"）
      :max-tokens    最大 token 数（默认 4096）
      :system-prompt 系统提示词
      :temperature   温度参数
      :tools         tool var 列表
      :filters       Filter 列表
      :memory        ChatMemory store（可选）
      :max-iterations 最大工具调用循环次数（默认 10）}

   返回:
   Agent map（持 kernel + conversation-id + settings）"
  [opts]
  (let [k (common/ensure-kernel opts)]
    {:kernel          k
     :conversation-id (str "kern-" (java.util.UUID/randomUUID))
     :settings        (select-keys opts [:system-prompt :max-iterations])}))

(defn chat
  "对话（按 conversation-id 自动累积历史）

   参数:
   - agent:   Agent 实例
   - message: 用户消息字符串
   - opts:    可选 {:system-prompt :max-iterations :tool-choice}

   返回:
   {:text \"...\" :tool-calls-made [...]}"
  ([agent message]
   (chat agent message nil))
  ([agent message opts]
   (let [settings (:settings agent)
         sp (or (:system-prompt opts) (:system-prompt settings))
         tctx (ctx/with-conversation-id (ctx/create) (:conversation-id agent))
         invoke-opts (cond-> {:context tctx
                              :max-iterations (or (:max-iterations opts)
                                                  (:max-iterations settings) 10)}
                       sp (assoc :system-prompts [{:role "system" :content sp}])
                       (:tool-choice opts) (assoc :tool-choice (:tool-choice opts)))
         result (kernel/invoke (:kernel agent) [(msg/user message)] invoke-opts)]
     {:text (get-in result [:response :text])
      :tool-calls-made (:tool-calls-made result)})))

(defn reset!
  "清空该会话历史"
  [agent]
  (memory/mem-clear (:memory (:kernel agent)) (:conversation-id agent))
  nil)

(defn get-history
  "获取该会话的完整中立消息历史"
  [agent]
  (memory/mem-get (:memory (:kernel agent)) (:conversation-id agent)))

;; 兼容别名：工作消息等同于完整历史（不再有双轨）
(def get-messages get-history)
