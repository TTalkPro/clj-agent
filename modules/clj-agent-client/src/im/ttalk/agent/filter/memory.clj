(ns im.ttalk.agent.filter.memory
  "Memory Filter - 按 conversation-id 读写历史

    以 around-chat filter 形态挂进 chat-client 的 filter 链：

    - req 段：把本次入参(delta)存入 store，再用完整历史替换发给 LLM 的 messages
    - resp 段：把 LLM 的 assistant 回复(可能含 tool-calls)存入 store

    conversation-id 从 ChatRequest 的 :context(扁平 map)读取；为空时整体 no-op
    (保留一次性/无记忆调用)。store 是 filter 的私有闭包，chat-client 对其无感知。

    用法：
    (build-chat-client {:chat-model cm
                        :tools tools
                        :filters [(memory-filter store)]})"
  (:require [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.memory :as mem]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.response :as resp]))

(set! *warn-on-reflection* true)

(defn response->neutral
  "把归一化响应(ILLMResponse)转成中立 assistant 消息。
    归一化响应的 tool-calls 已与中立形状同构（{:id :name(字符串) :args}，
    v0.2 统一后不再需要 :input→:args 换名），仅经 msg/tool-call 复位形状。

    :replay-blocks（provider 经 IReplayableResponse 抽出的不透明载荷）**必须**
    随消息进历史：它是「原样带回下一轮」的唯一载体，在这里丢掉，下一轮 wire 层
    就再也拿不回来了——这正是 P3 修的那个洞（docs/provider-variant-design.md
    §1.3；实测代价：M3 正确率 100%→82.5%）。本函数不解释它，只搬运。"
  [response]
  (let [text (resp/response-text response)
        calls (resp/response-tool-calls response)
        m (if (seq calls)
            (msg/assistant-tool-calls
              (mapv (fn [{:keys [id name args]}] (msg/tool-call id name args)) calls)
              (when (seq text) text))
            (msg/assistant text))]
    (msg/with-blocks m (resp/response-replay-blocks response))))

(defn memory-filter
  "构造按 conversation-id 读写历史的 around-chat filter，闭包绑定 store。

    应作为 filters vector 的第一个元素注册，确保其他 filter 看到完整对话历史。"
  [store]
  (flt/map->Filter
   {:name :memory
   ;; 暴露绑定的 store，便于 create-agent 在传入预构建 chat-client 时复用同一实例
   ;; （react heal/clear 与 chat-client 落库必须用同一 store，见 client/create-agent）。
   ;; 四个钩子之外的键 → Filter record 的 ext-map，`(:store f)` 照常可读。
   :store store
   :chat (fn [req chain]
           (if-let [cid (get-in (flt/req-context req) [:conversation-id])]
             (do
               (mem/mem-add store cid (mapv msg/normalize (flt/req-messages req)))  ;; 存 delta
               ;; 展开历史 → 下游；响应侧走 fmap（同步下即恒等展开，
               ;; :chat 终端将来异步化时同一份代码不用改，见 §2.6.4）
               (flt/fmap (chain (flt/with-messages req (mem/mem-get store cid)))
                         (fn [resp]
                           (mem/mem-add store cid [(response->neutral (:response resp))])
                           resp)))
             (chain req)))}))
