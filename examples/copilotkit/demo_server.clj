(ns copilotkit.demo-server
  "把 clj-agent 挂成 CopilotKit v2 前端可以**直连**的 runtime，用于与
   `~/workspace/CopilotKit/examples/v2/react/demo` 联调。

   ## 为什么这份能联调

   那个 demo 自带一个 dev-only 逃生口（`src/app/runtime-url.ts`）：

       NEXT_PUBLIC_COPILOTKIT_RUNTIME_URL=http://localhost:4002/api/copilotkit

   设了它，前端就不再走自己那个 Next API route（内含 Node runtime），而是把
   AG-UI 流量直接打到这个进程。**中间那个 Node runtime 就此不需要**。

   ## 跑

       # 1) 起 runtime（本文件）
       MINIMAX_API_KEY=... clojure -M:copilotkit -e \\
         \"(load-file \\\"examples/copilotkit/demo_server.clj\\\")\"

       # 2) 起前端（CopilotKit 仓库里）
       cd ~/workspace/CopilotKit/examples/v2/react/demo
       NEXT_PUBLIC_COPILOTKIT_RUNTIME_URL=http://localhost:4002/api/copilotkit \\
         pnpm dev

   然后开 http://localhost:3000 直接聊。

   ## 这里挂了什么

   - `get-weather`   普通服务端工具（验证 TOOL_CALL_* 四件套）
   - `wipe-database` `{:sensitive true}`——**审批 HITL**：模型要调它就暂停，
     前端收到的是 AG-UI 的一等暂停态：`RUN_FINISHED` +
     `outcome:{type:\"interrupt\"}`（`useInterrupt` / `useHumanInTheLoop` 直接
     接管渲染），答复走下一次 run 的 `resume[]`
   - `:supersede` 并发策略：用户连发两条时，上一条落 `:run/cancelled`
   - **Open Generative UI 插件**（`CLJ_AGENT_GENUI=1` 才装）：模型多一个
     `generateSandboxedUi` 工具，生成的 HTML/CSS/JS 以 activity 事件流给前端

   路由与 SSE 编码在 `http_kit_routes.clj`（web 层不进库，design-principles §2）。"
  (:require [clojure.string :as str]
            [copilotkit.genui :as genui]
            [copilotkit.http-kit-routes :as routes]
            [im.ttalk.agent.agui.a2ui :as a2ui]
            [im.ttalk.agent.agui.mcp :as agui-mcp]
            [im.ttalk.agent.mcp.tools :as mcp-tools]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.pause :as pause]
            [im.ttalk.agent.provider.minimax :as minimax]
            [im.ttalk.agent.tool :refer [deftool]]))

(def auth-token (or (System/getenv "MINIMAX_API_KEY")
                    (System/getenv "MINIMAX_AUTH_TOKEN")))

(def genui?
  "Open Generative UI 插件（`copilotkit.genui`，本目录下）——**默认不装**。

       CLJ_AGENT_GENUI=1 MINIMAX_API_KEY=… clojure -M:copilotkit -e …

   装上以后：模型多一个 `generateSandboxedUi` 工具，参数被翻译成 activity 事件
   流给前端；`/info` 报 `openGenerativeUIEnabled: true`，CopilotKit 前端据此
   注册它那半边的 renderer（沙箱 iframe）。"
  (= "1" (System/getenv "CLJ_AGENT_GENUI")))

(def a2ui?
  "A2UI 插件（`agui.a2ui`）——**默认不装**，`CLJ_AGENT_A2UI=1` 打开。

   与 genui 的分工：genui 让模型写 HTML/CSS/JS（沙箱执行），A2UI 让模型按
   **组件白名单**拼组件树（前端用自己的组件库渲染，不执行任意代码）。"
  (= "1" (System/getenv "CLJ_AGENT_A2UI")))

(def mcp-servers
  "MCP server 列表——**默认空**。`CLJ_AGENT_MCP_URL` 给一个或多个（逗号分隔）：

       CLJ_AGENT_MCP_URL=http://localhost:4100/mcp

   仓库里有两台可以直接对：`examples/mcp/server_example.clj`（2026-07-28，
   我们自己的 `mcp.server` 起的）与 `examples/copilotkit/mcp_server_example.clj`
   （legacy 握手时代，用来验客户端的回退）。连哪台都行——`mcp.client` 自己判时代。
   装上以后：server 的工具进工具表（handler 就是 `tools/call`），带 UI 资源的
   工具（MCP Apps）结果之外还会发一条 activity 消息。"
  (->> (str/split (or (System/getenv "CLJ_AGENT_MCP_URL") "") #",")
       (map str/trim)
       (remove str/blank?)
       (map-indexed (fn [i url] {:type :http :url url :server-id (str "mcp-" i)}))
       vec))

(deftool get-weather
  "查询指定城市的当前天气"
  [[city :string "城市名"]]
  (println "  [tool] get-weather" city)
  (str city "：晴，25°C，湿度 60%"))

(deftool wipe-database
  "清空数据库（危险操作，执行前必须人工批准）"
  [[confirm :string "确认串，必须是 YES"]]
  {:sensitive true}
  (println "  [tool] wipe-database" confirm)
  (str "数据库已清空（confirm=" confirm "）"))

(defn- base-spec []
  {:provider (minimax/create-provider {:api-key auth-token})
   :model minimax/default-model
   :max-tokens 1024
   :tools [#'get-weather #'wipe-database]
   ;; 跨 run 共享：历史归 ChatMemory，暂停快照归 PauseStore
   :memory (memory/in-memory-store)
   :pause-store (pause/in-memory-pause-store)
   ;; 配了 :on-pause 才启用「敏感工具自动暂停」
   :on-pause (fn [{:keys [pending-tool]}]
               (println "  [pause] 等审批:" (:name pending-tool)))
   :system-prompt "你是一个中文助手。需要查天气就调用 get-weather 工具。"})

(defn make-spec []
  ;; 插件是**一层包装**，不是配置项：不装就完全不存在于这份 spec 里
  (cond-> (base-spec)
    genui? genui/with-tool
    a2ui?  a2ui/with-tool
    (seq mcp-servers) (mcp-tools/with-tools mcp-servers)))

(def suggest?
  "无状态建议端点（`POST …/agent/:id/suggest`）——**默认开**。

   关掉（`CLJ_AGENT_SUGGEST=0`）就退回旧行为：`/info` 报
   `suggestions: false`，前端于是把 `copilotkitSuggest` 塞进 `/run` 的 tools
   里自己凑合——设计文档 §9.10 第 5 条那个「输入框敲了字发不出去」就是那么来的。"
  (not= "0" (System/getenv "CLJ_AGENT_SUGGEST")))

(def threads?
  "线程只读面（`/threads*`）——**默认开**。把已有的东西暴露出来：会话表 +
   ChatMemory + 事件缓冲，前端于是有了线程列表。`CLJ_AGENT_THREADS=0` 关掉。"
  (not= "0" (System/getenv "CLJ_AGENT_THREADS")))

(defn- plugin-opts [spec]
  (cond-> {:suggestions? suggest? :threads? threads?}
    genui? (assoc :event-transform (genui/event-transform)
                  :open-generative-ui? true)
    ;; 两个插件都装时，事件流各过一遍：外层先跑 a2ui，再把结果逐条喂给 genui
    (and genui? a2ui?)
    (assoc :event-transform
           (let [g (genui/event-transform) a (a2ui/event-transform)]
             (fn [run]
               (let [gf (g run) af (a run)]
                 (fn [ev] (into [] (mapcat gf) (af ev)))))))
    (and a2ui? (not genui?))
    (assoc :event-transform (a2ui/event-transform))
    a2ui? (assoc :input-transform (a2ui/input-transform)
                 ;; 与 genui 的 `:open-generative-ui?` 对称：不报这一位，stock 前端
                 ;; 只有自己传了 catalog 才会激活 A2UI（`CopilotKitProvider.tsx:328`
                 ;; 的 `a2uiActive = runtimeA2UIEnabled || a2uiCatalogProvided`）
                 :a2ui? true)
    ;; MCP：工具已经在 spec 里了，这里只接 MCP Apps 的两件事——
    ;; activity 消息（event-transform）与前端 iframe 的代理通道（mcp-proxy）
    (seq mcp-servers)
    (as-> opts
          (let [apps (agui-mcp/app-tools (:tools spec))
                mcp-t (agui-mcp/event-transform {:apps apps :servers mcp-servers})
                prev (:event-transform opts)]
            (assoc opts
                   :mcp-proxy #(agui-mcp/proxy-request mcp-servers %)
                   :event-transform
                   (if prev
                     (fn [run] (let [p (prev run) m (mcp-t run)]
                                 (fn [ev] (into [] (mapcat m) (p ev)))))
                     mcp-t))))))

(defonce state (atom nil))

(defn start!
  ([] (start! 4002))
  ([port]
   (when-not auth-token
     (println "需要 MINIMAX_API_KEY（或旧变量 MINIMAX_AUTH_TOKEN）")
     (System/exit 1))
   ;; **spec 只建一次**：`make-spec` 会去连 MCP server（`tools/list`），
   ;; 建两次就连两次
   (let [spec (make-spec)
         s (routes/start! port spec "/api/copilotkit" (plugin-opts spec))]
     (reset! state s)
     (println (str "clj-agent AG-UI runtime 已启动: http://localhost:" port "/api/copilotkit"))
     (println (if genui?
                "  · Open Generative UI 插件：已装（generateSandboxedUi）"
                "  · Open Generative UI 插件：未装（CLJ_AGENT_GENUI=1 打开）"))
     (println (if a2ui?
                "  · A2UI 插件：已装（render_a2ui + v0.9 基础 catalog）"
                "  · A2UI 插件：未装（CLJ_AGENT_A2UI=1 打开）"))
     (println (if suggest?
                "  · /suggest 无状态建议端点：开"
                "  · /suggest 无状态建议端点：关（CLJ_AGENT_SUGGEST=0）"))
     (println (if threads?
                "  · /threads 线程只读面：开"
                "  · /threads 线程只读面：关（CLJ_AGENT_THREADS=0）"))
     (println (if (seq mcp-servers)
                (str "  · MCP：已接 " (count mcp-servers) " 个 server "
                     (mapv :url mcp-servers))
                "  · MCP：未接（CLJ_AGENT_MCP_URL=http://…/mcp 打开）"))
     (println "前端接法：在 CopilotKit 的 examples/v2/react/demo 下")
     (println (str "  NEXT_PUBLIC_COPILOTKIT_RUNTIME_URL=http://localhost:" port
                   "/api/copilotkit pnpm dev"))
     s)))

(defn stop! []
  (when-let [s @state]
    (routes/stop! s)
    (reset! state nil)
    (println "已停止")))

(when-not (System/getProperty "clj-agent.embedded-examples")
  (start!)
  ;; 前台挂住，Ctrl-C 退出
  @(promise))
