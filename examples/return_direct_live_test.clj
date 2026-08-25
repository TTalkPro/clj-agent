(ns return-direct-live-test
  "return-direct + 可插拔续跑判据 × MiniMax 真实 provider 端到端验证。

   对标 Spring AI 2.0 `ToolCallingAdvisor` 的 return direct 与
   `ToolExecutionEligibilityChecker`；设计见
   `docs/advisor-alignment-design.md` §1.1–1.2。

   ## live 验的是什么

   1. **工具结果一字不改地成为最终答案**——return-direct 的全部意义。转人工
      话术、合规文案、退款金额这类东西**不能**被模型润色。对照组（普通工具）
      同一句话会被模型改写成一大段——两边一比，价值才具体;
   2. **LLM 只被调用 1 次**——结果不回灌（回灌就等于让模型有机会改写）;
   3. **transcript 补落库的修复真的管用**（本次对齐里最容易漏的一刀）：
      正常路径下工具结果是靠**下一次 invoke-chat** 经 memory filter 落库的，
      return-direct 没有下一次。不补这一刀，历史里就只剩
      `assistant(tool_calls)` 而无结果 → 下个 turn 的 heal-dangling 会把它整条
      摘掉 → 「用户问了、也答了」在历史里双双蒸发。
      场景 3 用**真实的第二轮对话**验证模型确实还看得见上一轮的转人工记录;
   4. **续跑判据**（eligibility-fn）返回 false → 模型明明要调工具，工具一个
      都不执行。

   运行（需 MINIMAX_API_KEY，兼容旧的 MINIMAX_AUTH_TOKEN）：
     clojure -M -e \"(load-file \\\"examples/return_direct_live_test.clj\\\")\""
  (:require [clojure.string :as str]
            [im.ttalk.agent.chat-client :as chat-client]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.tool :refer [deftool]]
            [im.ttalk.agent.chat-model :as chat-model]
            [im.ttalk.agent.model.response :as resp]
            [im.ttalk.agent.filter.memory :as ma]
            [im.ttalk.agent.react :as react]
            [im.ttalk.agent.provider.minimax :as minimax]))

;;; ============================================================
;;; 环境与公共设施
;;; ============================================================

;; provider 默认读 MINIMAX_API_KEY；MINIMAX_AUTH_TOKEN 为旧变量名的兼容回退
(def auth-token (or (System/getenv "MINIMAX_API_KEY")
                    (System/getenv "MINIMAX_AUTH_TOKEN")))

(when-not auth-token
  (println "需要 MINIMAX_API_KEY（或旧变量 MINIMAX_AUTH_TOKEN）")
  (System/exit 1))

(def p (minimax/create-provider {:api-key auth-token}))
(def MODEL minimax/default-model)

(def failures (atom 0))

(defn check [desc ok?]
  (if ok?
    (println "  ✓" desc)
    (do (swap! failures inc)
        (println "  ✗ FAIL:" desc))))

(defn- clip [s n]
  (let [s (str/replace (str s) #"\s+" " ")]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

;;; ============================================================
;;; 工具：一个 return-direct（转人工），一个普通（对照组）
;;; ============================================================

;; 转人工话术是合规文案：一个字都不能被模型润色 → return-direct
(deftool handoff
  "把当前会话转接给人工客服"
  [[reason :string "转接原因"]]
  {:return-direct true}
  (str "【工单已创建】已转接人工客服，原因：" reason "。工单号 T-88123，请保持在线，客服将在 3 分钟内接入。"))

;; 对照组：同样的信息，但走普通工具 → 会被模型回灌改写
(deftool handoff-plain
  "把当前会话转接给人工客服"
  [[reason :string "转接原因"]]
  (str "【工单已创建】已转接人工客服，原因：" reason "。工单号 T-88123，请保持在线，客服将在 3 分钟内接入。"))

(deftool get-weather
  "获取指定城市的实时天气信息"
  [[city :string "城市名称"]]
  (str city "：晴，26°C"))

;;; ============================================================
;;; probe：数 LLM 调用次数
;;; ============================================================

(defn probe [calls]
  {:name :probe
   :chat (fn [req chain] (swap! calls inc) (chain req))})

(defn build [store calls tools & {:keys [eligibility-fn]}]
  (chat-client/build-chat-client
    (cond-> {:chat-model (chat-model/create-chat-model p {:model MODEL :max-tokens 1024})
             :tools (vec tools)
             :filters [(ma/memory-filter store) (probe calls)]}
      eligibility-fn (assoc :eligibility-fn eligibility-fn))))

(defn ask [k store cid q]
  (react/invoke k store [{:role :user :content q}]
                {:context (ctx/with-conversation-id (ctx/create) cid)}))

(def q-handoff "我要投诉退款纠纷，请立刻调用转人工工具（reason 填「退款纠纷」）。")

;;; ============================================================
;;; 场景 1：return-direct —— 工具结果一字不改即最终答案
;;; ============================================================

(defn test-return-direct []
  (println "\n=== 场景 1: return-direct → 工具原文即最终答案，LLM 只调 1 次 ===")
  (let [calls (atom 0)
        store (memory/in-memory-store)
        k (build store calls [#'handoff])
        r (ask k store "conv-direct" q-handoff)
        answer (resp/response-text (:response r))
        expected (handoff {:reason "退款纠纷"})]
    (println "   LLM 调用次数:" @calls)
    (println "   最终答案:" (clip answer 90))
    (check "turn 完成" (= :completed (:status r)))
    (check "带 :return-direct 标记" (true? (:return-direct r)))
    (check "**LLM 只被调用 1 次**——结果不回灌（回灌就等于给模型改写的机会）"
           (= 1 @calls))
    (check "答案与工具输出**逐字相同**——合规文案没被模型润色一个字"
           (= expected answer))
    (check "工具照常记入 :tool-calls-made"
           (= [:handoff] (mapv :name (:tool-calls-made r))))
    (check ":direct-messages 是内部键，不外泄给调用方"
           (not (contains? r :direct-messages)))))

;;; ============================================================
;;; 场景 2：对照组 —— 普通工具会被模型回灌改写
;;; ============================================================

(defn test-plain-contrast []
  (println "\n=== 场景 2: 对照组——同样的话术走普通工具，会被模型改写 ===")
  (let [calls (atom 0)
        store (memory/in-memory-store)
        k (build store calls [#'handoff-plain])
        r (ask k store "conv-plain" q-handoff)
        answer (resp/response-text (:response r))
        expected (handoff-plain {:reason "退款纠纷"})]
    (println "   LLM 调用次数:" @calls)
    (println "   最终答案:" (clip answer 90))
    (check "普通工具：结果回灌 → LLM 被调用 2 次" (= 2 @calls))
    (check "普通工具：最终答案**不是**工具原文（模型重新组织过）"
           (not= expected answer))
    (println "   ⓘ 两边一比即 return-direct 的价值：合规文案要么逐字送达，要么任由模型润色")))

;;; ============================================================
;;; 场景 3：transcript 补落库（本次对齐最容易漏的一刀）
;;; ============================================================

(defn test-transcript-persisted []
  (println "\n=== 场景 3: 补落库 → 下一轮模型仍看得见上一轮的转人工记录 ===")
  (let [calls (atom 0)
        store (memory/in-memory-store)
        k (build store calls [#'handoff])
        cid "conv-multi"
        _ (ask k store cid q-handoff)
        h1 (memory/mem-get store cid)]
    (println "   第 1 轮后 history:" (pr-str (mapv :role h1)))
    (check "transcript 完整：user → assistant(tool_calls) → tool(result)"
           (= [:user :assistant :tool] (mapv :role h1)))
    (check "工具结果确实落库了（不补这一刀这里就是空的）"
           (str/includes? (str (:content (last h1))) "T-88123"))
    (check "无悬空 tool_use（否则下个 turn 的 heal 会把整条摘掉）"
           (empty? (#'react/dangling-tool-call-ids h1)))

    ;; 真实第二轮：模型能不能看见上一轮
    (let [r2 (ask k store cid "我刚才的请求被怎么处理了？工单号是多少？请依据对话历史如实回答。")
          a2 (resp/response-text (:response r2))]
      (println "   第 2 轮回答:" (clip a2 90))
      (println "   第 2 轮后 history:" (pr-str (mapv :role (memory/mem-get store cid))))
      (check "第 2 轮完成" (= :completed (:status r2)))
      (check "heal 没摘掉任何东西——历史继续增长"
             (= [:user :assistant :tool :user :assistant]
                (mapv :role (memory/mem-get store cid))))
      (check "模型答得出工单号 —— 证明上一轮的工具结果真的在历史里"
             (str/includes? a2 "T-88123")))))

;;; ============================================================
;;; 场景 4：可插拔续跑判据（ToolExecutionEligibilityChecker）
;;; ============================================================

(defn test-eligibility []
  (println "\n=== 场景 4: :eligibility-fn 返回 false → 模型要调工具也不执行 ===")
  (let [calls (atom 0)
        store (memory/in-memory-store)
        k (build store calls [#'get-weather]
                 :eligibility-fn (fn [_resp ctx] (pos? (ctx/get-var ctx :budget 0))))
        ;; 预算为 0 → 判据说停
        r (react/invoke k store
                        [{:role :user :content "北京天气怎么样？请调用 get-weather 工具查询。"}]
                        {:context (ctx/create {:budget 0})})]
    (println "   LLM 调用次数:" @calls)
    (println "   回答:" (clip (resp/response-text (:response r)) 70))
    (check "turn 按最终答案收尾（:completed）" (= :completed (:status r)))
    (check "工具一个都没执行（判据说停）" (empty? (:tool-calls-made r)))
    (check "只调 1 次 LLM——不续跑" (= 1 @calls)))

  (println "   （对照）预算充足 → 判据放行，工具照常执行")
  (let [calls (atom 0)
        store (memory/in-memory-store)
        k (build store calls [#'get-weather]
                 :eligibility-fn (fn [_resp ctx] (pos? (ctx/get-var ctx :budget 0))))
        r (react/invoke k store
                        [{:role :user :content "北京天气怎么样？请调用 get-weather 工具查询。"}]
                        {:context (ctx/create {:budget 5})})]
    (println "   LLM 调用次数:" @calls)
    (check "判据放行 → 工具执行" (seq (:tool-calls-made r)))
    (check "结果回灌 → LLM 调用 >1 次" (> @calls 1))))

;;; ============================================================
;;; 汇总
;;; ============================================================

(defn run []
  (println "return-direct + eligibility live 验证 | model =" MODEL "| provider = :minimax")
  (doseq [f [test-return-direct test-plain-contrast
             test-transcript-persisted test-eligibility]]
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
