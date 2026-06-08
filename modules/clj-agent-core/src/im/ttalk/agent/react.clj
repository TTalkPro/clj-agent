(ns im.ttalk.agent.react
  "工具调用循环（从 kernel 下沉而来）

   kernel 只提供原语 invoke-chat / invoke-tool；'驱动 LLM↔工具直到文本或暂停'
   这套策略(含 max-iterations / gate 暂停 / resume / 悬空 tool_use 自愈)属编排层，
   放在 simpleagent。每轮只向 invoke-chat 传 delta，由 kernel 的 memory filter
   按 conversation-id 拼出完整历史。

   store 显式传入(kernel 不再持有 memory)：用于 heal 与临时会话清理；
   与 kernel 上挂载的 memory-filter 必须是同一个 store 实例。"
  (:require [clojure.string]
            [im.ttalk.agent.kernel :as kernel]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.tool :as tool]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.memory :as memory]))

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
                 :when (and v
                            (or (nil? tags-set)
                                (tool/has-any-tag? v tags-set))
                            (or (nil? exclude-tags-set)
                                (not (tool/has-any-tag? v exclude-tags-set))))]
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
      sp-str (assoc :system-prompt sp-str))))

(defn execute-batch
  "按 gate 决策执行一批工具调用，产出中立 tool 结果消息 + 记录 + 更新后的 ToolContext。

   gate: (fn [tool-call] -> :proceed | :reject)，nil 视为全 :proceed。
   - :proceed 调 kernel/invoke-tool（异常捕获为错误结果，不中断）
   - :reject 跳过执行，填入「已拒绝执行」中立结果

   返回: {:messages [...] :records [...] :context ...}"
  [kernel tool-calls gate tool-context init-records]
  (reduce
    (fn [{:keys [messages records context]} tc]
      (let [fn-key (keyword (:name tc))
            decision (if gate (gate tc) :proceed)]
        (if (= :reject decision)
          {:messages (conj messages (msg/tool-result (:id tc) (:name tc) "已拒绝执行"))
           :records  (conj records {:name fn-key :args (:input tc) :result :rejected})
           :context  context}
          (let [{:keys [value context]}
                (try (kernel/invoke-tool kernel fn-key (:input tc) context)
                     (catch Exception e
                       {:value (str "错误: " (.getMessage e)) :context context}))]
            {:messages (conj messages (msg/tool-result (:id tc) (:name tc) value))
             :records  (conj records {:name fn-key :args (:input tc) :result value})
             :context  context}))))
    {:messages [] :records init-records :context tool-context}
    tool-calls))

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
   无悬空则 no-op。store 即挂载于 kernel 的同一 memory store。"
  [store conv-id]
  (when conv-id
    (let [dangling (dangling-tool-call-ids (memory/mem-get store conv-id))]
      (when (seq dangling)
        (memory/mem-add store conv-id
                        (mapv #(msg/tool-result (:id %) (:name %)
                                                "已取消（上一轮工具调用未审批/未恢复）")
                              dangling))))))

(defn- run-tool-loop
  "统一工具循环：从 delta 起步，驱动 LLM ↔ 工具，直到文本响应或被 gate 暂停。

   返回:
   {:status :completed :response r :tool-context c :tool-calls-made [...]}
   {:status :paused    :loop-state {:tool-calls :remaining :records} :pending-tool {...} :tool-context c}"
  [kernel delta remaining records tctx gate chat-opts]
  (loop [delta delta, remaining remaining, records records, tctx tctx]
    (when (zero? remaining)
      ;; payload 用已执行的工具记录数（之前误报恒为 0 的 remaining）
      (throw (ex-info "工具调用循环次数超过上限（max-iterations）"
                      {:reason :max-iterations-exceeded
                       :tool-call-count (count records)
                       :tool-calls-made records})))
    (let [{:keys [response context]} (kernel/invoke-chat kernel delta (assoc chat-opts :context tctx))
          tctx context
          calls (response/response-tool-calls response)]
      (cond
        (empty? calls)
        {:status :completed :response response
         :tool-context tctx :tool-calls-made records}

        (and gate (some #(= :pause (gate %)) calls))
        (let [paused-call (first (filter #(= :pause (gate %)) calls))]
          {:status :paused
           :pause-reason (str "需要审批: " (:name paused-call))
           :loop-state {:tool-calls calls :remaining (dec remaining) :records records}
           :pending-tool {:name (:name paused-call)
                          :args (:input paused-call)
                          :tool-call paused-call}
           :tool-calls-made records
           :tool-context tctx})

        :else
        (let [{:keys [messages records context]}
              (execute-batch kernel calls gate tctx records)]
          (recur messages (dec remaining) records context))))))

(defn invoke
  "工具调用循环主入口（统一循环）。

   参数:
   - kernel:   Kernel 实例（需注册 LLM 服务）
   - store:    ChatMemory store（与 kernel 上 memory-filter 同一实例；用于 heal/临时清理）
   - messages: 本轮新消息（中立消息）
   - opts:     {:context :system-prompts :max-iterations :tool-choice :tool-gate :tags/:exclude-tags}

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
        ;; 自愈：上一轮暂停未 resume 会留下悬空 tool_use，开新一轮前补「已取消」配平
        _ (heal-dangling-tool-calls! store conv-id)
        result (run-tool-loop kernel (mapv msg/normalize messages)
                              max-iter [] init-ctx
                              (:tool-gate opts)
                              (build-chat-opts kernel opts))]
    ;; 临时会话仅在完成时清理（暂停需保留历史以便 resume）
    (when (and ephemeral? (= :completed (:status result)))
      (memory/mem-clear store conv-id))
    result))

(defn resume
  "从 paused 的 loop-state 继续工具循环。

   参数:
   - kernel:     Kernel 实例
   - loop-state: invoke 返回的 :loop-state {:tool-calls :remaining :records}
   - decision:   :approved（强制全部执行）| :rejected（gate 决定，敏感→拒绝）
   - opts:       同 invoke（须含带 conversation-id 的 :context 以接续历史）

   返回: 同 invoke（:completed 或再次 :paused）"
  [kernel loop-state decision opts]
  (let [{:keys [tool-calls remaining records]} loop-state
        tctx (or (:context opts) (ctx/create))
        gate (:tool-gate opts)
        ;; rejected：把暂停那批里 gate 判 :pause 的工具落实为 :reject（否则会被执行）
        resume-gate (if (= decision :approved)
                      (constantly :proceed)
                      (fn [tc] (if (and gate (= :pause (gate tc))) :reject :proceed)))
        {:keys [messages records context]}
        (execute-batch kernel tool-calls resume-gate tctx records)]
    (run-tool-loop kernel messages remaining records context
                   gate
                   (build-chat-opts kernel opts))))
