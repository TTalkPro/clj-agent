(ns im.ttalk.agent.plugins.http-test
  (:require [clojure.test :refer :all]
            [im.ttalk.agent.plugins.http :as http]
            [im.ttalk.agent.core.kernel.tool :as tool]
            [im.ttalk.agent.core.kernel.plugin :as kp]))

(deftest http-tools-metadata-test
  (testing "http-get has correct metadata"
    (let [v #'http/http-get]
      (is (tool/tool-function? v))
      (let [schema (tool/get-schema v)]
        (is (= "http-get" (:name schema)))
        (is (contains? (get-in schema [:input_schema :properties]) :url)))))

  (testing "http-post has correct metadata"
    (let [v #'http/http-post]
      (is (tool/tool-function? v))
      (let [schema (tool/get-schema v)]
        (is (= "http-post" (:name schema)))
        (is (contains? (get-in schema [:input_schema :properties]) :url))
        (is (contains? (get-in schema [:input_schema :properties]) :body)))))

  (testing "http-put has correct metadata"
    (let [v #'http/http-put]
      (is (tool/tool-function? v))
      (let [schema (tool/get-schema v)]
        (is (= "http-put" (:name schema))))))

  (testing "http-delete has correct metadata"
    (let [v #'http/http-delete]
      (is (tool/tool-function? v))
      (let [schema (tool/get-schema v)]
        (is (= "http-delete" (:name schema)))))))

(deftest http-tools-plugin-test
  (testing "plugin structure"
    (is (instance? im.ttalk.agent.core.kernel.plugin.KernelPlugin http/http-tools))
    (is (= 4 (kp/function-count http/http-tools))))

  (testing "plugin contains expected tools"
    (let [names (set (kp/list-function-names http/http-tools))]
      (is (contains? names :http-get))
      (is (contains? names :http-post))
      (is (contains? names :http-put))
      (is (contains? names :http-delete))))

  (testing "schemas are generated"
    (let [schemas (kp/get-schemas http/http-tools)]
      (is (= 4 (count schemas)))
      (is (every? #(contains? % :name) schemas))
      (is (every? #(contains? % :input_schema) schemas)))))

(deftest http-get-error-handling-test
  (testing "http-get with invalid URL returns error message"
    (let [result (http/http-get {:url "http://invalid-host-that-does-not-exist-12345.example"})]
      (is (string? result))
      (is (clojure.string/includes? result "请求失败")))))
