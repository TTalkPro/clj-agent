(ns im.ttalk.agent.llm.prompt.selector
  "动态 Example 选择器实现

   提供多种示例选择策略：
   - LengthBasedSelector: 基于长度选择
   - SimilaritySelector: 基于相似度选择
   - MaxMarginalRelevanceSelector: 最大边际相关性选择

   使用示例：

   ;; 基于长度选择
   (def selector (create-length-selector {:max-length 1000}))

   ;; 添加示例
   (def selector (add-example selector {:input \"Hello\" :output \"你好\"}))

   ;; 选择示例
   (select-examples selector {:input \"World\"})"
  (:require [im.ttalk.agent.llm.prompt.protocol :as proto]
            [clojure.string :as str]))

;; ============================================================
;; 辅助函数
;; ============================================================

(defn- example-length
  "计算示例的长度

   参数:
   - example: 示例 map

   返回: 字符数"
  [example]
  (reduce-kv (fn [acc _ v]
               (+ acc (count (str v))))
             0
             example))

(defn- simple-similarity
  "简单的字符串相似度（基于共同字符）

   参数:
   - s1: 字符串 1
   - s2: 字符串 2

   返回: 相似度分数 (0.0 - 1.0)"
  [s1 s2]
  (if (or (empty? s1) (empty? s2))
    0.0
    (let [set1 (set (str/lower-case s1))
          set2 (set (str/lower-case s2))
          intersection (clojure.set/intersection set1 set2)
          union (clojure.set/union set1 set2)]
      (/ (count intersection) (count union)))))

(defn- calculate-similarity
  "计算输入与示例的相似度

   参数:
   - input-vars: 输入变量 map
   - example: 示例 map
   - input-key: 用于比较的输入键

   返回: 相似度分数"
  [input-vars example input-key]
  (let [input-val (str (get input-vars input-key ""))
        example-val (str (get example input-key ""))]
    (simple-similarity input-val example-val)))

;; ============================================================
;; LengthBasedSelector 实现
;; ============================================================

(defrecord LengthBasedSelector [examples max-length example-length-fn]
  proto/IExampleSelector

  (select-examples [_ _]
    ;; 按长度选择示例，不超过 max-length
    (loop [selected []
           remaining examples
           total-length 0]
      (if (empty? remaining)
        selected
        (let [example (first remaining)
              len (example-length-fn example)]
          (if (> (+ total-length len) max-length)
            selected
            (recur (conj selected example)
                   (rest remaining)
                   (+ total-length len)))))))

  (add-example [this example]
    (->LengthBasedSelector
      (conj examples example)
      max-length
      example-length-fn)))

(defn create-length-selector
  "创建基于长度的示例选择器

   按顺序选择示例，直到达到最大长度限制。

   参数:
   - opts: 配置选项
     - :examples         初始示例列表
     - :max-length       最大总长度（字符数）
     - :example-length-fn 自定义长度计算函数

   返回: LengthBasedSelector 实例

   示例:
   (def selector
     (create-length-selector
       {:max-length 500
        :examples [{:input \"A\" :output \"B\"}]}))"
  [{:keys [examples max-length example-length-fn]
    :or {examples [] max-length 1000 example-length-fn example-length}}]
  (->LengthBasedSelector examples max-length example-length-fn))

;; ============================================================
;; SimilaritySelector 实现
;; ============================================================

(defrecord SimilaritySelector [examples k input-key similarity-fn]
  proto/IExampleSelector

  (select-examples [_ input-variables]
    ;; 按相似度排序，选择 top-k
    (->> examples
         (map (fn [ex]
                {:example ex
                 :score (similarity-fn input-variables ex input-key)}))
         (sort-by :score >)
         (take k)
         (map :example)
         vec))

  (add-example [this example]
    (->SimilaritySelector
      (conj examples example)
      k
      input-key
      similarity-fn)))

(defn create-similarity-selector
  "创建基于相似度的示例选择器

   根据输入与示例的相似度选择最相关的示例。

   参数:
   - opts: 配置选项
     - :examples      初始示例列表
     - :k             选择数量（默认 4）
     - :input-key     用于计算相似度的键（默认 :input）
     - :similarity-fn 自定义相似度函数

   返回: SimilaritySelector 实例

   示例:
   (def selector
     (create-similarity-selector
       {:k 3
        :input-key :question
        :examples [{:question \"什么是AI？\" :answer \"...\"}]}))"
  [{:keys [examples k input-key similarity-fn]
    :or {examples [] k 4 input-key :input similarity-fn calculate-similarity}}]
  (->SimilaritySelector examples k input-key similarity-fn))

;; ============================================================
;; MaxMarginalRelevanceSelector 实现
;; ============================================================

(defn- mmr-score
  "计算 MMR 分数

   MMR = λ * similarity(query, doc) - (1 - λ) * max(similarity(doc, selected))

   参数:
   - lambda: 权衡参数 (0-1)
   - query-sim: 与查询的相似度
   - selected-sims: 与已选示例的相似度列表

   返回: MMR 分数"
  [lambda query-sim selected-sims]
  (let [max-selected-sim (if (empty? selected-sims) 0 (apply max selected-sims))]
    (- (* lambda query-sim)
       (* (- 1 lambda) max-selected-sim))))

(defrecord MMRSelector [examples k input-key lambda similarity-fn]
  proto/IExampleSelector

  (select-examples [_ input-variables]
    ;; 使用 MMR 选择多样性示例
    (if (empty? examples)
      []
      (let [;; 计算所有示例与输入的相似度
            scored-examples
            (mapv (fn [ex]
                    {:example ex
                     :query-sim (similarity-fn input-variables ex input-key)})
                  examples)]
        (loop [selected []
               candidates scored-examples
               n 0]
          (if (or (>= n k) (empty? candidates))
            (mapv :example selected)
            (let [;; 计算每个候选的 MMR 分数
                  with-mmr
                  (mapv (fn [c]
                          (let [selected-sims
                                (mapv (fn [s]
                                        (simple-similarity
                                          (str (get (:example c) input-key))
                                          (str (get (:example s) input-key))))
                                      selected)]
                            (assoc c :mmr-score
                                   (mmr-score lambda (:query-sim c) selected-sims))))
                        candidates)
                  ;; 选择 MMR 分数最高的
                  best (apply max-key :mmr-score with-mmr)]
              (recur (conj selected best)
                     (remove #(= (:example %) (:example best)) candidates)
                     (inc n))))))))

  (add-example [this example]
    (->MMRSelector
      (conj examples example)
      k
      input-key
      lambda
      similarity-fn)))

(defn create-mmr-selector
  "创建最大边际相关性（MMR）选择器

   在保持相关性的同时增加示例多样性。

   参数:
   - opts: 配置选项
     - :examples      初始示例列表
     - :k             选择数量（默认 4）
     - :input-key     用于计算相似度的键（默认 :input）
     - :lambda        多样性参数 (0-1，默认 0.5)
     - :similarity-fn 自定义相似度函数

   返回: MMRSelector 实例

   示例:
   (def selector
     (create-mmr-selector
       {:k 3
        :lambda 0.7
        :examples [...]}))"
  [{:keys [examples k input-key lambda similarity-fn]
    :or {examples [] k 4 input-key :input lambda 0.5 similarity-fn calculate-similarity}}]
  (->MMRSelector examples k input-key lambda similarity-fn))

;; ============================================================
;; SemanticSimilaritySelector（向量检索）
;; ============================================================

(defrecord SemanticSelector [examples k input-key embedding-fn]
  proto/IExampleSelector

  (select-examples [_ input-variables]
    ;; 使用嵌入函数计算语义相似度
    (if embedding-fn
      (let [input-text (str (get input-variables input-key ""))
            input-embedding (embedding-fn input-text)
            scored (mapv (fn [ex]
                           (let [ex-text (str (get ex input-key ""))
                                 ex-embedding (embedding-fn ex-text)
                                 ;; 余弦相似度
                                 score (if (and input-embedding ex-embedding)
                                         (/ (reduce + (map * input-embedding ex-embedding))
                                            (* (Math/sqrt (reduce + (map #(* % %) input-embedding)))
                                               (Math/sqrt (reduce + (map #(* % %) ex-embedding)))))
                                         0)]
                             {:example ex :score score}))
                         examples)]
        (->> scored
             (sort-by :score >)
             (take k)
             (mapv :example)))
      ;; 如果没有嵌入函数，回退到简单相似度
      (proto/select-examples
        (->SimilaritySelector examples k input-key calculate-similarity)
        input-variables)))

  (add-example [this example]
    (->SemanticSelector
      (conj examples example)
      k
      input-key
      embedding-fn)))

(defn create-semantic-selector
  "创建语义相似度选择器

   使用嵌入向量计算语义相似度。

   参数:
   - opts: 配置选项
     - :examples      初始示例列表
     - :k             选择数量（默认 4）
     - :input-key     用于计算相似度的键（默认 :input）
     - :embedding-fn  嵌入函数 (fn [text] -> vector)

   返回: SemanticSelector 实例"
  [{:keys [examples k input-key embedding-fn]
    :or {examples [] k 4 input-key :input}}]
  (->SemanticSelector examples k input-key embedding-fn))

;; ============================================================
;; 组合选择器
;; ============================================================

(defrecord CompositeSelector [selectors merge-fn]
  proto/IExampleSelector

  (select-examples [_ input-variables]
    (let [all-examples (mapcat #(proto/select-examples % input-variables) selectors)]
      (merge-fn all-examples)))

  (add-example [this example]
    (->CompositeSelector
      (mapv #(proto/add-example % example) selectors)
      merge-fn)))

(defn create-composite-selector
  "创建组合选择器

   组合多个选择器的结果。

   参数:
   - selectors: 选择器列表
   - merge-fn: 合并函数 (fn [examples] -> examples)

   返回: CompositeSelector 实例"
  [selectors & {:keys [merge-fn] :or {merge-fn #(vec (distinct %))}}]
  (->CompositeSelector selectors merge-fn))
