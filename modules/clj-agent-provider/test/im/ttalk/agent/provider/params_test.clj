(ns im.ttalk.agent.provider.params-test
  "build-params 提示词控制 / 缓存集成单测（Anthropic + OpenAI 兼容）"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.provider.anthropic :as anthropic]
            [im.ttalk.agent.provider.common.openai-compat :as compat]))

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

(deftest anthropic-service-tier-test
  (testing "service-tier 仅显式提供时发送"
    (let [p (anthropic-build-params
              {:model "claude-opus-4-8" :service-tier "auto"}
              [{:role "user" :content "hi"}] [])]
      (is (= "auto" (:service_tier p))))
    (let [p (anthropic-build-params
              {:model "claude-opus-4-8"}
              [{:role "user" :content "hi"}] [])]
      (is (not (contains? p :service_tier))))))

(deftest anthropic-skills-container-test
  (testing "container（Skills）仅显式提供时发送"
    (let [p (anthropic-build-params
              {:model "claude-opus-4-8"
               :container {:skills [{:type "anthropic" :skill_id "xlsx"}]}}
              [{:role "user" :content "hi"}] [])]
      (is (= {:skills [{:type "anthropic" :skill_id "xlsx"}]} (:container p))))
    (let [p (anthropic-build-params
              {:model "claude-opus-4-8"} [{:role "user" :content "hi"}] [])]
      (is (not (contains? p :container))))))

(def anthropic-build-headers #'anthropic/build-headers)

(deftest anthropic-beta-header-test
  (testing "beta 字符串 -> anthropic-beta 头"
    (let [h (anthropic-build-headers
              {:auth-scheme :x-api-key :anthropic-version "2023-06-01"
               :beta "skills-2025-10-02"} "k")]
      (is (= "skills-2025-10-02" (get h "anthropic-beta")))
      (is (= "k" (get h "x-api-key")))))
  (testing "beta 向量 -> 逗号拼接、去重"
    (let [h (anthropic-build-headers
              {:auth-scheme :x-api-key
               :beta ["a" "b" "a" ""]} "k")]
      (is (= "a,b" (get h "anthropic-beta")))))
  (testing "无 beta 不发送该头"
    (let [h (anthropic-build-headers {:auth-scheme :x-api-key} "k")]
      (is (not (contains? h "anthropic-beta"))))))

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
  (testing "中立 tool-choice 关键字翻译为 OpenAI wire 形态（回归 D2：曾透传 Anthropic {:type ..}）"
    (is (= "auto"     (:tool_choice (compat/build-params {:model "m" :tool-choice :auto} [] [{:x 1}]))))
    (is (= "required" (:tool_choice (compat/build-params {:model "m" :tool-choice :required} [] [{:x 1}]))))
    (is (= "none"     (:tool_choice (compat/build-params {:model "m" :tool-choice :none} [] [{:x 1}]))))
    ;; 指定具体工具 / 已是字符串 → 原样透传
    (is (= {:type "function" :function {:name "f"}}
           (:tool_choice (compat/build-params {:model "m" :tool-choice {:type "function" :function {:name "f"}}} [] [{:x 1}])))))
  (testing "GLM thinking 开关透传（仅显式提供时发送）"
    (let [p (compat/build-params
              {:model "glm-4.7" :thinking {:type "enabled" :clear_thinking true}}
              [{:role "user" :content "hi"}] [])]
      (is (= {:type "enabled" :clear_thinking true} (:thinking p))))
    (let [p (compat/build-params {:model "glm-4.7"} [{:role "user" :content "hi"}] [])]
      (is (not (contains? p :thinking)))))
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

(deftest openai-compat-tool-and-reasoning-test
  (testing "parallel-tool-calls 布尔值精确透传（含 false）"
    (let [p (compat/build-params
              {:model "gpt-4" :parallel-tool-calls false}
              [{:role "user" :content "hi"}] [])]
      (is (= false (:parallel_tool_calls p))))
    (let [p (compat/build-params
              {:model "gpt-4" :parallel-tool-calls true}
              [{:role "user" :content "hi"}] [])]
      (is (= true (:parallel_tool_calls p))))
    (let [p (compat/build-params {:model "gpt-4"} [{:role "user" :content "hi"}] [])]
      (is (not (contains? p :parallel_tool_calls)))))
  (testing "reasoning-effort / verbosity 仅显式提供时发送"
    (let [p (compat/build-params
              {:model "gpt-5" :reasoning-effort "high" :verbosity "low"}
              [{:role "user" :content "hi"}] [])]
      (is (= "high" (:reasoning_effort p)))
      (is (= "low" (:verbosity p))))
    (let [p (compat/build-params {:model "gpt-5"} [{:role "user" :content "hi"}] [])]
      (is (not (contains? p :reasoning_effort)))
      (is (not (contains? p :verbosity)))))
  (testing "结构化输出 json_schema/strict 透传到 response_format"
    (let [rf {:type "json_schema"
              :json_schema {:name "Person" :strict true
                            :schema {:type "object"
                                     :properties {:name {:type "string"}}
                                     :required ["name"]}}}
          p (compat/build-params
              {:model "gpt-4" :response-format rf}
              [{:role "user" :content "hi"}] [])]
      (is (= rf (:response_format p)))))
  (testing "多模态输出 modalities / audio 透传"
    (let [p (compat/build-params
              {:model "gpt-4o-audio-preview"
               :modalities ["text" "audio"]
               :audio {:voice "alloy" :format "wav"}}
              [{:role "user" :content "hi"}] [])]
      (is (= ["text" "audio"] (:modalities p)))
      (is (= {:voice "alloy" :format "wav"} (:audio p))))
    (let [p (compat/build-params {:model "gpt-4"} [{:role "user" :content "hi"}] [])]
      (is (not (contains? p :modalities)))
      (is (not (contains? p :audio))))))
