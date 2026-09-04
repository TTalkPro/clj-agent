(ns im.ttalk.agent.subagent.delegate
  "子 Agent 委派工具构建器（对标 beamai_agent_delegate）

   提供三类入口：

   1. **同步单委派** `delegate-tool`：spawn→await→drop，同步拿结果。
   2. **同步并发 fan-out** `fanout-tool`：tasks 列表，并发启动多个子 agent，汇总。
   3. **异步管理工具集** `management-tools`：spawn/list/result/kill/restart，供 LLM 跨轮自管。

   每个函数返回**内联工具 map**（含 :handler），可直接放入 :tools 列表。

   三类入口的 config 都认一个可选的 `:observer`——**协议无关的观察挂点**，
   原样透进 `manager/spawn!` 的 spec（契约见 `manager` 的 ns docstring）。
   AG-UI 那侧用它把委派期间的 token / 工具调用归属到一条 lane
   （docs/subagent-event-attribution-design.md）；不传就是今天的行为，逐字不变。

   使用示例：
   (def research-tool
     (delegate-tool {:name \"deep_research\"
                     :subagent-fn (fn [_ _] {:provider p :model \"gpt-4o\"})
                     :timeout 120000}))
   (create-agent {:tools [#'my-tool research-tool] ...})"
  (:require [im.ttalk.agent.subagent.manager :as mgr]
            [clojure.string]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; 内部辅助
;;; ============================================================

(declare delegate-run)

(defn- get-arg [args k]
  (or (get args (keyword k)) (get args (name k)) ""))

(defn- compose-prompt
  "拼接 [seed, background, task]，跳过空/nil 串。"
  [seed background task]
  (->> [seed background task]
       (remove #(or (nil? %) (clojure.string/blank? (str %))))
       (clojure.string/join "\n\n")))

(defn- safe-result-str [outcome]
  (cond
    (contains? outcome :ok)    (str (:ok outcome))
    (contains? outcome :error) (str "子 Agent 失败: " (pr-str (:error outcome)))
    :else                      (str "未知结果: " (pr-str outcome))))

(defn- suspended!
  "子 agent 挂起在等人 → **把暂停往上冒**，别当成结果也别当成失败。

   靠 `ex-data` 的 `:error-class`（`model.error/classify-exception` 明写的
   「工具作者标注，最高优先级」那个口子）把它分类成 `:subagent-suspended`，
   react 的屏障据此走 `subagent-pause`——与环境类错误暂停是同一处接缝、不同分支。

   ⛔ **不能借 `:environment` 那条路**：那条的 `:pause-reason` 会写成「环境类
   错误」，而这压根不是错误。那个串会经 `RUN_FINISHED.outcome.interrupts[].reason`
   到用户眼前。"
  [spawn-id outcome]
  (throw (ex-info (or (get-in outcome [:suspended :reason]) "子 agent 等待人工答复")
                  {:error-class :subagent-suspended
                   :spawn-id spawn-id
                   :pending-tool (get-in outcome [:suspended :pending-tool])})))

(defn- run-sync
  "spawn → await → drop，超时则 kill。返回结果字符串。

   **R6: try/finally 保 kill!/drop!**——await! 的底层 deref（CountDownLatch.await）
   被引擎超时 interrupt 时抛 InterruptedException，若无 finally 则 kill!/drop!
   跳过、子 agent 泄漏且继续烧 token。

   **挂起是第三条出路**：既不返回结果也不算失败，而是抛一个分类过的异常让
   父循环在屏障处暂停（见 `suspended!`）。这时**不能 drop**——注册表条目里存着
   等人答复的那个 agent 实例，摘了就没法 resume。"
  [spec timeout-ms]
  (let [spawn-id (:ok (mgr/spawn! spec))
        ;; **凭手里的 outcome 判，不查注册表状态**：`await!` 在 worker `deliver`
        ;; 那一刻就返回，而把状态标成 `:suspended` 的 `finish!` 在它之后一步——
        ;; 查注册表会读到还没更新的 `:running`，于是把条目当场摘掉，resume 再也
        ;; 找不到那个 agent（实测撞到过：第二段变成重开一个新子 agent）。
        suspended? (volatile! false)]
    (try
      (let [outcome (mgr/await! spawn-id timeout-ms)]
        (when (= {:error :timeout} outcome) (mgr/kill! spawn-id))
        (when (:suspended outcome)
          (vreset! suspended? true)
          (suspended! spawn-id outcome))
        (safe-result-str outcome))
      (finally
        ;; **挂起的不摘**：条目里存着等人答复的 agent 实例（`mgr/resume!` 要用）。
        ;; 其余情况照旧摘干净——R6 那条「子 agent 泄漏且继续烧 token」还管着。
        (when-not @suspended? (mgr/drop! spawn-id))))))

(defn- resume-sync
  "对着一个**已挂起**的子 agent 续跑（父那边收到人的答复之后）。

   与 `run-sync` 的分工：那个负责「起一个新的」，这个负责「把停着的那个接上」。
   续跑之后还可能再次挂起（一轮里连着两个敏感工具），所以三条出路与首段一样。"
  [spawn-id {:keys [decision payload observer]}]
  (let [suspended? (volatile! false)]                 ;; 同 `run-sync`：不查注册表
    (try
      (let [outcome (mgr/resume! spawn-id decision payload observer)]
        (when (:suspended outcome)
          (vreset! suspended? true)
          (suspended! spawn-id outcome))
        (safe-result-str outcome))
      (finally
        (when-not @suspended? (mgr/drop! spawn-id))))))

(defn- resume-target
  "本次 tool-call 是不是在续跑一个挂起的子 agent？是就返回那个 spawn-id。

   `:subagent/resume` 由 `react/resume-subagent` 钉进 ToolContext（框架件，
   见 `ctx/framework-keys`），形如
   `{:decision d :payload p :observer o :by-call {tool-call-id spawn-id}}`。
   按 **tool-call id** 对号入座：同一批里几个委派并发挂起时，猜不得。"
  [ctx]
  (when-let [r (get ctx :subagent/resume)]
    (get (:by-call r) (get ctx :tool/call-id))))

(defn- deadline-ms [timeout-ms]
  (when timeout-ms (+ (System/currentTimeMillis) timeout-ms)))

(defn- remaining-ms [deadline]
  (when deadline (max 0 (- deadline (System/currentTimeMillis)))))

;;; ============================================================
;;; 公开 API
;;; ============================================================

(defn delegate-tool
  "构建单委派工具（同步：spawn→await→drop）。

   config 键：
   - :name         工具名（必需）
   - :description  工具描述（可选）
   - :subagent-fn  (fn [args ctx] -> subagent-config)（必需）
   - :seed-fn      (fn [args ctx] -> prompt-prefix)（可选）
   - :result-fn    (fn [chat-result] -> string)（可选，默认取 :text）
   - :timeout      超时毫秒（可选，默认 60000）
   - :observer     观察者工厂（可选，见 ns docstring）
   - :subagent-name 给观察者看的子 agent 名（可选，缺省取工具名）

   返回内联工具 map，可放入 :tools 列表。"
  [config]
  (let [subagent-fn (:subagent-fn config)
        seed-fn     (or (:seed-fn config) (constantly ""))
        result-fn   (or (:result-fn config) :text)
        timeout     (or (:timeout config) 60000)]
    {:name        (:name config)
     :description (or (:description config)
                      "将子任务委派给独立子 Agent 执行。")
     :timeout     timeout  ;; R6: 写进 inline map → 工具声明恒优先，引擎缺省砍不掉它
     :input_schema {:type       "object"
                    :properties {:task    {:type "string" :description "委派给子 Agent 的任务描述。"}
                                 :context {:type "string" :description "子任务的背景信息（可选）。"}}
                    :required   ["task"]}
     :handler     (fn [args ctx]
                    ;; **续跑优先**：父在屏障处暂停、人答复之后，`react/resume-subagent`
                    ;; 会把 `{tool-call-id → spawn-id}` 与决定放进 ToolContext 再重跑
                    ;; 这一批。认出来就接上停着的那个，而不是重开一个新的
                    ;; ——重开等于把人刚才的答复扔了、token 重烧一遍。
                    (if-let [sid (resume-target ctx)]
                      (resume-sync sid (get ctx :subagent/resume))
                      (delegate-run config subagent-fn seed-fn result-fn timeout args ctx)))}))

(defn- delegate-run
  "首段：拼 prompt、spawn、await（`delegate-tool` 的 handler 抽出来，好让
   续跑那条路读起来是并列的两支）。"
  [config subagent-fn seed-fn result-fn timeout args ctx]
  (let [subagent-config (subagent-fn args ctx)
        seed       (seed-fn args ctx)
        background (get-arg args :context)
        task       (get-arg args :task)
        prompt     (compose-prompt seed background task)
        spec       {:subagent-config subagent-config
                    :prompt          prompt
                    :result-fn       result-fn
                    :owner           (get ctx :conversation-id)
                    :observer        (or (:observer config)
                                         ;; 嵌套：父 lane 的观察者把子工厂钉在
                                         ;; ToolContext 上（`manager/do-run`）。
                                         ;; 工具声明里显式给的优先
                                         (get ctx :subagent/observer))
                    :subagent-name   (or (:subagent-name config)
                                         (:name config))
                    ;; 给观察者的是**原始 task**，不是拼好的 prompt
                    ;; ——prompt 里还有 seed 与背景，动辄几 KB，
                    ;; 塞进 SUBAGENT_STARTED.description 没人读得下去
                    :task            task
                    ;; 本次委派是哪个 tool-call 发起的。同一批里几个委派工具
                    ;; 并发时，这是唯一能把 lane 挂回正确那张工具卡片的依据
                    :parent-tool-call-id (get ctx :tool/call-id)}]
    (run-sync spec timeout)))

(defn fanout-tool
  "构建并发 fan-out 委派工具。

   LLM 传入 tasks 列表，每个任务独立 spawn 子 agent 并发执行，汇总所有结果。
   config 键同 delegate-tool。"
  [config]
  (let [subagent-fn (:subagent-fn config)
        seed-fn     (or (:seed-fn config) (constantly ""))
        result-fn   (or (:result-fn config) :text)
        timeout     (or (:timeout config) 60000)]
    {:name        (:name config)
     :description (or (:description config)
                      "将多个子任务并发委派给独立子 Agent，汇总所有结果。")
     :timeout     timeout  ;; R6: 同 delegate-tool
     :input_schema {:type       "object"
                    :properties {:tasks   {:type  "array"
                                           :items {:type "string"}
                                           :description "要并发执行的子任务列表。"}
                                 :context {:type "string" :description "共享背景信息（可选）。"}}
                    :required   ["tasks"]}
     :handler     (fn [args ctx]
                    (let [raw-tasks (or (get args :tasks) (get args "tasks") [])
                          tasks     (if (sequential? raw-tasks) (vec raw-tasks) [])
                          background (get-arg args :context)
                          seed       (seed-fn args ctx)
                          owner      (get ctx :conversation-id)]
                      (if (empty? tasks)
                        "错误：tasks 不能为空"
                         (let [base-name (or (:subagent-name config) (:name config))
                               ;; 并发 spawn 所有子 agent
                               spawn-ids (map-indexed
                                          (fn [idx task]
                                            (let [spec {:subagent-config (subagent-fn (assoc args :task task) ctx)
                                                        :prompt          (compose-prompt seed background task)
                                                        :result-fn       result-fn
                                                        :owner           owner
                                                        :observer        (or (:observer config)
                                                          ;; 嵌套：父 lane 的观察者把
                                                          ;; 子工厂钉在 ToolContext 上
                                                          ;; （`manager/do-run`）。工具
                                                          ;; 声明里显式给的优先
                                                          (get ctx :subagent/observer))
                                                        ;; 每路一个名字：N 路并发在前端是 N 条
                                                        ;; lane，同名的话除了 id 谁也认不出谁
                                                        :subagent-name   (str base-name "#" idx)
                                                        :task            task
                                                        ;; N 路共享同一个 tool-call
                                                        ;; ——它们本就是一次调用的扇出
                                                        :parent-tool-call-id (get ctx :tool/call-id)}]
                                              (:ok (mgr/spawn! spec))))
                                          tasks)
                               spawn-ids (vec spawn-ids)
                               deadline  (deadline-ms timeout)
                               ;; 共享截止时间，顺序 await（总等待时间 ≤ timeout）
                               ;; R6: try/finally 保 drop! — 与 run-sync 同款，
                               ;; 被引擎超时 interrupt 时 await! 抛 InterruptedException，
                               ;; 无 finally 则当前及后续 spawn-id 泄漏
                               outcomes  (mapv (fn [spawn-id]
                                                (try
                                                  (let [remain (or (remaining-ms deadline) 0)
                                                        o (mgr/await! spawn-id remain)]
                                                    (when (= {:error :timeout} o) (mgr/kill! spawn-id))
                                                    o)
                                                  (finally
                                                    (mgr/drop! spawn-id))))
                                              spawn-ids)
                              entries   (mapv (fn [task outcome]
                                               (str "任务: " task "\n结果: " (safe-result-str outcome)))
                                             tasks outcomes)]
                          (clojure.string/join "\n\n---\n\n" entries)))))}))

(defn management-tools
  "返回一组异步管理工具（5 个内联工具 map），让 LLM 跨轮自主管理子 agent。

   工具列表：spawn_subagent / list_subagents / subagent_result / kill_subagent / restart_subagent

   config 键同 delegate-tool。

   用法：
   (create-agent {:tools (into [#'my-tool] (delegate/management-tools my-config))})"
  [config]
  (let [subagent-fn (:subagent-fn config)
        seed-fn     (or (:seed-fn config) (constantly ""))
        result-fn   (or (:result-fn config) :text)]
    [{:name        "spawn_subagent"
      :description "异步启动一个子 Agent（立即返回 id，不等待结果）。"
      :input_schema {:type       "object"
                     :properties {:task    {:type "string" :description "子任务描述。"}
                                  :context {:type "string" :description "背景信息（可选）。"}}
                     :required   ["task"]}
      :handler (fn [args ctx]
                 (let [spec {:subagent-config (subagent-fn args ctx)
                             :prompt          (compose-prompt (seed-fn args ctx)
                                                              (get-arg args :context)
                                                              (get-arg args :task))
                             :result-fn       result-fn
                             :owner           (get ctx :conversation-id)
                             :observer        (or (:observer config)
                                                          ;; 嵌套：父 lane 的观察者把
                                                          ;; 子工厂钉在 ToolContext 上
                                                          ;; （`manager/do-run`）。工具
                                                          ;; 声明里显式给的优先
                                                          (get ctx :subagent/observer))
                             :subagent-name   (or (:subagent-name config) "subagent")
                             :task            (get-arg args :task)
                             :parent-tool-call-id (get ctx :tool/call-id)}
                       spawn-id (:ok (mgr/spawn! spec))]
                   (str "子 Agent 已启动，id: " spawn-id)))}

     {:name        "list_subagents"
      :description "列出本会话所有子 Agent 及其状态（running/done/failed/killed）。"
      :input_schema {:type "object" :properties {} :required []}
      :handler (fn [_args ctx]
                 (let [agents (mgr/list-agents (get ctx :conversation-id))]
                   (if (empty? agents)
                     "无子 Agent"
                     (->> agents
                          (mapv #(str "id: " (:id %) "  状态: " (name (:status %))))
                          (clojure.string/join "\n")))))}

     {:name        "subagent_result"
      :description "查询子 Agent 的结果（运行中返回 not_ready，完成返回结果）。"
      :input_schema {:type       "object"
                     :properties {:id {:type "string" :description "子 Agent id。"}}
                     :required   ["id"]}
      :handler (fn [args _ctx]
                 (let [qid (get-arg args :id)
                       ret (mgr/result qid)]
                   (cond
                     (:ok ret)                   (str "结果: " (safe-result-str (:ok ret)))
                     (= (:error ret) :not-ready) "运行中，尚未完成"
                     (= (:error ret) :not-found) "未找到该子 Agent"
                     :else                       (str "查询失败: " (pr-str ret)))))}

     {:name        "kill_subagent"
      :description "Kill 正在运行的子 Agent。"
      :input_schema {:type       "object"
                     :properties {:id {:type "string" :description "子 Agent id。"}}
                     :required   ["id"]}
      :handler (fn [args _ctx]
                 (mgr/kill! (get-arg args :id))
                 "已 Kill")}

     {:name        "restart_subagent"
      :description "用原始 spec 重启子 Agent（同 id，状态回 running）。"
      :input_schema {:type       "object"
                     :properties {:id {:type "string" :description "子 Agent id。"}}
                     :required   ["id"]}
      :handler (fn [args _ctx]
                 (let [ret (mgr/restart! (get-arg args :id))]
                   (if (:ok ret)
                     (str "已重启，id: " (:ok ret))
                     (str "重启失败: " (pr-str ret)))))}]))
