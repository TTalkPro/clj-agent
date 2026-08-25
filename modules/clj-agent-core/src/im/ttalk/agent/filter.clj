(ns im.ttalk.agent.filter
  "Filter 系统 - 扁平 vector，注册顺序即执行顺序

    所有 filter 都是 around：(fn [req chain] -> resp)。
    filter 通过 :chat / :tool / :turn / :iteration 四个键挂到对应的链上，
    可任意并存。四条链从外到内：

        :turn        每 turn 一次        包整个工具循环
         └ :iteration 每轮一次           包 LLM 调用 + 本轮工具批次
            ├ :chat    每轮一次          只包 LLM 调用
            │  └ :token-xform 每 token
            └ :tool    每 tool call 一次 单个工具执行

    - :chat  包单次 LLM 调用（工具循环内每轮执行；memory/日志/重试）
    - :tool  包单次工具执行（并行任务内各自生效；超时/审批/限流）
    - :iteration 包**单轮迭代**（LLM 调用 + 该轮工具批次；单轮预算/单轮重试/
             基于本轮工具结果的收尾决策）。IterationRequest
             {:messages :context :index :remaining}，前两者可改写；
             IterationResult 为 {:status :continue :messages :context}
             （本轮跑完工具、还要接着转）或既有终态（:completed/:paused/
             :cancelled）。filter 可改写下一轮 delta、递归重入重跑这一轮、
             或不调 chain 短路成 :completed。
             **硬规则同 :turn**：:paused/:cancelled 结果必须透传、不得重入。
             **暂停照常出链**：暂停是终端的返回值而非异常，:paused 结果沿链
             回流，around 后半段照常执行、filter 看得见暂停——单轮计时/预算
             记账在 HITL 下也能正常收尾。
             **但 resume 的那半批不算一轮**：resume 执行的是「暂停那一轮的
             下半截」（批次已定、无新 LLM 调用），不经过本链；续跑的循环从下
             一个完整轮重新进链，:index 也从 0 重新计。
             **重入即记账**：重入一轮 = 那一轮的 LLM 调用与工具批次真的又跑了
             一遍，`remaining` 由循环本体在实际执行工具批次后扣减，故 filter
             重入自然计入，max-iterations 仍是硬上限。
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

    第五钩子 :token-xform（流式专用，非 around 形状）：值为一个 **transducer**，
    作用于出站 token-data 流（{:token ...} / {:reasoning-token ...}）。
    组装在 invoke-chat-stream 的 terminal 内（chat 链之后）：
    provider 原始 token → :token-xform 链（注册顺序，靠前者先见原始 token）→
    最终 on-token。正常完流调 completion arity（缓冲 flush），异常不 flush；
    状态作用域 = 单次 LLM 流。**硬边界**：只变换交付给 on-token 的流，
    不改 stream-fn 返回的最终 :response（memory/turn 用原文）。
    设计见 docs/token-stream-filter-design.md。

    Filter 定义（`Filter` record，五个钩子字段固定；写普通 map 也行，
    `build-chat-client` 会经 `as-filter` 归一化）:
      {:name :my-filter
       :chat (fn [req chain] ...)     ;; 可选，挂到 chat 链
       :tool (fn [req chain] ...)     ;; 可选，挂到 tool 链
       :turn (fn [req chain] ...)     ;; 可选，挂到 turn 链
       :iteration (fn [req chain] ...);; 可选，挂到 iteration 链
       :token-xform xform}            ;; 可选，流式 token 变换（transducer）

    五个钩子之外的键（如 memory filter 的 `:store`）照常可读——record 的
    ext-map 收着，`(:store f)` 不变。

    **装配期预编译**：`compile-hooks` 在 `build-chat-client` 时把五条链各折一次，
    产出 `CompiledHooks`；运行期只做 `(chain-builder terminal)`。此前每次
    `invoke-tool` / `invoke-chat` 都要 `keep` 全量 filters + `reverse` + `reduce`
    重建一遍链——工具循环里每轮每个 tool call 一次。

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
    (build-chat-client {:chat-model cm
                        :tools [#'t1 #'t2]
                        :filters [memory-filter retry-filter logging-filter]})"
  (:require [clojure.string]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.request :as req]
            [im.ttalk.agent.model.response :as resp]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; :chat 链的请求 / 响应类型
;;; ============================================================

;; 对标 Spring AI 的 ChatClientRequest / ChatClientResponse：**链上流动的东西**
;; 与**发给模型的东西**是两个类型，中间隔着 `:context`。
;;
;;     ChatClientRequest{:request ChatRequest, :context Context, :on-token f}
;;                        └────────┬────────┘
;;                        只有这一段下发给 provider
;;
;; 为什么不合成一个：`:context` 是请求级共享状态（只读快照，工具写意图经
;; :writes 折叠），`:on-token` 是流式 sink——两者都**不能**出现在 wire 上。
;; 合成一个类型就等于把「不该发出去的」和「要发出去的」放进同一个 map，
;; 再靠一张白名单去筛——那张白名单正是 provider-variant-design.md §1.2
;; 「递不到底」那个 bug 的成因。分成两层，筛子就不需要了。
(defrecord ChatClientRequest [request context on-token])

(defrecord ChatClientResponse [response context])

(defn chat-client-request?
  "是否已是 `ChatClientRequest` record。"
  [x]
  (instance? ChatClientRequest x))

(defn as-chat-client-request
  "归一化成 `ChatClientRequest`（已是 record 则原样返回）。

   map 写法里 `:context` / `:on-token` 之外的键**全部**归到内层 ChatRequest
   （`req/as-chat-request` 的扁平写法），故历史的扁平形状
   `{:messages … :tools … :tool-choice … :system-prompt … :context …}`
   照旧能构造出正确的两层结构。

   ⚠️ 归一化只管**构造**：filter 内部读字段仍须走下面的存取器
   （`(:messages req)` 在两层结构上会拿到 nil，不会静默给你旧语义）。"
  [x]
  (cond
    (chat-client-request? x) x
    (map? x) (->ChatClientRequest (req/as-chat-request (dissoc x :context :on-token :request))
                                  (:context x)
                                  (:on-token x))
    :else (throw (ex-info "无法归一化为 ChatClientRequest（需 record 或 map）"
                          {:value x :type (type x)}))))

(defn as-chat-client-response
  "归一化成 `ChatClientResponse`（已是 record 则原样返回）。"
  [x]
  (cond
    (instance? ChatClientResponse x) x
    (map? x) (->ChatClientResponse (:response x) (:context x))
    :else (throw (ex-info "无法归一化为 ChatClientResponse（需 record 或 map）"
                          {:value x :type (type x)}))))

;;; 存取器 —— filter 改写请求走这里，不必满屏 assoc-in
;;;
;;; 每个都是「读一层 / 写一层」的薄封装，存在的理由只有一个：两层结构下
;;; `(update req :messages …)` 会静默在**外层**建出一个不存在的 `:messages` 键，
;;; 而不是报错。给出存取器，改写点就不会写成那个样子。

(defn req-messages   "读本次请求的消息列表。" [r] (:messages (:request r)))
(defn req-context    "读请求级共享状态（只读快照）。" [r] (:context r))
(defn req-on-token   "读流式 sink（非流式为 nil）。" [r] (:on-token r))

(defn req-option
  "读一个调用选项（:tools / :tool-choice / :system-prompt / provider 私有键…）。"
  ([r k] (req/option (:request r) k))
  ([r k not-found] (req/option (:request r) k not-found)))

(defn with-messages
  "换掉消息列表，返回新 ChatClientRequest。"
  [r messages]
  (update r :request req/with-messages messages))

(defn update-messages
  "以函数更新消息列表，返回新 ChatClientRequest。"
  [r f & args]
  (assoc r :request (apply req/update-messages (:request r) f args)))

(defn with-option
  "写一个调用选项，返回新 ChatClientRequest。"
  [r k v]
  (update r :request req/with-option k v))

(defn with-options
  "合并一组调用选项，返回新 ChatClientRequest。"
  [r m]
  (update r :request req/with-options m))

(defn with-context
  "换掉请求级共享状态，返回新 ChatClientRequest。"
  [r ctx]
  (assoc r :context ctx))

(defn with-on-token
  "换掉流式 sink，返回新 ChatClientRequest。"
  [r f]
  (assoc r :on-token f))

;;; ============================================================
;;; Filter 创建
;;; ============================================================

(defrecord Filter [name chat tool turn iteration token-xform])

(defn filter?
  "是否已是 `Filter` record（归一化后的形态）。"
  [x]
  (instance? Filter x))

(defn as-filter
  "把 filter 定义归一化成 `Filter` record（已是 record 则原样返回）。

   五个钩子键落到 record 字段（读取走字段而非 hash 查找），其余键进 ext-map
   照常可读——memory filter 暴露的 `:store` 正是靠这条活着。

   `build-chat-client` 对 `:filters` 逐个调用本函数，所以用户侧写 map 字面量、
   写 `create-filter`、写 record 三种形态等价。"
  [f]
  (cond
    (filter? f) f
    (map? f)    (map->Filter f)
    :else       (throw (ex-info (str "filter 必须是 map 或 Filter record，实为 " (pr-str (type f)))
                                {:filter f}))))

(defn create-filter
  "创建 filter 定义。

    参数:
    - name: 标识(keyword)
    - opts: 可选键值对
      :chat (fn [req chain] resp)  — around 单次 LLM 调用，可选
      :tool (fn [req chain] resp)  — around 单次工具执行，可选
      :turn (fn [req chain] resp)  — around 整个工具循环（每 turn 一次），可选
      :iteration (fn [req chain] resp) — around 单轮迭代（LLM 调用 + 本轮工具
                                     批次），可选
      :token-xform xform           — 流式出站 token 变换（transducer），可选
      五者可任意并存

    返回: `Filter` record"
  [name & {:keys [chat tool turn iteration token-xform]}]
  (->Filter name chat tool turn iteration token-xform))

;;; ============================================================
;;; 洋葱链构建
;;; ============================================================

(defn build-chain
  "把 around 函数序列折成洋葱，最内层为 terminal。

    序列中靠前的函数在最外层（最先处理请求，最后处理响应）。
    返回 (fn [req] -> resp)。

    一次性用法（测试 / 手搓一条链）。**装配期已知 filter 集合时用
    `compile-chain`**——它把 `reverse` 和序列遍历折在装配期，运行期只剩
    「给 terminal 折 n 层闭包」。"
  [around-fns terminal]
  (reduce (fn [downstream f]
            (fn [req] (f req downstream)))
          terminal
          (reverse around-fns)))

(defn compile-chain
  "预折叠 around 序列 → `(fn [terminal] -> chain)`。装配期调一次，运行期
   每次调用只把 terminal 塞进去。

   **空序列返回 `identity`**：没有 filter 时链就是 terminal 本身，一层包装
   都不加——`invoke-tool` 在无 tool filter 的常见配置下由此彻底零开销。"
  [around-fns]
  (let [fs (vec (reverse around-fns))]
    (if (zero? (count fs))
      identity
      (fn [terminal]
        (reduce (fn [downstream f]
                  (fn [req] (f req downstream)))
                terminal
                fs)))))

;;; ============================================================
;;; 装配期预编译：CompiledHooks
;;; ============================================================

(defn- compile-token-xform
  "预 comp `:token-xform` 链 → `(fn [sink] -> rf)`，无则 nil。

   `sink` 是链上存活的 on-token。返回的 rf **每次流现场实例化**（有状态
   xform 的作用域 = 单次 LLM 流），但 `comp` 只在装配期做一次。"
  [xforms]
  (when-let [xf (some->> (seq xforms) (apply comp))]
    (fn [sink]
      (xf (fn ([acc] acc)
            ([acc tok] (when sink (sink tok)) acc))))))

(defrecord CompiledHooks [source chat tool turn iteration token-xform])

(defn compile-hooks
  "把 filter 向量编成五条预折叠的链构造器（四条 around + 一条 token xform）。

   - `:source`      归一化后的 filter 向量；持有者据此判断 hooks 是否仍与
                    自己的 `:filters` 同源（见 `chat-client/filter-hooks`）
   - `:chat` / `:tool` / `:turn` / `:iteration`  `(fn [terminal] -> chain)`
   - `:token-xform` `(fn [sink] -> rf)` 或 nil"
  [filters]
  (let [fs (mapv as-filter filters)]
    (->CompiledHooks fs
                     (compile-chain (keep :chat fs))
                     (compile-chain (keep :tool fs))
                     (compile-chain (keep :turn fs))
                     (compile-chain (keep :iteration fs))
                     (compile-token-xform (keep :token-xform fs)))))

;;; ============================================================
;;; 内置 filter: 日志
;;; ============================================================

(def logging-filter
  "日志 filter —— 打印工具调用信息与结果。"
  (create-filter :logging
   :tool (fn [req chain]
           (let [name (get-in req [:function :name])]
             (println (str "[ChatClient] 调用工具: " name " 参数: " (pr-str (:args req))))
             (let [resp (chain req)
                   r (:result resp)]
               (println (str "[ChatClient] 工具结果: " name " => "
                             (if (and (string? r) (> (count r) 100))
                               (str (subs r 0 100) "...")
                               (pr-str r))))
               resp)))))

;;; ============================================================
;;; 内置 filter: LLM 调用日志（:chat）
;;; ============================================================

(defn filter-hooks
  "取一个 ChatClient 的预编译 filter 链（`CompiledHooks`）。

   正常路径下就是 `build-chat-client` 装配期算好的那份，直接返回。若有人绕过
   `with-filters` 直接 `(assoc chat-client :filters ...)`，hooks 就与 `:filters`
   脱钩了——此时**现场重编译**兜底：语义永远跟着 `:filters` 走，宁可慢也
   不能静默用旧链（filter 悄悄失效是这套机制最难查的一类 bug）。

   **住在 filter ns 而不是 chat-client**：它认识的是链（`CompiledHooks`、
   `compile-hooks`），对 ChatClient 只用两个关键字取值——依赖方向因此是
   chat-client → filter 单向，不构成循环。"
  [chat-client]
  (let [hooks (:hooks chat-client)
        fs    (:filters chat-client)]
    (if (identical? (:source hooks) fs)
      hooks
      (compile-hooks fs))))

(defn with-filters
  "换掉 ChatClient 的 filter 链，同时重编译 hooks。**改 `:filters` 走这里**——
   直接 assoc 虽有 `filter-hooks` 兜底，但那是每次 invoke 都重编一遍，白扔
   装配期成果。"
  [chat-client fs]
  (let [hooks (compile-hooks fs)]
    (assoc chat-client :filters (:source hooks) :hooks hooks)))

;;; ============================================================
;;; 内置 filter: 日志（:chat）
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
    (create-filter :logging-chat
     :chat (fn [req chain]
             (emit (str "[Chat] → messages=" (count (req-messages req))
                        " tools=" (count (req-option req :tools))
                        " tool-choice=" (req-option req :tool-choice)))
             (let [resp (chain req)
                   r (:response resp)
                   calls (resp/response-tool-calls r)]
               (emit (str "[Chat] ← "
                          (if (seq calls)
                            (str "tool-calls=" (pr-str (mapv :name calls)))
                            (str "text=" (clip (resp/response-text r))))))
               resp)))))

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
    (create-filter :safeguard
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
                 (chain req)))))))

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
    (create-filter :re-reading
     :turn (fn [req chain]
             (if (or (:resume? req) (empty? (:messages req)))
               (chain req)
               (chain (update req :messages
                              (fn [ms]
                                (mapv (fn [m]
                                        (if (and (= :user (:role m)) (string? (:content m)))
                                          (assoc m :content (render (:content m)))
                                          m))
                                      ms)))))))))

;;; ============================================================
;;; 内置 filter: 敏感工具审批
;;;
;;; 注：这里曾有 timeout-filter（超时控制），2026-07-16 删除——超时已是内建机制：
;;; 工具声明 `deftool {:timeout ms}` > 引擎缺省 `(…-tool-calling-manager
;;; {:timeout ms})` > 不超时，由 chat-client/invoke-tool 强制、开箱即生效，filter
;;; 无事可做。机制本体是 tool/call-with-timeout。删除记录见 CHANGELOG 0.3.0。
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
     (create-filter :approval
      :tool (fn [req chain]
              (if (get-in req [:function :sensitive])
                (if (approve (get-in req [:function :name]) (:args req))
                  (chain req)
                  {:result "用户拒绝了此敏感工具调用"})
                (chain req)))))))

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
    (create-filter :validation
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
                     result))))))))

;;; ============================================================
;;; 内置 filter: token 流变换（:token-xform，流式专用）
;;; ============================================================

(defn token-redact-filter
  "无状态逐 token 正则脱敏 filter（:token-xform）。

   只改写 :token 字段，其余 token-data（:reasoning-token 等）原样透传。

   已知限制：秘密被切在两个 chunk 之间时漏检——跨 chunk 检测需有状态
   缓冲，按需自写 transducer 或改用 hold-release-filter。"
  [re replacement]
  (create-filter :token-redact
   :token-xform (map (fn [tok]
                    (if (:token tok)
                      (update tok :token clojure.string/replace re replacement)
                      tok)))))

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
  (create-filter :hold-release
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
                    acc))))))
