(ns im.ttalk.agent.server.agui.thread-store
  "`…/threads*` 那七条路背后的存储（C# Keelson.Agui 的 IAguiThreadStore / AguiThreadStore 委托版）。

   **契约是 C# 的六个可选槽**，在这里写成一个 fn 的 map —— 给了哪个键就亮哪个槽，没给的那几条路回 501：

     :list     (fn [query] …)           GET /threads。query = 查询串原样 {\"agentId\" \"echo\" \"limit\" \"2\"}（值全是串，同名取第一个）。
                                        回线程记录的序列 ⇒ 补成 {threads, nextCursor: null}；回带 :threads 的 map ⇒ 原样出（缺 nextCursor 补 null）
     :messages (fn [thread-id] …)       GET /threads/:id/messages ⇒ {messages}；nil = 没这条线程（404）
     :events   (fn [thread-id] …)       GET /threads/:id/events ⇒ {events}；nil = 没这条线程
     :state    (fn [thread-id] …)       GET /threads/:id/state。回 nil = 没这条线程（404）；回 {:state v} = 有（v 可以是 nil ⇒ {\"state\":null}）
     :update   (fn [thread-id agent-id updates] …)  PATCH /threads/:id（改名 / 取消归档）与 POST /threads/:id/archive（updates = {:archived true}）。
                                        updates 已去掉 agentId / userId。回改过的那条线程记录（PATCH ⇒ **裸的**出）；nil = 没这条线程
     :delete   (fn [thread-id agent-id] …)  DELETE /threads/:id。false = 没这条线程

   外加一个可选钩子（C# IAguiThreadRecorder）：
     :record   (fn [agent-id input agui-event])  每台 run 的每条 AG-UI 事件（RUN_STARTED 是第一条，input 是那台的 RunAgentInput）。
                                        在泵上同步调：要快；抛了吞掉（⛔ 不打断 run）。`/suggest` 不喂

   抛异常 ⇒ 500 `thread_store_error`，带着异常的话（⛔ 不吞）。

   **接 clj-agent-agui**：它的 `thread-store/ThreadStore` 只管元数据（三个槽：list / update / delete），消息在 ChatMemory、事件在
   runtime 的事件存储 —— `from-clj-agent` 把这三样拼成上面的六槽 map（host 收到一个 ThreadStore 时自动套上）。"
  (:require [im.ttalk.agent.agui.codec :as codec]
            [im.ttalk.agent.agui.registry :as registry]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.agui.state :as agui-state]
            [im.ttalk.agent.agui.thread-store :as ts]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.server.strings :refer [msg]]
            [im.ttalk.agent.server.strings :as err])
  (:import (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)))

(set! *warn-on-reflection* true)

(def slot-keys [:list :messages :events :state :update :delete])

(def slot-names
  "槽 ⇒ 进 501 文案的名字（就是槽的关键字；C# 版这里是 AguiThreadSlots 的名字）。"
  {:list ":list" :messages ":messages" :events ":events" :state ":state" :update ":update" :delete ":delete"})

(defn has-slot? [store slot] (fn? (get store slot)))

(defn slots [store] (into #{} (filter #(has-slot? store %)) slot-keys))

(defn mutations?
  "`threadEndpoints.mutations`：update 与 delete 都给了。"
  [store]
  (and (has-slot? store :update) (has-slot? store :delete)))

;; ── 接 clj-agent-agui ────────────────────────────────

(def ^:private ^DateTimeFormatter iso-ms
  (.withZone (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'") ZoneOffset/UTC))

(defn- iso [ms] (.format iso-ms (Instant/ofEpochMilli (long (or ms (System/currentTimeMillis))))))

(defn- entry-of-thread
  "线程归哪台 agent：线程表里记着 agentId 就按它找，找不到退回第一台。"
  [reg rec]
  (or (registry/find-agent reg (:agent-id rec)) (registry/default-agent reg)))

(defn thread-record
  "中立记录 ⇒ C# InMemoryAguiThreadStore 的线程记录形状（organizationId / createdById 为空串，时间带毫秒的 ISO-8601 UTC）。"
  [reg rec]
  {:id (:id rec)
   :name (or (:name rec) "")
   :agentId (or (:agent-id rec) (:id (entry-of-thread reg rec)))
   :organizationId ""
   :createdById ""
   :archived (boolean (:archived? rec))
   :createdAt (iso (:created-at rec))
   :updatedAt (iso (:updated-at rec))})

(def ^:private name-max 60)

(defn- name-of
  "线程名缺省取第一条 user 消息的文本（截到 60 字；C# InMemoryAguiThreadStore 同）。"
  [messages]
  (some (fn [m]
          (let [c (:content m)]
            (when (and (= "user" (:role m)) (string? c) (seq c))
              (if (<= (count c) name-max) c (subs c 0 name-max)))))
        messages))

(defn- fold-state
  "最近一轮的状态：那一轮客户端带来的 state 作基线，再叠上那一轮之后缓冲里的快照 / 增量。"
  [base events]
  (let [tail (reverse (take-while #(not= :run/started (:type %)) (reverse events)))]
    (reduce (fn [st ev]
              (case (:type ev)
                :state/snapshot (:state ev)
                :state/delta (agui-state/apply-ops st (:delta ev))
                st))
            base tail)))

(defn from-clj-agent
  "clj-agent-agui 的线程存储（元数据）+ 注册表 ⇒ 六槽 map。
   - list / update / delete 跟着 `ts` 给的槽；messages 在至少一台 agent 配了 `:memory` 时才亮（读那台的 ChatMemory）；
     events / state 一直亮（runtime 的事件存储，缺省有界环形缓冲、不落库 —— 见 clj-agent-agui 的 event-store）。
   - :record 在 RUN_STARTED 上 `ts-touch!`（线程从此在 /threads 里），新线程按第一条 user 消息起名，记下那一轮的 state 作基线。
   - update / delete 的 agentId 对不上 ⇒ 当作没这条线程（C# 同）。delete = 摘会话（`rt/purge!`）+ 清 ChatMemory + 删记录。"
  [store reg]
  (let [bases (atom {})
        rec-of (fn [tid] (ts/ts-get store tid))
        owned (fn [tid agent-id] (let [r (rec-of tid)] (when (and r (= agent-id (:agent-id r))) r)))
        events-of (fn [tid] (let [{:keys [runtime]} (entry-of-thread reg (rec-of tid))]
                              (or (rt/buffered-events runtime tid) [])))]
    (cond-> {:events (fn [tid] (when (rec-of tid) (codec/events->agui (events-of tid))))
             :state (fn [tid] (when (rec-of tid) {:state (fold-state (get @bases tid) (events-of tid))}))
             :record (fn [agent-id input agui]
                       (when (= "RUN_STARTED" (:type agui))
                         (let [tid (:threadId input)
                               fresh? (nil? (rec-of tid))]
                           (ts/ts-touch! store tid {:agent-id agent-id})
                           (swap! bases assoc tid (:state input))
                           (when (and fresh? (ts/has-slot? store :update))
                             (when-let [n (name-of (:messages input))]
                               (ts/ts-update! store tid {:name n}))))))}
      (ts/has-slot? store :list)
      (assoc :list (fn [q]
                     (let [{:keys [threads next-cursor]}
                           (ts/ts-list store {:agent-id (get q "agentId") :include-archived? (get q "includeArchived")
                                              :limit (get q "limit") :cursor (get q "cursor")})]
                       {:threads (mapv #(thread-record reg %) threads) :nextCursor next-cursor})))

      (some :memory (registry/agents reg))
      (assoc :messages (fn [tid]
                         (when-let [r (rec-of tid)]
                           (let [mem (:memory (entry-of-thread reg r))]
                             (if mem (codec/messages->thread-messages (memory/mem-get mem tid)) [])))))

      (ts/has-slot? store :update)
      (assoc :update (fn [tid agent-id updates]
                       (when (owned tid agent-id)
                         (some->> (ts/ts-update! store tid (cond-> {}
                                                             (contains? updates :name) (assoc :name (:name updates))
                                                             (contains? updates :archived) (assoc :archived? (boolean (:archived updates)))))
                                  (thread-record reg)))))

      (ts/has-slot? store :delete)
      (assoc :delete (fn [tid agent-id]
                       (if-let [r (owned tid agent-id)]
                         (let [{:keys [runtime memory]} (entry-of-thread reg r)]
                           (rt/purge! runtime tid)
                           (when memory (memory/mem-clear memory tid))
                           (swap! bases dissoc tid)
                           (ts/ts-delete! store tid))
                         false))))))

(defn normalize
  "宿主给的存储 ⇒ 六槽 map：nil ⇒ nil；clj-agent-agui 的 ThreadStore ⇒ `from-clj-agent`；map 原样。
   一个槽都不给 ⇒ 抛 `agui-registry/thread-store-no-slot`（它的表现与「没注册」一模一样，多半是写漏了）。"
  [store reg]
  (when (some? store)
    (let [m (if (satisfies? ts/ThreadStore store) (from-clj-agent store reg) store)]
      (when-not (and (map? m) (seq (slots m)))
        (throw (err/error (msg :agui-registry/thread-store-no-slot) {:im.ttalk.agent.server/kind :argument})))
      m)))
