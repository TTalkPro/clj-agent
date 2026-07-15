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
