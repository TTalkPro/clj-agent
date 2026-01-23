(ns im.ttalk.agent.tools.executor
  "工具执行器

   职责：
   - 高级工具执行（并行、批量、策略）
   - 重试逻辑（指数退避）
   - 降级机制（fallback）
   - 超时控制
   - 错误处理

   使用示例：

   (require '[im.ttalk.agent.tools.executor :as executor])
   (require '[im.ttalk.agent.tools.api :as tools])

   (def registry (tools/create-tool-registry))

   ;; 并行执行
   (executor/execute-parallel registry tool-calls)

   ;; 带重试的执行
   (executor/execute-with-retry registry :calculator {:expression \"2+2\"}
                                {:max-retries 3})"
  (:require [im.ttalk.agent.tools.tool-registry :as tool-registry]
            [im.ttalk.agent.tools.resilience :as resilience]
            [clojure.core.async :as async]
            [taoensso.timbre :as log]))

;;; ============================================================
;;; 配置常量
;;; ============================================================

(def ^:private default-config
  "默认执行配置"
  {:timeout-ms 30000
   :max-retries 3
   :initial-delay-ms 1000
   :max-delay-ms 10000
   :backoff-factor 2
   :parallel? true
   :continue-on-error? false
   :throw-on-error? true})

;;; ============================================================
;;; 执行结果
;;; ============================================================

(defn make-execution-result
  "创建执行结果

   参数：
     tool-name   - 工具名称
     success     - 是否成功
     result      - 结果或错误
     duration-ms - 执行耗时
     retry-count - 重试次数（可选）
     metadata    - 元数据（可选）

   返回：
     ExecutionResult map"
  ([tool-name success result duration-ms]
   (make-execution-result tool-name success result duration-ms 0 {}))
  ([tool-name success result duration-ms retry-count metadata]
   {:tool-name tool-name
    :success success
    :result (when success result)
    :error (when-not success result)
    :duration-ms duration-ms
    :retry-count retry-count
    :metadata metadata}))

;;; ============================================================
;;; 单工具执行
;;; ============================================================

(defn- execute-single-tool
  "执行单个工具

   参数：
     registry   - ToolRegistry 实例
     tool-name  - 工具名称
     arguments  - 参数 map
     start-time - 开始时间

   返回：
     ExecutionResult"
  [registry tool-name arguments start-time]
  (try
    (let [result (tool-registry/execute-tool registry tool-name arguments)
          duration (- (System/currentTimeMillis) start-time)]
      (make-execution-result tool-name (:success result) (:result result) duration))
    (catch Exception e
      (let [duration (- (System/currentTimeMillis) start-time)]
        (log/error "Tool execution error:" tool-name (.getMessage e))
        (make-execution-result tool-name false (.getMessage e) duration)))))

;;; ============================================================
;;; 超时执行
;;; ============================================================

(defn execute-with-timeout
  "带超时的工具执行

   参数：
     registry   - ToolRegistry 实例
     tool-name  - 工具名称
     arguments  - 参数 map
     timeout-ms - 超时时间（毫秒）

   返回：
     ExecutionResult"
  [registry tool-name arguments timeout-ms]
  (let [start-time (System/currentTimeMillis)
        result-future (future (execute-single-tool registry tool-name arguments start-time))
        timeout-val (deref result-future timeout-ms ::timeout)]
    (if (= timeout-val ::timeout)
      (let [duration (- (System/currentTimeMillis) start-time)]
        (log/warn "Tool execution timeout:" tool-name "after" duration "ms")
        (make-execution-result tool-name false
                               (str "Execution timeout after " timeout-ms " ms")
                               duration))
      timeout-val)))

;;; ============================================================
;;; 重试逻辑（委托给 resilience 模块）
;;; ============================================================

(defn execute-with-retry
  "带重试的工具执行

   委托给 resilience 模块的重试实现，避免代码重复。
   resilience 模块提供更完整的重试功能（抖动、异常过滤等）。

   参数：
     registry  - ToolRegistry 实例
     tool-name - 工具名称
     arguments - 参数 map
     config    - 配置 map（可选）

   返回：
     ExecutionResult

   配置选项：
     :max-retries      - 最大重试次数（默认 3）
     :initial-delay-ms - 初始延迟（默认 1000）
     :max-delay-ms     - 最大延迟（默认 10000）
     :backoff-factor   - 退避因子（默认 2）
     :timeout-ms       - 单次超时（默认 30000）"
  ([registry tool-name arguments]
   (execute-with-retry registry tool-name arguments default-config))
  ([registry tool-name arguments config]
   (let [cfg (merge default-config config)
         timeout-ms (:timeout-ms cfg)
         retry-config {:max-retries (:max-retries cfg)
                       :initial-delay-ms (:initial-delay-ms cfg)
                       :max-delay-ms (:max-delay-ms cfg)
                       :backoff-factor (:backoff-factor cfg)
                       :jitter? false}  ; 保持原有行为，不添加抖动
         attempt-count (atom 0)
         ;; 执行函数（带超时）
         execute-fn (fn []
                      (log/info (if (zero? @attempt-count)
                                  (str "Executing tool: " tool-name)
                                  (str "Retrying tool: " tool-name " attempt " @attempt-count)))
                      (swap! attempt-count inc)
                      (let [result (execute-with-timeout registry tool-name arguments timeout-ms)]
                        (if (:success result)
                          result
                          ;; 失败时抛出异常触发重试
                          (throw (ex-info "Tool execution failed"
                                          {:result result})))))]
     ;; 使用 resilience 模块执行重试
     (try
       (let [result (resilience/retry execute-fn retry-config)]
         (assoc result :retry-count (dec @attempt-count)))
       (catch Exception e
         ;; 重试耗尽后返回最后的失败结果
         (let [result (or (-> e ex-data :result)
                          (make-execution-result tool-name false (.getMessage e) 0))]
           (assoc result :retry-count (dec @attempt-count))))))))

;;; ============================================================
;;; 降级机制
;;; ============================================================

(defn execute-with-fallback
  "带降级的工具执行

   参数：
     registry      - ToolRegistry 实例
     primary-tool  - 主工具名称
     primary-args  - 主工具参数
     fallback-tool - 降级工具名称
     fallback-args - 降级工具参数（可选）

   返回：
     ExecutionResult"
  ([registry primary-tool primary-args fallback-tool]
   (execute-with-fallback registry primary-tool primary-args fallback-tool primary-args))
  ([registry primary-tool primary-args fallback-tool fallback-args]
   (log/info "Executing primary tool:" primary-tool)
   (let [start-time (System/currentTimeMillis)
         primary-result (execute-single-tool registry primary-tool primary-args start-time)]
     (if (:success primary-result)
       primary-result
       (do
         (log/warn "Primary tool failed, using fallback:" fallback-tool)
         (let [fallback-result (execute-single-tool registry fallback-tool fallback-args
                                                     (System/currentTimeMillis))]
           (assoc fallback-result
                  :metadata {:primary-tool primary-tool
                             :primary-error (:error primary-result)})))))))

;;; ============================================================
;;; 并行执行
;;; ============================================================

(defn- execute-tool-async
  "异步执行工具

   参数：
     registry   - ToolRegistry 实例
     tool-call  - {:name ... :args ...}
     start-time - 开始时间

   返回：
     future"
  [registry {:keys [name args]} start-time]
  (future (execute-single-tool registry name args start-time)))

(defn execute-parallel
  "并行执行多个工具

   参数：
     registry   - ToolRegistry 实例
     tool-calls - [{:name :tool-name :args {...}} ...]
     config     - 配置 map（可选）

   返回：
     [ExecutionResult ...]"
  ([registry tool-calls]
   (execute-parallel registry tool-calls default-config))
  ([registry tool-calls config]
   (if (empty? tool-calls)
     []
     (let [start-time (System/currentTimeMillis)
           futures (mapv #(execute-tool-async registry % start-time) tool-calls)]
       (mapv deref futures)))))

(defn execute-parallel-with-timeout
  "并行执行多个工具（带全局超时）

   参数：
     registry   - ToolRegistry 实例
     tool-calls - [{:name :tool-name :args {...}} ...]
     timeout-ms - 全局超时时间（毫秒）

   返回：
     [ExecutionResult ...]"
  [registry tool-calls timeout-ms]
  (if (empty? tool-calls)
    []
    (let [start-time (System/currentTimeMillis)
          futures (mapv #(execute-tool-async registry % start-time) tool-calls)
          results (mapv #(deref % timeout-ms ::timeout) futures)]
      (mapv (fn [result]
              (if (= result ::timeout)
                (let [duration (- (System/currentTimeMillis) start-time)]
                  (make-execution-result :unknown false
                                         (str "Global timeout after " timeout-ms " ms")
                                         duration))
                result))
            results))))

;;; ============================================================
;;; 批量执行
;;; ============================================================

(defn execute-batch
  "批量执行工具（分批控制并发）

   参数：
     registry   - ToolRegistry 实例
     tool-calls - [{:name :tool-name :args {...}} ...]
     batch-size - 每批大小（默认 5）
     config     - 执行配置（可选）

   返回：
     [ExecutionResult ...]"
  ([registry tool-calls batch-size]
   (execute-batch registry tool-calls batch-size default-config))
  ([registry tool-calls batch-size config]
   (if (empty? tool-calls)
     []
     (let [batches (partition-all batch-size tool-calls)]
       (vec (mapcat #(execute-parallel registry % config) batches))))))

;;; ============================================================
;;; 流式执行
;;; ============================================================

(defn execute-streaming
  "流式执行工具（返回结果通道）

   参数：
     registry   - ToolRegistry 实例
     tool-calls - [{:name :tool-name :args {...}} ...]
     config     - 配置 map（可选）

   返回：
     core.async 通道（接收 ExecutionResult）"
  ([registry tool-calls]
   (execute-streaming registry tool-calls default-config))
  ([registry tool-calls _config]
   (let [out-channel (async/chan)]
     (async/go-loop [calls tool-calls]
       (if-let [{:keys [name args]} (first calls)]
         (let [result (execute-single-tool registry name args (System/currentTimeMillis))]
           (async/>! out-channel result)
           (recur (rest calls)))
         (async/close! out-channel)))
     out-channel)))

;;; ============================================================
;;; 策略执行
;;; ============================================================

(defn- execute-sequential
  "顺序执行工具

   参数：
     registry   - ToolRegistry 实例
     tool-calls - 工具调用列表

   返回：
     [ExecutionResult ...]"
  [registry tool-calls]
  (mapv (fn [{:keys [name args]}]
          (execute-single-tool registry name args (System/currentTimeMillis)))
        tool-calls))

(defn execute-with-strategy
  "使用策略执行工具

   参数：
     registry   - ToolRegistry 实例
     tool-calls - [{:name :tool-name :args {...}} ...]
     strategy   - 执行策略（:parallel, :sequential, :batch）
     config     - 配置 map（可选）

   返回：
     [ExecutionResult ...]

   策略：
     :parallel   - 并行执行（默认）
     :sequential - 顺序执行
     :batch      - 分批执行（需要 :batch-size）"
  ([registry tool-calls strategy]
   (execute-with-strategy registry tool-calls strategy default-config))
  ([registry tool-calls strategy config]
   (case strategy
     :parallel (execute-parallel registry tool-calls config)
     :sequential (execute-sequential registry tool-calls)
     :batch (execute-batch registry tool-calls (get config :batch-size 5) config)
     (execute-parallel registry tool-calls config))))

;;; ============================================================
;;; 执行统计
;;; ============================================================

(defn calculate-stats
  "计算执行统计信息

   参数：
     results - [ExecutionResult ...]

   返回：
     统计信息 map"
  [results]
  (let [total (count results)
        successes (count (filter :success results))
        durations (map :duration-ms results)
        avg-duration (if (pos? total)
                       (/ (reduce + 0 durations) total)
                       0)]
    {:total total
     :success successes
     :failed (- total successes)
     :avg-duration avg-duration
     :max-duration (apply max 0 durations)
     :min-duration (apply min Long/MAX_VALUE durations)}))

(defn print-execution-summary
  "打印执行摘要

   参数：
     results - [ExecutionResult ...]"
  [results]
  (let [stats (calculate-stats results)]
    (println "\n=== 工具执行摘要 ===")
    (println "总计:" (:total stats))
    (println "成功:" (:success stats))
    (println "失败:" (:failed stats))
    (println (format "平均耗时: %.2f ms" (double (:avg-duration stats))))
    (println "最大耗时:" (:max-duration stats) "ms")
    (println "最小耗时:" (:min-duration stats) "ms")
    (println)))
