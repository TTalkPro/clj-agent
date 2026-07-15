(ns structured-output-live-test
  "结构化输出自我修正（`validation-turn-filter` + `advisor/structured-output`）
   × MiniMax 真实 provider 端到端验证。

   对标 Spring AI 2.0 `StructuredOutputValidationAdvisor`；设计见
   `docs/advisor-alignment-design.md` §3。

   ## 要证明的不是「校验能跑」，是「模型真的据反馈改对了」

   校验器本身有单测（`structured_output_test.clj`，10 组）。live 唯一值得验的
   是那个单测证明不了的东西：**把「缺少必填字段 internal_review_code」这句话
   丢回给真实模型，它会不会真的把那个字段补上**。这正是 Spring 强调的
   「给诊断信息，而不是干巴巴重试」——机制对不对，只有真模型能回答。

   触发真实失败的办法（不作假）：schema 要求一个 prompt 里**没提过**的字段
   （`birth_year`）。模型第一轮几乎必然漏掉 → 校验真实失败 → 反馈点名该字段 →
   模型第二轮补上。整条链路每一步都是真的。

   > 选字段有讲究（实测踩过）：最初用的是 `internal_review_code`——失败确实真
   > 发生了，但模型**修不好**（它根本不知道这个内部编号该填什么），连续两次
   > 反馈后仍旧漏掉，3 次调用耗尽。缺失字段必须是**模型答得上来**的
   > （`birth_year` 它知道），否则测的就不是「据反馈自我修正」而是「模型会不会
   > 编造一个它无从得知的值」。

   ## 断言不依赖模型是否恰好合规

   「合格 → 不重试」这类断言若直接写 `(= 1 调用次数)`，模型偶尔首轮不合规就会
   让 CI 变红——那是模型行为，不是回归。故改为**条件断言**：先算首轮输出合不
   合格，再据此断言对应的不变量（合格→必须只调 1 次；不合格→必须重入）。
   两条路都在测机制，且永不 flake。

   ## 零依赖的接缝

   core 无任何依赖，不内置 JSON 解析器——`validate-fn` 的 `:parse-fn` 由调用方
   注入。本脚本注入 cheshire（根 deps.edn 已有）。

   验证点：
   1. 合格输出 → 一次通过，**不重试**（不合格才重入，别把正常路径也拖慢）;
   2. **自我修正**：真实模型据反馈补上缺失字段 → 最终校验通过;
   3. 反馈消息里**点名了具体问题**（而非「请重试」），且进了对话历史;
   4. 真实模型输出常包 ```json 围栏 —— strip-fences 在真机输出上生效;
   5. **重试耗尽 → 原样返回**（不可满足的 schema + max-retries 1 → 恰好 2 次
      LLM 调用后收手，不无限重试）。

   运行（需 MINIMAX_API_KEY，兼容旧的 MINIMAX_AUTH_TOKEN）：
     clojure -M -e \"(load-file \\\"examples/structured_output_live_test.clj\\\")\""
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [im.ttalk.agent.kernel :as kernel]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.model.service :as service]
            [im.ttalk.agent.model.response :as resp]
            [im.ttalk.agent.advisor :as flt]
            [im.ttalk.agent.advisor.memory :as ma]
            [im.ttalk.agent.advisor.structured-output :as so]
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

;; core 零依赖不内置 JSON 解析器 → 由调用方注入
(def parse-json #(json/parse-string % true))

;;; ============================================================
;;; probe：数 LLM 调用次数 + 抓每轮发出去的历史
;;; ============================================================

(defn probe [log]
  {:name :probe
   :chat (fn [req chain]
           (let [r (chain req)]
             (swap! log conj {:messages (:messages req)
                              :text (resp/response-text (:response r))})
             r))})

(defn run-case
  "挂 memory + validation(turn) + probe 跑一轮。
   probe 在 memory 之后 = 洋葱更内层 → 看到的 :messages 是 memory 展开的完整历史
   （反馈消息也在里面）。"
  [svc schema question & {:keys [max-retries] :or {max-retries 2}}]
  (let [log (atom [])
        store (memory/in-memory-store)
        k (kernel/build-kernel
            {:service svc
             :filters [(ma/memory-filter store)
                       (flt/validation-turn-filter
                         (so/validate-fn schema :parse-fn parse-json)
                         :max-retries max-retries)
                       (probe log)]})
        result (react/invoke k store [{:role :user :content question}]
                             {:context (ctx/create)})
        text (resp/response-text (:response result))]
    {:log @log
     :result result
     :raw text
     :parsed (try (parse-json (so/strip-fences text)) (catch Throwable _ ::unparseable))
     :attempts (count @log)}))

(defn- all-user-text
  "最后一轮发出去的历史里全部 user 消息的文本（找反馈消息用）。"
  [{:keys [log]}]
  (->> (last log) :messages
       (filter #(= :user (:role %)))
       (map #(str (:content %)))
       (str/join "\n")))

;;; ============================================================
;;; 场景 1：合格输出 → 一次通过，不重试
;;; ============================================================

(def films-schema
  {:type "object"
   :properties {:actor {:type "string"}
                :films {:type "array"}}
   :required ["actor" "films"]})

(defn- first-output-valid?
  "首轮输出是否已合格——用来做条件断言，避免把模型的偶发不合规当成回归。"
  [r schema]
  (let [t1 (try (parse-json (so/strip-fences (:text (first (:log r)))))
                (catch Throwable _ ::unparseable))]
    (and (not= ::unparseable t1) (nil? (so/validate-value t1 schema)))))

(defn test-happy-path [svc]
  (println "\n=== 场景 1: 合格输出 → 一次通过，不重试 ===")
  (let [r (run-case svc films-schema
                    "生成一位知名演员的电影作品列表。只输出 JSON，字段：actor（演员名，字符串）、films（电影名数组）。不要输出任何其它文字。")]
    (println "   原始输出:" (clip (:raw r) 100))
    (println "   LLM 调用次数:" (:attempts r))
    (check "循环收敛" (= :completed (:status (:result r))))
    (check "最终输出可解析且符合 schema"
           (and (not= ::unparseable (:parsed r))
                (nil? (so/validate-value (:parsed r) films-schema))))
    ;; 条件断言：两条路都在测机制，且不因模型偶发不合规而 flake
    (if (first-output-valid? r films-schema)
      (check "首轮即合格 → 只调用 1 次 LLM（正常路径不被校验拖慢）"
             (= 1 (:attempts r)))
      (check "首轮不合格 → 发生了重入（本次模型没一把过，机制照样正确）"
             (> (:attempts r) 1)))
    ;; 真机输出常带 ```json 围栏——报告即可，是否带围栏是模型行为
    (println (str "   ⓘ 模型" (if (str/includes? (:raw r) "```") "带了" "没带")
                  " ```json 围栏"
                  (when (str/includes? (:raw r) "```") "（strip-fences 已剥掉，故校验通过）")))))

;;; ============================================================
;;; 场景 2：自我修正 —— 真实模型据反馈补上缺失字段
;;; ============================================================

;; schema 要求一个 prompt 里没提过、但模型**答得上来**的字段
;; → 第一轮几乎必然漏（真实失败），且反馈后补得上（自我修正可达成）。
;; 反例见 ns 文档：换成 internal_review_code 这种模型无从得知的字段，
;; 它两轮反馈都补不上——那测的就不是自我修正了。
(def strict-schema
  {:type "object"
   :properties {:actor {:type "string"}
                :films {:type "array"}
                :birth_year {:type "integer"}}
   :required ["actor" "films" "birth_year"]})

(defn test-self-correction [svc]
  (println "\n=== 场景 2: 自我修正（真实校验失败 → 反馈 → 模型改对） ===")
  (let [r (run-case svc strict-schema
                    ;; 刻意不提 birth_year —— 让失败真实发生
                    "生成一位知名演员的电影作品列表。只输出 JSON，字段：actor（演员名，字符串）、films（电影名数组）。不要输出任何其它文字。")
        feedback (all-user-text r)
        t1 (try (parse-json (so/strip-fences (:text (first (:log r)))))
                (catch Throwable _ nil))]
    (println "   第 1 次输出:" (clip (:text (first (:log r))) 90))
    (println "   最终输出:  " (clip (:raw r) 90))
    (println "   LLM 调用次数:" (:attempts r))

    (check "第 1 次输出确实漏了 birth_year（失败是真的，不是造的）"
           (nil? (:birth_year t1)))
    (check "发生了重入（>1 次 LLM 调用）" (> (:attempts r) 1))
    (check "反馈消息点名了具体问题（而非「请重试」）——Spring 的核心主张"
           (str/includes? feedback "birth_year"))
    (check "反馈消息进了对话历史（memory 在位，递归重入才拿得到上下文）"
           (str/includes? feedback "未通过校验"))
    (check "最终输出符合 schema —— 模型真的据反馈改对了"
           (and (not= ::unparseable (:parsed r))
                (nil? (so/validate-value (:parsed r) strict-schema))))
    (check "补上的字段确实有值"
           (integer? (:birth_year (:parsed r))))))

;;; ============================================================
;;; 场景 3：重试耗尽 → 原样返回（不无限重试）
;;; ============================================================

;; :enum [] —— 任何取值都不合法，模型再怎么改也过不了
(def impossible-schema
  {:type "object"
   :properties {:status {:type "string" :enum []}}
   :required ["status"]})

(defn test-exhaustion [svc]
  (println "\n=== 场景 3: 重试耗尽 → 原样返回（max-retries 1 → 恰好 2 次调用） ===")
  (let [r (run-case svc impossible-schema
                    "只输出 JSON：{\"status\": \"ok\"}。不要输出任何其它文字。"
                    :max-retries 1)]
    (println "   LLM 调用次数:" (:attempts r))
    (println "   最终输出:" (clip (:raw r) 80))
    (check "恰好 2 次 LLM 调用（首次 + 1 次重试）——不无限重试" (= 2 (:attempts r)))
    (check "耗尽后原样返回最后一次结果（:completed，由调用方自行判断合格与否）"
           (= :completed (:status (:result r))))
    (check "返回的结果确实仍不合格（校验器没被糊弄过去）"
           (some? (so/validate-value (:parsed r) impossible-schema)))))

;;; ============================================================
;;; 汇总
;;; ============================================================

(defn run []
  (println "结构化输出自我修正 live 验证 | model =" MODEL "| provider = :minimax")
  (let [svc (service/create-service p {:model MODEL :max-tokens 2048})]
    (doseq [f [test-happy-path test-self-correction test-exhaustion]]
      (try
        (f svc)
        (catch Throwable t
          (swap! failures inc)
          (println "  ✗ 场景异常:" (.getMessage t)))))
    (println)
    (if (zero? @failures)
      (println "全部通过 ✓")
      (println @failures "项失败 ✗"))
    (System/exit (if (zero? @failures) 0 1))))

(run)
