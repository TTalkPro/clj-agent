(ns im.ttalk.agent.provider.stream.anthropic-stream-test
  "Anthropic 流式：usage 合并与 error 事件单测"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.provider.stream.anthropic :as stream]))

(defn- run-events
  "把一串事件喂给 process-event，返回最终 state"
  [events]
  (reduce (fn [st ev] (first (stream/process-event ev st)))
          (stream/make-initial-state)
          events))

(deftest usage-merge-test
  (testing "message_delta 的顶层 usage（output_tokens）并入 message.usage，并保留 message_start 的 input/cache token"
    (let [events [{:type "message_start"
                   :message {:id "m1" :model "claude-opus-4-8"
                             :usage {:input_tokens 100
                                     :cache_read_input_tokens 800
                                     :cache_creation_input_tokens 200}}}
                  {:type "content_block_start" :index 0 :content_block {:type "text" :text ""}}
                  {:type "content_block_delta" :index 0 :delta {:type "text_delta" :text "你好"}}
                  {:type "content_block_stop" :index 0}
                  {:type "message_delta"
                   :delta {:stop_reason "end_turn"}
                   :usage {:output_tokens 50}}
                  {:type "message_stop"}]
          state (run-events events)
          resp (stream/build-response state)
          usage (:usage resp)]
      (is (= 100 (:input_tokens usage)))
      (is (= 50 (:output_tokens usage)))
      (is (= 800 (:cache_read_input_tokens usage)))
      (is (= 200 (:cache_creation_input_tokens usage)))
      (is (= "end_turn" (:stop_reason resp)))
      ;; 文本累积正确
      (is (= [{:type "text" :text "你好"}] (:content resp))))))

(deftest normalize-response-usage-test
  (testing "normalize-response 产出含 cache token 的统一 usage"
    (let [events [{:type "message_start"
                   :message {:id "m1" :model "claude-opus-4-8"
                             :usage {:input_tokens 100 :cache_read_input_tokens 800}}}
                  {:type "content_block_delta" :index 0 :delta {:type "text_delta" :text "hi"}}
                  {:type "message_delta" :delta {:stop_reason "end_turn"} :usage {:output_tokens 50}}]
          state (run-events events)
          r (stream/normalize-response state)
          usage (:usage r)]
      (is (= 100 (:input-tokens usage)))
      (is (= 50 (:output-tokens usage)))
      (is (= 800 (:cache-read-tokens usage))))))

(deftest error-event-test
  (testing "error 事件被记录，build-response 抛出"
    (let [state (run-events [{:type "message_start" :message {:id "m1"}}
                             {:type "error" :error {:type "overloaded_error"
                                                    :message "overloaded"}}])]
      (is (= {:type "overloaded_error" :message "overloaded"} (:error state)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"streaming error"
                            (stream/build-response state))))))
