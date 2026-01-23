(ns im.ttalk.agent.tools.tool-registry-test
  "ToolRegistry 测试"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.tools.tool-registry :as tr]
            [im.ttalk.agent.tools.protocol :as proto]
            [im.ttalk.agent.tools.provider.local :as local]))

;;; ============================================================
;;; 创建和基本操作测试
;;; ============================================================

(deftest create-tool-registry-test
  (testing "创建默认 ToolRegistry"
    (let [registry (tr/create-tool-registry)]
      (is (tr/registry? registry))
      (is (= :tool-registry (:name registry)))
      (is (= :local-first (:conflict-resolution registry)))
      (is (= 0 (tr/tool-count registry)))
      (is (= 0 (tr/provider-count registry)))))

  (testing "创建自定义配置的 ToolRegistry"
    (let [registry (tr/create-tool-registry
                     :name :my-registry
                     :conflict-resolution :provider-first)]
      (is (= :my-registry (:name registry)))
      (is (= :provider-first (:conflict-resolution registry))))))

;;; ============================================================
;;; 工具注册测试
;;; ============================================================

(deftest register-tool-test
  (testing "注册单个工具"
    (let [registry (tr/create-tool-registry)
          _ (tr/register-tool! registry :calc "计算器"
                               {:type "object" :properties {:expr {:type "string"}}}
                               (fn [{:keys [expr]}] (str (eval (read-string expr)))))]
      (is (= 1 (tr/local-tool-count registry)))
      (is (= 1 (tr/tool-count registry)))
      (is (some? (tr/get-tool registry :calc)))))

  (testing "注册多个工具"
    (let [registry (tr/create-tool-registry)
          tools [{:name :add
                  :description "加法"
                  :input-schema {:type "object"}
                  :handler (fn [{:keys [a b]}] (+ a b))}
                 {:name :sub
                  :description "减法"
                  :input-schema {:type "object"}
                  :handler (fn [{:keys [a b]}] (- a b))}]
          _ (tr/register-tools! registry tools)]
      (is (= 2 (tr/tool-count registry)))
      (is (some? (tr/get-tool registry :add)))
      (is (some? (tr/get-tool registry :sub))))))

(deftest unregister-tool-test
  (testing "注销工具"
    (let [registry (-> (tr/create-tool-registry)
                       (tr/register-tool! :calc "计算器" {} identity))]
      (is (= 1 (tr/tool-count registry)))
      (tr/unregister-tool! registry :calc)
      (is (= 0 (tr/local-tool-count registry))))))

;;; ============================================================
;;; Provider 注册测试
;;; ============================================================

(deftest register-provider-test
  (testing "注册 Provider"
    (let [registry (tr/create-tool-registry)
          provider (-> (local/create-local-provider :test-provider)
                       (local/register-tool! :p-tool "Provider 工具" {} identity))]
      (tr/register-provider! registry provider)
      (is (= 1 (tr/provider-count registry)))
      (is (= [:test-provider] (tr/list-providers registry)))
      ;; Provider 的工具也应该被计入总数
      (is (= 1 (tr/tool-count registry)))))

  (testing "注册多个 Provider"
    (let [registry (tr/create-tool-registry)
          p1 (-> (local/create-local-provider :provider-1)
                 (local/register-tool! :tool-1 "工具 1" {} identity))
          p2 (-> (local/create-local-provider :provider-2)
                 (local/register-tool! :tool-2 "工具 2" {} identity))]
      (tr/register-providers! registry [p1 p2])
      (is (= 2 (tr/provider-count registry)))
      (is (= 2 (tr/tool-count registry))))))

(deftest unregister-provider-test
  (testing "注销 Provider"
    (let [provider (-> (local/create-local-provider :test-provider)
                       (local/register-tool! :p-tool "工具" {} identity))
          registry (-> (tr/create-tool-registry)
                       (tr/register-provider! provider))]
      (is (= 1 (tr/provider-count registry)))
      (tr/unregister-provider! registry :test-provider)
      (is (= 0 (tr/provider-count registry))))))

;;; ============================================================
;;; 冲突解决测试
;;; ============================================================

(deftest local-first-conflict-resolution-test
  (testing "local-first: 本地工具优先"
    (let [provider (-> (local/create-local-provider :provider)
                       (local/register-tool! :calc "Provider 计算" {}
                                             (fn [_] "from-provider")))
          registry (-> (tr/create-tool-registry :conflict-resolution :local-first)
                       (tr/register-provider! provider)
                       (tr/register-tool! :calc "本地计算" {}
                                          (fn [_] "from-local")))]
      ;; 本地优先，应该执行本地工具
      (let [result (tr/execute-tool registry :calc {})]
        (is (:success result))
        (is (= "from-local" (:result result)))))))

(deftest provider-first-conflict-resolution-test
  (testing "provider-first: Provider 工具优先"
    (let [provider (-> (local/create-local-provider :provider)
                       (local/register-tool! :calc "Provider 计算" {}
                                             (fn [_] "from-provider")))
          registry (-> (tr/create-tool-registry :conflict-resolution :provider-first)
                       (tr/register-provider! provider)
                       (tr/register-tool! :calc "本地计算" {}
                                          (fn [_] "from-local")))]
      ;; Provider 优先，应该执行 Provider 工具
      (let [result (tr/execute-tool registry :calc {})]
        (is (:success result))
        (is (= "from-provider" (:result result)))))))

(deftest error-conflict-resolution-test
  (testing "error: 冲突时抛出异常"
    (let [provider (-> (local/create-local-provider :provider)
                       (local/register-tool! :calc "Provider 计算" {} identity))
          registry (-> (tr/create-tool-registry :conflict-resolution :error)
                       (tr/register-provider! provider))]
      ;; 尝试注册同名工具应该抛出异常
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Tool already exists"
            (tr/register-tool! registry :calc "本地计算" {} identity))))))

;;; ============================================================
;;; 执行测试
;;; ============================================================

(deftest execute-tool-test
  (testing "执行本地工具"
    (let [registry (-> (tr/create-tool-registry)
                       (tr/register-tool! :add "加法" {}
                                          (fn [{:keys [a b]}] (+ a b))))]
      (let [result (tr/execute-tool registry :add {:a 2 :b 3})]
        (is (:success result))
        (is (= 5 (:result result))))))

  (testing "执行 Provider 工具"
    (let [provider (-> (local/create-local-provider :math)
                       (local/register-tool! :multiply "乘法" {}
                                             (fn [{:keys [a b]}] (* a b))))
          registry (-> (tr/create-tool-registry)
                       (tr/register-provider! provider))]
      (let [result (tr/execute-tool registry :multiply {:a 3 :b 4})]
        (is (:success result))
        (is (= 12 (:result result))))))

  (testing "执行不存在的工具"
    (let [registry (tr/create-tool-registry)]
      (let [result (tr/execute-tool registry :nonexistent {})]
        (is (not (:success result)))
        (is (re-find #"not found" (:error result)))))))

;;; ============================================================
;;; IToolProvider 协议测试
;;; ============================================================

(deftest tool-provider-protocol-test
  (testing "ToolRegistry 实现 IToolProvider 协议"
    (let [registry (-> (tr/create-tool-registry)
                       (tr/register-tool! :calc "计算" {}
                                          (fn [{:keys [expr]}] (eval (read-string expr)))))]
      (is (satisfies? proto/IToolProvider registry))
      (is (= :tool-registry (proto/provider-name registry)))
      (is (= 1 (count (proto/list-tools registry))))
      (is (some? (proto/get-tool registry :calc))))))

;;; ============================================================
;;; 便捷构造器测试
;;; ============================================================

(deftest from-tools-test
  (testing "从工具列表创建 Registry"
    (let [tools [{:name :a :description "A" :input-schema {} :handler identity}
                 {:name :b :description "B" :input-schema {} :handler identity}]
          registry (tr/from-tools tools)]
      (is (tr/registry? registry))
      (is (= 2 (tr/tool-count registry))))))

(deftest from-providers-test
  (testing "从 Provider 列表创建 Registry"
    (let [p1 (-> (local/create-local-provider :p1)
                 (local/register-tool! :t1 "Tool 1" {} identity))
          p2 (-> (local/create-local-provider :p2)
                 (local/register-tool! :t2 "Tool 2" {} identity))
          registry (tr/from-providers [p1 p2])]
      (is (tr/registry? registry))
      (is (= 2 (tr/provider-count registry)))
      (is (= 2 (tr/tool-count registry))))))

(deftest from-tools-and-providers-test
  (testing "从工具和 Provider 创建 Registry"
    (let [tools [{:name :local-tool :description "Local" :input-schema {} :handler identity}]
          provider (-> (local/create-local-provider :provider)
                       (local/register-tool! :provider-tool "Provider" {} identity))
          registry (tr/from-tools-and-providers tools [provider])]
      (is (tr/registry? registry))
      (is (= 1 (tr/local-tool-count registry)))
      (is (= 1 (tr/provider-count registry)))
      (is (= 2 (tr/tool-count registry))))))

;;; ============================================================
;;; 统计测试
;;; ============================================================

(deftest registry-stats-test
  (testing "获取 Registry 统计信息"
    (let [provider (-> (local/create-local-provider :my-provider)
                       (local/register-tool! :p-tool "Provider 工具" {} identity))
          registry (-> (tr/create-tool-registry :name :test-registry)
                       (tr/register-tool! :local-tool "本地工具" {} identity)
                       (tr/register-provider! provider))
          stats (tr/registry-stats registry)]
      (is (= :test-registry (:name stats)))
      (is (= :local-first (:conflict-resolution stats)))
      (is (= 1 (:local-tools-count stats)))
      (is (= 1 (:providers-count stats)))
      (is (= 2 (:total-tools-count stats)))
      (is (= [:my-provider] (:providers stats)))
      (is (= [:local-tool] (:local-tool-names stats))))))

;;; ============================================================
;;; 与 Agent 集成测试（模拟）
;;; ============================================================

(deftest can-be-used-as-tool-provider-test
  (testing "ToolRegistry 可以作为 IToolProvider 使用"
    (let [registry (-> (tr/create-tool-registry)
                       (tr/register-tool! :greet "问候"
                                          {:type "object" :properties {:name {:type "string"}}}
                                          (fn [{:keys [name]}] (str "Hello, " name "!"))))]
      ;; 模拟 Agent 调用 - 通过 Protocol 方法
      (is (satisfies? proto/IToolProvider registry))

      ;; list-tools 返回工具定义
      (let [tools (proto/list-tools registry)]
        (is (= 1 (count tools)))
        (is (= :greet (:name (first tools)))))

      ;; execute-tool 执行工具
      (let [result (proto/execute-tool registry :greet {:name "World"})]
        (is (:success result))
        (is (= "Hello, World!" (:result result)))))))
