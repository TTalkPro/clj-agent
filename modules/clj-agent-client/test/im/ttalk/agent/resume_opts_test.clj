(ns im.ttalk.agent.resume-opts-test
  "四个入口共用一份 opts 透传（`loop-passthrough-keys`）。

   钉的是这个洞：`react/build-chat-opts` 一直支持 `:on-token` / `:cancel-token`，
   但只有 `chat-stream` 在 `build-invoke-opts` **之后**手动补上它们——`chat-async`
   没补，`resume` 连 opts 位都没有。于是异步入口不能流式也不能取消，
   而 HITL 第二段（审批之后的续跑，往往正是最终答案）两样都没有。

   见 docs/agent-runtime-design.md §6.1 / §6.2。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.async :as async]
            [im.ttalk.agent.model :as provider]
            [im.ttalk.agent.simple-agent :as agent]
            [im.ttalk.agent.streaming :as streaming]
            [im.ttalk.agent.tool :refer [deftool]]))

(def executed (atom []))

(deftool danger-op
  "危险操作（敏感，需审批）"
  [[target :string "目标"]]
  {:sensitive true}
  (swap! executed conj target)
  (str "已处理 " target))

(defrecord StreamProvider [responses]
  provider/ILLMProvider
  (provider-name [_] :stream-mock)
  (call-llm [_ _ _ _] (let [r (first @responses)] (swap! responses rest) r))
  (extract-tool-calls [_ r] (:tool-calls r))
  (extract-text [_ r] (:text r))
  (build-tool-result [_ id content] {:role "tool" :tool_call_id id :content content})
  (supports-function-calling? [_] true)
  (supports-stream? [_] true)
  (call-llm-stream [_ _ _ _ on-token]
    (let [r (first @responses)]
      (swap! responses rest)
      (doseq [c (some->> (:text r) (map str))] (on-token {:token c}))
      r))
  (tool->schema [_ t] t))

(defn- agent-with [responses]
  (agent/create-agent {:provider (->StreamProvider (atom responses))
                       :tools [#'danger-op]
                       :on-pause (fn [_])}))

(deftest resume-streams-tokens-test
  (testing "resume 的续跑吃 :on-token —— 改之前这里一个 token 都没有"
    (reset! executed [])
    (let [a (agent-with [{:text nil :tool-calls [{:id "tc1" :name "danger-op" :args {:target "X"}}]}
                         {:text "处理完毕"}])
          first-tokens (atom [])
          resume-tokens (atom [])
          r1 (agent/chat-stream a "处理 X" #(swap! first-tokens conj (:token %)))]
      (is (= :paused (:status r1)))
      (is (empty? @executed))
      (let [r2 (agent/resume a "approved" nil {:on-token #(swap! resume-tokens conj (:token %))})]
        (is (= :completed (:status r2)))
        (is (= "处理完毕" (:text r2)))
        (is (= ["X"] @executed))
        (is (= "处理完毕" (apply str @resume-tokens))
            "审批之后那段必须逐 token 出来")))))

(deftest resume-async-streams-and-cancels-test
  (testing "resume-async 同样吃 :on-token / :cancel-token"
    (reset! executed [])
    (let [a (agent-with [{:text nil :tool-calls [{:id "tc1" :name "danger-op" :args {:target "Y"}}]}
                         {:text "异步处理完毕"}])
          tokens (atom [])]
      (agent/chat a "处理 Y")
      (is (agent/paused? a))
      (let [r (async/join (agent/resume-async a "approved" nil
                                              {:on-token #(swap! tokens conj (:token %))}))]
        (is (= :completed (:status r)))
        (is (= "异步处理完毕" (apply str @tokens))))))

  (testing "resume 前就取消 → 续跑不再往下跑"
    (reset! executed [])
    (let [a (agent-with [{:text nil :tool-calls [{:id "tc1" :name "danger-op" :args {:target "Z"}}]}
                         {:text "不该到这里"}])
          token (streaming/make-cancel-token)]
      (agent/chat a "处理 Z")
      (is (agent/paused? a))
      (streaming/request-cancel! token)
      (let [r (agent/resume a "approved" nil {:cancel-token token})]
        (is (= :cancelled (:status r)))
        (is (= ["Z"] @executed) "被批准的那个工具是 resume 的第一步，它照样跑完")))))

(deftest chat-async-streams-and-cancels-test
  (testing "chat-async 传 :on-token 即异步流式"
    (let [a (agent-with [{:text "异步流式"}])
          tokens (atom [])]
      (is (= :completed (:status (async/join (agent/chat-async a "hi" {:on-token #(swap! tokens conj (:token %))})))))
      (is (= "异步流式" (apply str @tokens)))))

  (testing "chat-async 传 :cancel-token 即可取消"
    (let [a (agent-with [{:text "不该产出"}])
          token (streaming/make-cancel-token)]
      (streaming/request-cancel! token)
      (is (= :cancelled (:status (async/join (agent/chat-async a "hi" {:cancel-token token}))))))))

(deftest passthrough-ignores-absent-keys-test
  (testing "没传的键不进 loop opts（nil 不该覆盖）"
    (let [a (agent-with [{:text "ok"}])]
      (is (= :completed (:status (agent/chat a "hi" {}))))
      (is (= :completed (:status (agent/chat a "hi" nil)))))))
