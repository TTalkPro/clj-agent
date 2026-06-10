(ns im.ttalk.agent.provider.stream.dashscope-stream-test
  "DashScope 原生流式 SSE 处理器单测（parse-sse-line / process-event / build-response）。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.provider.stream.dashscope :as ds]
            [im.ttalk.agent.provider.dashscope :as dashscope]
            [im.ttalk.agent.model :as model]))

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
      (is (= "杭州是浙江的省会。" (:accumulated final)))
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
