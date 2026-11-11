(ns im.ttalk.agent.agui.registry-test
  "多 agent 注册表（designs/agui-borrow.md §4，tasks/G-04.md）。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.agui.registry :as registry]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.agui.support :as sup]))

(defn- mk-rt
  ([] (mk-rt nil))
  ([hold] (sup/runtime {:provider (sup/provider (repeat 5 {:text "一二三"}) {:chunk-size 1 :hold hold})})))

(deftest construct-test
  (testing "装配期抛：空表 / 缺 id / 缺 runtime / 重名 / :stop 不是函数"
    (let [r (mk-rt)]
      (is (thrown? clojure.lang.ExceptionInfo (registry/registry [])))
      (is (thrown? clojure.lang.ExceptionInfo (registry/registry [{:runtime r}])))
      (is (thrown? clojure.lang.ExceptionInfo (registry/registry [{:id "" :runtime r}])))
      (is (thrown? clojure.lang.ExceptionInfo (registry/registry [{:id "a"}])))
      (is (thrown? clojure.lang.ExceptionInfo (registry/registry [{:id "a" :runtime {}}])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"重名"
                            (registry/registry [{:id "a" :runtime r} {:id "a" :runtime r}])))
      (is (thrown? clojure.lang.ExceptionInfo (registry/registry [{:id "a" :runtime r :stop "x"}])))
      (is (thrown? clojure.lang.ExceptionInfo (registry/registry [{:id "a" :external? true :runtime {}}])))
      (is (map? (registry/registry [{:id "a" :external? true :description "自带驱动"}])))
      (rt/shutdown! r))))

(deftest lookup-test
  (let [r1 (mk-rt) r2 (mk-rt)
        reg (registry/registry [{:id "default" :runtime r1 :description "通用"}
                                {:id "ops" :runtime r2 :suggest? true :extra 42}])]
    (testing "加入顺序稳定；条目是开放 map（路由自己的键原样留着）"
      (is (= ["default" "ops"] (registry/agent-ids reg)))
      (is (= 42 (:extra (registry/find-agent reg "ops"))))
      (is (nil? (registry/find-agent reg "nope")))
      (is (nil? (registry/find-agent reg nil)))
      (is (= "default" (:id (registry/default-agent reg))))
      (is (registry/registry? reg))
      (is (not (registry/registry? {}))))
    (testing "info：agents 是字典、description 进去、suggestions = 任一台支持"
      (let [info (registry/info reg)]
        (is (= #{"default" "ops"} (set (keys (:agents info)))))
        (is (= "通用" (get-in info [:agents "default" :description])))
        (is (true? (:suggestions info))))
      (let [reg2 (registry/registry [{:id "a" :runtime r1}])]
        (is (false? (:suggestions (registry/info reg2))))))
    (testing "shutdown! 按 runtime 去重"
      (let [shared (registry/registry [{:id "a" :runtime r1} {:id "b" :runtime r1}])]
        (is (= 1 (count (registry/runtimes shared))))))
    (testing "external 只报名录给的能力位；没给就不谎报内置 runtime 的缺省"
      (let [ext (registry/registry [{:id "ext" :external? true}
                                    {:id "advertised" :external? true
                                     :capabilities {:tools {:supported true}}}])]
        (is (empty? (registry/runtimes ext)))
        (is (= {} (get-in (registry/info ext) [:agents "ext" :capabilities])))
        (is (= {:tools {:supported true}}
               (get-in (registry/info ext) [:agents "advertised" :capabilities])))
        (registry/shutdown! ext)))
    (registry/shutdown! reg)))

(deftest stop-test
  (testing "缺省 stop：runtime/stop!，原因 :stop；没在跑 / 没这台 → false"
    (let [hold (promise)
          r (mk-rt hold)
          reg (registry/registry [{:id "default" :runtime r}])
          c (sup/collector)]
      (rt/subscribe r "t1" {:on-event (:on-event c)})
      (rt/start-run! r "t1" "hi")
      (sup/wait-for #(some #{:message/delta} ((:types c))))
      (is (false? (registry/stop! reg "nope" "t1")))
      (is (false? (registry/stop! reg "default" "没有这个线程")))
      (is (true? (registry/stop! reg "default" "t1")))
      (deliver hold true)
      (is (sup/wait-for #(some #{:run/cancelled} ((:types c)))))
      (is (= :stop (:reason (sup/terminal-event ((:events c))))))
      (registry/shutdown! reg)))

  (testing "条目自己的 :stop 说了算"
    (let [r (mk-rt)
          calls (atom [])
          reg (registry/registry [{:id "ops" :runtime r
                                   :stop (fn [{:keys [thread-id agent runtime]}]
                                           (swap! calls conj [thread-id (:id agent) (some? runtime)])
                                           false)}])]
      (is (false? (registry/stop! reg "ops" "t9")))
      (is (= [["t9" "ops" true]] @calls))
      (registry/shutdown! reg))))
