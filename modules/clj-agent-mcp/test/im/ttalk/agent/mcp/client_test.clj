(ns im.ttalk.agent.mcp.client-test
  "双时代判定。**传输就是一个函数**，所以这里不起服务、不起进程——
   每个用例就是「对面这样答，客户端该怎么判」。

   判据出处：规范 PR #2844 与官方 C# SDK `McpClientImpl.cs:355-420` 的注释。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.mcp.client :as mc]
            [im.ttalk.agent.mcp.protocol :as p]
            [im.ttalk.agent.mcp.transport :as t]))

(defn- recording-transport
  "把收到的消息记进 log，按 `respond` 回。返回 `[transport log]`。"
  [respond]
  (let [log (atom [])]
    [(fn [msg] (swap! log conj msg) (respond msg))
     log]))

(defn- ok [id result] {"jsonrpc" "2.0" "id" id "result" result})
(defn- err [id code message & [data]]
  {"jsonrpc" "2.0" "id" id
   "error" (cond-> {"code" code "message" message} data (assoc "data" data))})

(defn- discover-ok [versions]
  {"supportedVersions" versions
   "capabilities" {"tools" {}}
   "ttlMs" 0 "cacheScope" "private"
   "instructions" "用法说明"
   "_meta" {p/meta-server-info {"name" "demo" "version" "9"}}})

(def ^:private init-ok
  {"protocolVersion" "2025-11-25"
   "capabilities" {"tools" {}}
   "serverInfo" {"name" "old-demo" "version" "1"}})

(defn- methods-of [log] (mapv #(get % "method") @log))

;;; ============================================================
;;; modern
;;; ============================================================

(deftest modern-happy-path-test
  (testing "discover 说支持我们这版 → modern，且**不发 initialize**"
    (let [[tp log] (recording-transport
                    (fn [msg] (ok (get msg "id") (discover-ok [p/version-2026-07]))))
          c (mc/client {:transport tp})
          st (mc/connect! c)]
      (is (= :modern (:era st)))
      (is (= p/version-2026-07 (:protocol-version st)))
      (is (= {"tools" {}} (:capabilities st)))
      (is (= "用法说明" (mc/instructions c)))
      (is (= {"name" "demo" "version" "9"} (mc/server-info c))
          "serverInfo 从结果的 _meta 里取——modern 没有握手回执可拿")
      (is (= ["server/discover"] (methods-of log))))))

(deftest modern-requests-carry-meta-test
  (testing "连上之后**每条**请求都带版本与能力"
    (let [[tp log] (recording-transport
                    (fn [msg]
                      (ok (get msg "id")
                          (if (= "server/discover" (get msg "method"))
                            (discover-ok [p/version-2026-07])
                            {"tools" []}))))
          c (mc/client {:transport tp})]
      (mc/list-tools c)
      (let [last-msg (last @log)]
        (is (= "tools/list" (get last-msg "method")))
        (is (= p/version-2026-07 (p/protocol-version-of last-msg)))
        (is (map? (get-in last-msg ["params" "_meta" p/meta-client-caps])))
        (is (= {:name "clj-agent-mcp" :version "0.3"}
               (get-in last-msg ["params" "_meta" p/meta-client-info])))))))

(deftest connect-is-idempotent-test
  (let [calls (atom 0)
        c (mc/client {:transport (fn [msg]
                                   (swap! calls inc)
                                   (ok (get msg "id") (discover-ok [p/version-2026-07])))})]
    (mc/connect! c)
    (mc/connect! c)
    (is (= 1 @calls) "连过了就不再探测——时代是 server 的属性，判一次就够")))

;;; ============================================================
;;; 回退到 legacy
;;; ============================================================

(deftest fallback-on-method-not-found-test
  (testing "-32601：老 server 没有 server/discover → 回退握手"
    (let [[tp log] (recording-transport
                    (fn [msg]
                      (case (get msg "method")
                        "server/discover" (err (get msg "id") -32601 "Method not found")
                        "initialize" (ok (get msg "id") init-ok)
                        nil)))
          c (mc/client {:transport tp})
          st (mc/connect! c)]
      (is (= :legacy (:era st)))
      (is (= "2025-11-25" (:protocol-version st)))
      (is (= {"name" "old-demo" "version" "1"} (:server-info st)))
      (is (= ["server/discover" "initialize" "notifications/initialized"] (methods-of log))
          "**那条通知不能省**：讲究的 server 收到它之前会拒掉后续所有请求"))))

(deftest fallback-is-not-keyed-to-one-code-test
  (testing "规范明写：回退判据不得只认一个错误码"
    (doseq [code [-32601 -32602 -32700 -32603 -1]]
      (let [c (mc/client {:transport (fn [msg]
                                       (case (get msg "method")
                                         "server/discover" (err (get msg "id") code "nope")
                                         "initialize" (ok (get msg "id") init-ok)
                                         nil))})]
        (is (= :legacy (mc/era (doto c mc/connect!)))
            (str "错误码 " code " 也该回退"))))))

(deftest fallback-on-http-status-test
  (testing "老 server 在 HTTP 层就拒了（404 = 它要 Mcp-Session-Id）→ 回退"
    (let [c (mc/client {:transport (fn [msg]
                                     (case (get msg "method")
                                       "server/discover"
                                       (throw (t/transport-error "MCP HTTP 404" {:status 404}))
                                       "initialize" (ok (get msg "id") init-ok)
                                       nil))})]
      (is (= :legacy (mc/era (doto c mc/connect!)))))))

(deftest fallback-when-discover-lists-only-legacy-test
  (testing "它懂 discover，但只支持 legacy 版 → 用它报的里最新的那个握手"
    (let [[tp log] (recording-transport
                    (fn [msg]
                      (case (get msg "method")
                        "server/discover" (ok (get msg "id")
                                              (discover-ok ["2025-11-25" "2025-06-18"]))
                        "initialize" (ok (get msg "id") (assoc init-ok "protocolVersion" "2025-11-25"))
                        nil)))
          c (mc/client {:transport tp})]
      (is (= :legacy (mc/era (doto c mc/connect!))))
      (is (= "2025-11-25" (get-in (second @log) ["params" "protocolVersion"]))
          "握手时要的是**双方都支持**的那个版本"))))

;;; ============================================================
;;; -32022：换版重试，而不是回退
;;; ============================================================

(deftest retry-is-capped-test
  (testing "server 反复推荐一个我们要过又被拒的版本 → 重试封顶后退回握手

            今天 `modern-versions` 里只有 2026-07-28 一个版本，所以「换一个 modern
            版重试」这条真路径**还没法构造**；能构造的是它的退化形态——server 拒了
            2026-07-28 却又在 supported 里只报它。没有上限的话这就是一个礼貌的死循环。"
    (let [[tp log] (recording-transport
                    (fn [msg]
                      (if (= p/version-2026-07 (p/protocol-version-of msg))
                        (err (get msg "id") -32022 "Unsupported protocol version"
                             {"supported" ["2026-07-28"] "requested" "2026-07-28"})
                        (ok (get msg "id") (discover-ok [p/version-2026-07])))))
          ;; 第一次按 2026-07-28 被拒、supported 里又只有它——这是个死循环的形状，
          ;; 客户端必须自己封顶（见 connect! 的 :retry 分支）
          c (mc/client {:transport tp})]
      (is (= :legacy (mc/era (doto c mc/connect!)))
          "重试封顶后退回握手，而不是礼貌地转圈")
      (is (<= (count @log) 5))))

  (testing "-32022 只报 legacy 版 → 回退握手，且用它报的版本"
    (let [[tp log] (recording-transport
                    (fn [msg]
                      (case (get msg "method")
                        "server/discover"
                        (err (get msg "id") -32022 "Unsupported protocol version"
                             {"supported" ["2025-06-18"]})
                        "initialize" (ok (get msg "id") (assoc init-ok "protocolVersion" "2025-06-18"))
                        nil)))
          c (mc/client {:transport tp})]
      (is (= :legacy (mc/era (doto c mc/connect!))))
      (is (= "2025-06-18" (get-in (second @log) ["params" "protocolVersion"]))))))

;;; ============================================================
;;; 不回退的两个信号
;;; ============================================================

(deftest sep-2575-errors-do-not-fall-back-test
  (testing "-32021 能力不够：**原样抛**——回退到 initialize 治不好，只会把真错藏起来"
    (let [[tp log] (recording-transport
                    (fn [msg] (err (get msg "id") -32021 "Missing required client capability"
                                   {"requiredCapabilities" ["sampling"]})))
          c (mc/client {:transport tp})]
      (is (thrown? clojure.lang.ExceptionInfo (mc/connect! c)))
      (is (= ["server/discover"] (methods-of log)) "**没有**发 initialize")))

  (testing "-32020 信封头不匹配：同上"
    (let [[tp log] (recording-transport
                    (fn [msg] (err (get msg "id") -32020 "Header mismatch")))
          c (mc/client {:transport tp})]
      (is (thrown? clojure.lang.ExceptionInfo (mc/connect! c)))
      (is (= ["server/discover"] (methods-of log))))))

;;; ============================================================
;;; 钉死版本
;;; ============================================================

(deftest pinned-legacy-version-skips-probe-test
  (testing "钉死一个 legacy 版 = 明说走握手，不必先探测一轮"
    (let [[tp log] (recording-transport
                    (fn [msg] (ok (get msg "id") (assoc init-ok "protocolVersion" "2025-06-18"))))
          c (mc/client {:transport tp :protocol-version "2025-06-18"})]
      (is (= :legacy (mc/era (doto c mc/connect!))))
      (is (= ["initialize" "notifications/initialized"] (methods-of log))))))

;;; ============================================================
;;; 特性方法
;;; ============================================================

(deftest pagination-follows-cursor-test
  (testing "翻页取全部——server 分页时只拿第一页是静默丢工具"
    (let [c (mc/client {:transport
                        (fn [msg]
                          (ok (get msg "id")
                              (case (get msg "method")
                                "server/discover" (discover-ok [p/version-2026-07])
                                "tools/list" (if-let [cur (get-in msg ["params" "cursor"])]
                                               {"tools" [{"name" (str "t-" cur)}]}
                                               {"tools" [{"name" "t-0"}] "nextCursor" "p1"})
                                {})))})]
      (is (= ["t-0" "t-p1"] (mapv #(get % "name") (mc/list-tools c)))))))

(deftest protocol-error-throws-tool-error-does-not-test
  (let [c (mc/client {:transport
                      (fn [msg]
                        (case (get msg "method")
                          "server/discover" (ok (get msg "id") (discover-ok [p/version-2026-07]))
                          "tools/call" (if (= "boom" (get-in msg ["params" "name"]))
                                         (err (get msg "id") -32602 "Unknown tool: boom")
                                         (ok (get msg "id")
                                             {"content" [{"type" "text" "text" "坏了"}]
                                              "isError" true}))
                          (ok (get msg "id") {})))})]
    (testing "协议错误 → 抛，带得出错误码"
      (let [e (try (mc/call-tool c "boom" {}) nil (catch Exception e e))]
        (is (mc/mcp-error? e))
        (is (= -32602 (mc/error-code e)))))

    (testing "工具**自己**失败（isError）→ 不抛，原样返回给调用方判断"
      (let [r (mc/call-tool c "ok" {})]
        (is (true? (get r "isError")))))))

(deftest tools-call-carries-name-for-routing-test
  (testing "tools/call 的 name 要在 params 里——HTTP 传输靠它写 Mcp-Name 头"
    (let [[tp log] (recording-transport
                    (fn [msg] (ok (get msg "id")
                                  (if (= "server/discover" (get msg "method"))
                                    (discover-ok [p/version-2026-07])
                                    {"content" []}))))
          c (mc/client {:transport tp})]
      (mc/call-tool c "get_weather" {"city" "北京"})
      (is (= "get_weather" (p/routing-name (last @log)))))))
