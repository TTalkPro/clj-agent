(ns im.ttalk.agent.provider.usage-test
  "usage 归一化补 cache token 单测（core 契约，向后兼容）"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.model.response :as response]))

(deftest normalize-usage-backward-compat-test
  (testing "OpenAI 经典格式不变"
    (is (= {:input-tokens 100 :output-tokens 50 :total-tokens 150}
           (response/normalize-usage {:prompt_tokens 100 :completion_tokens 50 :total_tokens 150}))))
  (testing "Anthropic 基础格式不含 cache 字段"
    (let [u (response/normalize-usage {:input_tokens 100 :output_tokens 50})]
      (is (= {:input-tokens 100 :output-tokens 50 :total-tokens 150} u))
      (is (not (contains? u :cache-read-tokens)))
      (is (not (contains? u :cache-write-tokens)))))
  (testing "nil 安全"
    (is (nil? (response/normalize-usage nil)))))

(deftest normalize-usage-anthropic-cache-test
  (testing "Anthropic cache 读/写 token"
    (let [u (response/normalize-usage
              {:input_tokens 100 :output_tokens 50
               :cache_read_input_tokens 800 :cache_creation_input_tokens 200})]
      (is (= 100 (:input-tokens u)))
      (is (= 50 (:output-tokens u)))
      (is (= 800 (:cache-read-tokens u)))
      (is (= 200 (:cache-write-tokens u))))))

(deftest normalize-usage-openai-cache-test
  (testing "OpenAI prompt_tokens_details.cached_tokens"
    (let [u (response/normalize-usage
              {:prompt_tokens 1000 :completion_tokens 50
               :prompt_tokens_details {:cached_tokens 900}})]
      (is (= 900 (:cache-read-tokens u)))))
  (testing "DeepSeek prompt_cache_hit_tokens"
    (let [u (response/normalize-usage
              {:prompt_tokens 1000 :completion_tokens 50 :prompt_cache_hit_tokens 700})]
      (is (= 700 (:cache-read-tokens u))))))
