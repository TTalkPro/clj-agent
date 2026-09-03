(ns im.ttalk.agent.agui.hitl-test
  "跨请求 HITL：暂停是 run 的终态，resume 是**新的 run**，订阅跨 run 连续。

   这里也钉住框架侧那唯一一处改动（设计文档 §6.1）：**resume 的续跑必须能流式**。
   改之前 `resume-prep` 构造的 opts 里根本没有 `:on-token`，审批之后那段
   （往往正是最终答案）一个 token 都不会出。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.agui.support :as sup]
            [im.ttalk.agent.agui.tools :as agui-tools]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.pause :as pause]
            [im.ttalk.agent.tool :refer [deftool]]))

(def executed (atom []))

(deftool refund-order
  "给订单退款（敏感操作，需人工批准）"
  [[order-id :string "订单号"]]
  {:sensitive true}
  (swap! executed conj order-id)
  (str "订单 " order-id " 已退款"))

(defn- tool-call [id name args]
  {:text nil :tool-calls [{:id id :name name :args args}]})

(defn- hitl-runtime [responses]
  (sup/runtime {:provider (sup/provider responses {:chunk-size 2})
                :tools [#'refund-order]
                :on-pause (fn [_])}))

(deftest pause-then-resume-across-requests-test
  (reset! executed [])
  (let [r (hitl-runtime [(tool-call "tc1" "refund-order" {:order-id "A-1"})
                         {:text "退款已完成"}])
        c (sup/collector)]
    (rt/subscribe r "c1" {:on-event (:on-event c)})

    (testing "第一段：敏感工具 → :run/paused（终态），工具没执行"
      (let [{run1 :run-id} (rt/start-run! r "c1" "给 A-1 退款")]
        (is (sup/wait-for #(sup/terminal-event ((:events c)))))
        (let [term (sup/terminal-event ((:events c)))]
          (is (= :run/paused (:type term)))
          (is (= "refund-order" (get-in term [:pending-tool :name])))
          (is (empty? @executed)))
        (is (= :awaiting-resume (:state (rt/run-status r "c1"))))
        (is (= run1 (:run-id (rt/awaiting r "c1"))))

        (testing "暂停中再发一句：显式拒绝，不静默丢掉那次审批"
          (let [again (rt/start-run! r "c1" "别管了再说点别的")]
            (is (= :awaiting-resume (:status again)))
            (is (= "refund-order" (get-in again [:pending-tool :name])))))

        (testing "第二段：**另一个线程**凭 conv-id resume（审批按钮是另一个请求）"
          (let [res (deref (future (rt/resume-run! r "c1" "approved")) 5000 ::timeout)]
            (is (= :started (:status res)))
            (is (not= run1 (:run-id res)) "resume 是新的 run")
            (is (sup/wait-for #(= :run/finished (:type (last ((:events c)))))))
            (is (= ["A-1"] @executed) "批准后工具真的执行了")))))

    (testing "订阅跨 run 连续：seq 单调无洞、两个 run 各恰好一个终态"
      (let [evs ((:events c))]
        (is (= (range (count evs)) (map :seq evs)) "会话级 seq，跨 run 不重置")
        (is (= 2 (count (distinct (map :run-id evs)))))
        (is (= [:run/paused :run/finished]
               (mapv :type (filter #(#{:run/paused :run/finished :run/error :run/cancelled} (:type %)) evs))))))

    (testing "§6.1 回归：审批之后那段是**流式**的，不是一次性甩出来"
      (let [evs ((:events c))
            second-run (:run-id (last evs))
            deltas (filter #(and (= :message/delta (:type %)) (= second-run (:run-id %))) evs)]
        (is (>= (count deltas) 2)
            "resume 收不到 :on-token 时这里会是 0/1——正是改之前的样子")
        (is (= "退款已完成" (apply str (map :text deltas))))))

    (testing "暂停时不给 pending 工具编一个假结果"
      (let [evs ((:events c))
            paused-run (:run-id (first evs))
            results (filter #(and (= :tool/result (:type %)) (= paused-run (:run-id %))) evs)]
        (is (empty? results))))

    (rt/shutdown! r)))

(deftest discard-pause-test
  (testing ":discard-pause? 显式丢弃没人答复的暂停——AG-UI 前端工具常常永远不回结果"
    (reset! executed [])
    (let [r (hitl-runtime [(tool-call "tc1" "refund-order" {:order-id "C-3"})
                           {:text "换个话题的回答"}])
          c (sup/collector)]
      (rt/subscribe r "c1" {:on-event (:on-event c)})
      (rt/start-run! r "c1" "给 C-3 退款")
      (is (sup/wait-for #(= :run/paused (:type (last ((:events c)))))))

      (testing "缺省仍然拒绝（人工审批被静默丢掉是隐蔽的语义损失）"
        (is (= :awaiting-resume (:status (rt/start-run! r "c1" "算了说点别的")))))

      (testing "显式丢弃后新 run 正常起跑"
        (let [res (rt/start-run! r "c1" "算了说点别的" {:discard-pause? true})]
          (is (= :started (:status res)))
          (is (sup/wait-for #(= :run/finished (:type (last ((:events c)))))))
          (is (empty? @executed) "被丢弃的那个工具当然没执行")
          (is (nil? (rt/awaiting r "c1")))))
      (rt/shutdown! r))))

(deftest resume-without-pause-test
  (let [r (hitl-runtime [{:text "ok"}])]
    (is (= {:status :not-paused} (rt/resume-run! r "c-none" "approved")))
    (rt/shutdown! r)))

(deftest rejected-resume-test
  (reset! executed [])
  (let [r (hitl-runtime [(tool-call "tc1" "refund-order" {:order-id "B-2"})
                         {:text "已按你的意思取消"}])
        c (sup/collector)]
    (rt/subscribe r "c1" {:on-event (:on-event c)})
    (rt/start-run! r "c1" "给 B-2 退款")
    (sup/wait-for #(= :run/paused (:type (last ((:events c))))))
    (rt/resume-run! r "c1" "rejected" {:message "金额不对"})
    (is (sup/wait-for #(= :run/finished (:type (last ((:events c)))))))
    (is (empty? @executed) "拒绝 = 工具不执行")
    (is (some #(and (= :tool/result (:type %))
                    (re-find #"已拒绝执行" (str (:content %))))
              ((:events c)))
        "拒绝理由作为工具结果回灌给模型")
    (rt/shutdown! r)))

;;; ============================================================
;;; AG-UI 前端工具（§7.2：既有 HITL 词汇的一个用法，零框架改动）
;;; ============================================================

(deftest frontend-tool-roundtrip-test
  (testing "前端 action：模型发调用 → 暂停 → 前端结果经 :reply 回灌 → 续跑"
    (let [r (rt/runtime {:agent-fn (agui-tools/agent-fn
                                    {:provider (sup/provider
                                                [(tool-call "fe-1" "show-dialog" {:title "确认"})
                                                 {:text "用户点了确认"}]
                                                {:chunk-size 3})
                                     :memory (memory/in-memory-store)
                                     :pause-store (pause/in-memory-pause-store)})})
          fe-tool (agui-tools/frontend-tool {:name "show-dialog"
                                             :description "在前端弹个对话框"
                                             :parameters {:type "object"
                                                          :properties {:title {:type "string"}}}})
          c (sup/collector)]
      (is (true? (agui-tools/frontend? fe-tool)))
      (is (nil? (:agui/frontend fe-tool))
          "前端标记走 metadata——那个 map 去掉 :handler 就整个发给模型，不能塞私货")
      (rt/subscribe r "c1" {:on-event (:on-event c)})
      (rt/start-run! r "c1" "弹个框" {:tools [fe-tool]})
      (is (sup/wait-for #(= :run/paused (:type (last ((:events c)))))))
      (is (= "show-dialog" (get-in (rt/awaiting r "c1") [:pending-tool :name])))

      ;; 前端执行完，把结果送回来（AG-UI 的 client-side tool 结果是 JSON 串）
      (rt/resume-run! r "c1" "reply" {:message "{\"clicked\":\"ok\"}"})
      (is (sup/wait-for #(= :run/finished (:type (last ((:events c)))))))
      (is (= "用户点了确认" (:text (last ((:events c)))))
          "terminal-event 取的是**第一个**终态（那是 :run/paused）——跨 run 要看最后一条")
      (is (some #(and (= :tool/result (:type %))
                      (= "{\"clicked\":\"ok\"}" (:content %)))
                ((:events c)))
          "载荷即工具结果——服务端 handler 一次都没跑（跑了会抛）")
      (rt/shutdown! r))))

(deftest frontend-tool-agent-fn-test
  (testing "tools/agent-fn 自动接好 gate：前端工具进 :tools，中断回调进 :callbacks"
    (let [af (agui-tools/agent-fn {:provider (sup/provider [{:text "ok"}])})
          fe (agui-tools/frontend-tool {:name "fe-x" :parameters {}})
          a (af {:conversation-id "c1" :tools [fe]})]
      (is (fn? (get-in a [:callbacks :on-tool-call])))
      (is (= {:interrupt :frontend-tool} ((get-in a [:callbacks :on-tool-call]) "fe-x" {})))
      (is (nil? ((get-in a [:callbacks :on-tool-call]) "别的工具" {})))))

  (testing "没有前端工具时不平白挂回调"
    (let [af (agui-tools/agent-fn {:provider (sup/provider [{:text "ok"}])})
          a (af {:conversation-id "c1" :tools []})]
      (is (nil? (get-in a [:callbacks :on-tool-call]))))))
