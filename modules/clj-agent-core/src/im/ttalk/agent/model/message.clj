(ns im.ttalk.agent.model.message
  "中立消息格式 - provider 无关的对话消息表示

   这是对话历史的唯一真相形态。Memory store 只存这种形状，
   发送给 LLM 时由各 provider 的 wire 转换器转成自家格式
   （见 llm/wire/openai、llm/wire/anthropic）。

   消息形状：

   {:role :system    :content \"...\"}
   {:role :user      :content \"...\"}
   {:role :assistant :content \"...\"}                       ; 纯文本
   {:role :assistant :content nil
    :tool-calls [{:id \"c1\" :name \"get_weather\" :args {:city \"北京\"}}]}
   {:role :tool :tool-call-id \"c1\" :name \"get_weather\" :content \"晴 22°C\"}

   约束：
   - role 用 keyword（:system/:user/:assistant/:tool）
   - tool-call: {:id 字符串 :name 字符串 :args map}
   - tool 消息的 :tool-call-id 关联到对应调用的 :id

   assistant 消息可携带 **:blocks**（可选）：

   {:role :assistant :content \"...\" :tool-calls [...]
    :blocks {:provider :minimax :format :anthropic-content :data [...]}}

   这是「必须原样带回下一轮」的**不透明载荷**（Anthropic thinking 块 + signature、
   Gemini thought_signature 之类）。性质与 :tool 消息的 :writes 一样——
   **中立层只搬运、不解释**；区别是 wire 层**会**消费它，但只由认得 :format 的
   那一方消费，认不出就当它不存在（降级路径见 wire/anthropic）。

   来源：service 归一化时经可选协议 model/IReplayableResponse 抽取，
   由 advisor/memory 的 response->neutral 挂上来。
   动机与实测代价见 docs/provider-variant-design.md（不回传：正确率 100%→82.5%）。")

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 构造函数
;;; ============================================================

(defn system
  "系统消息"
  [content]
  {:role :system :content content})

(defn user
  "用户消息"
  [content]
  {:role :user :content content})

(defn assistant
  "assistant 纯文本消息"
  [content]
  {:role :assistant :content content})

(defn tool-call
  "构造单个工具调用

   参数：
   - id:   调用 ID（字符串）
   - name: 工具名（字符串或 keyword，规范化为字符串）
   - args: 参数 map

   返回：{:id :name :args}"
  [id name args]
  {:id id
   :name (if (keyword? name) (clojure.core/name name) (str name))
   :args (or args {})})

(defn with-blocks
  "给 assistant 消息挂上不透明回放载荷（nil / 空则原样返回）。

   blocks 形如 {:format :anthropic-content :data [...]}——中立层不解释 :data。"
  [m blocks]
  (if (seq blocks) (assoc m :blocks blocks) m))

(defn blocks
  "读取不透明回放载荷（无则 nil）。"
  [m]
  (:blocks m))

(defn assistant-tool-calls
  "带工具调用的 assistant 消息

   参数：
   - tool-calls: [{:id :name :args} ...]（用 tool-call 构造）
   - content:    可选的伴随文本

   返回：{:role :assistant :content content-or-nil :tool-calls [...]}"
  ([tool-calls]
   (assistant-tool-calls tool-calls nil))
  ([tool-calls content]
   {:role :assistant
    :content content
    :tool-calls (vec tool-calls)}))

(defn tool-result
  "工具结果消息

   参数：
   - tool-call-id: 关联的工具调用 ID
   - name:         工具名（字符串或 keyword）
   - content:      结果内容（非字符串会 pr-str）
   - writes:       (可选) 该工具对状态槽的写意图 {k v}——event-sourcing
                   元数据：只进历史存储，不发给 LLM（wire 层构造时天然剥落）。
                   见 docs/agent-loop-concurrency-design.md §12.4

   返回：{:role :tool :tool-call-id :name :content (:writes)}"
  ([tool-call-id name content]
   (tool-result tool-call-id name content nil))
  ([tool-call-id name content writes]
   (cond-> {:role :tool
            :tool-call-id tool-call-id
            :name (if (keyword? name) (clojure.core/name name) (str name))
            :content (if (string? content) content (pr-str content))}
     (seq writes) (assoc :writes writes))))

;;; ============================================================
;;; 谓词与访问器
;;; ============================================================

(defn role [m] (:role m))
(defn content [m] (:content m))
(defn tool-calls [m] (:tool-calls m))
(defn tool-call-id [m] (:tool-call-id m))

(defn message?
  "是否为中立消息（有 keyword role）"
  [m]
  (and (map? m) (keyword? (:role m))))

(defn has-tool-calls?
  "assistant 消息是否含工具调用"
  [m]
  (boolean (seq (:tool-calls m))))

(defn system? [m] (= :system (:role m)))
(defn user? [m] (= :user (:role m)))
(defn assistant? [m] (= :assistant (:role m)))
(defn tool? [m] (= :tool (:role m)))

;;; ============================================================
;;; 兼容：legacy 字符串-role map → 中立
;;; ============================================================

(defn normalize
  "把可能是 legacy 形态（role 为字符串）的消息规范化为中立消息。
   已是中立（keyword role）则原样返回。

   仅做 role 关键字化与基本字段透传，供迁移期使用。"
  [m]
  (if (keyword? (:role m))
    m
    (let [r (keyword (:role m))]
      (cond-> (assoc m :role r)
        ;; OpenAI legacy tool 消息：:tool_call_id → :tool-call-id
        (and (= r :tool) (:tool_call_id m))
        (-> (assoc :tool-call-id (:tool_call_id m))
            (dissoc :tool_call_id))))))
