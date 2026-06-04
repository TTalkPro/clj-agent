(ns im.ttalk.agent.provider.wire.wire-test
  "P1 中立 ↔ wire 转换单测（OpenAI / Anthropic）"
  (:require [clojure.test :refer [deftest testing is]]
            [cheshire.core :as json]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.provider.wire.openai :as oai]
            [im.ttalk.agent.provider.wire.anthropic :as ant]))

;;; 公共测试数据：一段含工具往返的中立对话
(def conversation
  [(msg/system "你是助手")
   (msg/user "北京天气?")
   (msg/assistant-tool-calls [(msg/tool-call "c1" "get_weather" {:city "北京"})])
   (msg/tool-result "c1" "get_weather" "晴 22°C")
   (msg/assistant "北京晴，22°C。")])

;;; ============================================================
;;; OpenAI wire
;;; ============================================================

(deftest openai-neutral->wire
  (let [{:keys [messages]} (oai/neutral->wire conversation)]
    (testing "system/user 内联为普通消息"
      (is (= {:role "system" :content "你是助手"} (nth messages 0)))
      (is (= {:role "user" :content "北京天气?"} (nth messages 1))))
    (testing "assistant tool_calls 是 OpenAI function 形态，arguments 为 JSON 字符串"
      (let [a (nth messages 2)
            tc (first (:tool_calls a))]
        (is (= "assistant" (:role a)))
        (is (= "c1" (:id tc)))
        (is (= "function" (:type tc)))
        (is (= "get_weather" (get-in tc [:function :name])))
        (is (= {:city "北京"} (json/parse-string (get-in tc [:function :arguments]) true)))))
    (testing "tool 结果是 role=tool + tool_call_id"
      (is (= {:role "tool" :tool_call_id "c1" :content "晴 22°C"} (nth messages 3))))))

(deftest openai-response->neutral
  (testing "纯文本响应"
    (is (= (msg/assistant "你好")
           (oai/response->neutral {:choices [{:message {:role "assistant" :content "你好"}}]}))))
  (testing "工具调用响应（arguments 为 JSON 字符串）"
    (let [resp {:choices [{:message {:role "assistant" :content nil
                                     :tool_calls [{:id "c9" :type "function"
                                                   :function {:name "get_weather"
                                                              :arguments "{\"city\":\"上海\"}"}}]}}]}
          n (oai/response->neutral resp)]
      (is (msg/has-tool-calls? n))
      (is (= [(msg/tool-call "c9" "get_weather" {:city "上海"})] (msg/tool-calls n))))))

(deftest openai-roundtrip
  (testing "neutral→wire→response->neutral 对 assistant 工具调用保持等价"
    (let [{:keys [messages]} (oai/neutral->wire conversation)
          assistant-wire (nth messages 2)
          ;; 模拟把该 assistant wire 当成响应回读
          resp {:choices [{:message assistant-wire}]}
          back (oai/response->neutral resp)]
      (is (= [(msg/tool-call "c1" "get_weather" {:city "北京"})]
             (msg/tool-calls back))))))

;;; ============================================================
;;; Anthropic wire
;;; ============================================================

(deftest anthropic-neutral->wire
  (let [{:keys [system messages]} (ant/neutral->wire conversation)]
    (testing "system 提到顶层参数，不在 messages 里"
      (is (= "你是助手" system))
      (is (every? #(not= "system" (:role %)) messages)))
    (testing "user 普通消息"
      (is (= {:role "user" :content "北京天气?"} (first messages))))
    (testing "assistant tool_use 块"
      (let [a (nth messages 1)
            block (first (:content a))]
        (is (= "assistant" (:role a)))
        (is (= "tool_use" (:type block)))
        (is (= "c1" (:id block)))
        (is (= "get_weather" (:name block)))
        (is (= {:city "北京"} (:input block)))))
    (testing "tool 结果是 user + tool_result 块"
      (let [t (nth messages 2)
            block (first (:content t))]
        (is (= "user" (:role t)))
        (is (= "tool_result" (:type block)))
        (is (= "c1" (:tool_use_id block)))
        (is (= "晴 22°C" (:content block)))))))

(deftest anthropic-merges-consecutive-tool-results
  (testing "连续两条 tool 结果合并进同一条 user 消息"
    (let [conv [(msg/assistant-tool-calls [(msg/tool-call "a" "f1" {})
                                           (msg/tool-call "b" "f2" {})])
                (msg/tool-result "a" "f1" "r1")
                (msg/tool-result "b" "f2" "r2")]
          {:keys [messages]} (ant/neutral->wire conv)
          user-msg (last messages)]
      (is (= "user" (:role user-msg)))
      (is (= 2 (count (:content user-msg))))
      (is (= ["a" "b"] (mapv :tool_use_id (:content user-msg)))))))

(deftest anthropic-response->neutral
  (testing "纯文本"
    (is (= (msg/assistant "hi")
           (ant/response->neutral {:content [{:type "text" :text "hi"}]}))))
  (testing "tool_use"
    (let [resp {:content [{:type "tool_use" :id "c3" :name "get_weather" :input {:city "广州"}}]}
          n (ant/response->neutral resp)]
      (is (msg/has-tool-calls? n))
      (is (= [(msg/tool-call "c3" "get_weather" {:city "广州"})] (msg/tool-calls n))))))

;;; ============================================================
;;; 中立消息模块自身
;;; ============================================================

(deftest neutral-normalize-legacy
  (testing "legacy 字符串 role 规范化为 keyword"
    (is (= :user (:role (msg/normalize {:role "user" :content "x"}))))
    (is (= :tool (:role (msg/normalize {:role "tool" :tool_call_id "c1" :content "r"})))))
  (testing "legacy OpenAI tool 消息 :tool_call_id → :tool-call-id"
    (let [n (msg/normalize {:role "tool" :tool_call_id "c1" :content "r"})]
      (is (= "c1" (:tool-call-id n)))
      (is (not (contains? n :tool_call_id))))))
