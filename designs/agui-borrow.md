# 借鉴 Keelson：AG-UI 服务端能力补齐（2026-11-08）

> 状态：✅ 八项全部实现（见各任务完成记录）。事件持久化**不做**（2026-11-08 用户拍板），但事件存储做成可替换挂点（§8，G-08）。
> 来源：Keelson（C#，`K:` = 远端 `~/workspace/keelson/src/`）的 `Keelson.Agui` / `Keelson.Agui.Server`。
> clj 侧：`L:` = `modules/clj-agent-agui/src/im/ttalk/agent/agui/`，`E:` = `examples/copilotkit/`，`C:` = `modules/clj-agent-client/src/im/ttalk/agent/`。
> 上位文档：[docs/agent-runtime-design.md](../docs/agent-runtime-design.md)（尤其 §8.1 / §8.2）、
> [docs/hitl-timeline-design.md](../docs/hitl-timeline-design.md)、[docs/design-principles.md](../docs/design-principles.md)。

## 0. 总表

| § | 项 | clj-agent 现状 | 任务 | 本轮 |
|---|---|---|---|---|
| 1 | 审批时改参数（approveWithEdits）+ 拒绝理由透传 | responseSchema 只有 `decision` 枚举；`/info` 不报 `approveWithEdits` | G-01 | ✅ |
| 2 | 同一轮多条审批（逐条答复、INTERRUPT_INCOMPLETE / UNKNOWN / BAD_RESUME） | react 只记**第一条**被拦的调用；一个决策管整批 | G-02 | ✅ |
| 3 | SSE 心跳 | 无：空闲流被代理掐断 | G-03 | ✅ |
| 4 | 多 agent 注册表（id → runtime，404 agent_not_found，每台自己的 suggest / stop） | 路由硬编码 `["default"]`，路径里的 agent id 被忽略 | G-04 | ✅ |
| 5 | 取消原因 Stop / Superseded / Shutdown / Orphan + 可选孤儿自动停 | 只有一种取消；`:run/cancelled` 不说为什么 | G-05 | ✅ |
| 6 | 线程元数据持久化（名称、归档、agentId、时间） | 进程内 `atom {}`；会话被驱逐即从列表消失 | G-06 | ✅ |
| 7 | 入参校验 400/413、/stop 带 interrupt、/threads 过滤分页、缺槽 501 | 坏 JSON / 缺字段 → 500；/threads 不过滤不分页 | G-07 | ✅ |

依赖：G-02 → G-01（G-01 的 codec schema 是 G-02 的逐条答复形状的子集）；G-04 → G-07（路由按 agent 分派之后才谈得上 `agent_not_found`）；G-06 → G-07（过滤 / 分页 / 501 都长在线程存储上）。G-03、G-05 独立。

**分工边界**（公共约定）：`react.clj` / `simple_agent.clj` / `pause.clj` 归 runtime agent；本文涉及它们的部分只写**需求**，不改。

---

## §1 审批时改参数 + 拒绝理由透传（G-01）

### 动机

审批卡片上最常见的不是「同意 / 拒绝」二选一，而是「**差一点**」：`send-email` 收件人写错一个、`delete-order` 想改成软删。今天只能拒绝再让模型重来一轮（多花一整轮 token，且模型未必改对）。拒绝时用户写的理由（「先退款」）也到不了模型——wire 上没有它的位置，`resume-decision`（`E:http_kit_routes.clj:167-182`）只认 `decision`。

### Keelson 出处

- `K:Keelson.Agui/ChatAguiRunState.cs` `ApproveSchema`（`{approved: boolean, editedArgs: object}`，`required: [approved]`；`editedArgs` 注明「Full replacement … Not merged」）。
- 同文件 `FromAnswer`：`status=cancelled` ⇒ 拒绝；`payload.approved == false` ⇒ 拒绝，带 `payload.reason` 则「已拒绝：<理由>」；否则批准，`editedArgs` 给了就**整体替换**参数（⛔ 不合并）。

### clj-agent 现状

- **client 侧其实已经具备**：`C:react.clj:1066-1100`（`resume-approval-batch`）——`:approved` + `{:args 新参数}` 替换 pending 调用的参数；拒绝 + `{:message 理由}` ⇒ 「已拒绝执行：<理由>」。语义写在 `docs/hitl-timeline-design.md` §2.1。
  ⚠️ 这一条与下达任务时的判断不同：**单条审批**的改参数 / 理由不需要 runtime agent 先改任何东西，只缺 codec 与路由这一层的翻译。
- 缺的是 wire：`L:codec.clj:50-59` 的 `decision-response-schema` 只有 `decision` 枚举；`L:codec.clj:550` 明写「`approveWithEdits` 不报」。
- `E:http_kit_routes.clj:167-182` `resume-decision`：payload 是 map 时只看 `:decision`，**`{approved:false}` 会被判成批准**（没有 `decision` 键 ⇒ 落到 `"approved"`）。今天我们自己的 schema 不要 `approved`，所以没人踩；按 Keelson / AG-UI 通用审批形状回的客户端会踩。

### 方案

1. **codec schema**（`L:codec.clj`）：`decision-response-schema` 扩成**兼容两套**的形状：

   ```json
   {"type":"object",
    "properties":{"decision":{"type":"string","enum":["approved","rejected"]},
                  "approved":{"type":"boolean"},
                  "editedArgs":{"type":"object","description":"整体替换工具参数，不合并"},
                  "reason":{"type":"string","description":"拒绝理由，原样交给模型"}},
    "anyOf":[{"required":["decision"]},{"required":["approved"]}]}
   ```

   `decision` 留着是向后兼容（已有前端按它回）；`approved` 对齐 Keelson / 通用客户端。
2. **能力位**：`run-info` 的 `humanInTheLoop` 加 `approveWithEdits true`——**只在路由真的接了 editedArgs 之后**（同「不谎报能力位」）。做成 `run-info` 的 `:approve-with-edits?` 开关，缺省 false。
3. **路由 `resume-decision` 改法**：返回 `[decision payload]` 而不是只返回 decision：

   | 入参 | → decision | → payload |
   |---|---|---|
   | `status=cancelled` | `"rejected"` | `{:message <payload 文本>}`（有才带） |
   | `payload.approved == false` 或 `decision` 判拒 | `"rejected"` | `{:message reason}`（有才带） |
   | `payload.editedArgs` 是 map | `"approved"` | `{:args editedArgs}`（**整体替换**） |
   | 其余 | `"approved"` | nil |

   ⛔ 不再把客户端 payload 原样透传给 `resume-run!`（今天 `(when (map? payload) payload)`）：客户端的键名与 client 的 `{:args :message}` 词汇不是一回事，原样透传等于让客户端能往 resume 里塞任意键。
4. **对 client resume API 的需求**：单条审批**无新增需求**（`{:args}` / `{:message}` 已在）。一条要求写进任务：`:args` 的键是 cheshire 解析出的**关键字键**，与模型原始调用参数（同样经 JSON 解析）同形，替换后由工具的参数校验照常把关——client 侧不需要再做键规范化。若 runtime agent 后续把 `{:args}` 改成合并语义，本条要跟着改（Keelson 明确不合并）。

### 不做什么

- 不做「部分参数合并」（Keelson 明确不合并：合并会让「删掉某个参数」无法表达）。
- 不给前端工具（`metadata.kind = "frontend-tool"`）这套 schema——它要的是**结果**不是决策（`L:codec.clj:38-48`）。

### 与 design-principles 的关系

§1 四问：现在有人要用（CopilotKit `useHumanInTheLoop` 的审批卡片就带编辑态）；不建的话用户**做不到**（wire 上没有位置）；换来的是能力；触发条件写得出。client 侧能力已存在，本项只是把它接到 wire 上，没有新的框架字段。

### 测试方案

codec：schema 形状、`approveWithEdits` 开关；路由：`resume-decision` 五行真值表（纯函数，examples 离线测试）；端到端：mock provider + sensitive 工具，`resume[]` 带 `editedArgs` ⇒ 工具收到的是替换后的参数；带 `approved:false, reason` ⇒ 模型收到「已拒绝执行：<理由>」。

---

## §2 同一轮多条审批（G-02）

### 动机

模型一轮里并发调两个敏感工具（「给 A 和 B 各发一封邮件」）。今天：只有**第一条**被当成 interrupt 发出去，用户点一次「同意」，**两封都发了**——第二封用户根本没看见。点「拒绝」则两封都拒（`C:react.clj:1089-1096`：`:approved` 是 `(constantly :proceed)`，拒绝是「gate 判敏感的全拒」）。这是**越权**，不只是体验问题。

### Keelson 出处

`K:Keelson.Agui/ChatAguiRunState.cs`：
- `Judge`：after-chat 对整轮一次性判，所有被拦的调用都进 `_gatedOrder`，`Done()` 时 `outcome.interrupts` 一次发全。
- `ResumeAsync`：`INTERRUPT_BAD_RESUME`（resume 条目解析失败）、`INTERRUPT_UNKNOWN`（答了一条不在 pending 里的）、`INTERRUPT_REQUIRED`（一条都没答）/ `INTERRUPT_INCOMPLETE`（答了一部分，剩下的按**现在的策略**仍要审批）——这四种都以 `RUN_ERROR` 收口，不叫模型、不跑工具。
- 答了的逐条按 `FromAnswer` 执行或拒绝，按 pending 顺序发 `TOOL_CALL_RESULT`。

### clj-agent 现状

- `C:react.clj:680-697`：`paused-call` 取 `(first (filter … :pause))`，`:loop-state :pending-id` 只有一个，`:pending-tool` 只有一个。
- `L:runtime.clj:216-220` `terminal-payload` / `L:codec.clj:69-101` `interrupt-of`：一个 interrupt。
- `E:http_kit_routes.clj:226-235` `matching-resume`：只找**一条**匹配的 resume 条目；`resume[]` 里有别的条目被静默忽略。
- PauseStore 快照（`docs/hitl-timeline-design.md` §3.1）是单 pending 的形状。

### 方案

**分两层，第一层不归本模块：**

1. **client（runtime agent）需求**：
   - 暂停结果多给 `:pending-tools [{:name :args :tool-call}...]`（保留 `:pending-tool` = 第一条，向后兼容）；`:loop-state` 多给 `:pending-ids [...]`（保留 `:pending-id`）。PauseStore 快照 version 升一档或做成可选键（旧快照只有 `:pending-id` 时按单条处理）。
   - resume 接受**逐条决策**：`(resume agent :per-call {:decisions {"tc1" {:decision :approved :args {...}} "tc2" {:decision :rejected :message "..."}}})`。缺席的 id 怎么办由 client 判：按当前 gate 仍要审批 ⇒ 抛 `ex-info {:type :interrupt-incomplete :pending [...]}`；未知 id ⇒ `{:type :interrupt-unknown}`。（错误类型名留给 runtime agent 定，本模块按 `ex-data :type` 映射。）
2. **agui（本模块）**：
   - `terminal-payload` 带 `:pending-tools`；`codec/interrupt-of` 变 `interrupts-of`，`outcome.interrupts` 一次发全（每条 id = 各自的 tool-call id，现有 `interrupt-id` 规则不变）。
   - `runtime/awaiting` 返回 `:pending-tools`；`/pending` 一条暂停列多条。
   - 路由：`matching-resume` 改成**全量校验**——`resume[]` 里每条都要对得上某个 pending（否则 409 `interrupt_unknown`）；解析失败 ⇒ 400 `interrupt_bad_resume`；部分答复 ⇒ 交给 client 判，client 抛 incomplete ⇒ 409 `interrupt_incomplete`（带仍待答复的 id 列表）。这三种都在**起 run 之前**判掉、回普通 JSON 错（Keelson 是起 run 后发 `RUN_ERROR`；我们的路由在起 run 前就能判，回 4xx 更早、客户端更好处理，且不会留下一条只有 `RUN_STARTED` + `RUN_ERROR` 的空 run）。
   - 能力位不变（`interrupts true` 已经在报；多条是同一协议的完整实现）。

### 不做什么

- 不在 agui 层自己拆批、自己逐条调 `resume`（那等于在 runtime 外重写一遍 react 的批执行）。
- 不在 resume 时重新问模型。

### 与 design-principles 的关系

§1：真实需求（越权）。§3：批的执行边界仍在 ChatClient/TCM 内，agui 只翻译决策——不跨边界。

### 测试方案

client：两条 sensitive 调用 ⇒ `:pending-tools` 两条；逐条决策 ⇒ 一条执行一条拒绝；漏答 ⇒ incomplete。agui：`outcome.interrupts` 两条；路由三种 4xx；端到端一批两条审批。

---

## §3 SSE 心跳（G-03）

### 动机

工具跑 40 秒（爬网页、跑 SQL）期间流上一个字节都没有。nginx 缺省 `proxy_read_timeout 60s`、云 LB 常见 30–60s 空闲超时——流被掐，而且**没有 `RUN_FINISHED`**，前端一直转圈（Keelson `AguiServerOptions.Heartbeat` 注释原话）。`/connect` 旁观流跨 run 连续，空闲更久。

### Keelson 出处

`K:Keelson.Agui.Server/AguiHost.cs` `AguiServerOptions.Heartbeat`（缺省 15s，`Zero` = 关）、`HeartbeatFrame`（缺省注释帧 `: keelson-alive`，可换数据帧给前端看门狗用）；`/run`、`/connect`、`/suggest` 三条流都用。

### clj-agent 现状

`E:http_kit_routes.clj:92-121`（`sse-response`）、`:365-377`（`sse-detached`）：只在有事件时 `hk/send!`，没有任何保活。

### 方案（全部在 examples——SSE 是 web 层的事，design-principles §2）

- `start!` / `handler` 选项：`:heartbeat-ms`（缺省 15000；0 / nil = 关）、`:heartbeat-frame`（`(fn [] string)`，缺省 `": clj-agent-alive\n\n"`——SSE 注释帧，`EventSource` 与 `@ag-ui/client` 的解析器都跳过注释）。
- 实现：一个公开的小函数 `heartbeat!` `{:period-ms :send! :frame :idle-ms-fn}` → 停止函数。**闲够才发**：每条真实事件帧都刷新「最后发送时间」，tick 时距上次发送不足 `period-ms` 就不发（忙的流上没有多余字节）。调度用进程内一根 daemon `ScheduledExecutorService`（examples 里 `defonce`、懒建）——所有连接共用一根线程，不是每连接一根。
- 连接关闭（`on-close`）或流终态关流时停掉心跳。

### 不做什么

- 不进库：库里没有 SSE（`L:runtime.clj` ns docstring「零 web 依赖」）。
- 不做数据帧心跳的缺省：数据帧会进 `@ag-ui/client` 的事件校验，`{}` 不是合法 AG-UI 事件——想要就自己给 `:heartbeat-frame`。

### 测试方案

examples 离线：`heartbeat!` 用 20ms 周期 + 假 `send!`：空闲时发、持续有真实帧时不发、停止后不再发、`period-ms 0` 不起调度。

---

## §4 多 agent 注册表（G-04）

### 动机

一个服务挂多台 agent（「客服」用便宜模型 + 只读工具，「运维」用强模型 + 敏感工具）是 CopilotKit `<CopilotKit agent="ops">` 的常规用法，`/info` 的 `agents` 本来就是字典。今天 `E:http_kit_routes.clj:602-631` 的 `handler` 只收一个 runtime、`/info` 写死 `["default"]`，路径里的 `:id` 被正则忽略——请求 `/agent/ops/run` 也跑 default，**静默串台**。

### Keelson 出处

`K:Keelson.Agui/AguiRegistry.cs`：`Add`（同名当场抛）、`Agents`（加入顺序 ⇒ `/info` 稳定）、`Find`、`Info()`（`suggestions` = 任一 agent 支持）；`K:Keelson.Agui.Server/AguiEndpoints.cs` `FindPost`（404 `agent_not_found`）、`Suggest`（那台不支持 ⇒ 404）、`Stop`（`agent.StopAsync(threadId, run)`，缺省按停）。

### clj-agent 现状

- `E:http_kit_routes.clj:611`：`(codec/run-info agent-ids opts)`，agent-ids 恒为 `["default"]`；`:612-630` 全部按后缀正则分派，不解析 `:id`。
- `L:codec.clj:532` `run-info` 已经支持多个 id + `:descriptions`。

### 方案

库里新 ns **`im.ttalk.agent.agui.registry`**（纯数据 + 函数，零 web 依赖）：

```clojure
(def reg (registry/registry
           [{:id "default" :runtime rt1 :description "通用助手"}
            {:id "ops" :runtime rt2 :description "运维"
             :suggest? true                         ;; 这台支持 /suggest
             :stop (fn [{:keys [runtime thread-id]}] ...)}]))  ;; 可选，缺省 rt/stop! 原因 :stop
(registry/agent-ids reg)        ;; => ["default" "ops"]（加入顺序）
(registry/find-agent reg "ops") ;; => 条目 map 或 nil
(registry/stop! reg "ops" "t1") ;; => true/false
(registry/info reg opts)        ;; => codec/run-info，带 descriptions、suggestions = 任一台支持
(registry/shutdown! reg)        ;; 每个**不同**的 runtime 关一次
```

- **装配期抛**：空列表、缺 `:id` / `:runtime`、id 重名（Keelson：两台同名的表现是「列出来一台、跑的是另一台」）。
- 条目是**开放 map**：路由层自己的键（`:server-tool-names`、`:agent-spec`、`:input-transform`、`:mcp-proxy`…）原样放在条目里，registry 不解释——同 `runtime` 不懂 SSE 的分工。
- 多台可共享一个 runtime（同一套 memory），也可各自一个；threadId 的全局唯一由客户端保证（CopilotKit 生成 UUID）。

路由（examples）：
- `handler` 改收 registry：`(handler reg base-path opts)`；按 `^/agent/([^/]+)/(run|connect|suggest|approve|stop)(?:/([^/]+))?$` 解析，找不到 ⇒ **404 `{"error":"agent_not_found"}`**。
- `/suggest`：那台没 `:suggest?`（或缺 `:agent-spec`）⇒ 404 `not_found`（与 Keelson 同，客户端据 `/info.suggestions` 本就不会来）。
- `/stop/:threadId` ⇒ `registry/stop!`。
- `/pending` 遍历所有 agent；`/threads` 是 runtime 级（`agentId` 是查询参数，见 §7）。
- `start!` 单 agent 签名不变（内部建一台 `"default"` 的 registry）；新增 `start-registry!` 接任意 registry。

### 不做什么

- 不做「每台 agent 一份能力位覆盖」（`multimodal` 等仍是全局开关）：现在没有两台能力不同的真实场景，`/info` 每台 agent 照报同一份。需要时 `run-info` 的 `:capabilities` 已能整体覆盖。
- 不做 Origin 白名单谓词 / `AllowHeaders` 拼接（Keelson 放在 registry 里）：CORS 是 web 层的事，已在路由里（`cors-for`）。
- 不做运行时增删 agent（Keelson `Add` 可以随时调）：装配期一次给全，值不可变——没有热插拔的真实场景。

### 与 design-principles 的关系

§1：真实需求（多 agent 是前端协议的一等概念，今天会静默串台）。§2：registry ns 不碰 HTTP。

### 测试方案

agui 单测：构造校验（空 / 重名 / 缺键抛）、顺序、`find-agent`、`stop!` 缺省与自定义、`info` 的 agents 字典与 `suggestions`、`shutdown!` 去重。examples 离线：未知 agent ⇒ 404 `agent_not_found`、`/info` 列两台、不支持 suggest ⇒ 404。

---

## §5 取消原因 + 孤儿自动停（G-05）

### 动机

四种取消语义不同：用户按停该掐**工作**；supersede 多半是刷新后的接续；停机是进程收摊；孤儿是「没人要了」。今天 `:run/cancelled` 一律长一样，前端无法区分「你按了停止」与「被另一个页签顶掉了」（后者该提示「另一个窗口接管了对话」）；运维看日志也分不出 shutdown 杀掉的 run。
另一个真实场景：用户关掉页签，run 还在跑（设计如此：断线 ≠ 停止，§4.1）——但如果是一个要跑十分钟、烧 token 的 run，部署方希望「没人看就停」。

### Keelson 出处

- `K:Keelson.Agui/AguiRun.cs` `AguiCancelReason { Stop, Superseded, Shutdown, Orphan }`；`Cancel(reason)`：**先置原因再取消令牌**，第一个原因算数。
- `K:Keelson.Agui.Server/AguiHost.cs` `AguiServerOptions.OrphanCancel`（缺省 0 = 关）；`Drive.Subscribe/Unsubscribe`：最后一个订阅者走后起定时器，到点仍无人 ⇒ `Cancel(Orphan)`；期间有人来就撤销。

### clj-agent 现状

- `L:runtime.clj:322-327` `request-stop!*`：无原因；`L:event.clj:99-105` `request-stop!` 只记布尔。
- supersede（`L:runtime.clj:355-358`）、`stop!`（`:495-511`）、`shutdown!`（`:584-606`）走同一条无原因的路。
- `L:codec.clj:148` `:run/cancelled` → `RUN_FINISHED result.status "cancelled"`。
- 订阅退订（`L:runtime.clj:577-582`）只摘回调，不看「还剩几个」。

### 方案

- `event/request-stop!` 多收一个 `reason`（关键字，缺省 `:stop`），记在发射器的 `:finalize` 里，**第一个原因算数**；新增 `event/stop-reason`。
- runtime：
  - `stop!` 加 4-arity `(stop! rt conv-id run-id reason)`；旧的两个 arity 原因 = `:stop`。
  - supersede ⇒ `:superseded`；`shutdown!` ⇒ `:shutdown`；孤儿 ⇒ `:orphan`。
  - `:run/cancelled` 载荷带 `:reason`；`run-status` 多给 `:cancel-reason`（在跑但已登记取消时也看得到——「正在停止（被顶替）…」）。
  - 新选项 **`:orphan-cancel-ms`**（缺省 0 = 关，与上游同：没人看的 run 照样跑完）。退订（含订阅者抛异常被摘）后若订阅表空、且有 in-flight run ⇒ 用 `CompletableFuture/delayedExecutor` 排一个延时任务；**代数（generation）计数**：每次订阅 / 退订 +1，延时任务只在代数没变、订阅表仍空、且还是同一个 run 时 `stop!` 原因 `:orphan`。不建后台线程（JDK 的公共延时执行器），与 `sweep!`「不为它长一根线程」同一取舍。
  - ⚠️ 只盯**订阅者**，不盯「有没有 HTTP 连接」——runtime 不知道连接（§2），而 `/run` 的 SSE 本身就是一个订阅者，语义一致。
- codec：`RUN_FINISHED.result` 多一格 `reason`（`"stop"` / `"superseded"` / `"shutdown"` / `"orphan"`）。`result` 是协议里的自由字段，多一格不破坏客户端。
- 路由：`start!` 透传 `:orphan-cancel-ms`。

### 不做什么

- 不把原因塞进 core 的取消令牌（`streaming/make-cancel-token` 归 core，且工具侧今天没有读原因的消费者）。agent 侧要分辨，读 `rt/run-status` 的 `:cancel-reason`。
- 不做 Keelson 的 `Abandon`（「客户端走了」显式 API）：孤儿定时器覆盖了它，且 runtime 本来就没有「连接」概念。
- 不发额外事件：原因随终态一起走。

### 与 design-principles 的关系

§1：前端区分「被顶替」与「被停止」、部署方的孤儿止损都是写得出的场景；缺省行为不变（孤儿停关着、原因只是多一个键）。

### 测试方案

agui 单测：`stop!` ⇒ `:reason :stop`；supersede ⇒ 旧 run `:superseded`；`shutdown!` ⇒ `:shutdown`；第一个原因算数（先 stop 再 shutdown 仍是 `:stop`）；孤儿：50ms 内无人 ⇒ `:orphan`；退订后马上又有人订阅 ⇒ 不停；缺省关 ⇒ 退订后 run 照跑完。codec：`result.reason`。

---

## §6 线程元数据持久化（G-06）

### 动机

`/threads` 是 CopilotKit 侧栏的数据源。今天（`docs/agent-runtime-design.md` §9.17 写明的三条边界）：会话空闲 30 分钟被驱逐 ⇒ **从列表消失**（历史还在 ChatMemory，却点不进去）；名字与归档只活在进程内，重启即失。用户第二天打开页面，侧栏是空的——但 sqlite ChatMemory 里明明有昨天的对话。

### Keelson 出处

- `K:Keelson.Agui/AguiThreadStore.cs` `IAguiThreadStore`（六个可选槽 `AguiThreadSlots`，缺槽 ⇒ 501）；`ListAsync(query)` 原样收 `agentId` / `includeArchived` / `limit` / `cursor`。
- `K:Keelson.Agui/InMemoryAguiThreadStore.cs`：线程记录 `{id, name, agentId, archived, createdAt, updatedAt}`；按 `updatedAt` 新的在前，`cursor` = 偏移量的字符串。
- Keelson 的进程内实现**还存消息与事件**（`IAguiThreadRecorder`）——那一半我们不跟，见下。

### clj-agent 现状

- `E:http_kit_routes.clj:498-516` `thread-record`：数据来自 `rt/run-status` + 一张 `meta-atom`；`:538-600` `handle-threads` 的列表 = `rt/conversations`（只有未驱逐的）。
- `E:http_kit_routes.clj:683` `:thread-meta (when threads? (atom {}))`。

### 方案

库里新 ns **`im.ttalk.agent.agui.thread-store`**：

```clojure
(defprotocol ThreadStore
  (ts-slots   [s])                       ;; #{:list :update :delete} 的子集
  (ts-touch!  [s thread-id {:keys [agent-id now]}])  ;; 没有就建（created=updated=now），有就刷 updated；agent-id 只在建时写
  (ts-get     [s thread-id])             ;; 记录或 nil
  (ts-list    [s {:keys [agent-id include-archived? limit cursor]}])  ;; => {:threads [...] :next-cursor str|nil}
  (ts-update! [s thread-id {:keys [name archived?]}])  ;; => 改后的记录或 nil（没这条）
  (ts-delete! [s thread-id]))            ;; => 是否删了
```

- 记录形状（中立、毫秒）：`{:id :agent-id :name :archived? :created-at :updated-at}`。wire 形状（ISO 时间、`organizationId` 占位）由路由翻译，库里不出现。
- `(in-memory-store)`：`atom` 实现；`(in-memory-store {:now f})` 可注入时钟。
- 列表语义（两个实现逐字一致，一份共享的 `list-page` 纯函数做排序 / 过滤 / 分页）：缺省**不列归档**；`agent-id` 给了只列那台；按 `updated-at` 降序、`id` 升序稳定；`cursor` = 偏移量字符串；`limit` 非正 / 非数字 ⇒ 不限。
- **sqlite 实现**：单独的 ns **`im.ttalk.agent.agui.thread-store.sqlite`**，按需 require（与 `memory.sqlite` 同款：常开连接 + `locking`）。表 `agui_threads(id TEXT PK, agent_id, name, archived INTEGER, created_at INTEGER, updated_at INTEGER)`，可与 `memory.sqlite` 共用一个库文件（表名不撞）。
  **零新依赖**：`next.jdbc` / `sqlite-jdbc` 已是 `clj-agent-client` 的依赖（`memory/sqlite.clj` 在用），agui 依赖 client，传递可得；不 require 这个 ns 就不加载 JDBC（design-principles 的「按需加载」）。
- 路由接入：`handle-run` 起 run 成功后 `ts-touch!`（带 agentId）；`/threads` 列表来自 store（驱逐后照样在），再拼上 `rt/run-status`（在跑 / 暂停态）——**store 管「有哪些线程、叫什么」，ChatMemory 管「说了什么」，runtime 管「现在在干嘛」**，三者各管一件、不重复。
- `start!`：`:threads? true` 缺省用 `in-memory-store`；`:thread-store` 可传任何实现（如 sqlite）。

### 不做什么

- **不做事件持久化**：Keelson 的 `InMemoryAguiThreadStore` 同时存消息与事件、`/connect` 能从存储补帧。我们不跟，理由是 `docs/agent-runtime-design.md` §8.1（事件缓冲是传输设施不是存储，不建第二真相店）与 §8.2（不做事件日志持久化 / durable execution）：消息的真相在 ChatMemory，暂停的真相在 PauseStore。线程表只存**元数据**——它不是任何对话内容的副本，不构成第二真相源。
  **已拍板（2026-11-08）**：事件持久化本库不做；改为把事件存储做成可替换挂点，持久化由外部库实现（§8，G-08）。
- 不存消息：`/threads/:id/messages` 照旧读 ChatMemory。
- 不自动生成线程名（Keelson 取第一条 user 消息前 60 字）：前端在 `name` 为 null 时自己显示摘要，服务端再生成一份是重复。
- 不做 `/threads/subscribe` 实时通道（照旧 404）。

### 与 design-principles 的关系

§1：「侧栏重启后为空」是用户可见的缺陷，ChatMemory 协议没有「列出会话」，用户自己补需要写一张表 + 接进路由——不是几行。协议是因为**确有两个实现**（内存 / sqlite），不是对称性（对照 `docs/agent-runtime-design.md` §8.3 的重启条件「出现第二个真实后端」）。§2：store 是纯数据，不碰 HTTP。

### 测试方案

agui 单测（两个实现跑同一组用例）：touch 建 / 刷新、agent-id 只在建时写、列表排序 / 归档隐藏 / `include-archived?` / agent 过滤 / limit+cursor 翻页到底 `next-cursor nil`、update 返回 nil（不存在）、delete；sqlite：同一库文件关掉重开仍在（「驱逐 / 重启后仍可见」）。examples：run 之后 `rt/forget!`（模拟驱逐）⇒ `/threads` 仍列出。

---

## §7 入参校验与 HTTP 面补齐（G-07）

### 动机

- `/run` 发一个坏 JSON（或空体），`json/parse-string` 抛 ⇒ http-kit **500**，日志里一段栈；缺 `threadId` ⇒ `start-run!` 拿 nil 当会话 id 照跑（nil 会话！）。客户端拿到 500 无从判断是自己错了还是服务挂了。
- 超大请求体整个 `slurp` 进内存，没有上限。
- `/stop` 只回 `{stopped: true}`；CopilotKit 客户端期待响应体里带 `interrupt`（那条 `RUN_ERROR code=STOPPED` 由前端合成，Keelson `AguiEndpoints.Stop` 照此回）。
- `/threads` 不认 `agentId` / `includeArchived` / `limit` / `cursor`（上游客户端都会发），归档的线程也照列。
- 线程面某条路背后没有数据源（没配 memory、store 不支持改名）时，今天要么 500 要么假装成功。

### Keelson 出处

`K:Keelson.Agui.Server/AguiEndpoints.cs`：`ReadInput`（解析失败 ⇒ 400 + `WireCode`，超限 `InvalidDataException` ⇒ 413 `body_too_large`；**在开 SSE 之前**回普通 JSON 错）；`Stop`（`{stopped:true, interrupt:{type:"RUN_ERROR", message:"Run stopped by user", code:"STOPPED"}}` / `{stopped:false, message}`）；`NotImplemented`（501 `not_implemented`）；`NoThread`（404 `thread_not_found`）；`AguiServerOptions.MaxBodyBytes`（缺省 16 MiB）；`AguiHttp.WriteError`（`{"error":code,"message":msg}`）。

### clj-agent 现状

`E:http_kit_routes.clj:151-152` `read-body`（无上限、抛即 500）；`:455-463` `handle-stop`；`:538-600` `handle-threads`；`L:codec.clj:400-434` `parse-run-input`（不校验）；`L:codec.clj:642-645` `threadEndpoints` 三格同一个布尔。

### 方案

- **库**（`L:codec.clj`）：`(run-input-problem body {:require-run-id? bool})` → nil 或 `{:error code :message msg}`：body 不是 JSON 对象 ⇒ `bad_json`；`threadId` 缺 / 非字符串 / 空白 ⇒ `missing_thread_id`；`runId` 同理 ⇒ `missing_run_id`（只对 `/run` 要求）。纯数据，不出 HTTP 状态码——状态码是路由的事。
  `run-info` 多认一个 `:thread-endpoints {:list :inspect :mutations}`（逐格报，覆盖 `:threads?` 的三格同值），`realtimeMetadata` 仍恒 false。
- **路由**（examples）：
  - `read-body` 有上限（`:max-body-bytes`，缺省 16 MiB）：多读一个字节判超限 ⇒ 413 `body_too_large`；解析失败 / 非对象 ⇒ 400 `bad_json`。统一 `error-response status code message` ⇒ `{"error","message"}`。
  - `/run`：`run-input-problem` 有问题 ⇒ 400；`/connect` 要 `threadId`；`/suggest` 坏 JSON ⇒ 400（保留原 `no_messages`）。
  - `/stop`：停了 ⇒ `{stopped:true, interrupt:{type:"RUN_ERROR", message:"Run stopped by user", code:"STOPPED"}}`；没停 ⇒ `{stopped:false, message:"没有在跑的 run"}`。
  - `/threads` 列表：查询串 `agentId` / `includeArchived=true` / `limit` / `cursor` 原样交给 store，回 `{threads, nextCursor}`。
  - **缺槽 501**（`{"error":"not_implemented"}`）：store 无 `:list` ⇒ 列表 501；无 `:update` ⇒ 改名 / 归档 501；无 `:delete` ⇒ DELETE 501；没配 memory ⇒ `/messages` 501。`/info.threadEndpoints` 按同一份槽位逐格报（list = `:list`；inspect = 有 memory；mutations = `:update` 与 `:delete` 都有）。
  - 改名 / 归档目标不存在 ⇒ 404 `thread_not_found`；`PATCH /threads/:id` 与 `POST` 同义（上游用 PATCH，`{archived:false}` 即取消归档），CORS 方法表加 `PATCH`。

### 不做什么

- 不强制改动类请求体带 `agentId`（Keelson 缺了回 400）：我们的线程 id 全局唯一，`agentId` 只用于过滤；强制它只会让现有前端（不带）的改名失败。
- 不做 Keelson 那套逐路由 405 + `Allow`：只有 `/threads/:id` 上认不出的方法回 405，其余认不出的落到 404，足够。
- 不做单路由退路（`POST {prefix}` 带 `{method, params, body}`）：CopilotKit v2 用多路由，没有真实调用方。

### 与 design-principles 的关系

§2：校验函数在 codec（协议知识），状态码 / 读体上限在 examples（web 层）。§1：500 → 400 是用户可见的错误归属修正，不是防御性机器——**非法输入在边界处拒绝**，与「装配期抛」同一口径。

### 测试方案

agui 单测：`run-input-problem` 真值表、`run-info :thread-endpoints`。examples 离线（直接调 `handler`，不起 server）：坏 JSON / 非对象 / 缺 threadId / 缺 runId ⇒ 400；超限 ⇒ 413；未知 agent ⇒ 404；`/stop` 两种响应体；`/threads` 过滤 / 归档隐藏 / 分页；缺槽 501；驱逐后仍列出。

---

## §8 事件存储可替换挂点（G-08）

### 动机

用户拍板（2026-11-08）：**事件持久化不做，但逻辑要能整个替换**——将来由外部库直接提供持久化实现，
就像 Keelson 的卫星包 `Keelson.Agui.Bitcask` 之于 `Keelson.Agui`。原来的会话环形缓冲写死在
`runtime.clj` 的会话条目里（`:buffer` atom + `buf-add!` / `buf-since`），外部想换只能改库。

### Keelson 出处

- `src/Keelson.Agui/AguiThreadStore.cs`：`IAguiThreadStore`（六个可选槽）、`IAguiThreadRecorder`；
- `IAguiThreadEventStore.GetEventsAfterAsync` / `IAguiThreadSequenceRecorder`（线程内序号、last-seq 补发）；
- `src/Keelson.Agui.Bitcask/BitcaskAguiThreadStore.cs`：同时实现以上几个接口的卫星后端，重开同一路径历史仍在。

### 方案

新 ns `im.ttalk.agent.agui.event-store`：

```clojure
(defprotocol EventStore
  (es-append!  [s conv-id event])   ; 会话锁内同步调：要快、不许抛
  (es-since    [s conv-id since])   ; {:events [...] :gap? bool}
  (es-events   [s conv-id])         ; 只读端点
  (es-last-seq [s conv-id])         ; 新会话条目接上序号（重启后 :seq 不从 0 重来）
  (es-release! [s conv-id])         ; 驱逐 / forget：进程内实现释放，持久化实现不动数据
  (es-delete!  [s conv-id]))        ; 删除线程：真删
```

- 缺省实现 `ring-store {:buffer-size 512}` = 原环形缓冲，行为逐字不变；`es/since-of` 供实现方复用 gap 语义。
- runtime 新选项 `:event-store`（不给则 `(ring-store {:buffer-size ..})`）；`:seq` 由 runtime 分配，存储原样保存。
- 新 `rt/purge!`：`forget!` + `es-delete!`；examples 的删除线程 / 清空线程路由改用它。`forget!` 与空闲驱逐只调 `es-release!`。
- 存储抛异常：runtime 记日志吞掉（append 不打断 run；since 读失败按 gap 处理走 resync；last-seq 失败从头编号）。

### 不做什么

- **本库不带任何持久化实现**（不建 sqlite / 文件版事件日志）——那正是要留给外部库的部分。
- 不改变 §8.1 的真相分工：即便插了持久化存储，`:since` 补不上时仍以 ChatMemory 快照 resync；
  事件存储只是回放设施，不是对话真相。
- 线程元数据仍走 G-06 的 `ThreadStore`；外部持久化库通常同时实现 `ThreadStore` 与 `EventStore`（同 Bitcask 版）。

### 与 design-principles 的关系

§1「无真实需求不建」与 `docs/agent-runtime-design.md` §8.3 原本要求「出现第二个真实后端再抽协议」；
本条由用户显式拍板（外部持久化后端是既定方向），记为对 §8.2 / §8.3 的修订，见该文档 §8.2 补注。

### 测试方案

`event_store_test.clj`：环形缓冲语义（有界、gap、last-seq、release）；模拟外部持久化实现插入后
「重启」（新建 runtime）事件仍可读、`:since` 从存储补发不走 resync、新 run 的 `:seq` 接着走、
`forget!` 不删 / `purge!` 才删；存储全抛异常时 run 照常完成、订阅者照常收到实时事件。

## 附：实现与设计的偏离

逐条见 `tasks/G-03.md`～`tasks/G-07.md` 的「完成记录」。要点：

- G-04：`handler` 签名改为 `[reg base-path & [opts]]`（仓内唯一调用方是 `start!`）。
- G-05：孤儿定时器比较订阅代数而非撤销定时器。
- G-01：核对后发现单条审批的改参数 / 理由 client 侧**已具备**，不依赖 runtime agent（见 `tasks/G-01.md`）。
