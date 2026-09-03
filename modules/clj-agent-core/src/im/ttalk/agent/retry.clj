(ns im.ttalk.agent.retry
  "通用重试：指数退避 + 满抖动，判据取自 canonical error

   **落点在 ChatModel，位于整个 filter 栈之下**（与 beamai `beamai_chat_model`
   同一取舍）。这不是随手放的——filter 看到的必须是「一次逻辑调用」：

     :chat filter（memory / 计时 / 记账）
       └ ChatModel.call ──┐
                          ├ 尝试 1 ✗ → 退避 → 尝试 2 ✓   ← 重试在这里面
                          └ 返回一个响应

   若重试在 filter 链**之上**（或做成一个 filter），一次网络抖动就会让
   memory-filter 把同一轮 delta 写两遍、让计时 filter 记出两条记录。放在链下，
   重试重入碰不到任何 filter，`around_chat` 的副作用每轮只发生一次。
   要观测每次**真实尝试**，用 `:on-retry` 回调——它是为此存在的，不是日志糖。

   **流式不重试**：token 已经投递给 sink，重跑会让下游看到重复内容。要容错
   请在 turn 层重跑整轮（`:turn` / `:iteration` 链都能递归重入）。故本 ns 只
   服务 `ChatModel.call`，`stream` 路径不经过它。

   **判据是 canonical error 的 `:retryable?`**，不是 HTTP 状态码——那是
   provider 边界的事（见 `provider.http.client/response->error`，它顺带把
   `Retry-After` 解析成 `:retry-after-ms` 塞进错误 map，core 只读那个数）。
   core 因此对 HTTP 一无所知；本 ns **无外部依赖**，只读 canonical error 里的两个键
   （`:retryable?` / `:retry-after-ms`）。（异步版 `run-async` 用到 core 内的链结果
   组合子 `im.ttalk.agent.filter`，仍无第三方依赖。）

   **同步 / 异步两个入口共用一套判据**：`run` 与 `run-async` 的重试次数、
   `retryable-ex?`、退避曲线（含 `Retry-After` 与满抖动）都走同一批函数，
   只差「等待」的实现——同步 `Thread/sleep`，异步 `CompletableFuture/delayedExecutor`
   （绝不能在异步路径上 sleep：那会睡掉传输层 executor 的线程）。

   参数三级取值：**单次 opts > provider config > 框架默认**；`:max-retries 0`
   即关闭（等价于直接调用，零开销）。"
  (:require [im.ttalk.agent.async :as async]
            [im.ttalk.agent.filter :as flt]))

(set! *warn-on-reflection* true)

(def default-opts
  "框架默认重试配置。

   - :max-retries  最大重试次数（**不含**首次调用）；0 = 关闭
   - :base-delay   首次退避基准（毫秒）
   - :multiplier   退避倍率
   - :max-delay    单次退避上限（毫秒）——`Retry-After` 也受它约束
   - :respect-retry-after? 是否优先采用错误里的 :retry-after-ms"
  {:max-retries 2
   :base-delay 1000
   :multiplier 2.0
   :max-delay 30000
   :respect-retry-after? true})

(defn resolve-opts
  "三级合并重试配置：框架默认 < provider/model config 的 :retry < 单次 opts 的 :retry。

   每一级都接受 nil（跳过）/ true（用上一级结果）/ false（关闭，等价
   `{:max-retries 0}`）/ map（作为配置合并）。

   返回: 合并后的配置 map（`:max-retries` 为 0 表示不重试）"
  ([config] (resolve-opts config nil))
  ([config opts]
   (let [level (fn [acc r]
                 (cond
                   (nil? r)   acc
                   (true? r)  acc
                   (false? r) (assoc acc :max-retries 0)
                   (map? r)   (merge acc r)
                   :else (throw (ex-info (str ":retry 必须是 nil / true / false / map，实为 " (pr-str r))
                                         {:retry r}))))]
     (-> default-opts
         (level (:retry config))
         (level (:retry opts))))))

(defn retryable-ex?
  "异常是否可重试——**唯一判据是 canonical error 的 `:retryable?`**。

   provider 边界已经把 401 / 400 这类判成不可重试、把 429 / 5xx / 连接失败判成
   可重试（见 `model.error/http-response->error`），这里不再二次猜测：没有
   `:retryable?` 的异常（框架自身的 bug、用户工具抛的）一律**不重试**。
   猜错的代价不对称——对一个本质不可重试的错误反复打 API 是在烧钱和触发风控。"
  [^Throwable e]
  (boolean (:retryable? (ex-data e))))

(defn compute-backoff
  "第 attempt 次重试的退避毫秒（含满抖动）。attempt 从 0 开始。

   基础退避 = min(base-delay × multiplier^attempt, max-delay)，再在
   [0, 基础退避] 上取满抖动——抖动是为了避免一批并发请求同时醒来把服务端
   再打挂一次（重试风暴）。

   rand-fn 可注入，测试用。"
  ([attempt opts] (compute-backoff attempt opts rand))
  ([attempt {:keys [base-delay multiplier max-delay]
             :or {base-delay 1000 multiplier 2.0 max-delay 30000}}
    rand-fn]
   (let [raw    (* base-delay (Math/pow multiplier attempt))
         capped (min raw (double max-delay))]
     (long (* (rand-fn) capped)))))

(defn- delay-for
  "本次重试该睡多久：服务端给了 `Retry-After` 就听它的，否则退避计算。

   **`Retry-After` 必须受 `:max-delay` 约束**——服务端回 `Retry-After: 3600`
   时若照单全收，同步线程会直接睡一小时，`:max-delay` 形同虚设。"
  [^Throwable e attempt {:keys [respect-retry-after? max-delay] :as opts} rand-fn]
  (let [ra (when respect-retry-after? (:retry-after-ms (ex-data e)))]
    (if ra
      (long (min ra (double max-delay)))
      (compute-backoff attempt opts rand-fn))))

(defn run
  "在重试保护下执行 `f`（无参函数）。

   参数:
   - f:    无参函数；失败时**抛** ex-info（data 即 canonical error）
   - opts: `resolve-opts` 的结果，另可带
     - :on-retry (fn [{:keys [attempt delay-ms error exception]}])
                 每次退避**之前**调用，用来观测真实尝试次数
     - :rand-fn  随机数函数（测试用）
     - :sleep-fn (fn [ms])（测试用；缺省 Thread/sleep）

   返回: `f` 的返回值

   抛出: 最后一次失败的异常——**原样重抛**，不包一层。调用方拿到的仍是
   canonical error，`:retryable?` / `:status` 全程不丢（README 的错误模型
   承诺「各边界可单向转换」，重试层没有资格改写它）。"
  [f {:keys [max-retries on-retry rand-fn sleep-fn]
      :or {rand-fn rand}
      :as opts}]
  (let [sleep! (or sleep-fn #(when (pos? %) (Thread/sleep (long %))))
        maxr   (long (or max-retries 0))]
    (loop [attempt 0]
      (let [result (try
                     {:ok (f)}
                     (catch Throwable t
                       (if (and (< attempt maxr) (retryable-ex? t))
                         {:retry t}
                         (throw t))))]
        (if-let [t (:retry result)]
          (let [delay-ms (delay-for t attempt opts rand-fn)]
            (when on-retry
              (on-retry {:attempt   (inc attempt)
                         :delay-ms  delay-ms
                         :error     (ex-data t)
                         :exception t}))
            (sleep! delay-ms)
            (recur (inc attempt)))
          (:ok result))))))

(defn run-async
  "`run` 的异步孪生：`f` 是无参函数，返回**链结果**（deferred 或普通值）。

   与 `run` 的差别只有两处，其余（判据 `retryable-ex?`、次数、退避曲线含
   `Retry-After` 与满抖动、`:on-retry` 回调时机）**逐字共用**：

   1. 失败经 deferred 的 error channel 传播，用 `flt/fcatch` 接（同步抛出也接得住）；
   2. 退避不 `Thread/sleep`——用 `async/delayed`（`CompletableFuture/delayedExecutor`）。注入了 `:sleep-fn` 时仍走它（测试用：立即返回、只记账）。

   返回: deferred（或普通值——`f` 同步返回且没触发重试时就是普通值，形态保持见
   filter-chain-design.md §2.6.4 契约 C1）。
   抛出/落 error channel: 最后一次失败的异常，**原样**，不包一层。

   递归深度 ≤ max-retries（缺省 2），不吃栈。"
  [f {:keys [max-retries on-retry rand-fn sleep-fn]
      :or {rand-fn rand}
      :as opts}]
  (let [maxr (long (or max-retries 0))
        wait (fn [ms] (if sleep-fn (do (sleep-fn ms) nil) (async/delayed ms)))]
    (letfn [(step [attempt]
              (flt/fcatch (f)
                (fn [t]
                  (if (and (< attempt maxr) (retryable-ex? t))
                    (let [delay-ms (delay-for t attempt opts rand-fn)]
                      (when on-retry
                        (on-retry {:attempt   (inc attempt)
                                   :delay-ms  delay-ms
                                   :error     (ex-data t)
                                   :exception t}))
                      (flt/fbind (wait delay-ms) (fn [_] (step (inc attempt)))))
                    (throw t)))))]
      (step 0))))

(defn wrap
  "把 `f` 包成带重试的同名函数。config/opts 语义同 `resolve-opts`。"
  [f config opts]
  (fn [& args] (run #(apply f args) (merge (resolve-opts config opts)
                                           (select-keys opts [:on-retry :rand-fn :sleep-fn])))))
