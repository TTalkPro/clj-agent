(ns im.ttalk.agent.provider.common.memo
  "轻量有界缓存工具。

   与 clojure.core/memoize 的区别：容量有界（超限整体清空重建），
   不会随参数多样性无限增长——适合做库代码里的转换缓存。")

(defn bounded
  "把单参纯函数 f 包装为带有界缓存的版本。

   按参数 `=` 缓存结果；缓存条目数达到 cap 时整体清空（简单粗暴但无泄漏）。
   适合「参数在稳定生命周期内反复出现」的转换——典型如 kernel 常驻的
   tools 列表在 ReAct 循环每轮 LLM 调用时重复做 wire 转换。

   注意：f 须为纯函数且返回非 nil（nil 会被视作未命中重算，仍正确但无缓存收益）。
   并发下可能偶发重复计算（swap! 竞态），结果一致、无害。"
  [f cap]
  (let [cache (atom {})]
    (fn [x]
      (if-some [v (get @cache x)]
        v
        (let [v (f x)]
          (swap! cache (fn [c] (assoc (if (>= (count c) cap) {} c) x v)))
          v)))))
