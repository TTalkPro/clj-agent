(ns im.ttalk.agent.admission
  "在途票据池：把「同时压在某个 provider/模型上的请求数」关进一个计数
   （designs/provider-reliability.md §5，Keelson `ChatAdmission.cs` / `AdmittingChatClient.cs`）。

   - **按键分池**：每个键 `permits` 张票（缺省 8），键通常是 `\"<provider>/<model>\"`。
   - **退避不占票**：票只跟「一次尝试」走——`chat-model/with-admission` 把取票放在
     DefaultChatModel 重试循环的**里面**，退避等待发生在票外。
   - **异步不占线程**：`acquire-async` 返回 CompletableFuture，排队期间不阻塞任何线程；
     还票时直接把票**移交**给队首等待者（不经 available，防插队），移交在虚拟线程上完成，
     免得把等待者的后续阶段跑在还票线程上。
   - 排队不设死线（限流是分钟尺度）；要放弃就取消：同步路径中断线程，异步路径 cancel future。
   - **无全局单例**（design-principles §3）：要共享就把同一个池显式传给多个 ChatModel。

   ```clojure
   (def pool (admission/admission {:permits 4}))
   (admission/with-permit pool \"anthropic/claude\" #(call-llm ...))
   (admission/with-permit-async pool \"anthropic/claude\" #(call-llm-async ...))
   ```"
  (:require [im.ttalk.agent.filter :as flt])
  (:import [java.util.concurrent CompletableFuture CancellationException ExecutionException]
           [java.util.concurrent.atomic AtomicBoolean]))

(set! *warn-on-reflection* true)

(def default-permits
  "缺省每键票数（与 Keelson `ChatAdmission.DefaultPermits` 同口径）。"
  8)

(defrecord Admission [permits pools])

(defrecord Ticket [key pool ^AtomicBoolean released])

(defn admission
  "建一个票据池。opts: `{:permits n}`（每个键最多 n 张票；≤0 或缺省 → 8）。"
  ([] (admission nil))
  ([{:keys [permits]}]
   (->Admission (if (and permits (pos? permits)) (long permits) default-permits)
                (atom {}))))

(def ^:private default-key "default")

(defn- pool-for [^Admission adm key]
  (let [k (or key default-key)]
    (or (get @(:pools adm) k)
        (get (swap! (:pools adm)
                    (fn [m] (if (contains? m k)
                              m
                              (assoc m k (atom {:available (:permits adm)
                                                :waiters clojure.lang.PersistentQueue/EMPTY})))))
             k))))

(defn release!
  "归还一张票（幂等：重复调用不会把池吹大）。有等待者 → 移交给队首（跳过已取消的）。"
  [^Ticket ticket]
  (when (and ticket (.compareAndSet ^AtomicBoolean (:released ticket) false true))
    (let [pool (:pool ticket)]
      (loop []
        (let [[old _] (swap-vals! pool
                                  (fn [{:keys [waiters] :as s}]
                                    (if (seq waiters)
                                      (update s :waiters pop)
                                      (update s :available inc))))]
          (when-let [^CompletableFuture cf (peek (:waiters old))]
            (if (.isDone cf)
              ;; 等待者已取消：票还在手上，继续移交给下一个（或回到 available）
              (recur)
              (let [t (->Ticket (:key ticket) pool (AtomicBoolean. false))]
                ;; 虚拟线程上完成：等待者的后续阶段不跑在还票线程上。
                ;; 完成失败（这一瞬间被取消了）→ 把这张新票再还回去。
                (Thread/startVirtualThread
                  ^Runnable (fn [] (when-not (.complete cf t) (release! t))))))))))
    nil))

(defn acquire-async
  "取一张票 → `CompletableFuture<ticket>`。有空位立即完成，否则排队（不占线程）。
   cancel 这个 future 即放弃排队（已到手的票由取消方负责 `release!`，见 `acquire!`）。"
  ^CompletableFuture [^Admission adm key]
  (let [pool (pool-for adm key)
        cf (CompletableFuture.)
        [old _] (swap-vals! pool
                            (fn [{:keys [available] :as s}]
                              (if (pos? available)
                                (update s :available dec)
                                (update s :waiters conj cf))))]
    (when (pos? (:available old))
      (.complete cf (->Ticket key pool (AtomicBoolean. false))))
    cf))

(defn acquire!
  "阻塞取一张票。线程被中断 → 放弃排队（若票恰好已到手则自动归还）并抛 InterruptedException。"
  [^Admission adm key]
  (let [cf (acquire-async adm key)]
    (try
      (.get cf)
      (catch InterruptedException e
        (when-not (.cancel cf false)
          ;; 已经完成：票到手了，还回去
          (try (release! (.getNow cf nil)) (catch Throwable _ nil)))
        (throw e))
      (catch ExecutionException e
        (throw (or (.getCause e) e)))
      (catch CancellationException e
        (throw e)))))

(defn with-permit
  "同步：取票 → 跑 thunk → finally 还票。"
  [adm key thunk]
  (let [t (acquire! adm key)]
    (try (thunk)
         (finally (release! t)))))

(defn with-permit-async
  "异步：取票（不占线程）→ 跑 thunk（返回链结果）→ 完成或失败时还票。返回链结果。"
  [adm key thunk]
  (flt/fbind (acquire-async adm key)
             (fn [ticket]
               (flt/fcatch
                 (flt/fmap (thunk) (fn [v] (release! ticket) v))
                 (fn [t] (release! ticket) (throw t))))))

(defn in-flight
  "现在占着票的请求数；不给 key → 所有池加总（观测用，不做同步）。"
  ([^Admission adm]
   (reduce + 0 (map (fn [p] (- (:permits adm) (:available @p))) (vals @(:pools adm)))))
  ([^Admission adm key]
   (if-let [p (get @(:pools adm) (or key default-key))]
     (- (:permits adm) (:available @p))
     0)))

(defn waiting
  "正在排队等票的请求数（不含已取消的）；不给 key → 所有池加总。"
  ([^Admission adm]
   (reduce + 0 (map (fn [p] (count (remove #(.isDone ^CompletableFuture %) (:waiters @p))))
                    (vals @(:pools adm)))))
  ([^Admission adm key]
   (if-let [p (get @(:pools adm) (or key default-key))]
     (count (remove #(.isDone ^CompletableFuture %) (:waiters @p)))
     0)))
