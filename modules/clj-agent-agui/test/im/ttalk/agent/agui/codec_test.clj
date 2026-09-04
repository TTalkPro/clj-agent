(ns im.ttalk.agent.agui.codec-test
  "AG-UI 编解码。字段名逐个对过 CopilotKit 的 `middleware-sse-parser.test.ts`
   ——`toolCallName` 不是 `name`、`TOOL_CALL_ARGS.delta` 是 **JSON 字符串**。
   这类地方猜错了前端不报错，只会静默少渲染一块，所以钉死。"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
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
  (testing "暂停走 AG-UI 的 interrupt 协议：RUN_FINISHED + outcome"
    (let [p (codec/->agui (assoc base :type :run/paused
                                 :reason "需要审批"
                                 :pending-tool {:name "refund" :args {:id "A"}
                                                :tool-call {:id "tc9"}}))
          itr (first (get-in p [:outcome :interrupts]))]
      (is (= "RUN_FINISHED" (:type p)))
      (is (= "interrupt" (get-in p [:outcome :type])))
      (is (= "需要审批" (:reason itr)) "reason 必填且是字符串")
      (is (= "tc9" (:toolCallId itr)) "挂回那张工具卡片")
      (is (= "tc9" (:id itr)) "interrupt id 取 tool-call id——resume 凭它对上")
      (is (= "refund" (get-in itr [:metadata :pendingTool :name])))
      (is (= ["approved" "rejected"] (get-in itr [:responseSchema :properties :decision :enum])))))
  (testing "reason 是关键字也要发成字符串（协议要求）"
    (is (= "approval-required"
           (-> (codec/->agui (assoc base :type :run/paused :reason :approval-required))
               (get-in [:outcome :interrupts 0 :reason])))))
  (testing "没有 pending 工具的暂停：id 退回 run-id，不发 toolCallId"
    (let [itr (-> (codec/->agui (assoc base :type :run/paused))
                  (get-in [:outcome :interrupts 0]))]
      (is (= "r1-interrupt" (:id itr)))
      (is (nil? (:toolCallId itr)))
      (is (= "paused" (:reason itr)))))
  (testing "interrupt-id 对暂停事件与 awaiting 返回值同解——路由靠它校验 resume"
    (let [aw {:run-id "r1" :pending-tool {:name "refund" :tool-call {:id "tc9"}}}]
      (is (= "tc9" (codec/interrupt-id aw)))
      (is (= "r1-interrupt" (codec/interrupt-id {:run-id "r1"})))))
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
  (testing ":run/paused 一条就够：RUN_FINISHED 收口，outcome 说清是停不是完"
    (let [evs (codec/->agui-events (assoc base :type :run/paused :reason "需要审批"))]
      (is (= ["RUN_FINISHED"] (mapv :type evs)))
      (is (codec/terminal? (first evs)) "流可以关——少了终态客户端会一直吊着")
      (is (= "interrupt" (get-in (first evs) [:outcome :type]))
          "没有 outcome 的 RUN_FINISHED 在标准客户端眼里 = 正常跑完了")))
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
      (is (= [] (:resume in)) "没带 resume 就是空，不是 nil")
      (is (= {:cart 1} (:state in)))))
  (testing "interrupt 协议的答复在请求体的 resume[] 里"
    (let [in (codec/parse-run-input
              {:threadId "t1" :runId "r2"
               :messages [{:role "user" :content "同意"}]
               :resume [{:interruptId "tc9" :status "resolved"
                         :payload {:decision "approved"}}]})]
      (is (= [{:interruptId "tc9" :status "resolved" :payload {:decision "approved"}}]
             (:resume in))))))

(deftest run-info-test
  (let [info (codec/run-info ["default" "support"] {:descriptions {"support" "客服"}})]
    (testing "agents 是**字典**不是数组（客户端 Object.entries 按 id 建 proxy）"
      (is (map? (:agents info)))
      (is (= #{"default" "support"} (set (keys (:agents info)))))
      (is (= "default" (get-in info [:agents "default" :name])))
      (is (= "客服" (get-in info [:agents "support" :description]))))
    (is (= "sse" (:mode info)))
    (testing "报 interrupt 能力位——标准客户端据此走 resume[]，不然它不知道我们支持"
      (is (true? (get-in info [:agents "default" :capabilities :humanInTheLoop :interrupts])))
      (is (true? (get-in info [:agents "default" :capabilities :humanInTheLoop :approvals]))))
    (testing "不谎报能力位"
      (is (nil? (get-in info [:agents "default" :capabilities :humanInTheLoop :approveWithEdits]))
          "改参数再执行还没实现")
      (is (nil? (:inspectorMetadata info)))
      (is (nil? (:intelligence info)))
      (is (false? (:suggestions info)))
      (is (nil? (:threadEndpoints info))
          "web 层没挂 /threads 就不报这一档——客户端据此降级"))

    (testing "插件能力位缺省全 false，且不发 a2ui 对象"
      (is (false? (:openGenerativeUIEnabled info)))
      (is (false? (:a2uiEnabled info)))
      (is (nil? (:a2ui info))))))

(deftest plugin-capability-flags-test
  (testing "A2UI 开着时**扁平位与对象都发**——客户端读的是
            `a2uiInfo?.enabled ?? a2uiEnabled ?? false`（agent-registry.ts:1351），
            对象是新的真相源，扁平位是老客户端的兼容位"
    (let [info (codec/run-info ["default"] {:a2ui? true})]
      (is (true? (:a2uiEnabled info)))
      (is (= {:enabled true} (:a2ui info)))
      (is (nil? (get-in info [:a2ui :agents]))
          "不带 agents = 对每个 agent 都生效（agent-registry.ts:249）——
           我们的 a2ui 是 runtime 级的 event-transform，本来就没有按 agent 限定")))

  (testing "两个插件位互不影响：装一个不会把另一个也报成 true"
    (is (false? (:a2uiEnabled (codec/run-info ["default"] {:open-generative-ui? true}))))
    (is (false? (:openGenerativeUIEnabled (codec/run-info ["default"] {:a2ui? true}))))
    (let [both (codec/run-info ["default"] {:a2ui? true :open-generative-ui? true})]
      (is (true? (:a2uiEnabled both)))
      (is (true? (:openGenerativeUIEnabled both))))))

(deftest inbound-context-test
  (testing "RunAgentInput.context 被解出来——之前整个丢掉，前端 useAgentContext
            注册的东西从来没进过模型"
    (let [parsed (codec/parse-run-input
                  {:threadId "t1" :runId "r1"
                   :messages [{:id "m1" :role "user" :content "在看哪一页？"}]
                   :context [{:description "当前页面是" :value "/orders/42"}]})]
      (is (= [{:description "当前页面是" :value "/orders/42"}] (:context parsed)))))

  (testing "渲染成 system 段：带出处抬头，一条一行"
    (let [p (codec/context->prompt [{:description "当前页面是" :value "/orders/42"}
                                    {:description "选中的行" :value "3"}])]
      (is (str/includes? p "AG-UI context，非用户输入")
          "不写抬头模型会把页面状态当成用户指令")
      (is (str/includes? p "- 当前页面是 /orders/42"))
      (is (str/includes? p "- 选中的行 3"))))

  (testing "value 非字符串按 JSON 打平——schema 说是 string，但
            useAgentContext 收的是 JsonSerializable，路上谁 stringify 看客户端版本"
    (is (str/includes? (codec/context->prompt [{:description "购物车" :value {:items 2}}])
                       "{\"items\":2}")))

  (testing "没有上下文就是 nil——调用方据此**不传** :extra-system-prompts，
            而不是塞一段空抬头进 system"
    (is (nil? (codec/context->prompt [])))
    (is (nil? (codec/context->prompt nil)))
    (is (nil? (codec/context->prompt [{:description "空的" :value ""}]))
        "值为空的条目没有信息量，整条丢")))

(deftest parent-run-id-test
  (testing "入站解出来"
    (is (= "parent-1" (:parent-run-id (codec/parse-run-input
                                       {:threadId "t1" :runId "r2"
                                        :parentRunId "parent-1"
                                        :messages [{:id "m1" :role "user" :content "hi"}]}))))
    (is (nil? (:parent-run-id (codec/parse-run-input {:threadId "t1" :runId "r2"})))))

  (testing "出站只挂在 RUN_STARTED 上——协议里 RUN_FINISHED / RUN_ERROR 没有这一位"
    (is (= {:type "RUN_STARTED" :threadId "t1" :runId "r1" :parentRunId "parent-1"}
           (codec/->agui (assoc base :type :run/started :parent-run-id "parent-1"))))
    (is (= {:type "RUN_STARTED" :threadId "t1" :runId "r1"}
           (codec/->agui (assoc base :type :run/started)))
        "没有父 run 就不发这个键，而不是发 null")
    (is (nil? (:parentRunId (codec/->agui (assoc base :type :run/finished
                                                 :parent-run-id "parent-1")))))))

(deftest thread-endpoints-flag-test
  (testing "挂了 /threads 才报 :threadEndpoints，且 realtimeMetadata 如实为 false"
    (let [te (:threadEndpoints (codec/run-info ["default"] {:threads? true}))]
      (is (= {:list true :inspect true :mutations true :realtimeMetadata false} te))
      (is (false? (:realtimeMetadata te))
          "/threads/subscribe 我们如实 404，报 true 会让客户端等一条永远不来的流")))

  (testing "缺省不报——不写这个键 = 告诉客户端「没有」，Inspector 的线程面锁着"
    (is (nil? (:threadEndpoints (codec/run-info ["default"]))))
    (is (nil? (:threadEndpoints (codec/run-info ["default"] {:threads? false}))))))

(deftest reasoning-is-a-first-class-message-test
  (testing "思考块走 AG-UI 的 reasoning 消息，不再是 CUSTOM"
    (let [start (codec/->agui-events (assoc base :type :reasoning/started :message-id "m1-reasoning"))
          delta (codec/->agui (assoc base :type :message/thinking
                                     :message-id "m1-reasoning" :text "让我想想"))
          end (codec/->agui-events (assoc base :type :reasoning/ended :message-id "m1-reasoning"))]
      (is (= [{:type "REASONING_START" :messageId "m1-reasoning"}
              {:type "REASONING_MESSAGE_START" :messageId "m1-reasoning" :role "reasoning"}]
             start)
          "开：外层括号 + 那条 reasoning 消息")
      (is (= {:type "REASONING_MESSAGE_CONTENT" :messageId "m1-reasoning" :delta "让我想想"}
             delta))
      (is (= [{:type "REASONING_MESSAGE_END" :messageId "m1-reasoning"}
              {:type "REASONING_END" :messageId "m1-reasoning"}]
             end)
          "合：与开对称")
      (is (not-any? #(= "CUSTOM" (:type %)) (concat start [delta] end))
          "THINKING_* 在 0.0.59 已 deprecated，CUSTOM 更是绕路")
      (is (not-any? codec/terminal? (concat start [delta] end))))))

(deftest agui-messages-inbound-test
  (testing "入站消息解析（无状态 run 用；/run 的历史仍取服务端权威）"
    (let [out (codec/agui->messages
               [{:role "system" :content "你是助手"}
                {:role "user" :content "北京天气"}
                {:role "assistant" :content "查一下"
                 :toolCalls [{:id "t1" :type "function"
                              :function {:name "get_weather" :arguments "{\"city\":\"北京\"}"}}]}
                {:role "tool" :toolCallId "t1" :content "晴"}
                {:role "assistant" :content "北京晴"}])]
      (is (= [:system :user :assistant :tool :assistant] (mapv :role out)))
      (is (= "t1" (:id (first (:tool-calls (nth out 2))))))
      (is (= {:city "北京"} (:args (first (:tool-calls (nth out 2)))))
          "arguments 是 JSON 串，要解开")
      (is (= "t1" (:tool-call-id (nth out 3))))))

  (testing "认不出的角色整条丢掉，不喂脏数据给模型"
    (is (= [:user] (mapv :role (codec/agui->messages
                                [{:role "user" :content "hi"}
                                 {:role "cpk-extension" :content "?"}])))))

  (testing "与出站互为反面"
    (let [ms [(msg/user "你好") (msg/assistant "在")]]
      (is (= ms (codec/agui->messages (codec/messages->agui ms)))))))

(deftest suggestions-flag-test
  (is (false? (:suggestions (codec/run-info ["default"])))
      "不实现 /suggest 就别报——报了客户端会去打一条 404")
  (is (true? (:suggestions (codec/run-info ["default"] {:suggestions? true})))))

(deftest thread-messages-shape-test
  (testing "线程列表那套 toolCalls 是**扁的**，与事件流那套不是一个形状"
    (let [ms [(msg/user "你好")
              (msg/assistant-tool-calls [(msg/tool-call "tc1" "get_weather" {:city "北京"})] "查一下")
              (msg/tool-result "tc1" "get_weather" "晴")]
          out (codec/messages->thread-messages ms)]
      (is (= ["user" "assistant" "tool"] (mapv :role out)))
      (is (= {:id "tc1" :name "get_weather" :args {:city "北京"}}
             (first (:toolCalls (second out))))
          "扁的 {id,name,args}——事件流那套是 {id,type,function:{name,arguments}}")
      (is (= "tc1" (:toolCallId (nth out 2))))
      (is (nil? (:toolCalls (first out))) "没有工具调用就不发这个键"))))

;;; ============================================================
;;; 多模态：AG-UI InputContent → 中立部件
;;; ============================================================

(deftest agui-content->neutral-test
  (testing "纯文本原样返回 —— 形状一变，每个下游都得先学会拆部件才能收一句「你好」"
    (is (= "你好" (codec/agui-content->neutral "你好")))
    (is (nil? (codec/agui-content->neutral nil))))

  (testing "text 部件"
    (is (= [{:type :text :text "这张图里是什么？"}]
           (codec/agui-content->neutral [{:type "text" :text "这张图里是什么？"}]))))

  (testing "内联 image：source.type=data → :file + :data + media-type + 文件名"
    (is (= [{:type :file :media-type "image/png" :data "AAA" :filename "dot.png"}]
           (codec/agui-content->neutral
            [{:type "image"
              :source {:type "data" :value "AAA" :mimeType "image/png"}
              :metadata {:filename "dot.png"}}]))))

  (testing "远端 url：走 :url 而不是 :data"
    (let [[p] (codec/agui-content->neutral
               [{:type "image" :source {:type "url" :value "https://x/a.png"
                                        :mimeType "image/png"}}])]
      (is (= "https://x/a.png" (:url p)))
      (is (nil? (:data p)))))

  (testing "document / audio / video 在中立层不各立一类，统一 :file + media-type"
    (is (= :file (:type (first (codec/agui-content->neutral
                                [{:type "document"
                                  :source {:type "data" :value "JVBER"
                                           :mimeType "application/pdf"}}])))))
    (is (= "application/pdf"
           (:media-type (first (codec/agui-content->neutral
                                [{:type "document"
                                  :source {:type "data" :value "JVBER"
                                           :mimeType "application/pdf"}}]))))))

  (testing "字符串 key 也认（JSON 没经 keywordize 时）"
    (is (= [{:type :text :text "hi"}]
           (codec/agui-content->neutral [{"type" "text" "text" "hi"}]))))

  (testing "混排：正文 + 图片，顺序不变"
    (let [ps (codec/agui-content->neutral
              [{:type "text" :text "看图"}
               {:type "image" :source {:type "data" :value "AAA" :mimeType "image/png"}}])]
      (is (= [:text :file] (mapv :type ps))))))

(deftest parse-run-input-translates-multimodal-test
  (testing "/run 取最后一条 user 消息 —— 多模态那条要翻成部件，不能 str 成一坨"
    (let [{:keys [message]}
          (codec/parse-run-input
           {:threadId "t1" :runId "r1"
            :messages [{:role "user"
                        :content [{:type "text" :text "这是什么"}
                                  {:type "image"
                                   :source {:type "data" :value "AAA" :mimeType "image/png"}}]}]})]
      (is (vector? message))
      (is (= [:text :file] (mapv :type message)))))

  (testing "纯文本那条不受影响"
    (is (= "你好" (:message (codec/parse-run-input
                             {:threadId "t1" :runId "r1"
                              :messages [{:role "user" :content "你好"}]}))))))

(deftest agui->messages-translates-user-content-test
  (testing "/suggest 那条路同理：user 的部件数组要翻，system / tool 仍是纯文本"
    (let [[u s] (codec/agui->messages
                 [{:role "user" :content [{:type "text" :text "看图"}
                                          {:type "image"
                                           :source {:type "data" :value "AAA"
                                                    :mimeType "image/png"}}]}
                  {:role "system" :content "你是助手"}])]
      (is (= :user (:role u)))
      (is (= [:text :file] (mapv :type (:content u))))
      (is (= "你是助手" (:content s))))))
