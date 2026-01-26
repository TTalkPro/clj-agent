(ns im.ttalk.agent.core.kernel.context-test
  "Context、Filter、Invoke 系统综合测试"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.core.kernel.context :as context]
            [im.ttalk.agent.core.kernel.tool :as tool :refer [deftool]]
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
      (is (= [] (:messages ctx)))
      (is (= [] (:history ctx)))
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

;; ============================================================
;; Phase 1.5: Messages/History 双轨消息测试
;; ============================================================

(deftest context-messages-test
  (testing "空 messages"
    (let [ctx (context/create)]
      (is (= [] (context/get-messages ctx)))))

  (testing "append-message"
    (let [ctx (-> (context/create)
                  (context/append-message {:role "user" :content "hello"})
                  (context/append-message {:role "assistant" :content "hi"}))]
      (is (= 2 (count (context/get-messages ctx))))
      (is (= "hello" (:content (first (context/get-messages ctx)))))))

  (testing "set-messages 替换"
    (let [ctx (-> (context/create)
                  (context/append-message {:role "user" :content "old"})
                  (context/set-messages [{:role "system" :content "summary"}]))]
      (is (= 1 (count (context/get-messages ctx))))
      (is (= "summary" (:content (first (context/get-messages ctx))))))))

(deftest context-history-test
  (testing "空历史"
    (let [ctx (context/create)]
      (is (= [] (context/get-history ctx)))))

  (testing "add-history"
    (let [ctx (-> (context/create)
                  (context/add-history {:role "user" :content "hello"})
                  (context/add-history {:role "assistant" :content "hi"}))]
      (is (= 2 (count (context/get-history ctx))))
      (is (= "hello" (:content (first (context/get-history ctx))))))))

(deftest context-track-message-test
  (testing "track-message 同时追加到 messages 和 history"
    (let [msg {:role "user" :content "hello"}
          ctx (context/track-message (context/create) msg)]
      (is (= [msg] (context/get-messages ctx)))
      (is (= [msg] (context/get-history ctx)))))

  (testing "track-message 多次调用累积"
    (let [ctx (-> (context/create)
                  (context/track-message {:role "user" :content "q1"})
                  (context/track-message {:role "assistant" :content "a1"})
                  (context/track-message {:role "user" :content "q2"}))]
      (is (= 3 (count (context/get-messages ctx))))
      (is (= 3 (count (context/get-history ctx))))))

  (testing "messages 可 set 而 history 只追加"
    (let [ctx (-> (context/create)
                  (context/track-message {:role "user" :content "q1"})
                  (context/track-message {:role "assistant" :content "a1"})
                  ;; summarize：重置 messages 但 history 不变
                  (context/set-messages [{:role "system" :content "summary"}])
                  ;; 继续 track
                  (context/track-message {:role "user" :content "q2"}))]
      ;; messages: summary + q2
      (is (= 2 (count (context/get-messages ctx))))
      (is (= "summary" (:content (first (context/get-messages ctx)))))
      ;; history: q1 + a1 + q2（只追加）
      (is (= 3 (count (context/get-history ctx))))
      (is (= "q1" (:content (first (context/get-history ctx))))))))

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
;; Phase 3: Filter 4类型管道测试
;; ============================================================

(deftest filter-create-test
  (testing "创建 filter 定义"
    (let [f (filters/create-filter :test-f :pre-invocation
              (fn [ctx] {:action :continue :context ctx})
              :priority 10)]
      (is (= :test-f (:name f)))
      (is (= :pre-invocation (:type f)))
      (is (fn? (:handler f)))
      (is (= 10 (:priority f)))))

  (testing "默认 priority 为 0"
    (let [f (filters/create-filter :test-f :post-chat
              (fn [ctx] {:action :continue :context ctx}))]
      (is (= 0 (:priority f))))))

(deftest filter-pre-invocation-test
  (testing "pre-invocation filter 修改参数"
    (let [f (filters/create-filter :modify-args :pre-invocation
              (fn [filter-ctx]
                {:action :continue
                 :context (update filter-ctx :args assoc :extra "injected")}))
          result (filters/apply-pre-invocation-filters
                   [f]
                   {:name :test-fn}
                   {:x 1}
                   (context/create))]
      (is (contains? result :ok))
      (is (= "injected" (get-in result [:ok :args :extra])))
      (is (= 1 (get-in result [:ok :args :x])))))

  (testing "pre-invocation filter 跳过执行"
    (let [f (filters/create-filter :skip-f :pre-invocation
              (fn [_] {:action :skip :value "skipped!"}))
          result (filters/apply-pre-invocation-filters
                   [f]
                   {:name :test-fn}
                   {:x 1}
                   (context/create))]
      (is (= "skipped!" (:skip result)))))

  (testing "pre-invocation filter 报错"
    (let [f (filters/create-filter :err-f :pre-invocation
              (fn [_] {:action :error :reason "not allowed"}))
          result (filters/apply-pre-invocation-filters
                   [f]
                   {:name :test-fn}
                   {}
                   (context/create))]
      (is (= "not allowed" (:error result)))))

  (testing "多个 pre-invocation filter 按 priority 执行"
    (let [order (atom [])
          f1 (filters/create-filter :f1 :pre-invocation
               (fn [ctx] (swap! order conj :f1) {:action :continue :context ctx})
               :priority 10)
          f2 (filters/create-filter :f2 :pre-invocation
               (fn [ctx] (swap! order conj :f2) {:action :continue :context ctx})
               :priority 5)
          _ (filters/apply-pre-invocation-filters
              [f1 f2]
              {:name :test-fn}
              {}
              (context/create))]
      ;; f2 (priority 5) 先执行
      (is (= [:f2 :f1] @order)))))

(deftest filter-post-invocation-test
  (testing "post-invocation filter 修改结果"
    (let [f (filters/create-filter :modify-result :post-invocation
              (fn [filter-ctx]
                {:action :continue
                 :context (update filter-ctx :result str " [modified]")}))
          result (filters/apply-post-invocation-filters
                   [f]
                   {:name :test-fn}
                   {:x 1}
                   "original"
                   (context/create))]
      (is (= "original [modified]" (get-in result [:ok :result])))))

  (testing "post-invocation filter 修改 context"
    (let [f (filters/create-filter :track-f :post-invocation
              (fn [filter-ctx]
                {:action :continue
                 :context (update filter-ctx :context
                                  context/add-trace {:type :post-filter})}))
          result (filters/apply-post-invocation-filters
                   [f]
                   {:name :test-fn}
                   {}
                   "value"
                   (context/create))]
      (is (= 1 (count (context/get-trace (get-in result [:ok :context]))))))))

(deftest filter-pre-chat-test
  (testing "pre-chat filter 注入 system prompt"
    (let [f (filters/create-filter :inject-system :pre-chat
              (fn [filter-ctx]
                {:action :continue
                 :context (update filter-ctx :messages
                                  #(into [{:role "system" :content "You are helpful"}] %))}))
          result (filters/apply-pre-chat-filters
                   [f]
                   [{:role "user" :content "hi"}]
                   (context/create))]
      (is (= 2 (count (get-in result [:ok :messages]))))
      (is (= "system" (:role (first (get-in result [:ok :messages]))))))))

(deftest filter-post-chat-test
  (testing "post-chat filter 修改响应"
    (let [f (filters/create-filter :modify-resp :post-chat
              (fn [filter-ctx]
                {:action :continue
                 :context (update filter-ctx :response
                                  assoc :text "modified response")}))
          result (filters/apply-post-chat-filters
                   [f]
                   {:text "original" :tool-calls nil}
                   (context/create))]
      (is (= "modified response" (get-in result [:ok :response :text]))))))

(deftest filter-type-isolation-test
  (testing "不同类型的 filter 互不干扰"
    (let [pre-inv (filters/create-filter :pre :pre-invocation
                    (fn [ctx] {:action :continue :context ctx}))
          post-inv (filters/create-filter :post :post-invocation
                     (fn [ctx] {:action :continue :context ctx}))
          pre-chat (filters/create-filter :pre-c :pre-chat
                     (fn [ctx] {:action :continue :context ctx}))
          ;; pre-invocation 管道只执行 pre-invocation 类型
          result (filters/apply-pre-invocation-filters
                   [pre-inv post-inv pre-chat]
                   {:name :test}
                   {}
                   (context/create))]
      (is (contains? result :ok)))))

;; ============================================================
;; Phase 4: invoke-tool 测试
;; ============================================================

;; 用于测试的 tool
(deftool inc-counter
  "增加计数器"
  []
  {:context true}
  (let [c (context/get-var ctx :counter 0)]
    {:result (str "counter=" (inc c))
     :context (context/set-var ctx :counter (inc c))}))

(deftest invoke-tool-test
  (let [test-tools [#'simple-echo #'append-item #'inc-counter]
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-tools test-tools)))]

    (testing "invoke-tool 普通 tool 返回 {:value :context}"
      (let [ctx (context/create)
            result (core/invoke-tool kernel :simple-echo {:text "hi"} ctx)]
        (is (= "echo: hi" (:value result)))
        (is (context/context? (:context result)))))

    (testing "invoke-tool context tool 返回更新的 context"
      (let [ctx (context/create {:items ["x"]})
            result (core/invoke-tool kernel :append-item {:item "y"} ctx)]
        (is (= "已添加: y" (:value result)))
        (is (= ["x" "y"] (context/get-var (:context result) :items)))))

    (testing "invoke-tool 多次调用传递 context"
      (let [ctx0 (context/create {:counter 0})
            r1 (core/invoke-tool kernel :inc-counter {} ctx0)
            r2 (core/invoke-tool kernel :inc-counter {} (:context r1))
            r3 (core/invoke-tool kernel :inc-counter {} (:context r2))]
        (is (= 3 (context/get-var (:context r3) :counter)))))

    (testing "invoke-tool 函数未找到抛异常"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"函数未找到"
            (core/invoke-tool kernel :nonexistent {} (context/create)))))))

(deftest invoke-tool-with-filters-test
  (let [test-tools [#'simple-echo #'append-item]
        ;; pre-invocation filter: 修改参数
        pre-f (filters/create-filter :modify :pre-invocation
                (fn [filter-ctx]
                  {:action :continue
                   :context (update filter-ctx :args
                                    assoc :text "modified")}))
        ;; post-invocation filter: 添加 trace
        post-f (filters/create-filter :trace :post-invocation
                 (fn [filter-ctx]
                   {:action :continue
                    :context (update filter-ctx :context
                                     context/add-trace {:type :invoked
                                                        :fn (:name (:function filter-ctx))})}))
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-tools test-tools)
                     (core/add-filter pre-f)
                     (core/add-filter post-f)))]

    (testing "pre-invocation filter 修改参数"
      (let [result (core/invoke-tool kernel :simple-echo {:text "original"} (context/create))]
        ;; filter 将 :text 改为 "modified"
        (is (= "echo: modified" (:value result)))))

    (testing "post-invocation filter 添加 trace"
      (let [result (core/invoke-tool kernel :simple-echo {:text "x"} (context/create))]
        (is (= 1 (count (context/get-trace (:context result)))))
        (is (= :invoked (:type (first (context/get-trace (:context result))))))))))

(deftest invoke-tool-skip-filter-test
  (let [test-tools [#'simple-echo]
        skip-f (filters/create-filter :blocker :pre-invocation
                 (fn [_] {:action :skip :value "blocked by filter"}))
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-tools test-tools)
                     (core/add-filter skip-f)))]

    (testing "pre-invocation skip 直接返回值"
      (let [result (core/invoke-tool kernel :simple-echo {:text "hi"} (context/create))]
        (is (= "blocked by filter" (:value result)))))))

;; ============================================================
;; Phase 5: invoke（工具调用循环）测试
;; ============================================================

(deftest invoke-test
  (let [cart-tools [#'append-item #'inc-counter]
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
                          :content (or (:result r) "")})
                       results)))}
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-tools cart-tools)
                     (core/add-service mock-service)))]

    (testing "工具调用循环中 context 逐步累积"
      (reset! call-count 0)
      (let [ctx (context/create {:items [] :user-id "u123"})
            result (core/invoke kernel
                     [{:role "user" :content "添加 book 和 pen"}]
                     {:context ctx})]
        ;; 验证最终响应
        (is (= "已添加 book 和 pen 到您的列表" (get-in result [:response :text])))
        ;; 验证 context 中的 items 累积了两个工具调用的结果
        (is (= ["book" "pen"]
               (context/get-var (:context result) :items)))
        ;; 原始变量保持不变
        (is (= "u123"
               (context/get-var (:context result) :user-id)))
        ;; 验证有工具调用记录
        (is (= 2 (count (:tool-calls-made result))))))))

(deftest invoke-multi-iteration-test
  (let [counter-tools [#'inc-counter]
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
                          :content (or (:result r) "")})
                       results)))}
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-tools counter-tools)
                     (core/add-service mock-service)))]

    (testing "多轮工具调用中 context 持续累积"
      (reset! call-count 0)
      (let [ctx (context/create {:counter 0})
            result (core/invoke kernel
                     [{:role "user" :content "计数三次"}]
                     {:context ctx})]
        (is (= "计数完成" (get-in result [:response :text])))
        ;; counter 累积了 3 次
        (is (= 3 (context/get-var (:context result) :counter)))
        (is (= 3 (count (:tool-calls-made result))))))))

(deftest invoke-with-filter-test
  (let [items-tools [#'append-item]
        call-count (atom 0)
        ;; post-invocation filter 添加 trace
        tracking-filter (filters/create-filter :track :post-invocation
                          (fn [filter-ctx]
                            {:action :continue
                             :context (update filter-ctx :context
                                              context/add-trace
                                              {:type :filter-track
                                               :tool (:name (:function filter-ctx))})}))
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
                          :content (or (:result r) "")})
                       results)))}
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-tools items-tools)
                     (core/add-filter tracking-filter)
                     (core/add-service mock-service)))]

    (testing "filter 的 context 修改也被累积"
      (reset! call-count 0)
      (let [ctx (context/create {:items []})
            result (core/invoke kernel
                     [{:role "user" :content "add x"}]
                     {:context ctx})]
        (is (= "done" (get-in result [:response :text])))
        ;; tool 的修改
        (is (= ["x"] (context/get-var (:context result) :items)))
        ;; filter 的 trace 修改
        (is (= 1 (count (context/get-trace (:context result)))))
        (is (= :filter-track
               (:type (first (context/get-trace (:context result))))))))))

(deftest invoke-no-context-test
  (let [echo-tools [#'simple-echo]
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
                          :content (or (:result r) "")})
                       results)))}
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-tools echo-tools)
                     (core/add-service mock-service)))]

    (testing "不传 context 时使用默认空 context"
      (reset! call-count 0)
      (let [result (core/invoke kernel
                     [{:role "user" :content "echo hi"}]
                     {})]
        (is (= "done" (get-in result [:response :text])))
        ;; 返回的 context 是默认创建的空 context
        (is (context/context? (:context result)))
        (is (= {} (:variables (:context result))))))))

(deftest invoke-context-messages-accumulation-test
  (let [echo-tools2 [#'simple-echo]
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
                          :content (or (:result r) "")})
                       results)))}
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-tools echo-tools2)
                     (core/add-service mock-service)))]

    (testing "invoke 后 context.messages 和 history 累积消息"
      (reset! call-count 0)
      (let [result (core/invoke kernel
                     [{:role "user" :content "test"}]
                     {})]
        ;; messages 和 history 都应该有消息记录
        (is (pos? (count (context/get-messages (:context result)))))
        (is (pos? (count (context/get-history (:context result)))))
        ;; messages 和 history 长度应该相同（没有 summarize）
        (is (= (count (context/get-messages (:context result)))
               (count (context/get-history (:context result)))))))))

(deftest invoke-system-prompts-test
  (let [echo-tools3 [#'simple-echo]
        received-opts (atom nil)
        mock-service
        {:chat-fn
         (fn [_msgs opts]
           (reset! received-opts opts)
           {:text "ok"
            :tool-calls nil
            :assistant-msg {:role "assistant" :content "ok"}})
         :build-result-msgs (fn [_ _] [])}
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-tools echo-tools3)
                     (core/add-service mock-service)))]

    (testing "system-prompts 通过 :system-prompt 传给 chat-fn"
      (reset! received-opts nil)
      (core/invoke kernel
        [{:role "user" :content "hi"}]
        {:system-prompts [{:role "system" :content "Be helpful"}]})
      (is (= "Be helpful" (:system-prompt @received-opts))))))

;; ============================================================
;; invoke-chat 测试
;; ============================================================

(deftest invoke-chat-test
  (let [mock-service
        {:chat-fn
         (fn [msgs opts]
           {:text "hello response"
            :tool-calls nil
            :assistant-msg {:role "assistant" :content "hello response"}})
         :build-result-msgs (fn [_ _] [])}
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-service mock-service)))]

    (testing "invoke-chat 返回 {:response :context}"
      (let [result (core/invoke-chat kernel
                     [{:role "user" :content "hi"}]
                     {})]
        (is (= "hello response" (get-in result [:response :text])))
        (is (context/context? (:context result)))))

    (testing "invoke-chat 无 service 抛异常"
      (let [no-svc-kernel (core/build-kernel (core/create-kernel-builder))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"未配置 LLM 服务"
              (core/invoke-chat no-svc-kernel [{:role "user" :content "hi"}] {})))))))

(deftest invoke-chat-with-pre-chat-filter-test
  (let [;; pre-chat filter 注入系统消息
        inject-filter (filters/create-filter :inject :pre-chat
                        (fn [filter-ctx]
                          {:action :continue
                           :context (update filter-ctx :messages
                                            #(into [{:role "system" :content "injected"}] %))}))
        received-msgs (atom nil)
        mock-service
        {:chat-fn
         (fn [msgs _opts]
           (reset! received-msgs msgs)
           {:text "response"
            :tool-calls nil
            :assistant-msg {:role "assistant" :content "response"}})
         :build-result-msgs (fn [_ _] [])}
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-filter inject-filter)
                     (core/add-service mock-service)))]

    (testing "pre-chat filter 修改传给 LLM 的消息"
      (reset! received-msgs nil)
      (core/invoke-chat kernel [{:role "user" :content "hi"}] {})
      (is (= "system" (:role (first @received-msgs))))
      (is (= "injected" (:content (first @received-msgs)))))))
