(ns im.ttalk.agent.core.pregel.vertex
  "Pregel Vertex - 顶点数据结构

   顶点是 Pregel 图中的计算单元。

   顶点结构:
   {:id vertex-id              ;; 顶点标识
    :value any                 ;; 顶点值
    :halted boolean            ;; 是否停止（不再激活）
    :compute-fn fn             ;; 计算函数
    :metadata map}             ;; 元数据

   Compute 函数签名:
   (fn [vertex context] -> {:value new-value :messages [...] :vote-to-halt boolean})")

;;; ============================================================
;;; 顶点创建
;;; ============================================================

(defn create-vertex
  "创建顶点

   参数:
   - id: 顶点标识
   - compute-fn: 计算函数 (fn [vertex context] -> result)
   - opts: 可选参数
     :value    - 初始值
     :metadata - 元数据

   返回: 顶点 map"
  [id compute-fn & {:keys [value metadata]
                    :or {value nil metadata {}}}]
  {:id id
   :value value
   :halted false
   :compute-fn compute-fn
   :metadata metadata})

(defn vertex?
  "检查是否为有效的顶点"
  [x]
  (and (map? x)
       (contains? x :id)
       (contains? x :compute-fn)))

;;; ============================================================
;;; 顶点属性访问
;;; ============================================================

(defn vertex-id
  "获取顶点 ID"
  [v]
  (:id v))

(defn vertex-value
  "获取顶点值"
  [v]
  (:value v))

(defn vertex-halted?
  "检查顶点是否已停止"
  [v]
  (:halted v))

(defn vertex-compute-fn
  "获取顶点计算函数"
  [v]
  (:compute-fn v))

(defn vertex-metadata
  "获取顶点元数据"
  [v]
  (:metadata v {}))

;;; ============================================================
;;; 顶点修改
;;; ============================================================

(defn set-value
  "设置顶点值

   参数:
   - vertex: 顶点
   - value: 新值

   返回: 更新后的顶点"
  [vertex value]
  (assoc vertex :value value))

(defn set-halted
  "设置顶点停止状态

   参数:
   - vertex: 顶点
   - halted: boolean

   返回: 更新后的顶点"
  [vertex halted]
  (assoc vertex :halted halted))

(defn halt
  "标记顶点为停止（不再激活）"
  [vertex]
  (set-halted vertex true))

(defn activate
  "激活顶点（取消停止状态）"
  [vertex]
  (set-halted vertex false))

(defn update-metadata
  "更新顶点元数据

   参数:
   - vertex: 顶点
   - f: 更新函数
   - args: 额外参数

   返回: 更新后的顶点"
  [vertex f & args]
  (apply update vertex :metadata f args))

;;; ============================================================
;;; 计算结果
;;; ============================================================

(defn compute-result
  "创建计算结果

   参数:
   - opts: 结果选项
     :value        - 新的顶点值
     :messages     - 发送的消息列表 [{:target vertex-id :data any}]
     :vote-to-halt - 是否投票停止

   返回: 计算结果 map"
  [{:keys [value messages vote-to-halt]
    :or {messages [] vote-to-halt false}}]
  {:value value
   :messages messages
   :vote-to-halt vote-to-halt})

(defn send-message
  "创建发送消息

   参数:
   - target: 目标顶点 ID
   - data: 消息数据

   返回: 消息 map"
  [target data]
  {:target target
   :data data})

(defn send-messages
  "创建发送给多个目标的消息

   参数:
   - targets: 目标顶点 ID 列表
   - data: 消息数据

   返回: 消息列表"
  [targets data]
  (mapv #(send-message % data) targets))

;;; ============================================================
;;; 计算上下文
;;; ============================================================

(defn create-context
  "创建计算上下文

   参数:
   - opts: 上下文选项
     :superstep     - 当前超步编号
     :num-vertices  - 图中顶点总数
     :global-state  - 全局状态
     :messages      - 收到的消息列表

   返回: 上下文 map"
  [{:keys [superstep num-vertices global-state messages]
    :or {superstep 0 num-vertices 0 global-state {} messages []}}]
  {:superstep superstep
   :num-vertices num-vertices
   :global-state global-state
   :messages messages})

(defn context-superstep
  "获取当前超步编号"
  [ctx]
  (:superstep ctx))

(defn context-num-vertices
  "获取顶点总数"
  [ctx]
  (:num-vertices ctx))

(defn context-global-state
  "获取全局状态"
  [ctx]
  (:global-state ctx))

(defn context-messages
  "获取收到的消息"
  [ctx]
  (:messages ctx))

(defn has-messages?
  "检查是否有消息"
  [ctx]
  (seq (:messages ctx)))
