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
                    (fn [_]
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
                  (fn [_] (Thread/sleep 60000) {:ok "never"})]
      (let [{id :ok} (mgr/spawn! {:prompt "x"})]
        (Thread/sleep 100)
        (mgr/kill! id)
        (is (= {:error :killed} (mgr/await! id 1000)))
        (is (= {:ok {:error :killed}} (mgr/result id)))))))

(deftest interrupted-worker-does-not-overwrite-killed-test
  (testing "被中断的 worker unwind 后不把 :killed 覆盖成 :failed（回归：finish! 终态守卫）"
    (with-redefs [im.ttalk.agent.subagent.manager/do-run
                  (fn [_] (Thread/sleep 60000) {:ok "never"})]
      (let [{id :ok} (mgr/spawn! {:prompt "x"})]
        (Thread/sleep 100)
        (mgr/kill! id)
        (Thread/sleep 300)                       ;; 等中断的 worker 完成 unwind
        (is (= :killed (:status (first (mgr/list-agents)))))
        (is (= {:ok {:error :killed}} (mgr/result id)))))))

(deftest failed-run-marks-failed-test
  (testing "do-run 返回 error → :failed，await! 拿到错误"
    (with-redefs [im.ttalk.agent.subagent.manager/do-run
                  (fn [_] {:error {:status :error :detail "boom"}})]
      (let [{id :ok} (mgr/spawn! {:prompt "x"})]
        (let [r (mgr/await! id 5000)]
          (is (= :error (get-in r [:error :status]))))
        (is (= :failed (wait-status id :failed 2000)))))))
