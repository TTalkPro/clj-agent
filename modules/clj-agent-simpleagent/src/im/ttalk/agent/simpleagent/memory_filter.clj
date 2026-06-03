(ns im.ttalk.agent.simpleagent.memory-filter
  "Memory Filter - 按 conversation-id 读写历史

    以 around-chat filter 形态挂进 kernel 的 filter 链：

    - req 段：把本次入参(delta)存入 store，再用完整历史替换发给 LLM 的 messages
    - resp 段：把 LLM 的 assistant 回复(可能含 tool-calls)存入 store

    conversation-id 从 ChatRequest 的 :context(扁平 map)读取；为空时整体 no-op
    (保留一次性/无记忆调用)。store 是 filter 的私有闭包，kernel 对其无感知。

    用法：
    (build-kernel {:service svc
                   :tools tools
                   :filters [(memory-filter store)]})"
  (:require [im.ttalk.agent.simpleagent.memory :as mem]
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

(defn memory-filter
  "构造按 conversation-id 读写历史的 around-chat filter，闭包绑定 store。

    应作为 filters vector 的第一个元素注册，确保其他 filter 看到完整对话历史。"
  [store]
  {:name :memory
   :chat (fn [req chain]
           (if-let [cid (get-in req [:context :conversation-id])]
             (do
               (mem/mem-add store cid (mapv msg/normalize (:messages req)))   ;; 存 delta
               (let [resp (chain (assoc req :messages (mem/mem-get store cid)))] ;; 展开历史 → 下游
                 (mem/mem-add store cid [(response->neutral (:response resp))]) ;; 存回复
                 resp))
             (chain req)))})
