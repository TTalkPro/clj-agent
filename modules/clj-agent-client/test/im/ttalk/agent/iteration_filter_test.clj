(ns im.ttalk.agent.iteration-filter-test
  "Iteration 级 filter 链（filter-chain-design §2.5）——
   每轮一次（含末轮）/ 改写下一轮 delta / 短路成 :completed / 递归重入重跑一轮
   / 重入如实记账（预算 + records）/ max-iterations 对重入仍是硬上限 /
   暂停时 around 只进不出 + resume 不重复进链 / 与 :turn 叠加。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.context :as context]
            [im.ttalk.agent.chat-client :as core]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.filter.memory :as ma]
            [im.ttalk.agent.react :as agent-loop]
            [im.ttalk.agent.tool :refer [deftool]]))

(deftool noop-tool
  "占位工具"
  []
  "ok")

(defn- build-chat-client [cm filters & [opts]]
  (core/build-chat-client (merge {:chat-model cm
                                  :tools [#'noop-tool]
                                  :filters (vec filters)}
                                 opts)))

(defn- n-tool-rounds-cm
  "前 n 次调用返回 tool-call，第 n+1 次返回文本答案。"
  [n calls]
  {:chat-fn (fn [_ _]
              (if (<= (swap! calls inc) n)
                (response/make-response :text nil
                  :tool-calls [{:id (str "t" @calls) :name "noop-tool" :args {}}])
                (response/make-response :text "done" :tool-calls nil)))})

(defn- run [chat-client store cid messages & [opts]]
  (agent-loop/invoke chat-client store messages
    (merge {:context (context/with-conversation-id (context/create) cid)} opts)))

;;; ============================================================
;;; 频次：每轮一次，末轮也算
;;; ============================================================

(deftest iteration-once-per-round-test
  (testing "三轮工具 + 一轮收尾 → iteration 4 次、turn 1 次、chat 4 次"
    (let [iter-hits (atom 0) turn-hits (atom 0) chat-hits (atom 0)
          store (memory/in-memory-store)
          counter {:name :counter
                   :iteration (fn [req chain] (swap! iter-hits inc) (chain req))
                   :turn      (fn [req chain] (swap! turn-hits inc) (chain req))
                   :chat      (fn [req chain] (swap! chat-hits inc) (chain req))}
          chat-client (build-chat-client (n-tool-rounds-cm 3 (atom 0))
                                         [(ma/memory-filter store) counter])
          r (run chat-client store "it-1" [(msg/user "干活")])]
      (is (= :completed (:status r)))
      (is (= 1 @turn-hits))
      (is (= 4 @iter-hits) "每轮一次，收尾那轮也进链")
      (is (= @chat-hits @iter-hits) "iteration 与 chat 同频，差别只在包住的范围")))

  (testing ":index 从 0 起逐轮递增；:remaining 逐轮递减"
    (let [seen (atom [])
          store (memory/in-memory-store)
          probe {:name :probe
                 :iteration (fn [req chain]
                              (swap! seen conj [(:index req) (:remaining req)])
                              (chain req))}
          chat-client (build-chat-client (n-tool-rounds-cm 2 (atom 0))
                                         [(ma/memory-filter store) probe])]
      (run chat-client store "it-2" [(msg/user "干活")] {:max-iterations 10})
      (is (= [[0 10] [1 9] [2 8]] @seen)))))

;;; ============================================================
;;; 改写下一轮 delta —— :chat 做不到的那件事
;;; ============================================================

(deftest iteration-rewrites-next-delta-test
  (testing "filter 改写 :continue 结果的 :messages → 下一轮 LLM 收到改写后的 delta"
    (let [seen (atom [])
          store (memory/in-memory-store)
          cm {:chat-fn (fn [msgs _]
                          (swap! seen conj msgs)
                          (if (= 1 (count @seen))
                            (response/make-response :text nil
                              :tool-calls [{:id "t1" :name "noop-tool" :args {}}])
                            (response/make-response :text "done" :tool-calls nil)))}
          annotate {:name :annotate
                    :iteration (fn [req chain]
                                 (let [r (chain req)]
                                   (if (= :continue (:status r))
                                     (update r :messages conj (msg/system "本轮工具已复核"))
                                     r)))}
          chat-client (build-chat-client cm [(ma/memory-filter store) annotate])
          r (run chat-client store "it-3" [(msg/user "干活")])]
      (is (= :completed (:status r)))
      ;; 第二次 LLM 调用的 messages 是 memory 展开的完整历史，末尾应含注入的那条
      (is (= "本轮工具已复核" (:content (last (second @seen))))
          "filter 对下一轮 delta 的改写经 memory 落库后抵达 provider"))))

;;; ============================================================
;;; 短路：不调 chain
;;; ============================================================

(deftest iteration-short-circuit-test
  (testing "不调 chain 直接返回 :completed → 该轮 LLM 压根不发生"
    (let [calls (atom 0)
          store (memory/in-memory-store)
          cm {:chat-fn (fn [_ _] (swap! calls inc)
                          (response/make-response :text "不该被调到" :tool-calls nil))}
          guard {:name :guard
                 :iteration (fn [_req _chain]
                              {:status :completed
                               :response (response/make-response :text "拦下了" :tool-calls nil)
                               :tool-context (context/create)
                               :tool-calls-made []})}
          chat-client (build-chat-client cm [(ma/memory-filter store) guard])
          r (run chat-client store "it-4" [(msg/user "干活")])]
      (is (= :completed (:status r)))
      (is (= "拦下了" (response/response-text (:response r))))
      (is (= 0 @calls) "短路的那一轮不发 LLM"))))

;;; ============================================================
;;; 递归重入：重跑这一轮，且如实记账
;;; ============================================================

(deftest iteration-reentry-test
  (testing "重入一轮 → 那一轮的 LLM 与工具真的再跑一遍，预算与 records 都如实计入"
    (let [llm-calls (atom 0)
          retried (atom false)
          store (memory/in-memory-store)
          ;; 第 1 次要工具，第 2 次（重入后）也要工具，之后收尾
          cm {:chat-fn (fn [_ _]
                          (let [n (swap! llm-calls inc)]
                            (if (<= n 2)
                              (response/make-response :text nil
                                :tool-calls [{:id (str "t" n) :name "noop-tool" :args {}}])
                              (response/make-response :text "done" :tool-calls nil))))}
          once {:name :retry-once
                :iteration (fn [req chain]
                             (let [r (chain req)]
                               (if (and (= :continue (:status r)) (not @retried))
                                 (do (reset! retried true)
                                     (chain req))          ;; 重跑这一轮
                                 r)))}
          chat-client (build-chat-client cm [(ma/memory-filter store) once])
          r (run chat-client store "it-5" [(msg/user "干活")] {:max-iterations 10})]
      (is (= :completed (:status r)))
      (is (= 3 @llm-calls) "第 0 轮跑了两次（原始 + 重入），加收尾轮")
      (is (= 2 (count (:tool-calls-made r)))
          "工具确实执行了两次 → records 记两条（记发生过什么，不记逻辑上算几轮）")))

  (testing "max-iterations 对 filter 重入仍是硬上限"
    (let [store (memory/in-memory-store)
          ;; LLM 永远要工具；filter 每轮都重入一次 → 预算消耗翻倍
          cm {:chat-fn (fn [_ _]
                          (response/make-response :text nil
                            :tool-calls [{:id "t" :name "noop-tool" :args {}}]))}
          always {:name :always-retry
                  :iteration (fn [req chain]
                               (let [r (chain req)]
                                 (if (= :continue (:status r)) (chain req) r)))}
          chat-client (build-chat-client cm [(ma/memory-filter store) always])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"max-iterations"
            (run chat-client store "it-6" [(msg/user "干活")] {:max-iterations 4}))
          "重入吃掉的预算照样计入，否则无限重入能绕过上限"))))

;;; ============================================================
;;; HITL：暂停时 around 只进不出，resume 不重复进链
;;; ============================================================

(deftest iteration-pause-semantics-test
  (testing "暂停是返回值不是异常 → around 后半段照常执行，filter 看得见 :paused"
    (let [entered (atom 0) seen (atom [])
          store (memory/in-memory-store)
          calls (atom 0)
          cm (n-tool-rounds-cm 1 calls)
          probe {:name :probe
                 :iteration (fn [req chain]
                              (swap! entered inc)
                              (let [r (chain req)]
                                (swap! seen conj (:status r))
                                r))}
          chat-client (build-chat-client cm [(ma/memory-filter store) probe])
          opts {:context (context/with-conversation-id (context/create) "it-7")
                :tool-gate (fn [_] :pause)}
          paused (agent-loop/invoke chat-client store [(msg/user "干活")] opts)]
      (is (= :paused (:status paused)))
      (is (= 1 @entered))
      (is (= [:paused] @seen)
          "结果沿链回流，所以单轮计时/记账在 HITL 暂停时也能正常收尾")
      (let [r (agent-loop/resume chat-client (:loop-state paused) :approved opts)]
        (is (= :completed (:status r)))
        ;; resume 执行的是「暂停那一轮的下半截」（无新 LLM 调用），不进链；
        ;; 续跑的收尾轮是完整一轮，进链一次。
        (is (= 2 @entered) "resume 后只有续跑的完整轮进链，那半批不算一轮")
        (is (= [:paused :completed] @seen))))))

;;; ============================================================
;;; 与 :turn 叠加
;;; ============================================================

(deftest iteration-under-turn-reentry-test
  (testing "turn 重入 → 全新循环、全新预算，iteration 链在新循环里重新计数"
    (let [turn-hits (atom 0) iter-hits (atom 0) indices (atom [])
          store (memory/in-memory-store)
          cm {:chat-fn (fn [_ _] (response/make-response :text "答" :tool-calls nil))}
          retry-turn {:name :retry-turn
                      :turn (fn [req chain]
                              (let [r (chain req)]
                                (if (= 1 (swap! turn-hits inc))
                                  (chain (assoc req :messages [(msg/user "再来")]))
                                  r)))}
          probe {:name :probe
                 :iteration (fn [req chain]
                              (swap! iter-hits inc)
                              (swap! indices conj (:index req))
                              (chain req))}
          chat-client (build-chat-client cm [(ma/memory-filter store) retry-turn probe])
          r (run chat-client store "it-8" [(msg/user "干活")])]
      (is (= :completed (:status r)))
      (is (= 2 @iter-hits) "两次 turn 各跑一轮")
      (is (= [0 0] @indices) ":index 是「本次 run-tool-loop 内的轮序」，turn 重入即归零"))))

;;; ============================================================
;;; 零开销：没挂 iteration filter 时行为不变
;;; ============================================================

(deftest no-iteration-filter-unchanged-test
  (testing "没挂 :iteration filter → 链是 identity，循环行为与加这层之前一致"
    (let [store (memory/in-memory-store)
          chat-client (build-chat-client (n-tool-rounds-cm 2 (atom 0))
                                         [(ma/memory-filter store)])
          r (run chat-client store "it-9" [(msg/user "干活")])]
      (is (= :completed (:status r)))
      (is (= "done" (response/response-text (:response r))))
      (is (= 2 (count (:tool-calls-made r)))))))
