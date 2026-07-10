(ns im.ttalk.agent.process.event
  "Process 事件：创建与路由（纯函数）。

   事件形状：
   {:name   :event-name    ;; 事件名（keyword）
    :source :step-id       ;; 产出方 step（runtime 自动填充；初始事件为 nil）
    :data   any            ;; 载荷
    :type   :public}       ;; :public | :internal | :error

   绑定（Edge）形状：
   {:event-name   :info-gathered   ;; 匹配的事件名
    :target-step  :generate-docs   ;; 目标 step
    :target-input :product-info    ;; 投递到目标 step 的哪个输入槽
    :transform    nil}             ;; 可选 (fn [data] -> data)")

(set! *warn-on-reflection* true)

(defn make-event
  "创建事件。type 缺省 :public。"
  ([name data] (make-event name data nil))
  ([name data {:keys [source type]}]
   {:name   name
    :source source
    :data   data
    :type   (or type :public)}))

(defn error-event
  "由 step 错误构造 :error 类型事件（供 error-handler 消费）。
   data 为 {:reason ... :step 出错的 step-id ...}。"
  [source-step error]
  (make-event :error (assoc error :step source-step)
              {:source source-step :type :error}))

(defn route
  "事件 → 通过 bindings 匹配 → 投递列表。

   返回 [{:step target-step :input target-input :data 载荷} ...]；
   transform 存在时对 data 先行变换。无匹配返回空。"
  [event bindings]
  (into []
        (keep (fn [{:keys [event-name target-step target-input transform]}]
                (when (= event-name (:name event))
                  {:step  target-step
                   :input target-input
                   :data  (if transform (transform (:data event)) (:data event))})))
        bindings))
