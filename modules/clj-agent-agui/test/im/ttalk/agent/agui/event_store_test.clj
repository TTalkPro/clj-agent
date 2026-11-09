(ns im.ttalk.agent.agui.event-store-test
  "事件存储挂点（designs/agui-borrow.md §8）：缺省环形缓冲的语义不变；外部持久化
   实现插进来后，重启（新建 runtime）事件仍在、序号接得上、:since 跨进程续传；
   forget! 不删持久化数据、purge! 才删；存储抛异常不打断 run。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.agui.event-store :as es]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.agui.support :as sup]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.pause :as pause]))

(deftest ring-store-test
  (let [s (es/ring-store {:buffer-size 3})]
    (is (nil? (es/es-last-seq s "c")))
    (doseq [i (range 5)] (es/es-append! s "c" {:type :x :seq i}))
    (is (= [2 3 4] (mapv :seq (es/es-events s "c"))) "有界：只留最近 3 条")
    (is (= 4 (es/es-last-seq s "c")))
    (is (= {:events [] :gap? false} (es/es-since s "c" nil)))
    (is (= [4] (mapv :seq (:events (es/es-since s "c" 3)))))
    (is (false? (:gap? (es/es-since s "c" 1))))
    (is (true? (:gap? (es/es-since s "c" 0))) "起点越过 since → gap")
    (es/es-release! s "c")
    (is (= [] (es/es-events s "c")) "环形缓冲：驱逐即释放"))
  (is (thrown? clojure.lang.ExceptionInfo (es/ring-store {:buffer-size 0}))))

(defn- durable-store
  "模拟外部持久化实现（同 Keelson Agui.Bitcask 的角色）：数据活在 runtime 之外，
   release 不动数据，delete 才删。"
  [data]
  (reify es/EventStore
    (es-append! [_ cid ev] (swap! data update cid (fnil conj []) ev) nil)
    (es-since [_ cid since] (es/since-of (get @data cid []) since))
    (es-events [_ cid] (vec (get @data cid [])))
    (es-last-seq [_ cid] (some-> (get @data cid) peek :seq))
    (es-release! [_ _] nil)
    (es-delete! [_ cid] (swap! data dissoc cid) nil)))

(defn- run! [r cid msg]
  (let [c (sup/collector)
        unsub (rt/subscribe r cid {:on-event (:on-event c)})]
    (rt/start-run! r cid msg)
    (sup/wait-for #(sup/terminal-event ((:events c))))
    (unsub)
    ((:events c))))

(deftest pluggable-durable-store-test
  (let [data (atom {})
        spec {:memory (memory/in-memory-store) :pause-store (pause/in-memory-pause-store)}
        mk (fn [responses]
             (sup/runtime (assoc spec :provider (sup/provider responses)) {:event-store (durable-store data)}))]
    (testing "重启后事件仍在、序号接着走"
      (let [r1 (mk [{:text "第一轮"}])
            evs1 (run! r1 "t1" "hi")
            last1 (:seq (last evs1))]
        (rt/shutdown! r1)
        (let [r2 (mk [{:text "第二轮"}])]
          (is (= (mapv :seq evs1) (mapv :seq (rt/buffered-events r2 "t1")))
              "新 runtime 没跑过这条线程，也读得到持久化的事件")
          (let [late (sup/collector)]
            (rt/subscribe r2 "t1" {:since (- last1 2) :on-event (:on-event late)})
            (is (= [(dec last1) last1] (mapv :seq ((:events late))))
                ":since 跨进程续传：从存储补发，不走 resync")
            (is (not-any? #(= :run/resync (:type %)) ((:events late)))))
          (let [evs2 (run! r2 "t1" "再来")]
            (is (every? #(> (:seq %) last1) evs2) "序号不从 0 重来")
            (is (= (inc last1) (:seq (first evs2)))))
          (testing "forget! 只腾内存，purge! 才删"
            (rt/forget! r2 "t1")
            (is (seq (get @data "t1")))
            (rt/purge! r2 "t1")
            (is (nil? (get @data "t1"))))
          (rt/shutdown! r2))))))

(deftest failing-store-does-not-break-run-test
  (let [boom (reify es/EventStore
               (es-append! [_ _ _] (throw (RuntimeException. "disk full")))
               (es-since [_ _ _] (throw (RuntimeException. "disk full")))
               (es-events [_ _] (throw (RuntimeException. "disk full")))
               (es-last-seq [_ _] (throw (RuntimeException. "disk full")))
               (es-release! [_ _] (throw (RuntimeException. "disk full")))
               (es-delete! [_ _] nil))
        r (sup/runtime {:provider (sup/provider [{:text "照样跑完"}])} {:event-store boom})
        evs (run! r "t1" "hi")]
    (is (= :run/finished (:type (sup/terminal-event evs))) "订阅者照常收到实时事件")
    (is (= "照样跑完" (sup/text-of evs)))
    (is (= [] (rt/buffered-events r "t1")))
    (rt/shutdown! r)))
