(ns im.ttalk.agent.provider.openai-responses-test
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.model :as proto]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.provider.factory.builder :as builder]
            [im.ttalk.agent.provider.factory.config :as config]
            [im.ttalk.agent.provider.openai-responses :as provider]
            [im.ttalk.agent.provider.stream.openai-responses :as stream]
            [im.ttalk.agent.provider.wire.openai-responses :as wire]))

(def raw-response
  {:id "resp_1"
   :model "MiniMax-M3.1-Flash"
   :status "completed"
   :usage {:input_tokens 10 :output_tokens 4 :total_tokens 14}
   :output
   [{:type "reasoning"
     :summary []
     :content [{:type "reasoning_text" :text "think"}]}
    {:type "message"
     :content [{:type "output_text" :text "OK"}]}
    {:type "function_call"
     :id "fc_1"
     :call_id "call_1"
     :name "search"
     :arguments "{\"q\":\"clojure\"}"}]})

(deftest neutral->wire-test
  (testing "system messages become instructions; assistant items stay flat"
    (let [{:keys [instructions input]}
          (wire/neutral->wire
           "be brief"
           [(msg/system "also be safe")
            (msg/user "hello")
            (msg/assistant-tool-calls
             [(msg/tool-call "call_1" "search" {:q "clojure"})]
             "thinking answer")])]
      (is (= "be brief\n\nalso be safe" instructions))
      (is (= [{:type "message" :role "user" :content "hello"}
              {:type "message" :role "assistant"
               :content [{:type "input_text" :text "thinking answer"}]}
              {:type "function_call" :call_id "call_1" :name "search"
               :arguments "{\"q\":\"clojure\"}"}]
             input)))))

  (testing "matching replay blocks preserve the provider function call"
    (let [call (msg/tool-call "call_1" "search" {:q "clojure"})
          m (assoc (msg/assistant-tool-calls [call] nil)
                   :blocks {:provider :openai-responses
                            :format wire/replay-format
                            :data [{:type "function_call" :call_id "call_1"
                                    :name "search" :arguments "{}"
                                    :id "fc_1" :status "completed"}]})]
      (is (= [{:type "function_call" :call_id "call_1" :name "search"
               :arguments "{}" :id "fc_1"}]
             (:input (wire/neutral->wire nil [m]))))))

  (testing "tool result becomes a function_call_output item"
    (is (= [{:type "function_call_output" :call_id "call_1" :output "done"}]
           (:input (wire/neutral->wire nil [(msg/tool-result "call_1" "search" "done")])))))

(deftest tools-and-params-test
  (testing "chat function schemas flatten for responses"
    (is (= [{:type "function" :name "search"
             :parameters {:type "object" :properties {}}
             :description "search"}]
           (wire/tools->wire
            [{:type "function"
              :function {:name "search" :description "search"
                         :parameters {:type "object" :properties {}}}}]))))

  (testing "optional params are present only when supplied"
    (is (= {:model "m" :input [] :instructions "s" :max_output_tokens 64
            :reasoning {:effort "low" :summary "auto"}
            :text {:format {:type "json_object"}}}
           (provider/build-params
            {:model "m" :system-prompt "s" :max-tokens 64
             :reasoning-effort "low" :reasoning-summary "auto"
             :response-format {:type "json_object"}}
            {:input [] :instructions "s"}
            [])))))

(deftest provider-response-test
  (let [enhanced (provider/provider-response raw-response :openai-responses)]
    (is (= "OK" (wire/extract-text enhanced)))
    (is (= "think" (wire/extract-reasoning enhanced)))
    (is (= [(msg/tool-call "call_1" "search" {:q "clojure"})]
           (wire/extract-tool-calls enhanced)))
    (is (= [{:id "call_1" :type "function"
             :function {:name "search" :arguments "{\"q\":\"clojure\"}"}}]
           (get-in enhanced [:choices 0 :message :tool_calls])))
    (is (= "tool_calls" (get-in enhanced [:choices 0 :finish_reason]))))

  (testing "replay payload only contains function calls"
    (is (= {:provider :openai-responses :format :openai-responses-output
            :data [(last (:output raw-response))]}
           (wire/replay-blocks raw-response)))))

(deftest stream-test
  (let [{:keys [process-fn make-initial-state build-response]} (stream/make-stream-processor)
        state (reduce (fn [st [event expected]]
                        (let [[st token] (process-fn event st)]
                          (is (= expected (select-keys token (keys expected))))
                          st))
                      (make-initial-state)
                      [[{:type "response.reasoning_text.delta" :delta "think"}
                        {:reasoning-token "think"}]
                       [{:type "response.output_text.delta" :delta "OK"}
                        {:token "OK"}]
                       [{:type "response.completed"
                         :response raw-response}
                        {}]])]
    (is (= raw-response (build-response state))))

  (testing "deltas can build a response when completed event is absent"
    (let [{:keys [process-fn make-initial-state build-response]} (stream/make-stream-processor)
          state (as-> (make-initial-state) $
                  (process-fn {:type "response.output_item.added"
                               :output_index 0
                               :item {:type "function_call" :id "fc"
                                      :call_id "call" :name "search"}}
                              $)
                  (first $)
                  (process-fn {:type "response.function_call_arguments.delta"
                               :output_index 0
                               :delta "{\"q\":\"clojure\"}"}
                              $)
                  (first $))]
      (is (= [{:type "function_call" :call_id "call" :name "search"
               :arguments "{\"q\":\"clojure\"}" :output_index 0}]
             (:output (build-response state)))))))

(deftest factory-test
  (let [[status cfg] (config/resolve-config :openai-responses
                                            {:api-key "k"} false)]
    (is (= :ok status))
    (is (= "/v1/responses" (:api-path cfg)))
    (is (= :openai-responses
           (proto/provider-name
            (builder/create-provider :openai-responses {:api-key "k"}))))))
