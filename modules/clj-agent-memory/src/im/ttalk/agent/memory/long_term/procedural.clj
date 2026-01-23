(ns im.ttalk.agent.memory.long-term.procedural
  "程序记忆实现 - 存储行为规则和模式

   提供：
   - 系统提示词管理
   - 行为规则存储
   - 基于反馈的规则学习
   - 上下文相关规则激活

   使用示例：
   (def memory (create-procedural-memory store))
   (set-system-prompt memory \"user-123\" \"你是一个助手\")
   (add-rule memory \"user-123\" {:condition \"用户说中文\" :action \"用中文回复\"})"
  (:require [im.ttalk.agent.memory.protocol :as proto]))

;; =============================================================================
;; 辅助函数
;; =============================================================================

(defn- generate-rule-id []
  (str "rule-" (java.util.UUID/randomUUID)))

(defn- system-prompt-key []
  "system-prompt")

(defn- rule-key [rule-id]
  (str "rule:" rule-id))

(defn- rules-index-key []
  "rules-index")

(defn- patterns-key []
  "learned-patterns")

(defn- now []
  (System/currentTimeMillis))

;; =============================================================================
;; ProceduralMemory 实现
;; =============================================================================

(defrecord ProceduralMemory [store config]
  proto/IProceduralMemory

  (get-system-prompt [_ namespace]
    (let [data (proto/kv-get store namespace (system-prompt-key))]
      (:prompt data)))

  (set-system-prompt [_ namespace prompt]
    (let [key (system-prompt-key)
          record {:prompt prompt
                  :namespace namespace
                  :updated-at (now)}]
      (proto/kv-put store namespace key record)
      prompt))

  (append-to-system-prompt [this namespace addition]
    (let [existing (or (proto/get-system-prompt this namespace) "")
          new-prompt (str existing "\n\n" addition)]
      (proto/set-system-prompt this namespace new-prompt)))

  (add-rule [_ namespace rule]
    (let [rule-id (or (:id rule) (generate-rule-id))
          timestamp (now)
          record {:id rule-id
                  :namespace namespace
                  :condition (:condition rule)
                  :action (:action rule)
                  :priority (or (:priority rule) 0)
                  :active (if (contains? rule :active) (:active rule) true)
                  :source (or (:source rule) :manual)
                  :created-at timestamp
                  :updated-at timestamp}
          key (rule-key rule-id)]

      ;; 存储规则
      (proto/kv-put store namespace key record)

      ;; 更新索引
      (let [idx (or (proto/kv-get store namespace (rules-index-key)) {:rule-ids []})
            updated-idx (update idx :rule-ids #(conj (vec %) rule-id))]
        (proto/kv-put store namespace (rules-index-key) updated-idx))

      rule-id))

  (get-rules [_ namespace opts]
    (let [idx (proto/kv-get store namespace (rules-index-key))
          rule-ids (or (:rule-ids idx) [])
          active-only (:active-only opts)
          source-filter (:source opts)
          priority-min (:priority-min opts)]
      (->> rule-ids
           (map #(proto/kv-get store namespace (rule-key %)))
           (filter some?)
           (filter #(if active-only (:active %) true))
           (filter #(if source-filter (= (:source %) source-filter) true))
           (filter #(if priority-min (>= (:priority %) priority-min) true))
           (sort-by :priority >))))  ; 按优先级降序

  (get-active-rules [this namespace context]
    ;; 获取所有激活的规则
    ;; 高级实现可以根据 context 匹配 condition
    (let [all-rules (proto/get-rules this namespace {:active-only true})]
      ;; 简单实现：返回所有激活规则
      ;; TODO: 实现条件匹配逻辑
      all-rules))

  (update-rule [_ namespace rule-id updates]
    (let [key (rule-key rule-id)
          existing (proto/kv-get store namespace key)]
      (when existing
        (let [updated (-> existing
                          (merge updates)
                          (assoc :updated-at (now)))]
          (proto/kv-put store namespace key updated)
          updated))))

  (deactivate-rule [this namespace rule-id]
    (proto/update-rule this namespace rule-id {:active false}))

  (delete-rule [_ namespace rule-id]
    (let [key (rule-key rule-id)]
      (proto/kv-delete store namespace key)

      ;; 更新索引
      (let [idx (proto/kv-get store namespace (rules-index-key))]
        (when idx
          (let [updated-idx (update idx :rule-ids
                                    #(filterv (fn [id] (not= id rule-id)) %))]
            (proto/kv-put store namespace (rules-index-key) updated-idx))))
      true))

  (update-from-feedback [this namespace feedback]
    (let [feedback-type (:type feedback)
          context (:context feedback)
          suggestion (:suggestion feedback)
          existing-patterns (or (proto/kv-get store namespace (patterns-key))
                                {:patterns [] :feedback-count 0})]

      ;; 记录反馈模式
      (let [pattern {:type feedback-type
                     :context context
                     :suggestion suggestion
                     :timestamp (now)}
            updated-patterns (-> existing-patterns
                                 (update :patterns #(conj (vec %) pattern))
                                 (update :feedback-count inc))]
        (proto/kv-put store namespace (patterns-key) updated-patterns)

        ;; 根据反馈类型采取行动
        (case feedback-type
          :positive
          ;; 正面反馈：可能提升相关规则优先级
          (when suggestion
            (proto/add-rule this namespace
                            {:condition (str "类似场景: " (pr-str context))
                             :action suggestion
                             :priority 1
                             :source :learned}))

          :negative
          ;; 负面反馈：可能降低相关规则优先级或创建避免规则
          (when suggestion
            (proto/add-rule this namespace
                            {:condition (str "避免场景: " (pr-str context))
                             :action (str "不要: " suggestion)
                             :priority 2
                             :source :learned}))

          :correction
          ;; 纠正：添加新规则
          (when suggestion
            (proto/add-rule this namespace
                            {:condition (str "当: " (pr-str context))
                             :action suggestion
                             :priority 3
                             :source :learned}))

          ;; 默认：只记录
          nil)

        pattern)))

  (get-learned-patterns [_ namespace]
    (let [data (proto/kv-get store namespace (patterns-key))]
      (:patterns data []))))

;; =============================================================================
;; 工厂函数
;; =============================================================================

(defn create-procedural-memory
  "创建程序记忆实例

   参数：
   - store: IStore 实例

   示例：
   (create-procedural-memory store)"
  [store & {:keys []}]
  (->ProceduralMemory store {}))

;; =============================================================================
;; 便捷函数
;; =============================================================================

(defn create-rule
  "创建规则"
  [condition action & {:keys [priority source]}]
  {:condition condition
   :action action
   :priority (or priority 0)
   :source (or source :manual)})

(defn create-language-rule
  "创建语言偏好规则"
  [language]
  (create-rule
    (str "用户使用" language)
    (str "使用" language "回复")
    :priority 5))

(defn create-style-rule
  "创建风格规则"
  [style description]
  (create-rule
    "通用"
    (str "采用" style "风格: " description)
    :priority 3))
