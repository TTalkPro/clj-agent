(ns im.ttalk.agent.core.kernel.process.step
  "Process Step - Step 激活逻辑

   Step 是 Process 中的最小执行单元。
   每个 Step 有输入槽、内部状态、激活条件和执行逻辑。

   Step 定义:
   {:id              :step-name
    :init            (fn [config] -> initial-state)       ;; 可选
    :can-activate?   (fn [inputs state] -> boolean)       ;; 可选
    :on-activate     (fn [inputs state context] -> result) ;; 必需
    :on-resume       (fn [data state context] -> result)  ;; 可选
    :required-inputs [:input]                             ;; 默认 [:input]
    :config          {}}                                  ;; 可选

   Step Runtime State:
   {:step-def          step-def
    :state             any          ;; step 内部状态
    :collected-inputs  {}           ;; 已收集的输入
    :activation-count  0}           ;; 激活次数

   on-activate 返回值:
   {:events  [{:name :event-name :data any}]   正常完成
    :state   new-state
    :context updated-context}

   {:pause {:reason \"...\" :state new-state}}   暂停

   {:error {:reason \"...\"}}                    错误"
  (:require [im.ttalk.agent.core.kernel.process.event :as event]))

;;; ============================================================
;;; Step 初始化
;;; ============================================================

(defn init-step
  "初始化 step runtime state

   调用 step-def 的 :init 函数（如有），创建 runtime state。

   参数:
   - step-def: step 定义 map

   返回:
   step runtime state map"
  [step-def]
  (let [init-fn (:init step-def)
        config (or (:config step-def) {})
        initial-state (if init-fn
                        (init-fn config)
                        nil)]
    {:step-def         step-def
     :state            initial-state
     :collected-inputs {}
     :activation-count 0}))

;;; ============================================================
;;; 输入收集
;;; ============================================================

(defn collect-input
  "向 step 投递一个输入值

   参数:
   - step-state:  step runtime state
   - input-name:  输入槽名称（keyword）
   - data:        输入数据

   返回:
   更新后的 step runtime state"
  [step-state input-name data]
  (assoc-in step-state [:collected-inputs input-name] data))

(defn clear-inputs
  "清除已收集的输入（激活后调用，支持循环重激活）

   参数:
   - step-state: step runtime state

   返回:
   更新后的 step runtime state"
  [step-state]
  (assoc step-state :collected-inputs {}))

;;; ============================================================
;;; 激活检查
;;; ============================================================

(defn- default-required-inputs
  "获取 step 的 required-inputs（默认 [:input]）"
  [step-def]
  (or (:required-inputs step-def) [:input]))

(defn check-activation
  "检查 step 是否满足激活条件

   条件:
   1. required-inputs 全部在 collected-inputs 中存在
   2. can-activate?（如定义）返回 true

   参数:
   - step-state: step runtime state

   返回: boolean"
  [step-state]
  (let [step-def (:step-def step-state)
        required (default-required-inputs step-def)
        collected (:collected-inputs step-state)
        all-present? (every? #(contains? collected %) required)]
    (if all-present?
      (if-let [can-fn (:can-activate? step-def)]
        (can-fn collected (:state step-state))
        true)
      false)))

;;; ============================================================
;;; Step 执行
;;; ============================================================

(defn execute
  "执行 step 的 on-activate

   参数:
   - step-state: step runtime state
   - context:    Context 对象

   返回:
   {:result    activation-result     ;; on-activate 的返回值
    :step-state updated-step-state}  ;; 更新后的 runtime state

   activation-result 格式:
   {:events [...] :state s :context c} | {:pause {...}} | {:error {...}}"
  [step-state context]
  (let [step-def (:step-def step-state)
        on-activate (:on-activate step-def)
        inputs (:collected-inputs step-state)
        state (:state step-state)]
    (try
      (let [result (on-activate inputs state context)
            ;; 标记事件来源
            step-id (:id step-def)
            result (if (:events result)
                     (update result :events
                             (fn [evts]
                               (mapv #(event/with-source % step-id) evts)))
                     result)
            ;; 更新 step state
            new-step-state (-> step-state
                               (clear-inputs)
                               (update :activation-count inc))
            new-step-state (if (contains? result :state)
                             (assoc new-step-state :state (:state result))
                             (if (:pause result)
                               (assoc new-step-state :state
                                      (get-in result [:pause :state]))
                               new-step-state))]
        {:result     result
         :step-state new-step-state})
      (catch Exception e
        {:result     {:error {:reason (.getMessage e)}}
         :step-state (-> step-state
                         (clear-inputs)
                         (update :activation-count inc))}))))

;;; ============================================================
;;; Step 恢复
;;; ============================================================

(defn resume-step
  "恢复暂停的 step

   参数:
   - step-state: step runtime state
   - data:       恢复数据
   - context:    Context 对象

   返回:
   {:result activation-result :step-state updated-step-state}
   或 nil（step 不支持 resume）"
  [step-state data context]
  (let [step-def (:step-def step-state)
        on-resume (:on-resume step-def)]
    (when on-resume
      (try
        (let [result (on-resume data (:state step-state) context)
              step-id (:id step-def)
              result (if (:events result)
                       (update result :events
                               (fn [evts]
                                 (mapv #(event/with-source % step-id) evts)))
                       result)
              new-step-state (if (contains? result :state)
                               (assoc step-state :state (:state result))
                               step-state)]
          {:result     result
           :step-state new-step-state})
        (catch Exception e
          {:result     {:error {:reason (.getMessage e)}}
           :step-state step-state})))))
