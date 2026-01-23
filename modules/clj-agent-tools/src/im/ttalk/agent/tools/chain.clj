(ns im.ttalk.agent.tools.chain
  "工具链 DSL

   职责：
   - 工具链定义和执行
   - 变量替换（{{variable}}）
   - 条件执行（:when）
   - 工具组合和编排

   使用示例：

   (require '[im.ttalk.agent.tools.chain :as chain])

   ;; 定义工具链
   (def my-chain
     (chain/defchain
       {:tool :search
        :args {:query \"{{topic}}\"}
        :output-key :search-results}
       {:tool :summarize
        :args {:text \"{{search-results}}\"}
        :output-key :summary
        :when #(seq (:search-results %))}))

   ;; 执行工具链
   (chain/execute my-chain {:topic \"Clojure\"})"
  (:require [im.ttalk.agent.tools.tool-registry :as tool-registry]
            [im.ttalk.agent.tools.executor :as executor]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;;; ============================================================
;;; 变量替换
;;; ============================================================

(def ^:private variable-pattern
  "匹配 {{variable}} 的正则表达式"
  #"\{\{([^}]+)\}\}")

(defn- parse-path
  "解析变量路径（支持嵌套访问）

   示例：
   \"user.name\" => [:user :name]
   \"results.0.title\" => [:results 0 :title]"
  [path-str]
  (mapv (fn [part]
          (if (re-matches #"\d+" part)
            (Integer/parseInt part)
            (keyword part)))
        (str/split path-str #"\.")))

(defn- get-nested
  "获取嵌套值

   参数：
     context - 上下文 map
     path    - 路径向量 [:key1 :key2]

   返回：
     值或 nil"
  [context path]
  (reduce (fn [acc k]
            (cond
              (nil? acc) nil
              (and (number? k) (sequential? acc)) (nth acc k nil)
              (map? acc) (get acc k)))
          context
          path))

(defn- replace-variable
  "替换单个变量

   参数：
     s       - 原始字符串
     context - 上下文 map

   返回：
     替换后的字符串或值"
  [s context]
  (if (string? s)
    ;; 检查是否整个字符串就是一个变量
    (if-let [[_ var-name] (re-matches #"^\{\{([^}]+)\}\}$" s)]
      ;; 整个字符串是变量，返回原始值（保持类型）
      (let [path (parse-path var-name)]
        (get-nested context path))
      ;; 部分变量替换，结果为字符串
      (str/replace s variable-pattern
                   (fn [[_ var-name]]
                     (let [path (parse-path var-name)
                           value (get-nested context path)]
                       (if (nil? value)
                         (str "{{" var-name "}}")
                         (str value))))))
    s))

(defn substitute-variables
  "递归替换 map 中的所有变量

   参数：
     template - 模板 map（可包含嵌套结构）
     context  - 上下文 map

   返回：
     替换后的 map

   示例：
   (substitute-variables
     {:query \"{{topic}}\" :limit 10}
     {:topic \"Clojure\"})
   ; => {:query \"Clojure\" :limit 10}"
  [template context]
  (cond
    (map? template)
    (into {} (map (fn [[k v]]
                    [k (substitute-variables v context)])
                  template))

    (sequential? template)
    (mapv #(substitute-variables % context) template)

    (string? template)
    (replace-variable template context)

    :else
    template))

;;; ============================================================
;;; 条件判断
;;; ============================================================

(defn- evaluate-condition
  "评估条件

   参数：
     condition - 条件（函数、关键字或值）
     context   - 上下文 map

   返回：
     boolean"
  [condition context]
  (cond
    ;; 函数：调用函数
    (fn? condition)
    (boolean (condition context))

    ;; 关键字：检查上下文中该键是否存在且非空
    (keyword? condition)
    (let [value (get context condition)]
      (if (coll? value)
        (seq value)
        (some? value)))

    ;; 向量 [:key :exists] 或 [:key :not-empty]
    (and (vector? condition) (= 2 (count condition)))
    (let [[key-path op] condition
          value (if (vector? key-path)
                  (get-nested context key-path)
                  (get context key-path))]
      (case op
        :exists (some? value)
        :not-empty (if (coll? value) (seq value) (some? value))
        :empty (if (coll? value) (empty? value) (nil? value))
        :truthy (boolean value)
        :falsy (not value)
        (boolean value)))

    ;; 其他：直接返回 boolean
    :else
    (boolean condition)))

(defn- should-execute?
  "判断是否应该执行工具

   参数：
     step    - 步骤定义
     context - 当前上下文

   返回：
     boolean"
  [step context]
  (if-let [condition (:when step)]
    (evaluate-condition condition context)
    true))

;;; ============================================================
;;; 工具链步骤
;;; ============================================================

(defrecord ChainStep
  [tool          ; 工具名称（关键字）
   args          ; 参数模板（可包含 {{variable}}）
   output-key    ; 输出键名（可选）
   when          ; 条件（可选）
   on-error      ; 错误处理策略（可选）
   retry         ; 重试配置（可选）
   fallback      ; 降级配置（可选）
   transform])   ; 结果转换函数（可选）

(defn make-step
  "创建工具链步骤

   参数：
     step-def - 步骤定义 map

   返回：
     ChainStep 实例

   步骤定义选项：
     :tool       - 工具名称（必需）
     :args       - 参数模板
     :output-key - 输出键名（默认为工具名）
     :when       - 执行条件
     :on-error   - 错误处理 (:skip, :stop, :continue)
     :retry      - 重试配置 {:max-retries 3 ...}
     :fallback   - 降级配置 {:tool :alt-tool :args {...}}
     :transform  - 结果转换函数"
  [{:keys [tool args output-key when on-error retry fallback transform]
    :or {on-error :stop}}]
  (->ChainStep tool
               (or args {})
               (or output-key tool)
               when
               on-error
               retry
               fallback
               transform))

;;; ============================================================
;;; 工具链
;;; ============================================================

(defrecord ToolChain
  [steps        ; ChainStep 列表
   config])     ; 链配置

(defn defchain
  "定义工具链

   参数：
     steps - 步骤定义列表

   返回：
     ToolChain 实例

   示例：
   (defchain
     {:tool :search :args {:q \"{{query}}\"} :output-key :results}
     {:tool :parse :args {:data \"{{results}}\"}})"
  [& steps]
  (->ToolChain (mapv make-step steps) {}))

(defn chain
  "创建工具链（带配置）

   参数：
     config - 链配置
     steps  - 步骤定义列表

   返回：
     ToolChain 实例

   配置选项：
     :parallel?         - 是否并行执行无依赖步骤（默认 false）
     :continue-on-error - 错误时是否继续（默认 false）
     :timeout-ms        - 全局超时（毫秒）"
  [config & steps]
  (->ToolChain (mapv make-step steps) config))

;;; ============================================================
;;; 步骤执行
;;; ============================================================

(defn- execute-step
  "执行单个步骤

   参数：
     registry - ToolRegistry 实例
     step     - ChainStep
     context  - 当前上下文

   返回：
     {:success bool :result any :context map}"
  [registry step context]
  (let [tool-name (:tool step)
        args-template (:args step)
        output-key (:output-key step)]
    (log/debug "Executing step:" tool-name "with template:" args-template)

    ;; 替换变量
    (let [resolved-args (substitute-variables args-template context)]
      (log/debug "Resolved args:" resolved-args)

      ;; 执行工具
      (let [result (if-let [retry-config (:retry step)]
                     (executor/execute-with-retry registry tool-name resolved-args retry-config)
                     (tool-registry/execute-tool registry tool-name resolved-args))]

        (if (:success result)
          ;; 成功：应用转换并更新上下文
          (let [raw-result (:result result)
                transformed (if-let [transform-fn (:transform step)]
                              (transform-fn raw-result)
                              raw-result)
                new-context (assoc context output-key transformed)]
            {:success true
             :result transformed
             :context new-context
             :step-name tool-name})

          ;; 失败：尝试降级
          (if-let [fallback-config (:fallback step)]
            (let [fallback-tool (:tool fallback-config)
                  fallback-args (substitute-variables
                                  (or (:args fallback-config) resolved-args)
                                  context)
                  fallback-result (tool-registry/execute-tool registry fallback-tool fallback-args)]
              (if (:success fallback-result)
                (let [new-context (assoc context output-key (:result fallback-result))]
                  {:success true
                   :result (:result fallback-result)
                   :context new-context
                   :step-name tool-name
                   :used-fallback true})
                {:success false
                 :error (:error fallback-result)
                 :context context
                 :step-name tool-name}))

            {:success false
             :error (or (:error result) "Unknown error")
             :context context
             :step-name tool-name}))))))

;;; ============================================================
;;; 链执行
;;; ============================================================

(defn execute
  "执行工具链

   参数：
     registry        - ToolRegistry 实例
     tool-chain      - ToolChain 实例
     initial-context - 初始上下文 map

   返回：
     {:success bool
      :context map       ; 最终上下文
      :results [...]     ; 每步结果
      :error string}     ; 错误信息（如果失败）

   示例：
   (execute registry my-chain {:query \"Clojure\"})"
  [registry tool-chain initial-context]
  (let [steps (:steps tool-chain)
        config (:config tool-chain)
        continue-on-error? (:continue-on-error config false)]

    (loop [remaining-steps steps
           context initial-context
           results []]
      (if (empty? remaining-steps)
        ;; 所有步骤完成
        {:success true
         :context context
         :results results}

        (let [step (first remaining-steps)]
          ;; 检查条件
          (if (should-execute? step context)
            ;; 执行步骤
            (let [step-result (execute-step registry step context)]
              (if (:success step-result)
                ;; 成功：继续下一步
                (recur (rest remaining-steps)
                       (:context step-result)
                       (conj results step-result))

                ;; 失败：根据配置决定是否继续
                (let [on-error (or (:on-error step) :stop)]
                  (case on-error
                    :skip
                    (do
                      (log/warn "Step failed, skipping:" (:tool step))
                      (recur (rest remaining-steps)
                             context
                             (conj results step-result)))

                    :continue
                    (if continue-on-error?
                      (recur (rest remaining-steps)
                             context
                             (conj results step-result))
                      {:success false
                       :context context
                       :results (conj results step-result)
                       :error (:error step-result)})

                    ;; :stop 或默认
                    {:success false
                     :context context
                     :results (conj results step-result)
                     :error (:error step-result)}))))

            ;; 条件不满足：跳过
            (do
              (log/debug "Skipping step (condition not met):" (:tool step))
              (recur (rest remaining-steps)
                     context
                     (conj results {:skipped true
                                    :step-name (:tool step)
                                    :reason "Condition not met"})))))))))

;;; ============================================================
;;; 便捷函数
;;; ============================================================

(defn run-chain
  "运行工具链（简化版）

   参数：
     registry - ToolRegistry 实例
     steps    - 步骤定义列表
     context  - 初始上下文

   返回：
     最终上下文或抛出异常"
  [registry steps context]
  (let [tool-chain (apply defchain steps)
        result (execute registry tool-chain context)]
    (if (:success result)
      (:context result)
      (throw (ex-info "Tool chain failed"
                      {:error (:error result)
                       :results (:results result)})))))

(defn execute-simple
  "简单执行（只返回最终结果）

   参数：
     registry   - ToolRegistry 实例
     tool-chain - ToolChain 实例
     context    - 初始上下文
     result-key - 结果键（可选，默认为最后一步的 output-key）

   返回：
     结果值或 nil"
  ([registry tool-chain context]
   (let [last-step (last (:steps tool-chain))
         result-key (:output-key last-step)]
     (execute-simple registry tool-chain context result-key)))
  ([registry tool-chain context result-key]
   (let [result (execute registry tool-chain context)]
     (when (:success result)
       (get (:context result) result-key)))))

;;; ============================================================
;;; 工具链组合
;;; ============================================================

(defn concat-chains
  "连接多个工具链

   参数：
     chains - ToolChain 列表

   返回：
     新的 ToolChain"
  [& chains]
  (->ToolChain (vec (mapcat :steps chains)) {}))

(defn prepend-step
  "在链前添加步骤

   参数：
     tool-chain - ToolChain
     step-def   - 步骤定义

   返回：
     新的 ToolChain"
  [tool-chain step-def]
  (->ToolChain (into [(make-step step-def)] (:steps tool-chain))
               (:config tool-chain)))

(defn append-step
  "在链后添加步骤

   参数：
     tool-chain - ToolChain
     step-def   - 步骤定义

   返回：
     新的 ToolChain"
  [tool-chain step-def]
  (->ToolChain (conj (:steps tool-chain) (make-step step-def))
               (:config tool-chain)))

;;; ============================================================
;;; 工具链分析
;;; ============================================================

(defn analyze-chain
  "分析工具链

   参数：
     tool-chain - ToolChain

   返回：
     分析结果 map"
  [tool-chain]
  (let [steps (:steps tool-chain)]
    {:step-count (count steps)
     :tools (mapv :tool steps)
     :output-keys (mapv :output-key steps)
     :has-conditions? (some :when steps)
     :has-retries? (some :retry steps)
     :has-fallbacks? (some :fallback steps)
     :variables (set (mapcat (fn [step]
                               (when-let [args (:args step)]
                                 (->> (tree-seq coll? seq args)
                                      (filter string?)
                                      (mapcat #(re-seq variable-pattern %))
                                      (map second))))
                             steps))}))

(defn print-chain
  "打印工具链结构

   参数：
     tool-chain - ToolChain"
  [tool-chain]
  (println "\n=== Tool Chain ===")
  (doseq [[idx step] (map-indexed vector (:steps tool-chain))]
    (println (str (inc idx) ". " (:tool step)))
    (println "   Args:" (pr-str (:args step)))
    (println "   Output:" (:output-key step))
    (when (:when step)
      (println "   Condition: yes"))
    (when (:retry step)
      (println "   Retry:" (pr-str (:retry step))))
    (when (:fallback step)
      (println "   Fallback:" (:tool (:fallback step)))))
  (println))

;;; ============================================================
;;; 高级模式
;;; ============================================================

(defn parallel-steps
  "创建并行步骤（无依赖的步骤并行执行）

   参数：
     registry - ToolRegistry 实例
     steps    - 步骤定义列表
     context  - 初始上下文

   返回：
     并行执行结果

   注意：这些步骤共享初始上下文，结果合并到最终上下文"
  [registry steps context]
  (let [chain-steps (mapv make-step steps)
        futures (mapv (fn [step]
                        (future
                          (when (should-execute? step context)
                            (execute-step registry step context))))
                      chain-steps)
        results (mapv deref futures)]
    ;; 合并所有成功的结果到上下文
    (reduce (fn [ctx result]
              (if (and result (:success result))
                (merge ctx (select-keys (:context result)
                                        [(:output-key (first (filter #(= (:step-name result) (:tool %))
                                                                     chain-steps)))]))
                ctx))
            context
            results)))

(defn branch
  "条件分支

   参数：
     registry     - ToolRegistry 实例
     condition    - 条件函数
     true-chain   - 条件为真时执行的链
     false-chain  - 条件为假时执行的链（可选）

   返回：
     分支执行函数"
  ([registry condition true-chain]
   (branch registry condition true-chain nil))
  ([registry condition true-chain false-chain]
   (fn [context]
     (if (evaluate-condition condition context)
       (execute registry true-chain context)
       (when false-chain
         (execute registry false-chain context))))))
