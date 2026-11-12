(ns im.ttalk.agent.server.agui.host-hooks-test
  "观澜接有状态 runtime 的两处 wire 缺口（feedbacks/2026-11-12-chartroom-stateful-agui-gaps.md ①②），走真 HTTP：
   审批策略给的 `:metadata {:kind \"question\"}` 出现在 Interrupt.metadata；`resume[].status = cancelled`
   经宿主的 `:reject-note` 变成交给模型的那句「用户取消了…」。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.agui.registry :as registry]
            [im.ttalk.agent.agui.runtime :as rt]
            [im.ttalk.agent.agui.tools :as agui-tools]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.pause :as pause]
            [im.ttalk.agent.server.agui.support :as s :refer [with-agui]]
            [im.ttalk.agent.tool :refer [deftool]]))

(set! *warn-on-reflection* true)

(deftool ask-user-question
  "问用户一个问题"
  [[q :string "问题"]]
  (str "不该执行到这里: " q))

(defn- note [{:keys [reason cancelled?]}]
  (cond cancelled? "用户取消了这次调用（resume status=cancelled）"
        reason (str "用户拒绝了这次调用：" reason)
        :else "用户拒绝了这次调用（approved=false）"))

(defn- entry [p]
  {:id "keel"
   :server-tool-names #{"ask-user-question"}
   :runtime (rt/runtime
             {:agent-fn (agui-tools/agent-fn
                         {:provider p :memory (memory/in-memory-store)
                          :pause-store (pause/in-memory-pause-store)
                          :tools [#'ask-user-question]
                          :reject-note note
                          :approval {:policy (fn [_] {:decision :prompt
                                                      :metadata {:kind "question"}})
                                     :cache false}})})})

(deftest host-kind-and-cancel-note
  (let [p (s/provider [(s/tool-call "tq" "ask-user-question" {:q "选哪个"}) (s/text "那就算了")])]
    (with-agui [c (s/start (registry/registry [(entry p)]))]
      (let [fin (peek (:events (s/sse! c "agent/keel/run" (s/body "t-q" "r-1" "帮我挑"))))
            itr (get-in fin [:outcome :interrupts 0])]
        (testing "① 宿主的 kind 上了 wire，pendingTool 照补"
          (is (= "question" (get-in itr [:metadata :kind])))
          (is (= "ask-user-question" (get-in itr [:metadata :pendingTool :name]))))
        (let [evs (:events (s/sse! c "agent/keel/run"
                                   (s/body "t-q" "r-2" "" {:resume [{:interruptId (:id itr) :status "cancelled"}]})))]
          (is (= "RUN_FINISHED" (:type (peek evs))))
          (testing "② 取消 ⇒ 宿主文案交给模型"
            (is (some (fn [msgs] (some #(= "用户取消了这次调用（resume status=cancelled）" (:content %)) msgs))
                      @(:asked p)))))))))
