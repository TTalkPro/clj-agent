(ns copilotkit.mcp-server-example
  "一个**最小的 MCP server**（Streamable HTTP），用来验 `agui.mcp` 的客户端那一半。

   单测用可注入的假传输把「握手 → 列工具 → 调工具」跑完了，但**传输本身测不到**
   ——JSON-RPC 走没走对、会话头有没有回传、SSE 响应认不认，只有真打一次 HTTP
   才知道。所以这里起一个真的：JDK 的 `java.net.http` 打过来，http-kit 接。

   实现的是 MCP 的一个**子集**（够 `agui.mcp` 用，不是完整 server）：

     initialize            → 回协议版本 + serverInfo，并下发 Mcp-Session-Id
     notifications/initialized → 202，无体
     tools/list            → 两个工具：一个普通的，一个**带 UI 资源**（MCP App）
     tools/call            → 执行
     resources/read        → 回那块 UI 的 HTML
     ping                  → {}

   跑：

       clojure -M:copilotkit -e \"(load-file \\\"examples/copilotkit/mcp_server_example.clj\\\")\"

   或者在别的脚本里 `(mcp-server-example/start! 4100)`。"
  (:require [cheshire.core :as json]
            [org.httpkit.server :as hk]))

(def tools
  [{:name "get_stock"
    :description "查一支股票的最新价"
    :inputSchema {:type "object"
                  :properties {:symbol {:type "string" :description "股票代码"}}
                  :required ["symbol"]}}
   ;; `_meta` 里带 `ui/resourceUri` = 这是个 **MCP App** 工具：结果之外还有一块界面
   {:name "show_chart"
    :description "画一支股票的走势图"
    :inputSchema {:type "object"
                  :properties {:symbol {:type "string" :description "股票代码"}}
                  :required ["symbol"]}
    :_meta {"ui/resourceUri" "ui://stocks/chart.html"}}])

(def ^:private prices {"AAPL" 231.5 "MSFT" 402.1 "NVDA" 118.9})

(def executed (atom []))

(defn- call-tool [{:keys [name arguments]}]
  (swap! executed conj [name arguments])
  (let [sym (or (:symbol arguments) (get arguments "symbol") "AAPL")
        price (get prices sym 100.0)]
    (case name
      "get_stock" {:content [{:type "text" :text (str sym " 最新价 " price " 美元")}]}
      "show_chart" {:content [{:type "text"
                               :text (json/generate-string
                                      {:symbol sym :points [(- price 3) (- price 1) price]})}]}
      {:content [{:type "text" :text (str "unknown tool: " name)}] :isError true})))

(defn- handle-rpc
  "一条 JSON-RPC 请求 → 响应（通知返回 nil：通知没有 id，按协议不回结果）。"
  [{:keys [id method params]}]
  (let [result (case method
                 "initialize" {:protocolVersion (or (:protocolVersion params) "2025-06-18")
                               :capabilities {:tools {} :resources {}}
                               :serverInfo {:name "clj-agent-demo-mcp" :version "0.1"}}
                 "tools/list" {:tools tools}
                 "tools/call" (call-tool params)
                 "resources/read" {:contents [{:uri (:uri params)
                                               :mimeType "text/html+mcp"
                                               :text (str "<div class=\"chart\">"
                                                          "<h3>走势图</h3>"
                                                          "<canvas id=\"c\"></canvas></div>")}]}
                 "ping" {}
                 ::unknown)]
    (when id
      (if (= ::unknown result)
        {:jsonrpc "2.0" :id id :error {:code -32601 :message (str "Method not found: " method)}}
        {:jsonrpc "2.0" :id id :result result}))))

(defn handler [request]
  (if (= :post (:request-method request))
    (let [body (-> request :body slurp (json/parse-string true))
          resp (handle-rpc body)]
      (if resp
        {:status 200
         ;; 握手时下发会话 id——客户端必须在后续请求里回传（这条正是只有真机
         ;; 才验得到的：假传输里没有头这回事）
         :headers (cond-> {"Content-Type" "application/json"}
                    (= "initialize" (:method body)) (assoc "Mcp-Session-Id" "sess-1"))
         :body (json/generate-string resp)}
        {:status 202 :body ""}))                ;; 通知
    {:status 405 :body "MCP endpoint takes POST"}))

(defonce state (atom nil))

(defn start!
  ([] (start! 4100))
  ([port]
   (reset! state (hk/run-server handler {:port port}))
   (println (str "最小 MCP server 已启动: http://localhost:" port "/mcp"))
   @state))

(defn stop! []
  (when-let [s @state] (s) (reset! state nil)))

(when-not (System/getProperty "clj-agent.embedded-examples")
  (start!)
  @(promise))
