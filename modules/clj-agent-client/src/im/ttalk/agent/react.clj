(ns im.ttalk.agent.react
  "工具调用循环（从 kernel 下沉而来）

   kernel 只提供原语 invoke-chat / invoke-tool；'驱动 LLM↔工具直到文本或暂停'
   这套策略(含 max-iterations / gate 暂停 / resume / 悬空 tool_use 自愈)属编排层，
   放在 simpleagent。每轮只向 invoke-chat 传 delta，由 kernel 的 memory filter
   按 conversation-id 拼出完整历史。

   store 显式传入(kernel 不再持有 memory)：用于 heal 与临时会话清理；
   与 kernel 上挂载的 memory-filter 必须是同一个 store 实例。

   callbacks 独立于 kernel filter：:on-llm-call/:on-llm-result/:on-tool-result
   在循环关键节点直接触发，不走 filter 链。gate 评估结果缓存，确保每个工具调用
   恰好触发一次观察回调（不重复）。"
  (:require [clojure.string]
            [im.ttalk.agent.advisor :as filters]
            [im.ttalk.agent.callbacks :as cb]
            [im.ttalk.agent.kernel :as kernel]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.tool :as tool]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.streaming :as streaming]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent Callable ExecutorService Executors Future]))

(set! *warn-on-reflection* true)

(def ^:private default-max-iterations
  "工具调用循环默认最大次数"
  10)

(defn- filter-tools-by-tags
  "根据 tags 过滤 kernel 的 tool schemas（OR 逻辑；均 nil 时返回全部）。"
  [kernel {:keys [tags exclude-tags]}]
  (let [all-tools (:tools kernel)
        tool-vars-map (:tool-vars kernel)
        tags-set (when tags (set tags))
        exclude-tags-set (when exclude-tags (set exclude-tags))]
    (if (and (nil? tags-set) (nil? exclude-tags-set))
      all-tools
      (vec (for [tool-schema all-tools
                 :let [fn-name (keyword (:name tool-schema))
                       v (get tool-vars-map fn-name)]
                 :when (and (or (nil? v)  ;; inline tools（无 var）始终保留
                                (and (or (nil? tags-set) (tool/has-any-tag? v tags-set))
                                     (or (nil? exclude-tags-set) (not (tool/has-any-tag? v exclude-tags-set))))))]
             tool-schema)))))

(defn- build-chat-opts
  "从 invoke/resume 的 opts 构建传给 invoke-chat 的 chat 选项
   （system-prompt 合并 + 工具 schema 过滤 + tool-choice）。"
  [kernel opts]
  (let [system-prompts (or (:system-prompts opts) [])
        sp-str (when (seq system-prompts)
                 (->> system-prompts (map :content) (clojure.string/join "\n")))
        tool-schemas (filter-tools-by-tags kernel opts)
        tool-choice (or (:tool-choice opts) :auto)]
    (cond-> {:tools tool-schemas :tool-choice tool-choice}
      sp-str (assoc :system-prompt sp-str)
      (:on-token opts) (assoc :on-token (:on-token opts))
      (:cancel-token opts) (assoc :cancel-token (:cancel-token opts)))))

(def ^:private tool-executor
  "工具批次 map 阶段的共享虚拟线程 executor（与 subagent/manager 同款先例）。"
  (delay (Executors/newVirtualThreadPerTaskExecutor)))

(defn- invoke-with-retry
  "调用 kernel/invoke-tool；仅当错误为 :transient 且工具声明 :retry 时
   按策略指数退避重试（幂等前提；重试会重跑整条 tool filter 链）。"
  [kernel fn-key args tool-context]
  (let [policy (kernel/retry-policy kernel fn-key)
        max-r  (long (or (:max-retries policy) 0))]
    (loop [attempt 0]
      (let [resp (kernel/invoke-tool kernel fn-key args tool-context)]
        (if (and policy
                 (= :transient (get-in resp [:error :class]))
                 (< attempt max-r))
          (do (Thread/sleep (long (* (:initial-delay-ms policy)
                                     (Math/pow 2 attempt))))
              (recur (inc attempt)))
          resp)))))

(defn- invoke-one
  "执行单个 tool call（含 gate reject 分支）。异常折为错误结果，不逃逸。
   返回 {:tc tc :value str (:writes {k v}) (:error {:class :message})
         (:rejected? true)}——
   错误/拒绝的结果没有 :writes，reduce 时自动跳过（单工具事务性）；
   :error 携带故障类别，供屏障处策略路由（S2）。"
  [kernel tc decision tool-context on-tool-result]
  (cond
    (= :reject decision)
    {:tc tc :value "已拒绝执行" :rejected? true}

    ;; 拒绝带理由：模型直接拿到原因，省一轮干猜
    (and (map? decision) (contains? decision :reject))
    {:tc tc :value (str "已拒绝执行：" (:reject decision)) :rejected? true}

    ;; 答复即结果（ask-user 语义）：不执行工具，载荷直接作为工具结果回模型
    (and (map? decision) (contains? decision :reply))
    {:tc tc :value (str (:reply decision)) :replied? true}

    :else
    (let [fn-key (keyword (:name tc))
          {:keys [value writes error]}
          (try (invoke-with-retry kernel fn-key (:args tc) tool-context)
               (catch Exception e
                 (let [m (or (not-empty (.getMessage e)) (.getName (class e)))]
                   {:value (str "错误: " m)
                    :error {:class :semantic :message m}})))]
      ;; 完成即触发（并行下批内顺序不确定；需确定顺序请读 :records）
      (when on-tool-result
        (try (on-tool-result (name fn-key) value) (catch Throwable _ nil)))
      (cond-> {:tc tc :value value}
        (seq writes) (assoc :writes writes)
        error        (assoc :error error)))))

(defn- return-direct-batch?
  "整批是否 return-direct。与 Spring AI 一致取**全体**语义（allMatch）：
   混批（部分声明）时继续正常回灌 LLM——「一半直接返回、一半交给模型」
   没有自洽解释。"
  [kernel calls]
  (and (seq calls)
       (every? #(kernel/return-direct-tool? kernel (:name %)) calls)))

(defn- direct-response
  "把工具结果消息拼成最终响应（return-direct 时不再问 LLM，结果即答案）。"
  [messages]
  (response/make-response
    :text (->> messages (keep :content) (clojure.string/join "\n"))
    :finish-reason :stop))

(defn execute-batch
  "MapReduce 执行一批工具调用（设计见 docs/agent-loop-concurrency-design.md §9）。

   map：每个调用在虚拟线程执行，全部拿同一份轮初 tool-context 快照
   （同批工具互相看不到对方的写）。批内任一工具声明 :serial 时整批退化为
   按序执行（副作用顺序可预期；状态语义不变，仍是快照 + 屏障折叠）。

   reduce：屏障收齐后，各工具的 :writes 按 tool-call 原始序经 kernel
   :state-slots 的槽级 reducer 折叠进 context（未声明槽默认 last-writer；
   同批多工具写同一未声明槽记 warn）。messages/records 按原始序排回。

   gate: (fn [tool-call] -> 决策)，nil 视为全 :proceed；批前串行预判
   （审批可交互，绝不并发）。决策词汇：
   - :proceed          执行
   - :reject           跳过，结果「已拒绝执行」
   - {:reject 理由}    跳过，结果「已拒绝执行：<理由>」（模型直接拿到原因）
   - {:reply 结果}     不执行，载荷即工具结果（ask-user 语义）

   on-tool-result: (fn [tool-name result-str])，每个实际执行的工具恰好触发
   一次（:reject / {:reply} 不触发）；并行下批内触发顺序不确定。

   返回: {:messages [...] :records [...] :context 新ctx
          :errors [{:id :name :class :message :tc} ...]}
   :errors 为本批失败调用及其故障类别（重试耗尽后仍失败的才出现在此），
   供屏障处策略路由（环境类 → 暂停等人，见 run-tool-loop）。"
  ([kernel tool-calls gate tool-context init-records]
   (execute-batch kernel tool-calls gate tool-context init-records nil))
  ([kernel tool-calls gate tool-context init-records on-tool-result]
   (let [;; ① gate 预判：串行、按原序（审批可能交互）
         decisions (mapv #(if gate (gate %) :proceed) tool-calls)
         serial?   (boolean (some #(kernel/serial-tool? kernel (:name %)) tool-calls))
         ;; ②③④ map + 屏障：并行（缺省）或整批退化按序
         results   (if (or serial? (<= (count tool-calls) 1))
                     (mapv (fn [tc d] (invoke-one kernel tc d tool-context on-tool-result))
                           tool-calls decisions)
                     (let [futs (mapv (fn [tc d]
                                        (.submit ^ExecutorService @tool-executor
                                                 ^Callable
                                                 (fn [] (invoke-one kernel tc d tool-context
                                                                    on-tool-result))))
                                      tool-calls decisions)]
                       (mapv (fn [^Future f] (.get f)) futs)))
         ;; ⑤ reduce：writes 按原始序折叠（collect-all——错误/拒绝无 :writes）
         {:keys [context conflicts]}
         (ctx/apply-writes tool-context
                           (keep :writes results)
                           (get-in kernel [:settings :state-slots]))]
     (when (seq conflicts)
       (log/warn "同批多个工具写入未声明 reducer 的槽（last-writer 按调用序生效）:"
                 conflicts))
     ;; ⑥ messages/records 按原始序排回（:writes 随消息进历史——event-sourcing
     ;; 元数据，wire 层不发给 LLM；失败/被拒调用无 writes，历史中自然缺席）
     {:messages (mapv (fn [{:keys [tc value writes]}]
                        (msg/tool-result (:id tc) (:name tc) value writes))
                      results)
      :records  (into init-records
                      (map (fn [{:keys [tc value rejected?]}]
                             {:name   (keyword (:name tc))
                              :args   (:args tc)
                              :result (if rejected? :rejected value)}))
                      results)
      :errors   (into []
                      (keep (fn [{:keys [tc error]}]
                              (when error
                                {:id      (:id tc)
                                 :name    (:name tc)
                                 :class   (:class error)
                                 :message (:message error)
                                 :tc      tc})))
                      results)
      :context  context})))

(defn run-tools
  "execute-batch 的无 gate 特例：全部执行。供外部手搓工具循环使用。"
  [kernel tool-calls tool-context]
  (execute-batch kernel tool-calls nil tool-context []))

(defn- dangling-tool-call-ids
  "history 中出现在 assistant :tool-calls 里、但没有对应 tool 结果消息的 {:id :name}。"
  [history]
  (let [paired (into #{} (keep :tool-call-id) history)]
    (for [m history :when (= :assistant (:role m))
          {:keys [id name]} (:tool-calls m)
          :when (not (paired id))]
      {:id id :name name})))

(defn heal-dangling-tool-calls!
  "开新一轮前的自愈：为 conv-id 历史里的悬空 tool_use 补「已取消」中立结果，使会话重新配平。
   无悬空则 no-op。store 即挂载于 kernel 的同一 memory store。
   store 为 nil 时（如 :memory false 的子 Agent）直接跳过，无需自愈。"
  [store conv-id]
  (when (and store conv-id)
    (let [dangling (dangling-tool-call-ids (memory/mem-get store conv-id))]
      (when (seq dangling)
        (memory/mem-add store conv-id
                        (mapv #(msg/tool-result (:id %) (:name %)
                                                "已取消（上一轮工具调用未审批/未恢复）")
                              dangling))))))

(defn- env-pause
  "构造环境类错误的暂停返回值（屏障处策略钩子，S2）。
   批次已执行完且结果/写折叠均已落定；暂停发生在「结果交给模型之前」。
   resume 决策：:retry（环境已修复，重跑失败调用）| :proceed（错误结果交给模型）。"
  [env-errors batch-messages records remaining tctx]
  {:status :paused
   :pause-reason (str "环境类错误，需人工介入: "
                      (clojure.string/join "; "
                        (map #(str (:name %) ": " (:message %)) env-errors)))
   :loop-state {:phase          :env-retry
                :batch-messages batch-messages
                :failed-calls   (mapv :tc env-errors)
                :remaining      remaining
                :records        records}
   :pending-tool (let [e (first env-errors)]
                   {:name (:name e) :args (:args (:tc e)) :tool-call (:tc e)})
   :tool-calls-made records
   :tool-context tctx})

(defn- run-tool-loop
  "统一工具循环：从 delta 起步，驱动 LLM ↔ 工具，直到文本响应或暂停。

   callbacks 携带观察回调（:on-llm-call/:on-llm-result/:on-tool-result）和元数据
   （:metadata）。gate 评估结果按 tool-call :id 缓存，确保每个工具调用恰好触发
   一次（不因 pause 检测的两阶段逻辑而重复）。

   policy: {:on-env-error :pause|:proceed}——屏障处发现 :environment 类错误时
   暂停等人（HITL）还是照常把错误结果交给模型（缺省 :proceed）。

   返回:
   {:status :completed :response r :tool-context c :tool-calls-made [...]}
   {:status :paused    :loop-state {...} :pending-tool {...} :tool-context c}
   （暂停两种 phase：审批（工具未执行）与 :env-retry（批已执行、环境类失败））"
  [kernel delta remaining records tctx gate chat-opts callbacks policy]
  (let [token (:cancel-token chat-opts)
        meta (or (:metadata callbacks) {})
        on-tool-result (:on-tool-result callbacks)]
    (loop [delta delta, remaining remaining, records records, tctx tctx]
      (if (streaming/cancelled? token)
        {:status :cancelled :tool-context tctx :tool-calls-made records}
        (do
          ;; 每次 LLM 调用前触发（观察用，不影响流程）
          (cb/invoke callbacks :on-llm-call delta meta)
          (let [{:keys [response context]}
                (if (:on-token chat-opts)
                  (binding [streaming/*register-cancel* (when token (streaming/binding-register token))]
                    (kernel/invoke-chat-stream kernel delta (assoc chat-opts :context tctx)))
                  (kernel/invoke-chat kernel delta (assoc chat-opts :context tctx)))
                tctx context
                calls (response/response-tool-calls response)]
            ;; 每次 LLM 返回后触发（观察用，不影响流程）
            (cb/invoke callbacks :on-llm-result response meta)
            (cond
              (streaming/cancelled? token)
              {:status :cancelled :response response
               :tool-context tctx :tool-calls-made records}

              (empty? calls)
              {:status :completed :response response
               :tool-context tctx :tool-calls-made records}

              ;; 续跑判据（对标 Spring AI ToolExecutionEligibilityChecker）：
              ;; 判据说停 → 带 tool-call 的响应也按最终答案收尾（工具不执行）
              (not ((or (get-in kernel [:settings :eligibility-fn]) (constantly true))
                    response tctx))
              {:status :completed :response response
               :tool-context tctx :tool-calls-made records}

              ;; gate 评估缓存：按 :id 存结果，确保每个 tool-call 恰好评估一次。
              ;; 这修正了原来 some+filter 两阶段导致的双重触发问题，
              ;; 使 on-tool-call 集成在 gate 中时能保证「每工具恰好一次」语义。
              :else
              (let [gate-cache (when gate
                                 (into {} (mapv #(vector (:id %) (gate %)) calls)))
                    cached-gate (when gate-cache
                                  (fn [tc] (get gate-cache (:id tc) :proceed)))
                    paused-call (when gate-cache
                                  (first (filter #(= :pause (get gate-cache (:id %) :proceed)) calls)))]
                (cond
                  (some? paused-call)
                  {:status :paused
                   :pause-reason (str "需要审批: " (:name paused-call))
                   :loop-state {:tool-calls calls :remaining (dec remaining)
                                :records records
                                :pending-id (:id paused-call)}   ;; resume payload 定位用
                   :pending-tool {:name (:name paused-call)
                                  :args (:args paused-call)
                                  :tool-call paused-call}
                   :tool-calls-made records
                   :tool-context tctx}

                  (<= remaining 0)
                  (throw (ex-info "工具调用循环次数超过上限（max-iterations）"
                                  {:reason :max-iterations-exceeded
                                   :tool-call-count (count records)
                                   :tool-calls-made records}))

                  :else
                  (let [{:keys [messages records context errors]}
                        (execute-batch kernel calls cached-gate tctx records on-tool-result)
                        env-errors (when (= :pause (:on-env-error policy))
                                     (filterv #(= :environment (:class %)) errors))]
                    (cond
                      ;; 屏障处策略钩子：环境类失败 → 带一致快照暂停等人
                      (seq env-errors)
                      (env-pause env-errors messages records (dec remaining) context)

                      ;; return direct：结果即最终答案，不再回灌 LLM。
                      ;; :direct-messages 交给调用方落库——正常路径下工具结果是靠
                      ;; **下一次 invoke-chat** 经 memory filter 落库的，这里没有
                      ;; 下一次，不补落库就会在历史里留下悬空 tool_use。
                      (return-direct-batch? kernel calls)
                      {:status :completed
                       :response (direct-response messages)
                       :tool-context context
                       :tool-calls-made records
                       :direct-messages messages
                       :return-direct true}

                      :else
                      (recur messages (dec remaining) records context))))))))))))

(defn- kernel-memory-store
  "取 kernel 上 memory filter 绑定的 store（filter 刻意暴露 :store）。
   没挂 memory filter → nil（此时工具结果本就不落库，直接返回也无需补）。"
  [kernel]
  (some #(when (= :memory (:name %)) (:store %)) (:filters kernel)))

(defn- persist-direct-messages!
  "return-direct 收尾时补落库工具结果消息。

   正常路径下，某轮工具结果是靠**下一轮 invoke-chat** 经 memory filter 落库的
   （每轮只向 invoke-chat 传 delta）。return-direct 没有下一轮，若不补这一刀，
   历史里就只剩 assistant(tool_calls) 而无对应结果——下个 turn 的
   heal-dangling-tool-calls! 会把它整条摘掉，于是「用户问了、也答了」在历史里
   双双蒸发。

   落库形状 = 正常路径的形状（user → assistant(tool_calls) → tool(results)），
   与 Spring AI returnDirect 的 history 一致：不额外造一条 assistant 消息。

   幂等：只在结果带 :direct-messages 时落一次，随后摘掉该键——turn filter
   递归重入时不会重复落库。无 memory filter / 无 conv-id 时跳过。"
  [result kernel conv-id]
  (if-let [ms (:direct-messages result)]
    (let [store (kernel-memory-store kernel)]
      (when (and store conv-id)
        (memory/mem-add store conv-id (mapv msg/normalize ms)))
      (dissoc result :direct-messages))
    result))

(defn invoke
  "工具调用循环主入口（统一循环）。

   参数:
   - kernel:   Kernel 实例（需注册 LLM 服务）
   - store:    ChatMemory store（与 kernel 上 memory-filter 同一实例；用于 heal/临时清理）
   - messages: 本轮新消息（中立消息）
   - opts:     {:context :system-prompts :max-iterations :tool-choice :tool-gate :tags/:exclude-tags
               :on-env-error :pause|:proceed（缺省 :proceed——环境类工具失败照常交给模型；
                              :pause 时在屏障处暂停等人，resume :retry/:proceed 续跑）
               :callbacks  回调 map（:on-llm-call/:on-llm-result/:on-tool-result/:metadata 等）}

   返回:
   {:status :completed :response r :tool-context c :tool-calls-made [...]}
   {:status :paused    :loop-state {...} :pending-tool {...} :tool-context c}"
  [kernel store messages opts]
  (when-not (:service kernel)
    (throw (ex-info "Kernel 未配置 LLM 服务（请在 build-kernel 中提供 :service）"
                    {:kernel-keys (keys kernel)})))
  (let [base-ctx (or (:context opts) (ctx/create))
        ephemeral? (nil? (ctx/conversation-id base-ctx))
        conv-id (or (ctx/conversation-id base-ctx)
                    (str "conv-" (java.util.UUID/randomUUID)))
        init-ctx (ctx/with-conversation-id base-ctx conv-id)
        max-iter (or (:max-iterations opts)
                     (get-in kernel [:settings :max-tool-iterations])
                     default-max-iterations)
        callbacks (or (:callbacks opts) {})
        _ (heal-dangling-tool-calls! store conv-id)]
    (try
      ;; turn 链：包整个工具循环（每 turn 一次；filter 可改写 :messages/:context、
      ;; 递归重入——每次重入获得全新 max-iterations 预算）。设计见 §14。
      (let [turn-terminal (fn [treq]
                            (-> (run-tool-loop kernel
                                               (mapv msg/normalize (:messages treq))
                                               max-iter [] (or (:context treq) init-ctx)
                                               (:tool-gate opts)
                                               (build-chat-opts kernel opts)
                                               callbacks
                                               {:on-env-error (or (:on-env-error opts) :proceed)})
                                (persist-direct-messages! kernel conv-id)))
            turn-chain (filters/build-chain (keep :turn (:filters kernel)) turn-terminal)
            result (turn-chain {:messages messages :context init-ctx})]
        (when (and ephemeral? store (= :completed (:status result)))
          (memory/mem-clear store conv-id))
        result)
      (catch Throwable t
        (when (and ephemeral? store) (memory/mem-clear store conv-id))
        (throw t)))))

(defn- replace-tool-results
  "把重试产出的 tool 结果消息按 :tool-call-id 替换进原批次消息（保持原序）。"
  [orig-messages retry-messages]
  (let [by-id (into {} (map (juxt :tool-call-id identity)) retry-messages)]
    (mapv #(or (get by-id (:tool-call-id %)) %) orig-messages)))

(defn- resume-env
  "从 :env-retry 暂停恢复（批已执行、环境类失败）。
   decision :retry   → 环境已修复：重跑失败调用，结果按 id 替换进原批次消息；
                       若仍有环境类失败则再次暂停（同 phase）。
   decision 其他     → :proceed：原错误结果照常交给模型。"
  [kernel {:keys [batch-messages failed-calls remaining records]} decision opts]
  (let [tctx (or (:context opts) (ctx/create))
        gate (:tool-gate opts)
        callbacks (or (:callbacks opts) {})
        policy {:on-env-error (or (:on-env-error opts) :pause)}
        chat-opts (build-chat-opts kernel opts)]
    (if (= :retry decision)
      (let [{:keys [messages records context errors]}
            (execute-batch kernel failed-calls nil tctx records
                           (:on-tool-result callbacks))
            merged (replace-tool-results batch-messages messages)
            env-errors (when (= :pause (:on-env-error policy))
                         (filterv #(= :environment (:class %)) errors))]
        (if (seq env-errors)
          (env-pause env-errors merged records remaining context)
          (run-tool-loop kernel merged remaining records context
                         gate chat-opts callbacks policy)))
      (run-tool-loop kernel batch-messages remaining records tctx
                     gate chat-opts callbacks policy))))

(declare ^:private resume-approval)

(defn resume
  "从 paused 的 loop-state 继续工具循环。

   两种暂停 phase：
   - 审批暂停（缺省，工具未执行）：decision :approved（强制全部执行）
     | :rejected（gate 决定，敏感→拒绝）| :reply（不执行 pending 工具，
     payload :message 直接作为其结果——ask-user 语义）
   - :env-retry（批已执行、环境类失败）：decision :retry（环境已修复，重跑
     失败调用）| :proceed（错误结果交给模型）

   opts :payload（审批 phase 可选，携带用户答复）：
   - :approved + {:args 新参数}   → pending 工具以替换后的参数执行（编辑后批准）
   - :rejected + {:message 理由}  → 结果「已拒绝执行：<理由>」（模型直接拿到原因）
   - :reply    + {:message 答复}  → 答复即 pending 工具的结果（必填）

   参数:
   - kernel:     Kernel 实例
   - loop-state: invoke 返回的 :loop-state
   - decision:   见上
   - opts:       同 invoke（须含带 conversation-id 的 :context 以接续历史；
                 可含 :payload）

   turn 链语义：resume 同样经过 :turn 洋葱——首次进入终端执行「暂停 turn 的
   延续」（消费 loop-state）；turn filter 的递归重入（如校验反馈）走全新
   工具循环（新 delta，上下文由 memory 拼接）。TurnRequest 带 :resume? true
   标记，请求侧改写类 filter（RAG 注入等）应据此跳过首次改写。

   返回: 同 invoke（:completed 或再次 :paused）"
  [kernel loop-state decision opts]
  (let [continuation
        (fn []
          (if (= :env-retry (:phase loop-state))
            (resume-env kernel loop-state decision opts)
            (resume-approval kernel loop-state decision opts)))
        tctx (or (:context opts) (ctx/create))
        max-iter (or (:max-iterations opts)
                     (get-in kernel [:settings :max-tool-iterations])
                     default-max-iterations)
        consumed? (atom false)
        ;; 终端一次性分派：首调 = 暂停延续；重入 = 常规循环（新 delta）
        terminal (fn [treq]
                   (-> (if (compare-and-set! consumed? false true)
                         (continuation)
                         (run-tool-loop kernel
                                        (mapv msg/normalize (:messages treq))
                                        max-iter [] (or (:context treq) tctx)
                                        (:tool-gate opts)
                                        (build-chat-opts kernel opts)
                                        (or (:callbacks opts) {})
                                        {:on-env-error (or (:on-env-error opts) :proceed)}))
                       ;; 延续与重入都可能撞上 return-direct 收尾
                       (persist-direct-messages! kernel (ctx/conversation-id tctx))))
        turn-chain (filters/build-chain (keep :turn (:filters kernel)) terminal)]
    (turn-chain {:resume? true :messages nil :context tctx})))

(defn- resume-approval
  "审批暂停的延续（工具未执行）：按 decision/payload 组 resume-gate，
   执行批次后继续循环。"
  [kernel loop-state decision opts]
  (let [{:keys [tool-calls remaining records pending-id]} loop-state
        payload (:payload opts)
        _ (when (and (= :reply decision) (not (string? (:message payload))))
            (throw (ex-info ":reply 需要 opts :payload {:message \"...\"}"
                            {:payload payload})))
        _ (when (and (= :reply decision) (nil? pending-id))
            (throw (ex-info "loop-state 缺少 :pending-id（旧版本暂停态不支持 :reply）"
                            {})))
        ;; 编辑后批准：替换 pending 工具的参数
        tool-calls (if-let [new-args (and (= :approved decision) (:args payload))]
                     (mapv #(if (= pending-id (:id %)) (assoc % :args new-args) %)
                           tool-calls)
                     tool-calls)
        tctx (or (:context opts) (ctx/create))
        gate (:tool-gate opts)
        callbacks (or (:callbacks opts) {})
        on-tool-result (:on-tool-result callbacks)
        resume-gate
        (case decision
          :approved (constantly :proceed)
          :reply    (fn [tc] (if (= pending-id (:id tc))
                               {:reply (:message payload)}
                               :proceed))
          ;; 缺省 :rejected：gate 判敏感的拒绝（可带理由）
          (fn [tc] (if (and gate (= :pause (gate tc)))
                     (if-let [m (:message payload)] {:reject m} :reject)
                     :proceed)))
        {:keys [messages records context]}
        (execute-batch kernel tool-calls resume-gate tctx records on-tool-result)]
    (run-tool-loop kernel messages remaining records context
                   gate
                   (build-chat-opts kernel opts)
                   callbacks
                   {:on-env-error (or (:on-env-error opts) :proceed)})))
