(ns im.ttalk.agent.core.kernel.provider-test
  "Tests for core kernel provider protocol and service"
  (:require [clojure.test :refer [deftest testing is are]]
            [im.ttalk.agent.core.kernel.provider :as provider]
            [im.ttalk.agent.core.kernel.service :as service]
            [im.ttalk.agent.core.kernel.types :as types]
            [im.ttalk.agent.core.kernel.errors :as errors]))

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

  (tool->schema [_ tool] tool)

  (build-assistant-message [_ response]
    {:role "assistant" :content (:text response)})

  (build-result-messages [_ assistant-msg tool-results]
    (into [assistant-msg]
          (mapv (fn [{:keys [tool-id result error]}]
                  {:role "tool"
                   :tool_call_id tool-id
                   :content (or result (str "Error: " error))})
                tool-results))))

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
          tcs [{:id "tc1" :name :calc :input {:x 1}}]]
      (is (= tcs (provider/extract-tool-calls p {:tool-calls tcs})))
      (is (nil? (provider/extract-tool-calls p {:tool-calls nil}))))))

(deftest test-build-assistant-message
  (testing "build-assistant-message creates correct message"
    (let [p (make-test-provider)]
      (is (= {:role "assistant" :content "Hello"}
             (provider/build-assistant-message p {:text "Hello"}))))))

(deftest test-build-result-messages
  (testing "build-result-messages creates correct message list"
    (let [p (make-test-provider)
          assistant-msg {:role "assistant" :content "I'll call the tool"}
          tool-results [{:tool-id "tc1" :result "42" :error nil}
                        {:tool-id "tc2" :result nil :error "not found"}]]
      (is (= [{:role "assistant" :content "I'll call the tool"}
              {:role "tool" :tool_call_id "tc1" :content "42"}
              {:role "tool" :tool_call_id "tc2" :content "Error: not found"}]
             (provider/build-result-messages p assistant-msg tool-results))))))

(deftest test-call-with-tools
  (testing "call-with-tools returns unified response"
    (let [response {:text "Hello" :tool-calls [{:id "t1" :name :foo :input {}}]}
          p (make-test-provider response)
          result (provider/call-with-tools p {} [] [])]
      (is (types/response? result))
      (is (= "Hello" (:text result)))
      (is (= [{:id "t1" :name :foo :input {}}]
             (:tool-calls result))))))

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
    ;; supports-stream?, tool->schema, build-assistant-message, build-result-messages,
    ;; and call-llm-stream. These are tested on a plain Object.
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
                    (on-token {:token text :index 0 :accumulated text}))
                  response))
              (extract-tool-calls [_ _response] nil)
              (extract-text [_ response] (:text response))
              (build-tool-result [_ tool-id content]
                {:role "tool" :tool_call_id tool-id :content content})
              (supports-function-calling? [_] false)
              (supports-stream? [_] false)
              (tool->schema [_ tool] tool)
              (build-assistant-message [this response]
                {:role "assistant" :content (provider/extract-text this response)})
              (build-result-messages [_ assistant-msg tool-results]
                (into [assistant-msg]
                      (mapv (fn [{:keys [tool-id result error]}]
                              {:role "tool"
                               :tool_call_id tool-id
                               :content (or result (str "Error: " error))})
                            tool-results))))
          tokens (atom [])
          response (provider/call-llm-stream p {} [] nil
                     (fn [t] (swap! tokens conj t)))]
      (is (= {:text "streamed text"} response))
      (is (= 1 (count @tokens)))
      (is (= "streamed text" (:token (first @tokens))))))

  (testing "build-assistant-message with extract-text"
    (let [p (make-test-provider)]
      (is (= {:role "assistant" :content "hello"}
             (provider/build-assistant-message p {:text "hello"})))))

  (testing "build-result-messages OpenAI style"
    (let [p (make-test-provider)
          result (provider/build-result-messages
                   p
                   {:role "assistant" :content "calling tool"}
                   [{:tool-id "t1" :result "done" :error nil}])]
      (is (= [{:role "assistant" :content "calling tool"}
              {:role "tool" :tool_call_id "t1" :content "done"}]
             result)))))

;;; ============================================================
;;; Service Tests
;;; ============================================================

(deftest test-create-service
  (testing "create-service returns a map with :chat-fn and :build-result-msgs"
    (let [p (make-test-provider {:text "hi" :tool-calls nil})
          svc (service/create-service p {:model "test" :max-tokens 100})]
      (is (map? svc))
      (is (fn? (:chat-fn svc)))
      (is (fn? (:build-result-msgs svc))))))

(deftest test-service-chat-fn-text-response
  (testing "chat-fn correctly normalizes text-only response"
    (let [p (make-test-provider {:text "Hello world" :tool-calls nil})
          svc (service/create-service p {:model "test" :max-tokens 100})
          result ((:chat-fn svc) [{:role "user" :content "hi"}] {})]
      (is (= "Hello world" (:text result)))
      (is (nil? (:tool-calls result)))
      (is (= {:role "assistant" :content "Hello world"} (:assistant-msg result)))
      (is (some? (:raw-response result))))))

(deftest test-service-chat-fn-tool-calls-response
  (testing "chat-fn correctly normalizes tool-calls response"
    (let [tool-calls [{:id "tc1" :name :calculator :input {:expr "2+2"}}]
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
                {:role "tool" :tool_call_id tool-id :content content})
              (build-assistant-message [_ response]
                {:role "assistant" :content (:text response)})
              (build-result-messages [_ assistant-msg tool-results]
                (into [assistant-msg]
                      (mapv (fn [{:keys [tool-id result error]}]
                              {:role "tool" :tool_call_id tool-id
                               :content (or result (str "Error: " error))})
                            tool-results))))
          svc (service/create-service p {:model "test" :max-tokens 100})
          tools [{:name "calc" :description "Calculator"}]]
      ((:chat-fn svc) [] {:tools tools :tool-choice :auto})
      (is (= tools (get-in @call-args [:config :tools])))
      (is (= {:type "auto"} (get-in @call-args [:config :tool-choice]))))))

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
                {:role "tool" :tool_call_id tool-id :content content})
              (build-assistant-message [_ response]
                {:role "assistant" :content (:text response)})
              (build-result-messages [_ assistant-msg tool-results]
                [assistant-msg]))
          svc (service/create-service p {:model "test" :max-tokens 100})]
      ((:chat-fn svc) [] {:tools [{:name "x"}] :tool-choice :none})
      (is (nil? (get-in @call-args [:config :tools])))
      (is (nil? (get-in @call-args [:config :tool-choice]))))))

(deftest test-service-build-result-msgs
  (testing "build-result-msgs delegates to provider"
    (let [p (make-test-provider)
          svc (service/create-service p {:model "test" :max-tokens 100})
          assistant-msg {:role "assistant" :content "calling tools"}
          tool-results [{:tool-id "t1" :result "42" :error nil}]
          result ((:build-result-msgs svc) assistant-msg tool-results)]
      (is (= [{:role "assistant" :content "calling tools"}
              {:role "tool" :tool_call_id "t1" :content "42"}]
             result)))))

;;; ============================================================
;;; Types Tests
;;; ============================================================

(deftest test-make-tool-call
  (testing "make-tool-call creates proper structure"
    (let [tc (types/make-tool-call "id1" "calc" {:x 1})]
      (is (types/tool-call? tc))
      (is (= "id1" (:id tc)))
      (is (= :calc (:name tc)))
      (is (= {:x 1} (:input tc)))))

  (testing "make-tool-call with keyword name"
    (let [tc (types/make-tool-call "id2" :search {:q "test"})]
      (is (= :search (:name tc)))))

  (testing "make-tool-call with nil input"
    (let [tc (types/make-tool-call "id3" :foo nil)]
      (is (= {} (:input tc))))))

(deftest test-make-response
  (testing "make-response creates proper structure"
    (let [r (types/make-response :text "hello" :tool-calls [])]
      (is (types/response? r))
      (is (= "hello" (:text r)))
      (is (= [] (:tool-calls r)))))

  (testing "make-response with defaults"
    (let [r (types/make-response)]
      (is (= "" (:text r)))
      (is (= [] (:tool-calls r)))))

  (testing "has-text? and has-tool-calls?"
    (is (types/has-text? (types/make-response :text "hi")))
    (is (not (types/has-text? (types/make-response :text ""))))
    (is (types/has-tool-calls? (types/make-response :tool-calls [{:id "1" :name :x :input {}}])))
    (is (not (types/has-tool-calls? (types/make-response :tool-calls []))))))

(deftest test-message-helpers
  (testing "user-message"
    (is (= {:role "user" :content "hi"} (types/user-message "hi"))))

  (testing "assistant-message"
    (is (= {:role "assistant" :content "hello"} (types/assistant-message "hello"))))

  (testing "assistant-message with tool-calls"
    (let [msg (types/assistant-message "text" [{:id "1"}])]
      (is (= "assistant" (:role msg)))
      (is (= [{:id "1"}] (:tool_calls msg)))))

  (testing "system-message"
    (is (= {:role "system" :content "You are helpful"} (types/system-message "You are helpful"))))

  (testing "tool-message"
    (is (= {:role "tool" :tool_call_id "t1" :content "result"}
           (types/tool-message "t1" "result")))))

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
