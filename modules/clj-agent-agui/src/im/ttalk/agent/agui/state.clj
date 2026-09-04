(ns im.ttalk.agent.agui.state
  "共享状态的 JSON Pointer / JSON Patch（RFC 6901 / 6902）与 **delta 规范化**。

   **纯函数，不认识发射器也不认识 AG-UI 事件**——拿状态与 op 数组进来，算出新
   状态与（可能被补过的）op 数组出去。事件那一侧在 `agui.event`。

   为什么服务端非得自己算一遍：AG-UI 的共享状态是「一条快照 + 一串增量」，增量
   **打在客户端那份状态上**。服务端每发一条 delta 都得同步在自己这边应用，否则
   下一条 delta 就是对着一个想象中的状态算的。上游把这件事放在服务端解
   （`CopilotKit/packages/runtime/src/agent/state-delta.ts`），我们照同一个口径。

   `normalize-ops` 挡的是一个**一个字都不报**的静默失败（下游在另一台运行时上
   实测到，见 feedbacks/2026-09-04-agui-no-shared-state-tools-or-delta.md）：
   客户端 `state` 还是 `{}` 时收到 `add /todos/-`——RFC 6902 要求 `/todos` 已存在，
   必然失败，而客户端**只保留旧状态 + `console.warn`**，一个错都不报给用户。

   「没发过快照就先发 delta」那条由 `agui.event` 在发射侧补（它才知道发过没有）。

   **模型写错了当作「这条 op 不适用」跳过，不抛**——那是模型生成的字符串，
   炸掉整条 run 比丢一条 op 糟得多。"
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def ^:private none
  "「不适用」哨兵。**不能用 nil**：nil 是合法的状态值（`replace /a nil`），
   拿它兼作失败信号会把「写成 nil」和「没写成」搅在一起。"
  ::none)

;;; ============================================================
;;; RFC 6901 JSON Pointer
;;; ============================================================

(defn parse-pointer
  "`\"/a/b\"` → `[\"a\" \"b\"]`；`\"\"` → `[]`；**非法返回 nil**（不抛）。

   非法有两种：不以 `/` 开头的非空串；`~` 后面不是 `0`/`1`（转义只定义了
   `~0` = `~`、`~1` = `/`）。"
  [p]
  (cond
    (not (string? p)) nil
    (= "" p) []
    (not (str/starts-with? p "/")) nil
    :else (let [segs (str/split (subs p 1) #"/" -1)]
            (when-not (some #(re-find #"~(?![01])" %) segs)
              (mapv #(-> ^String % (str/replace "~1" "/") (str/replace "~0" "~")) segs)))))

(defn- seg-key
  "路径段 → map 的键。**关键字优先**：中立层的 map 键是关键字（cheshire 生成
   JSON 时照样对），但外面塞进来的字符串键也认；都没有就按关键字建新键。"
  [node seg]
  (let [k (keyword seg)]
    (cond
      (contains? node k)   k
      (contains? node seg) seg
      :else                k)))

(defn- array-index
  "数组下标：只认 `0` 与不带前导零的正整数。`end-ok?` 允许等于长度
   （`add` 可以插在末位之后）。越界 / 不合法返回 nil。"
  [seg n end-ok?]
  (when (and (string? seg) (re-matches #"0|[1-9]\d*" seg))
    (let [i (parse-long seg)]
      (when (and i (if end-ok? (<= i n) (< i n))) i))))

(defn lookup
  "顺路径取值，返回 `{:exists? bool :value v}`。

   **存在与「值是 nil」必须分开**——补数组那条守卫全靠这个区分。"
  [root segs]
  (loop [node root segs (seq segs)]
    (if-not segs
      {:exists? true :value node}
      (let [seg (first segs)]
        (cond
          (map? node)    (let [k (seg-key node seg)]
                           (if (contains? node k)
                             (recur (get node k) (next segs))
                             {:exists? false}))
          (vector? node) (if-let [i (array-index seg (count node) false)]
                           (recur (nth node i) (next segs))
                           {:exists? false})
          :else          {:exists? false})))))

;;; ============================================================
;;; RFC 6902 JSON Patch
;;; ============================================================

(defn- update-parent
  "走到 `segs` 的**父节点**，用 `(f parent last-seg)` 换掉它。

   `f` 返回哨兵即整条 op 不适用（路上任何一段不存在也一样）。返回
   `{:root r :applied? bool}`——`applied?` 为假时 `root` 原样，调用方据此决定
   跳过。"
  [root segs f]
  (letfn [(walk [node segs]
            (if (= 1 (count segs))
              (f node (first segs))
              (let [seg (first segs)]
                (cond
                  (map? node)
                  (let [k (seg-key node seg)]
                    (if (contains? node k)
                      (let [child (walk (get node k) (subvec segs 1))]
                        (if (= none child) none (assoc node k child)))
                      none))

                  (vector? node)
                  (if-let [i (array-index seg (count node) false)]
                    (let [child (walk (nth node i) (subvec segs 1))]
                      (if (= none child) none (assoc node i child)))
                    none)

                  :else none))))]
    (let [r (walk root (vec segs))]
      (if (= none r) {:root root :applied? false} {:root r :applied? true}))))

(defn- insert-at [v i x] (vec (concat (subvec v 0 i) [x] (subvec v i))))
(defn- remove-at [v i] (vec (concat (subvec v 0 i) (subvec v (inc i)))))

(defn- add-at [root segs value]
  (if (empty? segs)
    {:root value :applied? true}                     ;; 路径为空 = 整体替换
    (update-parent root segs
                   (fn [parent seg]
                     (cond
                       (map? parent)    (assoc parent (seg-key parent seg) value)
                       (vector? parent) (if (= "-" seg)
                                          (conj parent value)
                                          (if-let [i (array-index seg (count parent) true)]
                                            (insert-at parent i value)
                                            none))
                       :else            none)))))

(defn- remove-at* [root segs]
  (if (empty? segs)
    {:root root :applied? false}                     ;; 根删不掉
    (update-parent root segs
                   (fn [parent seg]
                     (cond
                       (map? parent)    (let [k (seg-key parent seg)]
                                          (if (contains? parent k) (dissoc parent k) none))
                       (vector? parent) (if-let [i (array-index seg (count parent) false)]
                                          (remove-at parent i)
                                          none)
                       :else            none)))))

(defn- replace-at [root segs value]
  (if (:exists? (lookup root segs))
    (add-at root segs value)
    {:root root :applied? false}))

(defn- op-key
  "op 的字段可能是关键字键也可能是字符串键——模型经 JSON 过来，路上谁 keywordize
   由 provider 决定，两种都认。"
  [op k]
  (if (map? op) (or (get op k) (get op (name k))) nil))

(defn apply-op
  "应用一条 op，返回 `{:root r :applied? bool}`。

   覆盖 `add` / `remove` / `replace` / `test` / `copy` / `move`（与上游
   `applyStateDeltaOperation` 同集）；认不出的 op、非法路径一律 `:applied? false`
   且状态原样。"
  [root op]
  (let [path (op-key op :path)
        segs (parse-pointer path)]
    (if (nil? segs)
      {:root root :applied? false}
      (case (some-> (op-key op :op) str)
        "add"     (add-at root segs (op-key op :value))
        "remove"  (remove-at* root segs)
        "replace" (replace-at root segs (op-key op :value))
        "test"    (let [{:keys [exists? value]} (lookup root segs)]
                    {:root root :applied? (and exists? (= value (op-key op :value)))})
        "copy"    (let [from (parse-pointer (op-key op :from))
                        src  (when from (lookup root from))]
                    (if (:exists? src)
                      (add-at root segs (:value src))
                      {:root root :applied? false}))
        "move"    (let [from-p (op-key op :from)
                        from   (parse-pointer from-p)
                        src    (when from (lookup root from))]
                    (cond
                      (not (:exists? src)) {:root root :applied? false}
                      (= from-p path)      {:root root :applied? true}
                      :else (let [removed (remove-at* root from)]
                              (if (:applied? removed)
                                (let [added (add-at (:root removed) segs (:value src))]
                                  (if (:applied? added) added {:root root :applied? false}))
                                {:root root :applied? false}))))
        {:root root :applied? false}))))

(defn apply-ops
  "顺序应用一串 op。**跳过不适用的**，不抛（同 `apply-op` 的理由）。"
  [root ops]
  (reduce (fn [st op] (:root (apply-op st op))) root ops))

;;; ============================================================
;;; delta 规范化
;;; ============================================================

(defn normalize-ops
  "规范化一串 op，返回 `{:ops [...] :state 新状态}`。

   唯一的补丁动作（照抄上游 `normalizeStateDelta`）：`add /x/-` 而 `/x` **不存在**、
   但 `/x` 的父存在时，**在它前面插一条 `{op:add, path:/x, value:[]}`**。

   为什么必须在服务端补：RFC 6902 的 `add /todos/-` 要求 `/todos` 已经是个数组；
   客户端 state 还是 `{}` 时这条必然失败，而 `@ag-ui/client` **只保留旧状态 +
   console.warn**——用户看到的是「什么都没发生」，没有任何报错指向真正的原因。

   父不存在时**不补**（那要连着建好几层，猜得太多）——照发，让它按 RFC 失败，
   与上游行为一致。"
  [ops state]
  (reduce
   (fn [{:keys [ops state]} op]
     (let [path (op-key op :path)
           add-tail? (and (= "add" (some-> (op-key op :op) str))
                          (string? path)
                          (str/ends-with? path "/-"))
           arr-path (when add-tail? (subs path 0 (- (count path) 2)))
           arr-segs (when arr-path (parse-pointer arr-path))
           need-init? (and arr-segs
                           (not (:exists? (lookup state arr-segs)))
                           (:exists? (lookup state (vec (butlast arr-segs)))))
           init-op (when need-init? {:op "add" :path arr-path :value []})
           after-init (if init-op (apply-op state init-op) {:root state :applied? false})
           ops (cond-> ops (:applied? after-init) (conj init-op))
           state (:root after-init)]
       {:ops (conj ops op)
        :state (:root (apply-op state op))}))
   {:ops [] :state state}
   ops))
