(ns im.ttalk.agent.core.kernel.filter
  "Filter 系统 - 4类型线性管道模型

   参考 beamai_kernel 设计，Filter 分为 4 种类型：
   - :pre-invocation   工具调用前（可修改参数/跳过执行）
   - :post-invocation  工具调用后（可修改结果）
   - :pre-chat         LLM 调用前（可修改消息）
   - :post-chat        LLM 调用后（可修改响应）

   Filter 定义:
   {:name     :filter-name
    :type     :pre-invocation | :post-invocation | :pre-chat | :post-chat
    :handler  (fn [filter-ctx] -> filter-result)
    :priority priority-number}  ;; 数字越小优先级越高

   Filter Result（handler 返回值）:
   {:action :continue :context updated-filter-ctx}  ;; 继续执行
   {:action :skip     :value result-value}           ;; 跳过执行，直接返回
   {:action :error    :reason error-reason}          ;; 中止管道

   使用示例:

   (def my-filter
     (create-filter :my-log :pre-invocation
       (fn [filter-ctx]
         (println \"Calling:\" (:function filter-ctx))
         {:action :continue :context filter-ctx})
       :priority 10))

   (-> (create-kernel-builder)
       (add-filter my-filter)
       (build-kernel))"
  (:require [clojure.string]
            [im.ttalk.agent.core.kernel.tool :as tool]))

;;; ============================================================
;;; Filter 创建
;;; ============================================================

(defn create-filter
  "创建 filter 定义

   参数:
   - name:     filter 名称（keyword）
   - type:     filter 类型 :pre-invocation | :post-invocation | :pre-chat | :post-chat
   - handler:  处理函数 (fn [filter-ctx] -> filter-result)
   - opts:     可选参数
     :priority  优先级（数字越小越先执行，默认 0）

   返回:
   filter 定义 map"
  [name type handler & {:keys [priority] :or {priority 0}}]
  {:name     name
   :type     type
   :handler  handler
   :priority priority})

;;; ============================================================
;;; Filter 管道执行
;;; ============================================================

(defn- sort-filters
  "按优先级排序 filters（数字越小越先执行）"
  [filters]
  (sort-by :priority filters))

(defn- filters-by-type
  "获取指定类型的 filters 并按优先级排序"
  [filters type]
  (sort-filters (filter #(= type (:type %)) filters)))

(defn- run-pipeline
  "通用 filter 管道执行器

   参数:
   - filters:     该类型的 filter 列表（已排序）
   - init-state:  初始状态 map
   - build-ctx:   (fn [state] -> filter-ctx) 构建传给 handler 的上下文
   - extract:     (fn [handler-result state] -> updated-state) 从 handler 返回值提取更新
   - wrap-ok:     (fn [final-state] -> result) 包装最终成功结果

   返回:
   {:ok ...} | {:skip ...} | {:error ...}"
  [filters init-state build-ctx extract wrap-ok]
  (loop [remaining filters
         state init-state]
    (if (empty? remaining)
      {:ok (wrap-ok state)}
      (let [f (first remaining)
            result ((:handler f) (build-ctx state))]
        (case (:action result)
          :continue (recur (rest remaining) (extract result state))
          :skip     {:skip (:value result)}
          :error    {:error (:reason result)}
          ;; 默认视为 continue
          (recur (rest remaining) state))))))

(defn apply-pre-invocation-filters
  "执行 pre-invocation filter 管道

   参数:
   - filters:  所有 filter 定义列表
   - func-def: 函数定义信息 {:name :schema ...}
   - args:     调用参数 map
   - context:  Context 对象

   返回:
   {:ok {:args a :context c}} | {:skip value} | {:error reason}"
  [filters func-def args context]
  (run-pipeline
    (filters-by-type filters :pre-invocation)
    {:args args :context context}
    (fn [state]
      {:function func-def :args (:args state)
       :context (:context state) :metadata {}})
    (fn [result state]
      (let [ctx (:context result)]
        {:args (or (:args ctx) (:args state))
         :context (or (:context ctx) (:context state))}))
    identity))

(defn apply-post-invocation-filters
  "执行 post-invocation filter 管道

   参数:
   - filters:  所有 filter 定义列表
   - func-def: 函数定义信息
   - args:     调用参数 map
   - result:   函数执行结果
   - context:  Context 对象

   返回:
   {:ok {:result r :context c}} | {:error reason}"
  [filters func-def args result context]
  (run-pipeline
    (filters-by-type filters :post-invocation)
    {:result result :context context}
    (fn [state]
      {:function func-def :args args :result (:result state)
       :context (:context state) :metadata {}})
    (fn [r state]
      (let [ctx (:context r)]
        {:result (or (:result ctx) (:result state))
         :context (or (:context ctx) (:context state))}))
    identity))

(defn apply-pre-chat-filters
  "执行 pre-chat filter 管道

   参数:
   - filters:  所有 filter 定义列表
   - messages: 消息列表
   - context:  Context 对象

   返回:
   {:ok {:messages m :context c}} | {:error reason}"
  [filters messages context]
  (run-pipeline
    (filters-by-type filters :pre-chat)
    {:messages messages :context context}
    (fn [state]
      {:messages (:messages state) :context (:context state) :metadata {}})
    (fn [r state]
      (let [ctx (:context r)]
        {:messages (or (:messages ctx) (:messages state))
         :context (or (:context ctx) (:context state))}))
    identity))

(defn apply-post-chat-filters
  "执行 post-chat filter 管道

   参数:
   - filters:  所有 filter 定义列表
   - response: LLM 响应
   - context:  Context 对象

   返回:
   {:ok {:response r :context c}} | {:error reason}"
  [filters response context]
  (run-pipeline
    (filters-by-type filters :post-chat)
    {:response response :context context}
    (fn [state]
      {:response (:response state) :context (:context state) :metadata {}})
    (fn [r state]
      (let [ctx (:context r)]
        {:response (or (:response ctx) (:response state))
         :context (or (:context ctx) (:context state))}))
    identity))

;;; ============================================================
;;; 内置 Filter: 日志
;;; ============================================================

(def logging-pre-filter
  "日志 pre-invocation filter — 打印工具调用信息"
  (create-filter :logging-pre :pre-invocation
    (fn [filter-ctx]
      (println (str "[Kernel] 调用工具: " (get-in filter-ctx [:function :name])
                    " 参数: " (pr-str (:args filter-ctx))))
      {:action :continue :context filter-ctx})
    :priority 100))

(def logging-post-filter
  "日志 post-invocation filter — 打印工具结果"
  (create-filter :logging-post :post-invocation
    (fn [filter-ctx]
      (let [r (:result filter-ctx)]
        (println (str "[Kernel] 工具结果: " (get-in filter-ctx [:function :name])
                      " => " (if (and (string? r) (> (count r) 100))
                               (str (subs r 0 100) "...")
                               (pr-str r)))))
      {:action :continue :context filter-ctx})
    :priority 100))

;;; ============================================================
;;; 内置 Filter: 异常捕获
;;; ============================================================

(def error-handling-filter
  "异常捕获 pre-invocation filter（最高优先级）

   包裹在管道最外层，确保不会因异常中断对话循环。
   注意：此 filter 的 handler 中不实际捕获异常，
   异常捕获在 core/invoke-tool 层面处理。
   此 filter 作为标记存在，可用于自定义错误处理逻辑。"
  (create-filter :error-handling :pre-invocation
    (fn [filter-ctx]
      {:action :continue :context filter-ctx})
    :priority -100))

;;; ============================================================
;;; 内置 Filter: 超时控制
;;; ============================================================

(defn timeout-filter
  "超时控制 pre-invocation filter 工厂

   如果后续执行（包括函数调用）超过指定时间，返回 :skip 结果。
   注意：实际超时控制在 invoke-tool 中配合使用。

   参数:
   - timeout-ms: 超时时间（毫秒）

   返回:
   filter 定义 map"
  [timeout-ms]
  (create-filter :timeout :pre-invocation
    (fn [filter-ctx]
      ;; 在 context 中标记超时限制，供 invoke-tool 使用
      {:action :continue
       :context (assoc filter-ctx :timeout-ms timeout-ms)})
    :priority -50))

;;; ============================================================
;;; 内置 Filter: 敏感工具审批
;;; ============================================================

(defn approval-filter
  "敏感工具审批 pre-invocation filter

   对标记为 :sensitive 的工具调用进行人工确认。

   参数:
   - approve-fn: 审批函数 (fn [func-name args] -> boolean)
                 默认使用标准输入输出交互

   返回:
   filter 定义 map"
  ([]
   (approval-filter nil))
  ([approve-fn]
   (let [default-approve (fn [func-name args]
                           (println (str "\n[审批] 敏感工具调用:"))
                           (println (str "  工具: " func-name))
                           (println (str "  参数: " (pr-str args)))
                           (print "  是否允许执行? (y/n): ")
                           (flush)
                           (= "y" (clojure.string/lower-case (or (read-line) ""))))
         approve (or approve-fn default-approve)]
     (create-filter :approval :pre-invocation
       (fn [filter-ctx]
         (let [func-def (:function filter-ctx)
               is-sensitive? (:sensitive func-def)]
           (if is-sensitive?
             (if (approve (:name func-def) (:args filter-ctx))
               {:action :continue :context filter-ctx}
               {:action :skip :value "用户拒绝了此敏感工具调用"})
             {:action :continue :context filter-ctx})))
       :priority 50))))
