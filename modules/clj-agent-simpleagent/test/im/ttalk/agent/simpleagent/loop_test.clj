(ns im.ttalk.agent.simpleagent.loop-test
  "工具调用循环测试（从 core context_test 的 Phase 5 迁来）

   循环 + memory 已下沉 simpleagent：kernel 只提供 invoke-chat/invoke-tool，
   memory 以 memory-filter 形态挂进 kernel，store 由调用方持有并显式传给 invoke。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.core.kernel :as core]
            [im.ttalk.agent.core.kernel.context :as context]
            [im.ttalk.agent.core.kernel.tool :refer [deftool]]
            [im.ttalk.agent.core.llm.response :as response]
            [im.ttalk.agent.core.llm.message :as msg]
            [im.ttalk.agent.simpleagent.memory :as memory]
            [im.ttalk.agent.simpleagent.memory-filter :as ma]
            [im.ttalk.agent.simpleagent.loop :as agent-loop]))

;;; ============================================================
;;; 测试工具
;;; ============================================================

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

;;; ============================================================
;;; 公共设施：mock service + 挂载 memory-filter 的 kernel
;;; ============================================================

(defn- mock-service [responses-fn]
  {:chat-fn (fn [_msgs _opts] (responses-fn))
   :build-result-msgs (fn [_ _] [])})

(defn- build [tools svc store]
  (let [filters (when store [(ma/memory-filter store)])]
    (core/build-kernel
      {:service  svc
       :tools    (vec tools)
       :filters  (vec filters)})))

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
                      :tool-calls [{:id "tc1" :name "append-item" :input {:item "book"}}
                                   {:id "tc2" :name "append-item" :input {:item "pen"}}])
                    (response/make-response :text "已添加 book 和 pen" :tool-calls nil)))))
        store (memory/in-memory-store)
        kernel (build [#'append-item] svc store)]
    (testing "工具结果写回 ToolContext，原变量保留，tool-calls-made 记录"
      (reset! call-count 0)
      (let [result (agent-loop/invoke kernel store
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

(deftest invoke-stores-history-in-memory-test
  (let [call-count (atom 0)
        svc (mock-service
              (fn []
                (let [n (swap! call-count inc)]
                  (if (= n 1)
                    (response/make-response :text nil
                      :tool-calls [{:id "tc1" :name "simple-echo" :input {:text "hi"}}])
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
                        (response/make-response :text "ok" :tool-calls nil))
             :build-result-msgs (fn [_ _] [])}
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
                      :tool-calls [{:id "tc1" :name "simple-echo" :input {:text "hi"}}])
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
