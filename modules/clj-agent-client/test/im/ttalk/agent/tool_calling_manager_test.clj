(ns im.ttalk.agent.tool-calling-manager-test
  "ToolCallingManager 协议与 ChatClient 注入边界测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.filter :as filters]
            [im.ttalk.agent.context :as context]
             [im.ttalk.agent.chat-client :as chat-client]
            [im.ttalk.agent.tool-registry :as registry]
             [im.ttalk.agent.model.response :as response]
             [im.ttalk.agent.react :as react]
             [im.ttalk.agent.tool-calling-manager :as manager]))

(set! *warn-on-reflection* true)

(defn- inline-tool [name handler & [serial?]]
  (cond-> {:name name
           :description name
           :input_schema {:type "object" :properties {} :required []}
           :handler handler}
    serial? (assoc :serial true)))

(deftest manager-protocol-injection-test
  (testing "build-chat-client 缺省 manager 为 nil，注入值保持 identity"
    (let [seen (atom nil)
          canned {:messages [{:role :tool :content "canned"}]
                  :records [] :context {:mock true} :errors []}
          mock-manager
          (reify manager/ToolCallingManager
            (execute-tool-calls [_ k resp opts]
              (reset! seen {:chat-client k
                            :response resp
                            :calls (response/response-tool-calls resp)
                            :opts opts})
              canned))
          plain (chat-client/build-chat-client {})
          injected (chat-client/build-chat-client {:tool-manager mock-manager})
          calls [{:id "1" :name "noop" :args {:x 1}}]
          resp (response/make-response :tool-calls calls)
          opts {:gate nil :tool-context {} :records [] :on-tool-result nil}]
      (is (nil? (:tool-manager plain))
          "nil remains the compatibility path used before manager injection")
      (is (identical? mock-manager (:tool-manager injected)))
      (is (= canned (manager/execute-tool-calls
                      (:tool-manager injected) injected resp opts)))
      (is (identical? resp (:response @seen)))
      (is (= calls (:calls @seen)))
      (is (= opts (:opts @seen))))))

(deftest manager-boundary-contract-test
  (let [filter-trace (atom [])
        execution-trace (atom [])
        tool-filter (filters/create-filter
                      :boundary-filter
                      :tool (fn [req chain]
                              (swap! filter-trace conj (get-in req [:function :name]))
                              (chain req)))
        manager-trace (atom [])
        mock-manager
        (reify manager/ToolCallingManager
          (execute-tool-calls [_ k resp {:keys [tool-context records]}]
            (let [calls (response/response-tool-calls resp)]
              (swap! manager-trace conj (mapv :name calls))
              ;; A custom manager owns batch orchestration but continues to use the
              ;; chat-client primitives that preserve filter, serial, and writes contracts.
              (let [serial? (boolean (some #(registry/serial-tool? k (:name %)) calls))
                  invoke (fn [tc]
                           (let [{:keys [value writes]}
                                 (chat-client/invoke-tool k (:name tc) (:args tc) tool-context)]
                             {:tc tc :value value :writes writes}))
                  results (if serial?
                            (mapv invoke calls)
                            (mapv deref (mapv #(future (invoke %)) calls)))
                  folded (context/apply-writes tool-context (keep :writes results)
                                               (get-in k [:settings :state-slots]))]
              {:messages (mapv (fn [{:keys [tc value]}]
                                 {:tool-call-id (:id tc) :content value})
                               results)
               :records (into records
                              (map (fn [{:keys [tc value]}]
                                     {:name (keyword (:name tc))
                                      :args (:args tc)
                                      :result value}))
                              results)
               :context (:context folded)
               :errors []}))))
        mk-handler (fn [tag writes]
                     (fn [_ _]
                       (swap! execution-trace conj [tag :start])
                       (Thread/sleep 20)
                       (swap! execution-trace conj [tag :end])
                       {:result (name tag) :writes writes}))
        k (chat-client/build-chat-client
            {:tools [(inline-tool "serial-writer" (mk-handler :serial {:items "a"}) true)
                     (inline-tool "writer" (mk-handler :writer {:items "b"}))]
             :filters [tool-filter]
             :state-slots {:items {:init [] :reduce conj}}
             :tool-manager mock-manager})
        calls [{:id "1" :name "serial-writer" :args {}}
               {:id "2" :name "writer" :args {}}]
        resp (response/make-response :tool-calls calls)
        result (manager/execute-tool-calls (:tool-manager k) k resp
                                           {:gate nil :tool-context {} :records []
                                            :on-tool-result nil})]
    (testing ":tool filter remains around each chat-client tool invocation"
      (is (= [:serial-writer :writer] @filter-trace)))
    (testing ":serial declaration still degrades the custom batch to call order"
      (is (true? (registry/serial-tool? k "serial-writer")))
      (is (= [[:serial :start] [:serial :end]
              [:writer :start] [:writer :end]]
             @execution-trace)))
    (testing ":writes still fold at the barrier through ctx/apply-writes"
      (is (= ["a" "b"] (context/get-var (:context result) :items))))
    (is (= [["serial-writer" "writer"]] @manager-trace))))

(deftest multi-impl-switching-test
  (let [run-with
        (fn [tool-manager]
          (let [timestamps (atom {})
                handler (fn [tag]
                          (fn [_ _]
                            (swap! timestamps assoc-in [tag :start] (System/nanoTime))
                            (Thread/sleep 80)
                            (swap! timestamps assoc-in [tag :end] (System/nanoTime))
                            {:result (name tag)}))
                k (chat-client/build-chat-client
                    {:tools [(inline-tool "tool-a" (handler :a))
                             (inline-tool "tool-b" (handler :b))]
                     :tool-manager tool-manager})
                calls [{:id "a" :name "tool-a" :args {}}
                       {:id "b" :name "tool-b" :args {}}]
                resp (response/make-response :tool-calls calls)]
            (manager/execute-tool-calls tool-manager k resp
                                        {:gate nil :tool-context {} :records []
                                         :on-tool-result nil})
            @timestamps))
        virtual-times (run-with (react/virtual-thread-tool-calling-manager))
        sequential-times (run-with (react/sequential-tool-calling-manager))]
    (testing "virtual-thread implementation overlaps independent tools"
      (is (< (get-in virtual-times [:b :start])
             (get-in virtual-times [:a :end]))))
    (testing "sequential implementation preserves strict call order"
      (is (<= (get-in sequential-times [:a :end])
              (get-in sequential-times [:b :start]))))))

;;; ============================================================
;;; ThreadPoolToolCallingManager —— 有界池引擎
;;; ============================================================

(defn- peak-concurrency-run
  "跑一批 n 个互不相干的慢工具，返回 {:peak 观察到的最大并发 :result 批结果}。"
  [tool-manager n]
  (let [live    (atom 0)
        peak    (atom 0)
        handler (fn [_ _]
                  (swap! peak max (swap! live inc))
                  (Thread/sleep 60)
                  (swap! live dec)
                  {:result "ok"})
        k       (chat-client/build-chat-client
                  {:tools        (mapv #(inline-tool (str "t" %) handler) (range n))
                   :tool-manager tool-manager})
        calls   (mapv (fn [i] {:id (str i) :name (str "t" i) :args {}}) (range n))
        resp    (response/make-response :tool-calls calls)
        result  (manager/execute-tool-calls tool-manager k resp
                                            {:gate nil :tool-context {} :records []
                                             :on-tool-result nil})]
    (is (zero? @live) "every tool released before the barrier returned")
    {:peak @peak :result result}))

(deftest thread-pool-manager-bulkhead-test
  (testing "pool-size caps tool concurrency — the bulkhead the VT engine cannot give"
    (with-open [^java.io.Closeable m (react/thread-pool-tool-calling-manager {:pool-size 2})]
      (let [{:keys [peak result]} (peak-concurrency-run m 6)]
        (is (<= peak 2) "at most pool-size tools run at once")
        (is (= 2 peak) "and the pool is actually saturated (not accidentally serial)")
        (is (= 6 (count (:messages result))) "all six calls still complete")))
    (testing "the same batch on the unbounded VT engine has no such cap"
      (let [{:keys [peak]} (peak-concurrency-run
                             (react/virtual-thread-tool-calling-manager) 6)]
        (is (= 6 peak) "VT runs the whole batch at once — no isolation boundary")))))

(deftest thread-pool-manager-runs-on-its-own-threads-test
  (testing "tools execute on this instance's named pool threads, not the shared VT executor"
    (with-open [^java.io.Closeable m (react/thread-pool-tool-calling-manager
                                       {:pool-size 2 :thread-name-prefix "bulkhead-test-"})]
      (let [threads (atom #{})
            handler (fn [_ _]
                      (swap! threads conj (.getName (Thread/currentThread)))
                      (Thread/sleep 40)
                      {:result "ok"})
            k (chat-client/build-chat-client
                {:tools        [(inline-tool "a" handler) (inline-tool "b" handler)]
                 :tool-manager m})
            resp (response/make-response :tool-calls [{:id "1" :name "a" :args {}}
                                                      {:id "2" :name "b" :args {}}])]
        (manager/execute-tool-calls m k resp {:gate nil :tool-context {} :records []
                                              :on-tool-result nil})
        (is (= 2 (count @threads)) "both pool threads were used")
        (is (every? #(clojure.string/starts-with? % "bulkhead-test-") @threads))))))

(deftest thread-pool-manager-contract-parity-test
  (testing ":serial degrades the batch and :writes still fold — same as the other engines"
    (with-open [^java.io.Closeable m (react/thread-pool-tool-calling-manager {:pool-size 4})]
      (let [run (fn [tool-manager]
                  (let [trace   (atom [])
                        mk      (fn [tag]
                                  (fn [_ _]
                                    (swap! trace conj [tag :start])
                                    (Thread/sleep 20)
                                    (swap! trace conj [tag :end])
                                    {:result (name tag) :writes {:items (name tag)}}))
                        k (chat-client/build-chat-client
                            {:tools        [(inline-tool "serial-writer" (mk :serial) true)
                                            (inline-tool "writer" (mk :writer))]
                             :state-slots  {:items {:init [] :reduce conj}}
                             :tool-manager tool-manager})
                        resp (response/make-response
                               :tool-calls [{:id "1" :name "serial-writer" :args {}}
                                            {:id "2" :name "writer" :args {}}])
                        result (manager/execute-tool-calls
                                 tool-manager k resp
                                 {:gate nil :tool-context {} :records []
                                  :on-tool-result nil})]
                    {:trace @trace
                     :items (context/get-var (:context result) :items)
                     :names (mapv :name (:records result))}))
            pooled (run m)
            virtual (run (react/virtual-thread-tool-calling-manager))]
        (is (= [[:serial :start] [:serial :end] [:writer :start] [:writer :end]]
               (:trace pooled))
            ":serial still degrades the whole batch to call order on a bounded pool")
        (is (= ["serial" "writer"] (:items pooled))
            ":writes still fold at the barrier in call order")
        (is (= (dissoc virtual :trace) (dissoc pooled :trace))
            "engine choice does not change the observable batch result")))))

(deftest thread-pool-manager-lifecycle-test
  (let [m    (react/thread-pool-tool-calling-manager {:pool-size 1})
        k    (chat-client/build-chat-client {:tools        [(inline-tool "noop" (fn [_ _] {:result "ok"}))]
                                             :tool-manager m})
        resp (response/make-response :tool-calls [{:id "1" :name "noop" :args {}}])
        opts {:gate nil :tool-context {} :records [] :on-tool-result nil}]
    (testing "executes while the pool is open"
      (is (= 1 (count (:messages (manager/execute-tool-calls m k resp opts))))))
    (testing "the pool belongs to the record — shutdown! closes it"
      (react/shutdown-tool-calling-manager! m)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"线程池已关闭"
            (manager/execute-tool-calls m k resp opts))))
    (testing "engines that hold no resources make shutdown! a no-op"
      (is (nil? (react/shutdown-tool-calling-manager!
                  (react/sequential-tool-calling-manager))))
      (is (nil? (react/shutdown-tool-calling-manager!
                  (react/virtual-thread-tool-calling-manager)))))
    (testing "pool-size must be a positive integer"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pool-size"
            (react/thread-pool-tool-calling-manager {:pool-size 0}))))))
