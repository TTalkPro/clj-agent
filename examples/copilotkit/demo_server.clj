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
     前端会收到 `CUSTOM/cljagent.run.paused` 事件
   - `:supersede` 并发策略：用户连发两条时，上一条落 `:run/cancelled`

   路由与 SSE 编码在 `http_kit_routes.clj`（web 层不进库，design-principles §2）。"
  (:require [copilotkit.http-kit-routes :as routes]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.pause :as pause]
            [im.ttalk.agent.provider.minimax :as minimax]
            [im.ttalk.agent.tool :refer [deftool]]))

(def auth-token (or (System/getenv "MINIMAX_API_KEY")
                    (System/getenv "MINIMAX_AUTH_TOKEN")))

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

(defn make-spec []
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

(defonce state (atom nil))

(defn start!
  ([] (start! 4002))
  ([port]
   (when-not auth-token
     (println "需要 MINIMAX_API_KEY（或旧变量 MINIMAX_AUTH_TOKEN）")
     (System/exit 1))
   (let [s (routes/start! port (make-spec))]
     (reset! state s)
     (println (str "clj-agent AG-UI runtime 已启动: http://localhost:" port "/api/copilotkit"))
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
