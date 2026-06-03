(ns im.ttalk.agent.core.kernel.filter
  "Advisor 系统 - 洋葱式 around 链（对标 Spring AI Advisor）

   kernel 只认 Advisor：根抽象是 advise-call(req, chain)，chain = (fn [req] -> resp)
   代表下游(后续 advisor + 最内层 terminal)。advisor 自己决定调不调 chain、调几次、
   调用前后干什么 —— 可 around(短路 / 重试 / 计时)。before/after 是它的语法糖。

   同一套机制服务两类调用，靠 :phase 区分、各有不同的 terminal：
   - :chat  —— invoke-chat，terminal 调 LLM；request = {:messages :tools :tool-choice :system-prompt :context}
   - :tool  —— invoke-tool，terminal 调函数；request = {:function :args :context}

   Advisor 定义:
   {:name        :memory
    :phase       :chat | :tool
    :order       100              ;; 越小越靠外层(最先 before、最后 after)；同序按列表序
    ;; 二选一:
    :advise-call (fn [req chain] -> resp)   ;; 高级:完整 around
    :before      (fn [req] -> req')         ;; 普通:只改写请求
    :after       (fn [resp] -> resp')}      ;; 普通:只改写响应

   使用示例:
   (-> (create-kernel-builder)
       (add-advisor (logging-tool-advisor))
       (build-kernel))"
  (:require [clojure.string]))

;;; ============================================================
;;; Advisor 创建与洋葱执行器
;;; ============================================================

(defn create-advisor
  "创建 advisor 定义。

   参数:
   - name:  标识(keyword)
   - phase: :chat | :tool
   - opts:  :order(默认 0)；:advise-call 或 :before/:after(后两者为糖)

   返回: advisor 定义 map"
  [name phase & {:keys [order advise-call before after] :or {order 0}}]
  (cond-> {:name name :phase phase :order order}
    advise-call (assoc :advise-call advise-call)
    before      (assoc :before before)
    after       (assoc :after after)))

(defn- ->advise-call
  "把 advisor 归一成 around 函数 (fn [req chain] -> resp)。
   只给 before/after 的，合成 (after (chain (before req)))。"
  [advisor]
  (or (:advise-call advisor)
      (let [before (or (:before advisor) identity)
            after  (or (:after advisor) identity)]
        (fn [req chain] (after (chain (before req)))))))

(defn advisors-for-phase
  "取指定 phase 的 advisors(未排序)。"
  [advisors phase]
  (filter #(= phase (:phase %)) advisors))

(defn build-chain
  "把 advisors 折成洋葱，最内层为 terminal(真正干活，如调 LLM/调函数)。

   order 最小的在最外层：req 从外向里穿 before 段，resp 从里向外穿 after 段。
   返回 (fn [req] -> resp)。"
  [advisors terminal]
  (reduce (fn [downstream advisor]
            (let [advise (->advise-call advisor)]
              (fn [req] (advise req downstream))))
          terminal
          (reverse (sort-by :order advisors))))

;;; ============================================================
;;; 内置 tool advisor: 日志
;;; ============================================================

(def logging-tool-advisor
  "日志 tool advisor —— 打印工具调用信息与结果（around，名字在前后两段都可见）。"
  (create-advisor :logging :tool :order 100
    :advise-call
    (fn [req chain]
      (let [name (get-in req [:function :name])]
        (println (str "[Kernel] 调用工具: " name " 参数: " (pr-str (:args req))))
        (let [resp (chain req)
              r (:result resp)]
          (println (str "[Kernel] 工具结果: " name " => "
                        (if (and (string? r) (> (count r) 100))
                          (str (subs r 0 100) "...")
                          (pr-str r))))
          resp)))))

;;; ============================================================
;;; 内置 tool advisor: 超时控制（around）
;;; ============================================================

(defn timeout-tool-advisor
  "超时控制 tool advisor 工厂：下游执行超过 timeout-ms 则返回超时结果（不抛异常）。"
  [timeout-ms]
  (create-advisor :timeout :tool :order -50
    :advise-call
    (fn [req chain]
      (let [r (deref (future (chain req)) timeout-ms ::timeout)]
        (if (= r ::timeout)
          {:result (str "工具调用超时（" timeout-ms "ms）") :context (:context req)}
          r)))))

;;; ============================================================
;;; 内置 tool advisor: 敏感工具审批（around 短路）
;;; ============================================================

(defn approval-tool-advisor
  "敏感工具审批 tool advisor：对标记 :sensitive 的工具调用做人工确认，拒绝则短路。

   参数:
   - approve-fn: (fn [func-name args] -> boolean)，默认走标准输入交互"
  ([] (approval-tool-advisor nil))
  ([approve-fn]
   (let [default-approve (fn [func-name args]
                           (println (str "\n[审批] 敏感工具调用:"))
                           (println (str "  工具: " func-name))
                           (println (str "  参数: " (pr-str args)))
                           (print "  是否允许执行? (y/n): ")
                           (flush)
                           (= "y" (clojure.string/lower-case (or (read-line) ""))))
         approve (or approve-fn default-approve)]
     (create-advisor :approval :tool :order 50
       :advise-call
       (fn [req chain]
         (if (get-in req [:function :sensitive])
           (if (approve (get-in req [:function :name]) (:args req))
             (chain req)
             {:result "用户拒绝了此敏感工具调用" :context (:context req)})
           (chain req)))))))
