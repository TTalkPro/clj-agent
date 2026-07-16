(ns im.ttalk.agent.tool-calling-manager
  "工具调用批次执行协议。")

(set! *warn-on-reflection* true)

(defprotocol ToolCallingManager
  "工具执行统一管理 seam。

   本协议只提升工具执行入口，使 ReAct 循环可注入替代实现；它不接管声明级
   :serial 策略，不替代单工具 :tool filter around 链，也不接管 :writes 在
   ctx/apply-writes 屏障处的折叠。"
  (execute-tool-calls [this kernel response opts]
    "从 ILLMResponse 抽取 tool_calls、调度执行并返回 ToolExecutionResult。

     opts 为 {:gate :tool-context :records :on-tool-result}。manager 内部通过
     response/response-tool-calls 提取调用。返回稳定的 ToolExecutionResult map：
     {:messages :records :context :errors}。"))

(defn manager-timeout
  "引擎为**没有声明 `:timeout`** 的工具设定的缺省超时（毫秒）；nil = 不超时。

   **为什么时间上限属于引擎**：manager 就是执行引擎（线程模型 + 隔离边界 +
   调度策略），「这批工具最多跑多久」是执行策略的一部分，故随引擎一起构造——
   而不是散落在某个 filter 或全局配置里。与 beamai `manager_opts.tool_timeout`
   同一立场。

   优先级：**工具声明 `deftool {:timeout ms}` > 本值 > 不超时**。
   工具最清楚自己要跑多久；引擎值是「这个部署里没特别声明的工具一律封顶多少」。

   实现方式是读 record 的 `:timeout` 字段而非加协议方法——加方法会打断既有的
   自定义实现（`reify` 出来的 manager 会在调用时抛 AbstractMethodError）。
   自定义实现没有该字段即 nil，自然退化为「不设缺省」。"
  [m]
  (when m (:timeout m)))
