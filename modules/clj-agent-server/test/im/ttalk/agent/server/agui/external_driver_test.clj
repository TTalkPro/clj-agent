(ns im.ttalk.agent.server.agui.external-driver-test
  "锦书反馈：自带 run 驱动的 agent 不该被迫塞占位 runtime；routes 要能按 agent 注入 run / stop；
   外部源要能把事件喂进 /connect 的帧环。真 HTTP，零 mock runtime。"
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [im.ttalk.agent.agui.registry :as registry]
            [im.ttalk.agent.server.agui :as agui]
            [im.ttalk.agent.server.agui.host :as host]
            [im.ttalk.agent.server.agui.http :as ahttp]
            [im.ttalk.agent.server.http :as nh]
            [im.ttalk.agent.server.agui.replay :as replay]
            [im.ttalk.agent.server.agui.support :as s :refer [with-agui]]))

(set! *warn-on-reflection* true)

(defn- external-registry []
  (registry/registry [{:id "jinshu" :external? true :description "自带 ChatEngine"}]))

(defn- run-handler [hits]
  (fn [req]
    (swap! hits conj {:path (:uri req)
                      :body (get req ::agui/request-body)})
    (ahttp/json-response 200 {:driver "run" :agent (nh/param req :id)})))

(defn- stop-handler [hits]
  (fn [req]
    (swap! hits conj {:path (:uri req)
                      :thread (or (nh/param req :threadId)
                                  (get req ::agui/external-thread-id))})
    (ahttp/json-response 200 {:driver "stop"})))

(deftest registry-allows-external-entry
  (testing "名录只差元数据，不再要求占位 runtime"
    (let [reg (external-registry)]
      (is (= ["jinshu"] (registry/agent-ids reg)))
      (is (= "自带 ChatEngine" (get-in (registry/info reg) [:agents "jinshu" :description])))
      (is (empty? (registry/runtimes reg)))
      (is (false? (registry/stop! reg "jinshu" "t-1")))
      (registry/shutdown! reg)))
  (testing "external 不能和 runtime 同时给；没标 external 仍装配期抛"
    (is (thrown? clojure.lang.ExceptionInfo
                 (registry/registry [{:id "x" :external? true :runtime {}}])))
    (is (thrown? clojure.lang.ExceptionInfo (registry/registry [{:id "x"}])))))

(deftest injected-run-and-stop-take-over-both-route-shapes
  (let [hits (atom [])
        opts {:agents {"jinshu" {:run (run-handler hits)
                                 :stop (stop-handler hits)}}}]
    (with-agui [c (s/start (external-registry) opts {:prefix "/agui"})]
      (testing "/info 有外部条目"
        (let [j (:json (s/send! c :get "info"))]
          (is (= "自带 ChatEngine" (get-in j [:agents :jinshu :description])))
          (is (= {} (get-in j [:agents :jinshu :capabilities])) "外部驱动不继承内置 runtime 能力位")))
      (testing "字面量路由交出去，req 仍是普通 Ring req"
        (let [r (s/post! c "agent/jinshu/run" "{\"threadId\":\"t\"}")]
          (is (= "run" (:driver (:json r))))))
      (testing "单路由退路也交出去，解析后的 body / threadId 通过 request 扩展传入"
        (let [single (s/post! c ""
                              "{\"method\":\"agent/stop\",\"params\":{\"agentId\":\"jinshu\",\"threadId\":\"t-9\"}}")]
          (is (= "stop" (:driver (:json single))))))
      (is (= [{:path "/agui/agent/jinshu/run" :body "{\"threadId\":\"t\"}"}
              {:path "/agui/" :thread "t-9"}]
             (mapv #(select-keys % [:path :body :thread]) @hits)))
      (is (empty? (host/runs (:host c))) "外部驱动不进内置 drive 表"))))

(deftest connect-source-writes-replay-ring-and-stream
  (let [calls (atom [])
        source (fn [tid after-seq deliver]
                 (swap! calls conj [tid after-seq])
                 (deliver {:type "RUN_STARTED" :threadId tid :runId "r-ext" :seq 1})
                 (deliver {:type "RUN_FINISHED" :threadId tid :runId "r-ext" :seq 2})
                 nil)]
    (with-agui [c (s/start (external-registry)
                           {:agents {"jinshu" {:connect-source source}}}
                           {:prefix "/agui"})]
      (let [{:keys [events resp]} (s/sse! c "agent/jinshu/connect"
                                          "{\"threadId\":\"t-1\",\"runId\":\"r-c\",\"lastSeq\":1}")]
        (is (= 200 (:status resp)))
        (is (= ["RUN_STARTED" "RUN_FINISHED"] (s/types events)))
        (is (= "r-ext" (:runId (first events)))))
      (is (= [["t-1" 1]] @calls) "after-seq 原样给外部源，过滤是它的职责")
      (let [rp (host/replay-of (:host c))]
        (is (= :finished (replay/state rp "t-1")))
        (let [cursor (replay/open-cursor rp "t-1" 0)
              raw (mapv #(String. ^bytes %) (replay/take! rp cursor))]
          (is (= 2 (count raw)))
          (let [evs (s/agui-events (str/join "\n\n" raw))]
            (is (= ["RUN_STARTED" "RUN_FINISHED"] (s/types evs)))))))))

(deftest external-driver-options-validated-at-assembly
  (is (thrown? clojure.lang.ExceptionInfo
               (agui/routes "/agui" (host/host (external-registry))
                            {:agents {"x" {:run "not-fn"}}})))
  (is (thrown? clojure.lang.ExceptionInfo
               (agui/routes "/agui" (host/host (external-registry))
                            {:agents {"x" {:nope (fn [_])}}}))))
