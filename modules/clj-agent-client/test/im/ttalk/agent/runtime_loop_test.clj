(ns im.ttalk.agent.runtime-loop-test
  "designs/runtime-borrow.md 的 react 侧挂点：临时提醒（R-04）、工具侧停回合 +
   tool-guard 端到端（R-03）、pause_turn（R-13）、生命周期事件（R-11）、
   一次暂停多条审批 / 逐件决定 / 改参数（§15）。"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.chat-client :as cc]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.events :as events]
            [im.ttalk.agent.filter.dead-loop :as dead-loop]
            [im.ttalk.agent.filter.memory :as mf]
            [im.ttalk.agent.filter.tool-guard :as tg]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.react :as react]
            [im.ttalk.agent.tool :refer [deftool]]))

(deftool echo "回显" [[x :string "x"]] (str "echo:" x))
(deftool boom "总失败" [[x :string "x"]] (throw (ex-info "后端 500" {})))
(deftool del "删除（敏感）" [[path :string "路径"]] {:sensitive true} (str "deleted " path))

(defn- scripted
  "按序返回响应；记录每次收到的消息。"
  [responses seen]
  (let [q (atom responses)]
    {:chat-fn (fn [ms _]
                (swap! seen conj (vec ms))
                (let [r (first @q)] (swap! q rest) (or r (response/make-response :text "完"))))}))

(defn- tc [id name args] (response/make-response :tool-calls [{:id id :name name :args args}]))

(defn- client [cm store & filters]
  (cc/build-chat-client {:chat-model cm :tools [#'echo #'boom #'del]
                         :filters (into (if store [(mf/memory-filter store)] []) filters)}))

(defn- run [c store cid & [opts]]
  (react/invoke c store [(msg/user "开始")]
                (merge {:context (ctx/with-conversation-id (ctx/create) cid)} opts)))

;;; ------------------------------------------------------------ 临时提醒 + 死循环

(deftest notes-hook-test
  (testing ":iteration filter 给的 :notes 只附在本次请求末尾，不进 store"
    (let [seen (atom []) store (memory/in-memory-store)
          once (atom true)
          noter {:name :noter
                 :iteration (fn [req chain]
                              (chain (if (and (pos? (:index req)) @once)
                                       (do (reset! once false) (assoc req :notes ["注意别打转"]))
                                       req)))}
          c (client (scripted [(tc "a" "echo" {:x "1"})] seen) store noter)
          r (run c store "n-1")]
      (is (= :completed (:status r)))
      (is (str/includes? (:content (peek (second @seen))) "<system-reminder>"))
      (is (str/includes? (:content (peek (second @seen))) "注意别打转"))
      (is (not-any? #(str/includes? (str (:content %)) "注意别打转") (memory/mem-get store "n-1")))))
  (testing "没有 memory filter 时拼进本轮 delta"
    (let [seen (atom [])
          noter {:name :noter :iteration (fn [req chain] (chain (assoc req :notes ["N"])))}
          c (client (scripted [] seen) nil noter)]
      (react/invoke c nil [(msg/user "q")] {})
      (is (str/includes? (:content (peek (first @seen))) "N")))))

(deftest dead-loop-test
  (let [seen (atom []) store (memory/in-memory-store)
        rounds (repeat 5 (tc "x" "echo" {:x "same"}))
        c (client (scripted (map-indexed (fn [i r] (assoc-in r [:tool-calls 0 :id] (str "c" i))) rounds) seen)
                  store (dead-loop/dead-loop-filter))
        r (run c store "d-1" {:max-iterations 10})
        reminded (keep-indexed (fn [i ms] (when (str/includes? (str (:content (peek ms))) "打转") i)) @seen)]
    (is (= :completed (:status r)))
    (testing "连续 4 轮同指纹 → 第 5 次请求带提醒（只一次，提醒后窗口清空）"
      (is (= [4] reminded)))
    (testing "store 无痕"
      (is (not-any? #(str/includes? (str (:content %)) "打转") (memory/mem-get store "d-1"))))))

;;; ------------------------------------------------------------ tool-guard 端到端

(deftest tool-guard-stop-turn-test
  (let [seen (atom []) store (memory/in-memory-store)
        calls (map #(tc (str "b" %) "boom" {:x "1"}) (range 6))
        c (client (scripted calls seen) store (tg/tool-guard-filter :disable-threshold nil))
        r (run c store "g-1" {:max-iterations 10})]
    (testing "第 4 次同参失败 → 停回合，:stopped-by :tool-guard"
      (is (= :completed (:status r)))
      (is (= :tool-guard (:stopped-by r)))
      (is (str/includes? (get-in r [:response :text]) "到此为止"))
      (is (= 4 (count (:tool-calls-made r))))
      (is (nil? (:loop/stop (:tool-context r)))))
    (testing "第 3 次的结果是提醒"
      (is (some #(str/includes? (str (:content %)) "不要再原样重试") (memory/mem-get store "g-1"))))
    (testing "停回合那批工具结果补落库（没有悬空 tool_use）"
      (let [h (memory/mem-get store "g-1")]
        (is (= (count (mapcat :tool-calls (filter msg/assistant? h)))
               (count (filter msg/tool? h))))))))

(deftest tool-guard-survives-pause-test
  (testing "计数在 context 里，随暂停快照 EDN 往返后 resume 接着数"
    (let [store (memory/in-memory-store)
          resp [(tc "b1" "boom" {:x "1"})
                (tc "b2" "boom" {:x "1"})
                (response/make-response :tool-calls [{:id "d1" :name "del" :args {:path "/x"}}])
                (tc "b3" "boom" {:x "1"})]
          c (client (scripted resp (atom [])) store (tg/tool-guard-filter :disable-threshold nil))
          gate (fn [t] (if (= "del" (:name t)) :pause :proceed))
          r (run c store "g-2" {:tool-gate gate})
          rt (edn/read-string (pr-str (select-keys r [:loop-state :tool-context])))]
      (is (= :paused (:status r)))
      (is (= 2 (get-in rt [:tool-context :tool-guard/state :repeats (tg/fingerprint "boom" {:x "1"})])))
      (let [r2 (react/resume c (:loop-state rt) :approved
                             {:context (:tool-context rt) :tool-gate gate})]
        (is (some #(str/includes? (str (:content %)) "第 3 次") (memory/mem-get store "g-2")))
        (is (= :completed (:status r2)))))))

;;; ------------------------------------------------------------ pause_turn

(deftest pause-turn-test
  (testing "pause → 原样续跑（不加 user 消息）→ 最终文本"
    (let [seen (atom []) store (memory/in-memory-store)
          paused (response/make-response :text "搜索中…" :finish-reason "pause_turn"
                                         :replay-blocks {:format :anthropic-content :data [{:type "server_tool_use"}]})
          c (client (scripted [paused (response/make-response :text "答案")] seen) store)
          r (run c store "p-1")]
      (is (= "答案" (get-in r [:response :text])))
      (is (= 2 (count @seen)))
      (testing "第二次请求以那条 assistant 消息结尾，没有新增 user 消息"
        (is (msg/assistant? (peek (second @seen))))
        (is (= 1 (count (filter msg/user? (second @seen))))))))
  (testing "预算耗尽：按当前响应收尾，不抛"
    (let [paused (response/make-response :text "还在搜" :finish-reason "pause_turn")
          c (client (scripted (repeat 5 paused) (atom [])) (memory/in-memory-store))
          r (run c (memory/in-memory-store) "p-2" {:max-iterations 2})]
      (is (= :completed (:status r)))
      (is (= "还在搜" (get-in r [:response :text])))))
  (testing "没有内容不续"
    (let [seen (atom [])
          c (client (scripted [(response/make-response :finish-reason "pause_turn")] seen) nil)]
      (react/invoke c nil [(msg/user "q")] {})
      (is (= 1 (count @seen))))))

;;; ------------------------------------------------------------ 事件

(deftest events-test
  (let [evs (atom []) store (memory/in-memory-store)
        c (client (scripted [(response/make-response :tool-calls [{:id "e1" :name "echo" :args {:x "1"}}]
                                                     :usage {:input_tokens 10 :output_tokens 2})
                             (response/make-response :text "好" :usage {:input_tokens 20 :output_tokens 3})]
                            (atom []))
                  store)
        mc (events/metrics-collector)
        r (run c store "e-1" {:callbacks {:on-event (fn [e] (swap! evs conj e) ((:on-event mc) e))
                                          :metadata {:conversation-id "e-1" :run-id "r1"}}})
        types (mapv :type @evs)]
    (is (= :completed (:status r)))
    (testing "顺序与成对"
      (is (= [:turn/started :iteration/started :llm/finished :usage :tool/started :tool/finished
              :iteration/started :llm/finished :usage :turn/finished]
             types))
      (is (every? events/event-types types)))
    (testing "usage 累计、工具状态、元数据"
      (is (= 30 (get-in (last @evs) [:usage :input-tokens])))
      (is (= :ok (:status (first (filter #(= :tool/finished (:type %)) @evs)))))
      (is (every? #(= "r1" (:run-id %)) @evs)))
    (testing "metrics-collector"
      (let [s ((:snapshot mc))]
        (is (= 1 (:turns s)))
        (is (= 2 (:llm-calls s)))
        (is (= 1 (:tool-calls s)))
        (is (= 5 (get-in s [:tokens :output-tokens]))))))
  (testing "出口抛异常被吞；流式有 TTFT"
    (let [ttft (atom nil)
          cm {:chat-fn (fn [_ _] (response/make-response :text "x"))
              :stream-fn (fn [_ _ on-tok] (Thread/sleep 5) (on-tok {:token "x"}) (response/make-response :text "x"))}
          c (cc/build-chat-client {:chat-model cm})
          r (react/invoke c nil [(msg/user "q")]
                          {:on-token (fn [_])
                           :callbacks {:on-event (fn [e]
                                                   (when (= :llm/finished (:type e)) (reset! ttft (:ttft-ms e)))
                                                   (throw (ex-info "UI 坏了" {})))}})]
      (is (= :completed (:status r)))
      (is (number? @ttft)))))

;;; ------------------------------------------------------------ 多条审批 / 逐件决定

(deftest multi-approval-test
  (let [two-dels (response/make-response :tool-calls [{:id "d1" :name "del" :args {:path "/a"}}
                                                      {:id "e1" :name "echo" :args {:x "hi"}}
                                                      {:id "d2" :name "del" :args {:path "/b"}}])
        gate (fn [t] (if (= "del" (:name t)) {:pause {:reason "删除要审批"}} :proceed))
        mk (fn [] (let [store (memory/in-memory-store)]
                    [store (client (scripted [two-dels] (atom [])) store)]))]
    (testing "一次暂停带全部待审批调用"
      (let [[store c] (mk)
            r (run c store "m-1" {:tool-gate gate})]
        (is (= :paused (:status r)))
        (is (= ["d1" "d2"] (mapv #(get-in % [:tool-call :id]) (:pending-tools r))))
        (is (= ["d1" "d2"] (get-in r [:loop-state :pending-ids])))
        (is (= "删除要审批" (get-in r [:pending-tool :approval :reason])))))
    (testing "逐件决定：d1 改参数后批准、d2 拒绝带理由；非审批调用照常执行"
      (let [[store c] (mk)
            r (run c store "m-2" {:tool-gate gate})
            r2 (react/resume c (:loop-state r) :rejected
                             {:context (:tool-context r) :tool-gate gate
                              :payload {:decisions {"d1" {:decision "approved" :args {:path "/safe"}}
                                                    :d2 {:decision :rejected :message "不许删 b"}}}})
            results (into {} (map (juxt :tool-call-id :content)) (filter msg/tool? (memory/mem-get store "m-2")))]
        (is (= :completed (:status r2)))
        (is (= "deleted /safe" (results "d1")))
        (is (= "echo:hi" (results "e1")))
        (is (= "已拒绝执行：不许删 b" (results "d2")))))
    (testing "逐件决定须答全答对：漏答 / 未知 id 抛"
      (let [[store c] (mk)
            r (run c store "m-3" {:tool-gate gate})
            go (fn [ds] (try (react/resume c (:loop-state r) :approved
                                           {:context (:tool-context r) :tool-gate gate :payload {:decisions ds}})
                             (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))]
        (is (= :interrupt-incomplete (go {"d1" {:decision :approved}})))
        (is (= :interrupt-unknown (go {"d1" {:decision :approved} "d2" {:decision :rejected} "zz" {:decision :approved}})))
        (go {"d1" {:decision :approved} "d2" {:decision :reply :message "先别删，告诉我原因"}})
        (let [results (into {} (map (juxt :tool-call-id :content)) (filter msg/tool? (memory/mem-get store "m-3")))]
          (is (= "deleted /a" (results "d1")))
          (is (= "先别删，告诉我原因" (results "d2"))))))
    (testing "暂停态 EDN 往返（新增键同样可序列化）"
      (let [[store c] (mk)
            r (run c store "m-4" {:tool-gate gate})]
        (is (= (select-keys r [:loop-state :pending-tools])
               (edn/read-string (pr-str (select-keys r [:loop-state :pending-tools])))))))))
