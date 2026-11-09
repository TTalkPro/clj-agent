(ns im.ttalk.agent.compaction-test
  "R-01 摘要式上下文压缩（designs/runtime-borrow.md §1）。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.async :as async]
            [im.ttalk.agent.chat-client :as cc]
            [im.ttalk.agent.filter.compaction :as cp]
            [im.ttalk.agent.filter.memory :as mf]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.response :as response]))

(deftest threshold-test
  (is (= 75000 (cp/threshold {:context-window 100000 :trigger-ratio 0.5})) "小窗口比例抬到 0.75")
  (is (= 500000 (cp/threshold {:context-window 1000000 :trigger-ratio 0.5})))
  (is (= 64000 (cp/threshold {:context-window 70000 :trigger-ratio 0.5})) "地板")
  (is (= 51000 (cp/threshold {:context-window 60000 :trigger-ratio 0.5})) "地板顶出窗口 → 0.85")
  (is (= 1 (cp/threshold {:context-window 0}))))

(def call-msg (msg/assistant-tool-calls [(msg/tool-call "c1" "t" {})]))

(deftest safe-cut-test
  (let [ms [(msg/user "q") call-msg (msg/tool-result "c1" "t" "r") (msg/assistant "a")]]
    (is (cp/safe-cut? ms 1))
    (is (not (cp/safe-cut? ms 2)) "劈开调用与结果")
    (is (cp/safe-cut? ms 3))
    (testing "find-safe-cut 不落在禁止位"
      (let [c (cp/find-safe-cut ms 0 {:keep-min-tokens 1 :keep-min-text 1 :keep-max-tokens 1000})]
        (is (cp/safe-cut? ms c))))))

(deftest micro-compact-test
  (let [ms (vec (concat [(msg/user "q")]
                        (mapcat (fn [i] [(msg/assistant-tool-calls [(msg/tool-call (str "c" i) "t" {})])
                                         (msg/tool-result (str "c" i) "t" (apply str (repeat 300 "x")))])
                                (range 4))))
        out (cp/micro-compact ms 2 100)]
    (is (= cp/cleared-marker (:content (nth out 2))))
    (is (= cp/cleared-marker (:content (nth out 4))))
    (is (<= (count (:content (nth out 8))) 100))
    (is (= (count ms) (count out)) "位置不变、配对不动")
    (is (= out (cp/micro-compact out 2 100)) "幂等")))

;;; ------------------------------------------------------------ filter

(def small-budget {:context-window 2000 :keep-min-tokens 50 :keep-min-text 1 :keep-max-tokens 400
                   :micro? false})

(defn- seed! [store cid n]
  (memory/mem-add store cid
                  (vec (mapcat (fn [i] [(msg/user (str "问题" i " " (apply str (repeat 400 "q"))))
                                        (msg/assistant (str "回答" i " " (apply str (repeat 400 "a"))))])
                               (range n)))))

(defn- client [store sent filter-opts & [chat-fn]]
  (cc/build-chat-client
   {:chat-model {:chat-fn (or chat-fn (fn [ms _] (swap! sent conj (vec ms)) (response/make-response :text "ok")))}
    :filters [(mf/memory-filter store) (apply cp/compaction-filter (mapcat identity filter-opts))]}))

(defn- ask [c cid text] (cc/invoke-chat c [(msg/user text)] {:context {:conversation-id cid}}))

(deftest summary-and-cache-test
  (let [store (memory/in-memory-store) sent (atom []) summaries (atom 0) events (atom [])
        c (client store sent {:budget small-budget
                              :summarizer (fn [t] (swap! summaries inc) (is (str/includes? t "问题0")) "早先聊了问题0到问题9")
                              :on-compaction #(swap! events conj (:kind %))})]
    (seed! store "s-1" 10)
    (ask c "s-1" "新问题")
    (testing "超阈值 → 前缀换成摘要，尾部保留最近的"
      (let [ms (first @sent)]
        (is (cp/summary-message? (first ms)))
        (is (str/includes? (:content (first ms)) "早先聊了"))
        (is (= "新问题" (:content (peek ms))))
        (is (< (count ms) 22))))
    (testing "store 缺省不被改写"
      (is (= 22 (count (memory/mem-get store "s-1")))))
    (testing "第二轮命中缓存：不再调摘要器，视图仍以摘要开头"
      (ask c "s-1" "再问")
      (is (= 1 @summaries))
      (is (cp/summary-message? (first (second @sent)))))
    (is (= [:summary] @events))))

(deftest summary-failure-test
  (let [store (memory/in-memory-store) sent (atom []) calls (atom 0)
        c (client store sent {:budget small-budget
                              :summarizer (fn [_] (swap! calls inc) (throw (ex-info "摘要器挂了" {})))})]
    (seed! store "f-1" 10)
    (testing "摘要失败 → 丢最老，仍在阈值内"
      (ask c "f-1" "q1")
      (is (not-any? cp/summary-message? (first @sent)))
      (is (< (count (first @sent)) 22)))
    (testing "连续 3 次失败后停用摘要器（仍丢最老）"
      (ask c "f-1" "q2")
      (ask c "f-1" "q3")
      (ask c "f-1" "q4")
      (is (= 3 @calls))
      (is (< (count (last @sent)) 27)))))

(deftest no-summarizer-drops-test
  (let [store (memory/in-memory-store) sent (atom [])
        c (client store sent {:budget small-budget})]
    (seed! store "d-1" 10)
    (ask c "d-1" "q")
    (is (= "q" (:content (peek (first @sent)))))
    (is (< (count (first @sent)) 22))))

(defn- overflow-ex [] (ex-info "prompt is too long" {:type :context-overflow-error :retryable? false}))

(deftest overflow-recovery-test
  (testing "provider 报超长 → 压缩后重发一次"
    (let [store (memory/in-memory-store) sizes (atom []) kinds (atom [])
          c (client store nil {:budget {:context-window 1000000 :keep-min-tokens 50 :keep-min-text 1}
                               :summarizer (constantly "摘要")
                               :on-compaction #(swap! kinds conj (:kind %))}
                    (fn [ms _] (swap! sizes conj (count ms))
                      (if (> (count ms) 10) (throw (overflow-ex)) (response/make-response :text "ok"))))]
      (seed! store "o-1" 10)
      (is (= "ok" (response/response-text (:response (ask c "o-1" "q")))))
      (is (= 2 (count @sizes)))
      (is (> (first @sizes) 10))
      (is (<= (second @sizes) 10))
      (is (= [:summary :overflow-recovery] @kinds))))
  (testing "压不动 → 原错照抛"
    (let [store (memory/in-memory-store)
          c (client store nil {:summarizer (constantly "摘要")} (fn [_ _] (throw (overflow-ex))))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"too long" (ask c "o-2" "q")))))
  (testing "非超长错误不处理"
    (let [n (atom 0)
          c (client (memory/in-memory-store) nil {} (fn [_ _] (swap! n inc) (throw (ex-info "401" {:type :auth-error}))))]
      (is (thrown? clojure.lang.ExceptionInfo (ask c "o-3" "q")))
      (is (= 1 @n))))
  (testing ":context-window-exceeded 响应同样恢复"
    (let [store (memory/in-memory-store) n (atom 0)
          c (client store nil {:budget {:context-window 1000000 :micro? false :keep-min-tokens 50 :keep-min-text 1}
                                  :summarizer (constantly "摘要")}
                    (fn [ms _] (swap! n inc)
                      (if (> (count ms) 10)
                        (response/make-response :finish-reason "model_context_window_exceeded")
                        (response/make-response :text "ok"))))]
      (seed! store "o-4" 10)
      (is (= "ok" (response/response-text (:response (ask c "o-4" "q")))))
      (is (= 2 @n))))
  (testing "异步路径（invoke-chat-async）同样恢复"
    (let [store (memory/in-memory-store) n (atom 0)
          c (client store nil {:budget {:context-window 1000000 :keep-min-tokens 50 :keep-min-text 1}
                                  :summarizer (constantly "摘要")}
                    (fn [ms _] (swap! n inc)
                      (if (> (count ms) 10) (throw (overflow-ex)) (response/make-response :text "ok"))))]
      (seed! store "o-5" 10)
      (let [r (async/join (cc/invoke-chat-async c [(msg/user "q")] {:context {:conversation-id "o-5"}}))]
        (is (= "ok" (response/response-text (:response r))))
        (is (= 2 @n))))))

(deftest write-back-test
  (let [store (memory/in-memory-store) sent (atom [])
        c (client store sent {:budget small-budget :summarizer (constantly "摘要X") :write-back-store store})]
    (seed! store "w-1" 10)
    (let [ids-before (set (map :id (memory/mem-get store "w-1")))]
      (ask c "w-1" "q")
      (let [h (memory/mem-get store "w-1")]
        (is (cp/summary-message? (first h)) "store 被改写成摘要开头")
        (is (< (count h) 22))
        (is (contains? ids-before (:id (second h))) "保留下来的消息 id 不变")
        (is (= "ok" (:content (last h))) "随后的响应照常落库")))))

;;; ------------------------------------------------------------ 可替换挂点（:store / :write-back）

(defn- durable-store
  "模拟外部持久化实现：状态活在 filter 实例之外（重启 = 新建 filter 实例）。"
  [data]
  (reify cp/CompactionStore
    (cs-get [_ cid] (get @data cid))
    (cs-put! [_ cid st] (swap! data assoc cid st) nil)
    (cs-delete! [_ cid] (swap! data dissoc cid) nil)))

(deftest pluggable-store-test
  (let [store (memory/in-memory-store) data (atom {}) summaries (atom 0)
        opts {:budget small-budget :store (durable-store data)
              :summarizer (fn [_] (swap! summaries inc) "早先的摘要")}]
    (seed! store "p-1" 10)
    (ask (client store (atom []) opts) "p-1" "新问题")
    (is (= 1 @summaries))
    (testing "存进外部存储的是可持久部分（EDN 可往返）"
      (let [st (get @data "p-1")]
        (is (= #{:cache :failures} (set (keys st))))
        (is (= st (read-string (pr-str st))))))
    (testing "「重启」后（新 filter 实例、同一存储）命中缓存，不再调摘要器"
      (let [sent (atom [])]
        (ask (client store sent opts) "p-1" "再问")
        (is (= 1 @summaries))
        (is (cp/summary-message? (first (first @sent))))))
    (testing "ChatMemory 缺省仍不改写"
      (is (= 24 (count (memory/mem-get store "p-1")))))))

(deftest failing-store-degrades-test
  (let [store (memory/in-memory-store) sent (atom [])
        boom (reify cp/CompactionStore
               (cs-get [_ _] (throw (RuntimeException. "db down")))
               (cs-put! [_ _ _] (throw (RuntimeException. "db down")))
               (cs-delete! [_ _] nil))
        c (client store sent {:budget small-budget :store boom :summarizer (constantly "摘要")})]
    (seed! store "b-1" 10)
    (let [r (ask c "b-1" "q")]
      (is (= "ok" (response/response-text (:response r))) "存储坏了照样压缩、照样回答")
      (is (cp/summary-message? (first (first @sent)))))))

(deftest pluggable-write-back-test
  (let [store (memory/in-memory-store) sent (atom []) archived (atom nil)
        c (client store sent {:budget small-budget :summarizer (constantly "摘要Y")
                              ;; 自定义策略：原文归档到别处，再改写 ChatMemory
                              :write-back (fn [cid compacted original]
                                            (reset! archived [cid (count original)])
                                            ((cp/store-write-back store) cid compacted compacted))})]
    (seed! store "wb-1" 10)
    (ask c "wb-1" "q")
    (is (= ["wb-1" 21] @archived) "策略拿到会话 id 与压缩前的完整历史")
    (is (cp/summary-message? (first (memory/mem-get store "wb-1"))))))
