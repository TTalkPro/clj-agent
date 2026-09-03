(ns im.ttalk.agent.agui.mcp-test
  "MCP Apps 的**前端那半边**：activity 事件与 UI 代理白名单。

   协议栈（握手、传输、tools/list、tools/call）已经搬到 `clj-agent-mcp`，
   它的测试在那个模块里——本 ns 不再重复测协议，只测「AG-UI 这侧多做的那些事」。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.agui.mcp :as agui-mcp]
            [im.ttalk.agent.mcp.protocol :as p]
            [im.ttalk.agent.mcp.tools :as mcp-tools]))

(def ^:private wire-tools
  [{"name" "get_stock" "description" "查股价"
    "inputSchema" {"type" "object" "properties" {}}}
   {"name" "show_chart" "description" "画走势图"
    "inputSchema" {"type" "object" "properties" {}}
    "_meta" {"ui" {"resourceUri" "ui://charts/line.html"}}}])

(defn- fake-server [log]
  (fn [msg]
    (swap! log conj msg)
    {"jsonrpc" "2.0" "id" (get msg "id")
     "result" (case (get msg "method")
                "server/discover" {"supportedVersions" [p/version-2026-07]
                                   "capabilities" {"tools" {}}}
                "tools/list" {"tools" wire-tools}
                "tools/call" {"content" [{"type" "text" "text" "结果"}]}
                "resources/read" {"contents" [{"uri" (get-in msg ["params" "uri"])
                                               "text" "<html/>"}]}
                "ping" {}
                {})}))

(defn- server-spec [log] {:transport (fake-server log) :server-id "fake"})

(defn- inline-tools [log] (mcp-tools/connect-servers [(server-spec log)]))

;;; ============================================================
;;; activity 事件
;;; ============================================================

(deftest app-tools-picks-ui-tools-test
  (testing "只挑带 UI 的——普通 MCP 工具就是普通工具，不该多出一块界面"
    (let [apps (agui-mcp/app-tools (inline-tools (atom [])))]
      (is (= ["show_chart"] (keys apps)))
      (is (= "ui://charts/line.html" (get-in apps ["show_chart" :resource-uri])))
      (is (= "fake" (get-in apps ["show_chart" :server-id]))))))

(deftest event-transform-test
  (let [servers [{:server-id "fake" :url "http://fake/mcp"}]
        apps (agui-mcp/app-tools (inline-tools (atom [])))
        xf ((agui-mcp/event-transform {:apps apps :servers servers}) {})]

    (testing "带 UI 的工具出结果 → 补一条 activity 快照，且**原事件照旧在前**"
      (let [_ (xf {:type :tool/args :tool-call-id "tc1" :args {"symbol" "AAPL"}})
            out (xf {:type :tool/result :tool-call-id "tc1" :name "show_chart"
                     :content "结果文本"})]
        (is (= [:tool/result :activity/snapshot] (mapv :type out)))
        (let [snap (second out)]
          (is (= "mcp-apps" (:activity-type snap)))
          (is (true? (:replace snap)) "每次结果整块换掉，所以不需要 delta")
          (is (= "ui://charts/line.html" (get-in snap [:content :resourceUri])))
          (is (= {"symbol" "AAPL"} (get-in snap [:content :toolInput]))
              "参数是上一条 :tool/args 记下来的——快照里要带上")
          (is (= (mcp-tools/server-hash (first servers))
                 (get-in snap [:content :serverHash]))
              "前端只有 serverId/serverHash 两个引用，它不知道 url"))))

    (testing "普通工具不补 activity"
      (is (= [:tool/result] (mapv :type (xf {:type :tool/result :tool-call-id "tc2"
                                             :name "get_stock" :content "42"})))))

    (testing "别的事件原样透传"
      (is (= [:message/delta] (mapv :type (xf {:type :message/delta :text "hi"})))))))

;;; ============================================================
;;; UI 代理
;;; ============================================================

(deftest proxy-is-whitelisted-test
  (let [log (atom [])
        servers [(assoc (server-spec log) :url "http://fake/mcp")]]

    (testing "白名单内的方法照转"
      (is (= "结果" (get-in (agui-mcp/proxy-request
                             servers {:serverId "fake" :method "tools/call"
                                      :params {:name "get_stock" :arguments {}}})
                            ["content" 0 "text"])))
      (is (= "<html/>" (get-in (agui-mcp/proxy-request
                                servers {:serverId "fake" :method "resources/read"
                                         :params {:uri "ui://charts/line.html"}})
                               ["contents" 0 "text"]))))

    (testing "白名单外的一律拒——那块界面跑在浏览器里，不能让它随便调"
      (is (re-find #"not allowed"
                   (:error (agui-mcp/proxy-request
                            servers {:serverId "fake" :method "tools/list"})))))

    (testing "按 serverHash 也能找到（前端拿到的可能只有哈希）"
      (is (map? (agui-mcp/proxy-request
                 servers {:serverHash (mcp-tools/server-hash (first servers))
                          :method "ping"}))))

    (testing "认不出的 server"
      (is (re-find #"Unknown server"
                   (:error (agui-mcp/proxy-request servers {:serverId "nope" :method "ping"})))))))
