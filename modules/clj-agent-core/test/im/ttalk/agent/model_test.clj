(ns im.ttalk.agent.model-test
  "Tests for core llm provider protocol and service"
  (:require [clojure.test :refer [deftest testing is are]]
            [im.ttalk.agent.model :as provider]
            [im.ttalk.agent.model.service :as service]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.model.error :as errors]))

;;; ============================================================
;;; Mock Provider for testing
;;; ============================================================

(defrecord TestProvider [responses]
  provider/ILLMProvider
  (provider-name [_] :test)

  (call-llm [_ config messages tools]
    (let [resp (first @responses)]
      (swap! responses rest)
      resp))

  (extract-tool-calls [_ response]
    (:tool-calls response))

  (extract-text [_ response]
    (:text response))

  (build-tool-result [_ tool-id content]
    {:role "tool"
     :tool_call_id tool-id
     :content (if (string? content) content (pr-str content))})

  (supports-function-calling? [_] true)
  (supports-stream? [_] false)

  (tool->schema [_ tool] tool))

(defn- make-test-provider [& responses]
  (->TestProvider (atom (vec responses))))

;;; ============================================================
;;; Protocol Tests
;;; ============================================================

(deftest test-provider?
  (testing "provider? returns true for ILLMProvider implementations"
    (let [p (make-test-provider)]
      (is (provider/provider? p))))

  (testing "provider? returns false for nil"
    (is (not (provider/provider? nil)))))

(deftest test-provider-name
  (testing "provider-name returns the correct name"
    (let [p (make-test-provider)]
      (is (= :test (provider/provider-name p))))))

(deftest test-call-llm
  (testing "call-llm returns the configured response"
    (let [response {:text "Hello" :tool-calls nil}
          p (make-test-provider response)]
      (is (= response (provider/call-llm p {} [] nil))))))

(deftest test-extract-text
  (testing "extract-text returns text from response"
    (let [p (make-test-provider)]
      (is (= "Hello" (provider/extract-text p {:text "Hello"})))
      (is (nil? (provider/extract-text p {:text nil}))))))

(deftest test-extract-tool-calls
  (testing "extract-tool-calls returns tool calls from response"
    (let [p (make-test-provider)
          tcs [{:id "tc1" :name "calc" :args {:x 1}}]]
      (is (= tcs (provider/extract-tool-calls p {:tool-calls tcs})))
      (is (nil? (provider/extract-tool-calls p {:tool-calls nil}))))))

(deftest test-call-simple
  (testing "call-simple returns text string"
    (let [response {:text "Simple response" :tool-calls nil}
          p (make-test-provider response)]
      (is (= "Simple response" (provider/call-simple p {} []))))))

;;; ============================================================
;;; Default Implementation Tests
;;; ============================================================

(deftest test-default-implementations
  (testing "extend-type Object defaults work for unextended objects"
    ;; The Object extension provides defaults for supports-function-calling?,
    ;; supports-stream?, tool->schema, and call-llm-stream. These are tested on a plain Object.
    (let [obj (Object.)]
      (testing "default supports-function-calling? is false"
        (is (false? (provider/supports-function-calling? obj))))

      (testing "default supports-stream? is false"
        (is (false? (provider/supports-stream? obj))))

      (testing "default tool->schema returns tool as-is"
        (let [tool {:name :calc :description "Calculator"}]
          (is (= tool (provider/tool->schema obj tool)))))))

  (testing "TestProvider with call-llm-stream fallback"
    ;; Use a provider that doesn't override call-llm-stream
    (let [p (reify provider/ILLMProvider
              (provider-name [_] :fallback-test)
              (call-llm [_ _config _messages _tools]
                {:text "streamed text"})
              (call-llm-stream [this config messages tools on-token]
                ;; Simulate default: call-llm then invoke callback
                (let [response (provider/call-llm this config messages tools)
                      text (provider/extract-text this response)]
                  (when (and on-token (seq text))
                    (on-token {:token text :index 0}))
                  response))
              (extract-tool-calls [_ _response] nil)
              (extract-text [_ response] (:text response))
              (build-tool-result [_ tool-id content]
                {:role "tool" :tool_call_id tool-id :content content})
              (supports-function-calling? [_] false)
              (supports-stream? [_] false)
              (tool->schema [_ tool] tool))
          tokens (atom [])
          response (provider/call-llm-stream p {} [] nil
                     (fn [t] (swap! tokens conj t)))]
      (is (= {:text "streamed text"} response))
      (is (= 1 (count @tokens)))
      (is (= "streamed text" (:token (first @tokens)))))))

;;; ============================================================
;;; Service Tests
;;; ============================================================

(deftest test-create-service
  (testing "create-service returns a map with :chat-fn"
    (let [p (make-test-provider {:text "hi" :tool-calls nil})
          svc (service/create-service p {:model "test" :max-tokens 100})]
      (is (map? svc))
      (is (fn? (:chat-fn svc))))))

(deftest test-service-chat-fn-text-response
  (testing "chat-fn correctly normalizes text-only response"
    (let [p (make-test-provider {:text "Hello world" :tool-calls nil})
          svc (service/create-service p {:model "test" :max-tokens 100})
          result ((:chat-fn svc) [{:role "user" :content "hi"}] {})]
      (is (= "Hello world" (:text result)))
      (is (nil? (:tool-calls result)))
      (is (some? (:raw-response result))))))

(deftest test-service-chat-fn-tool-calls-response
  (testing "chat-fn correctly normalizes tool-calls response"
    (let [tool-calls [{:id "tc1" :name "calculator" :args {:expr "2+2"}}]
          p (make-test-provider {:text "" :tool-calls tool-calls})
          svc (service/create-service p {:model "test" :max-tokens 100})
          result ((:chat-fn svc) [{:role "user" :content "calc 2+2"}] {})]
      (is (nil? (:text result)))
      (is (= tool-calls (:tool-calls result))))))

(deftest test-service-chat-fn-with-tools-opt
  (testing "chat-fn passes tools from opts to provider"
    (let [call-args (atom nil)
          p (reify provider/ILLMProvider
              (provider-name [_] :spy)
              (call-llm [_ config messages tools]
                (reset! call-args {:config config :messages messages :tools tools})
                {:text "ok" :tool-calls nil})
              (extract-tool-calls [_ response] (:tool-calls response))
              (extract-text [_ response] (:text response))
              (build-tool-result [_ tool-id content]
                {:role "tool" :tool_call_id tool-id :content content}))
          svc (service/create-service p {:model "test" :max-tokens 100})
          tools [{:name "calc" :description "Calculator"}]]
      ((:chat-fn svc) [] {:tools tools :tool-choice :auto})
      (is (= tools (get-in @call-args [:config :tools])))
      ;; core 只下发中立关键字，wire 转换由各 provider 边界负责（见 openai_compat/anthropic ->wire-tool-choice）
      (is (= :auto (get-in @call-args [:config :tool-choice]))))))

(deftest test-service-chat-fn-tool-choice-without-tools
  (testing "无 tools 时即便指定 tool-choice 也不下发（严格 OpenAI 端点会 400）"
    (let [call-args (atom nil)
          p (reify provider/ILLMProvider
              (provider-name [_] :spy)
              (call-llm [_ config _ _]
                (reset! call-args {:config config})
                {:text "ok" :tool-calls nil})
              (extract-tool-calls [_ response] (:tool-calls response))
              (extract-text [_ response] (:text response))
              (build-tool-result [_ tool-id content]
                {:role "tool" :tool_call_id tool-id :content content}))
          svc (service/create-service p {:model "test" :max-tokens 100})]
      ((:chat-fn svc) [] {:tool-choice :auto})
      (is (nil? (get-in @call-args [:config :tools])))
      (is (nil? (get-in @call-args [:config :tool-choice]))))))

(deftest test-service-chat-fn-tool-choice-none
  (testing "chat-fn with :tool-choice :none does not pass tools"
    (let [call-args (atom nil)
          p (reify provider/ILLMProvider
              (provider-name [_] :spy)
              (call-llm [_ config messages tools]
                (reset! call-args {:config config})
                {:text "text only" :tool-calls nil})
              (extract-tool-calls [_ response] (:tool-calls response))
              (extract-text [_ response] (:text response))
              (build-tool-result [_ tool-id content]
                {:role "tool" :tool_call_id tool-id :content content}))
          svc (service/create-service p {:model "test" :max-tokens 100})]
      ((:chat-fn svc) [] {:tools [{:name "x"}] :tool-choice :none})
      (is (nil? (get-in @call-args [:config :tools])))
      (is (nil? (get-in @call-args [:config :tool-choice]))))))

;;; ============================================================
;;; Types Tests
;;; ============================================================

(deftest test-tool-call
  (testing "统一 tool-call 形状 {:id :name(字符串) :args}（v0.2）"
    (let [tc (msg/tool-call "id1" "calc" {:x 1})]
      (is (= "id1" (:id tc)))
      (is (= "calc" (:name tc)))
      (is (= {:x 1} (:args tc)))))

  (testing "keyword name 规范化为字符串"
    (let [tc (msg/tool-call "id2" :search {:q "test"})]
      (is (= "search" (:name tc)))))

  (testing "nil args 归一化为空 map"
    (let [tc (msg/tool-call "id3" :foo nil)]
      (is (= {} (:args tc))))))

(deftest test-make-response
  (testing "make-response creates proper structure"
    (let [r (response/make-response :text "hello" :tool-calls [])]
      (is (response/response? r))
      (is (= "hello" (:text r)))
      (is (= [] (:tool-calls r)))))

  (testing "make-response with defaults"
    (let [r (response/make-response)]
      (is (nil? (:text r)))
      (is (nil? (:tool-calls r)))))

  (testing "has-text? and has-tool-calls?"
    (is (response/has-text? (response/make-response :text "hi")))
    (is (not (response/has-text? (response/make-response :text ""))))
    (is (response/has-tool-calls? (response/make-response :tool-calls [{:id "1" :name "x" :args {}}])))
    (is (not (response/has-tool-calls? (response/make-response :tool-calls []))))))

(deftest test-message-helpers
  (testing "中立消息构造（v0.2 唯一词汇，keyword role）"
    (is (= {:role :user :content "hi"} (msg/user "hi")))
    (is (= {:role :assistant :content "hello"} (msg/assistant "hello")))
    (is (= {:role :system :content "You are helpful"} (msg/system "You are helpful")))
    (is (= {:role :tool :tool-call-id "t1" :name "calc" :content "result"}
           (msg/tool-result "t1" "calc" "result"))))

  (testing "带工具调用的 assistant 消息"
    (let [m (msg/assistant-tool-calls [(msg/tool-call "1" "f" {})] "text")]
      (is (= :assistant (:role m)))
      (is (= "text" (:content m)))
      (is (= [{:id "1" :name "f" :args {}}] (:tool-calls m))))))

;;; ============================================================
;;; Errors Tests
;;; ============================================================

(deftest test-error-creation
  (testing "error creates proper structure"
    (let [e (errors/error :timeout-error "timed out")]
      (is (errors/error? e))
      (is (= :timeout-error (:type e)))
      (is (:retryable? e))))

  (testing "non-retryable error"
    (let [e (errors/error :auth-error "unauthorized")]
      (is (not (:retryable? e)))))

  (testing "未知错误类型保守默认不可重试（回归：曾默认 true 致反复重试）"
    (let [e (errors/error :some-unknown-error "?")]
      (is (not (:retryable? e))))
    ;; 显式 :retryable? 仍优先
    (is (:retryable? (errors/error :some-unknown-error "?" {:retryable? true}))))

  (testing "error with opts"
    (let [e (errors/error :network-error "failed" {:status 502 :provider :openai})]
      (is (= 502 (:status e)))
      (is (= :openai (:provider e))))))

(deftest test-error-predicates
  (testing "http-error?"
    (is (errors/http-error? (errors/error :network-error "net")))
    (is (errors/http-error? (errors/error :provider-error "err" {:status 500})))
    (is (not (errors/http-error? (errors/error :validation-error "bad")))))

  (testing "auth-error?"
    (is (errors/auth-error? (errors/error :auth-error "no auth")))
    (is (errors/auth-error? (errors/error :provider-error "err" {:status 401}))))

  (testing "rate-limit-error?"
    (is (errors/rate-limit-error? (errors/error :rate-limit-error "slow down")))
    (is (errors/rate-limit-error? (errors/error :provider-error "err" {:status 429})))))

(deftest test-result-helpers
  (testing "with-error-handling success"
    (let [result (errors/with-error-handling #(+ 40 2))]
      (is (= :ok (first result)))
      (is (= 42 (second result)))))

  (testing "with-error-handling failure"
    (let [result (errors/with-error-handling #(throw (java.io.IOException. "oops")))]
      (is (= :error (first result)))
      (is (= :network-error (:type (second result)))))))

(deftest test-exception->error-classification
  (testing "IOException -> network-error（可重试）"
    (let [e (errors/exception->error (java.io.IOException. "conn reset"))]
      (is (= :network-error (:type e)))
      (is (true? (:retryable? e)))
      (is (= "conn reset" (:message e)))))
  (testing "TimeoutException -> timeout-error（可重试）"
    (let [e (errors/exception->error (java.util.concurrent.TimeoutException. "took too long"))]
      (is (= :timeout-error (:type e)))
      (is (true? (:retryable? e)))))
  (testing "其他异常 -> provider-error"
    (let [e (errors/exception->error (RuntimeException. "boom"))]
      (is (= :provider-error (:type e)))))
  (testing "无 message 用类名兜底"
    (let [e (errors/exception->error (RuntimeException.))]
      (is (string? (:message e)))))
  (testing "ex-info 携带 canonical error 时幂等透传（保留 :type/:status/:retryable?）（D5 枢纽）"
    ;; 模拟 provider 用 errors/throw! 抛出的 401：不可重试，绝不能被重新归为 :provider-error
    (let [orig (errors/error :auth-error "Unauthorized" {:status 401 :provider :openai})
          e (errors/exception->error (ex-info "Unauthorized" orig))]
      (is (= :auth-error (:type e)))
      (is (false? (:retryable? e)))      ;; 修复前会变成 :provider-error → retryable? true
      (is (= 401 (:status e)))
      (is (= :openai (:provider e)))))
  (testing "透传时叠加外层 context"
    (let [orig (errors/error :rate-limit-error "slow" {:status 429})
          e (errors/exception->error (ex-info "slow" orig) {:op "call-llm"})]
      (is (= :rate-limit-error (:type e)))
      (is (= {:op "call-llm"} (:context e)))))
  (testing "普通 ex-info（data 非 canonical）仍按 class 分类"
    (let [e (errors/exception->error (ex-info "x" {:foo 1}))]
      (is (= :provider-error (:type e)))))
  (testing "UnsupportedOperationException -> validation-error，不可重试"
    (let [e (errors/exception->error (UnsupportedOperationException. "stream not supported"))]
      (is (= :validation-error (:type e)))
      (is (false? (:retryable? e))))))

(deftest test-http-response->error
  (testing "状态码映射到错误类型与可重试性"
    (are [status etype retry?]
        (let [e (errors/http-response->error {:status status :body "x"} :openai)]
          (and (= etype (:type e)) (= retry? (:retryable? e)) (= :openai (:provider e))))
      401 :auth-error       false
      403 :auth-error       false
      429 :rate-limit-error true
      500 :provider-error   true
      503 :provider-error   true
      400 :validation-error false))
  (testing "从 body 提取错误消息"
    (is (= "no access" (:message (errors/http-response->error
                                   {:status 401 :body {:error "no access"}} :openai)))))
  (testing "OpenAI 风格嵌套错误体 {:error {:message ..}} —— :message 必为字符串（回归：曾返回 map 致 throw! ClassCastException）"
    (let [e (errors/http-response->error
              {:status 400 :body {:error {:message "missing param messages" :type "invalid_request"}}}
              :minimax)]
      (is (string? (:message e)))
      (is (= "missing param messages" (:message e)))
      ;; 能被 throw! 正常抛出（不再 ClassCastException）
      (is (thrown? clojure.lang.ExceptionInfo (errors/throw! e))))))

(deftest test-format-error
  (testing "格式化含类型/状态/provider"
    (is (= "[TIMEOUT-ERROR] 请求超时 (HTTP 504) [openai]"
           (errors/format-error (errors/error :timeout-error "请求超时"
                                              {:status 504 :provider :openai}))))))
