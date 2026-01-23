(ns im.ttalk.agent.core.common.result
  "Result Type 模式 - Either Monad 错误处理

   核心理念：
   - 使用类型系统显式处理错误
   - 避免异常抛出，使用返回值表示错误
   - Monad 组合，支持链式调用
   - 纯函数式，无副作用

   ========================================
   基本用法
   ========================================

   (require '[im.ttalk.agent.core.common.result :as r])

   ;; 创建 Success
   (r/success 42)
   ; => #im.ttalk.agent.core.common.result.Success{:value 42}

   ;; 创建 Failure
   (r/failure \"出错了\")
   ; => #im.ttalk.agent.core.common.result.Failure{:error \"出错了\"}

   ;; 检查结果
   (r/success? (r/success 42))
   ; => true

   (r/failure? (r/failure \"错误\"))
   ; => true

   ========================================
   Monad 操作
   ========================================

   ;; fmap - 对成功值应用函数
   (-> (r/success 5)
       (r/fmap inc))
   ; => #success{:value 6}

   (-> (r/failure \"错误\")
       (r/fmap inc))
   ; => #failure{:error \"错误\"}  ; 失败时跳过

   ;; flat-map - 链式 Result 操作
   (-> (r/success 10)
       (r/flat-map #(r/success (* % 2)))
       (r/flat-map #(r/success (str \"结果: \" %))))
   ; => #success{:value \"结果: 20\"}

   ;; 失败时短路
   (-> (r/failure \"第一步失败\")
       (r/flat-map #(r/success (* % 2)))
       (r/flat-map #(r/success (str \"结果: \" %))))
   ; => #failure{:error \"第一步失败\"}

   ========================================
   高阶函数
   ========================================

   ;; with-error-handling - 包装可能失败的操作
   (r/with-error-handling
     (fn [] (/ 10 2)))
   ; => #success{:value 5}

   (r/with-error-handling
     (fn [] (/ 10 0)))
   ; => #failure{:error \"Divide by zero\"}

   ;; lift - 将普通函数提升为 Result 函数
   (def safe-divide
     (r/lift (fn [a b] (/ a b))))

   (safe-divide 10 2)
   ; => #success{:value 5}

   (safe-divide 10 0)
   ; => #failure{:error \"Divide by zero\"}

   ========================================
   组合和转换
   ========================================

   ;; all - 所有 Results 都成功才成功
   (r/all [(r/success 1) (r/success 2) (r/success 3)])
   ; => #success{:value [1 2 3]}

   (r/all [(r/success 1) (r/failure \"错误\")])
   ; => #failure{:error \"错误\"}

   ;; map-all - 对列表应用函数，收集所有结果
   (r/map-all inc [1 2 3])
   ; => #success{:value [2 3 4]}

   ;; result-sequence - 将 Result 列表转换为列表的 Result
   (r/result-sequence [(r/success 1) (r/success 2)])
   ; => #success{:value [1 2]}

   ========================================
   错误处理
   ========================================

   ;; recover - 失败时提供默认值
   (-> (r/failure \"错误\")
       (r/recover 42))
   ; => #success{:value 42}

   ;; recover-with - 失败时执行函数
   (-> (r/failure \"错误\")
       (r/recover-with #(r/success (str \"恢复: \" %))))
   ; => #success{:value \"恢复: 错误\"}

   ;; or-else - 失败时尝试备选方案
   (-> (r/failure \"方案1失败\")
       (r/or-else (r/success \"方案2成功\")))
   ; => #success{:value \"方案2成功\"}

   ========================================
   提取值
   ========================================

   ;; unwrap - 提取值或抛出异常
   (r/unwrap (r/success 42))
   ; => 42

   (r/unwrap (r/failure \"错误\"))
   ; => throws Exception

   ;; unwrap-or - 提供默认值
   (r/unwrap-or (r/success 42) 0)
   ; => 42

   (r/unwrap-or (r/failure \"错误\") 0)
   ; => 0

   ========================================
   重试逻辑
   ========================================

   (r/retry
     (fn [] (risky-operation))
     {:max-retries 3
      :delay-ms 1000
      :backoff :exponential})
   ; => #success{...} 或 #failure{...}")

;; =============================================================================
;; Record 定义
;; =============================================================================

(defrecord Success [value]
  Object
  (toString [this]
    (str "#success{:value " value "}")))

(defrecord Failure [error]
  Object
  (toString [this]
    (str "#failure{:error " error "}")))

;; =============================================================================
;; 构造函数
;; =============================================================================

(defn success
  "创建成功结果

   参数:
   - value: 结果值

   返回: Success record

   示例:
   (success 42)
   (success {:count 10})
   (success \"完成\")"
  [value]
  (->Success value))

(defn failure
  "创建失败结果

   参数:
   - error: 错误信息（字符串、异常或 map）

   返回: Failure record

   示例:
   (ailure \"出错了\")
   (ailure (ex-info \"错误\" {:code 500}))
   (ailure {:type :timeout :message \"超时\"})"
  [error]
  (->Failure error))

(defn from-try
  "从 try-catch 创建 Result

   参数:
   - f: 要执行的函数

   返回: Success 或 Failure

   示例:
   (from-try (fn [] (/ 10 2)))
   ; => #success{:value 5}

   (from-try (fn [] (/ 10 0)))
   ; => #failure{:error ...}"
  [f]
  (try
    (success (f))
    (catch Exception e
      (failure e))))

;; =============================================================================
;; 类型检查
;; =============================================================================

(defn success?
  "检查是否为成功结果

   参数:
   - result: Result 实例

   返回: boolean

   示例:
   (success? (success 42))
   ; => true"
  [result]
  (instance? Success result))

(defn failure?
  "检查是否为失败结果

   参数:
   - result: Result 实例

   返回: boolean

   示例:
   (ailure? (ailure \"错误\"))
   ; => true"
  [result]
  (instance? Failure result))

;; =============================================================================
;; Functor 操作
;; =============================================================================

(defn fmap
  "对成功值应用函数（Functor map）

   如果是 Success，应用函数并返回新的 Success
   如果是 Failure，跳过并返回原 Failure

   参数:
   - result: Result 实例
   - f: 转换函数

   返回: 新的 Result

   示例:
   (fmap (success 5) inc)
   ; => #success{:value 6}

   (fmap (ailure \"错误\") inc)
   ; => #ailure{:error \"错误\"}"
  [result f]
  (if (success? result)
    (success (f (:value result)))
    result))

(defn map-error
  "对错误值应用函数

   如果是 Failure，应用函数并返回新的 Failure
   如果是 Success，跳过并返回原 Success

   参数:
   - result: Result 实例
   - f: 转换函数

   返回: 新的 Result

   示例:
   (map-error (ailure \"error\") str/upper-case)
   ; => #ailure{:error \"ERROR\"}"
  [result f]
  (if (failure? result)
    (failure (f (:error result)))
    result))

;; =============================================================================
;; Monad 操作
;; =============================================================================

(defn flat-map
  "链式 Result 操作（Monad bind）

   如果是 Success，应用函数（应返回 Result）
   如果是 Failure，跳过并返回原 Failure

   参数:
   - result: Result 实例
   - f: 返回 Result 的函数

   返回: 新的 Result

   示例:
   (-> (success 10)
       (flat-map #(success (* % 2)))
       (flat-map #(success (str \"结果: \" %))))
   ; => #success{:value \"结果: 20\"}

   短路示例:
   (-> (ailure \"失败\")
       (flat-map #(success (* % 2))))
   ; => #ailure{:error \"失败\"}"
  [result f]
  (if (success? result)
    (f (:value result))
    result))

(defn compose
  "组合多个返回 Result 的函数（从左到右）

   参数:
   - fs: 函数列表

   返回: 组合后的函数

   示例:
   (def validate-pos (fn [x] (if (pos? x) (success x) (ailure \"必须为正\"))))
   (def validate-even (fn [x] (if (even? x) (success x) (ailure \"必须为偶数\"))))

   (def validate (compose validate-pos validate-even))

   (validate 4)
   ; => #success{:value 4}

   (validate 3)
   ; => #ailure{:error \"必须为偶数\"}"
  [& fs]
  (fn [x]
    (reduce (fn [result f]
              (if (success? result)
                (f (:value result))
                (reduced result)))
            (success x)
            fs)))

;; =============================================================================
;; 高阶函数
;; =============================================================================

(defn lift
  "将普通函数提升为 Result 函数

   参数:
   - f: 普通函数

   返回: 返回 Result 的函数

   示例:
   (def safe-divide (lift /))

   (safe-divide 10 2)
   ; => #success{:value 5}

   (safe-divide 10 0)
   ; => #ailure{:error \"Divide by zero\"}"
  [f]
  (fn [& args]
    (try
      (success (apply f args))
      (catch Exception e
        (failure e)))))

(defn with-error-handling
  "包装函数调用为 Result（别名）

   参数:
   - f: 要执行的函数
   - args: 参数列表

   返回: Success 或 Failure

   示例:
   (with-error-handling (fn [] (/ 10 2)))
   ; => #success{:value 5}"
  [f & args]
  (from-try (apply f args)))

;; =============================================================================
;; 组合操作
;; =============================================================================

(defn all
  "组合多个 Result（所有成功才成功）

   参数:
   - results: Result 列表

   返回: Success（包含值列表）或第一个 Failure

   示例:
   (all [(success 1) (success 2) (success 3)])
   ; => #success{:value [1 2 3]}

   (all [(success 1) (ailure \"错误\")])
   ; => #ailure{:error \"错误\"}"
  [results]
  (reduce (fn [acc result]
            (if (and (success? acc) (success? result))
              (success (conj (:value acc) (:value result)))
              (if (failure? result)
                (reduced result)
                acc)))
          (success [])
          results))

(defn result-sequence
  "将 Result 列表转换为列表的 Result

   类似于 all，但接受列表而非变参

   参数:
   - results: Result 列表

   返回: Success（包含值列表）或第一个 Failure

   示例:
   (result-sequence [(success 1) (success 2)])
   ; => #success{:value [1 2]}"
  [results]
  (all results))

(defn map-all
  "对列表应用函数，收集所有 Result

   参数:
   - f: 返回 Result 的函数
   - coll: 集合

   返回: Success（包含结果列表）或第一个 Failure

   示例:
   (map-all (fn [x] (success (* x 2))) [1 2 3])
   ; => #success{:value [2 4 6]}"
  [f coll]
  (result-sequence (map f coll)))

;; =============================================================================
;; 错误恢复
;; =============================================================================

(defn recover
  "失败时提供默认值

   参数:
   - result: Result 实例
   - default-value: 默认值

   返回: Success（包含默认值）或原 Success

   示例:
   (recover (ailure \"错误\") 42)
   ; => #success{:value 42}"
  [result default-value]
  (if (failure? result)
    (success default-value)
    result))

(defn recover-with
  "失败时执行函数

   参数:
   - result: Result 实例
   - f: 处理函数（接收 error，返回 Result）

   返回: f 的结果或原 Success

   示例:
   (recover-with (ailure \"错误\")
     (fn [e] (success (str \"恢复: \" e))))
   ; => #success{:value \"恢复: 错误\"}"
  [result f]
  (if (failure? result)
    (f (:error result))
    result))

(defn or-else
  "失败时尝试备选 Result

   参数:
   - result: Result 实例
   - alternative: 备选 Result（ thunk）

   返回: 原成功或备选结果

   示例:
   (or-else (ailure \"失败1\") (fn [] (ailure \"失败2\")))
   ; => #ailure{:error \"失败2\"}

   (or-else (ailure \"失败\") (fn [] (success \"成功\")))
   ; => #success{:value \"成功\"}"
  [result alternative]
  (if (success? result)
    result
    (alternative)))

;; =============================================================================
;; 提取值
;; =============================================================================

(defn unwrap
  "提取值或抛出异常

   如果是 Success，返回值
   如果是 Failure，抛出异常

   参数:
   - result: Result 实例

   返回: 值或抛出异常

   示例:
   (unwrap (success 42))
   ; => 42

   (unwrap (ailure \"错误\"))
   ; => throws Exception"
  [result]
  (if (success? result)
    (:value result)
    (throw (if (instance? Exception (:error result))
              (:error result)
              (ex-info (str "Failure: " (:error result))
                       {:error (:error result)})))))

(defn unwrap-or
  "提取值或返回默认值

   参数:
   - result: Result 实例
   - default-value: 默认值

   返回: 值或默认值

   示例:
   (unwrap-or (success 42) 0)
   ; => 42

   (unwrap-or (ailure \"错误\") 0)
   ; => 0"
  [result default-value]
  (if (success? result)
    (:value result)
    default-value))

(defn unwrap-or-else
  "提取值或执行函数

   参数:
   - result: Result 实例
   - f: 返回默认值的函数

   返回: 值或 f 的结果

   示例:
   (unwrap-or-else (ailure \"错误\") (fn [] (println \"使用默认值\") 42))
   ; 使用默认值
   ; => 42"
  [result f]
  (if (success? result)
    (:value result)
    (f)))

;; =============================================================================
;; 重试逻辑
;; =============================================================================

(defn- calculate-delay
  "计算重试延迟（私有）"
  [attempt delay-ms backoff]
  (case backoff
    :fixed delay-ms
    :linear (* delay-ms (inc attempt))
    :exponential (* delay-ms (Math/pow 2 attempt))
    delay-ms))  ; 默认值

(defn retry
  "带退避的重试逻辑

   参数:
   - f: 返回 Result 的函数
   - opts: 选项
     :max-retries - 最大重试次数（默认 3）
     :delay-ms - 基础延迟（默认 1000）
     :backoff - 退避策略（:fixed, :linear, :exponential，默认 :fixed）
     :should-retry? - 判断是否重试的函数（默认重试所有失败）

   返回: Success 或最后的 Failure

   示例:
   (retry (fn [] (risky-op))
     {:max-retries 3
      :delay-ms 1000
      :backoff :exponential})"
  [f & {:keys [max-retries delay-ms backoff should-retry?]
         :or {max-retries 3
              delay-ms 1000
              backoff :fixed
              should-retry? (constantly true)}}]
  (loop [attempt 0
         last-result nil]
    (if (>= attempt max-retries)
      last-result
      (let [result (try
                     (f)
                     (catch Exception e
                       (failure e)))]
        (if (or (success? result)
                (not (should-retry? (:error result) attempt)))
          result
          (do
            (Thread/sleep (long (calculate-delay attempt delay-ms backoff)))
            (recur (inc attempt) result)))))))

;; =============================================================================
;; 快速失败宏
;; =============================================================================

;; 注意：<?? 宏需要在支持 return 的上下文中使用
;; 实际实现中，这通常与 monad 或 async 框架配合使用
;; 这里暂时注释掉，因为标准 Clojure 不支持 return

(comment
  (defmacro <??
    "解包 Result 或提前返回

     如果 result 是 Success，返回值
     如果 result 是 Failure，从包含函数返回 Failure

     参数:
     - result: Result 表达式

     返回: 值或提前返回

     示例:
     (defn validate-and-process [input]
       (let [parsed (?? (parse-input input))
             validated (?? (validate parsed))]
         (success (process validated))))"
    [result]
    `(let [r# ~result]
       (if (success? r#)
         (:value r#)
         (return r#))))
  )

;; =============================================================================
;; 便捷宏
;; =============================================================================

(defmacro result-let
  "Result 专用的 let 绑定（monadic bind）

   如果任何绑定失败，整个表达式失败

   参数:
   - bindings: [symbol result-expr ...]
   - body: 成功时执行的表达式

   返回: Success 或第一个 Failure

   示例:
   (result-let [x (success 10)
                y (success 20)
                z (success (* x y))]
     (success (str \"结果: \" z)))
   ; => #success{:value \"结果: 200\"}

   (result-let [x (success 10)
                y (ailure \"错误\")]
     (success (+ x y)))
   ; => #ailure{:error \"错误\"}"
  [bindings & body]
  (if (empty? bindings)
    `(do ~@body)
    `(let [result# ~(second bindings)]
       (if (success? result#)
         (let [~(first bindings) (:value result#)]
           (result-let ~(drop 2 bindings) ~@body))
         result#))))
