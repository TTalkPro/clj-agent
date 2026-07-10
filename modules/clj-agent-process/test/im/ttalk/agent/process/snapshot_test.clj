(ns im.ttalk.agent.process.snapshot-test
  "Process × Timeline 快照适配：自动存档 / 断点续跑（跨重启）/ 分支实验。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.process.builder :as pb]
            [im.ttalk.agent.process.runtime :as rt]
            [im.ttalk.agent.process.snapshot :as snap]
            [im.ttalk.agent.timeline :as tl]
            [im.ttalk.agent.timeline.sqlite :as tls]))

(defn- approval-spec
  "gather → (pause 等审批) → apply。gather 累积 state 供恢复断言。"
  []
  (-> (pb/builder :approval)
      (pb/add-step {:id :gather
                    :init (fn [_] {:seen 0})
                    :on-activate (fn [inputs state _]
                                   {:events [{:name :gathered :data (:input inputs)}]
                                    :state {:seen (inc (:seen state))}})})
      (pb/add-step {:id :ask
                    :on-activate (fn [inputs _ _]
                                   {:pause {:reason "等待审批"
                                            :state {:pending (:input inputs)}}})
                    :on-resume (fn [data state _]
                                 (if (= "approved" data)
                                   {:events [{:name :approved :data (:pending state)}]}
                                   {:pause {:reason "仍在等待"}}))})
      (pb/add-step {:id :apply
                    :on-activate (fn [inputs _ c]
                                   {:context (ctx/set-var c :applied (:input inputs))
                                    :events []})})
      (pb/on-event :start :gather)
      (pb/on-event :gathered :ask)
      (pb/on-event :approved :apply)
      (pb/set-initial-event :start "part-42")
      (pb/build)))

(deftest checkpointer-auto-saves-test
  (let [m (tl/manager (tl/in-memory-store))
        result (rt/run-process (approval-spec)
                               {:on-quiescent (snap/checkpointer m "t1")})]
    (is (rt/paused? result))
    (testing "静止点 + 暂停点都自动落 Timeline，reason 入 entry 顶层"
      (let [cps (snap/list-checkpoints m "t1")]
        (is (= [:quiescent :paused] (map :checkpoint-reason cps)))
        (is (every? #(= :approval (:process-name %)) cps))))
    (testing "最新存档是暂停点，快照字段齐全"
      (let [cp (snap/latest-checkpoint m "t1")]
        (is (snap/paused-checkpoint? cp))
        (is (= :ask (get-in cp [:data :paused-step])))
        (is (= "等待审批" (get-in cp [:data :pause-reason])))
        (is (= {:pending "part-42"} (get-in cp [:data :step-states :ask :state])))))))

(deftest checkpointer-strips-kernel-test
  (let [m (tl/manager (tl/in-memory-store))
        fake-kernel {:not-serializable (fn [])}
        _ (rt/run-process (approval-spec)
                          {:context (ctx/create {:kernel fake-kernel :user-id "u1"})
                           :on-quiescent (snap/checkpointer m "t1")})
        cp (snap/latest-checkpoint m "t1")]
    (is (nil? (get-in cp [:data :context :kernel])) "缺省剥离 :kernel")
    (is (= "u1" (get-in cp [:data :context :user-id])) "其余 context 保留")))

(deftest resume-across-restart-test
  (testing "SQLite 持久化 → 新 store 实例（模拟重启）→ 从暂停存档续跑到完成"
    (let [f (java.io.File/createTempFile "snap-restart" ".db")
          _ (.deleteOnExit f)
          path (.getAbsolutePath f)]
      ;; 第一段进程：跑到暂停，存档落库
      (with-open [s ^java.io.Closeable (tls/sqlite-store path)]
        (let [m (tl/manager s)
              r (rt/run-process (approval-spec)
                                {:context (ctx/create {:kernel :fake-kernel})
                                 :on-quiescent (snap/checkpointer m "t1")})]
          (is (rt/paused? r))))
      ;; 第二段进程：重开库，找到暂停点，注回 kernel，approve 续跑
      (with-open [s ^java.io.Closeable (tls/sqlite-store path)]
        (let [m  (tl/manager s)
              cp (snap/latest-checkpoint m "t1")
              _  (is (snap/paused-checkpoint? cp))
              result (snap/resume-checkpoint (approval-spec) cp "approved"
                                             {:context-extras {:kernel :fake-kernel}})]
          (is (= :completed (:status result)))
          (is (= "part-42" (ctx/get-var (:context result) :applied))
              "pause 携带的 state 经快照跨重启存活并驱动后续步骤"))))))

(deftest resume-checkpoint-rejects-non-paused-test
  (let [m (tl/manager (tl/in-memory-store))
        _ (rt/run-process (approval-spec) {:on-quiescent (snap/checkpointer m "t1")})
        quiescent-cp (first (snap/list-checkpoints m "t1"))]
    (is (not (snap/paused-checkpoint? quiescent-cp)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"不是暂停点"
          (snap/resume-checkpoint (approval-spec) quiescent-cp "x")))))

(deftest branch-experiment-test
  (testing "回到静止点存档开分支，用不同输入重跑——两条时间线并存互不污染"
    (let [m (tl/manager (tl/in-memory-store))
          spec (-> (pb/builder :exp)
                   (pb/add-step {:id :calc
                                 :init (fn [_] {:sum 0})
                                 :on-activate
                                 (fn [inputs state c]
                                   (let [sum (+ (:sum state) (:input inputs))]
                                     {:state {:sum sum}
                                      :context (ctx/set-var c :sum sum)
                                      :events [{:name :done :data sum}]}))})
                   (pb/add-step {:id :sink :on-activate (fn [_ _ _] {:events []})})
                   (pb/on-event :add :calc)
                   (pb/on-event :done :sink)
                   (pb/set-initial-event :add 10)
                   (pb/build))
          ;; main：10 → 存档 → 再 +5 = 15
          _  (rt/run-process spec {:on-quiescent (snap/checkpointer m "t1")})
          cp (snap/latest-checkpoint m "t1")
          _  (rt/run-process spec (merge (snap/restore-opts cp)
                                         {:initial-events [{:name :add :data 5}]
                                          :on-quiescent (snap/checkpointer m "t1")}))
          main-sum (get-in (snap/latest-checkpoint m "t1")
                           [:data :step-states :calc :state :sum])
          ;; exp：回到 10 的存档开分支，+100 = 110
          _ (snap/branch! m "t1" (:id cp) "exp")
          _ (rt/run-process spec (merge (snap/restore-opts cp)
                                        {:initial-events [{:name :add :data 100}]
                                         :on-quiescent (snap/checkpointer m "t1")}))
          exp-sum (get-in (snap/latest-checkpoint m "t1")
                          [:data :step-states :calc :state :sum])]
      (is (= 15 main-sum))
      (is (= 110 exp-sum))
      (testing "切回 main，其时间线未被实验污染"
        (tl/switch-branch! m "t1" "main")
        (is (= 15 (get-in (snap/latest-checkpoint m "t1")
                          [:data :step-states :calc :state :sum]))))
      (testing "exp 分支血缘穿过锚点回到 main 的历史"
        (tl/switch-branch! m "t1" "exp")
        (let [head (snap/latest-checkpoint m "t1")
              lineage (tl/get-lineage m "t1" (:id head))]
          (is (= (:id cp) (:id (first (filter #(= "main" (:branch-id %)) lineage))))))))))
