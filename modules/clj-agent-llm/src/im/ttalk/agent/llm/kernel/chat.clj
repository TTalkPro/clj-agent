(ns im.ttalk.agent.llm.kernel.chat
  "LLM Chat Service 工厂

   创建符合 Kernel service 接口的 LLM 服务 map。
   Service 格式：
     {:chat-fn           (fn [messages opts] -> normalized-response)
      :build-result-msgs (fn [assistant-msg tool-results] -> [msg ...])}

   使用示例：

   (require '[im.ttalk.agent.llm.kernel.chat :as chat])
   (require '[im.ttalk.agent.core.kernel.core :as kernel])

   ;; 方式 1: 使用 create-service 创建 service 并传给 Kernel
   (def service (chat/create-service
                  {:model \"glm-4-flash-250414\"
                   :base-url \"https://open.bigmodel.cn/api/anthropic\"
                   :api-key (System/getenv \"ZHIPU_API_KEY\")}))

   (def app-kernel
     (-> (kernel/create-kernel-builder)
         (kernel/add-plugin my-tools)
         (kernel/add-service service)
         (kernel/build-kernel)))

   ;; 对话
   (kernel/invoke-chat-with-tools app-kernel messages {})"
  (:require [im.ttalk.agent.llm.core.protocol :as proto]
            [clojure.string :as str]))

;;; ============================================================
;;; Anthropic 响应解析
;;; ============================================================

(defn- has-tool-use?
  "检查 Anthropic 响应是否包含 tool_use 块"
  [response]
  (boolean (some #(= "tool_use" (:type %))
                 (:content response))))

(defn- extract-tool-calls
  "从 Anthropic 响应中提取工具调用列表

   返回:
   [{:id \"toolu_xxx\" :name \"fn-name\" :input {...}} ...]"
  [response]
  (->> (:content response)
       (filter #(= "tool_use" (:type %)))
       (mapv (fn [{:keys [id name input]}]
               {:id    id
                :name  name
                :input (or input {})}))))

(defn- extract-text
  "从 Anthropic 响应中提取文本内容"
  [response]
  (->> (:content response)
       (filter #(= "text" (:type %)))
       (map :text)
       (str/join "\n")))

;;; ============================================================
;;; 归一化响应
;;; ============================================================

(defn- normalize-response
  "将 LLM 原始响应归一化为 Kernel 标准格式

   返回:
   {:text            文本内容（可为 nil）
    :tool-calls      工具调用列表（可为 nil）
    :assistant-msg   原始 assistant 消息（用于追加到历史）
    :raw-response    原始 API 响应}"
  [response]
  (let [tool-calls (when (has-tool-use? response)
                     (extract-tool-calls response))
        text       (extract-text response)]
    {:text          (when (seq text) text)
     :tool-calls    (when (seq tool-calls) tool-calls)
     :assistant-msg {:role    "assistant"
                     :content (:content response)}
     :raw-response  response}))

;;; ============================================================
;;; 构建工具结果消息（Anthropic 格式）
;;; ============================================================

(defn- build-result-msgs
  "将 assistant 消息和工具结果转为消息列表

   Anthropic 格式要求：
   1. 保留原始 assistant 消息（包含 tool_use blocks）
   2. 工具结果以 user 消息返回，content 中包含 tool_result blocks

   参数:
   - assistant-msg: {:role \"assistant\" :content [...]}
   - tool-results: [{:tool-id \"...\" :result \"...\" :error nil} ...]

   返回:
   [assistant-msg tool-results-msg]"
  [assistant-msg tool-results]
  [assistant-msg
   {:role    "user"
    :content (mapv (fn [{:keys [tool-id result error]}]
                     {:type        "tool_result"
                      :tool_use_id tool-id
                      :content     (or result (str "Error: " error))})
                   tool-results)}])

;;; ============================================================
;;; Service 工厂
;;; ============================================================

(defn create-service
  "创建 LLM Chat Service

   创建符合 Kernel service 接口的 map，包含 :chat-fn 和 :build-result-msgs。

   参数:
   - opts: 配置 map
     {:provider    ILLMProvider 实例（若提供则直接使用）
      :model       模型名称（默认 \"glm-4\"）
      :max-tokens  最大生成 token 数（默认 4096）
      :base-url    API 基础 URL
      :api-key     API 密钥
      :system-prompt 系统提示词（可选）
      :temperature   温度参数（可选）}

   返回:
   {:chat-fn (fn [messages opts] -> normalized-response)
    :build-result-msgs (fn [assistant-msg tool-results] -> [msg ...])}"
  [{:keys [provider model max-tokens base-url api-key
           system-prompt temperature]}]
  (let [;; 创建或使用已有 provider
        llm-provider (or provider
                         (let [create-fn (requiring-resolve
                                           'im.ttalk.agent.llm.provider.anthropic/create-provider)]
                           (create-fn (cond-> {}
                                        api-key  (assoc :api-key api-key)
                                        base-url (assoc :base-url base-url)))))
        ;; 模型配置
        config (cond-> {:model      (or model "glm-4")
                        :max-tokens (or max-tokens 4096)}
                 system-prompt (assoc :system-prompt system-prompt)
                 temperature   (assoc :temperature temperature))]
    {:chat-fn
     (fn [messages opts]
       ;; tools 已经是 Anthropic 格式（由 kernel get-tools 生成），
       ;; 直接放入 config 的 :tools 字段，跳过 schema 转换。
       ;; tool-choice 也通过 config 传入。
       (let [tool-choice (:tool-choice opts)
             ;; :none 时不传 tools，让 LLM 纯文本回复
             tools       (when (not= tool-choice :none)
                           (:tools opts))
             call-config (cond-> config
                           (seq tools)
                           (assoc :tools tools)
                           (and tool-choice (not= tool-choice :none))
                           (assoc :tool-choice
                                  (case tool-choice
                                    :auto {:type "auto"}
                                    :required {:type "any"}
                                    tool-choice)))
             response    (proto/call-llm llm-provider call-config messages nil)]
         (normalize-response response)))

     :build-result-msgs
     build-result-msgs}))
