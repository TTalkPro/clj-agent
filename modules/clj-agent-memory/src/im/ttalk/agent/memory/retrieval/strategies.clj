(ns im.ttalk.agent.memory.retrieval.strategies
  "记忆检索策略实现

   提供多种检索策略：
   - RecentStrategy: 最近 N 条消息
   - WindowStrategy: 滑动窗口
   - SummaryStrategy: 摘要策略
   - SemanticStrategy: 语义搜索
   - HybridStrategy: 混合策略

   使用示例：
   ;; 创建策略
   (def strategy (create-retrieval-strategy :hybrid {:recent-n 5 :semantic-top-k 3}))

   ;; 检索 - 传入组件 map
   (retrieve strategy
             {:buffer buffer :semantic semantic :episodic episodic}
             query
             context)"
  (:require [im.ttalk.agent.memory.protocol :as proto]))

;; =============================================================================
;; 最近消息策略
;; =============================================================================

(defrecord RecentStrategy [n]
  proto/IRetrievalStrategy

  (retrieve [_ store query context]
    (let [buffer (:buffer store)
          messages (when buffer
                     (proto/get-messages-window buffer n))]
      {:messages (or messages [])
       :facts []
       :episodes []
       :rules []
       :strategy :recent})))

(defn create-recent-strategy
  "创建最近消息策略"
  [n]
  (->RecentStrategy n))

;; =============================================================================
;; 滑动窗口策略
;; =============================================================================

(defrecord WindowStrategy [window-size keep-system]
  proto/IRetrievalStrategy

  (retrieve [_ store query context]
    (let [buffer (:buffer store)]
      (if buffer
        (let [all-msgs (proto/get-messages buffer)
              system-msgs (if keep-system
                            (filterv #(= (:role %) "system") all-msgs)
                            [])
              other-msgs (filterv #(not= (:role %) "system") all-msgs)
              window-msgs (vec (take-last window-size other-msgs))]
          {:messages (vec (concat system-msgs window-msgs))
           :facts []
           :episodes []
           :rules []
           :strategy :window})
        {:messages [] :facts [] :episodes [] :rules [] :strategy :window}))))

(defn create-window-strategy
  "创建滑动窗口策略"
  [window-size & {:keys [keep-system] :or {keep-system true}}]
  (->WindowStrategy window-size keep-system))

;; =============================================================================
;; Token 限制策略
;; =============================================================================

(defrecord TokenLimitStrategy [max-tokens keep-system]
  proto/IRetrievalStrategy

  (retrieve [_ store query context]
    (let [buffer (:buffer store)]
      (if buffer
        (let [all-msgs (proto/get-messages buffer)
              system-msgs (if keep-system
                            (filterv #(= (:role %) "system") all-msgs)
                            [])
              other-msgs (filterv #(not= (:role %) "system") all-msgs)
              ;; 简单估算：每条消息约 content 长度 / 4 tokens
              estimate-tokens (fn [msg] (int (/ (count (str (:content msg))) 4)))
              system-tokens (reduce + 0 (map estimate-tokens system-msgs))
              remaining-tokens (- max-tokens system-tokens)
              ;; 从最近的消息开始累加
              recent-msgs (loop [msgs (reverse other-msgs)
                                 result []
                                 tokens 0]
                            (if (or (empty? msgs) (>= tokens remaining-tokens))
                              (vec (reverse result))
                              (let [msg (first msgs)
                                    msg-tokens (estimate-tokens msg)]
                                (recur (rest msgs)
                                       (conj result msg)
                                       (+ tokens msg-tokens)))))]
          {:messages (vec (concat system-msgs recent-msgs))
           :facts []
           :episodes []
           :rules []
           :strategy :token-limit})
        {:messages [] :facts [] :episodes [] :rules [] :strategy :token-limit}))))

(defn create-token-limit-strategy
  "创建 Token 限制策略"
  [max-tokens & {:keys [keep-system] :or {keep-system true}}]
  (->TokenLimitStrategy max-tokens keep-system))

;; =============================================================================
;; 语义搜索策略
;; =============================================================================

(defrecord SemanticStrategy [top-k memory-types threshold]
  proto/IRetrievalStrategy

  (retrieve [_ store query context]
    (let [namespace (or (:namespace context) "default")
          query-text (if (string? query) query (:text query))

          ;; 从 store map 获取组件
          semantic (:semantic store)
          episodic (:episodic store)
          procedural (:procedural store)

          ;; 获取相关事实
          facts (when (and (contains? memory-types :semantic) semantic)
                  (proto/query-facts semantic namespace query-text {:top-k top-k}))

          ;; 获取相似情景
          episodes (when (and (contains? memory-types :episodic) episodic)
                     (proto/query-similar episodic query-text {:top-k top-k
                                                               :namespace namespace}))

          ;; 获取活跃规则
          rules (when (and (contains? memory-types :procedural) procedural)
                  (proto/get-active-rules procedural namespace context))]
      {:messages []
       :facts (or facts [])
       :episodes (or episodes [])
       :rules (or rules [])
       :strategy :semantic})))

(defn create-semantic-strategy
  "创建语义搜索策略"
  [& {:keys [top-k memory-types threshold]
      :or {top-k 5
           memory-types [:semantic :episodic]
           threshold 0.5}}]
  (->SemanticStrategy top-k (set memory-types) threshold))

;; =============================================================================
;; 混合策略
;; =============================================================================

(defrecord HybridStrategy [config]
  proto/IRetrievalStrategy

  (retrieve [_ store query context]
    (let [namespace (or (:namespace context) "default")
          query-text (if (string? query) query (:text query))

          ;; 从 store map 获取组件
          buffer (:buffer store)
          semantic (:semantic store)
          episodic (:episodic store)
          procedural (:procedural store)

          ;; 短期记忆：最近消息
          recent-n (or (:recent-n config) 5)
          messages (when buffer
                     (proto/get-messages-window buffer recent-n))

          ;; 长期记忆：语义搜索
          semantic-top-k (or (:semantic-top-k config) 3)

          facts (when (and (:include-semantic config true) semantic query-text)
                  (proto/query-facts semantic namespace query-text
                                     {:top-k semantic-top-k}))

          episodes (when (and (:include-episodic config true) episodic query-text)
                     (proto/query-similar episodic query-text
                                          {:top-k semantic-top-k
                                           :namespace namespace
                                           :outcome-filter :success}))

          rules (when (and (:include-procedural config true) procedural)
                  (proto/get-active-rules procedural namespace context))]
      {:messages (or messages [])
       :facts (or facts [])
       :episodes (or episodes [])
       :rules (or rules [])
       :strategy :hybrid})))

(defn create-hybrid-strategy
  "创建混合策略

   配置选项：
   - :recent-n          最近消息数（默认 5）
   - :semantic-top-k    语义搜索数量（默认 3）
   - :include-semantic  是否包含语义记忆（默认 true）
   - :include-episodic  是否包含情景记忆（默认 true）
   - :include-procedural 是否包含程序记忆（默认 true）

   示例：
   (create-hybrid-strategy {:recent-n 10 :semantic-top-k 5})"
  [config]
  (->HybridStrategy config))

;; =============================================================================
;; 策略工厂
;; =============================================================================

(defn create-retrieval-strategy
  "创建检索策略

   type: :recent | :window | :token-limit | :semantic | :hybrid
   opts: 策略配置

   示例：
   (create-retrieval-strategy :recent {:n 10})
   (create-retrieval-strategy :hybrid {:recent-n 5 :semantic-top-k 3})"
  [type opts]
  (case type
    :recent (create-recent-strategy (or (:n opts) 10))
    :window (create-window-strategy (or (:window-size opts) 20)
                                    :keep-system (:keep-system opts true))
    :token-limit (create-token-limit-strategy (or (:max-tokens opts) 4000)
                                              :keep-system (:keep-system opts true))
    :semantic (create-semantic-strategy
                :top-k (:top-k opts 5)
                :memory-types (:memory-types opts [:semantic :episodic])
                :threshold (:threshold opts 0.5))
    :hybrid (create-hybrid-strategy opts)
    ;; 默认使用混合策略
    (create-hybrid-strategy opts)))
