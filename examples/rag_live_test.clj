(ns rag-live-test
  "RAG 注入（`filter/rag`）× MiniMax 真实 provider 端到端验证。

   对标 Spring AI `QuestionAnswerAdvisor`；设计见
   `docs/advisor-alignment-design.md` §7.1。

   ## 怎么证明「答案真的来自检索」

   拿真实世界的知识提问，无法区分「检索生效」与「模型本来就知道」。故语料
   全部是**虚构事实**（Nimbus-7 咖啡机、内部报销规定）——模型训练数据里不可能
   有「每 42 天除垢」这个数字。于是：

   - 不挂 RAG → 模型答不出 42（证明这不是先验知识）；
   - 挂上 RAG → 模型答出 42（证明 grounding 真的成立）。

   对照组是这个脚本的核心，不是装饰。

   ## 断言的是机制，不是模型的措辞

   「检索为空时不注入」这类分支，断言的是**发给 provider 的用户消息有没有被
   改写**（probe 直接观察），而不是「模型有没有说我不知道」——后者是模型行为，
   会波动，拿它当断言等于给 CI 埋雷。模型的实际回答只打印、不断言。

   验证点：
   1. 检索器收到的 query = 用户原问题，且**每 turn 只检索一次**（:turn 挂点的
      全部理由：挂 :chat 会在工具循环内每轮重复检索）;
   2. grounding 成立：对照组答不出、RAG 组答得出;
   3. 用户消息被改写为「原问题 + 检索上下文 + grounding 指令」，且原问题在最前;
   4. 检索为空 → **不注入**（消息原样），刻意偏离 Spring 的空上下文 + 拒答指令;
   5. `:inject-when-empty? true` → 恢复 Spring 的严格 grounding（注入空上下文）;
   6. `:top-k` 传到检索器并截断结果。

   运行（需 MINIMAX_API_KEY，兼容旧的 MINIMAX_AUTH_TOKEN）：
     clojure -M -e \"(load-file \\\"examples/rag_live_test.clj\\\")\""
  (:require [clojure.string :as str]
            [im.ttalk.agent.chat-client :as chat-client]
            [im.ttalk.agent.filter :as flt]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.chat-model :as chat-model]
            [im.ttalk.agent.model.response :as resp]
            [im.ttalk.agent.filter.memory :as ma]
            [im.ttalk.agent.filter.rag :as rag]
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
;;; 虚构语料 —— 模型训练数据里不可能有这些数字
;;; ============================================================

(def corpus
  [{:text "Nimbus-7 型咖啡机的除垢周期为每 42 天一次，必须使用 pH 3.2 的柠檬酸溶液，不可用白醋。"
    :metadata {:source "nimbus7-manual#3.1"}}
   {:text "Nimbus-7 型咖啡机的保修期为 26 个月，序列号以 NB7- 开头，保修不覆盖除垢不当造成的损坏。"
    :metadata {:source "nimbus7-manual#8.4"}}
   {:text "Zephyr-3 型磨豆机的刀盘更换周期为每研磨 380 公斤咖啡豆一次。"
    :metadata {:source "zephyr3-manual#2.2"}}
   {:text "公司内部报销规定：单笔餐饮费上限 178 元，须在 9 个工作日内提交，超期需部门总监签字。"
    :metadata {:source "finance-policy#5"}}
   {:text "公司内部差旅规定：高铁二等座可直接报销，商务座需提前申请。"
    :metadata {:source "finance-policy#6"}}])

;;; ============================================================
;;; 一个最小的 IRetriever 实现（示范用户怎么接自己的检索）
;;; ============================================================

(defn- tokens [s]
  ;; 中文按二元组切；latin 按词切（与 tool-search 的索引同款思路）
  (let [s (str/lower-case (or s ""))
        latin (re-seq #"[a-z0-9]+" s)
        cjk (mapcat (fn [run]
                      (if (< (count run) 2)
                        [run]
                        (map #(subs run % (+ % 2)) (range (dec (count run))))))
                    (re-seq #"[一-鿿]+" s))]
    (set (concat latin cjk))))

(defn keyword-retriever
  "按 token 重叠打分的内存检索器。真实项目里这里换成你的向量库。"
  [docs & [log]]
  (reify rag/IRetriever
    (retrieve [_ query top-k]
      (when log (swap! log conj [query top-k]))
      (let [q (tokens query)]
        (->> docs
             (map (fn [d] [(count (clojure.set/intersection q (tokens (:text d)))) d]))
             (filter (fn [[s _]] (pos? s)))
             (sort-by (fn [[s _]] (- s)))
             (take top-k)
             (mapv second))))))

;;; ============================================================
;;; probe：观察真正发给 provider 的用户消息
;;; ============================================================

(defn probe [log]
  {:name :probe
   :chat (fn [req chain]
           (swap! log conj {:messages (flt/req-messages req)})
           (chain req))})

(defn- last-user-content [{:keys [messages]}]
  (->> messages (filter #(= :user (:role %))) last :content str))

(defn run-case
  "跑一次对话。rag-filter 为 nil 则是对照组（不挂 RAG）。"
  [cm rag-filter question]
  (let [log (atom [])
        store (memory/in-memory-store)
        k (chat-client/build-chat-client
            {:chat-model cm
             :filters (cond-> [(ma/memory-filter store)]
                        rag-filter (conj rag-filter)
                        :always (conj (probe log)))})
        result (react/invoke k store [{:role :user :content question}]
                             {:context (ctx/create)})]
    {:log @log
     :result result
     :answer (resp/response-text (:response result))
     :sent (last-user-content (first @log))}))

;;; ============================================================
;;; 场景 1：grounding —— 对照组答不出，RAG 组答得出
;;; ============================================================

(def q-nimbus "Nimbus-7 型咖啡机多久需要除垢一次？要用什么溶液？")

(defn test-grounding [cm]
  (println "\n=== 场景 1: grounding（虚构事实，对照组不可能知道） ===")
  (let [rlog (atom [])
        base (run-case cm nil q-nimbus)
        r (run-case cm (rag/qa-turn-filter (keyword-retriever corpus rlog) :top-k 2)
                    q-nimbus)]

    (println "   对照组答案:" (clip (:answer base) 90))
    (println "   RAG  组答案:" (clip (:answer r) 90))
    (println "   检索器调用:" (pr-str @rlog))

    (check "对照组（无 RAG）答不出 42 —— 证明这不是模型的先验知识"
           (not (str/includes? (str (:answer base)) "42")))
    (check "RAG 组答出 42 天 —— grounding 成立"
           (str/includes? (str (:answer r)) "42"))
    (check "RAG 组答出柠檬酸/pH 3.2 —— 第二条事实也来自检索"
           (or (str/includes? (str (:answer r)) "柠檬酸")
               (str/includes? (str (:answer r)) "3.2")))

    (check "检索器收到的 query = 用户原问题" (= q-nimbus (ffirst @rlog)))
    (check ":top-k 传到检索器" (= 2 (second (first @rlog))))
    (check "每 turn 只检索一次（:turn 挂点的全部理由）" (= 1 (count @rlog)))

    (check "用户消息被改写：原问题在最前"
           (str/starts-with? (:sent r) q-nimbus))
    (check "用户消息被改写：检索到的原文被拼进去"
           (str/includes? (:sent r) "42 天"))
    (check "用户消息被改写：带 grounding 指令"
           (str/includes? (:sent r) "仅"))
    (check "对照组的用户消息未被改写（原样）" (= q-nimbus (:sent base)))))

;;; ============================================================
;;; 场景 2：检索为空 —— 不注入（刻意偏离 Spring）
;;; ============================================================

(def q-unrelated "帮我写一句关于春天的短诗。")

(defn test-empty-retrieval [cm]
  (println "\n=== 场景 2: 检索为空 → 不注入（默认） ===")
  (let [rlog (atom [])
        r (run-case cm (rag/qa-turn-filter (keyword-retriever corpus rlog) :top-k 2)
                    q-unrelated)]
    (println "   检索器调用:" (pr-str @rlog))
    (println "   答案:" (clip (:answer r) 90))
    ;; 断言机制（消息有没有被改写），不断言模型措辞
    (check "检索器被调用了（filter 没偷懒）" (= 1 (count @rlog)))
    (check "检索为空 → 用户消息原样，不注入空上下文"
           (= q-unrelated (:sent r)))
    (check "模型照常作答（没被空上下文逼到拒答）"
           (= :completed (:status (:result r))))))

(defn test-inject-when-empty [cm]
  (println "\n=== 场景 3: :inject-when-empty? true → 恢复 Spring 的严格 grounding ===")
  (let [r (run-case cm (rag/qa-turn-filter (keyword-retriever corpus) :top-k 2
                                           :inject-when-empty? true)
                    q-unrelated)]
    (println "   发给 provider 的用户消息:" (clip (:sent r) 110))
    (println "   答案:" (clip (:answer r) 90))
    (check "检索为空仍注入（消息被改写）" (not= q-unrelated (:sent r)))
    (check "注入的是带 grounding 指令的空上下文"
           (str/includes? (:sent r) "无法回答"))))

;;; ============================================================
;;; 场景 4：top-k 截断
;;; ============================================================

(defn test-top-k [cm]
  (println "\n=== 场景 4: :top-k 截断（只有 top-1 进 prompt） ===")
  ;; 「公司内部规定」两条都命中；top-k 1 时只应进来最相关的那条
  (let [r (run-case cm (rag/qa-turn-filter (keyword-retriever corpus) :top-k 1)
                    "公司内部报销规定：单笔餐饮费上限是多少？")]
    (println "   发给 provider 的用户消息:" (clip (:sent r) 110))
    (check "命中的那条进了 prompt" (str/includes? (:sent r) "178"))
    (check "top-k 之外的文档没进 prompt" (not (str/includes? (:sent r) "商务座")))
    (check "答案含 178（grounding）" (str/includes? (str (:answer r)) "178"))))

;;; ============================================================
;;; 汇总
;;; ============================================================

(defn run []
  (println "RAG live 验证 | model =" MODEL "| provider = :minimax")
  (let [cm (chat-model/create-chat-model p {:model MODEL :max-tokens 2048})]
    (doseq [f [test-grounding test-empty-retrieval test-inject-when-empty test-top-k]]
      (try
        (f cm)
        (catch Throwable t
          (swap! failures inc)
          (println "  ✗ 场景异常:" (.getMessage t)))))
    (println)
    (if (zero? @failures)
      (println "全部通过 ✓")
      (println @failures "项失败 ✗"))
    (System/exit (if (zero? @failures) 0 1))))

(run)
