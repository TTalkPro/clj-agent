(ns im.ttalk.agent.agui.cancel-reason-test
  "取消原因 + 孤儿自动停（designs/agui-borrow.md §5，tasks/G-05.md）。

   时序全部用闸门（`:hold`）钉住，不靠 sleep 猜——同 runtime-test 的教训。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.agui.codec :as codec]
            [im.ttalk.agent.agui.event :as event]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.agui.support :as sup]))

(defn- cancelled-of [events]
  (first (filter #(= :run/cancelled (:type %)) events)))

(defn- held-runtime
  "一个卡在第一个 token 之后的 runtime。返回 `[rt hold]`。"
  ([] (held-runtime nil))
  ([rt-opts]
   (let [hold (promise)]
     [(sup/runtime {:provider (sup/provider (repeat 5 {:text "一二三四"}) {:chunk-size 1 :hold hold})}
                   rt-opts)
      hold])))

(deftest event-stop-reason-test
  (testing "第一个原因算数；缺省 :stop"
    (let [em (event/emitter {:run-id "r" :conversation-id "c" :next-seq (let [n (atom -1)] #(swap! n inc))
                             :sink (fn [_])})]
      (is (nil? (event/stop-reason em)))
      (is (true? (event/request-stop! em :superseded)))
      (is (false? (event/request-stop! em :shutdown)))
      (is (= :superseded (event/stop-reason em)) "后来的原因不覆盖")))
  (testing "1-arity 仍是 :stop（老调用点不变）"
    (let [em (event/emitter {:run-id "r" :conversation-id "c" :next-seq (let [n (atom -1)] #(swap! n inc))
                             :sink (fn [_])})]
      (event/request-stop! em)
      (is (= :stop (event/stop-reason em))))))

(deftest stop-reason-test
  (testing "stop! → :run/cancelled 带 :reason :stop；run-status 看得到"
    (let [[r hold] (held-runtime)
          c (sup/collector)]
      (rt/subscribe r "c1" {:on-event (:on-event c)})
      (rt/start-run! r "c1" "hi")
      (sup/wait-for #(some #{:message/delta} ((:types c))))
      (is (true? (rt/stop! r "c1")))
      (let [st (rt/run-status r "c1")]
        (is (or (= :stop (:cancel-reason st)) (= :idle (:state st)))))
      (deliver hold true)
      (is (sup/wait-for #(cancelled-of ((:events c)))))
      (is (= :stop (:reason (cancelled-of ((:events c))))))
      (rt/shutdown! r)))

  (testing "4-arity 自定义原因；第一个原因算数（先 stop 再 shutdown 仍是 :stop）"
    (let [[r hold] (held-runtime)
          c (sup/collector)]
      (rt/subscribe r "c1" {:on-event (:on-event c)})
      (rt/start-run! r "c1" "hi")
      (sup/wait-for #(some #{:message/delta} ((:types c))))
      (is (true? (rt/stop! r "c1" nil :stop)))
      (deliver hold true)
      (rt/shutdown! r)
      (is (sup/wait-for #(cancelled-of ((:events c)))))
      (is (= :stop (:reason (cancelled-of ((:events c)))))))))

(deftest supersede-reason-test
  (testing "被顶掉的旧 run 原因是 :superseded"
    (let [[r hold] (held-runtime {:on-concurrent :supersede})
          c (sup/collector)]
      (rt/subscribe r "c1" {:on-event (:on-event c)})
      (let [first-run (rt/start-run! r "c1" "第一句")]
        (sup/wait-for #(some #{:message/delta} ((:types c))))
        (let [f (future (rt/start-run! r "c1" "第二句"))]
          (sup/wait-for #(:stopping? (rt/run-status r "c1")) 2000)
          (is (= :superseded (:cancel-reason (rt/run-status r "c1"))))
          (deliver hold true)
          (is (= :started (:status (deref f 5000 ::timeout)))))
        (sup/wait-for #(= :idle (:state (rt/run-status r "c1"))))
        (let [old (->> ((:events c)) (filter #(= (:run-id first-run) (:run-id %))) cancelled-of)]
          (is (= :superseded (:reason old)))))
      (rt/shutdown! r))))

(deftest shutdown-reason-test
  (testing "shutdown! → :shutdown"
    (let [[r hold] (held-runtime {:shutdown-wait-ms 10000})
          c (sup/collector)]
      (rt/subscribe r "c1" {:on-event (:on-event c)})
      (rt/start-run! r "c1" "hi")
      (sup/wait-for #(some #{:message/delta} ((:types c))))
      ;; shutdown! 先登记取消、再等 run 收尾（订阅表要等它收完才清）——闸门在它
      ;; 等的时候放开，终态于是还能送到订阅者手上
      (future (sup/wait-for #(:stopping? (rt/run-status r "c1")) 3000) (deliver hold true))
      (rt/shutdown! r)
      (is (sup/wait-for #(cancelled-of ((:events c)))))
      (is (= :shutdown (:reason (cancelled-of ((:events c)))))))))

(deftest orphan-cancel-test
  (testing "最后一个订阅者走后到点仍没人 → :orphan"
    (let [[r hold] (held-runtime {:orphan-cancel-ms 30})
          watcher (sup/collector)
          c (sup/collector)
          unsub (rt/subscribe r "c1" {:on-event (:on-event c)})]
      (rt/start-run! r "c1" "hi")
      (sup/wait-for #(some #{:message/delta} ((:types c))))
      (unsub)
      (is (sup/wait-for #(= :orphan (:cancel-reason (rt/run-status r "c1"))) 3000)
          "没人看了，到点按停")
      ;; 停稳之后用 :since 回看终态（订阅表空着才会触发孤儿，所以先不订）
      (deliver hold true)
      (sup/wait-for #(= :idle (:state (rt/run-status r "c1"))))
      (rt/subscribe r "c1" {:since -1 :on-event (:on-event watcher)})
      (is (= :orphan (:reason (cancelled-of ((:events watcher))))))
      (rt/shutdown! r)))

  (testing "走了又马上回来 → 不停"
    (let [[r hold] (held-runtime {:orphan-cancel-ms 80})
          c (sup/collector)
          unsub (rt/subscribe r "c1" {:on-event (:on-event c)})]
      (rt/start-run! r "c1" "hi")
      (sup/wait-for #(some #{:message/delta} ((:types c))))
      (unsub)
      (rt/subscribe r "c1" {:on-event (:on-event c)})   ;; 刷新：重连回来了
      (Thread/sleep 200)                                 ;; 远超定时器，确认它作废了
      (is (not (:stopping? (rt/run-status r "c1"))))
      (deliver hold true)
      (is (sup/wait-for #(some #{:run/finished} ((:types c)))))
      (rt/shutdown! r)))

  (testing "缺省关：订阅者走了 run 照样跑完"
    (let [[r hold] (held-runtime)
          c (sup/collector)
          unsub (rt/subscribe r "c1" {:on-event (:on-event c)})]
      (rt/start-run! r "c1" "hi")
      (sup/wait-for #(some #{:message/delta} ((:types c))))
      (unsub)
      (Thread/sleep 50)
      (is (not (:stopping? (rt/run-status r "c1"))))
      (deliver hold true)
      (is (sup/wait-for #(= :idle (:state (rt/run-status r "c1")))))
      (is (nil? (:cancel-reason (rt/run-status r "c1"))))
      (rt/shutdown! r))))

(deftest codec-cancel-reason-test
  (testing "RUN_FINISHED.result.reason；没有原因就不写"
    (let [base {:type :run/cancelled :run-id "r1" :conversation-id "t1" :seq 3}]
      (is (= {:status "cancelled" :reason "superseded"}
             (:result (codec/->agui (assoc base :reason :superseded)))))
      (is (= {:status "cancelled"} (:result (codec/->agui base)))))))
