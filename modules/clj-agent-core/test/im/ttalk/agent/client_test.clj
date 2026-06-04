(ns im.ttalk.agent.client-test
  "统一 Agent 单元测试（合并原 kernel-agent / process-agent 测试）

   覆盖：基础对话 / 多轮 / 工具 / reset / system-prompt / pause-resume /
   conversation-id 恢复 / SQLite 持久化 / 未-resume 保护。"
  (:require [clojure.test :refer :all]
            [im.ttalk.agent.client :as agent]
            [im.ttalk.agent.test-support :as ts]
            [im.ttalk.agent.kernel :as kernel]
            [im.ttalk.agent.model.service :as service]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.memory.sqlite :as sqlite]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.provider :as provider])
  (:import [java.io File]))

;;; ============================================================
;;; 基础对话
;;; ============================================================

(deftest basic-chat-test
  (testing "无工具：返回 :completed + text"
    (let [p (ts/create-mock-provider [{:text "你好！我是助手。" :tool-calls nil}])
          a (agent/create-agent {:provider p :model "test"})
          r (agent/chat a "你好")]
      (is (= :completed (:status r)))
      (is (= "你好！我是助手。" (:text r)))
      (is (empty? (:tool-calls-made r))))))

(deftest multi-turn-test
  (testing "多轮：历史自动累积"
    (let [p (ts/create-mock-provider [{:text "你好！" :tool-calls nil}
                                      {:text "你说了'测试'" :tool-calls nil}])
          a (agent/create-agent {:provider p :model "test"})]
      (agent/chat a "测试")
      (is (= 2 (count (agent/get-history a))))      ;; user + assistant
      (agent/chat a "我说了啥？")
      (is (= 4 (count (agent/get-history a)))))))

(deftest tool-calling-test
  (testing "工具调用：先 tool_calls 后 text"
    (let [p (ts/create-mock-provider
              [{:text nil :tool-calls [{:id "c1" :name :mock-get-weather :input {:city "北京"}}]}
               {:text "北京晴 25°C" :tool-calls nil}])
          a (agent/create-agent {:provider p :model "test" :tools ts/mock-tools})
          r (agent/chat a "北京天气？")]
      (is (= "北京晴 25°C" (:text r)))
      (is (= 1 (count (:tool-calls-made r))))
      (is (= :mock-get-weather (:name (first (:tool-calls-made r))))))))

(deftest reset-test
  (testing "reset! 清空历史"
    (let [p (ts/create-mock-provider [{:text "回复1" :tool-calls nil}])
          a (agent/create-agent {:provider p :model "test"})]
      (agent/chat a "消息1")
      (is (= 2 (count (agent/get-history a))))
      (agent/reset! a)
      (is (empty? (agent/get-history a))))))

(deftest get-history==get-messages-test
  (let [p (ts/create-mock-provider [{:text "回复" :tool-calls nil}])
        a (agent/create-agent {:provider p :model "test"})]
    (agent/chat a "你好")
    (is (= (agent/get-messages a) (agent/get-history a)))))

(defn- spy-provider [log]
  (reify provider/ILLMProvider
    (provider-name [_] :spy)
    (call-llm [_ config _messages _tools]
      (swap! log conj config)
      {:text "OK" :tool-calls nil})
    (extract-tool-calls [_ r] (:tool-calls r))
    (extract-text [_ r] (:text r))
    (build-tool-result [_ tid c] {:role "tool" :tool_call_id tid :content c})
    (build-assistant-message [_ r] {:role "assistant" :content (:text r)})
    (build-result-messages [_ am trs] (into [am] trs))
    (supports-function-calling? [_] true)
    (supports-stream? [_] false)
    (call-llm-stream [this c m t _] (provider/call-llm this c m t))
    (tool->schema [_ t] t)))

(deftest system-prompt-test
  (testing "system-prompt 经 settings 传到 chat-fn config"
    (let [log (atom [])
          a (agent/create-agent {:provider (spy-provider log) :model "test"
                                 :system-prompt "你是数学助手"})]
      (agent/chat a "1+1?")
      (is (= "你是数学助手" (:system-prompt (first @log))))))
  (testing "chat opts 覆盖 system-prompt"
    (let [log (atom [])
          a (agent/create-agent {:provider (spy-provider log) :model "test"
                                 :system-prompt "默认"})]
      (agent/chat a "?" {:system-prompt "覆盖"})
      (is (= "覆盖" (:system-prompt (first @log)))))))

(deftest pre-built-kernel-test
  (testing ":kernel 选项直接复用"
    (let [p (ts/create-mock-provider [{:text "来自预构建" :tool-calls nil}])
          svc (service/create-service p {:model "test" :max-tokens 100})
          k (kernel/build-kernel {:service svc})
          a (agent/create-agent {:kernel k})]
      (is (= "来自预构建" (:text (agent/chat a "测试")))))))

;;; ============================================================
;;; pause / resume（需配置 :on-pause 启用 gate）
;;; ============================================================

(deftest no-pause-without-on-pause-test
  (testing "不配 on-pause：敏感工具不暂停，直接执行"
    (let [p (ts/create-mock-provider
              [{:text nil :tool-calls [{:id "c1" :name :dangerous-tool :input {:target "x"}}]}
               {:text "已执行" :tool-calls nil}])
          a (agent/create-agent {:provider p :model "test" :tools ts/test-plugin})] ;; 无 :on-pause
      (let [r (agent/chat a "删除")]
        (is (= :completed (:status r)))
        (is (= :dangerous-tool (:name (first (:tool-calls-made r)))))))))

(deftest sensitive-pause-test
  (testing "配 on-pause：敏感工具暂停"
    (let [p (ts/create-mock-provider
              [{:text nil :tool-calls [{:id "c1" :name :dangerous-tool :input {:target "/tmp/x"}}]}])
          a (agent/create-agent {:provider p :model "test" :tools ts/test-plugin
                                 :on-pause (fn [_] nil)})
          r (agent/chat a "删除文件")]
      (is (= :paused (:status r)))
      (is (string? (:pause-reason r)))
      (is (= :dangerous-tool (:name (:pending-tool r))))
      (is (agent/paused? a)))))

(deftest resume-approved-test
  (let [p (ts/create-mock-provider
            [{:text nil :tool-calls [{:id "c1" :name :dangerous-tool :input {:target "目标"}}]}
             {:text "操作已完成" :tool-calls nil}])
        a (agent/create-agent {:provider p :model "test" :tools ts/test-plugin
                               :on-pause (fn [_] nil)})]
    (agent/chat a "执行危险操作")
    (is (agent/paused? a))
    (let [r (agent/resume a "approved")]
      (is (= :completed (:status r)))
      (is (= "操作已完成" (:text r)))
      (is (not (agent/paused? a))))))

(deftest resume-rejected-test
  (let [p (ts/create-mock-provider
            [{:text nil :tool-calls [{:id "c1" :name :dangerous-tool :input {:target "目标"}}]}
             {:text "好的，已取消" :tool-calls nil}])
        a (agent/create-agent {:provider p :model "test" :tools ts/test-plugin
                               :on-pause (fn [_] nil)})]
    (agent/chat a "执行危险操作")
    (let [r (agent/resume a "rejected")]
      (is (= :completed (:status r)))
      (is (= "好的，已取消" (:text r)))
      ;; 拒绝必须真正阻止敏感工具执行：历史里是「已拒绝执行」而非「已执行危险操作」
      (let [tool-msgs (filter msg/tool? (agent/get-history a))]
        (is (some #(re-find #"已拒绝执行" (:content %)) tool-msgs))
        (is (not-any? #(re-find #"已执行危险操作" (:content %)) tool-msgs))))))

(deftest mixed-tools-pause-test
  (testing "safe + sensitive 混合：在 sensitive 处暂停"
    (let [p (ts/create-mock-provider
              [{:text nil :tool-calls [{:id "c1" :name :safe-tool :input {:input "数据"}}
                                       {:id "c2" :name :dangerous-tool :input {:target "目标"}}]}
               {:text "全部完成" :tool-calls nil}])
          a (agent/create-agent {:provider p :model "test" :tools ts/test-plugin
                                 :on-pause (fn [_] nil)})
          r (agent/chat a "混合操作")]
      (is (= :paused (:status r)))
      (is (= :dangerous-tool (:name (:pending-tool r)))))))

(deftest on-pause-callback-test
  (let [log (atom nil)
        p (ts/create-mock-provider
            [{:text nil :tool-calls [{:id "c1" :name :dangerous-tool :input {:target "重要"}}]}])
        a (agent/create-agent {:provider p :model "test" :tools ts/test-plugin
                               :on-pause (fn [info] (reset! log info))})]
    (agent/chat a "删除重要文件")
    (is (some? @log))
    (is (string? (:reason @log)))
    (is (= :dangerous-tool (:name (:pending-tool @log))))))

(deftest resume-not-paused-throws-test
  (let [p (ts/create-mock-provider [{:text "正常" :tool-calls nil}])
        a (agent/create-agent {:provider p :model "test" :tools ts/test-plugin
                               :on-pause (fn [_] nil)})]
    (agent/chat a "你好")
    (is (thrown? clojure.lang.ExceptionInfo (agent/resume a "approved")))))

;;; ============================================================
;;; 未-resume 保护
;;; ============================================================

(deftest cancel-pending-protection-test
  (testing "暂停后不 resume 直接开新对话：补「已取消」结果，无悬空 tool_use"
    (let [p (ts/create-mock-provider
              [{:text nil :tool-calls [{:id "c1" :name :dangerous-tool :input {:target "x"}}]}
               {:text "新回答" :tool-calls nil}])
          a (agent/create-agent {:provider p :model "test" :tools ts/test-plugin
                                 :on-pause (fn [_] nil)})]
      (is (= :paused (:status (agent/chat a "删除"))))
      ;; 不 resume，直接新对话
      (let [r (agent/chat a "换个问题")]
        (is (= :completed (:status r)))
        (is (not (agent/paused? a)))
        (let [hist (agent/get-history a)
              tool-msgs (filter msg/tool? hist)]
          ;; 存在「已取消」工具结果，且 assistant(tool-calls) 已被配对
          (is (some #(re-find #"已取消" (:content %)) tool-msgs)))))))

(deftest kernel-heals-dangling-on-shared-store-test
  (testing "暂停未 resume，另一 agent（共享 store/同 conv）开新对话：kernel 自愈悬空 tool_use"
    (let [store (memory/in-memory-store)
          p1 (ts/create-mock-provider
               [{:text nil :tool-calls [{:id "c1" :name :dangerous-tool :input {:target "/tmp/x"}}]}])
          a1 (agent/create-agent {:provider p1 :model "test" :tools ts/test-plugin
                                  :memory store :conversation-id "shared-heal"
                                  :on-pause (fn [_] nil)})]
      (is (= :paused (:status (agent/chat a1 "删除"))))
      ;; 第二个 agent 自身不处于暂停态 → cancel-pending! 不触发，纯靠 kernel 入口自愈
      (let [p2 (ts/create-mock-provider [{:text "新回答" :tool-calls nil}])
            a2 (agent/create-agent {:provider p2 :model "test" :tools ts/test-plugin
                                    :memory store :conversation-id "shared-heal"})
            r (agent/chat a2 "换个问题")
            hist (agent/get-history a2)
            call-ids (into #{} (for [m hist :when (msg/assistant? m)
                                     tc (:tool-calls m)] (:id tc)))
            paired (into #{} (keep :tool-call-id hist))]
        (is (= :completed (:status r)))
        (is (some #(and (msg/tool? %) (re-find #"已取消" (:content %))) hist))
        (is (every? paired call-ids) "历史中无悬空 tool_use（全部已配对结果）")))))

;;; ============================================================
;;; conversation-id 恢复（共享 store）
;;; ============================================================

(deftest conversation-id-shared-store-test
  (testing "同 conversation-id + 共享 store：另一 agent 看到历史"
    (let [store (memory/in-memory-store)
          p1 (ts/create-mock-provider [{:text "记住了" :tool-calls nil}])
          a1 (agent/create-agent {:provider p1 :model "test" :memory store
                                  :conversation-id "user-7"})]
      (agent/chat a1 "我叫小明")
      (let [p2 (ts/create-mock-provider [{:text "" :tool-calls nil}])
            a2 (agent/create-agent {:provider p2 :model "test" :memory store
                                    :conversation-id "user-7"})]
        ;; a2 未对话即可见 a1 的历史
        (is (= 2 (count (agent/get-history a2))))
        (is (= "我叫小明" (:content (first (agent/get-history a2)))))))))

;;; ============================================================
;;; SQLite 持久化
;;; ============================================================

(defn- temp-db []
  (let [f (File/createTempFile "clj-agent-test" ".db")]
    (.deleteOnExit f)
    (.getAbsolutePath f)))

(deftest sqlite-store-basic-test
  (let [path (temp-db)
        s (sqlite/sqlite-store path)]
    (testing "add/get/clear"
      (is (= [] (memory/mem-get s "c1")))
      (memory/mem-add s "c1" [(msg/user "a") (msg/assistant "b")])
      (is (= [(msg/user "a") (msg/assistant "b")] (memory/mem-get s "c1")))
      (memory/mem-clear s "c1")
      (is (= [] (memory/mem-get s "c1"))))
    (testing "中立消息（含 tool-calls）无损往返"
      (memory/mem-add s "c2"
        [(msg/assistant-tool-calls [(msg/tool-call "t1" "get_weather" {:city "北京"})])
         (msg/tool-result "t1" "get_weather" "晴")])
      (let [out (memory/mem-get s "c2")]
        (is (msg/has-tool-calls? (first out)))
        (is (= {:city "北京"} (:args (first (msg/tool-calls (first out))))))
        (is (= "t1" (msg/tool-call-id (second out))))))))

(deftest sqlite-persists-across-instances-test
  (testing "跨 store 实例（模拟重启）持久"
    (let [path (temp-db)
          s1 (sqlite/sqlite-store path)
          a (agent/create-agent {:provider (ts/create-mock-provider [{:text "ok" :tool-calls nil}])
                                 :model "test" :memory s1 :conversation-id "u1"})]
      (agent/chat a "你好")
      ;; 新建一个指向同文件的 store（= 重启后重新打开）
      (let [s2 (sqlite/sqlite-store path)]
        (is (= 2 (count (memory/mem-get s2 "u1"))))
        (is (= "你好" (:content (first (memory/mem-get s2 "u1")))))))))
