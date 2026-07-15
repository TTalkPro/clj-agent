(ns im.ttalk.agent.tool-calling-manager-test
  "ToolCallingManager 协议与 Kernel 注入边界测试。"
  (:require [clojure.test :refer [deftest is testing]]
            [im.ttalk.agent.advisor :as filters]
            [im.ttalk.agent.context :as context]
             [im.ttalk.agent.kernel :as kernel]
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
  (testing "build-kernel 缺省 manager 为 nil，注入值保持 identity"
    (let [seen (atom nil)
          canned {:messages [{:role :tool :content "canned"}]
                  :records [] :context {:mock true} :errors []}
          mock-manager
          (reify manager/ToolCallingManager
            (execute-tool-calls [_ k resp opts]
              (reset! seen {:kernel k
                            :response resp
                            :calls (response/response-tool-calls resp)
                            :opts opts})
              canned))
          plain (kernel/build-kernel {})
          injected (kernel/build-kernel {:tool-manager mock-manager})
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
              ;; kernel primitives that preserve filter, serial, and writes contracts.
              (let [serial? (boolean (some #(kernel/serial-tool? k (:name %)) calls))
                  invoke (fn [tc]
                           (let [{:keys [value writes]}
                                 (kernel/invoke-tool k (:name tc) (:args tc) tool-context)]
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
        k (kernel/build-kernel
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
    (testing ":tool filter remains around each kernel tool invocation"
      (is (= [:serial-writer :writer] @filter-trace)))
    (testing ":serial declaration still degrades the custom batch to call order"
      (is (true? (kernel/serial-tool? k "serial-writer")))
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
                k (kernel/build-kernel
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
