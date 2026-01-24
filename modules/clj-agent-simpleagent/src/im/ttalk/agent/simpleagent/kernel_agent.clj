(ns im.ttalk.agent.simpleagent.kernel-agent
  "Kernel Agent - 简单同步模式

   包装 kernel invoke API，atom 管理 context 自动累积对话。

   使用示例：

   (def agent (create-agent
                {:provider my-provider
                 :model \"glm-4.7\"
                 :system-prompt \"你是一个助手\"
                 :tools [my-plugin]}))

   (chat agent \"你好\")
   ;; => {:text \"...\" :tool-calls-made [...]}"
  (:refer-clojure :exclude [reset!])
  (:require [im.ttalk.agent.core.kernel.core :as kernel]
            [im.ttalk.agent.core.kernel.context :as ctx]
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
      :tools         KernelPlugin 实例列表或 tool var 列表
      :filters       Filter 列表
      :max-iterations 最大工具调用循环次数（默认 10）}

   返回:
   Agent map"
  [opts]
  (let [k (common/ensure-kernel opts)]
    {:kernel       k
     :context-atom (atom (ctx/create))
     :settings     (select-keys opts [:system-prompt :max-iterations])}))

(defn chat
  "对话（有状态累积）

   参数:
   - agent:   Agent 实例
   - message: 用户消息字符串
   - opts:    可选选项 map
     {:system-prompt  覆盖系统提示词
      :max-iterations 覆盖最大迭代次数
      :tool-choice    工具选择策略}

   返回:
   {:text \"...\" :tool-calls-made [...]}"
  ([agent message]
   (chat agent message nil))
  ([agent message opts]
   (let [user-msg {:role "user" :content message}
         settings (:settings agent)
         system-prompts (when-let [sp (or (:system-prompt opts)
                                          (:system-prompt settings))]
                          [{:role "system" :content sp}])
         invoke-opts (cond-> {:context @(:context-atom agent)
                              :max-iterations (or (:max-iterations opts)
                                                  (:max-iterations settings) 10)}
                       system-prompts (assoc :system-prompts system-prompts)
                       (:tool-choice opts) (assoc :tool-choice (:tool-choice opts)))
         result (kernel/invoke (:kernel agent) [user-msg] invoke-opts)]
     (clojure.core/reset! (:context-atom agent) (:context result))
     {:text (get-in result [:response :text])
      :tool-calls-made (:tool-calls-made result)})))

(defn reset!
  "重置 context

   参数:
   - agent: Agent 实例

   返回: nil"
  [agent]
  (clojure.core/reset! (:context-atom agent) (ctx/create))
  nil)

(defn get-context
  "获取当前 context

   参数:
   - agent: Agent 实例

   返回: Context map"
  [agent]
  @(:context-atom agent))

(defn get-history
  "获取完整历史

   参数:
   - agent: Agent 实例

   返回: 消息列表"
  [agent]
  (ctx/get-history @(:context-atom agent)))

(defn get-messages
  "获取工作消息

   参数:
   - agent: Agent 实例

   返回: 消息列表"
  [agent]
  (ctx/get-messages @(:context-atom agent)))
