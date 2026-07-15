(ns im.ttalk.agent.structured-output-test
  "结构化输出校验测试（对标 Spring StructuredOutputValidationAdvisor 的 validate 侧）"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string]
            [im.ttalk.agent.model.response :as resp]
            [im.ttalk.agent.advisor :as flt]
            [im.ttalk.agent.advisor.structured-output :as so]))

(def ^:private schema
  {:type "object"
   :properties {:actor {:type "string"}
                :films {:type "array" :items {:type "string"}}
                :rating {:type "integer"}
                :status {:type "string" :enum ["active" "retired"]}}
   :required ["actor" "films"]})

;;; ============================================================
;;; validate-value（纯函数）
;;; ============================================================

(deftest validate-value-happy-test
  (testing "合规值 → nil"
    (is (nil? (so/validate-value {:actor "Keanu" :films ["Matrix" "John Wick"]} schema)))
    (is (nil? (so/validate-value {:actor "K" :films [] :rating 5 :status "active"} schema)))))

(deftest validate-value-required-test
  (testing "缺必填字段 → 指名道姓（模型据此就能改对）"
    (is (= "缺少必填字段 films" (so/validate-value {:actor "K"} schema)))
    (is (= "缺少必填字段 actor" (so/validate-value {:films []} schema)))))

(deftest validate-value-type-test
  (testing "类型不符 → 报期望与实际（对齐 Spring 的 expected 'array', got 'string'）"
    (is (= "字段 films 期望 array，实为 string"
           (so/validate-value {:actor "K" :films "Matrix"} schema)))
    (is (= "字段 actor 期望 string，实为 integer"
           (so/validate-value {:actor 1 :films []} schema))))

  (testing "根类型不符"
    (is (= "字段 根 期望 object，实为 array"
           (so/validate-value [1 2] schema))))

  (testing "数组元素类型不符 → 路径带下标"
    (is (= "字段 films[1] 期望 string，实为 integer"
           (so/validate-value {:actor "K" :films ["ok" 42]} schema))))

  (testing "integer 不收浮点，number 收整数"
    (is (some? (so/validate-value {:actor "K" :films [] :rating 1.5} schema)))
    (is (nil? (so/validate-value 1 {:type "number"})))
    (is (nil? (so/validate-value 1.5 {:type "number"}))))

  (testing "boolean 不被当成 integer/number（Clojure 里易错）"
    (is (some? (so/validate-value true {:type "integer"})))
    (is (some? (so/validate-value true {:type "number"})))
    (is (nil? (so/validate-value true {:type "boolean"})))))

(deftest validate-value-enum-test
  (testing "enum 不匹配 → 列出可选值"
    (is (= "字段 status 必须是 \"active\" / \"retired\" 之一，实为 \"dead\""
           (so/validate-value {:actor "K" :films [] :status "dead"} schema)))))

(deftest validate-value-nested-test
  (testing "嵌套对象路径可读"
    (let [s {:type "object"
             :properties {:user {:type "object"
                                 :properties {:name {:type "string"}}
                                 :required ["name"]}}
             :required ["user"]}]
      (is (= "缺少必填字段 user.name" (so/validate-value {:user {}} s)))
      (is (= "字段 user.name 期望 string，实为 integer"
             (so/validate-value {:user {:name 7}} s)))))

  (testing "对象数组的深层路径"
    (let [s {:type "array"
             :items {:type "object"
                     :properties {:id {:type "integer"}}
                     :required ["id"]}}]
      (is (= "缺少必填字段 [1].id" (so/validate-value [{:id 1} {}] s))))))

(deftest validate-value-key-flavors-test
  (testing "字符串键与 keyword 键都认（解析器是否 keywordize 不该影响校验）"
    (is (nil? (so/validate-value {"actor" "K" "films" []} schema)))
    (is (= "缺少必填字段 films" (so/validate-value {"actor" "K"} schema)))))

(deftest validate-value-open-world-test
  (testing "未声明的属性不管（等价 additionalProperties: true）"
    (is (nil? (so/validate-value {:actor "K" :films [] :extra "whatever"} schema))))

  (testing "未声明 :type 的属性放行"
    (is (nil? (so/validate-value {:x [1 "a" {}]} {:type "object" :properties {:x {}}})))))

;;; ============================================================
;;; strip-fences
;;; ============================================================

(deftest strip-fences-test
  (testing "剥掉 ```json 围栏（模型即便被要求纯 JSON 也常包一层）"
    (is (= "{\"a\":1}" (so/strip-fences "```json\n{\"a\":1}\n```")))
    (is (= "{\"a\":1}" (so/strip-fences "```\n{\"a\":1}\n```")))
    (is (= "{\"a\":1}" (so/strip-fences "  {\"a\":1}  ")))
    (is (= "" (so/strip-fences nil))))

  (testing "多行 JSON 不被截断"
    (is (= "{\n  \"a\": 1\n}" (so/strip-fences "```json\n{\n  \"a\": 1\n}\n```")))))

;;; ============================================================
;;; validate-fn 工厂
;;; ============================================================

;; 极简 JSON 解析替身——core 零依赖，测试里也不引 cheshire
(defn- fake-parse [s]
  (if (clojure.string/starts-with? (clojure.string/trim s) "{")
    (read-string s)                       ;; 测试数据写成 EDN 形状即可
    (throw (ex-info "Unexpected token" {}))))

(defn- result-of [text] {:response (resp/make-response :text text)})

(deftest validate-fn-test
  (let [v (so/validate-fn schema :parse-fn fake-parse)]

    (testing "合规 → nil"
      (is (nil? (v (result-of "{:actor \"K\" :films [\"a\"]}")))))

    (testing "带围栏也能过"
      (is (nil? (v (result-of "```json\n{:actor \"K\" :films [\"a\"]}\n```")))))

    (testing "结构不合规 → 具体问题"
      (is (= "缺少必填字段 films" (v (result-of "{:actor \"K\"}")))))

    (testing "非法 JSON → 报解析失败而非崩溃"
      (is (clojure.string/includes? (v (result-of "not json at all")) "不是合法 JSON")))

    (testing "空回答"
      (is (= "回答为空，未产出任何 JSON。" (v (result-of "   ")))))

    (testing "缺 :parse-fn 直接报错（而非静默不校验）"
      (is (thrown? clojure.lang.ExceptionInfo (so/validate-fn schema))))))

;;; ============================================================
;;; 与 validation-turn-filter 合体（机制 + 判据）
;;; ============================================================

(deftest validate-fn-drives-retry-test
  (testing "不合规 → 反馈重入；合规即停（机制来自 validation-turn-filter）"
    (let [attempts (atom 0)
          feedbacks (atom [])
          f (flt/validation-turn-filter (so/validate-fn schema :parse-fn fake-parse)
                                        :max-retries 2)
          terminal (fn [req]
                     (swap! attempts inc)
                     (when-let [m (first (:messages req))]
                       (swap! feedbacks conj (:content m)))
                     {:status :completed
                      :response (resp/make-response
                                  :text (if (= 1 @attempts)
                                          "{:actor \"K\"}"          ;; 缺 films
                                          "{:actor \"K\" :films []}"))})
          out ((flt/build-chain [(:turn f)] terminal) {:messages [{:role :user :content "生成"}]})]
      (is (= :completed (:status out)))
      (is (= 2 @attempts) "第 1 次不合规 → 反馈重入 → 第 2 次合规")
      (is (clojure.string/includes? (second @feedbacks) "缺少必填字段 films")
          "反馈里带上具体问题——模型据此自我修正，而非盲目重试"))))
