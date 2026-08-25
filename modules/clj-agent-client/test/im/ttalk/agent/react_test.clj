(ns im.ttalk.agent.react-test
  "工具调用循环测试（从 core context_test 的 Phase 5 迁来）

   循环 + memory 已下沉 simpleagent：chat-client 只提供 invoke-chat/invoke-tool，
   memory 以 memory-filter 形态挂进 chat-client，store 由调用方持有并显式传给 invoke。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.chat-client :as core]
            [im.ttalk.agent.context :as context]
            [im.ttalk.agent.tool :refer [deftool]]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.filter.memory :as ma]
            [im.ttalk.agent.tool-calling-manager :as tcm]
            [im.ttalk.agent.react :as agent-loop]))

;;; ============================================================
;;; 测试工具
;;; ============================================================

(deftool simple-echo
  "简单回显"
  [[text :string "文本"]]
  (str "echo: " text))

(deftool append-item
  "添加项目到列表（ctx 只读；写 delta 走 :writes，槽 reducer 负责合并）"
  [[item :string "项目名"]]
  {:context true}
  (let [items (context/get-var ctx :items [])]
    {:result (str "已添加: " item "（快照中已有 " (count items) " 项）")
     :writes {:items item}}))

(deftool inc-counter
  "增加计数器"
  []
  {:context true}
  (let [c (context/get-var ctx :counter 0)]
    {:result (str "counter=" (inc c))
     :writes {:counter (inc c)}}))

;;; ============================================================
;;; 公共设施：mock chat-model + 挂载 memory-filter 的 chat-client
;;; ============================================================

(defn- mock-chat-model [responses-fn]
  {:chat-fn (fn [_msgs _opts] (responses-fn))})

(defn- build
  ([tools cm store] (build tools cm store nil))
  ([tools cm store state-slots]
   (let [filters (when store [(ma/memory-filter store)])]
     (core/build-chat-client
       (cond-> {:chat-model  cm
                :tools    (vec tools)
                :filters  (vec filters)}
         state-slots (assoc :state-slots state-slots))))))

;;; ============================================================
;;; invoke 工具循环
;;; ============================================================

(deftest invoke-tool-loop-accumulates-context-test
  (let [call-count (atom 0)
        cm (mock-chat-model
              (fn []
                (let [n (swap! call-count inc)]
                  (if (= n 1)
                    (response/make-response
                      :text nil
                      :tool-calls [{:id "tc1" :name "append-item" :args {:item "book"}}
                                   {:id "tc2" :name "append-item" :args {:item "pen"}}])
                    (response/make-response :text "已添加 book 和 pen" :tool-calls nil)))))
        store (memory/in-memory-store)
        chat-client (build [#'append-item] cm store
                           {:items {:init [] :reduce conj}})]
    (testing "同批两个写经槽 reducer 按序折叠进 ToolContext，原变量保留"
      (reset! call-count 0)
      (let [result (agent-loop/invoke chat-client store
                     [{:role :user :content "添加 book 和 pen"}]
                     {:context (context/create {:items [] :user-id "u123"})})]
        (is (= "已添加 book 和 pen" (get-in result [:response :text])))
        (is (= ["book" "pen"] (context/get-var (:tool-context result) :items))
            "conj reducer 按 tool-call 原始序折叠")
        (is (= "u123" (context/get-var (:tool-context result) :user-id)))
        (is (= 2 (count (:tool-calls-made result))))))))

(deftest invoke-multi-iteration-test
  (let [call-count (atom 0)
        cm (mock-chat-model
              (fn []
                (let [n (swap! call-count inc)]
                  (if (<= n 3)
                    (response/make-response :text nil
                      :tool-calls [{:id (str "tc" n) :name "inc-counter" :args {}}])
                    (response/make-response :text "计数完成" :tool-calls nil)))))
        store (memory/in-memory-store)
        chat-client (build [#'inc-counter] cm store)]
    (testing "多轮工具调用 context 持续累积"
      (reset! call-count 0)
      (let [result (agent-loop/invoke chat-client store
                     [{:role :user :content "计数三次"}]
                     {:context (context/create {:counter 0})})]
        (is (= "计数完成" (get-in result [:response :text])))
        (is (= 3 (context/get-var (:tool-context result) :counter)))
        (is (= 3 (count (:tool-calls-made result))))))))

(deftest max-iterations-exceeded-throws-test
  (testing "LLM 持续调工具超过 max-iterations 时抛 ex-info（:max-iterations-exceeded）"
    (let [tc-id (atom 0)
          cm (mock-chat-model
                ;; 永远返回工具调用（每次唯一 id），逼近上限
                (fn [] (response/make-response :text nil
                         :tool-calls [{:id (str "tc" (swap! tc-id inc))
                                       :name "inc-counter" :args {}}])))
          store (memory/in-memory-store)
          chat-client (build [#'inc-counter] cm store)
          ex (try
               (agent-loop/invoke chat-client store
                 [{:role :user :content "loop"}]
                 {:context (context/with-conversation-id (context/create {:counter 0}) "conv-max")
                  :max-iterations 3})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :max-iterations-exceeded (:reason (ex-data ex))))
      (testing "已执行的工具结果全部落库（回归：旧实现会在执行后、落库前抛异常丢结果）"
        (let [stored (memory/mem-get store "conv-max")
              dangling (#'agent-loop/dangling-tool-call-ids stored)]
          ;; max-iterations=3 → 恰好执行 3 批工具，3 条 tool-result 全部持久化
          ;; （旧实现只会留下 2 条，第 3 批结果随异常丢失）
          (is (= 3 (count (filter #(= :tool (:role %)) stored))))
          ;; 仅最后一个「未执行」的 tool_use 悬空（heal 标记"已取消"才是正确的）；
          ;; 已执行的批次都已正确配对，不被误判
          (is (= 1 (count dangling))))))))

(deftest negative-max-iterations-does-not-loop-test
  (testing "max-iterations 为负数时不会无限循环，立即抛上限错误"
    (let [cm (mock-chat-model
                (fn [] (response/make-response :text nil
                         :tool-calls [{:id "tc" :name "inc-counter" :args {}}])))
          store (memory/in-memory-store)
          chat-client (build [#'inc-counter] cm store)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (agent-loop/invoke chat-client store
                     [{:role :user :content "x"}]
                     {:context (context/with-conversation-id (context/create {:counter 0}) "conv-neg")
                      :max-iterations -1}))))))

(deftest invoke-stores-history-in-memory-test
  (let [call-count (atom 0)
        cm (mock-chat-model
              (fn []
                (let [n (swap! call-count inc)]
                  (if (= n 1)
                    (response/make-response :text nil
                      :tool-calls [{:id "tc1" :name "simple-echo" :args {:text "hi"}}])
                    (response/make-response :text "done" :tool-calls nil)))))
        store (memory/in-memory-store)
        chat-client (build [#'simple-echo] cm store)]
    (testing "带 conversation-id 时历史存进 ChatMemory store"
      (reset! call-count 0)
      (agent-loop/invoke chat-client store
        [{:role :user :content "echo hi"}]
        {:context (context/with-conversation-id (context/create) "conv-x")})
      (let [stored (memory/mem-get store "conv-x")]
        ;; user, assistant(tool-calls), tool-result, assistant(text)
        (is (= 4 (count stored)))))))

(deftest invoke-system-prompts-test
  (let [received-opts (atom nil)
        cm {:chat-fn (fn [_msgs opts]
                        (reset! received-opts opts)
                        (response/make-response :text "ok" :tool-calls nil))}
        store (memory/in-memory-store)
        chat-client (build [] cm store)]
    (testing "system-prompts 通过 :system-prompt 传给 chat-fn"
      (reset! received-opts nil)
      (agent-loop/invoke chat-client store
        [{:role :user :content "hi"}]
        {:system-prompts [{:role "system" :content "Be helpful"}]})
      (is (= "Be helpful" (:system-prompt @received-opts))))))

;;; ============================================================
;;; 外部手搓工具循环（只回传 delta，历史由 memory-filter 拼）
;;; ============================================================

(deftest external-tool-loop-test
  (let [call-count (atom 0)
        cm (mock-chat-model
              (fn []
                (let [n (swap! call-count inc)]
                  (if (= n 1)
                    (response/make-response :text nil
                      :tool-calls [{:id "tc1" :name "simple-echo" :args {:text "hi"}}])
                    (response/make-response :text "done" :tool-calls nil)))))
        store (memory/in-memory-store)
        chat-client (build [#'simple-echo] cm store)
        cid "ext-1"
        tctx (context/with-conversation-id (context/create) cid)]
    (reset! call-count 0)
    (testing "首轮只发 user，得到 tool_calls（内部不执行）"
      (let [{:keys [response]} (core/invoke-chat chat-client [(msg/user "echo hi")] {:context tctx})]
        (is (response/has-tool-calls? response))
        (testing "手动 run-tools 后只回传 tool 结果 delta"
          (let [{:keys [messages]} (agent-loop/run-tools chat-client (response/response-tool-calls response) tctx)
                {:keys [response]} (core/invoke-chat chat-client messages {:context tctx})]
            (is (= "done" (response/response-text response)))))))
    (testing "Memory store 拼出完整历史"
      ;; user, assistant(tool-calls), tool-result, assistant(text)
      (is (= 4 (count (memory/mem-get store cid)))))))

;;; ============================================================
;;; execute-batch MapReduce 语义（S1，设计文档 §9）
;;; ============================================================

;; 内联工具工厂：行为可闭包注入（deftool 是静态宏，动态行为用 inline handler）
(defn- inline-tool [name handler & [serial?]]
  (cond-> {:name name
           :description name
           :input_schema {:type "object" :properties {} :required []}
           :handler handler}
    serial? (assoc :serial true)))

(defn- batch-chat-client
  "无 chat-model 的 chat-client（只测 execute-batch），tools 为 inline 工具。"
  [tools & [state-slots]]
  (core/build-chat-client (cond-> {:tools (vec tools)}
                       state-slots (assoc :state-slots state-slots))))

(defn- tc [id name] {:id id :name name :args {}})

(defn- via-manager
  "用**指定引擎**跑一批。`execute-batch` 现在缺省串行，故一切「并发语义」的测试
   都必须显式选引擎——否则它们会在串行下退化成假通过（握手靠 deref 超时兜底）。"
  [m chat-client calls]
  (tcm/execute-tool-calls m chat-client
                          (response/make-response :text nil :tool-calls calls)
                          {:tool-context {} :records []}))

(deftest default-manager-is-sequential-test
  (testing "**缺省 TCM 是串行**：不指定 :tool-manager 时同批工具严格按序、无重叠
            （v0.3 破坏性变更——此前缺省是虚拟线程并行。并发要求同批工具的副作用
             彼此无序依赖，那是调用方才知道的性质，框架不替它假定）"
    (let [trace (atom [])
          mk (fn [id] (fn [_ _] (swap! trace conj [id :start]) (Thread/sleep 60)
                        (swap! trace conj [id :end]) "ok"))
          chat-client (batch-chat-client [(inline-tool "a" (mk :a)) (inline-tool "b" (mk :b))])]
      (agent-loop/execute-batch chat-client [(tc "1" "a") (tc "2" "b")] nil {} [])
      (is (= [[:a :start] [:a :end] [:b :start] [:b :end]] @trace)
          "严格按调用序，无重叠")))

  (testing "要并发是**显式**决定：注入 VT 引擎"
    (let [a (promise) b (promise)
          mk (fn [own other]
               (fn [_ _] (deliver own true)
                 (if (true? (deref other 3000 false)) "ok" "not-parallel")))
          chat-client (batch-chat-client [(inline-tool "ta" (mk a b))
                                          (inline-tool "tb" (mk b a))])
          {:keys [messages]} (via-manager (agent-loop/virtual-thread-tool-calling-manager)
                                          chat-client [(tc "1" "ta") (tc "2" "tb")])]
      (is (= ["ok" "ok"] (mapv :content messages))
          "两个工具互等对方启动——只有真并发才都拿到 ok"))))

(deftest batch-snapshot-isolation-test
  (testing "同批工具互相看不到对方的写（全部拿轮初快照）——**在并发引擎下**才有意义"
    (let [gate-a (promise) gate-b (promise)
          writer (fn [_ _] (deliver gate-a true)
                   (deref gate-b 3000 false)
                   {:result "wrote" :writes {:seen "from-a"}})
          reader (fn [_ ctx] (deref gate-a 3000 false)
                   (deliver gate-b true)
                   (str "saw=" (context/get-var ctx :seen "nothing")))
          chat-client (batch-chat-client [(inline-tool "writer" writer)
                                          (inline-tool "reader" reader)])
          {:keys [messages context]} (via-manager
                                       (agent-loop/virtual-thread-tool-calling-manager)
                                       chat-client [(tc "1" "writer") (tc "2" "reader")])]
      (is (= "saw=nothing" (:content (second messages))) "reader 读到的是轮初快照")
      (is (= "from-a" (context/get-var context :seen)) "屏障后写已折叠进新 context")))

  (testing "串行引擎下快照语义相同（状态语义与引擎无关）"
    (let [writer (fn [_ _] {:result "wrote" :writes {:seen "from-a"}})
          reader (fn [_ ctx] (str "saw=" (context/get-var ctx :seen "nothing")))
          chat-client (batch-chat-client [(inline-tool "writer" writer)
                                          (inline-tool "reader" reader)])
          {:keys [messages context]} (agent-loop/execute-batch
                                       chat-client [(tc "1" "writer") (tc "2" "reader")] nil {} [])]
      (is (= "saw=nothing" (:content (second messages)))
          "即使串行、writer 先跑完，reader 仍只看到轮初快照")
      (is (= "from-a" (context/get-var context :seen))))))

(deftest batch-last-writer-by-index-test
  (testing "last-writer 按 tool-call 原始序而非完成序（index 0 慢但先折叠）"
    (let [slow (fn [_ _] (Thread/sleep 150) {:result "slow" :writes {:x :from-slow}})
          fast (fn [_ _] {:result "fast" :writes {:x :from-fast}})
          chat-client (batch-chat-client [(inline-tool "slow" slow) (inline-tool "fast" fast)])
          {:keys [context]} (agent-loop/execute-batch
                              chat-client [(tc "1" "slow") (tc "2" "fast")] nil {} [])]
      (is (= :from-fast (context/get-var context :x))
          "index 大者后折叠生效，与完成顺序无关"))))

(deftest batch-failed-writes-dropped-test
  (testing "抛异常的工具：错误折为结果，writes 不生效；其余工具照常"
    (let [boom (fn [_ _] (throw (ex-info "炸了" {})))
          good (fn [_ _] {:result "ok" :writes {:good true}})
          chat-client (batch-chat-client [(inline-tool "boom" boom) (inline-tool "good" good)])
          {:keys [messages context]} (agent-loop/execute-batch
                                       chat-client [(tc "1" "boom") (tc "2" "good")] nil {} [])]
      (is (clojure.string/includes? (:content (first messages)) "错误"))
      (is (true? (context/get-var context :good)) "collect-all：单个失败不炸批次")
      (is (nil? (context/get-var context :boom))))))

(deftest batch-messages-order-test
  (testing "messages/records 按 tool-call 原始序排回（与完成序无关）"
    (let [slow (fn [_ _] (Thread/sleep 150) "slow-done")
          fast (fn [_ _] "fast-done")
          chat-client (batch-chat-client [(inline-tool "slow" slow) (inline-tool "fast" fast)])
          {:keys [messages records]} (agent-loop/execute-batch
                                       chat-client [(tc "1" "slow") (tc "2" "fast")] nil {} [])]
      (is (= ["1" "2"] (mapv :tool-call-id messages)))
      (is (= ["slow-done" "fast-done"] (mapv :content messages)))
      (is (= [:slow :fast] (mapv :name records))))))

(deftest batch-serial-degrade-test
  (testing "批内含 :serial 工具 → 整批退化按序执行（无重叠）

            **必须在并发引擎下测**：缺省引擎本就串行，拿它测「退化」等于什么都没测
            （v0.3 缺省改串行后，本测试若仍走 execute-batch 就是假通过）。"
    (let [trace (atom [])
          mk (fn [tag] (fn [_ _]
                         (swap! trace conj [tag :start])
                         (Thread/sleep 50)
                         (swap! trace conj [tag :end])
                         "done"))
          chat-client (batch-chat-client [(inline-tool "s1" (mk :s1) true)
                                          (inline-tool "s2" (mk :s2))])
          _ (via-manager (agent-loop/virtual-thread-tool-calling-manager)
                         chat-client [(tc "1" "s1") (tc "2" "s2")])]
      (is (= [[:s1 :start] [:s1 :end] [:s2 :start] [:s2 :end]] @trace)
          "选了并发引擎，但批内有 :serial → 整批仍按原始序依次执行")))

  (testing "对照：同样两个工具、去掉 :serial 声明 → VT 引擎下真并发（证明上面测的是退化）"
    (let [a (promise) b (promise)
          mk (fn [own other] (fn [_ _] (deliver own true)
                               (if (true? (deref other 2000 false)) "并发" "串行")))
          chat-client (batch-chat-client [(inline-tool "n1" (mk a b))
                                          (inline-tool "n2" (mk b a))])
          {:keys [messages]} (via-manager (agent-loop/virtual-thread-tool-calling-manager)
                                          chat-client [(tc "1" "n1") (tc "2" "n2")])]
      (is (= ["并发" "并发"] (mapv :content messages))))))

(deftest batch-reject-test
  (testing ":reject 不执行、不触发回调、无 writes、记录 :rejected"
    (let [executed (atom false)
          cb-hits (atom [])
          w (fn [_ _] (reset! executed true) {:result "ran" :writes {:x 1}})
          chat-client (batch-chat-client [(inline-tool "w" w) (inline-tool "ok" (fn [_ _] "fine"))])
          gate (fn [t] (if (= "w" (:name t)) :reject :proceed))
          {:keys [messages records context]}
          (agent-loop/execute-batch chat-client [(tc "1" "w") (tc "2" "ok")] gate {} []
                                    (fn [n _] (swap! cb-hits conj n)))]
      (is (false? @executed))
      (is (= "已拒绝执行" (:content (first messages))))
      (is (= :rejected (:result (first records))))
      (is (nil? (context/get-var context :x)))
      (is (= ["ok"] @cb-hits) "reject 的工具不触发 on-tool-result"))))

(deftest batch-conflict-warn-path-test
  (testing "同批写同一未声明槽：last-writer 生效（warn 路径可执行不炸）"
    (let [w1 (fn [_ _] {:result "1" :writes {:x 1}})
          w2 (fn [_ _] {:result "2" :writes {:x 2}})
          chat-client (batch-chat-client [(inline-tool "w1" w1) (inline-tool "w2" w2)])
          {:keys [context]} (agent-loop/execute-batch
                              chat-client [(tc "1" "w1") (tc "2" "w2")] nil {} [])]
      (is (= 2 (context/get-var context :x))))))

;;; ============================================================
;;; S2：瞬态重试 + 环境类屏障暂停（设计文档 §5/§9）
;;; ============================================================

(deftest transient-retry-test
  (testing "声明 :retry 的工具：:transient 失败自动退避重试至成功"
    (let [attempts (atom 0)
          flaky (fn [_ _]
                  (if (< (swap! attempts inc) 3)
                    (throw (ex-info "网络抖动" {:error-class :transient}))
                    "第三次成功"))
          chat-client (batch-chat-client [(assoc (inline-tool "flaky" flaky)
                                                 :retry {:max-retries 3 :initial-delay-ms 1})])
          {:keys [messages errors]} (agent-loop/execute-batch
                                      chat-client [(tc "1" "flaky")] nil {} [])]
      (is (= 3 @attempts))
      (is (= "第三次成功" (:content (first messages))))
      (is (empty? errors) "重试成功后不出现在 :errors")))

  (testing "重试耗尽仍失败 → 错误结果 + :errors 携带类别"
    (let [attempts (atom 0)
          dead (fn [_ _] (swap! attempts inc)
                 (throw (ex-info "一直挂" {:error-class :transient})))
          chat-client (batch-chat-client [(assoc (inline-tool "dead" dead)
                                                 :retry {:max-retries 2 :initial-delay-ms 1})])
          {:keys [messages errors]} (agent-loop/execute-batch
                                      chat-client [(tc "1" "dead")] nil {} [])]
      (is (= 3 @attempts) "初次 + 2 次重试")
      (is (clojure.string/includes? (:content (first messages)) "错误"))
      (is (= :transient (:class (first errors))))))

  (testing "未声明 :retry → 不重试"
    (let [attempts (atom 0)
          flaky (fn [_ _] (swap! attempts inc)
                  (throw (ex-info "抖动" {:error-class :transient})))
          chat-client (batch-chat-client [(inline-tool "flaky" flaky)])]
      (agent-loop/execute-batch chat-client [(tc "1" "flaky")] nil {} [])
      (is (= 1 @attempts))))

  (testing ":semantic 失败即使声明 :retry 也不重试（重试同一调用无意义）"
    (let [attempts (atom 0)
          bad (fn [_ _] (swap! attempts inc) (throw (ex-info "查无此人" {})))
          chat-client (batch-chat-client [(assoc (inline-tool "bad" bad)
                                                 :retry {:max-retries 3 :initial-delay-ms 1})])]
      (agent-loop/execute-batch chat-client [(tc "1" "bad")] nil {} [])
      (is (= 1 @attempts)))))

(def ^:dynamic *tenant* :none)

(deftest binding-conveyance-across-batch-shapes-test
  (testing "动态绑定对工具可见，且**不随批次大小改变**（回归：executor 路径曾丢绑定——
            同一工具因 LLM 临场决定发 1 个还是 2 个 tool-call 而看到不同的 binding）"
    (let [probe (fn [_ _] (str *tenant*))
          chat-client (batch-chat-client [(inline-tool "a" probe) (inline-tool "b" probe)])]
      (binding [*tenant* :acme]
        (let [one (mapv :content (:messages (agent-loop/execute-batch
                                              chat-client [(tc "1" "a")] nil {} [])))
              two (mapv :content (:messages (agent-loop/execute-batch
                                              chat-client [(tc "1" "a") (tc "2" "b")] nil {} [])))]
          (is (= [":acme"] one) "批内 1 个 call（内联路径）")
          (is (= [":acme" ":acme"] two) "批内 2 个 call（executor 路径）——修复前这里是 :none")))))

  (testing "换引擎不改变工具看到的 binding（引擎只决定「怎么跑」，不决定「跑的是什么」）"
    (let [probe (fn [_ _] (str *tenant*))
          chat-client (batch-chat-client [(inline-tool "a" probe) (inline-tool "b" probe)])
          calls [(tc "1" "a") (tc "2" "b")]
          resp (response/make-response :text nil :tool-calls calls)
          opts {:tool-context {} :records []}
          run (fn [m] (mapv :content (:messages (tcm/execute-tool-calls m chat-client resp opts))))]
      (binding [*tenant* :acme]
        (is (= (run (agent-loop/sequential-tool-calling-manager))
               (run (agent-loop/virtual-thread-tool-calling-manager)))
            "Sequential 与 VirtualThread 引擎须给出相同结果")))))

(deftest tool-error-contained-not-fatal-test
  (testing "工具抛**非致命 Error** → 收敛为该工具的错误结果，不打死整个循环
            （回归 review#5：各层此前一律 catch Exception，Error 全部逃逸——
             一个工具的深递归 StackOverflowError 会整轮死，而分层错误路由的
             全部意义就是「一个工具坏了不牵连别人」）"
    (doseq [[label thrower] [["StackOverflowError（工具深递归 bug）"
                              #(throw (StackOverflowError. "深递归"))]
                             ["AssertionError（工具里的 assert）"
                              #(throw (AssertionError. "断言失败"))]
                             ["NoClassDefFoundError（该工具缺可选依赖）"
                              #(throw (NoClassDefFoundError. "com/missing/Dep"))]]]
      (let [chat-client (batch-chat-client [(inline-tool "boom" (fn [_ _] (thrower)))])
            {:keys [messages errors]} (agent-loop/execute-batch
                                        chat-client [(tc "1" "boom")] nil {} [])]
        (is (clojure.string/includes? (:content (first messages)) "错误") label)
        (is (= :semantic (:class (first errors)))
            (str label "：工具自身 bug，重试无意义")))))

  (testing "真·栈溢出（不是手工 throw）同样被收敛"
    (let [recurse (fn [_ _] (let [f (fn f [n] (+ 1 (f (inc n))))] (f 0)))
          chat-client (batch-chat-client [(inline-tool "recurse" recurse)])
          {:keys [errors]} (agent-loop/execute-batch chat-client [(tc "1" "recurse")] nil {} [])]
      (is (= :semantic (:class (first errors))))))

  (testing "同批：一个工具栈溢出不牵连另一个（这正是收敛的意义）"
    (let [chat-client (batch-chat-client [(inline-tool "bad" (fn [_ _] (throw (StackOverflowError. "x"))))
                                          (inline-tool "good" (fn [_ _] "我没事"))])
          {:keys [messages]} (agent-loop/execute-batch
                               chat-client [(tc "1" "bad") (tc "2" "good")] nil {} [])]
      (is (clojure.string/includes? (:content (first messages)) "错误"))
      (is (= "我没事" (:content (second messages))))))

  (testing "filter 抛的 Error 同样收敛——invoke-one 是本批的**完备**边界"
    (let [bad-filter {:name :bad :tool (fn [_ _] (throw (StackOverflowError. "filter 炸了")))}
          chat-client (core/build-chat-client {:tools [(inline-tool "t" (fn [_ _] "ok"))]
                                               :filters [bad-filter]})
          {:keys [errors]} (agent-loop/execute-batch chat-client [(tc "1" "t")] nil {} [])]
      (is (= :semantic (:class (first errors))))))

  (testing "**致命** Error 仍原样上抛——吞掉 OOM 只会掩盖真因，且收敛动作本身还要分配内存"
    (let [chat-client (batch-chat-client [(inline-tool "oom" (fn [_ _] (throw (OutOfMemoryError. "堆爆了"))))])]
      (is (thrown? OutOfMemoryError
            (agent-loop/execute-batch chat-client [(tc "1" "oom")] nil {} []))))))

(deftest manager-default-timeout-test
  (testing "**缺省不超时**：既没声明、引擎也没给 :timeout → 工具跑多久都不管
            （框架不替调用方决定何时放弃）"
    (let [chat-client (batch-chat-client [(inline-tool "slow" (fn [_ _] (Thread/sleep 200) "done"))])
          {:keys [messages]} (agent-loop/execute-batch chat-client [(tc "1" "slow")] nil {} [])]
      (is (= "done" (:content (first messages))))))

  (testing "引擎缺省 `{:timeout ms}` → 未声明的工具被封顶（时间上限属于执行策略，随引擎构造）"
    (let [tm (agent-loop/sequential-tool-calling-manager {:timeout 150})
          chat-client (core/build-chat-client {:tools [(inline-tool "slow" (fn [_ _] (Thread/sleep 5000) "done"))]
                                               :tool-manager tm})
          {:keys [messages errors]} (via-manager tm chat-client [(tc "1" "slow")])]
      (is (clojure.string/includes? (:content (first messages)) "超时"))
      (is (= :transient (:class (first errors))))))

  (testing "优先级：工具声明 > 引擎缺省（声明更紧 → 提前超时，报声明值）"
    (let [tm (agent-loop/sequential-tool-calling-manager {:timeout 9000})
          chat-client (core/build-chat-client
                   {:tools [(assoc (inline-tool "slow" (fn [_ _] (Thread/sleep 5000) "done"))
                                   :timeout 150)]
                    :tool-manager tm})
          {:keys [messages]} (via-manager tm chat-client [(tc "1" "slow")])]
      (is (clojure.string/includes? (:content (first messages)) "150ms")
          "报的是工具声明的 150ms，不是引擎的 9000ms")))

  (testing "优先级：工具声明 > 引擎缺省（声明更宽 → 引擎缺省不砍它）"
    (let [tm (agent-loop/sequential-tool-calling-manager {:timeout 100})
          chat-client (core/build-chat-client
                   {:tools [(assoc (inline-tool "ok" (fn [_ _] (Thread/sleep 300) "done"))
                                   :timeout 9000)]
                    :tool-manager tm})
          {:keys [messages]} (via-manager tm chat-client [(tc "1" "ok")])]
      (is (= "done" (:content (first messages)))
          "引擎缺省 100ms < 下游 300ms，但声明 9000ms 胜出")))

  (testing "三个引擎都接受 :timeout；坏值构造期即拒"
    (is (some? (agent-loop/sequential-tool-calling-manager {:timeout 100})))
    (is (some? (agent-loop/virtual-thread-tool-calling-manager {:timeout 100})))
    (with-open [m (agent-loop/thread-pool-tool-calling-manager {:pool-size 1 :timeout 100})]
      (is (some? m)))
    (doseq [bad ["5s" -1 0]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":timeout 必须为正整数毫秒"
            (agent-loop/sequential-tool-calling-manager {:timeout bad}))))))

(deftest timeout-transient-retry-test
  (testing "声明超时 → :transient → 声明 :retry 的工具被重试，第二次按时完成（链路断言：此前各环节单测有、整链无钉）"
    (let [attempts (atom 0)
          slow-then-fast (fn [_ _]
                           (if (= 1 (swap! attempts inc))
                             (do (Thread/sleep 60000) "never")
                             "第二次很快"))
          chat-client (core/build-chat-client
                   {:tools [(assoc (inline-tool "flaky" slow-then-fast)
                                   :timeout 200
                                   :retry {:max-retries 2 :initial-delay-ms 1})]})
          {:keys [messages errors]} (agent-loop/execute-batch
                                      chat-client [(tc "1" "flaky")] nil {} [])]
      (is (= 2 @attempts) "第一次超时触发一次重试——注意幂等前提：重试发起时上一次调用可能仍在跑")
      (is (= "第二次很快" (:content (first messages))))
      (is (empty? errors)))))

(deftest timeout-partial-batch-test
  (testing "同批一个工具超时不殃及其它——部分结果是每工具超时的自然结果（对照 beamai 需专门一层 gather deadline 才有）；缺省超时来自**引擎** {:timeout ms}"
    (let [slow (fn [_ _] (Thread/sleep 60000) "never")
          fast (fn [_ _] "快的正常返回")
          tm   (agent-loop/sequential-tool-calling-manager {:timeout 200})
          chat-client (core/build-chat-client
                   {:tools [(inline-tool "slow" slow) (inline-tool "fast" fast)]
                    :tool-manager tm})
          {:keys [messages errors]} (via-manager tm chat-client
                                                 [(tc "1" "slow") (tc "2" "fast")])]
      (is (clojure.string/includes? (:content (first messages)) "超时"))
      (is (= "快的正常返回" (:content (second messages))))
      (is (= [:transient] (mapv :class errors)) "只有超时者进 :errors"))))

(deftest env-error-pause-resume-test
  (let [env-ok (atom false)   ;; 模拟环境：false=凭证失效，true=已修复
        calls  (atom 0)
        cm (mock-chat-model
              (fn []
                (let [n (swap! calls inc)]
                  (if (= n 1)
                    (response/make-response :text nil
                      :tool-calls [{:id "t1" :name "fetch" :args {}}
                                   {:id "t2" :name "note" :args {}}])
                    (response/make-response :text "拿到了" :tool-calls nil)))))
        fetch (fn [_ _] (if @env-ok
                          "数据"
                          (throw (ex-info "凭证失效" {:error-class :environment}))))
        note  (fn [_ _] "旁路成功")
        store (memory/in-memory-store)
        chat-client (core/build-chat-client
                 {:chat-model cm
                  :tools [(inline-tool "fetch" fetch) (inline-tool "note" note)]
                  :filters [(ma/memory-filter store)]})]
    (testing "环境类失败 → 屏障处暂停（:env-retry），旁路工具结果保留"
      (reset! calls 0)
      (let [r (agent-loop/invoke chat-client store [(msg/user "取数据")]
                {:context (context/with-conversation-id (context/create) "env-1")
                 :on-env-error :pause})]
        (is (= :paused (:status r)))
        (is (= :env-retry (get-in r [:loop-state :phase])))
        (is (clojure.string/includes? (:pause-reason r) "环境类错误"))
        (is (= "fetch" (get-in r [:pending-tool :name])))
        (testing "resume :retry（环境已修复）→ 失败工具重跑、结果按 id 替换、循环完成"
          (reset! env-ok true)
          (let [r2 (agent-loop/resume chat-client (:loop-state r) :retry
                     {:context (context/with-conversation-id (context/create) "env-1")
                      :on-env-error :pause})]
            (is (= :completed (:status r2)))
            (is (= "拿到了" (get-in r2 [:response :text])))
            ;; 历史里 t1 的结果应是重试后的"数据"，不是错误
            (let [stored (memory/mem-get store "env-1")
                  t1-results (filterv #(= "t1" (:tool-call-id %)) stored)]
              (is (= ["数据"] (mapv :content t1-results))
                  "重试结果替换原错误，历史无重复 tool_result"))))))
    (testing "resume :proceed → 错误结果照常交给模型"
      (reset! env-ok false)
      (reset! calls 0)
      (let [r (agent-loop/invoke chat-client store [(msg/user "再取")]
                {:context (context/with-conversation-id (context/create) "env-2")
                 :on-env-error :pause})
            r2 (agent-loop/resume chat-client (:loop-state r) :proceed
                 {:context (context/with-conversation-id (context/create) "env-2")
                  :on-env-error :pause})]
        (is (= :completed (:status r2)))
        (let [stored (memory/mem-get store "env-2")
              t1-results (filterv #(= "t1" (:tool-call-id %)) stored)]
          (is (clojure.string/includes? (:content (first t1-results)) "错误")
              "错误结果原样进入历史交给模型"))))))

(deftest env-error-default-proceed-test
  (testing "react 层缺省 :proceed：环境类失败不暂停，错误结果交给模型"
    (let [calls (atom 0)
          cm (mock-chat-model
                (fn []
                  (if (= 1 (swap! calls inc))
                    (response/make-response :text nil
                      :tool-calls [{:id "t1" :name "broken" :args {}}])
                    (response/make-response :text "我看到工具挂了" :tool-calls nil))))
          broken (fn [_ _] (throw (ex-info "磁盘满" {:error-class :environment})))
          store (memory/in-memory-store)
          chat-client (core/build-chat-client
                   {:chat-model cm
                    :tools [(inline-tool "broken" broken)]
                    :filters [(ma/memory-filter store)]})
          r (agent-loop/invoke chat-client store [(msg/user "干活")]
              {:context (context/with-conversation-id (context/create) "env-3")})]
      (is (= :completed (:status r)))
      (is (= "我看到工具挂了" (get-in r [:response :text])))))

  (testing "R7: executor 路径上致命 Throwable 被 Future.get 包成 ExecutionException——
            run-on-executor 拆 cause 原样重抛，逃逸类型不随引擎变（串行=裸 OOM，并发=EE → 修后均为裸 OOM）"
    (let [oom-tool (fn [_ _] (throw (OutOfMemoryError. "堆爆了")))
          chat-client (batch-chat-client [(inline-tool "oom" oom-tool)
                                          (inline-tool "good" (fn [_ _] "我没事"))])
          tm (agent-loop/virtual-thread-tool-calling-manager)]
      ;; OOM 是致命的（fatal-throwable? = true），应原样上抛——不被收敛成工具错误。
      ;; 修复前：VT 引擎的 Future.get 把 OOM 包成 ExecutionException（普通 Exception），
      ;; invoke-one 的 catch Throwable 看不出它是致命的 → 收敛成 :semantic 错误。
      ;; 修复后：run-on-executor 拆 cause → 原 OOM 上抛 → invoke-one 的 fatal-throwable? 认出 → 重抛。
      (is (thrown? OutOfMemoryError
            (via-manager tm chat-client [(tc "1" "oom")])))))

  (testing "R7 对照：串行引擎下同样致命 → 同样上抛（证明不是引擎差异）"
    (let [oom-tool (fn [_ _] (throw (OutOfMemoryError. "堆爆了")))
          chat-client (batch-chat-client [(inline-tool "oom" oom-tool)])]
      (is (thrown? OutOfMemoryError
            (agent-loop/execute-batch chat-client [(tc "1" "oom")] nil {} []))))))
