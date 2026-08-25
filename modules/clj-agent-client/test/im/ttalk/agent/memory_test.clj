(ns im.ttalk.agent.memory-test
  "ChatMemory store + memory-filter 单测"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.memory :as mem]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.response :as resp]
            [im.ttalk.agent.filter.memory :as mf]
            [im.ttalk.agent.filter :as flt]))

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
;;; P3: memory filter（洋葱式 chat filter）
;;; ============================================================

(defn- run-filter
  "把 memory-filter 折进洋葱，terminal 返回固定 response 并记录它看到的 messages。
   返回 {:seen <terminal 看到的 messages> :out <链结果>}。"
  [store messages tc response]
  (let [seen (atom nil)
        terminal (fn [req]
                   (reset! seen (:messages req))
                   {:response response :context (:context req)})
        chain (flt/build-chain (keep :chat [(mf/memory-filter store)]) terminal)
        out (chain {:messages messages :context tc})]
    {:seen @seen :out out}))

(deftest memory-filter-prepends-history
  (let [store (mem/in-memory-store)
        tc {:conversation-id "s1"}
        晴 (resp/make-response :text "晴" :tool-calls nil)]
    (testing "首轮：terminal 看到 [user]，回复被存"
      (let [{:keys [seen]} (run-filter store [(msg/user "北京天气?")] tc 晴)]
        (is (= [(msg/user "北京天气?")] seen))))
    (testing "次轮：只传 delta，terminal 看到完整历史（含上一轮回复）"
      (let [{:keys [seen]} (run-filter store [(msg/user "明天呢?")] tc 晴)]
        (is (= [(msg/user "北京天气?") (msg/assistant "晴") (msg/user "明天呢?")] seen))))))

(deftest memory-filter-stores-reply
  (let [store (mem/in-memory-store)]
    (run-filter store [(msg/user "hi")] {:conversation-id "s2"}
                 (resp/make-response :text "你好" :tool-calls nil))
    (is (= [(msg/user "hi") (msg/assistant "你好")] (mem/mem-get store "s2")))))

(deftest memory-filter-stores-tool-calls
  (let [store (mem/in-memory-store)
        response (resp/make-response
                   :text nil
                   :tool-calls [{:id "c1" :name "get_weather" :args {:city "北京"}}])]
    (run-filter store [(msg/user "天气")] {:conversation-id "s3"} response)
    (let [stored (last (mem/mem-get store "s3"))]
      (is (msg/has-tool-calls? stored))
      (is (= [(msg/tool-call "c1" "get_weather" {:city "北京"})]
             (msg/tool-calls stored))))))

(deftest memory-filter-noop-without-conv-id
  (let [store (mem/in-memory-store)
        {:keys [seen]} (run-filter store [(msg/user "x")] {}
                                    (resp/make-response :text "x" :tool-calls nil))]
    (testing "无 conv-id：messages 原样、store 不写"
      (is (= [(msg/user "x")] seen))
      (is (= [] (mem/mem-get store "any"))))))
