(ns im.ttalk.agent.pause-test
  "HITL 暂停态持久化（设计文档 §11）——
   loop-state EDN 往返 / PauseStore 双实现 / 跨重启审批与 env-retry 恢复 /
   context 累积恢复（缺口修复回归）/ 清理时机。"
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.client :as agent]
            [im.ttalk.agent.context :as context]
            [im.ttalk.agent.kernel :as core]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.advisor.memory :as ma]
            [im.ttalk.agent.pause :as pause]
            [im.ttalk.agent.react :as agent-loop]
            [im.ttalk.agent.test-support :as ts]
            [im.ttalk.agent.tool :refer [deftool]]))

;;; ============================================================
;;; 测试工具
;;; ============================================================

(deftool note-writer
  "记笔记（写状态槽）"
  [[note :string "内容"]]
  {:result (str "记了: " note)
   :writes {:notes note}})

(deftool note-reader
  "读笔记（只读 context）"
  []
  {:context true}
  (str "notes=" (context/get-var ctx :notes [])))

(def ^:private env-ok
  "模拟环境开关：false=凭证失效" (atom false))

(deftool env-fetch
  "环境依赖的取数工具"
  []
  (if @env-ok
    "数据到手"
    (throw (ex-info "凭证失效" {:error-class :environment}))))

;;; ============================================================
;;; PauseStore 双实现
;;; ============================================================

(deftest pause-store-test
  (doseq [[label store] [["in-memory" (pause/in-memory-pause-store)]
                         ["sqlite" (pause/sqlite-pause-store ":memory:")]]]
    (testing (str label "：存 / 取 / 覆盖 / 清")
      (is (nil? (pause/pause-load store "c1")))
      (pause/pause-save! store "c1" {:version 1 :loop-state {:remaining 3}})
      (is (= {:version 1 :loop-state {:remaining 3}} (pause/pause-load store "c1")))
      (pause/pause-save! store "c1" {:version 1 :loop-state {:remaining 2}})
      (is (= 2 (get-in (pause/pause-load store "c1") [:loop-state :remaining]))
          "同会话再次暂停覆盖")
      (pause/pause-clear! store "c1")
      (is (nil? (pause/pause-load store "c1"))))))

(deftest snapshot-strip-test
  (testing "不可序列化的 context key（如 :kernel）存档时剥离"
    (let [snap (pause/snapshot "c1" {:pause-reason "r"
                                     :loop-state {:remaining 1}
                                     :tool-context {:conversation-id "c1"
                                                    :kernel (fn [] :not-edn)}})]
      (is (= {:conversation-id "c1"} (:tool-context snap)))
      (is (= snap (edn/read-string (pr-str snap))) "快照整体可 EDN 往返"))))

;;; ============================================================
;;; react 层：loop-state EDN 往返后 resume 照常工作
;;; ============================================================

(defn- mock-svc [responses-fn] {:chat-fn (fn [_ _] (responses-fn))})

(deftest loop-state-edn-roundtrip-test
  (testing "审批 phase：loop-state 经 pr-str/read-string 往返后 resume 成功"
    (let [calls (atom 0)
          svc (mock-svc (fn [] (if (= 1 (swap! calls inc))
                                 (response/make-response :text nil
                                   :tool-calls [{:id "d1" :name "mock-get-weather"
                                                 :args {:city "北京"}}])
                                 (response/make-response :text "查完了" :tool-calls nil))))
          store (memory/in-memory-store)
          kernel (core/build-kernel {:service svc :tools [#'ts/mock-get-weather]
                                     :filters [(ma/memory-filter store)]})
          gate (fn [_] :pause)
          r (agent-loop/invoke kernel store [{:role :user :content "查天气"}]
              {:context (context/with-conversation-id (context/create) "rt-1")
               :tool-gate gate})
          _ (is (= :paused (:status r)))
          ls' (edn/read-string (pr-str (:loop-state r)))
          r2 (agent-loop/resume kernel ls' :approved
               {:context (context/with-conversation-id (context/create) "rt-1")})]
      (is (= :completed (:status r2)))
      (is (= "查完了" (get-in r2 [:response :text])))))

  (testing ":env-retry phase：loop-state EDN 往返后 resume :retry 成功"
    (reset! env-ok false)
    (let [calls (atom 0)
          svc (mock-svc (fn [] (if (= 1 (swap! calls inc))
                                 (response/make-response :text nil
                                   :tool-calls [{:id "f1" :name "env-fetch" :args {}}])
                                 (response/make-response :text "到手" :tool-calls nil))))
          store (memory/in-memory-store)
          kernel (core/build-kernel {:service svc :tools [#'env-fetch]
                                     :filters [(ma/memory-filter store)]})
          r (agent-loop/invoke kernel store [{:role :user :content "取数"}]
              {:context (context/with-conversation-id (context/create) "rt-2")
               :on-env-error :pause})
          _ (is (= :paused (:status r)))
          _ (is (= :env-retry (get-in r [:loop-state :phase])))
          ls' (edn/read-string (pr-str (:loop-state r)))
          _ (reset! env-ok true)
          r2 (agent-loop/resume kernel ls' :retry
               {:context (context/with-conversation-id (context/create) "rt-2")
                :on-env-error :pause})]
      (is (= :completed (:status r2)))
      (is (= "到手" (get-in r2 [:response :text]))))))

;;; ============================================================
;;; client 端到端：跨"重启"恢复（新 agent 实例 + 同 stores）
;;; ============================================================

(defn- agent-opts
  "两个 agent 实例共用同一 provider/store/pause-store = 模拟重启后重建。"
  [provider mem ps cid]
  {:provider provider :model "test"
   :tools [#'note-writer #'ts/dangerous-tool #'note-reader]
   :state-slots {:notes {:init [] :reduce conj}}
   :memory mem :pause-store ps :conversation-id cid
   :callbacks {:on-tool-call (fn [n _] (when (= "dangerous-tool" n)
                                         {:interrupt "需要审批"}))}})

(deftest cross-restart-approval-test
  (let [provider (ts/create-mock-provider
                   [{:text nil :tool-calls [{:id "w1" :name "note-writer"
                                             :args {:note "n1"}}]}
                    {:text nil :tool-calls [{:id "d1" :name "dangerous-tool"
                                             :args {:target "x"}}]}
                    {:text nil :tool-calls [{:id "r1" :name "note-reader" :args {}}]}
                    {:text "全部完成" :tool-calls nil}])
        mem (memory/in-memory-store)
        ps  (pause/in-memory-pause-store)
        cid "restart-1"
        a1  (agent/create-agent (agent-opts provider mem ps cid))]
    (testing "暂停时快照自动持久化"
      (let [r (agent/chat a1 "记 n1 然后删库")]
        (is (= :paused (:status r)))
        (is (some? (pause/pause-load ps cid)))))
    (testing "「重启」后新实例：paused? 透明回落 store，resume 照常"
      (let [a2 (agent/create-agent (agent-opts provider mem ps cid))]
        (is (agent/paused? a2) "新实例经 PauseStore 发现暂停态")
        (let [r (agent/resume a2 "approved")]
          (is (= :completed (:status r)))
          (is (= "全部完成" (:text r)))
          (testing "暂停前累积的 context 槽在 resume 后可读（缺口修复回归）"
            (is (some #(= "notes=[\"n1\"]" (:result %)) (:tool-calls-made r))
                "note-reader 在恢复后的轮次读到暂停前 note-writer 的写入"))
          (testing "完成后持久化快照清除"
            (is (nil? (pause/pause-load ps cid)))))))))

(deftest cross-restart-env-retry-test
  (reset! env-ok false)
  (let [provider (ts/create-mock-provider
                   [{:text nil :tool-calls [{:id "f1" :name "env-fetch" :args {}}]}
                    {:text "数据处理完毕" :tool-calls nil}])
        mem (memory/in-memory-store)
        ps  (pause/in-memory-pause-store)
        cid "restart-2"
        mk  (fn [] (agent/create-agent
                     {:provider provider :model "test" :tools [#'env-fetch]
                      :memory mem :pause-store ps :conversation-id cid
                      :on-env-error :pause}))
        a1  (mk)]
    (testing "环境类失败 → 暂停并持久化"
      (let [r (agent/chat a1 "取数")]
        (is (= :paused (:status r)))
        (is (= :env-retry (get-in (pause/pause-load ps cid) [:loop-state :phase])))))
    (testing "修复环境 + 重启后 resume \"retry\" → 重跑成功"
      (reset! env-ok true)
      (let [a2 (mk)
            r (agent/resume a2 "retry")]
        (is (= :completed (:status r)))
        (is (= "数据处理完毕" (:text r)))
        (is (nil? (pause/pause-load ps cid)))))))

(deftest new-chat-clears-stale-pause-test
  (testing "暂停态下开新对话：持久化快照一并清除（未-resume 保护）"
    (let [provider (ts/create-mock-provider
                     [{:text nil :tool-calls [{:id "d1" :name "dangerous-tool"
                                               :args {:target "x"}}]}
                      {:text "新话题回复" :tool-calls nil}])
          mem (memory/in-memory-store)
          ps  (pause/in-memory-pause-store)
          cid "restart-3"
          a   (agent/create-agent
                {:provider provider :model "test" :tools [#'ts/dangerous-tool]
                 :memory mem :pause-store ps :conversation-id cid
                 :callbacks {:on-tool-call (fn [_ _] {:interrupt "审批"})}})]
      (is (= :paused (:status (agent/chat a "删库"))))
      (is (some? (pause/pause-load ps cid)))
      (is (= :completed (:status (agent/chat a "算了聊别的"))))
      (is (nil? (pause/pause-load ps cid)) "cancel-pending! 清掉持久化快照")
      (is (not (agent/paused? a))))))
