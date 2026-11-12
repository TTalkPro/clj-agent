(ns im.ttalk.agent.stateful-gaps-test
  "观澜接有状态 runtime 的缺口（feedbacks/2026-11-12-chartroom-stateful-agui-gaps.md）：
   ① 审批策略 / 回调的 `:metadata` 进暂停 · ② resume 拒绝 / 取消文案钩子 `:reject-note`
   ③ 暂停快照自带对话（`:loop-state :transcript`），resume 不依赖 memory · ④ `:chat-options` 进每一轮。"
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.approval :as approval]
            [im.ttalk.agent.chat-client :as core]
            [im.ttalk.agent.context :as context]
            [im.ttalk.agent.filter.memory :as ma]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.pause :as pause]
            [im.ttalk.agent.react :as agent-loop]
            [im.ttalk.agent.simple-agent :as agent]
            [im.ttalk.agent.tool :refer [deftool]]))

(deftool gap-echo
  "回显"
  [[v :string "值"]]
  (str "echo: " v))

(defn- scripted
  "第 1 次调用回 tool-call，之后回文本；记下每次收到的消息与选项。"
  [seen]
  (let [n (atom 0)]
    {:chat-fn (fn [msgs opts]
                (swap! seen conj {:msgs (vec msgs) :opts opts})
                (if (= 1 (swap! n inc))
                  (response/make-response :text nil
                    :tool-calls [{:id "g1" :name "gap-echo" :args {:v "x"}}])
                  (response/make-response :text "好了" :tool-calls nil)))}))

(defn- cc [cm store]
  (core/build-chat-client (cond-> {:chat-model cm :tools [#'gap-echo]}
                            store (assoc :filters [(ma/memory-filter store)]))))

(defn- ctx [cid] (context/with-conversation-id (context/create) cid))

;;; ④ ----------------------------------------------------------------

(deftest chat-options-reach-every-round-test
  (let [seen (atom [])
        client (cc (scripted seen) nil)]
    (agent-loop/invoke client nil [(msg/user "hi")]
      {:context (ctx "co-1")
       :chat-options {:thinking-effort :high :temperature 0.2 :tools :ignored}})
    (is (= 2 (count @seen)))
    (doseq [{:keys [opts]} @seen]
      (is (= :high (:thinking-effort opts)) "每一轮都带")
      (is (= 0.2 (:temperature opts)))
      (is (sequential? (:tools opts)) "循环自管的键不让"))))

(deftest simple-agent-chat-options-merge-test
  (let [seen (atom [])
        a (agent/create-agent {:chat-client (cc (scripted seen) nil) :memory false
                               :chat-options {:thinking-enabled true :temperature 0.7}})]
    (agent/chat a "hi" {:chat-options {:temperature 0.1}})
    (is (every? #(= true (get-in % [:opts :thinking-enabled])) @seen) "装配期的在")
    (is (every? #(= 0.1 (get-in % [:opts :temperature])) @seen) "本次逐键覆盖")))

;;; ③ ----------------------------------------------------------------

(deftest no-memory-resume-carries-transcript-test
  (testing "无 memory：暂停快照恒带对话，EDN 往返后 resume 的第一轮看得见用户的问题"
    (let [seen (atom [])
          client (cc (scripted seen) nil)
          r (agent-loop/invoke client nil [(msg/user "问题本身")]
              {:context (ctx "tr-1") :tool-gate (fn [_] :pause)})
          ls (edn/read-string (pr-str (:loop-state r)))]
      (is (= :paused (:status r)))
      (is (= [:user :assistant] (mapv :role (:transcript ls))))
      (let [r2 (agent-loop/resume client ls :approved {:context (ctx "tr-1")})
            msgs (:msgs (last @seen))]
        (is (= :completed (:status r2)))
        (is (= [:user :assistant :tool] (mapv :role msgs)))
        (is (= "问题本身" (:content (first msgs))))))))

(deftest memory-resume-refills-empty-store-test
  (testing "有 memory + :pause-transcript?：store 丢了（重启）也能续跑，历史先回填"
    (let [seen (atom [])
          cm (scripted seen)
          store1 (memory/in-memory-store)
          r (agent-loop/invoke (cc cm store1) store1 [(msg/user "问题本身")]
              {:context (ctx "tr-2") :tool-gate (fn [_] :pause) :pause-transcript? true})
          ls (edn/read-string (pr-str (:loop-state r)))
          store2 (memory/in-memory-store)          ;; 「重启」：空 store
          r2 (agent-loop/resume (cc cm store2) ls :approved {:context (ctx "tr-2")})
          msgs (:msgs (last @seen))]
      (is (= [:user :assistant] (mapv :role (:transcript ls))))
      (is (= :completed (:status r2)))
      (is (= [:user :assistant :tool] (mapv :role msgs)))
      (is (= "问题本身" (:content (first msgs))))
      (is (= [:user :assistant :tool :assistant] (mapv :role (memory/mem-get store2 "tr-2"))))))
  (testing "有 memory 缺省不带（同进程续跑以 store 为准）"
    (let [store (memory/in-memory-store)
          r (agent-loop/invoke (cc (scripted (atom [])) store) store [(msg/user "q")]
              {:context (ctx "tr-3") :tool-gate (fn [_] :pause)})]
      (is (nil? (get-in r [:loop-state :transcript]))))))

(deftest agent-resume-across-restart-without-durable-memory-test
  (testing "create-agent :pause-transcript? + 持久 PauseStore + 非持久 memory：新实例照样续跑"
    (let [seen (atom [])
          cm (scripted seen)
          ps (pause/sqlite-pause-store ":memory:")
          mk #(agent/create-agent {:chat-client (cc cm nil) :memory (memory/in-memory-store)
                                   :pause-store ps :conversation-id "tr-4"
                                   :pause-transcript? true
                                   :callbacks {:on-tool-call (fn [_ _] {:interrupt "问一下"})}})
          r (agent/chat (mk) "问题本身")
          _ (is (= :paused (:status r)))
          r2 (agent/resume (mk) "approved")
          msgs (:msgs (last @seen))]
      (is (= :completed (:status r2)))
      (is (= "问题本身" (:content (first msgs))))
      (is (= [:user :assistant :tool] (mapv :role msgs))))))

;;; ② ----------------------------------------------------------------

(defn- note [{:keys [reason cancelled?]}]
  (cond cancelled? "用户取消了这次调用（resume status=cancelled）"
        reason (str "用户拒绝了这次调用：" reason)
        :else "用户拒绝了这次调用（approved=false）"))

(defn- tool-text [seen] (->> (:msgs (last @seen)) (filter #(= :tool (:role %))) first :content))

(deftest reject-note-test
  (doseq [[label payload expected]
          [["拒绝带理由" {:message "太贵"} "用户拒绝了这次调用：太贵"]
           ["拒绝不带理由" nil "用户拒绝了这次调用（approved=false）"]
           ["取消" {:cancelled? true} "用户取消了这次调用（resume status=cancelled）"]
           ["逐件取消" {:decisions {"g1" {:decision "rejected" :cancelled? true}}}
            "用户取消了这次调用（resume status=cancelled）"]]]
    (testing label
      (let [seen (atom [])
            client (cc (scripted seen) nil)
            r (agent-loop/invoke client nil [(msg/user "q")]
                {:context (ctx "rn") :tool-gate (fn [_] :pause)})]
        (agent-loop/resume client (:loop-state r) :rejected
          {:context (ctx "rn") :payload payload :reject-note note})
        (is (= expected (tool-text seen)))))))

(deftest reject-note-default-unchanged-test
  (let [seen (atom [])
        client (cc (scripted seen) nil)
        r (agent-loop/invoke client nil [(msg/user "q")]
            {:context (ctx "rn2") :tool-gate (fn [_] :pause)})]
    (agent-loop/resume client (:loop-state r) :rejected
      {:context (ctx "rn2") :payload {:message "太贵" :cancelled? true}})
    (is (= "已拒绝执行：太贵" (tool-text seen)) "不配钩子：文案不变")))

(deftest agent-reject-note-test
  (let [seen (atom [])
        a (agent/create-agent {:chat-client (cc (scripted seen) nil) :memory false
                               :reject-note note
                               :callbacks {:on-tool-call (fn [_ _] {:interrupt "问"})}})]
    (agent/chat a "q")
    (agent/resume a "rejected")
    (is (= "用户拒绝了这次调用（approved=false）" (tool-text seen)))))

;;; ① ----------------------------------------------------------------

(deftest pause-carries-host-metadata-test
  (testing "审批策略的 :metadata 进 pending-tool :approval"
    (let [client (cc (scripted (atom [])) nil)
          gate (approval/gate client {:policy (fn [_] {:decision :prompt :metadata {:kind "question"}})
                                      :cache nil})
          r (agent-loop/invoke client nil [(msg/user "q")] {:context (ctx "md-1") :tool-gate gate})]
      (is (= {:kind "question"} (get-in r [:pending-tool :approval :metadata])))))
  (testing ":on-tool-call 回调 {:interrupt .. :metadata ..}"
    (let [a (agent/create-agent {:chat-client (cc (scripted (atom [])) nil) :memory false
                                 :callbacks {:on-tool-call (fn [_ _] {:interrupt "问"
                                                                      :metadata {:kind "question"}})}})
          r (agent/chat a "q")]
      (is (= :paused (:status r)))
      (is (= {:kind "question"} (get-in r [:pending-tool :approval :metadata]))))))
