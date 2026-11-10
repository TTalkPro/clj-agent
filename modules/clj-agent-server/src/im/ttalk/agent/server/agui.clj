(ns im.ttalk.agent.server.agui
  "AG-UI 的 HTTP 端（C# Keelson.Agui.Server 的 AguiEndpoints；keel agui/http.ss + server-http.ss）：CopilotKit runtime 顶替那一层
   要的几条路，挂在任意前缀下。**全是 Ring**：`routes` 是 clj-net-server 的路由表（`handler` 直接给一个 Ring 处理器），
   SSE 是流式正文（StreamableResponseBody）—— 不认识 Jetty；clj-net-server 的宿主（Jetty 12）或任何 Ring 服务端都能挂。
   run / 注册表 / 编解码 / 线程存储用上游 clj-agent-agui —— C# 的 Keelson.Agui（AguiRegistry、AguiRun、RunAgentInput …）
   由它顶替，不重移植。

   ```
   GET  {prefix}/info                         名录与能力位
   POST {prefix}/agent/:id/run                纯 AG-UI 的 SSE
   POST {prefix}/agent/:id/connect            只订阅、不起 run：重放在飞 / 刚跑完那一轮，跟到底；没有 ⇒ 200 空流
   POST {prefix}/agent/:id/suggest            「猜你想问」，无状态（条目 :suggest? + :agent-spec 才有，不然 404）
   POST {prefix}/agent/:id/stop/:threadId     按停（那条 RUN_ERROR code=STOPPED 在响应体里，⛔ 不发进流）
   POST {prefix}                              单路由退路：{\"method\": …, \"params\": {…}, \"body\": …}
   OPTIONS 任意                               预检（永远 204；真正的闸在真请求那一次）

   —— 下面七条只在给了 :thread-store 时存在 ——
   GET    {prefix}/threads                    {\"threads\":[…],\"nextCursor\":…}（查询串原样交给存储）
   GET    {prefix}/threads/:id/messages       {\"messages\":[…]}
   GET    {prefix}/threads/:id/events         {\"events\":[…]}
   GET    {prefix}/threads/:id/state          {\"state\":…}
   PATCH  {prefix}/threads/:id                那条线程记录（⛔ 不套信封）
   POST   {prefix}/threads/:id/archive        {\"threadId\":…,\"archived\":true}
   DELETE {prefix}/threads/:id                {\"threadId\":…,\"deleted\":true}（⚠️ 带 JSON 体）
   ```
   认不出的路径：任何方法都 404 `not_found`（⛔ 不是 405）。CORS 缺省**拒**（见 im.ttalk.agent.server.agui.http）。
   线程那几条：存储缺那个槽 ⇒ 501 `not_implemented`；没这条线程 ⇒ 404 `thread_not_found`；方法不对 ⇒ 405 带 `Allow`；
   改动类的体不是对象 / 缺 agentId ⇒ 400；存储抛了 ⇒ 500 `thread_store_error`。…/threads/subscribe 与 …/threads/clear 照旧 404。

   ```clojure
   (def h (host/host reg {:version \"1.0.0\" :origin-allowed (ahttp/origin-allow \"http://localhost:3000\")}))
   (def s (net/start (agui/routes \"/api/copilotkit\" h) {:port 3000}))   ; im.ttalk.agent.server.jetty（Jetty）
   ;; 或别的 Ring 服务端：(agui/handler \"/api/copilotkit\" h)
   ```
   `routes` 返回 clj-net-server 的路由表，元数据里带停机钩子（停机时按停所有在飞的 run，原因 :shutdown）。

   注册表条目（clj-agent-agui `registry/registry`）在 :id :runtime :description :suggest? :stop 之外还认：
     :agent-spec          `/suggest` 要现建一次性 agent 的那份 create-agent 配置（无状态：临时 memory、不进注册表）
     :memory              这台的 ChatMemory（线程存储的适配器读 /threads/:id/messages）
     :server-tool-names   服务端工具名（前端同名声明 = 渲染 / 审批意图，见 im.ttalk.agent.server.agui.resume）
     :input-transform     入站插件：(fn [body] 输入)，把 RunAgentInput 解析结果改一笔再起跑（如 clj-agent-agui 的 `a2ui/input-transform`）
     :mcp-proxy           MCP Apps 的前端代理：(fn [proxied-request] result)。`/run` 的 `forwardedProps.__proxiedMCPRequest`
                          不是一次 run（前端 iframe 里的 MCP App 要调 server）——不起 agent，回一对 RUN_STARTED /
                          RUN_FINISHED{result}（上游 CopilotKit 的做法；如 clj-agent-agui 的 `mcp/proxy-request`）
   这两格也可以放在 host 选项里当缺省值，条目里给了就以条目为准。

   **与 C# 的差别**（详见 tasks/J-03.md）：被按停的 run 在流里以 `RUN_FINISHED result.status=cancelled`（带 reason）收口
   ——clj-agent-agui 的契约是「恰好一个终态」，C# 是什么都不再发；`/connect` 的 lastSeq 续读（IAguiThreadEventStore /
   序号记录器）不做；对端断开要靠心跳的那一次写才发现（Ring 的流式正文 / Jetty 的阻塞输出不报告空闲时的断连）。"
  (:require [cheshire.core :as json]
            [im.ttalk.agent.agui.codec :as codec]
            [im.ttalk.agent.agui.registry :as registry]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.simple-agent :as agent]
            [im.ttalk.agent.server.agui.host :as host]
            [im.ttalk.agent.server.agui.http :as ahttp]
            [im.ttalk.agent.server.agui.input :as input]
            [im.ttalk.agent.server.agui.replay :as replay]
            [im.ttalk.agent.server.agui.resume :as resume]
            [im.ttalk.agent.server.strings :refer [msg]]
            [im.ttalk.agent.server.agui.thread-store :as store]
            [im.ttalk.agent.server.strings :as err]
            [im.ttalk.agent.server.http :as nh]
            [im.ttalk.agent.server.routing :as routing]
            [im.ttalk.agent.server.sse :as sse])
  (:import (java.util.concurrent CompletableFuture LinkedBlockingQueue TimeUnit TimeoutException)))

(set! *warn-on-reflection* true)

;; ── 出口 ─────────────────────────────────────────────

(defn- error [status code message] (ahttp/error-response status code message))

(defn- endpoints-line [h]
  (msg :agui-server/endpoints
       (if (some registry/supports-suggest? (registry/agents (:registry h)))
         (:text (msg :agui-server/endpoints-suggest))
         "")))

(defn info
  "`/info` 的正文：clj-agent-agui 的 `registry/info`（codec/run-info）+ C# 的口径：threadEndpoints 按存储的槽逐格报
   （inspect 拿 messages 当代表），agents 按注册顺序。"
  [h]
  (let [o (:options h)
        st (:thread-store h)
        reg (:registry h)
        body (registry/info reg (merge {:approve-with-edits? true}
                                       (:info o)
                                       {:version (:version o)
                                        :a2ui? (:a2ui? o)
                                        :open-generative-ui? (:open-generative-ui? o)
                                        :thread-endpoints {:list (store/has-slot? st :list)
                                                           :inspect (store/has-slot? st :messages)
                                                           :mutations (store/mutations? st)}}))]
    (update body :agents (fn [m] (apply array-map (mapcat (fn [id] [id (get m id)]) (registry/agent-ids reg)))))))

(defn- sse-response
  "SSE 响应（Ring map，正文流式）；`producer` 在服务端写正文的那条线程上跑。"
  [h req producer]
  (let [o (:options h)]
    (sse/response req {:heartbeat-ms (:heartbeat-ms o) :heartbeat-frame (:heartbeat-frame o)} producer)))

(defn- pump-queue!
  "把队列抄到 SSE 上，直到收场 / 写不出去 / 停机。"
  [s poll]
  (loop []
    (let [f (poll)]
      (cond (= host/end f) nil
            (nil? f) (when-not (sse/gone? s) (recur))
            :else (when (sse/write-frame! s f) (recur))))))

;; ── 读体 ─────────────────────────────────────────────

(defn- read-text
  "读请求体：[:ok 串] / [:too-large ex]。"
  [h req]
  (try [:ok (nh/read-body-string req (get-in h [:options :max-body-bytes]))]
       (catch clojure.lang.ExceptionInfo e
         (if (= "server/body-too-large" (err/code e)) [:too-large e] (throw e)))))

(defn- read-input
  "RunAgentInput ⇒ [:ok 输入] / [:resp 400 或 413 的响应]。⚠️ 在开 SSE 之前答（开了流状态码就已经是 200）。"
  [h req body]
  (let [[tag v] (if body [:ok body] (read-text h req))]
    (if (= :too-large tag)
      [:resp (error 413 "body_too_large" (ex-message v))]
      (try [:ok (input/decode v)]
           (catch clojure.lang.ExceptionInfo e
             (if-let [wc (input/wire-code e)]
               [:resp (error 400 wc (ex-message e))]
               (throw e)))))))

(defn- find-post
  "POST 才收（405）；找 agent（404 agent_not_found）⇒ [:ok 条目] / [:resp 响应]。"
  [h req id]
  (cond
    (not= :post (:request-method req)) [:resp (error 405 "method_not_allowed" (msg :agui-server/post-only))]
    :else (if-let [e (registry/find-agent (:registry h) id)]
            [:ok e]
            [:resp (error 404 "agent_not_found" (msg :agui-server/agent-not-found id))])))

(defmacro ^:private let-ok
  "`(let-ok [x (step …) y (step …)] body)`：每一步回 [:ok v] 就接着走，回 [:resp r] 就以 r 作答。"
  [bindings & body]
  (if (empty? bindings)
    `(do ~@body)
    (let [[sym expr & more] bindings]
      `(let [[tag# v#] ~expr]
         (if (= :ok tag#)
           (let [~sym v#] (let-ok [~@more] ~@body))
           v#)))))

;; ── /agent/:id/run ───────────────────────────────────

(defn- proxied-mcp-response
  "MCP Apps 前端代理调用：⛔ 不起 run，回一对 RUN_STARTED / RUN_FINISHED{result}。"
  [h req in mcp-proxy proxied]
  (let [tid (:threadId in) rid (:runId in)
        result (try (mcp-proxy proxied) (catch Throwable t {:error (or (ex-message t) (str t))}))]
    (sse-response h req (fn [s]
                          (sse/write-frames! s [(host/frame-of {:type "RUN_STARTED" :threadId tid :runId rid})
                                                (host/frame-of {:type "RUN_FINISHED" :threadId tid :runId rid
                                                                :result result})])))))

(defn- run* [h req id body]
  (let-ok [entry (find-post h req id)
           in (read-input h req body)]
    (let [tid (:threadId in)
          ;; 入站插件 / MCP 代理：条目里给了以条目为准，否则取 host 选项里的缺省
          entry (merge (select-keys (:options h) [:input-transform :mcp-proxy]) entry)
          proxied (get-in in [:forwardedProps :__proxiedMCPRequest])]
      (if (and proxied (:mcp-proxy entry))
        (proxied-mcp-response h req in (:mcp-proxy entry) proxied)
      (let [result (resume/start! entry in)]
      (case (:status result)
        :started
        (let [d (host/start-drive! h {:entry entry :thread-id tid :run-id (:run-id result)
                                      :client-run-id (:runId in) :since (:since result) :input in})]
          (host/subscribe! d)
          (sse-response h req (fn [s]
                                (try (pump-queue! s #(host/poll-direct d 200))
                                     ;; ⚠️ 断连 = 退订，run 照跑（按停走 /stop）
                                     (finally (host/unsubscribe! h d true))))))

        :busy (error 409 "thread_running" (msg :agui-server/thread-running tid))
        :resume-error (let [[status code extra] (:error result)]
                        (ahttp/json-response status (assoc extra :error code)))
        :not-paused (error 409 "no_pending_interrupt" (msg :agui-server/no-pending-interrupt tid))
        (error 409 "thread_awaiting_resume" (msg :agui-server/thread-awaiting-resume tid))))))))

;; ── /agent/:id/connect ───────────────────────────────

(defn- await-change
  "等「有新帧 / 收场」，最多 200 ms（调用方每圈都看一眼流断没断；⛔ 不用 anyOf 挂在流的 aborted 上 —— 一圈挂一个，攒到断连才放）。"
  [^CompletableFuture change]
  (try (.get change 200 TimeUnit/MILLISECONDS)
       (catch TimeoutException _ nil)))

(defn- connect* [h req id body]
  (let-ok [_ (find-post h req id)
           in (read-input h req body)]
    (let [tid (:threadId in)
          rp (:replay h)
          cursor (replay/open-cursor rp tid (host/now-ms h))
          d (when cursor (host/drive-of h tid))]
      (when d (host/subscribe! d))
      (sse-response
       h req
       (fn [s]
         ;; 没有可重放的 ⇒ 200 空流立即结束（⛔ 不是 404，上游行为）
         (when cursor
           (try
             (loop []
               (when-not (sse/gone? s)
                 (let [change (if d (host/next-change d) (CompletableFuture/completedFuture true))
                       frames (replay/take! rp cursor)]
                   (when (or (empty? frames) (sse/write-frames! s frames))
                     (when-not (or (replay/done? rp cursor) (nil? d))
                       (await-change change)
                       (recur))))))
             (finally (when d (host/unsubscribe! h d false))))))))))

;; ── /agent/:id/suggest：无状态 —— 不进注册表、不进帧环 ──

(defn- ack-tool
  "建议 run 里客户端声明的工具 ⇒ 空回执的内联工具 + :return-direct（⛔ 不用前端工具：建议工具从来不回结果，会把会话卡死）。"
  [{:keys [name description parameters]}]
  (cond-> {:name (clojure.core/name name) :return-direct true :handler (fn [_args _ctx] "")}
    description (assoc :description description)
    parameters (assoc :parameters parameters)))

(defn- suggest* [h req id body]
  (let [e (registry/find-agent (:registry h) id)]
    (if-not (and e (registry/supports-suggest? e) (:agent-spec e))
      (error 404 "not_found" (endpoints-line h))
      (let-ok [entry (find-post h req id)
               in (read-input h req body)]
        (let [msgs (codec/agui->messages (:messages in))
              history (vec (butlast msgs))
              last-msg (last msgs)
              tools (mapv ack-tool (:tools in))
              mem (memory/in-memory-store)]
          (if (nil? last-msg)
            (error 400 "no_messages" (msg :agui-server/no-messages))
            (let [q (LinkedBlockingQueue.)
                  _ (when (seq history) (memory/mem-add mem "suggest" history))
                  ctx (codec/context->prompt (:context in))
                  done (rt/run-detached!
                        {:agent (agent/create-agent (-> (:agent-spec entry)
                                                        (dissoc :pause-store :on-pause)
                                                        (assoc :memory mem :conversation-id "suggest" :tools tools)))
                         :message (:content last-msg)
                         :run-id (:runId in)
                         :on-event (fn [ev]
                                     (doseq [a (codec/->agui-events ev)
                                             :let [a (cond-> a (contains? a :threadId) (assoc :threadId (:threadId in)))]]
                                       (.offer q (host/frame-of a))
                                       (when (codec/terminal? a) (.offer q host/end))))
                         :opts (cond-> {:tool-choice (if (seq tools) :required :auto) :max-iterations 1}
                                 ctx (assoc :extra-system-prompts [ctx]))})]
              (host/track-suggest! h done)
              (sse-response h req (fn [s] (pump-queue! s #(.poll q 200 TimeUnit/MILLISECONDS)))))))))))

;; ── /agent/:id/stop/:threadId ────────────────────────

(defn- stop* [h req id tid]
  (let-ok [entry (find-post h req id)]
    ;; 先问 agent（条目的 :stop；缺省 = rt/stop!，原因 :stop）；响应体形状不变
    (let [running? (= :running (:state (rt/run-status (:runtime entry) tid)))
          r (try {:ok (registry/stop! (:registry h) id tid)} (catch Throwable e {:error e}))]
      (cond
        (:error r) (error 500 "stop_failed" (msg :agui-server/stop-failed id (ex-message (:error r))))
        (not (:ok r)) (ahttp/json-response 200 {:stopped false
                                                :message (:text (if running?
                                                                  (msg :agui-server/stop-declined id tid)
                                                                  (msg :agui-server/no-running-run tid)))})
        :else (ahttp/json-response 200 {:stopped true
                                        :interrupt {:type "RUN_ERROR" :message "Run stopped by user" :code "STOPPED"}})))))

;; ── 线程存储那七条 ───────────────────────────────────

(defn- not-allowed [allow]
  ;; 405 要带 Allow（上游每一条都带）
  (ahttp/error-response 405 {"Allow" allow} "method_not_allowed" (msg :agui-server/method-not-allowed allow)))

(defn- not-implemented [slot]
  ;; 501 不是随手挑的：上游 Inspector 对 /events 与 /state 的 501 有专门一条路 —— 画成「这一栏没有」
  (error 501 "not_implemented" (msg :agui-server/thread-slot-not-implemented (store/slot-names slot))))

(defn- no-thread [tid] (error 404 "thread_not_found" (msg :agui-server/thread-not-found tid)))

(defn- try-store
  "调存储：[:ok 值]；抛了 ⇒ [:resp 500]（带着它的话，⛔ 不吞）。"
  [f]
  (try [:ok (f)]
       (catch Throwable e
         [:resp (error 500 "thread_store_error" (msg :agui-server/thread-store-error (or (ex-message e) (str e))))])))

(defn- query-first [req] (into {} (map (fn [[k vs]] [k (or (first vs) "")])) (nh/query req)))

(defn- thread-list [h req]
  (let [st (:thread-store h)]
    (cond
      (not= :get (:request-method req)) (not-allowed "GET")
      (not (store/has-slot? st :list)) (not-implemented :list)
      :else
      (let-ok [r (try-store #((:list st) (query-first req)))]
        (let [threads (when (map? r) (or (get r :threads) (get r "threads")))]
          (cond
            ;; 数组 ⇒ 补成上游本地退路那份信封（nextCursor: null）
            (sequential? r) (ahttp/json-response 200 {:threads r :nextCursor nil})
            ;; 带 threads 的对象 ⇒ 原样出（缺 nextCursor 补 null）；⛔ 不补 joinCode
            (sequential? threads) (ahttp/json-response 200 (if (or (contains? r :nextCursor) (contains? r "nextCursor"))
                                                             r
                                                             (assoc r :nextCursor nil)))
            :else (error 500 "thread_store_bad_shape" (msg :agui-server/thread-store-list-shape))))))))

(defn- mutation-body
  "改动类的体：JSON 对象且有非空 agentId ⇒ [:ok [agent-id updates]]（去掉 agentId / userId）；否则 [:resp 400 / 413]。"
  [h req]
  (let [[tag v] (read-text h req)]
    (if (= :too-large tag)
      [:resp (error 413 "body_too_large" (ex-message v))]
      (let [o (input/parse-json v)]
        (cond
          (not (map? o)) [:resp (error 400 "bad_json" (msg :agui-server/thread-mutation-bad-json))]
          (not (and (string? (:agentId o)) (seq (:agentId o))))
          [:resp (error 400 "bad_request" (msg :agui-server/thread-mutation-no-agent))]
          :else [:ok [(:agentId o) (dissoc o :agentId :userId)]])))))

(defn- thread-array [h req tid slot k]
  (let [st (:thread-store h)]
    (cond
      (not= :get (:request-method req)) (not-allowed "GET")
      (not (store/has-slot? st slot)) (not-implemented slot)
      :else (let-ok [a (try-store #((get st slot) tid))]
              (if (nil? a) (no-thread tid) (ahttp/json-response 200 {k a}))))))

(defn- flag [tid k found?]
  (if found? (ahttp/json-response 200 {:threadId tid k true}) (no-thread tid)))

(defn- thread-op [h req op]
  (let [st (:thread-store h)
        tid (nh/param req :threadId)
        m (:request-method req)]
    (case op
      "messages" (thread-array h req tid :messages :messages)
      "events" (thread-array h req tid :events :events)
      "state" (cond
                (not= :get m) (not-allowed "GET")
                (not (store/has-slot? st :state)) (not-implemented :state)
                :else (let-ok [r (try-store #((:state st) tid))]
                        ;; ⚠️ 没这条线程（404）与有线程、没状态（{"state":null}）不是一回事
                        (if (nil? r) (no-thread tid) (ahttp/json-response 200 {:state (:state r)}))))
      ;; archive —— 走的也是 update，只是 updates 写死
      "archive" (cond
                  (not= :post m) (not-allowed "POST")
                  (not (store/has-slot? st :update)) (not-implemented :update)
                  :else (let-ok [[agent-id] (mutation-body h req)
                                 rec (try-store #((:update st) tid agent-id {:archived true}))]
                          (flag tid :archived (some? rec)))))))

(defn- thread [h req]
  (let [st (:thread-store h)
        tid (nh/param req :threadId)]
    (cond
      ;; subscribe / clear 不当作 :threadId（上游逐字如此），这两条路本身也不路由
      (contains? #{"subscribe" "clear"} tid) (error 404 "not_found" (endpoints-line h))

      (= :patch (:request-method req))
      (if-not (store/has-slot? st :update)
        (not-implemented :update)
        (let-ok [[agent-id updates] (mutation-body h req)
                 rec (try-store #((:update st) tid agent-id updates))]
          ;; ⭐ 回的是裸的线程记录（⛔ 不套信封）
          (if (nil? rec) (no-thread tid) (ahttp/json-response 200 rec))))

      (= :delete (:request-method req))
      (if-not (store/has-slot? st :delete)
        (not-implemented :delete)
        (let-ok [[agent-id] (mutation-body h req)
                 deleted (try-store #((:delete st) tid agent-id))]
          (flag tid :deleted (boolean deleted))))

      :else (not-allowed "PATCH, DELETE"))))

;; ── 单路由退路 ───────────────────────────────────────

(defn- single [h req]
  (if (not= :post (:request-method req))
    (error 404 "not_found" (endpoints-line h))
    (let [[tag text] (read-text h req)
          env (when (= :ok tag) (input/parse-json text))]
      (if-not (map? env)
        (error 404 "not_found" (endpoints-line h))
        (let [method (:method env)
              p (:params env)
              agent-id (or (when (map? p) (:agentId p)) "")
              ;; 信封里的 body 已经是解析过的 JSON，而输入解析吃原文 ⇒ 再编回去（输入解析只有一份）
              body (if (contains? env :body) (json/generate-string (:body env)) "")]
          (case method
            "info" (ahttp/json-response 200 (info h))
            "agent/run" (run* h req agent-id body)
            "agent/connect" (connect* h req agent-id body)
            "agent/suggest" (suggest* h req agent-id body)
            "agent/stop" (stop* h req agent-id (or (when (map? p) (:threadId p)) ""))
            (error 404 "unknown_method" (msg :agui-server/unknown-method (or method (:text (msg :agui-server/absent)))))))))))

;; ── 挂路 ─────────────────────────────────────────────

(defn routes
  "把 AG-UI 那几条路挂到 `prefix` 下（如 \"/api/copilotkit\"，\"\" = 根）⇒ clj-net-server 的路由表（`routing/router` 把它变成一个
   Ring 处理器；im.ttalk.agent.server.jetty/start 直接收它）。元数据 `:im.ttalk.agent.server/on-stopping` 带停机钩子（按停所有在飞的
   run）—— 别的 Ring 服务端上请在停机时自己调 `im.ttalk.agent.server.agui.host/cancel-all!`。
   第二个参数是 host（im.ttalk.agent.server.agui.host/host 的返回值），或注册表 + 第三个参数的 host 选项。"
  ([prefix host-or-registry] (routes prefix host-or-registry nil))
  ([prefix host-or-registry opts]
   (let [h (if (host/host? host-or-registry) host-or-registry (host/host host-or-registry opts))
         g (fn [f] (fn [req] (ahttp/guard req h f)))
         id #(nh/param % :id)
         rs (cond-> [[:any "/info" (g (fn [_] (ahttp/json-response 200 (info h))))]
                     [:any "/agent/:id/run" (g #(run* h % (id %) nil))]
                     [:any "/agent/:id/connect" (g #(connect* h % (id %) nil))]
                     [:any "/agent/:id/suggest" (g #(suggest* h % (id %) nil))]
                     [:any "/agent/:id/stop/:threadId" (g #(stop* h % (id %) (nh/param % :threadId)))]]
              ;; ⭐⭐ 没注册线程存储就根本不挂这几条 —— 那时这些路径是「认不出的路径」
              (:thread-store h)
              (into (concat [[:any "/threads" (g #(thread-list h %))]]
                            (for [op ["messages" "events" "state" "archive"]]
                              [:any (str "/threads/:threadId/" op) (g #(thread-op h % op))])
                            [[:any "/threads/:threadId" (g #(thread h %))]]))
              true (conj [:any "" (g #(single h %))]
                         ;; 认不出的路径：任何方法都 404 not_found（keel agui-http-route 的末格；⛔ 不是 405）
                         [:any "/*path" (g (fn [_] (error 404 "not_found" (endpoints-line h))))]))]
     (with-meta (routing/group prefix rs)
       {:im.ttalk.agent.server/on-stopping [#(host/cancel-all! h)] ::host h}))))

(defn handler
  "同 `routes`，直接给一个 Ring 处理器（挂到任何 Ring 服务端上用；停机时记得 `host/cancel-all!`）。"
  ([prefix host-or-registry] (handler prefix host-or-registry nil))
  ([prefix host-or-registry opts] (routing/router (routes prefix host-or-registry opts))))

(defn host-of "routes 用的那个 host。" [routes] (::host (meta routes)))
