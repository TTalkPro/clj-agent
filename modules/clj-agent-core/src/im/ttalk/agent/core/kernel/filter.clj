(ns im.ttalk.agent.core.kernel.filter
  "Filter 系统 - 工具调用拦截链

   灵感来自 Semantic Kernel 的 IAutoFunctionInvocationFilter。
   Filter 是工具调用的中间件，可在执行前后注入逻辑（日志、审批、超时等）。

   Filter 函数签名:
   (fn [context next-fn]
     ;; context 包含:
     ;;   :tool-name  工具名称（关键字）
     ;;   :tool-args  工具参数（map）
     ;;   :tool-id    工具调用 ID（字符串）
     ;;   :kernel     Kernel 实例
     ;;   :history    当前对话历史
     ;;   :context    完整 Context 对象（含 variables/trace/metadata）
     ;;
     ;; next-fn: 调用链中的下一步
     ;;   调用则继续执行，不调用则跳过工具执行
     ;;
     ;; 返回: 执行结果 map
     ;;   {:tool-id \"...\" :name :keyword :result \"...\" :error nil :context ctx}
     ;;   如果结果包含 :context，更新后的 context 会传递给后续调用

     ;; 示例: 读写 context 变量的 filter
     (let [user-id (context/get-var (:context context) :user-id)]
       (println \"User:\" user-id))
     (let [result (next-fn context)]
       ;; filter 可以在结果中更新 context
       (update result :context context/add-trace {:type :filter :data \"done\"})))

   使用示例：

   ;; 注册到 Kernel
   (-> (create-kernel-builder)
       (add-filter logging-filter)
       (add-filter (timeout-filter 5000))
       (build-kernel))"
  (:require [im.ttalk.agent.core.kernel.tool :as tool]))

;;; ============================================================
;;; Filter 链构建
;;; ============================================================

(defn build-filter-chain
  "构建 Filter 执行链

   将多个 filter 函数组合成洋葱式执行链。
   执行顺序：第一个 filter 最先进入、最后退出（类似 Ring middleware）。

   context 传递规则：
   - 下行（进入）：filter 可修改 ctx 的 :context 字段后传给 next-fn
   - 上行（返回）：结果中的 :context 字段携带更新后的 Context

   参数:
   - filters:    filter 函数列表 [(fn [ctx next] ...)]
   - execute-fn: 最终执行函数 (fn [ctx] -> result)

   返回:
   组合后的执行函数 (fn [context] -> result)

   示例:
   (def chain (build-filter-chain [log-f err-f] exec-fn))
   (chain {:tool-name :calc :tool-args {:x 1} :context my-ctx})
   ;; 执行顺序: log-f → err-f → exec-fn → err-f → log-f"
  [filters execute-fn]
  (reduce (fn [next-fn filter-fn]
            (fn [context]
              (filter-fn context next-fn)))
          execute-fn
          (reverse filters)))

;;; ============================================================
;;; 内置 Filter: 日志
;;; ============================================================

(defn logging-filter
  "日志 Filter - 打印工具调用前后的信息

   输出格式:
   [Kernel] 调用工具: :tool-name 参数: {...}
   [Kernel] 工具结果: :tool-name => \"...\"

   不修改执行结果，仅打印日志。"
  [context next-fn]
  (println (str "[Kernel] 调用工具: " (:tool-name context)
                " 参数: " (pr-str (:tool-args context))))
  (let [result (next-fn context)]
    (println (str "[Kernel] 工具结果: " (:tool-name context)
                  " => " (if (:error result)
                           (str "错误: " (:error result))
                           (let [r (:result result)]
                             (if (and (string? r) (> (count r) 100))
                               (str (subs r 0 100) "...")
                               (pr-str r))))))
    result))

;;; ============================================================
;;; 内置 Filter: 异常捕获
;;; ============================================================

(defn error-handling-filter
  "异常捕获 Filter - 将异常转为结构化错误

   捕获下游执行中抛出的异常，返回 {:error \"...\"} 而非让异常传播。
   适合放在 filter 链最外层，确保工具调用不会因异常中断对话循环。
   保留结果中的 :context 不变。"
  [context next-fn]
  (try
    (next-fn context)
    (catch Exception e
      (cond-> {:tool-id (:tool-id context)
               :name    (:tool-name context)
               :result  nil
               :error   (str "工具执行异常: " (.getMessage e))}
        (:context context) (assoc :context (:context context))))))

;;; ============================================================
;;; 内置 Filter: 超时控制
;;; ============================================================

(defn timeout-filter
  "超时控制 Filter 工厂

   如果工具执行超过指定时间，返回超时错误。

   参数:
   - timeout-ms: 超时时间（毫秒）

   返回:
   filter 函数

   示例:
   (add-filter builder (timeout-filter 5000))"
  [timeout-ms]
  (fn [context next-fn]
    (let [result (deref (future (next-fn context))
                        timeout-ms
                        ::timeout)]
      (if (= result ::timeout)
        (cond-> {:tool-id (:tool-id context)
                 :name    (:tool-name context)
                 :result  nil
                 :error   (str "工具调用超时（" timeout-ms "ms）")}
          (:context context) (assoc :context (:context context)))
        result))))

;;; ============================================================
;;; 内置 Filter: 敏感工具审批
;;; ============================================================

(defn approval-filter
  "敏感工具审批 Filter

   对标记为 :sensitive 的工具调用进行人工确认。
   在标准输入输出上打印确认提示，等待用户输入 y/n。

   非敏感工具直接放行。"
  [context next-fn]
  (let [kernel (:kernel context)
        fn-name (:tool-name context)
        ;; 在 kernel 的 plugins 中查找此函数是否标记为敏感
        is-sensitive? (some (fn [plugin]
                              (when-let [v (get (:functions plugin)
                                                (keyword fn-name))]
                                (tool/sensitive? v)))
                            (:plugins kernel))]
    (if is-sensitive?
      ;; 敏感工具：请求审批
      (do
        (println (str "\n[审批] 敏感工具调用:"))
        (println (str "  工具: " fn-name))
        (println (str "  参数: " (pr-str (:tool-args context))))
        (print "  是否允许执行? (y/n): ")
        (flush)
        (let [input (read-line)]
          (if (= "y" (clojure.string/lower-case (or input "")))
            (next-fn context)
            (cond-> {:tool-id (:tool-id context)
                     :name    (:tool-name context)
                     :result  nil
                     :error   "用户拒绝了此敏感工具调用"}
              (:context context) (assoc :context (:context context))))))
      ;; 非敏感工具：直接放行
      (next-fn context))))

;;; ============================================================
;;; Filter 组合工具
;;; ============================================================

(defn conditional-filter
  "条件 Filter - 仅在条件满足时应用 filter

   参数:
   - pred:      条件判断函数 (fn [context] -> boolean)
   - filter-fn: 条件满足时应用的 filter

   返回:
   filter 函数

   示例:
   ;; 仅对 :dangerous 类别的工具应用超时
   (conditional-filter
     #(= :dangerous (:tool-category %))
     (timeout-filter 3000))"
  [pred filter-fn]
  (fn [context next-fn]
    (if (pred context)
      (filter-fn context next-fn)
      (next-fn context))))

(defn compose-filters
  "组合多个 filter 为单个 filter

   参数:
   - filters: filter 函数列表

   返回:
   组合后的 filter 函数（等效于按顺序应用所有 filter）

   示例:
   (def my-filter (compose-filters [logging-filter error-handling-filter]))"
  [filters]
  (fn [context next-fn]
    (let [chain (build-filter-chain filters next-fn)]
      (chain context))))
