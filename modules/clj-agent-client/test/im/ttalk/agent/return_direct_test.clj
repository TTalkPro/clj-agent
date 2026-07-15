(ns im.ttalk.agent.return-direct-test
  "ToolCallingAdvisor 缺口补齐：return-direct + 可插拔续跑判据

   对标 Spring AI 2.0 ToolCallingAdvisor 的 return direct 与
   ToolExecutionEligibilityChecker。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.kernel :as kernel]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.tool :refer [deftool]]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.advisor.memory :as ma]
            [im.ttalk.agent.react :as react]))

(deftool handoff
  "转人工客服"
  [[reason :string "转接原因"]]
  {:return-direct true}
  (str "已转人工：" reason))

(deftool escalate
  "升级工单"
  [[level :string "级别"]]
  {:return-direct true}
  (str "已升级到 " level))

(deftool normal-tool
  "普通工具"
  [[x :string "输入"]]
  (str "处理了 " x))

(defn- build [tools svc store & {:keys [eligibility-fn]}]
  (kernel/build-kernel
    (cond-> {:service svc
             :tools (vec tools)
             :filters (if store [(ma/memory-filter store)] [])}
      eligibility-fn (assoc :eligibility-fn eligibility-fn))))

;;; ============================================================
;;; return-direct
;;; ============================================================

(deftest return-direct-short-circuits-llm-test
  (let [calls (atom 0)
        svc {:chat-fn (fn [_ _]
                        (swap! calls inc)
                        (response/make-response
                          :text nil
                          :tool-calls [{:id "h1" :name "handoff"
                                        :args {:reason "退款纠纷"}}]))}
        store (memory/in-memory-store)
        k (build [#'handoff] svc store)
        result (react/invoke k store
                             [{:role :user :content "我要退款"}]
                             {:context (ctx/create)})]

    (testing "工具结果即最终答案"
      (is (= :completed (:status result)))
      (is (= "已转人工：退款纠纷" (response/response-text (:response result))))
      (is (true? (:return-direct result))))

    (testing "LLM 只被调用一次——结果不回灌（若回灌 chat-fn 会再返回 tool-call 而死循环）"
      (is (= 1 @calls)))

    (testing ":direct-messages 是内部键，不外泄给调用方"
      (is (not (contains? result :direct-messages))))))

(deftest return-direct-persists-transcript-test
  (testing "工具结果补落库 → 历史无悬空 tool_use（正常路径靠下一次 invoke-chat 落库，这里没有下一次）"
    (let [svc {:chat-fn (fn [_ _]
                          (response/make-response
                            :text nil
                            :tool-calls [{:id "h1" :name "handoff" :args {:reason "投诉"}}]))}
          store (memory/in-memory-store)
          k (build [#'handoff] svc store)
          cid "conv-direct"]
      (react/invoke k store
                    [{:role :user :content "投诉"}]
                    {:context (ctx/with-conversation-id (ctx/create) cid)})
      (let [history (memory/mem-get store cid)]
        (is (= [:user :assistant :tool] (mapv :role history))
            "完整 transcript：user → assistant(tool_calls) → tool(result)")
        (is (= "已转人工：投诉" (:content (last history))))
        (is (empty? (#'react/dangling-tool-call-ids history))
            "无悬空 tool_use——否则下个 turn 会被 heal 整条摘掉"))

      (testing "下一个 turn 的 heal 不会摘掉任何东西（历史完整）"
        (let [svc2 {:chat-fn (fn [_ _] (response/make-response :text "好的"))}
              k2 (build [#'handoff] svc2 store)]
          (react/invoke k2 store
                        [{:role :user :content "谢谢"}]
                        {:context (ctx/with-conversation-id (ctx/create) cid)})
          (is (= [:user :assistant :tool :user :assistant]
                 (mapv :role (memory/mem-get store cid)))))))))

(deftest return-direct-all-match-semantics-test
  (testing "混批（部分声明 return-direct）→ 照常回灌 LLM（与 Spring 的 allMatch 一致）"
    (let [calls (atom 0)
          svc {:chat-fn (fn [_ _]
                          (if (= 1 (swap! calls inc))
                            (response/make-response
                              :text nil
                              :tool-calls [{:id "h1" :name "handoff" :args {:reason "x"}}
                                           {:id "n1" :name "normal-tool" :args {:x "y"}}])
                            (response/make-response :text "综合回复")))}
          store (memory/in-memory-store)
          k (build [#'handoff #'normal-tool] svc store)
          result (react/invoke k store [{:role :user :content "hi"}] {:context (ctx/create)})]
      (is (= "综合回复" (response/response-text (:response result))))
      (is (nil? (:return-direct result)))
      (is (= 2 @calls) "混批仍回灌 → 第二次 LLM 调用")))

  (testing "整批都是 return-direct（多个）→ 结果按序拼接为最终答案"
    (let [svc {:chat-fn (fn [_ _]
                          (response/make-response
                            :text nil
                            :tool-calls [{:id "h1" :name "handoff" :args {:reason "a"}}
                                         {:id "e1" :name "escalate" :args {:level "P1"}}]))}
          store (memory/in-memory-store)
          k (build [#'handoff #'escalate] svc store)
          result (react/invoke k store [{:role :user :content "hi"}] {:context (ctx/create)})]
      (is (= "已转人工：a\n已升级到 P1" (response/response-text (:response result)))))))

(deftest return-direct-records-tool-calls-test
  (testing "return-direct 的工具照常出现在 :tool-calls-made"
    (let [svc {:chat-fn (fn [_ _]
                          (response/make-response
                            :text nil
                            :tool-calls [{:id "h1" :name "handoff" :args {:reason "z"}}]))}
          store (memory/in-memory-store)
          k (build [#'handoff] svc store)
          result (react/invoke k store [{:role :user :content "hi"}] {:context (ctx/create)})]
      (is (= [:handoff] (mapv :name (:tool-calls-made result)))))))

;;; ============================================================
;;; 可插拔续跑判据（ToolExecutionEligibilityChecker）
;;; ============================================================

(deftest eligibility-fn-test
  (testing "判据返回 false → 带 tool-call 的响应按最终答案收尾，工具不执行"
    (let [executed (atom false)
          svc {:chat-fn (fn [_ _]
                          (response/make-response
                            :text "我本想调工具"
                            :tool-calls [{:id "n1" :name "normal-tool" :args {:x "a"}}]))}
          store (memory/in-memory-store)
          k (build [#'normal-tool] svc store :eligibility-fn (fn [_resp _ctx] false))
          result (react/invoke k store [{:role :user :content "hi"}] {:context (ctx/create)})]
      (is (= :completed (:status result)))
      (is (= "我本想调工具" (response/response-text (:response result))))
      (is (false? @executed))
      (is (empty? (:tool-calls-made result)) "工具一个都没执行")))

  (testing "判据可读 context —— 预算耗尽即停"
    (let [calls (atom 0)
          svc {:chat-fn (fn [_ _]
                          (swap! calls inc)
                          (response/make-response
                            :text "停"
                            :tool-calls [{:id (str "n" @calls) :name "normal-tool" :args {:x "a"}}]))}
          store (memory/in-memory-store)
          k (build [#'normal-tool] svc store
                   :eligibility-fn (fn [_resp ctx] (pos? (ctx/get-var ctx :budget 0))))
          result (react/invoke k store [{:role :user :content "hi"}]
                               {:context (ctx/create {:budget 0})})]
      (is (= :completed (:status result)))
      (is (= 1 @calls))
      (is (empty? (:tool-calls-made result)))))

  (testing "缺省判据（未声明）→ 有 tool-call 就跑（行为不变）"
    (let [calls (atom 0)
          svc {:chat-fn (fn [_ _]
                          (if (= 1 (swap! calls inc))
                            (response/make-response
                              :text nil
                              :tool-calls [{:id "n1" :name "normal-tool" :args {:x "a"}}])
                            (response/make-response :text "完成")))}
          store (memory/in-memory-store)
          k (build [#'normal-tool] svc store)
          result (react/invoke k store [{:role :user :content "hi"}] {:context (ctx/create)})]
      (is (= "完成" (response/response-text (:response result))))
      (is (= [:normal-tool] (mapv :name (:tool-calls-made result)))))))
