(ns im.ttalk.agent.provider.common.rate-limit
  "统一限流快照（designs/provider-reliability.md §6；Keelson `Providers/RateLimitInfo.cs`）。

   OpenAI 的 `x-ratelimit-{limit,remaining,reset}-{requests,tokens}` 与 Anthropic 的
   `anthropic-ratelimit-{requests,tokens}-{limit,remaining,reset}` 折成同一形状：

     {:requests-limit :requests-remaining :requests-reset
      :tokens-limit   :tokens-remaining   :tokens-reset}

   头缺哪格就没有哪格；全空 → nil（不挂）。有 `retry-after` 头时另带 `:retry-after`
   （Anthropic 旧 `parse-rate-limit` 形状的超集，向后兼容）。

   头值可以是字符串，也可以是 `java.net.http` 给的 `List<String>`（`http.client` 的
   `http-headers->map` 就是这么存的）——取第一个值。"
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn header-value
  "大小写无关地取一个响应头的值（字符串）。值是 List / 向量时取第一个。"
  [headers k]
  (when headers
    (let [target (str/lower-case (name k))
          v (some (fn [[hk hv]] (when (= target (str/lower-case (name hk))) hv)) headers)]
      (cond
        (nil? v) nil
        (string? v) v
        (instance? java.util.List v) (some-> (first v) str)
        (sequential? v) (some-> (first v) str)
        :else (str v)))))

(defn- ->number
  "数值头 → long（小数四舍五入）；不是数字 → 原字符串（与旧 anthropic 解析一致，不丢信息）。"
  [s]
  (when-let [s (some-> s str/trim not-empty)]
    (or (parse-long s)
        (when-let [d (parse-double s)]
          (when (Double/isFinite d) (Math/round d)))
        s)))

(defn- text [s] (some-> s str/trim not-empty))

(def ^:private fields
  [[:requests-limit     ->number "x-ratelimit-limit-requests"     "anthropic-ratelimit-requests-limit"]
   [:requests-remaining ->number "x-ratelimit-remaining-requests" "anthropic-ratelimit-requests-remaining"]
   [:requests-reset     text     "x-ratelimit-reset-requests"     "anthropic-ratelimit-requests-reset"]
   [:tokens-limit       ->number "x-ratelimit-limit-tokens"       "anthropic-ratelimit-tokens-limit"]
   [:tokens-remaining   ->number "x-ratelimit-remaining-tokens"   "anthropic-ratelimit-tokens-remaining"]
   [:tokens-reset       text     "x-ratelimit-reset-tokens"       "anthropic-ratelimit-tokens-reset"]
   [:retry-after        ->number "retry-after"                    nil]])

(defn parse-rate-limit
  "响应头 → 限流快照 map；全空 → nil。"
  [headers]
  (when headers
    (let [m (reduce (fn [acc [k f h1 h2]]
                      (if-let [v (f (or (header-value headers h1)
                                        (when h2 (header-value headers h2))))]
                        (assoc acc k v)
                        acc))
                    {} fields)]
      (when (seq (dissoc m :retry-after)) m))))
