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
            [im.ttalk.agent.callbacks :as cb]
            [im.ttalk.agent.kernel :as kernel]
            [im.ttalk.agent.context :as ctx]
            [im.ttalk.agent.tool :as tool]
            [im.ttalk.agent.model.message :as msg]
            [im.ttalk.agent.model.response :as response]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.streaming :as streaming]))

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

(defn execute-batch
  "按 gate 决策执行一批工具调用，产出中立 tool 结果消息 + 记录 + 更新后的 ToolContext。

   gate: (fn [tool-call] -> :proceed | :reject)，nil 视为全 :proceed。
   - :proceed 调 kernel/invoke-tool（异常捕获为错误结果，不中断）
   - :reject 跳过执行，填入「已拒绝执行」中立结果

   on-tool-result: (fn [tool-name result-str])，nil 时不触发。在 execute-batch 内
   对每个实际执行的工具恰好触发一次（:reject 的工具不触发）。

   返回: {:messages [...] :records [...] :context ...}"
  ([kernel tool-calls gate tool-context init-records]
   (execute-batch kernel tool-calls gate tool-context init-records nil))
  ([kernel tool-calls gate tool-context init-records on-tool-result]
   (reduce
     (fn [{:keys [messages records context]} tc]
       (let [fn-key (keyword (:name tc))
             decision (if gate (gate tc) :proceed)]
         (if (= :reject decision)
           {:messages (conj messages (msg/tool-result (:id tc) (:name tc) "已拒绝执行"))
            :records  (conj records {:name fn-key :args (:args tc) :result :rejected})
            :context  context}
           (let [{:keys [value context]}
                 (try (kernel/invoke-tool kernel fn-key (:args tc) context)
                      (catch Exception e
                        {:value (str "错误: " (or (not-empty (.getMessage e))
                                                   (.getName (class e))))
                         :context context}))]
             (when on-tool-result
               (try (on-tool-result (name fn-key) value) (catch Throwable _ nil)))
             {:messages (conj messages (msg/tool-result (:id tc) (:name tc) value))
              :records  (conj records {:name fn-key :args (:args tc) :result value})
              :context  context}))))
     {:messages [] :records init-records :context tool-context}
     tool-calls)))

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

(defn- run-tool-loop
  "统一工具循环：从 delta 起步，驱动 LLM ↔ 工具，直到文本响应或被 gate 暂停。

   callbacks 携带观察回调（:on-llm-call/:on-llm-result/:on-tool-result）和元数据
   （:metadata）。gate 评估结果按 tool-call :id 缓存，确保每个工具调用恰好触发
   一次（不因 pause 检测的两阶段逻辑而重复）。

   返回:
   {:status :completed :response r :tool-context c :tool-calls-made [...]}
   {:status :paused    :loop-state {:tool-calls :remaining :records} :pending-tool {...} :tool-context c}"
  [kernel delta remaining records tctx gate chat-opts callbacks]
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
                   :loop-state {:tool-calls calls :remaining (dec remaining) :records records}
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
                  (let [{:keys [messages records context]}
                        (execute-batch kernel calls cached-gate tctx records on-tool-result)]
                    (recur messages (dec remaining) records context)))))))))))

(defn invoke
  "工具调用循环主入口（统一循环）。

   参数:
   - kernel:   Kernel 实例（需注册 LLM 服务）
   - store:    ChatMemory store（与 kernel 上 memory-filter 同一实例；用于 heal/临时清理）
   - messages: 本轮新消息（中立消息）
   - opts:     {:context :system-prompts :max-iterations :tool-choice :tool-gate :tags/:exclude-tags
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
      (let [result (run-tool-loop kernel (mapv msg/normalize messages)
                                  max-iter [] init-ctx
                                  (:tool-gate opts)
                                  (build-chat-opts kernel opts)
                                  callbacks)]
        (when (and ephemeral? store (= :completed (:status result)))
          (memory/mem-clear store conv-id))
        result)
      (catch Throwable t
        (when (and ephemeral? store) (memory/mem-clear store conv-id))
        (throw t)))))

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
        callbacks (or (:callbacks opts) {})
        on-tool-result (:on-tool-result callbacks)
        resume-gate (if (= decision :approved)
                      (constantly :proceed)
                      (fn [tc] (if (and gate (= :pause (gate tc))) :reject :proceed)))
        {:keys [messages records context]}
        (execute-batch kernel tool-calls resume-gate tctx records on-tool-result)]
    (run-tool-loop kernel messages remaining records context
                   gate
                   (build-chat-opts kernel opts)
                   callbacks)))
