(ns im.ttalk.agent.agui.input-validation-test
  "`codec/run-input-problem` 与 `/info.threadEndpoints` 逐格报
   （designs/agui-borrow.md §7，tasks/G-07.md）。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.agui.codec :as codec]))

(deftest run-input-problem-test
  (testing "非对象 → bad_json"
    (is (= "bad_json" (:error (codec/run-input-problem nil))))
    (is (= "bad_json" (:error (codec/run-input-problem [1 2]))))
    (is (= "bad_json" (:error (codec/run-input-problem "x")))))
  (testing "threadId 缺 / 空白 / 非字符串 → missing_thread_id"
    (is (= "missing_thread_id" (:error (codec/run-input-problem {}))))
    (is (= "missing_thread_id" (:error (codec/run-input-problem {:threadId "  "}))))
    (is (= "missing_thread_id" (:error (codec/run-input-problem {:threadId 42})))))
  (testing "runId 只在 :require-run-id? 时要"
    (is (nil? (codec/run-input-problem {:threadId "t1"})))
    (is (= "missing_run_id" (:error (codec/run-input-problem {:threadId "t1"} {:require-run-id? true}))))
    (is (nil? (codec/run-input-problem {:threadId "t1" :runId "r1"} {:require-run-id? true}))))
  (testing "每条都带给人看的 message"
    (is (string? (:message (codec/run-input-problem {}))))))

(deftest thread-endpoints-test
  (testing ":thread-endpoints 逐格覆盖 :threads?；realtimeMetadata 恒 false"
    (is (= {:list true :inspect false :mutations false :realtimeMetadata false}
           (:threadEndpoints (codec/run-info ["default"] {:threads? true
                                                          :thread-endpoints {:list true}})))))
  (testing "不给 :thread-endpoints 时与原来一样（三格同 :threads?）"
    (is (= {:list true :inspect true :mutations true :realtimeMetadata false}
           (:threadEndpoints (codec/run-info ["default"] {:threads? true}))))
    (is (= {:list false :inspect false :mutations false :realtimeMetadata false}
           (:threadEndpoints (codec/run-info ["default"]))))))
