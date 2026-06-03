(ns im.ttalk.agent.core.kernel.context-test
  "Context（扁平 ToolContext）、Filter、Invoke 系统综合测试（Memory Filter 版）"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.core.kernel.context :as context]
            [im.ttalk.agent.core.kernel.tool :as tool :refer [deftool]]
            [im.ttalk.agent.core.kernel.filter :as filters]
            [im.ttalk.agent.core.llm.response :as response]
            [im.ttalk.agent.core.llm.message :as msg]
            [im.ttalk.agent.core.memory :as memory]
            [im.ttalk.agent.core.kernel :as core]))

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
;; Phase 3: Filter 4 类型管道
;; ============================================================

(deftest filter-create-test
  (testing "创建 filter 定义"
    (let [f (filters/create-filter :test-f :pre-invocation
              (fn [ctx] {:action :continue :context ctx})
              :priority 10)]
      (is (= :test-f (:name f)))
      (is (= :pre-invocation (:type f)))
      (is (= 10 (:priority f))))))

(deftest filter-pre-invocation-test
  (testing "修改参数"
    (let [f (filters/create-filter :modify-args :pre-invocation
              (fn [fc] {:action :continue :context (update fc :args assoc :extra "injected")}))
          result (filters/apply-pre-invocation-filters [f] {:name :test-fn} {:x 1} (context/create))]
      (is (= "injected" (get-in result [:ok :args :extra])))
      (is (= 1 (get-in result [:ok :args :x])))))

  (testing "跳过执行"
    (let [f (filters/create-filter :skip-f :pre-invocation (fn [_] {:action :skip :value "skipped!"}))
          result (filters/apply-pre-invocation-filters [f] {:name :test-fn} {:x 1} (context/create))]
      (is (= "skipped!" (:skip result)))))

  (testing "报错"
    (let [f (filters/create-filter :err-f :pre-invocation (fn [_] {:action :error :reason "not allowed"}))
          result (filters/apply-pre-invocation-filters [f] {:name :test-fn} {} (context/create))]
      (is (= "not allowed" (:error result)))))

  (testing "按 priority 执行"
    (let [order (atom [])
          f1 (filters/create-filter :f1 :pre-invocation
               (fn [c] (swap! order conj :f1) {:action :continue :context c}) :priority 10)
          f2 (filters/create-filter :f2 :pre-invocation
               (fn [c] (swap! order conj :f2) {:action :continue :context c}) :priority 5)]
      (filters/apply-pre-invocation-filters [f1 f2] {:name :test-fn} {} (context/create))
      (is (= [:f2 :f1] @order)))))

(deftest filter-post-invocation-test
  (testing "修改结果"
    (let [f (filters/create-filter :modify-result :post-invocation
              (fn [fc] {:action :continue :context (update fc :result str " [modified]")}))
          result (filters/apply-post-invocation-filters [f] {:name :test-fn} {:x 1} "original" (context/create))]
      (is (= "original [modified]" (get-in result [:ok :result])))))

  (testing "修改 context（set-var）"
    (let [f (filters/create-filter :mark :post-invocation
              (fn [fc] {:action :continue
                        :context (update fc :context context/set-var :marked true)}))
          result (filters/apply-post-invocation-filters [f] {:name :test-fn} {} "value" (context/create))]
      (is (true? (context/get-var (get-in result [:ok :context]) :marked))))))

(deftest filter-pre-chat-test
  (testing "注入 system 消息"
    (let [f (filters/create-filter :inject-system :pre-chat
              (fn [fc] {:action :continue
                        :context (update fc :messages #(into [{:role "system" :content "You are helpful"}] %))}))
          result (filters/apply-pre-chat-filters [f] [{:role "user" :content "hi"}] (context/create))]
      (is (= 2 (count (get-in result [:ok :messages]))))
      (is (= "system" (:role (first (get-in result [:ok :messages]))))))))

(deftest filter-post-chat-test
  (testing "修改响应"
    (let [f (filters/create-filter :modify-resp :post-chat
              (fn [fc] {:action :continue :context (update fc :response assoc :text "modified response")}))
          result (filters/apply-post-chat-filters [f] {:text "original" :tool-calls nil} (context/create))]
      (is (= "modified response" (get-in result [:ok :response :text]))))))

;; ============================================================
;; Phase 4: invoke-tool（含 filter）
;; ============================================================

(deftest invoke-tool-test
  (let [kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-tools [#'simple-echo #'append-item #'inc-counter])))]
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
  (let [pre-f (filters/create-filter :modify :pre-invocation
                (fn [fc] {:action :continue :context (update fc :args assoc :text "modified")}))
        post-f (filters/create-filter :mark :post-invocation
                 (fn [fc] {:action :continue :context (update fc :context context/set-var :invoked true)}))
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-tools [#'simple-echo])
                     (core/add-filter pre-f)
                     (core/add-filter post-f)))]
    (testing "pre 修改参数"
      (is (= "echo: modified" (:value (core/invoke-tool kernel :simple-echo {:text "original"} (context/create))))))
    (testing "post 修改 context"
      (let [result (core/invoke-tool kernel :simple-echo {:text "x"} (context/create))]
        (is (true? (context/get-var (:context result) :invoked)))))))

(deftest invoke-tool-skip-filter-test
  (let [skip-f (filters/create-filter :blocker :pre-invocation (fn [_] {:action :skip :value "blocked by filter"}))
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-tools [#'simple-echo])
                     (core/add-filter skip-f)))]
    (testing "pre skip 直接返回值"
      (is (= "blocked by filter" (:value (core/invoke-tool kernel :simple-echo {:text "hi"} (context/create))))))))

;; ============================================================
;; Phase 5: invoke（工具调用循环，Memory Filter 模式）
;; ============================================================

(defn- mock-service [responses-fn]
  {:chat-fn (fn [_msgs _opts] (responses-fn))
   :build-result-msgs (fn [_ _] [])})

(deftest invoke-tool-loop-accumulates-context-test
  (let [call-count (atom 0)
        svc (mock-service
              (fn []
                (let [n (swap! call-count inc)]
                  (if (= n 1)
                    (response/make-response
                      :text nil
                      :tool-calls [{:id "tc1" :name "append-item" :input {:item "book"}}
                                   {:id "tc2" :name "append-item" :input {:item "pen"}}])
                    (response/make-response :text "已添加 book 和 pen" :tool-calls nil)))))
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-tools [#'append-item])
                     (core/add-service svc)))]
    (testing "工具结果写回 ToolContext，原变量保留，tool-calls-made 记录"
      (reset! call-count 0)
      (let [result (core/invoke kernel
                     [{:role :user :content "添加 book 和 pen"}]
                     {:context (context/create {:items [] :user-id "u123"})})]
        (is (= "已添加 book 和 pen" (get-in result [:response :text])))
        (is (= ["book" "pen"] (context/get-var (:tool-context result) :items)))
        (is (= "u123" (context/get-var (:tool-context result) :user-id)))
        (is (= 2 (count (:tool-calls-made result))))))))

(deftest invoke-multi-iteration-test
  (let [call-count (atom 0)
        svc (mock-service
              (fn []
                (let [n (swap! call-count inc)]
                  (if (<= n 3)
                    (response/make-response :text nil
                      :tool-calls [{:id (str "tc" n) :name "inc-counter" :input {}}])
                    (response/make-response :text "计数完成" :tool-calls nil)))))
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-tools [#'inc-counter])
                     (core/add-service svc)))]
    (testing "多轮工具调用 context 持续累积"
      (reset! call-count 0)
      (let [result (core/invoke kernel
                     [{:role :user :content "计数三次"}]
                     {:context (context/create {:counter 0})})]
        (is (= "计数完成" (get-in result [:response :text])))
        (is (= 3 (context/get-var (:tool-context result) :counter)))
        (is (= 3 (count (:tool-calls-made result))))))))

(deftest invoke-stores-history-in-memory-test
  (let [call-count (atom 0)
        svc (mock-service
              (fn []
                (let [n (swap! call-count inc)]
                  (if (= n 1)
                    (response/make-response :text nil
                      :tool-calls [{:id "tc1" :name "simple-echo" :input {:text "hi"}}])
                    (response/make-response :text "done" :tool-calls nil)))))
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-tools [#'simple-echo])
                     (core/add-service svc)))]
    (testing "带 conversation-id 时历史存进 Kernel 的 ChatMemory store"
      (reset! call-count 0)
      (core/invoke kernel
        [{:role :user :content "echo hi"}]
        {:context (context/with-conversation-id (context/create) "conv-x")})
      (let [stored (memory/mem-get (:memory kernel) "conv-x")]
        ;; user, assistant(tool-calls), tool-result, assistant(text)
        (is (= 4 (count stored)))))))

(deftest invoke-system-prompts-test
  (let [received-opts (atom nil)
        svc {:chat-fn (fn [_msgs opts]
                        (reset! received-opts opts)
                        (response/make-response :text "ok" :tool-calls nil))
             :build-result-msgs (fn [_ _] [])}
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-service svc)))]
    (testing "system-prompts 通过 :system-prompt 传给 chat-fn"
      (reset! received-opts nil)
      (core/invoke kernel
        [{:role :user :content "hi"}]
        {:system-prompts [{:role "system" :content "Be helpful"}]})
      (is (= "Be helpful" (:system-prompt @received-opts))))))

;; ============================================================
;; invoke-chat
;; ============================================================

(deftest invoke-chat-test
  (let [svc {:chat-fn (fn [_ _] (response/make-response :text "hello response" :tool-calls nil))
             :build-result-msgs (fn [_ _] [])}
        kernel (core/build-kernel (-> (core/create-kernel-builder) (core/add-service svc)))]
    (testing "invoke-chat 返回 {:response :context}"
      (let [result (core/invoke-chat kernel [{:role :user :content "hi"}] {})]
        (is (= "hello response" (get-in result [:response :text])))
        (is (context/context? (:context result)))))
    (testing "无 service 抛异常"
      (let [no-svc (core/build-kernel (core/create-kernel-builder))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"未配置 LLM 服务"
              (core/invoke-chat no-svc [{:role :user :content "hi"}] {})))))))

;; ============================================================
;; 外部手搓工具循环（Spring AI 风格：只回传 delta，历史由 Memory Filter 拼）
;; ============================================================

(deftest external-tool-loop-test
  (let [call-count (atom 0)
        svc (mock-service
              (fn []
                (let [n (swap! call-count inc)]
                  (if (= n 1)
                    (response/make-response :text nil
                      :tool-calls [{:id "tc1" :name "simple-echo" :input {:text "hi"}}])
                    (response/make-response :text "done" :tool-calls nil)))))
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-tools [#'simple-echo])
                     (core/add-service svc)))
        cid "ext-1"
        tctx (context/with-conversation-id (context/create) cid)]
    (reset! call-count 0)
    (testing "首轮只发 user，得到 tool_calls（内部不执行）"
      (let [{:keys [response]} (core/invoke-chat kernel [(msg/user "echo hi")] {:context tctx})]
        (is (response/has-tool-calls? response))
        (testing "手动 run-tools 后只回传 tool 结果 delta"
          (let [{:keys [messages]} (core/run-tools kernel (response/response-tool-calls response) tctx)
                {:keys [response]} (core/invoke-chat kernel messages {:context tctx})]
            (is (= "done" (response/response-text response)))))))
    (testing "Memory store 拼出完整历史"
      ;; user, assistant(tool-calls), tool-result, assistant(text)
      (is (= 4 (count (memory/mem-get (:memory kernel) cid)))))))

(deftest invoke-chat-with-pre-chat-filter-test
  (let [inject (filters/create-filter :inject :pre-chat
                 (fn [fc] {:action :continue
                           :context (update fc :messages #(into [{:role "system" :content "injected"}] %))}))
        received (atom nil)
        svc {:chat-fn (fn [msgs _] (reset! received msgs)
                        (response/make-response :text "response" :tool-calls nil))
             :build-result-msgs (fn [_ _] [])}
        kernel (core/build-kernel
                 (-> (core/create-kernel-builder)
                     (core/add-filter inject)
                     (core/add-service svc)))]
    (testing "pre-chat filter 修改传给 LLM 的消息"
      (reset! received nil)
      (core/invoke-chat kernel [{:role :user :content "hi"}] {})
      (is (= "system" (:role (first @received))))
      (is (= "injected" (:content (first @received)))))))
