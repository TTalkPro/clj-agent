(ns im.ttalk.agent.advisor
  "Filter 系统 - 扁平 vector，注册顺序即执行顺序

    所有 filter 都是 around：(fn [req chain] -> resp)。
    filter 通过 :chat / :tool / :turn 三个键挂到对应的链上，可任意并存：

    - :chat  包单次 LLM 调用（工具循环内每轮执行；memory/日志/重试）
    - :tool  包单次工具执行（并行任务内各自生效；超时/审批/限流）
    - :turn  包**整个工具循环**（每 turn 一次；RAG 注入/最终答案校验/
             guardrail/turn 级预算）。TurnRequest {:messages :context (:resume?)}
             可改写；TurnResult 为循环结果 {:status :response :tool-context ...}。
             闭包链天然\"仅下游\"，turn filter 可多次 (chain req) 递归重入
             （evaluator-optimizer；重入的 :messages 应为新 delta，完整上下文
             由 memory filter 拼接）。**硬规则**：:paused/:cancelled/:error
             结果必须透传、不得重入（暂停态上重试会破坏 HITL 语义）。
             resume 同样经过 turn 链（TurnRequest 带 :resume? true，首次进入
             延续暂停的 turn、:messages 为 nil）——请求侧改写类 filter（RAG
             注入等）应在 :resume? 时跳过首次改写；响应侧（校验/guardrail）
             无需感知，递归重入照常。

    第四钩子 :token-xform（流式专用，非 around 形状）：值为一个 **transducer**，
    作用于出站 token-data 流（{:token ...} / {:reasoning-token ...}）。
    组装在 invoke-chat-stream 的 terminal 内（chat 链之后）：
    provider 原始 token → :token-xform 链（注册顺序，靠前者先见原始 token）→
    最终 on-token。正常完流调 completion arity（缓冲 flush），异常不 flush；
    状态作用域 = 单次 LLM 流。**硬边界**：只变换交付给 on-token 的流，
    不改 stream-fn 返回的最终 :response（memory/turn 用原文）。
    设计见 docs/token-stream-filter-design.md。

    Filter 定义:
      {:name :my-filter
       :chat (fn [req chain] ...)     ;; 可选，挂到 chat 链
       :tool (fn [req chain] ...)     ;; 可选，挂到 tool 链
       :turn (fn [req chain] ...)     ;; 可选，挂到 turn 链
       :token-xform xform}            ;; 可选，流式 token 变换（transducer）

    filter 可以通过闭包携带自己的上下文：
      (defn caching-filter []
        (let [cache (atom {})]
          {:name :cache
           :chat (fn [req chain]
                   (if-let [hit (@cache (:k req))]
                     {:resp hit}
                     (let [resp (chain req)]
                       (swap! cache assoc (:k req) (:resp resp))
                       resp)))}))

    使用示例:
    (build-kernel {:service svc
                   :tools [#'t1 #'t2]
                   :filters [memory-filter retry-filter logging-filter]})"
  (:require [clojure.string]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.response :as resp]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; Filter 创建
;;; ============================================================

(defn create-filter
  "创建 filter 定义。

    参数:
    - name: 标识(keyword)
    - opts: 可选键值对
      :chat (fn [req chain] resp)  — around 单次 LLM 调用，可选
      :tool (fn [req chain] resp)  — around 单次工具执行，可选
      :turn (fn [req chain] resp)  — around 整个工具循环（每 turn 一次），可选
      :token-xform xform           — 流式出站 token 变换（transducer），可选
      四者可任意并存

    返回: filter 定义 map"
  [name & {:keys [chat tool turn token-xform]}]
  (cond-> {:name name}
    chat (assoc :chat chat)
    tool (assoc :tool tool)
    turn (assoc :turn turn)
    token-xform (assoc :token-xform token-xform)))

;;; ============================================================
;;; 洋葱链构建
;;; ============================================================

(defn build-chain
  "把 around 函数序列折成洋葱，最内层为 terminal。

    序列中靠前的函数在最外层（最先处理请求，最后处理响应）。
    返回 (fn [req] -> resp)。"
  [around-fns terminal]
  (reduce (fn [downstream f]
            (fn [req] (f req downstream)))
          terminal
          (reverse around-fns)))

;;; ============================================================
;;; 内置 filter: 日志
;;; ============================================================

(def logging-filter
  "日志 filter —— 打印工具调用信息与结果。"
  {:name :logging
   :tool (fn [req chain]
           (let [name (get-in req [:function :name])]
             (println (str "[Kernel] 调用工具: " name " 参数: " (pr-str (:args req))))
             (let [resp (chain req)
                   r (:result resp)]
               (println (str "[Kernel] 工具结果: " name " => "
                             (if (and (string? r) (> (count r) 100))
                               (str (subs r 0 100) "...")
                               (pr-str r))))
               resp)))})

;;; ============================================================
;;; 内置 filter: LLM 调用日志（:chat）
;;; ============================================================

(defn logging-chat-filter
  "LLM 请求/响应日志 filter（对标 Spring AI `SimpleLoggerAdvisor`）。

   `logging-filter` 只覆盖工具侧；本 filter 补上 LLM 侧——工具循环内每轮一次。

   opts:
   - :log-fn   (fn [line] ...)，缺省 println
   - :preview  文本预览截断长度（缺省 200；nil 表示不截断）"
  [& {:keys [log-fn preview] :or {preview 200}}]
  (let [emit (or log-fn println)
        clip (fn [s]
               (let [s (str s)]
                 (if (and preview (> (count s) preview))
                   (str (subs s 0 preview) "...")
                   s)))]
    {:name :logging-chat
     :chat (fn [req chain]
             (emit (str "[Chat] → messages=" (count (:messages req))
                        " tools=" (count (:tools req))
                        " tool-choice=" (:tool-choice req)))
             (let [resp (chain req)
                   r (:response resp)
                   calls (resp/response-tool-calls r)]
               (emit (str "[Chat] ← "
                          (if (seq calls)
                            (str "tool-calls=" (pr-str (mapv :name calls)))
                            (str "text=" (clip (resp/response-text r))))))
               resp))}))

;;; ============================================================
;;; 内置 filter: 敏感词短路（:turn）
;;; ============================================================

(defn- message-text
  "取消息的纯文本内容（多模态 content 向量取其中的文本片段）。"
  [m]
  (let [c (:content m)]
    (cond
      (string? c) c
      (sequential? c) (->> c
                           (keep #(cond (string? %) % (map? %) (:text %)))
                           (clojure.string/join " "))
      :else "")))

(defn safeguard-turn-filter
  "敏感词短路 filter（对标 Spring AI `SafeGuardAdvisor`）。

   入口消息命中任一敏感词 → **不进循环**，直接返回 failure-response。
   匹配为**大小写不敏感的子串包含**（Spring 原版大小写敏感；我们放宽，
   因为大小写绕过是显然的漏网）。

   **为什么挂 :turn 而不是 :chat**：Spring 的 SafeGuard 查的是「用户这次的
   输入」，在工具循环外只查一次。挂 :chat 会在循环内每轮重查，而第 2 轮起
   `:messages` 是 memory 拼出的**完整历史 + 工具往返**——既重复告警又会因
   历史里的旧内容误伤。

   **语义后果（刻意）**：短路发生在 :turn 层，memory filter（:chat，在循环内）
   压根不会执行——被拦的输入与拒答都不落库。history 里不留有害内容，代价是
   下一轮模型看不到「用户问过什么、被拒了」。

   resume 天然安全：`:resume?` 进入时 `:messages` 为 nil，无文本可查即放行
   （延续暂停的 turn，入口消息早在首次进入时查过了）。

   参数:
   - sensitive-words: 敏感词集合/序列
   - :failure-response 命中时的回复文本（缺省「抱歉，我无法回应该内容。」）"
  [sensitive-words & {:keys [failure-response]
                      :or {failure-response "抱歉，我无法回应该内容。"}}]
  (let [words (->> sensitive-words
                   (map (comp clojure.string/lower-case str))
                   (remove clojure.string/blank?)
                   set)]
    {:name :safeguard
     :turn (fn [req chain]
             (let [text (->> (:messages req)
                             (map message-text)
                             (clojure.string/join " ")
                             clojure.string/lower-case)
                   hit (when (seq text)
                         (first (filter #(clojure.string/includes? text %) words)))]
               (if hit
                 {:status :completed
                  :response (resp/make-response :text failure-response)
                  :tool-context (:context req)
                  :tool-calls-made []
                  :blocked-by :safeguard}      ;; 观察用；调用方可据此计数/告警
                 (chain req))))}))

;;; ============================================================
;;; 内置 filter: RE2 重读（:turn）
;;; ============================================================

(defn re-reading-filter
  "RE2 重读 filter（对标 Spring AI `ReReadingAdvisor`）。

   把入口的用户问题重复一遍附在其后（\"Read the question again: ...\"），
   出处：Re-Reading Improves Reasoning in LLMs。

   挂 :turn 且只改写**入口消息**——每 turn 增强一次。挂 :chat 会把循环内每轮
   的历史都重读一遍（既无意义又污染 transcript）。`:resume?` 时跳过
   （延续场景没有入口消息可改写，改了也会被忽略）。

   opts:
   - :template (fn [原问题] -> 重读文本)，缺省
               「<原问题>\\nRead the question again: <原问题>」"
  [& {:keys [template]}]
  (let [render (or template
                   (fn [q] (str q "\nRead the question again: " q)))]
    {:name :re-reading
     :turn (fn [req chain]
             (if (or (:resume? req) (empty? (:messages req)))
               (chain req)
               (chain (update req :messages
                              (fn [ms]
                                (mapv (fn [m]
                                        (if (and (= :user (:role m)) (string? (:content m)))
                                          (assoc m :content (render (:content m)))
                                          m))
                                      ms))))))}))

;;; ============================================================
;;; 内置 filter: 超时控制
;;; ============================================================

(defn timeout-filter
  "超时控制 filter 工厂：下游执行超过时限则返回超时结果（不抛异常）。

   时限解析优先级：**工具自己声明的 `:timeout`**（deftool 选项，经
   `:function :timeout` 抵达）> 本 filter 构造时的 timeout-ms 缺省值。
   工具最清楚自己要跑多久；filter 值是没声明时的整体缺省。
   inline 工具（delegate 等）无 var 元数据，恒取 filter 缺省值。

   **语义如实声明：超时 = 放弃等待，不是终止执行。**JVM 没有强杀原语
   （Thread.stop 已移除），超时后只能 interrupt 下游线程：
   - 阻塞在 Thread/sleep、可中断 IO 上的工具会被打断；
   - 纯 CPU 循环、普通 socket read（绝大多数 HTTP 客户端）**打不断**——
     工具会继续跑完，其外部副作用可能在超时结果返回**之后**才落地。
   超时归类 :transient：声明 :retry 的工具会被自动重试——重试发起时
   **上一次调用可能仍在执行**，故声明 :retry 的超时工具必须幂等。

   下游跑在**虚拟线程**上（不用 clojure.core/future 的 send-off 平台
   线程池）：被放弃的执行只占几 KB 虚拟线程栈，且不破坏
   ToolCallingManager 各引擎的线程模型（工具函数体不被搬去平台线程）。

   超时结果不带 :writes——被超时调用的写意图不生效（事务性）。"
  [timeout-ms]
  {:name :timeout
   :tool (fn [req chain]
           (let [t-ms (or (get-in req [:function :timeout]) timeout-ms)
                 p    (promise)
                 th   (Thread/startVirtualThread
                        (fn []
                          (deliver p (try {:ok (chain req)}
                                          (catch Throwable e {:err e})))))
                 r    (deref p t-ms ::timeout)]
             (cond
               (= r ::timeout)
               (do (.interrupt th)
                   {:result (str "工具调用超时（" t-ms "ms）")
                    :error  {:class :transient
                             :message (str "timeout " t-ms "ms")}})

               (:err r) (throw ^Throwable (:err r))
               :else    (:ok r))))})

;;; ============================================================
;;; 内置 filter: 敏感工具审批
;;; ============================================================

(defn approval-filter
  "敏感工具审批 filter：对标记 :sensitive 的工具调用做人工确认，拒绝则短路。

    参数:
    - approve-fn: (fn [func-name args] -> boolean)，默认走标准输入交互"
  ([] (approval-filter nil))
  ([approve-fn]
   (let [default-approve (fn [func-name args]
                           (println (str "\n[审批] 敏感工具调用:"))
                           (println (str "  工具: " func-name))
                           (println (str "  参数: " (pr-str args)))
                           (print "  是否允许执行? (y/n): ")
                           (flush)
                           (= "y" (clojure.string/lower-case (or (read-line) ""))))
         approve (or approve-fn default-approve)]
     {:name :approval
      :tool (fn [req chain]
              (if (get-in req [:function :sensitive])
                (if (approve (get-in req [:function :name]) (:args req))
                  (chain req)
                  {:result "用户拒绝了此敏感工具调用"})
                (chain req)))})))

;;; ============================================================
;;; 内置 filter: 最终答案校验（turn 链，递归重试）
;;; ============================================================

(defn validation-turn-filter
  "最终答案校验 turn filter（对标 Spring AI StructuredOutputValidationAdvisor，
   设计见 docs/agent-loop-concurrency-design.md §14.2）。

   validate-fn: (fn [turn-result] -> nil=通过 | 字符串=不合格原因)。
   不合格时把原因作为反馈消息重入循环（新 delta；完整上下文由 memory filter
   拼接，故须与 memory 同挂），最多重试 max-retries 次，耗尽后原样返回最后
   一次结果（调用方自行判断 valid 与否）。

   :paused / :cancelled / :error 结果透传不重试（HITL 硬规则）。

   opts:
   - :max-retries  重试上限（缺省 2）
   - :feedback-fn  (fn [原因] -> 中立消息)，缺省生成
                   「你的上一个回答未通过校验：<原因>。请修正后重新回答。」"
  [validate-fn & {:keys [max-retries feedback-fn]
                  :or {max-retries 2}}]
  (let [mk-feedback (or feedback-fn
                        (fn [problem]
                          (msg/user (str "你的上一个回答未通过校验：" problem
                                         "。请修正后重新回答。"))))]
    {:name :validation
     :turn (fn [req chain]
             (loop [attempt 0, req req]
               (let [result (chain req)]
                 (if (not= :completed (:status result))
                   result                          ;; 暂停/取消/错误：透传
                   (if-let [problem (validate-fn result)]
                     (if (>= attempt max-retries)
                       result                      ;; 耗尽：原样返回
                       ;; 重入是有真实入口消息的新循环：摘掉可能继承的
                       ;; :resume? 标记，让下游请求侧 filter 照常工作
                       (recur (inc attempt)
                              (-> req
                                  (dissoc :resume?)
                                  (assoc :messages [(mk-feedback problem)]))))
                     result)))))}))

;;; ============================================================
;;; 内置 filter: token 流变换（:token-xform，流式专用）
;;; ============================================================

(defn token-redact-filter
  "无状态逐 token 正则脱敏 filter（:token-xform）。

   只改写 :token 字段，其余 token-data（:reasoning-token 等）原样透传。

   已知限制：秘密被切在两个 chunk 之间时漏检——跨 chunk 检测需有状态
   缓冲，按需自写 transducer 或改用 hold-release-filter。"
  [re replacement]
  {:name :token-redact
   :token-xform (map (fn [tok]
                    (if (:token tok)
                      (update tok :token clojure.string/replace re replacement)
                      tok)))})

(defn hold-release-filter
  "先审后放 filter（:token-xform）：缓冲整条流不外泄，正常完流时 check-fn
   收全文（:token 拼接），通过则缓冲按原序放行，不通过则只 emit 一个
   替换 token。

   check-fn: (fn [full-text] -> nil=通过 | 字符串=不通过时的替换文本)。

   代价即语义：用户在流结束前看不到任何 token——「完整答案没成形就无法审」
   的根本矛盾任何机制都消不掉（Spring AI 的 Flux buffer 同样毁流式 UX），
   本 filter 只是把缓冲逻辑标准化。异常完流不 flush（缓冲丢弃）由机制保证。

   注意：只影响交付给 on-token 的流；最终 :response 仍是原始完整答案，
   要改写答案本身请用 validation-turn-filter（turn 链）。"
  [check-fn]
  {:name :hold-release
   :token-xform (fn [rf]
               (let [buf (volatile! [])]
                 (fn
                   ([] (rf))
                   ([acc]
                    (let [toks @buf
                          _ (vreset! buf [])
                          text (apply str (keep :token toks))
                          acc (if-let [replacement (check-fn text)]
                                (unreduced (rf acc {:token replacement}))
                                (reduce rf acc toks))]
                      (rf acc)))
                   ([acc tok]
                    (vswap! buf conj tok)
                    acc))))})
