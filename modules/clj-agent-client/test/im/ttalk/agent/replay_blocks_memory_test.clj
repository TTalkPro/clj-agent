(ns im.ttalk.agent.replay-blocks-memory-test
  "P3 回传契约的 client 侧：response->neutral 必须把不透明载荷带进历史。

   这里正是 P3 修的那个洞（docs/provider-variant-design.md §1.3）：载荷在响应里
   有、在历史里没有 → 下一轮 wire 层再也拿不回来。实测代价 M3 正确率 100%→82.5%。

   还钉一条容易在重构里丢掉的性质：**载荷要能跨轮活下来**，即经 memory store
   存取往返后仍在。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.advisor.memory :as am]
            [im.ttalk.agent.memory :as mem]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.response :as resp]))

(def ^:private blocks
  {:format :anthropic-content
   :data [{:type "thinking" :thinking "先想想" :signature "sig-9"}
          {:type "text" :text "答案"}]})

(deftest response->neutral-carries-blocks
  (testing "纯文本响应：载荷挂到中立消息的 :blocks"
    (let [m (am/response->neutral (resp/make-response :text "答案" :replay-blocks blocks))]
      (is (= blocks (msg/blocks m)))
      (is (= "答案" (msg/content m)))))

  (testing "带 tool-calls 的响应：载荷同样挂上（工具循环才是它真正要用的场景）"
    (let [m (am/response->neutral
              (resp/make-response :text "我来查"
                                  :tool-calls [{:id "c1" :name "get-weather" :args {:city "北京"}}]
                                  :replay-blocks blocks))]
      (is (= blocks (msg/blocks m)))
      (is (msg/has-tool-calls? m))))

  (testing "没有载荷（老 provider）→ 消息里干脆没有这个键，不是 nil 占位"
    (let [m (am/response->neutral (resp/make-response :text "答案"))]
      (is (not (contains? m :blocks))))))

(deftest blocks-survive-memory-round-trip
  (testing "存进 store 再取出来，载荷仍在——跨轮存活是它唯一的用途"
    (let [store (mem/in-memory-store)
          m (am/response->neutral (resp/make-response :text "答案" :replay-blocks blocks))]
      (mem/mem-add store "cid-1" [(msg/user "问题") m])
      (let [restored (mem/mem-get store "cid-1")
            assistant-msg (last restored)]
        (is (= blocks (msg/blocks assistant-msg)))
        (is (= "sig-9" (-> (msg/blocks assistant-msg) :data first :signature))
            "signature 是 Anthropic 官方端点校验回传的凭据，丢了等于没回传")))))

(deftest with-blocks-is-a-no-op-on-nil
  (testing "nil / 空载荷不写键——避免历史里塞满 :blocks nil"
    (is (not (contains? (msg/with-blocks (msg/assistant "hi") nil) :blocks)))
    (is (not (contains? (msg/with-blocks (msg/assistant "hi") {}) :blocks)))))
