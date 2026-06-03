(ns im.ttalk.agent.tools.resilience
  "弹性执行模块

   提供工具级别的弹性包装函数：
   - 重试机制（指数退避）
   - 超时控制
   - 断路器
   - 速率限制"
  (:require [taoensso.timbre :as log]
            [im.ttalk.agent.core.kernel.filter :as kf]))

;; ============================================================
;; 默认配置
;; ============================================================

(def default-retry-opts
  "默认重试配置"
  {:max-retries 3
   :initial-delay-ms 1000
   :max-delay-ms 30000
   :backoff-factor 2.0
   :jitter? true
   :jitter-factor 0.1
   :retryable-exceptions #{Exception}
   :non-retryable-exceptions #{}})

;; ============================================================
;; 延迟计算
;; ============================================================

(defn- calculate-delay
  "计算重试延迟（指数退避 + 可选抖动）"
  [attempt opts]
  (let [{:keys [initial-delay-ms max-delay-ms backoff-factor jitter? jitter-factor]} opts
        base-delay (min (long (* initial-delay-ms (Math/pow backoff-factor attempt)))
                        max-delay-ms)]
    (if jitter?
      (let [jitter (* base-delay jitter-factor (- (* 2 (rand)) 1))]
        (max 0 (long (+ base-delay jitter))))
      base-delay)))

(defn- retryable?
  "判断异常是否可重试"
  [exception opts]
  (let [{:keys [retryable-exceptions non-retryable-exceptions]} opts]
    (and (some #(instance? % exception) retryable-exceptions)
         (not (some #(instance? % exception) non-retryable-exceptions)))))

;; ============================================================
;; 重试
;; ============================================================

(defn with-retry
  "带重试的执行

   参数:
   - opts: 重试配置 map
   - f:    要执行的无参函数

   配置选项:
   - :max-retries           最大重试次数（默认 3）
   - :initial-delay-ms      初始延迟毫秒（默认 1000）
   - :max-delay-ms          最大延迟毫秒（默认 30000）
   - :backoff-factor        退避因子（默认 2.0）
   - :jitter?               是否添加抖动（默认 true）
   - :on-retry              重试回调 (fn [attempt exception delay-ms])"
  [opts f]
  (let [cfg (merge default-retry-opts opts)
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

;; ============================================================
;; 超时
;; ============================================================

(defn with-timeout
  "带超时的执行

   参数:
   - timeout-ms: 超时毫秒数
   - f:          要执行的无参函数

   超时时抛出 TimeoutException"
  [timeout-ms f]
  (let [future-result (future (f))
        result (deref future-result timeout-ms ::timeout)]
    (if (= result ::timeout)
      (do
        (future-cancel future-result)
        (throw (java.util.concurrent.TimeoutException.
                 (str "Operation timed out after " timeout-ms " ms"))))
      result)))

;; ============================================================
;; 断路器
;; ============================================================

(defn create-circuit-breaker
  "创建断路器

   配置选项:
   - :failure-threshold   触发熔断的失败次数（默认 5）
   - :success-threshold   恢复正常的成功次数（默认 3）
   - :reset-timeout-ms    熔断后多久尝试恢复（默认 60000）
   - :half-open-max-calls 半开状态最大调用次数（默认 3）"
  ([]
   (create-circuit-breaker {}))
  ([opts]
   (let [config (merge {:failure-threshold 5
                         :success-threshold 3
                         :reset-timeout-ms 60000
                         :half-open-max-calls 3}
                       opts)]
     {:state (atom {:status :closed
                    :failures 0
                    :successes 0
                    :last-failure nil
                    :half-open-calls 0})
      :config config})))

(defn- should-allow?
  [cb]
  (let [state @(:state cb)
        config (:config cb)
        status (:status state)]
    (case status
      :closed true
      :open (let [elapsed (- (System/currentTimeMillis)
                             (or (:last-failure state) 0))]
              (when (>= elapsed (:reset-timeout-ms config))
                (swap! (:state cb) assoc
                       :status :half-open
                       :half-open-calls 0
                       :successes 0)
                true))
      :half-open (< (:half-open-calls state)
                    (:half-open-max-calls config)))))

(defn- record-success! [cb]
  (let [config (:config cb)]
    (swap! (:state cb)
           (fn [state]
             (case (:status state)
               :half-open
               (let [successes (inc (:successes state))]
                 (if (>= successes (:success-threshold config))
                   (assoc state :status :closed :failures 0 :successes 0)
                   (assoc state :successes successes)))
               (assoc state :failures 0))))))

(defn- record-failure! [cb]
  (let [config (:config cb)]
    (swap! (:state cb)
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

(defn with-circuit-breaker
  "通过断路器执行

   参数:
   - cb: 断路器实例
   - f:  要执行的无参函数"
  [cb f]
  (if (should-allow? cb)
    (do
      (when (= :half-open (:status @(:state cb)))
        (swap! (:state cb) update :half-open-calls inc))
      (try
        (let [result (f)]
          (record-success! cb)
          result)
        (catch Exception e
          (record-failure! cb)
          (throw e))))
    (throw (ex-info "Circuit breaker is open"
                    {:status (:status @(:state cb))}))))

;; ============================================================
;; 速率限制
;; ============================================================

(defn create-rate-limiter
  "创建速率限制器

   配置选项:
   - :max-tokens             最大令牌数（默认 10）
   - :refill-rate-per-second 每秒补充令牌数（默认 1）"
  ([]
   (create-rate-limiter {}))
  ([opts]
   (let [config (merge {:max-tokens 10
                         :refill-rate-per-second 1}
                       opts)]
     {:state (atom {:tokens (:max-tokens config)
                    :last-refill (System/currentTimeMillis)})
      :config config})))

(defn- refill-tokens! [rl]
  (let [config (:config rl)
        max-tokens (:max-tokens config)
        refill-rate (:refill-rate-per-second config)]
    (swap! (:state rl)
           (fn [{:keys [tokens last-refill]}]
             (let [now (System/currentTimeMillis)
                   elapsed-seconds (/ (- now last-refill) 1000.0)
                   new-tokens (min max-tokens
                                   (+ tokens (* refill-rate elapsed-seconds)))]
               {:tokens new-tokens
                :last-refill now})))))

(defn- acquire-token! [rl]
  (refill-tokens! rl)
  (let [acquired (atom false)]
    (swap! (:state rl)
           (fn [{:keys [tokens] :as state}]
             (if (>= tokens 1)
               (do (reset! acquired true)
                   (update state :tokens dec))
               state)))
    @acquired))

(defn with-rate-limit
  "带速率限制的执行

   参数:
   - rl: 速率限制器实例
   - f:  要执行的无参函数

   无法获取令牌时抛出异常"
  [rl f]
  (if (acquire-token! rl)
    (f)
    (throw (ex-info "Rate limit exceeded"
                    {:available-tokens (:tokens @(:state rl))}))))

;; ============================================================
;; Kernel Tool Filter 工厂
;; ============================================================

(defn circuit-breaker-filter
  "断路器 tool filter

   断路器 open 时短路（不调下游），返回提示结果。

   参数:
   - cb: 断路器实例（由 create-circuit-breaker 创建）

   返回: filter 定义 map"
  [cb]
  (kf/create-filter :circuit-breaker :tool :order -30
    :around
    (fn [req chain]
      (if (should-allow? cb)
        (do (when (= :half-open (:status @(:state cb)))
              (swap! (:state cb) update :half-open-calls inc))
            (chain req))
        {:result "Circuit breaker is open" :context (:context req)}))))

(defn rate-limit-filter
  "速率限制 tool filter

   无法获取令牌时短路（不调下游），返回提示结果。

   参数:
   - rl: 速率限制器实例（由 create-rate-limiter 创建）

   返回: filter 定义 map"
  [rl]
  (kf/create-filter :rate-limit :tool :order -20
    :around
    (fn [req chain]
      (if (acquire-token! rl)
        (chain req)
        {:result "Rate limit exceeded" :context (:context req)}))))
