(ns im.ttalk.agent.core.kernel.advisor-test
  "洋葱式 Advisor 执行器测试

   覆盖：洋葱序(before 正序 / after 逆序) / 请求-响应改写 / 短路(不调 chain) /
   around(重试、计时 try-finally) / phase 过滤 / order 排序 / 空链。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.core.kernel.filter :as flt]))

;;; ============================================================
;;; 洋葱序：before 外→内，after 内→外
;;; ============================================================

(deftest onion-order-test
  (testing "before 按 order 正序、after 逆序，terminal 居中"
    (let [log (atom [])
          mk  (fn [name]
                (flt/create-advisor name :chat
                  :order (case name :a 0 :b 10)
                  :before (fn [req] (swap! log conj [:before name]) req)
                  :after  (fn [resp] (swap! log conj [:after name]) resp)))
          terminal (fn [req] (swap! log conj [:terminal]) {:resp :ok})
          chain (flt/build-chain [(mk :b) (mk :a)] terminal)  ;; 乱序传入
          out (chain {:req 1})]
      (is (= {:resp :ok} out))
      ;; a(order 0) 最外层：before a → before b → terminal → after b → after a
      (is (= [[:before :a] [:before :b] [:terminal] [:after :b] [:after :a]]
             @log)))))

;;; ============================================================
;;; 请求 / 响应改写穿透
;;; ============================================================

(deftest rewrite-flows-through-test
  (testing "before 改 req、after 改 resp，逐层叠加"
    (let [a (flt/create-advisor :a :chat :order 0
              :before (fn [req] (update req :n inc))
              :after  (fn [resp] (update resp :tag conj :a)))
          b (flt/create-advisor :b :chat :order 10
              :before (fn [req] (update req :n * 10))
              :after  (fn [resp] (update resp :tag conj :b)))
          terminal (fn [req] {:seen-n (:n req) :tag []})
          out ((flt/build-chain [a b] terminal) {:n 1})]
      ;; before: a 先 (1->2)，b 后 (2->20) → terminal 看到 20
      (is (= 20 (:seen-n out)))
      ;; after: b 先 (内层) 再 a → [:b :a]
      (is (= [:b :a] (:tag out))))))

;;; ============================================================
;;; 短路：advisor 不调 chain
;;; ============================================================

(deftest short-circuit-test
  (testing "advisor 不调用 chain 直接返回 → 下游(含 terminal)不执行"
    (let [reached (atom false)
          guard (flt/create-advisor :guard :chat :order 0
                  :advise-call (fn [_req _chain] {:resp :blocked}))
          terminal (fn [_req] (reset! reached true) {:resp :llm})
          out ((flt/build-chain [guard] terminal) {:req 1})]
      (is (= {:resp :blocked} out))
      (is (false? @reached) "terminal 不应被触达"))))

(deftest cache-hit-test
  (testing "缓存命中走 around：命中不调 chain，未命中调一次并回填"
    (let [calls (atom 0)
          cache (atom {})
          cache-advisor (flt/create-advisor :cache :chat :order 0
                          :advise-call
                          (fn [req chain]
                            (if-let [hit (@cache (:k req))]
                              {:resp hit :cached true}
                              (let [resp (chain req)]
                                (swap! cache assoc (:k req) (:resp resp))
                                resp))))
          terminal (fn [req] (swap! calls inc) {:resp (str "v-" (:k req))})
          chain (flt/build-chain [cache-advisor] terminal)
          r1 (chain {:k "x"})
          r2 (chain {:k "x"})]
      (is (= "v-x" (:resp r1)))
      (is (= "v-x" (:resp r2)))
      (is (true? (:cached r2)))
      (is (= 1 @calls) "第二次命中缓存，terminal 只跑一次"))))

;;; ============================================================
;;; around：重试 / 计时
;;; ============================================================

(deftest retry-test
  (testing "around 可多次调 chain 实现重试"
    (let [attempts (atom 0)
          retry (flt/create-advisor :retry :chat :order 0
                  :advise-call
                  (fn [req chain]
                    (loop [n 3]
                      (let [resp (chain req)]
                        (if (or (:ok resp) (zero? n)) resp (recur (dec n)))))))
          terminal (fn [_req]
                     (let [a (swap! attempts inc)]
                       (if (>= a 3) {:ok true :a a} {:ok false :a a})))
          out ((flt/build-chain [retry] terminal) {:req 1})]
      (is (true? (:ok out)))
      (is (= 3 @attempts) "前两次失败重试，第三次成功"))))

(deftest around-timing-test
  (testing "around 可 try/finally 跨整段下游(扁平 fold 做不到)"
    (let [finally-ran (atom false)
          timer (flt/create-advisor :timer :chat :order 0
                  :advise-call
                  (fn [req chain]
                    (try (chain req)
                         (finally (reset! finally-ran true)))))
          terminal (fn [_req] {:resp :ok})
          out ((flt/build-chain [timer] terminal) {:req 1})]
      (is (= {:resp :ok} out))
      (is (true? @finally-ran)))))

;;; ============================================================
;;; phase 过滤 / order / 空链
;;; ============================================================

(deftest phase-filter-test
  (testing "advisors-for-phase 只取对应 phase"
    (let [as [(flt/create-advisor :c1 :chat)
              (flt/create-advisor :t1 :tool)
              (flt/create-advisor :c2 :chat)]]
      (is (= [:c1 :c2] (mapv :name (flt/advisors-for-phase as :chat))))
      (is (= [:t1] (mapv :name (flt/advisors-for-phase as :tool)))))))

(deftest empty-chain-test
  (testing "无 advisor 时直接走 terminal"
    (let [terminal (fn [req] {:echo (:req req)})
          out ((flt/build-chain [] terminal) {:req 42})]
      (is (= {:echo 42} out)))))

(deftest order-ties-by-list-test
  (testing "同 order 按列表序，先出现的在外层"
    (let [log (atom [])
          mk (fn [name] (flt/create-advisor name :chat :order 0
                          :before (fn [req] (swap! log conj name) req)))
          terminal (fn [_] {:resp :ok})]
      ((flt/build-chain [(mk :first) (mk :second)] terminal) {:req 1})
      (is (= [:first :second] @log) "first 在外层，before 先跑"))))
