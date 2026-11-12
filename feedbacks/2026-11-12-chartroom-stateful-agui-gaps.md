# 观澜（chartroom）接 clj-agent-agui 有状态 runtime 的缺口（2026-11-12）

> 来源：clj-agent 对 `2026-11-11-chartroom-agent-port-gaps.md` ① 的回复（「请观澜像锦书一样接有状态 runtime，删掉
> `chartroom.agent.agui.{chat-agent,run,convert,events}`；缺什么按缺口单独报」）。观澜侧评估见 chartroom `tasks/AK-01.md` 的 AK-13 节。
> 依赖：clj-agent 0.5.456（HEAD f25a70c）。观澜的硬约束：**前端 `frontend/` 一行不改**（happy 0.2.1346 的 agui 客户端 + 观澜 workbench / approve），
> 项目库 / 配置库 **DDL 不动**（C# 版与 JVM 版互开同一个项目目录、零迁移，是验收契约）。
>
> 结论：**这一刀没迁**——①②两条不改前端做不到（或要观澜在 wire 上手改事件，那违反「AG-UI 事件用库的类型发，⛔ 手拼」），
> 先报缺口；库侧补齐后观澜再迁。其余几条是迁移时会遇到的差，一并列出供判断。

## ① interrupt 的 `metadata` 被 codec 写死，宿主没法给自己的 kind（⛔ 不改前端做不到）

`codec/interrupt-of` 无条件 `(assoc :metadata {:kind "approval"|"frontend-tool" :pendingTool …})`；暂停事件里 `:message`、`:response-schema`
都透传了，唯独 metadata 没有口子。

观澜的「提问卡」（`ask_user_question` 工具，服务端工具、走审批停）靠 **`metadata.kind = "question"`** 认：
- `frontend/src/chartroom/approve.cljs` `question-interrupt?`（135–138 行）—— 审批卡（`render-approval`）跳过提问；
- workbench 的 `send!`：挂着提问时输入框里那句话 = 这次提问的答复（走 resume，⛔ 不起新 run）。

被写成 `"approval"` 之后：提问工具卡里同时画出提问卡 + 允许 / 拒绝卡；用户在输入框里答 ⇒ 起新 run ⇒ 409 `thread_awaiting_resume`。

**建议**：暂停事件带的宿主 metadata（gate / 审批策略给的 `:metadata`）**合并**进 `Interrupt.metadata`（宿主给了 `:kind` 就用宿主的；
`pendingTool` 照补）。与 `:message` / `:response-schema` 同一处透传即可。

## ② 拒绝 / 取消时交给模型的工具结果文案写死（行为判据对不上）

clj-agent：`react` / `approval` 里拒绝 ⇒ `"已拒绝执行：" + 理由`。观澜（≙ Keelson ChatAguiAgent H6，C# 判据 ResumeTests 钉着）：
- `approved:false` 带理由 ⇒ `用户拒绝了这次调用：<理由>`；不带 ⇒ `用户拒绝了这次调用（approved=false）`；
- `status:cancelled` ⇒ `用户取消了这次调用（resume status=cancelled）`。

模型据这句决定下一步（「用户拒绝」与「策略禁止」是两种处境）。**建议**：给拒绝 / 取消结果一个文案钩子
（如 runtime / react opts 的 `:reject-note (fn [{:keys [tool reason cancelled?]}] 文本)`），缺省不变。

## ③ 续跑要靠 durable memory 拼历史，而观澜的会话库只存正文（数据布局问题，需要观澜作者拍板）

回复里写明「暂停后的 resume 仍靠 memory 拼历史（loop-state 里不存对话）」。观澜的 `project.db.messages` 是 C# 定下的契约，只落
user / assistant **正文**（工具调用与结果不落），模型看到的完整历史一直来自前端每次带上的 `messages`（无状态续跑）。
接有状态 runtime 后要跨重启续跑，就得**另起一份持久化的完整 transcript**（ChatMemory）+ PauseStore —— 落在项目 `.chartroom/` 下的新文件，
这是项目目录布局的变化（观澜规矩：改布局先问作者）。

**问库侧**：能否让 PauseStore 的暂停快照**自带续跑所需的那段对话**（或给一个「resume 不依赖 memory」的选项）？那样宿主只需持久化
PauseStore 一样东西，不必为续跑再维护一份 ChatMemory。

## ④ `react/invoke` 不把任意单次选项递给每一轮请求

0.5.456 的 DefaultChatModel 已经 config ⊕ 单次选项（谢谢），但工具循环里每一轮的请求由 `react/build-chat-opts` 现拼，只带
`:tools` / `:tool-choice` / `:system-prompt` / `:on-token` / `:cancel-token`。观澜的思考档（`:thinking-effort` / `:thinking-enabled`，
输入框旁切换、下一句生效）因此仍只能写进每轮新建的那台 ChatModel 的 config。**建议**：invoke opts 认一格 `:chat-options`（map），
原样并进每一轮的 chat 选项。

## ⑤ 观察（不要求改）

- 挂着审批时又发一句：Keelson 是流内 `RUN_ERROR INTERRUPT_REQUIRED`，clj-agent-server 是流前 HTTP 409 `thread_awaiting_resume`。
  观澜前端本来就不在挂着时起新 run，影响小；错误码大小写（`interrupt_incomplete` vs `INTERRUPT_INCOMPLETE`）前端也不认。
- 同一批里有一件要审批：clj-agent 一件都不先跑（审批之外的等续跑时跑）；Keelson 是判停那件之前的照跑。观澜判据已按 clj-agent 口径改过（AK-08）。
- `MESSAGES_SNAPSHOT` 在 `RUN_FINISHED{interrupt}` 之前：观澜前端不依赖（happy 自己的流式状态够用），迁移时可以不要。


## clj-agent 回复（2026-11-12，未提交）

①～④ 都按建议补了，都不配时行为不变；⑤ 收到，不改。

- **① interrupt 带宿主 metadata**：暂停的来源可以带 `:metadata`，codec 用 `merge {:kind 缺省} 宿主 {:pendingTool …}` 并进
  `Interrupt.metadata`：宿主给了 `:kind` 就用宿主的，`pendingTool` 始终由库补。来源有两个：
  - 审批策略（`create-agent :approval {:policy f}`）回 `{:decision :prompt :metadata {:kind "question"}}`；
  - `:callbacks :on-tool-call` 回 `{:interrupt 理由 :metadata {:kind "question"}}`。

  落在 `pending-tool :approval :metadata`，随暂停快照走，多条暂停时每条用各自的。观澜的四档策略只要在「提问」这档的决定上
  带 `:metadata {:kind "question"}`，前端 `question-interrupt?` 就认得出来。
- **② 拒绝 / 取消文案钩子**：`create-agent :reject-note (fn [{:keys [tool args call-id reason cancelled? expired?]}] 文本|nil)`
  （`react/resume` 也认同名选项），返回 nil 时用缺省的「已拒绝执行[：理由]」。server 把 `resume[].status = cancelled` 翻成
  `{:cancelled? true}`（逐条决策同样），`approved:false` 不置这个标记；审批超时转成的拒绝带 `:expired? true`。
  钩子只管 resume 时人做的决定，策略 `:forbid` 不经过它（「用户拒绝」和「策略禁止」两种处境照旧分开）。
  按 C# 判据可以这样写：
  `(fn [{:keys [reason cancelled?]}] (cond cancelled? "用户取消了这次调用（resume status=cancelled）" reason (str "用户拒绝了这次调用：" reason) :else "用户拒绝了这次调用（approved=false）"))`。
- **③ 暂停快照自带对话**：`:loop-state :transcript` 存到 assistant(tool_calls) 为止的完整历史。
  - 挂 memory 的 agent：`create-agent :pause-transcript? true` 才带；resume 时如果 store 里这一会话是空的（重启后 in-memory
    丢了），就先用它回填，store 里有内容就不动。
  - 不挂 memory（`react/invoke` 不带 store）：快照一律带。这里顺带修了一个 bug：以前 resume 后第一轮只送工具结果，模型
    看不到用户的问题。

  所以要跨重启续跑，观澜只需要持久化 PauseStore，ChatMemory 可以留在进程内。⚠ 只管续跑：重启后**再起新的一句**时，
  runtime 照旧只送最后一条 user 消息、历史从 memory 来。如果新句也要接上旧历史，得在 `:agent-fn` 里用项目库的正文给
  memory 回填，或者换持久 memory，这一步由观澜决定。
- **④ 每一轮的单次选项**：`:chat-options`（map）原样并进工具循环每一轮的 chat 选项。来源：
  - `create-agent :chat-options`（装配期）；
  - `chat` / `chat-async` / `resume` 的 opts 里的 `:chat-options`（单次），按键覆盖装配期的值；
  - `rt/start-run!` / `resume-run!` 的 opts 透传；
  - `server.agui` 的 `:input-transform` 返回 `:chat-options` 时，新 run 和续跑都会带上（只从宿主的 transform 取，客户端
    载荷里的不认）。

  循环自己管的键（`:tools` / `:tool-choice` / `:system-prompt` / `:on-token` / `:cancel-token`）以循环为准。思考档可以从
  输入框旁的开关经 `forwardedProps` → `:input-transform` → `:chat-options {:thinking-effort …}` 传下去，不用再每轮新建 ChatModel。
- **⑤**：两边口径不同的地方（409 发在流前、同一批先跑审批之外的工具、`MESSAGES_SNAPSHOT` 的位置）都保持现状。
- 测试：client `stateful_gaps_test`（④ 每一轮都带并按键覆盖；③ 无 memory 下 EDN 往返后续跑、有 memory 时空 store 回填、
  agent 加 sqlite PauseStore 跨实例续跑；② 四种拒绝 / 取消的文案、不配钩子时文案不变；① 策略和回调两个来源）；agui codec
  的宿主 kind（单条、多条、不给时与从前逐字相同）；server 走真 HTTP 的 `host_hooks_test`（`kind=question` 上 wire；
  `cancelled` 经钩子变成模型看到的那句），`resume_unit_test` 的 cancelled 断言改为带 `:cancelled? true`。
