(ns im.ttalk.agent.provider.http.retry
  "HTTP 重试与错误分类（对标 Spring AI RetryUtils）

   ========================================
   设计目标
   ========================================

   - 错误二分类：transient（可重试）vs non-transient（不可重试），对标
     Spring AI 的 TransientAiException / NonTransientAiException。
   - 指数退避 + 满抖动（full jitter），避免重试风暴。
   - 尊重服务端的 Retry-After 响应头（429 / 503 常见）。
   - opt-in：仅当调用方在 config 里传入 :retry 时启用，不改变现有默认行为。

   ========================================
   错误分类规则
   ========================================

   可重试（transient）：
    - 网络层错误（连接失败、超时）—— HTTP 客户端返回 {:error ...}
   - HTTP 408 / 409 / 425 / 429 / 500 / 502 / 503 / 504 / 529

   不可重试（non-transient）：
   - 其余 4xx（400 参数错误、401 鉴权、403、404 等）—— 重试无意义

   ========================================
   基本用法
   ========================================

   (require '[im.ttalk.agent.provider.http.retry :as retry])

   ;; 包裹一个返回 http response map 的函数
   (retry/with-retry
     #(http/post url :headers h :body b)
     {:max-retries 3})

   ;; 关闭重试（max-retries 0）即等价于直接调用
   "
  (:require [clojure.string :as str]))

;; ============================================================
;; 默认配置
;; ============================================================

(def default-retry-opts
  "默认重试配置

   - :max-retries          最大重试次数（不含首次调用）
   - :base-delay           首次退避基准（毫秒）
   - :multiplier           退避倍率
   - :max-delay            单次退避上限（毫秒）
   - :respect-retry-after? 是否优先采用响应头 Retry-After"
  {:max-retries 3
   :base-delay 1000
   :multiplier 2.0
   :max-delay 30000
   :respect-retry-after? true})

;; ============================================================
;; 错误分类
;; ============================================================

(def transient-status-codes
  "可重试的 HTTP 状态码集合"
  #{408 409 425 429 500 502 503 504 529})

(defn transient-status?
  "状态码是否可重试

   示例:
   (transient-status? 429) ; => true
   (transient-status? 400) ; => false"
  [status]
  (contains? transient-status-codes status))

(defn transient-response?
  "http response map 是否代表一个可重试的失败

   参数:
   - resp: {:status int :error any :success? bool ...}

   返回: boolean

   规则:
   - 网络层错误 (:error 非空) -> 可重试
   - 状态码命中 transient-status-codes -> 可重试
   - 其余（含成功响应、不可重试 4xx）-> 不重试"
  [resp]
  (boolean
    (or (some? (:error resp))
        (transient-status? (:status resp)))))

(defn retryable-ex?
  "异常是否可重试

   连接/超时类异常视为可重试；其余默认不可重试。"
  [^Throwable e]
  (let [data (ex-data e)]
    (cond
      ;; ex-info 显式声明
      (contains? data :retryable?) (:retryable? data)
      (contains? data :status)     (transient-status? (:status data))
      ;; IO/超时类异常
      (instance? java.io.IOException e) true
      (instance? java.net.SocketTimeoutException e) true
      :else false)))

;; ============================================================
;; Retry-After 解析
;; ============================================================

(defn- header-get
  "大小写无关地取响应头（HTTP 客户端头通常是小写关键字或字符串，但不同栈大小写不一）"
  [headers k]
  (when headers
    (let [target (str/lower-case (name k))]
      (some (fn [[hk hv]]
              (when (= target (str/lower-case (name hk))) hv))
            headers))))

(defn parse-retry-after
  "从响应头解析 Retry-After，返回毫秒数或 nil

   支持两种格式：
   - 秒数：\"120\" -> 120000
   - HTTP-date：\"Wed, 21 Oct 2025 07:28:00 GMT\" -> 距今毫秒（>=0）

   参数:
   - headers: 响应头 map
   - now-ms:  当前时间戳（毫秒），便于测试注入；缺省取系统时间"
  ([headers] (parse-retry-after headers (System/currentTimeMillis)))
  ([headers now-ms]
   (when-let [v (header-get headers "retry-after")]
     (let [s (str/trim (str v))]
       (cond
         (str/blank? s) nil
         ;; 纯数字 -> 秒
         (re-matches #"\d+" s) (* (parse-long s) 1000)
         ;; HTTP-date
         :else (try
                 (let [instant (-> (java.time.format.DateTimeFormatter/RFC_1123_DATE_TIME)
                                   (.parse s)
                                   (java.time.Instant/from))
                       delta (- (.toEpochMilli instant) now-ms)]
                   (max 0 delta))
                 (catch Exception _ nil)))))))

;; ============================================================
;; 退避计算
;; ============================================================

(defn compute-backoff
  "计算第 attempt 次重试的退避毫秒（含满抖动）

   attempt 从 0 开始。基础退避 = min(base * multiplier^attempt, max-delay)，
   再在 [0, 基础退避] 上取满抖动（full jitter）。

   参数:
   - attempt: 第几次重试（0-based）
   - opts:    {:base-delay :multiplier :max-delay}
   - rand-fn: 取 [0,1) 随机数的函数，便于测试注入（缺省 rand）"
  ([attempt opts] (compute-backoff attempt opts rand))
  ([attempt {:keys [base-delay multiplier max-delay]
             :or {base-delay 1000 multiplier 2.0 max-delay 30000}}
    rand-fn]
   (let [raw (* base-delay (Math/pow multiplier attempt))
         capped (min raw (double max-delay))]
     (long (* (rand-fn) capped)))))

;; ============================================================
;; 重试执行
;; ============================================================

(defn- sleep! [ms]
  (when (pos? ms)
    (Thread/sleep (long ms))))

(defn with-retry
  "带指数退避的重试包装

   参数:
   - request-fn: 无参函数，返回 http response map（{:status :error :success? ...}）
   - opts:       重试配置（合并 default-retry-opts）
     - :max-retries          最大重试次数
     - :base-delay           退避基准毫秒
     - :multiplier           退避倍率
     - :max-delay            退避上限毫秒
     - :respect-retry-after? 是否优先用 Retry-After 头
     - :retry-on             判定是否需要重试的谓词 (fn [resp] bool)，
                             缺省 transient-response?
     - :rand-fn              随机数函数（测试用）
     - :on-retry             每次重试前回调 (fn [{:keys [attempt delay-ms response]}])

   返回: 最终 http response map（成功，或重试耗尽后的最后一次响应）

   说明:
   - 仅对同步、整体可重试的请求使用（如非流式 LLM 调用、或流式建链阶段）。
   - max-retries 为 0 时等价于直接调用 request-fn。"
  [request-fn opts]
  (let [{:keys [max-retries respect-retry-after? retry-on rand-fn on-retry max-delay]
         :or {retry-on transient-response? rand-fn rand}
         :as merged} (merge default-retry-opts opts)]
    (loop [attempt 0]
      (let [resp (request-fn)]
        (if (or (>= attempt max-retries)
                (not (retry-on resp)))
          resp
          (let [retry-after (when respect-retry-after?
                              (parse-retry-after (:headers resp)))
                ;; Retry-After 必须受 max-delay 上限约束：否则服务端返回 Retry-After: 3600
                ;; 会让同步线程直接 sleep 1 小时，:max-delay 形同虚设。
                retry-after (when retry-after (min retry-after (double max-delay)))
                delay-ms (or retry-after
                             (compute-backoff attempt merged rand-fn))]
            (when on-retry
              (on-retry {:attempt (inc attempt)
                         :delay-ms delay-ms
                         :response resp}))
            (sleep! delay-ms)
            (recur (inc attempt))))))))

(defn maybe-with-retry
  "根据 config :retry 决定是否启用重试（opt-in 入口）

   参数:
   - config:     provider config，读取其 :retry
     - 为 nil/false -> 直接调用 request-fn（零开销）
     - 为 true      -> 用默认配置重试
     - 为 map       -> 作为重试配置
   - request-fn: 无参函数，返回 http response map

   返回: http response map"
  [config request-fn]
  (let [r (:retry config)]
    (cond
      (nil? r)   (request-fn)
      (false? r) (request-fn)
      (true? r)  (with-retry request-fn {})
      (map? r)   (with-retry request-fn r)
      :else      (request-fn))))
