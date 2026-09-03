(ns im.ttalk.agent.mcp.protocol-test
  "信封与常量。断言逐条对着规范 2026-07-28 与官方 C# SDK 的字段名写——
   这类地方猜错了对面**不会报错**，只会静默少一半功能或回 -32020。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.mcp.protocol :as p]))

(deftest modern-request-carries-per-request-meta-test
  (testing "modern：版本与能力**每条请求都带**——server 不得从上一条推断"
    (let [msg (p/request "tools/list" {} {:id 1 :protocol-version p/version-2026-07})
          m (get-in msg ["params" "_meta"])]
      (is (= "2.0" (get msg "jsonrpc")))
      (is (= 1 (get msg "id")))
      (is (= p/version-2026-07 (get m p/meta-protocol-version)))
      (is (map? (get m p/meta-client-caps)) "clientCapabilities 是必填")
      (is (= "io.modelcontextprotocol/protocolVersion" p/meta-protocol-version))
      (is (= "io.modelcontextprotocol/clientCapabilities" p/meta-client-caps))))

  (testing "legacy：**不写 `_meta`**——由调用方传 nil 表达，不在这层按版本号猜

            曾经在这里按 `(modern? version)` 守卫过，结果是探测一个没列进
            `modern-versions` 的新版本时 `_meta` 被静默丢掉，请求悄悄退化成 legacy
            形状、server 按老语义服务，两边都不报错。现在时代由 `mcp.client` 判，
            这层只照做。"
    (let [msg (p/request "tools/list" {} {:id 1 :protocol-version nil})]
      (is (nil? (get-in msg ["params" "_meta"]))))
    (let [msg (p/request "tools/list" {} {:id 1 :protocol-version "2099-01-01"})]
      (is (= "2099-01-01" (get-in msg ["params" "_meta" p/meta-protocol-version]))
          "没见过的版本照样写进去——server 会用 -32022 告诉我们它支持哪些")))

  (testing "没有 id 的请求当场抛——协议禁止，且 null id 也禁止"
    (is (thrown? clojure.lang.ExceptionInfo (p/request "ping" {} {:id nil})))))

(deftest protocol-version-is-read-back-from-envelope-test
  (testing "HTTP 头的版本是从信封反读的——两处各记一份就是 -32020 的来源"
    (let [msg (p/request "ping" {} {:id 1 :protocol-version p/version-2026-07})]
      (is (= p/version-2026-07 (p/protocol-version-of msg))))
    (is (nil? (p/protocol-version-of (p/request "ping" {} {:id 1}))))))

(deftest routing-name-only-for-three-methods-test
  (testing "Mcp-Name 只对三个方法有值，且取的字段各不相同"
    (is (= "get_weather" (p/routing-name {"method" "tools/call" "params" {"name" "get_weather"}})))
    (is (= "greet" (p/routing-name {"method" "prompts/get" "params" {"name" "greet"}})))
    (is (= "file:///a" (p/routing-name {"method" "resources/read" "params" {"uri" "file:///a"}})))
    (is (nil? (p/routing-name {"method" "tools/list" "params" {}}))
        "别的方法不发这个头——多发了也是不一致")))

(deftest notification-has-no-id-test
  (is (nil? (get (p/notification "notifications/initialized") "id"))))

(deftest error-codes-test
  (testing "MCP 在 -32020..-32099 段里定义的三个码"
    (is (= -32020 (:header-mismatch p/error-codes)))
    (is (= -32021 (:missing-required-client-capability p/error-codes)))
    (is (= -32022 (:unsupported-protocol-version p/error-codes))))

  (testing "这三个是「对面确实是 modern」的信号，回退判据全靠它们"
    (is (every? p/sep-2575-error? [-32020 -32021 -32022]))
    (is (not (p/sep-2575-error? -32601)) "方法不存在 = 老 server")
    (is (not (p/sep-2575-error? -32602)))))

(deftest unsupported-version-error-lists-supported-test
  (testing "-32022 必须带 supported，客户端要靠它重挑一个版本"
    (let [e (p/unsupported-version-error 1 "1900-01-01" ["2026-07-28" "2025-11-25"])]
      (is (= -32022 (get-in e ["error" "code"])))
      (is (= ["2026-07-28" "2025-11-25"] (get-in e ["error" "data" "supported"])))
      (is (= "1900-01-01" (get-in e ["error" "data" "requested"]))))))

(deftest result-type-defaults-to-complete-test
  (testing "老 server 不发 resultType——规范要求把「没有」当作 complete"
    (is (= "complete" (p/result-type {"content" []})))
    (is (false? (p/input-required? {"content" []}))))
  (testing "MRTR：server 说还差点信息"
    (is (true? (p/input-required? {"resultType" "input_required"})))))

(deftest result-response-shape-test
  (testing "modern：带 resultType 与 _meta.serverInfo"
    (let [r (p/result-response 1 {"tools" []} {:protocol-version p/version-2026-07
                                               :server-info {:name "s" :version "1"}})]
      (is (= "complete" (get-in r ["result" "resultType"])))
      (is (= {:name "s" :version "1"} (get-in r ["result" "_meta" p/meta-server-info])))))

  (testing "legacy：**不发 resultType**——老客户端不认识"
    (let [r (p/result-response 1 {"tools" []})]
      (is (nil? (get-in r ["result" "resultType"]))))))

(deftest version-helpers-test
  (is (p/modern? "2026-07-28"))
  (is (not (p/modern? "2025-11-25")))
  (testing "从 server 报的列表里挑一个我们也支持的 legacy 版"
    (is (= "2025-06-18" (p/best-legacy-version ["2025-06-18" "1999-01-01"])))
    (is (= "2025-11-25" (p/best-legacy-version ["2025-11-25" "2025-06-18"])) "挑最新的")
    (is (= "2025-11-25" (p/best-legacy-version nil)) "一个都对不上就退到 legacy 里最新的")))

(deftest unknown-method-key-throws-at-assembly-test
  (testing "方法名拼错要**当场**抛，而不是运行期收一个看起来像「server 不支持」的 -32601"
    (is (thrown? clojure.lang.ExceptionInfo (p/method :tools-lst)))
    (is (= "tools/call" (p/method :tools-call)))))
