(ns im.ttalk.agent.agui.codec-test
  "AG-UI 编解码。字段名逐个对过 CopilotKit 的 `middleware-sse-parser.test.ts`
   ——`toolCallName` 不是 `name`、`TOOL_CALL_ARGS.delta` 是 **JSON 字符串**。
   这类地方猜错了前端不报错，只会静默少渲染一块，所以钉死。"
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.agui.codec :as codec]
            [im.ttalk.agent.model.message :as msg]))

(def ^:private base {:run-id "r1" :conversation-id "t1" :seq 0 :ts 0})

(deftest run-lifecycle-test
  (is (= {:type "RUN_STARTED" :threadId "t1" :runId "r1"}
         (codec/->agui (assoc base :type :run/started))))
  (is (= {:type "RUN_FINISHED" :threadId "t1" :runId "r1"}
         (codec/->agui (assoc base :type :run/finished))))
  (testing "AG-UI 没有 cancelled：按「结束了，但带原因」发"
    (is (= {:type "RUN_FINISHED" :threadId "t1" :runId "r1" :result {:status "cancelled"}}
           (codec/->agui (assoc base :type :run/cancelled)))))
  (is (= {:type "RUN_ERROR" :message "炸了" :code "provider-error"}
         (codec/->agui (assoc base :type :run/error
                              :error {:class :provider-error :message "炸了"})))))

(deftest text-message-test
  (is (= {:type "TEXT_MESSAGE_START" :messageId "m1" :role "assistant"}
         (codec/->agui (assoc base :type :message/started :message-id "m1"))))
  (is (= {:type "TEXT_MESSAGE_CONTENT" :messageId "m1" :delta "你好"}
         (codec/->agui (assoc base :type :message/delta :message-id "m1" :text "你好"))))
  (is (= {:type "TEXT_MESSAGE_END" :messageId "m1"}
         (codec/->agui (assoc base :type :message/ended :message-id "m1")))))

(deftest tool-call-test
  (let [start (codec/->agui (assoc base :type :tool/started :tool-call-id "tc1" :name "get_weather"))
        args (codec/->agui (assoc base :type :tool/args :tool-call-id "tc1" :args {:city "北京"}))]
    (is (= "TOOL_CALL_START" (:type start)))
    (is (= "get_weather" (:toolCallName start)) "是 toolCallName，不是 name")
    (is (= "TOOL_CALL_ARGS" (:type args)))
    (is (string? (:delta args)) "delta 是 JSON 字符串，不是对象")
    (is (= {"city" "北京"} (json/parse-string (:delta args)))))
  (is (= {:type "TOOL_CALL_END" :toolCallId "tc1"}
         (codec/->agui (assoc base :type :tool/ended :tool-call-id "tc1"))))
  (let [r (codec/->agui (assoc base :type :tool/result :tool-call-id "tc1" :content "晴"))]
    (is (= ["TOOL_CALL_RESULT" "tc1" "tool" "晴"]
           [(:type r) (:toolCallId r) (:role r) (:content r)]))))

(deftest paused-and-state-test
  (let [p (codec/->agui (assoc base :type :run/paused
                               :reason "需要审批"
                               :pending-tool {:name "refund" :args {:id "A"}
                                              :tool-call {:id "tc9"}}))]
    (is (= "CUSTOM" (:type p)))
    (is (= "cljagent.run.paused" (:name p)))
    (is (= "tc9" (get-in p [:value :pendingTool :toolCallId]))))
  (is (= {:type "STATE_SNAPSHOT" :snapshot {:cart 2}}
         (codec/->agui (assoc base :type :state/snapshot :state {:cart 2})))))

(deftest messages-test
  (let [ms [(msg/user "你好")
            (msg/assistant-tool-calls [(msg/tool-call "tc1" "get_weather" {:city "北京"})] "查一下")
            (msg/tool-result "tc1" "get_weather" "晴")
            (msg/assistant "北京晴")]
        out (codec/messages->agui ms)]
    (is (= ["user" "assistant" "tool" "assistant"] (mapv :role out)))
    (is (= 4 (count (distinct (map :id out)))) "合成 id 至少同一次快照内自洽")
    (is (= "tc1" (get-in out [1 :toolCalls 0 :id])))
    (is (= {"city" "北京"} (json/parse-string (get-in out [1 :toolCalls 0 :function :arguments]))))
    (is (= "tc1" (:toolCallId (nth out 2))))))

(deftest resync-becomes-messages-snapshot-test
  (let [ev {:type :run/resync :conversation-id "t1" :seq 9 :ts 0
            :messages [(msg/user "你好") (msg/assistant "在")]}
        out (codec/->agui ev)]
    (is (= "MESSAGES_SNAPSHOT" (:type out)))
    (is (= ["user" "assistant"] (mapv :role (:messages out))))))

(deftest paused-gets-a-terminal-test
  (testing ":run/paused 发两条：CUSTOM 告诉前端停在哪，RUN_FINISHED 把 run 收口"
    (let [evs (codec/->agui-events (assoc base :type :run/paused :reason "需要审批"))]
      (is (= ["CUSTOM" "RUN_FINISHED"] (mapv :type evs)))
      (is (= "paused" (get-in (second evs) [:result :status])))
      (is (codec/terminal? (second evs)))
      (is (not (codec/terminal? (first evs))))))
  (testing "AG-UI 的 run 必须有终态——少了它客户端会一直吊着"
    (is (codec/terminal? (codec/->agui (assoc base :type :run/finished))))
    (is (codec/terminal? (codec/->agui (assoc base :type :run/cancelled))))
    (is (codec/terminal? (codec/->agui (assoc base :type :run/error :error {:message "x"})))))
  (testing "普通事件仍是一对一"
    (is (= 1 (count (codec/->agui-events (assoc base :type :run/started)))))
    (is (= 0 (count (codec/->agui-events (assoc base :type :压根不存在)))))))

(deftest unknown-event-dropped-test
  (is (nil? (codec/->agui (assoc base :type :message/thinking-unknown))))
  (testing "批量转换保持顺序并丢掉没有对应物的"
    (is (= ["RUN_STARTED" "RUN_FINISHED"]
           (mapv :type (codec/events->agui [(assoc base :type :run/started)
                                            (assoc base :type :不存在的类型)
                                            (assoc base :type :run/finished)]))))))

(deftest parse-run-input-test
  (testing "历史取服务端权威：只取客户端最后一条 user 消息"
    (let [in (codec/parse-run-input
              {:threadId "t1" :runId "r1"
               :messages [{:role "user" :content "第一句"}
                          {:role "assistant" :content "回了"}
                          {:role "user" :content "第二句"}]
               :tools [{:name "fe" :parameters {}}]
               :state {:cart 1}})]
      (is (= "t1" (:conversation-id in)))
      (is (= "第二句" (:message in)))
      (is (= 1 (count (:agui-tools in))))
      (is (= {:cart 1} (:state in))))))

(deftest run-info-test
  (let [info (codec/run-info ["default" "support"] {:descriptions {"support" "客服"}})]
    (testing "agents 是**字典**不是数组（客户端 Object.entries 按 id 建 proxy）"
      (is (map? (:agents info)))
      (is (= #{"default" "support"} (set (keys (:agents info)))))
      (is (= "default" (get-in info [:agents "default" :name])))
      (is (= "客服" (get-in info [:agents "support" :description]))))
    (is (= "sse" (:mode info)))
    (testing "不谎报能力位"
      (is (nil? (:inspectorMetadata info)))
      (is (nil? (:intelligence info)))
      (is (false? (:suggestions info))))))
