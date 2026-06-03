(ns im.ttalk.agent.core.memory-test
  "P2 ChatMemory store + P3 Memory Filter 单测"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.core.memory :as mem]
            [im.ttalk.agent.core.llm.message :as msg]
            [im.ttalk.agent.core.llm.response :as resp]
            [im.ttalk.agent.core.kernel.memory-filter :as mf]
            [im.ttalk.agent.core.kernel.filter :as flt]))

;;; ============================================================
;;; P2: store
;;; ============================================================

(deftest in-memory-basic
  (let [s (mem/in-memory-store)]
    (testing "空会话返回 []"
      (is (= [] (mem/mem-get s "c1"))))
    (testing "add/get 按会话隔离"
      (mem/mem-add s "c1" [(msg/user "a")])
      (mem/mem-add s "c1" [(msg/assistant "b")])
      (mem/mem-add s "c2" [(msg/user "x")])
      (is (= [(msg/user "a") (msg/assistant "b")] (mem/mem-get s "c1")))
      (is (= [(msg/user "x")] (mem/mem-get s "c2"))))
    (testing "clear"
      (mem/mem-clear s "c1")
      (is (= [] (mem/mem-get s "c1")))
      (is (= [(msg/user "x")] (mem/mem-get s "c2"))))))

(deftest in-memory-normalizes-legacy
  (let [s (mem/in-memory-store)]
    (mem/mem-add s "c" [{:role "user" :content "hi"}])
    (is (= :user (:role (first (mem/mem-get s "c")))))))

(deftest windowed-keeps-tail
  (let [s (mem/windowed (mem/in-memory-store) {:max-messages 2})]
    (mem/mem-add s "c" [(msg/user "1") (msg/assistant "2") (msg/user "3")])
    (testing "只返回尾部 2 条，底层仍完整"
      (is (= [(msg/assistant "2") (msg/user "3")] (mem/mem-get s "c"))))))

(deftest windowed-preserves-system-and-drops-orphan-tool
  (let [s (mem/windowed (mem/in-memory-store) {:max-messages 1})]
    (mem/mem-add s "c"
      [(msg/system "sys")
       (msg/assistant-tool-calls [(msg/tool-call "t1" "f" {})])
       (msg/tool-result "t1" "f" "r")])
    (let [out (mem/mem-get s "c")]
      (testing "system 始终保留在最前"
        (is (msg/system? (first out))))
      (testing "窗口头部孤立 tool 被丢弃（其 assistant 被裁掉）"
        (is (not-any? msg/tool? out))))))

;;; ============================================================
;;; P3: memory filter
;;; ============================================================

(defn- run-pre [filters messages tool-context]
  ;; 模拟 invoke-chat 里 pre-chat 管道：context 即 ToolContext 扁平 map
  (flt/apply-pre-chat-filters filters messages tool-context))

(defn- run-post [filters response tool-context]
  (flt/apply-post-chat-filters filters response tool-context))

(deftest memory-filter-pre-stores-and-prepends
  (let [store (mem/in-memory-store)
        [pre _] (mf/memory-filters store)
        tc {:conversation-id "s1"}]
    (testing "首轮：存 user，messages 变为完整历史"
      (let [r1 (run-pre [pre] [(msg/user "北京天气?")] tc)]
        (is (= [(msg/user "北京天气?")] (get-in r1 [:ok :messages])))))
    (testing "次轮：只传 delta，pre 拼出完整历史"
      ;; 先模拟上一轮 assistant 已被 post 存入
      (mem/mem-add store "s1" [(msg/assistant "晴")])
      (let [r2 (run-pre [pre] [(msg/user "明天呢?")] tc)
            msgs (get-in r2 [:ok :messages])]
        (is (= 3 (count msgs)))
        (is (= [(msg/user "北京天气?") (msg/assistant "晴") (msg/user "明天呢?")] msgs))))))

(deftest memory-filter-post-stores-assistant
  (let [store (mem/in-memory-store)
        [_ post] (mf/memory-filters store)
        tc {:conversation-id "s2"}
        response (resp/make-response :text "你好" :tool-calls nil)]
    (run-post [post] response tc)
    (is (= [(msg/assistant "你好")] (mem/mem-get store "s2")))))

(deftest memory-filter-post-stores-tool-calls
  (let [store (mem/in-memory-store)
        [_ post] (mf/memory-filters store)
        tc {:conversation-id "s3"}
        response (resp/make-response
                   :text nil
                   :tool-calls [{:id "c1" :name :get_weather :input {:city "北京"}}])]
    (run-post [post] response tc)
    (let [stored (first (mem/mem-get store "s3"))]
      (is (msg/has-tool-calls? stored))
      (is (= [(msg/tool-call "c1" "get_weather" {:city "北京"})]
             (msg/tool-calls stored))))))

(deftest memory-filter-noop-without-conv-id
  (let [store (mem/in-memory-store)
        [pre _] (mf/memory-filters store)
        r (run-pre [pre] [(msg/user "x")] {})]   ;; 无 conversation-id
    (testing "无 conv-id：messages 原样、store 不写"
      (is (= [(msg/user "x")] (get-in r [:ok :messages]))))))
