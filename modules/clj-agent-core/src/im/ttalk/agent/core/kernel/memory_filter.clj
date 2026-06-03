(ns im.ttalk.agent.core.kernel.memory-filter
  "Memory Filter - 对标 Spring AI MessageChatMemoryAdvisor

   把对话历史的读写从「显式 thread Context」转为「按 conversation-id 走 store」。

   - pre-chat：把本次入参（delta）存入 store，再用完整历史替换发给 LLM 的 messages
   - post-chat：把 LLM 的 assistant 回复（可能含 tool-calls）存入 store

   conversation-id 从 ToolContext（filter-ctx 的 :context，一个扁平 map）读取。
   conv-id 为空时 filter no-op（保留一次性/无记忆调用）。

   用法：
   (let [store (memory/in-memory-store)]
     (-> (kernel/create-kernel-builder)
         (kernel/add-service svc)
         (as-> b (reduce kernel/add-filter b (memory-filters store)))
         (kernel/build-kernel)))"
  (:require [im.ttalk.agent.core.kernel.filter :as flt]
            [im.ttalk.agent.core.memory :as mem]
            [im.ttalk.agent.core.llm.message :as msg]
            [im.ttalk.agent.core.llm.response :as resp]))

(defn- conv-id [filter-ctx]
  (get-in filter-ctx [:context :conversation-id]))

(defn response->neutral
  "把归一化响应（ILLMResponse）转成中立 assistant 消息。
   归一化响应里 text / tool-calls 已是 provider 无关形态。"
  [response]
  (let [text (resp/response-text response)
        calls (resp/response-tool-calls response)]
    (if (seq calls)
      (msg/assistant-tool-calls
        (mapv (fn [{:keys [id name input]}] (msg/tool-call id name input)) calls)
        (when (seq text) text))
      (msg/assistant text))))

(defn pre-chat-filter
  "存 delta + 用完整历史替换 messages"
  [store]
  (flt/create-filter :memory-pre :pre-chat
    (fn [filter-ctx]
      (if-let [cid (conv-id filter-ctx)]
        (do
          (mem/mem-add store cid (mapv msg/normalize (:messages filter-ctx)))
          {:action :continue
           :context (assoc filter-ctx :messages (mem/mem-get store cid))})
        {:action :continue :context filter-ctx}))
    :priority -1000))  ;; 最先展开历史，使后续 pre-chat filter 看到完整对话

(defn post-chat-filter
  "存 assistant 回复"
  [store]
  (flt/create-filter :memory-post :post-chat
    (fn [filter-ctx]
      (when-let [cid (conv-id filter-ctx)]
        (mem/mem-add store cid [(response->neutral (:response filter-ctx))]))
      {:action :continue :context filter-ctx})
    :priority 1000))

(defn memory-filters
  "返回 [pre-chat-filter post-chat-filter]，闭包绑定 store"
  [store]
  [(pre-chat-filter store)
   (post-chat-filter store)])
