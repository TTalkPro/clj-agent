(ns im.ttalk.agent.resume-payload-test
  "resume 带 payload（设计文档 §13）——
   拒绝带理由 / 批准改参 / reply 即结果（ask-user）/ 参数校验。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.simple-agent :as agent]
            [im.ttalk.agent.context :as context]
            [im.ttalk.agent.chat-client :as core]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.filter.memory :as ma]
            [im.ttalk.agent.react :as agent-loop]
            [im.ttalk.agent.test-support :as ts]
            [im.ttalk.agent.tool :refer [deftool]]))

(def ^:private executed (atom nil))

(deftool echo-args
  "回显参数（记录是否执行）"
  [[v :string "值"]]
  (reset! executed v)
  (str "执行了: " v))

;;; ============================================================
;;; react 层
;;; ============================================================

(defn- pause-then [final-text]
  (let [calls (atom 0)]
    {:chat-fn (fn [_ _]
                (if (= 1 (swap! calls inc))
                  (response/make-response :text nil
                    :tool-calls [{:id "p1" :name "echo-args" :args {:v "原参数"}}])
                  (response/make-response :text final-text :tool-calls nil)))}))

(defn- paused-run [cm conv-id]
  (let [store (memory/in-memory-store)
        chat-client (core/build-chat-client {:chat-model cm :tools [#'echo-args]
                                             :filters [(ma/memory-filter store)]})
        r (agent-loop/invoke chat-client store [(msg/user "干活")]
            {:context (context/with-conversation-id (context/create) conv-id)
             :tool-gate (fn [_] :pause)})]
    (is (= :paused (:status r)))
    (is (= "p1" (get-in r [:loop-state :pending-id])) "loop-state 携带 pending-id")
    {:chat-client chat-client :store store :r r
     :opts {:context (context/with-conversation-id (context/create) conv-id)}}))

(deftest rejected-with-message-test
  (reset! executed nil)
  (let [{:keys [chat-client r opts store]} (paused-run (pause-then "收到") "rp-1")
        r2 (agent-loop/resume chat-client (:loop-state r) :rejected
             (assoc opts :payload {:message "参数有风险，先改 v"}
                         :tool-gate (fn [_] :pause)))]
    (is (= :completed (:status r2)))
    (is (nil? @executed) "拒绝不执行")
    (let [tool-msg (->> (memory/mem-get store "rp-1")
                        (filter #(= :tool (:role %))) first)]
      (is (= "已拒绝执行：参数有风险，先改 v" (:content tool-msg))
          "模型直接拿到拒绝理由"))))

(deftest approved-with-args-test
  (reset! executed nil)
  (let [{:keys [chat-client r opts]} (paused-run (pause-then "改参执行完") "rp-2")
        r2 (agent-loop/resume chat-client (:loop-state r) :approved
             (assoc opts :payload {:args {:v "改后的参数"}}))]
    (is (= :completed (:status r2)))
    (is (= "改后的参数" @executed) "pending 工具以替换后的参数执行")))

(deftest reply-as-result-test
  (reset! executed nil)
  (let [{:keys [chat-client r opts store]} (paused-run (pause-then "明白") "rp-3")
        r2 (agent-loop/resume chat-client (:loop-state r) :reply
             (assoc opts :payload {:message "用户选择了 B 方案"}))]
    (is (= :completed (:status r2)))
    (is (nil? @executed) "reply 不执行工具")
    (let [tool-msg (->> (memory/mem-get store "rp-3")
                        (filter #(= :tool (:role %))) first)]
      (is (= "用户选择了 B 方案" (:content tool-msg)) "答复即工具结果"))
    (is (some #(= "用户选择了 B 方案" (:result %)) (:tool-calls-made r2))
        "records 记录 reply 值")))

(deftest reply-validation-test
  (let [{:keys [chat-client r opts]} (paused-run (pause-then "x") "rp-4")]
    (testing ":reply 缺 :message → 抛"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":message"
            (agent-loop/resume chat-client (:loop-state r) :reply opts))))
    (testing ":reply 遇旧版无 :pending-id 的 loop-state → 抛"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pending-id"
            (agent-loop/resume chat-client (dissoc (:loop-state r) :pending-id) :reply
              (assoc opts :payload {:message "m"})))))))

;;; ============================================================
;;; client 端到端：ask-user 模式
;;; ============================================================

(def ^:private ask-ran (atom false))

(deftool ask-user
  "向用户提问（永不真正执行——gate 拦截暂停，resume reply 送回答案）"
  [[question :string "问题"]]
  (reset! ask-ran true)
  "不应执行到这里")

(deftest ask-user-pattern-test
  (reset! ask-ran false)
  (let [provider (ts/create-mock-provider
                   [{:text nil :tool-calls [{:id "q1" :name "ask-user"
                                             :args {:question "要 A 还是 B？"}}]}
                    {:text "好的，按 B 方案执行" :tool-calls nil}])
        mem (memory/in-memory-store)
        a (agent/create-agent
            {:provider provider :model "test" :tools [#'ask-user]
             :memory mem :conversation-id "ask-1"
             :callbacks {:on-tool-call (fn [n _] (when (= "ask-user" n)
                                                   {:interrupt "等用户回答"}))}})]
    (let [r (agent/chat a "帮我选方案")]
      (is (= :paused (:status r)))
      (is (= "要 A 还是 B？" (get-in r [:pending-tool :args :question])
          ) "宿主把问题呈给用户"))
    (let [r2 (agent/resume a "reply" {:message "B"})]
      (is (= :completed (:status r2)))
      (is (= "好的，按 B 方案执行" (:text r2)))
      (is (false? @ask-ran) "工具体从未执行")
      (let [tool-msg (->> (memory/mem-get mem "ask-1")
                          (filter #(= :tool (:role %))) first)]
        (is (= "B" (:content tool-msg)) "用户答复作为工具结果进历史")))))

(deftest env-phase-rejects-reply-test
  (testing "环境类暂停不支持 reply"
    (let [provider (ts/create-mock-provider
                     [{:text nil :tool-calls [{:id "f1" :name "broken-env" :args {}}]}])
          mem (memory/in-memory-store)]
      ;; 内联定义环境失败工具
      (let [a (agent/create-agent
                {:provider provider :model "test"
                 :tools [{:name "broken-env" :description "env 失败"
                          :input_schema {:type "object" :properties {} :required []}
                          :handler (fn [_ _] (throw (ex-info "凭证失效"
                                                             {:error-class :environment})))}]
                 :memory mem :conversation-id "envr-1"
                 :on-env-error :pause})]
        (is (= :paused (:status (agent/chat a "干活"))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"不支持"
              (agent/resume a "reply" {:message "x"})))))))
