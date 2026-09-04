(ns im.ttalk.agent.agui.subagent-test
  "子 agent lane 的接线与协议映射（docs/subagent-event-attribution-design.md S3）。

   端到端：父 run 调委派工具 → 子 agent 在 worker 线程上真跑一轮（mock provider）
   → 它的 token / 收尾带着归属回到父 run 的事件流里。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.agui.codec :as codec]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.agui.subagent :as subagent]
            [im.ttalk.agent.agui.support :as sup]
            [im.ttalk.agent.simple-agent :as agent]
            [im.ttalk.agent.subagent.delegate :as delegate]))

;;; ============================================================
;;; 端到端
;;; ============================================================

(defn- delegating-runtime
  "父 agent 只有一个委派工具；子 agent 用自己的 mock provider 跑一轮。"
  [{:keys [subagent-events? sub-text]}]
  (let [parent-provider (sup/provider
                         [{:text nil :tool-calls [{:id "tc1" :name "deep_research"
                                                   :args {:task "查一下冷暴露"}}]}
                          {:text "已经让子 agent 查过了"}])]
    (rt/runtime
     {:subagent-events? subagent-events?
      :agent-fn
      (fn [{:keys [conversation-id tools subagent-observer]}]
        (agent/create-agent
         {:provider parent-provider
          :memory false
          :conversation-id conversation-id
          :tools (conj (vec tools)
                       (delegate/delegate-tool
                        {:name "deep_research"
                         :subagent-name "research_agent"
                         :subagent-fn (fn [_ _]
                                        {:provider (sup/provider [{:text (or sub-text "三条事实")}]
                                                                 {:chunk-size 2})
                                         :memory false})
                         :observer subagent-observer
                         :timeout 10000}))}))})))

(defn- run-once! [r]
  (let [c (sup/collector)
        unsub (rt/subscribe r "c1" {:on-event (:on-event c)})]
    (rt/start-run! r "c1" "帮我查一下")
    (sup/wait-for #(sup/terminal-event ((:events c))) 15000)
    (unsub)
    ((:events c))))

(deftest lane-events-reach-the-parent-stream-test
  (let [events (run-once! (delegating-runtime {:subagent-events? true :sub-text "三条事实"}))
        types  (mapv :type events)
        lane   (filterv :subagent-run-id events)
        started (first (filter #(= :subagent/started (:type %)) events))]

    (testing "lane 的开场带名字与原始 task"
      (is (some? started))
      (is (= "research_agent" (:name started)))
      (is (= "查一下冷暴露" (:task started)))
      (is (string? (:subagent-run-id started))))

    (testing "子 agent 的 token 回到了父 run 的事件流，且带归属"
      (let [lane-id (:subagent-run-id started)
            lane-text (apply str (keep #(when (and (= :message/delta (:type %))
                                                   (= lane-id (:subagent-run-id %)))
                                          (:text %))
                                       events))]
        (is (= "三条事实" lane-text))
        (is (every? #(= lane-id (:subagent-run-id %)) lane)
            "本轮只有一条 lane，所有带归属的事件都是它的")))

    (testing "父 run 自己的事件不带归属——那是分流的全部依据"
      (let [parent-evs (remove :subagent-run-id events)]
        (is (some #(= :run/started (:type %)) parent-evs))
        (is (some #(= :tool/started (:type %)) parent-evs))
        (is (= "已经让子 agent 查过了"
               (apply str (keep #(when (= :message/delta (:type %)) (:text %)) parent-evs))))))

    (testing "契约：seq 无洞；终态恰好一个且在最后；lane 的收尾不算终态"
      (is (= (range (count events)) (mapv :seq events)))
      (is (= :run/finished (:type (last events))))
      (is (= 1 (count (filter #(#{:run/finished :run/error :run/cancelled :run/paused} (:type %))
                              events))))
      (is (< (.indexOf types :subagent/finished) (.indexOf types :run/finished))
          "lane 先收口，run 才收口——同步委派下工具阻塞着，顺序是结构保证的"))

    (testing "开场在工具调用之后、收尾在工具结果之前"
      (is (< (.indexOf types :tool/started) (.indexOf types :subagent/started)))
      (is (< (.indexOf types :subagent/finished) (.indexOf types :tool/result))))))

(deftest parent-tool-call-id-disambiguates-concurrent-delegations-test
  (testing "同一批里两个委派并发：每条 lane 各自挂回**自己**那张工具卡片

            这正是不能从「开着的工具」里猜的场景——两条都开着，猜必然挂错一条。"
    (let [parent-provider (sup/provider
                           [{:text nil
                             :tool-calls [{:id "tc-a" :name "deep_research" :args {:task "甲"}}
                                          {:id "tc-b" :name "deep_research" :args {:task "乙"}}]}
                            {:text "两路都查完了"}])
          r (rt/runtime
             {:subagent-events? true
              :agent-fn
              (fn [{:keys [conversation-id tools subagent-observer]}]
                (agent/create-agent
                 {:provider parent-provider
                  :memory false
                  :conversation-id conversation-id
                  :tools (conj (vec tools)
                               (delegate/delegate-tool
                                {:name "deep_research"
                                 :subagent-fn (fn [args _]
                                                {:provider (sup/provider
                                                            [{:text (str "答:" (get args :task))}])
                                                 :memory false})
                                 :observer subagent-observer
                                 :timeout 10000}))}))})
          events (run-once! r)
          started (filterv #(= :subagent/started (:type %)) events)
          agui (codec/events->agui events)]

      (is (= 2 (count started)))
      (is (= #{"tc-a" "tc-b"} (set (map :parent-tool-call-id started)))
          "两条 lane 各自记住了发起它的那个 tool-call")
      (is (= 2 (count (set (map :subagent-run-id started)))) "两条独立的 lane")

      (testing "任务与 tool-call 的配对没有错位"
        (is (= {"tc-a" "甲" "tc-b" "乙"}
               (into {} (map (juxt :parent-tool-call-id :task)) started))))

      (testing "协议侧：parentToolCallId 与 TOOL_CALL_START 的 toolCallId 对得上"
        (let [tool-ids (set (keep #(when (= "TOOL_CALL_START" (:type %)) (:toolCallId %)) agui))
              parents  (set (keep :parentToolCallId agui))]
          (is (= #{"tc-a" "tc-b"} tool-ids))
          (is (= tool-ids parents))))

      (testing "两条 lane 的文本各归各的 message，不交错"
        (let [by-lane (group-by :subagent-run-id
                                (filter #(and (:subagent-run-id %)
                                              (= :message/delta (:type %)))
                                        events))]
          (is (= #{"答:甲" "答:乙"}
                 (set (map (fn [[_ evs]] (apply str (map :text evs))) by-lane))))
          (is (every? (fn [[_ evs]] (= 1 (count (set (map :message-id evs))))) by-lane)
              "一条 lane 一个 message-id——共用发射器的话这里就串了"))))))

(deftest switch-off-changes-nothing-test
  (testing "开关关着：一条 lane 事件都没有，也没有任何归属字段"
    (let [events (run-once! (delegating-runtime {:subagent-events? false}))]
      (is (empty? (filter #(#{:subagent/started :subagent/finished :subagent/error} (:type %))
                          events)))
      (is (empty? (filter :subagent-run-id events)))
      (is (= :run/finished (:type (last events))))
      (is (= (range (count events)) (mapv :seq events)))
      (is (some #(= :tool/result (:type %)) events)
          "委派照跑——关的是观察，不是能力"))))

;;; ============================================================
;;; 协议映射
;;; ============================================================

(deftest codec-subagent-events-test
  (testing "SUBAGENT_STARTED：task 落在 description，父 lane 落在 parentSubagentRunId"
    (is (= {:type "SUBAGENT_STARTED" :subagentRunId "sa-2" :name "researcher"
            :description "查商户" :parentSubagentRunId "sa-1"}
           (codec/->agui {:type :subagent/started :subagent-run-id "sa-2"
                          :parent-subagent-run-id "sa-1"
                          :name "researcher" :task "查商户"}))))

  (testing "SUBAGENT_FINISHED：成功走 outcome.success"
    (is (= {:type "SUBAGENT_FINISHED" :subagentRunId "sa-1" :outcome {:type "success"}}
           (codec/->agui {:type :subagent/finished :subagent-run-id "sa-1" :outcome :success}))))

  (testing "killed / timeout 没有协议对应物 → SUBAGENT_ERROR + code，不伪装成成功"
    (is (= {:type "SUBAGENT_ERROR" :subagentRunId "sa-1"
            :message "子 agent 已被终止" :code "killed"}
           (codec/->agui {:type :subagent/finished :subagent-run-id "sa-1" :outcome :killed})))
    (is (= "timeout" (:code (codec/->agui {:type :subagent/finished :subagent-run-id "sa-1"
                                           :outcome :timeout})))))

  (testing "SUBAGENT_ERROR：canonical error 的 class 进 code"
    (is (= {:type "SUBAGENT_ERROR" :subagentRunId "sa-1" :message "boom" :code "provider-error"}
           (codec/->agui {:type :subagent/error :subagent-run-id "sa-1"
                          :error {:class :provider-error :message "boom"}}))))

  (testing "归属字段打在**所有**事件上——老客户端靠 schema 的 passthrough 原样忽略"
    (is (= "sa-1" (:subagentRunId (codec/->agui {:type :message/delta :message-id "m0"
                                                 :text "半句" :subagent-run-id "sa-1"}))))
    (is (= "sa-1" (:subagentRunId (codec/->agui {:type :tool/started :tool-call-id "tc9"
                                                 :name "search" :subagent-run-id "sa-1"}))))
    (is (nil? (:subagentRunId (codec/->agui {:type :message/delta :message-id "m0" :text "父的"})))))

  (testing "思考块那一对也各自带上归属（->agui-events 展开成两条）"
    (let [pair (codec/->agui-events {:type :reasoning/started :message-id "sa-1-m0-reasoning"
                                     :subagent-run-id "sa-1"})]
      (is (= 2 (count pair)))
      (is (every? #(= "sa-1" (:subagentRunId %)) pair)))))

;;; ============================================================
;;; outcome 翻译（manager 的形状 → 事件层的形状）
;;; ============================================================

(deftest subagent-attribution-anchors-test
  (testing "SUBAGENT_STARTED 与 TOOL_CALL_START 锚在同一条 assistant 消息上"
    (let [events (run-once! (delegating-runtime {:subagent-events? true :sub-text "三条事实"}))
          started (first (filter #(= :subagent/started (:type %)) events))
          tool-started (first (filter #(= :tool/started (:type %)) events))
          later-text (first (filter #(and (= :message/started (:type %))
                                          (nil? (:subagent-run-id %)))
                                    events))]
      (is (some? (:parent-message-id tool-started))
          "TOOL_CALL_START 的锚点——前端靠它把工具卡片挂进那条 assistant 消息")
      (is (= (:parent-message-id tool-started) (:parent-message-id started))
          "AG-UI 要求工具调用的归属与其 parentMessageId 的归属一致，两处得是同一条消息")
      (is (not= (:message-id later-text) (:parent-message-id tool-started))
          "锚的是**发起这次调用的那一轮**（模型没说话、直接调工具），
           不是后面那轮总结的文本消息——所以这个 id 上没有 TEXT_MESSAGE_*，
           与上游参考实现（demo-agents/src/openai.ts:81 无条件锚）一致")))

  (testing "SUBAGENT_FINISHED 带上子 agent 的产出"
    (let [events (run-once! (delegating-runtime {:subagent-events? true :sub-text "三条事实"}))
          fin (first (filter #(= :subagent/finished (:type %)) events))]
      (is (some? (:result fin)))
      (is (= {:type "SUBAGENT_FINISHED" :subagentRunId (:subagent-run-id fin)
              :outcome {:type "success"} :result (:result fin)}
             (codec/->agui fin))))))

(deftest finish-of-translates-manager-outcomes-test
  (let [finish-of #'subagent/finish-of]
    (is (= {:outcome :success :result "结果"} (finish-of {:ok "结果"}))
        ":ok 的值就是协议里的 SUBAGENT_FINISHED.result——以前扔掉了")
    (is (= {:outcome :killed}  (finish-of {:error :killed})))
    (is (= {:outcome :timeout} (finish-of {:error :timeout})))
    (is (= :provider-error (get-in (finish-of {:error {:crashed true :message "NPE"}})
                                   [:error :class])))
    (is (re-find #"崩溃" (get-in (finish-of {:error {:crashed true :message "NPE"}})
                                 [:error :message])))
    (is (re-find #"未正常完成" (get-in (finish-of {:error {:status :error :detail {}}})
                                       [:error :message])))))
