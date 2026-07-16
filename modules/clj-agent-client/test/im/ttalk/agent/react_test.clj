(ns im.ttalk.agent.react-test
  "工具调用循环测试（从 core context_test 的 Phase 5 迁来）

   循环 + memory 已下沉 simpleagent：kernel 只提供 invoke-chat/invoke-tool，
   memory 以 memory-filter 形态挂进 kernel，store 由调用方持有并显式传给 invoke。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.kernel :as core]
            [im.ttalk.agent.context :as context]
            [im.ttalk.agent.tool :refer [deftool]]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.advisor :as flt]
            [im.ttalk.agent.advisor.memory :as ma]
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
;;; 公共设施：mock service + 挂载 memory-filter 的 kernel
;;; ============================================================

(defn- mock-service [responses-fn]
  {:chat-fn (fn [_msgs _opts] (responses-fn))})

(defn- build
  ([tools svc store] (build tools svc store nil))
  ([tools svc store state-slots]
   (let [filters (when store [(ma/memory-filter store)])]
     (core/build-kernel
       (cond-> {:service  svc
                :tools    (vec tools)
                :filters  (vec filters)}
         state-slots (assoc :state-slots state-slots))))))

;;; ============================================================
;;; invoke 工具循环
;;; ============================================================

(deftest invoke-tool-loop-accumulates-context-test
  (let [call-count (atom 0)
        svc (mock-service
              (fn []
                (let [n (swap! call-count inc)]
                  (if (= n 1)
                    (response/make-response
                      :text nil
                      :tool-calls [{:id "tc1" :name "append-item" :args {:item "book"}}
                                   {:id "tc2" :name "append-item" :args {:item "pen"}}])
                    (response/make-response :text "已添加 book 和 pen" :tool-calls nil)))))
        store (memory/in-memory-store)
        kernel (build [#'append-item] svc store
                      {:items {:init [] :reduce conj}})]
    (testing "同批两个写经槽 reducer 按序折叠进 ToolContext，原变量保留"
      (reset! call-count 0)
      (let [result (agent-loop/invoke kernel store
                     [{:role :user :content "添加 book 和 pen"}]
                     {:context (context/create {:items [] :user-id "u123"})})]
        (is (= "已添加 book 和 pen" (get-in result [:response :text])))
        (is (= ["book" "pen"] (context/get-var (:tool-context result) :items))
            "conj reducer 按 tool-call 原始序折叠")
        (is (= "u123" (context/get-var (:tool-context result) :user-id)))
        (is (= 2 (count (:tool-calls-made result))))))))

(deftest invoke-multi-iteration-test
  (let [call-count (atom 0)
        svc (mock-service
              (fn []
                (let [n (swap! call-count inc)]
                  (if (<= n 3)
                    (response/make-response :text nil
                      :tool-calls [{:id (str "tc" n) :name "inc-counter" :args {}}])
                    (response/make-response :text "计数完成" :tool-calls nil)))))
        store (memory/in-memory-store)
        kernel (build [#'inc-counter] svc store)]
    (testing "多轮工具调用 context 持续累积"
      (reset! call-count 0)
      (let [result (agent-loop/invoke kernel store
                     [{:role :user :content "计数三次"}]
                     {:context (context/create {:counter 0})})]
        (is (= "计数完成" (get-in result [:response :text])))
        (is (= 3 (context/get-var (:tool-context result) :counter)))
        (is (= 3 (count (:tool-calls-made result))))))))

(deftest max-iterations-exceeded-throws-test
  (testing "LLM 持续调工具超过 max-iterations 时抛 ex-info（:max-iterations-exceeded）"
    (let [tc-id (atom 0)
          svc (mock-service
                ;; 永远返回工具调用（每次唯一 id），逼近上限
                (fn [] (response/make-response :text nil
                         :tool-calls [{:id (str "tc" (swap! tc-id inc))
                                       :name "inc-counter" :args {}}])))
          store (memory/in-memory-store)
          kernel (build [#'inc-counter] svc store)
          ex (try
               (agent-loop/invoke kernel store
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
    (let [svc (mock-service
                (fn [] (response/make-response :text nil
                         :tool-calls [{:id "tc" :name "inc-counter" :args {}}])))
          store (memory/in-memory-store)
          kernel (build [#'inc-counter] svc store)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (agent-loop/invoke kernel store
                     [{:role :user :content "x"}]
                     {:context (context/with-conversation-id (context/create {:counter 0}) "conv-neg")
                      :max-iterations -1}))))))

(deftest invoke-stores-history-in-memory-test
  (let [call-count (atom 0)
        svc (mock-service
              (fn []
                (let [n (swap! call-count inc)]
                  (if (= n 1)
                    (response/make-response :text nil
                      :tool-calls [{:id "tc1" :name "simple-echo" :args {:text "hi"}}])
                    (response/make-response :text "done" :tool-calls nil)))))
        store (memory/in-memory-store)
        kernel (build [#'simple-echo] svc store)]
    (testing "带 conversation-id 时历史存进 ChatMemory store"
      (reset! call-count 0)
      (agent-loop/invoke kernel store
        [{:role :user :content "echo hi"}]
        {:context (context/with-conversation-id (context/create) "conv-x")})
      (let [stored (memory/mem-get store "conv-x")]
        ;; user, assistant(tool-calls), tool-result, assistant(text)
        (is (= 4 (count stored)))))))

(deftest invoke-system-prompts-test
  (let [received-opts (atom nil)
        svc {:chat-fn (fn [_msgs opts]
                        (reset! received-opts opts)
                        (response/make-response :text "ok" :tool-calls nil))}
        store (memory/in-memory-store)
        kernel (build [] svc store)]
    (testing "system-prompts 通过 :system-prompt 传给 chat-fn"
      (reset! received-opts nil)
      (agent-loop/invoke kernel store
        [{:role :user :content "hi"}]
        {:system-prompts [{:role "system" :content "Be helpful"}]})
      (is (= "Be helpful" (:system-prompt @received-opts))))))

;;; ============================================================
;;; 外部手搓工具循环（只回传 delta，历史由 memory-filter 拼）
;;; ============================================================

(deftest external-tool-loop-test
  (let [call-count (atom 0)
        svc (mock-service
              (fn []
                (let [n (swap! call-count inc)]
                  (if (= n 1)
                    (response/make-response :text nil
                      :tool-calls [{:id "tc1" :name "simple-echo" :args {:text "hi"}}])
                    (response/make-response :text "done" :tool-calls nil)))))
        store (memory/in-memory-store)
        kernel (build [#'simple-echo] svc store)
        cid "ext-1"
        tctx (context/with-conversation-id (context/create) cid)]
    (reset! call-count 0)
    (testing "首轮只发 user，得到 tool_calls（内部不执行）"
      (let [{:keys [response]} (core/invoke-chat kernel [(msg/user "echo hi")] {:context tctx})]
        (is (response/has-tool-calls? response))
        (testing "手动 run-tools 后只回传 tool 结果 delta"
          (let [{:keys [messages]} (agent-loop/run-tools kernel (response/response-tool-calls response) tctx)
                {:keys [response]} (core/invoke-chat kernel messages {:context tctx})]
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

(defn- batch-kernel
  "无 service 的 kernel（只测 execute-batch），tools 为 inline 工具。"
  [tools & [state-slots]]
  (core/build-kernel (cond-> {:tools (vec tools)}
                       state-slots (assoc :state-slots state-slots))))

(defn- tc [id name] {:id id :name name :args {}})

(deftest batch-true-parallel-test
  (testing "同批两个工具互等对方启动——串行执行会死等超时报 :not-parallel"
    (let [a (promise) b (promise)
          mk (fn [own other]
               (fn [_ _] (deliver own true)
                 (if (true? (deref other 3000 false)) "ok" "not-parallel")))
          kernel (batch-kernel [(inline-tool "ta" (mk a b))
                                (inline-tool "tb" (mk b a))])
          {:keys [messages]} (agent-loop/execute-batch
                               kernel [(tc "1" "ta") (tc "2" "tb")] nil {} [])]
      (is (= ["ok" "ok"] (mapv :content messages))))))

(deftest batch-snapshot-isolation-test
  (testing "同批工具互相看不到对方的写（全部拿轮初快照）"
    (let [gate-a (promise) gate-b (promise)
          writer (fn [_ _] (deliver gate-a true)
                   (deref gate-b 3000 false)
                   {:result "wrote" :writes {:seen "from-a"}})
          reader (fn [_ ctx] (deref gate-a 3000 false)
                   (deliver gate-b true)
                   (str "saw=" (context/get-var ctx :seen "nothing")))
          kernel (batch-kernel [(inline-tool "writer" writer)
                                (inline-tool "reader" reader)])
          {:keys [messages context]} (agent-loop/execute-batch
                                       kernel [(tc "1" "writer") (tc "2" "reader")] nil {} [])]
      (is (= "saw=nothing" (:content (second messages))) "reader 读到的是轮初快照")
      (is (= "from-a" (context/get-var context :seen)) "屏障后写已折叠进新 context"))))

(deftest batch-last-writer-by-index-test
  (testing "last-writer 按 tool-call 原始序而非完成序（index 0 慢但先折叠）"
    (let [slow (fn [_ _] (Thread/sleep 150) {:result "slow" :writes {:x :from-slow}})
          fast (fn [_ _] {:result "fast" :writes {:x :from-fast}})
          kernel (batch-kernel [(inline-tool "slow" slow) (inline-tool "fast" fast)])
          {:keys [context]} (agent-loop/execute-batch
                              kernel [(tc "1" "slow") (tc "2" "fast")] nil {} [])]
      (is (= :from-fast (context/get-var context :x))
          "index 大者后折叠生效，与完成顺序无关"))))

(deftest batch-failed-writes-dropped-test
  (testing "抛异常的工具：错误折为结果，writes 不生效；其余工具照常"
    (let [boom (fn [_ _] (throw (ex-info "炸了" {})))
          good (fn [_ _] {:result "ok" :writes {:good true}})
          kernel (batch-kernel [(inline-tool "boom" boom) (inline-tool "good" good)])
          {:keys [messages context]} (agent-loop/execute-batch
                                       kernel [(tc "1" "boom") (tc "2" "good")] nil {} [])]
      (is (clojure.string/includes? (:content (first messages)) "错误"))
      (is (true? (context/get-var context :good)) "collect-all：单个失败不炸批次")
      (is (nil? (context/get-var context :boom))))))

(deftest batch-messages-order-test
  (testing "messages/records 按 tool-call 原始序排回（与完成序无关）"
    (let [slow (fn [_ _] (Thread/sleep 150) "slow-done")
          fast (fn [_ _] "fast-done")
          kernel (batch-kernel [(inline-tool "slow" slow) (inline-tool "fast" fast)])
          {:keys [messages records]} (agent-loop/execute-batch
                                       kernel [(tc "1" "slow") (tc "2" "fast")] nil {} [])]
      (is (= ["1" "2"] (mapv :tool-call-id messages)))
      (is (= ["slow-done" "fast-done"] (mapv :content messages)))
      (is (= [:slow :fast] (mapv :name records))))))

(deftest batch-serial-degrade-test
  (testing "批内含 :serial 工具 → 整批退化按序执行（无重叠）"
    (let [trace (atom [])
          mk (fn [tag] (fn [_ _]
                         (swap! trace conj [tag :start])
                         (Thread/sleep 50)
                         (swap! trace conj [tag :end])
                         "done"))
          kernel (batch-kernel [(inline-tool "s1" (mk :s1) true)
                                (inline-tool "s2" (mk :s2))])
          _ (agent-loop/execute-batch kernel [(tc "1" "s1") (tc "2" "s2")] nil {} [])]
      (is (= [[:s1 :start] [:s1 :end] [:s2 :start] [:s2 :end]] @trace)
          "按原始序依次执行，不并发"))))

(deftest batch-reject-test
  (testing ":reject 不执行、不触发回调、无 writes、记录 :rejected"
    (let [executed (atom false)
          cb-hits (atom [])
          w (fn [_ _] (reset! executed true) {:result "ran" :writes {:x 1}})
          kernel (batch-kernel [(inline-tool "w" w) (inline-tool "ok" (fn [_ _] "fine"))])
          gate (fn [t] (if (= "w" (:name t)) :reject :proceed))
          {:keys [messages records context]}
          (agent-loop/execute-batch kernel [(tc "1" "w") (tc "2" "ok")] gate {} []
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
          kernel (batch-kernel [(inline-tool "w1" w1) (inline-tool "w2" w2)])
          {:keys [context]} (agent-loop/execute-batch
                              kernel [(tc "1" "w1") (tc "2" "w2")] nil {} [])]
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
          kernel (batch-kernel [(assoc (inline-tool "flaky" flaky)
                                       :retry {:max-retries 3 :initial-delay-ms 1})])
          {:keys [messages errors]} (agent-loop/execute-batch
                                      kernel [(tc "1" "flaky")] nil {} [])]
      (is (= 3 @attempts))
      (is (= "第三次成功" (:content (first messages))))
      (is (empty? errors) "重试成功后不出现在 :errors")))

  (testing "重试耗尽仍失败 → 错误结果 + :errors 携带类别"
    (let [attempts (atom 0)
          dead (fn [_ _] (swap! attempts inc)
                 (throw (ex-info "一直挂" {:error-class :transient})))
          kernel (batch-kernel [(assoc (inline-tool "dead" dead)
                                       :retry {:max-retries 2 :initial-delay-ms 1})])
          {:keys [messages errors]} (agent-loop/execute-batch
                                      kernel [(tc "1" "dead")] nil {} [])]
      (is (= 3 @attempts) "初次 + 2 次重试")
      (is (clojure.string/includes? (:content (first messages)) "错误"))
      (is (= :transient (:class (first errors))))))

  (testing "未声明 :retry → 不重试"
    (let [attempts (atom 0)
          flaky (fn [_ _] (swap! attempts inc)
                  (throw (ex-info "抖动" {:error-class :transient})))
          kernel (batch-kernel [(inline-tool "flaky" flaky)])]
      (agent-loop/execute-batch kernel [(tc "1" "flaky")] nil {} [])
      (is (= 1 @attempts))))

  (testing ":semantic 失败即使声明 :retry 也不重试（重试同一调用无意义）"
    (let [attempts (atom 0)
          bad (fn [_ _] (swap! attempts inc) (throw (ex-info "查无此人" {})))
          kernel (batch-kernel [(assoc (inline-tool "bad" bad)
                                       :retry {:max-retries 3 :initial-delay-ms 1})])]
      (agent-loop/execute-batch kernel [(tc "1" "bad")] nil {} [])
      (is (= 1 @attempts)))))

(def ^:dynamic *tenant* :none)

(deftest binding-conveyance-across-batch-shapes-test
  (testing "动态绑定对工具可见，且**不随批次大小改变**（回归：executor 路径曾丢绑定——
            同一工具因 LLM 临场决定发 1 个还是 2 个 tool-call 而看到不同的 binding）"
    (let [probe (fn [_ _] (str *tenant*))
          kernel (batch-kernel [(inline-tool "a" probe) (inline-tool "b" probe)])]
      (binding [*tenant* :acme]
        (let [one (mapv :content (:messages (agent-loop/execute-batch
                                              kernel [(tc "1" "a")] nil {} [])))
              two (mapv :content (:messages (agent-loop/execute-batch
                                              kernel [(tc "1" "a") (tc "2" "b")] nil {} [])))]
          (is (= [":acme"] one) "批内 1 个 call（内联路径）")
          (is (= [":acme" ":acme"] two) "批内 2 个 call（executor 路径）——修复前这里是 :none")))))

  (testing "换引擎不改变工具看到的 binding（引擎只决定「怎么跑」，不决定「跑的是什么」）"
    (let [probe (fn [_ _] (str *tenant*))
          kernel (batch-kernel [(inline-tool "a" probe) (inline-tool "b" probe)])
          calls [(tc "1" "a") (tc "2" "b")]
          resp (response/make-response :text nil :tool-calls calls)
          opts {:tool-context {} :records []}
          run (fn [m] (mapv :content (:messages (tcm/execute-tool-calls m kernel resp opts))))]
      (binding [*tenant* :acme]
        (is (= (run (agent-loop/sequential-tool-calling-manager))
               (run (agent-loop/virtual-thread-tool-calling-manager)))
            "Sequential 与 VirtualThread 引擎须给出相同结果")))))

(deftest timeout-transient-retry-test
  (testing "timeout-filter 超时 → :transient → 声明 :retry 的工具被重试，第二次按时完成（链路断言：此前各环节单测有、整链无钉）"
    (let [attempts (atom 0)
          slow-then-fast (fn [_ _]
                           (if (= 1 (swap! attempts inc))
                             (do (Thread/sleep 60000) "never")
                             "第二次很快"))
          kernel (core/build-kernel
                   {:tools   [(assoc (inline-tool "flaky" slow-then-fast)
                                     :retry {:max-retries 2 :initial-delay-ms 1})]
                    :filters [(flt/timeout-filter 200)]})
          {:keys [messages errors]} (agent-loop/execute-batch
                                      kernel [(tc "1" "flaky")] nil {} [])]
      (is (= 2 @attempts) "第一次超时触发一次重试——注意幂等前提：重试发起时上一次调用可能仍在跑")
      (is (= "第二次很快" (:content (first messages))))
      (is (empty? errors)))))

(deftest timeout-partial-batch-test
  (testing "同批一个工具超时不殃及其它——部分结果是每工具超时的自然结果（对照 beamai 需专门一层 gather deadline 才有）"
    (let [slow (fn [_ _] (Thread/sleep 60000) "never")
          fast (fn [_ _] "快的正常返回")
          kernel (core/build-kernel
                   {:tools   [(inline-tool "slow" slow) (inline-tool "fast" fast)]
                    :filters [(flt/timeout-filter 200)]})
          {:keys [messages errors]} (agent-loop/execute-batch
                                      kernel [(tc "1" "slow") (tc "2" "fast")] nil {} [])]
      (is (clojure.string/includes? (:content (first messages)) "超时"))
      (is (= "快的正常返回" (:content (second messages))))
      (is (= [:transient] (mapv :class errors)) "只有超时者进 :errors"))))

(deftest env-error-pause-resume-test
  (let [env-ok (atom false)   ;; 模拟环境：false=凭证失效，true=已修复
        calls  (atom 0)
        svc (mock-service
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
        kernel (core/build-kernel
                 {:service svc
                  :tools [(inline-tool "fetch" fetch) (inline-tool "note" note)]
                  :filters [(ma/memory-filter store)]})]
    (testing "环境类失败 → 屏障处暂停（:env-retry），旁路工具结果保留"
      (reset! calls 0)
      (let [r (agent-loop/invoke kernel store [(msg/user "取数据")]
                {:context (context/with-conversation-id (context/create) "env-1")
                 :on-env-error :pause})]
        (is (= :paused (:status r)))
        (is (= :env-retry (get-in r [:loop-state :phase])))
        (is (clojure.string/includes? (:pause-reason r) "环境类错误"))
        (is (= "fetch" (get-in r [:pending-tool :name])))
        (testing "resume :retry（环境已修复）→ 失败工具重跑、结果按 id 替换、循环完成"
          (reset! env-ok true)
          (let [r2 (agent-loop/resume kernel (:loop-state r) :retry
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
      (let [r (agent-loop/invoke kernel store [(msg/user "再取")]
                {:context (context/with-conversation-id (context/create) "env-2")
                 :on-env-error :pause})
            r2 (agent-loop/resume kernel (:loop-state r) :proceed
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
          svc (mock-service
                (fn []
                  (if (= 1 (swap! calls inc))
                    (response/make-response :text nil
                      :tool-calls [{:id "t1" :name "broken" :args {}}])
                    (response/make-response :text "我看到工具挂了" :tool-calls nil))))
          broken (fn [_ _] (throw (ex-info "磁盘满" {:error-class :environment})))
          store (memory/in-memory-store)
          kernel (core/build-kernel
                   {:service svc
                    :tools [(inline-tool "broken" broken)]
                    :filters [(ma/memory-filter store)]})
          r (agent-loop/invoke kernel store [(msg/user "干活")]
              {:context (context/with-conversation-id (context/create) "env-3")})]
      (is (= :completed (:status r)))
      (is (= "我看到工具挂了" (get-in r [:response :text]))))))
