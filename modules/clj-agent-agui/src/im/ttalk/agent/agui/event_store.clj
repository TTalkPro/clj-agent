(ns im.ttalk.agent.agui.event-store
  "会话事件存储的**可替换挂点**（designs/agui-borrow.md §8；对照 Keelson
   `IAguiThreadEventStore` / `IAguiThreadSequenceRecorder` 与卫星包
   `Keelson.Agui.Bitcask`）。

   **本库只带一个实现**：`ring-store`，即原来 runtime 内置的会话环形缓冲
   （有界、进程内、不落库）。缺省行为与以前逐字相同。

   **为什么要有这个协议**（2026-11-08 用户拍板）：事件持久化本库**不做**——
   `docs/agent-runtime-design.md` §8.1 / §8.2 的结论不变（对话真相在 ChatMemory，
   暂停真相在 PauseStore，事件缓冲是传输设施）。但事件存储的逻辑要**能整个换掉**：
   将来外部库（同 Keelson 的 `Agui.Bitcask`）实现本协议、经 runtime 的
   `:event-store` 插进来，就能让 `/threads/:id/events`、`/threads/:id/state` 在
   重启后仍有内容，`:since` / Last-Event-ID 跨进程续传。本库不需要为此改一行。

   即便插了持久化实现，它仍然**不是**对话真相：`:since` 补不上时 runtime 照旧以
   ChatMemory 的消息快照 resync（§8.1），事件存储只是回放设施。

   ## 契约（实现方必须满足）

   - **`:seq` 由 runtime 分配**：会话内单调递增、跨 run 连续。存储按原样保存，
     不改、不重排。
   - **`es-append!` 在会话锁内同步调用**：要快，**不许抛**（抛了 runtime 记日志吞掉，
     不打断 run，但这条事件就没进存储）。慢存储请自己做写缓冲。
   - **`es-last-seq`**：会话新建条目时 runtime 用它接上序号（重启后 `:seq` 不从 0
     重来——否则客户端拿着旧的 Last-Event-ID 会漏收）。没有记录返回 nil。
   - **`es-since`**：返回 `{:events [...] :gap? bool}`；`since` 为 nil 返回空且不报
     gap；`gap?` 为真表示存储起点已越过 `since`，中间那段补不回来，runtime 会走 resync。
   - **`es-release!`**：会话从 runtime 内存里被驱逐 / forget 时调。进程内实现借机
     释放内存；持久化实现**什么都不做**（数据要留着给重启后的续传）。
   - **`es-delete!`**：线程被删除时调（`rt/purge!`）。持久化实现在这里真删。

   事件是中立的 Clojure map（`agui.event` 的事件形状，含 `:type :seq :ts` 等），
   EDN 可往返；持久化实现自己决定编码（EDN / Transit / JSON）。"
  (:require [taoensso.timbre :as log]))

(set! *warn-on-reflection* true)

(defprotocol EventStore
  (es-append! [s conv-id event]
    "追加一条（已带 `:seq`）。会话锁内同步调用：要快、不许抛。")
  (es-since [s conv-id since]
    "`since` 之后的事件：`{:events [...] :gap? bool}`。")
  (es-events [s conv-id]
    "现存的全部事件（只读端点用）。没有返回 []。")
  (es-last-seq [s conv-id]
    "该会话最后一条事件的 `:seq`，没有返回 nil。")
  (es-release! [s conv-id]
    "会话被 runtime 驱逐 / forget：进程内实现释放内存，持久化实现不动数据。")
  (es-delete! [s conv-id]
    "线程被删除：真删。"))

;;; ============================================================
;;; 共用语义
;;; ============================================================

(defn since-of
  "对一串按 `:seq` 升序的事件做 `es-since` 的语义（实现方可直接复用）。"
  [events since]
  (if (nil? since)
    {:events [] :gap? false}
    {:events (filterv #(> (long (:seq %)) (long since)) events)
     :gap?   (boolean (and (seq events) (> (long (:seq (first events))) (inc (long since)))))}))

;;; ============================================================
;;; 缺省实现：进程内环形缓冲
;;; ============================================================

(defn- ring-add [cap events ev]
  (let [e (conj (or events []) ev)]
    (if (> (count e) cap) (subvec e (- (count e) cap)) e)))

(defrecord RingEventStore [cap rings]
  EventStore
  (es-append! [_ conv-id event]
    (swap! rings update conv-id #(ring-add cap % event))
    nil)
  (es-since [_ conv-id since] (since-of (get @rings conv-id []) since))
  (es-events [_ conv-id] (vec (get @rings conv-id [])))
  (es-last-seq [_ conv-id] (some-> (get @rings conv-id) peek :seq))
  (es-release! [_ conv-id] (swap! rings dissoc conv-id) nil)
  (es-delete! [_ conv-id] (swap! rings dissoc conv-id) nil))

(defn ring-store
  "进程内环形缓冲：每会话最多 `:buffer-size` 条（缺省 512），会话驱逐即丢、进程停即丢。
   这就是 runtime 不配 `:event-store` 时用的那个。`:buffer-size` 必须是正整数（装配期抛）。"
  ([] (ring-store nil))
  ([{:keys [buffer-size] :or {buffer-size 512}}]
   (when-not (and (integer? buffer-size) (pos? buffer-size))
     (throw (ex-info ":buffer-size 必须是正整数" {:buffer-size buffer-size})))
   (->RingEventStore (long buffer-size) (atom {}))))

;;; ============================================================
;;; runtime 侧的安全调用（存储抛异常不打断 run）
;;; ============================================================

(defn safe-append! [s conv-id event]
  (try (es-append! s conv-id event)
       (catch Throwable t
         (log/warn "agui event-store 追加失败（已吞，不影响 run）:" (.getMessage t)))))

(defn safe-last-seq [s conv-id]
  (try (es-last-seq s conv-id)
       (catch Throwable t
         (log/warn "agui event-store 取 last-seq 失败，序号从头开始:" (.getMessage t))
         nil)))

(defn safe-since [s conv-id since]
  (try (es-since s conv-id since)
       (catch Throwable t
         (log/warn "agui event-store 补发读取失败，按 gap 处理（走 resync）:" (.getMessage t))
         {:events [] :gap? (some? since)})))

(defn safe-release! [s conv-id]
  (try (es-release! s conv-id)
       (catch Throwable t (log/warn "agui event-store release 失败:" (.getMessage t)))))
