(ns im.ttalk.agent.core.kernel.context
  "Context - 贯穿 Kernel 调用链的一等数据结构

   Context 包含用户自定义状态（variables），双轨消息系统，
   filter 和 plugin/tool 均可读写。invoke 返回更新后的 context，
   外部可传入 context 再次调用 invoke。

   Context 数据结构:
   {:__context__ true
    :variables   {}        ;; 用户自定义状态 (keyword -> any)
    :messages    []        ;; 工作缓冲（发给 LLM，可 summarize/truncate）
    :history     []        ;; 完整对话日志（只追加）
    :kernel      nil       ;; Kernel 引用
    :trace       []        ;; 执行跟踪 [{:timestamp :type :data}]
    :metadata    {}}       ;; 元数据

   双轨消息系统:
   - messages: 发给 LLM 的工作缓冲，可被 summarize 或 truncate 重置
   - history:  完整对话日志，只追加不删减
   - track-message: 同时追加到 messages 和 history

   使用示例:

   (def ctx (create {:user-id \"u123\" :cart []}))
   (def ctx2 (set-var ctx :cart [{:item \"book\" :qty 1}]))
   (get-var ctx2 :cart)
   ;; => [{:item \"book\" :qty 1}]")

;;; ============================================================
;;; Context 创建
;;; ============================================================

(defn create
  "创建 Context

   参数:
   - vars-map: (可选) 初始变量 map (keyword -> any)

   返回:
   Context map"
  ([]
   (create {}))
  ([vars-map]
   {:__context__ true
    :variables   (or vars-map {})
    :messages    []
    :history     []
    :kernel      nil
    :trace       []
    :metadata    {}}))

(defn context?
  "判断 x 是否为 Context

   参数:
   - x: 任意值

   返回: boolean"
  [x]
  (and (map? x) (true? (:__context__ x))))

;;; ============================================================
;;; Variables 操作
;;; ============================================================

(defn get-var
  "读取 Context 变量

   参数:
   - ctx:     Context
   - k:       变量名（keyword）
   - default: (可选) 默认值

   返回: 变量值或 default"
  ([ctx k]
   (get-var ctx k nil))
  ([ctx k default]
   (get-in ctx [:variables k] default)))

(defn set-var
  "设置 Context 变量

   参数:
   - ctx: Context
   - k:   变量名（keyword）
   - v:   变量值

   返回: 更新后的 Context"
  [ctx k v]
  (assoc-in ctx [:variables k] v))

(defn set-vars
  "批量设置 Context 变量

   参数:
   - ctx:      Context
   - vars-map: 变量 map (keyword -> any)

   返回: 更新后的 Context"
  [ctx vars-map]
  (update ctx :variables merge vars-map))

;;; ============================================================
;;; Messages 操作（工作缓冲）
;;; ============================================================

(defn get-messages
  "获取 messages 工作缓冲

   参数:
   - ctx: Context

   返回: 消息列表"
  [ctx]
  (:messages ctx))

(defn set-messages
  "替换 messages 工作缓冲（用于 summarize 后重置）

   参数:
   - ctx:  Context
   - msgs: 新的消息列表

   返回: 更新后的 Context"
  [ctx msgs]
  (assoc ctx :messages (vec msgs)))

(defn append-message
  "追加消息到 messages 工作缓冲末尾

   参数:
   - ctx: Context
   - msg: 消息 map

   返回: 更新后的 Context"
  [ctx msg]
  (update ctx :messages conj msg))

;;; ============================================================
;;; History 操作（完整日志，只追加）
;;; ============================================================

(defn get-history
  "获取完整对话历史

   参数:
   - ctx: Context

   返回: 消息列表"
  [ctx]
  (:history ctx))

(defn add-history
  "追加消息到 history 末尾（只追加）

   参数:
   - ctx: Context
   - msg: 消息 map

   返回: 更新后的 Context"
  [ctx msg]
  (update ctx :history conj msg))

;;; ============================================================
;;; 双轨消息操作
;;; ============================================================

(defn track-message
  "同时追加到 messages 和 history

   参数:
   - ctx: Context
   - msg: 消息 map

   返回: 更新后的 Context"
  [ctx msg]
  (-> ctx
      (append-message msg)
      (add-history msg)))

;;; ============================================================
;;; Kernel 关联
;;; ============================================================

(defn with-kernel
  "关联 Kernel 到 Context

   参数:
   - ctx:    Context
   - kernel: Kernel 实例

   返回: 更新后的 Context"
  [ctx kernel]
  (assoc ctx :kernel kernel))

(defn get-kernel
  "获取关联的 Kernel

   参数:
   - ctx: Context

   返回: Kernel 实例或 nil"
  [ctx]
  (:kernel ctx))

;;; ============================================================
;;; Trace 操作
;;; ============================================================

(defn add-trace
  "添加跟踪条目

   参数:
   - ctx:   Context
   - entry: 跟踪条目 map (会自动添加 :timestamp)

   返回: 更新后的 Context"
  [ctx entry]
  (update ctx :trace conj
          (assoc entry :timestamp (System/currentTimeMillis))))

(defn get-trace
  "获取执行跟踪

   参数:
   - ctx: Context

   返回: 跟踪条目列表"
  [ctx]
  (:trace ctx))

;;; ============================================================
;;; Metadata 操作
;;; ============================================================

(defn get-metadata
  "获取元数据

   参数:
   - ctx: Context

   返回: 元数据 map"
  [ctx]
  (:metadata ctx))

(defn set-metadata
  "设置元数据字段

   参数:
   - ctx: Context
   - k:   元数据键
   - v:   元数据值

   返回: 更新后的 Context"
  [ctx k v]
  (assoc-in ctx [:metadata k] v))
