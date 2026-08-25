(ns im.ttalk.agent.filter-test
  "Filter 执行器测试

    覆盖：注册顺序 / around 改写 / 短路 / 重试 / chat+tool 并存 / 空链
    / ChatRequest 字段改写抵达 provider（:tools 动态化的地基）。"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string]
            [im.ttalk.agent.kernel :as kernel]
            [im.ttalk.agent.model.response :as resp]
            [im.ttalk.agent.tool :as tool :refer [deftool]]
            [im.ttalk.agent.tool-calling-manager :as tcm]
            [im.ttalk.agent.filter :as flt]))

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
;;; 超时机制（tool/call-with-timeout —— 唯一实现，kernel/invoke-tool 消费）
;;; + 内置 filter：approval
;;;
;;; 注：timeout-filter 已删除（2026-07-16）——超时是内建机制：工具声明 >
;;; 引擎缺省 > 不超时，由 invoke-tool 强制。下面的机制测试直接打
;;; call-with-timeout 本体；端到端（声明/引擎缺省/优先级）见 kernel 级测试。
;;; ============================================================

(defn- run-tool-chain
  "把单个 tool filter 与 terminal 折成链并执行请求。"
  [filter-map terminal req]
  ((flt/build-chain [(:tool filter-map)] terminal) req))

(deftest call-with-timeout-mechanism-test
  (testing "超时 → [:timeout]，且下游线程收到中断（可中断的阻塞不泄漏）"
    (let [interrupted? (promise)
          r (tool/call-with-timeout 100
              (fn [] (try (Thread/sleep 60000)
                          (catch InterruptedException _ (deliver interrupted? true)))
                  "never"))]
      (is (= [:timeout] r))
      (is (true? (deref interrupted? 2000 :timeout))
          "慢任务线程应收到中断，不泄漏工作线程")))

  (testing "按时完成 → [:ok v] 原样透传"
    (is (= [:ok {:result "ok" :writes {:x 1}}]
           (tool/call-with-timeout 5000 (fn [] {:result "ok" :writes {:x 1}})))))

  (testing "f 抛异常 → [:err 原异常]（不包 ExecutionException，调用方拿到原对象）"
    (let [e (ex-info "boom" {:k 1})
          [tag t] (tool/call-with-timeout 5000 (fn [] (throw e)))]
      (is (= :err tag))
      (is (identical? e t)))))

(def ^:dynamic *tenant* :none)

(deftest call-with-timeout-environment-test
  (testing "调用方的动态绑定对 f 可见（回归：改用虚拟线程时曾丢掉 clojure.core/future 的绑定传导——静默给根值）"
    (binding [*tenant* :acme]
      (is (= [:ok :acme] (tool/call-with-timeout 5000 (fn [] *tenant*))))))

  (testing "f 跑在虚拟线程上（回归：曾用 clojure.core/future = send-off 平台线程——毁掉引擎线程模型，且平台线程上 socket read 无视 interrupt）"
    (is (= [:ok true]
           (tool/call-with-timeout 5000 (fn [] (.isVirtual (Thread/currentThread))))))))

(deftest timeout-abandons-not-kills-test
  (testing "诚实语义：CPU 忙循环打不断——[:timeout] 返回后任务仍在后台跑（JVM 无 kill 原语，超时=放弃等待≠终止执行）"
    (let [stop  (atom false)
          beats (atom 0)
          r (tool/call-with-timeout 100
              (fn [] (while (not @stop) (swap! beats inc)) "done"))]
      (is (= [:timeout] r))
      (let [b1 @beats]
        (Thread/sleep 100)
        (is (> @beats b1)
            "超时已返回而忙循环仍在跳动——本测试钉住真实语义，防后人误以为有 kill"))
      (reset! stop true))))

(deftool sleepy-declared
  "声明 200ms 超时但睡 60s 的工具（端到端测试用）"
  [[x :string "输入" :default "v"]]
  {:timeout 200}
  (Thread/sleep 60000)
  x)

(deftool sleepy-plain
  "睡 60s 但不声明超时（对照组）"
  [[x :string "输入" :default "v"]]
  (Thread/sleep 60000)
  x)

(defn- inline-sleepy
  "内联工具：睡 60s，可带 :timeout 声明（对照 :serial/:retry 也是这么声明的）"
  [nm & {:keys [timeout]}]
  (cond-> {:name nm :description nm
           :input_schema {:type "object" :properties {} :required []}
           :handler (fn [_ _] (Thread/sleep 60000) "done")}
    timeout (assoc :timeout timeout)))

(deftest declared-timeout-works-without-any-filter-test
  (testing "**开箱即生效**：裸 kernel、零 filter，deftool :timeout 照样强制
            （回归 review#4：曾经唯独 :timeout 要用户手动挂 filter 才生效，
             而 :serial / :retry / :return-direct 都是 react/kernel 直接消费——
             用户写下声明却静默无效，正是我们要修的那个 bug 换了个位置）"
    (let [k  (kernel/build-kernel {:service {} :tools [#'sleepy-declared]})
          t0 (System/currentTimeMillis)
          r  (kernel/invoke-tool k :sleepy-declared {} nil)
          dt (- (System/currentTimeMillis) t0)]
      (is (clojure.string/includes? (:value r) "超时"))
      (is (= :transient (get-in r [:error :class])) "归 :transient → 声明 :retry 的工具可重试")
      (is (< dt 5000) "声明的 200ms 生效——修复前无 filter 时睡满 60s")
      (is (nil? (:writes r)) "超时结果不带 writes（事务性）")))

  (testing "未声明 → 不超时、零开销（不起线程，与从前逐字相同）"
    (let [k (kernel/build-kernel {:service {} :tools [#'sleepy-plain]})
          done (promise)]
      (.start (Thread. ^Runnable (fn [] (kernel/invoke-tool k :sleepy-plain {} nil)
                                   (deliver done :finished))))
      (is (= :still-running (deref done 600 :still-running))
          "没有声明就没有超时——不该被任何缺省砍掉"))))

(deftest inline-tool-declared-timeout-test
  (testing "**内联工具的 :timeout 同样生效**（回归 review#1：曾只修了 var 工具那一半——
            两个 func-def 构造点分头维护，inline 那个硬编码没带 :timeout；
            而 inline 的 :serial / :retry 一直是生效的，独 :timeout 静默失效。
            delegate-tool 恰恰是内联且跑整个子 agent，最需要超时的就是它）"
    (let [k  (kernel/build-kernel {:service {} :tools [(inline-sleepy "slow" :timeout 200)]})
          t0 (System/currentTimeMillis)
          r  (kernel/invoke-tool k :slow {} nil)
          dt (- (System/currentTimeMillis) t0)]
      (is (clojure.string/includes? (:value r) "超时"))
      (is (= :transient (get-in r [:error :class])))
      (is (< dt 5000) "修复前：睡满 60s")))

  (testing "内联工具未声明 → 不超时（与 var 工具对称）"
    (let [k (kernel/build-kernel {:service {} :tools [(inline-sleepy "plain")]})]
      (is (nil? (kernel/tool-timeout k :plain))))))

;; 引擎桩：satisfies ToolCallingManager **且**携带 :timeout 字段。
;; 不用裸 map `{:timeout ms}`——那是个陷阱先例：manager-timeout 读得到它，但这样的
;; kernel 一旦进真实 react 循环，会在 tcm/execute-tool-calls 抛 No implementation
;; of method（P9 review 逮到的 R5 尾巴）。真引擎（三个构造器）在 client 模块，
;; core 造不了，故用最小 defrecord 桩：协议 + 字段两个契约都满足。
(defrecord StubManager [timeout]
  tcm/ToolCallingManager
  (execute-tool-calls [_ _ _ _]
    (throw (ex-info "StubManager 只携带 :timeout 供 invoke-tool 读取，不执行批次" {}))))

(deftest declared-timeout-beats-engine-default-test
  (testing "优先级：工具声明 > 引擎缺省（声明更**宽**时引擎缺省不砍它）"
    (let [slowish (assoc (inline-sleepy "s" :timeout 5000)
                         :handler (fn [_ _] (Thread/sleep 300) "done"))
          k (kernel/build-kernel {:service {} :tools [slowish]
                                  :tool-manager (->StubManager 100)})]
      (is (= "done" (:value (kernel/invoke-tool k :s {} nil)))
          "引擎缺省 100ms < 下游 300ms，但声明 5000ms 胜出")))

  (testing "优先级：工具声明 > 引擎缺省（声明更**紧**时提前超时，报的是声明值）"
    (let [k (kernel/build-kernel {:service {}
                                  :tools [#'sleepy-declared]
                                  :tool-manager (->StubManager 60000)})
          r (kernel/invoke-tool k :sleepy-declared {} nil)]
      (is (clojure.string/includes? (:value r) "200ms")
          "报的是工具声明的 200ms，不是引擎的 60000ms")))

  (testing "未声明的工具吃引擎缺省"
    (let [k (kernel/build-kernel {:service {}
                                  :tools [#'sleepy-plain]
                                  :tool-manager (->StubManager 150)})
          r (kernel/invoke-tool k :sleepy-plain {} nil)]
      (is (clojure.string/includes? (:value r) "150ms"))))

  (testing "都没给 → 不超时（缺省语义）"
    (let [quick (assoc (inline-sleepy "q")
                       :handler (fn [_ _] (Thread/sleep 200) "done"))
          k (kernel/build-kernel {:service {} :tools [quick]})]
      (is (= "done" (:value (kernel/invoke-tool k :q {} nil)))))))

(deftest timeout-validated-at-build-kernel-test
  (testing "坏 :timeout 在**装配期**就炸，而非执行期（回归 review#3：
            \"5s\" 曾每次调用抛 ClassCastException，-1 曾让工具每次静默立刻超时）"
    (doseq [bad ["5s" -1 0 2.7]]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #":timeout 必须为正整数毫秒"
            (kernel/build-kernel {:service {} :tools [(inline-sleepy "bad" :timeout bad)]}))
          (str "应拒绝 " (pr-str bad)))))

  (testing "合法值与未声明照常通过"
    (is (some? (kernel/build-kernel {:service {} :tools [(inline-sleepy "ok" :timeout 500)]})))
    (is (some? (kernel/build-kernel {:service {} :tools [(inline-sleepy "none")]})))
    (is (some? (kernel/build-kernel {:service {} :tools [#'sleepy-declared]})))))

(deftest manager-timeout-validated-at-build-kernel-test
  (testing "R5: :tool-manager 的坏 :timeout 在装配期即拒（与工具声明的校验对称）"
    (doseq [bad ["5s" -1 0 2.7]]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #":tool-manager 的 :timeout 必须为正整数毫秒"
            (kernel/build-kernel {:service {}
                                  :tools [(inline-sleepy "ok")]
                                  :tool-manager (->StubManager bad)}))
          (str "应拒绝 " (pr-str bad)))))

  (testing "合法的 :tool-manager :timeout 照常通过"
    (is (some? (kernel/build-kernel {:service {}
                                     :tools [(inline-sleepy "ok")]
                                     :tool-manager (->StubManager 500)}))))

  (testing "无 :timeout 字段的 :tool-manager 不受影响（reify/自定义实现）"
    (is (some? (kernel/build-kernel {:service {}
                                     :tools [(inline-sleepy "ok")]
                                     :tool-manager (->StubManager nil)})))))

(deftool instant-tool
  "瞬间返回的工具（R1 测试用：审批耗时应排除在超时预算外）"
  [[x :string "输入" :default "v"]]
  {:timeout 300}
  (str "done:" x))

(deftest approval-outside-timeout-budget-test
  (testing "R1: 审批等待**不**吃工具的超时预算——超时只包裹工具本体，不包裹 filter 链
            （回归：run-chain 曾把整条 filter 链一起计时，操作员审批慢一点就超时）"
    (let [;; 审批 filter：睡 800ms 后放行（模拟人工审批延迟）
          slow-approval {:name :slow-approval
                         :tool (fn [req chain]
                                 (Thread/sleep 800)  ;; 远超工具声明的 300ms 超时
                                 (chain req))}
          ;; 工具声明 300ms 超时，但工具本体瞬间返回
          k (kernel/build-kernel {:service {}
                                  :tools [#'instant-tool]
                                  :filters [slow-approval]})
          t0 (System/currentTimeMillis)
          r (kernel/invoke-tool k :instant-tool {:x "ok"} nil)
          dt (- (System/currentTimeMillis) t0)]
      ;; 修复后：审批 800ms 在计时区外，工具本体瞬间完成 → 不超时
      (is (= "done:ok" (:value r))
          "工具本体瞬间完成——800ms 审批不应触发 300ms 超时")
      (is (nil? (:error r)) "不应有超时错误")
      (is (> dt 700) "确实等了审批（> 800ms），证明审批在链上执行了")))

  (testing "对照：工具本体慢 → 超时照常触发（证明超时确实在终端内生效）"
    (let [slow-tool {:name :slow
                     :description "慢工具"
                     :input_schema {:type "object" :properties {} :required []}
                     :timeout 200
                     :handler (fn [_ _] (Thread/sleep 60000) "never")}
          k (kernel/build-kernel {:service {} :tools [slow-tool]})]
      (is (clojure.string/includes? (:value (kernel/invoke-tool k :slow {} nil)) "超时")))))

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

;;; ============================================================
;;; Filter record 归一化
;;; ============================================================

(deftest as-filter-normalizes-test
  (testing "map 字面量 → Filter record"
    (let [f (flt/as-filter {:name :x :chat identity})]
      (is (flt/filter? f))
      (is (= :x (:name f)))
      (is (= identity (:chat f)))))

  (testing "四个钩子之外的键进 ext-map，照常可读（memory filter 的 :store 靠这条活着）"
    (let [store (Object.)
          f (flt/as-filter {:name :memory :store store :chat identity})]
      (is (flt/filter? f))
      (is (identical? store (:store f)))))

  (testing "已是 record → 原样返回，不重建"
    (let [f (flt/create-filter :x :chat identity)]
      (is (identical? f (flt/as-filter f)))))

  (testing "create-filter 产出 record；未给的钩子为 nil"
    (let [f (flt/create-filter :x :turn identity)]
      (is (flt/filter? f))
      (is (= identity (:turn f)))
      (is (nil? (:chat f)))
      (is (nil? (:iteration f)))
      (is (nil? (:token-xform f)))))

  (testing "五个钩子可任意并存"
    (let [f (flt/create-filter :all :chat identity :tool identity
                               :turn identity :iteration identity
                               :token-xform (map identity))]
      (is (every? some? [(:chat f) (:tool f) (:turn f) (:iteration f) (:token-xform f)]))))

  (testing "非 map 非 record → 装配期即抛"
    (is (thrown? clojure.lang.ExceptionInfo (flt/as-filter :not-a-filter)))))

;;; ============================================================
;;; compile-chain：装配期预折叠
;;; ============================================================

(deftest compile-chain-test
  (testing "空序列 → identity：链就是 terminal 本身，一层包装都不加"
    (let [terminal (fn [req] {:n (:n req)})
          build (flt/compile-chain [])]
      (is (identical? terminal (build terminal)))
      (is (= {:n 1} ((build terminal) {:n 1})))))

  (testing "与 build-chain 等价：靠前者在最外层"
    (let [log (atom [])
          mk (fn [tag] (fn [req chain]
                         (swap! log conj [:pre tag])
                         (let [r (chain req)]
                           (swap! log conj [:post tag])
                           r)))
          fns [(mk :a) (mk :b)]
          terminal (fn [_] :done)]
      (is (= :done ((flt/build-chain fns terminal) {})))
      (let [via-build @log]
        (is (= [[:pre :a] [:pre :b] [:post :b] [:post :a]] via-build))
        (reset! log [])
        (let [build (flt/compile-chain fns)]
          (is (= [] @log) "compile-chain 本身不执行任何 filter")
          (is (= :done ((build terminal) {})))
          (is (= via-build @log)
              "同一序列，compile-chain 与 build-chain 的执行顺序逐字相同")))))

  (testing "同一 builder 可反复吃不同 terminal"
    (let [build (flt/compile-chain [(fn [req chain] (chain (update req :n inc)))])]
      (is (= 2 ((build (fn [req] (:n req))) {:n 1})))
      (is (= 20 ((build (fn [req] (* 10 (:n req)))) {:n 1}))))))

;;; ============================================================
;;; compile-hooks：四条链各收各的钩子
;;; ============================================================

(deftest compile-hooks-test
  (testing ":source 是归一化后的 filter 向量"
    (let [hooks (flt/compile-hooks [{:name :a :chat identity}])]
      (is (every? flt/filter? (:source hooks)))))

  (testing "每条链只收对应钩子——挂 :chat 的 filter 不该跑在 tool 链上"
    (let [hits (atom [])
          f {:name :multi
             :chat      (fn [req chain] (swap! hits conj :chat) (chain req))
             :tool      (fn [req chain] (swap! hits conj :tool) (chain req))
             :iteration (fn [req chain] (swap! hits conj :iteration) (chain req))}
          hooks (flt/compile-hooks [f])
          terminal (fn [_] :done)]
      (((:chat hooks) terminal) {})
      (is (= [:chat] @hits))
      (reset! hits [])
      (((:tool hooks) terminal) {})
      (is (= [:tool] @hits))
      (reset! hits [])
      (((:iteration hooks) terminal) {})
      (is (= [:iteration] @hits))
      (reset! hits [])
      (((:turn hooks) terminal) {})
      (is (= [] @hits) "没挂 :turn → turn 链是纯 terminal")))

  (testing "无 :token-xform filter → :token-xform 为 nil（流式路径据此走零开销分支）"
    (is (nil? (:token-xform (flt/compile-hooks [{:name :a :chat identity}]))))
    (is (some? (:token-xform (flt/compile-hooks
                               [(flt/token-redact-filter #"x" "*")]))))))

;;; ============================================================
;;; kernel 侧：预编译链的装配、替换与兜底
;;; ============================================================

(deftest kernel-hooks-test
  (testing "build-kernel 归一化 :filters 为 record，hooks 与之同源"
    (let [k (kernel/build-kernel {:service {} :filters [{:name :a :chat identity}]})]
      (is (every? flt/filter? (:filters k)))
      (is (identical? (:filters k) (:source (:hooks k))))
      (is (identical? (:hooks k) (kernel/filter-hooks k))
          "同源时 filter-hooks 直接返回装配期那份，不重编")))

  (testing "with-filters 换链 → hooks 跟着重编"
    (let [k  (kernel/build-kernel {:service {} :filters [{:name :a :chat identity}]})
          k2 (kernel/with-filters k [{:name :b :chat identity}])]
      (is (= [:b] (mapv :name (:filters k2))))
      (is (identical? (:filters k2) (:source (:hooks k2))))
      (is (= [:a] (mapv :name (:filters k))) "原 kernel 不受影响")))

  (testing "绕过 with-filters 直接 assoc :filters → filter-hooks 现场重编兜底"
    (let [seen (atom nil)
          k (kernel/build-kernel
              {:service {:chat-fn (fn [_ opts] (reset! seen opts) {:text "ok"})}
               :filters []})
          rogue {:name :rogue
                 :chat (fn [req chain] (chain (assoc req :tool-choice :forced)))}
          k2 (assoc k :filters [rogue])]     ;; 刻意绕开 API
      (kernel/invoke-chat k2 [{:role :user :content "hi"}] {})
      (is (= :forced (:tool-choice @seen))
          "hooks 与 :filters 脱钩时必须重编——静默用旧链会让 filter 悄悄失效"))))
