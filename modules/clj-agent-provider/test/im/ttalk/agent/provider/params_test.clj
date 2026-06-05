(ns im.ttalk.agent.provider.params-test
  "build-params 提示词控制 / 缓存集成单测（Anthropic + OpenAI 兼容）"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.provider.anthropic :as anthropic]
            [im.ttalk.agent.provider.openai-compat :as compat]))

;; build-params 在 anthropic 命名空间是私有的，用 var 取出
(def anthropic-build-params #'anthropic/build-params)

;; ============================================================
;; Anthropic build-params
;; ============================================================

(deftest anthropic-prompt-control-test
  (testing "提示词控制参数：存在才设"
    (let [p (anthropic-build-params
              {:model "claude-opus-4-8" :max-tokens 2048
               :temperature 0.3 :top-p 0.8 :top-k 40
               :stop ["STOP"] :metadata {:user_id "u1"}
               :thinking {:type "adaptive"}}
              [{:role "user" :content "hi"}]
              [])]
      (is (= 0.3 (:temperature p)))
      (is (= 0.8 (:top_p p)))
      (is (= 40 (:top_k p)))
      (is (= ["STOP"] (:stop_sequences p)))
      (is (= {:user_id "u1"} (:metadata p)))
      (is (= {:type "adaptive"} (:thinking p)))
      (is (= 2048 (:max_tokens p)))))
  (testing "未提供的采样参数不出现在请求体"
    (let [p (anthropic-build-params
              {:model "claude-opus-4-8"}
              [{:role "user" :content "hi"}]
              [])]
      (is (not (contains? p :temperature)))
      (is (not (contains? p :top_p)))
      (is (not (contains? p :top_k)))
      (is (not (contains? p :stop_sequences)))
      ;; 默认 max_tokens 兜底
      (is (= 4096 (:max_tokens p))))))

(deftest anthropic-cache-integration-test
  (testing "cache-strategy 经 build-params 注入 cache_control"
    (let [p (anthropic-build-params
              {:model "claude-opus-4-8" :system-prompt "长系统提示"
               :cache-strategy :system :cache-ttl "1h"}
              [{:role "user" :content "hi"}]
              [])]
      (is (vector? (:system p)))
      (is (= {:type "ephemeral" :ttl "1h"}
             (:cache_control (last (:system p)))))))
  (testing "无 cache-strategy 时 system 保持字符串"
    (let [p (anthropic-build-params
              {:model "claude-opus-4-8" :system-prompt "x"}
              [{:role "user" :content "hi"}]
              [])]
      (is (= "x" (:system p))))))

;; ============================================================
;; OpenAI 兼容 build-params
;; ============================================================

(deftest openai-compat-prompt-control-test
  (testing "采样参数存在才设（不再强塞 0.7/0.9 默认）"
    (let [p (compat/build-params {:model "gpt-4"} [{:role "user" :content "hi"}] [])]
      (is (not (contains? p :temperature)))
      (is (not (contains? p :top_p)))
      (is (= "gpt-4" (:model p)))))
  (testing "透传扩展参数"
    (let [p (compat/build-params
              {:model "gpt-4" :max-tokens 512 :temperature 0.2 :top-p 0.5
               :frequency-penalty 0.1 :presence-penalty 0.2 :seed 7 :n 2
               :tool-choice "auto" :user "u1"
               :stop ["x"] :response-format {:type "json_object"}}
              [{:role "user" :content "hi"}]
              [])]
      (is (= 512 (:max_tokens p)))
      (is (= 0.2 (:temperature p)))
      (is (= 0.5 (:top_p p)))
      (is (= 0.1 (:frequency_penalty p)))
      (is (= 0.2 (:presence_penalty p)))
      (is (= 7 (:seed p)))
      (is (= 2 (:n p)))
      (is (= "auto" (:tool_choice p)))
      (is (= "u1" (:user p)))
      (is (= ["x"] (:stop p)))
      (is (= {:type "json_object"} (:response_format p)))))
  (testing "extra-body 直接 merge，覆盖各家私有字段"
    (let [p (compat/build-params
              {:model "deepseek-chat" :extra-body {:enable_thinking false :foo 1}}
              [{:role "user" :content "hi"}]
              [])]
      (is (= false (:enable_thinking p)))
      (is (= 1 (:foo p)))))
  (testing "system-prompt 合并入 messages 首条"
    (let [p (compat/build-params {:model "gpt-4" :system-prompt "sys"}
                                 [{:role "user" :content "hi"}] [])]
      (is (= {:role "system" :content "sys"} (first (:messages p)))))))
