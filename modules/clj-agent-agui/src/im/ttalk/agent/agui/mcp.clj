(ns im.ttalk.agent.agui.mcp
  "**MCP Apps** —— 把「带 UI 资源的 MCP 工具」接进 AG-UI 的事件流与前端代理通道。

   ## 这里**只剩前端约定**，协议栈在 `clj-agent-mcp`

   分层与 genui 那次一致，判据也一样：**协议认不认**。

   | 归谁 | 什么 |
   |---|---|
   | `clj-agent-mcp`（独立模块） | JSON-RPC 信封、双时代握手、两种传输、`tools/list`、`tools/call`、工具接入 |
   | 本 ns（agui） | 带 UI 的工具出结果时补一条 **AG-UI activity 事件**；前端那块 iframe 发起调用时的**代理白名单** |

   activity 消息、`replace: true`、`forwardedProps.__proxiedMCPRequest` 都是
   **CopilotKit 前端**的约定，不是 MCP 协议的东西——所以它们在这儿，而不在协议模块里。

   ## 装

   ```clojure
   (require '[im.ttalk.agent.mcp.tools :as mcp-tools]   ;; 协议侧：把工具接进来
            '[im.ttalk.agent.agui.mcp :as agui-mcp])    ;; 前端侧：UI 那半边

   (let [spec (mcp-tools/with-tools base-spec servers)
         apps (agui-mcp/app-tools (:tools spec))]
     (rt/runtime {:agent-fn (tools/agent-fn spec)
                  :event-transform (agui-mcp/event-transform {:apps apps :servers servers})}))
   ```"
  (:require [im.ttalk.agent.mcp.client :as mc]
            [im.ttalk.agent.mcp.tools :as mcp-tools]))

(set! *warn-on-reflection* true)

(def activity-type
  "AG-UI activity 消息的类型标签。前端按它挑 renderer。"
  "mcp-apps")

;;; ============================================================
;;; MCP Apps：activity 事件
;;; ============================================================

(defn app-tools
  "工具表里哪些是**带 UI 的** → `{工具名 {:resource-uri … :server-id …}}`。"
  [tools]
  (into {}
        (keep (fn [t]
                (when-let [uri (mcp-tools/tool-ui-resource t)]
                  [(:name t) {:resource-uri uri
                              :server-id (mcp-tools/tool-server-id t)}])))
        tools))

(defn event-transform
  "`runtime` 的 `:event-transform`：带 UI 的工具出结果时，补一条 activity 快照。

   前端收到它就去把 `resourceUri` 指向的界面拉起来（`replace: true` = 每次结果
   整块换掉）。**只认带 UI 的工具**——普通 MCP 工具就是普通工具，不该多出一块界面。

   `:apps` 由 `app-tools` 算出来（装配期就知道，不必每轮再问 server）。"
  [{:keys [apps servers]}]
  (let [hash-of (into {} (map (fn [s] [(:server-id s) (mcp-tools/server-hash s)])) servers)]
    (fn [_run]
      (let [args-of (volatile! {})]     ;; tool-call-id -> 调用参数（快照里要带上）
        (fn [{:keys [type tool-call-id] :as ev}]
          (case type
            :tool/args (do (vswap! args-of assoc tool-call-id (:args ev)) [ev])

            :tool/result
            (if-let [app (get apps (:name ev))]
              [ev {:type :activity/snapshot
                   :message-id (str activity-type "-" tool-call-id)
                   :activity-type activity-type
                   :replace true
                   :content {:resourceUri (:resource-uri app)
                             :serverId (:server-id app)
                             :serverHash (get hash-of (:server-id app))
                             :toolInput (get @args-of tool-call-id)
                             :result (:content ev)}}]
              [ev])

            [ev]))))))

;;; ============================================================
;;; 前端 iframe 的代理通道
;;; ============================================================

(def proxy-allowed-methods
  "UI 代理**只允许这四个方法**。上游同款白名单——那块界面跑在浏览器里，
   不能让它随便调 server 的任何东西。"
  #{"tools/call" "resources/read" "notifications/message" "ping"})

(defn proxy-request
  "前端那块 MCP UI 发起的调用（`forwardedProps.__proxiedMCPRequest`）。

   `servers` 是你配的那几个；按 `serverId` 或 `serverHash` 找到是哪一个——
   **前端不知道 url**，它只有这两个引用。

   连接由 `mcp.client` 负责（含双时代判定），本函数只做**路由 + 白名单**。"
  [servers {:keys [serverId serverHash method params]}]
  (let [server (or (first (filter #(= serverId (:server-id %)) servers))
                   (first (filter #(= serverHash (mcp-tools/server-hash %)) servers)))]
    (cond
      (nil? server)
      {:error (str "Unknown server: " (or serverId serverHash))}

      (not (contains? proxy-allowed-methods method))
      {:error (str "MCP method not allowed for UI proxy: " method)}

      :else
      (try
        (let [c (mc/client server)]
          (case method
            "tools/call" (mc/call-tool c (:name params) (:arguments params))
            "resources/read" (mc/read-resource c (:uri params))
            "notifications/message" (do (mc/request! c :ping {}) {:success true})
            "ping" (mc/ping c)))
        (catch Throwable t
          {:error (str t)})))))
