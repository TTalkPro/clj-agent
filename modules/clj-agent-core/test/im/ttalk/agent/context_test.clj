(ns im.ttalk.agent.context-test
  "Context（扁平 ToolContext）、Filter、Invoke 系统综合测试（Memory Filter 版）"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.context :as context]
            [im.ttalk.agent.tool :as tool :refer [deftool]]
            [im.ttalk.agent.advisor :as filters]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.kernel :as core]))

;; ============================================================
;; Phase 1: ToolContext（扁平 map）
;; ============================================================

(deftest context-create-test
  (testing "创建空 context 为扁平空 map"
    (let [ctx (context/create)]
      (is (context/context? ctx))
      (is (= {} ctx))))

  (testing "创建带变量的 context"
    (let [ctx (context/create {:user-id "u123" :lang "zh"})]
      (is (= "u123" (context/get-var ctx :user-id)))
      (is (= "zh" (context/get-var ctx :lang)))))

  (testing "context? 判断（任意 map）"
    (is (context/context? (context/create)))
    (is (context/context? {}))
    (is (not (context/context? nil)))
    (is (not (context/context? "x")))))

(deftest context-variables-test
  (testing "get-var 默认值"
    (let [ctx (context/create)]
      (is (nil? (context/get-var ctx :missing)))
      (is (= "default" (context/get-var ctx :missing "default")))))

  (testing "set-var"
    (let [ctx (-> (context/create)
                  (context/set-var :name "Alice")
                  (context/set-var :age 30))]
      (is (= "Alice" (context/get-var ctx :name)))
      (is (= 30 (context/get-var ctx :age)))))

  (testing "set-vars 批量设置"
    (let [ctx (-> (context/create {:x 1})
                  (context/set-vars {:y 2 :z 3}))]
      (is (= 1 (context/get-var ctx :x)))
      (is (= 2 (context/get-var ctx :y)))
      (is (= 3 (context/get-var ctx :z)))))

  (testing "conversation-id 读写"
    (let [ctx (context/with-conversation-id (context/create) "s1")]
      (is (= "s1" (context/conversation-id ctx))))))

;; ============================================================
;; Phase 2: Tool 函数接收和返回 context（扁平）
;; ============================================================

(deftool simple-echo
  "简单回显"
  [[text :string "文本"]]
  (str "echo: " text))

(deftool append-item
  "添加项目到列表"
  [[item :string "项目名"]]
  {:context true}
  (let [items (context/get-var ctx :items [])]
    {:result (str "已添加: " item)
     :context (context/set-var ctx :items (conj items item))}))

(deftool inc-counter
  "增加计数器"
  []
  {:context true}
  (let [c (context/get-var ctx :counter 0)]
    {:result (str "counter=" (inc c))
     :context (context/set-var ctx :counter (inc c))}))

(deftest tool-context-metadata-test
  (testing "普通 tool 无 context 标记"
    (is (not (tool/context-tool? #'simple-echo))))
  (testing "context tool 有标记"
    (is (tool/context-tool? #'append-item))))

(deftest tool-invoke-with-context-test
  (testing "context tool 接收并更新扁平 context"
    (let [ctx (context/create {:items ["apple"]})
          result (tool/invoke #'append-item {:item "banana"} ctx)]
      (is (:success result))
      (is (= ["apple" "banana"] (context/get-var (:context result) :items)))))

  (testing "多次调用累积状态"
    (let [r1 (tool/invoke #'append-item {:item "a"} (context/create {:items []}))
          r2 (tool/invoke #'append-item {:item "b"} (:context r1))
          r3 (tool/invoke #'append-item {:item "c"} (:context r2))]
      (is (= ["a" "b" "c"] (context/get-var (:context r3) :items))))))

;; ============================================================
;; Phase 4: invoke-tool（含 filter）
;; ============================================================

(deftest invoke-tool-test
  (let [kernel (core/build-kernel
                 {:tools [#'simple-echo #'append-item #'inc-counter]})]
    (testing "普通 tool 返回 {:value :context}"
      (let [result (core/invoke-tool kernel :simple-echo {:text "hi"} (context/create))]
        (is (= "echo: hi" (:value result)))
        (is (context/context? (:context result)))))

    (testing "context tool 返回更新的 context"
      (let [result (core/invoke-tool kernel :append-item {:item "y"} (context/create {:items ["x"]}))]
        (is (= ["x" "y"] (context/get-var (:context result) :items)))))

    (testing "多次调用传递 context"
      (let [r1 (core/invoke-tool kernel :inc-counter {} (context/create {:counter 0}))
            r2 (core/invoke-tool kernel :inc-counter {} (:context r1))
            r3 (core/invoke-tool kernel :inc-counter {} (:context r2))]
        (is (= 3 (context/get-var (:context r3) :counter)))))

    (testing "函数未找到抛异常"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"函数未找到"
            (core/invoke-tool kernel :nonexistent {} (context/create)))))))

(deftest invoke-tool-with-filters-test
  (let [modify {:name :modify
                :tool (fn [req chain]
                        (chain (update req :args assoc :text "modified")))}
        mark {:name :mark
              :tool (fn [req chain]
                      (let [resp (chain req)]
                        (update resp :context context/set-var :invoked true)))}
        kernel (core/build-kernel
                 {:tools [#'simple-echo]
                  :filters [modify mark]})]
    (testing "around 改参数"
      (is (= "echo: modified" (:value (core/invoke-tool kernel :simple-echo {:text "original"} (context/create))))))
    (testing "around 改 context"
      (let [result (core/invoke-tool kernel :simple-echo {:text "x"} (context/create))]
        (is (true? (context/get-var (:context result) :invoked)))))))

(deftest invoke-tool-skip-filter-test
  (let [blocker {:name :blocker
                 :tool (fn [req _chain] {:result "blocked by filter" :context (:context req)})}
        kernel (core/build-kernel
                 {:tools [#'simple-echo]
                  :filters [blocker]})]
    (testing "filter 不调 chain 直接短路返回"
      (is (= "blocked by filter" (:value (core/invoke-tool kernel :simple-echo {:text "hi"} (context/create))))))))

;; ============================================================
;; invoke-chat
;; ============================================================

(deftest invoke-chat-test
  (let [svc {:chat-fn (fn [_ _] (response/make-response :text "hello response" :tool-calls nil))
             :build-result-msgs (fn [_ _] [])}
        kernel (core/build-kernel {:service svc})]
    (testing "invoke-chat 返回 {:response :context}"
      (let [result (core/invoke-chat kernel [{:role :user :content "hi"}] {})]
        (is (= "hello response" (get-in result [:response :text])))
        (is (context/context? (:context result)))))
    (testing "无 service 抛异常"
      (let [no-svc (core/build-kernel {})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"未配置 LLM 服务"
              (core/invoke-chat no-svc [{:role :user :content "hi"}] {})))))))

(deftest invoke-chat-with-chat-filter-test
  (let [inject {:name :inject
                :chat (fn [req chain]
                        (chain (update req :messages #(into [{:role :system :content "injected"}] %))))}
        received (atom nil)
        svc {:chat-fn (fn [msgs _] (reset! received msgs)
                        (response/make-response :text "response" :tool-calls nil))
             :build-result-msgs (fn [_ _] [])}
        kernel (core/build-kernel
                 {:service svc
                  :filters [inject]})]
    (testing "chat filter 修改传给 LLM 的消息"
      (reset! received nil)
      (core/invoke-chat kernel [{:role :user :content "hi"}] {})
      (is (= :system (:role (first @received))))
      (is (= "injected" (:content (first @received)))))))
