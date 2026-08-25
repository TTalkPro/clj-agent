(ns p3-replay-acceptance-live-test
  "P3 验收：**走真实框架**（create-agent），正确率是否回到 A 臂。

   为什么需要这个脚本——前两个实验都是**绕开框架**直叩 provider 的（那是为了
   控制回传内容），它们证明的是「回传策略造成差异」。而 P3 要证明的是另一件事：
   **修完之后，框架自己走出来的行为等于 A 臂**。中间隔着 chat-model 归一化、
   memory filter 落库、下一轮取历史、wire 还原四道关，任何一道漏掉载荷，
   这里的数字就会掉回 B 臂。

   单测已经钉住了链路（client 的 replay_blocks_loop_test 证明载荷到达协议边界，
   provider 的 replay_blocks_test 证明中立→wire 逐字还原）。**但单测证明不了
   模型拿到它之后会不会真的答对**——那只有真模型能回答，故有本脚本。

   基线（docs/provider-variant-design.md §7.5.3，M3，n=40/臂）：
     A 完整回传        100%  正确 / 逐轮全对 100%
     B 剥 thinking      82.5% 正确 / 逐轮全对 47.5%   ← 修复前的框架行为

   验收判据：正确率 ≥ 95%（20 次里至多错 1 次）。A 臂 40 次零方差，
   留 1 次余量是给网络与采样，不是给回归。

   运行（需 MINIMAX_API_KEY，会真实计费；约 20 次 × 8 轮 ≈ 160 次调用）：
     clojure -M -e '(load-file \"examples/p3_replay_acceptance_live_test.clj\")'

   环境变量：
     MINIMAX_API_KEY - 必需     MINIMAX_MODEL - 缺省 MiniMax-M3
     EXPERIMENT_N    - 次数，缺省 20"
  (:require [clojure.string :as str]
            [im.ttalk.agent.simple-agent :as agent]
            [im.ttalk.agent.tool :refer [deftool]]
            [im.ttalk.agent.provider.minimax :as minimax]))

(def auth-token (or (System/getenv "MINIMAX_API_KEY")
                    (System/getenv "MINIMAX_AUTH_TOKEN")))

(when-not auth-token
  (println "需要 MINIMAX_API_KEY（或旧变量 MINIMAX_AUTH_TOKEN）")
  (System/exit 1))

(def model (or (System/getenv "MINIMAX_MODEL") "MiniMax-M3"))
(def n (or (some-> (System/getenv "EXPERIMENT_N") Integer/parseInt) 20))

;;; 与 minimax_thinking_quality_experiment.clj 同一个任务与真值规则
(def ^:private start-slot 23)
(def ^:private chain-len 7)

(defn- slot-value [slot] (+ 10 (mod (+ (* slot 7) 13) 90)))
(defn- digit-sum [x] (reduce + (map #(Character/digit ^char % 10) (str x))))
(defn- next-slot [slot value] (+ (* (digit-sum value) 3) (mod slot 5)))

(def ^:private truth
  (let [slots (loop [s start-slot acc []]
                (if (= (count acc) chain-len) acc
                    (recur (next-slot s (slot-value s)) (conj acc s))))]
    {:slots slots :sum (reduce + (map slot-value slots))}))

(deftool vault-probe
  "查询金库槽位的值。"
  [[slot :number "槽位编号"]]
  (str "槽位 " slot " 的值是 " (slot-value (long slot))))

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

(defn- parse-answer [text]
  (or (some-> (re-find #"最终答案[：:]\s*\**\s*(-?\d+)" (or text "")) second parse-long)
      (some-> (last (re-seq #"-?\d+" (or text ""))) parse-long)))

(defn- run-once []
  ;; 每次新建 agent：默认 in-memory store，一次一条独立对话
  (let [tool-calls (atom 0)
        a (agent/create-agent
            {:provider (minimax/create-provider {})
             :model model
             :max-tokens 2048
             ;; P1 打通的那条路——provider 专属键从 create-agent 直达 provider。
             ;; M3 缺省关闭 thinking，不开就没有载荷可回传，验收也就无从谈起。
             :thinking {:type "adaptive"}
             :tools [#'vault-probe]
             :max-iterations 12
             :callbacks {:on-tool-call (fn [& _] (swap! tool-calls inc) nil)}})
        r (agent/chat a question)
        answer (parse-answer (:text r))]
    {:status (:status r)
     :answer answer
     :correct? (= answer (:sum truth))
     :tool-calls @tool-calls}))

(println "==================================================")
(println "P3 验收：走真实框架（create-agent）")
(println "模型：" model "| 次数：" n "| 真值：" (:sum truth) "| 链：" (:slots truth))
(println "基线：A 完整回传 100% / B 剥 thinking 82.5%（修复前的框架行为）")
(println "==================================================")

(def results
  (doall
    (for [i (range n)]
      (let [r (run-once)]
        (println (format "  #%2d -> 答案 %s %s | 工具调用 %d | %s"
                         (inc i) (pr-str (:answer r))
                         (if (:correct? r) "✓" "✗")
                         (:tool-calls r) (name (:status r))))
        r))))

(let [ok (count (filter :correct? results))
      rate (double (/ ok n))]
  (println "\n==================================================")
  (println (format "正确 %d/%d = %.1f%%（基线：A 100%% / B 82.5%%）" ok n (* 100 rate)))
  (if (>= rate 0.95)
    (do (println "✓ 验收通过：框架行为已等同 A 臂——载荷穿过了 chat-model→memory→wire 全链")
        (shutdown-agents)
        (System/exit 0))
    (do (println "✗ 验收失败：正确率没回到 A 臂水平。")
        (println "  先查 client 的 replay_blocks_loop_test 与 provider 的 replay_blocks_test：")
        (println "  单测全绿而这里掉，说明漏在两者之间没覆盖到的接缝上。")
        (shutdown-agents)
        (System/exit 1))))
