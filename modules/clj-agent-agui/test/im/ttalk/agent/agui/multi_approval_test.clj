(ns im.ttalk.agent.agui.multi-approval-test
  "同一轮多条审批（designs/agui-borrow.md §2，tasks/G-02.md）。

   今天之前：模型一轮并发调两个敏感工具，只有第一条变成 interrupt，点一次同意
   两条都执行——第二条用户根本没看见。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.agui.codec :as codec]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.agui.support :as sup]
            [im.ttalk.agent.tool :refer [deftool]]))

(def executed (atom []))

(deftool send-mail
  "发邮件（敏感）"
  [[to :string "收件人"]]
  {:sensitive true}
  (swap! executed conj to)
  (str "已发给 " to))

(defn- two-calls []
  {:text nil :tool-calls [{:id "tcA" :name "send-mail" :args {:to "a@x"}}
                          {:id "tcB" :name "send-mail" :args {:to "b@x"}}]})

(defn- mk []
  (sup/runtime {:provider (sup/provider [(two-calls) {:text "好了"}])
                :tools [#'send-mail]
                :on-pause (fn [_])}))

(defn- pause! [r c]
  (rt/subscribe r "c1" {:on-event (:on-event c)})
  (rt/start-run! r "c1" "给 a 和 b 各发一封")
  (sup/wait-for #(sup/terminal-event ((:events c))))
  (sup/terminal-event ((:events c))))

(deftest multi-pause-test
  (reset! executed [])
  (let [r (mk) c (sup/collector)
        term (pause! r c)]
    (testing "暂停事件带全部被叫停的调用；awaiting 同样"
      (is (= :run/paused (:type term)))
      (is (= ["tcA" "tcB"] (mapv #(get-in % [:tool-call :id]) (:pending-tools term))))
      (is (= 2 (count (:pending-tools (rt/awaiting r "c1")))))
      (is (empty? @executed)))
    (testing "outcome.interrupts 一次发全，每条自己的 id / pendingTool"
      (let [itrs (get-in (codec/->agui term) [:outcome :interrupts])]
        (is (= ["tcA" "tcB"] (mapv :id itrs)))
        (is (= ["tcA" "tcB"] (mapv :toolCallId itrs)))
        (is (= [{:to "a@x"} {:to "b@x"}] (mapv #(get-in % [:metadata :pendingTool :args]) itrs)))
        (is (= ["需要审批: send-mail" "需要审批: send-mail"] (mapv :reason itrs)))))
    (testing "逐条决策：A 改参数批准、B 带理由拒绝 → 只执行 A（且是改过的参数）"
      (let [res (rt/resume-run! r "c1" "approved"
                                {:decisions {"tcA" {:decision :approved :args {:to "a2@x"}}
                                             "tcB" {:decision :rejected :message "b 不要发"}}})]
        (is (= :started (:status res)))
        (is (sup/wait-for #(= :run/finished (:type (last ((:events c))))))))
      (is (= ["a2@x"] @executed)))
    (rt/shutdown! r)))

(deftest incomplete-decisions-test
  (reset! executed [])
  (let [r (mk) c (sup/collector)]
    (pause! r c)
    (testing "绕过路由直接漏答 → :run/error，类 :interrupt-incomplete，wire 码 INTERRUPT_INCOMPLETE"
      (reset! (:atom c) [])
      (rt/resume-run! r "c1" "approved" {:decisions {"tcA" {:decision :approved}}})
      (is (sup/wait-for #(sup/terminal-event ((:events c)))))
      (let [term (sup/terminal-event ((:events c)))]
        (is (= :run/error (:type term)))
        (is (= :interrupt-incomplete (get-in term [:error :class])))
        (is (= ["tcB"] (get-in term [:error :missing])))
        (is (= "INTERRUPT_INCOMPLETE" (:code (codec/->agui term)))))
      (is (empty? @executed) "一条都没执行"))
    (rt/shutdown! r)))

(deftest single-pause-unchanged-test
  (testing "单条暂停：interrupts 与从前逐字相同（一条、reason 照旧）"
    (let [ev {:type :run/paused :run-id "r1" :conversation-id "t1" :seq 1
              :reason "需要审批: x"
              :pending-tool {:name "x" :args {} :tool-call {:id "tc1"}}
              :pending-tools [{:name "x" :args {} :tool-call {:id "tc1"}}]}]
      (is (= [(-> (codec/->agui (dissoc ev :pending-tools)) :outcome :interrupts first)]
             (-> (codec/->agui ev) :outcome :interrupts))))))
