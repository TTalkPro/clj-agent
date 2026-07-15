(ns im.ttalk.agent.callbacks
  "Agent 独立回调系统 — 与 kernel advisor/filter 完全解耦。

   9 个回调钩子（对标 beamai_agent_callbacks）：
     :on-turn-start   (fn [metadata])                  新 turn 开始
     :on-turn-end     (fn [metadata])                  turn 正常完成
     :on-turn-error   (fn [error metadata])            turn 出错
     :on-llm-call     (fn [messages metadata])         每次 LLM 调用前（观察用）
     :on-llm-result   (fn [response metadata])         每次 LLM 返回后（观察用）
     :on-tool-call    (fn [tool-name args])            tool 调用前；返回 {:interrupt reason} 触发中断
     :on-tool-result  (fn [tool-name result])          tool 执行后（观察用）

   注意：`tool-name` 两处都是**字符串**（非 keyword）——拿 keyword 去 `=` 比较
   会永不相等，于是 `:on-tool-call` 的中断判断静默失效（曾真的踩过）。
     :on-interrupt    (fn [interrupt-info metadata])   进入中断状态时
     :on-resume       (fn [interrupt-info metadata])   从中断状态恢复时

   设计原则：
     - 大部分回调仅观察，不影响主流程
     - :on-tool-call 是唯一可影响流程的回调（返回 {:interrupt reason} 触发中断）
     - 回调抛异常时静默忽略（确保不中断主流程）
     - 与 kernel filter/advisor 完全解耦：回调不走 filter 链，直接在关键节点触发

   与 kernel filter 的区别：
     - kernel filter  洋葱式 around 拦截，可改写请求/响应，适合 memory/retry/cache
     - callback       轻量事件通知，适合日志/监控/审计/告警/中断控制")

(set! *warn-on-reflection* true)

(defn invoke
  "安全调用回调函数。

   从 callbacks map 中查找 kw 对应的回调，找到后用 args 调用并返回结果。
   未注册时返回 nil，异常时静默忽略并返回 nil。
   调用方不应依赖 :on-tool-call 以外的回调返回值（其余纯观察）。"
  [callbacks kw & args]
  (when-let [cb (get callbacks kw)]
    (try
      (apply cb args)
      (catch Throwable _
        nil))))

(defn build-metadata
  "从 agent map 构建传给回调的标准化元数据。

   键：:agent-id, :conversation-id, :turn-count, :run-id, :timestamp"
  ([agent]
   (build-metadata agent nil))
  ([agent run-id]
   (let [state (when-let [sa (:state-atom agent)] @sa)]
     {:agent-id        (:id agent)
      :conversation-id (:conversation-id agent)
      :turn-count      (get state :turn-count 0)
      :run-id          run-id
      :timestamp       (System/currentTimeMillis)})))
