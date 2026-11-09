(ns im.ttalk.agent.model.spec
  "模型元数据（ModelSpec）与注册表（designs/provider-reliability.md §8；
   Keelson `Models/ModelSpec.cs`、`Models/ModelRegistry.cs`）。

   压缩（触发线 = 上下文窗口 × 百分比）、工具结果截断、thinking 预算上限、主备链
   （`chat-model.fallback/from-registry`）都要问「这个模型的窗口 / 上限 / 能力 / 备选是什么」。
   本 ns **只放结构与查询 API**：不内置模型表（会过期，且是假想需求）、不按 spec 造 ChatModel
   （造法由宿主给）。

   - ref = `\"<provider>/<线上的模型 id>\"`，按**第一个**斜杠拆（`\"openrouter/anthropic/claude\"` →
     `[\"openrouter\" \"anthropic/claude\"]`）。
   - 注册表是**实例**（atom），没有全局单例（design-principles §3）。

   ```clojure
   (def reg (spec/registry))
   (spec/register! reg \"anthropic/claude-sonnet-4\"
     {:context-limit 200000 :output-limit 64000 :fallbacks [\"openai/gpt-4o\"]})
   (spec/context-limit reg \"anthropic/claude-sonnet-4\")   ;=> 200000
   (spec/fallback-chain reg \"anthropic/claude-sonnet-4\")  ;=> [\"openai/gpt-4o\"]
   ```"
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def thinking-answer-headroom
  "给思考预算留出的回答余量：思考预算上限 = 输出上限 − 它（Keelson `ThinkingAnswerHeadroom`）。"
  4096)

(def known-keys
  "认得的元数据键（其余键原样收进 :options，选项表是开放的）。"
  #{:label :context-limit :output-limit :thinking-levels :vision? :parallel-tools?
    :tool-output-limit :auto-compact-percent :usable-window-percent :metadata-guessed?
    :fallbacks})

;;; ============================================================
;;; ref
;;; ============================================================

(defn split-ref
  "按第一个斜杠拆成 [provider wire-id]；没有斜杠 / 斜杠在头或尾 → nil。"
  [ref]
  (when (string? ref)
    (let [i (str/index-of ref "/")]
      (when (and i (pos? i) (< i (dec (count ref))))
        [(subs ref 0 i) (subs ref (inc i))]))))

(defn ref-provider "ref 的 provider 段；拆不开 → nil。" [ref] (first (split-ref ref)))

(defn ref-wire-id
  "ref 的 wire-id 段；拆不开 → 原样（没有 provider 前缀的就是线上的名字）。"
  [ref]
  (or (second (split-ref ref)) ref))

(defn- bad! [msg data] (throw (ex-info msg (merge {:type :validation-error :message msg :retryable? false} data))))

;;; ============================================================
;;; ModelSpec
;;; ============================================================

(defn- check-fallbacks [ref fallbacks]
  (loop [[f & more :as fs] (seq fallbacks) seen #{ref} kept []]
    (if-not fs
      kept
      (do (when-not (split-ref f)
            (bad! (str "备用模型 ref 形状不对（应为 provider/model）：" (pr-str f)) {:ref f}))
          ;; 自己顶自己没有意义；同一条 ref 出现两次只算第一次
          (if (contains? seen f)
            (recur more seen kept)
            (recur more (conj seen f) (conj kept f)))))))

(defn model-spec
  "构造一条 ModelSpec（纯 map）。opts 认得的键见 `known-keys`，其余收进 `:options`。

   返回 `{:ref :provider :wire-id :label :context-limit :output-limit :thinking-levels :vision?
          :parallel-tools? :tool-output-limit :auto-compact-percent :usable-window-percent
          :metadata-guessed? :fallbacks [...] :options {...}}`（没给的键不出现，:fallbacks 恒为向量）。

   校验：ref 形状；`:thinking-levels` 须是字符串序列；fallbacks 去自身、去重、形状正确。"
  ([ref] (model-spec ref nil))
  ([ref opts]
   (let [[provider wire-id] (or (split-ref ref)
                                (bad! (str "模型 ref 形状不对（应为 provider/model）：" (pr-str ref)) {:ref ref}))
         levels (:thinking-levels opts)]
     (when (and (some? levels)
                (or (string? levels) (not (sequential? levels)) (not (every? string? levels))))
       (bad! ":thinking-levels 须是字符串序列（档位名即 wire 值）" {:ref ref :thinking-levels levels}))
     (cond-> (merge (select-keys opts (disj known-keys :fallbacks))
                    {:ref ref :provider provider :wire-id wire-id
                     :fallbacks (check-fallbacks ref (:fallbacks opts))
                     :options (apply dissoc opts known-keys)})
       levels (assoc :thinking-levels (vec levels))))))

(defn model-spec? [x] (and (map? x) (string? (:ref x)) (contains? x :wire-id)))

(defn option
  "取一项开放选项（:options 里的）；没有 → default。"
  ([spec k] (option spec k nil))
  ([spec k default] (get (:options spec) k default)))

(defn thinking-budget-cap
  "思考预算上限 = 输出上限 − 回答余量（4096）；没有输出上限或不够余量 → nil。"
  [spec]
  (when-let [o (:output-limit spec)]
    (let [cap (- o thinking-answer-headroom)]
      (when (pos? cap) cap))))

(defn usable-context
  "可用窗口 = 上下文窗口 × :usable-window-percent / 100（没给百分比 → 原窗口）；没有窗口 → nil。"
  [spec]
  (when-let [c (:context-limit spec)]
    (if-let [p (:usable-window-percent spec)]
      (long (quot (* c p) 100))
      c)))

(defn auto-compact-threshold
  "自动压缩触发线（token）= 可用窗口 × :auto-compact-percent / 100；缺任一 → nil。"
  [spec]
  (when-let [u (usable-context spec)]
    (when-let [p (:auto-compact-percent spec)]
      (long (quot (* u p) 100)))))

;;; ============================================================
;;; 注册表（实例）
;;; ============================================================

(declare register!)

(defn registry
  "新建一张空注册表（atom，线程安全）。可选给一组初始条目：ModelSpec 或 [ref opts]。"
  ([] (atom {:order [] :models {}}))
  ([specs]
   (let [r (registry)]
     (doseq [s specs]
       (if (model-spec? s) (register! r s) (apply register! r s)))
     r)))

(defn register!
  "登记一条模型（同 ref 替换，位置不变）。接受 ModelSpec，或 ref + opts。返回登记的 spec。"
  ([reg spec-or-ref] (register! reg spec-or-ref nil))
  ([reg spec-or-ref opts]
   (let [s (if (model-spec? spec-or-ref) spec-or-ref (model-spec spec-or-ref opts))]
     (swap! reg (fn [m] (-> m
                            (update :order #(if (some #{(:ref s)} %) % (conj % (:ref s))))
                            (assoc-in [:models (:ref s)] s))))
     s)))

(defn unregister!
  "注销一条模型；没有 → 什么都不做。"
  [reg ref]
  (swap! reg (fn [m] (-> m
                         (update :order #(vec (remove #{ref} %)))
                         (update :models dissoc ref))))
  nil)

(defn lookup "按 ref 取 spec；没有 → nil。" [reg ref] (get-in @reg [:models ref]))

(defn models "全部 spec，按登记先后。" [reg] (let [m @reg] (mapv #(get-in m [:models %]) (:order m))))

(defn set-fallbacks!
  "给已登记的模型换备用链（主模型先登记、备选后填是常见配法）。没有这条 → 抛 :validation-error。"
  [reg ref fallbacks]
  (let [s (or (lookup reg ref)
              (bad! (str "未登记的模型：" ref) {:ref ref :known (mapv :ref (models reg))}))
        s' (assoc s :fallbacks (check-fallbacks ref fallbacks))]
    (swap! reg assoc-in [:models ref] s')
    s'))

(defn fallback-chain
  "这条模型的备用链：按 :fallbacks 次序展开，各站自己的备选也接在后面（**广度优先**：先走完起点
   自己列的那几家）；去重、去掉起点、防环。没登记的 ref 留在链上——轮到它时才由造 ChatModel 的一方
   报错，登记次序不该影响主模型的可用性。起点没登记 → []。"
  [reg ref]
  (if-let [start (lookup reg ref)]
    (loop [pending (vec (:fallbacks start)) i 0 seen #{ref} chain []]
      (if (>= i (count pending))
        chain
        (let [r (nth pending i)]
          (if (contains? seen r)
            (recur pending (inc i) seen chain)
            (recur (into pending (:fallbacks (lookup reg r))) (inc i) (conj seen r) (conj chain r))))))
    []))

(defn context-limit "上下文窗口（token）；没登记 / 没给 → nil。" [reg ref] (:context-limit (lookup reg ref)))
(defn output-limit "输出上限（token）；没登记 / 没给 → nil。" [reg ref] (:output-limit (lookup reg ref)))
