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
  "默认智谱 AI 配置（OpenAI 兼容接口）"
  (base/make-config
    :zhipu
    "https://open.bigmodel.cn/api/paas/v4"
    "ZHIPU_API_KEY"
    :timeout 120000
    :default-model "glm-4"))

;;; ============================================================
;;; API 调用
;;; ============================================================

(defn call-zhipu
  "调用智谱 AI API（同步）

   参数：
   - config:   配置 map {:model \"glm-4\" :max-tokens 4096 ...}
   - messages: 消息列表 [{:role \"user\" :content \"...\"}]
   - tools:    工具列表

   返回：
   智谱 AI API 响应"
  [config messages tools]
  (base/call-api default-config config messages tools))

(defn call-zhipu-stream
  "流式调用智谱 AI API（同步，阻塞当前线程）

   参数：
   - config:   配置 map
   - messages: 消息列表
   - tools:    工具列表
   - on-token: 回调函数 (fn [{:keys [token index accumulated]}] ...)

   返回：
   最终完整响应"
  [config messages tools on-token]
  (base/call-api-stream default-config config messages tools on-token))

(defn call-zhipu-async
  "异步调用智谱 AI API

   参数：
   - config:   配置 map
   - messages: 消息列表
   - tools:    工具列表
   - callback: 回调函数 (fn [response] ...)

   返回：
   nil（结果通过 callback 返回）"
  [config messages tools callback]
  (base/call-api-async default-config config messages tools callback))

(defn call-zhipu-stream-async
  "异步流式调用智谱 AI API（非阻塞）

   参数：
   - config:      配置 map
   - messages:    消息列表
   - tools:       工具列表
   - on-token:    token 回调 (fn [{:keys [token]}] ...)
   - on-complete: 完成回调 (fn [response] ...)
   - on-error:    错误回调 (fn [error] ...)（可选）

   返回：
   nil（所有结果通过回调返回）"
  [config messages tools on-token on-complete & [on-error]]
  (base/call-api-stream-async default-config config messages tools
                              on-token on-complete on-error))

;;; ============================================================
;;; 工厂函数
;;; ============================================================

(defn create-provider
  "创建智谱 AI Provider 实例

   参数：
   - opts: API 选项（可选）{:api-key \"...\" :base-url \"...\"}

   返回：
   OpenAICompatProvider record

   示例：
   (def provider (create-provider))
   (def provider (create-provider {:api-key \"...\"}))"
  ([] (base/create-provider default-config))
  ([opts] (base/create-provider-with-opts default-config opts)))

