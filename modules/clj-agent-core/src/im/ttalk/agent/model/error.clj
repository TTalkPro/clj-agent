(ns im.ttalk.agent.model.error
  "LLM 错误处理模块

   提供统一的错误类型、分类和处理机制。

   错误类型：
   - :network-error    网络连接错误（可重试）
   - :timeout-error    请求超时（可重试）
   - :rate-limit-error 速率限制（可重试）
   - :auth-error       认证失败（不可重试）
   - :validation-error 参数验证失败（不可重试）
   - :parse-error      响应解析失败（不可重试）
   - :provider-error   Provider 特定错误

   使用示例：

   (require '[im.ttalk.agent.model.error :as errors])

   ;; 创建错误
   (errors/error :timeout-error \"请求超时\")

   ;; 检查是否可重试（直接关键字访问）
   (:retryable? err) ; => true

   ;; 安全执行
   (errors/with-error-handling #(api-call))"
  (:require [clojure.string :as str])
  (:import [java.io IOException]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 错误分类
;;; ============================================================

(def ^:private retryable-types
  "可重试的错误类型"
  #{:network-error :timeout-error :rate-limit-error :provider-error})

(def ^:private non-retryable-types
  "不可重试的错误类型"
  #{:auth-error :validation-error :parse-error})

;;; ============================================================
;;; 错误创建
;;; ============================================================

(defn error
  "创建错误 map

   参数：
   - type:    错误类型（关键字）
   - message: 错误消息
   - opts:    可选参数 map
     - :cause      原因异常
     - :status     HTTP 状态码
     - :provider   Provider 名称
     - :context    上下文信息

   返回：
   错误 map {:type :xxx :message \"...\" :retryable? bool}

   示例：
   (error :timeout-error \"请求超时\" {:status 504})"
  ([type message]
   (error type message nil))
  ([type message opts]
   (let [retryable? (cond
                      (contains? opts :retryable?) (:retryable? opts)
                      (contains? retryable-types type) true
                      (contains? non-retryable-types type) false
                      ;; 未知错误类型保守视作不可重试：「不确定」当「可重试」会让上层
                      ;; 对本质不可重试的错误反复打 API。需重试请显式传 :retryable? true。
                      :else false)]
     (cond-> {:type type
              :message message
              :retryable? retryable?}
       (:cause opts)    (assoc :cause (:cause opts))
       (:status opts)   (assoc :status (:status opts))
       (:provider opts) (assoc :provider (:provider opts))
       (:context opts)  (assoc :context (:context opts))))))

;;; ============================================================
;;; 工具错误分类（S2：屏障策略路由，见 docs/agent-loop-concurrency-design.md §5）
;;; ============================================================

(defn classify-exception
  "把工具执行抛出的异常分类为故障类别，决定屏障处的路由：

   - :semantic    （缺省）模型造成、只有模型能修 → 序列化为结果回给模型
   - :transient   重试同一调用有意义（超时/限流/网络抖动）→ 工具级自动重试
                  （仅当工具声明 :retry；幂等前提）
   - :environment 模型修不了、重试无用（认证失效/配额/磁盘满）→ 屏障处暂停等人

   判定顺序：
   1. ex-data 显式 :error-class（工具作者标注，最高优先级）
   2. canonical error（D5 词汇）：:retryable? true → :transient；
      :auth-error → :environment；其余 → :semantic
   3. 常见网络异常（SocketTimeoutException/ConnectException/HttpTimeoutException）
      → :transient
   4. 其余 → :semantic"
  [e]
  (let [data (ex-data e)]
    (cond
      (:error-class data) (:error-class data)
      (contains? data :retryable?) (cond
                                     (:retryable? data)          :transient
                                     (= :auth-error (:type data)) :environment
                                     :else                        :semantic)
      (or (instance? java.net.SocketTimeoutException e)
          (instance? java.net.ConnectException e)
          (instance? java.net.http.HttpTimeoutException e)) :transient
      :else :semantic)))

(defn fatal-throwable?
  "该 Throwable 是否**不该被收敛为工具错误**，必须原样上抛。

   背景：工具执行的各层此前一律 `catch Exception`，于是 `Error` 全部逃逸——
   一个工具的深递归 `StackOverflowError` 会打死整个 agent 循环，而分层错误路由
   （:semantic/:transient/:environment）的全部意义就是「一个工具坏了不牵连别人」。
   但也不能无差别 `catch Throwable`：吞掉 OOM 只会掩盖真因，且收敛动作本身还要
   分配内存，多半当场再炸。故要一条判据。

   **致命（放行）**：`VirtualMachineError` 中除 StackOverflowError 之外的那些
   （`OutOfMemoryError` / `InternalError` / `UnknownError`——JVM 自身已坏，
   继续跑工具没有意义）、`ThreadDeath`。

   **不致命（收敛）**：其余一切，尤其是
   - `StackOverflowError`——**工具自己的递归 bug**，栈一退就恢复，正是最该被
     收敛成「这一个工具失败」的那类。这是我们与 Scala `NonFatal` 的**有意分歧**：
     它把整个 `VirtualMachineError` 划为致命，但那是通用库的保守取舍；这里的
     Throwable 来自**用户工具函数体**，栈溢出是它最常见的自伤方式。
   - `AssertionError`（工具里的 `assert`）、`LinkageError` / `NoClassDefFoundError`
     （该工具缺可选依赖）——都只说明**这个工具**不可用，不该牵连整轮。

   收敛后经 `classify-exception` 归类（缺省 :semantic：工具 bug 重试无意义）。"
   [^Throwable t]
   (or (and (instance? VirtualMachineError t)
            (not (instance? StackOverflowError t)))
       (instance? ThreadDeath t)))

(defn contain-throwable
  "catch-Throwable 三元组（fatal 检查 / nil-message 回退 / 分类）的统一入口。

   致命的（OOM 等）原样上抛（`fatal-throwable?` 判据）；非致命的返回
   `{:message m :class c}`——调用方据此组装自己的错误形状（terminal map /
   success-false map / invoke-one 的 :value+:error）。

   **DRY 4 处手抄**：`tool/invoke`、`kernel/invoke-tool` 两个 terminal、
   `react/invoke-one` 此前各自重复 fatal-check + nil-message + classify-exception
   三步，三元组漂移即出 bug。"
  [^Throwable t]
  (when (fatal-throwable? t) (throw t))
  (let [m (or (not-empty (.getMessage t)) (.getName (class t)))]
    {:message m :class (classify-exception t)}))

;;; ============================================================
;;; 错误判断
;;; ============================================================

(defn error?
  "检查是否为错误 map

   参数：
   - x: 任意值

   返回：
   boolean"
  [x]
  (and (map? x)
       (contains? x :type)
       (contains? x :message)))

(defmacro ^:private deferrpred
  "生成错误类型谓词函数

   每个谓词检查: error? + (类型匹配 或 状态码匹配)"
  [fn-name doc-str type-check status-check]
  `(defn ~fn-name
     ~doc-str
     [~'err]
     (and (error? ~'err)
          (or ~type-check ~status-check))))

(deferrpred http-error?
  "检查是否为 HTTP 相关错误（网络/超时/有状态码）"
  (#{:network-error :timeout-error} (:type err))
  (contains? err :status))

(deferrpred auth-error?
  "检查是否为认证错误（401/403 或 :auth-error 类型）"
  (= :auth-error (:type err))
  (#{401 403} (:status err)))

(deferrpred rate-limit-error?
  "检查是否为速率限制错误（429 或 :rate-limit-error 类型）"
  (= :rate-limit-error (:type err))
  (= 429 (:status err)))

;;; ============================================================
;;; 异常转换
;;; ============================================================

(defn exception->error
  "将异常转换为错误 map

   参数：
   - e:       异常对象
   - context: 上下文信息（可选）

   返回：
   错误 map

   示例：
   (exception->error (IOException. \"连接失败\"))"
  ([e]
   (exception->error e nil))
  ([e context]
   (let [msg (or (.getMessage ^Throwable e) (str (class e)))
         base-opts (cond-> {:cause e}
                     context (assoc :context context))]
     (cond
       ;; ★ 幂等透传：ex-info 且 data 本身已是 canonical error（如 provider 用
       ;; errors/throw! 抛出）—— 直接取出，保留其已算好的 :type/:status/:retryable?，
       ;; 不再笼统归为 :provider-error（否则会把不可重试的 401 误标成可重试）。
       ;; 仅在外层显式传 context 时叠加，避免覆盖已有 :context。
       (and (instance? clojure.lang.ExceptionInfo e)
            (error? (ex-data e)))
       (cond-> (ex-data e)
         context (assoc :context context))

       ;; IO 异常 -> 网络错误
       (instance? IOException e)
       (error :network-error msg base-opts)

       ;; 超时异常
       (or (instance? java.util.concurrent.TimeoutException e)
           (str/includes? (str (class e)) "Timeout"))
       (error :timeout-error msg base-opts)

       ;; 不支持的能力（如 provider 流式未实现）-> 参数/能力类，明确不可重试
       (instance? UnsupportedOperationException e)
       (error :validation-error msg (assoc base-opts :retryable? false))

       ;; 其他异常 -> Provider 错误
       :else
       (error :provider-error msg
              (assoc base-opts :exception-type (str (class e))))))))

(defn http-response->error
  "将 HTTP 错误响应转换为错误 map

   参数：
   - response: HTTP 响应 {:status n :body ...}
   - provider: Provider 名称

   返回：
   错误 map

   示例：
   (http-response->error {:status 401 :body \"Unauthorized\"} :openai)"
  [response provider]
  (let [status (:status response)
        body (:body response)
        ;; message 必须是字符串：OpenAI 风格错误体是 {:error {:message ".." :type ".."}}（嵌套 map），
        ;; 直接取 (:error body) 会得到 map，throw! 的 ex-info 要求 String 会 ClassCastException。
        message (cond
                  (and (map? body) (map? (:error body)))
                  (or (get-in body [:error :message]) (pr-str (:error body)))
                  (and (map? body) (or (:error body) (:message body)))
                  (str (or (:error body) (:message body)))
                  (string? body) body
                  :else (str "HTTP " status " error"))
        opts {:status status :provider provider}]
    (cond
      (#{401 403} status) (error :auth-error message opts)
      (= 429 status)      (error :rate-limit-error message opts)
      (>= status 500)     (error :provider-error message (assoc opts :retryable? true))
      (>= status 400)     (error :validation-error message opts)
      :else               (error :provider-error message opts))))

;;; ============================================================
;;; 异常抛出
;;; ============================================================

(defn throw!
  "将错误转换为异常并抛出

   参数：
   - err: 错误 map

   抛出：
   ExceptionInfo"
  [err]
  ;; ex-info 要求 message 为 String；canonical error 的 :message 理应是字符串，
  ;; 但保险起见强制 str（避免上游构造失误导致 ClassCastException 掩盖真实错误）。
  (throw (ex-info (str (:message err)) err)))

;;; ============================================================
;;; 错误格式化
;;; ============================================================

(defn format-error
  "格式化错误为可读字符串

   参数：
   - err: 错误 map

   返回：
   格式化的字符串

   示例：
   (format-error {:type :timeout-error :message \"请求超时\" :status 504})
   ; => \"[TIMEOUT-ERROR] 请求超时 (HTTP 504)\""
  [err]
  (str "[" (str/upper-case (name (:type err))) "] "
       (:message err)
       (when-let [status (:status err)]
         (str " (HTTP " status ")"))
       (when-let [provider (:provider err)]
         (str " [" (name provider) "]"))))

;;; ============================================================
;;; 错误处理组合器
;;; ============================================================

(defn with-error-handling
  "执行函数并捕获异常转换为错误

   参数：
   - f:       要执行的函数
   - context: 上下文信息（可选）

   返回：
   [:ok result] 或 [:error error-map]

   示例：
   (with-error-handling #(api-call) {:operation \"call-llm\"})"
  ([f]
   (with-error-handling f nil))
  ([f context]
   (try
     [:ok (f)]
     (catch Exception e
       [:error (exception->error e context)]))))

