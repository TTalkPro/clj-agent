# 设计：统一 invoke + 合并 Agent

> 状态：✅ 已实施（决策 A：删两个 ns；gate 仅靠 :on-pause；invoke 统一走 run-tool-loop）
>
> 实施结果：
> - `kernel.clj`：新增 `build-chat-opts`/`execute-batch`/`run-tool-loop`/`resume`，`run-tools` 改为 execute-batch 无 gate 特例，`invoke` 走统一循环并返回 `:status`；`:tool-gate` opt。
> - `simpleagent.clj`：单一 `create-agent`/`chat`/`resume`/`paused?`/`reset!`/`get-history`；`:on-pause` 启用 gate（敏感工具暂停）。
> - 删除 `simpleagent/kernel_agent.clj` 与 `simpleagent/process_agent.clj`；`common.clj` 移除 finalize-result。
> - 测试与 examples 全部改用 `im.ttalk.agent.client`。
> - 重构相关测试 53 tests / 157 assertions / 0 failures；全量仅余 1 个与本次无关的 http_test 网络超时。
>
> ---
> 后续增量（持久化记忆三件套 + 测试合并）：
> - **持久化 ChatMemory**：新增 `core/memory/sqlite.clj`（SQLite 后端，中立消息以 EDN 存储；deps.edn 重新引入 next.jdbc + sqlite-jdbc）。
> - **`:conversation-id` 入参**：`create-agent` 支持传入固定会话 ID，配合持久 store 可跨重启按用户恢复历史。
> - **未-resume 保护**：`chat` 开头调用 `cancel-pending!`，暂停态下开新对话会为悬空 tool-calls 补「已取消」中立结果，避免严格 provider 因悬空 tool_use 报错。
> - **测试合并**：`kernel_agent_test` + `process_agent_test` → 单一 `agent_test.clj`（18 tests，含 SQLite 持久化 / conversation-id 共享 / 未-resume 保护新用例）。
>
> ---
> 原始设计如下。
> 目标：消除 `kernel/invoke` 与 `process-agent/run-loop` 的双份工具循环；把 kernel-agent / process-agent 合并为同一个 Agent，pause/resume 退化为"是否配置 gate"的可选行为。

## 1. 现状与问题

- `kernel/invoke`（kernel.clj）有一份工具循环：invoke-chat(delta) → run-tools → 再 invoke-chat。
- `process-agent/run-loop`（process_agent.clj）是**几乎相同的第二份循环**，只是在执行工具前多一道 `sensitive-call?` 检查 → 暂停。
- 两份循环都要正确处理 "delta + Memory Filter 拼历史 + run-tools"，改一处要同步另一处，易漂移。
- kernel-agent / process-agent 共享 kernel、conversation-id、memory、settings、finalize-result，差别仅在"用哪份循环 + 是否暂停"。

结论：循环应唯一化；两个 agent 应合并。

## 2. 核心机制：工具闸门（tool gate）

给 `invoke` 加一个**可选**的 per-tool-call 决策函数：

```clojure
;; gate: (fn [tool-call] -> :proceed | :pause | :reject)
;;   tool-call = {:id :name :input}
;;   :proceed 正常执行；:pause 暂停整批等待审批；:reject 跳过并填入拒绝结果
;; 不传 gate（nil）→ 全部 :proceed（= 今天 invoke 的行为，向后兼容）
```

批次语义（与现 process-agent 一致）：对 LLM 本轮返回的一组 tool-calls，
- 先对每个 call 求 gate 决策；
- **任一为 `:pause` → 暂停整批**（保存整批 + 剩余迭代）；
- 否则逐个执行：`:proceed` 调 `invoke-tool`，`:reject` 填"已拒绝"中立结果。

敏感检测由 agent 层提供默认 gate（见 §5），kernel 不内置"sensitive"概念，只认 gate。

## 3. 统一的循环（kernel.clj）

把循环体抽成一个可复用的内部函数，`invoke` 和 `resume` 都进入它：

```clojure
(defn- run-tool-loop
  "delta 起步的工具循环。返回 completed 或 paused。"
  [kernel delta remaining records tctx gate chat-opts]
  (loop [delta delta, remaining remaining, records records, tctx tctx]
    (when (zero? remaining) (throw ...上限...))
    (let [{:keys [response context]} (invoke-chat kernel delta (assoc chat-opts :context tctx))
          tctx context
          calls (response/response-tool-calls response)]
      (cond
        (empty? calls)
        {:status :completed :response response :tool-context tctx :tool-calls-made records}

        (and gate (some #(= :pause (gate %)) calls))
        {:status :paused
         :loop-state {:tool-calls calls :remaining (dec remaining) :records records}
         :pending-tool (let [c (first (filter #(= :pause (gate %)) calls))]
                         {:name (:name c) :args (:input c) :tool-call c})
         :tool-context tctx}

        :else
        (let [{:keys [messages records context]} (execute-batch kernel calls gate tctx records)]
          (recur messages (dec remaining) records context))))))
```

`execute-batch`：按 gate 决策执行一批，产出中立 tool 结果消息（合并今天的 run-tools + process-agent/reject-tools）：

```clojure
(defn- execute-batch [kernel calls gate tctx records]
  (reduce
    (fn [{:keys [messages records context]} tc]
      (let [decision (if gate (gate tc) :proceed)]
        (if (= :reject decision)
          {:messages (conj messages (msg/tool-result (:id tc) (:name tc) "已拒绝执行"))
           :records  (conj records {:name (keyword (:name tc)) :result :rejected})
           :context  context}
          (let [{:keys [value context]}
                (try (invoke-tool kernel (keyword (:name tc)) (:input tc) context)
                     (catch Exception e {:value (str "错误: " (.getMessage e)) :context context}))]
            {:messages (conj messages (msg/tool-result (:id tc) (:name tc) value))
             :records  (conj records {:name (keyword (:name tc)) :args (:input tc) :result value})
             :context  context}))))
    {:messages [] :records records :context tctx}
    calls))
```

公开入口：

```clojure
(defn invoke
  [kernel messages opts]   ;; opts 新增可选 :tool-gate
  ... 确保 conversation-id（临时 UUID + finally 清理，同今天）...
  (run-tool-loop kernel (mapv msg/normalize messages) max-iter [] init-ctx
                 (:tool-gate opts) chat-opts))

(defn resume
  "从 paused 的 loop-state 继续。decision = :approved | :rejected"
  [kernel loop-state decision opts]
  (let [{:keys [tool-calls remaining records]} loop-state
        tctx (ensure-conv-id (:context opts))
        ;; approved：强制全部 :proceed；rejected：用 gate（sensitive→:reject）
        resume-gate (if (= decision :approved) (constantly :proceed) (:tool-gate opts))
        {:keys [messages records context]} (execute-batch kernel tool-calls resume-gate tctx records)]
    (run-tool-loop kernel messages remaining records context (:tool-gate opts) chat-opts)))
```

要点：
- `run-loop` 从 process-agent 删除，循环只剩 kernel 这一份。
- `run-tools` 可保留为 `execute-batch` 的无 gate 特例（对外仍暴露，供手搓外部循环）。
- pause 的 `:loop-state` 是纯数据（tool-calls + remaining + records），可序列化（为将来持久化 resume 铺路）。

## 4. 返回形状统一

`invoke` 现在统一返回带 `:status` 的 map：

```clojure
{:status :completed :response r :tool-context c :tool-calls-made [...]}
{:status :paused    :loop-state {...} :pending-tool {...} :tool-context c}
```

- 不传 gate 的调用方永远拿到 `:completed`（且 `:response`/`:tool-context`/`:tool-calls-made` 仍在）→ **向后兼容**。
- 提供便捷取值：`(response-text-of result)` / 或 agent 层封装好 `{:text ...}`。

## 5. 合并后的 Agent（simpleagent）

单一 `create-agent`，pause 能力由 `:on-pause` 触发：

```clojure
(defn create-agent
  [opts]   ;; opts: :provider :model :tools :memory :system-prompt :max-iterations :on-pause
  (let [k (common/ensure-kernel opts)]
    {:kernel          k
     :conversation-id (str "agent-" (java.util.UUID/randomUUID))
     :state-atom      (atom {:status :idle :paused-state nil})
     :settings        (select-keys opts [:system-prompt :max-iterations :on-pause])}))

;; 默认 gate：配置了 on-pause 才启用，敏感工具 → :pause
(defn- gate-of [agent]
  (when (:on-pause (:settings agent))
    (fn [tc]
      (if (some-> (kernel/find-function (:kernel agent) (:name tc)) :tool-var tool/sensitive?)
        :pause :proceed))))

(defn chat [agent message & [opts]]
  (let [result (kernel/invoke (:kernel agent) [(msg/user message)]
                 {:context (tctx agent)
                  :tool-gate (gate-of agent)
                  :system-prompts (sys agent opts)
                  :max-iterations (max-iter agent opts)})]
    (finalize agent result)))   ;; 写 state-atom；:paused 触发 on-pause；返回标准化

(defn resume [agent decision]   ;; decision :approved | :rejected
  (let [ls (:loop-state (:paused-state @(:state-atom agent)))
        result (kernel/resume (:kernel agent) ls decision
                 {:context (tctx agent) :tool-gate (gate-of agent) ...})]
    (finalize agent result)))

(defn paused? [agent] (= :paused (:status @(:state-atom agent))))
(defn reset! [agent] (memory/mem-clear ...) (reset! state-atom {:status :idle ...}))
(defn get-history [agent] (memory/mem-get ...))
```

- **kernel-agent 用法** = 不传 `:on-pause` → gate=nil → 永不暂停，`chat` 永远 `:completed`。
- **process-agent 用法** = 传 `:on-pause` → 敏感工具暂停，可 `resume`。
- `state-atom` 始终存在但简单（idle/completed/paused）；非暂停场景几乎不用。

## 6. 命名空间收敛（决策点）

三选一：
- **A（推荐）**：保留 `im.ttalk.agent.client` 单一 `create-agent`；删除 `kernel-agent` / `process-agent` 两个 ns。最干净，**破坏 API**。
- **B**：保留两个 ns 作**薄 shim**（都委托统一 agent，`process-agent/create-process-agent` = `create-agent` 注入 on-pause）。不破坏 API，但留两个名字。
- **C**：合并实现到 simpleagent，`kernel-agent`/`process-agent` 标记 deprecated 后续删。渐进。

## 7. 影响与迁移

- 删除 `process-agent/run-loop`、`reject-tools`、`tctx-of` 等（逻辑搬进 kernel）。
- `kernel/invoke` 返回新增 `:status`（兼容）；新增 `kernel/resume`、`:tool-gate` opt。
- simpleagent 测试合并/重写；examples 里 process-agent 用法改 `create-agent + :on-pause`。
- 行为等价性需测试覆盖：无 gate==今天 invoke；有 gate==今天 process-agent（safe/approved/rejected/mixed/未resume）。

## 8. 收益 vs 代价

收益：
- **消除双份工具循环**（最大收益）；
- 一个 agent 概念，pause 成为可选维度；
- pause 快照纯数据，为持久化 resume 铺路；
- `run-tools`/`reject-tools` 合并为单一 `execute-batch`。

代价：
- `invoke` 返回引入 `:status`（兼容但调用方要适应）；
- 一次中等重构 + 测试调整；
- 命名空间收敛若选 A 则破坏 API。

## 9. 待你拍板

1. 命名空间：A（删两个，单 agent）/ B（薄 shim 保 API）/ C（deprecated 渐进）。
2. gate 触发：仅 `:on-pause` 启用（本设计默认）？还是单独 `:require-approval?` 开关 + 自定义 gate 入口？
3. `kernel/invoke` 是否保留一个"永不暂停"的快路径，还是统一走 `run-tool-loop`（gate=nil 即快路径）？（建议后者，单实现）
