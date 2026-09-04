(ns copilotkit.http-kit-routes
  "把 agui runtime 挂成 CopilotKit v2 前端直连的 HTTP 端点（http-kit 版）。

   **这一层刻意不在库里**（design-principles §2：agent 框架是一个库，不是 web
   应用）。库给的是「凭 conversation-id 找到 run」和「订阅事件」两件事；
   SSE 帧、路由、CORS、鉴权是你的 web 栈的事，就长这样：

   依赖（除 core / client / agui 外）：
     http-kit/http-kit  {:mvn/version \"2.8.0\"}
     cheshire/cheshire  {:mvn/version \"5.12.0\"}

   CopilotKit v2 前端只用四条路由（其余 threads / memories / suggest /
   transcribe 是它的产品功能，缺席即降级）：

     GET  {base}/info                       → 有哪些 agent（agents 是**字典**）
     POST {base}/agent/:id/suggest          → 无状态建议 run（可选，见 handle-suggest）
     GET  {base}/threads …                  → 线程只读面（可选，见 handle-threads）
     POST {base}/agent/:id/run              → 起 run，SSE 回事件流
     POST {base}/agent/:id/connect          → 只订阅（重连/旁观），SSE
     POST {base}/agent/:id/stop/:threadId   → 停（**threadId 在路径里，没有请求体**）

   这四条的形状全部对着 `packages/core/src/agent.ts` 与 `agent-registry.ts` 抄的
   ——联调时照出来两处：`/info` 的 agents 是字典（给数组前端会去请求
   `/agent/0/run`），`/stop` 的 threadId 是**路径段**不是请求体。

   前端这样接：
     <CopilotKit runtimeUrl=\"http://localhost:3000/api/copilotkit\"> … </CopilotKit>

   运行（REPL）：(start! 3000)"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [im.ttalk.agent.agui.codec :as codec]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.agui.state :as agui-state]
            [im.ttalk.agent.agui.tools :as agui-tools]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.simple-agent :as agent]
            [im.ttalk.agent.tool :as tool]
            [org.httpkit.server :as hk]))

;;; ============================================================
;;; SSE
;;; ============================================================

(def ^:private allowed-origins
  "开发期的前端源白名单：Next dev 默认起在 :3000，端口被占时它自己退到 :3002，
   两个都得认。真部署换成你的域名。"
  #{"http://localhost:3000"  "http://127.0.0.1:3000"
    "http://localhost:3002"  "http://127.0.0.1:3002"})

(def ^:private ^:dynamic cors-headers
  "当前请求的 CORS 头；`handler` 在每次请求最外层按 Origin 绑一次（默认值是
   没有 Origin 时的退化形态）。做成动态变量是为了不用把 request 穿到每一个
   `json-response` 调用点上去。

   **方法表里必须有 `DELETE`**：`DELETE /threads/:id` 不是简单请求，浏览器
   先发预检，表里没有它整条删除线程的路由在浏览器侧根本走不通（服务端那段
   cond 永远等不到请求）。"
  {"Access-Control-Allow-Origin" "*"
   "Access-Control-Allow-Methods" "GET, POST, DELETE, OPTIONS"
   "Access-Control-Allow-Headers" "Content-Type, Last-Event-ID, X-CopilotKit-Runtime-Client-GQL-Version"
   "Access-Control-Max-Age" "86400"
   "Vary" "Origin"})

(defn- cors-for
  "按请求的 Origin 算 CORS 头。

   白名单内**回声 Origin 并给 `Allow-Credentials`**——`*` 与 credentials 互斥，
   前端一旦带上 cookie，回 `*` 的响应浏览器直接判死。名单外退回 `*`（本例不用
   credentials 时照常可用）。回声了 Origin 就得给 `Vary: Origin`，否则中间缓存
   会把 A 源的响应喂给 B 源。"
  [request]
  (let [origin (get-in request [:headers "origin"])]
    (if (contains? allowed-origins origin)
      (assoc cors-headers
             "Access-Control-Allow-Origin" origin
             "Access-Control-Allow-Credentials" "true")
      cors-headers)))


(def ^:private sse-headers
  {"Content-Type" "text/event-stream; charset=utf-8"
   "Cache-Control" "no-cache, no-transform"
   "Connection" "keep-alive"
   "X-Accel-Buffering" "no"})          ;; nginx 别缓冲，否则「流式」变「一次性」

(defn- sse-frame [{:keys [seq]} agui]
  ;; `id:` 放会话级 `:seq`——浏览器重连时原样回传为 `Last-Event-ID`，
  ;; 那正好就是 subscribe 的 `:since`。这是**偏移续传**，不是全量重放。
  (str "id: " seq "\ndata: " (json/generate-string agui) "\n\n"))

(defn- sse-response
  "把一个订阅接到 http-kit 的 channel 上。

   `on-event` 的契约是**立刻返回**——`hk/send!` 正好如此（往 channel 一塞就走）。
   连接关掉时退订；**不取消 run**：断线不该杀掉用户已经发起的那一轮
   （要停请显式调 /stop）。这正是 runtime 存在的理由。

   `close-on-terminal?` 区分两条路：
   - `/run` **要关**——客户端的 `run()` 得在终态处收口，不关它就一直吊着；
   - `/connect` **不关**——它是旁观/重连流，跨 run 连续（`:seq` 本来就是会话级的）。
     HITL 下一次对话由多个 run 组成，在第一个终态就关会把续跑截断（联调实测）。"
  [runtime conv-id since {:keys [close-on-terminal?]}]
  (fn [request]
    (hk/with-channel request channel
      (hk/send! channel {:status 200 :headers (merge cors-headers sse-headers)} false)
      (let [unsub (volatile! nil)
            emit (fn [ev]
                   (doseq [agui (codec/->agui-events ev)]
                     (hk/send! channel (sse-frame ev agui) false)
                     ;; **终态即关流**：AG-UI 的一条 run 以终态收口，流不关客户端就
                     ;; 一直吊着（联调实测：工具卡片永远停在 inProgress）。
                     ;; 续跑是新的 run，前端用 /connect 接着看。
                     (when (and close-on-terminal? (codec/terminal? agui))
                       (when-let [u @unsub] (u))
                       (hk/close channel))))]
        (vreset! unsub (rt/subscribe runtime conv-id
                                     {:since since
                                      :on-event emit
                                      :on-close (fn [_] (hk/close channel))}))
        (hk/on-close channel (fn [_] (when-let [u @unsub] (u))))))))

(defn- sse-frames
  "把**已经算好**的几条 AG-UI 事件当成一条 SSE 流发完就关。

   给「不是 run 的 run」用（MCP UI 的代理调用）：客户端那边仍走
   `HttpAgent`，所以形状还得是一条以终态收口的事件流。"
  [agui-events]
  (fn [request]
    (hk/with-channel request channel
      (hk/send! channel {:status 200 :headers (merge cors-headers sse-headers)} false)
      (doseq [[i ev] (map-indexed vector agui-events)]
        (hk/send! channel (sse-frame {:seq i} ev) false))
      (hk/close channel))))

(defn- last-event-id
  "浏览器 EventSource 自动重连时带的头；没有就退回请求体里的 since。"
  [request body]
  (some-> (or (get-in request [:headers "last-event-id"]) (:since body))
          str parse-long))

(defn- json-response [body]
  {:status 200
   :headers (merge cors-headers {"Content-Type" "application/json"})
   :body (json/generate-string body)})

;;; ============================================================
;;; 四条路由
;;; ============================================================

(defn- read-body [request]
  (some-> (:body request) slurp (json/parse-string true)))

(defn- decision-of
  "前端 `respond(...)` 回来的载荷 → resume 决策。

   审批场景下那个载荷是**决策**（\"approved\" / \"rejected\"），不是工具结果——
   这正是它与前端工具的分野：前端工具自己把活干了，回来的是**结果**；审批只是
   点头或摇头，活还在服务端等着干。"
  [content]
  (let [c (str/lower-case (str/trim (str content)))]
    (if (or (str/includes? c "approve") (str/includes? c "同意")
            (= c "true") (= c "yes"))
      "approved"
      "rejected")))

(defn- resume-decision
  "AG-UI `resume[]` 条目 → resume 决策。

   `status` 是协议的一等字段（`\"resolved\"` / `\"cancelled\"`），**先看它**：
   取消就是拒绝，没有歧义。`resolved` 才去看载荷——前端可能什么都不带
   （「点了同意」本身就是全部信息），也可能带 `{decision: ...}`（我们在
   `responseSchema` 里就是这么要的）。"
  [{:keys [status payload]}]
  (cond
    (= "cancelled" status)                    "rejected"
    (map? payload)                            (if-let [d (or (:decision payload)
                                                             (get payload "decision"))]
                                                (decision-of d)
                                                "approved")
    (some? payload)                           (decision-of payload)
    :else                                     "approved"))

(defn- payload-text
  "`resume[].payload` → 交给模型的文本。

   **优先拆 `result`**：我们自己在 `responseSchema` 里就是这么要的
   （`{\"result\": …}`，见 `codec` 的 `frontend-tool-response-schema`），客户端照
   schema 填回来的就是这个形状。不拆的话模型读到的是一层没有意义的包装。

   其余 map 走 JSON —— `(str m)` 会印成 Clojure 字面量（`{:result \"x\"}`），
   模型那边是没见过的方言。"
  [payload]
  (cond
    (nil? payload) nil
    (map? payload) (let [r (or (:result payload) (get payload "result"))]
                     (if (some? r) (str r) (json/generate-string payload)))
    :else (str payload)))

(defn- frontend-reply
  "前端工具那支的 resume 载荷 → 交给模型的**工具结果文本**。

   ⛔ 不能直接 `(str payload)`：取消时协议里 `payload` 本来就可以缺席，`str` 一下
   得到的是**空字符串**——那成了「这次调用的结果」，模型读到一个空结果反而宣布
   「已成功执行」，拒绝往成功的方向被吃掉（实测，见 feedbacks/
   2026-09-04-frontend-tool-pause-looks-like-an-approval.md）。

   所以 `status` 是一等字段，**先看它**（同 `resume-decision` 的口径）：取消就明说
   没执行，让模型知道这一步作废；有载荷才当结果。"
  [resume-entry tool-result]
  (let [text (payload-text (:payload resume-entry))
        content (:content tool-result)]
    (cond
      ;; **取消的框子不能丢**：载荷只是「为什么」，不是「结果」。丢了框子模型会
      ;; 把它当成执行结果；丢了载荷则丢掉用户给的理由——两个都要
      (= "cancelled" (:status resume-entry))
      (str "用户取消了这次调用，该工具**未执行**，没有结果。"
           (when text (str "用户说明：" text)))

      (some? text) text
      (some? content) (str content)
      ;; resolved 但什么都没带：客户端认为「执行完了、没有返回值」——如实说，
      ;; 别让模型把空串当成内容
      :else "该工具已在客户端执行完成，但没有返回任何结果。")))

(defn- matching-resume
  "本次请求里、针对**当前这条**暂停的 resume 条目。

   校验 `interruptId`：客户端可能带着一条过期的答复重放（换页签、重连、
   用户在旧卡片上点了按钮）。对不上就当没有——按新消息起 run，而不是拿旧决定
   去恢复一个已经不是它的暂停。"
  [resume pending]
  (when pending
    (let [id (codec/interrupt-id pending)]
      (first (filter #(= id (str (:interruptId %))) resume)))))

(defn handle-run
  "POST {base}/agent/:id/run

   两件值得注意的事：

   1. **历史取服务端权威**（设计文档 §7.3）：前端把它那份完整 messages 发上来，
      我们只取最后一条 user 消息。memory filter 在循环内落库，heal-dangling /
      暂停恢复 / timeline 都依赖服务端历史——认客户端历史等于同时推翻这四样。
   1.5 **审批答复走请求体的 `resume[]`**（interrupt 协议）：上一条 run 以
      `RUN_FINISHED outcome=interrupt` 收口，人点了按钮，客户端把
      `{interruptId, status, payload}` 放在**下一次 run 的请求体**里带回来。
      协议里没有审批端点，就是这一条，所以它排在最前面判。
      （tool-call 型 interrupt 被 resolve 时，客户端**同时**会补一条 tool
      消息；先认 `resume` 才不会把它误当成前端工具的结果。）
   2. **前端工具的结果是以「新的一轮」回来的**（AG-UI 的 client-side tool 就是
      这么设计的）。所以这里要认出「会话正在等 resume，且这次带的是那个工具的
      结果」，转成 `resume-run! :reply`，而不是又起一个新 run。
   3. **前端声明的工具可能与服务端工具重名**——那是 `useHumanInTheLoop` 的用法：
      前端注册一个同名工具只为了**渲染 + 审批**，活还是服务端那个工具去干。
      不剔除就会在 ChatClient 装配期撞「工具名唯一」校验，整个 run 500。
      剔除之后挂起的仍是**服务端**工具，于是它回来的载荷按**决策**处理
      （真的去执行那个工具），而不是当成工具结果。
   4. **客户端也可能永远不回结果**——CopilotKit 的 suggestions 就是这样：它把
      `copilotkitSuggest` 塞进 `tools`，只读模型发出的 `TOOL_CALL_ARGS` 来渲染
      建议按钮，从不回结果。会话于是停在那儿等，用户下一句话全被挡住
      （联调时当场卡死：输入框敲了字，发不出去）。所以新用户消息到达、而挂起的
      正是**这次请求带上来的前端工具**时，`:discard-pause? true` 丢掉它继续。"
  [runtime server-tool-names {:keys [input-transform mcp-proxy]} request]
  (let [body (read-body request)
        ;; **MCP UI 的代理调用不是一次 run**：前端那块 MCP App 界面（跑在 iframe
        ;; 里）要调 server 时，把请求塞在 `forwardedProps.__proxiedMCPRequest` 里
        ;; 走 `/run` 这条路发上来。这时候不能起 agent——照上游的做法，回一对
        ;; `RUN_STARTED` / `RUN_FINISHED{result}` 就完了。
        proxied (get-in body [:forwardedProps :__proxiedMCPRequest])
        {:keys [conversation-id message agui-tools resume context parent-run-id state]}
        (cond-> (codec/parse-run-input body)
          ;; 入站插件的挂点（对称于 `:event-transform`）：`agui.a2ui` 用它把
          ;; `forwardedProps.a2uiAction`（用户在生成出来的界面上点了什么）接进本轮输入
          input-transform (input-transform body))
        ;; 前端注册的本轮上下文（`useAgentContext`）→ 追加的 system 段。
        ;; **两条腿都要给**：起 run 与 resume 后的续跑是同一轮对话的两段，
        ;; 只给前者的话，审批点完之后模型就突然不知道自己在看哪个页面了。
        ctx-opts (cond-> nil
                   (codec/context->prompt context)
                   (assoc :extra-system-prompts [(codec/context->prompt context)])
                   ;; 客户端声明的父 run：原样回到 `RUN_STARTED.parentRunId`
                   parent-run-id (assoc :parent-run-id parent-run-id))
        pending (rt/awaiting runtime conversation-id)
        pending-name (get-in pending [:pending-tool :name])
        resume-entry (matching-resume resume pending)
        tool-result (when pending
                      (->> (:messages body)
                           (filter #(= "tool" (:role %)))
                           last))
        ;; 与服务端工具重名的前端声明 = `useHumanInTheLoop` 的「我要渲染并审批它」，
        ;; **不是**要新增一个工具。留着会撞装配期的工具名唯一校验。
        real-frontend-tools (remove #(contains? server-tool-names (str (:name %))) agui-tools)
        frontend-names (into #{} (map #(str (:name %))) real-frontend-tools)
        ;; 挂起的是本次带上来的前端工具、而客户端这次并没有回结果 → 它不会回了
        stale-frontend-pause? (and pending
                                   (nil? tool-result)
                                   (nil? resume-entry)
                                   (contains? frontend-names pending-name))
        result (cond
                 ;; 带了 resume 却对不上当前这条 interrupt（过期重放：换页签、重连、
                 ;; 在旧卡片上点了按钮）。**什么都不起**，下面回 409——落进 start-run!
                 ;; 会拿旧决定去恢复一个已经不是它的暂停。
                 (and (seq resume) (nil? resume-entry))
                 {:status :interrupt-mismatch}

                 ;; interrupt 协议的答复：`resume[]` 是一等字段，压过一切推断
                 (and resume-entry (contains? server-tool-names pending-name))
                 (rt/resume-run! runtime conversation-id
                                 (resume-decision resume-entry)
                                 (when (map? (:payload resume-entry)) (:payload resume-entry))
                                 ctx-opts)

                 ;; 前端工具被 resolve：载荷**即**结果（ask-user 语义）
                 resume-entry
                 (rt/resume-run! runtime conversation-id "reply"
                                 {:message (frontend-reply resume-entry tool-result)}
                                 ctx-opts)

                 ;; 审批：挂起的是**服务端**工具 → 载荷是决策，工具还要真去执行
                 (and pending tool-result (contains? server-tool-names pending-name))
                 (rt/resume-run! runtime conversation-id
                                 (decision-of (:content tool-result)) nil
                                 ctx-opts)

                 ;; 前端工具：载荷**即**工具结果（ask-user 语义）
                 (and pending tool-result)
                 (rt/resume-run! runtime conversation-id "reply"
                                 {:message (str (:content tool-result))}
                                 ctx-opts)

                 :else
                 (rt/start-run! runtime conversation-id message
                                (merge ctx-opts
                                       {:tools (mapv agui-tools/frontend-tool real-frontend-tools)
                                        :discard-pause? stale-frontend-pause?
                                        ;; 共享状态的基线：客户端那份原样交给
                                        ;; runtime，后续 delta 对着它算
                                        :state state})))]
    (cond
      (and proxied mcp-proxy)
      ((sse-frames [{:type "RUN_STARTED" :threadId conversation-id :runId (:runId body)}
                    {:type "RUN_FINISHED" :threadId conversation-id :runId (:runId body)
                     :result (mcp-proxy proxied)}])
       request)

      (= :busy (:status result))
      {:status 409 :headers cors-headers
       :body (json/generate-string {:error "thread_busy" :runId (:run-id result)})}

      ;; **明说**，别落进下面那个订阅——那会开一条永远不来事件的流（客户端吊在那儿）
      (= :interrupt-mismatch (:status result))
      {:status 409 :headers cors-headers
       :body (json/generate-string
              (if pending
                {:error "interrupt_mismatch" :interruptId (codec/interrupt-id pending)}
                {:error "no_pending_interrupt"}))}

      :else
      ;; **用返回的 `:since` 订阅，不是 nil**：run 已经起跑并发了 `RUN_STARTED`，
      ;; 这里才轮到我们订阅。传 nil = 只收新事件 = 把 `RUN_STARTED` 漏掉，
      ;; 前端第一件事就是 `AGUIError: First event must be 'RUN_STARTED'`。
      ((sse-response runtime conversation-id (:since result) {:close-on-terminal? true}) request))))

(defn- sse-detached
  "把一次**没有会话**的 run 接到 SSE 上（`/suggest` 用）。

   与 `sse-response` 的差别正是这条路的全部特点：不订阅会话（没有会话），
   事件从 run 自己的发射器直接来，终态即关流。"
  [start!]
  (fn [request]
    (hk/with-channel request channel
      (hk/send! channel {:status 200 :headers (merge cors-headers sse-headers)} false)
      (start! (fn [ev]
                (doseq [agui (codec/->agui-events ev)]
                  (hk/send! channel (sse-frame ev agui) false)
                  (when (codec/terminal? agui) (hk/close channel))))))))

(defn- ack-tool
  "客户端在建议 run 里声明的工具 → 内联工具。

   **不能用 `agui-tools/frontend-tool`**：那个会让 gate 把 run 暂停下来等前端
   回结果，而建议工具（`copilotkitSuggest`）**从来不回结果**——前端只读
   `TOOL_CALL_ARGS` 把建议渲染成按钮（§9.10 第 5 条那个会话卡死就是这么来的）。
   所以这里给一个空回执的 handler + **`:return-direct`**：工具结果即最终答案，
   不再回灌 LLM。少了它模型会为「ok」这个结果再跑一轮（实测多花了一整轮
   reasoning + 29 块正文 token），而那一轮的产出没有任何人读——建议的载体是
   `TOOL_CALL_ARGS`，不是模型的话。"
  [{:keys [name description parameters]}]
  (cond-> {:name (clojure.core/name name)
           :return-direct true
           :handler (fn [_args _ctx] "")}
    description (assoc :description description)
    parameters  (assoc :parameters parameters)))

(defn handle-suggest
  "POST {base}/agent/:id/suggest —— **无状态**建议 run（「猜用户下一句想说什么」）。

   这条路与 `/run` 的每一处不同都是刻意的（对齐 CopilotKit 的 `handle-suggest`）：

   | | `/run` | `/suggest` |
   |---|---|---|
   | 历史 | **服务端权威**，只取最后一条 user 消息 | **客户端权威**——没有服务端线程，它发上来的就是全部上下文 |
   | 会话 | 进注册表：缓冲、订阅、stop / resume | **不进**（`rt/run-detached!`）——一次性的建议不该留下一堆废会话 |
   | 落库 | memory filter 循环内落库 | 不落：临时 store，跑完就没了 |
   | 工具 | 服务端工具 + 前端工具（会暂停） | 只有客户端这次声明的，**都不执行**（见 `ack-tool`） |
   | 插件 | 挂（genui / a2ui …） | **不挂**：工具选择已被强制，插件注入的工具是白费；MCP 之类还会多打一次网络往返 |

   为什么值得单开一条：不实现它，前端就把 `copilotkitSuggest` 塞进 `/run` 的
   `tools` 里绕过去——那正是「输入框敲了字发不出去」的来源（§9.10 第 5 条）。"
  [agent-spec request]
  (let [body (read-body request)
        msgs (codec/agui->messages (:messages body))
        history (vec (butlast msgs))
        last-msg (last msgs)
        tools (mapv ack-tool (:tools body))
        store (memory/in-memory-store)]     ;; 临时的：跑完就没人引用了
    (when (seq history) (memory/mem-add store "suggest" history))
    (if (nil? last-msg)
      {:status 400 :headers cors-headers
       :body (json/generate-string {:error "no_messages"})}
      ((sse-detached
        (fn [on-event]
          (rt/run-detached!
           {:agent (agent/create-agent
                    (-> agent-spec
                        ;; 不留痕：临时 store、不给 pause-store、不要 on-pause
                        (dissoc :pause-store :on-pause)
                        (assoc :memory store
                               :conversation-id "suggest"
                               :tools tools)))
            :message (:content last-msg)
            :on-event on-event
            ;; 只有一个工具时，中立的 `:required` 就等于上游那句「强制调
            ;; copilotkitSuggest」。第二轮 LLM 由 `ack-tool` 的 `:return-direct`
            ;; 挡掉，不是靠 `:max-iterations`——后者只限制**工具轮**的次数，
            ;; 工具跑完那次「总结一下」的 LLM 调用照发（实测）
            ;; 建议 run 也带前端上下文——CopilotKit 的 suggestion-engine 同样
            ;; 把 `context` 发上来（`suggestion-engine.ts:270`），少了它建议就
            ;; 脱离了用户正在看的那一页
            :opts (cond-> {:tool-choice (if (seq tools) :required :auto)
                           :max-iterations 1}
                    (codec/context->prompt (:context body))
                    (assoc :extra-system-prompts
                           [(codec/context->prompt (:context body))]))})))
       request))))

(defn handle-connect
  "POST {base}/agent/:id/connect —— 只订阅，不起 run（重连 / 第二个页签旁观）。"
  [runtime request]
  (let [body (read-body request)
        conv-id (:threadId body)]
    ((sse-response runtime conv-id (last-event-id request body) {:close-on-terminal? false}) request)))

(defn handle-stop
  "POST {base}/agent/:id/stop/:threadId

   **threadId 在路径里，请求没有 body**（`agent.ts` 的 `stopPath`）。

   返回 true 只表示**取消已登记**：JVM 上没有抢占原语，正在跑的工具会跑完。
   UI 上该显示「正在停止…」，停稳的信号是事件流里的终态。"
  [runtime request thread-id]
  (json-response {:stopped (boolean (and thread-id (rt/stop! runtime thread-id)))}))

(defn handle-pending
  "GET {base}/pending —— 当前有哪些会话卡在等审批。

   也不属于 AG-UI。真做审批台的话这就是它的数据源：`rt/conversations` +
   `rt/awaiting` 两个调用而已。

   `interruptId` 与事件流里那条 interrupt 是同一个（`codec/interrupt-id`），
   审批台拿它就能凭 `/approve` 或走 `resume[]` 答复。"
  [runtime]
  (json-response
   {:pending (into []
                   (keep (fn [cid]
                           (when-let [aw (rt/awaiting runtime cid)]
                             {:threadId cid
                              :runId (:run-id aw)
                              :interruptId (codec/interrupt-id aw)
                              :reason (:reason aw)
                              :pendingTool (get-in aw [:pending-tool :name])})))
                   (rt/conversations runtime))}))

(defn handle-approve
  "POST {base}/agent/:id/approve  {threadId, decision, payload}

   **这条不属于 AG-UI**——协议里的答复路径是下一次 run 的 `resume[]`（见
   `handle-run`），聊天页上的审批按钮走那条就够了。留着这个端点是为了**带外
   审批**：审批台、Slack 按钮、运维脚本——它们手上只有 conversation-id，不发
   聊天消息，也不该被迫伪造一次 run。打完再 `/connect` 接着看续跑（新的 run）。"
  [runtime request]
  (let [{:keys [threadId decision payload]} (read-body request)
        result (rt/resume-run! runtime threadId (or decision "approved") payload)]
    (json-response {:status (name (:status result))
                    :runId (:run-id result)})))

(defn- iso-now [ms]
  (.toString (java.time.Instant/ofEpochMilli (long (or ms (System/currentTimeMillis))))))

(defn- thread-record
  "会话 → 线程记录（上游 `LocalThreadEndpointRecord` 的形状）。

   `organizationId` / `createdById` 是 Intelligence（云产品）的字段，本地模式下
   上游也只是填个占位——照填，少了客户端解析会缺字段。"
  [runtime meta-atom agent-id conv-id]
  (let [st (rt/run-status runtime conv-id)
        m (get @meta-atom conv-id)]
    {:id conv-id
     :name (:name m)                     ;; 没起过名就是 null，前端会自己显示摘要
     :agentId agent-id
     :organizationId "local"
     :createdById "local"
     :archived (boolean (:archived m))
     :createdAt (iso-now (or (:created-at m) (:last-active st)))
     :updatedAt (iso-now (:last-active st))}))

(defn- fold-state
  "缓冲里的状态事件折成**当前值**：最后一条快照 + 其后的所有 delta。

   ⚠️ 只取最后一条快照是不够的（这里原来就是那么写的）——有了 `STATE_DELTA`
   之后，「改完再读」会读到改之前的值：增量根本没算进去。实测过：加完第二条
   待办，读面还停在第一条。

   ⚠️ 缓冲是**有界**的（`rt/buffered-events`，缺省 512 条）。快照被挤出去之后
   这里只剩下一串 delta，折出来的就是残的 —— 与「事件缓冲不是真相店」这条既有
   性质同源（真相在客户端那份 state 里，我们只做尽力而为的只读面）。没有任何
   状态事件时返回 nil，与从前一致。"
  [events]
  (reduce (fn [st ev]
            (case (:type ev)
              :state/snapshot (:state ev)
              :state/delta    (agui-state/apply-ops st (:delta ev))
              st))
          nil
          events))

(defn handle-threads
  "`/threads*` —— **把已有的东西暴露出来**，不是新建一套存储：

   | 端点 | 数据从哪来 |
   |---|---|
   | `GET /threads` | runtime 的**会话注册表** |
   | `GET /threads/:id/messages` | **ChatMemory**（服务端权威的那份历史） |
   | `GET /threads/:id/events` | 会话的**环形缓冲**（有界、不落库，见 `rt/buffered-events`） |
   | `GET /threads/:id/state` | 缓冲里最后一条 `:state/snapshot` |
   | `POST /threads/clear` / `DELETE /threads/:id` | 摘会话 + 清 ChatMemory |
   | `POST /threads/:id`（改名）/ `…/archive` | 进程内的一小张元数据表 |

   **两条要如实说的边界**：会话空闲 30 分钟会被驱逐（`:idle-ttl-ms`），
   驱逐后它就不在列表里了——历史还在 ChatMemory，但我们的 `ChatMemory` 协议
   没有「列出所有会话」，所以列不出来；名字与归档标记只活在**进程内**，
   重启即失（真做要给 ChatMemory 加一张线程表，那是另一件事）。"
  [runtime memory meta-atom agent-id path request]
  (let [seg (fn [re] (some-> (re-find re path) second java.net.URLDecoder/decode))
        method (:request-method request)
        thread-id (or (seg #"/threads/([^/]+)/(?:messages|events|state|archive)$")
                      (seg #"/threads/([^/]+)$"))]
    (cond
      (re-find #"/threads/subscribe$" path)
      ;; 实时订阅是 Intelligence（云产品）的东西，我们没有——**如实 404**，
      ;; 客户端会降级成轮询，比假装成功强
      {:status 404 :headers cors-headers :body "threads/subscribe not supported"}

      (re-find #"/threads/clear$" path)
      (do (doseq [cid (rt/conversations runtime)]
            (rt/forget! runtime cid)
            (when memory (memory/mem-clear memory cid)))
          (reset! meta-atom {})
          {:status 204 :headers cors-headers})

      (re-find #"/threads/[^/]+/messages$" path)
      (json-response {:messages (codec/messages->thread-messages
                                 (if memory (memory/mem-get memory thread-id) []))})

      (re-find #"/threads/[^/]+/events$" path)
      (json-response {:events (codec/events->agui (rt/buffered-events runtime thread-id))})

      (re-find #"/threads/[^/]+/state$" path)
      (json-response {:state (fold-state (rt/buffered-events runtime thread-id))})

      (re-find #"/threads/[^/]+/archive$" path)
      (do (swap! meta-atom assoc-in [thread-id :archived] true)
          (json-response {:threadId thread-id :archived true}))

      (and thread-id (= :delete method))
      (do (rt/forget! runtime thread-id)
          (when memory (memory/mem-clear memory thread-id))
          (swap! meta-atom dissoc thread-id)
          (json-response {:threadId thread-id :deleted true}))

      (and thread-id (= :post method))
      (let [{:keys [name]} (read-body request)]
        (swap! meta-atom assoc-in [thread-id :name] name)
        (json-response (thread-record runtime meta-atom agent-id thread-id)))

      :else
      (json-response {:threads (mapv #(thread-record runtime meta-atom agent-id %)
                                     (rt/conversations runtime))
                      :nextCursor nil}))))

(defn handler
  [runtime agent-ids base-path server-tool-names & [opts]]
  (fn [request]
    (binding [cors-headers (cors-for request)]
     (let [path (subs (:uri request) (min (count base-path) (count (:uri request))))]
       (cond
         (= :options (:request-method request))
         {:status 204 :headers cors-headers}

         (re-find #"/info$" path)    (json-response (codec/run-info agent-ids opts))
         (re-find #"/run$" path)     (handle-run runtime server-tool-names opts request)
         (re-find #"/suggest$" path) (if-let [spec (:agent-spec opts)]
                                       (handle-suggest spec request)
                                       {:status 404 :headers cors-headers
                                        :body "suggest not enabled"})
         (re-find #"/connect$" path) (handle-connect runtime request)
         ;; 不属于 AG-UI：应用自己的审批端点（见 handle-approve）
         (re-find #"/approve$" path) (handle-approve runtime request)
         (re-find #"/pending$" path)  (handle-pending runtime)
         ;; 线程只读面：把已有的东西暴露出来（见 handle-threads）
         (re-find #"/threads" path)
         (if-let [meta-atom (:thread-meta opts)]
           (handle-threads runtime (:memory opts) meta-atom (first agent-ids) path request)
           {:status 404 :headers cors-headers :body "threads not enabled"})
         ;; threadId 是路径段：/agent/:id/stop/:threadId
         (re-find #"/stop(/|$)" path)
         (handle-stop runtime request
                      (some-> (re-find #"/stop/([^/]+)$" path) second
                              java.net.URLDecoder/decode))
         :else {:status 404 :headers cors-headers :body "not found"})))))

;;; ============================================================
;;; 启动
;;; ============================================================

(defn start!
  "`agent-spec` 就是 `create-agent` 那份配置（provider / tools / memory /
   pause-store …）。**memory 与 pause-store 要跨 run 共享**，所以建在外面。

   agent id 用 `\"default\"`：CopilotKit 前端不指定 agent 时就找这个名字
   （`@copilotkit/shared` 的 `DEFAULT_AGENT_ID`）。"
  ([port agent-spec] (start! port agent-spec "/api/copilotkit" nil))
  ([port agent-spec base-path] (start! port agent-spec base-path nil))
  ([port agent-spec base-path {:keys [event-transform input-transform open-generative-ui?
                                      a2ui? suggestions? mcp-proxy threads? multimodal
                                      parallel-tools? max-iterations state-tools?]}]
   (let [runtime (rt/runtime (cond-> {:agent-fn (agui-tools/agent-fn agent-spec)
                                      ;; 聊天 UX：用户又发一条就顶掉上一条（旧 run 落 cancelled）
                                      :on-concurrent :supersede
                                      ;; 模型写共享状态的两把工具（见 rt/runtime）
                                      :state-tools? (boolean state-tools?)}
                               ;; 事件流插件（如 agui.a2ui / copilotkit.genui）；不传就完全不存在
                               event-transform (assoc :event-transform event-transform)))
         ;; 服务端工具名：用来把「前端同名声明」认成**渲染意图**而不是新工具
         server-tool-names (into #{}
                                 (comp (filter var?)
                                       (map #(:name (tool/get-schema %))))
                                 (:tools agent-spec))]
     {:server (hk/run-server (handler runtime ["default"] base-path server-tool-names
                                      {:open-generative-ui? open-generative-ui?
                                       ;; `/info` 的线程档能力位——挂了 `/threads`
                                       ;; 才报（见 `codec/run-info` 的 :threadEndpoints）
                                       :threads? (boolean threads?)
                                       ;; 多模态能力位：**装配方说了算**，库不猜
                                       ;; （见 `codec/run-info` 的 :multimodal）
                                       :multimodal multimodal
                                       ;; 同上：这两格也只有装配方知道
                                       ;; （工具引擎是不是并行的、循环上限是多少）
                                       :parallel-tools? parallel-tools?
                                       :max-iterations max-iterations
                                       ;; 装了写状态的工具才报 state 能力位——
                                       ;; 读面一直都在，但没有写的那一半，报 true
                                       ;; 就是谎报（模型根本写不动）
                                       :state? (boolean state-tools?)
                                       ;; 这两位都是 `/info` 的能力位：前端据此才注册
                                       ;; 对应的 renderer（`codec/run-info` 的注释）
                                       :a2ui? a2ui?
                                       :input-transform input-transform
                                       :mcp-proxy mcp-proxy
                                       ;; 线程只读面：历史在 ChatMemory，名字/归档
                                       ;; 标记在这张**进程内**的小表里
                                       :thread-meta (when threads? (atom {}))
                                       :memory (:memory agent-spec)
                                       :suggestions? suggestions?
                                       ;; `/suggest` 要现建一次性 agent，所以整份配置得留着
                                       :agent-spec (when suggestions? agent-spec)})
                             {:port port})
      :runtime runtime})))

(defn stop! [{:keys [server runtime]}]
  (rt/shutdown! runtime)
  (server))
