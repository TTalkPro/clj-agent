(ns im.ttalk.agent.process.step
  "Step 运行时状态与激活判定（纯函数）。

   step-spec（定义，见 builder/add-step）：
   {:id              :step-name
    :init            (fn [config] initial-state)         ;; 可选，缺省 state 为 nil
    :can-activate?   (fn [inputs state] boolean)         ;; 可选守卫，缺省恒 true
    :on-activate     (fn [inputs state context] result)  ;; 必填
    :on-resume       (fn [data state context] result)    ;; 可选（pause 后恢复）
    :on-terminate    (fn [state context] nil)            ;; 可选，资源清理
    :required-inputs [:input]                            ;; 缺省 [:input]
    :config          {}}

   step-state（runtime 私有）：
   {:state s :collected-inputs {input-slot data} :activation-count n}

   激活规则：
   - required-inputs 全部收齐 且 can-activate? 为真 → 激活
   - can-activate? 返回 false 时不清空 collected-inputs（新输入到达再查）
   - 激活执行后清空 collected-inputs（允许循环再激活）"
  )

(set! *warn-on-reflection* true)

(defn init-state
  "由 step-spec 创建初始 step-state（:init 缺省返回 nil state）。
   restored 非空时以快照恢复（跳过 init）。"
  [step-spec restored]
  (if restored
    {:state (:state restored)
     :collected-inputs {}
     :activation-count (or (:activation-count restored) 0)}
    {:state (when-let [f (:init step-spec)] (f (:config step-spec)))
     :collected-inputs {}
     :activation-count 0}))

(defn deliver-input
  "把一次投递写入 collected-inputs（同槽后到覆盖先到）。"
  [step-state input data]
  (assoc-in step-state [:collected-inputs input] data))

(defn inputs-ready?
  "required-inputs 是否全部收齐。"
  [step-spec step-state]
  (every? #(contains? (:collected-inputs step-state) %)
          (:required-inputs step-spec)))

(defn activatable?
  "收齐 + 守卫通过。"
  [step-spec step-state]
  (and (inputs-ready? step-spec step-state)
       (if-let [guard (:can-activate? step-spec)]
         (boolean (guard (:collected-inputs step-state) (:state step-state)))
         true)))

(defn after-activation
  "激活执行后的状态推进：清空 inputs、计数 +1、按需更新 state。
   result 含 :state 键才更新（区分「未返回 state」与「显式置 nil」）。"
  [step-state result]
  (cond-> (-> step-state
              (assoc :collected-inputs {})
              (update :activation-count inc))
    (contains? result :state) (assoc :state (:state result))))
