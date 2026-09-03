(ns im.ttalk.agent.subagent.manager-test
  "子 agent 管理器：虚拟线程 worker + 生命周期/kill 语义。

   do-run 用 with-redefs 打桩（不触真实 LLM）。"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [im.ttalk.agent.subagent.manager :as mgr]))

(use-fixtures :each (fn [f] (mgr/clear-all!) (f) (mgr/clear-all!)))

(defn- wait-status
  "轮询等待 id 的状态达到 expected（worker deliver promise 在 finish! 之前，
   await! 返回后状态登记可能尚未落账），超时返回最后一次观察值。"
  [id expected timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [st (:status (first (filter #(= id (:id %)) (mgr/list-agents))))]
        (if (or (= expected st) (> (System/currentTimeMillis) deadline))
          st
          (do (Thread/sleep 20) (recur)))))))

(deftest spawn-await-on-virtual-thread-test
  (testing "spawn→await 正常完成，且 worker 跑在虚拟线程上"
    (let [seen-virtual (promise)]
      (with-redefs [im.ttalk.agent.subagent.manager/do-run
                    (fn [_ _]
                      (deliver seen-virtual (.isVirtual (Thread/currentThread)))
                      {:ok "done"})]
        (let [{id :ok} (mgr/spawn! {:prompt "x"})]
          (is (= {:ok "done"} (mgr/await! id 5000)))
          (is (true? (deref seen-virtual 1000 :timeout))
              "worker 应运行在虚拟线程（与 HTTP 层策略一致）")
          (is (= :done (wait-status id :done 2000))))))))

(deftest kill-yields-explicit-result-test
  (testing "kill! 后 await!/result 返回明确 {:error :killed}（回归：曾返回 nil）"
    (with-redefs [im.ttalk.agent.subagent.manager/do-run
                  (fn [_ _] (Thread/sleep 60000) {:ok "never"})]
      (let [{id :ok} (mgr/spawn! {:prompt "x"})]
        (Thread/sleep 100)
        (mgr/kill! id)
        (is (= {:error :killed} (mgr/await! id 1000)))
        (is (= {:ok {:error :killed}} (mgr/result id)))))))

(deftest interrupted-worker-does-not-overwrite-killed-test
  (testing "被中断的 worker unwind 后不把 :killed 覆盖成 :failed（回归：finish! 终态守卫）"
    (with-redefs [im.ttalk.agent.subagent.manager/do-run
                  (fn [_ _] (Thread/sleep 60000) {:ok "never"})]
      (let [{id :ok} (mgr/spawn! {:prompt "x"})]
        (Thread/sleep 100)
        (mgr/kill! id)
        (Thread/sleep 300)                       ;; 等中断的 worker 完成 unwind
        (is (= :killed (:status (first (mgr/list-agents)))))
        (is (= {:ok {:error :killed}} (mgr/result id)))))))

(deftest failed-run-marks-failed-test
  (testing "do-run 返回 error → :failed，await! 拿到错误"
    (with-redefs [im.ttalk.agent.subagent.manager/do-run
                  (fn [_ _] {:error {:status :error :detail "boom"}})]
      (let [{id :ok} (mgr/spawn! {:prompt "x"})]
        (let [r (mgr/await! id 5000)]
          (is (= :error (get-in r [:error :status]))))
        (is (= :failed (wait-status id :failed 2000)))))))

;;; ============================================================
;;; 设计原则 §3「一个 ChatClient 绑定一个 TCM，不跨边界」——边界外不流通
;;; ============================================================

(def ^:dynamic *tenant* :none)

(deftest ambient-state-does-not-cross-delegate-boundary-test
  (testing "调用方的动态绑定**不**穿过 delegate 边界（§3「边界外不流通」）

            子 agent 是新 ChatClient + 新 TCM = 新执行边界，ambient 状态不该隐式流入。
            spawn-worker! 不用 bound-fn* 是**故意的**——本测试即为防止后人看它
            『像疏漏』而顺手改成 bound-fn*。要传状态给子 agent 走 subagent-config。

            对照 react_test/binding-conveyance-across-batch-shapes-test：同一 chat-client
            内的 executor 路径**必须**传导。同一条原则的两侧，方向相反。"
    (let [seen (promise)]
      (with-redefs [im.ttalk.agent.subagent.manager/do-run
                    (fn [_ _] (deliver seen *tenant*) {:ok "done"})]
        (binding [*tenant* :acme]
          (let [{id :ok} (mgr/spawn! {:prompt "x"})]
            (mgr/await! id 5000)
            (is (= :none (deref seen 2000 :timeout))
                "子 agent 看到的是根值——父的 binding 未跨界")))))))

(deftest parent-tool-manager-has-no-channel-into-subagent-test
  (testing "父 chat-client 的 :tool-manager 无自动渠道流入子 agent（§3「ChatClient ↔ TCM 1:1」）

            do-run 只吃 spec 的 :subagent-config（全部来自用户的 :subagent-fn），
            据此全新造 chat-client。父引擎要共享须用户亲手塞回去——踩坑，非漏洞。"
    (let [seen-config (promise)]
      (with-redefs [im.ttalk.agent.subagent.manager/do-run
                    (fn [spec _] (deliver seen-config (:subagent-config spec)) {:ok "done"})]
        (let [{id :ok} (mgr/spawn! {:prompt "x" :subagent-config {:memory false}})]
          (mgr/await! id 5000)
          (let [cfg (deref seen-config 2000 :timeout)]
            (is (not (contains? cfg :tool-manager))
                "子 agent 的 config 里不该出现引擎——它没有渠道到这里")
            (is (not (contains? cfg :chat-client))
                "更不该带着父 chat-client")))))))

;;; ============================================================
;;; 观察者钩子（docs/subagent-event-attribution-design.md §3.5 / S2）
;;;
;;; 契约：工厂每次 spawn 调一次、在 worker 线程上；四个钩子全可选、各自吞异常；
;;; :settle! 在 finally 里，故 kill / 超时 / 崩溃三条路径都覆盖得到。
;;; ============================================================

(defn- recording-observer
  "纯记录用的假观察者。返回 [factory log-atom]。"
  []
  (let [log (atom [])]
    [(fn [info]
       (swap! log conj [:made info])
       {:start!  (fn [] (swap! log conj [:start (.isVirtual (Thread/currentThread))]))
        :settle! (fn [outcome] (swap! log conj [:settle outcome]))})
     log]))

(defn- events-of [log k] (filterv #(= k (first %)) @log))

(deftest observer-made-once-per-spawn-on-worker-thread-test
  (testing "工厂每次 spawn 调一次，且在 worker 线程上（不是调用方线程）"
    (with-redefs [im.ttalk.agent.subagent.manager/do-run (fn [_ _] {:ok "done"})]
      (let [[factory log] (recording-observer)
            {id :ok} (mgr/spawn! {:prompt "查一下"
                                  :observer factory
                                  :subagent-name "research_agent"
                                  :task "查一下"
                                  :owner "c1"
                                  :parent-tool-call-id "tc-9"})]
        (mgr/await! id 5000)
        (wait-status id :done 2000)
        (is (= 1 (count (events-of log :made))))
        (let [info (second (first (events-of log :made)))]
          (is (= id (:id info)))
          (is (= "research_agent" (:name info)))
          (is (= "查一下" (:task info)))
          (is (= "c1" (:owner info)))
          (is (= 0 (:attempt info)))
          (is (= "tc-9" (:parent-tool-call-id info))))
        (is (= [[:start true]] (events-of log :start))
            "start! 在 worker 的虚拟线程上")))))

(deftest observer-task-falls-back-to-prompt-test
  (testing "没给 :task 就用 :prompt——观察者总拿得到点能显示的东西"
    (with-redefs [im.ttalk.agent.subagent.manager/do-run (fn [_ _] {:ok "done"})]
      (let [[factory log] (recording-observer)
            {id :ok} (mgr/spawn! {:prompt "拼好的完整 prompt" :observer factory})]
        (mgr/await! id 5000)
        (let [info (second (first (events-of log :made)))]
          (is (= "拼好的完整 prompt" (:task info)))
          (is (= "subagent" (:name info)) "没给名字时的缺省"))))))

(deftest settle-exactly-once-on-every-path-test
  (testing "正常完成：settle 一次，拿到 {:ok …}"
    (with-redefs [im.ttalk.agent.subagent.manager/do-run (fn [_ _] {:ok "done"})]
      (let [[factory log] (recording-observer)
            {id :ok} (mgr/spawn! {:prompt "x" :observer factory})]
        (mgr/await! id 5000)
        (wait-status id :done 2000)
        (is (= [[:settle {:ok "done"}]] (events-of log :settle))))))

  (testing "子 agent 返回错误：settle 一次，拿到 {:error …}"
    (with-redefs [im.ttalk.agent.subagent.manager/do-run
                  (fn [_ _] {:error {:status :error :detail "boom"}})]
      (let [[factory log] (recording-observer)
            {id :ok} (mgr/spawn! {:prompt "x" :observer factory})]
        (mgr/await! id 5000)
        (wait-status id :failed 2000)
        (is (= 1 (count (events-of log :settle))))
        (is (= :error (get-in (second (first (events-of log :settle))) [:error :status]))))))

  (testing "do-run 抛异常：settle 照样一次（finally 覆盖崩溃路径）"
    (with-redefs [im.ttalk.agent.subagent.manager/do-run
                  (fn [_ _] (throw (ex-info "worker 炸了" {})))]
      (let [[factory log] (recording-observer)
            {id :ok} (mgr/spawn! {:prompt "x" :observer factory})]
        (mgr/await! id 5000)
        (wait-status id :failed 2000)
        (is (= 1 (count (events-of log :settle))))
        (is (true? (get-in (second (first (events-of log :settle))) [:error :crashed]))))))

  (testing "kill：被中断的 worker unwind 时 settle 仍恰好一次，且看到 :killed"
    (with-redefs [im.ttalk.agent.subagent.manager/do-run
                  (fn [_ _] (Thread/sleep 60000) {:ok "never"})]
      (let [[factory log] (recording-observer)
            {id :ok} (mgr/spawn! {:prompt "x" :observer factory})]
        (Thread/sleep 150)
        (mgr/kill! id)
        (Thread/sleep 300)                      ;; 等中断的 worker 走完 finally
        (is (= [[:settle {:error :killed}]] (events-of log :settle))
            "超时路径同款：delegate 的 run-sync 超时后调的就是 kill!")))))

(deftest observer-hooks-never-affect-subagent-test
  (testing "工厂抛异常 = 没挂观察者，子 agent 照跑"
    (with-redefs [im.ttalk.agent.subagent.manager/do-run (fn [_ _] {:ok "done"})]
      (let [{id :ok} (mgr/spawn! {:prompt "x"
                                  :observer (fn [_] (throw (ex-info "工厂炸了" {})))})]
        (is (= {:ok "done"} (mgr/await! id 5000))))))

  (testing "start! / settle! 抛异常也不影响结果与状态登记"
    (with-redefs [im.ttalk.agent.subagent.manager/do-run (fn [_ _] {:ok "done"})]
      (let [{id :ok} (mgr/spawn!
                      {:prompt "x"
                       :observer (fn [_] {:start!  (fn [] (throw (ex-info "start 炸" {})))
                                          :settle! (fn [_] (throw (ex-info "settle 炸" {})))})})]
        (is (= {:ok "done"} (mgr/await! id 5000)))
        (is (= :done (wait-status id :done 2000)))))))

(deftest observer-decorate-and-chat-opts-test
  (testing ":decorate 装饰 agent、:chat-opts 并进 chat——两者都真的到了子 agent 那侧"
    (let [seen (atom nil)]
      (with-redefs [im.ttalk.agent.simple-agent/create-agent (fn [cfg] {:agent cfg})
                    im.ttalk.agent.simple-agent/chat
                    (fn [agent prompt opts]
                      (reset! seen {:agent agent :prompt prompt :opts opts})
                      {:status :completed :text "ok"})]
        (let [{id :ok} (mgr/spawn!
                        {:prompt "跑一下"
                         :observer (fn [_] {:decorate  #(assoc % :attached true)
                                            :chat-opts {:on-token :fake-sink}})})]
          (is (= {:ok "ok"} (mgr/await! id 5000)))
          (is (true? (:attached (:agent @seen))))
          (is (= {:on-token :fake-sink} (:opts @seen)))))))

  (testing ":decorate 抛异常 → 退回未装饰的 agent，不是让子 agent 跑不了"
    (let [seen (atom nil)]
      (with-redefs [im.ttalk.agent.simple-agent/create-agent (fn [cfg] {:agent cfg})
                    im.ttalk.agent.simple-agent/chat
                    (fn [agent _ _] (reset! seen agent) {:status :completed :text "ok"})]
        (let [{id :ok} (mgr/spawn!
                        {:prompt "x"
                         :observer (fn [_] {:decorate (fn [_] (throw (ex-info "装饰炸了" {})))})})]
          (is (= {:ok "ok"} (mgr/await! id 5000)))
          (is (nil? (:attached @seen)))))))

  (testing "没有观察者时，chat 收到的 opts 是 nil——今天的行为逐字不变"
    (let [seen (atom :unset)]
      (with-redefs [im.ttalk.agent.simple-agent/create-agent (fn [cfg] {:agent cfg})
                    im.ttalk.agent.simple-agent/chat
                    (fn [_ _ opts] (reset! seen opts) {:status :completed :text "ok"})]
        (let [{id :ok} (mgr/spawn! {:prompt "x"})]
          (is (= {:ok "ok"} (mgr/await! id 5000)))
          (is (nil? @seen)))))))

(deftest restart-is-a-new-attempt-test
  (testing "restart! 换代号：观察者据此把两次尝试分开，各自 start/settle 一次"
    (with-redefs [im.ttalk.agent.subagent.manager/do-run (fn [_ _] {:ok "done"})]
      (let [[factory log] (recording-observer)
            {id :ok} (mgr/spawn! {:prompt "x" :observer factory})]
        (mgr/await! id 5000)
        (wait-status id :done 2000)
        (mgr/restart! id)
        (mgr/await! id 5000)
        (wait-status id :done 2000)
        (is (= [0 1] (mapv #(:attempt (second %)) (events-of log :made))))
        (is (= 2 (count (events-of log :start))))
        (is (= 2 (count (events-of log :settle))))))))
