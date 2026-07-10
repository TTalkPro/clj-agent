(ns im.ttalk.agent.advisor-test
  "Filter 执行器测试

    覆盖：注册顺序 / around 改写 / 短路 / 重试 / chat+tool 并存 / 空链。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.advisor :as flt]))

;;; ============================================================
;;; 注册顺序即执行顺序
;;; ============================================================

(deftest registration-order-test
  (testing "vector 中靠前的 filter 在最外层"
    (let [log (atom [])
          a {:name :a
             :chat (fn [req chain]
                     (swap! log conj [:pre :a])
                     (let [resp (chain req)]
                       (swap! log conj [:post :a])
                       resp))}
          b {:name :b
             :chat (fn [req chain]
                     (swap! log conj [:pre :b])
                     (let [resp (chain req)]
                       (swap! log conj [:post :b])
                       resp))}
          terminal (fn [_] {:resp :ok})
          out ((flt/build-chain (keep :chat [a b]) terminal) {:req 1})]
      (is (= {:resp :ok} out))
      ;; a 在外层：pre a → pre b → terminal → post b → post a
      (is (= [[:pre :a] [:pre :b] [:post :b] [:post :a]] @log)))))

;;; ============================================================
;;; 请求 / 响应改写穿透
;;; ============================================================

(deftest rewrite-flows-through-test
  (testing "around 可改写 req 和 resp"
    (let [a {:name :a
             :chat (fn [req chain]
                     (let [resp (chain (update req :n inc))]
                       (update resp :tag conj :a)))}
          b {:name :b
             :chat (fn [req chain]
                     (let [resp (chain (update req :n * 10))]
                       (update resp :tag conj :b)))}
          terminal (fn [req] {:seen-n (:n req) :tag []})
          out ((flt/build-chain (keep :chat [a b]) terminal) {:n 1})]
      (is (= 20 (:seen-n out)))
      (is (= [:b :a] (:tag out))))))

;;; ============================================================
;;; 短路：around 不调 chain
;;; ============================================================

(deftest short-circuit-test
  (testing "around 不调用 chain 直接返回 → 下游不执行"
    (let [reached (atom false)
          guard {:name :guard
                 :chat (fn [_req _chain] {:resp :blocked})}
          terminal (fn [_req] (reset! reached true) {:resp :llm})
          out ((flt/build-chain (keep :chat [guard]) terminal) {:req 1})]
      (is (= {:resp :blocked} out))
      (is (false? @reached)))))

(deftest cache-hit-test
  (testing "缓存命中不调 chain，未命中调一次并回填"
    (let [calls (atom 0)
          cache (atom {})
          cache-filter {:name :cache
                        :chat (fn [req chain]
                                (if-let [hit (@cache (:k req))]
                                  {:resp hit :cached true}
                                  (let [resp (chain req)]
                                    (swap! cache assoc (:k req) (:resp resp))
                                    resp)))}
          terminal (fn [req] (swap! calls inc) {:resp (str "v-" (:k req))})
          chain (flt/build-chain (keep :chat [cache-filter]) terminal)
          r1 (chain {:k "x"})
          r2 (chain {:k "x"})]
      (is (= "v-x" (:resp r1)))
      (is (= "v-x" (:resp r2)))
      (is (true? (:cached r2)))
      (is (= 1 @calls)))))

;;; ============================================================
;;; around：重试 / 计时
;;; ============================================================

(deftest retry-test
  (testing "around 可多次调 chain 实现重试"
    (let [attempts (atom 0)
          retry {:name :retry
                 :chat (fn [req chain]
                         (loop [n 3]
                           (let [resp (chain req)]
                             (if (or (:ok resp) (zero? n)) resp (recur (dec n))))))}
          terminal (fn [_req]
                     (let [a (swap! attempts inc)]
                       (if (>= a 3) {:ok true :a a} {:ok false :a a})))
          out ((flt/build-chain (keep :chat [retry]) terminal) {:req 1})]
      (is (true? (:ok out)))
      (is (= 3 @attempts)))))

(deftest around-timing-test
  (testing "around 可 try/finally 跨整段下游"
    (let [finally-ran (atom false)
          timer {:name :timer
                 :chat (fn [req chain]
                         (try (chain req)
                              (finally (reset! finally-ran true))))}
          terminal (fn [_req] {:resp :ok})
          out ((flt/build-chain (keep :chat [timer]) terminal) {:req 1})]
      (is (= {:resp :ok} out))
      (is (true? @finally-ran)))))

;;; ============================================================
;;; chat + tool 并存
;;; ============================================================

(deftest dual-hook-test
  (testing "一个 filter 同时有 :chat 和 :tool"
    (let [log (atom [])
          dual {:name :dual
                :chat (fn [req chain]
                        (swap! log conj [:chat-pre])
                        (let [resp (chain req)]
                          (swap! log conj [:chat-post])
                          resp))
                :tool (fn [req chain]
                        (swap! log conj [:tool-pre])
                        (let [resp (chain req)]
                          (swap! log conj [:tool-post])
                          resp))}
          chat-terminal (fn [_] {:resp :chat-ok})
          tool-terminal (fn [_] {:result :tool-ok :context nil})]
      ;; chat 链
      ((flt/build-chain (keep :chat [dual]) chat-terminal) {:req 1})
      (is (= [[:chat-pre] [:chat-post]] @log))
      ;; tool 链
      (reset! log [])
      ((flt/build-chain (keep :tool [dual]) tool-terminal) {:req 1})
      (is (= [[:tool-pre] [:tool-post]] @log)))))

;;; ============================================================
;;; 空链
;;; ============================================================

(deftest empty-chain-test
  (testing "无 filter 时直接走 terminal"
    (let [terminal (fn [req] {:echo (:req req)})
          out ((flt/build-chain '() terminal) {:req 42})]
      (is (= {:echo 42} out)))))

;;; ============================================================
;;; 内置 filter：timeout / approval（此前无覆盖）
;;; ============================================================

(defn- run-tool-chain
  "把单个 tool filter 与 terminal 折成链并执行请求。"
  [filter-map terminal req]
  ((flt/build-chain [(:tool filter-map)] terminal) req))

(deftest timeout-filter-test
  (testing "下游超时 → 返回超时结果（不抛异常），context 保留"
    (let [slow (fn [_req] (Thread/sleep 60000) {:result "never" :context :c})
          resp (run-tool-chain (flt/timeout-filter 100) slow
                               {:function {:name :slow} :args {} :context {:k 1}})]
      (is (clojure.string/includes? (:result resp) "超时"))
      (is (= {:k 1} (:context resp)))))

  (testing "下游按时完成 → 原样透传"
    (let [fast (fn [req] {:result "ok" :context (:context req)})
          resp (run-tool-chain (flt/timeout-filter 5000) fast
                               {:function {:name :fast} :args {} :context :ctx})]
      (is (= "ok" (:result resp)))
      (is (= :ctx (:context resp)))))

  (testing "超时后后台任务被中断（future-cancel 生效）"
    (let [interrupted? (promise)
          slow (fn [_]
                 (try (Thread/sleep 60000)
                      (catch InterruptedException _ (deliver interrupted? true)))
                 {:result "never"})]
      (run-tool-chain (flt/timeout-filter 100) slow
                      {:function {:name :slow} :args {} :context nil})
      (is (true? (deref interrupted? 2000 :timeout))
          "慢工具线程应收到中断，不泄漏工作线程"))))

(deftest approval-filter-test
  (testing "敏感工具 + 批准 → 执行下游"
    (let [asked (atom nil)
          f (flt/approval-filter (fn [n args] (reset! asked [n args]) true))
          resp (run-tool-chain f (fn [_] {:result "done" :context nil})
                               {:function {:name :rm-rf :sensitive true}
                                :args {:path "/tmp/x"} :context nil})]
      (is (= "done" (:result resp)))
      (is (= [:rm-rf {:path "/tmp/x"}] @asked) "审批函数收到工具名与参数")))

  (testing "敏感工具 + 拒绝 → 短路，不调下游"
    (let [called (atom false)
          f (flt/approval-filter (fn [_ _] false))
          resp (run-tool-chain f (fn [_] (reset! called true) {:result "done"})
                               {:function {:name :rm-rf :sensitive true}
                                :args {} :context :ctx})]
      (is (clojure.string/includes? (:result resp) "拒绝"))
      (is (= :ctx (:context resp)))
      (is (false? @called) "下游不应被调用")))

  (testing "非敏感工具 → 不问审批直接放行"
    (let [asked (atom false)
          f (flt/approval-filter (fn [_ _] (reset! asked true) false))
          resp (run-tool-chain f (fn [_] {:result "ok"})
                               {:function {:name :safe :sensitive false} :args {} :context nil})]
      (is (= "ok" (:result resp)))
      (is (false? @asked))))

  (testing "默认审批走标准输入（y 批准 / 其他拒绝）"
    (let [f (flt/approval-filter)
          req {:function {:name :danger :sensitive true} :args {} :context nil}
          terminal (fn [_] {:result "executed"})]
      (binding [*out* (java.io.StringWriter.)]   ;; 吞掉审批提示输出
        (with-in-str "y\n"
          (is (= "executed" (:result (run-tool-chain f terminal req)))))
        (with-in-str "n\n"
          (is (clojure.string/includes?
                (:result (run-tool-chain f terminal req)) "拒绝")))))))
