(ns im.ttalk.agent.provider.stream.openai-stream-test
  "OpenAI 流式：tool_call 多块聚合、content/tool_calls 共存、reasoning、末块 usage 单测"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.provider.stream.openai :as stream]
            [im.ttalk.agent.model.response :as response]))

(defn- run-chunks
  "把一串 chunk 喂给 process-chunk，返回 [最终state 收集到的token序列]"
  [chunks]
  (reduce (fn [[st toks] chunk]
            (let [[st' td] (stream/process-chunk chunk st)]
              [st' (if td (conj toks td) toks)]))
          [(stream/make-initial-state) []]
          chunks))

;; ============================================================
;; SSE 解析
;; ============================================================

(deftest parse-sse-line-test
  (testing "data: 前缀解析为 JSON"
    (is (= {:id "x"} (stream/parse-sse-line "data: {\"id\": \"x\"}"))))
  (testing "[DONE] 返回 nil"
    (is (nil? (stream/parse-sse-line "data: [DONE]"))))
  (testing "非 data 行 / 畸形 JSON 返回 nil（不抛）"
    (is (nil? (stream/parse-sse-line ": keep-alive")))
    (is (nil? (stream/parse-sse-line "data: {不是json")))))

;; ============================================================
;; 文本累积
;; ============================================================

(deftest content-accumulation-test
  (testing "多块 content 累积，逐块 emit token"
    (let [[state toks] (run-chunks
                         [{:choices [{:delta {:role "assistant"}}]}
                          {:choices [{:delta {:content "你"}}]}
                          {:choices [{:delta {:content "好"}}]}
                          {:choices [{:delta {} :finish_reason "stop"}]}])]
      (is (= "你好" (str (:accumulated state))))
      (is (= ["你" "好"] (mapv :token toks)))
      (is (= "stop" (:finish-reason state)))
      (is (= "你好" (get-in (stream/build-response state) [:choices 0 :message :content]))))))

;; ============================================================
;; 工具调用：多块参数拼接 + 并行
;; ============================================================

(deftest tool-call-aggregation-test
  (testing "单个 tool_call 的 name/arguments 跨块拼接"
    (let [[state toks] (run-chunks
                         [{:choices [{:delta {:tool_calls [{:index 0 :id "call_1"
                                                            :function {:name "get_weather" :arguments "{\"ci"}}]}}]}
                          {:choices [{:delta {:tool_calls [{:index 0
                                                            :function {:arguments "ty\":\"BJ\"}"}}]}}]}
                          {:choices [{:delta {} :finish_reason "tool_calls"}]}])
          resp (stream/build-response state)
          tc (first (get-in resp [:choices 0 :message :tool_calls]))]
      (is (empty? toks))                                  ;; 工具调用不下发 token
      (is (= "call_1" (:id tc)))
      (is (= "get_weather" (get-in tc [:function :name])))
      (is (= "{\"city\":\"BJ\"}" (get-in tc [:function :arguments])))
      (is (= "tool_calls" (get-in resp [:choices 0 :finish_reason])))))
  (testing "并行多个 tool_call（不同 index）各自聚合并按 index 排序"
    (let [[state _] (run-chunks
                      [{:choices [{:delta {:tool_calls [{:index 0 :id "a" :function {:name "f0" :arguments "{}"}}]}}]}
                       {:choices [{:delta {:tool_calls [{:index 1 :id "b" :function {:name "f1" :arguments "{}"}}]}}]}])
          tcs (get-in (stream/build-response state) [:choices 0 :message :tool_calls])]
      (is (= ["a" "b"] (mapv :id tcs)))
      (is (= ["f0" "f1"] (mapv #(get-in % [:function :name]) tcs))))))

(deftest content-and-tool-calls-coexist-test
  (testing "同一 chunk 同时带 content 和 tool_calls 时，两者都不丢（回归：旧 cond 互斥会丢工具调用）"
    (let [[state toks] (run-chunks
                         [{:choices [{:delta {:content "稍等"
                                              :tool_calls [{:index 0 :id "c1"
                                                            :function {:name "search" :arguments "{}"}}]}}]}])
          resp (stream/build-response state)]
      (is (= "稍等" (str (:accumulated state))))         ;; content 累积
      (is (= ["稍等"] (mapv :token toks)))                ;; content 仍下发 token
      (is (= "c1" (-> resp (get-in [:choices 0 :message :tool_calls]) first :id))))))  ;; tool_call 未丢

;; ============================================================
;; reasoning_content
;; ============================================================

(deftest reasoning-then-content-test
  (testing "reasoning_content 先于 content 流出，分别 emit，互不混入"
    (let [[state toks] (run-chunks
                         [{:choices [{:delta {:reasoning_content "让我想"}}]}
                          {:choices [{:delta {:reasoning_content "想…"}}]}
                          {:choices [{:delta {:content "答案"}}]}])
          rtoks (filter :reasoning? toks)
          ctoks (remove :reasoning? toks)]
      (is (= "让我想想…" (str (:reasoning-accumulated state))))
      (is (= "答案" (str (:accumulated state))))
      (is (= ["让我想" "想…"] (mapv :reasoning-token rtoks)))
      (is (= ["答案"] (mapv :token ctoks)))
      ;; normalize-response 把 reasoning 与 text 分离
      (let [r (stream/normalize-response state :id "x" :model "deepseek-reasoner")]
        (is (= "答案" (response/response-text r)))
        (is (= "让我想想…" (response/response-reasoning r)))))))

;; ============================================================
;; 末块 usage / finish_reason
;; ============================================================

(deftest trailing-usage-chunk-test
  (testing "usage-only 末块（choices 为空 + 顶层 usage）被捕获"
    (let [[state _] (run-chunks
                      [{:choices [{:delta {:content "hi"}}]}
                       {:choices [{:delta {} :finish_reason "stop"}]}
                       {:choices [] :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}}])
          resp (stream/build-response state)
          r (stream/normalize-response state)]
      (is (= {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15} (:usage resp)))
      (is (= 10 (:input-tokens (response/response-usage r))))
      (is (= 5 (:output-tokens (response/response-usage r))))
      (is (= :stop (response/response-finish-reason r))))))
