(ns im.ttalk.agent.tools.strategies.fallback
  "降级策略 - 为工具执行添加降级能力

   核心理念：
   - 装饰器模式
   - 多级降级
   - 优雅降级

   ========================================
   基本用法
   ========================================

   (require '[im.ttalk.agent.tools.strategies.fallback :as fallback])

   ;; 单级降级
   (def executor-with-fallback
     (fallback/with-fallback execute-tool
       primary-tool
       fallback-tool))

   ;; 多级降级
   (def executor-with-multi-fallback
     (fallback/with-multi-fallback
       execute-tool
       [tool-1 tool-2 tool-3]))

   ========================================
   设计原则
   ========================================

   - 优雅降级
   - 错误处理
   - 可配置"
  (:require [im.ttalk.agent.core.common.result :as result]))

;; =============================================================================
;; 单级降级
;; =============================================================================

(defn execute-with-fallback
  "带降级的执行

   参数:
   - primary-fn: 主执行函数
   - fallback-fn: 降级执行函数
   - opts: 选项 map

   返回: Result

   示例:
   (execute-with-fallback
     (fn [] (execute-tool :premium-api {...}))
     (fn [] (execute-tool :free-api {...})))"
  [primary-fn fallback-fn & [opts]]
  (let [config (merge {:log-failures true} opts)
        primary-result (try
                         {:success true :value (primary-fn)}
                         (catch Exception e
                           {:success false :error {:type "exception" :message (.getMessage e)}}))]
    (if (:success primary-result)
      (do
        (when (:log-failures config)
          (println "[Fallback] Primary succeeded"))
        primary-result)
      (do
        (when (:log-failures config)
          (println "[Fallback] Primary failed, trying fallback"))
        (try
          {:success true :value (fallback-fn)}
          (catch Exception e
            {:success false :error {:type "exception" :message (.getMessage e)}}))))))

;; =============================================================================
;; 多级降级
;; =============================================================================

(defn execute-with-multi-fallback
  "多级降级执行

   参数:
   - execute-fns: 执行函数列表
   - opts: 选项 map

   返回: Result

   示例:
   (execute-with-multi-fallback
     [fn-1 fn-2 fn-3]
     {:log-failures true})"
  [execute-fns & [opts]]
  (let [config (merge {:log-failures true} opts)]
    (loop [fns execute-fns
           index 0
           last-error nil]
      (if (empty? fns)
        {:success false
         :error {:type "all_fallbacks_failed"
                 :message "所有降级都失败了"
                 :attempts index
                 :last-error last-error}}
        (let [current-fn (first fns)
              result (try
                       {:success true :value (current-fn)}
                       (catch Exception e
                         {:success false
                          :error {:type "exception"
                                  :message (.getMessage e)
                                  :index index}}))]
          (if (:success result)
            (do
              (when (:log-failures config)
                (println "[Fallback] Success at level" (inc index)))
              result)
            (do
              (when (:log-failures config)
                (println "[Fallback] Level" index "failed"))
              (recur (rest fns) (inc index) (:error result)))))))))

;; =============================================================================
;; 装饰器
;; =============================================================================

(defn with-fallback
  "为执行函数添加降级能力（装饰器）

   参数:
   - primary-fn: 主执行函数
   - fallback-fn: 降级执行函数

   返回: 带降级的函数

   示例:
   (def safe-execute
     (with-fallback execute-primary execute-backup))"
  [primary-fn fallback-fn]
  (fn [& args]
    (execute-with-fallback
      #(apply primary-fn args)
      #(apply fallback-fn args))))

(defn with-multi-fallback
  "为执行函数添加多级降级（装饰器）

   参数:
   - execute-fns: 执行函数列表

   返回: 带降级的函数

   示例:
   (def safe-execute
     (with-multi-fallback [fn-1 fn-2 fn-3]))"
  [execute-fns]
  (fn [& args]
    (execute-with-multi-fallback
      (mapv (fn [f] #(apply f args)) execute-fns))))

;; =============================================================================
;; 条件降级
;; =============================================================================

(defn execute-with-conditional-fallback
  "条件降级执行

   参数:
   - primary-fn: 主执行函数
   - condition-fn: 条件判断函数（接收错误，返回 boolean）
   - fallback-fn: 降级执行函数

   返回: Result

   示例:
   (execute-with-conditional-fallback
     execute-primary
     (fn [error] (= (:type error) \"rate_limit\"))
     execute-backup)"
  [primary-fn condition-fn fallback-fn]
  (let [primary-result (try
                         {:success true :value (primary-fn)}
                         (catch Exception e
                           {:success false :error {:type "exception" :message (.getMessage e)}}))]
    (if (:success primary-result)
      primary-result
      (if (condition-fn (:error primary-result))
        (try
          {:success true :value (fallback-fn)}
          (catch Exception e
            {:success false :error {:type "exception" :message (.getMessage e)}}))
          (catch Exception e
            {:success false :error {:type "exception" :message (.getMessage e)}}))
        primary-result))))

;; =============================================================================
;; 缓存降级
;; =============================================================================

(defn execute-with-cached-fallback
  "使用缓存值作为降级

   参数:
   - execute-fn: 执行函数
   - cache-fn: 缓存查找函数

   返回: Result

   示例:
   (defn get-from-cache [key]
     (get @cache key))

   (execute-with-cached-fallback
     (fn [] (execute-tool :api {...}))
     get-from-cache)"
  [execute-fn cache-fn]
  (let [result (try
                 {:success true :value (execute-fn)}
                 (catch Exception e
                   {:success false :error {:type "exception" :message (.getMessage e)}}))]
    (if (:success result)
      result
      (let [cached-value (cache-fn)]
        (if cached-value
          {:success true :value cached-value
           :from-cache? true}
          {:success false
           :error {:type "cache_miss"
                   :message "降级失败：缓存未命中"
                   :original-error (:error result)}})))))

;; =============================================================================
;; 默认值降级
;; =============================================================================

(defn execute-with-default
  "失败时返回默认值

   参数:
   - execute-fn: 执行函数
   - default-value: 默认值
   - opts: 选项 map

   返回: 执行结果或默认值

   示例:
   (execute-with-default
     (fn [] (execute-tool :api {...}))
     {:result \"default\"})"
  [execute-fn default-value & [opts]]
  (let [config (merge {:log-failure false} opts)]
    (try
      {:success true :value (execute-fn)}
      (catch Exception e
        (when (:log-failure config)
          (println "[Fallback] Using default value:" default-value))
        {:success true :value default-value
         :from-default? true}))))

;; =============================================================================
;; 便捷宏
;; =============================================================================

(defmacro with-fallback*
  "为代码块添加降级（宏版本）

   参数:
   - fallback-fn: 降级函数
   - body: 主执行体

   返回: 执行结果或降级结果

   示例:
   (with-fallback*
     (fn [] (backup-operation))
     (risky-operation))"
  [fallback-fn & body]
  `(execute-with-fallback (fn [] ~@body) ~fallback-fn))

(defmacro with-default*
  "失败时使用默认值（宏版本）

   参数:
   - default-value: 默认值
   - body: 执行体

   返回: 执行结果或默认值

   示例:
   (with-default* :default-value
     (risky-operation))"
  [default-value & body]
  `(execute-with-default (fn [] ~@body) ~default-value))
