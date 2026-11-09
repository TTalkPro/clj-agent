(ns im.ttalk.agent.filter.tool-output
  "工具结果截断 + 全文另存（designs/runtime-borrow.md §2，借 Keelson
   `Tools/ToolOutputTruncation.cs` / `ToolOutputSpill.cs`，二者又借 Codex）。

   一次 `cat` 大文件、一次大 JSON 查询，几十 KB 进历史，之后**每一轮**都要为它付费，
   而且很快撞窗。这里做两件事：

   - 纯函数 `truncate-middle` / `truncate-middle-tokens`：**头尾保留、中段截断**
     （报错信息常在尾部，只留头会把它截掉），中间放「…N chars truncated…」标记，
     **结果连同标记不超预算**，所以再截一次不变（幂等——压缩 filter 每轮都会再过一遍）；
   - `tool-output-filter`：`:tool` filter，超预算的结果截断并加警告头；可注入
     `:spill` 把全文另存，并在结果末尾告诉模型存在哪（模型要细节可以再去读）。

   缺省不挂 = 行为不变。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.tokens :as tokens]))

(set! *warn-on-reflection* true)

(defn- marker [removed] (str "…" removed " chars truncated…"))

(defn- floor-boundary
  "i 落在代理对中间（i-1 是高代理）就往前退一格。"
  ^long [^String s ^long i]
  (if (and (pos? i) (< i (.length s)) (Character/isHighSurrogate (.charAt s (dec i))))
    (dec i) i))

(defn- ceil-boundary
  "i 落在代理对中间（i 处是低代理）就往后进一格。"
  ^long [^String s ^long i]
  (if (and (< i (.length s)) (pos? i) (Character/isLowSurrogate (.charAt s i)))
    (inc i) i))

(defn truncate-middle
  "截到至多 `max-chars` 个字符（**含**中段标记），头尾各留约一半，不劈代理对。
   没超 → 原样；预算连标记都放不下 → 只留前缀。结果已在预算内，故幂等。"
  [s max-chars]
  (let [^String s (str s)
        n (.length s)
        max-chars (long (max 0 max-chars))]
    (if (<= n max-chars)
      s
      ;; 标记长度随「去掉几个字符」变，去掉得越多标记越长 → keep 单调减，必收敛
      (let [keep (loop [keep (- max-chars (count (marker (- n max-chars))))]
                   (let [k2 (- max-chars (count (marker (- n keep))))]
                     (if (>= k2 keep) keep (recur k2))))]
        (if (<= keep 0)
          (subs s 0 (floor-boundary s max-chars))
          (let [head (floor-boundary s (long (Math/ceil (/ keep 2.0))))
                tail-start (ceil-boundary s (- n (quot keep 2)))]
            (let [out (str (subs s 0 head) (marker (- tail-start head)) (subs s tail-start))]
              ;; 代理对退让可能让标记多一位数——超了就把预算收一格重来（保证 ≤ 预算 → 幂等）
              (if (> (count out) max-chars)
                (truncate-middle s (dec max-chars))
                out))))))))

(defn truncate-middle-tokens
  "按近似 token 预算（`tokens/estimate-text`）做 `truncate-middle`：二分出最大的字符
   预算使结果 ≤ max-tokens。没超 → 原样。"
  [s max-tokens]
  (let [s (str s)]
    (if (<= (tokens/estimate-text s) max-tokens)
      s
      (loop [lo 0 hi (count s)]
        (if (>= lo hi)
          (truncate-middle s lo)
          (let [mid (quot (+ lo hi 1) 2)]
            (if (<= (tokens/estimate-text (truncate-middle s mid)) max-tokens)
              (recur mid hi)
              (recur lo (dec mid)))))))))

(defn- line-count [^String s]
  (if (str/blank? s) 0 (count (str/split-lines s))))

(defn over-budget?
  "s 是否超过预算（`{:max-chars n}` 或 `{:max-tokens n}`，前者优先）。"
  [s {:keys [max-chars max-tokens]}]
  (cond
    max-chars  (> (count s) max-chars)
    max-tokens (> (tokens/estimate-text s) max-tokens)
    :else false))

(defn format-truncated
  "超预算才截，并在前面加警告头（原 token 数 / 总行数）；没超 → 原样。"
  [s {:keys [max-chars max-tokens] :as budget}]
  (let [s (str s)]
    (if-not (over-budget? s budget)
      s
      (str "[警告：输出过长已截断（原约 " (tokens/estimate-text s) " token，共 "
           (line-count s) " 行）]\n"
           (if max-chars (truncate-middle s max-chars) (truncate-middle-tokens s max-tokens))))))

(defn tool-output-filter
  "工具结果截断 `:tool` filter。

   opts:
   - :max-tokens  近似 token 预算（缺省 10000，Codex / Keelson 同款）
   - :max-chars   字符预算（给了就按它，优先于 :max-tokens）
   - :per-tool    {工具名 {:max-chars n | :max-tokens n}} 单工具覆盖
   - :exclude     不截的工具名集合
   - :spill       (fn [{:keys [call-id tool-name content]}] -> 位置字符串 | nil)
                  截断时先把全文交给它；回了位置就附在结果末尾告诉模型。
                  抛异常 / 回 nil → 不提位置（截断照做）。存哪是宿主的事。

   只动 `:result` 字符串；`:writes` / `:error` 原样。"
  [& {:keys [max-tokens max-chars per-tool exclude spill]
      :or {max-tokens 10000}}]
  (let [exclude (set (map name exclude))
        per-tool (into {} (map (fn [[k v]] [(name k) v])) per-tool)
        default-budget (if max-chars {:max-chars max-chars} {:max-tokens max-tokens})]
    (flt/create-filter :tool-output
     :tool (fn [req chain]
             (let [tool-name (str (some-> (get-in req [:function :name]) name))]
               (if (contains? exclude tool-name)
                 (chain req)
                 (flt/fmap
                  (chain req)
                  (fn [resp]
                    (let [r (:result resp)
                          budget (get per-tool tool-name default-budget)]
                      (if (and (string? r) (over-budget? r budget))
                        (let [where (when spill
                                      (try (spill {:call-id (get-in req [:context :tool/call-id])
                                                   :tool-name tool-name
                                                   :content r})
                                           (catch Throwable _ nil)))]
                          (assoc resp :result
                                 (cond-> (format-truncated r budget)
                                   (and (string? where) (not (str/blank? where)))
                                   (str "\n[完整输出已保存到：" where "]"))))
                        resp))))))))))
