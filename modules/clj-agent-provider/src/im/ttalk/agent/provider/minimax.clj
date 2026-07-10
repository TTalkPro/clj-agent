(ns im.ttalk.agent.provider.minimax
  "MiniMax Provider —— Anthropic 兼容实现（MiniMax 官方推荐使用 Anthropic 格式）。

   端点：https://api.minimaxi.com/anthropic/v1/messages
   鉴权：Authorization: Bearer <MINIMAX_API_KEY>（无需 anthropic-version 头）

   通过复用 anthropic provider 的端点抽象，完整继承其请求/响应/流式/工具调用/
   prompt-cache 策略/重试机制 —— MiniMax 只是换了 base-url + 路径 + Bearer 鉴权。

   模型（M 系列推理模型，content 可能含推理过程，建议给足 :max-tokens）：
   MiniMax-M3（最新，1M 上下文）、MiniMax-M2.7、MiniMax-M2.7-highspeed、
   MiniMax-M2.5、MiniMax-M2.1、MiniMax-M2 等。

   需要 MINIMAX_API_KEY 或显式 :api-key。

   (require '[im.ttalk.agent.provider.minimax :as minimax]
            '[im.ttalk.agent.model :as model])
   (def p (minimax/create-provider {:api-key \"...\"}))
   (model/call-llm p {:model \"MiniMax-M2.7\" :max-tokens 2048}
                   [{:role :user :content \"你好\"}] nil)"
  (:require [clojure.string :as str]
            [im.ttalk.agent.provider.anthropic :as anthropic]))

(set! *warn-on-reflection* true)

(def default-model "MiniMax-M2.7")

(def ^:private minimax-endpoint
  "MiniMax Anthropic 兼容端点配置"
  {:provider-name :minimax
   :base-url "https://api.minimaxi.com"
   :api-path "/anthropic/v1/messages"
   :auth-scheme :bearer
   :anthropic-version nil})

(defn create-provider
  "创建 MiniMax Provider（Anthropic 兼容）。

   参数：
   - opts: 可选 {:api-key \"...\" :base-url \"...\" ...}，作为调用 config 默认值。
           缺省从 MINIMAX_API_KEY 读取 api-key。

   返回：AnthropicProvider 实例（provider-name 为 :minimax）。
   api-key 缺失（空白）时抛 ExceptionInfo。"
  ([] (create-provider {}))
  ([opts]
   (let [api-key (or (:api-key opts) (System/getenv "MINIMAX_API_KEY"))]
     (when (str/blank? api-key)
       (throw (ex-info "minimax provider requires :api-key or MINIMAX_API_KEY"
                       {:required :api-key})))
     (anthropic/create-provider (merge minimax-endpoint opts {:api-key api-key})))))
