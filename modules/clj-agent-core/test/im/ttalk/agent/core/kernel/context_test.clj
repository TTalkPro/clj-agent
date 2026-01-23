(ns im.ttalk.agent.core.kernel.context-test
  "Context 贯穿 Kernel 调用链的测试"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.core.kernel.context :as context]
            [im.ttalk.agent.core.kernel.tool :as tool :refer [deftool]]
            [im.ttalk.agent.core.kernel.plugin :as plugin]
            [im.ttalk.agent.core.kernel.filter :as filters]
            [im.ttalk.agent.core.kernel.core :as core]))

;; ============================================================
;; Phase 1: Context 创建和变量操作
;; ============================================================

(deftest context-create-test
  (testing "创建空 context"
    (let [ctx (context/create)]
      (is (context/context? ctx))
      (is (= {} (:variables ctx)))
      (is (= [] (:history ctx)))
      (is (nil? (:kernel ctx)))
      (is (= [] (:trace ctx)))
      (is (= {} (:metadata ctx)))))

  (testing "创建带变量的 context"
    (let [ctx (context/create {:user-id "u123" :lang "zh"})]
      (is (context/context? ctx))
      (is (= "u123" (context/get-var ctx :user-id)))
      (is (= "zh" (context/get-var ctx :lang)))))

  (testing "context? 判断"
    (is (context/context? (context/create)))
    (is (not (context/context? {})))
    (is (not (context/context? nil)))
    (is (not (context/context? {:__context__ false})))))

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

  (testing "set-var 覆盖已有值"
    (let [ctx (-> (context/create {:x 1})
                  (context/set-var :x 99))]
      (is (= 99 (context/get-var ctx :x))))))

(deftest context-history-test
  (testing "空历史"
    (let [ctx (context/create)]
      (is (= [] (context/get-history ctx)))))

  (testing "添加消息"
    (let [ctx (-> (context/create)
                  (context/add-message {:role "user" :content "hello"})
                  (context/add-message {:role "assistant" :content "hi"}))]
      (is (= 2 (count (context/get-history ctx))))
      (is (= "hello" (:content (first (context/get-history ctx))))))))

(deftest context-kernel-test
  (testing "关联 kernel"
    (let [kernel {:test true}
          ctx (context/with-kernel (context/create) kernel)]
      (is (= kernel (context/get-kernel ctx)))))

  (testing "无 kernel"
    (is (nil? (context/get-kernel (context/create))))))

(deftest context-trace-test
  (testing "添加跟踪"
    (let [ctx (-> (context/create)
                  (context/add-trace {:type :tool-call :data "get-weather"})
                  (context/add-trace {:type :tool-result :data "sunny"}))]
      (is (= 2 (count (context/get-trace ctx))))
      (is (= :tool-call (:type (first (context/get-trace ctx)))))
      (is (number? (:timestamp (first (context/get-trace ctx))))))))

(deftest context-metadata-test
  (testing "设置和获取元数据"
    (let [ctx (-> (context/create)
                  (context/set-metadata :version "1.0")
                  (context/set-metadata :env "test"))]
      (is (= "1.0" (:version (context/get-metadata ctx))))
      (is (= "test" (:env (context/get-metadata ctx)))))))

(deftest context-invocation-context-test
  (testing "创建调用上下文"
    (let [tool-ctx (context/create-tool-context :get-weather {:city "北京"} "toolu_123")
          my-ctx (context/create {:user-id "u1"})
          inv-ctx (context/create-invocation-context tool-ctx :fake-kernel [] my-ctx)]
      (is (= :get-weather (:tool-name inv-ctx)))
      (is (= {:city "北京"} (:tool-args inv-ctx)))
      (is (= "toolu_123" (:tool-id inv-ctx)))
      (is (= :fake-kernel (:kernel inv-ctx)))
      (is (= [] (:history inv-ctx)))
      (is (context/context? (:context inv-ctx)))
      (is (= "u1" (context/get-var (:context inv-ctx) :user-id)))))

  (testing "创建调用上下文（无 context 参数）"
    (let [tool-ctx (context/create-tool-context :test {} nil)
          inv-ctx (context/create-invocation-context tool-ctx nil [])]
      (is (context/context? (:context inv-ctx)))
      (is (= {} (:variables (:context inv-ctx)))))))

;; ============================================================
;; Phase 2: Tool 函数接收和返回 context
;; ============================================================

;; 普通 tool（不需要 context）
(deftool simple-echo
  "简单回显"
  [[text :string "文本"]]
  (str "echo: " text))

;; 需要 context 的 tool
(deftool append-item
  "添加项目到列表"
  [[item :string "项目名"]]
  {:context true}
  (let [items (context/get-var ctx :items [])]
    {:result (str "已添加: " item)
     :context (context/set-var ctx :items (conj items item))}))

;; 需要 context 但不修改的 tool
(deftool read-counter
  "读取计数器"
  []
  {:context true}
  (let [counter (context/get-var ctx :counter 0)]
    {:result (str "counter=" counter)
     :context ctx}))

(deftest tool-context-metadata-test
  (testing "普通 tool 无 context 标记"
    (is (not (tool/context-tool? #'simple-echo))))

  (testing "context tool 有标记"
    (is (tool/context-tool? #'append-item))
    (is (tool/context-tool? #'read-counter))))

(deftest tool-invoke-without-context-test
  (testing "普通 tool invoke 不传 context"
    (let [result (tool/invoke #'simple-echo {:text "hello"})]
      (is (:success result))
      (is (= "echo: hello" (:result result)))
      (is (not (contains? result :context)))))

  (testing "普通 tool invoke 传 context 无影响"
    (let [ctx (context/create {:x 1})
          result (tool/invoke #'simple-echo {:text "hi"} ctx)]
      (is (:success result))
      (is (= "echo: hi" (:result result))))))

(deftest tool-invoke-with-context-test
  (testing "context tool 接收 context 并更新"
    (let [ctx (context/create {:items ["apple"]})
          result (tool/invoke #'append-item {:item "banana"} ctx)]
      (is (:success result))
      (is (= "已添加: banana" (:result result)))
      (is (context/context? (:context result)))
      (is (= ["apple" "banana"]
             (context/get-var (:context result) :items)))))

  (testing "context tool 多次调用累积状态"
    (let [ctx0 (context/create {:items []})
          r1 (tool/invoke #'append-item {:item "a"} ctx0)
          r2 (tool/invoke #'append-item {:item "b"} (:context r1))
          r3 (tool/invoke #'append-item {:item "c"} (:context r2))]
      (is (= ["a" "b" "c"]
             (context/get-var (:context r3) :items)))))

  (testing "context tool 读取变量"
    (let [ctx (context/create {:counter 42})
          result (tool/invoke #'read-counter {} ctx)]
      (is (= "counter=42" (:result result)))
      (is (= 42 (context/get-var (:context result) :counter))))))

;; ============================================================
;; Phase 3: Filter 读写 context variables
;; ============================================================

(deftest filter-context-access-test
  (testing "filter 读取 context 变量"
    (let [seen-user (atom nil)
          my-filter (fn [ctx next-fn]
                      (reset! seen-user
                              (context/get-var (:context ctx) :user-id))
                      (next-fn ctx))
          exec-fn (fn [ctx]
                    {:tool-id "t1" :name :test :result "ok"
                     :context (:context ctx)})
          chain (filters/build-filter-chain [my-filter] exec-fn)
          my-ctx (context/create {:user-id "u999"})
          filter-ctx {:tool-name :test :tool-args {}
                      :tool-id "t1" :context my-ctx}]
      (chain filter-ctx)
      (is (= "u999" @seen-user))))

  (testing "filter 修改 context 变量"
    (let [my-filter (fn [ctx next-fn]
                      ;; 修改 context 后传给下游
                      (let [updated-ctx (update ctx :context
                                                context/set-var :enriched true)]
                        (next-fn updated-ctx)))
          exec-fn (fn [ctx]
                    {:tool-id "t1" :name :test :result "ok"
                     :context (:context ctx)})
          chain (filters/build-filter-chain [my-filter] exec-fn)
          result (chain {:tool-name :test :tool-args {}
                         :tool-id "t1" :context (context/create)})]
      (is (true? (context/get-var (:context result) :enriched)))))

  (testing "filter 在结果中添加 trace"
    (let [my-filter (fn [ctx next-fn]
                      (let [result (next-fn ctx)]
                        (update result :context
                                context/add-trace {:type :filter :data "processed"})))
          exec-fn (fn [ctx]
                    {:tool-id "t1" :name :test :result "ok"
                     :context (:context ctx)})
          chain (filters/build-filter-chain [my-filter] exec-fn)
          result (chain {:tool-name :test :tool-args {}
                         :tool-id "t1" :context (context/create)})]
      (is (= 1 (count (context/get-trace (:context result)))))
      (is (= :filter (:type (first (context/get-trace (:context result)))))))))

;; ============================================================
;; Phase 4 & 5: Invoke 传入/返回 context
;; ============================================================

;; 用于测试的 tool
(deftool inc-counter
  "增加计数器"
  []
  {:context true}
  (let [c (context/get-var ctx :counter 0)]
    {:result (str "counter=" (inc c))
     :context (context/set-var ctx :counter (inc c))}))

(deftest invoke-with-context-test
  (let [plugin (plugin/create-plugin :test "测试插件"
                                     [#'simple-echo #'append-item #'inc-counter])
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-plugin plugin)))]

    (testing "invoke 普通 tool 返回空 context"
      (let [result (core/invoke kernel :simple-echo {:text "hi"})]
        (is (:success result))
        (is (= "echo: hi" (:result result)))))

    (testing "invoke context tool 返回更新的 context"
      (let [ctx (context/create {:items ["x"]})
            result (core/invoke kernel :append-item {:item "y"} ctx)]
        (is (:success result))
        (is (= "已添加: y" (:result result)))
        (is (= ["x" "y"] (context/get-var (:context result) :items)))))

    (testing "invoke context tool 多次调用传递 context"
      (let [ctx0 (context/create {:counter 0})
            r1 (core/invoke kernel :inc-counter {} ctx0)
            r2 (core/invoke kernel :inc-counter {} (:context r1))
            r3 (core/invoke kernel :inc-counter {} (:context r2))]
        (is (= 3 (context/get-var (:context r3) :counter)))))))

;; ============================================================
;; Phase 5: 工具调用循环中 context 逐步累积
;; ============================================================

(deftest invoke-chat-with-tools-context-test
  (let [;; 定义会修改 context 的工具
        plugin (plugin/create-plugin :cart "购物车插件"
                                     [#'append-item #'inc-counter])
        ;; 模拟 LLM 服务：第一次返回 tool_call，第二次返回文本
        call-count (atom 0)
        mock-service
        {:chat-fn
         (fn [_msgs _opts]
           (let [n (swap! call-count inc)]
             (case n
               ;; 第一次调用：LLM 返回两个 tool_calls
               1 {:text nil
                  :tool-calls [{:id "tc1" :name "append-item"
                                :input {:item "book"}}
                               {:id "tc2" :name "append-item"
                                :input {:item "pen"}}]
                  :assistant-msg {:role "assistant"
                                  :content nil
                                  :tool_calls [{:id "tc1"} {:id "tc2"}]}}
               ;; 第二次调用：LLM 返回文本
               {:text "已添加 book 和 pen 到您的列表"
                :tool-calls nil
                :assistant-msg {:role "assistant"
                                :content "已添加 book 和 pen 到您的列表"}})))
         :build-result-msgs
         (fn [assistant-msg results]
           (into [{:role "assistant" :content "" :tool_calls (:tool_calls assistant-msg)}]
                 (mapv (fn [r]
                         {:role "tool"
                          :tool_call_id (:tool-id r)
                          :content (or (:result r) (:error r) "")})
                       results)))}
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-plugin plugin)
                     (core/add-service mock-service)))]

    (testing "工具调用循环中 context 逐步累积"
      (reset! call-count 0)
      (let [ctx (context/create {:items [] :user-id "u123"})
            result (core/invoke-chat-with-tools kernel
                     [{:role "user" :content "添加 book 和 pen"}]
                     {:context ctx})]
        ;; 验证最终文本
        (is (= "已添加 book 和 pen 到您的列表" (:text result)))
        ;; 验证 context 中的 items 累积了两个工具调用的结果
        (is (= ["book" "pen"]
               (context/get-var (:context result) :items)))
        ;; 原始变量保持不变
        (is (= "u123"
               (context/get-var (:context result) :user-id)))
        ;; 验证有工具调用记录
        (is (= 2 (count (:tool-calls-made result))))))))

(deftest invoke-chat-with-tools-multi-iteration-context-test
  (let [plugin (plugin/create-plugin :counter "计数器插件" [#'inc-counter])
        call-count (atom 0)
        mock-service
        {:chat-fn
         (fn [_msgs _opts]
           (let [n (swap! call-count inc)]
             (if (<= n 3)
               ;; 前三次调用都返回 tool_call
               {:text nil
                :tool-calls [{:id (str "tc" n) :name "inc-counter" :input {}}]
                :assistant-msg {:role "assistant" :content nil
                                :tool_calls [{:id (str "tc" n)}]}}
               ;; 第四次返回文本
               {:text "计数完成"
                :tool-calls nil
                :assistant-msg {:role "assistant" :content "计数完成"}})))
         :build-result-msgs
         (fn [assistant-msg results]
           (into [{:role "assistant" :content "" :tool_calls (:tool_calls assistant-msg)}]
                 (mapv (fn [r]
                         {:role "tool"
                          :tool_call_id (:tool-id r)
                          :content (or (:result r) (:error r) "")})
                       results)))}
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-plugin plugin)
                     (core/add-service mock-service)))]

    (testing "多轮工具调用中 context 持续累积"
      (reset! call-count 0)
      (let [ctx (context/create {:counter 0})
            result (core/invoke-chat-with-tools kernel
                     [{:role "user" :content "计数三次"}]
                     {:context ctx})]
        (is (= "计数完成" (:text result)))
        ;; counter 累积了 3 次
        (is (= 3 (context/get-var (:context result) :counter)))
        (is (= 3 (count (:tool-calls-made result))))))))

(deftest invoke-chat-with-tools-filter-context-test
  (let [plugin (plugin/create-plugin :items "项目插件" [#'append-item])
        call-count (atom 0)
        ;; 会修改 context 的 filter
        tracking-filter (fn [ctx next-fn]
                          (let [result (next-fn ctx)]
                            (update result :context
                                    context/add-trace
                                    {:type :filter-track
                                     :tool (:tool-name ctx)})))
        mock-service
        {:chat-fn
         (fn [_msgs _opts]
           (let [n (swap! call-count inc)]
             (if (= n 1)
               {:text nil
                :tool-calls [{:id "tc1" :name "append-item" :input {:item "x"}}]
                :assistant-msg {:role "assistant" :content nil
                                :tool_calls [{:id "tc1"}]}}
               {:text "done"
                :tool-calls nil
                :assistant-msg {:role "assistant" :content "done"}})))
         :build-result-msgs
         (fn [assistant-msg results]
           (into [{:role "assistant" :content "" :tool_calls (:tool_calls assistant-msg)}]
                 (mapv (fn [r]
                         {:role "tool"
                          :tool_call_id (:tool-id r)
                          :content (or (:result r) (:error r) "")})
                       results)))}
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-plugin plugin)
                     (core/add-filter tracking-filter)
                     (core/add-service mock-service)))]

    (testing "filter 的 context 修改也被累积"
      (reset! call-count 0)
      (let [ctx (context/create {:items []})
            result (core/invoke-chat-with-tools kernel
                     [{:role "user" :content "add x"}]
                     {:context ctx})]
        (is (= "done" (:text result)))
        ;; tool 的修改
        (is (= ["x"] (context/get-var (:context result) :items)))
        ;; filter 的 trace 修改
        (is (= 1 (count (context/get-trace (:context result)))))
        (is (= :filter-track
               (:type (first (context/get-trace (:context result))))))))))

(deftest invoke-chat-with-tools-no-context-test
  (let [plugin (plugin/create-plugin :echo "回显插件" [#'simple-echo])
        call-count (atom 0)
        mock-service
        {:chat-fn
         (fn [_msgs _opts]
           (let [n (swap! call-count inc)]
             (if (= n 1)
               {:text nil
                :tool-calls [{:id "tc1" :name "simple-echo" :input {:text "hi"}}]
                :assistant-msg {:role "assistant" :content nil
                                :tool_calls [{:id "tc1"}]}}
               {:text "done"
                :tool-calls nil
                :assistant-msg {:role "assistant" :content "done"}})))
         :build-result-msgs
         (fn [assistant-msg results]
           (into [{:role "assistant" :content "" :tool_calls (:tool_calls assistant-msg)}]
                 (mapv (fn [r]
                         {:role "tool"
                          :tool_call_id (:tool-id r)
                          :content (or (:result r) (:error r) "")})
                       results)))}
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-plugin plugin)
                     (core/add-service mock-service)))]

    (testing "不传 context 时使用默认空 context"
      (reset! call-count 0)
      (let [result (core/invoke-chat-with-tools kernel
                     [{:role "user" :content "echo hi"}]
                     {})]
        (is (= "done" (:text result)))
        ;; 返回的 context 是默认创建的空 context
        (is (context/context? (:context result)))
        (is (= {} (:variables (:context result))))))))

;; ============================================================
;; 向后兼容性测试
;; ============================================================

(deftest backward-compatibility-test
  (let [plugin (plugin/create-plugin :test "测试" [#'simple-echo])
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-plugin plugin)))]

    (testing "invoke 不传 context 行为不变"
      (let [result (core/invoke kernel :simple-echo {:text "test"})]
        (is (:success result))
        (is (= "echo: test" (:result result)))))

    (testing "plugin/execute-tool 不传 context 行为不变"
      (let [result (plugin/execute-tool plugin :simple-echo {:text "hello"})]
        (is (:success result))
        (is (= "echo: hello" (:result result)))))

    (testing "tool/invoke 不传 context 行为不变"
      (let [result (tool/invoke #'simple-echo {:text "world"})]
        (is (:success result))
        (is (= "echo: world" (:result result)))))))
