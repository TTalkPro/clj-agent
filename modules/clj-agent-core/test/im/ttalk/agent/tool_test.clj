(ns im.ttalk.agent.tool-test
  "deftool 宏：参数默认值与 schema 单测"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
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

(deftool nested-object
  "对象参数的属性表写成键值对 vector"
  [[payload :object "载荷"
    :properties [[:chapter {:type "integer" :description "章节"}]
                 [:title {:type "string" :description "标题"}]
                 [:lands {:type "array" :description "落点"
                          :items {:type "string"}}]]
    :required ["chapter"]]
   [note :string "备注"]]
  {:payload payload :note note})

(deftest nested-properties-pairs-vector-keeps-order
  (testing ":properties 写成 [[k schema] …] 键值对 vector 时按声明顺序写出"
    (let [properties (get-in (tool/get-schema #'nested-object)
                             [:input_schema :properties :payload :properties])]
      (is (= [:chapter :title :lands] (keys properties)))
      (is (= "integer" (get-in properties [:chapter :type])))))
  (testing "键值对 vector 形式照样参与嵌套必填校验"
    (is (= #{"payload.chapter"}
           (set (map :path (ta/validate
                            (:input_schema (tool/get-schema #'nested-object))
                            {:payload {} :note "n"})))))))

;;; ============================================================
;;; AOT 判据：参数声明顺序在**编译后**仍须一致
;;; ============================================================

(def ^:private aot-probe-src
  "AOT 探针：10 个参数（>8 ⇒ 常量 map 会退化成 hash map）+ 一个键值对 vector 形式的
   嵌套属性表。编译成 class 后加载，才算真正复现 AOT（tools.build 的 compile-clj）。"
  (str "(ns aotprobe1.probe (:require [im.ttalk.agent.tool :refer [deftool]]))\n"
       "(deftool wide \"d\"\n"
       "  [[p0 :string \"0\"] [p1 :string \"1\"] [p2 :string \"2\"] [p3 :string \"3\"] [p4 :string \"4\"]\n"
       "   [p5 :string \"5\"] [p6 :string \"6\"] [p7 :string \"7\"] [p8 :string \"8\"] [p9 :string \"9\"]\n"
       "   [payload :object \"载荷\"\n"
       "    :properties [[:chapter {:type \"integer\"}] [:title {:type \"string\"}]]]\n"
       "   ]\n"
       "  p0)\n"))

(defn- delete-tree!
  [dir]
  (try
    (doseq [f (reverse (file-seq dir))]
      (.delete (io/file f)))
    (catch Exception _ nil)))

(defn- run-aot-probe
  "把探针**编译成 class** 再加载，返回 {:top-keys […] :nested-keys […]}。

   走子进程：编译产物必须与源码分开加载（同一 JVM 里 require 会直接吃源码），
   才能复现 AOT 下「常量 map 被重建」的路径。"
  []
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                       "clj-agent-aot"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        src-dir (io/file dir "aotprobe1")]
    (.mkdirs src-dir)
    (spit (io/file src-dir "probe.clj") aot-probe-src)
    (try
      (let [script (str "(require '[im.ttalk.agent.tool :as tool])\n"
                         "(binding [*compile-path* \"" dir "\"] (compile 'aotprobe1.probe))\n"
                         "(require 'aotprobe1.probe)\n"
                         "(let [v (ns-resolve (find-ns 'aotprobe1.probe) 'wide)\n"
                         "      props (get-in (:tool/schema (meta v)) [:input_schema :properties])\n"
                         "      nested (get-in props [:payload :properties])]\n"
                         "  (println \"PROBE_TOP\" (pr-str (keys props)))\n"
                         "  (println \"PROBE_NESTED\" (pr-str (keys nested))))\n")
            java-bin (str (System/getProperty "java.home") java.io.File/separator "bin"
                          java.io.File/separator "java")
            cp (str dir java.io.File/pathSeparator (System/getProperty "java.class.path"))
            {:keys [exit out err]} (shell/sh java-bin "-cp" cp "clojure.main" "-e" script)
            pick (fn [tag]
                    (some (fn [line]
                            (when (str/starts-with? line (str tag " "))
                              (edn/read-string (subs line (inc (count tag))))))
                          (str/split-lines out)))]
        (when-not (zero? exit)
          (throw (ex-info (str "AOT 探针子进程退出码 " exit "\n" err) {:exit exit :err err})))
        {:top-keys    (vec (pick "PROBE_TOP"))
         :nested-keys (vec (pick "PROBE_NESTED"))})
      (finally
        (delete-tree! dir)))))

(deftest schema-order-survives-aot
  (testing "AOT 编译后参数声明顺序仍与源码一致（回归：schema 曾是常量，>8 键即 hash map）"
    (let [{:keys [top-keys nested-keys]} (run-aot-probe)]
      (is (= [:p0 :p1 :p2 :p3 :p4 :p5 :p6 :p7 :p8 :p9 :payload] top-keys))
      (is (= [:chapter :title] nested-keys)))))

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
