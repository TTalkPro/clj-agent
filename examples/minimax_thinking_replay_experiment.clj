(ns minimax-thinking-replay-experiment
  "P0 验证实验：thinking 块不回传，到底会不会降级？

   出处：`docs/provider-variant-design.md` §6 P0。那份设计的全部推导来自代码
   （`advisor/memory.clj` 与 `wire/anthropic.clj` 会把 thinking 块抹平），
   **但没有一条来自真机**。本仓库的惯例是这类判断不能靠推导定案——所以先做实验，
   **实验为否则 P3 撤回**。

   这不是门禁，是**观测**。故它只在「实验本身没跑通」时 exit 1
   （缺 key / 网络失败 / 模型没发起工具调用，结论无从谈起），
   结论本身是负面的话照样 exit 0——把负面结果当失败，就没人敢做实验了。

   ============================================================
   四个探针，对应设计文档 §7 的四个「已知未知」
   ============================================================

   探针 0（§7.3）：M2.x 的 thinking 真的关不掉吗？
     同一句话发三次：不传 thinking / {:type \"disabled\"} / {:type \"adaptive\"}，
     看响应里有没有 thinking 块。顺带印出响应里的 model 字段——MiniMax 对未知模型名
     会**静默回退**，不报错，不看这个就会拿另一个模型的结果当结论。

   探针 1（§7.1，核心）：回传缺 thinking 块会怎样？
     第一轮拿到 assistant 响应（含 thinking + tool_use），构造三条**只差回传内容**
     的第二轮：
       A 完整回传   —— 官方文档要求的形态（response.content 原样）
       B 剥掉 thinking —— **当前框架的实际行为**
       C 保留 thinking 但删掉 signature —— 探 §7.2：签名到底校不校验
     然后**把整条工具循环跑完**（每轮都按本臂策略回传），记录它走了几轮、
     有没有在中途断掉、有没有重复问同一个东西。

     任务默认是 chain（强制串行）：城市名藏在代号后面，代号只能从上一步的工具
     结果里拿到，模型没法并行——decode → weather → decode → weather → 作答。
     `EXPERIMENT_TASK=parallel` 是先写的那套「查两个城市」，**它测不动这件事**：
     模型第一轮就把两个 get-weather 并行发了，整条链只有一次工具轮，而
     interleaved thinking 是「每次拿到工具结果后再想一次」。留着它只为对照。

   探针 2（§7.4）：流式与非流式的块形状同构吗？
     P3 要把块存进历史，两条路产出的形状必须一致，否则历史里会混进两种形状。

   ============================================================
   判据（照着读，别自由发挥）
   ============================================================

     B 报错（4xx）                       → 降级确凿，P3 立项，且优先级高于 P1/P2
     B 不报错但机制退化                   → 软降级：P3 立项，验收要钉住退化的那一条
       （不再发起第二次工具调用 / 不再产出 thinking / 重复调用同一工具）
     A/B/C 三臂机制上无差别               → **对 MiniMax 而言 P3 撤回**，只留 P1
       （Anthropic 官方与 Gemini 的需求独立成立，但那不是本实验的结论）

   token 用量只印不判：prompt cache 会让 token 对照得出反向结论（已有前车之鉴）。
   模型措辞同样只印不判——会波动的东西不能当判据。

   运行（需 MINIMAX_API_KEY）：
     clojure -M -e '(load-file \"examples/minimax_thinking_replay_experiment.clj\")'

   环境变量：
     MINIMAX_API_KEY  - 必需（兼容旧名 MINIMAX_AUTH_TOKEN）
     MINIMAX_MODELS   - 逗号分隔，缺省 \"MiniMax-M2.7\"；想连 M3 一起验就写
                        \"MiniMax-M2.7,MiniMax-M3\"
     EXPERIMENT_REPEAT - 每臂重复次数，缺省 2（用来分辨「差异」与「采样波动」）
     EXPERIMENT_TASK   - chain（缺省，强制串行多轮）| parallel（弱对照，见上）"
  (:require [clojure.set :as set]
            [clojure.string :as str]
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

(def models
  (-> (or (System/getenv "MINIMAX_MODELS") "MiniMax-M2.7")
      (str/split #",")
      (->> (map str/trim) (remove str/blank?) vec)))

(def repeat-n
  (or (some-> (System/getenv "EXPERIMENT_REPEAT") Integer/parseInt) 2))

;; 直接叩 provider 的**底层**函数，而不是走 agent：本实验要控制的恰恰是
;; agent 循环替我们决定的那件事（回传什么）。call-anthropic 收 wire 形态消息，
;; 给的正是这一层控制力。
(def ^:private base-config
  {:provider-name :minimax
   :base-url "https://api.minimaxi.com"
   :api-path "/anthropic/v1/messages"
   :auth-scheme :bearer
   :anthropic-version nil
   :api-key auth-token
   :max-tokens 2048})

;; 两套任务，默认用 chain。
;;
;; parallel 那套（先写的）测不动本实验要测的东西：模型在第一轮就把两个 get-weather
;; **并行**发出去了，整条链只有一次工具轮——interleaved thinking 是「每次拿到工具
;; 结果后再想一次」，一轮压根压不到它。用它得出的「无差别」是假阴性温床。
;;
;; chain 那套强制串行：城市藏在代号后面，代号只能从上一步的工具结果里拿到，
;; 模型没法并行——decode → weather → decode → weather → 作答，四个工具轮，
;; 每轮之间都必须重新思考。这才是回传缺 thinking 块该现形的地方。
(deftool get-weather
  "查询指定城市当前天气。一次只能查一个城市。"
  [[city :string "城市名"]]
  (case city
    "北京" "北京: 晴, 32°C。下一个代号：K3"
    "上海" "上海: 多云, 28°C。没有下一个代号了。"
    (str city ": 晴, 25°C")))

(deftool decode-city
  "把城市代号解码成城市名。"
  [[code :string "城市代号，如 X7"]]
  (case (str/upper-case (or code ""))
    "X7" "北京"
    "K3" "上海"
    (str "未知代号 " code)))

;; 注意：`call-anthropic` 收的是**已编译的 tool schema**（kernel 平时递下来的就是
;; `tool/get-schema` 的产物），不是 tool var。直接传 `[#'get-weather]` 会转出
;; {:name nil}，MiniMax 报 400「function name is empty (2013)」——绕过 agent 直叩
;; provider 时这一层是自己的活。
(def ^:private task
  (if (= "parallel" (System/getenv "EXPERIMENT_TASK")) :parallel :chain))

(def ^:private tools
  (if (= :chain task)
    [(tool/get-schema #'decode-city) (tool/get-schema #'get-weather)]
    [(tool/get-schema #'get-weather)]))

(def ^:private tool-impls
  {"get-weather" get-weather
   "decode-city" decode-city})

(def ^:private question
  (if (= :chain task)
    (str "代号 X7 和它后面的城市，哪个更热？规则：城市名藏在代号后面，必须先用 "
         "decode-city 把代号解码成城市名，才能用 get-weather 查它的天气；"
         "天气结果里会告诉你下一个代号。请从 X7 开始，一步步解码并查询，"
         "两个城市都查完后再回答哪个更热。")
    "北京和上海哪个更热？请用 get-weather 工具分别查询这两个城市（工具一次只能查一个城市），再回答。"))

;;; ============================================================
;;; 工具
;;; ============================================================

(defn- call!
  "叩一次；失败不抛，归一成 {:error ...}——三臂里有臂会报错正是我们要观测的。"
  [config messages]
  (try
    {:ok (anthropic/call-anthropic (merge base-config config) messages tools)}
    (catch clojure.lang.ExceptionInfo e
      {:error (assoc (ex-data e) :message (ex-message e))})
    (catch Exception e
      {:error {:type :unexpected :message (.getMessage e)}})))

(defn- blocks-of [resp] (vec (:content resp)))
(defn- block-types [resp] (mapv :type (blocks-of resp)))
(defn- thinking-blocks [resp] (filterv #(= "thinking" (:type %)) (blocks-of resp)))
(defn- tool-uses [resp] (filterv #(= "tool_use" (:type %)) (blocks-of resp)))
(defn- text-of [resp]
  (->> (blocks-of resp) (filter #(= "text" (:type %))) (map :text) (str/join "\n")))

(defn- args-of
  "tool_use 的 :input（HTTP 层已 keywordize，保险起见两种键都认）。"
  [tu]
  (let [in (:input tu)]
    (into {} (for [[k v] in] [(keyword (name k)) v]))))

(defn- target-of
  "本次调用问的是哪个「东西」（城市名或代号）——用来判断有没有重复问同一个。"
  [tu]
  (let [{:keys [city code]} (args-of tu)]
    (some-> (or city code) str/upper-case)))

(defn- run-tools
  "按响应里的**每一个** tool_use 块执行工具，构造 tool_result 列表。

   必须全部回复：MiniMax 会在一轮里并行发起多个 tool_use，只回其中一个 →
   400「tool call and result not match」，三臂一起挂，什么也测不出来
   （这正是本脚本第一版踩的坑）。
   deftool 生成的函数收 args map（关键字键），不是位置参数。"
  [resp]
  (mapv (fn [tu]
          (let [f (get tool-impls (:name tu))]
            {:type "tool_result"
             :tool_use_id (:id tu)
             :content (if f
                        (str (f (args-of tu)))
                        (str "未知工具 " (:name tu)))}))
        (tool-uses resp)))

(defn- brief [resp]
  (format "块=%s thinking=%d tool_use=%d stop=%s in/out=%s/%s model=%s"
          (pr-str (block-types resp))
          (count (thinking-blocks resp))
          (count (tool-uses resp))
          (pr-str (:stop_reason resp))
          (get-in resp [:usage :input_tokens])
          (get-in resp [:usage :output_tokens])
          (pr-str (:model resp))))

(def findings (atom []))
(defn- record! [m] (swap! findings conj m))

;;; ============================================================
;;; 探针 0：thinking 关得掉吗 + 有没有静默换模型
;;; ============================================================

(defn probe-0-thinking-switch [model]
  (println "\n--- 探针 0：thinking 开关语义（§7.3）+ 模型静默回退 ---")
  (doseq [[label thinking] [["省略 thinking" nil]
                            ["{:type \"disabled\"}" {:type "disabled"}]
                            ["{:type \"adaptive\"}" {:type "adaptive"}]]]
    (let [{:keys [ok error]} (call! (cond-> {:model model} thinking (assoc :thinking thinking))
                                    [{:role "user" :content "用一句话说明水为什么会结冰。"}])]
      (if error
        (println (format "  %-22s -> 报错 %s" label (pr-str (select-keys error [:type :status :message]))))
        (let [n (count (thinking-blocks ok))]
          (println (format "  %-22s -> thinking 块 %d 个 | %s" label n (brief ok)))
          (record! {:probe :thinking-switch :model model :arm label
                    :thinking-blocks n :returned-model (:model ok)})
          ;; MiniMax 对未知模型名静默回退，不报错——不比对就会拿别的模型的结果当结论
          (when (and (:model ok) (not= (:model ok) model))
            (println (format "  ⚠ 请求 %s，响应说是 %s —— 静默回退，本轮结论对 %s 不成立"
                             model (:model ok) model))))))))

;;; ============================================================
;;; 探针 1：三臂回传对照（核心）
;;; ============================================================

(defn- replay-policy
  "三臂只差这一件事：assistant 消息回传什么。"
  [arm resp]
  (let [blocks (blocks-of resp)]
    (case arm
      :A blocks                                                            ;; 官方要求：原样
      :B (filterv #(not= "thinking" (:type %)) blocks)                     ;; 当前框架行为
      :C (mapv #(if (= "thinking" (:type %)) (dissoc % :signature) %) blocks))))

(defn- run-arm
  "跑完一整条工具循环（最多 max-rounds 轮），每轮按本臂策略回传 assistant。

   单看第二轮不够：interleaved thinking 的价值在**跨轮**，模型要在每次工具结果
   之后再想一次。故这里跑成循环，记录它走了几轮、是否在中途断掉。"
  [model arm user-msg first-resp max-rounds]
  (loop [history [user-msg]
         resp first-resp
         round 1
         acc {:arm arm :rounds 0 :tool-uses 0 :thinking 0 :repeated? false}]
    (let [tus (tool-uses resp)
          asked (set (keep target-of tus))
          acc (-> acc
                  (update :rounds inc)
                  (update :tool-uses + (count tus))
                  (update :thinking + (count (thinking-blocks resp)))
                  (update :repeated? #(or % (boolean (seq (set/intersection
                                                            asked (:asked acc #{}))))))
                  (update :asked (fnil into #{}) asked))]
      (if (or (empty? tus) (>= round max-rounds))
        (assoc acc :final-text (text-of resp) :stop (:stop_reason resp))
        (let [msgs (conj history
                         {:role "assistant" :content (replay-policy arm resp)}
                         {:role "user" :content (run-tools resp)})
              {ok :ok err :error} (call! {:model model} msgs)]
          (if err
            (assoc acc :error (select-keys err [:type :status :message]) :failed-at-round round)
            (recur msgs ok (inc round) acc)))))))

(defn probe-1-replay [model]
  (println "\n--- 探针 1：回传缺 thinking 块会怎样（§7.1 / §7.2，核心）---")
  (let [user-msg {:role "user" :content question}
        {:keys [ok error]} (call! {:model model} [user-msg])]
    (cond
      error
      (do (println "  第一轮就失败，实验无从谈起：" (pr-str error))
          :inconclusive)

      (empty? (tool-uses ok))
      (do (println "  第一轮模型没发起工具调用，本次实验无从谈起（可重跑）。" (brief ok))
          :inconclusive)

      :else
      (let [think (thinking-blocks ok)
            signed? (boolean (some :signature think))]
        (println "  第一轮：" (brief ok))
        (println (format "  thinking 块 %d 个，带 signature：%s" (count think) signed?))
        (when (seq think)
          (let [t (str (:thinking (first think)))]
            (println "  thinking 首块预览：" (pr-str (subs t 0 (min 80 (count t)))))))
        (when-not signed?
          (println "  ↳ 无 signature：C 臂与 A 臂等价，跳过 C（§7.2 就此得出「不带签名」）"))
        (doseq [arm (if signed? [:A :B :C] [:A :B])
                i (range repeat-n)]
          (let [r (run-arm model arm user-msg ok (if (= :chain task) 7 4))]
            (if (:error r)
              (println (format "  臂 %s #%d -> ✗ 第 %d 轮报错 %s"
                               (name arm) (inc i) (:failed-at-round r) (pr-str (:error r))))
              (println (format "  臂 %s #%d -> 走完 %d 轮 | tool_use 共 %d | thinking 共 %d | 重复查同一城市：%s | stop=%s"
                               (name arm) (inc i) (:rounds r) (:tool-uses r) (:thinking r)
                               (:repeated? r) (pr-str (:stop r)))))
            (when-let [t (:final-text r)]
              (println (format "         末轮文本：%s" (pr-str (subs t 0 (min 70 (count t)))))))
            (record! (-> r (assoc :probe :replay :model model :run i) (dissoc :asked :final-text)))))
        :ok))))

;;; ============================================================
;;; 探针 2：流式 / 非流式块形状
;;; ============================================================

(defn probe-2-stream-shape [model]
  (println "\n--- 探针 2：流式与非流式的块形状是否同构（§7.4）---")
  (let [q [{:role "user" :content "用一句话说明彩虹为什么是弯的。"}]
        {sync-ok :ok sync-err :error} (call! {:model model} q)
        streamed (try
                   (anthropic/call-anthropic-stream (merge base-config {:model model}) q tools (fn [_] nil))
                   (catch Exception e {:stream-error (.getMessage e)}))]
    (if (or sync-err (:stream-error streamed))
      (println "  一侧失败，跳过：" (pr-str (or sync-err (:stream-error streamed))))
      (let [a (set (block-types sync-ok))
            b (set (block-types streamed))
            ;; 形状同构不只看块类型：thinking 块在两条路上带的字段必须一致
            keys-of (fn [resp] (->> (thinking-blocks resp) (mapcat keys) set))]
        (println "  非流式块类型：" (pr-str a) "| thinking 块字段：" (pr-str (keys-of sync-ok)))
        (println "  流式块类型：  " (pr-str b) "| thinking 块字段：" (pr-str (keys-of streamed)))
        (println (if (and (= a b) (= (keys-of sync-ok) (keys-of streamed)))
                   "  ✓ 同构"
                   "  ✗ 不同构 —— P3 前必须对齐，否则历史里会混进两种形状"))
        (record! {:probe :stream-shape :model model :sync a :stream b :same? (= a b)})))))

;;; ============================================================
;;; 判读
;;; ============================================================

(defn- verdict []
  (println "\n==================================================")
  (println "判读（按 docs/provider-variant-design.md §6 P0 的判据）")
  (println "==================================================")
  (let [replay (filter #(= :replay (:probe %)) @findings)
        by-arm (group-by :arm replay)
        arm-stat (fn [arm]
                   (let [rs (get by-arm arm)]
                     {:n (count rs)
                      :errors (count (filter :error rs))
                      :repeated (count (filter :repeated? rs))
                      :rounds (mapv :rounds rs)
                      :no-thinking (count (filter #(and (not (:error %)) (zero? (long (:thinking % 0)))) rs))}))]
    (if (empty? replay)
      (println "探针 1 没有产出可判读的数据（见上文原因）。")
      (do
        (doseq [arm [:A :B :C] :when (get by-arm arm)]
          (println (format "  臂 %s：%s" (name arm) (pr-str (arm-stat arm)))))
        (let [b (arm-stat :B) a (arm-stat :A)]
          (println)
          (cond
            ;; 先证伪自己：A 臂（官方要求的形态）也挂 = 实验搭错了，不是发现。
            ;; 第一版就在这里差点报出假阳性——它只回了一个 tool_result，
            ;; 而 MiniMax 一轮并行发起了两个 tool_use，三臂一起 400。
            (pos? (:errors a))
            (println "→ ⚠ A 臂（完整回传）也报错：**实验本身有问题，结论作废**。"
                     "\n  A 臂是官方文档要求的形态，它挂说明错在脚本或调用方式，不在回传策略。"
                     "\n  先看上面的报错信息（tool call and result not match = tool_result 没回全）。")

            (pos? (:errors b))
            (println "→ B 臂报错而 A 臂不报错：**降级确凿**，P3 立项，优先级高于 P1/P2。")

            (or (> (:repeated b) (:repeated a))
                (> (:no-thinking b) (:no-thinking a))
                (< (apply min (:rounds b)) (apply min (:rounds a))))
            (println "→ B 臂机制退化（重复查同一城市 / 不再产出 thinking / 轮次更少）："
                     "\n  **软降级**，P3 立项，验收要钉住退化的那一条。"
                     "\n  注意重复次数少时无法排除采样波动——加大 EXPERIMENT_REPEAT 复核。")

            :else
            (println "→ 三臂机制上无差别：**对 MiniMax 而言 P3 撤回**，只留 P1（config 白名单）。"
                     "\n  Anthropic 官方与 Gemini 的回传需求独立成立，但那不是本实验的结论。")))))
    (println "\n原始记录（可粘进设计文档 §7）：")
    (doseq [f @findings] (println "  " (pr-str f)))))

;;; ============================================================
;;; main
;;; ============================================================

(println "==================================================")
(println "P0 实验：MiniMax thinking 块回传对照")
(println "模型：" (str/join " / " models) "| 每臂重复：" repeat-n)
(println "==================================================")

(def results
  (doall
    (for [model models]
      (do
        (println (format "\n########## 模型 %s ##########" model))
        (probe-0-thinking-switch model)
        (let [r (probe-1-replay model)]
          (probe-2-stream-shape model)
          {:model model :probe-1 r})))))

(verdict)

(shutdown-agents)

;; exit 语义：实验跑通即 0（结论为负也是结论）；只有**结论无从谈起**才 1
(let [inconclusive (filter #(= :inconclusive (:probe-1 %)) results)]
  (when (seq inconclusive)
    (println "\n⚠ 以下模型没能得出结论（第一轮失败或模型未发起工具调用）："
             (str/join " " (map :model inconclusive))))
  (System/exit (if (= (count inconclusive) (count results)) 1 0)))
