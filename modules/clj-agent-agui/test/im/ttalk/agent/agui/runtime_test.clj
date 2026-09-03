(ns im.ttalk.agent.agui.runtime-test
  "runtime 的行为契约（docs/agent-runtime-design.md §9.6 测试清单）。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.agui.support :as sup]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.simple-agent :as agent]
            [im.ttalk.agent.tool :refer [deftool]]))

(def side-effects
  "工具真的跑过没有——`stop!` 之后它仍然会跑完（JVM 上取消不是抢占）。"
  (atom []))

(def tool-gate
  "工具里的闸门：测试 deliver 之前它不返回。取消的时序因此是确定的，不靠 sleep 猜。"
  (atom nil))

(deftool slow-mark
  "卡在闸门上，放行后打个记号（用来验证「取消不杀正在跑的工具」）"
  [[label :string "记号"]]
  (when-let [g @tool-gate] (deref g 5000 nil))
  (swap! side-effects conj label)
  (str "已标记 " label))

(defn- tool-call-response [id name args]
  {:text nil :tool-calls [{:id id :name name :args args}]})

(defn- run-to-terminal!
  [r conv-id message]
  (let [c (sup/collector)
        unsub (rt/subscribe r conv-id {:on-event (:on-event c)})
        started (rt/start-run! r conv-id message)]
    (sup/wait-for #(sup/terminal-event ((:events c))))
    (unsub)
    {:events ((:events c)) :started started}))

(deftest detached-run-test
  (testing "订阅者中途退订，run 照常跑完，历史照常进 ChatMemory"
    (let [store (memory/in-memory-store)
          r (sup/runtime {:provider (sup/provider [{:text "一二三四五六"}] {:chunk-size 1 :delay-ms 5})
                          :memory store})
          c (sup/collector)
          unsub (rt/subscribe r "c1" {:on-event (:on-event c)})]
      (rt/start-run! r "c1" "hi")
      (sup/wait-for #(some #{:message/delta} ((:types c))))
      (unsub)                                    ;; 断线：连接没了
      (is (sup/wait-for #(= :idle (:state (rt/run-status r "c1"))))
          "run 与请求解耦——订阅者走了它照跑")
      (is (= "一二三四五六"
             (->> (memory/mem-get store "c1") (filter #(= :assistant (:role %))) last :content)))
      (rt/shutdown! r))))

(deftest reconnect-since-test
  (testing "断线重连按 :since 补缺口——拼起来与全程在线逐字相同"
    (let [r (sup/runtime {:provider (sup/provider [{:text "一二三四五六七八"}] {:chunk-size 1 :delay-ms 5})})
          full (sup/collector)
          a (sup/collector)
          _ (rt/subscribe r "c1" {:on-event (:on-event full)})
          unsub-a (rt/subscribe r "c1" {:on-event (:on-event a)})]
      (rt/start-run! r "c1" "hi")
      (sup/wait-for (fn [] (>= (count (filter #(= :message/delta (:type %)) ((:events a)))) 2)))
      (unsub-a)
      (let [seen (mapv :seq ((:events a)))
            b (sup/collector)]
        (sup/wait-for #(sup/terminal-event ((:events full))))
        (rt/subscribe r "c1" {:since (last seen) :on-event (:on-event b)})
        (is (= (mapv :seq ((:events full)))
               (into (mapv :seq ((:events a))) (mapv :seq ((:events b)))))
            "A 收到的 ++ 重连后补的 == 全程在线收到的")
        (is (= ((:events full)) (into ((:events a)) ((:events b))))))
      (rt/shutdown! r)))

  (testing ":since 缺省 = 只收新事件（不重放）"
    (let [r (sup/runtime {:provider (sup/provider [{:text "ok"}])})]
      (run-to-terminal! r "c1" "hi")
      (let [late (sup/collector)]
        (rt/subscribe r "c1" {:on-event (:on-event late)})
        (is (empty? ((:events late)))))
      (rt/shutdown! r))))

(deftest resync-when-behind-test
  (testing ":since 早于缓冲起点 → 先发 :run/resync（ChatMemory 快照）再接 live"
    (let [r (sup/runtime {:provider (sup/provider [{:text "第一轮"} {:text "第二轮"}])}
                         {:buffer-size 3})]
      (run-to-terminal! r "c1" "hi")
      (run-to-terminal! r "c1" "再来")
      (let [c (sup/collector)]
        (rt/subscribe r "c1" {:since 0 :on-event (:on-event c)})
        (let [evs ((:events c))]
          (is (= :run/resync (:type (first evs))))
          (is (seq (:messages (first evs))) "快照来自 ChatMemory，不是第二个真相店")
          (is (= ["hi" "第一轮" "再来" "第二轮"]
                 (mapv :content (:messages (first evs)))))))
      (rt/shutdown! r))))

(deftest concurrent-start-test
  (testing "并发 start-run! 同一会话：恰好一个 :started，其余 :busy"
    (let [hold (promise)
          r (sup/runtime {:provider (sup/provider (repeat 200 {:text "在跑"}) {:hold hold})})
          results (->> (range 50)
                       (mapv (fn [_] (future (rt/start-run! r "c1" "hi"))))
                       (mapv deref))
          _ (deliver hold true)]
      (is (= 1 (count (filter #(= :started (:status %)) results))))
      (is (= 49 (count (filter #(= :busy (:status %)) results))))
      (is (apply = (keep :run-id results)) ":busy 报的是正在跑的那个 run-id")
      (rt/shutdown! r))))

(deftest supersede-test
  (testing ":supersede：旧 run 落 :run/cancelled（**不是 :error**），新 run 正常完成"
    (let [hold (promise)
          r (sup/runtime {:provider (sup/provider [{:text "慢的一轮"} {:text "新的一轮"}]
                                                  {:chunk-size 1 :hold hold})}
                         {:on-concurrent :supersede})
          c (sup/collector)]
      (rt/subscribe r "c1" {:on-event (:on-event c)})
      (let [first-run (rt/start-run! r "c1" "第一句")]
        (sup/wait-for (fn [] (some #{:message/delta} ((:types c)))))
        ;; 旧 run 卡在闸门上 → supersede 一定发生在它跑完之前（不靠 sleep 赌）
        (let [f (future (rt/start-run! r "c1" "第二句"))
              _ (sup/wait-for (fn [] (:stopping? (rt/run-status r "c1"))) 2000)
              _ (deliver hold true)
              second-run (deref f 5000 ::timeout)]
          (is (= :started (:status second-run)))
          (is (not= (:run-id first-run) (:run-id second-run)))
          (sup/wait-for (fn [] (= :idle (:state (rt/run-status r "c1")))))
          (let [by-run (group-by :run-id ((:events c)))
                old-evs (get by-run (:run-id first-run))
                new-evs (get by-run (:run-id second-run))
                terminal? #(#{:run/cancelled :run/error :run/finished :run/paused} (:type %))]
            (is (= :run/cancelled (:type (last old-evs))))
            (is (= :run/finished (:type (last new-evs))))
            (is (= 1 (count (filter terminal? old-evs))) "旧 run 也只有一个终态"))))
      (rt/shutdown! r))))

(deftest stop-semantics-test
  (testing "stop! 后终态是 :cancelled；**正在跑的工具照样跑完**（JVM 无抢占）"
    (reset! side-effects [])
    (reset! tool-gate (promise))
    (let [r (sup/runtime {:provider (sup/provider [(tool-call-response "tc1" "slow-mark" {:label "A"})
                                                   {:text "不该跑到这里"}])
                          :tools [#'slow-mark]})
          c (sup/collector)]
      (rt/subscribe r "c1" {:on-event (:on-event c)})
      (rt/start-run! r "c1" "跑工具")
      (sup/wait-for #(some #{:tool/started} ((:types c))))
      ;; 工具卡在闸门上：stop 一定落在「工具正在跑」这个窗口里
      (is (true? (rt/stop! r "c1")) "登记成功")
      (is (false? (rt/stop! r "c1")) "重复 stop 是 no-op")
      (is (true? (:stopping? (rt/run-status r "c1"))) "「正在停止…」——还没停稳")
      (deliver @tool-gate true)
      (is (sup/wait-for #(sup/terminal-event ((:events c)))))
      (is (= :run/cancelled (:type (sup/terminal-event ((:events c))))))
      (is (= ["A"] @side-effects) "取消 = 放弃等待，不是杀线程")
      (rt/shutdown! r)))

  (testing "stop! 带 run-id 只停那一个，不误伤别的"
    (let [hold (promise)
          r (sup/runtime {:provider (sup/provider (repeat 10 {:text "在跑"}) {:hold hold})})]
      (rt/start-run! r "c1" "hi")
      (is (false? (rt/stop! r "c1" "别的 run-id")))
      (is (false? (rt/stop! r "没有这个会话")))
      (deliver hold true)
      (rt/shutdown! r))))

(deftest error-run-test
  (testing "provider 抛异常：补关开着的块 + 恰好一个 :run/error"
    (let [r (sup/runtime {:provider (sup/provider [(fn [] (throw (ex-info "boom" {})))])})
          {:keys [events]} (run-to-terminal! r "c1" "hi")]
      (is (= :run/error (:type (last events))))
      (is (= 1 (count (filter #(#{:run/error :run/finished :run/cancelled :run/paused} (:type %)) events))))
      (rt/shutdown! r))))

(deftest subscriber-isolation-test
  (testing "抛异常的订阅者被摘除，其他订阅者与 run 不受影响"
    (let [r (sup/runtime {:provider (sup/provider [{:text "一二三"}] {:chunk-size 1})})
          good (sup/collector)
          bad-calls (atom 0)]
      (rt/subscribe r "c1" {:on-event (fn [_] (swap! bad-calls inc) (throw (ex-info "订阅者炸了" {})))})
      (rt/subscribe r "c1" {:on-event (:on-event good)})
      (let [{:keys [events]} (run-to-terminal! r "c1" "hi")]
        (is (= 1 @bad-calls) "炸一次就被摘")
        (is (= :run/finished (:type (last ((:events good))))))
        (is (seq events)))
      (rt/shutdown! r)))

  (testing "慢订阅者不改变别人的事件顺序"
    (let [r (sup/runtime {:provider (sup/provider [{:text "一二三四"}] {:chunk-size 1})})
          fast (sup/collector)]
      (rt/subscribe r "c1" {:on-event (fn [_] (Thread/sleep 3))})
      (rt/subscribe r "c1" {:on-event (:on-event fast)})
      (let [_ (run-to-terminal! r "c1" "hi")
            seqs (mapv :seq ((:events fast)))]
        (is (= (sort seqs) seqs)))
      (rt/shutdown! r))))

(deftest shutdown-test
  (testing "shutdown!：in-flight run 被停、订阅者收到 on-close、表清空"
    (let [r (sup/runtime {:provider (sup/provider (repeat 5 {:text "很慢"}) {:chunk-size 1 :delay-ms 30})})
          closed (atom [])
          c (sup/collector)]
      (rt/subscribe r "c1" {:on-event (:on-event c) :on-close #(swap! closed conj %)})
      (rt/start-run! r "c1" "hi")
      (sup/wait-for #(seq ((:events c))))
      (rt/shutdown! r)
      (is (= [:shutdown] @closed))
      (is (empty? (rt/conversations r)))
      (is (sup/terminal-event ((:events c))) "被停的 run 也有终态")
      (is (thrown? clojure.lang.ExceptionInfo (rt/start-run! r "c1" "关了还想起跑"))))))

(deftest same-semantics-as-chat-test
  (testing "事件流的最终文本 ≡ agent/chat 的返回值（不长出第二套语义）"
    (let [responses [{:text "同一个答案"}]
          r (sup/runtime {:provider (sup/provider responses)})
          {:keys [events]} (run-to-terminal! r "c1" "hi")
          direct (agent/chat (agent/create-agent {:provider (sup/provider responses)
                                                  :conversation-id "c2"})
                             "hi")]
      (is (= (:text direct) (:text (sup/terminal-event events))))
      (is (= (:text direct) (sup/text-of events)))
      (rt/shutdown! r))))

(deftest tool-events-test
  (testing "工具事件按 start → args → end → result，且 id 全程串得起来"
    (reset! side-effects [])
    (reset! tool-gate nil)
    (let [r (sup/runtime {:provider (sup/provider [(tool-call-response "tc-9" "slow-mark" {:label "B"})
                                                   {:text "跑完了"}])
                          :tools [#'slow-mark]})
          {:keys [events]} (run-to-terminal! r "c1" "跑工具")
          tool-evs (filterv #(#{:tool/started :tool/args :tool/ended :tool/result} (:type %)) events)]
      (is (= [:tool/started :tool/args :tool/ended :tool/result] (mapv :type tool-evs)))
      (is (every? #(= "tc-9" (:tool-call-id %)) tool-evs))
      (is (= {:label "B"} (:args (second tool-evs))))
      (is (= "已标记 B" (:content (last tool-evs))))
      (is (= :run/finished (:type (last events))))
      (rt/shutdown! r))))

(deftest start-run-returns-watermark-test
  (testing "start-run! 交出起跑前的 seq 水位，拿它订阅一条不漏——run 立刻起跑，"
    (testing "而调用方要等返回值才订阅，中间那段真空只能靠 :since 补"
      (let [hold (promise)
            r (sup/runtime {:provider (sup/provider [{:text "第一轮"} {:text "第二轮"}]
                                                    {:hold hold})})]
        ;; 第一个 run：全程没有订阅者
        (let [{:keys [since]} (rt/start-run! r "c1" "hi")]
          (is (= -1 since) "全新会话的水位是 -1")
          (deliver hold true))
        (sup/wait-for #(= :idle (:state (rt/run-status r "c1"))))
        ;; 第二个 run：拿 :since 订阅，必须**从 :run/started 开始**
        (let [{:keys [since]} (rt/start-run! r "c1" "再来")
              c (sup/collector)]
          (rt/subscribe r "c1" {:since since :on-event (:on-event c)})
          (is (sup/wait-for #(sup/terminal-event ((:events c)))))
          (is (= :run/started (:type (first ((:events c)))))
              "第一条就是 :run/started——传 nil 的话它已经溜过去了")
          (is (= :run/finished (:type (last ((:events c)))))))
        (rt/shutdown! r)))))

(deftest run-status-test
  (let [r (sup/runtime {:provider (sup/provider [{:text "ok"}])})]
    (is (nil? (rt/run-status r "不存在")))
    (run-to-terminal! r "c1" "hi")
    (let [st (rt/run-status r "c1")]
      (is (= :idle (:state st)))
      (is (false? (:stopping? st)))
      (is (= ["c1"] (rt/conversations r)))
      (is (pos? (:seq st))))
    (rt/shutdown! r)))
