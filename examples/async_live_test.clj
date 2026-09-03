(ns async-live-test
  "异步全链路 × 真实端点验证（provider 可选）。

   单测里 provider 是 mock，证明不了两件事——**真实网络下调用线程是否真的没被占住**，
   以及**原生异步分支（IAsyncLLMProvider）在真端点上跑不跑得通**。本脚本验这些。

   覆盖：
   1. 原生异步探测：MiniMax 走 AnthropicProvider，应实现 IAsyncLLMProvider
   2. 非流式：chat-model/call-async* 真实往返，派发不阻塞
   3. 流式：invoke-chat-stream-async token 逐个到达 + **on-token 线程契约**
      （§2.1：异步下不在调用线程，且是虚拟线程）
   4. 全链路：invoke-async 带工具循环（:chat/:iteration/:turn 三条链都在异步形态）
   5. 并发：4 个会话同时打，墙钟 ≈ 单次往返而不是 4 倍
   6. 取消：异步流式中途取消，上游停止

   **两套原生异步实现各验一遍**（`IAsyncLLMProvider` 有两个实现方）：
   - `minimax` → `AnthropicProvider`（Anthropic 协议），缺省 MiniMax-M2.7
   - `zhipu`   → `OpenAICompatProvider`（OpenAI 兼容协议），缺省 glm-5.3-flash

   环境变量：`ASYNC_LIVE_PROVIDER`（minimax 缺省 | zhipu）、`ASYNC_LIVE_MODEL`（覆盖模型名）、
   `MINIMAX_API_KEY` / `ZHIPU_API_KEY`（按所选 provider）
   运行：clojure -M -e \"(load-file \\\"examples/async_live_test.clj\\\")\""
  (:require [im.ttalk.agent.async :as async]
            [im.ttalk.agent.chat-client :as chat-client]
            [im.ttalk.agent.chat-model :as chat-model]
            [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.filter.memory :as ma]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.model :as proto]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.request :as req]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.react :as agent-loop]
            [im.ttalk.agent.simple-agent :as agent]
            [im.ttalk.agent.streaming :as streaming]
            [im.ttalk.agent.tool :refer [deftool]]
            [im.ttalk.agent.provider.minimax :as minimax]
            [im.ttalk.agent.provider.zhipu :as zhipu]))

(def failures (atom 0))

(defn- check [ok? label & [detail]]
  (if ok?
    (println "  ✓" label (or detail ""))
    (do (swap! failures inc)
        (println "  ✗" label (or detail "")))))

;;; provider 选择：两个 record 各代表一套 IAsyncLLMProvider 实现
(def provider-key (or (System/getenv "ASYNC_LIVE_PROVIDER") "minimax"))

(def ^:private setup
  (case provider-key
    "minimax" {:make #(minimax/create-provider {})   ;; 直接读 MINIMAX_API_KEY
               :model (or (System/getenv "ASYNC_LIVE_MODEL") minimax/default-model)
               :env "MINIMAX_API_KEY"
               :record "AnthropicProvider（Anthropic 协议）"}
    "zhipu"   {:make #(zhipu/create-provider {})     ;; 直接读 ZHIPU_API_KEY
               :model (or (System/getenv "ASYNC_LIVE_MODEL") "glm-5.3-flash")
               :env "ZHIPU_API_KEY"
               :record "OpenAICompatProvider（OpenAI 兼容协议）"}
    (throw (ex-info (str "未知 ASYNC_LIVE_PROVIDER: " provider-key) {}))))

;;; 动态 var 而非 def：`run` 可被 run_all_examples 以外部 provider 调用
;;; （见文件末尾的嵌入约定），独立运行时由 env 决定。
(def ^:dynamic *provider* nil)
(def ^:dynamic *model* nil)
(def ^:dynamic *label* nil)

(defn- ms-since [t0] (/ (- (System/nanoTime) t0) 1e6))
(defn- clip [s n] (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) "…") s)))

(deftool get-weather
  "查询指定城市的天气"
  [[city :string "城市名"]]
  (str city "：晴，26°C"))

(defn- build-cc [& {:keys [tools]}]
  (chat-client/build-chat-client
    (cond-> {:chat-model (chat-model/create-chat-model *provider* {:model *model* :max-tokens 512})
             :filters [(ma/memory-filter (memory/in-memory-store))]}
      tools (assoc :tools tools))))

;;; ============================================================
;;; 1. 原生异步探测
;;; ============================================================

(defn scenario-native []
  (println "\n[1] 原生异步探测")
  (println "     record =" (.getName (class *provider*)))
  (check (satisfies? proto/ILLMProvider *provider*) "实现 ILLMProvider")
  (check (satisfies? proto/IAsyncLLMProvider *provider*)
         "实现 IAsyncLLMProvider（走原生异步 HTTP，不占虚拟线程等待）")
  (let [m (chat-model/create-chat-model *provider* {:model *model*})]
    (check (satisfies? chat-model/IAsyncChatModel m) "DefaultChatModel 实现 IAsyncChatModel")))

;;; ============================================================
;;; 2. 非流式：真实往返 + 派发不阻塞
;;; ============================================================

(defn scenario-call-async []
  (println "\n[2] 非流式 call-async*：真实往返")
  ;; max-tokens 给足：**思考模型的 reasoning 也吃这份预算**——256 时 M2.7 会把额度
  ;; 全花在思考上、正文一个字不出（实测过两次，别再调小）
  (let [m (chat-model/create-chat-model *provider* {:model *model* :max-tokens 2048})
        ;; 预热：进程首次调用要建连接池 / 类加载 / JIT，实测派发能到 100ms+。
        ;; 那不是「阻塞」，但会污染这一项的判据，故先打一发再计时。
        _ (async/join (chat-model/call-async* m (req/chat-request [(msg/user "hi")] {})) 60000)
        t0 (System/nanoTime)
        d (chat-model/call-async* m (req/chat-request [(msg/user "用一句话说明什么是虚拟线程")] {}))
        dispatch (ms-since t0)
        r (async/join d 60000)
        total (ms-since t0)
        text (response/response-text r)]
    (check (async/deferred? d) "立刻拿到 deferred")
    ;; 判据取相对值：派发耗时应远小于整轮往返（绝对阈值会被冷启动/网络抖动误判）
    (check (< dispatch (* 0.1 total))
           (format "派发不阻塞：%.1fms，占整轮 %.0fms 的 %.1f%%" dispatch total (* 100.0 (/ dispatch total))))
    (check (and (string? text) (seq text)) "拿到文本" (clip text 60))
    (check (satisfies? response/ILLMResponse r) "归一化成 ChatResponse（不是裸 HTTP map）")
    ;; 未知模型名有的厂商会静默回退到别的模型——回显核对，别把「跑通了」记错账
    (check (or (nil? (:model r)) (= *model* (:model r)))
           "响应回显的模型名与请求一致" (str "回显 " (pr-str (:model r))))))

;;; ============================================================
;;; 3. 流式：token 到达 + 线程契约（§2.1）
;;; ============================================================

(defn scenario-stream-async []
  (println "\n[3] 流式 invoke-chat-stream-async：token + 线程契约")
  (let [cc (build-cc)
        caller (Thread/currentThread)
        toks (atom [])
        threads (atom #{})
        t0 (System/nanoTime)
        d (chat-client/invoke-chat-stream-async
            ;; 要足够长才能分成多块——短答案会被服务端一个 chunk 发完，
            ;; 那样测不出增量性（也不代表不增量）
            cc [(msg/user "从 1 数到 30，用逗号分隔，只输出数字")]
            {:on-token (fn [t]
                         (when (:token t)
                           (swap! toks conj (:token t))
                           (swap! threads conj (Thread/currentThread))))})
        dispatch (ms-since t0)
        r (async/join d 60000)
        text (response/response-text (:response r))]
    (check (< dispatch 50) (format "派发不阻塞：%.1fms" dispatch))
    ;; 分块粒度由服务端决定（实测 M2.7 会把短答案合并成 1–3 块），
    ;; 只要 >1 块就证明是增量交付而非流末一次性爆出
    (check (> (count @toks) 1) (str "token 分多块逐个到达，共 " (count @toks) " 块"))
    (check (and (string? text) (seq text)) "最终响应完整" (clip text 60))
    (check (not (contains? @threads caller))
           "on-token **不在调用线程**上派发（§2.1 契约的实证）")
    (check (every? #(.isVirtual ^Thread %) @threads)
           "派发线程是虚拟线程"
           (str (mapv #(.getName ^Thread %) @threads)))))

;;; ============================================================
;;; 4. 全链路：invoke-async + 工具循环
;;; ============================================================

(defn scenario-full-loop []
  (println "\n[4] 全链路 invoke-async：工具循环 + 三条链的异步探针")
  (let [seen (atom [])
        probe (fn [hook] {:name (keyword (str "probe-" (name hook)))
                          hook (fn [req chain]
                                 (let [out (chain req)]
                                   (swap! seen conj [hook (async/deferred? out)])
                                   out))})
        store (memory/in-memory-store)
        cc (chat-client/build-chat-client
             {:chat-model (chat-model/create-chat-model *provider* {:model *model* :max-tokens 512})
              :tools [#'get-weather]
              :filters [(ma/memory-filter store) (probe :chat)
                        (probe :iteration) (probe :turn)]})
        t0 (System/nanoTime)
        cf (agent-loop/invoke-async cc store [(msg/user "杭州今天天气怎么样？用工具查。")]
                                    {:context (im.ttalk.agent.context/with-conversation-id
                                                (im.ttalk.agent.context/create) "live-async-1")})
        dispatch (ms-since t0)
        r (async/join cf 120000)]
    (check (< dispatch 100) (format "派发不阻塞：%.1fms" dispatch))
    (check (= :completed (:status r)) "循环完成" (str (:status r)))
    (check (pos? (count (:tool-calls-made r)))
           (str "工具被调用 " (count (:tool-calls-made r)) " 次")
           (pr-str (mapv :name (:tool-calls-made r))))
    (check (seq (get-in r [:response :text])) "有最终答案" (clip (get-in r [:response :text]) 60))
    (check (and (seq @seen) (every? true? (map second @seen)))
           "三条链在异步入口下拿到的都是 deferred（全链路异步）"
           (pr-str (frequencies (map first @seen))))))

;;; ============================================================
;;; 5. 并发：墙钟 ≈ 单次往返
;;; ============================================================

(defn scenario-concurrency []
  (println "\n[5] 并发：4 个会话同时打真实端点")
  ;; **判据不拿「另起一次调用」当基线**：思考模型单次耗时方差很大（实测同一 prompt
  ;; 2.3s~11s），拿它比会假失败。直接量每个请求自身的耗时，看总墙钟是「最慢那个」
  ;; 还是「四个之和」——这才是并发有没有生效的定义。
  (let [n 4
        agents (mapv (fn [_] (agent/create-agent {:chat-client (build-cc)})) (range n))
        t0 (System/nanoTime)
        cfs (mapv (fn [a i]
                    (let [ti (System/nanoTime)]
                      (flt/fmap (agent/chat-async a (str "只回答一个数字：" (inc i) " 加 1 等于几？"))
                                (fn [r] [r (ms-since ti)]))))
                  agents (range n))
        dispatch (ms-since t0)
        pairs (mapv #(async/join % 180000) cfs)
        total (ms-since t0)
        rs (mapv first pairs)
        durs (mapv second pairs)
        slowest (apply max durs)
        sum (reduce + durs)]
    (check (< dispatch 100) (format "4 个请求全部发出耗时 %.1fms" dispatch))
    (check (every? #(= :completed (:status %)) rs)
           "4 个都完成" (pr-str (mapv #(clip (:text %) 12) rs)))
    (check (< total (* slowest 1.3))
           (format "并发生效：总墙钟 %.0fms ≈ 最慢的那个 %.0fms（若串行需 %.0fms）"
                   total slowest sum)
           (str "各自耗时 " (mapv #(long %) durs) "ms"))))

;;; ============================================================
;;; 6. 取消：异步流式中途中止上游
;;; ============================================================

(defn scenario-cancel []
  (println "\n[6] 取消：异步流式中途取消")
  ;; **M2.7 是思考模型**：正文之前先出一大段 reasoning。上一版只数 :token，
  ;; 结果 512 的预算全花在思考上、正文一个字没出，计数永远到不了触发点。
  ;; 这里按「任何 token 都算流已开动」触发取消——要验的是取消，不是正文。
  (let [cc (build-cc)
        token (streaming/make-cancel-token)
        toks (atom [])
        d (binding [streaming/*register-cancel* (streaming/binding-register token)]
            (chat-client/invoke-chat-stream-async
              cc [(msg/user "写一篇 500 字的短文介绍杭州")]
              {:on-token (fn [t]
                           (when-let [piece (or (:token t) (:reasoning-token t))]
                             (swap! toks conj piece)
                             (when (= 5 (count @toks))
                               (streaming/request-cancel! token))))}))
        r (async/join d 60000)
        text (response/response-text (:response r))]
    (check (>= (count @toks) 5) (str "流已开动，收到 " (count @toks) " 块（含 reasoning）"))
    (check (streaming/cancelled? token) "令牌已标记取消")
    (check (< (count @toks) 100) (str "取消后停在 " (count @toks) " 块（未跑满 500 字全文）"))
    (check (some? r) "取消返回空/部分响应而不是抛错" (clip text 40))))

;;; ============================================================
;;; 汇总
;;; ============================================================

(defn run
  "跑全部场景，**返回失败项数**（不 exit——嵌入 run_all_examples 时要接着往下跑）。

   无参：provider/model 由 `ASYNC_LIVE_PROVIDER` / `ASYNC_LIVE_MODEL` 决定。
   传 `{:provider <ILLMProvider 实例> :model \"...\" :label \"...\"}`：直接用给定的
   provider（run_all_examples 就是这么复用它的两个 provider 的）。"
  ([] (run nil))
  ([{:keys [provider model label]}]
   (let [prov  (or provider (when (System/getenv (:env setup)) ((:make setup))))
         model (or model *model* (:model setup))
         label (or label (:record setup))]
     (if-not prov
       (do (println "跳过异步 live 验证：缺少" (:env setup)) 0)
       (binding [*provider* prov, *model* model, *label* label]
         (reset! failures 0)
         (println "异步全链路 live 验证 | model =" model)
         (println "  底层实现:" label)
         (doseq [f [scenario-native scenario-call-async scenario-stream-async
                    scenario-full-loop scenario-concurrency scenario-cancel]]
           (try (f)
                (catch Throwable t
                  (swap! failures inc)
                  (println "  ✗ 场景异常:" (.getMessage t)))))
         (println)
         (if (zero? @failures)
           (println "全部通过 ✓")
           (println @failures "项失败 ✗"))
         @failures)))))

;;; 独立运行才 exit；被 run_all_examples `load-file` 进来时只定义、不自跑
;;; （那边设了这个系统属性，随后自己调 `run`）。
(when-not (System/getProperty "clj-agent.embedded-examples")
  (System/exit (if (zero? (run)) 0 1)))
