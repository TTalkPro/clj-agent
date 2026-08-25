(ns im.ttalk.agent.tool-meta-test
  "ToolMeta 表——var 与内联工具的声明在装配期汇成一张表，四个查询是它的薄封装。

   此前是四个查询函数各自手抄 `(if-let [v (get tool-vars k)] 读var 查inline)`
   双分支：`:timeout` 就是在这种重复里漏掉 inline 分支、对内联工具静默失效的；
   而且四处双分支是 **var 优先**，`invoke-tool` 的执行分派却是 **内联优先**——
   同名时「按 var 的策略执行内联的 handler」。合表暴露了这个矛盾，
   最终的处置是**装配期直接拒绝同名**（见 duplicate-tool-names-rejected-test），
   而不是在两套优先级里选一个。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.chat-client :as chat-client]
            [im.ttalk.agent.tool-registry :as registry]
            [im.ttalk.agent.tool :refer [deftool]]))

(deftool plain-tool
  "无任何声明"
  [[x :string "输入" :default "v"]]
  x)

(deftool decorated-tool
  "四个声明齐活"
  [[x :string "输入" :default "v"]]
  {:serial true :retry true :timeout 300 :return-direct true :sensitive true}
  x)

(deftool retry-map-tool
  "retry 给 map"
  [[x :string "输入" :default "v"]]
  {:retry {:max-retries 5}}
  x)

(defn- inline-tool
  [nm & opts]
  (merge {:name nm :description nm
          :input_schema {:type "object" :properties {} :required []}
          :handler (fn [_ _] "inline")}
         (apply hash-map opts)))

(defn- k-with [& tools]
  (chat-client/build-chat-client {:chat-model {} :tools (vec tools)}))

;;; ============================================================
;;; 一张表答完四个查询
;;; ============================================================

(deftest var-tool-meta-test
  (testing "var 工具：四个声明都从表里查得到"
    (let [k (k-with #'decorated-tool)]
      (is (true? (registry/serial-tool? k :decorated-tool)))
      (is (true? (registry/return-direct-tool? k :decorated-tool)))
      (is (= 300 (registry/tool-timeout k :decorated-tool)))
      (is (= {:max-retries 2 :initial-delay-ms 200} (registry/retry-policy k :decorated-tool))
          ":retry true → 装配期就归一化成默认策略")))

  (testing "无声明的 var 工具：false / nil，不是异常"
    (let [k (k-with #'plain-tool)]
      (is (false? (registry/serial-tool? k :plain-tool)))
      (is (false? (registry/return-direct-tool? k :plain-tool)))
      (is (nil? (registry/tool-timeout k :plain-tool)))
      (is (nil? (registry/retry-policy k :plain-tool)))))

  (testing ":retry map 与默认值 merge（装配期做掉，运行期不再 merge）"
    (let [k (k-with #'retry-map-tool)]
      (is (= {:max-retries 5 :initial-delay-ms 200} (registry/retry-policy k :retry-map-tool))))))

(deftest inline-tool-meta-test
  (testing "内联工具：同样四个声明，同一张表"
    (let [k (k-with (inline-tool "inl" :serial true :retry {:max-retries 3}
                                 :timeout 250 :return-direct true))]
      (is (true? (registry/serial-tool? k :inl)))
      (is (true? (registry/return-direct-tool? k :inl)))
      (is (= 250 (registry/tool-timeout k :inl)))
      (is (= {:max-retries 3 :initial-delay-ms 200} (registry/retry-policy k :inl)))))

  (testing "无声明的内联工具"
    (let [k (k-with (inline-tool "bare"))]
      (is (false? (registry/serial-tool? k :bare)))
      (is (nil? (registry/tool-timeout k :bare))))))

;;; ============================================================
;;; 字符串名 / 未注册
;;; ============================================================

(deftest lookup-forms-test
  (testing "关键字与字符串两种写法等价"
    (let [k (k-with #'decorated-tool)]
      (is (= (registry/tool-timeout k :decorated-tool)
             (registry/tool-timeout k "decorated-tool")))))

  (testing "未注册的工具：tool-meta nil，四个查询给 false / nil 而不抛"
    (let [k (k-with #'plain-tool)]
      (is (nil? (registry/tool-meta k :nope)))
      (is (false? (registry/serial-tool? k :nope)))
      (is (false? (registry/return-direct-tool? k :nope)))
      (is (nil? (registry/tool-timeout k :nope)))
      (is (nil? (registry/retry-policy k :nope))))))

;;; ============================================================
;;; 同名冲突：内联优先，与 invoke-tool 的执行分派对齐
;;; ============================================================

(deftest duplicate-tool-names-rejected-test
  (testing "var 与内联同名 → 装配期抛，不再有「谁赢」这回事"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"工具名重复"
          (k-with #'decorated-tool (inline-tool "decorated-tool" :timeout 999)))))

  (testing "两个同名内联工具"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"工具名重复"
          (k-with (inline-tool "same") (inline-tool "same")))))

  (testing "同一个 var 注册两次"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"工具名重复"
          (k-with #'plain-tool #'plain-tool))))

  (testing ":duplicates 点名所有重复的键，不止第一个"
    (let [e (try (k-with (inline-tool "a") (inline-tool "a")
                         (inline-tool "b") (inline-tool "b")
                         (inline-tool "ok"))
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= [:a :b] (:duplicates (ex-data e))))))

  (testing "重名校验先于其它装配期校验——名字都不唯一时先报名字"
    ;; 这两个内联工具同名**且**都带非法 :timeout：应报重名而非 timeout
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"工具名重复"
          (k-with (inline-tool "dup" :timeout "5s") (inline-tool "dup" :timeout "5s")))))

  (testing "不同名的工具照常共存"
    (let [k (k-with #'decorated-tool (inline-tool "inl"))]
      (is (= 300 (registry/tool-timeout k :decorated-tool)))
      (is (= "inline" (:value (chat-client/invoke-tool k :inl {} nil)))))))

;;; ============================================================
;;; ToolRequest 的 :function 段也在这张表里
;;; ============================================================

(deftest func-def-in-table-test
  (testing "var 工具的 :function 段带 schema 与 :sensitive"
    (let [fd (:func-def (registry/tool-meta (k-with #'decorated-tool) :decorated-tool))]
      (is (= :decorated-tool (:name fd)))
      (is (true? (:sensitive fd)))
      (is (some? (:schema fd)))))

  (testing "内联工具的 :function 段：无 schema、非 sensitive"
    (let [fd (:func-def (registry/tool-meta (k-with (inline-tool "inl")) :inl))]
      (is (= :inl (:name fd)))
      (is (false? (:sensitive fd)))
      (is (nil? (:schema fd))))))

;;; ============================================================
;;; 装配期校验先于归一化
;;; ============================================================

(deftest validation-precedes-normalization-test
  (testing "非法 :retry 在装配期就抛——不能先 merge 成看似合法的策略再放行"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":retry"
          (k-with (inline-tool "bad" :retry {:max-retries -1})))))

  (testing "非法 :timeout 同样"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":timeout"
          (k-with (inline-tool "bad" :timeout "5s"))))))

;;; ============================================================
;;; ToolRegistry：四个工具字段收成一个值
;;; ============================================================

(deftest chat-client-holds-one-tool-registry
  (testing "ChatClient 上只有 :tool-registry，四个旧字段不再平铺"
    (let [cc (k-with #'decorated-tool)]
      (is (instance? im.ttalk.agent.tool_registry.ToolRegistry (:tool-registry cc)))
      (doseq [gone [:tools :tool-vars :inline-handlers :tool-meta]]
        (is (nil? (get cc gone))
            (str gone " 应已收进 :tool-registry —— 平铺读法必须失效，"
                 "否则调用方会以为旧写法还成立")))))

  (testing "tool-schemas 取 schema 列表（原 (:tools cc) 的替代）"
    (let [cc (k-with #'decorated-tool (inline-tool "inl"))]
      (is (= #{"decorated-tool" "inl"}
             (set (map :name (registry/tool-schemas cc)))))
      (is (every? #(nil? (:handler %)) (registry/tool-schemas cc))
          "内联工具的 :handler 不进 schema 列表（它只归 registry 收着）")))

  (testing "查询函数吃 ChatClient 或裸 ToolRegistry —— 注册表是个独立的值"
    (let [cc  (k-with #'decorated-tool)
          reg (registry/registry-of cc)]
      (is (= (registry/tool-meta cc :decorated-tool)
             (registry/tool-meta reg :decorated-tool)))
      (is (= (registry/list-functions cc) (registry/list-functions reg)))
      (is (= (registry/tool-schemas cc) (registry/tool-schemas reg)))
      (is (identical? reg (registry/registry-of reg)) "registry-of 幂等"))))

