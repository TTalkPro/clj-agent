(ns im.ttalk.agent.tools.resilience
  "弹性执行模块

   职责：
   - 重试机制（指数退避）
   - 降级处理（fallback）
   - 断路器（circuit breaker）
   - 超时控制
   - 速率限制

   使用示例：

   (require '[im.ttalk.agent.tools.resilience :as r])

   ;; 带重试的执行
   (r/with-retry {:max-retries 3}
     (risky-operation))

   ;; 带降级的执行
   (r/with-fallback default-value
     (primary-operation))

   ;; 组合使用
   (r/with-resilience
     {:retry {:max-retries 3}
      :fallback {:value \"default\"}
      :timeout {:ms 5000}}
     (complex-operation))"
  (:require [taoensso.timbre :as log]))

;;; ============================================================
;;; 默认配置
;;; ============================================================

(def default-retry-config
  "默认重试配置"
  {:max-retries 3
   :initial-delay-ms 1000
   :max-delay-ms 30000
   :backoff-factor 2.0
   :jitter? true
   :jitter-factor 0.1
   :retryable-exceptions #{Exception}
   :non-retryable-exceptions #{}})

(def default-fallback-config
  "默认降级配置"
  {:value nil
   :fn nil
   :log? true})

(def default-timeout-config
  "默认超时配置"
  {:ms 30000
   :interrupt? true})

;;; ============================================================
;;; 延迟计算
;;; ============================================================

(defn- calculate-base-delay
  "计算基础延迟（指数退避）

   参数：
     attempt - 第几次尝试（从 0 开始）
     config  - 重试配置

   返回：
     延迟毫秒数"
  [attempt config]
  (let [{:keys [initial-delay-ms max-delay-ms backoff-factor]} config
        delay (* initial-delay-ms (Math/pow backoff-factor attempt))]
    (min (long delay) max-delay-ms)))

(defn- add-jitter
  "添加抖动

   参数：
     delay-ms      - 基础延迟
     jitter-factor - 抖动因子

   返回：
     添加抖动后的延迟"
  [delay-ms jitter-factor]
  (let [jitter (* delay-ms jitter-factor (- (* 2 (rand)) 1))]
    (max 0 (long (+ delay-ms jitter)))))

(defn calculate-delay
  "计算重试延迟

   参数：
     attempt - 第几次尝试（从 0 开始）
     config  - 重试配置

   返回：
     延迟毫秒数"
  [attempt config]
  (let [base-delay (calculate-base-delay attempt config)]
    (if (:jitter? config)
      (add-jitter base-delay (:jitter-factor config))
      base-delay)))

;;; ============================================================
;;; 异常判断
;;; ============================================================

(defn- retryable?
  "判断异常是否可重试

   参数：
     exception - 异常
     config    - 重试配置

   返回：
     boolean"
  [exception config]
  (let [{:keys [retryable-exceptions non-retryable-exceptions]} config]
    (and (some #(instance? % exception) retryable-exceptions)
         (not (some #(instance? % exception) non-retryable-exceptions)))))

;;; ============================================================
;;; 重试执行
;;; ============================================================

(defn retry
  "带重试的执行

   参数：
     f      - 要执行的函数（无参数）
     config - 重试配置

   返回：
     执行结果

   配置选项：
     :max-retries           - 最大重试次数（默认 3）
     :initial-delay-ms      - 初始延迟毫秒（默认 1000）
     :max-delay-ms          - 最大延迟毫秒（默认 30000）
     :backoff-factor        - 退避因子（默认 2.0）
     :jitter?               - 是否添加抖动（默认 true）
     :jitter-factor         - 抖动因子（默认 0.1）
     :retryable-exceptions  - 可重试的异常类型集合
     :non-retryable-exceptions - 不可重试的异常类型集合
     :on-retry              - 重试回调 (fn [attempt exception delay-ms] ...)

   示例：
   (retry #(call-api) {:max-retries 5})"
  [f config]
  (let [cfg (merge default-retry-config config)
        max-retries (:max-retries cfg)
        on-retry (:on-retry cfg)]
    (loop [attempt 0]
      (let [result (try
                     {:success true :value (f)}
                     (catch Exception e
                       {:success false :exception e}))]
        (if (:success result)
          (:value result)
          (let [ex (:exception result)]
            (if (and (< attempt max-retries)
                     (retryable? ex cfg))
              (let [delay-ms (calculate-delay attempt cfg)]
                (log/warn "Retry attempt" (inc attempt) "of" max-retries
                          "after" delay-ms "ms due to:" (.getMessage ex))
                (when on-retry
                  (on-retry (inc attempt) ex delay-ms))
                (Thread/sleep delay-ms)
                (recur (inc attempt)))
              (throw ex))))))))

(defmacro with-retry
  "带重试的执行宏

   参数：
     config - 重试配置
     body   - 要执行的代码

   示例：
   (with-retry {:max-retries 3}
     (risky-operation))"
  [config & body]
  `(retry (fn [] ~@body) ~config))

;;; ============================================================
;;; 降级执行
;;; ============================================================

(defn fallback
  "带降级的执行

   参数：
     f             - 主函数（无参数）
     fallback-fn   - 降级函数（接收异常作为参数）

   返回：
     执行结果

   示例：
   (fallback #(call-api) (fn [_] \"default\"))"
  [f fallback-fn]
  (try
    (f)
    (catch Exception e
      (log/warn "Primary operation failed, using fallback:" (.getMessage e))
      (fallback-fn e))))

(defn fallback-value
  "带默认值的降级执行

   参数：
     f       - 主函数（无参数）
     default - 默认值

   返回：
     执行结果或默认值

   示例：
   (fallback-value #(call-api) \"default\")"
  [f default]
  (fallback f (constantly default)))

(defmacro with-fallback
  "带降级的执行宏

   参数：
     fallback-expr - 降级表达式（值或函数调用）
     body          - 要执行的代码

   示例：
   (with-fallback \"default\"
     (risky-operation))

   (with-fallback (get-cached-value)
     (fetch-from-api))"
  [fallback-expr & body]
  `(try
     (do ~@body)
     (catch Exception e#
       (log/warn "Operation failed, using fallback:" (.getMessage e#))
       ~fallback-expr)))

(defmacro with-fallback-fn
  "带降级函数的执行宏

   参数：
     fallback-fn - 降级函数（接收异常）
     body        - 要执行的代码

   示例：
   (with-fallback-fn (fn [e] (handle-error e))
     (risky-operation))"
  [fallback-fn & body]
  `(try
     (do ~@body)
     (catch Exception e#
       (log/warn "Operation failed, calling fallback:" (.getMessage e#))
       (~fallback-fn e#))))

;;; ============================================================
;;; 超时执行
;;; ============================================================

(defn with-timeout-fn
  "带超时的执行

   参数：
     f          - 要执行的函数（无参数）
     timeout-ms - 超时毫秒
     config     - 配置（可选）

   返回：
     执行结果

   抛出：
     java.util.concurrent.TimeoutException - 超时时

   示例：
   (with-timeout-fn #(slow-operation) 5000)"
  ([f timeout-ms]
   (with-timeout-fn f timeout-ms {}))
  ([f timeout-ms config]
   (let [future-result (future (f))
         result (deref future-result timeout-ms ::timeout)]
     (if (= result ::timeout)
       (do
         (when (:interrupt? config true)
           (future-cancel future-result))
         (throw (java.util.concurrent.TimeoutException.
                  (str "Operation timed out after " timeout-ms " ms"))))
       result))))

(defmacro with-timeout
  "带超时的执行宏

   参数：
     timeout-ms - 超时毫秒
     body       - 要执行的代码

   示例：
   (with-timeout 5000
     (slow-operation))"
  [timeout-ms & body]
  `(with-timeout-fn (fn [] ~@body) ~timeout-ms))

;;; ============================================================
;;; 断路器
;;; ============================================================

(defrecord CircuitBreaker
  [state           ; atom {:status :closed/:open/:half-open, :failures 0, :last-failure nil}
   config])        ; {:failure-threshold 5, :reset-timeout-ms 60000, ...}

(def default-circuit-breaker-config
  "默认断路器配置"
  {:failure-threshold 5
   :success-threshold 3
   :reset-timeout-ms 60000
   :half-open-max-calls 3})

(defn create-circuit-breaker
  "创建断路器

   参数：
     config - 断路器配置

   返回：
     CircuitBreaker 实例

   配置选项：
     :failure-threshold   - 触发熔断的失败次数（默认 5）
     :success-threshold   - 恢复正常的成功次数（默认 3）
     :reset-timeout-ms    - 熔断后多久尝试恢复（默认 60000）
     :half-open-max-calls - 半开状态最大调用次数（默认 3）"
  ([]
   (create-circuit-breaker {}))
  ([config]
   (->CircuitBreaker
     (atom {:status :closed
            :failures 0
            :successes 0
            :last-failure nil
            :half-open-calls 0})
     (merge default-circuit-breaker-config config))))

(defn- circuit-status
  "获取断路器状态"
  [circuit-breaker]
  (:status @(:state circuit-breaker)))

(defn- should-allow-request?
  "判断是否允许请求通过"
  [circuit-breaker]
  (let [state @(:state circuit-breaker)
        config (:config circuit-breaker)
        status (:status state)]
    (case status
      :closed true
      :open (let [elapsed (- (System/currentTimeMillis)
                             (or (:last-failure state) 0))]
              (when (>= elapsed (:reset-timeout-ms config))
                ;; 转换到半开状态
                (swap! (:state circuit-breaker) assoc
                       :status :half-open
                       :half-open-calls 0
                       :successes 0)
                true))
      :half-open (< (:half-open-calls state)
                    (:half-open-max-calls config)))))

(defn- record-success!
  "记录成功"
  [circuit-breaker]
  (let [config (:config circuit-breaker)]
    (swap! (:state circuit-breaker)
           (fn [state]
             (case (:status state)
               :half-open
               (let [successes (inc (:successes state))]
                 (if (>= successes (:success-threshold config))
                   (assoc state :status :closed :failures 0 :successes 0)
                   (assoc state :successes successes)))
               ;; :closed - 重置失败计数
               (assoc state :failures 0))))))

(defn- record-failure!
  "记录失败"
  [circuit-breaker]
  (let [config (:config circuit-breaker)]
    (swap! (:state circuit-breaker)
           (fn [state]
             (let [failures (inc (:failures state))
                   now (System/currentTimeMillis)]
               (case (:status state)
                 :closed
                 (if (>= failures (:failure-threshold config))
                   (assoc state :status :open :failures failures :last-failure now)
                   (assoc state :failures failures :last-failure now))

                 :half-open
                 (assoc state :status :open :last-failure now)

                 state))))))

(defn execute-with-circuit-breaker
  "通过断路器执行

   参数：
     circuit-breaker - CircuitBreaker 实例
     f               - 要执行的函数（无参数）

   返回：
     执行结果

   抛出：
     异常 - 当断路器打开或执行失败时"
  [circuit-breaker f]
  (if (should-allow-request? circuit-breaker)
    (do
      (when (= :half-open (circuit-status circuit-breaker))
        (swap! (:state circuit-breaker) update :half-open-calls inc))
      (try
        (let [result (f)]
          (record-success! circuit-breaker)
          result)
        (catch Exception e
          (record-failure! circuit-breaker)
          (throw e))))
    (throw (ex-info "Circuit breaker is open"
                    {:status (circuit-status circuit-breaker)}))))

(defmacro with-circuit-breaker
  "通过断路器执行宏

   参数：
     circuit-breaker - CircuitBreaker 实例
     body            - 要执行的代码

   示例：
   (def cb (create-circuit-breaker))
   (with-circuit-breaker cb
     (call-external-service))"
  [circuit-breaker & body]
  `(execute-with-circuit-breaker ~circuit-breaker (fn [] ~@body)))

;;; ============================================================
;;; 速率限制
;;; ============================================================

(defrecord RateLimiter
  [state    ; atom {:tokens n, :last-refill timestamp}
   config]) ; {:max-tokens n, :refill-rate-per-second n}

(def default-rate-limiter-config
  "默认速率限制配置"
  {:max-tokens 10
   :refill-rate-per-second 1})

(defn create-rate-limiter
  "创建速率限制器

   参数：
     config - 配置

   返回：
     RateLimiter 实例

   配置选项：
     :max-tokens            - 最大令牌数（默认 10）
     :refill-rate-per-second - 每秒补充令牌数（默认 1）"
  ([]
   (create-rate-limiter {}))
  ([config]
   (let [cfg (merge default-rate-limiter-config config)]
     (->RateLimiter
       (atom {:tokens (:max-tokens cfg)
              :last-refill (System/currentTimeMillis)})
       cfg))))

(defn- refill-tokens!
  "补充令牌"
  [rate-limiter]
  (let [config (:config rate-limiter)
        max-tokens (:max-tokens config)
        refill-rate (:refill-rate-per-second config)]
    (swap! (:state rate-limiter)
           (fn [{:keys [tokens last-refill]}]
             (let [now (System/currentTimeMillis)
                   elapsed-seconds (/ (- now last-refill) 1000.0)
                   new-tokens (min max-tokens
                                   (+ tokens (* refill-rate elapsed-seconds)))]
               {:tokens new-tokens
                :last-refill now})))))

(defn acquire-token!
  "获取令牌

   参数：
     rate-limiter - RateLimiter 实例
     timeout-ms   - 超时毫秒（可选，默认 0 表示不等待）

   返回：
     boolean - 是否获取成功"
  ([rate-limiter]
   (acquire-token! rate-limiter 0))
  ([rate-limiter timeout-ms]
   (refill-tokens! rate-limiter)
   (let [start-time (System/currentTimeMillis)]
     (loop []
       (let [acquired (atom false)]
         (swap! (:state rate-limiter)
                (fn [{:keys [tokens] :as state}]
                  (if (>= tokens 1)
                    (do
                      (reset! acquired true)
                      (update state :tokens dec))
                    state)))
         (if @acquired
           true
           (if (and (pos? timeout-ms)
                    (< (- (System/currentTimeMillis) start-time) timeout-ms))
             (do
               (Thread/sleep 10)
               (refill-tokens! rate-limiter)
               (recur))
             false)))))))

(defn execute-with-rate-limit
  "带速率限制的执行

   参数：
     rate-limiter - RateLimiter 实例
     f            - 要执行的函数
     timeout-ms   - 等待超时毫秒（可选）

   返回：
     执行结果

   抛出：
     异常 - 当无法获取令牌时"
  ([rate-limiter f]
   (execute-with-rate-limit rate-limiter f 0))
  ([rate-limiter f timeout-ms]
   (if (acquire-token! rate-limiter timeout-ms)
     (f)
     (throw (ex-info "Rate limit exceeded"
                     {:available-tokens (:tokens @(:state rate-limiter))})))))

(defmacro with-rate-limit
  "带速率限制的执行宏

   参数：
     rate-limiter - RateLimiter 实例
     body         - 要执行的代码

   示例：
   (def rl (create-rate-limiter {:max-tokens 5}))
   (with-rate-limit rl
     (call-api))"
  [rate-limiter & body]
  `(execute-with-rate-limit ~rate-limiter (fn [] ~@body)))

;;; ============================================================
;;; 组合弹性模式
;;; ============================================================

(defn with-resilience-fn
  "组合多种弹性模式执行

   参数：
     f      - 要执行的函数
     config - 弹性配置

   返回：
     执行结果

   配置选项：
     :retry          - 重试配置
     :fallback       - 降级配置 {:value v} 或 {:fn f}
     :timeout        - 超时配置 {:ms n}
     :circuit-breaker - 断路器实例
     :rate-limiter   - 速率限制器实例"
  [f config]
  (let [{:keys [retry fallback timeout circuit-breaker rate-limiter]} config

        ;; 包装函数层层嵌套
        wrapped-fn f

        ;; 添加速率限制
        wrapped-fn (if rate-limiter
                     #(execute-with-rate-limit rate-limiter wrapped-fn)
                     wrapped-fn)

        ;; 添加断路器
        wrapped-fn (if circuit-breaker
                     #(execute-with-circuit-breaker circuit-breaker wrapped-fn)
                     wrapped-fn)

        ;; 添加超时
        wrapped-fn (if timeout
                     #(with-timeout-fn wrapped-fn (:ms timeout))
                     wrapped-fn)

        ;; 添加重试
        wrapped-fn (if retry
                     #(im.ttalk.agent.tools.resilience/retry wrapped-fn retry)
                     wrapped-fn)

        ;; 添加降级
        wrapped-fn (if fallback
                     (if-let [fallback-fn (:fn fallback)]
                       #(im.ttalk.agent.tools.resilience/fallback wrapped-fn fallback-fn)
                       #(im.ttalk.agent.tools.resilience/fallback-value wrapped-fn (:value fallback)))
                     wrapped-fn)]

    (wrapped-fn)))

(defmacro with-resilience
  "组合多种弹性模式执行宏

   参数：
     config - 弹性配置
     body   - 要执行的代码

   示例：
   (with-resilience
     {:retry {:max-retries 3}
      :fallback {:value \"default\"}
      :timeout {:ms 5000}}
     (call-external-api))"
  [config & body]
  `(with-resilience-fn (fn [] ~@body) ~config))
