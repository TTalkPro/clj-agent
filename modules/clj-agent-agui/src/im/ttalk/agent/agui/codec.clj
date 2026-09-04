(ns im.ttalk.agent.agui.codec
  "中立事件 ⇄ AG-UI 协议。

   **runtime 发中立事件，协议知识全在这一层**——换个前端协议只换这个 ns，
   runtime / event / emit 一个字都不用改（对称于「core 收回厂商 wire 知识」，
   见 docs/response-path-consolidation.md）。

   本 ns **不产出 HTTP 任何东西**：返回的是 Clojure map，SSE 帧、路由、CORS
   在 examples/copilotkit/（design-principles §2）。

   AG-UI 字段名逐个对过 CopilotKit 的 `middleware-sse-parser.test.ts`：
   `TOOL_CALL_START` 是 `toolCallName` 不是 `name`，`TOOL_CALL_ARGS` 的
   `delta` 是 **JSON 字符串**不是对象——这类地方猜错了前端不会报错，只会静默
   少渲染一块。"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [im.ttalk.agent.model.content :as content]
            [im.ttalk.agent.model.message :as msg]))

(set! *warn-on-reflection* true)

(declare message->agui)

(defn interrupt-id
  "一次暂停的稳定标识 —— 发出去是 `RUN_FINISHED.outcome.interrupts[].id`，
   客户端原样送回在 `RunAgentInput.resume[].interruptId` 里。

   取 pending 工具的 **tool-call id**：它本来就唯一，且与同一条 interrupt 的
   `toolCallId` 同源，前端把审批条挂回那张工具卡片不用二次关联。没有 pending
   工具的暂停（纯 ask-user）退回 run-id。

   **入参既是暂停事件也是 `runtime/awaiting` 的返回值**——两者 `:run-id` /
   `:pending-tool` 同形，路由侧据此校验「客户端回的是不是当前这一条」。"
  [{:keys [run-id pending-tool]}]
  (or (some-> (get-in pending-tool [:tool-call :id]) str)
      (str run-id "-interrupt")))

(def ^:private frontend-tool-response-schema
  "**前端工具**那条 interrupt 该回什么：这次调用的**结果**，不是决策。

   它要的不是批准——活在客户端，服务端只是停下来等它执行完把结果送回来
   （ask-user 语义，见路由的 resume 分支）。给 decision 枚举是**误导**：客户端
   照着回 `{\"status\":\"cancelled\"}`（取消不带载荷，协议里 payload 本就可选），
   落到 `:reply` 那支就变成一个**空字符串结果**，模型据此宣布「已成功执行」
   ——拒绝在语义上被吃掉，而且是往成功的方向吃。"
  {:type "object"
   :properties {:result {:description "这次工具调用的结果（客户端执行后回传）"}}
   :required ["result"]})

(def ^:private decision-response-schema
  "`Interrupt.responseSchema`：告诉前端「这条 interrupt 该回什么」，通用客户端
   照它渲染审批控件。

   我们的 resume 入口就是 `resume-run!` 的 decision，所以形状是决策而不是工具
   结果。前端工具那条路（AG-UI client-side tool）不看它——它自己以 tool-call
   的方式回结果（§7.2）。"
  {:type "object"
   :properties {:decision {:type "string" :enum ["approved" "rejected"]}}
   :required ["decision"]})

(defn- reason-str
  "AG-UI 的 `Interrupt.reason` 是必填字符串；我们的 `:reason` 可能是关键字。"
  [reason]
  (cond
    (keyword? reason) (name reason)
    (some? reason) (str reason)
    :else "paused"))

(defn- interrupt-of
  "暂停事件 → AG-UI `Interrupt`。

   **两类挂起必须在 wire 上分得开**，因为 resume 走的是两支完全不同的路：

   | `metadata.kind` | 挂起的是 | 客户端该回 |
   |---|---|---|
   | `\"approval\"` | 服务端 `:sensitive` 工具 | **决策**——活还在服务端等着干 |
   | `\"frontend-tool\"` | 客户端声明的工具 | **结果**——活在客户端 |

   曾经两者逐字段同形（都写「需要审批」、都给 decision 枚举），客户端没有任何
   字段能分开：照审批去回 `{status:\"cancelled\"}`（不带 payload）落到前端工具那支
   就成了**空字符串结果**，模型据此宣布「已成功执行」——拒绝往成功的方向被吃掉。

   三处一起改，客户端照哪一处判都对：`reason` 的措辞、`responseSchema` 的形状、
   `metadata.kind` 的标签。"
  [{:keys [pending-tool pending-frontend?] :as ev}]
  (let [tc-id (get-in pending-tool [:tool-call :id])
        name* (str (:name pending-tool))]
    (cond-> {:id (interrupt-id ev)
             :reason (if pending-frontend?
                       ;; 它要的不是批准，是**客户端去执行**
                       (str "需要客户端执行并回传结果: " name*)
                       (reason-str (:reason ev)))
             :responseSchema (or (:response-schema ev)
                                 (if pending-frontend?
                                   frontend-tool-response-schema
                                   decision-response-schema))}
      (:message ev) (assoc :message (str (:message ev)))
      tc-id         (assoc :toolCallId (str tc-id))
      pending-tool  (assoc :metadata {:kind (if pending-frontend? "frontend-tool" "approval")
                                      :pendingTool {:name (:name pending-tool)
                                                    :args (:args pending-tool)}}))))

(def ^:private subagent-outcome-message
  "AG-UI 没有 killed / timeout 这两种收尾，按 `SUBAGENT_ERROR` 发并把原因写进 code
   ——同 `:run/cancelled → RUN_FINISHED + result.status` 的既有取舍：宁可换个类型
   说清楚，也不造协议外的类型。"
  {:killed  "子 agent 已被终止"
   :timeout "子 agent 超时"})

(defn- usage->agui
  "中立用量条目 → AG-UI 的 `usage[]` 条目。

   键名逐个对着 `@ag-ui/core` 的 `RunFinishedEventSchema.usage`：`provider` /
   `model` / `inputTokens` / `outputTokens` / `totalTokens` / `reasoningTokens` /
   `cachedInputTokens`，**全部可选**。所以没有的位就不发——发一个 0 会被客户端
   当成「真的用了 0 个」求和进去。

   我们的 `:cache-write-tokens` / `:cache-miss-tokens` 在协议里没有对应位，
   不硬塞（AG-UI 的事件 schema 是 passthrough，塞了也只是没人读的私货）。"
  [u]
  (cond-> {}
    (:provider u)           (assoc :provider (str (:provider u)))
    (:model u)              (assoc :model (str (:model u)))
    (:input-tokens u)       (assoc :inputTokens (:input-tokens u))
    (:output-tokens u)      (assoc :outputTokens (:output-tokens u))
    (:total-tokens u)       (assoc :totalTokens (:total-tokens u))
    (:cache-read-tokens u)  (assoc :cachedInputTokens (:cache-read-tokens u))))

(defn- with-usage
  "终态事件带上本 run 的用量（没有就不带这个键）。

   **是数组**：一条 run 里可能换过模型、也可能有子 agent，客户端按数组求和
   （happy 的 `usage-totals`、CopilotKit 的用量显示都是这么写的）。"
  [agui ev]
  (cond-> agui
    (seq (:usage ev)) (assoc :usage (mapv usage->agui (:usage ev)))))

(defn- agui-of
  "中立事件 → AG-UI 事件 map。返回 nil = 该事件在 AG-UI 里没有对应物（丢弃）。"
  [{:keys [type run-id conversation-id message-id tool-call-id] :as ev}]
  (case type
    ;; `parentRunId` 只有 `RUN_STARTED` 有这一位（`RUN_FINISHED` / `RUN_ERROR`
    ;; 都没有），所以父链的声明就这一次机会
    :run/started    (cond-> {:type "RUN_STARTED" :threadId conversation-id :runId run-id}
                      (:parent-run-id ev) (assoc :parentRunId (str (:parent-run-id ev))))
    :run/finished   (with-usage {:type "RUN_FINISHED" :threadId conversation-id :runId run-id} ev)
    ;; AG-UI 没有 cancelled：按「结束了，但带原因」发，前端照常收口
    :run/cancelled  (with-usage {:type "RUN_FINISHED" :threadId conversation-id :runId run-id
                                 :result {:status "cancelled"}} ev)
    ;; `RUN_ERROR` 在协议里同样有 `usage`——半途炸掉的那半轮 token 也是花掉的
    :run/error      (with-usage {:type "RUN_ERROR"
                                 :message (or (get-in ev [:error :message]) "run failed")
                                 :code (some-> (get-in ev [:error :class]) name)} ev)
    ;; AG-UI 的一等暂停态：`RUN_FINISHED` + `outcome:"interrupt"`（interrupt 协议）。
    ;; 收口与告知是**同一条**事件——run 有终态（流可以关），outcome 又说清了
    ;; 「不是跑完了，是停在这儿等人」。客户端 `useInterrupt` / `useHumanInTheLoop`
    ;; 直接接管渲染，答复走下一次 run 的 `resume[]`（见 §5.2）。
    :run/paused     (with-usage {:type "RUN_FINISHED" :threadId conversation-id :runId run-id
                                 :outcome {:type "interrupt" :interrupts [(interrupt-of ev)]}} ev)

    :message/started {:type "TEXT_MESSAGE_START" :messageId message-id :role "assistant"}
    :message/delta   {:type "TEXT_MESSAGE_CONTENT" :messageId message-id :delta (:text ev)}
    :message/ended   {:type "TEXT_MESSAGE_END" :messageId message-id}
    ;; 思考块：AG-UI 的一等 reasoning 消息（`role: "reasoning"`），CopilotKit
    ;; 前端有专门的折叠面板渲染它（`CopilotChatReasoningMessage`）。
    ;; **不要用 `THINKING_*`**——0.0.59 起全部 deprecated，1.0 移除。
    ;; 开合各是**一对**事件（外层 REASONING_START/END + 内层消息开合），
    ;; 由 `->agui-events` 展开；这里给的是那一对里的内层。
    :reasoning/started {:type "REASONING_MESSAGE_START" :messageId message-id
                        :role "reasoning"}
    :message/thinking  {:type "REASONING_MESSAGE_CONTENT" :messageId message-id
                        :delta (:text ev)}
    :reasoning/ended   {:type "REASONING_MESSAGE_END" :messageId message-id}

    :tool/started {:type "TOOL_CALL_START" :toolCallId tool-call-id
                   :toolCallName (:name ev)
                   :parentMessageId (:parent-message-id ev)}
    :tool/args    {:type "TOOL_CALL_ARGS" :toolCallId tool-call-id
                   :delta (json/generate-string (or (:args ev) {}))}
    :tool/ended   {:type "TOOL_CALL_END" :toolCallId tool-call-id}
    :tool/result  {:type "TOOL_CALL_RESULT" :toolCallId tool-call-id
                   :messageId (str tool-call-id "-result")
                   :role "tool"
                   :content (:content ev)}

    :state/snapshot {:type "STATE_SNAPSHOT" :snapshot (:state ev)}
    ;; 增量：RFC 6902 的 op 数组，字段名就叫 `delta`（`StateDeltaEventSchema`）。
    ;; 规范化（补数组、首条前补快照）在发射侧做完了，这层只搬运
    :state/delta    {:type "STATE_DELTA" :delta (vec (:delta ev))}

    ;; 子 agent lane（AG-UI 0.0.59 起的 `SUBAGENT_*` 事件族）。
    ;; **这三个类型是老客户端的雷**：`@ag-ui/client` ≤ 0.0.57 在 HTTP transport 里
    ;; 就拿 discriminated union 校验每一条事件，一条未知类型掐断整条流，客户端侧
    ;; 什么都救不回来。所以产出侧有开关（`runtime` 的 `:subagent-events?`，缺省关）。
    ;; 相比之下**给既有事件多一个 `subagentRunId` 字段是安全的**——AG-UI 的事件
    ;; schema 是 `passthrough`，老客户端原样忽略。
    :subagent/started
    (cond-> {:type "SUBAGENT_STARTED"
             :subagentRunId (:subagent-run-id ev)
             :name (or (:name ev) "subagent")}
      (:task ev)                   (assoc :description (:task ev))
      (:parent-subagent-run-id ev) (assoc :parentSubagentRunId (:parent-subagent-run-id ev))
      (:parent-tool-call-id ev)    (assoc :parentToolCallId (:parent-tool-call-id ev))
      (:parent-message-id ev)      (assoc :parentMessageId (:parent-message-id ev)))

    :subagent/finished
    (let [oc (or (:outcome ev) :success)]
      (cond
        ;; 挂起：停下来等人，不是跑完也不是跑崩。`interruptIds` 让客户端把这张
        ;; 卡片与父 run 那条 interrupt 对上号
        (= :suspended oc)
        (cond-> {:type "SUBAGENT_FINISHED" :subagentRunId (:subagent-run-id ev)
                 :outcome {:type "suspended"}}
          (seq (:interrupt-ids ev))
          (assoc-in [:outcome :interruptIds] (vec (:interrupt-ids ev))))

        (= :success oc)
        (cond-> {:type "SUBAGENT_FINISHED" :subagentRunId (:subagent-run-id ev)
                 :outcome {:type "success"}}
          ;; 可选的完成载荷，语义同 `RUN_FINISHED.result`
          (some? (:result ev)) (assoc :result (:result ev)))

        :else
        {:type "SUBAGENT_ERROR" :subagentRunId (:subagent-run-id ev)
         :message (get subagent-outcome-message oc "子 agent 未正常结束")
         :code (name oc)}))

    :subagent/error
    {:type "SUBAGENT_ERROR"
     :subagentRunId (:subagent-run-id ev)
     :message (or (get-in ev [:error :message]) "subagent failed")
     :code (some-> (get-in ev [:error :class]) name)}

    ;; activity 消息：AG-UI 里「不是聊天文本、但要在对话流里占一块」的东西
    ;; （生成式 UI、进度卡片…）。快照建消息，delta 是 JSON Patch。
    ;; 产出方是 activity 类插件（`agui.a2ui`，或 examples 里的 `copilotkit.genui`），
    ;; 核心路径不发这两条。
    ;; `:replace true` = 这条快照整块换掉上一条（A2UI 就靠它，每次发完整的一份，
    ;; 于是根本不需要 delta）。缺省 false：genui 那条路是 snapshot + 一串 delta
    :activity/snapshot (cond-> {:type "ACTIVITY_SNAPSHOT" :messageId message-id
                                :activityType (:activity-type ev)
                                :content (:content ev)}
                         (:replace ev) (assoc :replace true))
    :activity/delta    {:type "ACTIVITY_DELTA" :messageId message-id
                        :activityType (:activity-type ev)
                        :patch (:patch ev)}
    ;; **不逐条打 `subagentRunId`**：协议留了这一位（一条快照可能混着多个生产者的
    ;; 消息），但在我们的架构里没有东西可归属——快照取自**父会话的 ChatMemory**
    ;; （`runtime/subscribe` 的 resync 分支），而子 agent 缺省 `:memory false`
    ;; （`subagent/manager` 的 do-run），跑在自己那份一次性 store 里，产出以**工具
    ;; 结果字符串**回到父历史，不是以消息落进去。父历史里每一条都是父自己的。
    ;;
    ;; 唯一的例外是用户显式给子 agent 传了与父同一个 store —— 那时子的消息确实会
    ;; 混进来，但我们也无从知道哪条是谁写的（中立消息没有 lane 字段，落库那一步
    ;; 也不认识 lane）。要做得给 memory filter 加「写入时打标」，成本不小而只覆盖
    ;; 一个反常用法。**故意不做**，别下次审计又把它列成缺口。
    :run/resync     {:type "MESSAGES_SNAPSHOT"
                     :messages (into [] (map-indexed (fn [i m] (message->agui m i)))
                                     (:messages ev))}
    nil))

(defn- with-lane
  "给一条 AG-UI 事件打上 lane 归属。

   `:subagent-run-id` 由发射器的 tag 挂在该 lane 的**每一条**事件上
   （`agui.event/subagent-emitter`），到了协议这层就是每条事件多一个 `subagentRunId`。
   统一在这里打而不是逐个 case 写，是因为它对所有类型一视同仁；`SUBAGENT_STARTED`
   自己那份已经在 case 里写死，not-contains 守卫保证不覆盖。"
  [agui ev]
  (cond-> agui
    (and (:subagent-run-id ev) (not (contains? agui :subagentRunId)))
    (assoc :subagentRunId (:subagent-run-id ev))))

(defn ->agui
  "中立事件 → AG-UI 事件 map（nil = 没有对应物），带 lane 归属。"
  [ev]
  (some-> (agui-of ev) (with-lane ev)))

(defn message->agui
  "中立消息 → AG-UI 消息。

   `:id` **优先用消息自己的**——ChatMemory 落库时补的那个（`msg/ensure-id`），
   跨快照稳定。没有才按位置合成（`m-<idx>`）：那只保证「同一次快照内部自洽」，
   `replace-tool-results` / `heal-dangling` 一让位置漂，同一条消息在两次快照里
   就是两个 id。走内置 store 的都有 id，退路是留给第三方 store 的。

   **与事件流的 message-id 不是一个 id 空间**（那边是 `<run-id>-mN`）——合成一个
   要让发射器的 id 流进 `response->neutral`，那是 core 依赖 agui 的方向，不做。"
  ([m] (message->agui m 0))
  ([m idx]
   (let [base {:id (or (:id m) (str "m-" idx)) :role (name (:role m))}]
     (cond
       (msg/tool? m)
       (assoc base :content (:content m) :toolCallId (:tool-call-id m))

       (seq (:tool-calls m))
       (assoc base
              :content (:content m)
              :toolCalls (mapv (fn [tc] {:id (:id tc)
                                         :type "function"
                                         :function {:name (:name tc)
                                                    :arguments (json/generate-string (or (:args tc) {}))}})
                               (:tool-calls m)))

       :else (assoc base :content (:content m))))))

(defn messages->agui
  [messages]
  (into [] (map-indexed (fn [i m] (message->agui m i))) messages))

(def ^:private agui-terminal-types
  #{"RUN_FINISHED" "RUN_ERROR"})

(defn terminal?
  "这条 AG-UI 事件是不是**终态**——传输层据此关流。AG-UI 的 run 必须以终态收口，
   收不到终态的客户端会一直吊着（联调时的表现：工具卡片永远停在 inProgress）。"
  [agui-event]
  (contains? agui-terminal-types (:type agui-event)))

(defn ->agui-events
  "中立事件 → **零或一条** AG-UI 事件（没有对应物则空）。

   曾经是一对多：`:run/paused` 发一条 `CUSTOM` 说「停在这儿等人」，再补一条
   `RUN_FINISHED` 把 run 收口——AG-UI 的 run 必须有终态，少了它 stock 前端不
   报错，只是 HTTP 请求永远不结束、工具卡片永远 `inProgress`（联调实测）。
   改用 interrupt 协议后两件事合成一条：`RUN_FINISHED` 自带 `outcome`，既是
   终态又说清了停因。续跑仍是**新的 run**，前端 `connect` 接着看（§4.7）。

   留着这个函数（而不是让传输层直接调 `->agui`）是对的——思考块马上又用上了：
   一条 `:reasoning/started` 要发 `REASONING_START` + `REASONING_MESSAGE_START`
   两条（外层是「这一段在想」，内层才是那条 reasoning 消息），收口对称。
   `->agui` 给的是内层那条，只认单条的调用方（如 live 脚本的假连接）因此也
   看得见思考内容，只是少了外层括号。"
  [{:keys [type message-id] :as ev}]
  (case type
    ;; 外层那对括号是在这里**合成**的，没走 `->agui`——所以归属得自己打一遍。
    ;; 漏了的话，子 agent 的思考块会出现「括号在对话里、内容在 console 里」。
    :reasoning/started [(with-lane {:type "REASONING_START" :messageId message-id} ev)
                        (->agui ev)]
    :reasoning/ended   [(->agui ev)
                        (with-lane {:type "REASONING_END" :messageId message-id} ev)]
    (if-let [one (->agui ev)] [one] [])))

(defn events->agui
  "批量转换并丢掉没有对应物的（保持顺序）。"
  [events]
  (into [] (mapcat ->agui-events) events))

;;; ============================================================
;;; 入站：RunAgentInput
;;; ============================================================

(defn- part-value
  "AG-UI 部件里取值：`source` 有 `data` / `url` 两档，另有已废弃的扁平 `binary`
   变体（`{type:\"binary\", mimeType, data|url}`）。三种都接，取到什么就交给
   `content/file-part` 自己去分辨。"
  [{:keys [source] :as part}]
  (let [get* (fn [m k] (or (get m k) (get m (name k))))
        src  (or source part)]
    {:value (or (get* src :value) (get* src :data) (get* src :url))
     :mime  (or (get* src :mimeType) (get* src :mime_type) (get* src :mediaType))}))

(defn agui-content->neutral
  "AG-UI 的 `InputContent` 数组 → 中立多模态部件（`im.ttalk.agent.model.content`）。

   **字符串原样返回。** 纯文本消息的形状一个字都不能变 —— 客户端也是这么做的
   （没附件就不升成数组），两边一致，`\"你好\"` 这种消息才不会平白走部件路径。

   映射（协议侧 → 中立侧）：

   | AG-UI | 中立 |
   | --- | --- |
   | `{type:\"text\", text}` | `{:type :text :text …}` |
   | `{type:\"image\"/\"audio\"/\"video\"/\"document\", source:{type:\"data\", value, mimeType}}` | `{:type :file :media-type … :data …}` |
   | 同上但 `source.type = \"url\"` | `{:type :file :media-type … :url …}` |

   image / audio / video / document 在中立层**不各立一类**，统一是 `:file` +
   media-type（与 Vercel AI SDK 的部件模型一致）—— wire 层按 media type 的顶层
   类别分派，新格式不用加新类型。

   ⚠ 不做这一跳的话，部件数组会被 `(str content)` 压成一坨字符串喂给模型：
   **不报错，只是内容全丢**，比报错难查得多。"
  [content*]
  (if-not (sequential? content*)
    content*
    (mapv (fn [part]
            (let [get*  (fn [m k] (or (get m k) (get m (name k))))
                  kind  (str (get* part :type))
                  {:keys [value mime]} (part-value part)
                  fname (get* (or (get* part :metadata) {}) :filename)]
              (case kind
                "text" (content/text-part (or (get* part :text) ""))
                ;; 认不出的类型也当文件走 —— 协议以后加了新类型（比如 3d、
                ;; sheet），照样把数据带下去，总好过整条丢掉
                (content/file-part value (cond-> {}
                                           mime  (assoc :media-type mime)
                                           fname (assoc :filename fname))))))
          content*)))

(defn parse-run-input
  "AG-UI `RunAgentInput` → 我们的 run 参数。

   **历史取服务端权威**（§7.3）：客户端带上来的 `messages` 是它自己那份完整历史，
   而我们的 memory filter 是服务端权威，且循环内落库 / heal-dangling / 暂停恢复 /
   timeline 四样全依赖它。所以这里**只取最后一条 user 消息**，其余忽略；前端要
   对齐历史走 `connect` 的 `MESSAGES_SNAPSHOT`。

   `state` 作为本 run 的**初始 context** 注入——turn 级、不落库，与现有语义逐字
   一致（跨 run 共享状态见 §7.1，路径是 fold-from-history，不建快照店）。

   `resume` 是 interrupt 协议的答复数组（`{interruptId, status, payload}`）：
   上一条 run 以 `outcome:\"interrupt\"` 收口，客户端把人的决定放在**下一次 run
   的请求体**里带回来——协议里没有单独的审批端点，就是这一条。

   `context` 是前端注册的**本轮环境说明**（`useAgentContext`，每次 run 都随请求
   体重发）——渲染成 system 段的活在 `context->prompt`。

   `parentRunId` 是可选的父 run 声明（嵌套 run）。我们**不解释它**，只原样回到
   `RUN_STARTED.parentRunId` 上。⚠️ CopilotKit 的 JS 客户端在发出去之前会把这个
   字段解构掉（`@ag-ui/client` 的 dist 里对它只有一处引用，就是丢弃那处），
   所以走 CopilotKit 这条路它恒为 nil——留着是为别的 AG-UI SDK 与自建客户端。"
  [{:keys [threadId runId messages tools state context forwardedProps resume parentRunId]}]
  (let [last-user (last (filter #(= "user" (or (:role %) (get % "role"))) messages))]
    {:conversation-id threadId
     :run-id runId
     ;; 多模态：客户端可能发 InputContent 数组，翻成中立部件再往下走
     ;; （不翻的话下面 (str …) 会把它压成一坨字符串，内容全丢且不报错）
     :message (agui-content->neutral (or (:content last-user) (get last-user "content")))
     :agui-tools (vec tools)
     :parent-run-id parentRunId
     :state state
     :context (vec context)
     :resume (vec resume)
     :forwarded-props forwardedProps}))

(defn context->prompt
  "AG-UI `RunAgentInput.context` → 一段 system 文本。返回 nil = 这轮没有上下文。

   条目是 `{description, value}`（`ContextSchema`，两个都是字符串）。前端
   `useAgentContext({description, value})` 注册，客户端**每次 run 都重发全量**
   ——所以它天然是 turn 级的：这一轮带就有，下一轮不带就没了。落到我们这边的
   出口是 `chat-async` 的 `:extra-system-prompts`（**追加**，不覆盖 agent 人设），
   同样不进 ChatMemory。

   `value` 按 schema 是字符串，但 `useAgentContext` 收的是 `JsonSerializable`，
   路上谁 stringify 由客户端版本决定——所以这里两种都认，非字符串按 JSON 打平。

   开头那句抬头是给模型的**出处说明**：不写的话这几行会被当成用户指令，而它们
   其实是页面状态。"
  [context]
  (let [lines (->> context
                   (keep (fn [c]
                           (let [desc (or (:description c) (get c "description"))
                                 v (if (contains? c :value) (:value c) (get c "value"))
                                 v-str (cond
                                         (string? v) v
                                         (nil? v) nil
                                         :else (json/generate-string v))]
                             (when (seq (str v-str))
                               (str "- " (if (seq (str desc)) (str desc " ") "") v-str)))))
                   vec)]
    (when (seq lines)
      (str "以下是前端在本轮提供的上下文（AG-UI context，非用户输入）：\n"
           (str/join "\n" lines)))))

(defn messages->thread-messages
  "中立消息 → `/threads/:id/messages` 的形状。

   **与事件流里的 `message->agui` 不是一个形状**（上游也分两套）：这里的
   `toolCalls` 是**扁的** `{id, name, args}`，事件流那套是 OpenAI 风格的
   `{id, type, function:{name, arguments}}`。照抄上游 `handleGetThreadMessages`
   的本地分支——线程列表页读的是这一套。"
  [messages]
  (into []
        (map-indexed
         (fn [i m]
           (let [base {:id (or (:id m) (str "m-" i)) :role (name (:role m))}]
             (cond
               (msg/tool? m) (assoc base :content (:content m) :toolCallId (:tool-call-id m))

               (seq (:tool-calls m))
               (cond-> (assoc base :toolCalls (mapv (fn [tc] {:id (:id tc)
                                                              :name (:name tc)
                                                              :args (:args tc)})
                                                    (:tool-calls m)))
                 (some? (:content m)) (assoc :content (:content m)))

               :else (cond-> base (some? (:content m)) (assoc :content (:content m)))))))
        messages))

(defn agui->messages
  "AG-UI 消息数组 → 中立消息。

   **入站方向，`message->agui` 的反面**。主路径用不着它——`/run` 的历史取服务端
   权威（见 `parse-run-input`）。用得着的是**无状态 run**（`/suggest`）：那条路
   没有服务端线程，客户端发上来的就是全部上下文。

   认不出角色的整条丢掉（客户端 SDK 各有各的扩展，宁可少喂也不喂脏数据）。"
  [messages]
  (into []
        (keep (fn [m]
                (let [role (or (:role m) (get m "role"))
                      content (or (:content m) (get m "content"))
                      tool-calls (or (:toolCalls m) (get m "toolCalls"))
                      tc-id (or (:toolCallId m) (get m "toolCallId"))]
                  (case (str role)
                    ;; user 可能是多模态部件数组 —— 先翻，再决定要不要 str
                    ;; （system / tool 在协议里只有纯文本）
                    "user"      (msg/user (let [c (agui-content->neutral content)]
                                            (if (sequential? c) c (str c))))
                    "system"    (msg/system (str content))
                    "developer" (msg/system (str content))
                    "tool"      (when tc-id (msg/tool-result tc-id nil (str content)))
                    "assistant"
                    (if (seq tool-calls)
                      (msg/assistant-tool-calls
                       (mapv (fn [tc]
                               (let [f (or (:function tc) (get tc "function"))
                                     args (or (:arguments f) (get f "arguments"))]
                                 (msg/tool-call (or (:id tc) (get tc "id"))
                                                (or (:name f) (get f "name"))
                                                (if (string? args)
                                                  (try (json/parse-string args true)
                                                       (catch Exception _ {}))
                                                  (or args {})))))
                             tool-calls)
                       (some-> content str))
                      (msg/assistant (str content)))
                    nil))))
        messages))

(defn run-info
  "`GET {base}/info` 的响应体。

   **`agents` 是以 agent id 为键的字典，不是数组**——客户端拿它做
   `Object.entries(runtimeInfo.agents)` 再按 id 建 proxy（`agent-registry.ts`）。
   给数组不会报错，只会把下标当成 agent id，于是前端去请求 `/agent/0/run`。
   这条是**联调时才照出来的**：单测和 live 脚本都不碰 `/info`。

   只声明我们真支持的——**不谎报能力位**（`intelligence` / `inspectorMetadata`
   是 CopilotKit 云产品的东西，我们没有就是没有；不写这些键等于告诉客户端
   「没有」，它会走降级路径）。

   `className` 客户端不解释，只在调试面板显示。

   `capabilities` 是**每个 agent 一份**（客户端 `agent-registry` 从这里读，
   再交给 `useCapabilities`）。我们报的这三位都是真的：会暂停等人
   （`supported`）、敏感工具要审批（`approvals`）、走 AG-UI 的 interrupt 协议
   （`interrupts`：`RUN_FINISHED outcome=interrupt` + 收 `resume[]`）。
   **`approveWithEdits` 不报**——改参数再执行我们还没实现，不谎报。

   `multimodal` 是**装配方传进来的**，本 ns 不猜：能不能收图取决于两件事——
   wire 认不认部件（`wire/anthropic` / `wire/openai` 都认）**且模型本身有视觉**，
   而后一条 codec 这层判不了。⛔ 也别按「provider 是谁」硬编码：同一个 provider
   下 `MiniMax-M2.7` 没视觉、`qwen-vl` 有。不传就不报这一格——客户端据此把附件
   入口收起来，比摆一颗点了才发现没用的回形针强。"
  ([agent-ids] (run-info agent-ids nil))
  ([agent-ids {:keys [version descriptions capabilities open-generative-ui? a2ui?
                      suggestions? threads? multimodal parallel-tools? max-iterations
                      state?]}]
   (cond->
    {:version (or version "clj-agent-agui/0.3")
     :mode "sse"
     :agents (into {}
                   (map (fn [id]
                          [id {:name id
                               :className "CljAgent"
                               :description (get descriptions id "")
                               :capabilities
                               (cond-> (or capabilities
                                           (cond->
                                            {:humanInTheLoop {:supported true
                                                              :approvals true
                                                              :interrupts true}
                                             ;; AG-UI 的出口就是 SSE，token 逐个发
                                             ;; ——这一格是本层的事实，与模型无关
                                             :transport {:streaming true}
                                             :tools
                                             {:supported true
                                              ;; `RunAgentInput.tools` 里的前端工具
                                              ;; 我们认（`agui-tools/frontend-tool`）
                                              :clientProvided true
                                              ;; **取决于装配时注入的 ToolCallingManager**：
                                              ;; 缺省引擎（Sequential）全程内联，是串行；
                                              ;; 要并行得注入 `virtual-thread-tool-calling-manager`。
                                              ;; 不传即 false —— 缺省构建下那就是实情
                                              :parallelCalls (boolean parallel-tools?)}
                                             ;; 我们发 REASONING_* 一族（`:reasoning/started`
                                             ;; → `:message/thinking` → `:reasoning/ended`）。
                                             ;; **读作「模型出思考我们就送到」，不是
                                             ;; 「模型一定有思考」**——后者本层判不了，
                                             ;; 同 `multimodal` 那一格的分工
                                             :reasoning {:supported true :streaming true}}
                                             ;; 共享状态：**装了写状态的工具才报**
                                             ;; （`agui.tools/state-tools`，runtime 的
                                             ;; `:state-tools?` 开关）。读面
                                             ;; （`/threads/:id/state`）一直都在，但
                                             ;; 没有写的那一半，这一格报 true 就是
                                             ;; 谎报——模型根本写不动
                                             state?
                                             (assoc :state {:snapshots true :deltas true})
                                             ;; 循环上限：装配方传，别在这儿抄一份缺省值
                                             ;; （`simple-agent/default-max-iterations`）
                                             max-iterations
                                             (assoc :execution {:maxIterations max-iterations})))
                                 multimodal (assoc :multimodal multimodal))}]))
                   agent-ids)
     :audioFileTranscriptionEnabled false
     ;; 报 true 客户端才会走 `/suggest` 那条无状态路；报 false（或不报）它就把
     ;; `copilotkitSuggest` 塞进 `/run` 的 tools 里自己凑合——那条路会把会话卡死
     ;; （设计文档 §9.10 第 5 条）
     :suggestions (boolean suggestions?)
     ;; 装了 Open Generative UI 插件（`examples/copilotkit/genui.clj`）才报 true——
     ;; 前端据此才会渲染沙箱 UI（并注册它那半边的 renderer）。不装就是 false，
     ;; 这条与「不谎报能力位」同源
     :openGenerativeUIEnabled (boolean open-generative-ui?)
     ;; A2UI 同理（插件是本模块的 `agui.a2ui`）。**扁平位与对象都要发**：客户端读的是
     ;; `a2uiInfo?.enabled ?? a2uiEnabled ?? false`（`agent-registry.ts:1351`）——
     ;; 扁平那个是给老客户端的兼容位，对象那个才是新的真相源
     :a2uiEnabled (boolean a2ui?)
     :telemetryDisabled true

     ;; 线程面。四位分别对着客户端的四个读取点：
     ;;   `list`      GET /threads —— `use-threads.tsx:283` 读它，false 就不拉列表
     ;;   `inspect`   GET /threads/:id/{messages,events,state} —— Inspector 的
     ;;               线程详情（`web-inspector` 18336/18364）据此才让你点开
     ;;   `mutations` 改名 / 归档 / DELETE /threads/:id —— `use-threads.tsx:285`
     ;;   `realtimeMetadata` **恒 false**：那是 `/threads/subscribe` 的实时推送，
     ;;               Intelligence（云产品）的东西，我们如实 404，客户端降级轮询
     ;;               （报 true 会让 `CopilotChat.tsx:285` 去等一条永远不来的流）
     ;;
     ;; **无论开关开还是关，这个键都发**——关的时候发一份全 false，而不是省略。
     ;; 省略与「明确说没有」在客户端那儿是两码事：`undefined` 只能理解成「这台
     ;; 没说」，于是它只好盲发一枪 `/threads` 拿 404 当答案（happy 的 TASK.md 就
     ;; 明写了「那一枪故意保留：不是所有运行时都声明 threadEndpoints」）。而
     ;; **「探测式能力发现」正是 `/info` 该消灭的东西**——同 usage 那条反馈里的
     ;; 「别在 /info 里留想象空间」。
     ;;
     ;; 这也不违反「不谎报能力位」：那条禁的是**报了没有的**，不是禁「如实说没有」。
     ;; 全 false 与省略在协议语义上等价（`list !== false` 两边都判否），差别只在
     ;; 客户端要不要多打一枪才知道。
     :threadEndpoints {:list (boolean threads?)
                       :inspect (boolean threads?)
                       :mutations (boolean threads?)
                       :realtimeMetadata false}}

     ;; 对象只在开着时发，与上游一致。**不带 `agents`**——那是「A2UI 只对某几个
     ;; agent 生效」的按 agent 限定（上游 #5369），而我们的 a2ui 是 runtime 级的
     ;; `:event-transform`，对所有 agent 一视同仁；客户端把缺省的 `agents` 解释成
     ;; 「对每个 agent 都生效」（`agent-registry.ts:249`），正是这个意思
     a2ui? (assoc :a2ui {:enabled true}))))

