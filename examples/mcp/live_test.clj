(ns mcp.live-test
  "MCP 两侧 × **真 HTTP** 端到端验证。

   单测用「一个函数当传输」把协议逻辑跑完了，但**传输本身测不到**——三个必填头
   有没有发对、状态码分得对不对、JSON / SSE 两种响应体认不认、双时代回退在真
   网络上走不走得通，只有真打一次 HTTP 才知道。

   两个场景：

   1. **modern**：打我们自己的 `mcp.server`（2026-07-28）——discover → tools/list
      → tools/call → resources/read → prompts/get，外加「内联工具真的能跑」；
   2. **legacy 回退**：打仓里那台老 server（`initialize` 握手 + 会话）——
      客户端应当先按 modern 探测、被拒后退回握手，并照常调工具。

   运行（不需要任何 API key，两台 server 都在本地起）：

       clojure -J-Dclj-agent.embedded-examples=1 -M:mcp -m mcp.live-test"
  (:require [copilotkit.mcp-server-example :as legacy]
            [im.ttalk.agent.mcp.client :as mc]
            [im.ttalk.agent.mcp.protocol :as p]
            [im.ttalk.agent.mcp.tools :as mt]
            [mcp.server-example :as modern]))

(def failures (atom 0))

(defn- check [ok? label & [detail]]
  (if ok?
    (println "  ✓" label (or detail ""))
    (do (swap! failures inc)
        (println "  ✗" label (or detail "")))))

;;; ============================================================
;;; 1. modern：打我们自己的 server
;;; ============================================================

(defn scenario-modern! [port]
  (println "\n[1] modern（2026-07-28）× 真 HTTP")
  (let [stop (modern/start-http! port)]
    (try
      (let [c (mc/client {:url (str "http://localhost:" port "/")})
            st (mc/connect! c)]
        (check (= :modern (:era st)) "判成 modern 时代" (:era st))
        (check (= p/version-2026-07 (:protocol-version st)) "协商到 2026-07-28")
        (check (= {"name" "clj-agent-demo" "version" "0.3"} (mc/server-info c))
               "serverInfo 从结果的 _meta 里拿到——modern 没有握手回执")
        (check (some? (mc/instructions c)) "instructions 拿到了")

        (let [tools (mc/list-tools c)]
          (check (= #{"get_weather" "fleet_stats"} (set (map #(get % "name") tools)))
                 "tools/list")
          (check (some? (get (first tools) "inputSchema"))
                 "schema 用的是 inputSchema 这个键名"))

        (check (= "杭州：晴，24℃"
                  (get-in (mc/call-tool c "get_weather" {"city" "杭州"}) ["content" 0 "text"]))
               "tools/call（含 Mcp-Name 头）")
        (check (= {"total" 128 "online" 97 "unit" "辆"}
                  (get (mc/call-tool c "fleet_stats" {}) "structuredContent"))
               "结构化结果原样过网")
        (check (= "这是一台 clj-agent 起的 MCP server。"
                  (get-in (mc/read-resource c "mem://readme") ["contents" 0 "text"]))
               "resources/read")
        (check (re-find #"苏州"
                        (get-in (mc/get-prompt c "daily_brief" {"city" "苏州"})
                                ["messages" 0 "content" "text"]))
               "prompts/get")

        ;; 内联工具那一跳：模型看到的就是这个 handler
        (let [t (mt/->inline-tool c (first (mc/list-tools c)))]
          (check (= "苏州：晴，24℃" ((:handler t) {"city" "苏州"} {}))
                 "MCP 工具 → 内联工具 → handler 真的打了一次 tools/call")))
      (finally (stop)))))

;;; ============================================================
;;; 2. legacy 回退：打那台老 server
;;; ============================================================

(defn scenario-legacy-fallback! [port]
  (println "\n[2] 双时代回退 × 真 HTTP（对面是 initialize 握手时代的 server）")
  (legacy/start! port)
  (try
    (let [c (mc/client {:url (str "http://localhost:" port "/mcp")})
          st (mc/connect! c)]
      (check (= :legacy (:era st)) "modern 探测被拒后退回握手" (:era st))
      (check (some? (:protocol-version st)) "协商出一个 legacy 版本" (:protocol-version st))
      (check (some? (:server-info st)) "serverInfo 从握手回执里拿到")
      (check (= #{"get_stock" "show_chart"} (set (map #(get % "name") (mc/list-tools c))))
             "退回之后照常 tools/list")
      (check (re-find #"AAPL"
                      (get-in (mc/call-tool c "get_stock" {"symbol" "AAPL"}) ["content" 0 "text"]))
             "退回之后照常 tools/call")
      (check (some? (mt/ui-resource-uri
                     (first (filter #(= "show_chart" (get % "name")) (mc/list-tools c)))))
             "带 UI 资源的工具认得出来（MCP Apps）"))
    (finally (legacy/stop!))))

;;; ============================================================
;;; 入口
;;; ============================================================

(defn -main [& _]
  (println "MCP × 真 HTTP 端到端")
  (scenario-modern! 4331)
  (scenario-legacy-fallback! 4332)
  (println (str "\n" (if (zero? @failures) "全部通过" (str @failures " 项失败"))))
  (shutdown-agents)
  (System/exit (if (zero? @failures) 0 1)))
