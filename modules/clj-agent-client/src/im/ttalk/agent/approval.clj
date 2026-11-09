(ns im.ttalk.agent.approval
  "声明式审批策略、会话审批缓存、审批者链、拒绝熔断（designs/runtime-borrow.md §5 §6；
   借 Keelson `Tools/ApprovalPolicy.cs` `RulePolicy.cs` `ApprovalCache.cs` `ToolHintPolicy.cs`
   与 `Advisors/ChainApprover.cs` `DenialBreaker.cs` `Approvers.cs`，它们又借 Codex execpolicy /
   guardian）。

   全是**纯函数与数据**，产出现有挂点能直接吃的东西：

   | 出口 | 给谁 | 场景 |
   |---|---|---|
   | `gate`            | react `:tool-gate` / simple-agent `:approval` | HITL：要问就**暂停**等人 |
   | `approval-filter` | chat-client `:filters`（`:tool` 链）          | 同步审批（CLI / 审批服务 / 模型审阅者） |
   | `->approve-fn`    | 旧的 `flt/approval-filter`                    | 兼容 |

   **决策**：`{:decision :allow|:prompt|:forbid :reason 字符串? :allow-session? bool}`，
   多条取最严（forbid > prompt > allow）。

   **策略**：`(fn [req] -> 决策)`，req = `{:name :args :sensitive? :inline? :hints :tool-call}`。

   **审批者**：`(fn [req] -> {:approved? bool :reason? :remember? :timed-out?} | nil)`，
   nil = 不表态（交给链上的下一位）。`:remember :session` = 「本会话都批准」。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.filter.tool-guard :as tool-guard]
            [im.ttalk.agent.tool :as tool]
            [im.ttalk.agent.tool-registry :as registry]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 决策
;;; ============================================================

(def ^:private rank {:allow 0 :prompt 1 :forbid 2})

(def allow "直接执行。" {:decision :allow})
(defn prompt "要问。" ([] (prompt nil)) ([reason] (cond-> {:decision :prompt} reason (assoc :reason reason))))
(defn forbid "不执行也不问，回拒绝（带理由）给模型。" ([] (forbid nil)) ([reason] (cond-> {:decision :forbid} reason (assoc :reason reason))))

(defn strictest
  "取最严（同级取先给的）；空 → allow。"
  [decisions]
  (reduce (fn [best d] (if (and d (> (rank (:decision d) 0) (rank (:decision best) 0))) d best))
          allow decisions))

;;; ============================================================
;;; 审批键 / 会话缓存
;;; ============================================================

(defn approval-key
  "审批键：工具名 + 规范化参数（键序不同、同值 → 同键）。"
  [tool-name args]
  (tool-guard/fingerprint (name tool-name) args))

(defn session-cache
  "会话审批缓存（「本会话都批准」过的键）。线程安全。"
  ([] (session-cache nil))
  ([initial] (atom (set initial))))

(defn approved? [cache k] (boolean (and cache (contains? @cache k))))
(defn remember! [cache k] (when cache (swap! cache conj k)) nil)
(defn snapshot "缓存快照（排序后的键向量，可 EDN 往返；随暂停快照保存）。" [cache] (when cache (vec (sort @cache))))
(defn restore! "把快照并回缓存。" [cache ks] (when (and cache (seq ks)) (swap! cache into ks)) nil)

;;; ============================================================
;;; JSON path + 谓词 + glob
;;; ============================================================

(defn parse-path
  "`$` `$.a.b` `a.b` `$.argv[0]` `$['k.x']` `a.0` → 段向量（字符串或整数下标）。非法抛。"
  [path]
  (let [s (str/trim (str path))
        s (cond (= s "$") "" (str/starts-with? s "$") (subs s 1) :else (str "." s))
        n (count s)]
    (loop [i 0, segs []]
      (if (>= i n)
        segs
        (let [c (.charAt ^String s i)]
          (cond
            (= c \.)
            (let [j (loop [j (inc i)] (if (and (< j n) (not (#{\. \[} (.charAt ^String s j)))) (recur (inc j)) j))
                  nm (subs s (inc i) j)]
              (when (str/blank? nm) (throw (ex-info (str "JSON path 非法（空段）：" path) {:path path})))
              (recur (long j) (conj segs (if (re-matches #"\d+" nm) (Long/parseLong nm) nm))))

            (= c \[)
            (let [close (str/index-of s "]" i)]
              (when-not close (throw (ex-info (str "JSON path 非法（缺 ]）：" path) {:path path})))
              (let [inner (str/trim (subs s (inc i) close))]
                (recur (long (inc close))
                       (conj segs (cond
                                    (re-matches #"\d+" inner) (Long/parseLong inner)
                                    (re-matches #"'(.*)'|\"(.*)\"" inner) (subs inner 1 (dec (count inner)))
                                    :else (throw (ex-info (str "JSON path 非法：" path) {:path path})))))))

            :else (throw (ex-info (str "JSON path 非法：" path) {:path path}))))))))

(defn- path-get
  "按段取值 → [found? v]。map 段先试 keyword 再试字符串；数字段在序列上当下标、在 map 上当键。"
  [args segs]
  (reduce (fn [[_ v] seg]
            (cond
              (and (integer? seg) (sequential? v))
              (if (< -1 seg (count v)) [true (nth v seg)] (reduced [false nil]))

              (map? v)
              (let [ks (if (integer? seg) [(keyword (str seg)) (str seg) seg] [(keyword seg) seg])]
                (if-let [k (some #(when (contains? v %) %) ks)]
                  [true (get v k)]
                  (reduced [false nil])))

              :else (reduced [false nil])))
          [true args]
          segs))

(defn- same? [a b]
  (let [norm #(if (keyword? %) (name %) %)
        a (norm a) b (norm b)]
    (cond
      (and (number? a) (number? b)) (== a b)
      (and (sequential? a) (sequential? b)) (and (= (count a) (count b)) (every? true? (map same? a b)))
      :else (= a b))))

(def ^:private ops #{:equals :in :regex :exists :prefix})

(defn- compile-predicate [p]
  (let [present (filter #(contains? p %) ops)]
    (when (not= 1 (count present))
      (throw (ex-info (str "参数谓词必须恰好一个运算（equals/in/regex/exists/prefix），实为 " (vec present))
                      {:predicate p})))
    (let [segs (parse-path (or (:path p) "$"))
          op (first present)
          arg (get p op)
          re (when (= op :regex)
               (try (re-pattern arg)
                    (catch Exception e (throw (ex-info (str "正则非法：" arg) {:predicate p} e)))))]
      (fn [args]
        (let [[found? v] (path-get args segs)]
          (case op
            :exists (= (boolean arg) found?)
            :equals (and found? (same? arg v))
            :in (and found? (boolean (some #(same? % v) arg)))
            :regex (and found? (or (string? v) (keyword? v)) (boolean (re-find re (name v))))
            :prefix (and found? (sequential? v) (>= (count v) (count arg))
                         (every? true? (map (fn [want got]
                                              (if (sequential? want) (boolean (some #(same? % got) want)) (same? want got)))
                                            arg v)))))))))

(defn glob-match?
  "工具名 glob：`*` 任意串、`?` 一个字符。"
  [pattern s]
  (let [re (str "\\A"
                (apply str (map #(case % \* ".*" \? "." (java.util.regex.Pattern/quote (str %))) (str pattern)))
                "\\z")]
    (boolean (re-matches (re-pattern re) (str s)))))

;;; ============================================================
;;; 策略
;;; ============================================================

(defn sensitive-policy
  "缺省策略（今天的行为）：`:sensitive?` → prompt，否则 allow。"
  [req]
  (if (:sensitive? req) (prompt) allow))

(defn strictest-policy
  "几条策略一起判，取最严。"
  [& policies]
  (fn [req] (strictest (map #(% req) policies))))

(defn- compile-rule [rule]
  (let [{:keys [tool decision justification match not-match]
         :or {decision :allow}} rule]
    (when (str/blank? (str tool)) (throw (ex-info "规则缺 :tool（工具名 glob）" {:rule rule})))
    (when-not (contains? rank decision)
      (throw (ex-info (str "规则 :decision 非法：" decision "（allow/prompt/forbid）") {:rule rule})))
    (when (and (some? justification) (str/blank? (str justification)))
      (throw (ex-info "规则 :justification 给了就不能是空白" {:rule rule})))
    (let [preds (mapv compile-predicate (:when rule))
          matches-args? (fn [args] (every? #(% args) preds))
          compiled {:rule rule :decision decision :justification justification
                    :matches? (fn [tool-name args] (and (glob-match? tool tool-name) (matches-args? args)))}]
      ;; 正反例自检：规则写错了在构造时就炸，不等到线上放错一次
      (doseq [ex match]
        (when-not (matches-args? ex)
          (throw (ex-info (str "规则自检失败：:match 例子没命中 " (pr-str ex)) {:rule rule}))))
      (doseq [ex not-match]
        (when (matches-args? ex)
          (throw (ex-info (str "规则自检失败：:not-match 例子命中了 " (pr-str ex)) {:rule rule}))))
      compiled)))

(defn rule-policy
  "数据驱动的审批策略：规则按工具名 glob + 参数谓词命中，**多条命中取最严**；
   一条都没命中 → `:fallback`（缺省 `sensitive-policy`）。规则在构造时校验与自检，
   不合格抛 ex-info。

   规则：
   {:tool \"shell*\"
    :when [{:path \"$.argv\" :prefix [\"git\" [\"status\" \"log\"]]}]   ; 恰好一个运算
    :decision :allow                                                  ; 缺省 :allow
    :justification \"只读 git 命令\"
    :match [{:argv [\"git\" \"status\"]}] :not-match [{:argv [\"git\" \"push\"]}]}

   谓词运算：`:equals`（深相等）`:in`（其一）`:regex`（字符串，不锚定）
   `:exists`（true/false）`:prefix`（数组前缀，某项写成向量 = 几种候选）。"
  [rules & {:keys [fallback] :or {fallback sensitive-policy}}]
  (let [compiled (mapv compile-rule rules)]
    (with-meta
      (fn [req]
        (let [hits (filter #((:matches? %) (str (:name req)) (or (:args req) {})) compiled)]
          (if (empty? hits)
            (fallback req)
            (let [best (apply max-key #(rank (:decision %)) (reverse hits))
                  reason (some #(when (= (:decision best) (:decision %)) (:justification %)) hits)]
              (case (:decision best)
                :allow allow
                :prompt (prompt reason)
                :forbid (forbid reason))))))
      {::rules rules})))

(defn requires-approval?
  "MCP 注解判据（Codex `requires_mcp_tool_approval_for_mode`）。hints 键：
   `:read-only?` `:destructive?` `:open-world?`（nil = 没给）。"
  [{:keys [read-only? destructive? open-world?]} mode]
  (case mode
    :prompt true
    :writes (not (true? read-only?))
    :approve false
    ;; :auto
    (cond
      (true? destructive?) true
      (true? read-only?) false
      :else (or (if (nil? destructive?) true destructive?)
                (if (nil? open-world?) true open-world?)))))

(defn hint-policy
  "按工具注解判的策略：只管**内联工具**（MCP 等外来工具，`:inline? true`）；var 工具
   仍按 `:sensitive?`。模式：`:auto`（注解：destructive⇒问、read-only⇒免、缺省⇒问）、
   `:prompt`（都问）、`:writes`（只读免问）、`:approve`（都不问）。`:prompt`/`:writes`
   问的时候不许「本会话都批准」（同 Codex）。

   opts: :mode 缺省模式（:auto）；:modes [[工具名glob 模式] …] 先到先得。"
  [& {:keys [mode modes] :or {mode :auto}}]
  (fn [req]
    (if-not (:inline? req)
      (sensitive-policy req)
      (let [m (or (some (fn [[g md]] (when (glob-match? g (:name req)) md)) modes) mode)]
        (cond
          (not (requires-approval? (:hints req) m)) allow
          (#{:prompt :writes} m) (assoc (prompt) :allow-session? false)
          :else (prompt))))))

;;; ============================================================
;;; 工具请求（策略的输入）
;;; ============================================================

(defn- hint-bool [v]
  (cond (boolean? v) v (#{"true" "false"} v) (= "true" v) :else nil))

(defn hints-of
  "从内联工具 map 读注解：`:annotations {:readOnlyHint ..}`（MCP 形状，键 keyword/字符串皆可）、
   顶层 `:read-only-hint` 等，或 MCP 接入时放在元数据里的原始工具定义。"
  [tool-map]
  (let [raw (:im.ttalk.agent.mcp.tools/raw (meta tool-map))
        ann (or (:annotations tool-map) (get tool-map "annotations")
                (get raw "annotations") (:annotations raw))
        ;; 取第一个非 nil（`or` 会把 false 当缺失跳过，注解 false 正是要的信息）
        pick (fn [k top] (hint-bool (first (remove nil? [(get tool-map top) (get ann k) (get ann (name k))]))))]
    {:read-only? (pick :readOnlyHint :read-only-hint)
     :destructive? (pick :destructiveHint :destructive-hint)
     :open-world? (pick :openWorldHint :open-world-hint)}))

(defn tool-request
  "react 的 tool-call → 策略输入。"
  [chat-client tc]
  (let [nm (str (:name tc))
        tmeta (registry/tool-meta chat-client nm)
        var? (some? (registry/find-function chat-client nm))
        schema (when-not var? (some #(when (= nm (str (:name %))) %) (registry/tool-schemas chat-client)))]
    {:name nm
     :args (:args tc)
     :sensitive? (boolean (get-in tmeta [:func-def :sensitive]))
     :inline? (and (some? tmeta) (not var?))
     :hints (if schema (hints-of schema) {})
     :tool-call tc}))

;;; ============================================================
;;; 出口一：react :tool-gate（HITL 暂停）
;;; ============================================================

(defn gate
  "策略 → react 的 `:tool-gate`：
   - allow  → `:proceed`
   - forbid → `{:reject 理由}`（不问，模型直接拿到原因）
   - prompt → 会话缓存命中 `:proceed`，否则 `{:pause {:reason :allow-session? :key}}`
             （react 暂停；`:key` 供 resume 时「本会话都批准」记账）

   opts: :policy（缺省 sensitive-policy）、:cache（`session-cache`，可选）"
  [chat-client {:keys [policy cache] :or {policy sensitive-policy}}]
  (fn [tc]
    (let [req (tool-request chat-client tc)
          d (policy req)
          k (approval-key (:name req) (:args tc))
          session? (not (false? (:allow-session? d)))]
      (case (:decision d)
        :forbid {:reject (or (:reason d) "策略禁止执行该工具")}
        :prompt (if (and session? (approved? cache k))
                  :proceed
                  {:pause (cond-> {:allow-session? session? :key k}
                            (:reason d) (assoc :reason (:reason d)))})
        :proceed))))

;;; ============================================================
;;; 审批者组合子（R-06）
;;; ============================================================

(defn chain-reviewer
  "审批链（可嵌套）：按序问，第一个非 nil 的说了算；某环抛 → 拒（带异常信息，不再往下问）；
   到底都不表态 → nil。"
  [& approvers]
  (fn [req]
    (reduce (fn [_ a]
              (let [r (try (a req)
                           (catch Throwable t
                             {:approved? false
                              :reason (str "审批环节出错（" (.getSimpleName (class t)) "）："
                                           (.getMessage t))}))]
                (if (some? r) (reduced r) nil)))
            nil approvers)))

(defn chain-approver
  "同 `chain-reviewer`，但到底无人表态 → **拒**（无人表态 ≠ 默许）。"
  [& approvers]
  (let [rv (apply chain-reviewer approvers)]
    (fn [req] (or (rv req) {:approved? false :reason "无人表态（审批链里没有一环给出决定）"}))))

(defn timeout-approver
  "给审批者套时限：超时是**结果**（`{:approved? false :timed-out? true}`），不是异常。
   在虚拟线程上跑，到点 interrupt（同工具超时的语义：放弃等待）。"
  [approver timeout-ms]
  (fn [req]
    (let [[tag v] (tool/call-with-timeout timeout-ms #(approver req))]
      (case tag
        :ok v
        :err (throw ^Throwable v)
        :timeout {:approved? false :timed-out? true :reason (str "审批超时（" timeout-ms "ms）")}))))

;;; ============================================================
;;; 拒绝熔断（R-06）
;;; ============================================================

(defn breaker-state
  "熔断初始状态。opts: :max-consecutive 3、:max-recent 10、:window 50（同 Codex guardian）。"
  ([] (breaker-state nil))
  ([opts]
   {:opts (merge {:max-consecutive 3 :max-recent 10 :window 50} opts)
    :consecutive 0 :recent [] :tripped? false}))

(defn breaker-record
  "记一次决定 → `[新状态 本次跳闸?]`。拒：连拒 +1；批准：连拒清零。只跳一次。"
  [st denied?]
  (let [{:keys [max-consecutive max-recent window]} (:opts st)
        recent (let [r (conj (:recent st) (boolean denied?))]
                 (if (> (count r) (max 1 window)) (subvec r (- (count r) (max 1 window))) r))
        consecutive (if denied? (inc (:consecutive st)) 0)
        st' (assoc st :recent recent :consecutive consecutive)
        trip? (and denied? (not (:tripped? st))
                   (or (>= consecutive max-consecutive)
                       (>= (count (filter true? recent)) max-recent)))]
    [(cond-> st' trip? (assoc :tripped? true)) trip?]))

(defn denial-breaker
  "熔断器（atom 包装）：`(record! b denied?)` → 本次跳闸返回 true。"
  ([] (denial-breaker nil))
  ([opts] (atom (breaker-state opts))))

(defn record!
  [b denied?]
  (let [out (volatile! false)]
    (swap! b (fn [st] (let [[st' t] (breaker-record st denied?)] (vreset! out t) st')))
    @out))

(def breaker-stop-note "审批者连续拒绝过多，本回合已停止。请重新说明需求或换一种做法。")

;;; ============================================================
;;; 出口二：同步审批 :tool filter
;;; ============================================================

(defn- filter-request [treq hints-fn]
  (let [f (:function treq)
        nm (str (some-> (:name f) name))]
    {:name nm
     :args (:args treq)
     :sensitive? (boolean (:sensitive f))
     :inline? (nil? (:schema f))
     :hints (or (when hints-fn (hints-fn nm)) {})
     :context (:context treq)
     :call-id (get-in treq [:context :tool/call-id])}))

(defn approval-filter
  "同步审批 `:tool` filter（CLI / 审批服务 / 模型审阅者）。

   opts:
   - :policy    策略（缺省 sensitive-policy）
   - :approver  审批者（`chain-approver` / `timeout-approver` / 模型审阅者…）；
                prompt 时没有审批者 → 一律拒（敏感操作不静默放行）
   - :cache     `session-cache`；审批者回 `:remember :session` 时记账，同键不再问
   - :breaker   熔断参数 map（如 {:max-consecutive 3}）；给了就开。按会话计数、
                每个 turn 清零；跳闸 → 写 `:loop/stop` 停回合
   - :hints-fn  (fn [工具名] -> hints)：filter 里拿不到注册表，内联工具的注解靠它给

   审批者收到的 req 多带 `:reason`（策略理由）`:context` `:call-id`。"
  [{:keys [policy approver cache breaker hints-fn] :or {policy sensitive-policy}}]
  (let [breakers (atom {})
        conv-of (fn [ctx] (or (:conversation-id ctx) ::no-conv))]
    (flt/create-filter :policy-approval
     :turn (fn [req chain]
             ;; 熔断作用域 = 一个回合；resume 是同一回合的延续，不清零
             (when (and breaker (not (:resume? req)))
               (swap! breakers dissoc (conv-of (:context req))))
             (chain req))
     :tool (fn [treq chain]
             (let [req (filter-request treq hints-fn)
                   d (policy req)
                   k (approval-key (:name req) (:args req))
                   session? (not (false? (:allow-session? d)))]
               (case (:decision d)
                 :forbid {:result (str "已拒绝执行：" (or (:reason d) "策略禁止执行该工具"))}
                 :prompt
                 (if (and session? (approved? cache k))
                   (chain treq)
                   (let [r (if approver
                             (try (approver (assoc req :reason (:reason d)))
                                  (catch Throwable t
                                    {:approved? false :reason (str "审批者出错：" (.getMessage t))}))
                             {:approved? false :reason "没有配置审批者"})
                         r (or r {:approved? false :reason "无人表态"})
                         conv (conv-of (:context req))
                         tripped? (when breaker
                                    (let [out (volatile! false)]
                                      (swap! breakers
                                             (fn [m]
                                               (let [[st t] (breaker-record (or (get m conv) (breaker-state breaker))
                                                                            (not (:approved? r)))]
                                                 (vreset! out t)
                                                 (assoc m conv st))))
                                      @out))]
                     (if (:approved? r)
                       (do (when (and session? (= :session (:remember r))) (remember! cache k))
                           (chain treq))
                       (cond-> {:result (str "已拒绝执行："
                                             (or (:reason r) (if (:timed-out? r) "审批超时" "审批未通过")))}
                         tripped? (assoc :writes {:loop/stop {:reason breaker-stop-note
                                                              :by :denial-breaker}})))))
                 (chain treq)))))))

(defn ->approve-fn
  "适配旧的 `flt/approval-filter`（它只对 :sensitive 工具问 `(approve-fn name args) -> bool`）。"
  [{:keys [approver]}]
  (fn [func-name args]
    (boolean (:approved? (when approver (approver {:name (str func-name) :args args :sensitive? true}))))))
