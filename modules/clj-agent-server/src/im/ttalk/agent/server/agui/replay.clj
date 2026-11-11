(ns im.ttalk.agent.server.agui.replay
  "`/connect` 的帧环（C# Keelson.Agui 的 AguiReplay；keel agui/replay.ss，designs/agui-connect.md N2）：按线程一只有界缓冲，
   只装**这一轮**从 RUN_STARTED 起编好的帧（字节）。是**传输**缓冲不是会话存储：跑完留 keep-ms 给迟到的 `/connect`，
   新一轮开始就覆盖。

   为什么不直接用 clj-agent-agui 的事件存储（`:event-store` / `subscribe :since`）：那是按**条数**有界、跨 run 连续的会话环，
   没有 C# 契约要的三样 —— 按**字节**封顶且超了整轮作废（truncated ⇒ `/connect` 回空流，⛔ 不发半截）、跑完后按时长保留、
   线程数 LRU 驱逐。那三样是小东西，就在这里补上。

   超 max-bytes ⇒ 这一轮标 :truncated、清空、不再攒。线程数超 max-threads ⇒ 驱逐**没在跑的**里最久没碰的（全在跑时宁可多一条）。
   时间由调用方传进来（毫秒）。线程安全（一把锁）。`:enabled? false` ⇒ 空转：不建条目、不进帧、`open-cursor` 恒 nil
   （⇒ `/connect` 对任何线程回 200 空流）。")

(set! *warn-on-reflection* true)

(defn replay
  "`:max-bytes`（缺省 4 MiB）`:keep-ms`（缺省 5 分钟）`:max-threads`（缺省 256）`:enabled?`（缺省 true）。负数 ⇒ IllegalArgumentException。"
  ([] (replay nil))
  ([{:keys [max-bytes keep-ms max-threads enabled?]
     :or {max-bytes (* 4 1024 1024) keep-ms 300000 max-threads 256 enabled? true}}]
   (doseq [[k v] {:max-bytes max-bytes :keep-ms keep-ms :max-threads max-threads}]
     (when (neg? (long v)) (throw (IllegalArgumentException. (str (name k) ": " v)))))
   {:lock (Object.) :table (atom {}) :max-bytes (long max-bytes) :keep-ms (long keep-ms)
    :max-threads (long max-threads) :enabled? (boolean enabled?)}))

(defn enabled? [r] (:enabled? r))

(defn thread-count [r] (locking (:lock r) (count @(:table r))))

(defn- new-entry [tid now]
  (atom {:thread-id tid :frames [] :bytes 0 :state :running :finished-at nil :touched now :generation 0}))

(defn begin!
  "新一轮：旧的（不管什么状态）整个换掉；线程数超了驱逐没在跑的。"
  [r tid now]
  (when (enabled? r)
    (locking (:lock r)
      (swap! (:table r) assoc tid (new-entry tid now))
      (loop []
        (when (> (count @(:table r)) (:max-threads r))
          (let [victim (->> (vals @(:table r))
                            (map deref)
                            (remove #(or (= :running (:state %)) (= tid (:thread-id %))))
                            (sort-by :touched)
                            first)]
            (when victim
              (swap! (:table r) dissoc (:thread-id victim))
              (recur))))))))

(defn ensure!
  "有这一轮的环就保留（⛔ 不覆盖在跑的那轮）；没有就开一条。外部事件源写帧用它。"
  [r tid now]
  (when (enabled? r)
    (locking (:lock r)
      (swap! (:table r) #(cond-> % (nil? (get % tid)) (assoc tid (new-entry tid now)))))))

(defn append!
  "攒。回 true；超限 ⇒ 标 truncated、清空、回 false。没有这条线程 / 已 truncated / 环没开 ⇒ false。"
  [r tid frames]
  (if-not (enabled? r)
    false
    (locking (:lock r)
      (let [e (get @(:table r) tid)]
        (cond
          (or (nil? e) (= :truncated (:state @e))) false
          (empty? frames) true
          :else
          (let [add (reduce + 0 (map #(alength ^bytes %) frames))]
            (if (> (+ (long (:bytes @e)) add) (long (:max-bytes r)))
              (do (swap! e #(-> % (assoc :state :truncated :frames [] :bytes 0) (update :generation inc)))
                  false)
              (do (swap! e #(-> % (update :frames into frames) (update :bytes + add)))
                  true))))))))

(defn finish!
  "终端事件到了（或 run 被按停）：开始计 keep。truncated 的照旧 truncated。"
  [r tid now]
  (when (enabled? r)
    (locking (:lock r)
      (when-let [e (get @(:table r) tid)]
        (swap! e #(cond-> (assoc % :finished-at now :touched now)
                    (= :running (:state %)) (assoc :state :finished)))))))

(defn state
  "`:running` / `:finished` / `:truncated`；没有 ⇒ nil。"
  [r tid]
  (when (enabled? r)
    (locking (:lock r) (some-> (get @(:table r) tid) deref :state))))

(defn open-cursor
  "从 0 起的读位置；没有可重放的 / truncated ⇒ nil（`/connect` 回 200 空流）。"
  [r tid now]
  (when (enabled? r)
    (locking (:lock r)
      (when-let [e (get @(:table r) tid)]
        (when-not (= :truncated (:state @e))
          (swap! e assoc :touched now)
          {:entry e :generation (:generation @e) :index (atom 0)})))))

(defn take!
  "从上次读到的位置起取帧；空 = 暂时没有。中途 truncated 了 ⇒ 空且 `done?`。"
  [r {:keys [entry generation index]}]
  (locking (:lock r)
    (let [{:keys [state frames] g :generation} @entry]
      (if (or (= :truncated state) (not= generation g) (>= @index (count frames)))
        []
        (let [out (subvec frames @index)]
          (reset! index (count frames))
          out)))))

(defn done?
  "那一轮已收尾且帧都读完了（或 truncated）⇒ 订阅者关流。"
  [r {:keys [entry generation index]}]
  (locking (:lock r)
    (let [{:keys [state frames] g :generation} @entry]
      (or (= :truncated state) (not= generation g)
          (and (= :finished state) (>= @index (count frames)))))))

(defn expire!
  "清掉跑完（或 truncated）且过了 keep 的；在跑的不动。回清掉几条。"
  [r now]
  (if-not (enabled? r)
    0
    (locking (:lock r)
      (let [dead (vec (for [e (vals @(:table r))
                       :let [{:keys [state finished-at thread-id]} @e]
                       :when (and (not= :running state) finished-at (> (- (long now) (long finished-at)) (:keep-ms r)))]
                   thread-id))]
        (swap! (:table r) #(apply dissoc % dead))
        (count dead)))))
