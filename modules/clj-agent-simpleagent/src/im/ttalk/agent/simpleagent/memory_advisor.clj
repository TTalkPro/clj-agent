(ns im.ttalk.agent.simpleagent.memory-advisor
  "Memory Advisor - 对标 Spring AI MessageChatMemoryAdvisor

   把对话历史的读写从「显式 thread Context」转为「按 conversation-id 走 store」，
   并以洋葱式 chat advisor 的形态挂进 kernel 的 advisor 链：

   - before 段：把本次入参(delta)存入 store，再用完整历史替换发给 LLM 的 messages
   - after  段：把 LLM 的 assistant 回复(可能含 tool-calls)存入 store

   conversation-id 从 ChatRequest 的 :context(扁平 map)读取；为空时整体 no-op
   (保留一次性/无记忆调用)。store 是 advisor 的私有闭包，kernel 对其无感知。

   用法：
   (-> (kernel/create-kernel-builder)
       (kernel/add-service svc)
       (kernel/add-advisor (memory-advisor store))
       (kernel/build-kernel))"
  (:require [im.ttalk.agent.core.kernel.filter :as flt]
            [im.ttalk.agent.simpleagent.memory :as mem]
            [im.ttalk.agent.core.llm.message :as msg]
            [im.ttalk.agent.core.llm.response :as resp]))

(defn response->neutral
  "把归一化响应(ILLMResponse)转成中立 assistant 消息。
   归一化响应里 text / tool-calls 已是 provider 无关形态。"
  [response]
  (let [text (resp/response-text response)
        calls (resp/response-tool-calls response)]
    (if (seq calls)
      (msg/assistant-tool-calls
        (mapv (fn [{:keys [id name input]}] (msg/tool-call id name input)) calls)
        (when (seq text) text))
      (msg/assistant text))))

(defn memory-advisor
  "构造按 conversation-id 读写历史的 chat advisor，闭包绑定 store。

   order -1000 → 处于链最外层：before 最先展开历史，after 最后存回复
   (使后续 advisor 看到完整对话、其改写不污染存储的回复)。"
  [store]
  (flt/create-advisor :memory :chat
    :order -1000
    :advise-call
    (fn [req chain]
      (if-let [cid (get-in req [:context :conversation-id])]
        (do
          (mem/mem-add store cid (mapv msg/normalize (:messages req)))   ;; 存 delta
          (let [resp (chain (assoc req :messages (mem/mem-get store cid)))] ;; 展开历史 → 下游
            (mem/mem-add store cid [(response->neutral (:response resp))]) ;; 存回复
            resp))
        (chain req)))))
