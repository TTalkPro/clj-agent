(ns im.ttalk.agent.server.agui.stop-test
  "C# StopTests.cs（A-16）：真 HTTP 上分得清 /stop、顶替、停机、孤儿；agent 的停止钩子可以只掐工作、让流正常 RUN_FINISHED；
   缺省 reject 下「刷新后同线程再 /run」是 409；`ahttp/guard` 让宿主补的路拿到同一套 Origin / CORS。

   映射：C# 的 AguiCancelReason ⇒ clj-agent-agui 的取消原因（:stop / :superseded / :shutdown / :orphan），这里从事件存储里
   读 :run/cancelled 的 :reason（C# 是 agent 在 ct 触发时读 AguiRun.CancelReason）；C# 的 AguiAgent.StopAsync 覆写 ⇒
   注册表条目的 :stop 函数。孤儿那条开了心跳（Jetty 的阻塞输出只在写时发现断连）。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.agui.registry :as registry]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.server.agui.http :as ahttp]
            [im.ttalk.agent.server.agui.support :as s :refer [with-agui]]
            [im.ttalk.agent.server.jetty :as net]
            [im.ttalk.agent.server.http :as nh]
            [im.ttalk.agent.server.support :as t]))

(set! *warn-on-reflection* true)

(defn- recorder
  "C# 的 Recorder：挂起的 agent（先发一个 token，等闸），事件存储记下取消原因。"
  [& [{:keys [id on-concurrent stop] :or {id "slow"}}]]
  (let [gate (promise) ended (promise) rec (s/recording-store)]
    {:gate gate :ended ended :rec rec
     :reg (registry/registry [(s/entry id (s/provider [] {:default (s/gated gate ended)})
                                       {:on-concurrent on-concurrent :event-store (:store rec) :stop stop})])}))

(defn- reasons [r] (s/cancel-reasons (:rec r) "t-1"))

(defn- seen [r] (s/wait-until #(seq (reasons r))) (first (reasons r)))

;; ── ① 原因 ───────────────────────────────────────────

(deftest stop-route-gives-stop
  (let [r (recorder)]
    (with-agui [c (s/start (:reg r) nil {:prefix "/agui"})]
      (let [{:keys [stream]} (s/post-stream! c "agent/slow/run" (s/body))]
        (is (s/wait-until #(s/run-of c "t-1")))
        (let [stop (:json (s/post! c "agent/slow/stop/t-1" ""))]
          (is (true? (:stopped stop)))
          (is (= "STOPPED" (get-in stop [:interrupt :code]))))
        (is (= :stop (seen r)))
        (is (= ["RUN_STARTED" "TEXT_MESSAGE_START"] (take 2 (s/types (s/read-events stream)))))))))

(deftest single-route-stop-gives-stop
  (let [r (recorder)]
    (with-agui [c (s/start (:reg r) nil {:prefix "/agui"})]
      (let [{:keys [stream]} (s/post-stream! c "agent/slow/run" (s/body))]
        (is (s/wait-until #(s/run-of c "t-1")))
        (is (true? (:stopped (:json (s/post! c "" "{\"method\":\"agent/stop\",\"params\":{\"agentId\":\"slow\",\"threadId\":\"t-1\"}}")))))
        (is (= :stop (seen r)))
        (s/read-events stream)))))

(deftest supersede-gives-superseded
  (let [r (recorder {:on-concurrent :supersede})]
    (with-agui [c (s/start (:reg r) nil {:prefix "/agui"})]
      (let [first-run (s/post-stream! c "agent/slow/run" (s/body))]
        (is (s/wait-until #(s/run-of c "t-1")))
        (let [second-run (future (s/post! c "agent/slow/run" (s/body "t-1" "r-2")))]
          (is (= :superseded (seen r)))
          (is (s/wait-until #(= "r-2" (:run-id (s/run-of c "t-1")))))
          (deliver (:gate r) true)
          (is (= "RUN_FINISHED" (:type (peek (s/agui-events (:body (deref second-run 5000 nil))))))))
        (is (s/wait-until #(nil? (s/run-of c "t-1"))))
        (is (= [:superseded] (reasons r)) "新的那台没被取消")
        (s/read-events (:stream first-run))))))

(deftest shutdown-gives-shutdown
  (let [r (recorder)]
    (with-agui [c (s/start (:reg r) nil {:prefix "/agui"})]
      (let [{:keys [stream]} (s/post-stream! c "agent/slow/run" (s/body))
            reading (future (try (s/read-events stream) (catch Exception _ nil)))]
        (is (s/wait-until #(s/run-of c "t-1")))
        (net/stop! (:server c))
        (is (= :shutdown (seen r)))
        (deref reading 5000 nil)))))

(deftest orphan-timer-gives-orphan
  (let [r (recorder)]
    (with-agui [c (s/start (:reg r) {:heartbeat-ms 50 :orphan-cancel-ms 100} {:prefix "/agui"})]
      (s/disconnect! c "agent/slow/run" (s/body))
      (is (= :orphan (seen r))))))

;; ── ② 停止钩子（C# 覆写 StopAsync）───────────────────────

(defn- decoupled
  "工作与流解耦的宿主：/stop 掐掉工作（闸当「已停止」开掉），不按停 run ⇒ 流照常收尾。"
  [gate mode stops]
  (fn [{:keys [runtime thread-id]}]
    (swap! stops inc)
    (case @mode
      :throw (throw (IllegalStateException. "boom"))
      :decline false
      (if (= :running (:state (rt/run-status runtime thread-id)))
        (do (deliver gate true) true)              ; 掐的是工作：这一轮以「已停止」正常收尾
        (boolean (rt/stop! runtime thread-id))))))   ; 没有 run ⇒ 缺省 ⇒ false

(deftest stop-hook-can-stop-work-and-let-stream-finish
  (let [gate (promise) mode (atom :work) stops (atom 0) rec (s/recording-store)
        reg (registry/registry [(s/entry "deco" (s/provider [] {:default (s/gated gate (promise))})
                                         {:event-store (:store rec) :stop (decoupled gate mode stops)})])]
    (with-agui [c (s/start reg nil {:prefix "/agui"})]
      (let [{:keys [stream]} (s/post-stream! c "agent/deco/run" (s/body))]
        (is (s/wait-until #(s/run-of c "t-1")))
        (let [stop (:json (s/post! c "agent/deco/stop/t-1" ""))]
          (is (true? (:stopped stop)))
          (is (= "STOPPED" (get-in stop [:interrupt :code])) "响应体形状不变"))
        (let [evs (s/read-events stream)]
          (is (= ["RUN_STARTED" "TEXT_MESSAGE_START" "TEXT_MESSAGE_CONTENT" "TEXT_MESSAGE_END" "RUN_FINISHED"] (s/types evs)))
          (is (nil? (get-in (peek evs) [:result :status])) "run 没被取消"))
        (is (empty? (s/cancel-reasons rec "t-1")))
        (is (= 1 @stops))
        (is (s/wait-until #(nil? (s/run-of c "t-1"))))
        (is (false? (:stopped (:json (s/post! c "agent/deco/stop/t-1" "")))) "没有 run ⇒ 缺省 ⇒ false")
        (is (= 2 @stops))))))

(deftest stop-hook-decline-and-throw
  (let [gate (promise) mode (atom :decline) stops (atom 0)
        reg (registry/registry [(s/entry "deco" (s/provider [] {:default (s/gated gate (promise))}) {:stop (decoupled gate mode stops)})])
        runtime (:runtime (first (registry/agents reg)))]
    (with-agui [c (s/start reg nil {:prefix "/agui"})]
      (let [{:keys [stream]} (s/post-stream! c "agent/deco/run" (s/body))]
        (is (s/wait-until #(s/run-of c "t-1")))
        (let [declined (:json (s/post! c "agent/deco/stop/t-1" ""))]
          (is (false? (:stopped declined)))
          (is (string? (:message declined))))
        (is (false? (:stopping? (rt/run-status runtime "t-1"))))
        (reset! mode :throw)
        (let [thrown (s/post! c "agent/deco/stop/t-1" "")]
          (is (= 500 (:status thrown)))
          (is (= "stop_failed" (get-in thrown [:json :error])))
          (is (re-find #"boom" (get-in thrown [:json :message]))))
        (is (false? (:stopping? (rt/run-status runtime "t-1"))) "抛了 ⇒ run 不动")
        (deliver gate true)
        (is (= "RUN_FINISHED" (:type (peek (s/read-events stream)))))))))

;; ── ③ 缺省 reject：刷新后同线程再 /run ─────────────────────

(deftest default-reject-refresh-then-rerun-is-409-connect-reattaches
  (let [r (recorder)]
    (with-agui [c (s/start (:reg r) nil {:prefix "/agui"})]
      (s/disconnect! c "agent/slow/run" (s/body))
      (Thread/sleep 100)
      (is (not (realized? (:ended r))) "断连 = 退订，run 照跑")
      (let [rerun (s/post! c "agent/slow/run" (s/body "t-1" "r-2"))]
        (is (= 409 (:status rerun)))
        (is (= "thread_running" (get-in rerun [:json :error]))))
      (let [connect (future (s/post! c "agent/slow/connect" (s/body)))]   ; 正门：/connect 接回去
        (Thread/sleep 100)
        (deliver (:gate r) true)
        (is (= ["RUN_STARTED" "TEXT_MESSAGE_START" "TEXT_MESSAGE_CONTENT" "TEXT_MESSAGE_END" "RUN_FINISHED"]
               (s/types (s/agui-events (:body (deref connect 5000 nil)))))))
      (is (s/wait-until #(nil? (s/run-of c "t-1"))))
      (is (= 200 (:status (s/post! c "agent/slow/run" (s/body "t-1" "r-3")))) "跑完了 ⇒ 能再起"))))

(deftest supersede-refresh-then-rerun-replaces-orphan
  (let [r (recorder {:on-concurrent :supersede})]
    (with-agui [c (s/start (:reg r) nil {:prefix "/agui"})]
      (s/disconnect! c "agent/slow/run" (s/body))
      (let [rerun (future (s/post! c "agent/slow/run" (s/body "t-1" "r-2")))]
        (is (= :superseded (seen r)))
        (is (s/wait-until #(= "r-2" (:run-id (s/run-of c "t-1")))))
        (deliver (:gate r) true)
        (let [resp (deref rerun 5000 nil)]
          (is (= 200 (:status resp)))
          (is (= "RUN_FINISHED" (:type (peek (s/agui-events (:body resp)))))))))))

;; ── ④ ahttp/guard：宿主补的路 ───────────────────────────

(defn- custom-stop
  "字面量段胜过 :id —— 宿主自己的 /stop（登记在 AG-UI 那几条之后）。"
  [hits]
  (fn [h]
    [["POST" "/agui/agent/mine/stop/:threadId"
      (fn [req] (ahttp/guard req h (fn [req]
                                     (swap! hits conj (nh/param req :threadId))
                                     (ahttp/json-response 200 {:stopped true}))))]]))

(deftest guard-on-custom-route-gets-origin-and-cors
  (let [hits (atom [])
        reg (registry/registry [(s/entry "mine" (s/provider []))])]
    (with-agui [c (s/start reg {:origin-allowed (ahttp/origin-allow "http://ok.test") :credentials? true :allow-headers ["X-Host-Token"]}
                           {:prefix "/agui" :extra (custom-stop hits)})]
      (let [ok (s/post! c "agent/mine/stop/t-9" "" :origin "http://ok.test")]
        (is (= 200 (:status ok)))
        (is (= "http://ok.test" (t/header ok "access-control-allow-origin")))
        (is (= "true" (t/header ok "access-control-allow-credentials")))
        (is (re-find #"Origin" (str (t/header ok "vary"))))
        (is (= ["t-9"] @hits) "打到的是宿主那条，不是 :id 那条"))
      (let [bad (s/post! c "agent/mine/stop/t-9" "" :origin "http://evil.test")]
        (is (= 403 (:status bad)))
        (is (= "forbidden_origin" (get-in bad [:json :error])))
        (is (nil? (t/header bad "access-control-allow-origin")))
        (is (= 1 (count @hits)) "被拒的没进 handler"))
      (let [p (s/send! c :options "agent/mine/stop/t-9" :origin "http://ok.test" :headers {"Access-Control-Request-Method" "POST"})]
        (is (= 204 (:status p)))
        (is (re-find #"X-Host-Token" (t/header p "access-control-allow-headers")))
        (is (= "http://ok.test" (t/header p "access-control-allow-origin")))
        (is (= 1 (count @hits)) "预检不进 handler"))
      (is (= 200 (:status (s/post! c "agent/mine/stop/t-8" ""))) "没有 Origin（curl / 服务端）⇒ 放行"))))

(deftest guard-on-custom-route-under-access-guard
  (let [hits (atom [])
        reg (registry/registry [(s/entry "mine" (s/provider []))])
        guard {:token "tk" :loopback-host-only? true :protected #(.startsWith ^String % "/agui")}]
    (with-agui [c (s/start reg {:origin-allowed (ahttp/origin-allow "http://127.0.0.1:3000")}
                           {:prefix "/agui" :extra (custom-stop hits) :server {:guard guard}})]
      (is (= 204 (:status (s/send! c :options "agent/mine/stop/t-1" :origin "http://127.0.0.1:3000"
                                   :headers {"Access-Control-Request-Method" "POST"})))
          "预检豁免 token，guard 答 204")
      (is (= 401 (:status (s/post! c "agent/mine/stop/t-1" "" :origin "http://127.0.0.1:3000"))))
      (is (empty? @hits))
      (let [ok (s/post! c "agent/mine/stop/t-1" "" :origin "http://127.0.0.1:3000" :headers {"Authorization" "Bearer tk"})]
        (is (= 200 (:status ok)))
        (is (= "http://127.0.0.1:3000" (t/header ok "access-control-allow-origin"))))
      (is (= ["t-1"] @hits)))))
