(ns minimax-thinking-quality-experiment
  "P0 后续实验：thinking 块不回传，会不会**让模型答错**？

   出处：`docs/provider-variant-design.md` §7.5——那里**预先**定死了判据，
   本脚本只负责执行，不负责解释。

   ============================================================
   前一个实验留下的缺口
   ============================================================

   `minimax_thinking_replay_experiment.clj` 已经证明（M3，两轮独立数据同向）：
   剥掉 thinking 回传 → 模型后续轮次**少思考约 1/4**（4.50 → 3.33 次/链）。
   但它同时证明：轮数、工具调用数、重复率、报错、答案**全同**。

   即：**行为差异确认，质量损害未证明**。而 §1 判据要的是「不建就做不到的能力」，
   「模型少想了几次」不是能力——除非它换来正确率。本实验就是去拿这个证据。

   前一个实验测不出质量差异的原因很具体：任务太简单（查两个城市比大小），
   少想几次照样答对。所以这里换一个**难到必须逐轮算**的任务。

   ============================================================
   任务设计（三条硬要求，缺一不可）
   ============================================================

   1. **答案唯一且可自动判定**——不是「哪个更热」这种一眼看穿的，是一个具体数字，
      脚本用同一套规则独立算出真值来比对。
   2. **必须逐轮推理**——下一个要查的槽位编号，得由**本轮刚拿到的值**现算
      （各位数字之和 × 3 + 当前编号 mod 5）。想省掉中间步骤就得凭空猜。
   3. **错误沉默复合**——工具对**任何**编号都照常返回值，不提示「你查错了」。
      算错一步 → 后面全歪 → 总和错，但过程里没有任何告警。这才逼真：
      真实任务里没人会告诉模型它刚才想岔了。

   于是每一轮都可判定对错（查的编号是否等于应查的），而不只是最后一个数字。

   ============================================================
   判据（**预注册**，跑之前就定死，别看到数据再找理由）
   ============================================================

     A 正确率 == 0                  → **地板效应，结论作废**（任务太难，测的是别的）
     A 与 B 都 == 100%              → **天花板效应，结论作废**（任务太简单，同上）
     B 正确率显著低于 A（单侧 Fisher p < 0.05）
                                    → **P3 立项**：回传契约有真实收益
     否则                           → **P3 永久不立项**：一个没有后果的行为差异，
                                       不值得一个协议 + 一个中立消息字段
                                       （§1.1 成本不对称：建的代价永久）

   只跑 A / B 两臂：signature 那条（C 臂）前一个实验已经结清，不重复花钱。

   ============================================================
   运行（需 MINIMAX_API_KEY；**会真实计费**）
   ============================================================

     clojure -M -e '(load-file \"examples/minimax_thinking_quality_experiment.clj\")'

   缺省 20 次/臂 × 2 臂 × 约 6 轮 ≈ 240 次调用，约 15–25 分钟。
   先用 EXPERIMENT_N=1 跑通再放开。

   环境变量：
     MINIMAX_API_KEY - 必需（兼容旧名 MINIMAX_AUTH_TOKEN）
     MINIMAX_MODEL   - 缺省 \"MiniMax-M3\"。**必须用能关 thinking 的模型**：
                       M2.x 关不掉，每轮必然思考，两臂天然无差别（前一个实验已证）
     EXPERIMENT_N    - 每臂次数，缺省 20（§7.5 要求 ≥20）"
  (:require [clojure.string :as str]
            [im.ttalk.agent.tool :as tool :refer [deftool]]
            [im.ttalk.agent.provider.anthropic :as anthropic]))

;;; ============================================================
;;; 环境
;;; ============================================================

(def auth-token (or (System/getenv "MINIMAX_API_KEY")
                    (System/getenv "MINIMAX_AUTH_TOKEN")))

(when-not auth-token
  (println "需要 MINIMAX_API_KEY（或旧变量 MINIMAX_AUTH_TOKEN）")
  (System/exit 1))

(def model (or (System/getenv "MINIMAX_MODEL") "MiniMax-M3"))
(def n-per-arm (or (some-> (System/getenv "EXPERIMENT_N") Integer/parseInt) 20))

(def ^:private config
  {:provider-name :minimax
   :base-url "https://api.minimaxi.com"
   :api-path "/anthropic/v1/messages"
   :auth-scheme :bearer
   :anthropic-version nil
   :api-key auth-token
   :model model
   :max-tokens 2048
   ;; M3 缺省关闭 thinking——不显式开，两臂无 thinking 可剥，实验空转
   :thinking {:type "adaptive"}})

;;; ============================================================
;;; 任务：金库链（真值由同一套规则独立算出）
;;; ============================================================

(def ^:private start-slot 23)

;; 链长 = 难度旋钮。**先用 EXPERIMENT_N=1 试跑校准，再放开正式跑**：
;; 5 步时两臂都答对（天花板，判别不出来），故正式实验用 7 步。
;; 这是跑之前的难度校准，不是看到结果再调判据——判据在 §7.5，一个字没动。
(def ^:private chain-len
  (or (some-> (System/getenv "EXPERIMENT_CHAIN") Integer/parseInt) 7))

(defn- slot-value
  "槽位 → 值。对**任何**编号都有答案：查错了也照样给值，不给告警。"
  [slot]
  (+ 10 (mod (+ (* slot 7) 13) 90)))

(defn- digit-sum [n]
  (reduce + (map #(Character/digit ^char % 10) (str n))))

(defn- next-slot [slot value]
  (+ (* (digit-sum value) 3) (mod slot 5)))

(def ^:private truth
  "标准链：[槽位…] / [值…] / 总和。"
  (let [slots (loop [s start-slot acc []]
                (if (= (count acc) chain-len)
                  acc
                  (recur (next-slot s (slot-value s)) (conj acc s))))
        values (mapv slot-value slots)]
    {:slots slots :values values :sum (reduce + values)}))

;; 链必须无环，否则「查错了」与「重复查」分不开，逐轮判对错就失效
(assert (= chain-len (count (set (:slots truth))))
        (str "链有重复槽位，换 start-slot 或规则：" (:slots truth)))

(deftool vault-probe
  "查询金库槽位的值。"
  [[slot :number "槽位编号"]]
  (str "槽位 " slot " 的值是 " (slot-value (long slot))))

(def ^:private tools [(tool/get-schema #'vault-probe)])

(def ^:private question
  (str "金库有很多槽位。请按规则一步步查询，最后给出总和。\n"
       "规则：\n"
       "1. 从槽位 " start-slot " 开始。\n"
       "2. 用 vault-probe 查询当前槽位，得到它的值 V。\n"
       "3. 下一个槽位编号 = （V 的各位数字之和 × 3） + （当前槽位编号 除以 5 的余数）。\n"
       "4. 重复第 2、3 步，直到一共查询了 " chain-len " 个槽位。\n"
       "5. 把这 " chain-len " 个值相加。\n"
       "工具对任何编号都会返回值，不会提示你查错，请自己算准。\n"
       "最后一行必须是：最终答案：<总和数字>"))

;;; ============================================================
;;; 调用与批改
;;; ============================================================

(defn- call! [messages]
  (try
    {:ok (anthropic/call-anthropic config messages tools)}
    (catch clojure.lang.ExceptionInfo e
      {:error (assoc (ex-data e) :message (ex-message e))})
    (catch Exception e
      {:error {:type :unexpected :message (.getMessage e)}})))

(defn- blocks-of [r] (vec (:content r)))
(defn- tool-uses [r] (filterv #(= "tool_use" (:type %)) (blocks-of r)))
(defn- thinking-n [r] (count (filterv #(= "thinking" (:type %)) (blocks-of r))))
(defn- text-of [r]
  (->> (blocks-of r) (filter #(= "text" (:type %))) (map :text) (str/join "\n")))

(defn- slot-of [tu]
  (let [in (:input tu)]
    (some-> (or (:slot in) (get in "slot")) long)))

(defn- run-tools [r]
  (mapv (fn [tu]
          {:type "tool_result"
           :tool_use_id (:id tu)
           :content (vault-probe {:slot (slot-of tu)})})
        (tool-uses r)))

(defn- replay
  "两臂唯一的差别。B = 框架当前行为（thinking 块进不了中立消息，回传时自然没有）。"
  [arm r]
  (if (= :A arm)
    (blocks-of r)
    (filterv #(not= "thinking" (:type %)) (blocks-of r))))

(defn- parse-answer
  "取『最终答案：<数字>』；没有该行则退回文本里最后一个整数（宽松，宁可判它对）。"
  [text]
  (or (some-> (re-find #"最终答案[：:]\s*\**\s*(-?\d+)" (or text "")) second parse-long)
      (some-> (last (re-seq #"-?\d+" (or text ""))) parse-long)))

(defn- run-once
  "跑完一条链，返回批改结果。"
  [arm]
  (let [user-msg {:role "user" :content question}]
    (loop [history [user-msg]
           resp (:ok (call! [user-msg]))
           round 1
           probed []
           thinking 0]
      (cond
        (nil? resp) {:arm arm :error :call-failed}

        (or (empty? (tool-uses resp)) (> round 16))
        (let [answer (parse-answer (text-of resp))
              ;; 逐轮判对错：查的编号是否等于应查的（顺序敏感）
              correct-steps (count (take-while true? (map = probed (:slots truth))))]
          {:arm arm
           :rounds round
           :thinking (+ thinking (thinking-n resp))
           :probed probed
           :answer answer
           :correct? (= answer (:sum truth))
           :correct-steps correct-steps
           :all-steps-correct? (= (vec probed) (:slots truth))})

        :else
        (let [msgs (conj history
                         {:role "assistant" :content (replay arm resp)}
                         {:role "user" :content (run-tools resp)})
              {ok :ok err :error} (call! msgs)]
          (if err
            {:arm arm :error (select-keys err [:type :status :message]) :round round}
            (recur msgs ok (inc round)
                   (into probed (keep slot-of (tool-uses resp)))
                   (+ thinking (thinking-n resp)))))))))

;;; ============================================================
;;; 统计：单侧 Fisher 精确检验（H1: A 的正确率 > B 的）
;;; ============================================================

(defn- c [n k]
  (if (or (neg? k) (> k n))
    0N
    (/ (reduce *' 1N (range (inc (- n k)) (inc n)))
       (reduce *' 1N (range 1 (inc k))))))

(defn- fisher-right-tail
  "2×2 表 [[a b] [c d]]：a=A 对, b=A 错, c=B 对, d=B 错。
   返回 P(A 至少这么好 | 边缘固定)——超几何右尾。"
  [a b cc d]
  (let [n (+ a b cc d)
        row1 (+ a b)
        col1 (+ a cc)
        lo (max 0 (- col1 (+ cc d)))
        hi (min row1 col1)]
    (double
      (reduce + 0
              (for [x (range a (inc hi))
                    :when (<= lo x)]
                (/ (* (c row1 x) (c (+ cc d) (- col1 x)))
                   (c n col1)))))))

;;; ============================================================
;;; 跑
;;; ============================================================

(println "==================================================")
(println "质量对照实验：剥掉 thinking 回传，会不会答错？")
(println "模型：" model "| 每臂" n-per-arm "次 | 判据见 docs/provider-variant-design.md §7.5")
(println "标准链：" (:slots truth) "→ 值" (:values truth) "→ 总和" (:sum truth))
(println "==================================================")

(def results
  (doall
    (for [arm [:A :B]
          i (range n-per-arm)]
      (let [r (run-once arm)]
        (println (format "  %s #%2d -> %s"
                         (name arm) (inc i)
                         (if (:error r)
                           (str "✗ " (pr-str (:error r)))
                           (format "答案 %s（真值 %d）%s | 逐轮对 %d/%d | thinking %d | 轮 %d"
                                   (pr-str (:answer r)) (:sum truth)
                                   (if (:correct? r) "✓" "✗")
                                   (:correct-steps r) chain-len
                                   (:thinking r) (:rounds r)))))
        r))))

;;; ============================================================
;;; 判读
;;; ============================================================

(defn- stat [arm]
  (let [rs (filter #(and (= arm (:arm %)) (not (:error %))) results)
        n (count rs)
        correct (count (filter :correct? rs))
        steps (count (filter :all-steps-correct? rs))]
    {:n n :correct correct
     :rate (if (pos? n) (double (/ correct n)) 0.0)
     :all-steps-correct steps
     :mean-thinking (if (pos? n)
                      (double (/ (reduce + (map #(long (:thinking % 0)) rs)) n))
                      0.0)
     :errors (count (filter #(and (= arm (:arm %)) (:error %)) results))}))

(let [a (stat :A) b (stat :B)
      p (fisher-right-tail (:correct a) (- (:n a) (:correct a))
                           (:correct b) (- (:n b) (:correct b)))]
  (println "\n==================================================")
  (println "判读（判据在 docs/provider-variant-design.md §7.5，跑之前已定死）")
  (println "==================================================")
  (println "  臂 A（完整回传）      ：" (pr-str a))
  (println "  臂 B（剥 thinking）   ：" (pr-str b))
  (println (format "  单侧 Fisher p = %.4f" p))
  (println)
  (cond
    (or (zero? (:n a)) (zero? (:n b)))
    (println "→ ⚠ 有臂一次都没跑成，**结论作废**。")

    (zero? (:correct a))
    (println "→ ⚠ **地板效应，结论作废**：A 臂（完整回传）也全错，说明任务太难，"
             "\n  测的是模型算术能力而不是回传策略。换更短的链再跑。")

    (and (= 1.0 (:rate a)) (= 1.0 (:rate b)))
    (println "→ ⚠ **天花板效应，结论作废**：两臂全对，任务太简单，判别不出来。"
             "\n  加长链或加大每步的计算量再跑。")

    (< p 0.05)
    (println (format "→ **B 显著更差（p = %.4f < 0.05）：P3 立项。** 回传契约有真实收益："
                     p)
             "\n  验收要钉住的正是这条正确率差异，而不是「thinking 块有没有回去」。")

    :else
    (println (format "→ **无显著差异（p = %.4f ≥ 0.05）：P3 永久不立项。**" p)
             "\n  按 §1.1 成本不对称——一个没有后果的行为差异，不值得一个协议 +"
             "\n  一个中立消息字段。前一个实验测到的「少思考 1/4」就此定性为"
             "\n  **可观测但无后果**。"))

  (println "\n原始记录（可粘进设计文档 §7）：")
  (doseq [r results] (println "  " (pr-str (dissoc r :probed)))))

(shutdown-agents)
;; 实验脚本：跑通即 0，结论为负也是结论（与 replay 实验同一约定）
(System/exit 0)
