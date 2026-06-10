(ns im.ttalk.agent.tool-test
  "deftool 宏：参数默认值与 schema 单测"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.tool :as tool :refer [deftool]]))

(deftool round-num
  "舍入到指定精度"
  [[x :number "值"]
   [precision :int "精度" :default 2]]
  {:x x :precision precision})

(deftool greet-ctx
  "带 context 与默认值"
  [[name :string "名字"]
   [greeting :string "问候语" :default "你好"]]
  {:context true}
  {:msg (str greeting "," name) :ctx ctx})

(deftest default-value-applied-when-omitted
  (testing "LLM 省略带 :default 的参数时取默认值，而非 nil（回归 BUG4）"
    ;; 修复前：解构无 :or，precision 为 nil
    (is (= {:x 3.14 :precision 2} (round-num {:x 3.14})))
    (is (= {:x 3.14 :precision 5} (round-num {:x 3.14 :precision 5})))))

(deftest default-value-applied-context-tool
  (testing "context 工具的默认值同样生效"
    (is (= "你好,张三" (:msg (greet-ctx {:name "张三"} {}))))
    (is (= "Hi,张三" (:msg (greet-ctx {:name "张三" :greeting "Hi"} {}))))))

(deftest schema-marks-default-param-optional
  (testing "带 :default 的参数不在 required 列表中"
    (let [schema (tool/get-schema #'round-num)
          required (get-in schema [:input_schema :required])]
      (is (= ["x"] required))
      (is (contains? (get-in schema [:input_schema :properties]) :precision)))))
