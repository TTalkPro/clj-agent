(ns im.ttalk.agent.timeline-test
  "Timeline 与多分支（设计文档 §12）——
   writes 进历史 / wire 剥除 / fork 前缀与血缘 / 暂停 fork 带快照 +
   HITL 决策分支 / rollback / prune / ancestry / 编辑重试。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.client :as agent]
            [im.ttalk.agent.kernel :as core]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.advisor.memory :as ma]
            [im.ttalk.agent.pause :as pause]
            [im.ttalk.agent.react :as agent-loop]
            [im.ttalk.agent.test-support :as ts]
            [im.ttalk.agent.timeline :as tl]
            [im.ttalk.agent.tool :refer [deftool]]))

;;; ============================================================
;;; writes 进历史（event-sourcing 元数据）
;;; ============================================================

(deftool tl-writer
  "写状态槽的工具"
  [[v :string "值"]]
  {:result (str "写了 " v) :writes {:slot v}})

(deftool tl-boom
  "必炸工具"
  []
  (throw (ex-info "炸" {})))

(deftest writes-recorded-in-history-test
  (let [calls (atom 0)
        svc {:chat-fn (fn [_ _]
                        (if (= 1 (swap! calls inc))
                          (response/make-response :text nil
                            :tool-calls [{:id "w1" :name "tl-writer" :args {:v "x"}}
                                         {:id "b1" :name "tl-boom" :args {}}])
                          (response/make-response :text "done" :tool-calls nil)))}
        store (memory/in-memory-store)
        kernel (core/build-kernel {:service svc :tools [#'tl-writer #'tl-boom]
                                   :filters [(ma/memory-filter store)]})]
    (agent-loop/invoke kernel store [(msg/user "干活")]
      {:context {:conversation-id "wh-1"}})
    (let [stored (memory/mem-get store "wh-1")
          by-id (into {} (map (juxt :tool-call-id identity)) stored)]
      (testing "成功工具的 :writes 随消息进历史"
        (is (= {:slot "x"} (:writes (by-id "w1")))))
      (testing "失败工具无 :writes（与事务性同真同假）"
        (is (nil? (:writes (by-id "b1"))))
        (is (clojure.string/includes? (:content (by-id "b1")) "错误"))))))

;; wire 层 :writes 剥除测试在 provider 模块（wire_writes_test.clj）——
;; client 不依赖 provider，此处只测 client 侧行为。

;;; ============================================================
;;; fork! / lineage / ancestry / rollback! / prune!
;;; ============================================================

(defn- seed! [mem conv-id n]
  (memory/mem-add mem conv-id
                  (vec (for [i (range n)] (msg/user (str "m" i)))))
  conv-id)

(deftest fork-basic-test
  (let [mem (memory/in-memory-store)
        lin (tl/in-memory-lineage-store)
        deps {:memory mem :lineage lin}]
    (seed! mem "main" 6)
    (testing "全量 fork：完整前缀 + 血缘"
      (let [b (tl/fork! deps "main" {:as "b-full"})]
        (is (= "b-full" b))
        (is (= 6 (count (memory/mem-get mem b))))
        (is (= {:parent "main" :fork-point 6}
               (select-keys (tl/lineage deps b) [:parent :fork-point])))))
    (testing "部分前缀 fork"
      (let [b (tl/fork! deps "main" {:at 3 :as "b-part"})]
        (is (= 3 (count (memory/mem-get mem b))))
        (is (= ["m0" "m1" "m2"] (mapv :content (memory/mem-get mem b))))))
    (testing "分支互不影响：主线追加不进分支"
      (memory/mem-add mem "main" [(msg/user "m6")])
      (is (= 6 (count (memory/mem-get mem "b-full")))))
    (testing "目标已存在 / :at 越界 / 空源 → 抛"
      (is (thrown? clojure.lang.ExceptionInfo (tl/fork! deps "main" {:as "b-full"})))
      (is (thrown? clojure.lang.ExceptionInfo (tl/fork! deps "main" {:at 99})))
      (is (thrown? clojure.lang.ExceptionInfo (tl/fork! deps "nothing" {}))))))

(deftest ancestry-and-prune-test
  (let [mem (memory/in-memory-store)
        lin (tl/in-memory-lineage-store)
        deps {:memory mem :lineage lin}]
    (seed! mem "root" 4)
    (let [c1 (tl/fork! deps "root" {:as "c1"})
          c2 (tl/fork! deps c1 {:at 2 :as "c2"})]
      (testing "ancestry 沿 parent 回溯"
        (is (= ["c2" "c1"] (mapv :id (tl/ancestry deps c2))))
        (is (= [] (tl/ancestry deps "root")) "根会话无血缘"))
      (testing "prune 有子分支拒绝；子先删则可"
        (is (thrown? clojure.lang.ExceptionInfo (tl/prune! deps c1)))
        (tl/prune! deps c2)
        (tl/prune! deps c1)
        (is (empty? (memory/mem-get mem c1)))
        (is (nil? (tl/lineage deps c1)))))))

(deftest rollback-test
  (let [mem (memory/in-memory-store)
        ps  (pause/in-memory-pause-store)
        deps {:memory mem :pause-store ps}]
    (seed! mem "rb" 5)
    (pause/pause-save! ps "rb" {:version 1 :loop-state {}})
    (tl/rollback! deps "rb" 3)
    (is (= ["m0" "m1" "m2"] (mapv :content (memory/mem-get mem "rb"))))
    (is (nil? (pause/pause-load ps "rb")) "截断后未决暂停失效")))

;;; ============================================================
;;; HITL 决策分支：暂停点 fork 两支，各自 resume 不同决策
;;; ============================================================

(deftest hitl-decision-branch-test
  (let [provider (ts/create-mock-provider
                   [;; 主线：模型要调敏感工具 → 暂停
                    {:text nil :tool-calls [{:id "d1" :name "dangerous-tool"
                                             :args {:target "db"}}]}
                    ;; 分支 A resume approved 后的收尾
                    {:text "已执行完成" :tool-calls nil}
                    ;; 分支 B resume rejected 后的收尾
                    {:text "好的，不执行" :tool-calls nil}])
        mem (memory/in-memory-store)
        ps  (pause/in-memory-pause-store)
        lin (tl/in-memory-lineage-store)
        deps {:memory mem :pause-store ps :lineage lin}
        mk (fn [cid] (agent/create-agent
                       {:provider provider :model "test"
                        :tools [#'ts/dangerous-tool]
                        :memory mem :pause-store ps :conversation-id cid
                        :callbacks {:on-tool-call (fn [_ _] {:interrupt "审批"})}}))
        a-main (mk "hitl-main")]
    (is (= :paused (:status (agent/chat a-main "删库"))))
    (testing "暂停点全量 fork：暂停快照连带复制"
      (let [b (tl/fork! deps "hitl-main" {:as "hitl-b"})]
        (is (some? (pause/pause-load ps b)))
        (is (= b (:conversation-id (pause/pause-load ps b))) "快照 conv-id 重写为分支")))
    (testing "两个分支各自 resume 不同决策，互不影响"
      (let [a-approve (mk "hitl-main")          ;; 主线批准
            r1 (agent/resume a-approve "approved")
            a-reject  (mk "hitl-b")             ;; 分支拒绝
            r2 (agent/resume a-reject "rejected")]
        (is (= :completed (:status r1)))
        (is (= "已执行完成" (:text r1)))
        (is (= :completed (:status r2)))
        (is (= "好的，不执行" (:text r2)))
        ;; 历史分叉：主线有执行结果，分支是"已拒绝执行"
        (let [tool-msg (fn [cid] (->> (memory/mem-get mem cid)
                                      (filter #(= :tool (:role %)))
                                      first :content))]
          (is (clojure.string/includes? (tool-msg "hitl-main") "已执行危险操作"))
          (is (= "已拒绝执行" (tool-msg "hitl-b"))))))))

;;; ============================================================
;;; 编辑重试（regenerate）：fork at 用户消息 + 替换 + chat
;;; ============================================================

(deftest edit-and-regenerate-test
  (let [provider (ts/create-mock-provider
                   [{:text "第一次回答" :tool-calls nil}
                    {:text "第二次回答" :tool-calls nil}])
        mem (memory/in-memory-store)
        deps {:memory mem :lineage (tl/in-memory-lineage-store)}
        a1 (agent/create-agent {:provider provider :model "test"
                                :memory mem :conversation-id "edit-main"})]
    ;; 造两轮对话：u1 a1 u2 a2（共 4 条）
    (agent/chat a1 "第一个问题")
    (agent/chat a1 "第二个问题")
    (let [n (count (memory/mem-get mem "edit-main"))
          _ (is (= 4 n))
          ;; 改写最后一问 = fork 前缀不含 u2（:at n-2），在分支上重发
          b (tl/fork! deps "edit-main" {:at (- n 2) :as "edit-b"})
          provider2 (ts/create-mock-provider [{:text "分支上的新回答" :tool-calls nil}])
          a2 (agent/create-agent {:provider provider2 :model "test"
                                  :memory mem :conversation-id b})
          r (agent/chat a2 "改写后的第二问")]
      (is (= :completed (:status r)))
      (is (= "分支上的新回答" (:text r)))
      (testing "分支历史 = 前缀 + 新问答；主线不受影响"
        (let [bm (memory/mem-get mem b)]
          (is (= 4 (count bm)))
          (is (= "改写后的第二问" (:content (nth bm 2))))
          (is (= 2 (:fork-point (tl/lineage deps b))))
          (is (= 4 (count (memory/mem-get mem "edit-main"))))
          (is (= "第二个问题" (:content (nth (memory/mem-get mem "edit-main") 2)))))))))
