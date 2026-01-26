(ns im.ttalk.agent.simpleagent.kernel-agent-test
  "Kernel Agent 单元测试

   使用 mock provider 验证 kernel-agent 功能。"
  (:require [clojure.test :refer :all]
            [im.ttalk.agent.simpleagent.kernel-agent :as ka]
            [im.ttalk.agent.simpleagent.test-support :as ts]
            [im.ttalk.agent.core.kernel.core :as kernel]
            [im.ttalk.agent.core.kernel.context :as ctx]
            [im.ttalk.agent.core.kernel.provider :as provider]
            [im.ttalk.agent.core.kernel.service :as service]))

;;; ============================================================
;;; 测试
;;; ============================================================

(deftest test-basic-chat
  (testing "基本对话 - 验证 text 返回"
    (let [provider (ts/create-mock-provider
                     [{:text "你好！我是助手。" :tool-calls nil}])
          agent (ka/create-agent {:provider provider :model "test"})]
      (let [result (ka/chat agent "你好")]
        (is (= "你好！我是助手。" (:text result)))
        (is (empty? (:tool-calls-made result)))))))

(deftest test-multi-turn-chat
  (testing "多轮对话 - context 自动累积"
    (let [provider (ts/create-mock-provider
                     [{:text "你好！" :tool-calls nil}
                      {:text "你刚才说了'测试消息'" :tool-calls nil}])
          agent (ka/create-agent {:provider provider :model "test"})]
      ;; 第一轮
      (ka/chat agent "测试消息")
      (is (= 2 (count (ka/get-messages agent))))  ;; user + assistant

      ;; 第二轮
      (let [result (ka/chat agent "我刚才说了什么？")]
        (is (= "你刚才说了'测试消息'" (:text result)))
        (is (= 4 (count (ka/get-messages agent))))))))  ;; 2 user + 2 assistant

(deftest test-tool-calling
  (testing "工具调用 - mock 返回 tool_calls 后 text"
    (let [provider (ts/create-mock-provider
                     [;; 第一次：返回工具调用
                      {:text nil
                       :tool-calls [{:id "call_1" :name :mock-get-weather
                                     :input {:city "北京"}}]}
                      ;; 第二次：返回文本
                      {:text "北京天气是晴天 25°C" :tool-calls nil}])
          agent (ka/create-agent {:provider provider
                                  :model "test"
                                  :tools ts/mock-tools})]
      (let [result (ka/chat agent "北京天气怎么样？")]
        (is (= "北京天气是晴天 25°C" (:text result)))
        (is (= 1 (count (:tool-calls-made result))))
        (is (= :mock-get-weather (:name (first (:tool-calls-made result)))))))))

(deftest test-reset
  (testing "reset! - context 清空"
    (let [provider (ts/create-mock-provider
                     [{:text "回复1" :tool-calls nil}
                      {:text "回复2" :tool-calls nil}])
          agent (ka/create-agent {:provider provider :model "test"})]
      (ka/chat agent "消息1")
      (is (= 2 (count (ka/get-messages agent))))

      (ka/reset! agent)
      (is (empty? (ka/get-messages agent)))
      (is (empty? (ka/get-history agent))))))

(deftest test-system-prompt
  (testing "system-prompt - 通过 settings 传递"
    (let [call-log (atom [])
          provider (reify provider/ILLMProvider
                     (provider-name [_] :spy)
                     (call-llm [_ config messages tools]
                       (swap! call-log conj {:config config :messages messages})
                       {:text "OK" :tool-calls nil})
                     (extract-tool-calls [_ r] (:tool-calls r))
                     (extract-text [_ r] (:text r))
                     (build-tool-result [_ tid c]
                       {:role "tool" :tool_call_id tid :content c})
                     (build-assistant-message [_ r]
                       {:role "assistant" :content (:text r)})
                     (build-result-messages [_ am trs]
                       (into [am] (mapv (fn [{:keys [tool-id result]}]
                                          {:role "tool" :tool_call_id tool-id :content (str result)})
                                        trs)))
                     (supports-function-calling? [_] true)
                     (supports-stream? [_] false)
                     (call-llm-stream [this c m t on] (provider/call-llm this c m t))
                     (tool->schema [_ t] t))
          agent (ka/create-agent {:provider provider
                                  :model "test"
                                  :system-prompt "你是数学助手"})]
      (ka/chat agent "1+1=?")
      (let [config (:config (first @call-log))]
        (is (= "你是数学助手" (:system-prompt config)))))))

(deftest test-pre-built-kernel
  (testing "预构建 kernel - :kernel 选项"
    (let [provider (ts/create-mock-provider
                     [{:text "来自预构建kernel" :tool-calls nil}])
          svc (service/create-service provider {:model "test" :max-tokens 100})
          k (-> (kernel/create-kernel-builder)
                (kernel/add-service svc)
                (kernel/build-kernel))
          agent (ka/create-agent {:kernel k})]
      (let [result (ka/chat agent "测试")]
        (is (= "来自预构建kernel" (:text result)))))))

(deftest test-get-history-vs-messages
  (testing "get-history 和 get-messages 一致性"
    (let [provider (ts/create-mock-provider
                     [{:text "回复" :tool-calls nil}])
          agent (ka/create-agent {:provider provider :model "test"})]
      (ka/chat agent "你好")
      ;; messages 和 history 应该相同（无 summarize 时）
      (is (= (ka/get-messages agent) (ka/get-history agent))))))

(deftest test-chat-with-opts-override
  (testing "chat opts 可覆盖 system-prompt"
    (let [call-log (atom [])
          provider (reify provider/ILLMProvider
                     (provider-name [_] :spy2)
                     (call-llm [_ config messages tools]
                       (swap! call-log conj config)
                       {:text "OK" :tool-calls nil})
                     (extract-tool-calls [_ r] (:tool-calls r))
                     (extract-text [_ r] (:text r))
                     (build-tool-result [_ tid c]
                       {:role "tool" :tool_call_id tid :content c})
                     (build-assistant-message [_ r]
                       {:role "assistant" :content (:text r)})
                     (build-result-messages [_ am trs]
                       (into [am] (mapv (fn [{:keys [tool-id result]}]
                                          {:role "tool" :tool_call_id tool-id :content (str result)})
                                        trs)))
                     (supports-function-calling? [_] true)
                     (supports-stream? [_] false)
                     (call-llm-stream [this c m t on] (provider/call-llm this c m t))
                     (tool->schema [_ t] t))
          agent (ka/create-agent {:provider provider
                                  :model "test"
                                  :system-prompt "默认提示"})]
      (ka/chat agent "问题" {:system-prompt "覆盖提示"})
      (is (= "覆盖提示" (:system-prompt (first @call-log)))))))
