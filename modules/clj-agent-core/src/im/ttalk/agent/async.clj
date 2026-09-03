(ns im.ttalk.agent.async
  "异步适配层：CompletionStage ⇄ 链结果组合子 + 虚拟线程入口 + 两回调 sink。

   `filter.clj` 只定义协议 `IChainResult`（契约见 docs/filter-chain-design.md
   §2.6.4，C5「协议进 core，实现不进」）；本 ns 是它的**第一个 deferred 实现**
   ——CompletionStage 是 JDK 自带、零依赖，故可以住在 core，`require` 即生效。
   manifold / core.async 的适配层照抄本 ns 的形状自写即可，两者都不必进 core。

   ## 为什么是 CompletableFuture 而不是 Flux

   turn 结果是**单值**（一个 TurnResult），不是流——Flux 那一套控制反转在这里
   买不到东西。真正的流是 token 流，而 token 流走 `:token-xform` transducer
   （`token-stream-filter-design.md`），与本 ns 无关。

   ## 为什么是虚拟线程

   LLM 调用与工具执行本身是**阻塞 IO**。Loom 下把它们跑在虚拟线程上，调用线程
   立刻拿到 CompletableFuture 返回，而阻塞代码一行不用改写成回调——这正是
   §2.6.2 说的「around 形状在 async 下不用变」。用 `Thread/startVirtualThread`
   而非 `clojure.core/future`（send-off 平台线程池）的理由同 `tool.clj`
   的 `call-with-timeout`：几 KB 栈、阻塞 IO 可被 interrupt 真正打断。

   ## 典型用法（Ring / Luminus 异步 handler）

   ```clojure
   (defn chat-handler [request respond raise]
     (-> (agent/chat-async my-agent (get-in request [:body-params :message]))
         (flt/fmap (fn [r] {:status 200 :body {:text (:text r)}}))
         (async/on-complete respond raise)))
   ```

   `respond` / `raise` 正是 Ring 3 异步 handler 的两个回调；`on-complete` 只是
   把链结果接到这一对回调上——**同步值也接**（此时 respond 在当前线程直接调用），
   所以同一个 handler 对同步与异步入口都成立。"
  (:require [im.ttalk.agent.filter :as flt])
  (:import [java.util.concurrent CompletableFuture CompletionStage CompletionException
            ExecutionException TimeUnit TimeoutException]
           [java.util.function Function BiConsumer]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 基础构造与解包
;;; ============================================================

(defn deferred?
  "x 是否为 deferred（本适配层认 `CompletionStage`）。"
  [x]
  (instance? CompletionStage x))

(defn completed
  "已完成的 deferred。"
  ^CompletableFuture [v]
  (CompletableFuture/completedFuture v))

(defn failed
  "已异常完成的 deferred。"
  ^CompletableFuture [^Throwable t]
  (let [cf (CompletableFuture.)]
    (.completeExceptionally cf t)
    cf))

(defn unwrap-cause
  "剥掉 `CompletionException` / `ExecutionException` 这层 JDK 包装，露出原异常。

   **为什么必须剥**：契约 C3 要求「异常语义不变」——filter 作者写
   `(flt/fcatch (chain req) handler)`，handler 在同步与异步两条路径上应当拿到
   **同一个异常对象**。不剥的话异步路径会多出一层 CompletionException，
   `(ex-data t)` / `instance?` 判断全部失效，这类 filter 就得为两条路径各写一遍。"
  ^Throwable [^Throwable t]
  (if (and t
           (or (instance? CompletionException t) (instance? ExecutionException t))
           (.getCause t))
    (recur (.getCause t))
    t))

(defn- ^Function as-function [f]
  (reify Function (apply [_ v] (f v))))

;;; ============================================================
;;; IChainResult 适配：deferred 上的 fmap / fbind / on-error
;;; ============================================================

(extend-protocol flt/IChainResult
  CompletionStage
  (fmap [x f]
    (.thenApply ^CompletionStage x (as-function f)))
  (fbind [x f]
    ;; f 可能返回同步值（如递归到头的最终结果），补一层 completed 再 compose，
    ;; 于是 fbind 永不产出嵌套 deferred。
    (.thenCompose ^CompletionStage x
                  (as-function (fn [v]
                                 (let [r (f v)]
                                   (if (deferred? r) r (completed r)))))))
  (on-error [x handler]
    ;; **exceptionallyCompose 而不是 exceptionally**：handler 可能返回 deferred
    ;; （重试就是这么写的——`retry/run-async` 的 handler 退避后重跑一次），
    ;; 用 exceptionally 会得到 deferred<deferred>。同步路径上
    ;; `(try … (catch t (h t)))` 本来就原样返回 h 的结果（是 deferred 就是
    ;; deferred），异步这边补上 compose 才算逐字同义（契约 C3）。
    (.exceptionallyCompose
      ^CompletionStage x
      (as-function (fn [t]
                     (let [r (handler (unwrap-cause t))]
                       (if (deferred? r) r (completed r))))))))

;;; ============================================================
;;; 虚拟线程入口
;;; ============================================================

(defn vthread
  "在虚拟线程上跑 thunk，立刻返回 `CompletableFuture`——调用线程不阻塞。

   thunk 抛出的任何 `Throwable` 落进 deferred 的 error channel（不吞、不打印）。

   `bound-fn*` 包装：调用方的**动态绑定帧**带进工作线程（`*register-cancel*`
   等靠动态 var 穿链的机制因此照常可见）。与 `clojure.core/future`、
   `react/run-on-executor` 的传导语义一致——引擎不改变「跑的是什么」。"
  ^CompletableFuture [thunk]
  (let [cf (CompletableFuture.)
        ;; bound-fn* 须在**调用方的**绑定帧内求值，故不能挪进 startVirtualThread
        task (bound-fn* (fn []
                          (try (.complete cf (thunk))
                               (catch Throwable t (.completeExceptionally cf t)))))]
    (Thread/startVirtualThread task)
    cf))

(defn delayed
  "ms 毫秒后完成的 deferred（值为 nil）。**不占线程**——JDK 的 delayedExecutor
   用共享调度器，到点才把任务丢出去。异步路径上的「等一会儿」一律用它，
   绝不 `Thread/sleep`（那会睡掉传输层 executor 的线程）。"
  ^CompletableFuture [ms]
  (let [cf (CompletableFuture.)]
    (if (pos? ms)
      (.execute (CompletableFuture/delayedExecutor (long ms) TimeUnit/MILLISECONDS)
                ^Runnable (fn [] (.complete cf nil)))
      (.complete cf nil))
    cf))

(defn inline
  "同步「跑法」：当场执行 thunk，返回普通值。

   与 `vthread` 同签名，专供「一份代码两条路径」的入口函数——
   `react/invoke` 传它、`react/invoke-async` 传 `vthread`，其余逐字相同。"
  [thunk]
  (thunk))

;;; ============================================================
;;; 出口：两回调 sink / 阻塞取值
;;; ============================================================

(defn on-complete
  "把链结果接到一对回调上：成功调 `success`，失败调 `failure`（拿到的是
   **解包后**的原异常）。返回 nil。

   同步值（含同步抛出）也照常接——所以 Ring handler 不必关心入口是否异步。
   正是 Ring 异步 handler 的 `(fn [request respond raise])` 那对回调。"
  [x success failure]
  (if (deferred? x)
    (do (.whenComplete ^CompletionStage x
                       (reify BiConsumer
                         (accept [_ v t]
                           (if t (failure (unwrap-cause t)) (success v)))))
        nil)
    (do (success x) nil)))

(defn join
  "阻塞取值：deferred 等它完成，同步值原样返回；失败时抛**解包后**的原异常。

   给调用方（脚本、测试、同步 API 边界）用。**filter 内部禁止调用**——
   契约 C2「永不阻塞」，filter 里想拿值请用 `flt/fmap`。"
  ([x]
   (if (deferred? x)
     (try (.get (.toCompletableFuture ^CompletionStage x))
          (catch Throwable t (throw (unwrap-cause t))))
     x))
  ([x timeout-ms]
   (if (deferred? x)
     (try (.get (.toCompletableFuture ^CompletionStage x) timeout-ms TimeUnit/MILLISECONDS)
          (catch TimeoutException t (throw t))
          (catch Throwable t (throw (unwrap-cause t))))
     x)))
