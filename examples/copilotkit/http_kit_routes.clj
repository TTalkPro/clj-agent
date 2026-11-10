(ns copilotkit.http-kit-routes
  "把 agui runtime 挂成 CopilotKit v2 前端直连的 HTTP 端点——**http-kit 版**，建在 clj-agent-server 上。

   AG-UI 那组路由（/info、/agent/:id/run|connect|suggest|stop、/threads*、单路由退路）、SSE、CORS、续跑判定全在
   `im.ttalk.agent.server.agui`（纯 Ring，designs/server-module.md）。本文件只剩三样：

   1. **装配**：注册表 + host 选项（`start!` / `start-registry!` 的签名照旧，demo_server 不用改）；
   2. **换服务端**：同一张路由表交给 http-kit——`routing/router` 变 Ring 处理器，`adapter.http-kit/wrap-streaming` 让 http-kit
      能发 Ring SPEC 的流式正文（它的 adapter 不实现 SPEC 的流式正文）。缺省 adapter（Jetty）见 examples/server/demo.clj；
   3. **两条不属于 AG-UI 的应用端点**：`GET {base}/pending`（审批台的数据源）与 `POST {base}/agent/:id/approve`（带外审批）。
      它们是本 demo 的产品功能，所以留在 examples、不进库；用 `agui.http/guard` 包着，CORS 与 AG-UI 那几条同口径。

   ⚠️ http-kit 上 SSE 发现不了断连（它在客户端断开后 `send!` 仍回成功）：流写到 run 结束；`:orphan-cancel-ms` 不生效。
   要断连检测换 Jetty（`im.ttalk.agent.server.jetty/start` 直接收 `routes` 的返回值）。

   前端这样接：
     <CopilotKit runtimeUrl=\"http://localhost:3000/api/copilotkit\"> … </CopilotKit>

   运行（REPL）：(start! 3000 agent-spec)"
  (:require [cheshire.core :as json]
            [im.ttalk.agent.agui.codec :as codec]
            [im.ttalk.agent.agui.registry :as registry]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.agui.thread-store :as ts]
            [im.ttalk.agent.agui.tools :as agui-tools]
            [im.ttalk.agent.server.adapter.http-kit :as hk-shim]
            [im.ttalk.agent.server.agui :as agui]
            [im.ttalk.agent.server.agui.host :as host]
            [im.ttalk.agent.server.agui.http :as ahttp]
            [im.ttalk.agent.server.http :as nh]
            [im.ttalk.agent.server.routing :as routing]
            [im.ttalk.agent.tool :as tool]
            [org.httpkit.server :as hk])
  (:import [java.nio.charset StandardCharsets]))

;;; ============================================================
;;; host 选项
;;; ============================================================

(def dev-origins
  "开发期的前端源白名单：Next dev 默认起在 :3000，端口被占时它自己退到 :3002，两个都得认。真部署换成你的域名
   （`:origin-allowed` 传 `(ahttp/origin-allow \"https://app.example.com\")`）。"
  ["http://localhost:3000" "http://127.0.0.1:3000" "http://localhost:3002" "http://127.0.0.1:3002"])

(def ^:private info-keys
  "交给 `codec/run-info` 的能力位（host 的 `:info`）。"
  [:multimodal :parallel-tools? :max-iterations :state? :capabilities :approve-with-edits?])

(defn- ->frame-fn
  "心跳帧：串或 (fn [] 串) ⇒ host 要的 (fn [] 字节)。"
  [f]
  (cond (nil? f) nil
        (string? f) (let [b (.getBytes ^String f StandardCharsets/UTF_8)] (fn [] b))
        :else (fn [] (.getBytes (str (f)) StandardCharsets/UTF_8))))

(defn host-options
  "本 demo 的 handler 选项 ⇒ `im.ttalk.agent.server.agui.host/host` 的选项。

   - `:thread-store`    线程存储（不给 = 没有 `/threads*`）
   - `:heartbeat-ms`    SSE 心跳周期（缺省 15000，0 = 关）
   - `:heartbeat-frame` 心跳帧，字符串或 `(fn [] string)`（缺省注释帧）
   - `:max-body-bytes`  请求体上限（缺省 16 MiB，超了 413）
   - `:input-transform` / `:mcp-proxy` 入站插件（条目里给了就以条目为准）
   - `:origin-allowed`  Origin 谓词（缺省 `dev-origins` 白名单；名单外带 Origin 的请求 403）
   - 其余是 `/info` 的能力位（`:version :open-generative-ui? :a2ui?` 与 `info-keys`）"
  [opts]
  (cond-> (merge {:origin-allowed (apply ahttp/origin-allow dev-origins)
                  :credentials? true
                  :allow-headers ["Last-Event-ID"]}
                 (select-keys opts [:version :open-generative-ui? :a2ui? :thread-store :heartbeat-ms
                                    :max-body-bytes :input-transform :mcp-proxy :origin-allowed :orphan-cancel-ms])
                 {:info (select-keys opts info-keys)})
    (:heartbeat-frame opts) (assoc :heartbeat-frame (->frame-fn (:heartbeat-frame opts)))
    true (update :version #(or % "0.0.0"))))

;;; ============================================================
;;; 两条不属于 AG-UI 的应用端点
;;; ============================================================

(defn- pending-body
  "当前有哪些会话卡在等审批。`interruptId` 与事件流里那条 interrupt 是同一个（`codec/interrupt-id`），
   审批台拿它就能凭 `/approve` 或走 `resume[]` 答复。"
  [reg]
  {:pending (into []
                  (for [{:keys [id runtime]} (registry/agents reg)
                        cid (rt/conversations runtime)
                        :let [aw (rt/awaiting runtime cid)]
                        :when aw]
                    {:threadId cid
                     :agentId id
                     :runId (:run-id aw)
                     :interruptId (codec/interrupt-id aw)
                     :reason (:reason aw)
                     :pendingTool (get-in aw [:pending-tool :name])
                     ;; 同一轮多条审批时每条一项（单条时就一项），id 即各自的 interruptId
                     :pendingTools (mapv (fn [p] {:interruptId (str (get-in p [:tool-call :id]))
                                                  :name (:name p)
                                                  :args (:args p)})
                                         (or (not-empty (vec (:pending-tools aw)))
                                             (when (:pending-tool aw) [(:pending-tool aw)])))}))})

(defn- approve
  "带外审批：审批台、Slack 按钮、运维脚本手上只有 conversation-id，不发聊天消息，也不该被迫伪造一次 run。
   协议里的答复路径是下一次 run 的 `resume[]`，聊天页上的按钮走那条就够了。打完再 `/connect` 接着看续跑。"
  [reg req]
  (let [entry (registry/find-agent reg (get-in req [:path-params :id]))
        body (try (json/parse-string (nh/read-body-string req) true) (catch Exception _ ::bad))]
    (cond
      (nil? entry) (ahttp/error-response 404 "agent_not_found" (str "没有这台 agent: " (get-in req [:path-params :id])))
      (not (map? body)) (ahttp/error-response 400 "bad_json" "请求体要是 {threadId, decision?, payload?}")
      (empty? (str (:threadId body))) (ahttp/error-response 400 "bad_input" "缺 threadId")
      :else (let [{:keys [threadId decision payload]} body
                  result (rt/resume-run! (:runtime entry) threadId (or decision "approved") payload)]
              (ahttp/json-response 200 {:status (name (:status result)) :runId (:run-id result)})))))

(defn app-routes
  "本 demo 自己的两条路（路由表格式）。"
  [h base-path]
  (let [reg (:registry h)]
    [[#{"GET" "OPTIONS"} (str base-path "/pending")
      (fn [req] (ahttp/guard req h (fn [_] (ahttp/json-response 200 (pending-body reg)))))]
     [#{"POST" "OPTIONS"} (str base-path "/agent/:id/approve")
      (fn [req] (ahttp/guard req h #(approve reg %)))]]))

;;; ============================================================
;;; 处理器与启动
;;; ============================================================

(defn routes
  "AG-UI 路由 + 本 demo 的两条应用路由（路由表；Jetty 宿主可以直接收它）。"
  [h base-path]
  (into (agui/routes base-path h) (app-routes h base-path)))

(defn handler
  "Ring 处理器（http-kit 用：已套流式垫片）。`reg` 是 `agui.registry` 的注册表；`opts` 见 `host-options`。
   回的处理器元数据里带 `::host`（停机时 `host/cancel-all!` 用）。"
  [reg base-path & [opts]]
  (when-not (registry/registry? reg)
    (throw (ex-info "handler 需要 agui.registry/registry 建的注册表" {})))
  (let [h (host/host reg (host-options opts))
        f (hk-shim/wrap-streaming (routing/router (routes h base-path)))]
    (with-meta (fn [req] (f req)) {::host h})))

(defn start-registry!
  "起一台挂着**多台 agent** 的服务（http-kit）。条目键见 `im.ttalk.agent.server.agui` 的 ns 文档
   （`:server-tool-names` `:memory` `:suggest?` + `:agent-spec` `:input-transform` `:mcp-proxy` …）。`opts` 同 `handler`。"
  [port reg base-path opts]
  (let [h (handler reg base-path opts)]
    {:server (hk/run-server h {:port port})
     :host (::host (meta h))
     :registry reg
     :runtime (:runtime (registry/default-agent reg))}))

(defn start!
  "`agent-spec` 就是 `create-agent` 那份配置（provider / tools / memory / pause-store …）。**memory 与 pause-store 要跨 run
   共享**，所以建在外面。agent id 用 `\"default\"`：CopilotKit 前端不指定 agent 时就找这个名字。多台 agent 用 `start-registry!`。

   线程面：`:threads? true` 缺省用进程内线程存储；`:thread-store` 可换成任何实现（如 `agui.thread-store.sqlite`）。
   `:orphan-cancel-ms` 透传给 runtime（http-kit 上不生效，见 ns 文档）。"
  ([port agent-spec] (start! port agent-spec "/api/copilotkit" nil))
  ([port agent-spec base-path] (start! port agent-spec base-path nil))
  ([port agent-spec base-path {:keys [event-transform suggestions? threads? thread-store
                                      state-tools? orphan-cancel-ms] :as opts}]
   (let [runtime (rt/runtime (cond-> {:agent-fn (agui-tools/agent-fn agent-spec)
                                      ;; 聊天 UX：用户又发一条就顶掉上一条（旧 run 落 cancelled，原因 superseded）
                                      :on-concurrent :supersede
                                      ;; 模型写共享状态的两把工具（见 rt/runtime）
                                      :state-tools? (boolean state-tools?)}
                               ;; 事件流插件（如 agui.a2ui / copilotkit.genui）；不传就完全不存在
                               event-transform (assoc :event-transform event-transform)
                               orphan-cancel-ms (assoc :orphan-cancel-ms orphan-cancel-ms)))
         ;; 服务端工具名：用来把「前端同名声明」认成**渲染意图**而不是新工具
         server-tool-names (into #{} (comp (filter var?) (map #(:name (tool/get-schema %)))) (:tools agent-spec))
         reg (registry/registry
              [{:id "default"
                :runtime runtime
                :server-tool-names server-tool-names
                :memory (:memory agent-spec)
                :suggest? (boolean suggestions?)
                ;; `/suggest` 要现建一次性 agent，所以整份配置得留着
                :agent-spec (when suggestions? agent-spec)}])]
     (start-registry! port reg base-path
                      (-> opts
                          (dissoc :event-transform :threads? :suggestions? :state-tools? :orphan-cancel-ms)
                          ;; 装了写状态的工具才报 state 能力位——没有写的那一半，报 true 就是谎报
                          (assoc :state? (boolean state-tools?)
                                 :thread-store (when threads? (or thread-store (ts/in-memory-store)))))))))

(defn stop! [{:keys [server host] reg :registry}]
  (when host (host/cancel-all! host))
  (registry/shutdown! reg)
  (server))
