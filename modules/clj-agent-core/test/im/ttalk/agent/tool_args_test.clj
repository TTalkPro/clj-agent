(ns im.ttalk.agent.tool-args-test
  "R-08 按 schema 完整校验工具参数（designs/runtime-borrow.md §8）。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.tool :as tool :refer [deftool]]
            [im.ttalk.agent.tool-args :as ta]))

(def schema
  {:type "object"
   :properties {:mode  {:type "string" :enum ["fast" "slow"] :description "模式"}
                :count {:type "integer" :minimum 1 :maximum 10}
                :ratio {:type "number" :exclusiveMinimum 0}
                :code  {:type "string" :pattern "^[A-Z]{3}$" :minLength 3 :maxLength 3}
                :tags  {:type "array" :minItems 1 :maxItems 3 :items {:type "string" :minLength 2}}
                :opts  {:type "object" :properties {:depth {:type "integer"}} :required ["depth"]}
                :flag  {:type "boolean"}}
   :required ["mode" "count"]})

(defn- paths [errs] (set (map :path errs)))

(deftest validate-test
  (testing "全部合法 → 空"
    (is (= [] (ta/validate schema {:mode "fast" :count 3 :ratio 0.5 :code "ABC"
                                   :tags ["ab"] :opts {:depth 1} :flag true}))))
  (testing "一次报全，带路径"
    (let [errs (ta/validate schema {:mode "medium" :count 11 :ratio 0 :code "abcd"
                                    :tags ["a" "bb" "cc" "dd"] :opts {} :flag "maybe"})]
      (is (= #{"mode" "count" "ratio" "code" "tags" "tags[0]" "opts.depth" "flag"} (paths errs)))
      (is (some #(str/includes? (:message %) "应为以下之一") errs))
      (is (some #(str/includes? (:message %) "≤ 10") errs))))
  (testing "缺必需参数"
    (is (= #{"mode" "count"} (paths (ta/validate schema {})))))
  (testing "类型不符只报类型，不再报其它"
    (let [errs (ta/validate schema {:mode "fast" :count {:x 1}})]
      (is (= 1 (count errs)))
      (is (str/includes? (:message (first errs)) "类型应为整数"))))
  (testing "兼容：数字字符串、\"true\"、可选参数 null、未知键"
    (is (= [] (ta/validate schema {:mode "fast" :count "3" :flag "true" :ratio nil :extra 1}))))
  (testing "字符串键的 schema 与参数（MCP 形状）"
    (is (= ["count"] (mapv :path (ta/validate {"type" "object"
                                               "properties" {"count" {"type" "integer" "minimum" 1}}}
                                              {"count" 0}))))))

(deftest format-errors-test
  (let [s (ta/format-errors "foo" (ta/validate schema {:mode "x"}))]
    (is (str/includes? s "工具 foo 参数校验失败（2 处）"))
    (is (str/includes? s "缺少必需参数: count"))
    (is (str/includes? s "mode（模式）：应为以下之一"))
    (is (str/includes? s "请按工具的参数 schema 修正"))))

(deftool pick
  "挑选"
  [[mode :string "模式" :enum ["a" "b"]]
   [n :int "数量" :minimum 1 :maximum 5 :default 1]
   [names :array "名字" :items {:type "string"} :min-items 1 :default ["x"]]]
  (str mode n names))

(deftest deftool-constraints-test
  (testing "约束进 input_schema（模型看得到）"
    (let [props (get-in (tool/get-schema #'pick) [:input_schema :properties])]
      (is (= ["a" "b"] (get-in props [:mode :enum])))
      (is (= 1 (get-in props [:n :minimum])))
      (is (= 5 (get-in props [:n :maximum])))
      (is (= {:type "string"} (get-in props [:names :items])))
      (is (= 1 (get-in props [:names :minItems])))))
  (testing "调用前完整校验：错误一次报全、不进函数体"
    (let [r (tool/invoke #'pick {:mode "c" :n 9})]
      (is (false? (:success r)))
      (is (str/includes? (:error r) "mode"))
      (is (str/includes? (:error r) "n"))
      (is (str/includes? (:error r) "修正后重试"))))
  (testing "合法照常执行，:default 生效"
    (is (= {:success true :result "a1[\"x\"]"} (tool/invoke #'pick {:mode "a"})))))
