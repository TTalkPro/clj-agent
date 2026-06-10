(ns im.ttalk.agent.provider.providers-test
  "OpenAI 兼容 provider（base/defprovider 生成）+ 工厂注册的单元测试。

   不触网：仅验证 create-provider / provider-name / 宏校验项 / 工厂注册。"
  (:require [clojure.test :refer [deftest testing is are]]
            [im.ttalk.agent.model :as model]
            [im.ttalk.agent.provider.api :as llm]
            [im.ttalk.agent.provider.openai :as openai]
            [im.ttalk.agent.provider.zhipu :as zhipu]
            [im.ttalk.agent.provider.gemini :as gemini]
            [im.ttalk.agent.provider.mistral :as mistral]
            [im.ttalk.agent.provider.deepseek :as deepseek]
            [im.ttalk.agent.provider.minimax :as minimax]
            [im.ttalk.agent.provider.ollama :as ollama]
            [im.ttalk.agent.provider.mock :as mock]))

;;; ============================================================
;;; defprovider 生成的 provider
;;; ============================================================

(deftest defprovider-creates-valid-provider
  (testing "create-provider 返回实现 ILLMProvider 的实例，provider-name 正确"
    (are [thunk expected]
         (let [p (thunk)]
           (and (satisfies? model/ILLMProvider p)
                (= expected (model/provider-name p))))
      #(openai/create-provider {:api-key "k"})    :openai
      #(zhipu/create-provider {:api-key "k"})      :zhipu
      #(gemini/create-provider {:api-key "k"})     :gemini
      #(mistral/create-provider {:api-key "k"})    :mistral
      #(deepseek/create-provider {:api-key "k"})   :deepseek
      #(minimax/create-provider {:api-key "k"})    :minimax
      #(ollama/create-provider {:model "llama2"})  :ollama)))

(deftest multi-instance-config-isolation
  (testing "同类 provider 多实例持有独立 config，不互相覆盖 key/base-url（回归 D1）"
    (let [p1 (openai/create-provider {:api-key "KEY-A" :base-url "https://a.example"})
          p2 (openai/create-provider {:api-key "KEY-B" :base-url "https://b.example"})]
      ;; 修复前：两实例共享全局 default-config atom，identical? 为 true 且 KEY-A 被覆盖
      (is (not (identical? (:config p1) (:config p2))))
      (is (= "KEY-A" (:api-key @(:config p1))))
      (is (= "KEY-B" (:api-key @(:config p2))))
      (is (= "https://a.example" (:base-url @(:config p1))))
      (is (= "https://b.example" (:base-url @(:config p2)))))))

;;; ============================================================
;;; 宏选项：:require-api-key?
;;; ============================================================

(deftest require-api-key-validation
  (testing "require-api-key? 的 provider 在 api-key 为空时抛 ExceptionInfo"
    ;; 显式空串 ⇒ get-api-key 返回 ""（blank），与环境变量无关，确定性触发
    (are [thunk] (thrown? clojure.lang.ExceptionInfo (thunk))
      #(gemini/create-provider {:api-key ""})
      #(mistral/create-provider {:api-key ""})
      #(deepseek/create-provider {:api-key ""})
      #(minimax/create-provider {:api-key ""})
      #(zhipu/create-provider {:api-key ""})
      #(zhipu/create-anthropic-provider {:api-key ""})))
  (testing "提供 api-key 即可正常创建"
    (is (= :deepseek (model/provider-name (deepseek/create-provider {:api-key "sk-x"}))))))

;;; ============================================================
;;; 智谱双协议
;;; ============================================================

(deftest zhipu-dual-protocol
  (testing "Anthropic 兼容协议 provider：provider-name 仍为 :zhipu，能力齐全"
    (let [p (zhipu/create-anthropic-provider {:api-key "k"})]
      (is (satisfies? model/ILLMProvider p))
      (is (= :zhipu (model/provider-name p)))
      (is (model/supports-function-calling? p))
      (is (model/supports-stream? p)))))

;;; ============================================================
;;; 宏选项：:require-model? 与 :api-key 预置（ollama）
;;; ============================================================

(deftest require-model-validation
  (testing "ollama 缺少 :model 抛错"
    (is (thrown? clojure.lang.ExceptionInfo (ollama/create-provider {})))
    (is (thrown? clojure.lang.ExceptionInfo (ollama/create-provider {:base-url "http://x"}))))
  (testing "提供 :model 即可创建（无需 api-key）"
    (is (= :ollama (model/provider-name (ollama/create-provider {:model "llama2"}))))))

;;; ============================================================
;;; 工厂注册
;;; ============================================================

(deftest factory-registers-all-builtins
  (testing "supported-providers 含全部内置 provider（含 deepseek / minimax / dashscope / openai-compat）"
    (llm/create-provider :mock)   ;; 触发延迟注册
    (let [supported (set (llm/supported-providers))]
      (are [k] (contains? supported k)
        :openai :anthropic :zhipu :ollama :gemini :mistral :deepseek :minimax :dashscope :openai-compat :mock))))

(deftest mock-error-and-history-test
  (testing "create-error-mock 调用时抛 ex-info（回归：曾把函数对象当文本返回不抛错）"
    (let [p (mock/create-error-mock)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (model/call-llm p {} [{:role "user" :content "hi"}] [])))))
  (testing "record-history? false 时 get/clear 不 NPE，返回 []"
    (let [p (mock/create-mock-provider {:record-history? false})]
      (is (= [] (mock/get-call-history p)))
      (is (= [] (mock/clear-call-history p))))))

(deftest openai-compat-registered-and-creatable
  (testing "openai-compat 经 factory 创建：base-url 必填，provider-name 为 :openai-compat"
    (let [p (llm/create-provider :openai-compat {:base-url "http://localhost:8000/v1" :api-key "k"})]
      (is (= :openai-compat (model/provider-name p)))
      (is (model/supports-stream? p)))
    (testing "缺 :base-url 抛错"
      (is (thrown? clojure.lang.ExceptionInfo
                   (llm/create-provider :openai-compat {:api-key "k"}))))))

(deftest dashscope-registered-and-creatable
  (testing "dashscope 可经 factory 创建，声明原生支持流式"
    (let [p (llm/create-provider :dashscope {:api-key "k"})]
      (is (= :dashscope (model/provider-name p)))
      (is (true? (model/supports-stream? p))))))   ;; 原生 SSE（X-DashScope-SSE）
