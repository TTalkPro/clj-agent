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
  "暂停事件 → AG-UI `Interrupt`。"
  [{:keys [pending-tool] :as ev}]
  (let [tc-id (get-in pending-tool [:tool-call :id])]
    (cond-> {:id (interrupt-id ev)
             :reason (reason-str (:reason ev))
             :responseSchema (or (:response-schema ev) decision-response-schema)}
      (:message ev) (assoc :message (str (:message ev)))
      tc-id         (assoc :toolCallId (str tc-id))
      pending-tool  (assoc :metadata {:pendingTool {:name (:name pending-tool)
                                                    :args (:args pending-tool)}}))))

(def ^:private subagent-outcome-message
  "AG-UI 没有 killed / timeout 这两种收尾，按 `SUBAGENT_ERROR` 发并把原因写进 code
   ——同 `:run/cancelled → RUN_FINISHED + result.status` 的既有取舍：宁可换个类型
   说清楚，也不造协议外的类型。"
  {:killed  "子 agent 已被终止"
   :timeout "子 agent 超时"})

(defn- agui-of
  "中立事件 → AG-UI 事件 map。返回 nil = 该事件在 AG-UI 里没有对应物（丢弃）。"
  [{:keys [type run-id conversation-id message-id tool-call-id] :as ev}]
  (case type
    ;; `parentRunId` 只有 `RUN_STARTED` 有这一位（`RUN_FINISHED` / `RUN_ERROR`
    ;; 都没有），所以父链的声明就这一次机会
    :run/started    (cond-> {:type "RUN_STARTED" :threadId conversation-id :runId run-id}
                      (:parent-run-id ev) (assoc :parentRunId (str (:parent-run-id ev))))
    :run/finished   {:type "RUN_FINISHED" :threadId conversation-id :runId run-id}
    ;; AG-UI 没有 cancelled：按「结束了，但带原因」发，前端照常收口
    :run/cancelled  {:type "RUN_FINISHED" :threadId conversation-id :runId run-id
                     :result {:status "cancelled"}}
    :run/error      {:type "RUN_ERROR"
                     :message (or (get-in ev [:error :message]) "run failed")
                     :code (some-> (get-in ev [:error :class]) name)}
    ;; AG-UI 的一等暂停态：`RUN_FINISHED` + `outcome:"interrupt"`（interrupt 协议）。
    ;; 收口与告知是**同一条**事件——run 有终态（流可以关），outcome 又说清了
    ;; 「不是跑完了，是停在这儿等人」。客户端 `useInterrupt` / `useHumanInTheLoop`
    ;; 直接接管渲染，答复走下一次 run 的 `resume[]`（见 §5.2）。
    :run/paused     {:type "RUN_FINISHED" :threadId conversation-id :runId run-id
                     :outcome {:type "interrupt" :interrupts [(interrupt-of ev)]}}

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
      (if (= :success oc)
        (cond-> {:type "SUBAGENT_FINISHED" :subagentRunId (:subagent-run-id ev)
                 :outcome {:type "success"}}
          ;; 可选的完成载荷，语义同 `RUN_FINISHED.result`
          (some? (:result ev)) (assoc :result (:result ev)))
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
   **`approveWithEdits` 不报**——改参数再执行我们还没实现，不谎报。"
  ([agent-ids] (run-info agent-ids nil))
  ([agent-ids {:keys [version descriptions capabilities open-generative-ui? a2ui?
                      suggestions? threads?]}]
   (cond->
    {:version (or version "clj-agent-agui/0.3")
     :mode "sse"
     :agents (into {}
                   (map (fn [id]
                          [id {:name id
                               :className "CljAgent"
                               :description (get descriptions id "")
                               :capabilities (or capabilities
                                                 {:humanInTheLoop {:supported true
                                                                   :approvals true
                                                                   :interrupts true}})}]))
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
     :telemetryDisabled true}

     ;; 对象只在开着时发，与上游一致。**不带 `agents`**——那是「A2UI 只对某几个
     ;; agent 生效」的按 agent 限定（上游 #5369），而我们的 a2ui 是 runtime 级的
     ;; `:event-transform`，对所有 agent 一视同仁；客户端把缺省的 `agents` 解释成
     ;; 「对每个 agent 都生效」（`agent-registry.ts:249`），正是这个意思
     a2ui? (assoc :a2ui {:enabled true})

     ;; 线程面：**只在 web 层真挂了 `/threads` 时才报**。客户端把这四位当成
     ;; 「这个 runtime 有没有这一档端点」的开关，缺省即降级：
     ;;   `list`      GET /threads —— `use-threads.tsx:283` 读它，false 就不拉列表
     ;;   `inspect`   GET /threads/:id/{messages,events,state} —— Inspector 的
     ;;               线程详情（`web-inspector` 18336/18364）据此才让你点开
     ;;   `mutations` 改名 / 归档 / DELETE /threads/:id —— `use-threads.tsx:285`
     ;;   `realtimeMetadata` **报 false**：那是 `/threads/subscribe` 的实时推送，
     ;;               Intelligence（云产品）的东西，我们如实 404，客户端降级轮询
     ;;               （报 true 会让 `CopilotChat.tsx:285` 去等一条永远不来的流）
     ;; 不报这个键 = 客户端 `threadEndpoints` 为 `undefined`，Inspector 的
     ;; `areThreadEndpointsAvailable()` 判 `typeof undefined === "object"` 为假，
     ;; 整个线程面对着我们是锁着的——四条路由明明都在跑（联调实测）。
     threads? (assoc :threadEndpoints {:list true
                                       :inspect true
                                       :mutations true
                                       :realtimeMetadata false}))))
