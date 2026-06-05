(ns im.ttalk.agent.provider.reasoning-test
  "推理/思考内容提取单测（Anthropic thinking + DeepSeek reasoning_content）"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.provider.common.response-parser :as oai-parser]
            [im.ttalk.agent.provider.anthropic :as anthropic]
            [im.ttalk.agent.provider.stream.openai :as oai-stream]
            [im.ttalk.agent.provider.stream.anthropic :as ant-stream]))

;; ============================================================
;; 通用 extract-reasoning（两种形态）
;; ============================================================

(deftest extract-reasoning-shapes-test
  (testing "Anthropic thinking 块"
    (is (= "让我想想"
           (response/extract-reasoning
             {:content [{:type "thinking" :thinking "让我想想" :signature "sig"}
                        {:type "text" :text "答案"}]}))))
  (testing "OpenAI/DeepSeek reasoning_content"
    (is (= "推理过程"
           (response/extract-reasoning
             {:choices [{:message {:role "assistant"
                                   :reasoning_content "推理过程"
                                   :content "最终答案"}}]}))))
  (testing "普通响应无推理 -> nil"
    (is (nil? (response/extract-reasoning {:content [{:type "text" :text "hi"}]})))
    (is (nil? (response/extract-reasoning {:choices [{:message {:content "hi"}}]})))
    (is (nil? (response/extract-reasoning nil)))))

(deftest finish-reason-pause-refusal-test
  (testing "新增 pause_turn / refusal 归一化"
    (is (= :pause (response/normalize-finish-reason "pause_turn")))
    (is (= :refusal (response/normalize-finish-reason "refusal")))
    (is (= :stop (response/normalize-finish-reason "end_turn")))))

;; ============================================================
;; 同步响应归一化带 reasoning
;; ============================================================

(deftest sync-normalize-includes-reasoning-test
  (testing "Anthropic normalize-response 提取 thinking 到 :reasoning，:text 仅含答案"
    (let [raw {:id "m1" :model "claude" :stop_reason "end_turn"
               :content [{:type "thinking" :thinking "思考中…"}
                         {:type "text" :text "你好"}]}
          r (anthropic/normalize-response raw)]
      (is (= "你好" (:text r)))
      (is (= "思考中…" (response/response-reasoning r)))))
  (testing "OpenAI(DeepSeek) normalize-response 提取 reasoning_content"
    (let [raw {:id "c1" :model "deepseek-reasoner"
               :choices [{:finish_reason "stop"
                          :message {:role "assistant"
                                    :reasoning_content "先分析…"
                                    :content "结论"}}]}
          r (oai-parser/normalize-response raw)]
      (is (= "结论" (:text r)))
      (is (= "先分析…" (response/response-reasoning r))))))

;; ============================================================
;; 流式累积 reasoning
;; ============================================================

(defn- run-oai [chunks]
  (reduce (fn [st c] (first (oai-stream/process-chunk c st)))
          (oai-stream/make-initial-state) chunks))

(deftest openai-stream-reasoning-test
  (testing "deepseek-reasoner 流式：reasoning_content 累积，与 content 分离"
    (let [chunks [{:choices [{:delta {:role "assistant"}}]}
                  {:choices [{:delta {:reasoning_content "先"}}]}
                  {:choices [{:delta {:reasoning_content "想想"}}]}
                  {:choices [{:delta {:content "答"}}]}
                  {:choices [{:delta {:content "案"}}]}]
          state (run-oai chunks)
          r (oai-stream/normalize-response state :id "x" :model "deepseek-reasoner")]
      (is (= "答案" (:text r)))
      (is (= "先想想" (response/response-reasoning r)))
      ;; build-response 把 reasoning_content 放回 message
      (let [raw (oai-stream/build-response state :id "x" :model "deepseek-reasoner")]
        (is (= "先想想" (get-in raw [:choices 0 :message :reasoning_content])))
        (is (= "答案" (get-in raw [:choices 0 :message :content]))))))
  (testing "reasoning token 通过 :reasoning-token 单独下发，不混入 :token"
    (let [[_ td] (oai-stream/process-chunk
                   {:choices [{:delta {:reasoning_content "x"}}]}
                   (oai-stream/make-initial-state))]
      (is (= "x" (:reasoning-token td)))
      (is (true? (:reasoning? td)))
      (is (nil? (:token td))))))

(defn- run-ant [events]
  (reduce (fn [st e] (first (ant-stream/process-event e st)))
          (ant-stream/make-initial-state) events))

(deftest anthropic-stream-thinking-test
  (testing "Anthropic 流式 thinking_delta 累积到 :reasoning，text 干净"
    (let [events [{:type "message_start" :message {:id "m" :model "claude"}}
                  {:type "content_block_start" :index 0 :content_block {:type "thinking" :thinking ""}}
                  {:type "content_block_delta" :index 0 :delta {:type "thinking_delta" :thinking "嗯"}}
                  {:type "content_block_delta" :index 0 :delta {:type "thinking_delta" :thinking "对"}}
                  {:type "content_block_delta" :index 0 :delta {:type "signature_delta" :signature "sig123"}}
                  {:type "content_block_stop" :index 0}
                  {:type "content_block_start" :index 1 :content_block {:type "text" :text ""}}
                  {:type "content_block_delta" :index 1 :delta {:type "text_delta" :text "答案"}}
                  {:type "message_delta" :delta {:stop_reason "end_turn"} :usage {:output_tokens 5}}]
          state (run-ant events)
          r (ant-stream/normalize-response state)]
      (is (= "答案" (:text r)))
      (is (= "嗯对" (response/response-reasoning r)))
      ;; 签名挂到对应块
      (is (= "sig123" (get-in state [:content-blocks 0 :signature])))))
  (testing "thinking token 走 :reasoning-token，不污染答案 :token"
    (let [st (-> (ant-stream/make-initial-state)
                 (assoc :content-blocks {0 {:type "thinking" :thinking ""}}))
          [_ td] (ant-stream/process-event
                   {:type "content_block_delta" :index 0
                    :delta {:type "thinking_delta" :thinking "z"}} st)]
      (is (= "z" (:reasoning-token td)))
      (is (true? (:reasoning? td)))
      (is (nil? (:token td))))))
