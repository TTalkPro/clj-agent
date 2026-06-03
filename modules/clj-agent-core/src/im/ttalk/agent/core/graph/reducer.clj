(ns im.ttalk.agent.core.graph.reducer
  "Graph Reducer - 字段级增量合并

   Field Reducer 用于定义如何合并并行执行产生的增量（delta）。
   每个字段可以有独立的合并策略。

   内置 Reducer:
   - last-write-wins  后值覆盖（默认）
   - append           列表追加
   - prepend          列表前置
   - deep-merge       Map 深度合并
   - increment        数值增量
   - max-val          取最大值
   - min-val          取最小值
   - union            集合并集
   - intersection     集合交集

   使用示例:

   (def field-reducers
     {:messages append
      :counter increment
      :config deep-merge})

   (apply-delta state {:messages [\"new\"] :counter 1}
                field-reducers)
   ;; messages 追加，counter 累加，其他字段后值覆盖"
  (:require [clojure.set]))

;;; ============================================================
;;; 内置 Reducer
;;; ============================================================

(defn last-write-wins
  "后值覆盖（默认策略）

   新值直接替换旧值。"
  [_old new]
  new)

(defn append
  "列表追加

   将新值追加到旧列表末尾。
   - 旧值为 nil 时，创建新列表
   - 新值为序列时，追加所有元素
   - 新值为单个值时，作为单元素追加"
  [old new]
  (let [old-vec (if (nil? old) [] (vec old))]
    (if (sequential? new)
      (into old-vec new)
      (conj old-vec new))))

(defn prepend
  "列表前置

   将新值插入到旧列表开头。"
  [old new]
  (let [old-vec (if (nil? old) [] (vec old))]
    (if (sequential? new)
      (into (vec new) old-vec)
      (into [new] old-vec))))

(defn deep-merge
  "Map 深度合并

   递归合并嵌套 map。
   - 两个 map: 递归合并
   - 其他情况: 新值覆盖"
  [old new]
  (if (and (map? old) (map? new))
    (merge-with deep-merge old new)
    new))

(defn increment
  "数值增量

   将新值加到旧值上。
   - 旧值为 nil 时，视为 0"
  [old new]
  (+ (or old 0) new))

(defn decrement
  "数值减量

   从旧值减去新值。"
  [old new]
  (- (or old 0) new))

(defn max-val
  "取最大值"
  [old new]
  (if (nil? old)
    new
    (max old new)))

(defn min-val
  "取最小值"
  [old new]
  (if (nil? old)
    new
    (min old new)))

(defn union
  "集合并集"
  [old new]
  (clojure.set/union (set old) (set new)))

(defn intersection
  "集合交集"
  [old new]
  (if (nil? old)
    (set new)
    (clojure.set/intersection (set old) (set new))))

;;; ============================================================
;;; 自定义 Reducer 构造器
;;; ============================================================

(defn with-transform
  "创建带转换的 reducer

   先对新值应用转换函数，再用 reducer 合并。

   参数:
   - reducer: 基础 reducer 函数
   - transform: 转换函数 (fn [new-val] -> transformed)

   返回: 新的 reducer 函数"
  [reducer transform]
  (fn [old new]
    (reducer old (transform new))))

(defn with-filter
  "创建带过滤的 reducer

   只有满足条件的新值才会被合并。

   参数:
   - reducer: 基础 reducer 函数
   - pred: 谓词函数 (fn [new-val] -> boolean)

   返回: 新的 reducer 函数"
  [reducer pred]
  (fn [old new]
    (if (pred new)
      (reducer old new)
      old)))

(defn conditional-reducer
  "创建条件 reducer

   根据条件选择不同的 reducer。

   参数:
   - pred: 条件函数 (fn [old new] -> boolean)
   - then-reducer: 条件为真时使用的 reducer
   - else-reducer: 条件为假时使用的 reducer

   返回: 新的 reducer 函数"
  [pred then-reducer else-reducer]
  (fn [old new]
    (if (pred old new)
      (then-reducer old new)
      (else-reducer old new))))

;;; ============================================================
;;; Delta 应用
;;; ============================================================

(defn apply-delta
  "应用 delta 到 state，使用 field-reducers

   参数:
   - state: 当前状态
   - delta: 增量 map
   - field-reducers: 字段级 reducer 配置 {field -> reducer-fn}
                     未配置的字段使用 last-write-wins

   返回: 新的 state"
  [state delta field-reducers]
  (reduce-kv
    (fn [s k v]
      (let [reducer (get field-reducers k last-write-wins)
            old-val (get s k)]
        (assoc s k (reducer old-val v))))
    state
    delta))

(defn apply-deltas
  "批量应用多个 delta

   参数:
   - state: 当前状态
   - deltas: delta 列表
   - field-reducers: 字段级 reducer 配置

   返回: 新的 state"
  [state deltas field-reducers]
  (reduce
    (fn [s delta]
      (apply-delta s delta field-reducers))
    state
    deltas))

;;; ============================================================
;;; Delta 计算
;;; ============================================================

(defn compute-delta
  "计算两个状态之间的增量

   参数:
   - old-state: 旧状态
   - new-state: 新状态
   - opts: 可选参数
     :include-removals - 是否包含删除的键（默认 false）

   返回: delta map（只包含变化的键值）"
  [old-state new-state & {:keys [include-removals] :or {include-removals false}}]
  (let [;; 找出新增或修改的键
        changes (reduce-kv
                  (fn [m k v]
                    (if (= v (get old-state k ::not-found))
                      m
                      (assoc m k v)))
                  {}
                  new-state)]
    (if include-removals
      ;; 找出删除的键，标记为 ::deleted
      (let [old-keys (set (keys old-state))
            new-keys (set (keys new-state))
            removed (clojure.set/difference old-keys new-keys)]
        (reduce #(assoc %1 %2 ::deleted) changes removed))
      changes)))

(defn merge-deltas
  "合并多个 delta 为一个

   使用指定的 field-reducers 合并冲突的键。

   参数:
   - deltas: delta 列表
   - field-reducers: 字段级 reducer 配置

   返回: 合并后的单个 delta"
  [deltas field-reducers]
  (reduce
    (fn [merged delta]
      (reduce-kv
        (fn [m k v]
          (if (contains? m k)
            (let [reducer (get field-reducers k last-write-wins)]
              (assoc m k (reducer (get m k) v)))
            (assoc m k v)))
        merged
        delta))
    {}
    deltas))

;;; ============================================================
;;; 预置 Reducer 配置
;;; ============================================================

(def default-reducers
  "默认 reducer 配置（所有字段使用 last-write-wins）"
  {})

(def list-field-reducers
  "列表字段的常用 reducer 配置"
  {:messages append
   :errors append
   :logs append
   :history append
   :results append})

(def counter-field-reducers
  "计数器字段的常用 reducer 配置"
  {:count increment
   :counter increment
   :total increment
   :sum increment})
