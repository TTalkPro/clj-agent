(ns im.ttalk.agent.process.builder
  "Process Builder：纯数据组装 + build 时校验。

   (-> (builder :doc-generation)
       (add-step {:id :gather-info
                  :on-activate (fn [inputs state ctx] ...)
                  :required-inputs [:product-name]})
       (add-step {:id :generate-docs
                  :on-activate (fn [inputs state ctx] ...)
                  :required-inputs [:product-info]})
       (on-event :start :gather-info :product-name)
       (on-event :info-gathered :generate-docs :product-info)
       (set-initial-event :start \"GlowBrew\")
       (build))
   ;; => process-spec，交给 runtime/run-process")

(set! *warn-on-reflection* true)

(defn builder
  "创建空 builder。"
  [process-name]
  {:name           process-name
   :steps          {}
   :step-order     []          ;; 保留注册顺序 → 同批激活时的确定性执行序
   :bindings       []
   :initial-events []
   :error-handler  nil})

(defn add-step
  "注册 step。:id 与 :on-activate 必填；:required-inputs 缺省 [:input]。
   重复 :id 立即抛错。"
  [b step-spec]
  (let [{:keys [id on-activate]} step-spec]
    (when-not (keyword? id)
      (throw (ex-info "step 需要 keyword :id" {:step step-spec})))
    (when (contains? (:steps b) id)
      (throw (ex-info (str "step 重复注册: " id) {:id id})))
    (when-not (fn? on-activate)
      (throw (ex-info (str "step " id " 缺少 :on-activate 函数") {:id id})))
    (-> b
        (assoc-in [:steps id]
                  (merge {:required-inputs [:input]} step-spec))
        (update :step-order conj id))))

(defn on-event
  "声明事件绑定：event-name 的事件投递到 target-step 的 target-input 槽
   （缺省 :input）。opts 支持 :transform (fn [data] -> data)。"
  ([b event-name target-step]
   (on-event b event-name target-step :input nil))
  ([b event-name target-step target-input]
   (on-event b event-name target-step target-input nil))
  ([b event-name target-step target-input {:keys [transform] :as _opts}]
   (update b :bindings conj {:event-name   event-name
                             :target-step  target-step
                             :target-input (or target-input :input)
                             :transform    transform})))

(defn set-initial-event
  "追加初始事件（可多次调用）。"
  [b event-name data]
  (update b :initial-events conj {:name event-name :data data :type :public}))

(defn on-error
  "配置 error-handler step：step 出错时，错误以 :error 事件投递到该 step 的
   :input 槽（而非直接 :failed）。"
  [b step-id]
  (assoc b :error-handler step-id))

(defn- validate!
  [{:keys [steps bindings error-handler] :as b}]
  (doseq [{:keys [event-name target-step]} bindings]
    (when-not (keyword? event-name)
      (throw (ex-info "binding 需要 keyword :event-name" {:binding event-name})))
    (when-not (contains? steps target-step)
      (throw (ex-info (str "binding 指向未注册的 step: " target-step)
                      {:target target-step :known (keys steps)}))))
  (when (and error-handler (not (contains? steps error-handler)))
    (throw (ex-info (str "error-handler 指向未注册的 step: " error-handler)
                    {:error-handler error-handler})))
  b)

(defn build
  "校验并产出 process-spec（纯数据，可复用于多次 run）。"
  [b]
  (-> b validate! (select-keys [:name :steps :step-order :bindings
                                :initial-events :error-handler])))
