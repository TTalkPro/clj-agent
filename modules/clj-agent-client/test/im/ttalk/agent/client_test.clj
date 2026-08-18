(ns im.ttalk.agent.client-test
  "统一 Agent 单元测试（合并原 kernel-agent / process-agent 测试）

   覆盖：基础对话 / 多轮 / 工具 / reset / system-prompt / pause-resume /
   conversation-id 恢复 / SQLite 持久化 / 未-resume 保护。"
  (:require [clojure.test :refer :all]
            [im.ttalk.agent.client :as agent]
            [im.ttalk.agent.test-support :as ts]
            [im.ttalk.agent.kernel :as kernel]
            [im.ttalk.agent.model.service :as service]
            [im.ttalk.agent.advisor.memory :as ma]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.memory.sqlite :as sqlite]
            [im.ttalk.agent.model.content :as content]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.error :as errors]
            [im.ttalk.agent.streaming :as streaming]
            [im.ttalk.agent.model :as provider])
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
              [{:text nil :tool-calls [{:id "c1" :name "mock-get-weather" :args {:city "北京"}}]}
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

;;; ============================================================
;;; chat-stream（流式对话）
;;; ============================================================

(defn- streaming-provider
  "真流式 mock provider：每次 call-llm-stream 按 responses 顺序取一项，
   逐个 emit token，返回 {:text :tool-calls}。response = {:tokens [..] :tool-calls ..}。"
  [responses]
  (let [calls (atom 0)]
    (reify provider/ILLMProvider
      (provider-name [_] :stream-mock)
      (call-llm [_ _ _ _]
        (let [{:keys [tokens tool-calls]} (nth @responses (min @calls (dec (count @responses))))]
          {:text (when (seq tokens) (apply str tokens)) :tool-calls tool-calls}))
      (call-llm-stream [_ _ _ _ on-token]
        (let [n (swap! calls inc)
              {:keys [tokens tool-calls]} (nth @responses (dec n))]
          (doseq [t tokens] (when on-token (on-token {:token t})))
          {:text (when (seq tokens) (apply str tokens)) :tool-calls tool-calls}))
      (extract-tool-calls [_ r] (:tool-calls r))
      (extract-text [_ r] (:text r))
      (build-tool-result [_ tid c] {:role "tool" :tool_call_id tid :content c})
      (supports-function-calling? [_] true)
      (supports-stream? [_] true)
      (tool->schema [_ t] t))))

(deftest chat-stream-test
  (testing "流式：token 逐个回调，最终结果正确，历史落库（与 chat 不分叉）"
    (let [tokens (atom [])
          p (streaming-provider (atom [{:tokens ["你好" "，" "世界"] :tool-calls nil}]))
          a (agent/create-agent {:provider p :model "test"})
          r (agent/chat-stream a "hi" (fn [t] (when (:token t) (swap! tokens conj (:token t)))))]
      (is (= :completed (:status r)))
      (is (= "你好，世界" (:text r)))
      (is (= ["你好" "，" "世界"] @tokens))         ;; 逐 token
      (is (= 2 (count (agent/get-history a)))))))   ;; user + assistant

(deftest chat-stream-fallback-test
  (testing "provider 不支持流式 → service 回退同步，全文作为单个 token emit"
    (let [tokens (atom [])
          p (ts/create-mock-provider [{:text "回复内容" :tool-calls nil}])  ;; supports-stream? false
          a (agent/create-agent {:provider p :model "test"})
          r (agent/chat-stream a "hi" (fn [t] (when (:token t) (swap! tokens conj (:token t)))))]
      (is (= :completed (:status r)))
      (is (= "回复内容" (:text r)))
      (is (= ["回复内容"] @tokens)))))               ;; 单个 token（整段）

(defn- cancellable-provider
  "流式 mock：call-llm-stream 登记一个 cancel-fn（解除阻塞），emit 一个 token 后
   **阻塞**直到被取消——模拟真 provider 的在途流。"
  []
  (reify provider/ILLMProvider
    (provider-name [_] :cancel-mock)
    (call-llm [_ _ _ _] {:text "a" :tool-calls nil})
    (call-llm-stream [_ _ _ _ on-token]
      (let [unblock (promise)]
        (streaming/register-cancel! (fn [] (deliver unblock :cancel)))   ;; 应用 request-cancel! 时触发
        (when on-token (on-token {:token "a"}))
        (deref unblock 3000 :timeout)                                    ;; 阻塞直到取消（兜底 3s）
        {:text "a" :tool-calls nil}))
    (extract-tool-calls [_ r] (:tool-calls r))
    (extract-text [_ r] (:text r))
    (build-tool-result [_ tid c] {:role "tool" :tool_call_id tid :content c})
    (supports-function-calling? [_] true)
    (supports-stream? [_] true)
    (tool->schema [_ t] t)))

(deftest chat-stream-cancel-test
  (testing "request-cancel! 中止流式：登记的 cancel 被调用，循环返回 :cancelled，无更多 token"
    (let [tokens (atom [])
          token  (streaming/make-cancel-token)
          a (agent/create-agent {:provider (cancellable-provider) :model "test"})
          result (promise)]
      (future (deliver result
                       (agent/chat-stream a "hi"
                         (fn [t] (when (:token t) (swap! tokens conj (:token t))))
                         {:cancel-token token})))
      ;; 等第一个 token 流出（provider 此时阻塞在 unblock 上）
      (loop [i 0] (when (and (empty? @tokens) (< i 300)) (Thread/sleep 10) (recur (inc i))))
      (is (= ["a"] @tokens))
      ;; 请求取消 → 触发登记的 cancel-fn → provider 解除阻塞返回 → 循环检测到取消
      (streaming/request-cancel! token)
      (let [r (deref result 5000 :timeout)]
        (is (= :cancelled (:status r)))
        (is (= ["a"] @tokens))               ;; 取消后不再有 token
        (is (true? (streaming/cancelled? token)))))))

(deftest chat-stream-with-tools-test
  (testing "工具回合不流正文，最终文本回合逐 token；历史含完整 tool 链"
    (let [tokens (atom [])
          p (streaming-provider
              (atom [{:tokens [] :tool-calls [{:id "c1" :name "mock-get-weather" :args {:city "北京"}}]}
                     {:tokens ["北京" "晴" "25°C"] :tool-calls nil}]))
          a (agent/create-agent {:provider p :model "test" :tools ts/mock-tools})
          r (agent/chat-stream a "北京天气?" (fn [t] (when (:token t) (swap! tokens conj (:token t)))))]
      (is (= :completed (:status r)))
      (is (= "北京晴25°C" (:text r)))
      (is (= ["北京" "晴" "25°C"] @tokens))          ;; 仅最终文本回合产 token
      (is (= 1 (count (:tool-calls-made r))))
      ;; 历史：user, assistant(tool_calls), tool-result, assistant(text)
      (is (= 4 (count (agent/get-history a)))))))

(deftest prebuilt-kernel-and-memory-test
  (testing ":kernel 未传 :memory → 复用 kernel memory-filter 的 store，多轮历史不丢（回归 BUG5）"
    (let [p (ts/create-mock-provider [{:text "回复1" :tool-calls nil}
                                      {:text "回复2" :tool-calls nil}])
          kernel-store (memory/in-memory-store)
          svc (service/create-service p {:model "test" :max-tokens 100})
          k (kernel/build-kernel {:service svc
                                  :filters [(ma/memory-filter kernel-store)]})
          a (agent/create-agent {:kernel k})]
      (is (identical? kernel-store (:memory a)))
      ;; kernel 原样复用（store 未变不重建）
      (is (identical? k (:kernel a)))
      (agent/chat a "消息1")
      (is (= 2 (count (agent/get-history a))))
      (agent/chat a "消息2")
      (is (= 4 (count (agent/get-history a))))))

  (testing ":kernel + :memory 同时指定 → 以 :memory 为准，memory-filter 重挂到用户 store"
    (let [p (ts/create-mock-provider [{:text "回复1" :tool-calls nil}
                                      {:text "回复2" :tool-calls nil}])
          kernel-store (memory/in-memory-store)
          my-store (memory/in-memory-store)
          svc (service/create-service p {:model "test" :max-tokens 100})
          k (kernel/build-kernel {:service svc
                                  :filters [(ma/memory-filter kernel-store)]})
          a (agent/create-agent {:kernel k :memory my-store})]
      ;; agent 与重挂后的 kernel filter 用同一个用户 store（不脱节）
      (is (identical? my-store (:memory a)))
      (agent/chat a "消息1")
      (agent/chat a "消息2")
      (is (= 4 (count (memory/mem-get my-store (:conversation-id a)))))
      ;; kernel 原 store 从未被写入
      (is (empty? (memory/mem-get kernel-store (:conversation-id a))))))

  (testing ":kernel 无 memory-filter 且未传 :memory → 自动挂默认 store，多轮可用"
    (let [p (ts/create-mock-provider [{:text "回复1" :tool-calls nil}
                                      {:text "回复2" :tool-calls nil}])
          svc (service/create-service p {:model "test" :max-tokens 100})
          k (kernel/build-kernel {:service svc})
          a (agent/create-agent {:kernel k})]
      (is (some? (:memory a)))
      (is (= [:memory] (mapv :name (:filters (:kernel a)))))
      (agent/chat a "消息1")
      (agent/chat a "消息2")
      (is (= 4 (count (agent/get-history a))))))

  (testing ":kernel + :memory false → 移除 memory-filter，完全无记忆"
    (let [p (ts/create-mock-provider [{:text "回复1" :tool-calls nil}])
          kernel-store (memory/in-memory-store)
          svc (service/create-service p {:model "test" :max-tokens 100})
          k (kernel/build-kernel {:service svc
                                  :filters [(ma/memory-filter kernel-store)]})
          a (agent/create-agent {:kernel k :memory false})]
      (is (nil? (:memory a)))
      (is (empty? (filter #(= :memory (:name %)) (:filters (:kernel a)))))
      (is (= :completed (:status (agent/chat a "消息1"))))
      (is (empty? (agent/get-history a)))
      ;; kernel 原 store 不被写入
      (is (empty? (memory/mem-get kernel-store (:conversation-id a))))))

  (testing ":kernel + :memory 重挂时保留其他自定义 filter（顺序：memory 最前）"
    (let [filter-ran (atom false)
          audit {:name :audit
                 :chat (fn [req chain] (reset! filter-ran true) (chain req))}
          p (ts/create-mock-provider [{:text "OK" :tool-calls nil}])
          my-store (memory/in-memory-store)
          svc (service/create-service p {:model "test" :max-tokens 100})
          k (kernel/build-kernel {:service svc
                                  :filters [(ma/memory-filter (memory/in-memory-store)) audit]})
          a (agent/create-agent {:kernel k :memory my-store})]
      (is (= [:memory :audit] (mapv :name (:filters (:kernel a)))))
      (agent/chat a "你好")
      (is (true? @filter-ran) "自定义 filter 重挂后仍生效")
      (is (= 2 (count (memory/mem-get my-store (:conversation-id a))))))))

(defn- spy-provider [log]
  (reify provider/ILLMProvider
    (provider-name [_] :spy)
    (call-llm [_ config _messages _tools]
      (swap! log conj config)
      {:text "OK" :tool-calls nil})
    (extract-tool-calls [_ r] (:tool-calls r))
    (extract-text [_ r] (:text r))
    (build-tool-result [_ tid c] {:role "tool" :tool_call_id tid :content c})
    (supports-function-calling? [_] true)
    (supports-stream? [_] false)
    (call-llm-stream [this c m t _] (provider/call-llm this c m t))
    (tool->schema [_ t] t)))

(defn- throwing-provider [ex]
  (reify provider/ILLMProvider
    (provider-name [_] :boom)
    (call-llm [_ _config _messages _tools] (throw ex))
    (extract-tool-calls [_ r] (:tool-calls r))
    (extract-text [_ r] (:text r))
    (build-tool-result [_ tid c] {:role "tool" :tool_call_id tid :content c})
    (supports-function-calling? [_] true)
    (supports-stream? [_] false)
    (call-llm-stream [this c m t _] (provider/call-llm this c m t))
    (tool->schema [_ t] t)))

(deftest error-path-test
  (testing "provider 抛 IOException -> {:status :error}，分类为 network-error，不抛裸异常"
    (let [a (agent/create-agent {:provider (throwing-provider (java.io.IOException. "连接失败"))
                                 :model "test"})
          r (agent/chat a "你好")]
      (is (= :error (:status r)))
      (is (nil? (:text r)))
      (is (= :network-error (get-in r [:error :type])))
      (is (true? (get-in r [:error :retryable?])))
      ;; agent 状态落到 :error
      (is (= :error (:status @(:state-atom a))))))
  (testing "普通异常 -> provider-error，并触发 :callbacks :on-turn-error"
    (let [seen (atom nil)
          a (agent/create-agent {:provider (throwing-provider (RuntimeException. "boom"))
                                 :model "test"
                                 :callbacks {:on-turn-error (fn [err _m] (reset! seen err))}})
          r (agent/chat a "hi")]
      (is (= :error (:status r)))
      (is (= :provider-error (get-in r [:error :type])))
      (is (= :provider-error (:type @seen)))))
  (testing "provider 抛 canonical 401（D5）-> 端到端保留 :auth-error / 不可重试 / status"
    ;; 模拟 D5 后 provider 用 errors/throw! 抛 401：经 exception->error 幂等透传，
    ;; 不再被笼统归为 :provider-error（retryable? true）
    (let [err401 (errors/error :auth-error "Unauthorized" {:status 401 :provider :openai})
          a (agent/create-agent {:provider (throwing-provider (ex-info "Unauthorized" err401))
                                 :model "test"})
          r (agent/chat a "hi")]
      (is (= :error (:status r)))
      (is (= :auth-error (get-in r [:error :type])))
      (is (false? (get-in r [:error :retryable?])))
      (is (= 401 (get-in r [:error :status]))))))

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

(deftest create-agent-ignores-filters-test
  (testing "agent 层不暴露 kernel filter：create-agent 传 :filters 被忽略，只挂 memory-filter"
    (let [filter-ran (atom false)
          user-filter {:name :user-spy
                       :chat (fn [req chain] (reset! filter-ran true) (chain req))}
          p (ts/create-mock-provider [{:text "OK" :tool-calls nil}])
          a (agent/create-agent {:provider p :model "test"
                                 :filters [user-filter]})]
      ;; kernel 上只有 memory-filter，没有用户 filter
      (is (= [:memory] (mapv :name (:filters (:kernel a)))))
      (let [r (agent/chat a "你好")]
        (is (= :completed (:status r)))
        (is (false? @filter-ran) "用户 filter 不应被执行"))))
  (testing "需要 filter 时走自建 kernel（:kernel 路径仍完整支持 filter）"
    (let [filter-ran (atom false)
          user-filter {:name :user-spy
                       :chat (fn [req chain] (reset! filter-ran true) (chain req))}
          p (ts/create-mock-provider [{:text "OK" :tool-calls nil}])
          svc (service/create-service p {:model "test" :max-tokens 100})
          store (memory/in-memory-store)
          k (kernel/build-kernel {:service svc
                                  :filters [(ma/memory-filter store) user-filter]})
          a (agent/create-agent {:kernel k})]
      (agent/chat a "你好")
      (is (true? @filter-ran) "自建 kernel 的 filter 正常生效"))))

;;; ============================================================
;;; pause / resume（通过 callbacks :on-tool-call 启用 gate）
;;; ============================================================

(defn- dangerous-gate
  "拦截 dangerous-tool，用于 pause/resume 测试（v0.2：tool 名为字符串）。"
  []
  {:on-tool-call (fn [n _] (when (= "dangerous-tool" n) {:interrupt "需要审批"}))})

(deftest no-pause-without-on-tool-call-test
  (testing "不配 callbacks :on-tool-call：所有工具直接执行，不暂停"
    (let [p (ts/create-mock-provider
              [{:text nil :tool-calls [{:id "c1" :name "dangerous-tool" :args {:target "x"}}]}
               {:text "已执行" :tool-calls nil}])
          a (agent/create-agent {:provider p :model "test" :tools ts/test-plugin})]
      (let [r (agent/chat a "删除")]
        (is (= :completed (:status r)))
        (is (= :dangerous-tool (:name (first (:tool-calls-made r)))))))))

(deftest sensitive-pause-test
  (testing "on-tool-call 返回 {:interrupt ...}：危险工具暂停"
    (let [p (ts/create-mock-provider
              [{:text nil :tool-calls [{:id "c1" :name "dangerous-tool" :args {:target "/tmp/x"}}]}])
          a (agent/create-agent {:provider p :model "test" :tools ts/test-plugin
                                 :callbacks (dangerous-gate)})
          r (agent/chat a "删除文件")]
      (is (= :paused (:status r)))
      (is (string? (:pause-reason r)))
      (is (= "dangerous-tool" (:name (:pending-tool r))))
      (is (agent/paused? a)))))

(deftest resume-approved-test
  (let [p (ts/create-mock-provider
            [{:text nil :tool-calls [{:id "c1" :name "dangerous-tool" :args {:target "目标"}}]}
             {:text "操作已完成" :tool-calls nil}])
        a (agent/create-agent {:provider p :model "test" :tools ts/test-plugin
                               :callbacks (dangerous-gate)})]
    (agent/chat a "执行危险操作")
    (is (agent/paused? a))
    (let [r (agent/resume a "approved")]
      (is (= :completed (:status r)))
      (is (= "操作已完成" (:text r)))
      (is (not (agent/paused? a))))))

(deftest resume-rejected-test
  (let [p (ts/create-mock-provider
            [{:text nil :tool-calls [{:id "c1" :name "dangerous-tool" :args {:target "目标"}}]}
             {:text "好的，已取消" :tool-calls nil}])
        a (agent/create-agent {:provider p :model "test" :tools ts/test-plugin
                               :callbacks (dangerous-gate)})]
    (agent/chat a "执行危险操作")
    (let [r (agent/resume a "rejected")]
      (is (= :completed (:status r)))
      (is (= "好的，已取消" (:text r)))
      ;; 拒绝必须真正阻止敏感工具执行：历史里是「已拒绝执行」而非「已执行危险操作」
      (let [tool-msgs (filter msg/tool? (agent/get-history a))]
        (is (some #(re-find #"已拒绝执行" (:content %)) tool-msgs))
        (is (not-any? #(re-find #"已执行危险操作" (:content %)) tool-msgs))))))

(deftest mixed-tools-pause-test
  (testing "safe + sensitive 混合：在 dangerous-tool 处暂停"
    (let [p (ts/create-mock-provider
              [{:text nil :tool-calls [{:id "c1" :name "safe-tool" :args {:input "数据"}}
                                       {:id "c2" :name "dangerous-tool" :args {:target "目标"}}]}
               {:text "全部完成" :tool-calls nil}])
          a (agent/create-agent {:provider p :model "test" :tools ts/test-plugin
                                 :callbacks (dangerous-gate)})
          r (agent/chat a "混合操作")]
      (is (= :paused (:status r)))
      (is (= "dangerous-tool" (:name (:pending-tool r)))))))

(deftest on-interrupt-callback-test
  (let [log (atom nil)
        p (ts/create-mock-provider
            [{:text nil :tool-calls [{:id "c1" :name "dangerous-tool" :args {:target "重要"}}]}])
        a (agent/create-agent {:provider p :model "test" :tools ts/test-plugin
                               :callbacks (assoc (dangerous-gate)
                                                 :on-interrupt
                                                 (fn [info _m] (reset! log info)))})]
    (agent/chat a "删除重要文件")
    (is (some? @log))
    (is (string? (:reason @log)))
    (is (= "dangerous-tool" (:name (:pending-tool @log))))))

(deftest resume-not-paused-throws-test
  (let [p (ts/create-mock-provider [{:text "正常" :tool-calls nil}])
        a (agent/create-agent {:provider p :model "test" :tools ts/test-plugin
                               :callbacks (dangerous-gate)})]
    (agent/chat a "你好")
    (is (thrown? clojure.lang.ExceptionInfo (agent/resume a "approved")))))

;;; ============================================================
;;; 未-resume 保护
;;; ============================================================

(deftest cancel-pending-protection-test
  (testing "暂停后不 resume 直接开新对话：补「已取消」结果，无悬空 tool_use"
    (let [p (ts/create-mock-provider
              [{:text nil :tool-calls [{:id "c1" :name "dangerous-tool" :args {:target "x"}}]}
               {:text "新回答" :tool-calls nil}])
          a (agent/create-agent {:provider p :model "test" :tools ts/test-plugin
                                 :callbacks (dangerous-gate)})]
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
               [{:text nil :tool-calls [{:id "c1" :name "dangerous-tool" :args {:target "/tmp/x"}}]}])
          a1 (agent/create-agent {:provider p1 :model "test" :tools ts/test-plugin
                                  :memory store :conversation-id "shared-heal"
                                  :callbacks (dangerous-gate)})]
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

(deftest sqlite-multimodal-roundtrip-test
  (testing "多模态部件经 SQLite（EDN 序列化）历史往返一字不变"
    ;; 部件里的二进制一律以 base64 **字符串**落地，正是为了过得去这一关——
    ;; 字节数组 pr-str/read-string 往返会变形。
    (let [s (sqlite/sqlite-store ":memory:")
          m (msg/user [(content/text-part "这张图里有几只猫？")
                       (content/image-part "https://example.com/cats.png")
                       (content/image-part "QUJD" {:media-type "image/png"})])]
      (memory/mem-add s "mm" [m])
      (let [[out] (memory/mem-get s "mm")]
        (is (= m out))
        (is (content/parts? (msg/content out)))
        (is (= "QUJD" (:data (nth (msg/content out) 2)))))
      (sqlite/close-store! s))))

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

(deftest sqlite-in-memory-persists-within-store-test
  (testing ":memory: 库在同一 store 生命周期内不丢数据（常开连接修复）"
    (let [s (sqlite/sqlite-store ":memory:")]
      (memory/mem-add s "c1" [(msg/user "a")])
      (memory/mem-add s "c1" [(msg/assistant "b")])
      ;; 多次操作共享同一内存库，建表与数据都在
      (is (= [(msg/user "a") (msg/assistant "b")] (memory/mem-get s "c1")))
      (sqlite/close-store! s))))

(deftest sqlite-closeable-test
  (testing "store 实现 Closeable，with-open 自动关闭"
    (let [path (temp-db)]
      (with-open [s (sqlite/sqlite-store path)]
        (memory/mem-add s "c1" [(msg/user "x")])
        (is (= 1 (count (memory/mem-get s "c1"))))))))

(deftest sqlite-concurrent-writes-test
  (testing "共享 store 并发写（locking conn 串行化）：无异常、消息不丢不重"
    (let [path (temp-db)
          s (sqlite/sqlite-store path)
          n-threads 8
          per-thread 25
          workers (mapv (fn [t]
                          (future
                            (dotimes [i per-thread]
                              (memory/mem-add s (str "conv-" (mod t 2))
                                              [(msg/user (str "t" t "-m" i))]))))
                        (range n-threads))]
      (doseq [w workers] @w)   ;; 全部完成（异常会在 deref 时抛出）
      (let [c0 (memory/mem-get s "conv-0")
            c1 (memory/mem-get s "conv-1")]
        (is (= (* n-threads per-thread) (+ (count c0) (count c1)))
            "总消息数 = 线程数 × 每线程写入数")
        (is (= (count c0) (count (distinct (map :content c0)))) "conv-0 无重复")
        (is (= (count c1) (count (distinct (map :content c1)))) "conv-1 无重复")))))

(deftest in-memory-concurrent-writes-test
  (testing "InMemoryStore 并发写（swap! 原子）：不丢不重"
    (let [s (memory/in-memory-store)
          workers (mapv (fn [t]
                          (future
                            (dotimes [i 50]
                              (memory/mem-add s "c" [(msg/user (str t "-" i))]))))
                        (range 8))]
      (doseq [w workers] @w)
      (is (= 400 (count (memory/mem-get s "c")))))))
