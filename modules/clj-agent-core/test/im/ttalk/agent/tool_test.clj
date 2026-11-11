(ns im.ttalk.agent.tool-test
  "deftool 宏：参数默认值与 schema 单测"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.tool-args :as ta]
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

(deftool record-codex-event
  "对象参数带嵌套 schema"
  [[event :object "事件本身"
    :properties {:chapter {:type "integer" :description "章节"}
                 :title {:type "string" :description "标题"}}
    :required ["chapter" "title"]
    :additional-properties false]
   [lands :array "落点" :items {:type "string"}]]
  {:event event :lands lands})

(deftest object-param-preserves-nested-schema
  (testing "对象参数的 :properties / :required / :additional-properties 进 schema"
    (let [schema (tool/get-schema #'record-codex-event)]
      (is (= {:type "object"
              :description "事件本身"
              :properties {:chapter {:type "integer" :description "章节"}
                           :title {:type "string" :description "标题"}}
              :required ["chapter" "title"]
              :additionalProperties false}
             (get-in schema [:input_schema :properties :event])))
      (testing "嵌套 required 也参与调用前校验"
        (is (= #{"event.chapter" "event.title"}
               (set (map :path (ta/validate
                                (:input_schema schema)
                                {:event {} :lands ["a"]})))))))))

(deftool wide-tool
  "十个参数的声明顺序必须保留"
  [[p01 :string "01"] [p02 :string "02"] [p03 :string "03"] [p04 :string "04"] [p05 :string "05"]
   [p06 :string "06"] [p07 :string "07"] [p08 :string "08"] [p09 :string "09"] [p10 :string "10"]]
  (str p01 p02 p03 p04 p05 p06 p07 p08 p09 p10))

(deftest property-order-follows-declaration
  (testing "properties 超过 8 个键时仍按声明顺序写出"
    (let [properties (get-in (tool/get-schema #'wide-tool)
                             [:input_schema :properties])]
      (is (= [:p01 :p02 :p03 :p04 :p05 :p06 :p07 :p08 :p09 :p10]
             (keys properties))))))

(deftool needs-city
  "查天气（city 必填）"
  [[city :string "城市"]]
  (str city " 晴"))

(deftest invoke-validates-required-args
  (testing "LLM 漏传必填参数时返回明确错误，而非进入函数体 NPE（回归：validate-args 曾是死代码）"
    (let [r (tool/invoke #'needs-city {})]
      (is (false? (:success r)))
      (is (re-find #"缺少必需参数: city" (:error r)))))
  (testing "提供必填参数正常执行"
    (let [r (tool/invoke #'needs-city {:city "北京"})]
      (is (:success r))
      (is (= "北京 晴" (:result r)))))
  (testing "带 :default 的参数省略不算缺参"
    (is (:success (tool/invoke #'round-num {:x 1})))))

(deftool declares-timeout
  "声明超时的工具"
  [[x :string "输入"]]
  {:timeout 500}
  x)

(deftest timeout-option-emits-metadata
  (testing ":timeout 选项生成 :tool/timeout 元数据（回归：曾是死选项——白名单收下、元数据不产、全库零读取）"
    (is (= 500 (:tool/timeout (meta #'declares-timeout))))
    (is (= 500 (tool/timeout-spec #'declares-timeout))))
  (testing "未声明 → nil（invoke-tool 据此回落引擎缺省或不超时）"
    (is (nil? (tool/timeout-spec #'round-num)))
    (is (nil? (tool/timeout-spec #'greet-ctx))))
  (testing "裸 tool/invoke（不经 chat-client）不超时——超时由 chat-client/invoke-tool 强制，
            tool/invoke 是更内层的原语，不消费 :timeout 声明（语义钉子）"
    (let [r (tool/invoke #'declares-timeout {:x "ok"})]
      (is (:success r))
      (is (= "ok" (:result r))))))
