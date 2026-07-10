(ns im.ttalk.agent.provider.deepseek
  "DeepSeek Provider —— OpenAI 兼容实现（统一由 base/defprovider 生成）。

   需要 DEEPSEEK_API_KEY 或显式 :api-key。
   模型：deepseek-chat、deepseek-reasoner。

   DeepSeek 专属差异（均已支持）：
   - reasoning_content：deepseek-reasoner 的思维链（同步 message.reasoning_content /
     流式 delta.reasoning_content），统一归一化到 :reasoning 字段，:text 为干净答案；
     流式推理 token 经回调 :reasoning-token 单独下发
   - 前缀续写（beta）：call-prefix-completion / call-prefix-completion-stream，
     最后一条 assistant 消息标记 prefix:true，模型从该前缀继续生成
     （自动走 https://api.deepseek.com/beta 路径），常与 :stop 搭配
   - SSE 末块 usage：流式最后一个 chunk 原生携带 usage
     （含 prompt_cache_hit_tokens / prompt_cache_miss_tokens），已捕获并归一化为
     :cache-read-tokens / :cache-miss-tokens
   - 注意：deepseek-reasoner 不支持 logprobs/top_logprobs（设置会报错），
     temperature/top_p/presence_penalty/frequency_penalty 会被忽略 ——
     本实现「存在才发送」，不传即安全

   作为 OpenAI 兼容实现，同样继承 common.openai-compat 的通用能力：
   :parallel-tool-calls、:response-format（json_object / json_schema+strict 结构化输出）、
   :stream-options、:extra-body 等（:reasoning-effort/:verbosity 为 OpenAI 系参数，
   DeepSeek 当前忽略，但传入安全）。

   (require '[im.ttalk.agent.provider.deepseek :as deepseek])
   (def provider (deepseek/create-provider {:api-key \"sk-...\"}))

   ;; 前缀续写：让模型续写 \"春天的风\"
   (deepseek/call-prefix-completion
     {:model \"deepseek-chat\" :max-tokens 256}
     [{:role \"user\" :content \"写一句诗\"}
      {:role \"assistant\" :content \"春天的风\"}]
     nil)"
  (:require [clojure.string :as str]
            [im.ttalk.agent.provider.common.base :as base]
            [im.ttalk.agent.provider.common.openai-compat :as compat]))

(set! *warn-on-reflection* true)

(base/defprovider deepseek
  :base-url "https://api.deepseek.com"
  :env-key "DEEPSEEK_API_KEY"
  :default-model "deepseek-chat"
  :require-api-key? true)

;;; ============================================================
;;; 前缀续写（Chat Prefix Completion，beta）
;;; ============================================================
;;; 文档：base_url 须为 https://api.deepseek.com/beta，
;;; 最后一条消息为 assistant 且 prefix=true，模型从其 content 继续生成。

(def beta-base-url
  "DeepSeek beta 功能基础 URL（前缀续写等）"
  "https://api.deepseek.com/beta")

(defn- assistant-role? [role]
  (contains? #{:assistant "assistant"} role))

(defn mark-prefix
  "把最后一条 assistant 消息标记为前缀（prefix: true）

   参数：
   - messages: wire 格式消息列表，最后一条必须是 assistant

   返回：标记后的消息向量；最后一条非 assistant 时抛 ExceptionInfo。"
  [messages]
  (let [v (vec messages)
        last-m (peek v)]
    (when-not (and last-m (assistant-role? (:role last-m)))
      (throw (ex-info "prefix completion requires the last message to be assistant"
                      {:last-role (:role last-m) :provider :deepseek})))
    (assoc v (dec (count v)) (assoc last-m :prefix true))))

(defn- beta-url [config]
  (-> (or (:beta-base-url config) beta-base-url)
      (str/replace #"/+$" "")
      (str "/chat/completions")))

(defn- api-key* [config]
  (or (:api-key config) (base/get-api-key default-config)))

(defn call-prefix-completion
  "对话前缀续写（同步，beta）

   最后一条 assistant 消息的 content 作为前缀，模型从其继续生成。
   config 与 call-deepseek 一致（建议搭配 :stop 控制结束位置）。

   返回：OpenAI 格式响应（续写内容在 choices[0].message.content）。"
  [config messages tools]
  (compat/call-api (beta-url config)
                   (api-key* config)
                   config
                   (mark-prefix messages)
                   tools
                   {:timeout (or (:timeout config) 120000)}))

(defn call-prefix-completion-stream
  "对话前缀续写（SSE 流式，beta）

   参数：
   - on-token: 回调 (fn [{:keys [token reasoning-token ...]}] ...)

   返回：与同步调用兼容的最终响应（含末块 usage、真实 finish_reason）。"
  [config messages tools on-token]
  (compat/call-api-stream (beta-url config)
                          (api-key* config)
                          config
                          (mark-prefix messages)
                          tools
                          on-token
                          {:timeout (or (:timeout config) 120000)}))
