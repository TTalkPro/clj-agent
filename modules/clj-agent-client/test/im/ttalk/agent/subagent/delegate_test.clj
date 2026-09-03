(ns im.ttalk.agent.subagent.delegate-test
  "委派工具：spec 的组装（含观察者透传）。

   manager 用 with-redefs 打桩——本 ns 验的是「工具 handler 递给 manager 的是什么」，
   不是子 agent 真跑起来会怎样（那在 manager-test）。"
  (:require [clojure.test :refer [deftest testing is]]
            [im.ttalk.agent.subagent.delegate :as delegate]
            [im.ttalk.agent.subagent.manager :as mgr]))

(defn- capture-specs
  "跑 f，收集这期间所有 mgr/spawn! 收到的 spec。返回 [ret specs]。"
  [f]
  (let [specs (atom [])
        n (atom -1)]
    (with-redefs [mgr/spawn! (fn [spec]
                               (swap! specs conj spec)
                               {:ok (str "sid-" (swap! n inc))})
                  mgr/await! (fn [id _] {:ok (str "结果@" id)})
                  mgr/kill!  (fn [_] nil)
                  mgr/drop!  (fn [_] nil)]
      [(f) @specs])))

(def ^:private observer-marker (fn [_] {:start! (fn [])}))

(def ^:private base-config
  {:name "deep_research"
   :subagent-fn (fn [_ _] {:provider :fake})
   :observer observer-marker})

(deftest delegate-tool-threads-observer-test
  (testing "单委派：observer / 子 agent 名 / 原始 task 进 spec"
    (let [tool (delegate/delegate-tool base-config)
          [ret specs] (capture-specs
                       #((:handler tool) {:task "查冷暴露" :context "背景很长"}
                                         {:conversation-id "c1"}))
          spec (first specs)]
      (is (= 1 (count specs)))
      (is (identical? observer-marker (:observer spec)))
      (is (= "deep_research" (:subagent-name spec)) "缺省取工具名")
      (is (= "查冷暴露" (:task spec))
          ":task 是原始任务，不是拼上 seed / 背景后的 prompt")
      (is (= "背景很长\n\n查冷暴露" (:prompt spec)) "prompt 照旧是拼好的那份")
      (is (= "c1" (:owner spec)))
      (is (string? ret))))

  (testing "本次委派是哪个 tool-call 发起的——从 ToolContext 的 :tool/call-id 取"
    (let [tool (delegate/delegate-tool base-config)
          [_ specs] (capture-specs #((:handler tool) {:task "x"}
                                     {:conversation-id "c1" :tool/call-id "tc-9"}))]
      (is (= "tc-9" (:parent-tool-call-id (first specs))))))

  (testing "拿不到就空着——协议里是可选字段，猜一个比空着坏"
    (let [tool (delegate/delegate-tool base-config)
          [_ specs] (capture-specs #((:handler tool) {:task "x"} {}))]
      (is (nil? (:parent-tool-call-id (first specs))))))

  (testing ":subagent-name 可以显式盖掉工具名"
    (let [tool (delegate/delegate-tool (assoc base-config :subagent-name "researcher"))
          [_ specs] (capture-specs #((:handler tool) {:task "x"} {}))]
      (is (= "researcher" (:subagent-name (first specs))))))

  (testing "不给 :observer 就是今天的行为——spec 里那个键是 nil"
    (let [tool (delegate/delegate-tool (dissoc base-config :observer))
          [_ specs] (capture-specs #((:handler tool) {:task "x"} {}))]
      (is (nil? (:observer (first specs)))))))

(deftest fanout-tool-names-each-lane-test
  (testing "fan-out：每路一个名字，否则前端 N 条 lane 除了 id 谁也认不出谁"
    (let [tool (delegate/fanout-tool base-config)
          [ret specs] (capture-specs
                       #((:handler tool) {:tasks ["甲" "乙" "丙"]} {:conversation-id "c1"}))]
      (is (= 3 (count specs)))
      (is (= ["deep_research#0" "deep_research#1" "deep_research#2"]
             (mapv :subagent-name specs)))
      (is (= ["甲" "乙" "丙"] (mapv :task specs)))
      (is (every? #(identical? observer-marker (:observer %)) specs))
      (is (every? #(= "c1" (:owner %)) specs))
      (is (re-find #"结果@sid-" ret) "汇总照旧")))

  (testing "N 路共享同一个 tool-call id——它们本就是一次调用的扇出"
    (let [tool (delegate/fanout-tool base-config)
          [_ specs] (capture-specs #((:handler tool) {:tasks ["甲" "乙"]}
                                     {:tool/call-id "tc-7"}))]
      (is (= ["tc-7" "tc-7"] (mapv :parent-tool-call-id specs)))))

  (testing "空 tasks 仍是那句错误，一个子 agent 都不 spawn"
    (let [tool (delegate/fanout-tool base-config)
          [ret specs] (capture-specs #((:handler tool) {:tasks []} {}))]
      (is (empty? specs))
      (is (= "错误：tasks 不能为空" ret)))))

(deftest management-tools-thread-observer-test
  (testing "spawn_subagent 也带上观察者（异步 lane 的边界见设计文档 §3.7b）"
    (let [tools (delegate/management-tools base-config)
          spawn (first (filter #(= "spawn_subagent" (:name %)) tools))
          [ret specs] (capture-specs #((:handler spawn) {:task "异步查一下"} {:conversation-id "c1"}))
          spec (first specs)]
      (is (identical? observer-marker (:observer spec)))
      (is (= "subagent" (:subagent-name spec)) "management-tools 的缺省名")
      (is (= "异步查一下" (:task spec)))
      (is (re-find #"sid-0" ret)))))
