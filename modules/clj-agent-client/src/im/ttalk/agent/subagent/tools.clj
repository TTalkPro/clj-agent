(ns im.ttalk.agent.subagent.tools
  "子代理异步工具组（designs/runtime-borrow.md §14；借 Keelson `Subagent/SubagentTools.cs`）。

   `delegate/management-tools` 有 spawn/list/result/kill/restart，但缺三样：
   **wait**（阻塞等一组子 agent，带超时——不然模型只能空转轮询 `subagent_result`）、
   **send**（给已完成的子 agent 续一句话，带着它自己的历史接着干）、**上限**
   （同时运行的数量、嵌套深度——不然一个模型能把 fan-out 套成指数）。

   五个内联工具：`spawn_agent` / `send_agent` / `wait_agent` / `list_agents` /
   `interrupt_agent`，基于 `subagent/manager`。

   - 深度经子 agent 的 `:tool-context` 种子 `:subagent/depth` **显式**传递（设计原则 §3：
     跨 ChatClient 边界只走显式通道）；
   - send = 同一 conversation-id + 同一 memory store 再起一次运行，历史自然续上；
   - 对模型暴露的 id 稳定不变（send 之后内部 spawn id 换了，表里记着映射）。"
  (:require [clojure.string :as str]
            [im.ttalk.agent.memory :as memory]
            [im.ttalk.agent.subagent.manager :as mgr]))

(set! *warn-on-reflection* true)

(defn- arg [args k] (or (get args k) (get args (name k))))

(defn- outcome-str [o]
  (cond
    (contains? o :ok) (str (:ok o))
    (:suspended o) (str "已挂起，等待人工答复：" (get-in o [:suspended :reason]))
    (= :timeout (:error o)) "仍在运行（等待超时）"
    :else (str "失败：" (pr-str (:error o)))))

(defn subagent-tools
  "返回五个内联工具 map。

   config:
   - :subagent-fn   (fn [args ctx] -> subagent-config)（必需，同 delegate-tool）
   - :result-fn     (fn [chat-result] -> 字符串)，缺省 :text
   - :max-agents    同一会话同时 running 的上限（缺省 4）
   - :max-depth     嵌套深度上限（缺省 2；顶层 agent 深度 0）
   - :wait-timeout-ms wait_agent 缺省等待上限（缺省 60000）
   - :observer / :subagent-name  同 delegate-tool"
  [{:keys [subagent-fn result-fn max-agents max-depth wait-timeout-ms observer subagent-name]
    :or {result-fn :text max-agents 4 max-depth 2 wait-timeout-ms 60000}}]
  (let [table (atom {})   ;; 暴露 id -> {:spawn-id :owner :config :task}
        running-count (fn [owner]
                        (count (filter (fn [[_ {:keys [spawn-id] o :owner}]]
                                         (and (= o owner)
                                              (= :running (:status (first (filter #(= spawn-id (:id %)) (mgr/list-agents)))))))
                                       @table)))
        status-of (fn [spawn-id] (or (:status (first (filter #(= spawn-id (:id %)) (mgr/list-agents)))) :unknown))
        launch! (fn [config prompt task ctx]
                  (:ok (mgr/spawn! {:subagent-config config
                                    :prompt prompt
                                    :result-fn result-fn
                                    :owner (get ctx :conversation-id)
                                    :observer (or observer (get ctx :subagent/observer))
                                    :subagent-name (or subagent-name "subagent")
                                    :task task
                                    :parent-tool-call-id (get ctx :tool/call-id)})))
        lookup (fn [args ctx]
                 (let [id (str (arg args :id))
                       e (get @table id)]
                   (when (and e (= (:owner e) (get ctx :conversation-id))) [id e])))]
    [{:name "spawn_agent"
      :description "异步启动一个子 Agent 处理一个独立任务，立即返回 id。之后用 wait_agent 取结果、send_agent 追问。"
      :input_schema {:type "object"
                     :properties {:task {:type "string" :description "交给子 Agent 的任务（写清楚目标与产出）。"}
                                  :context {:type "string" :description "背景信息（可选）。"}}
                     :required ["task"]}
      :handler (fn [args ctx]
                 (let [depth (long (or (get ctx :subagent/depth) 0))
                       owner (get ctx :conversation-id)]
                   (cond
                     (>= depth max-depth)
                     (str "拒绝：子 Agent 嵌套深度已达上限（" max-depth "），请自己完成这个任务。")

                     (>= (running-count owner) max-agents)
                     (str "拒绝：同时运行的子 Agent 已达上限（" max-agents "），先用 wait_agent 等已有的完成。")

                     :else
                     (let [store (memory/in-memory-store)
                           conv (str "sub-" (java.util.UUID/randomUUID))
                           config (-> (subagent-fn args ctx)
                                      (assoc :memory store :conversation-id conv)
                                      (update :tool-context assoc :subagent/depth (inc depth)))
                           task (str (arg args :task))
                           prompt (if-let [c (arg args :context)] (str "背景：" c "\n\n任务：" task) task)
                           sid (launch! config prompt task ctx)]
                       (swap! table assoc sid {:spawn-id sid :owner owner :config config :task task})
                       (str "子 Agent 已启动，id: " sid)))))}

     {:name "send_agent"
      :description "给一个已完成的子 Agent 再发一条消息（它记得之前的对话），立即返回；用 wait_agent 取结果。"
      :input_schema {:type "object"
                     :properties {:id {:type "string" :description "子 Agent id。"}
                                  :message {:type "string" :description "要发给它的话。"}}
                     :required ["id" "message"]}
      :handler (fn [args ctx]
                 (if-let [[id e] (lookup args ctx)]
                   (if (= :running (status-of (:spawn-id e)))
                     "它仍在运行，先用 wait_agent 等它完成再发。"
                     (let [sid (launch! (:config e) (str (arg args :message)) (:task e) ctx)]
                       (swap! table assoc-in [id :spawn-id] sid)
                       (str "已发送，id: " id)))
                   "未找到该子 Agent"))}

     {:name "wait_agent"
      :description "等待一个或多个子 Agent 完成并返回它们的结果（有超时）。"
      :input_schema {:type "object"
                     :properties {:ids {:type "array" :items {:type "string"} :description "子 Agent id 列表。"}
                                  :timeout_ms {:type "integer" :minimum 1 :description "最多等多久（毫秒，可选）。"}}
                     :required ["ids"]}
      :handler (fn [args ctx]
                 (let [deadline (+ (System/currentTimeMillis)
                                   (long (or (arg args :timeout_ms) wait-timeout-ms)))]
                   (str/join "\n\n"
                             (for [id (map str (arg args :ids))]
                               (if-let [[_ e] (lookup {:id id} ctx)]
                                 (str "id: " id "\n结果: "
                                      (outcome-str (mgr/await! (:spawn-id e)
                                                               (max 1 (- deadline (System/currentTimeMillis))))))
                                 (str "id: " id "\n未找到该子 Agent"))))))}

     {:name "list_agents"
      :description "列出本会话的子 Agent 及其状态（running/done/failed/killed/suspended）。"
      :input_schema {:type "object" :properties {}}
      :handler (fn [_ ctx]
                 (let [mine (filter #(= (get ctx :conversation-id) (:owner (val %))) @table)]
                   (if (empty? mine)
                     "无子 Agent"
                     (str/join "\n" (for [[id e] mine]
                                      (str "id: " id "  状态: " (name (status-of (:spawn-id e)))
                                           "  任务: " (:task e)))))))}

     {:name "interrupt_agent"
      :description "中止一个正在运行的子 Agent。"
      :input_schema {:type "object"
                     :properties {:id {:type "string" :description "子 Agent id。"}}
                     :required ["id"]}
      :handler (fn [args ctx]
                 (if-let [[_ e] (lookup args ctx)]
                   (do (mgr/kill! (:spawn-id e)) "已中止")
                   "未找到该子 Agent"))}]))
