(ns im.ttalk.agent.core.kernel.process.event
  "Process Event - 事件创建与路由

   事件是 Process 中 Step 之间通信的载体。
   Binding 定义事件到目标 Step 输入槽的路由规则。

   事件结构:
   {:name   :event-name      ;; 事件名称（用于路由匹配）
    :source :step-id         ;; 产出此事件的 step（自动填充）
    :data   any              ;; 事件数据
    :type   :public}         ;; :public | :internal | :error

   绑定结构:
   {:event-name   :event-name
    :target-step  :step-id
    :target-input :input-name
    :transform    nil}         ;; 可选 (fn [data] -> data)"
  (:refer-clojure :exclude [binding]))

;;; ============================================================
;;; 事件创建
;;; ============================================================

(defn create
  "创建事件

   参数:
   - name: 事件名称（keyword）
   - data: 事件数据

   返回: 事件 map"
  ([name]
   (create name nil))
  ([name data]
   {:name   name
    :source nil
    :data   data
    :type   :public}))

(defn error-event
  "创建错误事件

   参数:
   - source: 来源 step id
   - reason: 错误原因

   返回: 错误事件 map"
  [source reason]
  {:name   :error
   :source source
   :data   {:reason reason}
   :type   :error})

(defn external-event
  "创建外部事件

   外部事件用于从 process 外部注入事件，使运行中的 process
   能够接收来自外部系统的输入（如用户输入、webhook 回调等）。

   参数:
   - name: 事件名称（keyword）
   - data: 事件数据（可选）

   返回: 外部事件 map"
  ([name]
   (external-event name nil))
  ([name data]
   {:name   name
    :source :external
    :data   data
    :type   :external}))

(defn with-source
  "为事件标记来源 step

   参数:
   - event:  事件 map
   - source: step id

   返回: 更新后的事件"
  [event source]
  (assoc event :source source))

;;; ============================================================
;;; 绑定创建
;;; ============================================================

(defn binding
  "创建事件绑定（Edge）

   参数:
   - event-name:   要匹配的事件名称
   - target-step:  目标 step id
   - target-input: 目标 step 的输入槽名称
   - transform:    (可选) 数据转换函数

   返回: 绑定 map"
  ([event-name target-step target-input]
   (binding event-name target-step target-input nil))
  ([event-name target-step target-input transform]
   {:event-name   event-name
    :target-step  target-step
    :target-input target-input
    :transform    transform}))

;;; ============================================================
;;; 事件路由
;;; ============================================================

(defn route
  "路由事件到目标 step

   根据事件名称匹配 bindings，产出 delivery 列表。

   参数:
   - event:    事件 map
   - bindings: 绑定列表

   返回:
   delivery 列表 [{:step-id :input-name :data} ...]"
  [event bindings]
  (let [event-name (:name event)
        matching (filter #(= event-name (:event-name %)) bindings)]
    (mapv (fn [b]
            (let [data (:data event)
                  transformed (if-let [t (:transform b)]
                                (t data)
                                data)]
              {:step-id    (:target-step b)
               :input-name (:target-input b)
               :data       transformed}))
          matching)))
