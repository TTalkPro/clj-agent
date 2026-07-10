(ns im.ttalk.agent.provider.openai
  "OpenAI Provider —— OpenAI 兼容实现（统一由 base/defprovider 生成）。

   生成：default-config、call-openai{,-stream,-async,-stream-async}、create-provider。
   同步（单次 HTTP）与流式（SSE）调用均支持；流式回调 (fn [{:keys [token ...]}] ...)。

   调用 config 支持的 OpenAI 专属能力（均「存在才发送」，详见
   common.openai-compat/build-params）：
   - 工具调用：:tool-choice、:parallel-tool-calls（并行工具调用开关，精确透传 false）
   - 推理控制：:reasoning-effort（o 系列 / GPT-5：low|medium|high）
                :verbosity（GPT-5 输出冗长度：low|medium|high）
   - 多模态输出：:modalities（如 [\"text\" \"audio\"]）:audio（{:voice X :format Y}，gpt-4o-audio）
   - 结构化输出：:response-format
       {:type \"json_object\"} 或
       {:type \"json_schema\" :json_schema {:name \"X\" :strict true :schema {...}}}
       （可用 core 的 converter.json-schema/to-openai-response-format 从 schema 生成）
   - 采样：:temperature :top-p :max-tokens :stop :seed :n
            :frequency-penalty :presence-penalty :logprobs :top-logprobs
   - 流式 usage：:stream-options {:include_usage true}
   - 私有字段逃生通道：:extra-body（直接 merge 进请求体）
   - prompt caching：OpenAI 自动生效，命中 token 归一化到响应 usage 的
                     :cache-read-tokens（源自 prompt_tokens_details.cached_tokens）

   工具定义：deftool 生成的 schema（:input_schema 形态）与 OpenAI 风格
   （:parameters 形态）均被 schema.openai/tool->schema 正确识别，参数不丢失。

   (require '[im.ttalk.agent.provider.openai :as openai])
   (def provider (openai/create-provider {:api-key \"sk-...\"}))"
  (:require [im.ttalk.agent.provider.common.base :as base]))

(set! *warn-on-reflection* true)

(base/defprovider openai
  :base-url "https://api.openai.com/v1"
  :env-key "OPENAI_API_KEY"
  :default-model "gpt-4")
