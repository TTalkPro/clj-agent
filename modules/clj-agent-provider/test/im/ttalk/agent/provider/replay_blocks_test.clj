(ns im.ttalk.agent.provider.replay-blocks-test
  "P3 回传契约的 provider 侧：抽取（IReplayableResponse）与还原（wire）。

   动机不是「官方文档要求 content 完整回传」，而是实测代价——M3 上剥掉 thinking
   回传：正确率 100% → 82.5%、逐轮全对 100% → 47.5%（n=40/臂，p=0.0059，
   见 docs/provider-variant-design.md §7.5.3）。

   两条路都必须钉住：认得的原样吐，认不出的**降级重建**。后者是缺省路径——
   存量历史没有 :blocks，跨 provider 的历史 :format 也对不上。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.model :as proto]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.provider.anthropic :as anthropic]
            [im.ttalk.agent.provider.wire.anthropic :as wire]))

(def ^:private thinking-block
  {:type "thinking" :thinking "先解码再查天气" :signature "sig-abc123"})

(def ^:private raw-with-thinking
  {:id "msg_1" :model "MiniMax-M3" :stop_reason "tool_use"
   :content [thinking-block
             {:type "text" :text "我来查一下"}
             {:type "tool_use" :id "call_1" :name "get-weather" :input {:city "北京"}}]})

(def ^:private raw-without-thinking
  {:id "msg_2" :model "claude" :stop_reason "end_turn"
   :content [{:type "text" :text "你好"}]})

;;; ============================================================
;;; 抽取
;;; ============================================================

(deftest replay-blocks-extraction
  (let [p (anthropic/create-provider {:api-key "k"})]
    (testing "provider 实现了可选协议——satisfies? 探测得到"
      (is (satisfies? proto/IReplayableResponse p)))

    (testing "含 thinking 块 → 捕获**整段 content**（含 signature，逐字）"
      (let [{:keys [format data]} (proto/replay-blocks p raw-with-thinking)]
        (is (= :anthropic-content format))
        (is (= (:content raw-with-thinking) data))
        (is (= "sig-abc123" (:signature (first data)))
            "signature 必须一起带走：它是 Anthropic 官方端点校验回传的凭据")))

    (testing "无 thinking 块 → nil（范围严格限定在有实测证据的情况）"
      (is (nil? (proto/replay-blocks p raw-without-thinking))))

    (testing "content 不是块数组（旧式/异常响应）→ nil，不炸"
      (is (nil? (proto/replay-blocks p {:content "纯字符串"})))
      (is (nil? (proto/replay-blocks p {}))))))

;;; ============================================================
;;; 还原
;;; ============================================================

(deftest wire-replays-blocks-verbatim
  (testing "认得的 :blocks → 原样吐回，不重建"
    (let [m (-> (msg/assistant-tool-calls [(msg/tool-call "call_1" "get-weather" {:city "北京"})]
                                          "我来查一下")
                (msg/with-blocks {:format :anthropic-content
                                  :data (:content raw-with-thinking)}))
          {:keys [messages]} (wire/neutral->wire [(msg/user "北京天气") m])]
      (is (= (:content raw-with-thinking) (:content (second messages))))
      (is (= "thinking" (:type (first (:content (second messages)))))
          "thinking 块必须在回传的 content 里")
      (is (= "sig-abc123" (:signature (first (:content (second messages)))))))))

(deftest wire-falls-back-when-blocks-absent-or-foreign
  (testing "没有 :blocks → 走原来的 text + tool_use 重建（存量历史的路径）"
    (let [m (msg/assistant-tool-calls [(msg/tool-call "call_1" "get-weather" {:city "北京"})]
                                      "我来查一下")
          {:keys [messages]} (wire/neutral->wire [m])]
      (is (= [{:type "text" :text "我来查一下"}
              {:type "tool_use" :id "call_1" :name "get-weather" :input {:city "北京"}}]
             (:content (first messages))))))

  (testing "**别家 provider 的 :format → 当它不存在**（把方言喂给 Anthropic 端点必炸）"
    (let [m (-> (msg/assistant "你好")
                (msg/with-blocks {:format :gemini-parts :data [{:thought_signature "xyz"}]}))
          {:keys [messages]} (wire/neutral->wire [m])]
      (is (= "你好" (:content (first messages))))))

  (testing ":data 为空 → 同样降级，不吐空 content 数组"
    (let [m (-> (msg/assistant "你好")
                (msg/with-blocks {:format :anthropic-content :data []}))
          {:keys [messages]} (wire/neutral->wire [m])]
      (is (= "你好" (:content (first messages)))))))

;;; ============================================================
;;; 往返：响应 → 中立 → wire（本次改动的完整链路）
;;; ============================================================

(deftest round-trip-preserves-thinking
  (testing "抽取出的载荷经中立消息回到 wire，与原始 content 逐字相同"
    (let [p (anthropic/create-provider {:api-key "k"})
          blocks (proto/replay-blocks p raw-with-thinking)
          neutral (-> (msg/assistant "我来查一下") (msg/with-blocks blocks))
          {:keys [messages]} (wire/neutral->wire [neutral])]
      (is (= (:content raw-with-thinking) (:content (first messages)))))))
