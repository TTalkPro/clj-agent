(ns im.ttalk.agent.provider.stream.dashscope-stream-test
  "DashScope 原生流式 SSE 处理器单测（parse-sse-line / process-event / build-response）。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.provider.stream.dashscope :as ds]
            [im.ttalk.agent.provider.dashscope :as dashscope]
            [im.ttalk.agent.model :as model]
            [im.ttalk.agent.model.response :as response]))

(defn- run-events [lines]
  (reduce (fn [st line]
            (let [[nst _] (if-let [ev (ds/parse-sse-line line)]
                            (ds/process-event ev st)
                            [st nil])]
              nst))
          (ds/make-initial-state)
          lines))

(deftest parse-sse-line-test
  (testing "data 行解析（兼容有无空格），非 data 行返回 nil"
    (is (= {:output {:choices [{:message {:content "你好"}}]}}
           (ds/parse-sse-line "data:{\"output\":{\"choices\":[{\"message\":{\"content\":\"你好\"}}]}}")))
    ;; 带空格也兼容
    (is (= {:a 1} (ds/parse-sse-line "data: {\"a\":1}")))
    ;; 非 data 行（DashScope 的 id/event/:HTTP_STATUS）→ nil
    (is (nil? (ds/parse-sse-line "id:1")))
    (is (nil? (ds/parse-sse-line "event:result")))
    (is (nil? (ds/parse-sse-line ":HTTP_STATUS/200")))
    (is (nil? (ds/parse-sse-line "data:[DONE]")))
    (is (nil? (ds/parse-sse-line "")))))

(def ^:private sse-lines
  ;; 模拟 incremental_output=true 的逐 chunk 增量，DashScope SSE 事件帧
  ["id:1" "event:result" ":HTTP_STATUS/200"
   "data:{\"output\":{\"choices\":[{\"finish_reason\":\"null\",\"message\":{\"role\":\"assistant\",\"content\":\"杭州\"}}]},\"usage\":{\"input_tokens\":10,\"output_tokens\":1,\"total_tokens\":11},\"request_id\":\"req-1\"}"
   ""
   "id:2" "event:result" ":HTTP_STATUS/200"
   "data:{\"output\":{\"choices\":[{\"finish_reason\":\"null\",\"message\":{\"role\":\"assistant\",\"content\":\"是浙江\"}}]},\"request_id\":\"req-1\"}"
   ""
   "id:3" "event:result" ":HTTP_STATUS/200"
   "data:{\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"的省会。\"}}]},\"usage\":{\"input_tokens\":10,\"output_tokens\":6,\"total_tokens\":16},\"request_id\":\"req-1\"}"
   ""])

(deftest incremental-accumulation-test
  (testing "incremental 增量累积为完整文本，finish_reason/usage/id 正确"
    (let [tokens (atom [])
          final  (reduce (fn [st line]
                           (if-let [ev (ds/parse-sse-line line)]
                             (let [[nst tok] (ds/process-event ev st)]
                               (when tok (swap! tokens conj (:token tok)))
                               nst)
                             st))
                         (ds/make-initial-state)
                         sse-lines)
          resp (ds/build-response final)]
      ;; 逐 token（增量）
      (is (= ["杭州" "是浙江" "的省会。"] @tokens))
      ;; 累积全文
      (is (= "杭州是浙江的省会。" (str (:accumulated final))))
      ;; finish_reason 生成中的 "null" 不记，结束的 "stop" 记下
      (is (= "stop" (:finish-reason final)))
      ;; build-response 是 OpenAI 兼容形态，extract-text 读得到
      (is (= "杭州是浙江的省会。" (get-in resp [:choices 0 :message :content])))
      (is (= "stop" (get-in resp [:choices 0 :finish_reason])))
      (is (= "req-1" (:id resp)))
      (is (= 16 (get-in resp [:usage :total_tokens]))))))

(deftest provider-extract-text-on-stream-response-test
  (testing "provider 的 extract-text 能解析流式 build-response（与同步同形）"
    (let [resp (ds/build-response (run-events sse-lines))
          p (dashscope/create-provider {:api-key "k"})]
      (is (= "杭州是浙江的省会。" (model/extract-text p resp)))
      (is (true? (model/supports-stream? p))))))

(deftest reasoning-stream-test
  (testing "enable_thinking 开时的 reasoning_content 增量：单独 emit、不混入答案、进最终响应"
    (let [ev (fn [m] {:output {:choices [{:finish_reason "null" :message m}]} :request_id "r"})
          s0 (ds/make-initial-state)
          [s1 t1] (ds/process-event (ev {:role "assistant" :content "" :reasoning_content "先想"}) s0)
          [s2 t2] (ds/process-event (ev {:role "assistant" :content "" :reasoning_content "一下"}) s1)
          [s3 t3] (ds/process-event (ev {:role "assistant" :content "答案"}) s2)
          resp (ds/build-response s3)
          m (get-in resp [:choices 0 :message])]
      (is (= {:reasoning-token "先想" :reasoning? true} t1))
      (is (= {:reasoning-token "一下" :reasoning? true} t2))
      (is (= {:token "答案"} t3))
      (is (= "答案" (:content m)))
      (is (= "先想一下" (:reasoning_content m)))
      (is (= "先想一下" (response/extract-reasoning resp))
          "chat-model 的推理读口（model.response/extract-reasoning）读得到")))
  (testing "没有思考：最终响应不带 reasoning_content（既有形状不变）"
    (is (not (contains? (get-in (ds/build-response (run-events sse-lines)) [:choices 0 :message])
                        :reasoning_content)))))

(deftest thinking-params-on-the-wire-test
  (let [build #'dashscope/build-request
        params (fn [cfg _stream?] (:parameters (build cfg [{:role "user" :content "hi"}] [])))
        stream-params (fn [cfg] (:parameters (#'dashscope/build-stream-request cfg [{:role "user" :content "hi"}] [])))]
    (testing "没给思考键：什么都不写（缺省不变）"
      (is (not (contains? (params {:model "qwen-plus"} false) :enable_thinking)))
      (is (not (contains? (stream-params {:model "qwen-plus"}) :enable_thinking))))
    (testing ":thinking-effort：流式、非流式都 true（2026-11-10 放开），档位不上线"
      (is (true? (:enable_thinking (stream-params {:thinking-effort :high}))))
      (is (true? (:enable_thinking (params {:thinking-effort :high} false))))
      (is (not-any? #(contains? (params {:thinking-effort :high} false) %)
                    [:thinking :reasoning_effort])))
    (testing ":thinking-enabled false：显式 false"
      (is (false? (:enable_thinking (stream-params {:thinking-enabled false})))))
    (testing ":thinking-style 不管写什么都按 qwen，只有 :none 不写"
      (is (true? (:enable_thinking (stream-params {:thinking-effort :high :thinking-style :nested}))))
      (is (not (contains? (stream-params {:thinking-effort :high :thinking-style :none}) :enable_thinking))))
    (testing "显式 :enable-thinking 压过档位"
      (is (false? (:enable_thinking (stream-params {:thinking-effort :high :enable-thinking false})))))
    (testing ":extra-parameters 并入 parameters（thinking_budget 等私有字段）"
      (is (= 2048 (:thinking_budget (stream-params {:thinking-effort :high
                                                     :extra-parameters {:thinking_budget 2048}})))))))
