(ns im.ttalk.agent.agui.tools
  "AG-UI 前端工具（client-side tool / CopilotKit `useCopilotAction`）。

   **零框架改动**——三样都是现成的（docs/agent-runtime-design.md §7.2）：

   | AG-UI 要什么 | 我们已有的 |
   |---|---|
   | 前端 action 的 schema 进工具列表 | **内联工具 map**：`:handler` 之外的键**原样就是**发给模型的 schema（`chat-client/build-chat-client`） |
   | 模型发出 tool call 后挂起、等前端 | gate 判 `:pause` → 暂停快照自动落 PauseStore |
   | 前端执行完把结果送回 | `resume-run! :reply {:message 结果}`——「pending 工具不执行、载荷即其结果」正是 ask-user 语义 |

   于是「AG-UI 前端工具」不是一套新机制，只是**既有 HITL 词汇的一个用法**。

   **前端标记走 metadata 而不是 map 里的键**：那个 map 去掉 `:handler` 之后
   整个发给模型，多一个 `:agui/frontend` 就是往 wire 上塞私货；metadata 不序列化。"
  (:require [im.ttalk.agent.agui.emit :as emit]
            [im.ttalk.agent.simple-agent :as agent]))

(set! *warn-on-reflection* true)

(defn frontend-tool
  "AG-UI tool（`{:name :description :parameters}`，parameters 是 JSON Schema）
   → 内联工具 map。

   `:handler` 正常路径下**永远不会被调用**（gate 先把这一轮暂停了）。留一个会
   抛的 handler 而不是 `(constantly nil)`：真跑到了说明 gate 没接上，那时候
   静默返回 nil 比抛出去更难查。"
  [{:keys [name description parameters] :as agui-tool}]
  (with-meta
    (cond-> {:name (clojure.core/name name)
             :handler (fn [_args _ctx]
                        (throw (ex-info (str "前端工具 " name " 不该在服务端执行"
                                             "——gate 没有把这一轮暂停住")
                                        {:tool name})))}
      description (assoc :description description)
      parameters  (assoc :parameters parameters)
      (:strict agui-tool) (assoc :strict (:strict agui-tool)))
    {::frontend true}))

(defn frontend?
  [tool]
  (boolean (::frontend (meta tool))))

(defn frontend-names
  [tools]
  (into #{} (comp (filter frontend?) (map :name)) tools))

(defn interrupt-callback
  "`:on-tool-call` 回调：前端工具一律暂停。

   返回 `{:interrupt ...}` 即触发 gate 的 `:pause`（`simple-agent/gate-of`）。
   注意 `tool-name` 在回调里是**字符串**——拿 keyword 去比会永不相等，
   `callbacks.clj` 的 docstring 记着这个踩过的坑。"
  [names]
  (fn [tool-name _args]
    (when (contains? names tool-name)
      {:interrupt :frontend-tool})))

(defn agent-fn
  "把一份 `create-agent` 配置包成 runtime 要的 `:agent-fn`，并自动接好前端工具。

   ```clojure
   (rt/runtime {:agent-fn (tools/agent-fn {:provider p :memory store :pause-store ps
                                           :tools [#'my-server-tool]})})
   ;; 每个 run 的前端 action：
   (rt/start-run! rt \"c1\" \"帮我改主题\"
                  {:tools (map tools/frontend-tool (:tools agui-input))})
   ```

   每 run 现建一个 agent/ChatClient——**装配期就定好的工具集容不下每请求变化的
   前端 action**（§6.6）。runtime 随后会把会话级 state-atom 换上去，所以暂停态
   不会因为重建而丢。"
  [spec]
  (fn [{:keys [conversation-id tools]}]
    (let [tools (vec tools)
          names (frontend-names tools)]
      (agent/create-agent
       (cond-> (assoc spec :conversation-id conversation-id)
         (seq tools) (update :tools #(into (vec %) tools))
         (seq names) (update :callbacks emit/compose-callbacks
                             {:on-tool-call (interrupt-callback names)}))))))
