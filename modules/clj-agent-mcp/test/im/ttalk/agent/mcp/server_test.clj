(ns im.ttalk.agent.mcp.server-test
  "服务端：`handle-message` 是纯函数，所以这里全是「一条进、一条出」。

   最后一节是**往返**：把 client 的传输直接接到 server 的 `handle-message` 上。
   两侧对不上的地方，那节会先红——比任何一侧的单测都严。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.mcp.client :as mc]
            [im.ttalk.agent.mcp.protocol :as p]
            [im.ttalk.agent.mcp.server :as srv]))

(def ^:private weather-tool
  {:name "get_weather"
   :description "查天气"
   :parameters {"type" "object"
                "properties" {"city" {"type" "string"}}
                "required" ["city"]}
   :handler (fn [args _ctx] (str "晴，" (get args "city")))})

(def ^:private structured-tool
  {:name "stats"
   :description "结构化结果"
   :parameters {"type" "object" "properties" {}}
   :handler (fn [_ _] {:count 3 :unit "件"})})

(def ^:private boom-tool
  {:name "boom"
   :description "会炸"
   :parameters {"type" "object" "properties" {}}
   :handler (fn [_ _] (throw (ex-info "内部细节：/srv/secret/path" {})))})

(defn- test-server []
  (srv/server {:name "clj-agent" :version "0.3"
               :instructions "测试用"
               :tools [weather-tool structured-tool boom-tool]
               :resources [{:uri "mem://note" :name "note" :mimeType "text/plain"
                            :read (fn [_] "便条内容")}]
               :prompts [{:name "greet" :description "打招呼"
                          :get (fn [args] [{"role" "user"
                                            "content" {"type" "text"
                                                       "text" (str "你好 " (get args "who"))}}])}]}))

(defn- modern-req
  ([method] (modern-req method {}))
  ([method params] (modern-req method params p/version-2026-07))
  ([method params version]
   (p/request method params {:id 1 :protocol-version version})))

(defn- result-of [resp] (get resp "result"))
(defn- error-of [resp] (get resp "error"))

;;; ============================================================
;;; discover
;;; ============================================================

(deftest discover-is-mandatory-and-complete-test
  (testing "server MUST 实现 server/discover——客户端靠它免握手"
    (let [r (result-of (srv/handle-message (test-server) (modern-req "server/discover")))]
      (is (= ["2026-07-28" "2025-11-25" "2025-06-18"] (get r "supportedVersions")))
      (is (= {:tools {} :resources {} :prompts {}} (get r "capabilities")))
      (is (= "测试用" (get r "instructions")))
      (is (= 0 (get r "ttlMs")) "不缓存是安全的默认；声明可缓存却变了才是坑")
      (is (= "private" (get r "cacheScope")))))

  (testing "空 server 不谎报能力位——报了 tools，客户端会去 list 然后拿到空数组"
    (let [r (result-of (srv/handle-message (srv/server {}) (modern-req "server/discover")))]
      (is (= {} (get r "capabilities"))))))

;;; ============================================================
;;; modern 信封校验
;;; ============================================================

(deftest modern-envelope-validation-test
  (testing "缺 clientCapabilities → -32602（规范：缺必填字段即 malformed）"
    (let [msg {"jsonrpc" "2.0" "id" 1 "method" "tools/list"
               "params" {"_meta" {p/meta-protocol-version p/version-2026-07}}}
          e (error-of (srv/handle-message (test-server) msg))]
      (is (= -32602 (get e "code")))
      (is (re-find #"clientCapabilities" (get e "message")))))

  (testing "版本不认识 → -32022，且**列出我们支持的**，客户端要靠它重挑"
    (let [e (error-of (srv/handle-message (test-server)
                                          (modern-req "tools/list" {} "1999-01-01")))]
      (is (= -32022 (get e "code")))
      (is (= ["2026-07-28" "2025-11-25" "2025-06-18"] (get-in e ["data" "supported"])))
      (is (= "1999-01-01" (get-in e ["data" "requested"])))))

  (testing "modern 的成功响应带 resultType 与 _meta.serverInfo"
    (let [r (result-of (srv/handle-message (test-server) (modern-req "tools/list")))]
      (is (= "complete" (get r "resultType")))
      (is (= {:name "clj-agent" :version "0.3"} (get-in r ["_meta" p/meta-server-info]))))))

(deftest header-mismatch-is-checked-by-transport-layer-test
  (testing "头与信封不一致 → -32020。规则属于协议，但只有 web 层看得见头"
    (let [msg (modern-req "tools/call" {"name" "get_weather"})]
      (is (nil? (srv/check-headers msg {"mcp-protocol-version" p/version-2026-07
                                        "mcp-method" "tools/call"
                                        "mcp-name" "get_weather"})))
      (is (= -32020 (get-in (srv/check-headers msg {"mcp-protocol-version" "2025-11-25"})
                            ["error" "code"])))
      (is (= -32020 (get-in (srv/check-headers msg {"mcp-method" "tools/list"})
                            ["error" "code"])))
      (is (= -32020 (get-in (srv/check-headers msg {"mcp-name" "other_tool"})
                            ["error" "code"])))))

  (testing "头缺省不算不一致——只有**同时存在且不同**才是 -32020"
    (is (nil? (srv/check-headers (modern-req "tools/list") {})))))

;;; ============================================================
;;; legacy
;;; ============================================================

(deftest legacy-initialize-negotiates-version-test
  (testing "客户端要的版本我们支持 → 用它"
    (let [r (result-of (srv/handle-message
                        (test-server)
                        {"jsonrpc" "2.0" "id" 1 "method" "initialize"
                         "params" {"protocolVersion" "2025-06-18"}}))]
      (is (= "2025-06-18" (get r "protocolVersion")))
      (is (= {:name "clj-agent" :version "0.3"} (get r "serverInfo")))
      (is (nil? (get r "resultType")) "legacy 客户端不认识 resultType")))

  (testing "要一个我们不支持的 → 回我们最新的 legacy 版，而不是默默按自己的来"
    (let [r (result-of (srv/handle-message
                        (test-server)
                        {"jsonrpc" "2.0" "id" 1 "method" "initialize"
                         "params" {"protocolVersion" "1999-01-01"}}))]
      (is (= "2025-11-25" (get r "protocolVersion"))))))

(deftest legacy-request-without-meta-is-served-test
  (testing "没有 _meta 也不是 initialize → 按 legacy 请求服务（我们本来就无状态）"
    (let [r (result-of (srv/handle-message
                        (test-server)
                        {"jsonrpc" "2.0" "id" 2 "method" "tools/list" "params" {}}))]
      (is (= 3 (count (get r "tools"))))
      (is (nil? (get r "resultType"))))))

(deftest notifications-get-no-response-test
  (testing "协议禁止给通知回响应"
    (is (nil? (srv/handle-message (test-server)
                                  {"jsonrpc" "2.0" "method" "notifications/initialized"})))))

;;; ============================================================
;;; 工具
;;; ============================================================

(deftest tools-list-and-call-test
  (let [s (test-server)]
    (testing "tools/list 用 inputSchema 这个键名——叫 parameters 前端与模型都看不见"
      (let [tools (get (result-of (srv/handle-message s (modern-req "tools/list"))) "tools")
            t (first (filter #(= "get_weather" (get % "name")) tools))]
        (is (some? (get t "inputSchema")))
        (is (= "查天气" (get t "description")))))

    (testing "字符串返回值 → 一块 text"
      (let [r (result-of (srv/handle-message
                          s (modern-req "tools/call" {"name" "get_weather"
                                                      "arguments" {"city" "北京"}})))]
        (is (= [{"type" "text" "text" "晴，北京"}] (get r "content")))
        (is (nil? (get r "isError")))))

    (testing "结构化返回值 → structuredContent 与 content **都给**（新旧客户端各取所需）"
      (let [r (result-of (srv/handle-message s (modern-req "tools/call" {"name" "stats"})))]
        (is (= {:count 3 :unit "件"} (get r "structuredContent")))
        (is (seq (get r "content")))))

    (testing "工具抛异常 → isError，**不是** JSON-RPC 错误；且不外泄栈"
      (let [resp (srv/handle-message s (modern-req "tools/call" {"name" "boom"}))
            r (result-of resp)]
        (is (nil? (error-of resp)) "协议层没出错，是工具没干成")
        (is (true? (get r "isError")))
        (let [text (get-in r ["content" 0 "text"])]
          (is (re-find #"ExceptionInfo" text) "回类名，够模型判断该不该重试")
          (is (not (re-find #"secret" text))
              "**默认不外泄**异常消息——对面是外部客户端，消息里常有路径与内部结构"))))

    (testing "本地开发要看细节：显式打开开关才回完整消息"
      (let [s2 (srv/server {:tools [boom-tool] :expose-error-messages? true})
            r (result-of (srv/handle-message s2 (modern-req "tools/call" {"name" "boom"})))]
        (is (re-find #"secret" (get-in r ["content" 0 "text"])))))

    (testing "叫了个不存在的工具 → 这是**协议错误**（-32602），不是工具失败"
      (let [e (error-of (srv/handle-message s (modern-req "tools/call" {"name" "nope"})))]
        (is (= -32602 (get e "code")))))))

(deftest resources-and-prompts-test
  (let [s (test-server)]
    (is (= "便条内容"
           (get-in (result-of (srv/handle-message
                               s (modern-req "resources/read" {"uri" "mem://note"})))
                   ["contents" 0 "text"])))
    (testing "资源不存在用 -32602——老版本的 -32002 已作废，不再发"
      (is (= -32602 (get (error-of (srv/handle-message
                                    s (modern-req "resources/read" {"uri" "mem://nope"})))
                         "code"))))
    (is (= "你好 世界"
           (get-in (result-of (srv/handle-message
                               s (modern-req "prompts/get" {"name" "greet"
                                                            "arguments" {"who" "世界"}})))
                   ["messages" 0 "content" "text"])))))

(deftest unknown-method-test
  (is (= -32601 (get (error-of (srv/handle-message (test-server) (modern-req "no/such")))
                     "code"))))

;;; ============================================================
;;; 往返：client ↔ server，两侧同时钉住
;;; ============================================================

(defn- loopback
  "把 client 的传输直接接到 server 的 handle-message 上。"
  [s] (fn [msg] (srv/handle-message s msg)))

(deftest roundtrip-modern-test
  (testing "我们自己的 client 连我们自己的 server，走 modern"
    (let [s (test-server)
          c (mc/client {:transport (loopback s)})
          st (mc/connect! c)]
      (is (= :modern (:era st)))
      (is (= p/version-2026-07 (:protocol-version st)))
      (is (= {:name "clj-agent" :version "0.3"} (mc/server-info c))
          "modern 的 serverInfo 走结果的 _meta，两侧要对上")
      (is (= "测试用" (mc/instructions c)))
      (is (= #{"get_weather" "stats" "boom"}
             (set (map #(get % "name") (mc/list-tools c)))))
      (is (= "晴，上海"
             (get-in (mc/call-tool c "get_weather" {"city" "上海"}) ["content" 0 "text"])))
      (is (= "便条内容"
             (get-in (mc/read-resource c "mem://note") ["contents" 0 "text"])))
      (is (= 1 (count (mc/list-prompts c)))))))

(deftest roundtrip-legacy-test
  (testing "钉死 legacy 版：走握手，同一个 server 照样服务"
    (let [s (test-server)
          c (mc/client {:transport (loopback s) :protocol-version "2025-06-18"})
          st (mc/connect! c)]
      (is (= :legacy (:era st)))
      (is (= "2025-06-18" (:protocol-version st)))
      (is (= {:name "clj-agent" :version "0.3"} (:server-info st))
          "legacy 的 serverInfo 走握手回执——与 modern 是两条路，都得对")
      (is (= 3 (count (mc/list-tools c))))
      (is (= "晴，广州"
             (get-in (mc/call-tool c "get_weather" {"city" "广州"}) ["content" 0 "text"]))))))

(deftest roundtrip-tool-error-crosses-the-wire-test
  (testing "工具失败与协议失败在往返里仍然是两件事"
    (let [c (mc/client {:transport (loopback (test-server))})]
      (is (true? (get (mc/call-tool c "boom" {}) "isError")))
      (is (thrown? clojure.lang.ExceptionInfo (mc/call-tool c "nope" {}))))))

(deftest stdio-server-loop-test
  (testing "stdio 循环：一行一条，响应只写 out"
    (let [in (java.io.StringReader.
              (str (p/encode (modern-req "tools/list")) "\n"
                   (p/encode {"jsonrpc" "2.0" "method" "notifications/initialized"}) "\n"
                   "不是 JSON\n"))
          out (java.io.StringWriter.)]
      (srv/stdio-server! (test-server) in out)
      (let [lines (remove clojure.string/blank? (clojure.string/split-lines (str out)))]
        (is (= 2 (count lines)) "一条 tools/list 响应 + 一条 parse-error；通知**不回**")
        (is (= 3 (count (get-in (p/parse (first lines)) ["result" "tools"]))))
        (is (= -32700 (get-in (p/parse (second lines)) ["error" "code"])))))))
