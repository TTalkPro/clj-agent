(ns minimax-agent-live-test
  "SimpleAgent × MiniMax 真实 provider 端到端验证。

   验证点（对应三项要求）：
   1. callback 体系正确：9 个钩子在真实 LLM + 工具调用下按预期触发
      （on-turn-start/end/error, on-llm-call/result, on-tool-call/result,
       on-interrupt/resume）
   2. create-agent 支持自定义 :memory（自带 store / windowed / false 无记忆）
      与自定义 :kernel（预构建 kernel + 自定义 filter）
   3. agent 层不暴露 kernel filter：create-agent 传 :filters 被忽略（warn），
      自定义 filter 必须走自建 kernel

   运行（需 MINIMAX_AUTH_TOKEN）：
     clojure -M -e \"(load-file \\\"examples/minimax_agent_live_test.clj\\\")\""
  (:require [im.ttalk.agent.client :as agent]
            [im.ttalk.agent.tool :refer [deftool]]
            [im.ttalk.agent.kernel :as kernel]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.model.service :as service]
            [im.ttalk.agent.advisor.memory :as ma]
            [im.ttalk.agent.provider.minimax :as minimax]))

;;; ============================================================
;;; 环境与公共设施
;;; ============================================================

(def auth-token (System/getenv "MINIMAX_AUTH_TOKEN"))

(when-not auth-token
  (println "需要 MINIMAX_AUTH_TOKEN")
  (System/exit 1))

;; provider 默认读 MINIMAX_API_KEY；此处显式传 :api-key 接入 MINIMAX_AUTH_TOKEN
(def p (minimax/create-provider {:api-key auth-token}))
(def MODEL "MiniMax-M2.7")

(def failures (atom 0))

(defn check [desc ok?]
  (if ok?
    (println "  ✓" desc)
    (do (swap! failures inc)
        (println "  ✗ FAIL:" desc))))

(deftool get-weather
  "查询指定城市当前天气"
  [[city :string "城市名"]]
  (str city ": 晴, 25°C"))

(deftool send-email
  "发送邮件（敏感操作）"
  [[to :string "收件人"] [subject :string "主题"]]
  (str "已发送给 " to ": " subject))

;;; ============================================================
;;; 场景 1：callback 全链路（真实工具调用）
;;; ============================================================

(defn test-callbacks-full-chain []
  (println "\n=== 场景 1: callback 全链路（turn/llm/tool 观察钩子） ===")
  (let [log (atom [])
        rec (fn [kw] (fn [& args] (swap! log conj (into [kw] args))))
        a (agent/create-agent
            {:provider p :model MODEL :max-tokens 4096
             :tools [#'get-weather]
             :callbacks {:on-turn-start  (rec :turn-start)
                         :on-turn-end    (rec :turn-end)
                         :on-llm-call    (rec :llm-call)
                         :on-llm-result  (rec :llm-result)
                         :on-tool-call   (fn [n args]
                                           (swap! log conj [:tool-call n args])
                                           nil)   ;; 不中断
                         :on-tool-result (rec :tool-result)}})
        r (agent/chat a "北京今天天气怎么样？请用 get-weather 工具查询。")]
    (let [cnt (fn [kw] (count (filter #(= kw (first %)) @log)))]
      (check "chat 完成 :completed" (= :completed (:status r)))
      (check "回复文本非空" (some? (:text r)))
      (check "on-turn-start 触发 1 次" (= 1 (cnt :turn-start)))
      (check "on-turn-end 触发 1 次" (= 1 (cnt :turn-end)))
      (check "on-llm-call ≥2 次（工具轮+文本轮）" (>= (cnt :llm-call) 2))
      (check "on-llm-call/on-llm-result 次数一致" (= (cnt :llm-call) (cnt :llm-result)))
      (check "on-tool-call 触发 1 次（gate 缓存，无双触发）" (= 1 (cnt :tool-call)))
      (check "on-tool-result 触发 1 次" (= 1 (cnt :tool-result)))
      (let [[_ n args] (first (filter #(= :tool-call (first %)) @log))]
        (check "on-tool-call 收到 keyword 工具名" (= :get-weather n))
        (check "on-tool-call 收到 args(city)" (some? (:city args))))
      (let [[_ n res] (first (filter #(= :tool-result (first %)) @log))]
        (check "on-tool-result 名为 string" (= "get-weather" n))
        (check "on-tool-result 含工具输出" (.contains (str res) "25°C")))
      (check "turn-start 时 metadata 含 run-id"
             (some? (:run-id (second (first (filter #(= :turn-start (first %)) @log))))))
      (check "turn-end 后 turn-count=1"
             (= 1 (:turn-count (second (first (filter #(= :turn-end (first %)) @log)))))))))

;;; ============================================================
;;; 场景 2：on-tool-call 中断 → on-interrupt → resume → on-resume
;;; ============================================================

(defn test-interrupt-resume []
  (println "\n=== 场景 2: 中断/恢复 callback（on-interrupt / on-resume） ===")
  (let [log (atom [])
        approved (atom false)
        a (agent/create-agent
            {:provider p :model MODEL :max-tokens 4096
             :tools [#'send-email]
             :callbacks {:on-tool-call (fn [n _args]
                                         (when (and (= :send-email n) (not @approved))
                                           {:interrupt "发邮件需要人工审批"}))
                         :on-interrupt (fn [info m]
                                         (swap! log conj [:interrupt (:reason info) (:run-id m)]))
                         :on-resume    (fn [info _m]
                                         (swap! log conj [:resume (:approved? info)]))}})
        r1 (agent/chat a "必须调用 send-email 工具给 alice@example.com 发一封主题为'周报'的邮件，不要直接回答。")]
    (when-not (= :paused (:status r1))
      (println "    [debug] r1 =" (pr-str (dissoc r1 :tool-calls-made))))
    (check "首轮被中断 :paused" (= :paused (:status r1)))
    (check "pending-tool 是 send-email"
           (= "send-email" (some-> (get-in r1 [:pending-tool :name]) name)))
    (check "on-interrupt 触发" (some #(= :interrupt (first %)) @log))
    (check "agent paused? = true" (agent/paused? a))
    (if (agent/paused? a)
      (do
        (reset! approved true)
        (let [r2 (agent/resume a :approved)]
          (check "resume 后 :completed" (= :completed (:status r2)))
          (check "on-resume 触发且 approved? true"
                 (some #(and (= :resume (first %)) (true? (second %))) @log))
          (check "工具实际执行（tool-calls-made 含 send-email）"
                 (some #(= :send-email (:name %)) (:tool-calls-made r2)))))
      (check "resume 链路（前置 paused 未达成）" false))))

;;; ============================================================
;;; 场景 3：on-turn-error（无效模型名触发真实 API 错误）
;;; ============================================================

(defn test-turn-error []
  ;; 注：MiniMax 对未知模型名会静默回退到默认模型，不报错；
  ;; 故用无效 api-key 触发真实 401 来验证 on-turn-error 链路。
  (println "\n=== 场景 3: on-turn-error（无效 api-key → 真实 401） ===")
  (let [seen (atom nil)
        bad-p (minimax/create-provider {:api-key "sk-invalid-key-for-error-test"})
        a (agent/create-agent
            {:provider bad-p :model MODEL :max-tokens 256
             :callbacks {:on-turn-error (fn [err _m] (reset! seen err))}})
        r (agent/chat a "你好")]
    (check "status :error" (= :error (:status r)))
    (check "on-turn-error 收到错误对象" (some? @seen))
    (check "错误带 :type 分类" (keyword? (:type @seen)))
    (check "分类为 auth-error 且不可重试"
           (and (= :auth-error (:type @seen)) (false? (:retryable? @seen))))))

;;; ============================================================
;;; 场景 4：自定义 memory
;;; ============================================================

(defn test-custom-memory []
  (println "\n=== 场景 4: 自定义 memory（自带 store / false 无记忆） ===")
  ;; 4a. 自带 store：历史落在用户的 store 中，按 conversation-id 隔离
  (let [my-store (memory/in-memory-store)
        a (agent/create-agent {:provider p :model MODEL :max-tokens 2048
                               :memory my-store
                               :conversation-id "live-conv-1"})]
    (agent/chat a "我叫大卫，请记住。用一句话回应。")
    (check "自定义 store 被使用（identical）" (identical? my-store (:memory a)))
    (check "历史写入自定义 store" (= 2 (count (memory/mem-get my-store "live-conv-1"))))
    (let [r2 (agent/chat a "我叫什么名字？只回名字。")]
      (check "第二轮 :completed" (= :completed (:status r2)))
      (check "多轮记忆生效（回复含'大卫'）" (.contains (str (:text r2)) "大卫"))
      (check "store 累积 4 条消息" (= 4 (count (memory/mem-get my-store "live-conv-1"))))))
  ;; 4b. :memory false → 完全无记忆
  (let [a (agent/create-agent {:provider p :model MODEL :max-tokens 2048
                               :memory false})]
    (let [r (agent/chat a "你好，用一句话回应。")]
      (check ":memory false 仍可对话" (= :completed (:status r)))
      (check ":memory false → agent 无 store" (nil? (:memory a)))
      (check ":memory false → get-history 为空" (empty? (agent/get-history a))))))

;;; ============================================================
;;; 场景 5：自定义 kernel（预构建 + 自定义 filter）
;;; ============================================================

(defn test-custom-kernel []
  (println "\n=== 场景 5: 自定义 kernel（预构建 kernel + 自定义 filter） ===")
  (let [filter-hits (atom 0)
        audit-filter {:name :audit
                      :chat (fn [req chain] (swap! filter-hits inc) (chain req))}
        kernel-store (memory/in-memory-store)
        svc (service/create-service p {:model MODEL :max-tokens 2048})
        k (kernel/build-kernel {:service svc
                                :tools [#'get-weather]
                                :filters [(ma/memory-filter kernel-store) audit-filter]})
        a (agent/create-agent {:kernel k :conversation-id "live-conv-k"})]
    (let [r (agent/chat a "你好，用一句话回应。")]
      (check "预构建 kernel 对话 :completed" (= :completed (:status r)))
      (check "agent 复用 kernel memory-filter 的 store" (identical? kernel-store (:memory a)))
      (check "自定义 filter 在 kernel 层生效" (pos? @filter-hits))
      (check "历史落入 kernel store" (= 2 (count (memory/mem-get kernel-store "live-conv-k")))))
    ;; 5b. :kernel + :memory 同时指定 → 以 :memory 为准（memory-filter 重挂），其他 filter 保留
    (let [my-store (memory/in-memory-store)
          hits-before @filter-hits
          a2 (agent/create-agent {:kernel k :memory my-store
                                  :conversation-id "live-conv-k2"})]
      (check ":kernel+:memory 以 :memory 为准" (identical? my-store (:memory a2)))
      (check "重挂后 filter 顺序 memory 最前"
             (= [:memory :audit] (mapv :name (:filters (:kernel a2)))))
      (agent/chat a2 "我喜欢蓝色。用一句话回应。")
      (let [r2 (agent/chat a2 "我喜欢什么颜色？只回颜色。")]
        (check "自定义 store 多轮记忆生效（回复含'蓝'）" (.contains (str (:text r2)) "蓝"))
        (check "历史落入自定义 store" (= 4 (count (memory/mem-get my-store "live-conv-k2"))))
        (check "kernel 原 store 未被该会话写入" (empty? (memory/mem-get kernel-store "live-conv-k2")))
        (check "其他自定义 filter 重挂后仍生效" (> @filter-hits hits-before))))))

;;; ============================================================
;;; 场景 6：agent 不暴露 kernel filter
;;; ============================================================

(defn test-filters-not-exposed []
  (println "\n=== 场景 6: agent 层不暴露 :filters（忽略 + 只挂 memory-filter） ===")
  (let [filter-ran (atom false)
        sneaky {:name :sneaky
                :chat (fn [req chain] (reset! filter-ran true) (chain req))}
        a (agent/create-agent {:provider p :model MODEL :max-tokens 2048
                               :filters [sneaky]})]
    (check "kernel 上只有 memory-filter" (= [:memory] (mapv :name (:filters (:kernel a)))))
    (let [r (agent/chat a "你好，用一句话回应。")]
      (check "对话正常 :completed" (= :completed (:status r)))
      (check "传入的 :filters 未被执行" (false? @filter-ran)))))

;;; ============================================================
;;; 运行
;;; ============================================================

(defn run []
  (println "+----------------------------------------------------------+")
  (println "|  SimpleAgent × MiniMax live 验证 (" MODEL ")  |")
  (println "+----------------------------------------------------------+")
  (doseq [f [test-callbacks-full-chain
             test-interrupt-resume
             test-turn-error
             test-custom-memory
             test-custom-kernel
             test-filters-not-exposed]]
    (try
      (f)
      (catch Throwable t
        (swap! failures inc)
        (println "  ✗ 场景异常:" (.getMessage t)))))
  (println)
  (if (zero? @failures)
    (println "全部通过 ✓")
    (println @failures "项失败 ✗"))
  (System/exit (if (zero? @failures) 0 1)))

(run)
