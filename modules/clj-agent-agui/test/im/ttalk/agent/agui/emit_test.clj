(ns im.ttalk.agent.agui.emit-test
  "采集接线里的纯函数部分。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.agui.emit :as emit]
            [im.ttalk.agent.agui.event :as event]))

(defn- capture []
  (let [out (atom [])
        n (atom -1)]
    [(event/emitter {:run-id "r1" :conversation-id "c1"
                     :next-seq #(swap! n inc)
                     :sink #(swap! out conj %)
                     :now (constantly 0)})
     out]))

(deftest state-snapshot-filters-framework-and-live-objects-test
  (testing "框架键不进状态快照"
    (let [[em out] (capture)]
      (#'emit/emit-state! em {:conversation-id "c1" :tool/call-id "tc1" :cart [1 2]})
      (is (= [{:cart [1 2]}] (map :state @out)))))

  (testing "不可 EDN 往返的值被滤掉——context 装活对象是正常的，发出去会在 JSON 那步炸"
    (let [[em out] (capture)]
      (#'emit/emit-state! em {:chat-client (fn [] :live) :notes "ok"})
      (is (= [{:notes "ok"}] (map :state @out)))))

  (testing "滤完为空就不发——空快照对前端没有意义"
    (let [[em out] (capture)]
      (#'emit/emit-state! em {:conversation-id "c1"})
      (is (empty? @out))))

  (testing "业务状态原样发"
    (let [[em out] (capture)]
      (#'emit/emit-state! em {:cart [{:item "book"}] :step 3})
      (is (= [{:cart [{:item "book"}] :step 3}] (map :state @out))))))
