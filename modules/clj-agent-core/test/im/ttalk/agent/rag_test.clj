(ns im.ttalk.agent.rag-test
  "检索增强 turn filter 测试（对标 Spring QuestionAnswerAdvisor）"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string]
            [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.filter.rag :as rag]))

(defn- fake-retriever
  "记录 query/top-k，返回预置文档。"
  [docs & [log]]
  (reify rag/IRetriever
    (retrieve [_ query top-k]
      (when log (swap! log conj [query top-k]))
      (take top-k docs))))

(def ^:private ok-turn
  (fn [_] {:status :completed :response {:text "答案"}}))

(defn- run
  [f terminal req]
  ((flt/build-chain [(:turn f)] terminal) req))

(defn- seen-messages
  "跑一次，返回终端（工具循环）实际收到的 :messages。"
  [f req]
  (let [seen (atom nil)]
    (run f (fn [r] (reset! seen (:messages r)) (ok-turn r)) req)
    @seen))

;;; ============================================================
;;; 注入
;;; ============================================================

(deftest qa-injects-context-test
  (let [f (rag/qa-turn-filter (fake-retriever [{:text "巴黎是法国首都"}
                                               {:text "法国在欧洲"}]))
        content (:content (first (seen-messages f {:messages [{:role :user :content "法国首都?"}]})))]

    (testing "原问题保留在最前"
      (is (clojure.string/starts-with? content "法国首都?")))

    (testing "检索到的文档被拼进去"
      (is (clojure.string/includes? content "巴黎是法国首都"))
      (is (clojure.string/includes? content "法国在欧洲")))

    (testing "带 grounding 指令（不许用上下文外的知识）"
      (is (clojure.string/includes? content "仅")))))

(deftest qa-passes-query-and-topk-test
  (testing "用户问题原样作 query；:top-k 传给 retriever 并截断结果"
    (let [log (atom [])
          f (rag/qa-turn-filter (fake-retriever [{:text "d1"} {:text "d2"} {:text "d3"}] log)
                                :top-k 2)
          content (:content (first (seen-messages f {:messages [{:role :user :content "问题"}]})))]
      (is (= [["问题" 2]] @log))
      (is (clojure.string/includes? content "d2"))
      (is (not (clojure.string/includes? content "d3")) "top-k 之外的文档不进 prompt"))))

(deftest qa-injects-once-per-turn-test
  (testing "每 turn 恰好检索一次——:turn 挂点的全部理由（:chat 会每轮重检索）"
    (let [log (atom [])
          f (rag/qa-turn-filter (fake-retriever [{:text "d"}] log))
          ;; 终端模拟一个跑了多轮 LLM 的工具循环
          terminal (fn [_] (ok-turn nil))]
      (run f terminal {:messages [{:role :user :content "问题"}]})
      (is (= 1 (count @log))))))

;;; ============================================================
;;; 不注入的情形
;;; ============================================================

(deftest qa-empty-retrieval-test
  (testing "检索为空 → 不注入（刻意偏离 Spring：注入空上下文会让模型拒答一切）"
    (let [f (rag/qa-turn-filter (fake-retriever []))
          ms (seen-messages f {:messages [{:role :user :content "问题"}]})]
      (is (= [{:role :user :content "问题"}] ms))))

  (testing "全是空白文本的文档也视为空"
    (let [f (rag/qa-turn-filter (fake-retriever [{:text "  "} {:text nil}]))
          ms (seen-messages f {:messages [{:role :user :content "问题"}]})]
      (is (= [{:role :user :content "问题"}] ms))))

  (testing ":inject-when-empty? true → 恢复 Spring 的严格 grounding"
    (let [f (rag/qa-turn-filter (fake-retriever []) :inject-when-empty? true)
          content (:content (first (seen-messages f {:messages [{:role :user :content "问题"}]})))]
      (is (clojure.string/includes? content "无法回答")))))

(deftest qa-resume-skips-test
  (testing ":resume? → 不检索不改写（延续场景没有入口消息）"
    (let [log (atom [])
          f (rag/qa-turn-filter (fake-retriever [{:text "d"}] log))]
      (run f ok-turn {:resume? true :messages nil})
      (is (empty? @log) "resume 不该触发检索"))))

(deftest qa-no-user-message-test
  (testing "无 user 消息 → 不插手"
    (let [f (rag/qa-turn-filter (fake-retriever [{:text "d"}]))
          ms (seen-messages f {:messages [{:role :system :content "sys"}]})]
      (is (= [{:role :system :content "sys"}] ms))))

  (testing "多模态 content 不改写（宁可不插手也不丢图片片段）"
    (let [f (rag/qa-turn-filter (fake-retriever [{:text "d"}]))
          orig [{:role :user :content [{:type "text" :text "看图"}]}]
          ms (seen-messages f {:messages orig})]
      (is (= orig ms)))))

;;; ============================================================
;;; 定位与定制
;;; ============================================================

(deftest qa-rewrites-last-user-message-test
  (testing "只改写最后一条 user 消息，其余原样"
    (let [f (rag/qa-turn-filter (fake-retriever [{:text "D"}]))
          ms (seen-messages f {:messages [{:role :system :content "sys"}
                                          {:role :user :content "旧问题"}
                                          {:role :user :content "新问题"}]})]
      (is (= {:role :system :content "sys"} (nth ms 0)))
      (is (= {:role :user :content "旧问题"} (nth ms 1)))
      (is (clojure.string/starts-with? (:content (nth ms 2)) "新问题"))
      (is (clojure.string/includes? (:content (nth ms 2)) "D")))))

(deftest qa-custom-template-test
  (testing "自定义模板"
    (let [f (rag/qa-turn-filter (fake-retriever [{:text "D"}])
                                :template (fn [q c] (str c "||" q)))
          ms (seen-messages f {:messages [{:role :user :content "Q"}]})]
      (is (= "D||Q" (:content (first ms)))))))

(deftest qa-requires-retriever-test
  (testing "传入非 IRetriever 直接报错"
    (is (thrown? clojure.lang.ExceptionInfo (rag/qa-turn-filter {})))))
