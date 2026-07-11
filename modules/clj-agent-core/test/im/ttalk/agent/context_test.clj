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
;; Phase 1.5: apply-writes（批次写折叠）
;; ============================================================

(deftest apply-writes-test
  (testing "未声明槽：last-writer 按序覆盖 + conflict 上报"
    (let [{:keys [context conflicts]}
          (context/apply-writes {} [{:x 1} {:x 2 :y 9}] nil)]
      (is (= 2 (:x context)) "后写覆盖（按序，确定性）")
      (is (= 9 (:y context)))
      (is (= #{:x} conflicts) "同批写≥2次且无 reducer 的槽上报冲突")))

  (testing "声明 reducer 的槽：折叠合并，无 conflict"
    (let [{:keys [context conflicts]}
          (context/apply-writes {} [{:items "a"} {:items "b"}]
                                {:items {:init [] :reduce conj}})]
      (is (= ["a" "b"] (:items context)))
      (is (empty? conflicts))))

  (testing ":init 仅在 ctx 缺该 key 时垫底"
    (let [{:keys [context]}
          (context/apply-writes {:items ["x"]} [{:items "y"}]
                                {:items {:init [] :reduce conj}})]
      (is (= ["x" "y"] (:items context)))))

  (testing "交换律 reducer（如 +）"
    (let [{:keys [context]}
          (context/apply-writes {:total 0} [{:total 3} {:total 4}]
                                {:total {:init 0 :reduce +}})]
      (is (= 7 (:total context)))))

  (testing "空 writes 序列：原样返回"
    (let [{:keys [context conflicts]} (context/apply-writes {:a 1} [] nil)]
      (is (= {:a 1} context))
      (is (empty? conflicts)))))

;; ============================================================
;; Phase 2: Tool 读只读 context 快照 + :writes 声明写意图
;; ============================================================

(deftool simple-echo
  "简单回显"
  [[text :string "文本"]]
  (str "echo: " text))

(deftool append-item
  "添加项目到列表（ctx 只读；写 delta 走 :writes）"
  [[item :string "项目名"]]
  {:context true}
  (let [items (context/get-var ctx :items [])]
    {:result (str "已添加: " item "（此前 " (count items) " 项）")
     :writes {:items item}}))

(deftool inc-counter
  "增加计数器"
  []
  {:context true}
  (let [c (context/get-var ctx :counter 0)]
    {:result (str "counter=" (inc c))
     :writes {:counter (inc c)}}))

(deftool plain-writer
  "不声明 :context 的普通工具也能写状态（读写正交）"
  [[v :string "值"]]
  {:result (str "写入 " v)
   :writes {:flag v}})

(deftest tool-context-metadata-test
  (testing "普通 tool 无 context 标记"
    (is (not (tool/context-tool? #'simple-echo))))
  (testing "context tool 有标记"
    (is (tool/context-tool? #'append-item))))

(deftest tool-invoke-writes-test
  (testing "context tool 读只读快照，写意图经 :writes 返回（不改 context）"
    (let [ctx (context/create {:items ["apple"]})
          result (tool/invoke #'append-item {:item "banana"} ctx)]
      (is (:success result))
      (is (= "已添加: banana（此前 1 项）" (:result result)))
      (is (= {:items "banana"} (:writes result)))))

  (testing "跨批次累积：writes 经 apply-writes 折叠后进入下一次快照"
    (let [slots {:items {:init [] :reduce conj}}
          step (fn [ctx item]
                 (let [{:keys [writes]} (tool/invoke #'append-item {:item item} ctx)]
                   (:context (context/apply-writes ctx [writes] slots))))
          ctx (-> (context/create {:items []}) (step "a") (step "b") (step "c"))]
      (is (= ["a" "b" "c"] (context/get-var ctx :items)))))

  (testing "普通 1-arity 工具也可返回 :writes（读写正交，无需声明 :context）"
    (let [result (tool/invoke #'plain-writer {:v "on"})]
      (is (:success result))
      (is (= "写入 on" (:result result)))
      (is (= {:flag "on"} (:writes result))))))

;; ============================================================
;; Phase 4: invoke-tool（含 filter）
;; ============================================================

(deftest invoke-tool-test
  (let [kernel (core/build-kernel
                 {:tools [#'simple-echo #'append-item #'inc-counter]})]
    (testing "普通 tool 返回 {:value}，无 :writes"
      (let [result (core/invoke-tool kernel :simple-echo {:text "hi"} (context/create))]
        (is (= "echo: hi" (:value result)))
        (is (nil? (:writes result)))))

    (testing "写状态的 tool：context 只读，写意图经 :writes 透出"
      (let [in-ctx (context/create {:items ["x"]})
            result (core/invoke-tool kernel :append-item {:item "y"} in-ctx)]
        (is (= "已添加: y（此前 1 项）" (:value result)) "工具读到只读快照")
        (is (= {:items "y"} (:writes result)))))

    (testing "多次调用：调用方折叠 writes 后传入下一次快照"
      (let [r1 (core/invoke-tool kernel :inc-counter {} (context/create {:counter 0}))
            c1 (:context (context/apply-writes {:counter 0} [(:writes r1)] nil))
            r2 (core/invoke-tool kernel :inc-counter {} c1)
            c2 (:context (context/apply-writes c1 [(:writes r2)] nil))
            r3 (core/invoke-tool kernel :inc-counter {} c2)]
        (is (= {:counter 3} (:writes r3)))))

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
                        (update resp :result str " [marked]")))}
        kernel (core/build-kernel
                 {:tools [#'simple-echo]
                  :filters [modify mark]})]
    (testing "around 改参数"
      (is (= "echo: modified [marked]"
             (:value (core/invoke-tool kernel :simple-echo {:text "original"} (context/create))))))
    (testing "around 加工响应结果"
      (let [result (core/invoke-tool kernel :simple-echo {:text "x"} (context/create))]
        (is (clojure.string/ends-with? (:value result) " [marked]"))))))

(deftest invoke-tool-skip-filter-test
  (let [blocker {:name :blocker
                 :tool (fn [_req _chain] {:result "blocked by filter"})}
        kernel (core/build-kernel
                 {:tools [#'simple-echo]
                  :filters [blocker]})]
    (testing "filter 不调 chain 直接短路返回（无需回传 context）"
      (is (= "blocked by filter" (:value (core/invoke-tool kernel :simple-echo {:text "hi"} (context/create))))))))

;; ============================================================
;; invoke-chat
;; ============================================================

(deftest invoke-chat-test
  (let [svc {:chat-fn (fn [_ _] (response/make-response :text "hello response" :tool-calls nil))}
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
                        (response/make-response :text "response" :tool-calls nil))}
        kernel (core/build-kernel
                 {:service svc
                  :filters [inject]})]
    (testing "chat filter 修改传给 LLM 的消息"
      (reset! received nil)
      (core/invoke-chat kernel [{:role :user :content "hi"}] {})
      (is (= :system (:role (first @received))))
      (is (= "injected" (:content (first @received)))))))
