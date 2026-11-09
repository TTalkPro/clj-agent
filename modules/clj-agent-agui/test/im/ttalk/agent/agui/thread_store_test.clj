(ns im.ttalk.agent.agui.thread-store-test
  "线程元数据存储（designs/agui-borrow.md §6，tasks/G-06.md）。

   **两个实现跑同一组用例**：列表的过滤 / 排序 / 分页语义在 in-memory 里是一个
   纯函数，在 sqlite 里是一句 SQL——两边必须逐字一致，否则换个后端侧栏顺序就变。"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.agui.thread-store :as ts]
            [im.ttalk.agent.agui.thread-store.sqlite :as ts-sqlite]))

(defn- clock []
  (let [t (atom 1000)]
    {:now (fn [] @t) :advance! (fn [] (swap! t + 10))}))

(defn- contract!
  "对一个实现跑完整契约。`mk` = `(fn [now-fn] store)`。"
  [label mk]
  (testing label
    (let [{:keys [now advance!]} (clock)
          s (mk now)]
      (testing "slots"
        (is (= ts/all-slots (ts/ts-slots s)))
        (is (ts/has-slot? s :list))
        (is (not (ts/has-slot? nil :list))))

      (testing "touch 建 / 刷新；agent-id 只在建时写"
        (let [r (ts/ts-touch! s "t1" {:agent-id "default"})]
          (is (= {:id "t1" :agent-id "default" :name nil :archived? false
                  :created-at 1000 :updated-at 1000} r)))
        (advance!)
        (let [r (ts/ts-touch! s "t1" {:agent-id "ops"})]
          (is (= "default" (:agent-id r)) "线程归属不随后来的请求漂移")
          (is (= 1000 (:created-at r)))
          (is (= 1010 (:updated-at r))))
        (is (nil? (ts/ts-get s "nope"))))

      (testing "列表：新的在前、缺省不列归档、agent 过滤、翻页"
        (advance!) (ts/ts-touch! s "t2" {:agent-id "ops"})       ;; 1020
        (advance!) (ts/ts-touch! s "t3" {:agent-id "default"})   ;; 1030
        (advance!) (ts/ts-touch! s "t4" {:agent-id "default"})   ;; 1040
        (is (= ["t4" "t3" "t2" "t1"] (mapv :id (:threads (ts/ts-list s {})))))
        (is (nil? (:next-cursor (ts/ts-list s {}))))
        (advance!)
        (is (= true (:archived? (ts/ts-update! s "t3" {:archived? true}))))
        (is (= ["t4" "t2" "t1"] (mapv :id (:threads (ts/ts-list s {})))) "归档的缺省隐藏")
        (is (= ["t3" "t4" "t2" "t1"] (mapv :id (:threads (ts/ts-list s {:include-archived? "true"}))))
            "include-archived 认串 \"true\"；归档那下刷了 updated-at 所以排最前")
        (is (= ["t4" "t1"] (mapv :id (:threads (ts/ts-list s {:agent-id "default"})))))
        (let [p1 (ts/ts-list s {:limit "2"})
              p2 (ts/ts-list s {:limit "2" :cursor (:next-cursor p1)})]
          (is (= ["t4" "t2"] (mapv :id (:threads p1))))
          (is (= "2" (:next-cursor p1)))
          (is (= ["t1"] (mapv :id (:threads p2))))
          (is (nil? (:next-cursor p2)) "翻到底 next-cursor 为 nil"))
        (is (= 3 (count (:threads (ts/ts-list s {:limit "abc"})))) "认不出的 limit = 不限")
        (is (= 3 (count (:threads (ts/ts-list s {:limit 0}))))))

      (testing "update：只动给了的键；没这条 → nil"
        (let [r (ts/ts-update! s "t1" {:name "我的线程"})]
          (is (= "我的线程" (:name r)))
          (is (false? (:archived? r))))
        (is (= "我的线程" (:name (ts/ts-update! s "t1" {:archived? false}))))
        (is (nil? (:name (ts/ts-update! s "t1" {:name nil}))))
        (is (nil? (ts/ts-update! s "nope" {:name "x"}))))

      (testing "delete"
        (is (true? (ts/ts-delete! s "t2")))
        (is (false? (ts/ts-delete! s "t2")))
        (is (nil? (ts/ts-get s "t2"))))
      s)))

(deftest in-memory-contract-test
  (contract! "in-memory" (fn [now] (ts/in-memory-store {:now now}))))

(deftest sqlite-contract-test
  (let [s (contract! "sqlite :memory:" (fn [now] (ts-sqlite/sqlite-thread-store ":memory:" {:now now})))]
    (.close ^java.io.Closeable s)))

(deftest sqlite-survives-reopen-test
  (testing "关掉重开仍在——「进程重启后侧栏照样在」（驱逐 / 重启后可见）"
    (let [f (java.io.File/createTempFile "agui-threads" ".db")
          path (.getAbsolutePath f)]
      (try
        (with-open [s (ts-sqlite/sqlite-thread-store path)]
          (ts/ts-touch! s "t1" {:agent-id "default"})
          (ts/ts-update! s "t1" {:name "昨天的对话"}))
        (with-open [s (ts-sqlite/sqlite-thread-store path)]
          (is (= "昨天的对话" (:name (ts/ts-get s "t1"))))
          (is (= ["t1"] (mapv :id (:threads (ts/ts-list s {}))))))
        (finally (io/delete-file f true))))))

(deftest normalize-query-test
  (is (= {:agent-id nil :include-archived? false :limit nil :offset 0} (ts/normalize-query {})))
  (is (= {:agent-id "a" :include-archived? true :limit 5 :offset 10}
         (ts/normalize-query {:agent-id "a" :include-archived? "TRUE" :limit "5" :cursor "10"})))
  (is (= 0 (:offset (ts/normalize-query {:cursor "-3"}))) "认不出的 cursor 从头来"))
