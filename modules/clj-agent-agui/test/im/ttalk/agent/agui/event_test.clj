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

;;; ============================================================
;;; 子 agent lane（docs/subagent-event-attribution-design.md §3.3）
;;; ============================================================

(defn- run-emitter
  "带取号器与出口的 run 发射器。返回 {:em :out :n}——`:n` 是**取号水位**，
   「被吞掉的事件不占号」这条断言全靠它。"
  []
  (let [out (atom [])
        n   (atom -1)]
    {:em (event/emitter {:run-id "r1" :conversation-id "c1"
                         :next-seq #(swap! n inc)
                         :sink #(swap! out conj %)
                         :now (constantly 1000)})
     :out out
     :n n}))

(defn- types-of [out] (mapv :type @out))

(deftest lane-tags-every-event-test
  (testing "lane 发出的每条事件都带归属；父 run 自己的不带"
    (let [{:keys [em out]} (run-emitter)
          lane (event/subagent-emitter em {:subagent-run-id "sa-1"})]
      (event/emit! em :run/started {})
      (event/start-subagent! lane {:name "research_agent" :task "查一下"})
      (event/begin-message! lane "sa-1-m0")
      (event/emit-token! lane :token "事实一")
      (event/finish-subagent! lane {:outcome :success})
      (let [[parent & lane-evs] @out]
        (is (nil? (:subagent-run-id parent)) "run 的发射器无 tag")
        (is (every? #(= "sa-1" (:subagent-run-id %)) lane-evs))
        (is (every? #(= "r1" (:run-id %)) @out) "lane 仍属于父 run")
        (is (every? #(= "c1" (:conversation-id %)) @out)))
      (is (= [:run/started :subagent/started :message/started :message/delta
              :message/ended :subagent/finished]
             (types-of out)))
      (is (= "research_agent" (:name (second @out))))
      (is (= "查一下" (:task (second @out)))))))

(deftest lane-shares-session-seq-test
  (testing "契约 1：父 run 与两条 lane 交错发事件，:seq 仍单调无洞、无重号"
    (let [{:keys [em out]} (run-emitter)
          a (event/subagent-emitter em {:subagent-run-id "sa-a"})
          b (event/subagent-emitter em {:subagent-run-id "sa-b"})]
      (event/emit! em :run/started {})
      (event/start-subagent! a {:name "a"})
      (event/start-subagent! b {:name "b"})
      (event/begin-message! a "sa-a-m0")
      (event/begin-message! b "sa-b-m0")
      (event/emit-token! a :token "a1")
      (event/emit-token! b :token "b1")
      (event/emit-token! a :token "a2")
      (event/finish-subagent! a {:outcome :success})
      (event/finish-subagent! b {:outcome :success})
      (event/finish! em :run/finished {:text "done"})
      (is (= (range (count @out)) (map :seq @out)))
      (is (event/terminal? (last @out)) "终态仍是最后一条")
      (is (= 1 (count (filter event/terminal? @out))) "lane 的收尾不算 run 终态"))))

(deftest lane-silenced-after-run-terminal-test
  (testing "契约 2：父 run 终态之后，lane 的事件被整条吞掉，且**不占号**"
    (let [{:keys [em out n]} (run-emitter)
          lane (event/subagent-emitter em {:subagent-run-id "sa-1"})]
      (event/emit! em :run/started {})
      (event/finish! em :run/finished {:text "父 run 先跑完了"})
      (let [water @n
            tail  (count @out)]
        (event/start-subagent! lane {:name "迟到的子 agent"})
        (event/begin-message! lane "sa-1-m0")
        (event/emit-token! lane :token "没人听得见")
        (event/finish-subagent! lane {:outcome :success})
        (is (= tail (count @out)) "一条都没漏出去")
        (is (= water @n) "被吞掉的事件不占号——在 sink 里丢会留下永远补不回来的 seq 洞")
        (is (= 4 (event/silenced-count lane))
            "started + message/started + message/delta + finished，逐条计数。
             收尾时补关的 message/ended 不在里面——记账也在 deliver! 里，
             被吞掉的事件从没进过开集合，close-open! 于是无事可补")
        (is (zero? (event/drops lane)) "silenced 与 drops 是两码事")
        (is (= :run/finished (:type (last @out))))))))

(defn- session-emitter
  "照 runtime 那样接线：取号与出口**各自**拿会话锁，发射器再拿同一把当 `:gate`。
   —— 只有把三件事并进一次锁，并发下的到达顺序才等于取号顺序。"
  []
  (let [lock (Object.) n (atom -1) out (atom [])]
    {:lock lock :out out
     :em (event/emitter {:run-id "r1" :conversation-id "c1"
                         :next-seq #(locking lock (swap! n inc))
                         :sink     #(locking lock (swap! out conj %))
                         :gate lock
                         :now (constantly 1000)})}))

(deftest arrival-order-equals-seq-order-test
  (testing "并发发射：到达顺序 = 取号顺序。

            取号一次锁、投递另一次锁的话中间敞着窗口——A 取到 9、B 取到 10、
            B 先投递、A 再投递。SSE 的 `id:` 用的就是这个号，浏览器重连原样回传
            成 Last-Event-ID → :since；到达乱序会让客户端把水位记高，**后到的
            小号事件被当成已收，续传时直接跳过**。"
    (let [{:keys [em out]} (session-emitter)
          threads (mapv (fn [t]
                          (Thread. ^Runnable
                                   (fn [] (dotimes [i 60]
                                            (event/emit! em :message/delta
                                                         {:message-id (str "m" t) :text (str i)})))))
                        (range 8))]
      (doseq [^Thread t threads] (.start t))
      (doseq [^Thread t threads] (.join t))
      (let [seqs (mapv :seq @out)]
        (is (= 480 (count seqs)))
        (is (= (sort seqs) seqs) "到达顺序必须与号一致")
        (is (= (range 480) seqs) "无洞无重")))))

(deftest nothing-arrives-after-terminal-test
  (testing "契约 2：终态是最后一条——lane 与父 run 并发时也成立。

            守卫 `silenced?` 长在 lane 的 transform 上，判完到投出去之间不能让父
            run 插进来收口，否则「查的时候父还没终态、投的时候已经有了」。"
    (dotimes [_ 20]
      (let [{:keys [em out]} (session-emitter)
            lane (event/subagent-emitter em {:subagent-run-id "sa-1"})
            _ (event/start-subagent! lane {:name "researcher"})
            spam (Thread. ^Runnable
                          (fn [] (dotimes [i 200]
                                   (event/emit! lane :message/delta
                                                {:message-id "sa-1-m0" :text (str i)}))))]
        (.start spam)
        (event/finish! em :run/finished {:text "父 run 先收口"})
        (.join spam)
        (let [evs @out
              term-idx (.indexOf ^java.util.List (mapv event/terminal? evs) true)]
          (is (nat-int? term-idx))
          (is (= (dec (count evs)) term-idx) "终态之后一条都不许有")
          (is (= (sort (map :seq evs)) (map :seq evs))))))))

(deftest usage-rides-the-terminal-test
  (testing "每次 LLM 往返记一笔，终态那条一次性带出去"
    (let [{:keys [em out]} (session-emitter)]
      (event/record-usage! em {:model "m1" :input-tokens 10 :output-tokens 5})
      (event/record-usage! em {:model "m1" :input-tokens 20 :output-tokens 7})
      (event/finish! em :run/finished {:text "完"})
      (is (empty? (filter :usage (butlast @out))) "中途不发用量事件")
      (is (= [10 20] (mapv :input-tokens (:usage (last @out)))))))

  (testing "provider 没报 usage 就不记——宁可没这一格，也不给一排 0"
    (let [{:keys [em out]} (session-emitter)]
      (event/record-usage! em {:model "m1"})
      (event/record-usage! em nil)
      (event/finish! em :run/finished {})
      (is (nil? (:usage (last @out))))))

  (testing "**子 agent 的账算在这条 run 上**：lane 的用量累到根发射器"
    (let [{:keys [em out]} (session-emitter)
          lane (event/subagent-emitter em {:subagent-run-id "sa-1"})]
      (event/record-usage! em {:model "父" :input-tokens 1 :output-tokens 1})
      (event/record-usage! lane {:model "子" :input-tokens 2 :output-tokens 2})
      (event/finish! em :run/finished {})
      (is (= ["父" "子"] (mapv :model (:usage (last @out)))))
      (is (= 2 (count (event/usage-of lane))) "lane 上读到的也是根那一份"))))

(deftest lane-closed-before-run-terminal-test
  (testing "父 run 收口前，还开着的 lane 被主动关掉——AG-UI 要求每个开过的子代理
            在 RUN_FINISHED 前关闭。靠 `silenced?` 是不行的：那只是把 lane 的
            收尾吞掉，客户端看到的是一条永远不闭合的 SUBAGENT_STARTED"
    (let [{:keys [em out]} (run-emitter)
          lane (event/subagent-emitter em {:subagent-run-id "sa-1"})]
      (event/emit! em :run/started {})
      (event/start-subagent! lane {:name "researcher"})
      (event/begin-message! lane "sa-1-m0")
      (event/emit-token! lane :token "查到一半")
      ;; 子 agent 还开着，父 run 直接收口（:supersede / stop 就是这个形状）
      (event/finish! em :run/finished {:text "被打断了"})
      (let [types (mapv :type @out)]
        (is (= 1 (count (filter #(= :subagent/error (:type %)) @out)))
            "开着的 lane 补一条收尾")
        (is (< (.indexOf types :subagent/error) (.indexOf types :run/finished))
            "**必须排在 run 终态之前**——反过来就被 silenced? 吞了")
        (is (= (range (count @out)) (map :seq @out)) "补出来的事件照样占号，无洞")
        (is (= :run/finished (:type (last @out))) "终态仍是最后一条")
        (is (some #(and (= :message/ended (:type %)) (= "sa-1" (:subagent-run-id %))) @out)
            "lane 自己开着的正文消息也一并补关"))))

  (testing "被 stop / supersede 掐掉时，收尾说的是 killed 而不是「父先结束了」"
    (let [{:keys [em out]} (run-emitter)
          lane (event/subagent-emitter em {:subagent-run-id "sa-1"})]
      (event/start-subagent! lane {:name "researcher"})
      (event/request-stop! em)
      (event/finish! em :run/cancelled {})
      (let [fin (first (filter #(= :subagent/finished (:type %)) @out))]
        (is (= :killed (:outcome fin))
            "中立事件说 killed；译成 SUBAGENT_ERROR + code=killed 是 codec 的事"))))

  (testing "没宣告过自己的 lane 不补收尾——凭空冒出个未声明的子代理是纯噪声"
    (let [{:keys [em out]} (run-emitter)
          _lane (event/subagent-emitter em {:subagent-run-id "sa-1"})]
      (event/emit! em :run/started {})
      (event/finish! em :run/finished {:text "父 run 跑完了"})
      (is (empty? (filter #(#{:subagent/started :subagent/finished :subagent/error} (:type %))
                          @out)))))

  (testing "嵌套：内层先于外层收口"
    (let [{:keys [em out]} (run-emitter)
          outer (event/subagent-emitter em {:subagent-run-id "sa-1"})
          inner (event/subagent-emitter outer {:subagent-run-id "sa-2"})]
      (event/start-subagent! outer {:name "analyst"})
      (event/start-subagent! inner {:name "researcher"})
      (event/finish! em :run/finished {})
      (let [closes (filterv #(#{:subagent/finished :subagent/error} (:type %)) @out)]
        (is (= ["sa-2" "sa-1"] (mapv :subagent-run-id closes))
            "内层先关，外层后关")))))

(deftest lane-isolation-test
  (testing "契约 4：一条 lane 一个发射器实例，交错的 token 不进同一条消息"
    (let [{:keys [em out]} (run-emitter)
          a (event/subagent-emitter em {:subagent-run-id "sa-a"})
          b (event/subagent-emitter em {:subagent-run-id "sa-b"})]
      (event/begin-message! a "sa-a-m0")
      (event/begin-message! b "sa-b-m0")
      (event/emit-token! a :token "甲一")
      (event/emit-token! b :token "乙一")
      (event/emit-token! a :token "甲二")
      (event/end-message! a)
      (event/end-message! b)
      (let [by-lane (group-by :subagent-run-id @out)]
        (is (= #{"sa-a" "sa-b"} (set (keys by-lane))))
        (is (= [:message/started :message/delta :message/delta :message/ended]
               (mapv :type (by-lane "sa-a"))))
        (is (= [:message/started :message/delta :message/ended]
               (mapv :type (by-lane "sa-b"))))
        (is (apply = "sa-a-m0" (map :message-id (by-lane "sa-a"))))
        (is (apply = "sa-b-m0" (map :message-id (by-lane "sa-b"))))))))

(deftest lane-nesting-test
  (testing "嵌套 lane 带上父 lane 的 id"
    (let [{:keys [em out]} (run-emitter)
          outer (event/subagent-emitter em {:subagent-run-id "sa-1"})
          inner (event/subagent-emitter outer {:subagent-run-id "sa-2"})]
      (event/start-subagent! outer {:name "analyst"})
      (event/start-subagent! inner {:name "researcher"})
      (let [[o i] @out]
        (is (nil? (:parent-subagent-run-id o)))
        (is (= "sa-1" (:parent-subagent-run-id i)))
        (is (= "sa-2" (:subagent-run-id i))))))

  (testing "外层 lane 收口后，内层也闭嘴——守卫要递归查祖先，只看一级会漏在终态之后"
    (let [{:keys [em out n]} (run-emitter)
          outer (event/subagent-emitter em {:subagent-run-id "sa-1"})
          inner (event/subagent-emitter outer {:subagent-run-id "sa-2"})]
      (event/finish-subagent! outer {:outcome :success})
      (let [tail (count @out) water @n]
        (event/start-subagent! inner {:name "迟到的孙子"})
        (is (= tail (count @out)))
        (is (= water @n)))))

  (testing "父 run 终态后，隔了一层的内层 lane 同样闭嘴"
    (let [{:keys [em out]} (run-emitter)
          outer (event/subagent-emitter em {:subagent-run-id "sa-1"})
          inner (event/subagent-emitter outer {:subagent-run-id "sa-2"})]
      (event/finish! em :run/finished {})
      (let [tail (count @out)]
        (event/emit! inner :subagent/started {:name "x"})
        (is (= tail (count @out)) "外层 lane 还没收口，但 run 收了——祖先链上任意一个终态都算")))))

(deftest finish-subagent-test
  (testing "lane 收尾：补关自己开着的块，发一条收尾事件，幂等"
    (let [{:keys [em out]} (run-emitter)
          lane (event/subagent-emitter em {:subagent-run-id "sa-1"})]
      (event/begin-message! lane "sa-1-m0")
      (event/emit-token! lane :token "半句")
      (event/emit! lane :tool/started {:tool-call-id "tc1" :name "search"})
      (event/finish-subagent! lane {:outcome :success})
      (event/finish-subagent! lane {:outcome :killed})
      (is (= [:message/started :message/delta :tool/started
              :message/ended :tool/ended :tool/result :subagent/finished]
             (types-of out)))
      (is (= :success (:outcome (last @out))))
      (is (false? (event/terminal? (last @out)))
          "lane 的收尾不是 run 的终态——它必须过 transform，才受父 run 守卫的管")
      (is (re-find #"子 agent" (:content (first (filter #(= :tool/result (:type %)) @out))))
          "合成的工具结果说的是子 agent 没跑完，不是 run 没跑完")))

  (testing "失败走 :subagent/error，带 canonical error"
    (let [{:keys [em out]} (run-emitter)
          lane (event/subagent-emitter em {:subagent-run-id "sa-1"})]
      (event/finish-subagent! lane {:error {:class :provider-error :message "boom"}})
      (is (= [:subagent/error] (types-of out)))
      (is (= :provider-error (get-in (last @out) [:error :class])))
      (is (= "sa-1" (:subagent-run-id (last @out))))))

  (testing "kill / timeout 走 :outcome，不伪装成成功"
    (let [{:keys [em out]} (run-emitter)
          lane (event/subagent-emitter em {:subagent-run-id "sa-1"})]
      (event/finish-subagent! lane {:outcome :killed})
      (is (= :killed (:outcome (last @out)))))))

(deftest lane-emitter-never-throws-test
  (testing "§6.3 照旧：lane 的 sink 抛异常被兜住，且不吃号"
    (let [n (atom -1)
          calls (atom 0)
          em (event/emitter {:run-id "r1" :conversation-id "c1"
                             :next-seq #(swap! n inc)
                             :sink (fn [_] (swap! calls inc) (throw (ex-info "炸" {})))
                             :now (constantly 0)})
          lane (event/subagent-emitter em {:subagent-run-id "sa-1"})]
      (is (some? (event/start-subagent! lane {:name "x"})))
      (event/finish-subagent! lane {:outcome :success})
      (is (= 2 @calls))
      (is (= 2 (event/drops lane)))
      (is (= 1 @n)))))
