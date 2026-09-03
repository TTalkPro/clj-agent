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

(def ^:private paused-custom-name
  "AG-UI 没有一等的暂停态（CopilotKit 的 HITL 是「等前端返回结果的 tool call」）。
   先走 `CUSTOM`（设计文档 §5.2 待拍板项 2 的 (a)）：前端自定义渲染即可，
   不必先把我们的暂停语义硬塞进它的 tool-call 形状。"
  "cljagent.run.paused")

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
    :run/paused     {:type "CUSTOM" :name paused-custom-name
                     :value {:threadId conversation-id :runId run-id
                             :reason (:reason ev)
                             :pendingTool (when-let [pt (:pending-tool ev)]
                                            {:name (:name pt)
                                             :args (:args pt)
                                             :toolCallId (get-in pt [:tool-call :id])})}}

    :message/started {:type "TEXT_MESSAGE_START" :messageId message-id :role "assistant"}
    :message/delta   {:type "TEXT_MESSAGE_CONTENT" :messageId message-id :delta (:text ev)}
    :message/ended   {:type "TEXT_MESSAGE_END" :messageId message-id}
    ;; 思维 token：AG-UI 的 THINKING_* 不是所有前端都认，走 CUSTOM 最稳
    :message/thinking {:type "CUSTOM" :name "cljagent.thinking"
                       :value {:messageId message-id :delta (:text ev)}}

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
  "中立事件 → **一到多条** AG-UI 事件（没有对应物则空）。

   为什么会有一对多：`:run/paused` 在 AG-UI 里没有一等对应物，我们发一条
   `CUSTOM` 让前端知道「停在这儿等人」，**再补一条 `RUN_FINISHED` 把这条 run
   收口**——AG-UI 的 run 必须有终态。少了它，stock CopilotKit 前端不会报错，
   只是那个 HTTP 请求永远不结束、工具卡片永远 `inProgress`（联调实测）。
   续跑是**新的 run**，前端 `connect` 就能接着看（§4.7）。"
  [{:keys [type run-id conversation-id] :as ev}]
  (if (= :run/paused type)
    [(->agui ev)
     {:type "RUN_FINISHED" :threadId conversation-id :runId run-id
      :result {:status "paused" :reason (:reason ev)}}]
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
   一致（跨 run 共享状态见 §7.1，路径是 fold-from-history，不建快照店）。"
  [{:keys [threadId runId messages tools state forwardedProps]}]
  (let [last-user (last (filter #(= "user" (or (:role %) (get % "role"))) messages))]
    {:conversation-id threadId
     :run-id runId
     :message (or (:content last-user) (get last-user "content"))
     :agui-tools (vec tools)
     :state state
     :forwarded-props forwardedProps}))

(defn run-info
  "`GET {base}/info` 的响应体。

   **`agents` 是以 agent id 为键的字典，不是数组**——客户端拿它做
   `Object.entries(runtimeInfo.agents)` 再按 id 建 proxy（`agent-registry.ts`）。
   给数组不会报错，只会把下标当成 agent id，于是前端去请求 `/agent/0/run`。
   这条是**联调时才照出来的**：单测和 live 脚本都不碰 `/info`。

   只声明我们真支持的——**不谎报能力位**（`intelligence` / `inspectorMetadata` /
   `threadEndpoints` 是 CopilotKit 云产品的东西，我们没有就是没有；不写这些键
   等于告诉客户端「没有」，它会走降级路径）。

   `className` 客户端不解释，只在调试面板显示。"
  ([agent-ids] (run-info agent-ids nil))
  ([agent-ids {:keys [version descriptions]}]
   {:version (or version "clj-agent-agui/0.3")
    :mode "sse"
    :agents (into {}
                  (map (fn [id]
                         [id {:name id
                              :className "CljAgent"
                              :description (get descriptions id "")}]))
                  agent-ids)
    :audioFileTranscriptionEnabled false
    :suggestions false
    :openGenerativeUIEnabled false
    :telemetryDisabled true}))
