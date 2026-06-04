(ns im.ttalk.agent.advisor
  "Filter 系统 - 扁平 vector，注册顺序即执行顺序

    所有 filter 都是 around：(fn [req chain] -> resp)。
    filter 通过 :chat 和 :tool 键挂到对应的链上，两者可以并存。

    Filter 定义:
      {:name :my-filter
       :chat (fn [req chain] ...)     ;; 可选，挂到 chat 链
       :tool (fn [req chain] ...)}    ;; 可选，挂到 tool 链

    filter 可以通过闭包携带自己的上下文：
      (defn caching-filter []
        (let [cache (atom {})]
          {:name :cache
           :chat (fn [req chain]
                   (if-let [hit (@cache (:k req))]
                     {:resp hit}
                     (let [resp (chain req)]
                       (swap! cache assoc (:k req) (:resp resp))
                       resp)))}))

    使用示例:
    (build-kernel {:service svc
                   :tools [#'t1 #'t2]
                   :filters [memory-filter retry-filter logging-filter]})"
  (:require [clojure.string]))

;;; ============================================================
;;; Filter 创建
;;; ============================================================

(defn create-filter
  "创建 filter 定义。

    参数:
    - name: 标识(keyword)
    - opts: 可选键值对
      :chat (fn [req chain] resp)  — around-chat，可选
      :tool (fn [req chain] resp)  — around-tool，可选
      :chat 和 :tool 可以同时提供

    返回: filter 定义 map"
  [name & {:keys [chat tool]}]
  (cond-> {:name name}
    chat (assoc :chat chat)
    tool (assoc :tool tool)))

;;; ============================================================
;;; 洋葱链构建
;;; ============================================================

(defn build-chain
  "把 around 函数序列折成洋葱，最内层为 terminal。

    序列中靠前的函数在最外层（最先处理请求，最后处理响应）。
    返回 (fn [req] -> resp)。"
  [around-fns terminal]
  (reduce (fn [downstream f]
            (fn [req] (f req downstream)))
          terminal
          (reverse around-fns)))

;;; ============================================================
;;; 内置 filter: 日志
;;; ============================================================

(def logging-filter
  "日志 filter —— 打印工具调用信息与结果。"
  {:name :logging
   :tool (fn [req chain]
           (let [name (get-in req [:function :name])]
             (println (str "[Kernel] 调用工具: " name " 参数: " (pr-str (:args req))))
             (let [resp (chain req)
                   r (:result resp)]
               (println (str "[Kernel] 工具结果: " name " => "
                             (if (and (string? r) (> (count r) 100))
                               (str (subs r 0 100) "...")
                               (pr-str r))))
               resp)))})

;;; ============================================================
;;; 内置 filter: 超时控制
;;; ============================================================

(defn timeout-filter
  "超时控制 filter 工厂：下游执行超过 timeout-ms 则返回超时结果（不抛异常）。"
  [timeout-ms]
  {:name :timeout
   :tool (fn [req chain]
           (let [r (deref (future (chain req)) timeout-ms ::timeout)]
             (if (= r ::timeout)
               {:result (str "工具调用超时（" timeout-ms "ms）") :context (:context req)}
               r)))})

;;; ============================================================
;;; 内置 filter: 敏感工具审批
;;; ============================================================

(defn approval-filter
  "敏感工具审批 filter：对标记 :sensitive 的工具调用做人工确认，拒绝则短路。

    参数:
    - approve-fn: (fn [func-name args] -> boolean)，默认走标准输入交互"
  ([] (approval-filter nil))
  ([approve-fn]
   (let [default-approve (fn [func-name args]
                           (println (str "\n[审批] 敏感工具调用:"))
                           (println (str "  工具: " func-name))
                           (println (str "  参数: " (pr-str args)))
                           (print "  是否允许执行? (y/n): ")
                           (flush)
                           (= "y" (clojure.string/lower-case (or (read-line) ""))))
         approve (or approve-fn default-approve)]
     {:name :approval
      :tool (fn [req chain]
              (if (get-in req [:function :sensitive])
                (if (approve (get-in req [:function :name]) (:args req))
                  (chain req)
                  {:result "用户拒绝了此敏感工具调用" :context (:context req)})
                (chain req)))})))
