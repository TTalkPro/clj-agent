(ns im.ttalk.agent.subagent.tools-test
  "R-14 子代理异步工具组（designs/runtime-borrow.md §14）。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [im.ttalk.agent.chat-client :as cc]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.subagent.manager :as mgr]
            [im.ttalk.agent.subagent.tools :as st]))

(use-fixtures :each (fn [f] (mgr/clear-all!) (f) (mgr/clear-all!)))

(defn- counting-cm
  "回复「收到 N 条用户消息：<最后一条>」——send 续聊时 N 会增长。"
  [& [delay-ms]]
  {:chat-fn (fn [ms _]
              (when delay-ms (Thread/sleep delay-ms))
              (let [users (filter msg/user? ms)]
                (response/make-response :text (str "收到 " (count users) " 条用户消息：" (:content (last users))))))})

(defn- tools [cfg]
  (into {} (map (juxt :name :handler)) (st/subagent-tools cfg)))

(defn- id-of [s] (second (re-find #"id: (\S+)" s)))

(deftest spawn-wait-send-test
  (let [t (tools {:subagent-fn (fn [_ _] {:chat-client (cc/build-chat-client {:chat-model (counting-cm)})})})
        ctx {:conversation-id "p-1"}
        id (id-of ((t "spawn_agent") {:task "查天气"} ctx))]
    (testing "spawn → wait 拿结果"
      (is (str/includes? ((t "wait_agent") {:ids [id]} ctx) "收到 1 条用户消息：查天气")))
    (testing "send 续聊带历史（同一 id）"
      (is (= (str "已发送，id: " id) ((t "send_agent") {:id id :message "明天呢"} ctx)))
      (is (str/includes? ((t "wait_agent") {:ids [id]} ctx) "收到 2 条用户消息：明天呢")))
    (testing "list 只列本会话；别的会话看不到"
      (is (str/includes? ((t "list_agents") {} ctx) id))
      (is (= "无子 Agent" ((t "list_agents") {} {:conversation-id "other"})))
      (is (= "未找到该子 Agent" ((t "send_agent") {:id id :message "x"} {:conversation-id "other"}))))))

(deftest limits-test
  (testing "同时运行数量上限"
    (let [t (tools {:max-agents 1
                    :subagent-fn (fn [_ _] {:chat-client (cc/build-chat-client {:chat-model (counting-cm 2000)})})})
          ctx {:conversation-id "p-2"}
          id (id-of ((t "spawn_agent") {:task "慢任务"} ctx))]
      (is (str/starts-with? ((t "spawn_agent") {:task "再来一个"} ctx) "拒绝"))
      (testing "interrupt 生效"
        (is (= "已中止" ((t "interrupt_agent") {:id id} ctx)))
        (is (str/includes? ((t "wait_agent") {:ids [id]} ctx) "killed")))))
  (testing "嵌套深度上限：深度经 :tool-context 显式传递"
    (let [seen (atom nil)
          t (tools {:max-depth 2
                    :subagent-fn (fn [_ _] {:chat-client (cc/build-chat-client {:chat-model (counting-cm)})})})]
      (is (str/starts-with? ((t "spawn_agent") {:task "x"} {:conversation-id "p-3" :subagent/depth 2}) "拒绝"))
      (with-redefs [mgr/spawn! (fn [spec] (reset! seen spec) {:ok "s1"})]
        ((t "spawn_agent") {:task "x"} {:conversation-id "p-3" :subagent/depth 1}))
      (is (= 2 (get-in @seen [:subagent-config :tool-context :subagent/depth])))))
  (testing "wait 超时如实报告"
    (let [t (tools {:subagent-fn (fn [_ _] {:chat-client (cc/build-chat-client {:chat-model (counting-cm 2000)})})})
          ctx {:conversation-id "p-4"}
          id (id-of ((t "spawn_agent") {:task "慢"} ctx))]
      (is (str/includes? ((t "wait_agent") {:ids [id] :timeout_ms 50} ctx) "等待超时")))))
