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
  (:require [im.ttalk.agent.agui.state :as state]
            [taoensso.timbre :as log]))

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
                      吞掉、或在一条事件前后插入别的事件（`copilotkit.genui` 就靠它把
                      工具调用翻译成 activity 事件）。每个产出各自取号，所以
                      契约 1 不受影响；**终态事件不过 transform**（契约 2 因此
                      仍由构造保证）；transform 抛异常 = 原样放行并记一条 warn

   返回一个 map（内部状态在 atom 里）。**停止意图挂在这里而不是会话上**：
   `stop!` 与 supersede 改的都是**被停的那个 run** 自己的 holder，否则新 run 一
   重置共享标志，被替换掉的旧 run 收尾时就会把自己标成 error——CopilotKit 的
   `RunFinalizeControl` 修的正是这个（§2.1 第 4 条）。

   - :tag              可选。**merge 进本发射器发出的每一条事件**（`m` 优先）。
                       子 agent lane 的归属字段就是这么打上去的，见
                       `subagent-emitter`；run 自己的发射器不带 tag。"
  [{:keys [run-id conversation-id next-seq sink now transform tag gate]}]
  {:run-id run-id
   :tag tag
   ;; **取号 / 记账 / 投递 三件事的那把锁**（见 `deliver!`）。调用方给会话锁，
   ;; 不给就现造一个——不给的场合（`run-detached!`、单测手搓的发射器）本来就
   ;; 只有一条线程在发。lane 继承父的这把锁，见 `subagent-emitter`。
   :gate (or gate (Object.))
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
                 ;; 本 run 的 token 用量，一次 LLM 往返一条（`record-usage!`）。
                 ;; **累在根发射器上**：lane 的用量也算这条 run 的账，AG-UI 把
                 ;; `usage` 挂在 `RUN_FINISHED` 上、且是数组，正是为「一轮里换过
                 ;; 模型 / 有子 agent」准备的
                 :usage []
                 ;; 共享状态：本 run 那一份的**运行中副本**，与 `:usage` 一样累在
                 ;; 根发射器上（AG-UI 的 state 是 run 级的，lane 上的 `subagentRunId`
                 ;; 只表示「这条更新是谁产生的」，不是「状态归谁」）。
                 ;; `:state-emitted?` 记「发过快照没有」——delta 必须打在一份客户端
                 ;; 也有的基线上，没发过就得先补一条
                 :agent-state nil
                 :state-emitted? false
                 :drops 0})
   ;; 派生出去的子 agent lane（`subagent-emitter` 自己登记）。**父认子**这一向是
   ;; 收尾要用的：run 终态之前得先把还开着的 lane 收掉，否则客户端看到的是一条
   ;; 没有闭合的 `SUBAGENT_STARTED`（AG-UI 要求每个开过的子代理在 `RUN_FINISHED`
   ;; 前关闭，只有 `RUN_ERROR` 豁免）。`silenced?` 那一向是子认父，两向不重复。
   :children (atom [])
   :finalize (atom {:stop-requested? false})})

(defn request-stop!
  "登记**本 run** 的停止意图（`stop!` / supersede 都走这里）。返回是否是首次登记。"
  [em]
  (let [[old _] (swap-vals! (:finalize em) assoc :stop-requested? true)]
    (not (:stop-requested? old))))

(defn stop-requested? [em] (:stop-requested? @(:finalize em)))

(defn terminal-of [em] (:terminal @(:state em)))

(defn- root-of
  "顺着 `:parent` 找到 run 自己的发射器（lane 的祖先）。"
  [em]
  (if-let [p (:parent em)] (recur p) em))

(defn record-usage!
  "记一次 LLM 往返的用量。**不发事件**——它在终态那条上一次性带出去。

   `m` 形如 `{:model \"…\" :provider \"…\" :input-tokens n :output-tokens k
   :total-tokens t :cache-read-tokens c}`（中立命名，`response/normalize-usage`
   的形状）；译成 AG-UI 的 `inputTokens`/`outputTokens`/… 是 codec 的事。

   全空（provider 没报 usage）就不记——宁可不给这一格，也不给一排 0 让客户端
   把「没数据」画成「用了 0 个 token」。"
  [em m]
  (when (some some? ((juxt :input-tokens :output-tokens :total-tokens) m))
    (swap! (:state (root-of em)) update :usage conj m))
  nil)

(defn usage-of
  "本 run 至今记下的用量条目（根发射器上的那份）。"
  [em] (:usage @(:state (root-of em))))

(defn started?
  "本 lane 是否宣告过自己（发过 `:subagent/started`）。run 自己的发射器恒为 false。"
  [em] (boolean (:started? @(:state em))))
(defn text-emitted?
  "本 run 是否已经产出过任何文本（流式 token 或整块补发都算）。"
  [em] (:any-text? @(:state em)))
(defn drops [em] (:drops @(:state em)))

(defn silenced?
  "本发射器是否该闭嘴了——自己已有终态，或**任一祖先**（父 lane / 父 run）已有终态。

   只有 lane 会有祖先（`:parent`）。递归而不是只看直接父级：两层委派下，run 收口时
   内层 lane 的直接父级（外层 lane）可能还没收口，只看一级就会让内层的事件漏在
   `RUN_FINISHED` 之后——契约 2「终态是该 run 的最后一条」当场破。"
  [em]
  (boolean (or (terminal-of em)
               (when-let [p (:parent em)] (silenced? p)))))

(defn- track!
  "按事件类型维护开集合。纯内部记账，不产生事件。"
  [state {:keys [type message-id tool-call-id parent-message-id]}]
  (case type
    :message/started (update state :open-messages assoc message-id {:delta? false})
    ;; **思维 delta 不进正文消息的开集合**：AG-UI 里思考是独立的 reasoning
    ;; 消息（自己的 message-id），混进来会让 close-open! 给它补一条
    ;; `:message/ended`，前端于是收到一条从没开过的正文消息的结束
    :message/delta   (assoc-in state [:open-messages message-id :delta?] true)
    :message/ended   (update state :open-messages dissoc message-id)
    ;; **记下这次工具调用属于哪条正文消息**：`SUBAGENT_STARTED.parentMessageId`
    ;; 要凭 tool-call-id 反查它（委派工具跑起来时正文消息早已收口，
    ;; `current-message` 已经是 nil，只能靠这份记账）
    :tool/started    (update state :open-tools assoc tool-call-id
                             {:ended? false :result? false
                              :parent-message-id parent-message-id})
    :tool/ended      (cond-> state
                       (get-in state [:open-tools tool-call-id])
                       (assoc-in [:open-tools tool-call-id :ended?] true))
    :tool/result     (update state :open-tools dissoc tool-call-id)
    state))

(defn- deliver!
  "取号 → 记账 → 出口。**一个事件一个号**，transform 插进来的也一样。

   **三件事在同一把锁里做完**（`:gate`）。曾经是取号一次锁、投递另一次锁，中间
   敞着一个窗口：子 agent 的 worker 线程与父 run 的线程可以这样交错——A 取到 9、
   B 取到 10、**B 先投递**、A 再投递。号是对的，到达顺序不是。抓到过的现场里，
   一条 `:subagent/started` 拿着 seq 18 落在 seq 17 的 `:run/finished` **后面**。

   两个后果都不是「顺序不好看」这么轻：
   - SSE 的 `id:` 用的就是这个号，浏览器重连原样回传成 `Last-Event-ID` → `:since`。
     到达乱序会让客户端把水位记高，**后到的小号事件被当成已收，续传时直接跳过**；
   - 契约 2「终态是这条 run 的最后一条」在 AG-UI 那侧是硬要求，终态之后再来一条
     事件，标准客户端按协议违例处理。"
  [em ev]
  (locking (:gate em)
    (let [ev (assoc ev :seq ((:next-seq em)) :ts (or (:ts ev) ((:now em))))]
      (swap! (:state em) (fn [st]
                           (cond-> (track! st ev)
                             (terminal? ev) (assoc :terminal (:type ev)))))
      (try
        ((:sink em) ev)
        (catch Throwable t
          (swap! (:state em) update :drops inc)
          (log/warn "agui 事件 sink 抛异常（已兜住，run 不受影响）:" (.getMessage t))))
      ev)))

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
                    (:tag em)          ;; lane 归属（run 的发射器无 tag，merge 空 map）
                    m)]
    ;; **`expand` 也在锁里**：lane 的终态守卫（`silenced?`）就长在它的 transform 上，
    ;; 判完再投递，中间不能让父 run 插进来收口——否则「查的时候父还没终态、投的
    ;; 时候已经有了」，事件照样漏在终态之后。锁是可重入的，`deliver!` 再拿一次无妨。
    (locking (:gate em)
      (reduce (fn [_ ev] (deliver! em ev)) nil (expand em base)))))

;;; ---- 共享状态：一条快照 + 一串增量 ----

(defn agent-state
  "本 run 的共享状态**当前值**（服务端这份）。"
  [em] (:agent-state @(:state (root-of em))))

(defn seed-state!
  "把 `RunAgentInput.state` 作为本 run 的初始状态**装上但不发**。

   客户端本来就有这份（是它发上来的），再发一遍是噪声；装上是为了后续 delta
   对着**真实基线**算，而不是对着 nil 算。"
  [em v]
  (when (some? v)
    (swap! (:state (root-of em)) assoc :agent-state v))
  nil)

(defn emit-state-snapshot!
  "整份替换 + 发 `:state/snapshot`。

   ⚠️ `v` 必须是**状态对象本身，不是 JSON 字符串**。发成字符串之后客户端那边是
   一坨转义引号，更要命的是**后续每条 delta 都打不上**（patch 打在字符串上），
   而上游客户端不防这一手（`state = snapshot` 直接替换）。这里做不了类型强制
   ——JSON 里字符串也是合法值——但调用方（`agui.tools` 的写状态工具）会先解一层。"
  [em v]
  (swap! (:state (root-of em)) assoc :agent-state v :state-emitted? true)
  (emit! em :state/snapshot {:state v}))

(defn emit-state-delta!
  "规范化 + 发 `:state/delta`（RFC 6902 op 数组）。返回**实际发出去的** op 数组。

   两件在这里解掉的事，都是**一个字都不报**的静默失败：

   1. **没发过快照就发 delta** —— 客户端没有基线，patch 无处可打。所以首条 delta
      之前先补一条当前状态的快照。

      ⛔ **「没有状态」补的是 `{}`，不是跳过 —— 这一条是刻意比上游严，别「对齐
      上游」把它改回去。** 上游那个守卫逐字是
      `!hasEmittedState && initialState !== undefined`
      （`CopilotKit/packages/runtime/src/agent/state-delta.ts`
      的 `createStateEventNormalizer`）：`state` 字段**缺席**时它不补快照。上游
      自己撞不到，是因为它的 `AbstractAgent` 永远发 `state ?? {}`；而我们这条路
      客户端可以真的不带这个字段。跳过的后果不是少一条快照，是**下面第 2 条守卫
      一起失灵**——对着 nil 算，连 `add /todos []` 这条初始化 op 自己都打不上，
      于是裸 delta 照样发出去，客户端 `console.warn` 一声就把它丢了。
      我们把「字段缺席」与 `{}` 当同一件事（客户端的 state 缺省本来就是 `{}`）。
      下游在 2026-09-04 的回执里专门记了这一格，说另一台参照实现照抄上游的条件、
      那条路上仍会发出打不上的裸 delta；
   2. **`add /x/-` 而 `/x` 还不存在** —— 由 `state/normalize-ops` 在前面插一条
      `add /x []`（见那个 ns 的注释）。

   服务端这边同步应用一遍，下一条 delta 才是对着真实值算的。"
  [em ops]
  (let [root (root-of em)
        {:keys [state-emitted? agent-state]} @(:state root)
        base (or agent-state {})
        {ops' :ops next-state :state} (state/normalize-ops ops base)]
    (when-not state-emitted?
      (emit-state-snapshot! em base))
    (swap! (:state root) assoc :agent-state next-state :state-emitted? true)
    (emit! em :state/delta {:delta (vec ops')})
    (vec ops')))

;;; ---- 文本消息：自动开合 ----

(defn begin-message!
  "声明「接下来的 token 属于这条消息」。**不发事件**——真到第一个 token 才发
   `:message/started`，免得没产出文本的一轮凭空多一条空消息。"
  [em message-id]
  (swap! (:state em) assoc :current-message message-id)
  message-id)

(defn current-message [em] (:current-message @(:state em)))

(defn tool-parent-message
  "某次工具调用所属的正文消息 id（`:tool/started` 时记下的）。

   工具还开着（没到 `:tool/result`）才查得到——子 agent 的 `start!` 正是在工具
   执行期间调的，所以够用。"
  [em tool-call-id]
  (get-in @(:state em) [:open-tools tool-call-id :parent-message-id]))

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
              :content (let [noun (if (:tag em) "子 agent" "run")]
                         (if stopped?
                           (str "工具调用未完成：" noun " 已被停止")
                           (str "工具调用未完成：" noun " 在结束前没有产出结果")))
              :synthetic true
              :error {:class (if stopped? :cancelled :provider-error)
                      :message (if stopped? "stop_requested" "missing_terminal_event")}}))))

(declare finish-subagent!)

(defn- close-lanes!
  "把还开着的子 agent lane 收掉。**必须排在自己的终态之前**。

   为什么非做不可：lane 的事件受 `silenced?` 管——父一旦有终态，lane 后面的
   `SUBAGENT_FINISHED` 会在取号前被整条吞掉（那是契约 2 该有的行为）。于是
   `:supersede` 掐掉一轮、而子 agent 还在跑时，客户端收到的是一条**永远不闭合的**
   `SUBAGENT_STARTED`——AG-UI 明写每个开过的子代理必须在 `RUN_FINISHED` 前关闭
   （只有 `RUN_ERROR` 豁免，异常中断是预期行为）。正路是收口前主动关，不是靠静音。

   停止意图取自**父**（`stop-requested?` 是 run 级的）：被停掉就是 `killed`，
   否则是「父先结束了」。递归：内层 lane 先于外层关。"
  [em]
  (doseq [lane @(:children em)]
    ;; **只关宣告过的 lane**：没发过 `SUBAGENT_STARTED` 的，客户端根本不知道它
    ;; 存在，补一条收尾等于凭空冒出一个未声明的子代理（协议允许「无生命周期
    ;; 事件的归属」，但反过来发个没有开场的收尾就是纯噪声）。
    (when (and (started? lane) (not (terminal-of lane)))
      (finish-subagent! lane
                        (if (stop-requested? em)
                          {:outcome :killed}
                          {:error {:class :provider-error
                                   :message "子 agent 未收尾：父 run 先结束了"}})))))

(defn finish!
  "终态的**唯一出口**，幂等：第一次调用补关开着的块并发终态，之后一律 no-op。

   §4.6 的「已有终态则什么都不补」因此是构造保证的——不是靠在每个发射点检查。

   顺序：**先收子 lane，再关自己的块，最后发终态**。lane 的收尾属于父 run 终态
   之前的事件；反过来先发终态，lane 就被静音了（见 `close-lanes!`）。"
  [em type m]
  (when-not (terminal-of em)
    (close-lanes! em)
    (close-open! em (not= :run/paused type))
    ;; 用量搭终态这班车走（AG-UI 只在 `RUN_FINISHED` / `RUN_ERROR` 上有这一位）。
    ;; **排在 `close-lanes!` 之后**：子 agent 的那几笔账也才算进来
    (emit! em type (let [u (usage-of em)]
                     (cond-> m (seq u) (assoc :usage u))))))

;;; ============================================================
;;; 子 agent lane
;;; ============================================================
;;;
;;; 一条 lane = 一个子 agent 的一次运行（docs/subagent-event-attribution-design.md）。
;;; 它**寄生在父 run 的事件流里**：共用会话取号器与 sink，但自带一份状态。
;;;
;;; 三条契约怎么继续成立：
;;;   1. `:seq` 无洞 —— 共用 `:next-seq`（会话锁在 runtime 那侧）；
;;;   2. 恰好一个终态且在最后 —— lane **永不发 `:run/*`**，且父 run 一旦终态，
;;;      lane 的事件在 **`:transform` 里**（`deliver!` 取号之前）被整条吞掉。
;;;      **不能在 sink 里丢**：那时号已经取过，会留下一个永远补不回来的 seq 洞；
;;;   3. 永不抛 —— 沿用同一条 `deliver!` 路径。
;;;
;;; 第四条是 lane 自己的：**一条 lane 一个发射器实例**。10 路并发共用一个实例会
;;; 共用 `:current-message`，token 于是交错进同一条消息（上游实测过这个现象）。

(defn subagent-emitter
  "给 `parent`（run 的发射器，或另一条 lane）派生一条 lane 的发射器。

   `:transform` 槽被终态守卫占用，所以 lane **不接插件**——插件 transform 是按 run
   现造的有状态对象，多条 lane 共用一个实例，它记的「这一轮见过哪些 tool-call」会被
   交错的 lane 污染。"
  [parent {:keys [subagent-run-id]}]
  (let [silenced (atom 0)
        parent-tag (get-in parent [:tag :subagent-run-id])
        lane (assoc (emitter
                     {:run-id          (:run-id parent)          ;; lane 仍属于父 run
                      :conversation-id (:conversation-id parent)
                      :next-seq        (:next-seq parent)        ;; 契约 1
                      :sink            (:sink parent)
                      :now             (:now parent)
                      :transform       (fn [ev]
                                         (if (silenced? parent)
                                           (do (swap! silenced inc) [])
                                           [ev]))
                      ;; **与父共用同一把锁**：契约 2 的守卫要跨父子原子，
                      ;; 各拿各的锁等于没锁
                      :gate            (:gate parent)
                      :tag             (cond-> {:subagent-run-id subagent-run-id}
                                         parent-tag (assoc :parent-subagent-run-id parent-tag))})
                    :parent parent
                    :silenced silenced)]
    ;; **父认子**：收口时要凭这份名单把还开着的 lane 关掉（见 `close-lanes!`）
    (swap! (:children parent) conj lane)
    lane))

(defn lane-id
  "本发射器的 lane id；run 自己的发射器为 nil。消息 id 要靠它分道（契约 4）。"
  [em] (get-in em [:tag :subagent-run-id]))

(defn silenced-count
  "本 lane 因父 run（或祖先 lane）已终态而被吞掉的事件数。

   与 `drops`（sink 抛异常）分开计：一个是「出口坏了」，一个是「说话的时机过了」，
   排查时是两码事。"
  [em] (some-> (:silenced em) deref))

(defn start-subagent!
  "lane 的开场。`:subagent-run-id` 由 tag 自动带上，这里只补 lane 特有的字段。

   `parent-tool-call-id` 由委派工具从 ToolContext 的 `:tool/call-id` 取
   （`react/invoke-one` 钉的）。同一批里几个委派并发时，这是唯一能把 lane 挂回
   正确那张工具卡片的依据——从「开着的工具」里猜，多路并发下必然挂错。
   协议里它是可选字段，取不到就空着。"
  [em {:keys [name task parent-tool-call-id parent-message-id]}]
  (swap! (:state em) assoc :started? true)
  (emit! em :subagent/started
         (cond-> {}
           name                (assoc :name name)
           task                (assoc :task task)
           parent-tool-call-id (assoc :parent-tool-call-id parent-tool-call-id)
           ;; 包住那次工具调用的正文消息——协议里可选，取不到就空着。
           ;; 有它前端才能把子 agent 的折叠组挂进正确那条消息（AG-UI 还要求
           ;; 工具调用的归属与其 parentMessageId 的归属一致）
           parent-message-id   (assoc :parent-message-id parent-message-id))))

(defn finish-subagent!
  "lane 收尾的**唯一出口**，幂等：补关 lane 自己开着的块，再发一条收尾事件。

   `m` = `{:outcome :success | :killed | :timeout :result <any>}` 或
   `{:error <canonical error>}`。有 `:error` 走 `:subagent/error`，否则走
   `:subagent/finished`；`:result` 是协议里那个可选的完成载荷
   （`SUBAGENT_FINISHED.result`，语义同 `RUN_FINISHED.result`）。

   **这两个类型都不进 `terminal-types`**：那个集合是 run 的终态，`expand` 靠它决定
   「终态不过 transform」。lane 的收尾必须过 transform——它得受父 run 终态守卫的管，
   否则父 run 收口之后还能补出一条 `SUBAGENT_FINISHED`。这里的幂等靠自己标记。"
  [em {:keys [outcome error result interrupt-ids]}]
  (when-not (terminal-of em)
    (close-lanes! em)                  ;; 嵌套：内层 lane 先于外层收口
    (close-open! em true)
    (if error
      (emit! em :subagent/error {:error error})
      (emit! em :subagent/finished (cond-> {:outcome (or outcome :success)}
                                     (some? result) (assoc :result result)
                                     (seq interrupt-ids) (assoc :interrupt-ids
                                                                (vec interrupt-ids)))))
    (swap! (:state em) assoc :terminal (if error :subagent/error :subagent/finished))
    nil))
