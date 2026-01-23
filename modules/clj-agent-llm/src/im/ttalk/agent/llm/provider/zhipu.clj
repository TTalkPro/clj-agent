(ns im.ttalk.agent.llm.provider.zhipu
  "智谱 AI (ZhiPu) Provider 实现

   实现 ILLMProvider 协议，提供智谱 AI API 的完整访问。

   API 文档：https://docs.bigmodel.cn/api-reference

   使用示例：

   (require '[im.ttalk.agent.llm.provider.zhipu :as zhipu])

   (def provider (zhipu/create-provider))

   ;; 同步调用
   (zhipu/call-zhipu config messages tools)"
  (:require [im.ttalk.agent.llm.provider.base :as base]))

;;; ============================================================
;;; 配置
;;; ============================================================

(def ^:private default-config
  "默认智谱 AI 配置"
  (base/make-config
    :zhipu
    "https://open.bigmodel.cn/api/paas/v4/chat/completions"
    "ZHIPU_API_KEY"
    :timeout 120000
    :default-model "glm-4"))

;;; ============================================================
;;; API 调用
;;; ============================================================

(defn call-zhipu
  "调用智谱 AI API（同步）"
  [config messages tools]
  (base/call-api default-config config messages tools))

(defn call-zhipu-stream
  "流式调用智谱 AI API"
  [config messages tools on-token]
  (base/call-api-stream default-config config messages tools on-token))

(defn call-zhipu-async
  "异步调用智谱 AI API"
  [config messages tools callback]
  (base/call-api-async default-config config messages tools callback))

(defn call-zhipu-stream-async
  "异步流式调用智谱 AI API"
  [config messages tools on-token on-complete & [on-error]]
  (base/call-api-stream-async default-config config messages tools
                              on-token on-complete on-error))

;;; ============================================================
;;; 工厂函数
;;; ============================================================

(defn create-provider
  "创建智谱 AI Provider 实例"
  ([] (base/create-provider default-config))
  ([opts] (base/create-provider-with-opts default-config opts)))

