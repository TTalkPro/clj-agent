(ns im.ttalk.agent.llm.provider.mock
  "Mock LLM Provider - 用于测试和开发

   提供无需 API key 的 Mock LLM，支持：
   - 返回预设响应
   - 记录调用历史
   - 预设测试场景

   使用示例：

   (require '[im.ttalk.agent.llm.provider.mock :as mock])

   (def provider (mock/create-mock-provider))

   ;; 设置自定义响应
   (mock/set-mock-response provider \"Hello from Mock!\")"
  (:require [im.ttalk.agent.llm.core.protocol :as proto]))

;;; ============================================================
;;; 辅助函数
;;; ============================================================

(defn- default-mock-response
  "生成默认的 mock 响应"
  [input]
  (str "这是对以下问题的 mock 回答：\n\n"
       input
       "\n\n"
       "【注意】这是 Mock LLM 的响应，不是真实的 LLM 输出。"))

;;; ============================================================
;;; MockLLMProvider 实现
;;; ============================================================

(defrecord MockLLMProvider [config call-history]
  proto/ILLMProvider
  (provider-name [_] :mock)

  (call-llm [this config messages tools]
    (let [user-message (last messages)
          mock-response (or (:mock-response config)
                            (:mock-response @(:config this))
                            (default-mock-response (:content user-message)))
          result {:text mock-response
                  :mock true
                  :timestamp (System/currentTimeMillis)}]
      ;; 记录调用历史
      (when (:call-history this)
        (swap! (:call-history this) conj
               {:type :call
                :config config
                :messages messages
                :tools tools
                :result result
                :timestamp (System/currentTimeMillis)}))
      result))

  (call-llm-stream [this config messages tools on-token]
    ;; Mock 流式调用：逐字符返回
    (let [response (proto/call-llm this config messages tools)
          text (:text response)]
      (doseq [[i ch] (map-indexed vector text)]
        (on-token {:token (str ch)
                   :index i
                   :accumulated (subs text 0 (inc i))}))
      response))

  (extract-tool-calls [_ _response] [])

  (extract-text [_ response]
    (:text response))

  (build-tool-result [_ tool-id content]
    {:role "tool"
     :tool_call_id tool-id
     :content (if (string? content) content (pr-str content))})

  (supports-function-calling? [_] false)
  (supports-stream? [_] true)

  (tool->schema [_ tool]
    {:type "function"
     :function {:name (name (:name tool))
                :description (:description tool)
                :parameters (:parameters tool)}})

  (build-assistant-message [_ response]
    {:role "assistant" :content (:text response)})

  (build-result-messages [_ assistant-msg tool-results]
    (into [assistant-msg]
          (mapv (fn [{:keys [tool-id result error]}]
                  {:role "tool"
                   :tool_call_id tool-id
                   :content (or result (str "Error: " error))})
                tool-results))))

;;; ============================================================
;;; Mock 特有方法
;;; ============================================================

(defn get-call-history
  "获取 Mock Provider 的调用历史

   参数：
   - provider: MockLLMProvider 实例

   返回：
   调用记录列表 [{:type :call :config ... :messages ... :result ...}]"
  [provider]
  @(:call-history provider))

(defn clear-call-history
  "清空 Mock Provider 的调用历史

   参数：
   - provider: MockLLMProvider 实例

   返回：
   空列表 []"
  [provider]
  (reset! (:call-history provider) []))

(defn set-mock-response
  "设置 Mock Provider 的默认响应

   参数：
   - provider: MockLLMProvider 实例
   - response: 响应文本（字符串）

   返回：
   更新后的 provider"
  [provider response]
  (swap! (:config provider) assoc :mock-response response)
  provider)

;;; ============================================================
;;; 工厂函数
;;; ============================================================

(defn create-mock-provider
  "创建 Mock LLM Provider

   参数：
   - opts: 可选配置
     - :mock-response   - 默认 mock 响应
     - :record-history? - 是否记录调用历史（默认 true）

   示例：
   (def mock (create-mock-provider))
   (def mock (create-mock-provider {:mock-response \"Hello!\"}))"
  [& [opts]]
  (let [config (atom (merge {:max-tokens 4096
                             :record-history? true}
                            opts))
        call-history (when (not= false (:record-history? opts))
                       (atom []))]
    (->MockLLMProvider config call-history)))

;;; ============================================================
;;; 便捷函数
;;; ============================================================

(defn mock-call
  "便捷函数：直接调用 Mock LLM

   参数：
   - messages: 消息列表
   - config:   配置（可选）
   - tools:    工具列表（可选）

   返回：
   Mock 响应 map"
  ([messages]
   (mock-call messages {}))
  ([messages config]
   (mock-call messages config []))
  ([messages config tools]
   (let [provider (create-mock-provider config)]
     (proto/call-llm provider config messages tools))))

;;; ============================================================
;;; 预设场景
;;; ============================================================

(defn create-calculator-mock
  "创建计算器场景的 Mock Provider

   返回：
   预设为计算器回复的 MockLLMProvider 实例"
  []
  (create-mock-provider
    {:mock-response "我可以帮你进行数学计算，请提供表达式。"}))

(defn create-error-mock
  "创建会返回错误的 Mock Provider（用于测试错误处理）

   返回：
   调用时抛出异常的 MockLLMProvider 实例"
  []
  (create-mock-provider
    {:mock-response
     (fn [_]
       (throw (ex-info "Mock LLM 错误"
                       {:type :mock-error})))}))

