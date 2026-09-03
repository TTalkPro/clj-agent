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
            [im.ttalk.agent.agui.tools :as agui-tools]
            [im.ttalk.agent.tool :as tool]
            [org.httpkit.server :as hk]))

;;; ============================================================
;;; SSE
;;; ============================================================

(def ^:private cors-headers
  "浏览器在 :3000，runtime 在 :4002 —— 跨源。`credentials` 我们不用，所以
   `*` 就够；真部署换成你的域名白名单。"
  {"Access-Control-Allow-Origin" "*"
   "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
   "Access-Control-Allow-Headers" "Content-Type, Last-Event-ID, X-CopilotKit-Runtime-Client-GQL-Version"
   "Access-Control-Max-Age" "86400"})


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

(defn handle-run
  "POST {base}/agent/:id/run

   两件值得注意的事：

   1. **历史取服务端权威**（设计文档 §7.3）：前端把它那份完整 messages 发上来，
      我们只取最后一条 user 消息。memory filter 在循环内落库，heal-dangling /
      暂停恢复 / timeline 都依赖服务端历史——认客户端历史等于同时推翻这四样。
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
  [runtime server-tool-names request]
  (let [body (read-body request)
        {:keys [conversation-id message agui-tools]} (codec/parse-run-input body)
        pending (rt/awaiting runtime conversation-id)
        pending-name (get-in pending [:pending-tool :name])
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
                                   (contains? frontend-names pending-name))
        result (cond
                 ;; 审批：挂起的是**服务端**工具 → 载荷是决策，工具还要真去执行
                 (and pending tool-result (contains? server-tool-names pending-name))
                 (rt/resume-run! runtime conversation-id
                                 (decision-of (:content tool-result)) nil)

                 ;; 前端工具：载荷**即**工具结果（ask-user 语义）
                 (and pending tool-result)
                 (rt/resume-run! runtime conversation-id "reply"
                                 {:message (str (:content tool-result))})

                 :else
                 (rt/start-run! runtime conversation-id message
                                {:tools (mapv agui-tools/frontend-tool real-frontend-tools)
                                 :discard-pause? stale-frontend-pause?}))]
    (if (= :busy (:status result))
      {:status 409 :headers cors-headers
       :body (json/generate-string {:error "thread_busy" :runId (:run-id result)})}
      ;; **用返回的 `:since` 订阅，不是 nil**：run 已经起跑并发了 `RUN_STARTED`，
      ;; 这里才轮到我们订阅。传 nil = 只收新事件 = 把 `RUN_STARTED` 漏掉，
      ;; 前端第一件事就是 `AGUIError: First event must be 'RUN_STARTED'`。
      ((sse-response runtime conversation-id (:since result) {:close-on-terminal? true}) request))))

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
   `rt/awaiting` 两个调用而已。"
  [runtime]
  (json-response
   {:pending (into []
                   (keep (fn [cid]
                           (when-let [aw (rt/awaiting runtime cid)]
                             {:threadId cid
                              :runId (:run-id aw)
                              :reason (:reason aw)
                              :pendingTool (get-in aw [:pending-tool :name])})))
                   (rt/conversations runtime))}))

(defn handle-approve
  "POST {base}/agent/:id/approve  {threadId, decision, payload}

   **这条不属于 AG-UI**——人工审批是**你的应用**的事，协议里没有它。放在这里是
   为了把 HITL 跑完整：前端收到 `CUSTOM/cljagent.run.paused` 后渲染审批条，
   点「同意」打这个端点，再 `/connect` 接着看续跑（续跑是新的 run）。"
  [runtime request]
  (let [{:keys [threadId decision payload]} (read-body request)
        result (rt/resume-run! runtime threadId (or decision "approved") payload)]
    (json-response {:status (name (:status result))
                    :runId (:run-id result)})))

(defn handler
  [runtime agent-ids base-path server-tool-names]
  (fn [request]
    (let [path (subs (:uri request) (min (count base-path) (count (:uri request))))]
      (cond
        (= :options (:request-method request))
        {:status 204 :headers cors-headers}

        (re-find #"/info$" path)    (json-response (codec/run-info agent-ids))
        (re-find #"/run$" path)     (handle-run runtime server-tool-names request)
        (re-find #"/connect$" path) (handle-connect runtime request)
        ;; 不属于 AG-UI：应用自己的审批端点（见 handle-approve）
        (re-find #"/approve$" path) (handle-approve runtime request)
        (re-find #"/pending$" path)  (handle-pending runtime)
        ;; threadId 是路径段：/agent/:id/stop/:threadId
        (re-find #"/stop(/|$)" path)
        (handle-stop runtime request
                     (some-> (re-find #"/stop/([^/]+)$" path) second
                             java.net.URLDecoder/decode))
        :else {:status 404 :headers cors-headers :body "not found"}))))

;;; ============================================================
;;; 启动
;;; ============================================================

(defn start!
  "`agent-spec` 就是 `create-agent` 那份配置（provider / tools / memory /
   pause-store …）。**memory 与 pause-store 要跨 run 共享**，所以建在外面。

   agent id 用 `\"default\"`：CopilotKit 前端不指定 agent 时就找这个名字
   （`@copilotkit/shared` 的 `DEFAULT_AGENT_ID`）。"
  ([port agent-spec] (start! port agent-spec "/api/copilotkit"))
  ([port agent-spec base-path]
   (let [runtime (rt/runtime {:agent-fn (agui-tools/agent-fn agent-spec)
                              ;; 聊天 UX：用户又发一条就顶掉上一条（旧 run 落 cancelled）
                              :on-concurrent :supersede})
         ;; 服务端工具名：用来把「前端同名声明」认成**渲染意图**而不是新工具
         server-tool-names (into #{}
                                 (comp (filter var?)
                                       (map #(:name (tool/get-schema %))))
                                 (:tools agent-spec))]
     {:server (hk/run-server (handler runtime ["default"] base-path server-tool-names)
                             {:port port})
      :runtime runtime})))

(defn stop! [{:keys [server runtime]}]
  (rt/shutdown! runtime)
  (server))
