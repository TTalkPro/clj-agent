(ns im.ttalk.agent.converter.converter-test
  "结构化输出解析 / Schema 验证 / JSON Schema 转换单测"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.converter.json :as cj]
            [im.ttalk.agent.converter.protocol :as proto]
            [im.ttalk.agent.converter.json-schema :as js]))

(def person-schema
  {:name {:type :string :required true :description "用户名"}
   :age  {:type :number}})

;; ============================================================
;; StructuredOutputParser：解析 + 验证
;; ============================================================

(deftest structured-parse-valid-test
  (testing "合法 JSON 解析成功"
    (let [p (cj/create-structured-parser person-schema)
          r (proto/parse p "{\"name\": \"张三\", \"age\": 30}")]
      (is (proto/success? r))
      (is (= {:name "张三" :age 30} (proto/get-data r))))))

(deftest structured-parse-markdown-fence-test
  (testing "从 ```json 围栏中提取 JSON"
    (let [p (cj/create-structured-parser person-schema)
          r (proto/parse p "好的：\n```json\n{\"name\": \"李四\"}\n```\n以上。")]
      (is (proto/success? r))
      (is (= "李四" (:name (proto/get-data r)))))))

(deftest structured-parse-invalid-json-test
  (testing "无 JSON 内容 -> 失败"
    (let [p (cj/create-structured-parser person-schema)
          r (proto/parse p "完全没有 json")]
      (is (not (proto/success? r))))))

(deftest validate-missing-required-test
  (testing "缺必填字段报错"
    (let [p (cj/create-structured-parser person-schema)
          v (proto/validate p {:age 20})]
      (is (false? (:valid v)))
      (is (some #(re-find #"缺少必填字段: name" %) (:errors v))))))

(deftest validate-type-mismatch-message-test
  (testing "类型错误信息包含期望与实际类型（回归：旧实现 (type value) 被 :type 遮蔽，实际类型恒为空）"
    (let [p (cj/create-structured-parser person-schema)
          v (proto/validate p {:name "ok" :age "三十"})]   ;; age 应为 number，给了字符串
      (is (false? (:valid v)))
      (let [msg (first (filter #(re-find #"age" %) (:errors v)))]
        (is (re-find #"期望 number" msg))
        ;; 关键：实际类型不再为空，应出现 String
        (is (re-find #"实际 .*String" msg))))))

(deftest validate-all-good-test
  (testing "全部合法 -> :valid true"
    (let [p (cj/create-structured-parser person-schema)]
      (is (true? (:valid (proto/validate p {:name "ok" :age 1})))))))

;; ============================================================
;; JSON Schema 转换
;; ============================================================

(deftest to-json-schema-test
  (testing "clj-agent schema -> 标准 JSON Schema"
    (let [s (js/to-json-schema person-schema "Person")]
      (is (= "object" (:type s)))
      (is (= {:type "string" :description "用户名"} (get-in s [:properties "name"])))
      (is (= ["name"] (:required s)))
      (is (= false (:additionalProperties s)))
      (is (= "Person" (:title s))))))

(deftest to-openai-response-format-test
  (testing "生成 OpenAI response_format（json_schema + strict）"
    (let [rf (js/to-openai-response-format person-schema "Person")]
      (is (= "json_schema" (:type rf)))
      (is (= "Person" (get-in rf [:json_schema :name])))
      (is (true? (get-in rf [:json_schema :strict])))
      (is (= "object" (get-in rf [:json_schema :schema :type]))))))

(deftest json-schema-round-trip-test
  (testing "to-json-schema -> from-json-schema 往返保留 type/required"
    (let [back (js/from-json-schema (js/to-json-schema person-schema "Person"))]
      (is (= :string (get-in back [:name :type])))
      (is (true? (get-in back [:name :required])))
      (is (= :number (get-in back [:age :type]))))))

(deftest provider-format-dispatch-test
  (testing "to-provider-format 按 provider 出不同结构"
    (is (contains? (js/to-provider-format :openai person-schema "P") :response-format))
    (is (contains? (js/to-provider-format :anthropic person-schema "P") :tools))
    (is (contains? (js/to-provider-format :gemini person-schema "P") :generation-config))))
