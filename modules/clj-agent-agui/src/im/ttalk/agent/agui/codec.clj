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

(defn ->agui
  "中立事件 → AG-UI 事件 map。返回 nil = 该事件在 AG-UI 里没有对应物（丢弃）。"
  [{:keys [type run-id conversation-id message-id tool-call-id] :as ev}]
  (case type
    :run/started    {:type "RUN_STARTED" :threadId conversation-id :runId run-id}
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

    ;; activity 消息：AG-UI 里「不是聊天文本、但要在对话流里占一块」的东西
    ;; （生成式 UI、进度卡片…）。快照建消息，delta 是 JSON Patch。
    ;; 产出方见 `agui.genui`（可选插件），核心路径不发这两条。
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

(defn message->agui
  "中立消息 → AG-UI 消息。

   `:id` 是**合成的**：中立消息没有 id（design 文档 §6.7），这里按位置合成。
   `replace-tool-results` / `heal-dangling` 会让位置漂，所以合成 id 只保证
   「同一次快照内部自洽」，不保证跨快照稳定——那条要框架给中立消息加可选 `:id`
   才能真解决，先按验收项撞一次再定。"
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
    :reasoning/started [{:type "REASONING_START" :messageId message-id}
                        (->agui ev)]
    :reasoning/ended   [(->agui ev)
                        {:type "REASONING_END" :messageId message-id}]
    (if-let [one (->agui ev)] [one] [])))

(defn events->agui
  "批量转换并丢掉没有对应物的（保持顺序）。"
  [events]
  (into [] (mapcat ->agui-events) events))

;;; ============================================================
;;; 入站：RunAgentInput
;;; ============================================================

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
   的请求体**里带回来——协议里没有单独的审批端点，就是这一条。"
  [{:keys [threadId runId messages tools state forwardedProps resume]}]
  (let [last-user (last (filter #(= "user" (or (:role %) (get % "role"))) messages))]
    {:conversation-id threadId
     :run-id runId
     :message (or (:content last-user) (get last-user "content"))
     :agui-tools (vec tools)
     :state state
     :resume (vec resume)
     :forwarded-props forwardedProps}))

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
                    "user"      (msg/user (str content))
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

   只声明我们真支持的——**不谎报能力位**（`intelligence` / `inspectorMetadata` /
   `threadEndpoints` 是 CopilotKit 云产品的东西，我们没有就是没有；不写这些键
   等于告诉客户端「没有」，它会走降级路径）。

   `className` 客户端不解释，只在调试面板显示。

   `capabilities` 是**每个 agent 一份**（客户端 `agent-registry` 从这里读，
   再交给 `useCapabilities`）。我们报的这三位都是真的：会暂停等人
   （`supported`）、敏感工具要审批（`approvals`）、走 AG-UI 的 interrupt 协议
   （`interrupts`：`RUN_FINISHED outcome=interrupt` + 收 `resume[]`）。
   **`approveWithEdits` 不报**——改参数再执行我们还没实现，不谎报。"
  ([agent-ids] (run-info agent-ids nil))
  ([agent-ids {:keys [version descriptions capabilities open-generative-ui? suggestions?]}]
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
    ;; 装了 `agui.genui` 插件才报 true——前端据此才会渲染沙箱 UI（并注册它那半边
    ;; 的 renderer）。不装就是 false，这条与「不谎报能力位」同源
    :openGenerativeUIEnabled (boolean open-generative-ui?)
    :telemetryDisabled true}))
