(ns im.ttalk.agent.model.spec-test
  "ModelSpec 元数据与注册表（designs/provider-reliability.md §8）。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.model.spec :as spec]))

(deftest ref-test
  (is (= ["openrouter" "anthropic/claude"] (spec/split-ref "openrouter/anthropic/claude")))
  (is (nil? (spec/split-ref "noslash")))
  (is (nil? (spec/split-ref "/x")))
  (is (nil? (spec/split-ref "x/")))
  (is (= "gpt" (spec/ref-wire-id "gpt")) "没有前缀的就是线上的名字")
  (is (= "anthropic" (spec/ref-provider "anthropic/claude"))))

(deftest model-spec-test
  (testing "认得的键提到顶层，其余进 :options；fallbacks 去自身去重"
    (let [s (spec/model-spec "anthropic/claude-sonnet-4"
                             {:context-limit 200000 :output-limit 64000 :vision? true
                              :thinking-levels '("low" "high") :my-key 1
                              :fallbacks ["openai/gpt-4o" "anthropic/claude-sonnet-4" "openai/gpt-4o"]})]
      (is (= "anthropic" (:provider s)))
      (is (= "claude-sonnet-4" (:wire-id s)))
      (is (= 200000 (:context-limit s)))
      (is (= ["low" "high"] (:thinking-levels s)))
      (is (= {:my-key 1} (:options s)))
      (is (= 1 (spec/option s :my-key)))
      (is (= ["openai/gpt-4o"] (:fallbacks s)))
      (is (= 59904 (spec/thinking-budget-cap s)))))
  (testing "校验"
    (is (thrown? clojure.lang.ExceptionInfo (spec/model-spec "bad")))
    (is (thrown? clojure.lang.ExceptionInfo (spec/model-spec "a/b" {:thinking-levels "high"})))
    (is (thrown? clojure.lang.ExceptionInfo (spec/model-spec "a/b" {:fallbacks ["bad"]}))))
  (testing "派生查询"
    (let [s (spec/model-spec "a/b" {:context-limit 100000 :usable-window-percent 90 :auto-compact-percent 80})]
      (is (= 90000 (spec/usable-context s)))
      (is (= 72000 (spec/auto-compact-threshold s))))
    (is (nil? (spec/thinking-budget-cap (spec/model-spec "a/b" {:output-limit 4000}))))))

(deftest registry-test
  (let [reg (spec/registry)]
    (spec/register! reg "a/one" {:context-limit 1000 :fallbacks ["b/two"]})
    (spec/register! reg "b/two" {:fallbacks ["c/three" "a/one"]})
    (spec/register! reg "c/three" {:fallbacks ["b/two"]})
    (testing "查询"
      (is (= 1000 (spec/context-limit reg "a/one")))
      (is (nil? (spec/output-limit reg "a/one")))
      (is (= ["a/one" "b/two" "c/three"] (mapv :ref (spec/models reg)))))
    (testing "链：广度优先、去重、去起点、防环"
      (is (= ["b/two" "c/three"] (spec/fallback-chain reg "a/one")))
      (is (= [] (spec/fallback-chain reg "nope"))))
    (testing "同 ref 替换位置不变；set-fallbacks!；unregister!"
      (spec/register! reg "a/one" {:context-limit 2000})
      (is (= ["a/one" "b/two" "c/three"] (mapv :ref (spec/models reg))))
      (is (= 2000 (spec/context-limit reg "a/one")))
      (spec/set-fallbacks! reg "a/one" ["x/unregistered" "c/three"])
      (is (= ["x/unregistered" "c/three" "b/two"] (spec/fallback-chain reg "a/one"))
          "没登记的 ref 留在链上")
      (is (thrown? clojure.lang.ExceptionInfo (spec/set-fallbacks! reg "zz/zz" [])))
      (spec/unregister! reg "c/three")
      (is (nil? (spec/lookup reg "c/three"))))))
