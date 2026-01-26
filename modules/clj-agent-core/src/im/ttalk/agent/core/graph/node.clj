(ns im.ttalk.agent.core.graph.node
  "Graph Node - 节点定义

   节点是图中的计算单元，每个节点包含一个 handler 函数。

   Handler 签名:
   (fn [state vertex-input] -> result)

   - state: 全局状态 (graph.state)
   - vertex-input: 来自 dispatch 的输入参数（可选，普通激活时为 nil）

   返回值类型:
   - {:ok new-state}                     成功，返回新状态
   - {:error reason}                     失败
   - {:interrupt {:reason r :state s}}   暂停（human-in-the-loop）
   - {:command {:update delta :goto targets}} Command 模式

   使用示例:

   (def my-node
     (create-node :process
       (fn [state _]
         {:ok (assoc state :processed true)})))

   ;; 带选项
   (def my-node
     (create-node :process handler
       :metadata {:description \"处理数据\"}
       :timeout 5000))")

;;; ============================================================
;;; 特殊节点常量
;;; ============================================================

(def START
  "图的起始节点标识"
  :__start__)

(def END
  "图的终止节点标识"
  :__end__)

;;; ============================================================
;;; 节点创建
;;; ============================================================

(defn create-node
  "创建节点定义

   参数:
   - id: 节点标识（keyword）
   - handler: 处理函数 (fn [state vertex-input] -> result)
   - opts: 可选参数
     :metadata  - 节点元数据 map
     :timeout   - 执行超时（毫秒）
     :retry     - 重试次数

   返回: 节点定义 map"
  [id handler & {:keys [metadata timeout retry]
                 :or {metadata {} timeout nil retry 0}}]
  (when-not (keyword? id)
    (throw (ex-info "节点 id 必须是 keyword" {:id id})))
  (when-not (fn? handler)
    (throw (ex-info "节点 handler 必须是函数" {:id id})))
  {:id id
   :handler handler
   :metadata metadata
   :timeout timeout
   :retry retry})

(defn node?
  "检查是否为有效的节点定义"
  [x]
  (and (map? x)
       (keyword? (:id x))
       (fn? (:handler x))))

;;; ============================================================
;;; 节点属性访问
;;; ============================================================

(defn node-id
  "获取节点 ID"
  [node]
  (:id node))

(defn node-handler
  "获取节点 handler"
  [node]
  (:handler node))

(defn node-metadata
  "获取节点元数据"
  [node]
  (:metadata node {}))

(defn node-timeout
  "获取节点超时设置"
  [node]
  (:timeout node))

;;; ============================================================
;;; 结果构造器
;;; ============================================================

(defn ok
  "构造成功结果

   参数:
   - state: 新状态

   返回: {:ok state}"
  [state]
  {:ok state})

(defn error
  "构造错误结果

   参数:
   - reason: 错误原因（字符串或 map）

   返回: {:error reason}"
  [reason]
  {:error reason})

(defn interrupt
  "构造中断结果（human-in-the-loop）

   参数:
   - reason: 中断原因
   - state: 当前状态（可选）

   返回: {:interrupt {:reason r :state s}}"
  ([reason]
   {:interrupt {:reason reason}})
  ([reason state]
   {:interrupt {:reason reason :state state}}))

(defn command
  "构造 Command 结果

   Command 模式允许：
   - 直接指定 delta（跳过 diff 计算）
   - 覆盖默认路由（goto）

   参数:
   - opts: 选项 map
     :update - delta map（增量更新）
     :goto   - 目标节点或节点列表

   返回: {:command {...}}"
  [{:keys [update goto]}]
  {:command {:update (or update {})
             :goto goto}})

;;; ============================================================
;;; 结果检查
;;; ============================================================

(defn ok?
  "检查是否为成功结果"
  [result]
  (contains? result :ok))

(defn error?
  "检查是否为错误结果"
  [result]
  (contains? result :error))

(defn interrupt?
  "检查是否为中断结果"
  [result]
  (contains? result :interrupt))

(defn command?
  "检查是否为 Command 结果"
  [result]
  (contains? result :command))

;;; ============================================================
;;; 结果解析
;;; ============================================================

(defn result-state
  "从结果中提取状态

   - ok: 返回新状态
   - interrupt: 返回中断时的状态
   - 其他: 返回 nil"
  [result]
  (cond
    (ok? result) (:ok result)
    (interrupt? result) (get-in result [:interrupt :state])
    :else nil))

(defn result-error
  "从错误结果中提取错误信息"
  [result]
  (:error result))

(defn result-interrupt-reason
  "从中断结果中提取原因"
  [result]
  (get-in result [:interrupt :reason]))

(defn result-command
  "从 Command 结果中提取命令"
  [result]
  (:command result))

;;; ============================================================
;;; 特殊节点判断
;;; ============================================================

(defn start-node?
  "检查是否为起始节点"
  [node-or-id]
  (= START (if (map? node-or-id) (:id node-or-id) node-or-id)))

(defn end-node?
  "检查是否为终止节点"
  [node-or-id]
  (= END (if (map? node-or-id) (:id node-or-id) node-or-id)))
