(ns im.ttalk.agent.provider.cache-test
  "Anthropic prompt caching 策略层单测"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.provider.common.cache :as cache]))

(def base-params
  {:model "claude-opus-4-8"
   :max_tokens 1024
   :system "你是一个很长的系统提示……"
   :tools [{:name "f1" :input_schema {}}
           {:name "f2" :input_schema {}}]
   :messages [{:role "user" :content "你好"}
              {:role "assistant" :content "在的"}
              {:role "user" :content "现在几点"}]})

(deftest ephemeral-test
  (is (= {:type "ephemeral"} (cache/ephemeral nil)))
  (is (= {:type "ephemeral" :ttl "1h"} (cache/ephemeral "1h"))))

(deftest strategy-none-test
  (testing ":none 与未知策略原样返回"
    (is (= base-params (cache/apply-anthropic-cache base-params :none)))
    (is (= base-params (cache/apply-anthropic-cache base-params nil)))
    (is (= base-params (cache/apply-anthropic-cache base-params :bogus)))))

(deftest strategy-system-test
  (testing ":system 把字符串 system 块化并在末块打断点"
    (let [r (cache/apply-anthropic-cache base-params :system)
          sys (:system r)]
      (is (vector? sys))
      (is (= {:type "text" :text "你是一个很长的系统提示……"
              :cache_control {:type "ephemeral"}}
             (last sys)))
      (is (= 1 (cache/breakpoint-count r)))
      ;; 不动 tools / messages
      (is (= (:tools base-params) (:tools r))))))

(deftest strategy-tools-test
  (testing ":tools 在最后一个工具上打断点"
    (let [r (cache/apply-anthropic-cache base-params :tools)
          tools (:tools r)]
      (is (nil? (:cache_control (first tools))))
      (is (= {:type "ephemeral"} (:cache_control (last tools))))
      (is (= 1 (cache/breakpoint-count r)))
      (is (string? (:system r))))))

(deftest strategy-system-and-tools-test
  (testing ":system-and-tools 两个断点"
    (let [r (cache/apply-anthropic-cache base-params :system-and-tools)]
      (is (= {:type "ephemeral"} (:cache_control (last (:tools r)))))
      (is (= {:type "ephemeral"} (:cache_control (last (:system r)))))
      (is (= 2 (cache/breakpoint-count r))))))

(deftest strategy-conversation-test
  (testing ":conversation 标记最后一条消息的最后一个内容块（字符串转块）"
    (let [r (cache/apply-anthropic-cache base-params :conversation)
          last-msg (last (:messages r))
          blocks (:content last-msg)]
      (is (vector? blocks))
      (is (= {:type "text" :text "现在几点" :cache_control {:type "ephemeral"}}
             (last blocks)))
      (is (= 1 (cache/breakpoint-count r)))
      ;; 前面的消息不受影响
      (is (= "你好" (:content (first (:messages r))))))))

(deftest ttl-test
  (testing "ttl 透传到 cache_control"
    (let [r (cache/apply-anthropic-cache base-params :system "1h")]
      (is (= {:type "ephemeral" :ttl "1h"} (:cache_control (last (:system r))))))))

(deftest idempotent-existing-cache-control-test
  (testing "已带 cache_control 的块不会被重复覆盖"
    (let [params (assoc base-params :system
                        [{:type "text" :text "a" :cache_control {:type "ephemeral" :ttl "1h"}}])
          r (cache/apply-anthropic-cache params :system nil)]
      ;; 保留原 ttl，不被默认 5min 覆盖
      (is (= {:type "ephemeral" :ttl "1h"} (:cache_control (last (:system r))))))))

(deftest breakpoint-cap-test
  (testing "断点数不超过 4（system-and-tools 仅 2 个，安全）"
    (is (<= (cache/breakpoint-count
              (cache/apply-anthropic-cache base-params :system-and-tools))
            cache/max-breakpoints))))

(def tool-loop-params
  "含多轮工具结果的请求参数（模拟工具循环历史）"
  {:model "claude-opus-4-8"
   :max_tokens 1024
   :system "系统提示"
   :messages [{:role "user" :content "查天气"}
              {:role "assistant" :content [{:type "tool_use" :id "t1" :name "weather" :input {}}]}
              {:role "user" :content [{:type "tool_result" :tool_use_id "t1" :content "晴 25°C"}]}
              {:role "assistant" :content [{:type "tool_use" :id "t2" :name "weather" :input {}}]}
              {:role "user" :content [{:type "tool_result" :tool_use_id "t2" :content "多云 22°C"}]}]})

(deftest strategy-tool-results-test
  (testing ":tool-results 在最后一个 tool_result 块上打断点"
    (let [r (cache/apply-anthropic-cache tool-loop-params :tool-results)
          msgs (:messages r)
          last-tr-block (-> msgs (nth 4) :content first)
          earlier-tr-block (-> msgs (nth 2) :content first)]
      (is (= {:type "ephemeral"} (:cache_control last-tr-block)))
      ;; 仅最后一个 tool_result 被标记，之前的不动
      (is (nil? (:cache_control earlier-tr-block)))
      (is (= 1 (cache/breakpoint-count r)))))
  (testing "无 tool_result 时原样返回"
    (is (= base-params (cache/apply-anthropic-cache base-params :tool-results)))))

(deftest strategy-system-and-conversation-test
  (testing ":system-and-conversation 两个断点（system + 最后消息末块）"
    (let [r (cache/apply-anthropic-cache base-params :system-and-conversation)]
      (is (= {:type "ephemeral"} (:cache_control (last (:system r)))))
      (is (= {:type "ephemeral"} (:cache_control (last (:content (last (:messages r)))))))
      (is (= 2 (cache/breakpoint-count r))))))
