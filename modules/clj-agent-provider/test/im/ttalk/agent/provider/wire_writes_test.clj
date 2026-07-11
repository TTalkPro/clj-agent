(ns im.ttalk.agent.provider.wire-writes-test
  "中立 tool-result 消息的 :writes 元数据（event-sourcing，设计文档 §12.4）
   只进历史存储、不发给 LLM——wire 层显式构造时天然剥落，此处钉住该性质。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.provider.wire.openai :as wire-openai]
            [im.ttalk.agent.provider.wire.anthropic :as wire-anthropic]))

(deftest wire-strips-writes-test
  (let [msgs [(msg/user "干活")
              (msg/assistant-tool-calls [(msg/tool-call "t1" "note" {:v "x"})])
              (msg/tool-result "t1" "note" "写了 x" {:slot "x"})]]
    (testing "openai wire：:writes 不外泄"
      (let [wire (wire-openai/neutral->wire msgs)]
        (is (not (clojure.string/includes? (pr-str wire) ":writes")))
        (is (= "写了 x" (:content (last (:messages wire)))) "结果内容照常")))
    (testing "anthropic wire：:writes 不外泄"
      (let [wire (wire-anthropic/neutral->wire msgs)]
        (is (not (clojure.string/includes? (pr-str wire) ":writes")))))))
