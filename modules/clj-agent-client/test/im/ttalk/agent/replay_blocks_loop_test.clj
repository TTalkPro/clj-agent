(ns im.ttalk.agent.replay-blocks-loop-test
  "P3 验收（确定性部分）：**走完整 agent 循环**，第二轮发出去的历史里必须带着载荷。

   这是 P3 唯一真正要保证的事——单看 response->neutral 或单看 wire 都证明不了它：
   载荷要穿过 `service 归一化 → memory filter 落库 → 下一轮取历史 → 递给 provider`
   整条链。链上任何一环丢掉它，工具循环第二轮就退回修复前的行为
   （实测代价 M3 正确率 100%→82.5%，docs/provider-variant-design.md §7.5.3）。

   边界说明：client 模块只负责把载荷带到**协议边界**（provider/call-llm 收到的
   中立消息）。中立 → wire 的逐字还原由 provider 模块的 replay_blocks_test 覆盖，
   各测各的层，不跨模块依赖。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.client :as agent]
            [im.ttalk.agent.model :as proto]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.tool :refer [deftool]]))

(deftool probe-tool
  "查询一个槽位"
  [[slot :string "槽位"]]
  (str "槽位 " slot " 的值是 42"))

(def ^:private thinking-block
  {:type "thinking" :thinking "我需要先查一下" :signature "sig-loop-1"})

(defn- raw-turn-1 []
  {:id "m1" :model "MiniMax-M3" :stop_reason "tool_use"
   :content [thinking-block
             {:type "tool_use" :id "call_1" :name "probe-tool" :input {:slot "X7"}}]})

(defn- raw-turn-2 []
  {:id "m2" :model "MiniMax-M3" :stop_reason "end_turn"
   :content [{:type "thinking" :thinking "拿到了" :signature "sig-loop-2"}
             {:type "text" :text "值是 42"}]})

(defrecord ThinkingProvider [calls]
  proto/ILLMProvider
  (provider-name [_] :thinking-mock)
  (call-llm [_ _config messages _tools]
    (swap! calls conj (vec messages))
    (if (= 1 (count @calls)) (raw-turn-1) (raw-turn-2)))
  (extract-tool-calls [_ r]
    (->> (:content r)
         (filter #(= "tool_use" (:type %)))
         (mapv (fn [{:keys [id name input]}] (msg/tool-call id name input)))
         seq))
  (extract-text [_ r]
    (->> (:content r) (filter #(= "text" (:type %))) (map :text) first))
  (build-tool-result [_ id c] {:role "tool" :tool_call_id id :content c})
  (supports-function-calling? [_] true)
  (supports-stream? [_] false)
  (tool->schema [_ t] t)

  proto/IReplayableResponse
  (replay-blocks [_ r]
    (when (some #(= "thinking" (:type %)) (:content r))
      {:format :anthropic-content :data (vec (:content r))})))

(deftest thinking-blocks-reach-the-next-turn
  (let [calls (atom [])
        a (agent/create-agent {:provider (->ThinkingProvider calls)
                               :model "MiniMax-M3"
                               :tools [#'probe-tool]})
        r (agent/chat a "查一下 X7")]
    (is (= :completed (:status r)))
    (is (= 2 (count @calls)) "应当是两轮：工具调用 + 结果回灌")

    (testing "第二轮发出去的历史里，assistant 消息带着载荷"
      (let [second-turn (second @calls)
            assistant-msgs (filter #(= :assistant (:role %)) second-turn)]
        (is (seq assistant-msgs) "第二轮必须包含第一轮的 assistant 消息")
        (let [blocks (msg/blocks (first assistant-msgs))]
          (is (some? blocks) "载荷丢了——这正是 P3 修的洞，第二轮会退回修复前行为")
          (is (= :anthropic-content (:format blocks)))
          (is (= [thinking-block
                  {:type "tool_use" :id "call_1" :name "probe-tool" :input {:slot "X7"}}]
                 (:data blocks))
              "必须逐字：thinking 文本与 signature 都不能少")
          (is (= "sig-loop-1" (-> blocks :data first :signature))))))

    (testing "第一轮不受影响（历史里只有用户消息）"
      (is (= [:user] (mapv :role (first @calls)))))))

(deftest plain-provider-loop-unchanged
  (testing "不实现可选协议的 provider：整条链一如既往，历史里没有 :blocks 键"
    (let [calls (atom [])
          ;; 与上面同一个 record，但把协议实现摘掉——用 reify 造一个纯 ILLMProvider
          p (reify proto/ILLMProvider
              (provider-name [_] :plain-mock)
              (call-llm [_ _ messages _]
                (swap! calls conj (vec messages))
                {:id "m" :content [{:type "text" :text "好的"}] :stop_reason "end_turn"})
              (extract-tool-calls [_ _] nil)
              (extract-text [_ r] (-> r :content first :text))
              (build-tool-result [_ id c] {:role "tool" :tool_call_id id :content c})
              (supports-function-calling? [_] true)
              (supports-stream? [_] false)
              (tool->schema [_ t] t))
          a (agent/create-agent {:provider p :model "m"})
          r (agent/chat a "你好")]
      (is (= :completed (:status r)))
      (is (= "好的" (:text r)))
      (is (not (satisfies? proto/IReplayableResponse p)))
      (is (every? #(not (contains? % :blocks)) (apply concat @calls))))))
