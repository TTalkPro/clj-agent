(ns im.ttalk.agent.turn-filter-test
  "Turn 级 filter 链（设计文档 §14）——
   turn 一次 vs chat 每轮 / 递归校验重试 / paused 透传 / 入口消息改写（RAG 形态）/
   重试耗尽 / validation-turn-filter。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.advisor :as flt]
            [im.ttalk.agent.context :as context]
            [im.ttalk.agent.kernel :as core]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.advisor.memory :as ma]
            [im.ttalk.agent.react :as agent-loop]
            [im.ttalk.agent.tool :refer [deftool]]))

(deftool noop-tool
  "占位工具"
  []
  "ok")

(defn- build-kernel [svc filters & [tools]]
  (core/build-kernel {:service svc
                      :tools (or tools [#'noop-tool])
                      :filters (vec filters)}))

;;; ============================================================
;;; turn 一次 vs chat 每轮
;;; ============================================================

(deftest turn-once-chat-per-round-test
  (testing "三轮工具循环：turn filter 执行 1 次，chat filter 执行 4 次"
    (let [turn-hits (atom 0)
          chat-hits (atom 0)
          calls (atom 0)
          svc {:chat-fn (fn [_ _]
                          (if (<= (swap! calls inc) 3)
                            (response/make-response :text nil
                              :tool-calls [{:id (str "t" @calls) :name "noop-tool" :args {}}])
                            (response/make-response :text "done" :tool-calls nil)))}
          store (memory/in-memory-store)
          counter {:name :counter
                   :turn (fn [req chain] (swap! turn-hits inc) (chain req))
                   :chat (fn [req chain] (swap! chat-hits inc) (chain req))}
          kernel (build-kernel svc [(ma/memory-filter store) counter])
          r (agent-loop/invoke kernel store [(msg/user "干活")]
              {:context (context/with-conversation-id (context/create) "tf-1")})]
      (is (= :completed (:status r)))
      (is (= 1 @turn-hits) "turn 链每 turn 恰好一次")
      (is (= 4 @chat-hits) "chat 链每轮 LLM 调用一次（3 工具轮 + 1 收尾）"))))

;;; ============================================================
;;; 入口消息改写（RAG 形态：每 turn 注入一次）
;;; ============================================================

(deftest turn-rewrites-messages-test
  (testing "turn filter 改写入口消息（RAG 注入形态），只发生一次"
    (let [received (atom nil)
          svc {:chat-fn (fn [msgs _]
                          (reset! received msgs)
                          (response/make-response :text "答" :tool-calls nil))}
          rag {:name :rag
               :turn (fn [req chain]
                       (chain (update req :messages
                                      #(into [(msg/system "背景资料：X 是 42")] %))))}
          store (memory/in-memory-store)
          kernel (build-kernel svc [(ma/memory-filter store) rag])
          r (agent-loop/invoke kernel store [(msg/user "X 是多少？")]
              {:context (context/with-conversation-id (context/create) "tf-2")})]
      (is (= :completed (:status r)))
      (is (= :system (:role (first @received))))
      (is (= "背景资料：X 是 42" (:content (first @received)))))))

;;; ============================================================
;;; 递归校验重试（validation-turn-filter）
;;; ============================================================

(deftest validation-retry-test
  (testing "首答不合格 → 反馈重入 → 二答通过"
    (let [calls (atom 0)
          svc {:chat-fn (fn [msgs _]
                          (swap! calls inc)
                          (if (some #(clojure.string/includes? (str (:content %)) "未通过校验")
                                    msgs)
                            (response/make-response :text "{\"ok\":true}" :tool-calls nil)
                            (response/make-response :text "随便说说" :tool-calls nil)))}
          validate (fn [result]
                     (when-not (clojure.string/starts-with?
                                 (get-in result [:response :text]) "{")
                       "必须输出 JSON"))
          store (memory/in-memory-store)
          kernel (build-kernel svc [(ma/memory-filter store)
                                    (flt/validation-turn-filter validate
                                                                :max-retries 2)])
          r (agent-loop/invoke kernel store [(msg/user "给我 JSON")]
              {:context (context/with-conversation-id (context/create) "tf-3")})]
      (is (= :completed (:status r)))
      (is (= "{\"ok\":true}" (get-in r [:response :text])))
      (is (= 2 @calls) "一次原始 + 一次反馈重入")
      (testing "反馈消息经 memory 进入历史（模型看得到完整上下文）"
        (is (some #(and (= :user (:role %))
                        (clojure.string/includes? (str (:content %)) "未通过校验"))
                  (memory/mem-get store "tf-3")))))))

(deftest validation-retry-exhaustion-test
  (testing "重试耗尽：原样返回最后一次结果"
    (let [calls (atom 0)
          svc {:chat-fn (fn [_ _]
                          (swap! calls inc)
                          (response/make-response :text "永远不合格" :tool-calls nil))}
          store (memory/in-memory-store)
          kernel (build-kernel svc [(ma/memory-filter store)
                                    (flt/validation-turn-filter
                                      (constantly "不行") :max-retries 2)])
          r (agent-loop/invoke kernel store [(msg/user "试试")]
              {:context (context/with-conversation-id (context/create) "tf-4")})]
      (is (= :completed (:status r)))
      (is (= "永远不合格" (get-in r [:response :text])))
      (is (= 3 @calls) "原始 + 2 次重试后停止"))))

;;; ============================================================
;;; :paused 透传（HITL 硬规则）
;;; ============================================================

(deftest paused-passthrough-test
  (testing "校验 filter 遇 :paused 透传，不重入"
    (let [calls (atom 0)
          svc {:chat-fn (fn [_ _]
                          (swap! calls inc)
                          (response/make-response :text nil
                            :tool-calls [{:id "d1" :name "noop-tool" :args {}}]))}
          store (memory/in-memory-store)
          kernel (build-kernel svc [(ma/memory-filter store)
                                    (flt/validation-turn-filter (constantly "不行"))])
          r (agent-loop/invoke kernel store [(msg/user "干活")]
              {:context (context/with-conversation-id (context/create) "tf-5")
               :tool-gate (fn [_] :pause)})]
      (is (= :paused (:status r)) "暂停结果原样穿过 turn 链")
      (is (= 1 @calls) "未发生任何重入"))))

;;; ============================================================
;;; resume 经过 turn 链（边界补齐，§14）
;;; ============================================================

(deftest resume-through-turn-chain-test
  (testing "暂停 → resume 完成的最终答案经过校验 filter，不合格触发反馈重入"
    (let [calls (atom 0)
          svc {:chat-fn (fn [msgs _]
                          (swap! calls inc)
                          (cond
                            ;; 第 1 次：要调工具（gate 将暂停）
                            (= 1 @calls)
                            (response/make-response :text nil
                              :tool-calls [{:id "t1" :name "noop-tool" :args {}}])
                            ;; resume 后第一答：不合格
                            (not-any? #(clojure.string/includes? (str (:content %))
                                                                 "未通过校验")
                                      msgs)
                            (response/make-response :text "随口一答" :tool-calls nil)
                            ;; 反馈重入后：合格
                            :else
                            (response/make-response :text "{\"ok\":1}" :tool-calls nil)))}
          validate (fn [result]
                     (when-not (clojure.string/starts-with?
                                 (get-in result [:response :text]) "{")
                       "必须 JSON"))
          store (memory/in-memory-store)
          kernel (build-kernel svc [(ma/memory-filter store)
                                    (flt/validation-turn-filter validate)])
          opts {:context (context/with-conversation-id (context/create) "tf-6")
                :tool-gate (fn [_] :pause)}
          paused (agent-loop/invoke kernel store [(msg/user "干活")] opts)
          _ (is (= :paused (:status paused)))
          ;; resume：批准 → 工具执行 → "随口一答"（不合格）→ 校验反馈重入 → JSON
          r (agent-loop/resume kernel (:loop-state paused) :approved
              {:context (context/with-conversation-id (context/create) "tf-6")})]
      (is (= :completed (:status r)))
      (is (= "{\"ok\":1}" (get-in r [:response :text]))
          "resume 完成的答案同样被校验并重试")
      (is (= 3 @calls) "暂停轮 + resume 后不合格答 + 反馈重入答"))))

(deftest resume-turn-request-marker-test
  (testing "resume 的 TurnRequest 带 :resume? true（请求侧改写类 filter 据此跳过）"
    (let [seen (atom [])
          probe {:name :probe
                 :turn (fn [req chain]
                         (swap! seen conj (select-keys req [:resume? :messages]))
                         (chain req))}
          calls (atom 0)
          svc {:chat-fn (fn [_ _]
                          (if (= 1 (swap! calls inc))
                            (response/make-response :text nil
                              :tool-calls [{:id "t1" :name "noop-tool" :args {}}])
                            (response/make-response :text "done" :tool-calls nil)))}
          store (memory/in-memory-store)
          kernel (build-kernel svc [(ma/memory-filter store) probe])
          opts {:context (context/with-conversation-id (context/create) "tf-7")
                :tool-gate (fn [_] :pause)}
          paused (agent-loop/invoke kernel store [(msg/user "go")] opts)
          _ (agent-loop/resume kernel (:loop-state paused) :approved
              {:context (context/with-conversation-id (context/create) "tf-7")})]
      (is (= 2 (count @seen)) "invoke 一次 + resume 一次")
      (is (nil? (:resume? (first @seen))) "invoke 的 TurnRequest 无标记")
      (is (true? (:resume? (second @seen))))
      (is (nil? (:messages (second @seen))) "resume 首次进入无入口消息"))))
