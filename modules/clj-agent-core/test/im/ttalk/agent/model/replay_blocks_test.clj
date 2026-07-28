(ns im.ttalk.agent.model.replay-blocks-test
  "P3 回传契约的 core 侧：service 归一化时**探测**可选协议，把不透明载荷搬进响应。

   要钉住的关键性质是**可选**：不实现 IReplayableResponse 的 provider（仓库外的
   实现也算）必须一行不改照常工作。这就是不往 ILLMProvider 加方法的全部理由。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.model :as proto]
            [im.ttalk.agent.model.response :as resp]
            [im.ttalk.agent.model.service :as service]))

(def ^:private raw
  {:id "m1" :model "MiniMax-M3" :stop_reason "end_turn"
   :content [{:type "thinking" :thinking "想一下" :signature "sig-1"}
             {:type "text" :text "答案"}]})

;;; 实现了可选协议的 provider
(defrecord ReplayingProvider []
  proto/ILLMProvider
  (provider-name [_] :replaying)
  (call-llm [_ _ _ _] raw)
  (extract-tool-calls [_ _] nil)
  (extract-text [_ r] (->> (:content r) (filter #(= "text" (:type %))) (map :text) first))
  (build-tool-result [_ id c] {:role "tool" :tool_call_id id :content c})
  (supports-function-calling? [_] true)
  (supports-stream? [_] false)
  (tool->schema [_ t] t)

  proto/IReplayableResponse
  (replay-blocks [_ r] {:format :anthropic-content :data (vec (:content r))}))

;;; 老 provider：**一行不改**，不知道有这个协议
(defrecord PlainProvider []
  proto/ILLMProvider
  (provider-name [_] :plain)
  (call-llm [_ _ _ _] raw)
  (extract-tool-calls [_ _] nil)
  (extract-text [_ _] "答案")
  (build-tool-result [_ id c] {:role "tool" :tool_call_id id :content c})
  (supports-function-calling? [_] true)
  (supports-stream? [_] false)
  (tool->schema [_ t] t))

(deftest optional-protocol-is-really-optional
  (testing "satisfies? 精确区分两种 provider"
    (is (satisfies? proto/IReplayableResponse (->ReplayingProvider)))
    (is (not (satisfies? proto/IReplayableResponse (->PlainProvider)))
        "新协议**不得**有 extend-type Object 兜底——ILLMProvider 就是因为有兜底，
         satisfies? 对任意对象恒为 true（见 model/provider? 的注释）；
         本协议的整套机制建立在 satisfies? 上，加兜底等于当场失效")))

(deftest service-threads-replay-blocks
  (testing "实现了协议 → 归一化响应带上载荷"
    (let [svc (service/create-service (->ReplayingProvider) {:model "m"})
          r ((:chat-fn svc) [{:role :user :content "hi"}] {})]
      (is (= :anthropic-content (:format (resp/response-replay-blocks r))))
      (is (= (:content raw) (:data (resp/response-replay-blocks r))))
      (is (= "答案" (resp/response-text r)) "原有字段不受影响")))

  (testing "没实现 → nil，其余一切照旧（老 provider 零改动）"
    (let [svc (service/create-service (->PlainProvider) {:model "m"})
          r ((:chat-fn svc) [{:role :user :content "hi"}] {})]
      (is (nil? (resp/response-replay-blocks r)))
      (is (= "答案" (resp/response-text r)))))

  (testing "流式路径同样带上载荷（流式响应的块形状与非流式同构，实测确认）"
    (let [svc (service/create-service (->ReplayingProvider) {:model "m"})
          r ((:stream-fn svc) [{:role :user :content "hi"}] {} (fn [_] nil))]
      (is (= (:content raw) (:data (resp/response-replay-blocks r)))))))

(deftest make-response-carries-replay-blocks
  (testing "make-response 认这个键；不传即 nil"
    (is (= {:format :x :data [1]}
           (resp/response-replay-blocks (resp/make-response :text "t" :replay-blocks {:format :x :data [1]}))))
    (is (nil? (resp/response-replay-blocks (resp/make-response :text "t"))))))
