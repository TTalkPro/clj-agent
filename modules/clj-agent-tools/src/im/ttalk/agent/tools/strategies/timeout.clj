(ns im.ttalk.agent.tools.strategies.timeout
  "超时策略 - 为工具执行添加超时控制

   核心理念：
   - 装饰器模式
   - 可配置的超时时间
   - 支持取消和清理

   ========================================
   基本用法
   ========================================

   (require '[im.ttalk.agent.tools.strategies.timeout :as timeout])

   ;; 添加超时能力
   (def executor-with-timeout
     (timeout/with-timeout execute-tool
       {:timeout-ms 5000
        :on-timeout (fn [tool-name]
                        (println \"超时:\" tool-name))}))

   ========================================
   配置选项
   ========================================

   - :timeout-ms - 超时时间（毫秒，默认 30000）
   - :on-timeout - 超时时的回调
   - :throw-timeout? - 是否抛出异常（默认 false）
   - :default-value - 超时时返回的默认值"
  (:require [clojure.core.async :as async]
            [im.ttalk.agent.core.common.result :as result]))

;; =============================================================================
;; 超时配置
;; =============================================================================

(defn default-config
  "创建默认超时配置

   参数:
   - opts: 选项 map

   返回: 完整配置

   示例:
   (default-config {:timeout-ms 5000})"
  ([] (default-config {}))
  ([opts]
   (merge {:timeout-ms 30000
           :on-timeout (fn [info] nil)
           :throw-timeout? false
           :default-value nil}
          opts)))

;; =============================================================================
;; 核心超时逻辑
;; =============================================================================

(defn execute-with-timeout
  "带超时的执行

   参数:
   - execute-fn: 执行函数
   - config: 超时配置
   - tool-info: 工具信息（可选，用于回调）

   返回: Result

   示例:
   (execute-with-timeout
     (fn [] (execute-tool :calc {...}))
     (default-config {:timeout-ms 5000})
     {:name :calculator})"
  [execute-fn config & [tool-info]]
  (let [timeout-ch (async/timeout!! (:timeout-ms config))
        result-ch (async/thread
                     (try
                       {:success true :value (execute-fn)}
                       (catch Exception e
                         {:success false :error {:type "exception" :message (.getMessage e)}})))
        [result _] (async/alts!! [result-ch timeout-ch] :priority true)]
    (cond
      ;; 成功完成
      (and (= result-ch result) (:success result))
      result

      ;; 超时
      (= result-ch timeout-ch)
      (do
        ((:on-timeout config) (merge tool-info {:reason "timeout"}))
        (if (:throw-timeout? config)
          (throw (ex-info (str "超时: " (:timeout-ms config) "ms") tool-info))
          {:success false
           :error {:type "timeout"
                   :message (str "超时 " (:timeout-ms config) "ms")
                   :tool-info tool-info}}))

      ;; 错误
      :else result)))

;; =============================================================================
;; 装饰器
;; =============================================================================

(defn with-timeout
  "为执行函数添加超时能力（装饰器）

   参数:
   - execute-fn: 原始执行函数
   - opts: 选项 map

   返回: 带超时的函数

   示例:
   (def safe-execute
     (with-timeout execute-tool
       {:timeout-ms 5000}))
   ; (safe-execute :calc {...})"
  [execute-fn & [opts]]
  (let [config (default-config opts)]
    (fn [& args]
      (let [[tool-name & tool-args] args
            tool-info (when (keyword? tool-name)
                       {:name tool-name :args tool-args})]
        (if tool-info
          (execute-with-timeout
            #(apply execute-fn args)
            config
            tool-info)
          (execute-with-timeout
            #(apply execute-fn args)
            config))))))

;; =============================================================================
;; 策略快捷方式
;; =============================================================================

(defn with-short-timeout
  "使用短超时（5秒）

   参数:
   - execute-fn: 执行函数
   - opts: 其他选项

   返回: 带超时的函数

   示例:
   (with-short-timeout execute-tool)"
  [execute-fn & [opts]]
  (with-timeout execute-fn (merge {:timeout-ms 5000} (apply hash-map opts))))

(defn with-medium-timeout
  "使用中等超时（30秒）

   参数:
   - execute-fn: 执行函数
   - opts: 其他选项

   返回: 带超时的函数"
  [execute-fn & [opts]]
  (with-timeout execute-fn (merge {:timeout-ms 30000} (apply hash-map opts))))

(defn with-long-timeout
  "使用长超时（2分钟）

   参数:
   - execute-fn: 执行函数
   - opts: 其他选项

   返回: 带超时的函数"
  [execute-fn & [opts]]
  (with-timeout execute-fn (merge {:timeout-ms 120000} (apply hash-map opts))))

;; =============================================================================
;; 带默认值的超时
;; =============================================================================

(defn with-timeout-and-default
  "超时时返回默认值

   参数:
   - execute-fn: 执行函数
   - timeout-ms: 超时时间
   - default-value: 默认值
   - opts: 其他选项

   返回: 带超时的函数

   示例:
   (def safe-execute
     (with-timeout-and-default
       execute-tool
       5000
       {:error \"超时\"}))"
  [execute-fn timeout-ms default-value & [opts]]
  (with-timeout execute-fn
    (merge {:timeout-ms timeout-ms
            :default-value default-value}
           (apply hash-map opts))))

;; =============================================================================
;; 并行超时控制
;; =============================================================================

(defn execute-all-with-timeout
  "并行执行多个工具，每个都有超时

   参数:
   - execute-fns: 执行函数列表
   - config: 超时配置

   返回: 结果列表

   示例:
   (execute-all-with-timeout
     [execute-tool-1 execute-tool-2]
     (default-config {:timeout-ms 5000}))"
  [execute-fns config]
  (let [result-chs (mapv (fn [execute-fn]
                           (async/thread
                             (execute-with-timeout execute-fn config)))
                         execute-fns)
        timeout-ch (async/timeout!! (:timeout-ms config))
        results-ch (async/into [] result-chs)
        [results _] (async/alts!! [results-ch timeout-ch])]
    results))

;; =============================================================================
;; 超时统计
;; =============================================================================

(defn execute-with-stats
  "带统计信息的超时执行

   参数:
   - execute-fn: 执行函数
   - config: 配置 map

   返回: 带统计的结果

   示例:
   (execute-with-stats execute-tool (default-config))
   ; => {:value ... :timed-out? false :duration-ms 123}"
  [execute-fn config]
  (let [start-time (System/currentTimeMillis)
        result (execute-with-timeout execute-fn config)
        end-time (System/currentTimeMillis)]
    (merge result
           {:timed-out? (= (:timeout-ch result) result)
            :duration-ms (- end-time start-time)})))

;; =============================================================================
;; 便捷宏
;; =============================================================================

(defmacro with-timeout*
  "为代码块添加超时控制

   参数:
   - timeout-ms: 超时时间（毫秒）
   - body: 执行体

   返回: 执行结果或超时结果

   示例:
   (with-timeout* 5000
     (risky-operation))"
  [timeout-ms & body]
  `(execute-with-timeout (fn [] ~@body)
                        (default-config {:timeout-ms ~timeout-ms})))

(defmacro with-timeout-and-default*
  "超时时返回默认值（宏版本）

   参数:
   - timeout-ms: 超时时间（毫秒）
   - default-value: 默认值
   - body: 执行体

   返回: 执行结果或默认值

   示例:
   (with-timeout-and-default* 5000
     :timeout-result
     (risky-operation))"
  [timeout-ms default-value & body]
  `(execute-with-timeout (fn [] ~@body)
                        (default-config {:timeout-ms ~timeout-ms
                                        :default-value ~default-value})))
