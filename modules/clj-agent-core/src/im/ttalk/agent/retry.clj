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

   **流式只在首个 token 之前重试**：token 已经投递给 sink 之后重跑会让下游看到
   重复内容，那之后的失败原样抛；要容错请在 turn 层重跑整轮。首个 token 之前
   （建连失败、503、首字节超时）没有任何东西出门，重发安全——`stream-call` 用
   `:retry-gate` + 独立预算 `:stream-retries` 表达这一点（designs/provider-reliability.md §2）。

   **空补全**（HTTP 200 但无有效内容）走独立预算 `:empty-retries`，不占 `:max-retries`。

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
            [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.model.error :as errors]))

(set! *warn-on-reflection* true)

(def default-opts
  "框架默认重试配置。

   - :max-retries  最大重试次数（**不含**首次调用）；0 = 关闭
   - :base-delay   首次退避基准（毫秒）
   - :multiplier   退避倍率
   - :max-delay    单次退避上限（毫秒）——`Retry-After` 也受它约束
   - :respect-retry-after? 是否优先采用错误里的 :retry-after-ms
   - :empty-retries 空补全的独立预算（HTTP 200 但无有效内容时补打几次；0 = 不补打，空响应原样返回）
   - :empty-exhausted 空补全预算用完仍空时怎么办：`:return`（缺省，把空响应原样交回——与过去一致）
                     / `:throw`（抛 :empty-completion-error，交给主备切换 / 上层处理）
   - :stream-retries 流式「首个 token 之前」失败的独立预算（token 出门后一律不重试）

   三格预算各自记账、互不挪用（designs/provider-reliability.md §2）。

   判据（缺省不给 ⇒ 只看 canonical error 的 `:retryable?`，见 `retry-error?`）：
   - :skip-types   canonical error 的 `:type` 集合，命中一律不重试（如 `#{:rate-limit-error}`：
                   国内几家把限流与欠费都回 429，重试等于替用户再花钱）
   - :retryable?   (fn [error-map] bool)，**覆盖**缺省判据；`:skip-types` 仍先生效"
  {:max-retries 2
   :base-delay 1000
   :multiplier 2.0
   :max-delay 30000
   :respect-retry-after? true
   :empty-retries 1
   :empty-exhausted :return
   :stream-retries 2})

(defn resolve-opts
  "三级合并重试配置：框架默认 < provider/model config 的 :retry < 单次 opts 的 :retry。

   每一级都接受 nil（跳过）/ true（用上一级结果）/ false（关闭，等价
   `{:max-retries 0 :empty-retries 0 :stream-retries 0}`）/ map（作为配置合并）。

   返回: 合并后的配置 map（`:max-retries` 为 0 表示不重试）"
  ([config] (resolve-opts config nil))
  ([config opts]
   (let [level (fn [acc r]
                 (cond
                   (nil? r)   acc
                   (true? r)  acc
                   (false? r) (assoc acc :max-retries 0 :empty-retries 0 :stream-retries 0)
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

(defn retry-error?
  "canonical error（map，可为 nil）按重试配置该不该重试：

   1. 类型在 `:skip-types` 里 ⇒ 不重试；
   2. 给了 `:retryable?`（fn [error-map] bool）⇒ 听它的；
   3. 否则看错误自带的 `:retryable?`（同 `retryable-ex?`）。

   配置里两格都不给 ⇒ 与 `retryable-ex?` 逐字同义。ChatModel 的三格重试、provider 流式建链重试、
   embeddings 的 opt-in 重试共用这一个判据（宿主不必为了改一条判据去包 provider）。"
  [err {:keys [skip-types retryable?]}]
  (let [err (or err {})]
    (and (not (contains? (set skip-types) (:type err)))
         (boolean (if retryable? (retryable? err) (:retryable? err))))))

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

(defn- empty-error
  "空补全超预算时抛的 canonical error（designs/provider-reliability.md §2）。"
  [provider calls]
  (errors/error :empty-completion-error
                (str "模型返回空补全（HTTP 200 但无有效内容，已尝试 " calls " 次）")
                (cond-> {} provider (assoc :provider provider))))

(defn- http-retryable?
  "按 `:max-retries` 那一格能不能再打。**空补全一律不进这一格**——它即便 :retryable? true，
   也只归 `:empty-retries` 管；否则稳定吐空的模型会被放大成「max-retries × empty-retries」次。
   判据见 `retry-error?`（opts 的 `:skip-types` / `:retryable?`）。"
  [^Throwable t opts]
  (let [data (ex-data t)]
    (and (not= :empty-completion-error (:type data))
         (retry-error? data opts))))

(defn- attempt-summary [^Throwable t]
  (-> (or (ex-data t) {:message (or (.getMessage t) (.getName (class t)))})
      (dissoc :cause :attempts)))

(defn- give-up
  "放弃：试过不止一次 ⇒ 异常 ex-data 追加 `:attempts`（每次失败的 canonical error，去掉 :cause）。
   只试了一次 ⇒ 原异常原样返回（零改写）。"
  ^Throwable [^Throwable t trail]
  (if (> (count trail) 1)
    (errors/with-ex-data t {:attempts trail})
    t))

(defn- annotate [v calls record?]
  (if (and record? (map? v)) (assoc v :attempts calls) v))

(defn run
  "在重试保护下执行 `f`（无参函数）。

   参数:
   - f:    无参函数；失败时**抛** ex-info（data 即 canonical error）
   - opts: `resolve-opts` 的结果，另可带
     - :on-retry (fn [{:keys [attempt delay-ms error exception kind]}])
                 每次重试**之前**调用，用来观测真实尝试次数。`:kind` ∈
                 `:error`（按 :max-retries 退避重试）/ `:empty-completion`（空补全补打）/
                 opts 的 `:retry-kind`（流式路径传 `:stream`）
     - :rand-fn  随机数函数（测试用）
     - :sleep-fn (fn [ms])（测试用；缺省 Thread/sleep）
     以下为 designs/provider-reliability.md §2 新增，**不给即旧行为，逐字不变**：
     - :empty?            (fn [result] bool)；真 ⇒ 按 `:empty-retries`（缺省 1）**立即**补打
                          （不退避、不占 :max-retries、不抬高退避指数）；超预算按 `:empty-exhausted`：
                          `:throw` 抛 :empty-completion-error，否则（缺省）把空结果原样返回
     - :retry-gate        (fn [] bool)；每次决定重试前问一次，假 ⇒ 不重试（流式：本次尝试已吐过 token）
     - :record-attempts?  真 ⇒ 成功结果（map/record）上 assoc `:attempts`（含首次）
     - :provider          空补全错误的 :provider

   返回: `f` 的返回值（`:record-attempts?` 时带 :attempts）

   抛出: 最后一次失败的异常——canonical error 原样不变（`:retryable?` / `:status` 全程不丢，
   README 的错误模型承诺「各边界可单向转换」，重试层没有资格改写它）；试过不止一次时
   ex-data 追加 `:attempts`（经 `errors/with-ex-data`，消息 / cause / 栈逐字保留）。"
  [f {:keys [max-retries on-retry rand-fn sleep-fn empty? empty-retries empty-exhausted retry-gate
             record-attempts? provider retry-kind]
      :or {rand-fn rand}
      :as opts}]
  (let [sleep! (or sleep-fn #(when (pos? %) (Thread/sleep (long %))))
        maxr   (long (or max-retries 0))
        maxe   (if empty? (long (or empty-retries 1)) 0)
        gate?  #(or (nil? retry-gate) (boolean (retry-gate)))]
    (loop [http 0 empties 0 calls 1 trail []]
      (let [r (try {:ok (f)} (catch Throwable t {:err t}))]
        (if-let [^Throwable t (:err r)]
          (let [trail (conj trail (attempt-summary t))]
            (if (and (< http maxr) (http-retryable? t opts) (gate?))
              (let [delay-ms (delay-for t http opts rand-fn)]
                (when on-retry
                  (on-retry {:attempt   calls
                             :delay-ms  delay-ms
                             :error     (ex-data t)
                             :exception t
                             :kind      (or retry-kind :error)}))
                (sleep! delay-ms)
                (recur (inc http) empties (inc calls) trail))
              (throw (give-up t trail))))
          (let [v (:ok r)]
            (if (and empty? (empty? v))
              (let [err (empty-error provider calls)
                    trail (conj trail err)]
                (cond
                  (and (< empties maxe) (gate?))
                  (do (when on-retry
                        (on-retry {:attempt calls :delay-ms 0 :error err :kind :empty-completion}))
                      (recur http (inc empties) (inc calls) trail))
                  (= :throw empty-exhausted) (throw (give-up (ex-info (:message err) err) trail))
                  :else (annotate v calls record-attempts?)))
              (annotate v calls record-attempts?))))))))

(defn run-async
  "`run` 的异步孪生：`f` 是无参函数，返回**链结果**（deferred 或普通值）。

   与 `run` 的差别只有两处，其余（判据 `retryable-ex?`、三格预算、退避曲线含
   `Retry-After` 与满抖动、`:on-retry` 回调时机、`:attempts` 记录）**逐字共用**：

   1. 失败经 deferred 的 error channel 传播，用 `flt/fcatch` 接（同步抛出也接得住）；
   2. 退避不 `Thread/sleep`——用 `async/delayed`（`CompletableFuture/delayedExecutor`）。注入了 `:sleep-fn` 时仍走它（测试用：立即返回、只记账）。

   返回: deferred（或普通值——`f` 同步返回且没触发重试时就是普通值，形态保持见
   filter-chain-design.md §2.6.4 契约 C1）。
   抛出/落 error channel: 最后一次失败的异常（规则同 `run`）。

   递归深度 ≤ 总尝试次数，不吃栈。"
  [f {:keys [max-retries on-retry rand-fn sleep-fn empty? empty-retries empty-exhausted retry-gate
             record-attempts? provider retry-kind]
      :or {rand-fn rand}
      :as opts}]
  (let [maxr  (long (or max-retries 0))
        maxe  (if empty? (long (or empty-retries 1)) 0)
        gate? #(or (nil? retry-gate) (boolean (retry-gate)))
        wait  (fn [ms] (if sleep-fn (do (sleep-fn ms) nil) (async/delayed ms)))]
    (letfn [(on-err [^Throwable t http empties calls trail]
              (let [trail (conj trail (attempt-summary t))]
                (if (and (< http maxr) (http-retryable? t opts) (gate?))
                  (let [delay-ms (delay-for t http opts rand-fn)]
                    (when on-retry
                      (on-retry {:attempt   calls
                                 :delay-ms  delay-ms
                                 :error     (ex-data t)
                                 :exception t
                                 :kind      (or retry-kind :error)}))
                    (flt/fbind (wait delay-ms)
                               (fn [_] (step (inc http) empties (inc calls) trail))))
                  (throw (give-up t trail)))))
            (on-ok [v http empties calls trail]
              (if (and empty? (empty? v))
                (let [err (empty-error provider calls)
                      trail (conj trail err)]
                  (cond
                    (and (< empties maxe) (gate?))
                    (do (when on-retry
                          (on-retry {:attempt calls :delay-ms 0 :error err :kind :empty-completion}))
                        (step http (inc empties) (inc calls) trail))
                    (= :throw empty-exhausted) (throw (give-up (ex-info (:message err) err) trail))
                    :else (annotate v calls record-attempts?)))
                (annotate v calls record-attempts?)))
            (step [http empties calls trail]
              (flt/fbind (flt/fcatch (flt/fmap (f) (fn [v] [::ok v]))
                           (fn [t] [::err t]))
                         (fn [[tag x]]
                           (if (= ::ok tag)
                             (on-ok x http empties calls trail)
                             (on-err x http empties calls trail)))))]
      (step 0 0 1 []))))

(defn wrap
  "把 `f` 包成带重试的同名函数。config/opts 语义同 `resolve-opts`。"
  [f config opts]
  (fn [& args] (run #(apply f args) (merge (resolve-opts config opts)
                                           (select-keys opts [:on-retry :rand-fn :sleep-fn])))))
