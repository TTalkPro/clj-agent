(ns im.ttalk.agent.subagent.delegate
  "子 Agent 委派工具构建器（对标 beamai_agent_delegate）

   提供三类入口：

   1. **同步单委派** `delegate-tool`：spawn→await→drop，同步拿结果。
   2. **同步并发 fan-out** `fanout-tool`：tasks 列表，并发启动多个子 agent，汇总。
   3. **异步管理工具集** `management-tools`：spawn/list/result/kill/restart，供 LLM 跨轮自管。

   每个函数返回**内联工具 map**（含 :handler），可直接放入 :tools 列表。

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

(defn- run-sync
  "spawn → await → drop，超时则 kill。返回结果字符串。"
  [spec timeout-ms]
  (let [spawn-id (:ok (mgr/spawn! spec))
        outcome  (mgr/await! spawn-id timeout-ms)
        _        (when (= {:error :timeout} outcome) (mgr/kill! spawn-id))
        _        (mgr/drop! spawn-id)]
    (safe-result-str outcome)))

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

   返回内联工具 map，可放入 :tools 列表。"
  [config]
  (let [subagent-fn (:subagent-fn config)
        seed-fn     (or (:seed-fn config) (constantly ""))
        result-fn   (or (:result-fn config) :text)
        timeout     (or (:timeout config) 60000)]
    {:name        (:name config)
     :description (or (:description config)
                      "将子任务委派给独立子 Agent 执行。")
     :input_schema {:type       "object"
                    :properties {:task    {:type "string" :description "委派给子 Agent 的任务描述。"}
                                 :context {:type "string" :description "子任务的背景信息（可选）。"}}
                    :required   ["task"]}
     :handler     (fn [args ctx]
                    (let [subagent-config (subagent-fn args ctx)
                          seed       (seed-fn args ctx)
                          background (get-arg args :context)
                          task       (get-arg args :task)
                          prompt     (compose-prompt seed background task)
                          spec       {:subagent-config subagent-config
                                      :prompt          prompt
                                      :result-fn       result-fn
                                      :owner           (get ctx :conversation-id)}]
                      (run-sync spec timeout)))}))

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
                        (let [;; 并发 spawn 所有子 agent
                              spawn-ids (mapv (fn [task]
                                               (let [spec {:subagent-config (subagent-fn (assoc args :task task) ctx)
                                                           :prompt          (compose-prompt seed background task)
                                                           :result-fn       result-fn
                                                           :owner           owner}]
                                                 (:ok (mgr/spawn! spec))))
                                             tasks)
                              deadline  (deadline-ms timeout)
                              ;; 共享截止时间，顺序 await（总等待时间 ≤ timeout）
                              outcomes  (mapv (fn [spawn-id]
                                               (let [remain (or (remaining-ms deadline) 0)
                                                     o (mgr/await! spawn-id remain)]
                                                 (when (= {:error :timeout} o) (mgr/kill! spawn-id))
                                                 (mgr/drop! spawn-id)
                                                 o))
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
                             :owner           (get ctx :conversation-id)}
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
