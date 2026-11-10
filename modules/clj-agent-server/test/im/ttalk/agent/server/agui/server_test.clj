(ns im.ttalk.agent.server.agui.server-test
  "C# tests/Keelson.Agui.Server.Tests/ServerTests.cs（keel tests/agui-server.ss + agui-http.ss 的 HTTP 那一半）：真 socket、真 SSE。
   端到端：脚本化 provider + 一把服务端工具跑一次 run，从 HTTP 上收 SSE，对事件序列。

   与 C# 期望值不同的地方（都是 clj-agent-agui 的契约，不是本层的选择）：
   - 被按停 / 顶掉的 run 在流里以 RUN_FINISHED{result:{status:\"cancelled\",reason}} 收口（C# 什么都不再发）；
   - 假 agent 的第一个 token 同时带出 TEXT_MESSAGE_START 与 TEXT_MESSAGE_CONTENT（clj-agent 的消息块由 token 开出来）；
   - OrphanCancel 开了心跳：Jetty 的阻塞输出只在写的时候发现对端走了。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.server.agui.host :as host]
            [im.ttalk.agent.server.agui.http :as ahttp]
            [im.ttalk.agent.server.agui.replay :as replay]
            [im.ttalk.agent.server.strings :refer [msg]]
            [im.ttalk.agent.server.agui.support :as s :refer [with-agui]]
            [im.ttalk.agent.agui.registry :as registry]
            [im.ttalk.agent.agui.thread-store :as ts]
            [im.ttalk.agent.server.jetty :as net]
            [im.ttalk.http.sse]
            [im.ttalk.agent.server.support :as t]
            [im.ttalk.agent.server.agui]
            [ring.core.protocols]))

(set! *warn-on-reflection* true)

(def ^:private done-types ["RUN_STARTED" "TEXT_MESSAGE_START" "TEXT_MESSAGE_CONTENT" "TEXT_MESSAGE_END" "RUN_FINISHED"])

(defn- echo-registry [p & [opts]]
  (registry/registry [(s/entry "keel" p (merge {:tools [#'s/echo] :description "成品"} opts))]))

(defn- gated-registry [& [{:keys [id on-concurrent event-store] :or {id "slow"}}]]
  (let [gate (promise) ended (promise)]
    {:gate gate :ended ended
     :reg (registry/registry [(s/entry id (s/provider [] {:default (s/gated gate ended)})
                                       {:on-concurrent on-concurrent :event-store event-store})])}))

(defn- cancelled? [ev] (= "cancelled" (get-in ev [:result :status])))

;; §1 /info
(deftest info
  (let [reg (registry/registry [(s/entry "keel" (s/provider []) {:tools [#'s/echo] :description "成品"})
                                (s/entry "other" (s/provider []))])]
    (with-agui [c (s/start reg {:version "0.6.3"})]
      (let [r (s/send! c :get "info")
            j (:json r)]
        (is (= 200 (:status r)))
        (is (str/starts-with? (t/header r "content-type") "application/json"))
        (is (= "0.6.3" (:version j)))
        (is (= [:keel :other] (keys (:agents j))))
        (is (true? (get-in j [:agents :keel :capabilities :tools :supported])))
        (is (= "成品" (get-in j [:agents :keel :description])))))))

;; §2 ⭐ 端到端：假模型 + 服务端工具，从 HTTP 收 SSE，对事件序列
(deftest end-to-end-tool-run-over-sse
  (let [p (s/provider [(s/tool-call "tc-1" "echo" {:text "喂"}) (s/text "跑完了")])]
    (with-agui [c (s/start (echo-registry p))]
      (let [{:keys [events raw resp]} (s/sse! c "agent/keel/run" (s/body "t-1" "r-1" "跑一下"))]
        (is (= 200 (:status resp)))
        (is (str/starts-with? (t/header resp "content-type") "text/event-stream"))
        (is (nil? (t/header resp "content-length")) "分块，⛔ 没有 Content-Length")
        (is (= ["RUN_STARTED" "TOOL_CALL_START" "TOOL_CALL_ARGS" "TOOL_CALL_END" "TOOL_CALL_RESULT"
                "TEXT_MESSAGE_START" "TEXT_MESSAGE_CONTENT" "TEXT_MESSAGE_END" "RUN_FINISHED"]
               (s/types events)))
        (is (= "t-1" (:threadId (events 0))))
        (is (= "r-1" (:runId (events 0))) "RUN_STARTED.runId = 输入的 runId")
        (is (= "r-1" (:runId (peek events))))
        (is (= "{\"text\":\"喂\"}" (:delta (events 2))))
        (is (= "tc-1" (:toolCallId (events 4))))
        (is (= "跑完了" (:delta (events 6))))
        (is (str/starts-with? raw "data: {\"type\":\"RUN_STARTED\""))
        (is (every? #(str/starts-with? % "data: ") (remove empty? (str/split raw #"\n\n"))))
        (is (= "跑一下" (first (s/asked-user-texts p))))
        (is (empty? (host/runs (:host c))) "跑完销账")))))

;; §2 驱动补的 END 也在线上（agent 只开了消息、没关）
(deftest dangling-closed-on-the-wire
  (let [p (s/provider [(fn [on-token] (on-token {:token "回声"}) {:text "回声"})])]
    (with-agui [c (s/start (registry/registry [(s/entry "echo" p)]))]
      (let [{:keys [events]} (s/sse! c "agent/echo/run" (s/body "t-1" "r-1" "回声"))]
        (is (= done-types (s/types events)))
        (is (= "回声" (:delta (events 2))))))))

;; §3 两条同时在飞
(deftest two-runs-in-parallel
  (with-agui [c (s/start (echo-registry (s/provider [(s/text "一") (s/text "二")])))]
    (let [a (future (s/sse! c "agent/keel/run" (s/body "t-a" "r-a")))
          b (future (s/sse! c "agent/keel/run" (s/body "t-b" "r-b")))]
      (doseq [r [@a @b]]
        (is (= done-types (s/types (:events r))))))))

;; §4 错误路
(deftest error-routes
  (with-agui [c (s/start (echo-registry (s/provider [])))]
    (let [code (fn [r] [(:status r) (get-in r [:json :error])])]
      (is (= [404 "not_found"] (code (s/send! c :get "nope/x"))))
      (is (= [404 "agent_not_found"] (code (s/post! c "agent/ghost/run" (s/body)))))
      (is (= [405 "method_not_allowed"] (code (s/send! c :get "agent/keel/run"))))
      (let [bad (s/post! c "agent/keel/run" "{oops")]
        (is (str/starts-with? (t/header bad "content-type") "application/json") "普通 JSON 错，不是半条 SSE")
        (is (= [400 "bad_json"] (code bad))))
      (let [missing (s/post! c "agent/keel/run" "{\"runId\":\"r\"}")]
        (is (= [400 "bad_input"] (code missing)))
        (is (str/includes? (:body missing) "threadId")))
      (is (= [400 "bad_json"] (code (s/post! c "agent/keel/run" ""))) "空体")
      (is (= [400 "bad_input"] (code (s/post! c "agent/keel/run" "{\"threadId\":\"t\",\"runId\":\"r\",\"messages\":{}}"))))
      (is (= [400 "bad_input"] (code (s/post! c "agent/keel/run" "{\"threadId\":\"t\",\"runId\":\"r\",\"lastSeq\":-1}"))))
      (is (empty? (host/runs (:host c)))))))

(deftest body-too-large-is-413
  (with-agui [c (s/start (echo-registry (s/provider [])) {:max-body-bytes 64})]
    (let [r (s/post! c "agent/keel/run" (s/body "t-1" "r-1" (apply str (repeat 100 "长"))))]
      (is (= 413 (:status r)))
      (is (= "body_too_large" (get-in r [:json :error]))))))

;; 后缀：挂在任意前缀下都认得出（含根）
(deftest mounted-at-root
  (with-agui [c (s/start (echo-registry (s/provider [(s/text "好")])) nil {:prefix ""})]
    (is (= 200 (:status (s/send! c :get "info"))))
    (is (= "RUN_FINISHED" (:type (peek (:events (s/sse! c "agent/keel/run" (s/body)))))))))

;; §5 CORS
(deftest cors-allow-list
  (with-agui [c (s/start (echo-registry (s/provider [(s/text "好")]))
                         {:origin-allowed (ahttp/origin-allow "http://localhost:3000") :allow-headers ["X-Acme-Token"]})]
    (let [ok (s/post! c "agent/keel/run" (s/body) :origin "http://localhost:3000")]
      (is (= 200 (:status ok)))
      (is (= "http://localhost:3000" (t/header ok "access-control-allow-origin")) "SSE 那条路上也带")
      (is (str/includes? (str (t/header ok "vary")) "Origin"))
      (is (nil? (t/header ok "access-control-allow-credentials"))))
    (let [no (s/post! c "agent/keel/run" (s/body "t-2") :origin "http://evil.example")]
      (is (= 403 (:status no)))
      (is (= "forbidden_origin" (get-in no [:json :error])))
      (is (str/includes? (:body no) "origin-allow") "正文里直接写出该怎么开"))
    (let [p (s/send! c :options "agent/keel/run" :origin "http://localhost:3000")
          allow (t/header p "access-control-allow-headers")]
      (is (= 204 (:status p)))
      (is (str/includes? (t/header p "access-control-allow-methods") "POST"))
      (is (str/includes? allow "Content-Type"))
      (is (str/includes? allow "X-Acme-Token"))
      (is (not (str/includes? allow "*")))
      (is (= "http://localhost:3000" (t/header p "access-control-allow-origin"))))))

(deftest cors-closed-by-default
  (with-agui [c (s/start (echo-registry (s/provider [(s/text "好")])))]
    (is (= 403 (:status (s/post! c "agent/keel/run" (s/body) :origin "http://localhost:3000"))))
    (is (= 200 (:status (s/post! c "agent/keel/run" (s/body)))) "没有 Origin（curl / 服务端）⇒ 放行")
    (let [p (s/send! c :options "info" :origin "http://localhost:3000")]
      (is (= 204 (:status p)) "预检照样 204")
      (is (nil? (t/header p "access-control-allow-origin")) "⛔ 但不给 Allow-Origin"))))

(deftest credentials-header
  (with-agui [c (s/start (echo-registry (s/provider [])) {:origin-allowed (ahttp/origin-any) :credentials? true})]
    (is (= "true" (t/header (s/send! c :get "info" :origin "http://x") "access-control-allow-credentials")))))

;; §6 /stop
(deftest stop-without-run
  (with-agui [c (s/start (echo-registry (s/provider [])))]
    (let [r (:json (s/post! c "agent/keel/stop/t-none" ""))]
      (is (false? (:stopped r)))
      (is (str/includes? (:message r) "t-none")))
    (is (= 404 (:status (s/post! c "agent/ghost/stop/t-1" ""))))))

(deftest stop-running-run
  (let [{:keys [reg ended]} (gated-registry)]
    (with-agui [c (s/start reg)]
      (let [{:keys [stream]} (s/post-stream! c "agent/slow/run" (s/body))
            reading (future (s/read-events stream))]
        (is (s/wait-until #(s/run-of c "t-1")))
        (let [stop (:json (s/post! c "agent/slow/stop/t-1" ""))]
          (is (true? (:stopped stop)))
          (is (= "STOPPED" (get-in stop [:interrupt :code])) "那条 RUN_ERROR 在响应体里"))
        (is (false? (deref ended 5000 ::timeout)) "run 真的停了")
        (let [evs (deref reading 5000 [])]
          (is (= ["RUN_STARTED" "TEXT_MESSAGE_START"] (take 2 (s/types evs))))
          (is (not-any? #(= "RUN_ERROR" (:type %)) evs) "⛔ 流里没有 RUN_ERROR")
          (is (cancelled? (peek evs)) "clj-agent-agui：终态是 RUN_FINISHED status=cancelled")
          (is (= "stop" (get-in (peek evs) [:result :reason]))))
        (is (s/wait-until #(nil? (s/run-of c "t-1"))))))))

;; §6b 心跳
(deftest heartbeat-while-idle
  (with-agui [c (s/start (registry/registry [(s/entry "idle" (s/provider [(s/slow 300)]))]) {:heartbeat-ms 50})]
    (let [{:keys [events raw]} (s/sse! c "agent/idle/run" (s/body))]
      (is (>= (t/count-of raw ": clj-agent-alive\n\n") 2) raw)
      (is (= done-types (s/types events)) "心跳是注释不是事件"))))

;; A-18 心跳帧可配：/run 与 /connect 的流都用宿主给的帧
(deftest heartbeat-frame-is-configurable
  (let [{:keys [reg gate]} (gated-registry)]
    (with-agui [c (s/start reg {:heartbeat-ms 50 :heartbeat-frame #(im.ttalk.http.sse/comment-frame "chartroom-alive")})]
      (let [run (future (:body (s/post! c "agent/slow/run" (s/body))))]
        (is (s/wait-until #(s/run-of c "t-1")))
        (let [{:keys [stream]} (s/post-stream! c "agent/slow/connect" (s/body))
              connect-reading (future (t/read-all stream))]
          (Thread/sleep 200)
          (deliver gate true)
          (doseq [raw [(deref run 5000 "") (deref connect-reading 5000 "")]]
            (is (>= (t/count-of raw ": chartroom-alive\n\n") 2) raw)
            (is (not (str/includes? raw "clj-agent-alive")))))))))

;; §9 /connect：断连 ≠ 按停；重放整轮；跑完之后再 connect 照样整轮
(deftest connect-replays-in-flight-run
  (let [{:keys [reg gate ended]} (gated-registry)]
    (with-agui [c (s/start reg)]
      (is (= "message" (:event-type (s/disconnect! c "agent/slow/run" (s/body)))) "/run 那条连接中途掐掉")
      (let [connect (future (s/sse! c "agent/slow/connect" (s/body)))]
        (Thread/sleep 100)
        (is (not (realized? connect)) "跟着实时尾巴")
        (deliver gate true)
        (let [{:keys [events resp]} (deref connect 5000 nil)]
          (is (= 200 (:status resp)))
          (is (str/starts-with? (t/header resp "content-type") "text/event-stream"))
          (is (= done-types (s/types events)))
          (is (true? (deref ended 5000 ::timeout)) "run 照跑完了")
          (is (= (s/types events) (s/types (:events (s/sse! c "agent/slow/connect" (s/body))))) "keep 内再 connect ⇒ 整轮重放")))
      (is (false? (:stopped (:json (s/post! c "agent/slow/stop/t-1" "")))) "跑完了，销账了"))))

(deftest connect-edge-cases
  (with-agui [c (s/start (echo-registry (s/provider [])))]
    (let [{:keys [events raw resp]} (s/sse! c "agent/keel/connect" (s/body "t-unknown"))]
      (is (= 200 (:status resp)) "未知线程 ⇒ 200 空流（⛔ 不是 404）")
      (is (empty? events))
      (is (= "" raw)))
    (is (= 404 (:status (s/post! c "agent/ghost/connect" (s/body)))))
    (is (= 400 (:status (s/post! c "agent/keel/connect" "nope"))))
    (is (= 405 (:status (s/send! c :get "agent/keel/connect"))))))

;; §10 同线程上一轮还在飞 ⇒ 409；/stop 之后能再起；supersede
(deftest thread-running-is-409
  (let [{:keys [reg]} (gated-registry)]
    (with-agui [c (s/start reg)]
      (let [first-run (s/post-stream! c "agent/slow/run" (s/body))]
        (is (s/wait-until #(s/run-of c "t-1")))
        (let [second-run (s/post! c "agent/slow/run" (s/body "t-1" "r-2"))]
          (is (= 409 (:status second-run)))
          (is (= "thread_running" (get-in second-run [:json :error]))))
        (s/post! c "agent/slow/stop/t-1" "")
        (s/read-events (:stream first-run))
        (let [third (s/post-stream! c "agent/slow/run" (s/body "t-1" "r-3"))]
          (is (= 200 (:status third)))
          (s/post! c "agent/slow/stop/t-1" "")
          (s/read-events (:stream third)))))))

(deftest supersede-cancels-the-old-run
  (let [{:keys [reg gate ended]} (gated-registry {:on-concurrent :supersede})]
    (with-agui [c (s/start reg)]
      (let [first-run (s/post-stream! c "agent/slow/run" (s/body))]
        (is (s/wait-until #(s/run-of c "t-1")))
        (let [second-run (future (s/sse! c "agent/slow/run" (s/body "t-1" "r-2")))]
          (is (false? (deref ended 5000 ::timeout)) "旧的被按停")
          (is (s/wait-until #(= "r-2" (:run-id (s/run-of c "t-1")))))
          (deliver gate true)
          (let [evs (:events (deref second-run 5000 nil))]
            (is (= "r-2" (:runId (first evs))))
            (is (= "RUN_FINISHED" (:type (peek evs))))
            (is (not (cancelled? (peek evs))))))
        (let [old (s/read-events (:stream first-run))]
          (is (= ["RUN_STARTED" "TEXT_MESSAGE_START"] (take 2 (s/types old))))
          (is (= "superseded" (get-in (peek old) [:result :reason]))))))))

;; §11 环装不下：/run 那条照常收完；/connect 空流（⛔ 不发半截）
(deftest truncated-replay
  (with-agui [c (s/start (echo-registry (s/provider [(s/text (apply str (repeat 400 "长")))] {:chunk-size 2}))
                         {:replay-max-bytes 300})]
    (let [{:keys [events]} (s/sse! c "agent/keel/run" (s/body))]
      (is (= "RUN_FINISHED" (:type (peek events))))
      (is (= :truncated (replay/state (host/replay-of (:host c)) "t-1")))
      (is (empty? (:events (s/sse! c "agent/keel/connect" (s/body))))))))

;; A-57 :replay? false：帧环不攒。/run 那条照常收完；/connect 一律 200 空流
(deftest replay-ring-disabled
  (is (true? (:replay? host/default-options)) "缺省档照旧：照攒")
  (with-agui [c (s/start (echo-registry (s/provider [(s/text (apply str (repeat 400 "长")))] {:chunk-size 50}))
                         {:replay? false})]
    (let [{:keys [events resp]} (s/sse! c "agent/keel/run" (s/body))
          rp (host/replay-of (:host c))]
      (is (= 200 (:status resp)))
      (is (= "RUN_FINISHED" (:type (peek events))))
      (is (> (count events) 3))
      (is (false? (replay/enabled? rp)))
      (is (zero? (replay/thread-count rp)) "一轮跑完，环里一条都没建")
      (is (nil? (replay/state rp "t-1")))
      (let [{back :events connect-resp :resp} (s/sse! c "agent/keel/connect" (s/body))]
        (is (= 200 (:status connect-resp)))
        (is (empty? back))))))

;; A-57：带线程存储（记录钩子那一路）也要跟着关掉，且 ⛔ 不打断记账
(deftest replay-ring-disabled-with-thread-store
  (let [reg (echo-registry (s/provider [(s/text "跑完了")]))]
    (with-agui [c (s/start reg {:version "0.6.3" :thread-store (ts/in-memory-store) :replay? false})]
      (is (= "RUN_FINISHED" (:type (peek (:events (s/sse! c "agent/keel/run" (s/body)))))))
      (is (zero? (replay/thread-count (host/replay-of (:host c)))))
      (is (empty? (:events (s/sse! c "agent/keel/connect" (s/body)))))
      (let [recorded ((:events (:thread-store (:host c))) "t-1")]
        (is (= "RUN_STARTED" (:type (first recorded))) "存储那一趟照旧喂到")
        (is (= "RUN_FINISHED" (:type (peek recorded))))))))

;; §11 ⑧ orphan-cancel：没人看够久就按停
(deftest orphan-cancel
  (let [{:keys [reg ended]} (gated-registry)]
    ;; ⚠️ 开了心跳：Jetty 的阻塞输出只在写的时候发现对端走了（C# 这里心跳是关的）
    (with-agui [c (s/start reg {:heartbeat-ms 50 :orphan-cancel-ms 100})]
      (s/disconnect! c "agent/slow/run" (s/body))
      (is (false? (deref ended 5000 ::timeout)))
      (is (s/wait-until #(nil? (s/run-of c "t-1")))))))

;; §12 /suggest：无状态的一枪
(deftest suggest
  (let [{:keys [gate]} {:gate (promise)}
        ended (promise)
        hinter (s/entry "hinter" (s/provider []) {:suggest? true :agent-spec (s/suggest-spec (s/provider [(s/text "要不要看看今天的日志？")]))})
        assistant (s/entry "assistant" (s/provider [] {:default (s/gated gate ended)}))
        both (s/entry "both" (s/provider [] {:default (s/gated gate (promise))})
                      {:suggest? true :agent-spec (s/suggest-spec (s/provider [(s/text "")]))})]
    (with-agui [c (s/start (registry/registry [hinter assistant both]))]
      (let [{:keys [events resp]} (s/sse! c "agent/hinter/suggest" (s/body))]
        (is (= 200 (:status resp)))
        (is (= done-types (s/types events)))
        (is (= "r-1" (:runId (first events)))))
      (is (= 404 (:status (s/post! c "agent/assistant/suggest" (s/body)))) "没给 suggest 的那台 404")
      (is (empty? (host/runs (:host c))) "不进注册表")
      (is (true? (get-in (:json (s/send! c :get "info")) [:suggestions])))

      ;; 真会话那一轮在飞时 suggest 照样走完（⛔ 不是 409、也不顶掉它），之后 /connect 拿到的还是真会话
      (let [run (s/post-stream! c "agent/both/run" (s/body "t-real"))]
        (is (s/wait-until #(s/run-of c "t-real")))
        (let [{sevs :events sresp :resp} (s/sse! c "agent/both/suggest" (s/body "t-real" "r-s"))]
          (is (= 200 (:status sresp)))
          (is (= "RUN_FINISHED" (:type (peek sevs)))))
        (is (some? (s/run-of c "t-real")))
        (deliver gate true)
        (s/read-events (:stream run))
        (let [connected (:events (s/sse! c "agent/both/connect" (s/body "t-real")))]
          (is (= done-types (s/types connected)))
          (is (= "r-1" (:runId (first connected)))))))))

;; §10 ⑩ 单路由退路
(deftest single-route-envelope
  (with-agui [c (s/start (echo-registry (s/provider [(s/text "好")])) {:version "0.6.3"})]
    (is (= "0.6.3" (get-in (s/post! c "" "{\"method\":\"info\"}") [:json :version])))
    (let [env (str "{\"method\":\"agent/run\",\"params\":{\"agentId\":\"keel\"},\"body\":" (s/body) "}")]
      (is (= "RUN_FINISHED" (:type (peek (:events (s/sse! c "" env)))))))
    (let [r (s/post! c "" "{\"method\":\"nope\"}")]
      (is (= 404 (:status r)))
      (is (= "unknown_method" (get-in r [:json :error]))))
    ;; 文案在服务端线程上按缺省语言渲染 ⇒ 拿同一语言的那句来比
    (is (str/includes? (get-in (s/post! c "" "{}") [:json :message]) (:text (msg :agui-server/absent))))
    (is (= 404 (:status (s/post! c "" "not json"))))
    (is (= "not_found" (get-in (s/send! c :get "") [:json :error])) "GET 前缀本身 ⇒ not_found")))

;; §7 停机：在飞的 run 被按停、流收尾、服务端不挂死
(deftest graceful-stop-with-run-in-flight
  (let [{:keys [reg ended]} (gated-registry)]
    (with-agui [c (s/start reg {:heartbeat-ms 50})]
      (let [{:keys [stream]} (s/post-stream! c "agent/slow/run" (s/body))
            reading (future (try (s/read-events stream) (catch Exception _ nil)))]
        (is (s/wait-until #(s/run-of c "t-1")))
        (let [t0 (System/nanoTime)]
          (net/stop! (:server c))
          (is (< (/ (- (System/nanoTime) t0) 1e6) 4000)))
        (is (false? (deref ended 5000 ::timeout)))
        (deref reading 5000 nil)
        (is (s/wait-until #(zero? (host/in-flight (:host c)))))))))

;; Ring：同一个处理器不起服务端也能调（换任何 Ring 服务端都是这个行为）
(deftest handler-is-plain-ring
  (let [reg (echo-registry (s/provider [(s/text "好")]))
        h (im.ttalk.agent.server.agui/handler "/agui" reg {:version "9.9" :heartbeat-ms 0})]
    (try
      (let [r (h {:request-method :get :uri "/agui/info" :headers {}})]
        (is (= 200 (:status r)))
        (is (str/includes? (:body r) "\"version\":\"9.9\"")))
      (is (= 404 (:status (h {:request-method :get :uri "/agui/nope" :headers {}}))))
      (is (= 204 (:status (h {:request-method :options :uri "/agui/info" :headers {"origin" "http://x"}}))))
      (let [r (h {:request-method :post :uri "/agui/agent/keel/run" :headers {}
                  :body (java.io.ByteArrayInputStream. (.getBytes ^String (s/body) "UTF-8"))})
            out (java.io.ByteArrayOutputStream.)]
        (is (= 200 (:status r)))
        (ring.core.protocols/write-body-to-stream (:body r) r out)
        (is (= "RUN_FINISHED" (:type (peek (s/agui-events (.toString out "UTF-8")))))))
      (finally (registry/shutdown! reg)))))
