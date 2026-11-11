(ns im.ttalk.agent.server.agui.host
  "成品服务端的运行期（C# AguiHost；keel server-http.ss N3）：**run 归服务端驱动，连接只是订阅者**。

   run 本身由 clj-agent-agui 的 runtime 跑（会话注册表、取消、HITL 都在那边）；这里给每台经 `/run` 起的 run 挂一只**泵**
   （一个 runtime 订阅）：把中立事件编成 AG-UI 帧，进帧环（给 `/connect`，见 im.ttalk.agent.server.agui.replay）与直连队列
   （给起 run 的那条连接），喂线程存储的 :record 钩子。⚠️ 断连 ≠ 按停（刷新也是断连，而刷新正是 `/connect` 要救的场景）；
   按停的正门是 `/stop/:threadId`。

   选项（C# AguiRegistryOptions 里服务端用得上的那几格 + AguiServerOptions）：
     :version             进 /info（缺省 \"0.0.0\"）
     :origin-allowed      Origin 白名单谓词；缺省 nil = **拒**（带 Origin 的请求 403）。im.ttalk.agent.server.agui.http/origin-allow
     :credentials?        发 Access-Control-Allow-Credentials: true
     :allow-headers       宿主自己的请求头，并进预检的 Allow-Headers
     :a2ui? :open-generative-ui?  /info 的两格布尔
     :thread-store        线程存储：六槽 map 或 clj-agent-agui 的 ThreadStore（见 im.ttalk.agent.server.agui.thread-store）；给了才有 /threads*
     :heartbeat-ms        SSE 心跳（缺省 15000；0 = 关）。⚠️ 不是可有可无：空闲超时的代理会把不出声的流掐掉；JVM 上它还是发现断连的唯一途径
     :heartbeat-frame     (fn [] 字节)；缺省注释帧 `: clj-agent-alive`。/run、/connect、/suggest 的流都用它
     :replay-max-bytes    帧环一轮的字节上限（缺省 4 MiB；超了 ⇒ 这一轮 /connect 回空流）
     :replay-keep-ms      跑完之后留多久给迟到的 /connect（缺省 5 分钟）
     :replay-max-threads  帧环最多几条线程（缺省 256）
     :replay?             帧环攒不攒（缺省 true；false ⇒ /connect 对任何线程 200 空流，/run 照旧）
     :orphan-cancel-ms    最后一个订阅者走了之后过这么久没人来就按停（原因 :orphan；缺省 0 = 关）
     :max-body-bytes      请求体上限（缺省 16 MiB；超了 413）
     :now                 (fn [] ms)，可注入
     :info                其余交给 clj-agent-agui `codec/run-info` 的能力位（:multimodal :parallel-tools? :state? …）
     :input-transform     入站插件的缺省值（条目里给了就以条目为准；见 im.ttalk.agent.server.agui 的注册表条目键）
     :mcp-proxy           MCP Apps 前端代理的缺省值（同上）
     :agents              按 agent 注入外部驱动的缺省值（routes 也能再并一份；键同下面的注册表条目键）

   同线程上一轮还在飞时再来 `/run`（C# AguiRegistryOptions.ConcurrentRun）由那台 agent 的 runtime 决定：
   `rt/runtime` 的 `:on-concurrent :reject`（缺省，⇒ 409 thread_running）或 `:supersede`（旧的以 :superseded 取消）。"
  (:require [cheshire.core :as json]
            [im.ttalk.agent.agui.codec :as codec]
            [im.ttalk.agent.agui.registry :as registry]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.server.agui.replay :as replay]
            [im.ttalk.agent.server.strings :refer [msg]]
            [im.ttalk.agent.server.agui.thread-store :as store]
            [im.ttalk.agent.server.strings :as err]
            [im.ttalk.http.sse :as frame])
  (:import (java.util.concurrent CompletableFuture ConcurrentHashMap LinkedBlockingQueue TimeUnit)
           (java.util.concurrent.atomic AtomicBoolean)
           (java.util.function BiConsumer)))

(set! *warn-on-reflection* true)

(def default-options
  {:version "0.0.0"
   :origin-allowed nil
   :credentials? false
   :allow-headers []
   :a2ui? false
   :open-generative-ui? false
   :thread-store nil
   :heartbeat-ms 15000
   :heartbeat-frame nil
   :replay-max-bytes (* 4 1024 1024)
   :replay-keep-ms (* 5 60 1000)
   :replay-max-threads 256
   :replay? true
   :orphan-cancel-ms 0
   :max-body-bytes (* 16 1024 1024)
   :agents {}})

(defn host
  "建运行期。`reg` = clj-agent-agui 的 `registry/registry`（条目：:id :runtime，可选 :description :suggest? :agent-spec
   :memory :server-tool-names :stop …）。心跳为负 ⇒ `agui-server/negative-heartbeat`；:allow-headers 里有空串 ⇒
   `agui-registry/allow-headers-empty-string`；线程存储一个槽都没有 ⇒ `agui-registry/thread-store-no-slot`。"
  ([reg] (host reg nil))
  ([reg opts]
   (when-not (registry/registry? reg)
     (throw (IllegalArgumentException. "host needs an im.ttalk.agent.agui.registry/registry")))
   (let [o (merge default-options opts)]
     (when (neg? (long (or (:heartbeat-ms o) 0)))
       (throw (err/error (msg :agui-server/negative-heartbeat) {:im.ttalk.agent.server/kind :argument-out-of-range})))
     (doseq [h (:allow-headers o)]
       (when (empty? h) (throw (err/error (msg :agui-registry/allow-headers-empty-string) {:im.ttalk.agent.server/kind :argument}))))
     {::host true
      :registry reg
      :options o
      :thread-store (store/normalize (:thread-store o) reg)
      :replay (replay/replay {:max-bytes (:replay-max-bytes o) :keep-ms (:replay-keep-ms o)
                              :max-threads (:replay-max-threads o) :enabled? (:replay? o)})
      :drives (ConcurrentHashMap.)
      :suggests (ConcurrentHashMap.)
      :now (or (:now o) #(System/currentTimeMillis))})))

(defn host? [x] (boolean (and (map? x) (::host x))))

(defn now-ms ^long [h] (long ((:now h))))

(defn replay-of "帧环（宿主要读状态时）。" [h] (:replay h))

(defn publish-frame!
  "把一帧编好的 SSE 字节交给帧环（外部驱动写入口）。开跑的内置泵先建了环就接着攒；没有就开一条。"
  [h tid frames]
  (let [rp (replay-of h)]
    (replay/ensure! rp tid (now-ms h))
    (replay/append! rp tid frames)))

;; ── 帧 ───────────────────────────────────────────────

(defn frame-of
  "AG-UI 事件 ⇒ 一帧 SSE（`data: {json}\\n\\n`，不带 id: —— 同 C#）。"
  ^bytes [agui]
  (frame/event-frame (json/generate-string agui)))

(defn- rewrite-run-id
  "runtime 的 run-id 是它自己生成的 UUID；线上换回客户端在 RunAgentInput.runId 里给的那个（C# 同：RUN_STARTED.runId = 输入的 runId）。"
  [agui client-run-id]
  (if (and client-run-id (contains? agui :runId)) (assoc agui :runId client-run-id) agui))

;; ── 泵（Drive）────────────────────────────────────────

(defn drive-of [h tid] (.get ^ConcurrentHashMap (:drives h) tid))

(defn- summary [d]
  {:thread-id (:thread-id d) :run-id (:client-run-id d) :runtime-run-id (:run-id d) :agent-id (:agent-id d)})

(defn run
  "这条线程上经 /run 起的、在飞的那台：{:thread-id :run-id（客户端给的）:runtime-run-id :agent-id}；没有 ⇒ nil（C# Registry.Run）。"
  [h tid]
  (some-> (drive-of h tid) summary))

(defn runs "在飞的那些（C# Registry.Runs）。" [h] (mapv summary (.values ^ConcurrentHashMap (:drives h))))

(defn in-flight "在飞的 run 数（含 suggest）。" [h] (+ (.size ^ConcurrentHashMap (:drives h)) (.size ^ConcurrentHashMap (:suggests h))))

(defn- pulse! [d]
  (let [[^CompletableFuture old _] (swap-vals! (:pulse d) (fn [_] (CompletableFuture.)))]
    (.complete old true)))

(defn next-change
  "下一次「有新帧 / 收场」时完成的 future。⚠️ 先取它再读帧（否则读完到取之间的那一跳会漏等）。"
  ^CompletableFuture [d]
  (if (.get ^AtomicBoolean (:done d)) (CompletableFuture/completedFuture true) @(:pulse d)))

(defn- record! [h d agui]
  (when-let [f (:record (:thread-store h))]
    (try (f (:agent-id d) (:input d) agui) (catch Throwable _ nil))))

(defn- finish! [h d]
  (when (.compareAndSet ^AtomicBoolean (:done d) false true)
    (let [rp (:replay h)]
      (when (replay/enabled? rp) (replay/finish! rp (:thread-id d) (now-ms h))))
    (.remove ^ConcurrentHashMap (:drives h) (:thread-id d) d)
    (.offer ^LinkedBlockingQueue (:queue d) ::end)
    (pulse! d)
    (swap! (:orphan-gen d) inc)
    (.complete ^CompletableFuture (:finished d) true)
    (let [[old _] (swap-vals! (:unsub d) (constantly ::finished))]
      (when (fn? old) (old)))))

(defn- on-event
  "runtime 的订阅回调（在会话锁里同步调：要快、⛔ 不抛）。只认本 run 的事件。"
  [h d ev]
  (try
    (when (and (not (.get ^AtomicBoolean (:done d)))
               (or (nil? (:run-id ev)) (= (:run-id ev) (:run-id d))))
      (doseq [a (codec/->agui-events ev)
              :let [a (rewrite-run-id a (:client-run-id d))]]
        (record! h d a)
        (let [f (frame-of a)
              rp (:replay h)]
          (when (replay/enabled? rp) (replay/append! rp (:thread-id d) [f]))
          (when-not (.get ^AtomicBoolean (:direct-gone d)) (.offer ^LinkedBlockingQueue (:queue d) f))
          (pulse! d)
          (when (codec/terminal? a) (finish! h d)))))
    (catch Throwable _ nil)))

(defn start-drive!
  "起泵：帧环 begin、登记、订阅 runtime（从 start-run! 给的 :since 水位起，一条不漏）。
   args：:entry（注册表条目）:thread-id :run-id（runtime 的）:client-run-id :since :input（RunAgentInput）。"
  [h {:keys [entry thread-id since] :as args}]
  ;; 上一台的泵收完场再起（不然它的 finish 会落在新一轮的环上）
  (when-let [prev (drive-of h thread-id)]
    (deref (:finished prev) 5000 nil))
  (let [rp (:replay h)
        now (now-ms h)
        _ (when (replay/enabled? rp) (replay/expire! rp now) (replay/begin! rp thread-id now))
        d (merge (select-keys args [:thread-id :run-id :client-run-id :input])
                 {:runtime (:runtime entry)
                  :agent-id (:id entry)
                  :queue (LinkedBlockingQueue.)
                  :direct-gone (AtomicBoolean. false)
                  :done (AtomicBoolean. false)
                  :finished (CompletableFuture.)
                  :lock (Object.)
                  :subscribers (atom 0)
                  :orphan-gen (atom 0)
                  :pulse (atom (CompletableFuture.))
                  :unsub (atom nil)})]
    (.put ^ConcurrentHashMap (:drives h) thread-id d)
    (let [unsub (rt/subscribe (:runtime entry) thread-id {:since since :on-event #(on-event h d %)})
          [old _] (swap-vals! (:unsub d) (fn [v] (if (= v ::finished) v unsub)))]
      (when (= old ::finished) (unsub)))
    d))

(defn external-frame!
  "外部源交付一条 AG-UI 事件 / 已编帧：进帧环，回编好的 SSE 字节。终端事件也由这里判——交付方不再另发收场信号。"
  [h tid frame]
  (let [f (if (bytes? frame) frame (frame-of frame))]
    (publish-frame! h tid [f])
    (when (and (map? frame) (codec/terminal? frame))
      (replay/finish! (replay-of h) tid (now-ms h))
      ::end)))

(defn poll-direct
  "直连队列取一帧：字节 / `::end`（收场）/ nil（`ms` 内没有）。"
  [d ms]
  (.poll ^LinkedBlockingQueue (:queue d) (long ms) TimeUnit/MILLISECONDS))

(def end ::end)

(defn subscribe! [d]
  (locking (:lock d)
    (swap! (:subscribers d) inc)
    (swap! (:orphan-gen d) inc)))

(defn unsubscribe!
  "退订（⛔ 不按停）；`direct?` = 起 run 的那条连接走了 ⇒ 直连队列不再攒帧。最后一个走了且开了 :orphan-cancel-ms ⇒ 到点没人回来就按停。"
  [h d direct?]
  (when direct?
    (.set ^AtomicBoolean (:direct-gone d) true)
    (.clear ^LinkedBlockingQueue (:queue d)))
  (let [ms (long (or (get-in h [:options :orphan-cancel-ms]) 0))
        gen (locking (:lock d)
              (let [n (swap! (:subscribers d) dec)]
                (when (and (<= n 0) (pos? ms) (not (.get ^AtomicBoolean (:done d))))
                  (swap! (:orphan-gen d) inc))))]
    (when gen
      (.execute (CompletableFuture/delayedExecutor ms TimeUnit/MILLISECONDS)
                ^Runnable (fn []
                            (when (locking (:lock d)
                                    (and (= gen @(:orphan-gen d)) (<= @(:subscribers d) 0)
                                         (not (.get ^AtomicBoolean (:done d)))))
                              (rt/stop! (:runtime d) (:thread-id d) (:run-id d) :orphan)))))))

(defn track-suggest!
  "记一台 suggest（无状态，不进注册表、不进帧环；只为 `in-flight`）。`done` = run-detached! 的 future。"
  [h ^CompletableFuture done]
  (let [^ConcurrentHashMap m (:suggests h)]
    (.put m done true)
    (.whenComplete done (reify BiConsumer (accept [_ _ _] (.remove m done))))))

(defn cancel-all!
  "停机：按停所有在飞的 run（原因 :shutdown）。⚠️ suggest 停不了（clj-agent-agui 的 run-detached! 不交出取消令牌），流随停机关掉。"
  [h]
  (doseq [d (vec (.values ^ConcurrentHashMap (:drives h)))]
    (try (rt/stop! (:runtime d) (:thread-id d) (:run-id d) :shutdown) (catch Throwable _ nil))))
