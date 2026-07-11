(ns im.ttalk.agent.context
  "ToolContext - 请求/会话级的扁平状态 map

   重构后（Memory Filter 模式）：对话消息不再放在 Context，而是由
   记忆由 clj-agent-client 模块的 memory advisor 按 conversation-id 自管（core 对其零感知）。
   Context 退化为一个**单层扁平 map**，承载：
   - :conversation-id  会话 ID（Memory Filter 读取）
   - 任意工具/filter 的请求级共享 k/v（对标 Spring AI ToolContext）

   {:context true} 的工具拿到这个 map，可用 get-var/set-var 读写
   （请求级有效；跨轮累积请走 store）。

   使用示例:
   (def ctx (create {:conversation-id \"s1\" :user-id \"u1\"}))
   (get-var ctx :user-id)            ; => \"u1\"
   (set-var ctx :cart [{:item \"book\"}])")

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 创建
;;; ============================================================

(defn create
  "创建 ToolContext（扁平 map）

   参数:
   - vars-map: (可选) 初始 k/v"
  ([] {})
  ([vars-map] (or vars-map {})))

(defn context?
  "是否为 ToolContext（任意 map）"
  [x]
  (map? x))

;;; ============================================================
;;; 变量操作
;;; ============================================================

(defn get-var
  "读取变量"
  ([ctx k] (get ctx k))
  ([ctx k default] (get ctx k default)))

(defn set-var
  "设置变量，返回新 ctx"
  [ctx k v]
  (assoc ctx k v))

(defn set-vars
  "批量设置变量，返回新 ctx"
  [ctx vars-map]
  (merge ctx vars-map))

;;; ============================================================
;;; 批次写合并（Tool 阶段 MapReduce 的 reduce 半步）
;;; ============================================================

(defn apply-writes
  "把一批工具的写意图按序折叠进 ctx（纯函数）。

   参数:
   - ctx:        轮初 context（工具执行时拿到的同一份快照）
   - writes-seq: [{k v ...} ...] 每个元素是一个工具返回的 :writes map；
                 序列顺序必须是 tool-call 原始序（合并确定性的来源）
   - slots:      槽位声明 {k {:init v0 :reduce (fn [old new] merged)}}；
                 未声明的槽默认 last-writer（后写覆盖，按序确定）

   返回 {:context 新ctx :conflicts #{k ...}}
   conflicts = 同批被写 ≥2 次且未声明 reducer 的 key（调用方决定是否告警）。"
  [ctx writes-seq slots]
  (let [write-counts (frequencies (mapcat keys writes-seq))
        conflicts (into #{}
                        (keep (fn [[k n]]
                                (when (and (> n 1) (nil? (get-in slots [k :reduce])))
                                  k)))
                        write-counts)
        new-ctx (reduce
                  (fn [c writes]
                    (reduce-kv
                      (fn [c k v]
                        (if-let [rf (get-in slots [k :reduce])]
                          (assoc c k (rf (get c k (get-in slots [k :init])) v))
                          (assoc c k v)))
                      c writes))
                  ctx
                  writes-seq)]
    {:context new-ctx :conflicts conflicts}))

;;; ============================================================
;;; 会话 ID
;;; ============================================================

(defn conversation-id
  "读取会话 ID"
  [ctx]
  (:conversation-id ctx))

(defn with-conversation-id
  "设置会话 ID，返回新 ctx"
  [ctx conv-id]
  (assoc ctx :conversation-id conv-id))
