(ns im.ttalk.agent.core.kernel.context
  "ToolContext - 请求/会话级的扁平状态 map

   重构后（Memory Filter 模式）：对话消息不再放在 Context，而是由
   Memory Filter 按 conversation-id 存进 ChatMemory store（见 core/memory）。
   Context 退化为一个**单层扁平 map**，承载：
   - :conversation-id  会话 ID（Memory Filter 读取）
   - 任意工具/filter 的请求级共享 k/v（对标 Spring AI ToolContext）

   {:context true} 的工具拿到这个 map，可用 get-var/set-var 读写
   （请求级有效；跨轮累积请走 store）。

   使用示例:
   (def ctx (create {:conversation-id \"s1\" :user-id \"u1\"}))
   (get-var ctx :user-id)            ; => \"u1\"
   (set-var ctx :cart [{:item \"book\"}])")

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
