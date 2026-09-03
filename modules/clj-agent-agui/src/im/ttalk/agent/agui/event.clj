(ns im.ttalk.agent.agui.event
  "中立事件模型 + 每 run 的发射器。

   **零 AG-UI 概念**（协议编码在 `agui.codec`），**零 web 概念**
   （design-principles §2：本模块不认识 Request/Response/SSE 帧）。

   三条契约（docs/agent-runtime-design.md §4.2）：

   1. `:seq` 在一个**会话**内单调递增、无洞——不是每 run 重置。HITL 下一次对话
      由多个 run 组成（run → `:run/paused` → resume 起 run'），订阅挂在会话上跨
      run 连续；seq 按 run 重置会让 `:since` 退化成 `{run-id, seq}` 二元组。
      故 `next-seq` 由会话提供，发射器只管取号。
   2. **恰好一个终态事件**，且它是该 run 的最后一个事件。`finish!` 是终态的唯一
      出口且幂等——「已有终态就什么都不补」这条规则因此由构造保证，而不是靠检查。
   3. 错误值是 canonical error（`model.error`），不是字符串。

   **发射器自身永不抛**（§6.3）：`callbacks/invoke` 的吞异常语义对观察类回调是
   对的，对事件流是错的——吞掉一次发射 = `:seq` 出洞 = 断线续传从此对不上，
   而且不报错。所以这里反过来：sink 抛异常由**发射器**兜住并计数，
   `:seq` 由发射器自己分配（不依赖 sink 的返回值），一次失败不影响后续编号。"
  (:require [taoensso.timbre :as log]))

(set! *warn-on-reflection* true)

(def terminal-types
  "终态事件类型。`:run/paused` 也是终态——暂停是 run 的结束，续跑是**新的 run**
   （docs/agent-runtime-design.md §4.7）。"
  #{:run/finished :run/error :run/cancelled :run/paused})

(defn terminal?
  [event]
  (contains? terminal-types (:type event)))

;;; ============================================================
;;; 发射器
;;; ============================================================

(defn emitter
  "构造一个 run 的发射器。

   - :run-id          本 run 的 id
   - :conversation-id 会话 id
   - :next-seq        (fn [] long)——会话级取号器（契约 1）
   - :sink            (fn [event])——事件出口（runtime 提供：入缓冲 + 扇出）
   - :now             (fn [] ms)，可注入（测试）
   - :transform       (fn [event]) → **事件序列**，可选。**插件的挂点**：可以改写、
                      吞掉、或在一条事件前后插入别的事件（`agui.genui` 就靠它把
                      工具调用翻译成 activity 事件）。每个产出各自取号，所以
                      契约 1 不受影响；**终态事件不过 transform**（契约 2 因此
                      仍由构造保证）；transform 抛异常 = 原样放行并记一条 warn

   返回一个 map（内部状态在 atom 里）。**停止意图挂在这里而不是会话上**：
   `stop!` 与 supersede 改的都是**被停的那个 run** 自己的 holder，否则新 run 一
   重置共享标志，被替换掉的旧 run 收尾时就会把自己标成 error——CopilotKit 的
   `RunFinalizeControl` 修的正是这个（§2.1 第 4 条）。"
  [{:keys [run-id conversation-id next-seq sink now transform]}]
  {:run-id run-id
   :conversation-id conversation-id
   :next-seq next-seq
   :sink sink
   :transform transform
   :now (or now #(System/currentTimeMillis))
   :state (atom {:open-messages {}    ;; message-id -> {:delta? bool}
                 :open-tools    {}    ;; tool-call-id -> {:ended? bool :result? bool}
                 :current-message nil
                 :current-reasoning nil  ;; 开着的思考块（独立 message-id）
                 :terminal nil
                 :any-text? false
                 :drops 0})
   :finalize (atom {:stop-requested? false})})

(defn request-stop!
  "登记**本 run** 的停止意图（`stop!` / supersede 都走这里）。返回是否是首次登记。"
  [em]
  (let [[old _] (swap-vals! (:finalize em) assoc :stop-requested? true)]
    (not (:stop-requested? old))))

(defn stop-requested? [em] (:stop-requested? @(:finalize em)))

(defn terminal-of [em] (:terminal @(:state em)))
(defn text-emitted?
  "本 run 是否已经产出过任何文本（流式 token 或整块补发都算）。"
  [em] (:any-text? @(:state em)))
(defn drops [em] (:drops @(:state em)))

(defn- track!
  "按事件类型维护开集合。纯内部记账，不产生事件。"
  [state {:keys [type message-id tool-call-id]}]
  (case type
    :message/started (update state :open-messages assoc message-id {:delta? false})
    ;; **思维 delta 不进正文消息的开集合**：AG-UI 里思考是独立的 reasoning
    ;; 消息（自己的 message-id），混进来会让 close-open! 给它补一条
    ;; `:message/ended`，前端于是收到一条从没开过的正文消息的结束
    :message/delta   (assoc-in state [:open-messages message-id :delta?] true)
    :message/ended   (update state :open-messages dissoc message-id)
    :tool/started    (update state :open-tools assoc tool-call-id {:ended? false :result? false})
    :tool/ended      (cond-> state
                       (get-in state [:open-tools tool-call-id])
                       (assoc-in [:open-tools tool-call-id :ended?] true))
    :tool/result     (update state :open-tools dissoc tool-call-id)
    state))

(defn- deliver!
  "取号 → 记账 → 出口。**一个事件一个号**，transform 插进来的也一样。"
  [em ev]
  (let [ev (assoc ev :seq ((:next-seq em)) :ts (or (:ts ev) ((:now em))))]
    (swap! (:state em) (fn [st]
                         (cond-> (track! st ev)
                           (terminal? ev) (assoc :terminal (:type ev)))))
    (try
      ((:sink em) ev)
      (catch Throwable t
        (swap! (:state em) update :drops inc)
        (log/warn "agui 事件 sink 抛异常（已兜住，run 不受影响）:" (.getMessage t))))
    ev))

(defn- expand
  "过 `:transform`（若有）。**终态不过**——契约 2「恰好一个终态且在最后」是靠
   `finish!` 的构造保证的，让插件有机会吞掉或改写它，这条保证就没了。"
  [em ev]
  (let [t (:transform em)]
    (if (or (nil? t) (terminal? ev))
      [ev]
      (try
        (vec (t ev))
        (catch Throwable e
          (log/warn "agui 事件 transform 抛异常（已兜住，事件原样放行）:" (.getMessage e))
          [ev])))))

(defn emit!
  "发一个事件。返回**最后一条真正发出的**事件（transform 把它吞了则返回 nil；
   sink 抛异常时仍返回，只计一次 drop）。

   **永不抛**：这是 §6.3 立的不变量，有测试钉住。"
  [em type m]
  (let [base (merge {:type type
                     :run-id (:run-id em)
                     :conversation-id (:conversation-id em)
                     :ts ((:now em))}
                    m)]
    (reduce (fn [_ ev] (deliver! em ev)) nil (expand em base))))

;;; ---- 文本消息：自动开合 ----

(defn begin-message!
  "声明「接下来的 token 属于这条消息」。**不发事件**——真到第一个 token 才发
   `:message/started`，免得没产出文本的一轮凭空多一条空消息。"
  [em message-id]
  (swap! (:state em) assoc :current-message message-id)
  message-id)

(defn current-message [em] (:current-message @(:state em)))

;;; ---- 思考块：独立的一条消息，自动开合 ----

(defn- reasoning-id
  "思考块的 message-id：正文那条的 id 加后缀。

   **必须与正文消息不同 id**——AG-UI 里思考是一条 `role: \"reasoning\"` 的独立
   消息（前端有专门的折叠面板渲染它）。共用 id 会让同一个 id 上既有正文消息又有
   思考消息，客户端只认先到的那种。"
  [mid]
  (str mid "-reasoning"))

(defn end-reasoning!
  "关掉开着的思考块（若有）。**正文 token 一到就得关**：模型「想完了开始说」，
   AG-UI 侧就是思考消息收口、正文消息开始。"
  [em]
  (when-let [rid (:current-reasoning @(:state em))]
    (swap! (:state em) assoc :current-reasoning nil)
    (emit! em :reasoning/ended {:message-id rid})))

(defn emit-token!
  "增量 token。`kind` = `:token`（正文）或 `:reasoning-token`（思维）。

   两条独立的消息，各自开合：正文首个 token 补 `:message/started`，思维首个
   token 补 `:reasoning/started`；**互相到达时把对方收口**（一轮里可以来回切换，
   模型边想边说是常态）。"
  [em kind text]
  (when (and (string? text) (seq text))
    (let [mid (current-message em)]
      (when mid
        (if (= :reasoning-token kind)
          (let [rid (reasoning-id mid)]
            (when-not (= rid (:current-reasoning @(:state em)))
              (swap! (:state em) assoc :current-reasoning rid)
              (emit! em :reasoning/started {:message-id rid}))
            (emit! em :message/thinking {:message-id rid :text text}))
          (do
            (end-reasoning! em)
            (when-not (contains? (:open-messages @(:state em)) mid)
              (emit! em :message/started {:message-id mid :role :assistant}))
            (swap! (:state em) assoc :any-text? true)
            (emit! em :message/delta {:message-id mid :text text})))))))

(defn end-message!
  "关掉当前消息（若开着）。`full-text` 非空且该消息**一个 token 都没出过**时，
   补一条整块 delta——非流式 run 的最终答案就是这么进事件流的。"
  ([em] (end-message! em nil))
  ([em full-text]
   (end-reasoning! em)                 ;; 这一轮说完了，思考块必然也结束了
   (let [{:keys [current-message open-messages]} @(:state em)
         mid current-message]
     (when mid
       (let [open? (contains? open-messages mid)
             delta? (get-in open-messages [mid :delta?])]
         (cond
           (and open? delta?) (emit! em :message/ended {:message-id mid})

           (and (string? full-text) (seq full-text))
           (do (swap! (:state em) assoc :any-text? true)
               (when-not open?
                 (emit! em :message/started {:message-id mid :role :assistant}))
               (emit! em :message/delta {:message-id mid :text full-text})
               (emit! em :message/ended {:message-id mid}))

           open? (emit! em :message/ended {:message-id mid})))
       (swap! (:state em) assoc :current-message nil)))))

(defn ensure-text!
  "收尾兜底：本 run 一个字都没出过、但结果里有文本时，把它补成一条完整消息。

   踩得到的路径：`return-direct`（最终答案由工具结果合成，没有第二次 LLM 文本
   往返），以及任何在 `:on-llm-result` 之外产生最终文本的收尾。"
  [em message-id text]
  (when (and (string? text) (seq text) (not (text-emitted? em)))
    (begin-message! em message-id)
    (end-message! em text)))

;;; ---- 终态：补关 + 唯一出口 ----

(defn- close-open!
  "补关所有开着的块。合成的工具结果说明**为什么**没有真结果——停止与异常两种
   措辞不同（借 CopilotKit `finalizeRunEvents` 的做法）。

   `close-tools?` 为 false 时只关消息、不合成工具结果：**暂停时那个工具是真的
   还悬着**（等人审批 / 等前端执行），给它编一个「未完成」的结果是撒谎，
   而且下一个 run 真结果回来时前端会看到同一个 id 出现两次结果。"
  [em close-tools?]
  (end-reasoning! em)
  (let [{:keys [open-messages open-tools]} @(:state em)
        stopped? (stop-requested? em)]
    (doseq [mid (keys open-messages)]
      (emit! em :message/ended {:message-id mid}))
    (doseq [[tid {:keys [ended?]}] (when close-tools? open-tools)]
      (when-not ended?
        (emit! em :tool/ended {:tool-call-id tid}))
      (emit! em :tool/result
             {:tool-call-id tid
              :content (if stopped?
                         "工具调用未完成：run 已被停止"
                         "工具调用未完成：run 在结束前没有产出结果")
              :synthetic true
              :error {:class (if stopped? :cancelled :provider-error)
                      :message (if stopped? "stop_requested" "missing_terminal_event")}}))))

(defn finish!
  "终态的**唯一出口**，幂等：第一次调用补关开着的块并发终态，之后一律 no-op。

   §4.6 的「已有终态则什么都不补」因此是构造保证的——不是靠在每个发射点检查。"
  [em type m]
  (when-not (terminal-of em)
    (close-open! em (not= :run/paused type))
    (emit! em type m)))
