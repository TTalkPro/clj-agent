(ns im.ttalk.agent.mcp.tools-test
  "MCP 工具 → 内联工具。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.mcp.client :as mc]
            [im.ttalk.agent.mcp.protocol :as p]
            [im.ttalk.agent.mcp.tools :as mt]))

(def ^:private wire-tools
  [{"name" "get_stock" "description" "查股价"
    "inputSchema" {"type" "object" "properties" {"symbol" {"type" "string"}}}}
   {"name" "show_chart" "description" "画走势图"
    "inputSchema" {"type" "object" "properties" {}}
    ;; 规范的位置：_meta.ui.resourceUri（嵌套）
    "_meta" {"ui" {"resourceUri" "ui://charts/line.html"}}}
   {"name" "legacy_chart" "description" "早期写法"
    "inputSchema" {"type" "object" "properties" {}}
    "_meta" {"ui/resourceUri" "ui://charts/old.html"}}])

(defn- fake-server [log]
  (fn [msg]
    (swap! log conj msg)
    {"jsonrpc" "2.0" "id" (get msg "id")
     "result" (case (get msg "method")
                "server/discover" {"supportedVersions" [p/version-2026-07]
                                   "capabilities" {"tools" {}}}
                "tools/list" {"tools" wire-tools}
                "tools/call" {"content" [{"type" "text"
                                          "text" (str "结果:" (get-in msg ["params" "name"]))}]}
                {})}))

(defn- connected [log]
  (doto (mc/client {:transport (fake-server log) :server-id "fake"}) mc/connect!))

(deftest inline-tool-shape-test
  (testing "产出就是 clj-agent 的内联工具 map——不需要 require 任何 clj-agent 的东西"
    (let [c (connected (atom []))
          t (mt/->inline-tool c (first wire-tools))]
      (is (= "get_stock" (:name t)))
      (is (= "查股价" (:description t)))
      (is (= {"type" "object" "properties" {"symbol" {"type" "string"}}} (:parameters t))
          "schema 原样用 server 给的——翻译一遍只会引入我们自己的 bug")
      (is (fn? (:handler t)))
      (is (mt/mcp-tool? t))))

  (testing "handler 真的会去调 tools/call，且把文本抽出来"
    (let [log (atom [])
          c (connected log)
          t (mt/->inline-tool c (first wire-tools))]
      (is (= "结果:get_stock" ((:handler t) {"symbol" "AAPL"} {})))
      (is (= "get_stock" (get-in (last @log) ["params" "name"]))))))

(deftest ui-resource-detection-test
  (testing "规范的嵌套写法 `_meta.ui.resourceUri`"
    (is (= "ui://charts/line.html" (mt/ui-resource-uri (second wire-tools)))))
  (testing "兼容早期的扁平写法——认多一种不会错认"
    (is (= "ui://charts/old.html" (mt/ui-resource-uri (nth wire-tools 2)))))
  (testing "没有就不是 App 工具"
    (is (nil? (mt/ui-resource-uri (first wire-tools)))))

  (testing "带 UI 的工具，描述里要**告诉模型**它会画界面"
    (let [c (connected (atom []))
          t (mt/->inline-tool c (second wire-tools))]
      (is (re-find #"UI Resource: ui://charts/line.html" (:description t)))
      (is (= "ui://charts/line.html" (mt/tool-ui-resource t))))))

(deftest result-text-test
  (testing "结构化结果优先——新客户端读它，也免得 text 块只是它的字符串复读"
    (is (= "{\"count\":3}" (mt/result-text {"structuredContent" {"count" 3}
                                            "content" [{"type" "text" "text" "3 件"}]}))))
  (testing "多个 text 块拼起来"
    (is (= "甲\n乙" (mt/result-text {"content" [{"type" "text" "text" "甲"}
                                                {"type" "text" "text" "乙"}]}))))
  (testing "一块 text 都没有就整个 JSON 化——宁可给模型一坨 JSON，也别给它 nil"
    (is (re-find #"image" (mt/result-text {"content" [{"type" "image" "data" "…"}]}))))
  (testing "空结果给空串，不给 nil"
    (is (= "" (mt/result-text {})))))

(deftest connect-servers-isolates-failures-test
  (testing "一个 server 挂了不拖垮别的"
    (let [ok-spec {:transport (fake-server (atom [])) :server-id "ok"}
          bad-spec {:transport (fn [_] (throw (ex-info "连不上" {}))) :server-id "bad"}
          tools (mt/connect-servers [bad-spec ok-spec])]
      (is (= 3 (count tools)) "坏的那个跳过，好的那个照常接入")
      (is (every? mt/mcp-tool? tools))))

  (testing "app-tools 只挑带 UI 的"
    (let [tools (mt/connect-servers [{:transport (fake-server (atom [])) :server-id "ok"}])]
      (is (= #{"show_chart" "legacy_chart"} (set (map :name (mt/app-tools tools))))))))

(deftest server-hash-is-stable-test
  (testing "前端引用 server 用哈希——它不知道 url"
    (is (= (mt/server-hash {:url "http://a/mcp"}) (mt/server-hash {:url "http://a/mcp"})))
    (is (not= (mt/server-hash {:url "http://a/mcp"}) (mt/server-hash {:url "http://b/mcp"})))
    (is (string? (mt/server-hash {:command ["npx" "x"]})))))
