(ns im.ttalk.agent.model.service
  "LLM Service 工厂

   提供通用的 create-service，将任意 ILLMProvider 适配为 Kernel service map。
   响应使用统一格式（response/make-response），支持 OpenAI/Anthropic 等多 Provider。

   使用示例：

   (require '[im.ttalk.agent.model.service :as service])
   (require '[im.ttalk.agent.model :as provider])

   ;; 从 provider 创建 service
   (def svc (service/create-service my-provider
              {:model \"gpt-4\" :max-tokens 4096}))

   ;; service 是一个 map：
   ;; {:chat-fn (fn [messages opts] -> ILLMResponse)}"
  (:require [im.ttalk.agent.model :as provider]
            [im.ttalk.agent.model.response :as response]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 辅助函数
;;; ============================================================

(defn- build-call-config
  "合并基础配置和调用时选项

   合并策略：
   - tool-choice 为 :none 时不传 tools（强制纯文本回复）
   - tool-choice 以**中立关键字** :auto/:required 下发，由各 provider 边界翻译为自身 wire 格式
     （OpenAI: \"auto\"/\"required\"；Anthropic: {:type \"auto\"}/{:type \"any\"}）。core 不做带
     provider 倾向的 wire 转换。
   - 仅在**确实有 tools** 时才下发 tool-choice：无 tools 还带 tool_choice，严格 OpenAI 端点会 400。
   - system-prompt 直接传递

   参数：
   - config: 基础配置 {:model ... :max-tokens ...}
   - opts:   调用时选项 {:tools [...] :tool-choice ... :system-prompt ...}

   返回：
   合并后的调用配置 map"
  [config opts]
  (let [tool-choice (:tool-choice opts)
        tools (when (not= tool-choice :none)
                (:tools opts))
        system-prompt (:system-prompt opts)]
    (cond-> config
      (seq tools)
      (assoc :tools tools)
      ;; 只在有 tools 且非 :none 时透传中立 tool-choice（provider 侧翻译）
      (and tool-choice (not= tool-choice :none) (seq tools))
      (assoc :tool-choice tool-choice)
      system-prompt
      (assoc :system-prompt system-prompt))))

;;; ============================================================
;;; Service 工厂
;;; ============================================================

(defn- normalize-response
  "将 Provider 原始响应规范化为 Kernel 统一格式

   使用 response/make-response 创建 LLMResponse，自动归一化：
   - usage: 统一为 {:input-tokens :output-tokens :total-tokens}
   - finish-reason: 统一为关键字 :stop | :tool-use | :max-tokens 等

   参数：
   - provider: ILLMProvider 实例
   - raw-response: Provider 原始响应

   返回：
   LLMResponse record（实现 ILLMResponse 协议）"
  [provider raw-response]
  (let [text (provider/extract-text provider raw-response)
        tool-calls (provider/extract-tool-calls provider raw-response)
        ;; 中立层用「容许两种常见位置」的方式取 usage/finish-reason：
        ;; 顶层（Anthropic 风格）或 choices[0]（OpenAI 风格）。reasoning 同理走 core 提取。
        usage (or (:usage raw-response)
                  (get-in raw-response [:choices 0 :usage]))
        finish-reason (or (:stop_reason raw-response)
                          (get-in raw-response [:choices 0 :finish_reason]))]
    (response/make-response
      :id (:id raw-response)
      :model (:model raw-response)
      :text (when (seq text) text)
      :reasoning (response/extract-reasoning raw-response)
      :tool-calls (when (seq tool-calls) tool-calls)
      :usage usage
      :finish-reason finish-reason
      :provider (provider/provider-name provider)
      :raw-response raw-response
      ;; 不透明回放载荷：**可选**协议，探测到才取（见 model/IReplayableResponse）。
      ;; core 全程不解释 :data——它只负责把这段数据从响应搬到中立消息，
      ;; 让下一轮的 wire 转换器原样吐回去。厂商 wire 知识仍归 provider
      ;; （见 docs/response-path-consolidation.md）。
      :replay-blocks (when (satisfies? provider/IReplayableResponse provider)
                       (provider/replay-blocks provider raw-response)))))

(defn create-service
  "从 ILLMProvider 创建 Kernel service

   参数：
   - provider: ILLMProvider 实例
   - config:   模型配置 {:model \"...\" :max-tokens n :system-prompt \"...\"}

   返回：
   {:chat-fn   (fn [messages opts] -> normalized-response)
    :stream-fn (fn [messages opts on-token] -> normalized-response)}

   - :chat-fn   同步调用。
   - :stream-fn 流式调用：on-token 接收 {:token / :reasoning-token ...}（增量，不含全文累积），
     返回最终归一化响应（与 chat-fn 同形）。provider 不支持流式时回退同步，并把全文作为
     单个 token emit（保证 chat-stream 对任何 provider 都可用）。

   说明：历史的 :build-result-msgs 已移除——对话历史由 filter/memory 的
   response->neutral 用中立消息统一构建，service 无需再提供该函数。"
  [provider config]
  {:chat-fn
   (fn [messages opts]
     (let [call-config (build-call-config config opts)
           tools       (:tools call-config)
           response    (provider/call-llm provider call-config messages tools)]
       (normalize-response provider response)))
   :stream-fn
   (fn [messages opts on-token]
     (let [call-config (build-call-config config opts)
           tools       (:tools call-config)]
       (if (provider/supports-stream? provider)
         (normalize-response provider
                             (provider/call-llm-stream provider call-config messages tools on-token))
         ;; 不支持流式：同步调用，把全文作为单个 token emit，保证调用方契约一致
         (let [resp (normalize-response provider
                                        (provider/call-llm provider call-config messages tools))]
           (when-let [t (response/response-text resp)]
             (when on-token (on-token {:token t})))
           resp))))})
