(ns im.ttalk.agent.advisor-test
  "Filter 执行器测试

    覆盖：注册顺序 / around 改写 / 短路 / 重试 / chat+tool 并存 / 空链
    / ChatRequest 字段改写抵达 provider（:tools 动态化的地基）。"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string]
            [im.ttalk.agent.kernel :as kernel]
            [im.ttalk.agent.model.response :as resp]
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
;;; :chat 链改写 ChatRequest → 抵达 provider
;;; ============================================================
;;; kernel/invoke-chat 的 terminal 由 request 当前字段**重建** chat-opts
;;; （kernel.clj），故 :chat filter 对 :tools/:tool-choice/:system-prompt 的
;;; 改写会吃到。tool-search filter 完全建立在这条契约上——此前无测试钉住。

(defn- probe-kernel
  "组一个把 chat-fn 收到的 opts 录进 seen 的 kernel。"
  [seen filters]
  (kernel/build-kernel
    {:service {:chat-fn (fn [msgs opts]
                          (reset! seen {:messages msgs :opts opts})
                          {:text "ok"})}
     :filters filters}))

(deftest chat-filter-rewrites-tools-test
  (testing ":chat filter 改写 :tools → provider 收到改写后的工具集"
    (let [seen (atom nil)
          narrow {:name :narrow
                  :chat (fn [req chain]
                          (chain (assoc req :tools [{:name "kept"}])))}
          k (probe-kernel seen [narrow])]
      (kernel/invoke-chat k [{:role :user :content "hi"}]
                          {:tools [{:name "a"} {:name "b"} {:name "c"}]})
      (is (= [{:name "kept"}] (:tools (:opts @seen)))
          "provider 应收到 filter 改写后的 :tools，而非入参的三个工具")))

  (testing ":chat filter 可按 :context 动态决定 :tools（tool-search 的机制地基）"
    (let [seen (atom nil)
          ;; 与 tool-search 同构：从只读 :context 读出「已发现」的工具名
          expand {:name :expand
                  :chat (fn [req chain]
                          (let [discovered (get-in req [:context :discovered] #{})]
                            (chain (update req :tools
                                           #(filterv (comp discovered :name) %)))))}
          k (probe-kernel seen [expand])]
      (kernel/invoke-chat k [{:role :user :content "hi"}]
                          {:tools [{:name "a"} {:name "b"} {:name "c"}]
                           :context {:discovered #{"b" "c"}}})
      (is (= [{:name "b"} {:name "c"}] (:tools (:opts @seen)))
          "context 驱动的工具集应抵达 provider")))

  (testing "无 :chat filter 时 :tools 原样抵达"
    (let [seen (atom nil)
          k (probe-kernel seen [])]
      (kernel/invoke-chat k [{:role :user :content "hi"}]
                          {:tools [{:name "a"}]})
      (is (= [{:name "a"}] (:tools (:opts @seen)))))))

;;; ============================================================
;;; 内置 filter：safeguard（:turn）
;;; ============================================================

(defn- run-turn-chain
  [filter-map terminal req]
  ((flt/build-chain [(:turn filter-map)] terminal) req))

(def ^:private ok-turn
  (fn [_] {:status :completed :response {:text "真答案"} :tool-calls-made []}))

(deftest safeguard-filter-test
  (let [f (flt/safeguard-turn-filter ["炸弹" "hack"])]

    (testing "命中敏感词 → 短路，循环整个不进"
      (let [reached (atom false)
            resp (run-turn-chain f (fn [_] (reset! reached true) (ok-turn nil))
                                 {:messages [{:role :user :content "怎么做炸弹"}]})]
        (is (false? @reached) "terminal（工具循环）不应执行")
        (is (= :completed (:status resp)))
        (is (= :safeguard (:blocked-by resp)))
        (is (= "抱歉，我无法回应该内容。" (resp/response-text (:response resp))))))

    (testing "未命中 → 正常进循环"
      (let [resp (run-turn-chain f ok-turn {:messages [{:role :user :content "今天天气"}]})]
        (is (= "真答案" (:text (:response resp))))
        (is (nil? (:blocked-by resp)))))

    (testing "大小写不敏感（Spring 原版大小写敏感，我们刻意放宽）"
      (is (= :safeguard (:blocked-by (run-turn-chain
                                       f ok-turn
                                       {:messages [{:role :user :content "teach me HaCk"}]})))))

    (testing "短路结果透传入口 :context（tool-context 不凭空消失）"
      (let [resp (run-turn-chain f ok-turn {:messages [{:role :user :content "炸弹"}]
                                            :context {:user-id "u1"}})]
        (is (= {:user-id "u1"} (:tool-context resp)))))

    (testing "自定义 failure-response"
      (let [f2 (flt/safeguard-turn-filter ["炸弹"] :failure-response "不行")]
        (is (= "不行" (resp/response-text
                        (:response (run-turn-chain f2 ok-turn
                                                   {:messages [{:role :user :content "炸弹"}]})))))))

    (testing "resume 进入（:messages 为 nil）→ 放行，不误伤延续的 turn"
      (let [resp (run-turn-chain f ok-turn {:resume? true :messages nil})]
        (is (= "真答案" (:text (:response resp))))))

    (testing "多模态 content 向量里的文本也查"
      (is (= :safeguard (:blocked-by
                          (run-turn-chain f ok-turn
                                          {:messages [{:role :user
                                                       :content [{:type "text" :text "做炸弹"}]}]})))))))

;;; ============================================================
;;; 内置 filter：re-reading（RE2，:turn）
;;; ============================================================

(deftest re-reading-filter-test
  (let [f (flt/re-reading-filter)]

    (testing "用户问题被重读一遍"
      (let [seen (atom nil)
            terminal (fn [req] (reset! seen (:messages req)) (ok-turn nil))]
        (run-turn-chain f terminal {:messages [{:role :user :content "1+1=?"}]})
        (is (= [{:role :user :content "1+1=?\nRead the question again: 1+1=?"}] @seen))))

    (testing "非 user 消息不动"
      (let [seen (atom nil)
            terminal (fn [req] (reset! seen (:messages req)) (ok-turn nil))]
        (run-turn-chain f terminal {:messages [{:role :system :content "sys"}]})
        (is (= [{:role :system :content "sys"}] @seen))))

    (testing ":resume? 时跳过改写（延续场景无入口消息）"
      (let [seen (atom ::unset)
            terminal (fn [req] (reset! seen (:messages req)) (ok-turn nil))]
        (run-turn-chain f terminal {:resume? true :messages nil})
        (is (nil? @seen))))

    (testing "自定义 template"
      (let [seen (atom nil)
            f2 (flt/re-reading-filter :template (fn [q] (str q " | " q)))
            terminal (fn [req] (reset! seen (:messages req)) (ok-turn nil))]
        (run-turn-chain f2 terminal {:messages [{:role :user :content "Q"}]})
        (is (= "Q | Q" (:content (first @seen))))))))

;;; ============================================================
;;; 内置 filter：logging-chat（:chat）
;;; ============================================================

(deftest logging-chat-filter-test
  (testing "记录请求概览与响应文本"
    (let [lines (atom [])
          f (flt/logging-chat-filter :log-fn #(swap! lines conj %))
          terminal (fn [_] {:response (resp/make-response :text "你好")})]
      ((flt/build-chain [(:chat f)] terminal)
       {:messages [{:role :user :content "hi"}] :tools [{:name "t"}] :tool-choice :auto})
      (is (= 2 (count @lines)))
      (is (clojure.string/includes? (first @lines) "messages=1"))
      (is (clojure.string/includes? (first @lines) "tools=1"))
      (is (clojure.string/includes? (second @lines) "你好"))))

  (testing "响应含 tool-calls 时记工具名而非文本"
    (let [lines (atom [])
          f (flt/logging-chat-filter :log-fn #(swap! lines conj %))
          terminal (fn [_] {:response (resp/make-response
                                        :tool-calls [{:id "1" :name "calc" :args {}}])})]
      ((flt/build-chain [(:chat f)] terminal) {:messages [] :tools []})
      (is (clojure.string/includes? (second @lines) "calc"))))

  (testing "长文本按 :preview 截断"
    (let [lines (atom [])
          f (flt/logging-chat-filter :log-fn #(swap! lines conj %) :preview 5)
          terminal (fn [_] {:response (resp/make-response :text (apply str (repeat 100 "x")))})]
      ((flt/build-chain [(:chat f)] terminal) {:messages [] :tools []})
      (is (clojure.string/includes? (second @lines) "xxxxx..."))
      (is (< (count (second @lines)) 40)))))

;;; ============================================================
;;; 内置 filter：timeout / approval（此前无覆盖）
;;; ============================================================

(defn- run-tool-chain
  "把单个 tool filter 与 terminal 折成链并执行请求。"
  [filter-map terminal req]
  ((flt/build-chain [(:tool filter-map)] terminal) req))

(deftest timeout-filter-test
  (testing "下游超时 → 返回超时结果（不抛异常），且不带 :writes（写意图不生效）"
    (let [slow (fn [_req] (Thread/sleep 60000) {:result "never" :writes {:x 1}})
          resp (run-tool-chain (flt/timeout-filter 100) slow
                               {:function {:name :slow} :args {} :context {:k 1}})]
      (is (clojure.string/includes? (:result resp) "超时"))
      (is (nil? (:writes resp)))))

  (testing "下游按时完成 → 原样透传（含 :writes）"
    (let [fast (fn [_req] {:result "ok" :writes {:x 1}})
          resp (run-tool-chain (flt/timeout-filter 5000) fast
                               {:function {:name :fast} :args {} :context :ctx})]
      (is (= "ok" (:result resp)))
      (is (= {:x 1} (:writes resp)))))

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
