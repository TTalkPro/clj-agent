(ns im.ttalk.agent.timeline-sqlite-test
  "Timeline SQLite store：EDN 往返、跨实例持久化（模拟重启）、meta 持久化。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.timeline :as tl]
            [im.ttalk.agent.timeline.sqlite :as tls])
  (:import [java.io File]))

(defn- temp-db []
  (let [f (File/createTempFile "clj-agent-timeline" ".db")]
    (.deleteOnExit f)
    (.getAbsolutePath f)))

(deftest edn-roundtrip-test
  (with-open [store ^java.io.Closeable (tls/sqlite-store ":memory:")]
    (let [m (tl/manager store)
          data {:status :paused
                :step-states {:step-a {:state {:count 3} :activation-count 3}}
                :context {:user-id "u1" :nested {:k [1 2 :three]}}}
          e (tl/save! m "s1" data {:metadata {:checkpoint-reason :paused}})]
      (testing "复杂 EDN（keyword/嵌套/向量）无损往返"
        (is (= data (:data (tl/load-by-id m "s1" (:id e)))))
        (is (= :paused (:checkpoint-reason (tl/load-latest m "s1"))))))))

(deftest persistence-across-instances-test
  (let [path (temp-db)]
    ;; 第一个 store 实例：存 3 条 + 开分支 + 回退位置
    (with-open [s1 ^java.io.Closeable (tls/sqlite-store path)]
      (let [m (tl/manager s1)
            e1 (tl/save! m "s1" {:v 1})
            _  (tl/save! m "s1" {:v 2})
            _  (tl/save! m "s1" {:v 3})]
        (tl/create-branch! m "s1" (:id e1) "exp")
        (tl/go-back! m "s1" 1)))
    ;; 重开（= 进程重启）：entry、位置、分支登记全在
    (with-open [s2 ^java.io.Closeable (tls/sqlite-store path)]
      (let [m (tl/manager s2)]
        (testing "entry 链持久化"
          (is (= [1 2 3] (map (comp :v :data) (tl/list-entries m "s1")))))
        (testing "位置持久化（重启前回退到 v2）"
          (is (= {:v 2} (:data (tl/get-position-entry m "s1")))))
        (testing "分支登记持久化"
          (is (= #{"main" "exp"} (set (map :branch-id (tl/list-branches m "s1"))))))
        (testing "重启后可继续时间旅行"
          (is (= {:v 3} (:data (tl/go-forward! m "s1" 1)))))))))

(deftest owner-isolation-test
  (with-open [store ^java.io.Closeable (tls/sqlite-store ":memory:")]
    (let [m (tl/manager store)]
      (tl/save! m "a" {:who :a})
      (tl/save! m "b" {:who :b})
      (is (= :a (-> (tl/load-latest m "a") :data :who)))
      (is (= :b (-> (tl/load-latest m "b") :data :who)))
      (is (= 1 (count (tl/list-entries m "a")))))))
