(ns im.ttalk.agent.tools.strategies.retry
  "重试策略 - 为工具执行添加重试能力

   核心理念：
   - 装饰器模式
   - 可配置的重试策略
   - 指数退避

   ========================================
   基本用法
   ========================================

   (require '[im.ttalk.agent.tools.strategies.retry :as retry])

   ;; 添加重试能力
   (def executor-with-retry
     (retry/with-retry execute-tool
       {:max-retries 3
        :backoff :exponential
        :on-retry (fn [error attempt]
                      (println \"重试\" attempt))}))

   ========================================
   配置选项
   ========================================

   - :max-retries - 最大重试次数（默认 3）
   - :backoff - 退避策略（:fixed, :linear, :exponential）
   - :delay-ms - 基础延迟（默认 1000）
   - :should-retry? - 判断是否重试的函数
   - :on-retry - 重试时的回调"
  (:require [clojure.core.async :as async]
            [im.ttalk.agent.core.common.result :as result]))

;; =============================================================================
;; 重试配置
;; =============================================================================

(defn default-config
  "创建默认重试配置

   参数:
   - opts: 选项 map

   返回: 完整配置

   示例:
   (default-config {:max-retries 5})"
  ([] (default-config {}))
  ([opts]
   (merge {:max-retries 3
           :backoff :exponential
           :delay-ms 1000
           :should-retry? (constantly true)
           :on-retry (fn [error attempt] nil)}
          opts)))

;; =============================================================================
;; 退避计算
;; =============================================================================

(defn calculate-delay
  "计算重试延迟时间

   参数:
   - attempt: 当前尝试次数（从 0 开始）
   - config: 配置 map

   返回: 延迟时间（毫秒）

   示例:
   (calculate-delay 2 {:backoff :exponential :delay-ms 1000})
   ; => 4000"
  [attempt config]
  (let [backoff (:backoff config)
        base-delay (:delay-ms config)]
    (case backoff
      :fixed base-delay
      :linear (* base-delay (inc attempt))
      :exponential (* base-delay (Math/pow 2 attempt))
      base-delay)))

;; =============================================================================
;; 重试判断
;; =============================================================================

(defn should-retry?
  "判断是否应该重试

   参数:
   - error: 错误信息
   - attempt: 当前尝试次数
   - config: 配置 map

   返回: boolean

   示例:
   (should-retry? {:type \"timeout\"} 2 config)
   ; => true 或 false"
  [error attempt config]
  (and (< attempt (:max-retries config))
       ((:should-retry? config) error attempt)))

;; =============================================================================
;; 核心重试逻辑
;; =============================================================================

(defn execute-with-retry
  "带重试的执行

   参数:
   - execute-fn: 执行函数
   - config: 重试配置

   返回: Result

   示例:
   (execute-with-retry
     (fn [] (execute-tool :calc {...}))
     (default-config {:max-retries 3}))"
  [execute-fn config]
  (loop [attempt 0
         last-error nil]
    (let [result (try
                   {:success true :value (execute-fn)}
                   (catch Exception e
                     {:success false :error {:type "exception" :message (.getMessage e)}}))
          error? (not (:success result))]
      (cond
        ;; 成功 - 返回结果
        (not error?) result

        ;; 应该重试
        (should-retry? (:error result) attempt config)
        (do
          ((:on-retry config) (:error result) attempt)
          (let [delay-ms (calculate-delay attempt config)]
            (Thread/sleep delay-ms))
          (recur (inc attempt) (:error result)))

        ;; 不应该重试 - 返回错误
        :else result))))

;; =============================================================================
;; 装饰器
;; =============================================================================

(defn with-retry
  "为执行函数添加重试能力（装饰器）

   参数:
   - execute-fn: 原始执行函数
   - opts: 选项 map

   返回: 带重试的函数

   示例:
   (def safe-execute
     (with-retry execute-tool
       {:max-retries 3
        :backoff :exponential}))"
  [execute-fn & [opts]]
  (let [config (default-config opts)]
    (fn [& args]
      (execute-with-retry
        #(apply execute-fn args)
        config))))

;; =============================================================================
;; 策略快捷方式
;; =============================================================================

(defn with-exponential-backoff
  "使用指数退避的重试

   参数:
   - execute-fn: 执行函数
   - max-retries: 最大重试次数（默认 3）

   返回: 带重试的函数

   示例:
   (with-exponential-backoff execute-tool 3)"
  [execute-fn & [max-retries]]
  (with-retry execute-fn {:max-retries (or max-retries 3)
                              :backoff :exponential}))

(defn with-linear-backoff
  "使用线性退避的重试

   参数:
   - execute-fn: 执行函数
   - max-retries: 最大重试次数（默认 3）

   返回: 带重试的函数"
  [execute-fn & [max-retries]]
  (with-retry execute-fn {:max-retries (or max-retries 3)
                              :backoff :linear}))

(defn retry-on-timeout
  "仅在超时时重试

   参数:
   - execute-fn: 执行函数
   - max-retries: 最大重试次数（默认 3）

   返回: 带重试的函数

   示例:
   (retry-on-timeout execute-tool 3)"
  [execute-fn & [max-retries]]
  (with-retry execute-fn
    {:max-retries (or max-retries 3)
     :should-retry? (fn [error _]
                       (contains? #{"timeout" "network_error" "temporarily_unavailable"}
                                 (str/lower-case (:type error)))))}))

;; =============================================================================
;; 统计和监控
;; =============================================================================

(defn execute-with-stats
  "带统计信息的重试执行

   参数:
   - execute-fn: 执行函数
   - config: 配置 map

   返回: 带统计的结果

   示例:
   (execute-with-stats execute-tool (default-config))
   ; => {:value ... :attempts 2 :total-delay-ms 1000}"
  [execute-fn config]
  (let [start-time (System/currentTimeMillis)
        result (loop [attempt 0
                       attempts 0
                       last-error nil]
                  (let [result (try
                                 {:success true :value (execute-fn)}
                                 (catch Exception e
                                   {:success false :error {:type "exception"}}))]
                    (if (:success result)
                      {:result result
                       :attempts (inc attempts)
                       :total-delay-ms (- (System/currentTimeMillis) start-time)}
                      (if (should-retry? (:error result) attempt config)
                        (do
                          (Thread/sleep (calculate-delay attempt config))
                          (recur (inc attempt) (inc attempts) (:error result)))
                        {:result result
                         :attempts (inc attempts)
                         :total-delay-ms (- (System/currentTimeMillis) start-time)}))))]
    (merge (:result result)
           (select-keys result [:attempts :total-delay-ms]))))

;; =============================================================================
;; 便捷宏
;; =============================================================================

(defmacro retry-on-exception
  "捕获异常并重试

   参数:
   - config: 配置
   - body: 执行体

   返回: 执行结果

   示例:
   (retry-on-exception {:max-retries 3}
     (risky-operation))"
  [config & body]
  `(execute-with-retry (fn [] ~@body) ~config))
