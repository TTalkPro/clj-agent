(ns im.ttalk.agent.core.memory
  "ChatMemory - 按 conversation-id 管理中立消息历史

   这是 Memory Filter 的后端存储。只存中立消息（见 core/llm/message）。
   替代了旧 clj-agent-memory 模块中庞大的快照/时间线/多后端体系，
   这里只保留对话历史所需的极小子集。

   协议：
   - mem-get   [store conv-id]        -> [中立消息 ...]
   - mem-add   [store conv-id msgs]   追加
   - mem-clear [store conv-id]

   实现：
   - (in-memory-store)            atom 后端
   - (windowed store opts)        在 get 时按窗口裁剪（非破坏，底层仍存完整历史）"
  (:require [im.ttalk.agent.core.llm.message :as msg]))

;;; ============================================================
;;; 协议
;;; ============================================================

(defprotocol ChatMemory
  (mem-get   [this conv-id] "返回该会话的中立消息列表（无则 [])")
  (mem-add   [this conv-id msgs] "追加中立消息列表")
  (mem-clear [this conv-id] "清空该会话"))

;;; ============================================================
;;; in-memory 实现
;;; ============================================================

(defrecord InMemoryStore [state]            ; state: atom {conv-id -> [msgs]}
  ChatMemory
  (mem-get [_ conv-id]
    (get @state conv-id []))
  (mem-add [_ conv-id new-msgs]
    (let [norm (mapv msg/normalize new-msgs)]
      (swap! state update conv-id (fnil into []) norm))
    nil)
  (mem-clear [_ conv-id]
    (swap! state dissoc conv-id)
    nil))

(defn in-memory-store
  "创建 atom 后端的 ChatMemory"
  []
  (->InMemoryStore (atom {})))

;;; ============================================================
;;; 窗口裁剪（pairing-safe）
;;; ============================================================

(defn- safe-window
  "保留尾部 max-messages 条；若窗口头部是孤立的 :tool（其 assistant 工具调用
   被裁掉了），继续向后丢弃，直到头部是合法起点（非 :tool），避免 provider 报错。
   system 消息不计入窗口、始终保留在最前。"
  [msgs max-messages]
  (let [systems (filterv msg/system? msgs)
        body (filterv (complement msg/system?) msgs)
        windowed (if (and max-messages (> (count body) max-messages))
                   (subvec body (- (count body) max-messages))
                   body)
        ;; 丢弃头部孤立 tool 消息
        trimmed (loop [m windowed]
                  (if (and (seq m) (msg/tool? (first m)))
                    (recur (subvec m 1))
                    m))]
    (into systems trimmed)))

(defrecord WindowedStore [inner max-messages]
  ChatMemory
  (mem-get [_ conv-id]
    (safe-window (mem-get inner conv-id) max-messages))
  (mem-add [_ conv-id msgs]
    (mem-add inner conv-id msgs))
  (mem-clear [_ conv-id]
    (mem-clear inner conv-id)))

(defn windowed
  "包装一个 store，使 mem-get 只返回尾部 max-messages 条（pairing-safe）。
   底层仍保留完整历史。

   参数：
   - store: 被包装的 ChatMemory
   - opts:  {:max-messages N}"
  [store {:keys [max-messages]}]
  (->WindowedStore store max-messages))
