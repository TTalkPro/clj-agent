(ns im.ttalk.agent.test-support
  "SimpleAgent 测试公共设施

   提供 MockProvider、工具定义等共享测试基础设施。"
  (:require [im.ttalk.agent.model :as provider]
            [im.ttalk.agent.tool :refer [deftool]]))

;;; ============================================================
;;; Mock Provider
;;; ============================================================

(defrecord MockProvider [responses-atom]
  provider/ILLMProvider
  (provider-name [_] :mock)
  (call-llm [_ config messages tools]
    (let [resp (first @responses-atom)]
      (swap! responses-atom rest)
      (or resp {:text "默认回复" :tool-calls nil})))
  (extract-tool-calls [_ response]
    (:tool-calls response))
  (extract-text [_ response]
    (:text response))
  (build-tool-result [_ tool-id content]
    {:role "tool" :tool_call_id tool-id :content content})
  (supports-function-calling? [_] true)
  (supports-stream? [_] false)
  (call-llm-stream [this config messages tools on-token]
    (provider/call-llm this config messages tools))
  (tool->schema [_ tool] tool))

(defn create-mock-provider
  "创建 Mock Provider

   参数:
   - responses: 预设响应列表，按顺序返回"
  [responses]
  (->MockProvider (atom responses)))

;;; ============================================================
;;; Kernel Agent 测试工具
;;; ============================================================

(deftool mock-get-weather
  "获取天气"
  [[city :string "城市名"]]
  (str city ": 晴天 25°C"))

(deftool mock-calculate
  "计算"
  [[expr :string "表达式"]]
  (str "结果: 42"))

(def mock-tools
  "Kernel Agent 测试工具集"
  [#'mock-get-weather #'mock-calculate])

;;; ============================================================
;;; Process Agent 测试工具
;;; ============================================================

(deftool safe-tool
  "安全工具"
  [[input :string "输入"]]
  (str "安全结果: " input))

(deftool dangerous-tool
  "危险工具（需要审批）"
  [[target :string "目标"]]
  {:sensitive true}
  (str "已执行危险操作: " target))

(deftool another-safe-tool
  "另一个安全工具"
  [[data :string "数据"]]
  (str "处理: " data))

(def test-plugin
  "Process Agent 测试工具集"
  [#'safe-tool #'dangerous-tool #'another-safe-tool])
