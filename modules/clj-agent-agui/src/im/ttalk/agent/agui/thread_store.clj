(ns im.ttalk.agent.agui.thread-store
  "线程**元数据**存储（designs/agui-borrow.md §6，对照 Keelson `AguiThreadStore.cs` /
   `InMemoryAguiThreadStore.cs`）：有哪些线程、叫什么、归没归档、属于哪台 agent、
   什么时候建 / 动过。

   为什么要有：`/threads` 原来直接列 runtime 的会话表——会话空闲被驱逐就**从
   列表里消失**（历史明明还在 ChatMemory 里），名字与归档标记只活在进程内、重启
   即失。`ChatMemory` 协议没有「列出所有会话」，用户自己补得写一张表再接进路由。

   **三样东西各管一件**：本存储管「有哪些线程、叫什么」，ChatMemory 管「说了
   什么」，runtime 管「现在在干嘛」。

   ⛔ **不存消息、不存事件**（与 docs/agent-runtime-design.md §8.1 / §8.2 一致）：
   消息的真相在 ChatMemory，暂停的真相在 PauseStore，事件缓冲是传输设施。这张表
   不是任何对话内容的副本，所以不构成第二真相源。Keelson 的进程内实现同时存
   消息与事件——那一半我们不跟（要跟得先推翻 §8.2，待用户拍板）。

   两个实现：本 ns 的 `in-memory-store`，以及按需加载的
   `im.ttalk.agent.agui.thread-store.sqlite`（不 require 它就不碰 JDBC）。

   记录形状（中立、毫秒时间）：

   ```clojure
   {:id \"t1\" :agent-id \"default\" :name nil :archived? false
    :created-at 1730000000000 :updated-at 1730000000000}
   ```

   wire 形状（ISO 时间、`organizationId` 占位…）由路由翻译，本 ns 不出现。")

(set! *warn-on-reflection* true)

(def all-slots
  "存储可以只给一部分能力；路由对缺的那几条回 501（Keelson `AguiThreadSlots`）。

   | 槽 | 管哪条路 |
   |---|---|
   | `:list`   | `GET /threads` |
   | `:update` | 改名 / 归档 / 取消归档 |
   | `:delete` | `DELETE /threads/:id` |

   `touch!` / `get` 不是可选的：没有它们这张表就没有意义。"
  #{:list :update :delete})

(defprotocol ThreadStore
  (ts-slots [s]
    "本存储给了哪些槽，`all-slots` 的子集。")
  (ts-touch! [s thread-id opts]
    "`opts` = `{:agent-id :now}`。没这条就建（`created-at` = `updated-at` = now），
     有就刷 `updated-at`。`agent-id` **只在建时写**——线程归属不随后来的请求漂移。
     返回记录。")
  (ts-get [s thread-id]
    "记录或 nil。")
  (ts-list [s query]
    "`query` = `{:agent-id :include-archived? :limit :cursor}`，返回
     `{:threads [记录...] :next-cursor 串或nil}`。语义见 `list-page`。")
  (ts-update! [s thread-id updates]
    "`updates` 认 `:name`（串或 nil）与 `:archived?`（布尔），没给的键不动；
     刷 `updated-at`。返回改后的记录，没这条返回 nil。")
  (ts-delete! [s thread-id]
    "返回是否真删了一条。"))

;;; ============================================================
;;; 两个实现共用的列表语义
;;; ============================================================

(defn- parse-nat
  "查询串里的数：字符串或数字都认；非数字 / 负数 → nil。"
  [v]
  (cond
    (integer? v) (when-not (neg? (long v)) (long v))
    (string? v)  (some-> (re-matches #"\d{1,9}" v) parse-long)
    :else nil))

(defn normalize-query
  "把路由交来的查询（值可能是串）规整成 `{:agent-id :include-archived? :limit :offset}`。

   - `limit` 非正 / 非数字 → 不限（nil）
   - `cursor` 是上一页回的 `next-cursor`（偏移量的字符串），认不出 → 从头
   - `include-archived?` 认布尔或串 `\"true\"`（大小写不敏感），缺省 **false**"
  [{:keys [agent-id include-archived? limit cursor]}]
  {:agent-id (when (and (string? agent-id) (seq agent-id)) agent-id)
   :include-archived? (cond
                        (boolean? include-archived?) include-archived?
                        (string? include-archived?) (= "true" (.toLowerCase ^String include-archived?))
                        :else false)
   :limit (let [n (parse-nat limit)] (when (and n (pos? n)) n))
   :offset (or (parse-nat cursor) 0)})

(defn sort-key
  "列表顺序：`updated-at` 新的在前，同刻按 `id` 升序（稳定，翻页不重不漏）。"
  [{:keys [updated-at id]}]
  [(- (long (or updated-at 0))) (str id)])

(defn list-page
  "对**全部记录**做过滤 + 排序 + 分页。in-memory 直接用它；sqlite 在 SQL 里做同样的
   事，测试用同一组用例钉住两边一致。"
  [records query]
  (let [{:keys [agent-id include-archived? limit offset]} (normalize-query query)
        all (->> records
                 (filter #(or (nil? agent-id) (= agent-id (:agent-id %))))
                 (filter #(or include-archived? (not (:archived? %))))
                 (sort-by sort-key compare)
                 vec)
        page (cond->> (drop offset all) limit (take limit))
        page (vec page)
        next-offset (+ offset (count page))]
    {:threads page
     :next-cursor (when (< next-offset (count all)) (str next-offset))}))

(defn apply-updates
  "`ts-update!` 的纯函数部分：只动给了的键。"
  [record updates now]
  (cond-> (assoc record :updated-at now)
    (contains? updates :name)      (assoc :name (some-> (:name updates) str))
    (contains? updates :archived?) (assoc :archived? (boolean (:archived? updates)))))

;;; ============================================================
;;; 进程内实现
;;; ============================================================

(defrecord InMemoryThreadStore [threads now]
  ThreadStore
  (ts-slots [_] all-slots)
  (ts-touch! [_ thread-id {:keys [agent-id] :as opts}]
    (let [t (or (:now opts) (now))]
      (get (swap! threads (fn [m]
                            (if-let [r (get m thread-id)]
                              (assoc m thread-id (assoc r :updated-at t))
                              (assoc m thread-id {:id thread-id
                                                  :agent-id agent-id
                                                  :name nil
                                                  :archived? false
                                                  :created-at t
                                                  :updated-at t}))))
           thread-id)))
  (ts-get [_ thread-id] (get @threads thread-id))
  (ts-list [_ query] (list-page (vals @threads) query))
  (ts-update! [_ thread-id updates]
    (let [t (now)
          m (swap! threads (fn [m]
                             (if-let [r (get m thread-id)]
                               (assoc m thread-id (apply-updates r updates t))
                               m)))]
      (get m thread-id)))
  (ts-delete! [_ thread-id]
    (let [[old _] (swap-vals! threads dissoc thread-id)]
      (contains? old thread-id))))

(defn in-memory-store
  "进程内线程存储：开发 / 测试 / 单进程小部署。进程一停全没了。

   - `:now` `(fn [] ms)`，可注入（测试）"
  ([] (in-memory-store nil))
  ([{:keys [now]}]
   (->InMemoryThreadStore (atom {}) (or now #(System/currentTimeMillis)))))

(defn has-slot?
  "存储给没给这个槽（nil 存储 = 什么都没给）。"
  [store slot]
  (boolean (and store (contains? (ts-slots store) slot))))
