(ns im.ttalk.agent.core.kernel.process.builder
  "Process Builder - 构建 Process 定义

   提供链式 API 构建 process-spec，编译时验证。

   使用示例:

   (-> (builder :my-process)
       (add-step {:id :step-a
                  :on-activate (fn [inputs state ctx] ...)
                  :required-inputs [:data]})
       (add-step {:id :step-b
                  :on-activate (fn [inputs state ctx] ...)})
       (on-event :start :step-a :data)
       (on-event :step-a-done :step-b :input)
       (set-initial-event :start \"initial data\")
       (build))"
  (:require [im.ttalk.agent.core.kernel.process.event :as event]))

;;; ============================================================
;;; Builder 创建
;;; ============================================================

(defn builder
  "创建 Process Builder

   参数:
   - name: process 名称（keyword）

   返回: builder map"
  [name]
  {:__process_builder__ true
   :name                name
   :steps               {}
   :bindings            []
   :initial-events      []
   :error-handler       nil})

;;; ============================================================
;;; Builder API
;;; ============================================================

(defn add-step
  "添加 Step 到 builder

   step-spec 必须包含:
   - :id           step 标识（keyword）
   - :on-activate  激活函数 (fn [inputs state context] -> result)

   可选:
   - :init            初始化函数 (fn [config] -> state)
   - :can-activate?   激活守卫 (fn [inputs state] -> boolean)
   - :on-resume       恢复函数 (fn [data state context] -> result)
   - :on-terminate    终止清理函数 (fn [state context] -> nil)
   - :required-inputs 必需输入列表（默认 [:input]）
   - :config          配置 map

   参数:
   - b:        builder
   - step-spec: step 定义 map

   返回: 更新后的 builder"
  [b step-spec]
  (let [step-id (:id step-spec)]
    (when-not step-id
      (throw (ex-info "Step 定义缺少 :id" {:step-spec step-spec})))
    (when-not (:on-activate step-spec)
      (throw (ex-info "Step 定义缺少 :on-activate" {:step-id step-id})))
    (assoc-in b [:steps step-id] step-spec)))

(defn on-event
  "添加事件绑定（Edge）

   将名为 event-name 的事件路由到 target-step 的 target-input 输入槽。

   参数:
   - b:            builder
   - event-name:   事件名称
   - target-step:  目标 step id
   - target-input: 目标输入槽名称
   - transform:    (可选) 数据转换函数

   返回: 更新后的 builder"
  ([b event-name target-step target-input]
   (on-event b event-name target-step target-input nil))
  ([b event-name target-step target-input transform]
   (update b :bindings conj
           (event/binding event-name target-step target-input transform))))

(defn set-initial-event
  "设置初始事件（process 启动时触发）

   参数:
   - b:    builder
   - name: 事件名称
   - data: (可选) 事件数据

   返回: 更新后的 builder"
  ([b name]
   (set-initial-event b name nil))
  ([b name data]
   (update b :initial-events conj (event/create name data))))

(defn set-error-handler
  "设置错误处理 step

   当 step 执行出错时，错误事件会路由到此 step。

   参数:
   - b:       builder
   - step-id: 错误处理 step 的 id

   返回: 更新后的 builder"
  [b step-id]
  (assoc b :error-handler step-id))

(defn on-external-event
  "添加外部事件绑定

   将名为 event-name 的外部事件路由到 target-step。
   用于声明式定义 Step 如何响应外部事件。

   外部事件通过 runtime/send-event 发送，与内部事件使用相同的路由机制。
   此函数是 on-event 的语义别名，用于明确表示事件来源是外部系统。

   参数:
   - b:            builder
   - event-name:   外部事件名称
   - target-step:  目标 step id
   - target-input: 目标输入槽名称
   - transform:    (可选) 数据转换函数

   返回: 更新后的 builder

   示例:
   (-> (builder :interactive)
       (add-step {:id :responder
                  :on-activate (fn [inputs state ctx]
                                 {:events [{:name :response :data ...}]})})
       (on-external-event :user-input :responder :input)
       (build))"
  ([b event-name target-step target-input]
   (on-external-event b event-name target-step target-input nil))
  ([b event-name target-step target-input transform]
   ;; 外部事件绑定与普通绑定相同，只是语义上区分
   (update b :bindings conj
           (event/binding event-name target-step target-input transform))))

;;; ============================================================
;;; 编译验证
;;; ============================================================

(defn- validate-bindings
  "验证所有绑定的 target-step 存在"
  [steps bindings]
  (doseq [b bindings]
    (when-not (contains? steps (:target-step b))
      (throw (ex-info (str "绑定目标 step 不存在: " (:target-step b))
                      {:binding b
                       :available-steps (keys steps)})))))

(defn- validate-error-handler
  "验证 error-handler step 存在"
  [steps error-handler]
  (when (and error-handler (not (contains? steps error-handler)))
    (throw (ex-info (str "错误处理 step 不存在: " error-handler)
                    {:error-handler error-handler
                     :available-steps (keys steps)}))))

;;; ============================================================
;;; Build
;;; ============================================================

(defn build
  "编译 builder 为 process-spec

   执行验证并生成最终的 process 定义。

   参数:
   - b: builder

   返回: process-spec map

   异常: 验证失败时抛出 ex-info"
  [b]
  (let [steps (:steps b)
        bindings (:bindings b)]
    ;; 验证
    (when (empty? steps)
      (throw (ex-info "Process 至少需要一个 step" {:name (:name b)})))
    (validate-bindings steps bindings)
    (validate-error-handler steps (:error-handler b))
    ;; 生成 process-spec
    {:__process_spec__  true
     :name             (:name b)
     :steps            steps
     :bindings         bindings
     :initial-events   (:initial-events b)
     :error-handler    (:error-handler b)}))
