(ns im.ttalk.agent.tools.strategies.parallel
  "并行策略 - 并行执行多个工具

   核心理念：
   - 并行执行
   - 结果聚合
   - 超时控制

   ========================================
   基本用法
   ========================================

   (require '[im.ttalk.agent.tools.strategies.parallel :as parallel])

   ;; 并行执行
   (def results
     (parallel/execute-all
       [(fn [] (execute-tool :calc1 {...}))
        (fn [] (execute-tool :calc2 {...}))]))

   ========================================
   聚合策略
   ========================================

   - :all - 等待所有完成
   - :any - 任一成功即可
   - :first - 第一个成功的结果"
  (:require [clojure.core.async :as async]
            [im.ttalk.agent.core.common.result :as result]))

;; =============================================================================
;; 并行执行配置
;; =============================================================================

(defn default-config
  "创建默认并行配置

   参数:
   - opts: 选项 map

   返回: 完整配置

   示例:
   (default-config {:timeout-ms 5000})"
  ([] (default-config {}))
  ([opts]
   (merge {:timeout-ms 30000
           :aggregation :all
           :max-concurrent nil  ; nil 表示无限制
           :on-complete (fn [results] nil)}
          opts)))

;; =============================================================================
;; 核心并行执行
;; =============================================================================

(defn execute-all
  "并行执行多个函数

   参数:
   - execute-fns: 执行函数列表
   - opts: 选项 map

   返回: 结果列表

   示例:
   (execute-all
     [(fn [] (execute-tool :calc1 {...}))
      (fn [] (execute-tool :calc2 {...}))]
     {:timeout-ms 5000})"
  [execute-fns & [opts]]
  (let [config (default-config opts)
        max-concurrent (:max-concurrent config)
        timeout-ms (:timeout-ms config)

        ;; 限制并发数（如果需要）
        fns-to-execute (if (and max-concurrent (> (count execute-fns) max-concurrent))
                          (take max-concurrent execute-fns)
                          execute-fns)

        ;; 创建执行 channels
        result-chs (mapv (fn [execute-fn]
                            (async/thread
                              (try
                                {:success true :value (execute-fn)}
                                (catch Exception e
                                  {:success false
                                   :error {:type "exception"
                                           :message (.getMessage e)}}))))
                          fns-to-execute)

        ;; 等待所有结果（带超时）
        timeout-ch (async/timeout!! timeout-ms)
        results-ch (async/into [] result-chs)
        [results _] (async/alts!! [results-ch timeout-ch] :priority true)]

    (when (:on-complete config)
      ((:on-complete config) results))
    results))

;; =============================================================================
;; 聚合策略
;; =============================================================================

(defn aggregate-all
  "聚合所有结果（等待全部完成）

   参数:
   - results: 结果列表

   返回: 聚合结果

   示例:
   (aggregate-all results)
   ; => {:successful [...] :failed [...] :all results}"
  [results]
  (let [successful (filter :success results)
        failed (remove :success results)]
    {:successful successful
     :failed failed
     :all results
     :total (count results)
     :success-count (count successful)
     :failed-count (count failed)}))

(defn aggregate-any
  "返回任意一个成功的结果

   参数:
   - results: 结果列表

   返回: 聚合结果

   示例:
   (aggregate-any results)
   ; => {:success true :value ...} 或 {:success false ...}"
  [results]
  (or (first (filter :success results))
      {:success false
       :error {:type "all_failed"
               :message "所有执行都失败了"}}))

(defn aggregate-first-success
  "返回第一个成功的结果（带超时控制）

   参数:
   - execute-fns: 执行函数列表
   - opts: 选项 map

   返回: 第一个成功的结果

   示例:
   (aggregate-first-success
     [fn-1 fn-2 fn-3]
     {:timeout-ms 5000})"
  [execute-fns & [opts]]
  (let [config (default-config opts)
        timeout-ms (:timeout-ms config)

        ;; 创建 channels
        result-chs (mapv (fn [execute-fn]
                            (async/thread
                              (try
                                {:success true :value (execute-fn)}
                                (catch Exception e
                                  {:success false
                                   :error {:type "exception"
                                           :message (.getMessage e)}}))))
                          execute-fns)

        ;; 等待第一个成功或超时
        success-ch (async/go-loop []
                     (let [result (async/alt!!
                                  result-chs
                                  timeout-ch
                                  [val ch]
                                  (if (= ch timeout-ch)
                                    :timeout
                                    val))]
                       (cond
                         (= result :timeout)
                         :timeout

                         (:success result)
                         result

                         :else
                         (recur))))
        timeout-ch (async/timeout!! timeout-ms)]
    (async/<!! success-ch)))

;; =============================================================================
;; 装饰器
;; =============================================================================

(defn with-parallel-execution
  "为函数列表添加并行执行

   参数:
   - execute-fns: 执行函数列表
   - opts: 选项 map

   返回: 并行执行的函数

   示例:
   (def parallel-execute
     (with-parallel-execution
       [fn-1 fn-2 fn-3]
       {:timeout-ms 5000}))"
  [execute-fns & [opts]]
  (fn [& args]
    (let [bound-fns (mapv (fn [f] #(apply f args)) execute-fns)]
      (execute-all bound-fns opts))))

(defn with-parallel-all
  "并行执行并返回所有结果

   参数:
   - execute-fns: 执行函数列表
   - opts: 选项 map

   返回: 结果列表

   示例:
   (with-parallel-all
     [fn-1 fn-2]
     {:timeout-ms 5000})"
  [execute-fns & [opts]]
  (fn [& args]
    (let [bound-fns (mapv (fn [f] #(apply f args)) execute-fns)]
      (execute-all bound-fns (merge {:aggregation :all} opts)))))

;; =============================================================================
;; Map 操作
;; =============================================================================

(defn pmap
  "并行 map（带超时）

   参数:
   - f: 转换函数
   - coll: 集合
   - opts: 选项 map

   返回: 转换后的集合

   示例:
   (pmap inc [1 2 3 4] {:timeout-ms 5000})
   ; => [2 3 4 5]"
  [f coll & [opts]]
  (let [execute-fns (mapv (fn [item] #(f item)) coll)
        results (execute-all execute-fns opts)]
    (mapv :value results)))

(defn pmap-all
  "并行 map（保证所有结果）

   参数:
   - f: 转换函数
   - coll: 集合
   - opts: 选项 map

   返回: Result 列表（success 或 failure）

   示例:
   (pmap-all risky-fn items {:timeout-ms 5000})"
  [f coll & [opts]]
  (let [execute-fns (mapv (fn [item] #(f item)) coll)]
    (execute-all execute-fns opts)))

;; =============================================================================
;; 竞争执行
;; =============================================================================

(defn race
  "竞争执行：返回第一个完成的结果

   参数:
   - execute-fns: 执行函数列表
   - opts: 选项 map

   返回: 第一个完成的结果（成功或失败）

   示例:
   (race [fn-1 fn-2] {:timeout-ms 10000})"
  [execute-fns & [opts]]
  (let [config (default-config opts)
        timeout-ms (:timeout-ms config)

        ;; 创建 channels
        result-chs (mapv (fn [execute-fn]
                            (async/thread
                              (try
                                {:success true :value (execute-fn)}
                                (catch Exception e
                                  {:success false
                                   :error {:type "exception"
                                           :message (.getMessage e)}}))))
                          execute-fns)

        ;; 等待第一个完成
        result-ch (async/go-loop []
                     (let [result (async/alt!!
                                  result-chs
                                  timeout-ch
                                  [val ch]
                                  val)]
                       result))
        timeout-ch (async/timeout!! timeout-ms)]
    (async/<!! result-ch)))

;; =============================================================================
;; 批处理
;; =============================================================================

(defn execute-batches
  "分批并行执行（避免同时启动过多任务）

   参数:
   - execute-fns: 执行函数列表
   - batch-size: 批次大小
   - opts: 选项 map

   返回: 批次结果列表

   示例:
   (execute-batches
     execute-fns
     5
     {:timeout-ms 5000})"
  [execute-fns batch-size & [opts]]
  (let [batches (partition-all batch-size execute-fns)]
    (mapv (fn [batch]
              (execute-all batch opts))
            batches)))

;; =============================================================================
;; 结果处理
;; =============================================================================

(defn extract-successful
  "提取所有成功的结果

   参数:
   - results: 结果列表

   返回: 成功的值列表

   示例:
   (extract-successful results)
   ; => [{:value 1} {:value 2}]"
  [results]
  (mapv :value (filter :success results)))

(defn extract-errors
  "提取所有错误信息

   参数:
   - results: 结果列表

   返回: 错误列表

   示例:
   (extract-errors results)
   ; => [{:type \"...\" :message \"...\"} ...]"
  [results]
  (mapv :error (remove :success results)))

(defn has-successes?
  "检查是否有成功的结果

   参数:
   - results: 结果列表

   返回: boolean

   示例:
   (has-successes? results)
   ; => true 或 false"
  [results]
  (some :success results))

(defn all-successful?
  "检查是否全部成功

   参数:
   - results: 结果列表

   返回: boolean

   示例:
   (all-successful? results)
   ; => true 或 false"
  [results]
  (every? :success results))

;; =============================================================================
;; 统计
;; =============================================================================

(defn execution-stats
  "获取执行统计信息

   参数:
   - results: 结果列表

   返回: 统计 map

   示例:
   (execution-stats results)
   ; => {:total 5 :successful 3 :failed 2}"
  [results]
  {:total (count results)
   :successful (count (filter :success results))
   :failed (count (remove :success results))})

;; =============================================================================
;; 便捷宏
;; =============================================================================

(defmacro pvalues
  "并行计算多个表达式

   参数:
   - opts: 选项 map
   - & forms: 表达式列表

   返回: 结果列表

   示例:
   (pvalues {:timeout-ms 5000}
     (expensive-calc-1)
     (expensive-calc-2)
     (expensive-calc-3))"
  [opts & forms]
  `(let [execute-fns# (mapv (fn [form#]
                               (fn [] ~form#))
                             ~forms)]
     (execute-all execute-fns# ~opts)))
