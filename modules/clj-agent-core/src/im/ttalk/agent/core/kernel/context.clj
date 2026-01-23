(ns im.ttalk.agent.core.kernel.context
  "Context - 贯穿 Kernel 调用链的一等数据结构

   参考 beamai_context.erl 设计，Context 包含用户自定义状态（variables），
   filter 和 plugin/tool 均可读写。invoke 返回更新后的 context，
   外部可传入 context 再次调用 invoke。

   Context 数据结构:
   {:__context__ true
    :variables   {}        ;; 用户自定义状态 (keyword -> any)
    :history     []        ;; 消息历史
    :kernel      nil       ;; Kernel 引用
    :trace       []        ;; 执行跟踪 [{:timestamp :type :data}]
    :metadata    {}}       ;; 元数据

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
;;; History 操作
;;; ============================================================

(defn get-history
  "获取消息历史

   参数:
   - ctx: Context

   返回: 消息列表"
  [ctx]
  (:history ctx))

(defn add-message
  "添加消息到历史

   参数:
   - ctx: Context
   - msg: 消息 map

   返回: 更新后的 Context"
  [ctx msg]
  (update ctx :history conj msg))

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

;;; ============================================================
;;; 兼容函数（供 filter 系统使用）
;;; ============================================================

(defn create-tool-context
  "构建工具调用 context map（兼容旧接口）

   参数:
   - tool-name: 工具名称（关键字）
   - tool-args: 工具参数（map）
   - tool-id:   工具调用 ID（字符串）

   返回:
   {:tool-name :keyword :tool-args map :tool-id string}"
  [tool-name tool-args tool-id]
  {:tool-name tool-name
   :tool-args (or tool-args {})
   :tool-id   tool-id})

(defn create-invocation-context
  "构建完整调用上下文（含 kernel/history/context）

   在基础 tool context 上添加 kernel、history 和完整 Context 信息，
   供 Filter 链使用。

   参数:
   - tool-context: 基础工具 context（由 create-tool-context 创建）
   - kernel:       Kernel 实例
   - history:      当前对话历史（messages vector）
   - context:      (可选) 完整 Context 对象

   返回:
   完整的 filter context map"
  ([tool-context kernel history]
   (create-invocation-context tool-context kernel history (create)))
  ([tool-context kernel history context]
   (assoc tool-context
          :kernel kernel
          :history history
          :context context)))
