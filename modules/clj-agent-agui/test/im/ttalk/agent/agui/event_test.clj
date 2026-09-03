(ns im.ttalk.agent.agui.event-test
  "发射器的三条契约（docs/agent-runtime-design.md §4.2 / §4.6 / §6.3）。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.agui.event :as event]))

(defn- test-emitter
  ([] (test-emitter (atom [])))
  ([sink-atom]
   (let [n (atom -1)]
     [(event/emitter {:run-id "r1" :conversation-id "c1"
                      :next-seq #(swap! n inc)
                      :sink #(swap! sink-atom conj %)
                      :now (constantly 1000)})
      sink-atom])))

(deftest seq-monotonic-no-gap-test
  (testing ":seq 单调无洞——重连续传的全部依据"
    (let [[em out] (test-emitter)]
      (event/emit! em :run/started {})
      (event/begin-message! em "m0")
      (event/emit-token! em :token "你")
      (event/emit-token! em :token "好")
      (event/finish! em :run/finished {:text "你好"})
      (is (= (range (count @out)) (map :seq @out)))
      (is (every? #(= "r1" (:run-id %)) @out))
      (is (every? #(= "c1" (:conversation-id %)) @out)))))

(deftest exactly-one-terminal-test
  (testing "终态恰好一个，且是最后一个；重复 finish! 是 no-op"
    (let [[em out] (test-emitter)]
      (event/emit! em :run/started {})
      (event/finish! em :run/finished {:text "x"})
      (event/finish! em :run/error {:error {:message "不该出现"}})
      (event/finish! em :run/cancelled {})
      (is (= 1 (count (filter event/terminal? @out))))
      (is (event/terminal? (last @out)))
      (is (= :run/finished (:type (last @out)))))))

(deftest close-open-blocks-test
  (testing "异常收尾：开着的消息与工具块被补关，且终态在最后"
    (let [[em out] (test-emitter)]
      (event/begin-message! em "m0")
      (event/emit-token! em :token "半句")
      (event/emit! em :tool/started {:tool-call-id "tc1" :name "t"})
      (event/finish! em :run/error {:error {:message "boom"}})
      (let [types (map :type @out)]
        (is (= [:message/started :message/delta :tool/started
                :message/ended :tool/ended :tool/result :run/error]
               types))
        (is (= :run/error (:type (last @out))))
        (is (true? (:synthetic (first (filter #(= :tool/result (:type %)) @out)))))))))

(deftest paused-keeps-tool-open-test
  (testing "暂停不合成工具结果——那个工具是真的还悬着，编一个结果就是撒谎"
    (let [[em out] (test-emitter)]
      (event/emit! em :tool/started {:tool-call-id "tc1" :name "t"})
      (event/finish! em :run/paused {:reason "需要审批"})
      (is (= [:tool/started :run/paused] (map :type @out)))
      (is (empty? (filter #(= :tool/result (:type %)) @out))))))

(deftest stop-intent-is-per-run-test
  (testing "停止意图挂在本 run 的 holder 上；重复登记只算一次"
    (let [[em _] (test-emitter)]
      (is (false? (event/stop-requested? em)))
      (is (true? (event/request-stop! em)))
      (is (false? (event/request-stop! em)))
      (is (true? (event/stop-requested? em))))))

(deftest emitter-never-throws-test
  (testing "sink 抛异常被兜住，且不吃掉 seq（§6.3：callbacks 的吞异常语义对事件流是错的）"
    (let [n (atom -1)
          calls (atom 0)
          em (event/emitter {:run-id "r1" :conversation-id "c1"
                             :next-seq #(swap! n inc)
                             :sink (fn [_] (swap! calls inc) (throw (ex-info "sink 炸了" {})))
                             :now (constantly 0)})]
      (is (some? (event/emit! em :run/started {})))
      (is (some? (event/emit! em :run/finished {})))
      (is (= 2 @calls))
      (is (= 2 (event/drops em)))
      (is (= 1 @n) "seq 由发射器分配，sink 失败不影响编号"))))

(deftest non-streaming-text-test
  (testing "非流式：一个 token 都没有时，整段文本补成完整消息"
    (let [[em out] (test-emitter)]
      (event/begin-message! em "m0")
      (event/end-message! em "完整答案")
      (is (= [:message/started :message/delta :message/ended] (map :type @out)))
      (is (= "完整答案" (:text (second @out))))))
  (testing "流式：token 出过就只补 ended，不重复整段"
    (let [[em out] (test-emitter)]
      (event/begin-message! em "m0")
      (event/emit-token! em :token "答")
      (event/end-message! em "答案")
      (is (= [:message/started :message/delta :message/ended] (map :type @out)))
      (is (= "答" (:text (second @out)))))))

(deftest ensure-text-only-when-silent-test
  (testing "ensure-text! 只在本 run 一个字都没出过时补"
    (let [[em out] (test-emitter)]
      (event/begin-message! em "m0")
      (event/emit-token! em :token "有字")
      (event/end-message! em)
      (event/ensure-text! em "final" "不该出现")
      (is (= 1 (count (filter #(= :message/delta (:type %)) @out)))))
    (let [[em out] (test-emitter)]
      (event/ensure-text! em "final" "return-direct 的结果")
      (is (= [:message/started :message/delta :message/ended] (map :type @out))))))

(deftest reasoning-block-opens-and-closes-test
  (testing "思维 token 是**独立的一条消息**，与正文各自开合"
    (let [[em out] (test-emitter)]
      (event/begin-message! em "m0")
      (event/emit-token! em :reasoning-token "先想")
      (event/emit-token! em :reasoning-token "一下")
      (event/emit-token! em :token "答案是")
      (event/end-message! em)
      (is (= [:reasoning/started :message/thinking :message/thinking
              :reasoning/ended :message/started :message/delta :message/ended]
             (mapv :type @out))
          "正文 token 一到，思考块当场收口")
      (is (= "m0-reasoning" (:message-id (first @out))))
      (is (apply = "m0-reasoning" (map :message-id (take 4 @out)))
          "思考块自己一个 id——与正文共用 id 会让客户端只认先到的那种消息")
      (is (apply = "m0" (map :message-id (drop 4 @out))))))

  (testing "只想不说：终态收尾时思考块也要关"
    (let [[em out] (test-emitter)]
      (event/begin-message! em "m0")
      (event/emit-token! em :reasoning-token "想")
      (event/finish! em :run/finished {})
      (is (= [:reasoning/started :message/thinking :reasoning/ended :run/finished]
             (mapv :type @out)))
      (is (false? (event/text-emitted? em)) "思维 token 不算正文")))

  (testing "想→说→又想：来回切换，块与块之间不粘连"
    (let [[em out] (test-emitter)]
      (event/begin-message! em "m0")
      (event/emit-token! em :reasoning-token "想A")
      (event/emit-token! em :token "说A")
      (event/emit-token! em :reasoning-token "想B")
      (event/end-message! em)
      (is (= [:reasoning/started :message/thinking :reasoning/ended
              :message/started :message/delta
              :reasoning/started :message/thinking
              :reasoning/ended :message/ended]
             (mapv :type @out)))
      (is (= 2 (count (filter #(= :reasoning/started (:type %)) @out)))))))
