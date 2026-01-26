(ns im.ttalk.agent.plugin.utility-test
  (:require [clojure.test :refer :all]
            [im.ttalk.agent.plugin.utility :as utility]
            [im.ttalk.agent.core.kernel.tool :as tool]))

(deftest calculator-test
  (testing "basic arithmetic"
    (is (= "3" (utility/calculator {:expression "(+ 1 2)"})))
    (is (= "12" (utility/calculator {:expression "(* 3 4)"})))
    (is (= "2" (utility/calculator {:expression "(- 5 3)"})))
    (is (= "5" (utility/calculator {:expression "(/ 10 2)"}))))

  (testing "nested expressions"
    (is (= "7" (utility/calculator {:expression "(+ 1 (* 2 3))"})))
    (is (= "9" (utility/calculator {:expression "(+ (* 2 3) (- 5 2))"}))))

  (testing "error handling"
    (is (string? (utility/calculator {:expression "invalid"})))))

(deftest calculator-metadata-test
  (testing "deftool generates correct metadata"
    (let [v #'utility/calculator]
      (is (tool/tool-function? v))
      (is (= false (tool/sensitive? v)))
      (is (some? (tool/get-schema v)))
      (let [schema (tool/get-schema v)]
        (is (= "calculator" (:name schema)))
        (is (string? (:description schema)))
        (is (= "object" (get-in schema [:input_schema :type])))
        (is (contains? (get-in schema [:input_schema :properties]) :expression))))))

(deftest current-time-test
  (testing "default format"
    (let [result (utility/current-time {:format "yyyy-MM-dd HH:mm:ss"})]
      (is (string? result))
      (is (re-matches #"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}" result))))

  (testing "custom format"
    (let [result (utility/current-time {:format "yyyy/MM/dd"})]
      (is (re-matches #"\d{4}/\d{2}/\d{2}" result))))

  (testing "nil format uses default"
    (let [result (utility/current-time {})]
      (is (string? result))
      (is (re-matches #"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}" result)))))

(deftest all-tools-test
  (testing "all-tools is a valid collection"
    (is (vector? utility/all-tools))
    (is (= 2 (count utility/all-tools)))
    (is (every? var? utility/all-tools)))

  (testing "all-tools contains expected tools"
    (let [names (set (map #(-> % meta :name) utility/all-tools))]
      (is (contains? names 'calculator))
      (is (contains? names 'current-time))))

  (testing "schemas are generated"
    (let [schemas (map tool/get-schema utility/all-tools)]
      (is (= 2 (count schemas)))
      (is (every? #(contains? % :name) schemas))
      (is (every? #(contains? % :input_schema) schemas))))

  (testing "direct tool invocation"
    (let [result (tool/invoke #'utility/calculator {:expression "(+ 1 2)"})]
      (is (= "3" result)))))
