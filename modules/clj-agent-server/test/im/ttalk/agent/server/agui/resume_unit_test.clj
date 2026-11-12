(ns im.ttalk.agent.server.agui.resume-unit-test
  "续跑判定的纯函数（`resume-decision` / `resume-plan`）。用例原样搬自 examples/copilotkit/http_kit_routes_test.clj
   ——那份路由已改为调用本模块，判定逻辑只剩这一份（resume.clj 本就照搬自它）。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.server.agui.resume :as resume]))

(def ^:private two-pending
  {:run-id "r1"
   :pending-tool {:name "refund-order" :args {:order-id "A"} :tool-call {:id "tcA"}}
   :pending-tools [{:name "refund-order" :args {:order-id "A"} :tool-call {:id "tcA"}}
                   {:name "refund-order" :args {:order-id "B"} :tool-call {:id "tcB"}}]})

(deftest resume-decision-test
  (let [rd resume/resume-decision]
    (testing "cancelled → 拒绝，载荷文本当理由；带 :cancelled? 交给宿主的 :reject-note 分「取消」与「拒绝」"
      (is (= ["rejected" {:cancelled? true}] (rd {:status "cancelled"})))
      (is (= ["rejected" {:message "不要" :cancelled? true}] (rd {:status "cancelled" :payload {:reason "不要"}}))))
    (testing "⚠️ {approved:false} 不带 decision → 拒绝（从前被判成批准）"
      (is (= ["rejected" nil] (rd {:status "resolved" :payload {:approved false}})))
      (is (= ["rejected" nil] (rd {:status "resolved" :payload {:approved "false"}})))
      (is (= ["rejected" {:message "先退款"}]
             (rd {:status "resolved" :payload {:approved false :reason "先退款"}})))
      (is (= ["rejected" nil] (rd {:status "resolved" :payload {:approved false :editedArgs {:x 1}}}))
          "拒绝时 editedArgs 不生效"))
    (testing "批准 + editedArgs → {:args …}（整体替换）"
      (is (= ["approved" {:args {:order-id "B-2"}}]
             (rd {:status "resolved" :payload {:approved true :editedArgs {:order-id "B-2"}}})))
      (is (= ["approved" {:args {:order-id "B-2"}}]
             (rd {:status "resolved" :payload {:decision "approved" :editedArgs {:order-id "B-2"}}})))
      (is (= ["approved" nil] (rd {:status "resolved" :payload {:approved true :editedArgs "坏的"}}))))
    (testing "老前端的 {decision} 照旧"
      (is (= ["approved" nil] (rd {:status "resolved" :payload {:decision "approved"}})))
      (is (= ["rejected" {:message "太贵"}] (rd {:status "resolved" :payload {:decision "rejected" :reason "太贵"}}))))
    (testing "无载荷 = 批准；字符串载荷按 decision-of"
      (is (= ["approved" nil] (rd {:status "resolved"})))
      (is (= ["approved" nil] (rd {:status "resolved" :payload "同意"})))
      (is (= ["rejected" {:message "no"}] (rd {:status "resolved" :payload "no"}))))))

(deftest resume-plan-test
  (let [plan #(resume/resume-plan "t" %1 %2 #{"refund-order"})
        ok {:interruptId "tcA" :status "resolved" :payload {:approved true}}]
    (testing "没带 resume → nil（走原来的推断）"
      (is (nil? (plan nil two-pending)))
      (is (nil? (plan [] two-pending))))
    (testing "形状不对 → 400 interrupt_bad_resume"
      (is (= 400 (first (:error (plan {:x 1} two-pending)))))
      (is (= "interrupt_bad_resume" (second (:error (plan [{:status "resolved"}] two-pending)))))
      (is (= "interrupt_bad_resume" (second (:error (plan [{:interruptId "tcA" :status "weird"}] two-pending)))))
      (is (= "interrupt_bad_resume" (second (:error (plan ["tcA"] two-pending))))))
    (testing "没有暂停 → no_pending_interrupt；全对不上 → interrupt_mismatch"
      (is (= "no_pending_interrupt" (second (:error (plan [ok] nil)))))
      (is (= "interrupt_mismatch" (second (:error (plan [(assoc ok :interruptId "old")] two-pending))))))
    (testing "夹着不认识的 → interrupt_unknown；只答一半 → interrupt_incomplete"
      (let [[st code extra] (:error (plan [ok (assoc ok :interruptId "zzz")] two-pending))]
        (is (= [409 "interrupt_unknown" ["zzz"]] [st code (:unknown extra)])))
      (let [[st code extra] (:error (plan [ok] two-pending))]
        (is (= [409 "interrupt_incomplete" ["tcB"]] [st code (:missing extra)]))))
    (testing "答全 → 逐条决策（editedArgs → :args、reason → :message）"
      (is (= {:decisions {"tcA" {:decision :approved :args {:order-id "A2"}}
                          "tcB" {:decision :rejected :message "不退"}}}
             (plan [{:interruptId "tcA" :status "resolved" :payload {:approved true :editedArgs {:order-id "A2"}}}
                    {:interruptId "tcB" :status "resolved" :payload {:approved false :reason "不退"}}]
                   two-pending))))
    (testing "前端工具那条 → :reply（载荷即结果）"
      (let [p (assoc-in two-pending [:pending-tools 1 :name] "pickColor")]
        (is (= {:decision :reply :message "红"}
               (get-in (plan [ok {:interruptId "tcB" :status "resolved" :payload {:result "红"}}] p)
                       [:decisions "tcB"])))))
    (testing "单条暂停 → {:single entry}（G-01 那条路不变）"
      (is (= {:single ok} (plan [ok] (dissoc two-pending :pending-tools)))))))
