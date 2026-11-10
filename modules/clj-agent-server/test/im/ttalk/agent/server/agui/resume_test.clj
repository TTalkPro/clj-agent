(ns im.ttalk.agent.server.agui.resume-test
  "interrupt 协议走真 HTTP（C# 那边这一段在 ChatAguiAgent 里测；这里是 clj-agent-agui 的 HITL 经 im.ttalk.agent.server.agui.resume 接上来）：
   前端工具 ⇒ RUN_FINISHED outcome=interrupt；下一次 /run 的 resume[] 续跑；坏答复在起 run 之前就回 4xx。"
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.agui.registry :as registry]
            [im.ttalk.agent.server.agui.support :as s :refer [with-agui]]))

(set! *warn-on-reflection* true)

(def ^:private confirm-tool
  {:name "confirm" :description "问用户要不要继续" :parameters {:type "object" :properties {}}})

(defn- run-body [rid & [extra]]
  (s/body "t-h" rid "帮我做" (merge {:tools [confirm-tool]} extra)))

(deftest frontend-tool-interrupt-and-resume
  (let [p (s/provider [(s/tool-call "tc-1" "confirm" {}) (s/text "好的，继续")])]
    (with-agui [c (s/start (registry/registry [(s/entry "keel" p)]))]
      (let [evs (:events (s/sse! c "agent/keel/run" (run-body "r-1")))
            fin (peek evs)
            iid (get-in fin [:outcome :interrupts 0 :id])]
        (is (= "RUN_FINISHED" (:type fin)))
        (is (= "interrupt" (get-in fin [:outcome :type])) "暂停走 interrupt 协议：收口与告知是同一条")
        (is (string? iid))
        (testing "暂停中又来一句、也没再声明那个前端工具 ⇒ 409（⛔ 不开一条永远等不到事件的流）"
          (let [r (s/post! c "agent/keel/run" (s/body "t-h" "r-x" "别管了"))]
            (is (= 409 (:status r)))
            (is (= "thread_awaiting_resume" (get-in r [:json :error])))))
        (testing "答复对不上 ⇒ 409 interrupt_mismatch；形状不对 ⇒ 400"
          (is (= "interrupt_mismatch" (get-in (s/post! c "agent/keel/run" (run-body "r-2" {:resume [{:interruptId "nope" :status "resolved"}]}))
                                              [:json :error])))
          (let [bad (s/post! c "agent/keel/run" (run-body "r-2" {:resume [{:status "resolved"}]}))]
            (is (= 400 (:status bad)))
            (is (= "interrupt_bad_resume" (get-in bad [:json :error])))))
        (let [evs2 (:events (s/sse! c "agent/keel/run" (run-body "r-2" {:resume [{:interruptId iid :status "resolved" :payload {:result "要"}}]})))]
          (is (= "RUN_STARTED" (:type (first evs2))))
          (is (= "r-2" (:runId (first evs2))))
          (is (= "RUN_FINISHED" (:type (peek evs2))))
          (is (nil? (:outcome (peek evs2))))
          (is (some #(= "好的，继续" (:delta %)) evs2))
          (is (some (fn [msgs] (some #(= "要" (:content %)) msgs)) @(:asked p)) "载荷即工具结果，回到了模型那里"))))))

(deftest resume-without-pause-is-409
  (with-agui [c (s/start (registry/registry [(s/entry "keel" (s/provider []))]))]
    (let [r (s/post! c "agent/keel/run" (s/body "t-z" "r-1" "hi" {:resume [{:interruptId "x" :status "resolved"}]}))]
      (is (= 409 (:status r)))
      (is (= "no_pending_interrupt" (get-in r [:json :error]))))
    (is (= 400 (:status (s/post! c "agent/keel/run" (json/generate-string {:threadId "t" :runId "r" :resume {}}))))
        "resume 不是数组 ⇒ RunAgentInput 校验就回 400")))
