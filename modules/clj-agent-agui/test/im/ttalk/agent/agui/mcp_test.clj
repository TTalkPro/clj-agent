(ns im.ttalk.agent.agui.mcp-test
  "MCP 接入。**传输是注入的**——一个纯函数就能把「握手 → 列工具 → 调工具 →
   出 activity」整条链跑完，不用起服务，也不用网络。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.agui.codec :as codec]
            [im.ttalk.agent.agui.mcp :as mcp]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.agui.support :as support]))

;;; ============================================================
;;; 假 server：一个函数
;;; ============================================================

(def ^:private tools
  [{:name "get_stock"
    :description "查股价"
    :inputSchema {:type "object" :properties {:symbol {:type "string"}}}}
   {:name "show_chart"
    :description "画走势图"
    :inputSchema {:type "object" :properties {:symbol {:type "string"}}}
    :_meta {(keyword "ui/resourceUri") "ui://charts/line.html"}}])

(defn- fake-server
  "记下收到的每一条请求，按方法回结果。"
  [log]
  (fn [{:keys [id method params]}]
    (swap! log conj [method params])
    (when id                                   ;; 通知没有 id，不回
      {:jsonrpc "2.0" :id id
       :result (case method
                 "initialize" {:protocolVersion "2025-06-18"
                               :serverInfo {:name "fake" :version "1"}}
                 "tools/list" {:tools tools}
                 "tools/call" {:content [{:type "text" :text (str "结果:" (:name params))}]}
                 "resources/read" {:contents [{:uri (:uri params) :text "<html/>"}]}
                 "ping" {}
                 {})})))

(defn- test-client [log]
  (mcp/client {:transport (fake-server log) :server-id "fake" :url "http://fake/mcp"}))

;;; ============================================================
;;; 客户端
;;; ============================================================

(deftest handshake-test
  (testing "握手：initialize + notifications/initialized，且**幂等**"
    (let [log (atom [])
          c (test-client log)]
      (mcp/initialize! c)
      (mcp/initialize! c)
      (is (= ["initialize" "notifications/initialized"] (mapv first @log))
          "第二次不再握手——每次调工具都重握会把 server 的会话冲掉")
      (is (= mcp/default-protocol-version (get-in (second (first @log)) [:protocolVersion])))
      (is (= ["text/html+mcp"]
             (get-in (second (first @log))
                     [:capabilities :extensions (keyword "io.modelcontextprotocol/ui") :mimeTypes]))
          "不声明 UI 扩展，server 不会把带界面的工具给你")))

  (testing "任何调用前都会自动握手"
    (let [log (atom [])]
      (mcp/list-tools (test-client log))
      (is (= ["initialize" "notifications/initialized" "tools/list"] (mapv first @log))))))

(deftest error-becomes-exception-test
  (let [c (mcp/client {:transport (fn [{:keys [id]}]
                                    {:jsonrpc "2.0" :id id
                                     :error {:code -32601 :message "Method not found"}})})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Method not found"
                          (mcp/request! c "tools/list" {})))))

(deftest text-content-test
  (testing "取 text 块拼起来"
    (is (= "a\nb" (mcp/text-content {:content [{:type "text" :text "a"}
                                               {:type "image" :data "…"}
                                               {:type "text" :text "b"}]}))))
  (testing "一块 text 都没有也得给模型点东西，不能是 nil"
    (let [s (mcp/text-content {:content [{:type "image" :data "x"}]})]
      (is (string? s))
      (is (re-find #"image" s)))))

;;; ============================================================
;;; 工具接入
;;; ============================================================

(deftest tools-become-inline-tools-test
  (let [log (atom [])
        c (test-client log)
        inline (mapv #(mcp/->inline-tool c %) (mcp/list-tools c))
        [stock chart] inline]
    (testing "schema 原样进工具表"
      (is (= "get_stock" (:name stock)))
      (is (= {:type "object" :properties {:symbol {:type "string"}}} (:parameters stock))))

    (testing "handler 就是 tools/call，结果取 text 块"
      (reset! log [])
      (is (= "结果:get_stock" ((:handler stock) {:symbol "AAPL"} nil)))
      (is (= ["tools/call"] (mapv first @log)))
      (is (= {:name "get_stock" :arguments {:symbol "AAPL"}} (second (first @log)))))

    (testing "带 UI 资源的工具：描述里追加提示，元数据里记住 uri"
      (is (= "ui://charts/line.html" (mcp/ui-resource-uri (second tools))))
      (is (re-find #"\[UI Resource: ui://charts/line\.html\]" (:description chart)))
      (is (nil? (mcp/ui-resource-uri (first tools))) "普通工具没有"))

    (testing "app-tools 只挑带 UI 的"
      (is (= #{"show_chart"} (set (keys (mcp/app-tools inline))))))

    (testing "都带上 mcp 标记（元数据不序列化，不会混进发给模型的 schema）"
      (is (every? mcp/mcp-tool? inline))
      (is (nil? (:handler (dissoc chart :handler :name :description :parameters)))))))

(deftest server-down-does-not-take-others-with-it-test
  ;; server 配置里带 `:transport` 就直接用它——**测试不需要 with-redefs**，
  ;; 这正是把传输做成可注入的用处
  (let [tools (mcp/connect-servers
               [{:server-id "bad" :url "http://down/mcp"
                 :transport (fn [_] (throw (ex-info "connection refused" {})))}
                {:server-id "ok" :url "http://up/mcp"
                 :transport (fake-server (atom []))}])]
    (is (= ["get_stock" "show_chart"] (mapv :name tools))
        "挂了的跳过，好的照接——但会记一条 warn，不是静默吞掉")))

;;; ============================================================
;;; MCP Apps：activity 事件
;;; ============================================================

(deftest ui-tool-result-emits-activity-test
  (let [servers [{:server-id "fake" :type :http :url "http://fake/mcp"}]
        apps {"show_chart" {:resource-uri "ui://charts/line.html" :server-id "fake"}}
        t ((mcp/event-transform {:apps apps :servers servers}) {})
        out (into [] (mapcat t)
                  [{:type :tool/args :tool-call-id "tc1" :name "show_chart" :args {:symbol "AAPL"}}
                   {:type :tool/result :tool-call-id "tc1" :name "show_chart" :content "…"}
                   {:type :tool/result :tool-call-id "tc2" :name "get_stock" :content "…"}])]
    (testing "带 UI 的工具出结果 → 补一条 activity；普通工具不补"
      (is (= [:tool/args :tool/result :activity/snapshot :tool/result] (mapv :type out))))
    (let [snap (first (filter #(= :activity/snapshot (:type %)) out))]
      (is (= "mcp-apps" (:activity-type snap)))
      (is (true? (:replace snap)))
      (is (= "ui://charts/line.html" (get-in snap [:content :resourceUri])))
      (is (= {:symbol "AAPL"} (get-in snap [:content :toolInput]))
          "调用参数要带上——前端那块界面要靠它知道画的是谁")
      (is (= (mcp/server-hash (first servers)) (get-in snap [:content :serverHash]))
          "前端不知道 url，只有 serverId / serverHash 两个引用")
      (is (= "ACTIVITY_SNAPSHOT" (:type (codec/->agui snap)))))))

;;; ============================================================
;;; 代理通道
;;; ============================================================

(deftest proxy-is-whitelisted-test
  (let [log (atom [])
        servers [{:server-id "fake" :type :http :url "http://fake/mcp"
                  :transport (fake-server log)}]]
    (do
      (testing "白名单内的方法照转"
        (is (= "结果:show_chart"
               (mcp/text-content (mcp/proxy-request servers {:serverId "fake" :method "tools/call"
                                                             :params {:name "show_chart"}}))))
        (is (= [{:uri "ui://x" :text "<html/>"}]
               (:contents (mcp/proxy-request servers {:serverId "fake" :method "resources/read"
                                                      :params {:uri "ui://x"}})))))

      (testing "白名单外的一律拒——那块界面跑在浏览器里，不能让它随便调"
        (is (re-find #"not allowed"
                     (:error (mcp/proxy-request servers {:serverId "fake" :method "tools/list"})))))

      (testing "按 serverHash 也能找到（前端拿到的可能只有哈希）"
        (is (nil? (:error (mcp/proxy-request servers {:serverHash (mcp/server-hash (first servers))
                                                      :method "ping"})))))

      (testing "认不出的 server"
        (is (re-find #"Unknown server"
                     (:error (mcp/proxy-request servers {:serverId "nope" :method "ping"}))))))))

;;; ============================================================
;;; 端到端：装上跑一个 run
;;; ============================================================

(deftest end-to-end-through-runtime-test
  (let [log (atom [])
        c (test-client log)
        inline (mapv #(mcp/->inline-tool c %) (mcp/list-tools c))
        servers [{:server-id "fake" :type :http :url "http://fake/mcp"}]
        spec {:provider (support/provider
                         [{:text "" :tool-calls [{:id "tc-1" :name "show_chart"
                                                  :args {:symbol "AAPL"}}]}
                          {:text "图画好了"}])
              :model "mock"
              :tools inline}
        r (support/runtime spec {:event-transform (mcp/event-transform
                                                   {:apps (mcp/app-tools inline)
                                                    :servers servers})})
        col (support/collector)]
    (rt/subscribe r "c1" {:on-event (:on-event col)})
    (rt/start-run! r "c1" "画个 AAPL 的图")
    (is (support/wait-for #(support/terminal-event ((:events col)))))
    (let [evs ((:events col))]
      (is (= :run/finished (:type (support/terminal-event evs))))
      (is (some #(= "tools/call" (first %)) @log) "工具真的打到了 MCP server")
      (is (some #(= :activity/snapshot (:type %)) evs) "UI 工具带出了 activity")
      (is (= "结果:show_chart"
             (:content (first (filter #(= :tool/result (:type %)) evs))))
          "结果**在轮内**回灌给模型——这正是与上游「run 末尾才补」的差异")
      (let [seqs (mapv :seq evs)]
        (is (= seqs (range (first seqs) (inc (last seqs)))))))
    (rt/shutdown! r)))
