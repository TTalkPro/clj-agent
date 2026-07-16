(ns tool-timeout-live-test
  "工具超时 × MiniMax 真实循环验证（设计见 docs/tool-timeout-design.md）。

   运行：clojure -M examples/tool_timeout_live_test.clj（需 MINIMAX_API_KEY）

   **只验单测证明不了的四件事**（单测已覆盖返回值形状/优先级/线程模型，
   不在此重复）：
   1. 超时后**真实模型**收到 transient 错误、理解它、循环存活并作答；
   2. **阻塞 IO 真的被取消**——被超时的 socket read 抛 `Closed by interrupt`，
      副作用**不落地**。这是改虚拟线程的意外红利（见 §2.2 修订）；
   3. 声明 vs 缺省的**可观察后果对照**——同一个 3s 的活儿，声明救活 / 缺省杀死；
   4. **abandon 残余风险的真机证据**——CPU 忙循环打不断，其副作用在「已告知
      LLM 超时」之后才落地（设计文档 §2.3 的核心声明，用真实时间戳钉死）。

   断言一律钉机制（工具是否被调、耗时、tool result 形状、LLM 调用次数、时间戳
   先后），**不钉模型措辞**——后者会波动，拿它当断言等于给 CI 埋雷。

   场景 1/2 的慢后端是**本地裸 TCP**（工具阻塞在 `InputStream.read()` 上）；
   场景 4 用**纯 CPU 忙循环**——这两条是 JVM 上「可取消 / 不可取消」的真实分界，
   实测得出（见文件末尾的实测记录）。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.client :as agent]
            [im.ttalk.agent.kernel :as kernel]
            [im.ttalk.agent.react :as react]
            [im.ttalk.agent.model.service :as service]
            [im.ttalk.agent.provider.factory.builder :as factory]
            [im.ttalk.agent.provider.minimax :as minimax]
            [im.ttalk.agent.tool :refer [deftool]])
  (:import [java.net ServerSocket Socket]))

(set! *warn-on-reflection* true)

(def failures (atom 0))

(defn check [description ok?]
  (if ok?
    (println "  PASS" description)
    (do (swap! failures inc)
        (println "  FAIL" description))))

;;; ============================================================
;;; 本地慢后端：accept 后 sleep，再回一个字节
;;; ============================================================

(defn start-backend
  "本地 TCP 后端：每个连接 sleep delay-ms 后回一个字节。模拟慢/挂死的下游服务。"
  ^ServerSocket [delay-ms]
  (let [ss (ServerSocket. 0)]
    (Thread/startVirtualThread
      (fn []
        (try
          (loop []
            (let [^Socket sock (.accept ss)]
              (Thread/startVirtualThread
                (fn []
                  (try
                    (Thread/sleep (long delay-ms))
                    (doto (.getOutputStream sock) (.write 7) (.flush))
                    (.close sock)
                    (catch Throwable _ nil))))
              (recur)))
          (catch Throwable _ nil))))
    ss))

(def slow-port (atom nil))   ;; 3 秒后才回
(def fast-port (atom nil))   ;; 立刻回

(defn hit
  "打后端并阻塞在裸 socket read 上。返回后端回的字节。"
  [port]
  (with-open [sock (Socket. "127.0.0.1" (int port))]
    (.read (.getInputStream sock))))

;;; ============================================================
;;; 工具
;;; ============================================================

(def io-side-effect-at (atom nil))    ;; 阻塞 IO 工具的副作用落地时刻（预期：永不）
(def cpu-side-effect-at (atom nil))   ;; CPU 工具的副作用落地时刻（预期：超时之后）
(def timeout-reported-at (atom nil))  ;; 超时结果上报给循环的时刻

(deftool fetch-inventory
  "Query warehouse inventory for a SKU. Always use this tool for inventory questions."
  [[sku :string "SKU code"]]
  {:timeout 1000}
  ;; 后端 3s 才回，本工具声明 1s → 必超时。
  ;; **实测（JDK 25）**：虚拟线程上 socket read 会被 interrupt 打断（抛
  ;; SocketException "Closed by interrupt"）→ 下面两行永不执行，副作用不落地。
  (let [b (hit @slow-port)]
    (reset! io-side-effect-at (System/currentTimeMillis))
    (str sku " inventory: " b " units")))

(deftool get-price
  "Get the unit price for a SKU. Always use this tool for price questions."
  [[sku :string "SKU code"]]
  {:timeout 6000}
  ;; 后端 3s 才回，本工具声明 6s → **声明救了它**（引擎缺省仅 800ms）
  (let [b (hit @slow-port)]
    (str sku " price: " (* b 10) " CNY")))

(deftool get-stock-age
  "Get how many days the SKU has been in stock. Always use this tool for stock age questions."
  [[sku :string "SKU code"]]
  ;; 同样 3s 的活儿，但**不声明** → 被引擎的 800ms 缺省杀掉
  (let [b (hit @slow-port)]
    (str sku " stock age: " b " days")))

(def flaky-attempts (atom 0))

(deftool check-warehouse
  "Check warehouse availability for a SKU. Always use this tool for availability questions."
  [[sku :string "SKU code"]]
  {:timeout 1000 :retry {:max-retries 2 :initial-delay-ms 50}}
  ;; 第一次打慢后端（超时 → :transient → 重试），第二次打快后端（成功）。
  ;; 重试对模型透明：LLM 只会看到成功结果。
  (if (= 1 (swap! flaky-attempts inc))
    (str sku " available: " (hit @slow-port))
    (str sku " available: yes (" (hit @fast-port) " in stock)")))

(deftool compute-forecast
  "Run the CPU-bound demand forecast for a SKU. Always use this tool for forecast questions."
  [[sku :string "SKU code"]]
  {:timeout 800}
  ;; 纯 CPU 忙循环，**不检查中断标志** → interrupt 完全无效（JVM 无 kill 原语）。
  ;; 跑满 ~2.5s 后写下副作用时间戳——彼时超时早已上报给 LLM。
  ;; 这是 abandon 语义的**残余风险**：真实存在，但形态比「所有阻塞调用」窄得多。
  (let [deadline (+ (System/currentTimeMillis) 2500)
        n (loop [n 0]
            (if (< (System/currentTimeMillis) deadline) (recur (inc n)) n))]
    (reset! cpu-side-effect-at (System/currentTimeMillis))
    (str sku " forecast: " n " units")))

;;; ============================================================
;;; 观测：记录超时结果上报给循环的时刻
;;;
;;; 用 :on-tool-result 回调而非 filter——声明式超时现在由 kernel/invoke-tool 强制
;;; （在 filter 链**之外**），故没有任何 filter 看得见它。回调是超时结果抵达循环的
;;; 第一现场，本就是更贴切的探针。
;;; ============================================================

(defn make-agent
  "default-ms → **引擎**缺省（`(sequential-tool-calling-manager {:timeout ms})`）：
   时间上限属于执行策略，随引擎构造。nil = 不给缺省（框架缺省即不超时）。
   工具自己的 `deftool {:timeout ms}` 恒优先。"
  [tools default-ms]
  (let [provider (factory/create-provider-from-env :minimax)
        svc (service/create-service provider {:model minimax/default-model
                                              :max-tokens 512
                                              :temperature 0})
        llm-calls (atom 0)
        instrumented (assoc svc :chat-fn
                            (fn [messages opts]
                              (swap! llm-calls inc)
                              ((:chat-fn svc) messages opts)))
        k (kernel/build-kernel {:service instrumented
                                :tools tools
                                :tool-manager (react/sequential-tool-calling-manager
                                                (cond-> {} default-ms (assoc :timeout default-ms)))})]
    {:agent (agent/create-agent
              {:kernel k
               :callbacks {:on-tool-result
                           (fn [_ result]
                             (when (str/includes? (str result) "超时")
                               (compare-and-set! timeout-reported-at nil
                                                 (System/currentTimeMillis))))}})
     :llm-calls llm-calls}))

(defn tool-results
  "从 :tool-calls-made 取出工具结果（按调用序）。"
  [result]
  (mapv :result (:tool-calls-made result)))

;;; ============================================================
;;; 场景 1：超时 → 模型收到 transient 错误 → 循环存活作答
;;;         + 阻塞 IO 真被取消（副作用不落地）
;;; ============================================================

(defn scenario-1 []
  (println "\n场景 1：工具超时 → 真实模型收到错误 → 循环存活作答（+ 阻塞 IO 真被取消）")
  (reset! io-side-effect-at nil)
  (reset! timeout-reported-at nil)
  (let [{:keys [agent llm-calls]} (make-agent [#'fetch-inventory] nil)
        t0 (System/currentTimeMillis)
        result (agent/chat agent
                 "Check the inventory for SKU-42 and tell me what happened."
                 {:max-iterations 3})
        dt (- (System/currentTimeMillis) t0)
        results (tool-results result)]
    (check "工具被真实模型调用了" (pos? (count results)))
    (check "tool result 是超时错误（模型收到的就是它）"
           (boolean (some #(str/includes? (str %) "超时") results)))
    (check "以工具声明的 1000ms 为准超时（引擎未给缺省，声明是唯一来源）"
           (some? @timeout-reported-at))
    (check "循环存活：模型拿到错误后继续，给出非空最终答案"
           (not (str/blank? (str (:text result)))))
    (check "至少两次 LLM 调用（要工具 + 拿到错误后作答）" (<= 2 @llm-calls))
    (check "整轮完成（非 :error 终态）" (not= :error (:status result)))
    ;; ---- 阻塞 IO 真的被取消（P1-2 改虚拟线程的红利，实测推翻了原 §2.2）----
    (println "  —— 阻塞 IO 取消验证 ——")
    (Thread/sleep 3500)   ;; 等过后端的 3s：若未被取消，副作用此刻应已落地
    (check "被超时的 socket read **真被打断**，副作用不落地（虚拟线程上 JDK 关闭 socket）"
           (nil? @io-side-effect-at))
    (println "  LLM 调用次数：" @llm-calls "  整轮耗时：" dt "ms")
    (println "  模型最终答案：" (pr-str (:text result)))))

;;; ============================================================
;;; 场景 2：对照组——同样 3s 的活儿，声明救活 vs 缺省杀死
;;; ============================================================

(defn scenario-2 []
  (println "\n场景 2：对照组——引擎缺省 800ms；get-price 声明 6s（活）/ get-stock-age 不声明（死），同为 3s 的活儿")
  (let [{:keys [agent]} (make-agent [#'get-price #'get-stock-age] 800)
        result (agent/chat agent
                 "For SKU-7: get both the price and the stock age. Report both results."
                 {:max-iterations 3})
        made (:tool-calls-made result)
        ;; 按名分组取**全部**结果：模型看到超时后可能自行重试（它的自由，
        ;; 不是我们的机制）——故不钉调用次数，只钉「每次调用的结果形状」。
        by-name (group-by (comp name :name) made)
        prices (mapv :result (get by-name "get-price"))
        ages   (mapv :result (get by-name "get-stock-age"))]
    (check "两个工具都被调用了（次数由模型定，不钉）"
           (and (seq prices) (seq ages)))
    (check "get-price 声明 :timeout 6000 → 挺过 3s 的活儿，每次都拿到真实数据"
           (every? #(str/includes? (str %) "price") prices))
    (check "get-stock-age 未声明 → 每次都被引擎的 800ms 缺省杀掉"
           (every? #(str/includes? (str %) "超时") ages))
    (check "同一个 3s 的活儿，一活一死——差别只在工具声明（优先级在真实循环里可观察）"
           (and (seq prices) (seq ages)
                (not-any? #(str/includes? (str %) "超时") prices)
                (every? #(str/includes? (str %) "超时") ages)))
    (check "混合结果下循环仍存活并作答" (not (str/blank? (str (:text result)))))
    (println "  get-price     →" (pr-str prices))
    (println "  get-stock-age →" (pr-str ages) (str "（调用 " (count ages) " 次）"))
    (println "  模型最终答案：" (pr-str (:text result)))))

;;; ============================================================
;;; 场景 3：超时 → :transient → :retry，重试对模型透明
;;; ============================================================

(defn scenario-3 []
  (println "\n场景 3：超时 → :transient → :retry 自动重试（对模型透明）")
  (reset! flaky-attempts 0)
  (let [{:keys [agent llm-calls]} (make-agent [#'check-warehouse] nil)
        result (agent/chat agent
                 "Check warehouse availability for SKU-9 and report it."
                 {:max-iterations 3})
        results (tool-results result)]
    (check "工具被执行了两次（首次超时 + 一次重试）" (= 2 @flaky-attempts))
    (check "模型只看到成功结果，没看到超时错误（重试透明）"
           (boolean (and (seq results)
                         (some #(str/includes? (str %) "available") results)
                         (not-any? #(str/includes? (str %) "超时") results))))
    (check "记录里只有一条 tool result（重试不产生多余记录）" (= 1 (count results)))
    (check "最终答案非空" (not (str/blank? (str (:text result)))))
    (println "  工具实际执行次数：" @flaky-attempts "  LLM 调用次数：" @llm-calls)
    (println "  tool result：" (pr-str results))
    (println "  模型最终答案：" (pr-str (:text result)))))

;;; ============================================================
;;; 场景 4：abandon 残余风险——CPU 忙循环打不断，副作用在超时上报后落地
;;; ============================================================

(defn scenario-4 []
  (println "\n场景 4：abandon 残余风险——CPU 忙循环打不断（超时=放弃等待≠终止执行）")
  (reset! cpu-side-effect-at nil)
  (reset! timeout-reported-at nil)
  (let [{:keys [agent]} (make-agent [#'compute-forecast] nil)
        t0 (System/currentTimeMillis)
        result (agent/chat agent
                 "Run the demand forecast for SKU-3 and tell me what happened."
                 {:max-iterations 3})
        results (tool-results result)
        reported @timeout-reported-at]
    (check "工具被调用并在 800ms 声明处超时" (some? reported))
    (check "模型收到超时错误"
           (boolean (some #(str/includes? (str %) "超时") results)))
    (check "循环存活并作答" (not (str/blank? (str (:text result)))))
    (Thread/sleep 2500)   ;; 等 CPU 循环自己跑完
    (let [landed @cpu-side-effect-at]
      (check "被放弃的 CPU 工具**仍然跑完了**（interrupt 对不检查它的代码无效）"
             (some? landed))
      (check "其副作用落地在「已告知 LLM 超时」之后——真实的 abandon 残余风险"
             (boolean (and landed reported (> landed reported))))
      (when (and landed reported)
        (println (format "    超时上报 t=%dms → 副作用落地 t=%dms（晚 %dms，此间模型已在作答）"
                         (- reported t0) (- landed t0) (- landed reported)))))
    (println "  模型最终答案：" (pr-str (:text result)))))

;;; ============================================================

(defn run []
  (println "工具超时 x MiniMax live test（docs/tool-timeout-design.md）")
  (println "  JDK:" (System/getProperty "java.version"))
  (let [slow (start-backend 3000)
        fast (start-backend 0)]
    (reset! slow-port (.getLocalPort slow))
    (reset! fast-port (.getLocalPort fast))
    (println "  慢后端 :" @slow-port "（3s 才回）  快后端 :" @fast-port "（立刻回）")
    (try
      (scenario-1)
      (scenario-2)
      (scenario-3)
      (scenario-4)
      (catch Throwable t
        (swap! failures inc)
        (println "  ERROR" (.getMessage t))
        (.printStackTrace t))
      (finally
        (.close slow)
        (.close fast))))
  (println)
  (if (zero? @failures)
    (println "All checks passed")
    (println @failures "checks failed"))
  (System/exit (if (zero? @failures) 0 1)))

(run)

;;; ============================================================
;;; 实测记录（JDK 25.0.2，2026-07-16）——推翻了设计文档原 §2.2 的一条判断
;;;
;;; 原文写「InputStream.read() 读普通 socket 打不断，这条最要命」。实测：
;;;
;;;   虚拟线程 + socket read + interrupt → SocketException "Closed by interrupt"
;;;   平台线程 + socket read + interrupt → 读完成，interrupt 被完全忽略
;;;   CPU 忙循环（不检查标志）+ interrupt → 继续跑到自然结束
;;;
;;; 即：JDK 13+ 把 java.net.Socket 重实现在 NIO 之上（JEP 353），**虚拟线程上**
;;; 会响应 interrupt 并关闭 socket。而 P1-2 恰好把工具搬上了虚拟线程——于是
;;; 「阻塞 IO 打不断」这条对我们的实现**不成立**。原判断是平台线程时代的常识。
;;;
;;; 后果：P1-2 的价值被低估了——它不只让被放弃的执行变便宜（几 KB 栈），
;;; 更把最常见的工具形态（阻塞 IO）从「打不断」变成了「真能取消」。
;;; abandon 残余风险收窄为：不检查中断的 CPU 密集代码、native 调用、
;;; 吞掉 InterruptedException 的代码、工具自己 spawn 的平台线程。
;;; ============================================================
