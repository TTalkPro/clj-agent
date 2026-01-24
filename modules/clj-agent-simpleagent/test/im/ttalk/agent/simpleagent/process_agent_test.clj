(ns im.ttalk.agent.simpleagent.process-agent-test
  "Process Agent 单元测试

   使用 mock provider 验证 process-agent 的 pause/resume 功能。"
  (:require [clojure.test :refer :all]
            [im.ttalk.agent.simpleagent.process-agent :as pa]
            [im.ttalk.agent.simpleagent.test-support :as ts]
            [im.ttalk.agent.core.kernel.context :as ctx]))

;;; ============================================================
;;; 测试
;;; ============================================================

(deftest test-normal-chat-no-tools
  (testing "普通对话（无工具调用）- 直接完成"
    (let [provider (ts/create-mock-provider
                     [{:text "你好！" :tool-calls nil}])
          agent (pa/create-process-agent {:provider provider
                                          :model "test"
                                          :tools [ts/test-plugin]})]
      (let [result (pa/chat agent "你好")]
        (is (= :completed (:status result)))
        (is (= "你好！" (:text result)))
        (is (not (pa/paused? agent)))))))

(deftest test-safe-tool-call
  (testing "安全工具调用 - 不暂停，直接完成"
    (let [provider (ts/create-mock-provider
                     [;; 返回安全工具调用
                      {:text nil
                       :tool-calls [{:id "call_1" :name :safe-tool
                                     :input {:input "测试数据"}}]}
                      ;; 返回文本
                      {:text "工具执行完毕" :tool-calls nil}])
          agent (pa/create-process-agent {:provider provider
                                          :model "test"
                                          :tools [ts/test-plugin]})]
      (let [result (pa/chat agent "执行安全操作")]
        (is (= :completed (:status result)))
        (is (= "工具执行完毕" (:text result)))
        (is (= 1 (count (:tool-calls-made result))))
        (is (= :safe-tool (:name (first (:tool-calls-made result)))))))))

(deftest test-sensitive-tool-pause
  (testing "sensitive 工具暂停 - 返回 :paused"
    (let [provider (ts/create-mock-provider
                     [;; 返回 sensitive 工具调用
                      {:text nil
                       :tool-calls [{:id "call_1" :name :dangerous-tool
                                     :input {:target "/tmp/test"}}]}])
          agent (pa/create-process-agent {:provider provider
                                          :model "test"
                                          :tools [ts/test-plugin]})]
      (let [result (pa/chat agent "删除文件")]
        (is (= :paused (:status result)))
        (is (nil? (:text result)))
        (is (string? (:pause-reason result)))
        (is (= :dangerous-tool (:name (:pending-tool result))))
        (is (pa/paused? agent))))))

(deftest test-resume-approved
  (testing "resume approved - 工具执行，继续循环"
    (let [provider (ts/create-mock-provider
                     [;; 第一次：返回 sensitive 工具调用
                      {:text nil
                       :tool-calls [{:id "call_1" :name :dangerous-tool
                                     :input {:target "目标"}}]}
                      ;; resume 后：返回文本
                      {:text "操作已完成" :tool-calls nil}])
          agent (pa/create-process-agent {:provider provider
                                          :model "test"
                                          :tools [ts/test-plugin]})]
      ;; 触发暂停
      (pa/chat agent "执行危险操作")
      (is (pa/paused? agent))

      ;; 批准
      (let [result (pa/resume agent "approved")]
        (is (= :completed (:status result)))
        (is (= "操作已完成" (:text result)))
        (is (not (pa/paused? agent)))))))

(deftest test-resume-rejected
  (testing "resume rejected - 拒绝消息加入，循环继续"
    (let [provider (ts/create-mock-provider
                     [;; 第一次：返回 sensitive 工具调用
                      {:text nil
                       :tool-calls [{:id "call_1" :name :dangerous-tool
                                     :input {:target "目标"}}]}
                      ;; resume rejected 后：LLM 收到拒绝消息，返回文本
                      {:text "好的，已取消操作" :tool-calls nil}])
          agent (pa/create-process-agent {:provider provider
                                          :model "test"
                                          :tools [ts/test-plugin]})]
      ;; 触发暂停
      (pa/chat agent "执行危险操作")
      (is (pa/paused? agent))

      ;; 拒绝
      (let [result (pa/resume agent "rejected")]
        (is (= :completed (:status result)))
        (is (= "好的，已取消操作" (:text result)))
        (is (not (pa/paused? agent)))))))

(deftest test-paused-predicate
  (testing "paused? 谓词在各状态下正确"
    (let [provider (ts/create-mock-provider
                     [{:text "正常" :tool-calls nil}])
          agent (pa/create-process-agent {:provider provider
                                          :model "test"
                                          :tools [ts/test-plugin]})]
      ;; 初始状态
      (is (not (pa/paused? agent)))

      ;; 正常对话后
      (pa/chat agent "你好")
      (is (not (pa/paused? agent))))))

(deftest test-reset-clears-pause
  (testing "reset! 清除暂停状态"
    (let [provider (ts/create-mock-provider
                     [{:text nil
                       :tool-calls [{:id "call_1" :name :dangerous-tool
                                     :input {:target "x"}}]}])
          agent (pa/create-process-agent {:provider provider
                                          :model "test"
                                          :tools [ts/test-plugin]})]
      (pa/chat agent "危险操作")
      (is (pa/paused? agent))

      (pa/reset! agent)
      (is (not (pa/paused? agent)))
      (is (empty? (ctx/get-messages (pa/get-context agent)))))))

(deftest test-on-pause-callback
  (testing "on-pause 回调被调用"
    (let [callback-log (atom nil)
          provider (ts/create-mock-provider
                     [{:text nil
                       :tool-calls [{:id "call_1" :name :dangerous-tool
                                     :input {:target "重要文件"}}]}])
          agent (pa/create-process-agent {:provider provider
                                          :model "test"
                                          :tools [ts/test-plugin]
                                          :on-pause (fn [info]
                                                      (reset! callback-log info))})]
      (pa/chat agent "删除重要文件")
      (is (some? @callback-log))
      (is (string? (:reason @callback-log)))
      (is (= :dangerous-tool (:name (:pending-tool @callback-log)))))))

(deftest test-resume-not-paused-throws
  (testing "resume 非暂停状态抛异常"
    (let [provider (ts/create-mock-provider
                     [{:text "正常" :tool-calls nil}])
          agent (pa/create-process-agent {:provider provider
                                          :model "test"
                                          :tools [ts/test-plugin]})]
      (pa/chat agent "你好")
      (is (thrown? clojure.lang.ExceptionInfo
                   (pa/resume agent "approved"))))))

(deftest test-mixed-safe-and-sensitive-tools
  (testing "混合安全和 sensitive 工具 - 遇到第一个 sensitive 暂停"
    (let [provider (ts/create-mock-provider
                     [;; 返回混合工具调用（safe + sensitive）
                      {:text nil
                       :tool-calls [{:id "call_1" :name :safe-tool
                                     :input {:input "数据"}}
                                    {:id "call_2" :name :dangerous-tool
                                     :input {:target "目标"}}]}
                      ;; approve 后返回文本
                      {:text "全部完成" :tool-calls nil}])
          agent (pa/create-process-agent {:provider provider
                                          :model "test"
                                          :tools [ts/test-plugin]})]
      (let [result (pa/chat agent "执行混合操作")]
        ;; 应该在 dangerous-tool 处暂停
        (is (= :paused (:status result)))
        (is (= :dangerous-tool (:name (:pending-tool result))))))))

(deftest test-get-context
  (testing "get-context 返回当前上下文"
    (let [provider (ts/create-mock-provider
                     [{:text "回复" :tool-calls nil}])
          agent (pa/create-process-agent {:provider provider
                                          :model "test"
                                          :tools [ts/test-plugin]})]
      (is (ctx/context? (pa/get-context agent)))
      (pa/chat agent "消息")
      (is (seq (ctx/get-messages (pa/get-context agent)))))))
