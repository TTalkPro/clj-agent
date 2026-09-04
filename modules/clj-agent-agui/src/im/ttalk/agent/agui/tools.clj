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
  (:require [cheshire.core :as json]
            [im.ttalk.agent.agui.emit :as emit]
            [im.ttalk.agent.agui.event :as event]
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

(defn- coerce-json
  "模型可能把对象/数组发成 **JSON 字符串**——解一层。解不出集合就返回 nil，
   由调用方回一句能自纠的错，而不是把字符串当状态发出去。

   ⭐ 这一手是刚需，不是防御性编程：快照发成字符串之后，客户端那边是一坨转义
   引号，而且**后续每条 delta 都打不上**（patch 打在字符串上）——上游客户端
   `state = snapshot` 直接替换，不防这一手。整条链静默失效，一个字都不报。"
  [v]
  (cond
    (coll? v) v
    (string? v) (let [parsed (try (json/parse-string v true) (catch Exception _ nil))]
                  (when (coll? parsed) parsed))
    :else nil))

(defn state-tools
  "给模型两把**写共享状态**的工具，闭包在本 run 的发射器上。

   名字照抄上游（`CopilotKit/packages/runtime/src/agent/index.ts` 的
   `AGUISendStateSnapshot` / `AGUISendStateDelta`）——第三种叫法只会逼每个前端
   再写一层适配。

   为什么必须是**内联工具**而不是 `deftool` 的 var：它们要往**这一条 run 的
   发射器**上发事件，而 var 是进程级的。闭包是唯一能把 run 绑进去的形状
   （同 `delegate-tool` 的 `:observer`）。

   读面（`/threads/:id/state`）本来就有；缺的一直是这半边——没有写的入口，
   那条链永远不启动。"
  [em]
  [{:name "AGUISendStateSnapshot"
    :description (str "把**整份**共享状态发给前端（整体替换）。"
                      "首次建立状态、或结构大改时用这个；只改一两个字段用 "
                      "AGUISendStateDelta 更省。")
    :parameters {:type "object"
                 :properties {:snapshot {:type "object"
                                         :description "完整的状态对象（不是 JSON 字符串）"}}
                 :required ["snapshot"]}
    :handler (fn [args _ctx]
               (if-let [snap (coerce-json (or (:snapshot args) (get args "snapshot")))]
                 (do (event/emit-state-snapshot! em snap)
                     "共享状态已整体更新")
                 "snapshot 必须是一个状态**对象**（不是字符串、不是数组），请重发"))}

   {:name "AGUISendStateDelta"
    :description (str "用 JSON Patch（RFC 6902）增量修改共享状态。"
                      "op 取 add / remove / replace，path 是 JSON Pointer，"
                      "例如给列表追加：{\"op\":\"add\",\"path\":\"/todos/-\",\"value\":…}。")
    :parameters {:type "object"
                 :properties {:delta {:type "array"
                                      :description "RFC 6902 操作数组"
                                      :items {:type "object"}}}
                 :required ["delta"]}
    :handler (fn [args _ctx]
               (let [ops (coerce-json (or (:delta args) (get args "delta")))]
                 (if (sequential? ops)
                   (do (event/emit-state-delta! em (vec ops))
                       "共享状态已增量更新")
                   "delta 必须是一个 JSON Patch **操作数组**，请重发")))}])

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
