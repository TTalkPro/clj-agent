# 待完成任务

> 本文件按「轮次」堆叠：最新一轮在最上面，历史轮次原样保留（含结案说明）。

---

# 【当前轮】新建 `clj-agent-agui` 模块：原生 runtime 后台机制（2026-09-03）

> 设计：[`docs/agent-runtime-design.md`](docs/agent-runtime-design.md)（本轮施工单据此展开）。
> 起因：借鉴 `~/workspace/CopilotKit` `packages/runtime` v2 的「run 与 HTTP 请求解耦」
> 机制并**原生实现**，从而不需要它的 remote agent（也不需要那个 Node runtime）。
> 范围定调（用户拍板）：**整块新建模块 `clj-agent-agui`，不改既有 agent**——
> 框架侧只动设计文档 §6.1 判定为「绕不开」的那一处。
>
> 测试基线：416 tests / 1876 assertions（三模块）→ **收尾 458 tests / 2066
> assertions / 0 failures（四模块）**。
> 状态：**✅ 12 项全部完成 + 验收通过 + 真机验证通过**（2026-09-03）。唯一未做的是
> 真 CopilotKit React 前端联调——那要一个前端工程，不在本仓库里（设计文档 §9.4）。

## S0 — 前置：框架侧唯一的改动 + 模块骨架

- [x] **1. `resume` / `resume-async` 接 `:on-token` / `:cancel-token`**（§6.1，P0）：
      `resume-prep` 构造的 opts 从来没有这两个键，导致 HITL 第二段（审批后的续跑，
      往往正是最终答案）不流式、不可取消。新增 4-arity `[agent decision payload opts]`，
      旧调用一字不动。
- [x] **2. 四个入口共用一份 opts 透传**（§6.2）：把 `:on-token` / `:cancel-token`
      收进 `build-invoke-opts`，`chat-stream` 那两行手动 assoc 随之删掉；
      `chat-async` 因此**同时获得流式与取消**（这正是 6.1 那个洞的同一个根）。
- [x] **3. 新建 `modules/clj-agent-agui/` 并登记五处**：`deps.edn`（paths + test）、
      `tests.edn`、`bb.edn` 的 modules 表、`build.clj` 的 modules 表、
      `modules/README.md`、CI matrix。build.clj 的 `:override-core?` 布尔位
      泛化成 `:override-libs`（agui 同时依赖 core 与 client，只覆盖 core 会让
      pom 缺 client 依赖）。

## S1 — 骨架：事件模型 + 注册表（无 AG-UI，可独立验收）

- [x] **4. `agui/event.clj`**：中立事件 + 发射器。`:seq` **会话级**单调（不是每 run
      重置——§4.2 契约 1 施工时改的：HITL 下一次对话跨多个 run，订阅挂在会话上）；
      开集合跟踪（未闭合的 message / tool）；终态良构（§4.6）：已有终态则**什么都不补**，
      否则先补关再发终态；终态类型取自**本 run 自己**的停止意图 holder。
      **发射器自身永不抛**（§6.3：callbacks 的吞异常语义对事件流是错的）。
- [x] **5. `agui/emit.clj`**：接线。`on-token` → `:message/delta`；`:iteration` filter
      （请求侧 `:context` 取 state、响应侧 `:messages` 取带 `tool-call-id` 的结果）
      → `:tool/*` + `:state/snapshot`；`:on-llm-result` callback → `:tool/started`+`:args`
      （**tool-call-id 只有这里拿得到**：`:on-tool-call` 与 `:tool` 链的请求对象都没有 id）。
- [x] **6. `agui/runtime.clj`**：`runtime` / `start-run!` / `resume-run!` / `subscribe` /
      `stop!` / `run-status` / `shutdown!`。一会话一把锁一个 agent 一个 in-flight run。

## S2 — 生命周期完备

- [x] **7. 并发策略**：`:reject`（缺省）/ `:supersede`；被 supersede 的旧 run 落
      `:run/cancelled` **而不是 `:error`**（CopilotKit 的 `RunFinalizeControl` 坑）。
- [x] **8. 跨请求 HITL**：`resume-run!` 凭 conv-id 恢复；暂停中的会话
      `:awaiting-resume`，此时 `start-run!` 拒绝（§4.4，待拍板项 1 拍 (a)）。
- [x] **9. `:since` 偏移续传 + 落后 `:run/resync`**（走 ChatMemory 快照，不建第二真相店）；
      有界环形缓冲 + 空闲会话驱逐 + `shutdown!` 收尾。

## S3 — AG-UI 端到端

- [x] **10. `agui/codec.clj`**：中立事件 ⇄ AG-UI 事件、中立消息 → AG-UI 消息 +
      `/info` 响应体。`:run/paused` 先走 `CUSTOM`（待拍板项 2 的 (a)）。
- [x] **11. `agui/tools.clj`**：AG-UI 前端 action → inline tool + gate
      （§7.2：**零框架改动**——inline tool 的非 `:handler` 键原样就是 schema，
      gate 判 `:pause`，前端结果经 `resume :reply` 回灌）。
- [x] **12. `examples/copilotkit/`**：离线可跑的端到端示例（假容器，不引 web 依赖）
      + 一份真 http-kit 路由示例（四条路由 + SSE 编码）。

## 验收 ✅

- [x] 四模块全绿：**454 tests / 2032 assertions / 0 failures**（新增 38 tests /
      156 assertions）；agui 单模块 34 tests / 137 assertions；覆盖 §9.6 的 14 条清单
- [x] `bb check-docs` 全绿（7 个 README / 73 个 ns / 21 份 docs）
- [x] `examples/copilotkit/agui_example.clj` 离线跑通六场景（流式 / 工具 /
      断线重连 / 停止 / 审批 HITL / 前端工具），并入 `run_all_examples`
- [x] 框架侧回归钉住：`modules/clj-agent-client/test/.../resume_opts_test.clj`
      （resume 流式 / resume 取消 / chat-async 流式 / chat-async 取消）
- [x] **真机验证**（`examples/copilotkit/agui_live_test.clj`，MiniMax-M2.7 真实端点）
      六场景全过：流式 54 块真 token / 工具调用 id 全程串得起来 / 断线重连（6 字处
      断开，重连补齐 424 字，seq 连续无洞）/ 停止（100 字处停住落 `:run/cancelled`）/
      审批 HITL（续跑 20 块**流式**）/ 前端工具（`:reply` 回灌）
- [x] **真前端联调**（CopilotKit 官方 `examples/v2/react/demo` + 真浏览器 + 真
      MiniMax，**全程没起 Node runtime**）：demo 自带 dev-only 逃生口
      `NEXT_PUBLIC_COPILOTKIT_RUNTIME_URL`，指到我们的进程即可，它一行都不用改。
      跑通：流式渲染 / `get-weather` 工具卡片 / 敏感工具暂停（卡片停在 inProgress）/
      **另一个请求**凭 conversation-id 审批 → 工具真执行 → `/connect` 看到续跑的
      `TOOL_CALL_RESULT` 与最终回答 / CopilotKit suggestions 自动工作（它的
      `copilotkitSuggest` 走的就是我们的前端工具通道）

## 联调挖出来的五个问题（全部已修，设计文档 §9.10）

单测与 live 脚本全绿也照不到这些——它们全在「协议对接」那一层：

- [x] **1. `/info` 的 `agents` 必须是以 id 为键的字典**，我们发的是数组 →
      客户端 `Object.entries` 把下标当 agent id，去请求 `/agent/0/run`
- [x] **2. `/stop` 的 threadId 是路径段** `/stop/:threadId`，不是请求体 → 404
- [x] **3. `AGUIError: First event must be 'RUN_STARTED'`**：起 run 与订阅之间有
      真空（run 立刻起跑就发终态前的第一条，HTTP 层要等返回值才订阅）。
      `start-run!` 增返回 **`:since`**（起跑前的水位）——**这正是会话级 `:seq` 的用处**
- [x] **4. 暂停的 run 在 AG-UI 侧没有终态、流也不关** → 工具卡片永远 inProgress、
      HTTP 请求不结束。`:run/paused` 改发 `CUSTOM` + `RUN_FINISHED`；
      `/run` 终态即关流，**`/connect` 不关**（它跨 run）
- [x] **5. 会话被前端工具的暂停永久卡死**：CopilotKit suggestions 把
      `copilotkitSuggest` 塞进 `tools`，**只读 `TOOL_CALL_ARGS`、从不回结果**，
      会话于是永远 `:awaiting-resume`，挡住后续所有消息（输入框敲了字发不出去）。
      `start-run!` 增 `:discard-pause?`——**缺省仍是拒绝**（§4.4 的取舍不变），要丢就显式说
- [x] 顺带两条**不属于 AG-UI** 的端点：`POST …/approve`、`GET …/pending`
      （人工审批是应用自己的事，协议里没有它）
- [ ] **未做**：给 `CUSTOM/cljagent.run.paused` 写前端 renderer（前端工程的活），
      所以 demo 上的「同意」是用 curl 打 `/approve` 代替的

## 真机验证挖出来的老 bug（已修，设计文档 §9.9）

- [x] **Anthropic 路径每个工具发两遍**：`chat-model/build-call-config` 把本次调用的
      tools **同时**塞进 config 与第三个参数，而 `anthropic/build-params` 又把
      `(:tools config)` 当「预置 wire 工具」与之 `into` 在一起。`deftool` 的 schema
      两份都合法（只是白烧 token）所以一直没暴露；**内联工具是第一个不是 wire 形态的**
      （`:parameters`），config 那份没归一化，MiniMax 当场 400
      「invalid params, function parameters is empty (2013)」。
      修法：新增 `anthropic/merge-tools`（两边都归一化 + 按 `:name` 去重），
      `params_test.clj` 四条断言钉住。OpenAI 兼容那条路只读第三个参数，本来就没这问题。
- [x] **顺带记一条实测经验**：前端工具的 `description` 要写成**可调用的动作**
      （「弹出确认对话框并返回用户的选择」）。写成「在用户浏览器里弹…」，模型会判定
      「这不归我调」，转而给你讲怎么写 JS——live 脚本里踩过，注释已就地记下。

## 施工与设计的差异（五处，已记进设计文档 §9.8）

1. `:seq` 改成**会话级**单调（不是每 run 重置）——HITL 一次对话跨多个 run，
   订阅挂在会话上，`:since` 因此能退化成一个数，正好对上 SSE 的 `Last-Event-ID`；
2. `:retain-ms` 并入 `:buffer-size`（缓冲改会话级后不再按 run 分桶）；
3. `run-status` 加 `:stopping?`——「取消已登记但还没停稳」是真实存在的窗口，
   UI 要显示「正在停止…」就得读得到；
4. `build.clj` 的 `:override-core?` 布尔泛化成 `:override-libs` 列表——agui 同时
   依赖 core 与 client，只覆盖 core 会让 pom **只缺 client 那一条**；
5. 测试的时序改用**闸门**（mock provider 的 `:hold` + 工具里的 promise）而不是
   `sleep` 赌——初版聚合跑时当场间歇性红两条。

---

# 【当前轮】异步 ChatModel / Provider 落地（2026-09-03）

> 设计：[`docs/async-chat-model-design.md`](docs/async-chat-model-design.md)（本轮施工单据此展开）。
> 上游已落地：`:turn` 级异步入口 + 链结果组合子，见
> [`docs/filter-chain-design.md`](docs/filter-chain-design.md) §2.6–§2.7。
> 测试基线：406 tests / 1820 assertions → **收尾 416 tests / 1876 assertions / 0 failures**。
> 状态：**✅ 9 项全部完成**（2026-09-03）。
>
> **范围说明**：设计文档 §2 给 P2（react 循环全链路 deferred）设了「有真实高扇出
> 需求才做」的判据。本轮按明确指示**全做**，判据条目相应改记为「已提前实施」——
> 判据本身不撤销，它仍是同类决策的参考。

## P0 — 流式：把已经异步的底子让出去 ✅

- [x] **1. `stream_client` 暴露异步出口**：新增 `post-stream-deferred`（不 `@future`，`flt/fbind` 等流结束；退避用 `async/delayed` 不 `Thread/sleep`）。取消登记函数在**进入时**从动态 var 取出闭包住——重试 attempt 跑在完成线程上，那里已看不见绑定帧（同步版靠「整个 loop 在调用线程」天然成立）。
- [x] **2. provider 流式路径透出**：`openai_compat/call-api-stream-deferred`、`anthropic/call-anthropic-stream-deferred`；`base/call-api-stream-deferred` 转发。
- [x] **3. `on-token` 线程契约声明**：`docs/token-stream-filter-design.md` 新增 §2.1（三种入口的派发线程表；**单次流内仍串行**、flush 时机与顺序不变，故 `:token-xform` 不必加锁），中英 README 各补一段警示。

## P1 — 协议与 ChatModel ✅

- [x] **4. `IAsyncLLMProvider`**（`core/model.clj`）：`call-llm-async` / `call-llm-stream-async`，无 Object 兜底；文档写明「返回值形状必须与同步版逐字相同」——旧旁路正是栽在这。
- [x] **5. `retry/run-async`**（`core/retry.clj`）：`fbind` 自递归；`async/delayed`（`CompletableFuture/delayedExecutor`）退避；判据 / 次数 / 退避曲线 / `:on-retry` 与 `run` 共用。顺带把 `delayed` 放进 `core/async.clj` 公开（异步路径「等一会儿」的唯一实现）。
- [x] **6. `IAsyncChatModel` + 兜底**（`core/chat_model.clj`）：`call-async*` / `stream-call-async*` 探测，`DefaultChatModel` 双实现（provider 无原生异步时内部也走虚拟线程兜底）；`build-call-config` / 重试 / `normalize-response` 与同步分支共用一份。
- [x] **7. `invoke-chat-async` / `invoke-chat-stream-async`**（`core/chat_client.clj`）：终端返回 deferred；顺带抽出 `chat-model-of` / `token-sink` 供四个入口共用（flush 挪进 fmap 续延，异常不 flush 的语义不变）。
- [x] **8. provider 实现 + 清理旁路**：`http/client` 加 `request-deferred` / `post-deferred`（**响应 map 形状与同步版逐字相同**，两条路径共用后处理）；`OpenAICompatProvider` 与 `AnthropicProvider` 实现 `IAsyncLLMProvider`；callback 式旁路改成 deferred 直通（同名函数保留，语义换成返回 deferred）。

## P2 — react 循环全链路 deferred ✅

- [x] **9. `run-tool-loop` 同步/异步同源**：迭代体一份（响应侧 `flt/fmap`），注入 `chat-fn`（`sync-chat-call` / `async-chat-call`）× `drive`（`drive-sync` `loop/recur` 保常数栈 / `drive-async` `fbind` 自递归）；`invoke*` / `resume*` 各按跑法选，`resume-approval` 拆出阻塞段 `resume-approval-batch` 进 `run`（校验异常也落 deferred，不同步抛给调用方）。

## 验收 ✅ 全部通过

- [x] 两条路径逐字同义：`invoke-chat` ≡ `invoke-chat-async`（参数 / ChatResponse / replay blocks）、`invoke` ≡ `invoke-async`（状态 / 文本 / memory 落库形状）
- [x] 兜底可用：`FnChatModel`（`{:chat-fn …}`）与无原生异步的 provider 都拿得到 deferred
- [x] 重试等价：注入 `sleep-fn` / `rand-fn` 后，尝试次数、退避序列 `[100 200]`、`:on-retry` 观测两条路径完全相同
- [x] 归一化不跑偏：异步返回的是 `ChatResponse` 且 `:replay-blocks` 在位；provider 收到的 messages/config/tools 与同步逐字相同
- [x] 取消：`post-stream-deferred` 的取消令牌在调用线程登记，取消后返回空响应不抛错
- [x] mock：`SyncOnlyProvider` / `AsyncProvider` 两个 record 覆盖「兜底」与「原生异步」两条分支
- [x] `clojure -M:test` **416 tests / 1876 assertions / 0 failures**（基线 406/1820，新增 10 tests / 56 assertions）
- [x] `clojure -M scripts/check_docs.clj` 全绿（6 README / 68 ns / 20 份 docs）
- [x] `examples/async_luminus_handler_example.clj` 全绿（8 会话并发 213ms vs 串行 1600ms）
- [x] **真实端点 live 验证**：`examples/async_live_test.clj`，`ASYNC_LIVE_PROVIDER` 切换，**两套 `IAsyncLLMProvider` 实现各跑一遍**，各六场景全过：
      - `minimax`（MiniMax-M2.7 → `AnthropicProvider`）：派发 0.8ms / 整轮 11894ms；流式 2 块；并发总墙钟 5043ms = 最慢 5041ms（串行需 14661ms）
      - `zhipu`（**glm-5.3-flash** → `OpenAICompatProvider`）：派发 0.7ms / 整轮 14340ms；流式 60 块；模型名回显核对无静默回退；并发总墙钟 7040ms = 最慢 7040ms（串行需 14596ms）
      - 共同验到：全链路 `{:chat 2 :iteration 2 :turn 1}` 全是 deferred、on-token 确在虚拟线程且非调用线程、工具循环完成、异步流中途取消生效

## 落地中发现的三件事（设计时没预见，被测试逮到）

1. **`on-error` 必须 `exceptionallyCompose` 而不是 `exceptionally`**：重试 handler 返回的是 deferred，用 map 会得到 `deferred<deferred>`。同步 `(try … (catch t (h t)))` 本就原样返回 handler 的结果，异步补 compose 才算逐字同义（契约 C3 的隐含要求）。已写进 `IChainResult/on-error` 文档 + 回归测试。
2. **一个 `defrecord` 里同一个协议名只能出现一次**：把 `IAsyncLLMProvider` 插在 `ILLMProvider` 方法中间会让**先前那组方法变抽象**（实测 `AbstractMethodError: provider_name`）。故异步协议整块放 record 末尾，两处都钉了注释。
3. **动态 var 不跨异步边界**：取消令牌登记必须在调用线程把函数取出来闭包住，见 P0-1。

## 相关文档

- [`docs/async-chat-model-design.md`](docs/async-chat-model-design.md)（本轮设计 + 落地记录 + 测试锚点表；§2 的 P2 判据**被越过**一事有专门记录）
- [`docs/filter-chain-design.md`](docs/filter-chain-design.md) §2.6–§2.7（组合子契约与 `:turn` 级异步入口）
- [`docs/token-stream-filter-design.md`](docs/token-stream-filter-design.md) §2.1（on-token 线程契约）

---

# 【历史轮】2026-06-10 全量代码审查

> 来源：2026-06-10 全量代码审查（core / provider / 安全 / 架构 四路并行审查，关键结论经 REPL 实测复现）。
> 测试基线：`clojure -M:test` 182 tests / 707 assertions / 0 failures。

## P0 — 高危、改动局部、优先修复 ✅ 全部完成（2026-06-10）

> 测试：182 → 189 tests / 722 assertions / 0 failures。新增 7 个回归测试覆盖各修复。

- [x] **S1 examples RCE**：两处 `calculate` 改为白名单算术求值器 `safe-eval-arith`（仅 `+ - * / mod quot rem`）+ `clojure.edn/read-string`，禁用 `eval`。已验证拦截 `sh`/`getenv`/`eval`。
- [x] **BUG1 Anthropic 流式工具参数丢失**：`provider/stream/anthropic.clj` input_json_delta 累积时非字符串累加器（`{}`）视作空串起步。回归测试 `tool-use-input-accumulation-test`。
- [x] **BUG3 bailian 吞掉 HTTP 错误**：`provider/bailian.clj` do-request 对网络错误 / 4xx/5xx / JSON 解析失败均抛 ex-info（带 `:status :retryable? :request-id`），对齐 anthropic 契约。
- [x] **BUG4 deftool `:default` 失效**：`core/tool.clj` deftool 解构注入 `:or` 默认值 map（普通 + context 工具两分支）。回归测试 `tool_test.clj`。
- [x] **D1 defprovider 全局 config atom 串 key**：`provider/common/base.clj` create-provider-with-opts 改为基于默认值快照创建独立 atom；macro require-api-key? 改查实例 config。回归测试 `multi-instance-config-isolation`。
- [x] **D2 tool-choice 归一化错位**：core 只下发中立关键字（仅有 tools 时）；openai_compat / anthropic 各加 `->wire-tool-choice` 在边界翻译。回归测试 `test-service-chat-fn-tool-choice-without-tools` + 更新旧断言。
- [x] **D3 `extend-type Object` 致 `provider?` 恒真**：`core/model.clj` provider? 改用协议接口 `(:on-interface)` + 非 Object `:impls` 精确判定，保留 Object 默认实现。已验证对 map/string 返回 false。

## P1 — 中危正确性 / 文档不符 ✅ 全部完成（2026-06-10）

> 测试：189 → 194 tests / 744 assertions / 0 failures。新增回归测试覆盖 max-iterations、预构建 kernel、tool-choice wire、template、find-balanced-json、openai-compat。

- [x] **BUG5 预构建 :kernel 时 store 脱节**：`core/advisor/memory.clj` 暴露 `:store`；`core/client.clj` create-agent 在传入预构建 kernel 时复用其 memory-filter 的 store（不同/缺失时 warn）。回归测试 `prebuilt-kernel-reuses-its-store-test`。
- [x] **BUG react max-iterations 时机**：`core/react.clj` 移除 loop 顶部检查，改为「先 invoke-chat 落库上一批结果，仅当 LLM 仍要调工具却无预算时在 execute-batch 前抛出」。同时修复负数 max-iterations 无限循环。回归测试 `max-iterations-exceeded-throws-test` / `negative-max-iterations-does-not-loop-test`。
- [x] **BUG builder 注册守卫**：`provider/factory/builder.clj` 改为逐个补齐缺失的内置 provider（数据驱动 `builtin-providers`），用户先注册自定义 provider 不再屏蔽内置。
- [x] **BUG factory 默认 URL 拼接**：`provider/factory/config.clj` anthropic 去掉多余 `/v1`，mistral/ollama 补 `/v1`，与各自 defprovider 默认对齐。
- [x] **BUG OpenAI 流式 tool_calls 假定 :index**：`provider/stream/openai.clj` key 回退 `(or :index :id (count acc))`。
- [x] **BUG SSE/JSON 解析静默吞错**：`provider/stream/openai.clj` / `stream/anthropic.clj` / `http/client.clj` 三处 catch 改为 `log/warn|debug` 记录（含 data 预览），不再完全无声。
- [x] **BUG Retry-After 不受 max-delay 约束**：`provider/http/retry.clj` 取 `(min retry-after max-delay)`。
- [x] **BUG prompt/template `$`/`\` 崩溃**：`core/prompt/template.clj` 变量名用 `Pattern/quote`、替换串用 `Matcher/quoteReplacement`。回归测试见 prompt api_test。
- [x] **BUG find-balanced-json 不识别字符串字面量**：`core/converter/json.clj` 扫描跳过字符串字面量与转义。回归测试 `structured-parse-brace-in-string-test`。
- [x] **BUG 异常只取 .getMessage**：`core/tool.clj` / `kernel.clj` / `react.clj` 三处回退 `(.getName (class e))`，nil message 不再产出空错误。
- [x] **BUG prompt/protocol.clj 未 require**：`core/prompt/protocol.clj` 补 `clojure.set`/`clojure.string`；`selector.clj` 补 `clojure.set`。
- [x] **D4 文档承诺不符**：README Filter 段改写为真实 `:chat/:tool` API；**实现了 `:openai-compat` provider**（`openai_compat_provider.clj`，base-url 必填，注册进 factory）；README 流式说明改为「仅 provider 层可用，未接入 kernel/agent」。回归测试 `openai-compat-registered-and-creatable`。

## P2 — 设计 / 工程债 / 测试盲区

### ✅ 已完成（2026-06-10，局部、低风险）

> 测试：194 → 196 tests / 754 assertions / 0 failures。

- [x] **D9 工程/发布**：移除死依赖 `openai-clojure`（根+provider）与 `clj-http`（provider）；两个 build.clj scm/url 占位符改为真实仓库 `TTalkPro/clj-agent`；provider build.clj 用 `:override-deps` 把 core 的 `:local/root` 在构建期替换为 `:mvn/version`，修复发布 pom 缺失 core 依赖；README 依赖章节同步。
- [x] **并发设防（SQLite）**：`memory/sqlite.clj` 所有操作 `(locking conn ...)` 串行化，修复共享 Connection 非线程安全 + with-transaction 全局 autocommit 切换的竞态。（InMemoryStore 经核查 swap! 已原子，无需改。）client ns 补「单实例不可并发 chat/resume」声明。
- [x] **低优先级 bugs**：
  - mock `create-error-mock` 现真正抛错（函数型 mock-response 被调用）；`record-history? false` 时 get/clear 不再 NPE。回归测试 `mock-error-and-history-test`。
  - `tool/validate-args` 接入 `invoke`：缺必填参数返回明确错误而非进函数体 NPE。回归测试 `invoke-validates-required-args`。
  - `prompt/api combine` 的 `:separator` 用 `(apply concat opts)` 摊平 kwargs，不再静默失效。
  - `model/error` 未知错误类型默认 `retryable? false`（保守）。回归测试见 model_test。
  - `prompt/selector` 余弦相似度零向量分母为 0 时记 0 分，避免 NaN 排序。
  - `react/invoke` try/catch：ephemeral 会话异常路径也清理，不再泄漏 store 条目。
  - `converter/api validate` 对不支持的 parser fail-closed（返回未通过）而非谎报 `{:valid true}`。
- [x] **D7（部分）**：`http/client.clj with-retry`（会盲目重试 4xx 的重复实现）标记 `^:deprecated` 并在 docstring 警示，引导改用 `http/retry/maybe-with-retry`。

### ✅ 已清零（2026-07-16 复核结案）

> **复核结论**：此前 6 个 `[~]` **没有一个是「正在做」**——2 个活儿早干完了没改勾，
> 3 个设计文档里本就是拍板结论（TASK 没同步），1 个触发条件已到期未被触发。
> 逐条对着代码核过，不是照文字信。
>
> **模式**（值得记）：三条「暂缓」都写了触发条件（「留待 D7 顺带」「留 v0.2」），
> **条件全部到期，无一被触发去做**——按 [`docs/design-principles.md`](docs/design-principles.md)
> §1 四问判据的「触发条件写得出来吗」，这正是假想需求的定义：真需求不会在触发
> 条件打响后还没人管。故一并结案，不再挂账。

- [x] **BUG2 http-kit 伪流式 —— 全部完成**（方案见 `docs/streaming-async-design.md`）：
  *（2026-07-16 复核：10 个子项早已全 `[x]`，设计文档所称「唯一待续项 Undertow
  WebSocket 示例」实际也在——`examples/streaming/undertow_example.clj` 含完整 WS 段。
  父项此前一直挂 `[~]`，纯属没改勾。）*
  - [x] 实搜确认 http-kit 客户端流式是**官方已知限制**（issue #591，beta2 尝试后撤回），最新版仍做不了。
  - [x] 新建 `provider/http/stream_client.clj`：**java.net.http `fromLineSubscriber`** 真流式——零依赖、虚拟线程 executor、`on-token/on-complete/on-error` + `cancel`、非 2xx 走 D5 canonical error。
  - [x] **三条流式路径全部上真流式**：anthropic（anthropic/minimax/zhipu）+ openai_compat（openai/deepseek/zhipu/gemini/mistral/ollama/openai-compat）+ **DashScope 原生 SSE**（X-DashScope-SSE + incremental_output，专属 `stream/dashscope.clj` 解析器，`supports-stream? true`）；deepseek 测试改 stub 新传输。**全部内置 provider 现均支持流式**。
  - [x] **真实端点验证（MiniMax-M2.7 两个端点）**：Anthropic 端点 reasoning 分离、OpenAI 端点 token 逐个到达 + extract-text 完整。`examples/minimax_stream_test.clj`。
  - [x] **live 测试逮到并修 2 个真 bug**：`http-response->error` 嵌套 map message → ClassCastException；stream_client 缺默认 Content-Type。均加回归测试。全套单测 **192/762/0**。
  - [x] **移除 http-kit 流式死代码 + 废弃 with-retry**：`http/client.clj` 删 `stream-request`/`request-stream`/`post-stream`/`process-sse-stream`/`post-stream-async`/`stream-sse`/`parse-sse-line`/`with-retry`（605→263 行），ns 文档改为"仅非流式"；删对应死测试。该模块现只剩非流式请求。
  - [x] **stream_client 充分测试**：`stream_client_test.clj` 用 JDK 内置 `com.sun.net.httpserver` 起本地 SSE 服务（零依赖不联网），4 个集成测试覆盖真增量时序、默认 Content-Type、Authorization 透传、非 2xx→canonical error、显式 Content-Type 不覆盖、cancel 取消上游。全套 **194/757/0**。
  - [x] **kernel/SimpleAgent `chat-stream` 接入**：service 加 `:stream-fn`（不支持流式的 provider 回退同步 + 全文单 token）；kernel 加 `invoke-chat-stream`（复用同一 chat filter 链，on-token 透传 terminal）；react loop 有 on-token 时走 invoke-chat-stream；client 加 `chat-stream`。memory 在每回合流结束落库完整 assistant 消息，与同步**不分叉**。单测 3 个（流式/回退/带工具）+ **MiniMax 端到端 live 验证多轮记忆**。全套 **197/769/0**。
  - [x] **Web 集成示例（examples/streaming/，框架无关）**：为 4 个最常用 Luminus server（http-kit / Undertow / Jetty / Aleph）各写 WebSocket + SSE 示例 + README（同一"`on-token` → 各 server sink"模式 + 各自依赖 + 断连取消）。按「框架无关」硬约束：**只在 examples、web 依赖不进 core**。设计文档 §0.5 已固化。
  - [x] **chat-stream cancel 透传（断连即时中止生成）**：新增 `core/streaming.clj`（取消令牌 + 动态 var 桥）；令牌经 opts→react 循环（回合间停）、react 绑 `*register-cancel*`→provider 登记 stream_client 的在途 cancel（不改协议/config）；取消时 provider 宽 catch、cancelled? 优先返回空响应；client `chat-stream` 接 `:cancel-token`、finalize 加 `:cancelled`、再导出 `make-cancel-token`/`request-cancel!`。单测 `chat-stream-cancel-test` + **MiniMax live：取消后 ~7ms 停止、status :cancelled、不再烧 token**。示例的 WS on-close / SSE 断连均接 `request-cancel!`。全套 198/773/0。
- [x] **D5 错误模型统一 ✅ 结案**（核心 2026-06-10，方案见 `docs/error-model-unification.md`）：
  - [x] 步骤1 `exception->error` 升级为幂等枢纽（透传 canonical ex-data + 识别 UnsupportedOperationException）
  - [x] 步骤2 openai_compat / anthropic HTTP 失败改抛 canonical error（`http-response->error` + `throw!`）
  - [x] 步骤3 bailian 同步失败 + 流式不支持均抛 canonical error（不再裸抛 UnsupportedOperationException）
  - [x] 步骤6 README 错误模型说明更新
  - [x] **修复真实 bug**：provider 401 此前经 `exception->error` 被笼统归为 `:provider-error`（∈可重试集）→ 被误标 `retryable? true`；现端到端保留 `:auth-error`/`:retryable? false`/`:status 401`。回归测试 client_test/model_test/providers_test。
  - ❌ **步骤4（kernel 工具错误渲染）不做**（2026-07-16 结案）：设计文档 §7 写的本就是
    **否决理由**而非暂缓——`error.clj` 的类型表是 LLM/HTTP 取向，工具内部 NPE 经它会
    被标成 `[PROVIDER-ERROR]`，**对工具错误是误导**；故保留 P1 的 message/类名渲染，
    不强套 LLM 错误类型。TASK 此前把结论错记成待办。
  - ❌ **步骤5（converter/factory payload）不做——半个对象已蒸发**（2026-07-16 结案）：
    原定「留待 D7 顺带」，**D7 已完成而步骤5 未被触发**；更彻底的是**整个 `converter/`
    子系统已在 `c1a3ab1`「删除无消费者的 converter + prompt 子系统」中删除**——
    `json_schema.clj`/`api.clj`/`json.clj`/`protocol.clj`/`retry.clj` 全没了，
    core 里 `im.ttalk.agent.converter` 零引用。**挂了半年的账，对象自己先没了。**
    剩下的 factory 部分同样无人触发。按 §1 结案。
- [x] **D6 core 收回厂商 wire 知识 ✅ 结案**（方案见 `docs/response-path-consolidation.md`，该文状态即「✅ 全部完成，按『中立层容许别名』定调」）：
  - [x] 清掉 `call-with-tools` 后，usage/finish-reason/reasoning 归一化只剩 `model/response.clj` 单一权威来源（service/response_parser 都委托它）。
  - ❌ **彻底协议化（加 `extract-usage`/`extract-finish-reason`）不做**（2026-07-16 结案）：
    **保留 permissive 中立层（认识各家字段别名）就是 D6 的结论**，设计文档已「定调」。
    协议化是破坏性变更、收益/破坏不成比例，且**无真实需求**（按 §1 四问全落假想列）。
    原记「留 v0.2」——v0.2 已发布、0.3 也已在途，无人触发，即证其非真需求。
    **另**：同条挂的「converter/json_schema 的 provider 分发」——`converter/` 整个子系统
    已在 `c1a3ab1` 中删除，该项对象不存在，无从处理（详见设计文档 §3）。
- [x] **D7 重复抽象——死代码清理已完成**（方案见 `docs/response-path-consolidation.md`）：
  - [x] 删协议死方法 `build-result-messages`/`build-assistant-message`（协议 + Object 默认 + 4 provider record + 全部测试 reify）——`:build-result-msgs` 构造即死，历史由 `response->neutral` 中立消息构建。
  - [x] 删 `call-with-tools`（core 第 4 份冗余归一化，仅测试用）。
  - [x] 删死响应字段 `response-assistant-msg`/`:assistant-msg`（恒 nil，无读取方）+ openai_compat 独立死函数 `build-assistant-message`。
  - [x] 响应归一化从「4 份」收敛为 2 份活路径（同步 service + 流式 stream，输入不同，合理分工）。
  - [x] **双消息体系统一——已于 2026-07-10 落地（v0.2）**（2026-07-16 复核改勾）：
    `model/types.clj` **已删除**（`model/` 下现只有 message/response/error/service），
    tool-call 全库统一为 `{:id :name(字符串) :args}`，response→neutral 桥退化为形状复位。
    `scripts/check_docs.clj` 已为 `model.types` 立墓碑防文档复活。
    此前 TASK 仍记「剩余、建议 v0.2 专项」，与设计文档、代码、墓碑三方矛盾——纯属漏改勾
    （且其父项 D7 早已是 `[x]`，父子自相矛盾）。
- [x] **测试盲区（增量）**（2026-07-10 收尾）：
  - 流式整链路：已由 stream_client_test（7 个，含建链重试/非 2xx/cancel）+ dashscope_sync_test 覆盖。
  - timeout/approval filter：advisor_test 新增 2 个 deftest——超时返回/按时透传/**后台中断不泄漏线程**；
    审批的批准/拒绝短路/非敏感直通/默认 stdin 路径（with-in-str）。
  - 工厂 env 配置：新增 factory/config_test.clj（env 名规范/三级合并优先级/validate 各分支）。
    **顺带修真 bug**：get-env-var 曾生成 `OPENAI_API-KEY`（连字符，POSIX 不可设置）→
    :api-key/:base-url 的 env 读取从未生效；已改连字符转下划线 + 回归测试。
  - 并发：client_test 新增 SQLite 并发写（8 线程 ×25，locking 串行化不丢不重）+ InMemory 并发写。
  - provider record 端到端 mock：providers_test 既有覆盖，判定足够。

## P3 — 2026-07-10 优化轮

> 来源：全库四路并行分析（重复 / 死代码 / 架构债 / 性能）。
> 基线：209 tests / 841 assertions / 0 failures（httpbin flake 时 838，见下 A3）。

### ✅ 已完成（已提交 2e4c44b..25ee70a）

- [x] **流式 O(n²)**：stream/{openai,anthropic,dashscope} 累积器全改 StringBuilder 原地 append；
  on-token 契约移除 `:accumulated`（破坏性，仓库内零消费者）。
- [x] **死代码清理**：16 个零引用函数（tool/kernel/error/common/converter 门面）；
  修复 examples/test_glm_providers.clj 对已删 `call-with-tools` 的引用。
- [x] **反射清零（热路径）**：http/{client,retry,stream_client} + stream/* 开
  `*warn-on-reflection*` + 类型提示；顺带修 **`.PATCH` 方法不存在**的潜在运行时崩溃
  （HttpRequest.Builder 须走 `.method "PATCH"`）。
- [x] **HTTP/流式收敛**：三处逐字重复的流式同步编排 → `stream_client/post-stream-sync`；
  `response->error` 三份 → `http/client.clj` 一份；HttpClient 双实例 → 共享单例；
  修复 **dashscope 同步路径 JSON 双重解析**（成功响应 100% 误抛 :parse-error，bdbefd3 引入）
  + 回归测试 dashscope_sync_test.clj。
- [x] **设计文档对齐**：memory-filter-refactor（→已完成）/ onion-filter（→机制层已实施、
  下沉未做的逐决策状态表）/ streaming-async-design（→全部落地 + 本轮补记）。

### ✅ 快赢（A 组，2026-07-10 完成，未提交）

> 测试：209 → 211 tests / 847 assertions / 0 failures（httpbin flake 已根除）。

- [x] **A1 DashScope 复用 common + 修 input_schema 缺口**：删手写
  tool->dashscope-schema / extract-tool-calls / extract-text / build-tool-result，
  复用 schema.openai / response-parser / openai-compat。顺带修复 **deftool 工具
  （:input_schema）在 DashScope 下参数被静默丢成空对象**。回归测试
  `deftool-input-schema-not-dropped-test`（本地服务捕获出站请求体断言）。
- [x] **A2 tools->schemas 缓存**：新增 `common/memo.clj` `bounded`（有界、超限清空）；
  schema.{openai,anthropic} 的 tools->schemas 按 tools 列表缓存——ReAct 循环
  每轮 LLM 调用不再重转不变的工具列表。
- [x] **A3 httpbin 测试本地化**：client_test 两个外网用例改本地
  com.sun.net.httpserver 回显服务（模拟 httpbin 形状），删跳过逻辑；
  **顺带补 PATCH 回归测试**（覆盖本轮修复的 HttpRequest.Builder 无 .PATCH 崩溃）。
- [x] **A4 子 agent 虚拟线程**：subagent/manager spawn-worker! 从 clojure `future`
  （无界平台线程池）改为共享 `newVirtualThreadPerTaskExecutor` 的 `.submit`
  （仍返 j.u.c.Future，kill!/future-cancel 中断语义不变）。已验证 worker
  跑在虚拟线程 + kill 可中断。

### ✅ B 组（2026-07-10 完成，未提交）

> 测试：211 → 218 tests / 863 assertions / 0 failures。

- [x] **B1 流式建链重试**：`post-stream-sync` 加 `:retry` opt-in（同 maybe-with-retry
  约定：true=默认配置 / map=合并）；**仅当错误 retryable 且尚未流出任何 token** 时
  指数退避重试（token 已出则重试会重复内容，按原样抛错）；取消不重试。三 provider
  流式调用点透传 `(:retry config)`。3 个回归测试（503→成功 / 401 不重试 / 默认不重试）。
- [x] **B2 `*warn-on-reflection*` 全项目推广**：60 个 src ns 全部开启（脚本注入 53 个）；
  全量加载仅暴露 3 处告警并修复——`error.clj` `.getMessage` 无提示、`sqlite.clj`
  `.close` 无提示、`selector.clj` loop 自动装箱。现在任何新反射在编译期即告警。
- [x] **B4 子 agent kill!/终态语义**：kill! 同步写 `:result {:error :killed}`
  （此前 await!/result 返回 nil）；新增 `finish!` 终态守卫（同 promise + 仍 :running
  才落账）——被中断 worker 不再把 :killed 覆盖成 :failed、restart! 换代后旧 worker
  不践踏新状态。**顺带修复原有竞态**：spawn!/restart! 曾在注册表条目写入前启动
  worker，秒完成的 worker 状态永远卡 :running；改为先登记后启动。
  新增 `subagent/manager_test.clj`（4 tests，含虚拟线程断言）。

- [x] **B3 converter/prompt 子系统删除**（2026-07-10 用户拍板「全部删除」）：
  两子系统无任何 runtime 消费者（kernel/client/react/provider 零引用），仅自身测试存活。
  删除 `converter/*`（5 ns）+ `prompt/*`（5 ns）+ 对应测试目录；core 的 cheshire 依赖
  随 converter 移除（core src 已零 JSON 使用）；清理 4 处 README 引用。
  测试 218 → 196 / 798 assertions / 0 failures（减少的即被删子系统自身测试）。
  如未来需要结构化输出，provider 侧 `json_schema`/`response_format` 原生能力仍在。

### ✅ C 组 — v0.2 破坏性专项（2026-07-10 完成，未提交）

> 测试：196 tests / 798 assertions / 0 failures（root + core/client 各模块独立均绿）。

- [x] **C1 双消息体系统一**：tool-call 全库统一为 **`{:id :name(字符串) :args}`**
  （与中立消息同构；以「历史唯一真相」为准——字符串 name 经 SQLite/JSON 序列化往返
  不变形）。删除 `model/types.clj` 整个 ns（make-tool-call + 4 个字符串-role 消息
  构造器）；providers 改用 `msg/tool-call`；react/client `:input`→`:args`；
  `response->neutral` 桥退化为形状复位；provider/api 的消息 re-export 重指向中立
  构造器。**破坏性**：on-tool-call 回调收到的 name 为字符串（client 边界对偏差
  provider 做 keyword→字符串规范化兜底）。
- [x] **C2 agent 运行时下沉 `clj-agent-client`**（onion-filter 设计收尾，ns 不变）：
  client/common/react/callbacks/memory/memory.sqlite/advisor.memory/subagent 8 个 ns
  + 6 个测试文件迁至新模块；timbre/next.jdbc/sqlite 依赖随迁——**core 现为零依赖纯
  Clojure**（协议 + kernel 原语）。新模块含 deps.edn/build.clj（override-deps 发布
  模式同 provider）/tests.edn/README；root deps/tests.edn/CI matrix 同步三模块。
  **验收成立：`grep ChatMemory` 在 clj-agent-core 内零命中**。
  设计文档 onion-filter.md / response-path-consolidation.md 状态已对齐。

## P4 — 新功能（2026-07-10 启动，2026-07-11 全部撤下）

- [x] ~~Process Framework V1 / V2 / Timeline-Snapshot~~ → **2026-07-11 用户拍板
  整体删除，重新思考**。曾完整落地并全绿（V1 纯函数同步 18 tests、V2 core.async
  并行 13 tests、Timeline/Snapshot 14 tests；全套 249/1020/0），随后在
  「V2 无确定性静止点 → 对照 SK Process / MS Agent Framework 的 superstep
  checkpoint 模型」的讨论后判定设计有结构性问题，整个 `modules/clj-agent-process`
  模块（含 Timeline/Snapshot）+ root 接线（deps/tests.edn/CI/install/README）
  全部移除；core.async 依赖随之退出。
  - 代码可从 git 历史找回：V1 见 baf1994/a2a541d、Timeline 见 7bc5d64..764285c、
    V2 见 63dc926..fa13f2b（并行提交会话在删除决定前已提交）。
  - 三份设计文档（`docs/process-framework-design.md`、
    `docs/process-parallel-design.md`、`docs/timeline-snapshot-checkpoint.md`）
    保留并标注已废弃，作为 rethink 的输入。
- [x] **Agent 并发模型 rethink ✅ 结案（2026-07-17 补勾）**——子项 1-7 全部完成，
  第 8 项（多 Agent 编排层）按用户定调**不立项**（非暂缓：真需求出现时按决策阶梯
  重新评估，不是照此清单施工）。父项此前一直挂 `[ ]` 属漏勾，与子项状态矛盾。
  （原文照录 → `docs/agent-loop-concurrency-design.md`，
  2026-07-11）：核心结论——Agent loop 的并发只在 Tool 阶段（子 agent 也是工具）；
  Tool 阶段自带零成本屏障，建模为 MapReduce（map on snapshot + writes 数据化 +
  按原序纯折叠），竞态被执行模型消灭而非管理；合并语义用槽级 reducer；工具失败
  按语义/瞬态/环境/策略四类分层路由。实施台阶：
  1. [x] **execute-batch MapReduce 化（S1，2026-07-11 完成）**：同轮 tool-call
     虚拟线程并行（`:serial` 整批退化）；ToolContext 只读 + `:writes` 声明写
     + `:state-slots` 槽级 reducer 屏障折叠（`context/apply-writes`）；
     tool filter 响应收窄 `{:result (:writes)}`；delegate 8 处返回值随迁。
     破坏面见 CHANGELOG 0.3.0。新增 9 个语义测试，全套 213/870/0。
  2. [x] **屏障策略钩子（S2，2026-07-11 完成）**：`classify-exception` 分类通道
     （显式 :error-class > canonical retryable?/auth > 网络异常 > :semantic）；
     `deftool {:retry ...}` 瞬态类指数退避重试（幂等 opt-in，timeout-filter 超时
     也标 :transient）；环境类失败屏障处暂停（`:on-env-error :pause`，loop-state
     :phase :env-retry），resume :retry 重跑失败调用并按 tool-call-id 替换消息 /
     :proceed 交给模型；client HITL agent 自动 :pause、其余 :proceed。零破坏面
     （纯增量）。新增 5 测试组，全套 217/898/0。设计记录见文档 §10。
  3. [x] **HITL 持久化（2026-07-11 完成，从 S3 拆出的独立小件）**：新 ns
     `im.ttalk.agent.pause`（PauseStore 协议 + in-memory + SQLite EDN 实现）；
     `create-agent :pause-store` 暂停自动落库、终态/reset!/新 chat 自动清、
     `paused?`/`resume` 透明回落 store（跨重启恢复，API 不变）。**顺带修复
     resume context 缺口**（此前暂停前累积的 state slot 被裸 tctx 丢弃）+
     `create-agent` 透传 `:state-slots`。6 tests / 34 assertions，
     全套 223/932/0。设计记录见文档 §11。
  4. [x] **Timeline 与多分支（2026-07-11 完成）**：判定 Agent 持久状态 =
     对话历史（唯一真相，slots turn 级）→ 日志即 timeline，无需快照版本链。
     新 ns `im.ttalk.agent.timeline`：fork-as-new-conversation（前缀复制 +
     BranchStore 血缘，现有 ChatMemory 协议零改动）、rollback!/prune!/
     ancestry；暂停点全量 fork 连带复制 PauseStore 快照 → HITL 决策分支
     （两支各自 resume 不同决策）；编辑重试 = fork + 替换重发。
     **writes 进历史**（event-sourcing 伏笔）：tool-result 中立消息带
     `:writes` 元数据，只进存储、wire 层剥除（有测试钉住）。一致性不变量：
     合法 fork/rollback 点 = turn 边界/暂停点。7 tests / 39 assertions，
     全套 230/971/0。设计见文档 §12。
  5. [x] **resume 带 payload（2026-07-11 完成）**：审批 phase 三种载荷——
     拒绝带理由（结果「已拒绝执行：<理由>」）、批准改参（pending 工具替换
     args 执行）、`"reply"` 答复即结果（ask-user 模式解锁：提问工具 body
     永不执行，gate 拦截 + reply 送回答案）。决策词汇扩展进 execute-batch
     gate 契约（`{:reject 理由}`/`{:reply 结果}`）；loop-state 加
     :pending-id；env phase 显式拒收 reply。零破坏。6 tests / 27 assertions，
     全套 236/998/0。设计见文档 §13。
  6. [x] **HITL + Timeline 整合设计文档（2026-07-11）**：
     `docs/hitl-timeline-design.md`——暂停源（审批/环境失败）× resume 五种
     载荷 × 持久化跨重启 × 分支的权威参考（能力矩阵/一致性不变量/API 速查/
     已知边界），由 agent-loop-concurrency-design.md §5/§9–§13 整合而成。
  7. [x] **Turn 级 filter 链（2026-07-11 完成，Spring AI 2.0 advisor 吸收）**：
     filter 第三钩子 `:turn` 包整个工具循环（每 turn 一次；`react/invoke`
     以 run-tool-loop 为 terminal 组洋葱）。递归重入免费获得（闭包链天然
     仅下游，Spring AI 的 chain.copy 我们不需要）；硬规则 :paused/:cancelled/
     :error 透传。内置 `validation-turn-filter`（最终答案校验 + 反馈重入重试，
     以 ~30 行取回已删 converter 的核心价值）。解锁：每 turn 一次的 RAG 注入/
     guardrail/turn 级预算。零破坏。5 tests / 15 assertions，全套 241/1013/0。
     实施记录见文档 §14；**三链体系权威参考 `docs/filter-chain-design.md`**。
     resume 边界已补齐（同日）：resume 经 turn 链（:resume? 标记 + 终端
     一次性分派），resume 完成的答案同样过校验。全套 243/1021/0。
  8. [ ] 编排层（多 Agent）是否立项——**用户已定调：多 Agent 是更外层的决策，
     单 Agent 优先**；多 agent 问题空间地图已在讨论中盘点（拓扑/通信/状态/
     监督/HITL 冒泡/可观测/引擎三路线），用户判定太复杂暂弃；真有需求时按
     决策阶梯走（委派工具够不够 → handoff → HITL 冒泡 → 才到 BSP/actor）。

## P5 — Token 流变换链（`:token-xform`，2026-07-14）✅ 全部完成

> 来源：与 Spring AI `StreamAdvisor`（Flux 一等流）的对照——流式路径缺
> "逐 token 变换"（1→N / 跨 chunk 状态 / 流末 flush）。结论：filter 第四
> 钩子 `:token-xform`，值为 transducer（completion arity 天然是 end-of-stream
> flush 信号），不引 Reactor、不拆 Call/Stream 双接口。
> **权威设计：`docs/token-stream-filter-design.md`**。
> 测试：243/1021/0 → **253 tests / 1039 assertions / 0 failures**。

- [x] **机制**：`create-filter` 支持 `:token-xform`；`kernel/invoke-chat-stream`
  terminal 组合 xform 链包裹 on-token（chat filter 之后，provider 原始 token →
  xform 链 → sink）；正常完流 flush、异常不 flush、reduced? 早停、无 xform 零开销
  退化。硬边界：token 链只改交付流，不改最终 `:response`（memory/turn 用原文）。
- [x] **内置 filter**：`token-redact-filter`（无状态正则脱敏，注明跨 chunk
  限制）、`hold-release-filter`（先审后放：缓冲整流，完流 check-fn 全文，
  通过原序放行 / 不通过 emit 单个替换 token）。
- [x] **测试**：`token_xform_test.clj` 覆盖 7 个锚点（1→N flush / 异常不 flush /
  组合顺序 / hold-release 两分支 / 退化路径 / reasoning-token 透传 /
  最终响应不被变换）+ create-filter，10 tests / 18 assertions，全套回归全绿。
- [x] **文档**：`filter-chain-design.md` 加 token 链引言 + 内置表两行 +
  §4 对照表补 StreamAdvisor 行；CHANGELOG 0.3.0 条目。

## P6 — Spring AI 2.0 Advisor 全面对齐（2026-07-15）✅ 全部完成

> 来源：Spring AI 2.0.0 GA（2026-06）逐个 advisor 对照。结论：真正的缺口只有
> **一个半**——ToolSearch（全缺）与 ToolCalling 的 return-direct/eligibility
> （半缺）；其余要么早已等价拥有（`chain.copy` 递归 = 闭包链天然性质），
> 要么是刻意不跟（memory 放循环内、getOrder、advisor context map）。
> 两条旧结论被推翻（SafeGuard / RAG 本体，见下）。
> **权威设计：`docs/advisor-alignment-design.md`**。
> 测试：253/1039/0 → **292 tests / 1194 assertions / 0 failures**（零回归）。

- [x] **先补钉子**：`:chat` filter 改写 `:tools` 抵达 provider 的契约此前**无测试
  覆盖**，而 ToolSearch 完全建立在它之上——先补 3 个断言钉住再动工。
- [x] **ToolSearch**（`advisor/tool_search.clj`，≈ `ToolSearchToolCallingAdvisor`）：
  渐进式工具披露。**零新增钩子**——`search_tools` 是普通内联工具，`:writes`
  经槽 reducer（`into` 集合并）折叠进 tool-context，`:chat` filter 据此重写
  `:tools`；发现集合住在 tool-context 里 → 暂停/resume/持久化白拿正确。
  索引零依赖可插拔（`keyword-tool-index` 中文二元组切分 / `regex-tool-index`
  非法正则退化字面匹配；向量检索经 `IToolIndex` 注入）。
- [x] **return-direct + eligibility-fn**（`tool.clj`/`kernel.clj`/`react.clj`，
  ≈ ToolCallingAdvisor 的两个缺口）：整批全声明才生效（对齐 allMatch）；
  **补落库**——正常路径工具结果靠下一次 invoke-chat 落库，return-direct 没有
  下一次，不补则历史留悬空 tool_use 并被下个 turn 的 heal 整条摘掉。
- [x] **结构化输出判据**（`advisor/structured_output.clj`）：机制早有，缺的是
  判据——JSON Schema 子集校验 + 人话报错（模型据此自我修正）。core 零依赖，
  JSON 解析经 `:parse-fn` 注入。
- [x] **SafeGuard**（`safeguard-turn-filter`）：**推翻** §14.3「用户一个 chat
  filter 即可」——那句写在 turn 链之前且挂点有误（`:chat` 会每轮重查累积历史）。
- [x] **RAG 本体**（`advisor/rag.clj`）：**推翻** §4/§14.3「不跟本体」——推翻的是
  「不做本体」而非「不引 vector store」：仍零检索依赖，`IRetriever` 注入；
  本体价值在提示词编排不在检索。
- [x] **SimpleLogger / RE2**：`logging-chat-filter`（LLM 侧日志，既有
  `logging-filter` 只覆盖工具侧）、`re-reading-filter`。
- [x] **live 验证**（MiniMax-M2.7 真实 provider）：五个脚本共 **78 项行为断言**，
  均已实跑通过（各自反复跑 2–3 遍稳定）。断言一律钉机制（发出去的消息/工具集、
  LLM 调用次数、落库形状），不钉模型措辞——后者会波动，拿它当断言等于给 CI 埋雷。
  - `examples/safeguard_live_test.clj`（18 项）：拦下时**零 LLM 调用**；不落库的
    代价在真实多轮里可见（第 2 轮模型答「没有之前的记录」）；边界——工具结果里
    的敏感词照样通过（**入口守卫 ≠ 输出守卫**）。
  - `examples/return_direct_live_test.clj`（19 项）：对照组是重点——同一句合规
    话术 return-direct 逐字送达 vs 普通工具被模型改写。场景 3 用真实第二轮验证
    **补落库的修复**（模型答得出上一轮工单号 → transcript 没被 heal 摘掉）。
    另含 `:eligibility-fn` 放行/拦停对照。
  - `examples/rag_live_test.clj`（18 项）：语料全为虚构事实 → 对照组答不出、
    RAG 组答得出，grounding 才算被证明。并实跑印证「空检索不注入」这条偏离是
    对的：同一无关问题，默认 → 模型正常作诗；`:inject-when-empty? true`
    （Spring 行为）→ 模型拒答。
  - `examples/structured_output_live_test.clj`（12 项）：用「schema 要求 prompt
    里没提过的字段」触发**真实**校验失败 → 实测模型据反馈补上字段 → 通过。
    另钉死「合格只调 1 次 LLM」「耗尽恰好 2 次、原样返回」。
    **实测教训**：缺失字段必须是模型**答得上来**的——最初用
    `internal_review_code`，模型两轮反馈都补不上（无从得知该填什么），
    3 次耗尽；换 `birth_year` 后稳定 2 次收敛。这是自我修正方法本身的边界
    （Spring 隐含假设了模型有能力照做），已写进设计文档 §3。
  - `examples/toolsearch_live_test.clj`（11 项 + 冷/热缓存对照报告）。
  **跑真机推翻了三个基于单测的判断**：
  - 检索工具的**描述是 prompt 工程**：首版模型只检索一种能力就作答，另一半
    问题静默丢失（基线正确调了两个工具）。补「需要多种能力时必须为每一种各
    检索一次」后恢复；
  - 索引**缺 IDF**：「查询」「获取」这类中文常见动词与「天气」同分，搜天气
    捞出 get_holiday/get_balance。已加 IDF（全文档出现的词权重恰为 0 =
    天然停用词）+ 回归测试。**刻意不加相对分数截断**——实测真实失败是召回
    而非精确；
  - **prompt cache 会毁 token 对照**：`:input-tokens` 不含缓存命中部分，同一
    脚本跑第二遍基线会显示成「50 个工具 = 330 token」。必须用
    `input + cache-read + cache-write`。据此更正了文档里一版错误结论
    （原写「现金成本反贵 66%」实为热缓存假象；冷缓存下 ToolSearch 省 65%）。
- [x] **顺带修复**：`examples/minimax_agent_live_test.clj` 因环境变量改名
  （`MINIMAX_AUTH_TOKEN` → `MINIMAX_API_KEY`）**已失效**（启动即 exit 1）；
  改为两个变量都接受，模型名取 `minimax/default-model`。
- [x] **文档一致性门禁 + 六个 README 幽灵 API 清理**（`scripts/check_docs.clj`，
  接入 CI 的 `docs` job 与 `scripts/test-all.sh`）：排查发现文档里积了一批幽灵
  API——`:build-result-msgs`（源码明写已移除，四个 README 仍在头部 bullet 教人
  用）、`proto/call-with-tools`（协议无此方法）、`find-function` 的 `:plugin`、
  `invoke-tool` 的 `:context`、`chat-fn` 的 `:assistant-msg`、`model.types`、
  filter 的 `:order`/`:phase`/`:before`/`:after`；以及模块索引隐身：`pause`/
  `timeline` 不在 client README、DashScope 在 provider README 出现 0 次、
  `modules/README.md` 依赖图停留在 client 拆分之前。门禁四项：ns 存在 / ns 覆盖
  / 符号 resolve / 墓碑（map 键、宏选项没法 resolve，故显式登记，删 API 时补一条）。
  取舍：宁可漏报不可误报（alias 未绑定即跳过；注释与字符串先剥）。四项均经变异
  测试验证会真的失败；门禁自身的说明文档用 `<!-- check-docs:ignore-start/end -->`
  窄区间豁免（否则举反例会自我触发）。
- [x] **顺带修复**：`scripts/test-all.sh` 的 MODULES 漏了 `clj-agent-client`
  ——CI matrix 有它、本脚本没有，本地 test-all 长期静默跳过整个 Agent 运行时。
- [x] **文档**：新增 `docs/advisor-alignment-design.md`（权威对齐记录）；
  `filter-chain-design.md` §3 内置表 + §4 对照表刷新（两条推翻标注）；
  `agent-loop-concurrency-design.md` §14.3 加更正块；README（TOC/特性/内置
  filter/ToolSearch/RAG/结构化输出三节）、core README Filter API
  （**顺带修既有失效文档**：仍在写 `:order`/`:phase`/`:before`/`:after` 这套
  早已不存在的 API）、CHANGELOG 0.3.0。

## P7 — ToolCallingManager 协议（2026-07-15）✅ PR1 完成

> 来源：借鉴 Spring AI 2.0 `ToolCallingManager`，让工具执行入口升格为可注入协议。
> 修订 `advisor-alignment-design.md` §1.3《ToolCallingManager —— 我们不长这个
> 抽象》的旧立场。**权威设计：`docs/tool-calling-manager-design.md`**（v3，
> 含 Oracle review 16 项处置 + 18 条决策记录）。
> 测试：292/1194 → **296 tests / 1211 assertions / 0 failures**
> （+4 tests / +17 assertions，零回归）。

- [x] **协议落地**：`core/tool_calling_manager.clj`（新 ns）定义 `ToolCallingManager`
  协议，单方法 `execute-tool-calls [this kernel response opts]`（Spring 对齐签名，
  内部抽 tool_calls）。`Kernel` record 加第 7 字段 `tool-manager`；`build-kernel`
  接受可选 `:tool-manager`，缺省 nil 走原路径（**零行为变化**——现有 `execute-batch`
  5-arity 函数完全不动，nil-check fallback）。
- [x] **多 impl 是核心特性**：`VirtualThreadToolCallingManager`（默认，委托现有
  execute-batch）+ `SequentialToolCallingManager`（独立顺序路径）。`execute-batch`
  和 `execute-single` 退回成每个 record 的**内部 helper**——怎么实现完全是 record
  自己的选择。**多 impl 切换测试实证** VT 并行 vs Sequential 严格串行（相同工具，
  不同 manager，可观察的执行时间戳差异）。
- [x] **边界契约测试**（Oracle M4）：注入 mock manager 仍走 kernel 原语
  （`kernel/invoke-tool` / `kernel/serial-tool?` / `context/apply-writes`），
  实证 `:tool` filter 仍触发、`:serial` 仍退化、`:writes` 仍折叠——manager 不夺权。
- [x] **MiniMax live 验证**（`examples/tool_calling_manager_live_test.clj`）：
  instrumented manager wrapper 跑真实 MiniMax 工具循环，5 项机制断言（manager 被用、
  ≤2 LLM 调用、tool-call 形状正确、最终响应非空）。只钉机制不钉模型措辞。
- [x] **§1.3 同步**：`advisor-alignment-design.md` §1.3 末尾加「⚠️ 已修订」block
  （2026-07-16 重写）：三条论点逐条对账——「与 :serial / filter 重叠」已推翻
  （`:serial` 是工具作者的声明，引擎选型是部署方的事，层次不同）；
  「形状应是 :settings 单键注入」**已推翻但换了理由**（旧理由依赖 `:backend` 已作废；
  新理由：单键换线程池，manager 换引擎，三条能力级差异见专文 §2.2）；
  「出现真实需求再抽」成立且已兑现（需求 = 隔离与线程模型可换）。
- [x] **版本号对齐**：三个 `build.clj` 从 `0.2.%s` 改 `0.3.%s`，与 CHANGELOG
  `[0.3.0]` 对齐（此前 build 仍停在 0.2，与 CHANGELOG 不一致）。
- [x] **PR2/PR3 ❌ 否决**（2026-07-16 用户拍板，取代 07-15 的「⏸️ 搁置」）：
  `deftool :backend` 整条不做——**HTTP / MCP 是工具函数体内部逻辑**，框架不该知道
  工具走什么 transport（既不影响 LLM 怎么用，也不影响框架怎么调度）。
  与「搁置」的区别：搁置=有需求就照图施工；否决=**即使有 HTTP/MCP 需求也不这么做**，
  `deftool` 里直接写 `(http/post ...)` 即可。原设计文档 §5/§6 已删除，替换为
  §5《为什么不长 `:backend`》否决记录（含唯一可能重开场景：远端动态工具发现，
  且届时须重新设计、不得捡回原方案）。**代码零改动**（`deftool` / `tool/invoke` /
  `Kernel` 均未动）。**对 manager 零影响**（见下条）。
- [x] **manager 定位校准**（2026-07-16 用户拍板，文档 v5）：`ToolCallingManager`
  是**工具执行引擎**——线程模型 + 隔离边界 + 调度策略，**这一条理由本身足够**，
  且与 `:backend` 无关（manager 管「怎么执行」，`:backend` 想管「执行的是什么」）。
  §2 整章按此重写，§0 / §3 / §11（#20 作废，新增 #21）同步。
  **纠正 v4 的误判**：v4 因 `:backend` 否决而以为 manager「腿变细」，对 §1.3 的
  「`:settings` 注入 executor」让步——**错的**，差异是能力级不是偏好级：
  (a) 调度决策 `react.clj:167` 硬编码，`Executor` 接口够不着；
  (b) `SequentialToolCallingManager` 全程不构造 `Future`（`react.clj:207`），
  same-thread executor 模拟不出；(c) 池生命周期须与策略打包。
  **§2.3 新增隔离缺口记录**：`react.clj` 的 VT executor 是进程全局 `def`，
  所有 kernel 共享，无舱壁 / 无限流 / 无可关停边界。
- [x] **`ThreadPoolToolCallingManager` 补齐**（2026-07-16，隔离定位的落地）：
  有界 daemon 平台线程池，`{:pool-size N :thread-name-prefix "..."}`，缺省
  `availableProcessors`。实现 `java.io.Closeable`（`with-open` 可用）+
  `react/shutdown-tool-calling-manager!`；关停后再执行抛 `ex-info`
  （`:error-class :environment`）。**嵌套自锁不设防，改立不变量**（见下）。
- [x] **不变量：一个引擎属于一个 kernel，不跨 delegate 边界**（2026-07-16 用户拍板）：
  子 agent 自有引擎本就是默认——`delegate.clj:87` 的 subagent-config 全部来自用户
  `:subagent-fn`，`do-run` 据此全新造 kernel，父 kernel 的 `:tool-manager` **无渠道
  流入**。共享需用户亲手塞回去（踩坑，非漏洞）。**删除初版的 `*active-pools*`
  自锁保护**：跨 delegate 情形线程局部标记测不到（要修得改 subagent/manager 传播
  上下文 = 让违反不变量的用法能跑，且隔离仍软）；同线程情形无真实需求
  （`run-tools` 走全局 VT 不碰池）。**复盘**：我用「子 agent 共用会死锁」论证这个
  保护，而子 agent 根本不共享；主场景被划走后剩假想需求——刚用「无真实需求不建」
  毙掉 `:backend`，转头自己建了一个，标准要对自己也用。
- [x] **三引擎骨架抽取**（重构，行为不变）：`execute-batch-via` 统一 gate 预判 /
  `:serial` 整批退化 / writes 屏障折叠 / 结果按原序排回；引擎只挑 executor
  （nil = 内联，不构造 Future）。此前 VT 与 Sequential 各持一份 map+reduce 拷贝。
- [x] **新引擎测试**（+4 tests）：舱壁上限（pool-size 2 跑 6 工具峰值并发 = 2，
  同批 VT 引擎峰值 = 6，实证隔离差异）、独占命名线程、契约对等（`:serial` 退化 +
  writes 折叠 + 结果与 VT 引擎逐字段相同）、生命周期 + pool-size 校验。
  全套 **300 tests / 1227 assertions 通过**（基线 296/1211）。
- [x] **文档体系整理：原则提取 + 目录合并 + 索引**（2026-07-16 用户拍板，无代码变更）：
  - **新建 `docs/design-principles.md`——项目级设计原则唯一出处**（硬约束）：
    原则散在个案里 = 下份文档还得重推，故抽出集中收录，各设计文档改为**回指不重述**。
    **§1《无真实需求不建》**（新提）：本是 `advisor-alignment-design.md` §1.3 的
    「立项判据」，在 `tool-calling-manager-design.md` 里被反复重新发现（毙 `:backend`、
    毙 PR2/PR3、拆 `*active-pools*`）。含**四问判据**（现在有人用吗 / 不建用户怎么办 /
    换来能力还是"更声明式" / 触发条件写得出吗）、**落地约束**（已建抽象论据倒一条须
    重新称剩下的；防御性机器同样适用；否决方案重启须重新设计不得捡回旧稿；能立不变量
    的不建机器）、**案例法**（三次援引 + `ToolCallingManager` 自己险些踩中：假想那条
    腿当时比真实那条更有说服力）。**§2《框架无关》**：收录自 `docs/streaming-async-design.md`
    §0.5，内容不变。分工：§1 管纵向（抽象该不该存在），§2 管横向（依赖该不该跨进来）。
  - **联动改指针**：`tool-calling-manager-design.md` §0.5 与 `streaming-async-design.md`
    §0.5 双双退为指针（各自保留 doc-local 注记）；§5 / §5.4 / §4.3.1 改回指
    `design-principles.md` §1；`advisor-alignment-design.md` §1.3 标注「本节是 §1 出处，
    已提为项目级硬约束」。
  - **`design/` 并入 `docs/`**：7 份文档 `git mv` 迁移（git 全部识别为 rename，
    历史保留），`design/` 目录删除。界线本是历史形成的，且「专题 vs 主文档」无可判定
    标准，跨目录相对链接是纯税。全仓 13 处引用同步（含 `provider/anthropic.clj`、
    `provider/http/client.clj` 两个 docstring、`examples/streaming/README.md`、
    README/CHANGELOG/TASK）；移入文件里遗留的 `../docs/`、`docs/` 前缀一并归正。
    老路径 `design/xxx.md` → `docs/xxx.md` 一一对应，无重命名。
  - **新增 `docs/README.md` 索引**（16 份全收录）：按权威参考 / 已实施专题 / 进行中 /
    已废弃留档分组，沿用各文档 `状态：` 行作为「当下是否算数」的判据——对 process
    V1/V2/Timeline 三份废弃留档尤其要紧（讲得头头是道但不代表现状）。
  - **校验**：27 个 md 零断链、`design/` 零残留、索引 16/16 全覆盖（脚本校验）。

## P8 — 工具超时处理（2026-07-16）✅ 同日拍板并完成

> 来源：对照 beamai `ToolCallingManager` 的三层超时（层 1 每工具 spawn+kill /
> 层 2 gather deadline / 层 3 batch worker 兜底），问「我们能否也处理 tool 超时」。
> **权威设计：`docs/tool-timeout-design.md`**（🚧 待拍板，已进 `docs/README.md`
> 索引，check_docs 门禁绿）。基线：300 tests / 1227 assertions / 0 failures。
>
> **支点结论**：beamai 三层全部建立在 `exit(Pid, kill)` 这一个原语上——JVM 没有它
> （`Thread.stop` JDK 20+ 直接抛 `UnsupportedOperationException`，只剩协作式中断；
> 而最需要超时的 `InputStream.read` 读 socket——即绝大多数 HTTP 客户端——恰恰打不断）。
> 故只移植**策略**（超时优先级链、transient 分类→重试），不移植**结构**
> （层数、隔离边界——那是 BEAM 进程模型的倒影，照搬只能得到三层文档）。
> 我们的「超时」= **放弃等待 ≠ 终止执行**：被放弃的工具可能继续跑，其副作用可能在
> 告知 LLM「超时」之后才落地——这必须如实写进 docstring，照抄 beamai
> 「到点 kill 执行进程」的措辞即构成 API 谎言，比不做更糟。

### ✅ 分析已完成（结论在设计文档，此处记要点）

- [x] **实测证实真 bug：`deftool :timeout` 是死选项**（非推断，REPL 复现）：
  声明 `{:timeout 500}` 的工具 `Thread/sleep 3000` 跑满全程、返回 `:success true`。
  `:timeout` 仅出现在 opts 白名单（`tool.clj:210`）用于判定「首个 map 算不算 opts」，
  `:tool/timeout` 元数据**从未生成**（`:retry`/`:serial`/`:return-direct` 都有，
  独缺它），全库零读取。编译通过、无警告、零效果——**沉默 no-op，API 面上的谎言**。
  对照：beamai 的 `tool_spec.timeout` 是强制执行的（`resolve_timeout/2`）；
  我们抄了字段名，没抄行为。
- [x] **发现真 bug：`timeout-filter` 悄悄毁掉引擎线程模型**（`advisor.clj:252`）：
  `clojure.core/future` = send-off 池 = 无界**平台**线程，而 `chain` 包着其余 filter
  + 工具函数体本身 → 挂了此 filter，VT 引擎的工具实际跑在平台线程上
  （「每调用一根虚拟线程」的 docstring 不再成立）；超时后池线程释放、send-off
  线程留下继续跑 → ThreadPool 引擎的舱壁 bound 不住被放弃的工具。叠加反直觉结论：
  **最怕超时泄漏的恰是有界池引擎**（被弃任务永久占池槽，舱壁逐格坏死；VT 只漏几 KB 栈）。
- [x] **确认非 bug**：filter 链讲 `:result`、`invoke-one` 拿 `:value` 并不错位——
  `kernel.clj:226` 在边界做了映射。`timeout-filter` 的形状、挂点、`:transient`
  分类、不带 `:writes`（事务性）全部正确，问题只在线程模型与「读不到工具声明」。
- [x] **吸收**（纯策略，与进程模型无关，可移植）：超时优先级链
  「工具声明 > manager/filter 缺省 > 不超时」（beamai `resolve_timeout/2`）；
  超时归 `:transient` → 声明 `:retry` 的工具自动重试——这条链路我们**已经是通的**
  （`react.clj:75` 重试包在 filter 链外层），只差超时真的能发生。
- [x] **否决 beamai 层 2 / 层 3**——按 `design-principles.md` §1 四问**全落假想列**：
  层 3 的主要理由「防工具经 link 传播带崩调用者」在 JVM 不存在（无退出信号，
  `invoke-one` 的 try/catch 已是完备边界——**它在防一个我们没有的问题**）；
  层 2 的价值「保住部分结果」是每工具超时的自然结果（超时者自己变 transient 错误，
  同批其它工具正常返回），免费拥有；两层间的 grace 宽限协调是「有多层」自己制造的
  复杂度。真需求（「整轮工具预算 ≤ N 秒」的批级预算）出现时按其真实形状另行设计，
  不得捡回本方案。
- [x] **否决另两个落点**：`run-on-executor` 的 `(.get f)` 加超时——单工具调用走
  内联（`react.clj:205` `(<= (count tool-calls) 1)`），最常见情形完全不设防，
  「看起来有的保护」比没有更危险；`tool/invoke` 内部恒定起线程（beamai 层 1 的
  位置）——每个调用无条件加线程+Future 包装，换来一个打不断的超时，beamai 敢做
  是因为 spawn 约等于免费**且 kill 真有效**，两个前提我们都不满足。→ 走 filter
  （opt-in），不进 `tool/invoke`（恒定）。

### ✅ 落地（2026-07-16 拍板后当日完成；两项均为**修既有承诺**，不长新抽象，不触发 §1）

> **终态：314 tests / 1295 assertions / 0 failures**（起点 300/1227；core 96/363 +
> client 112/438 + provider 106/494，零反射告警，5 条墓碑）。MiniMax live 20 项断言，
> **四遍稳定**（含 filter 删除后的复验；场景 4 的 abandon 差值四次实测
> 1701/1702/1700/1700ms——恒定 ≈ CPU 循环 2500ms − 超时 800ms，测量可信）。
> 本节的演进弧：分析 → P0/P1 落地 → live 推翻 §2.2 → 修 2 个绑定 bug → §3 提级
> → code review 5 项 → 缺省机制定调（不超时 / 串行）→ timeout-filter 删除。
> 各阶段的独立计数见各小节（记录当时状态，不回改）。

- [x] **P0 修 `:timeout` 死选项**（方案 a：照抄 `:retry` 的现成路径）：
  - [x] P0-1 `deftool` 发出 `:tool/timeout` 元数据（四个 defn 分支，`tool.clj`）
  - [x] P0-2 加 `timeout-spec` 读取函数（对称 `retry-spec`；docstring 写明
    「声明本身无强制力，由 timeout-filter 消费」）
  - [x] P0-3 `build-func-def` 透传 `:timeout` 供 filter 读取（`kernel.clj`；
    inline tools 无 var 只吃 filter 缺省值——既有盲点，与 `:sensitive` 现状一致，不新增）
- [x] **P1 `timeout-filter` 重写**（`advisor.clj`）：
  - [x] P1-1 优先级链：`:function :timeout`（工具声明）> filter 构造缺省 `timeout-ms`
  - [x] P1-2 弃 `clojure.core/future` 改 `Thread/startVirtualThread`（修线程模型
    bug；顺带下游异常改为**原样重抛**，不再包 ExecutionException）
  - [x] P1-3 docstring 如实写明「放弃等待 ≠ 终止执行」+ 副作用窗口 +
    「声明 `:retry` 即承诺幂等」（deftool 元数据说明 / `timeout-spec` /
    `timeout-filter` 三处一致）
- [x] **测试全部落地**：元数据回归钉 + 端到端穿 `build-func-def`（修复前该用例
  睡满 60s）+ 「裸 invoke 不超时」语义钉（防误解为内置）；优先级三分支；
  虚拟线程 `.isVirtual` 断言 + 异常原样重抛；超时→`:transient`→`:retry` 重试
  整链（`react_test`）；超时不带 `:writes`；同批部分结果（慢者超时、快者原样返回、
  `:errors` 只含超时者）；**诚实测试**（CPU 忙循环超时返回后计数器仍在跳）。
- [x] **live 验证（MiniMax 真实 provider，`examples/tool_timeout_live_test.clj`）**：
  20 项行为断言，连跑 3 遍稳定。慢后端是**本地裸 TCP**（零依赖不联网），只有 LLM
  是真的——分工：live 验「真实模型 + 真实阻塞 IO」，单测验形状与分支。四场景：
  超时→模型理解错误→循环存活作答（2 次 LLM 调用）；**对照组**（filter 缺省 800ms、
  同为 3s 的活儿：声明 6s 的 `get-price` 拿到 `SKU-7 price: 70 CNY`，未声明的
  `get-stock-age` 被杀——优先级在真实循环里的可观察后果）；`:retry` 透明重试
  （实跑 2 次、模型只见成功结果、只 1 条 record）；**abandon 残余风险**
  （CPU 忙循环：超时上报 t=2361ms → 副作用落地 t=4059ms，晚 1.7s，其间模型已在作答）。
- [x] **跑真机推翻了一个判断（设计文档 §2.2 加修订块）**：初稿把
  「`InputStream.read()` 读普通 socket 打不断——**这条最要命**」列为残余风险主体。
  **实测 JDK 25.0.2 证明它对我们的实现不成立**：虚拟线程 + socket read + interrupt
  → 抛 `SocketException: Closed by interrupt`（**真被取消**）；平台线程才会无视
  interrupt 读完。原因是 JDK 13+ 把 `java.net.Socket` 重实现在 NIO 之上（JEP 353），
  虚拟线程上响应 interrupt 并关闭 socket——**而 P1-2 恰好把工具搬上了虚拟线程**。
  原判断是平台线程时代的常识，写文档时没实测。**后果：P1-2 的价值被文档低估**——
  它不只让被放弃的执行变便宜，更把最常见的工具形态（阻塞 IO / HTTP 调用）从
  「打不断」变成「真能取消」；§4 那个 bug 也因此**比初判更严重**（send-off 平台线程
  同时毁掉线程模型**和**取消能力）。残余风险收窄为：不检查中断标志的 CPU 密集代码、
  native 调用、吞掉 InterruptedException 的代码、工具自己 spawn 的平台线程。
  **§2.3 结论本身不变**（仍是放弃等待≠终止执行，仍无 kill 原语）。
- [x] **一处 live flake 及其教训**：场景 2 初版断言 `(= 2 (count tool-calls-made))`
  偶发失败——**模型看到超时后自行重试了 `get-stock-age`**，记录变 3 条。重不重试是
  模型的自由、不是我们的机制；改为按名分组断言「每次调用的结果形状」，不钉次数。
  又一个「断言钉机制、不钉模型行为」的实例。
- [x] **顺带逮到并修了两个动态绑定 bug**（live 之后追查「还有没有别的 bug」时
  发现；两个都是**静默**给根值——无报错、只是悄悄读错）：
  - **既有 bug：`run-on-executor` 从不传导绑定帧**。后果是同一个工具因
    **LLM 临场决定发几个 tool-call** 而看到不同的 `binding`：批内 1 个走
    `run-inline`（调用方线程 → `:acme`）、≥2 个走 executor（→ `:none`）；
    换引擎也变（Sequential → `:acme` / VirtualThread → `:none`）。
    **违反 `react.clj:247` 明写的引擎契约**「引擎只决定『怎么把这批跑完』，
    不决定『跑的是什么』」。
  - **本次引入的回归：`timeout-filter` 改虚拟线程时丢了传导**——
    `clojure.core/future` 自带绑定传导，`Thread/startVirtualThread` 没有。
    换 future 时没想到这层。
  - 两处均用 `bound-fn*` 包装任务修复（与 `future`/`pmap` 语义一致）；
    +2 tests（core 绑定可见 + 挂不挂 filter 一致；client 批次大小 1 vs 2 一致 +
    两引擎结果相同）。**两者都是设计原则 §3「边界内一致」的违反**（见下）。
- [x] **新增设计原则 §3「一个 Kernel 绑定一个 TCM，不跨边界」**（2026-07-16 用户
  拍板提为项目级硬约束，`docs/design-principles.md`）：**这个绑定就是执行边界——
  边界内必须一致**（工具可见的一切不得因批次大小 / 引擎选型 / filter 挂载而变），
  **边界外不得流通**（父 Kernel 的 TCM / 动态绑定 / ambient 状态不自动流入子 Agent；
  要传走 `subagent-config` 显式传）。
  **提级理由**：它本是 `tool-calling-manager-design.md` §4.3.1 拆 `*active-pools*`
  时立的不变量，却只以**括号举例**的形式躺在 §1.3 表格里——于是真要用时想不起来
  （我上一轮就没想起来，见下条复盘）。按本文开篇的论点「原则散在个案里的下场，
  就是下一份文档再把它重新推导一遍」，提级即对症。
  含 3.1 理由 / 3.2 两方向判据表 / 3.3 落地约束 / 3.4 案例法（4 条，含本次的
  两个 binding bug 判为「必须修」、`spawn-worker!` 判为「正确不改」——**同一条
  原则、方向相反的两个结论**）；「与两条原则的关系」章改写为三条（§2 划库与外部
  世界的边界，§3 划库内部执行单元之间的边界）。
  **测试钉住两个方向**：`react_test/binding-conveyance-across-batch-shapes-test`
  （边界内：批次大小 1 vs 2 一致 + 两引擎一致）、
  `subagent/manager_test/ambient-state-does-not-cross-delegate-boundary-test` +
  `parent-tool-manager-has-no-channel-into-subagent-test`（边界外：binding 不跨界、
  父 TCM 无渠道流入）。全套 **311 tests / 1259 assertions / 0 failures**。
- [x] **子 agent 不传导绑定 = 正确，不改**（2026-07-16 用户拍板，**由新提的
  设计原则 §3 定案**，取代此前「待定」）：子 agent 是新 Kernel + 新 TCM =
  **新执行边界**，ambient 状态本就不该隐式穿过去（§3「边界外不流通」）。
  `spawn-worker!` 不用 `bound-fn*` 是**故意的**，已加 docstring 说明 + 测试钉住
  （它看起来像疏漏，我上一轮就差点顺手「修好」）。
  **复盘（记在 §3.4）**：我最初拿 **§1 四问**答这题，结论「无具体调用方 → 假想
  需求 → 待定」——**结论碰巧不错，依据是错的**。不传导不是「还没人要」，而是
  **原则上就不该**：真需求来了 §1 的答案会翻转（有人要就建），§3 的不会
  （有人要也只给显式的 `subagent-config`，不给隐式通道）。拿错工具的原因是
  该原则当时**只是 §1.3 表格里的一个括号举例**，真要用时想不起来。
  （超时变纯 opt-in，层 3 退为纯隔离层）——从对方侧独立佐证了否决层 2/层 3 的判断：
  那两层的价值从来不在缺省截止，而在 BEAM 特有的隔离与 kill；两个体系收敛到同一
  形态：**超时是声明出来的策略，不是框架强加的缺省**。

### ✅ Code review 自查（2026-07-16，/code-review high）——5 条发现，全部实跑确认

> 测试：311/1259 → **314 tests / 1275 assertions / 0 failures**。
> **最重的两条打在自己身上**：#1「只修了一半」、#4「把 bug 挪了一步」。

- [x] **#1 内联工具的 `:timeout` 仍是死选项——只修了 var 那一半**：根因是**两个
  func-def 构造点**（var 走 `build-func-def`、inline 在 `kernel.clj` 另行硬编码）。
  实测 inline `{:timeout 300}` 跑满 3006ms 返回 "done"，而 inline 的 `:serial`/
  `:retry` 一直生效。讽刺的是 **`delegate-tool` 恰恰是内联、且跑整个子 agent**——
  最需要超时的正是拿不到超时的。**修**：新增 `kernel/tool-timeout`（var + inline，
  与 `serial-tool?`/`retry-policy`/`return-direct-tool?` 逐字同款）；
  **两个 func-def 构造点合并为一个**（新增字段只加一处，杜绝再漏）。
- [x] **#4 `:timeout` 是唯一不「开箱即生效」的 deftool 选项——等于把刚修的 bug
  挪了一步**：`:serial`/`:retry`/`:return-direct` 都由 kernel/react 直接消费，
  独 `:timeout` 要用户手动挂 filter，否则「编译通过、无警告、零效果」——
  **与死选项 bug 的可观察症状逐字相同**，只是从「白名单收下但没人读」变成
  「元数据发出但没人读」。**修**：强制力落到 **`kernel/invoke-tool`**（`run-chain`），
  **仅在声明时起线程**（未声明零开销）；`timeout-filter` 退为纯缺省——只管未声明的
  工具，**见到声明即让位**（优先级由让位实现，不比大小，同一 deadline 不套两层线程，
  实测 1 根线程）。**层次判断**：`:retry`/`:serial`/`:return-direct` 是**循环/批次**
  策略故属 react；`:timeout` 界定**单次调用**的时间上限故属 invoke-tool——放对层
  就不需要协调（实施中一度放进 react，随即发现 invoke-tool 直调没超时了：
  filter 已让位、react 又不在场。**放错层的代价就是要发明协调**）。
  推翻了设计文档 §3.5 对「方案 C」的否决（该否的只是「无条件起线程」，不是「下沉到
  调用原语」；且其另一条论据「换来打不断的超时」已被 §2.2 推翻）——已加修订块，
  是 §1.3「论据倒了一条 → 重新称剩下的」的一次实践。
- [x] **#3 `:timeout` 值零校验**：实测 `"5s"` → 每次调用抛 ClassCastException；
  `-1` → 每次**静默立刻超时**（工具永远跑不了，错误只说「超时」）；`2.7` → 静默截断。
  此前是死选项故写错也无害，**改活的同时就得为它的值负责**。**修**：
  `tool/valid-timeout?` + `kernel/build-kernel` 装配期校验（var 与 inline 汇合、
  尚未执行的最早时点），坏值在造 kernel 时就炸且报错点名。
- [x] **#2 §3「边界内一致」表述过宽，字面上禁掉了 filter 体系本身**：初稿把
  「filter 挂载」与批次大小、引擎选型并列写进禁令——但改变行为**正是 filter 的
  本职**（approval-filter 决定跑不跑、timeout-filter 决定超不超时），照字面援引会把
  这两个内置 filter 双双判为违反，而本轮工作恰恰依赖「挂了才有缺省超时」。
  **修**：新增 §3.2.1——**「一致」管执行环境（ambient state），不管行为**；
  真正要禁的是「filter 改了它**没声明**要改的东西」（如 timeout-filter 顺手弄丢
  `binding`）。判据一句话：挂上 filter，工具**该**看到它声明的效果，**不该**发现
  自己的 `binding` 没了、线程模型变了。§3.3 加 filter 行、§3.4 加两条判例。
  **教训**：原则要能判定违反才算硬约束——会把合法设计判成违反的表述，援引者只能
  靠猜哪条算数，等于没有判据。
- [x] **#5 工具抛 Error 会打死整个工具循环 —— 已修**（2026-07-16 用户拍板收敛非致命
  Error）：四个 catch 点（`tool/invoke`、kernel 的 inline/var terminal、
  `react/invoke-one`）此前一律 `catch Exception`，于是 `Error` 全部逃逸——一个工具的
  深递归 `StackOverflowError` 打死整轮，而分层错误路由的全部意义就是「一个工具坏了
  不牵连别人」。实测有无 timeout-filter 都一样，故非本轮引入（旧 `(future ...)` 曾靠
  FutureTask 把 Throwable 包成 ExecutionException 意外收敛过它，改原样重抛后这一处
  也没了）。
  **修法的核心是判据，不是无差别 catch Throwable**（吞掉 OOM 只会掩盖真因，且收敛
  动作本身还要分配内存，多半当场再炸）：新增 `model.error/fatal-throwable?`——
  **致命（放行）**= `VirtualMachineError` 中除 StackOverflowError 外的那些
  （OOM/InternalError/UnknownError）+ `ThreadDeath`；**其余收敛**，经
  `classify-exception` 归类（缺省 :semantic——工具自身 bug 重试无意义）。
  **与 Scala `NonFatal` 的有意分歧**：它把整个 `VirtualMachineError` 划为致命，
  但那是通用库的保守取舍；这里的 Throwable 来自**用户工具函数体**，栈溢出是它最常见
  的自伤方式，栈一退就恢复，正是最该收敛成「这一个工具失败」的那类。
  **+1 test / +11 assertions**（6 组）：三类非致命 Error 收敛（StackOverflow/
  Assertion/NoClassDefFound）、**真·深递归**（非手工 throw）、**同批一个溢出不牵连
  另一个**、filter 抛的 Error 也收敛（invoke-one 是完备边界）、**OOM 仍原样上抛**。
  全套 **315 tests / 1286 assertions / 0 failures**。
  **顺带修文档**：`tool-timeout-design.md` §3.4 原写「`invoke-one` 的 try/catch
  **已经**是完备的错误边界」——**当时并不完备**（只 catch Exception）。这是我在论证
  「不需要 beamai 层 3」时对自家防线的过度自信；结论不变（层 3 防的是 BEAM 的 link
  传播，我们确实没有），但支撑它的那条事实当时是错的。已加修订块，现在这句才成立。
- [x] **测试**：+3 tests / +16 assertions。`declared-timeout-works-without-any-filter-test`
  （裸 kernel 零 filter 声明即生效 + 未声明零开销不被砍）、
  `inline-tool-declared-timeout-test`（内联声明生效 + 未声明对称）、
  `declared-timeout-beats-filter-default-test`（声明更宽/更紧两向都胜出 filter 缺省、
  未声明吃缺省）、`timeout-validated-at-build-kernel-test`（`"5s"`/`-1`/`0`/`2.7`
  装配期全拒、合法值通过）；`timeout-filter-priority-test` 改写为
  `timeout-filter-only-defaults-undeclared-test`——**旧测试钉的是旧设计**
  （filter 自己实现优先级），现在优先级在 invoke-tool，filter 只负责让位。

### ✅ 缺省机制定调（2026-07-16 用户拍板，两条 💥 破坏性变更）

> 测试：315/1286 → **316 tests / 1301 assertions / 0 failures**；live 20 项仍全过
> （已改为验新机制）。

- [x] **缺省不超时，两个显式来源**：`工具声明 deftool {:timeout ms} > 引擎缺省
  (…-tool-calling-manager {:timeout ms}) > 不超时`。三个引擎均接受 `:timeout`
  （构造期校验正整数）；新增 `tcm/manager-timeout`（读 record 的 `:timeout` 字段而非
  加协议方法——加方法会让既有 `reify` 实现在调用时抛 AbstractMethodError；自定义
  实现无该字段即 nil，自然退化）+ `kernel/effective-tool-timeout`（声明 > 引擎）。
  **时间上限属于执行策略故随引擎构造**，不散落在 filter 里——与 beamai
  `manager_opts.tool_timeout` 同一立场。都没给则不起线程、零开销。
  `advisor/timeout-filter` 先退为兜底，随即**整条删除**（见下一节）。
- [x] **缺省 TCM 改为串行**（反转本版早先的 💥「同一轮 tool-call 并行执行」）：
  `execute-batch` 的 executor 从 `@tool-executor` 改为 nil（全程内联）。要并发须显式
  注入 `virtual-thread-tool-calling-manager`。**理由**：并发要求同批工具的副作用彼此
  无序依赖——那是调用方才知道的性质，框架不替它假定；串行是「无论工具长什么样都
  成立」的选择。**状态语义与引擎无关**（三个引擎都是轮初快照 + 屏障折叠），故这条
  只改调度、不改语义。
- [x] **测试跟着搬，并逮到两处「假通过」**：缺省改串行后，`batch-true-parallel-test`
  直接失败（钉的是旧默认）；更隐蔽的是 **`batch-serial-degrade-test` 与
  `batch-snapshot-isolation-test` 会静默假通过**——前者测「批内有 :serial 就退化为
  按序」，缺省本就串行时它什么都没测；后者的 gate-a/gate-b 握手在串行下退化成靠
  deref 超时兜底。**一切并发语义的测试现在必须显式选引擎**：新增 `via-manager`
  helper，三个测试改走 VT 引擎，并给退化测试补了「去掉 :serial 就真并发」的对照组
  （证明它测的确实是退化）。新增 `default-manager-is-sequential-test`（缺省严格按序
  无重叠 + 显式注入 VT 才并发）、`manager-default-timeout-test`（缺省不超时 / 引擎
  缺省生效 / 声明更紧更宽两向都胜出引擎缺省 / 三引擎均接受 :timeout / 坏值构造期拒）。
- [x] **live 改为验新机制**：`make-agent` 从挂 `timeout-filter` 改为
  `(sequential-tool-calling-manager {:timeout ms})`，场景 1/3/4 传 nil（不给引擎缺省，
  证明**声明是唯一来源**也能生效）。20 项全过。
- [x] **`timeout-filter` 整条删除**（2026-07-16 用户拍板，💥）：待定项结案。
  两个职责已被接管（强制力→`invoke-tool`、缺省→引擎 `:timeout`），剩下的唯一辩护
  「按名/标签动态时限」按 §1 四问全落假想列（用户 5 行 `:tool` filter 包
  `call-with-timeout` 即等价）；三层优先级是复杂度税（beamai 也只有两个来源）。
  连带删除：`build-func-def` 的 `:timeout` 字段（唯一读者就是该 filter，
  没有读者的字段就是下一个死选项；函数签名退回 `[fn-name tool-var]`）。
  保留：机制本体 `tool/call-with-timeout`（`invoke-tool` 消费），其机制测试
  从 filter 形态改写为直打本体（`call-with-timeout-mechanism-test` /
  `-environment-test` / `timeout-abandons-not-kills-test`）；kernel 级优先级
  测试改用引擎缺省桩。**墓碑已登记并经变异验证**（README 里复活它 CI 会逮住）。
  文档同步：filter-chain-design §3 表改删除记录、advisor-alignment 两处、
  agent-loop-concurrency 一处、三个 README 的教学段、tool-timeout-design §3
  终态块。**复盘一则**：变异验证时用 `git checkout README.md` 恢复，把本会话
  未提交的 README 改动一并冲掉（靠 grep 发现并重做）——变异测试的恢复手段
  应该用「删掉刚 append 的行」而非整文件回滚。
  全套 **314 tests / 1295 assertions / 0 failures**（净 -2 tests：filter 自身
  测试删除多于新增），live 20 项全过，5 条墓碑。

## P9 — Code review 第二轮（2026-07-16）✅ 全部完成

> 来源：/code-review high（8 个 finder 角度并行 → 35 候选 → 去重 10 → 3 个独立
> 验证 agent 逐条核实，**10/10 CONFIRMED**，每条有逐行证据；最重一条被 4 个角度
> 独立命中）。范围 = P8 全部未提交改动。
>
> **终态：316 tests / 1307 assertions / 0 failures**（core 98/373 + client 112/440 +
> provider 106/494）。MiniMax live 20 项全过（**六遍稳定**，跨越 P8 初版与 P9 架构
> 重排；场景 4 abandon 差值六次实测 1701/1702/1700/1700/1700/1700ms）。
> **根因一（R1/R2/R3）一举修掉**：超时从 run-chain（包整条 filter 链）下沉到
> terminal（只包工具本体）；**根因二（R4/R5）**：manager-timeout 改动态 var
> 不走 kernel 反向指针 + 装配期校验。R6-R10 各自独立小修。
>
> **结构性结论**：10 条几乎全是 P8 最后两个设计动作的连锁后果，不是零散笔误——
> 根因一 = `run-chain` 把超时包在**整条 filter 链**外面（该包 terminal）；
> 根因二 = `manager-timeout` 的 **duck-typing 缝**（字段读取 + kernel 反向指针）。
> 每步局部全绿、但「包链还是包 terminal」「字段还是协议」两个隐含面没被追问。
> 建议顺序：先两个根因（各自一举修掉 3-4 条），delegate try/finally 与 OOM 拆包
> 独立小修，文档五处随手。

### 根因一：超时包裹点错——包了整条 filter 链，该只包 terminal（工具本体）

- [x] **R1 审批等待被算进工具超时预算，且 :retry 重复弹审批**（`kernel.clj:264`
  `run-chain`）：approval-filter 的阻塞 read-line 在 `call-with-timeout` 内——
  操作员 8s 敲 y，5s 声明的工具从未执行却报「超时」:transient；声明 :retry 则
  整链重跑、对人重复弹框 max-retries 次，每次同样必死的时钟。旧 timeout-filter
  时代可把审批注册在计时区外，该控制点已不存在。**修法**：超时下沉到 terminal
  （beamai 也只计时 handler）——filter 链在计时区外。
- [x] **R2 任何超时生效即把工具本体搬上新 VT——Sequential「调用方线程」与
  ThreadPool 舱壁承诺双双静默失效**（`react.clj:330/380` + `kernel.clj:264`）：
  Java ThreadLocal/MDC 丢失（bound-fn* 只传 Clojure 动态绑定）；池线程只在 deref
  上等，pool-size 只 bound 住 deref 不 bound 真干活的线程，舱壁退化成信号量；
  引擎级缺省下每个快调用都付 VT+promise+bound-fn 开销。**修法**：与 R1 同源——
  有 executor 的引擎在**它自己拥有的线程**上执行并用 `Future.get(ms)` 计时，
  仅无 executor 的串行路径才起 VT；或如实降级文档承诺。
- [x] **R3 超时结果在链外合成——任何 :tool filter（日志/指标/审计）永远观察不到
  超时**（`kernel.clj:267`）：旧 filter 在洋葱内产出结果、外层可见（live 脚本的
  witness 探针即此写法——删 filter 当天它被迫改回调，**就是本缺陷的第一个受害者，
  当时没认出来**）；且被弃链可能在被 interrupt 的 VT 上跑完，外层 filter 对已丢弃
  结果照常 post 处理，审计日志记「成功」而 LLM 收到「超时」。**修法**：随 R1
  下沉后超时结果天然在链内产生，此条自动消除。

### 根因二：`manager-timeout` 的 duck-typing 缝

- [x] **R4 引擎 :timeout 读 kernel 反向指针而非实际执行的 manager**
  （`kernel.clj:225` `effective-tool-timeout`）：instrumented wrapper
  （reify 委派——live 脚本自己的先例模式）→ manager-timeout 返 nil，30s 部署
  封顶静默失效；独立 manager 直接跑批次 → 它自己的 :timeout 被忽略。现有测试
  把同一 tm 同时传 build-kernel 与 via-manager，遮住了分岔。**修法**：超时随
  执行路径传递（execute-tool-calls opts 或协议方法），不走 kernel 字段回读。
- [x] **R5 duck-typed :timeout 零校验 + map 桩先例是陷阱**（`kernel.clj:120`）：
  `check-timeout-opt!` 只覆盖三个 react 构造器；`:tool-manager {:timeout 0}`
  （0 truthy）→ 每次调用瞬时超时、`"5s"` → 每次 CCE——正是装配期校验声称要
  消灭的病，隔一个字段重现。核心测试立的 `{:timeout 150}` map 桩进真实 react
  循环会在 execute-tool-calls 抛 No implementation of method。**修法**：
  build-kernel 对 `:tool-manager` 的 `:timeout` 一并过 `valid-timeout?`（或
  随 R4 协议化后由构造器统一收口）；测试桩改用真引擎或 reify。

### 独立修复

- [x] **R6 delegate 被引擎超时打断 → kill!/drop! 永不执行——子 agent 泄漏且
  继续烧 token**（`delegate.clj:48`）：await! 的 promise deref（底层
  CountDownLatch.await）被 interrupt 时抛 InterruptedException，run-sync 的
  后续绑定跳过；且 delegate 的 :timeout 配置只喂 await!、未上报为 inline map
  的 :timeout 声明，引擎缺省因此能砍它。**修法**：run-sync 用 try/finally 保
  kill!/drop!；delegate-tool 把 :timeout 同时写进 inline map（声明恒优先）。
- [x] **R7 executor 路径上致命 Throwable 被 Future.get 洗成 ExecutionException**
  （`react.clj:177`）：invoke-one 忠实重抛的 OOM 被 FutureTask 捕获、`.get` 抛
  ExecutionException（普通 Exception）——逃逸类型随引擎而变（串行=裸 OOM，
  并发=EE），违反 §3「引擎不改变可观察语义」；新测试只走内联路径。**修法**：
  `.get` 处 catch ExecutionException 拆 cause，致命原样重抛；补 executor 路径
  的 OOM 测试。
- [x] **R8 run-tools 半应用引擎**（`react.clj:426`）：调度恒串行（无视
  :tool-manager，这半是文档记录过的刊式决定），但同一 manager 的 :timeout 却经
  invoke-tool 生效——不对称是 P8 新引入。**修法**（最小）：docstring 如实写明
  「恒串行不看引擎；但吃引擎的 :timeout 缺省」；（彻底）run-tools 接受可选
  manager。

### 文档/测试陈述与实现相反（随根因修复顺手改）

- [x] **R9 ThreadPool :timeout docstring 因果倒置**（`react.clj:380`）：写「被
  超时放弃的工具永久占池槽」——实际被超时的恰恰不占（跑在 VT 上、槽在 deadline
  释放），真占槽的是**无**超时的内联卡死。按此文案做容量规划/告警的运维会盯着
  健康的池、看不见池外堆积。（若 R2 改回池内执行计时，本条文案反而变对——
  与 R2 联动改。）
- [x] **R10 三处源码/测试陈述过时**（墓碑门禁只扫 README 管不到）：
  `tool_test.clj:67`「声明本身无强制力：不挂 timeout-filter 的裸 invoke 不超时」
  ——与「开箱即生效」直接矛盾（断言仅因裸 invoke 绕过 kernel 才碰巧通过）；
  `kernel.clj:253` run-chain docstring 现在时引用已删的 timeout-filter；
  `react.clj:69` tool-executor docstring 仍称承载所有工具批（现仅 VT 引擎用）。

### 次级（10 条上限挤出的 cleanup 候选）✅ 基本完成

> **勾与批注为 2026-07-17 复核补记**：此前这里挂着「未做，留给下一轮」的批注，
> 与事实相反——活在提交 9a14091 / ca667b3 / fab4cf7 里早已干完（提交信息即
> 「P9 次级 cleanup」），只是账没跟上。逐条对代码核过（src 使用点计数 + 实测），
> 不是照提交信息信。仅最后一条杂项里的「测试跨模块重复」未做。

- [x] catch-Throwable 收敛块 4 处手抄 → `err/contain-throwable` helper
  （src 5 处使用，fatal 检查/nil-message 回退/分类三元组不再漂移）
- [x] 「:timeout 必须为正整数毫秒」消息串 3 处手抄 → `tool/check-timeout!`
  （src 6 处使用，引擎侧校验也复用它）
- [x] 内联工具 by-name O(n) 热路径扫描 → 装配期预计算 `inline-meta` map
  （Kernel record 第 8 字段），serial/retry/timeout/return-direct 四个读取全 O(1)
- [x] :retry 装配期校验 → `valid-retry?` + `validate-tool-retries!`
  （实测 `{:max-retries "3"}` build-kernel 即拒、报错点名工具）
- [x] 杂项（部分）：live 脚本 `(cond-> {} default-ms ...)` 冗余已清；
  react_test「缺省不超时」sleep 已减半
- [ ] **仅剩**：优先级矩阵测试跨模块重复（react_test manager-default-timeout-test
  的声明更紧/更宽两条与 advisor_test kernel 层同矩阵，各带秒级 sleep）——client
  侧留一条「构造器把 :timeout 透传到 manager-timeout」断言即可

### 收尾（2026-07-17，用户拍板 3/4 两件）

- [x] **设计文档补 P9 修订记录**：`tool-timeout-design.md` 新增 **§5.6 后记**——
  记录两个架构事实变更（超时包裹点：整条 filter 链 → 只包 terminal；引擎缺省读取：
  kernel 字段回读 → 动态 var 随执行传递）+ 连带修复清单 + §3.5 修订块加指针
  （防止读到旧落点就停）。§5.6 里如实标注：`*active-manager-timeout*` 与被 §1
  毙掉的 `*active-pools*` 同类机器，但这次服务的是验证过的真实 bug，立得住。
- [x] **advisor_test 的 5 处 map 桩换 `StubManager` defrecord**（R5 尾巴）：裸
  `{:timeout ms}` 是陷阱先例——manager-timeout 读得到，但进真实 react 循环会在
  execute-tool-calls 抛 No implementation of method。defrecord 桩协议 + 字段两个
  契约都满足（execute-tool-calls 抛「桩不执行批次」的明确错误）；注意 **reify
  不行**——关键字查找拿不到 reify 的值，必须 defrecord。带注释说明为什么不用
  裸 map，防下个人再抄出陷阱。全套 316/1307/0。

## 🏁 v0.3.0 定稿（2026-07-17）

> 质量面：316 tests / 1307 assertions / 0 failures（core 98/373 + client 112/440 +
> provider 106/494）；MiniMax live 20 项六遍稳定；check_docs 双门禁绿（5 条墓碑）；
> 三个 build.clj 版本号 `0.3.%s` 对齐（**已被 P10 取代**：三份 build.clj 于
> 2026-07-28 合并为根 `build.clj` 一份，版本号自然唯一）；两轮 code review
> （15 条 CONFIRMED）全部落地。
> 发布面：CHANGELOG 头部已落定稿日期；**「从 v0.2 迁移到 v0.3」指南**（8 节，
> 第 1 节 = 互等工具在串行缺省下永久挂起的升级陷阱 + 排查判据 + 迁移代码）；
> README 版本引用改 0.3/v0.3.0。
> **剩余动作**（按惯例归提交方）：提交本轮文件 → 打 `v0.3.0` tag。
> 遗留非阻塞项：跨模块优先级测试重复（可选 cleanup）；check_docs 扫源码 docstring
> （已留否决记录：现在时引用 vs 历史注释机器不可判定，宁可漏报不可误报）。

### 📋 历史账本

2026-06 审查（P0/P1/P2 + 测试盲区）与 2026-07 优化轮（P3 + A/B/C 组）全部清零。

---

## P10 — 构建/开发脚本迁移：bb + tools.build（2026-07-28）✅ 同日完成

> 起因：用户提出「scripts 下多个脚本可以用 babashka 替换，并用 tools.build 构建
> 发布脚本」。落点是两处收敛——**任务入口**收到 `bb.edn`，**构建本体**收到根
> `build.clj`。

- [x] **五个 shell 脚本 → `bb.edn` 任务**：`scripts/{build,clean,install,test,repl}-all.sh`
  全部删除。任务表：`bb test [module]` / `check-docs` / `check` / `jar [module]` /
  `install` / `release` / `deploy` / `version` / `repl [example]`。模块名吃
  `core` 与 `clj-agent-core` 两种写法；`bb test` 的参数**先全解析成目录再开跑**，
  否则 `bb test core bogus` 会跑完 core 整套测试才炸「未知模块」。
  - 踩坑记录（`bb.edn` 按 **EDN** 读，非 Clojure）：正则字面量 `#"…"` 与匿名函数
    `#(…)` 都会在加载期直接报错（`No dispatch macro for: (`）。故 `strip-clj`
    手写 `subs`、`map` 用 `(fn [p] …)`。`repl`/`version` 覆盖 bb 内建命令，
    需 `:override-builtin true` 消警告。
- [x] **三份 build.clj → 根 `build.clj` 一份**（tools.build）：模块各自的 `build.clj`
  与 `deps.edn` 里的 `:build` alias 全部删除，改为根 `:build` alias + 一张模块表
  （`:key/:lib/:dir/:override-core?/:description`）+ 一套函数。
  - 合并的理由是三份 95% 雷同：同一套 pom-data、同一个 `0.3.<git-count>` 版本方案、
    同一段 override-deps 注释，连「`b/install` 必填 `:class-dir`」这条踩坑记录都抄了三遍。
  - 机制：`b/set-project-root!` —— tools.build 全部路径（`:project "deps.edn"`、
    `target/`、`class-dir`）相对 `*project-root*` 解析，切根即切模块，三模块**一个
    JVM** 跑完（原来 `cd` + 起 6 次 clojure）。`run-modules!` 用 `finally` 复位，
    半路抛异常不污染后续调用。
  - **例外**：deps-deploy **不吃** `*project-root*`，故 `deploy` 的 artifact/pom
    路径显式 `b/resolve-path` / `b/pom-path`（已实测三模块均得到带 `modules/…` 前缀
    的真实路径）。
- [x] **顺带修掉一个真 bug（旧 `build-all.sh` 在 fresh clone 上必然失败）**：它只跑
  `clean`+`jar`、从不 `install`，而 client/provider 的 basis 把 core 覆盖成同版本
  `:mvn/version`（否则 `:local/root` 写不出合法 Maven 坐标、pom 缺 core 依赖）——
  本地仓库没有该版本 core 时 `create-basis` 当场解析失败。且它的 MODULES 列表**根本
  没有 client**（与 P2 里 `test-all.sh` 漏测 client 同款毛病，见历史账本）。
  现在 `release` **逐模块** clean→jar→install（而非先 jar 全部再 install 全部，
  后者同样会在 fresh clone 上炸）；单独 `bb jar client` 走 `ensure-core-installed!`
  自动补一次 core。
- [x] **`scripts/check_docs.clj` 留在 JVM Clojure 上**（`bb check-docs` 只是入口）：
  它 require 全部源码 ns 再 `ns-resolve` 文档里的 alias/sym（检查 3），而源码带
  next.jdbc / sqlite 等 JVM 依赖，bb 跑不动。已在 ns docstring 与 CI 注释里写明
  「这是迁移的唯一例外」，防下一个人顺手把它也搬去 bb。
- [x] **CI 与本地跑同一条命令**：workflow 的 `docs` job 改 `bb check-docs`，
  test matrix 改 `bb test <module>`（setup-clojure 加 `bb: latest`）。
  动机同 P2 的 test-all 漏测 client：CI 与脚本各写一份列表，迟早分叉。
- [x] **文档同步**：README / README_EN 开发章节重写（任务表 + 「为什么 client/provider
  打包前要先 install core」的说明）、模块结构树改 `bb.edn` / `build.clj`、
  modules/README 结构树、`examples/simpleagent_examples.clj` 头部 `./scripts/repl.sh`
  → `bb repl`、check_docs 与两份 README 里以 `scripts/test-all.sh` 举例的那句改
  `scripts/check_docs.clj`（例子文件本身被删了）、CHANGELOG「🔧 内部 / 测试」补记。
  CHANGELOG / TASK 里**属于历史记录**的 `scripts/*.sh` 提及不动（它们描述当时发生的事）。

> 验证：`bb release` 三模块全绿（jar 齐全 + 进 `~/.m2`；client/provider 的 pom 里
> core 依赖坐标实测为 `im.ttalk:clj-agent-core:0.3.320`，即合并前那条「发布关键」
> 不变量仍成立）；删掉 m2 里的 core 后 `bb jar client` 自动补齐并成功；
> `bb check` 全绿（316 tests / 1307 assertions / 0 failures + check_docs 双门禁：
> 6 README / 55 ns / 5 墓碑 / 18 份 docs）；未知模块名在 `bb jar` 与 `bb test`
> 两条路径上都给出可读错误。
> 按惯例未提交（提交归并行会话）。

---

## P11 — Provider 变体差异（MiniMax thinking，2026-07-28）✅ P1 + P3 全部完成

> 起因：MiniMax 走 Anthropic 兼容接口但有自己的特征（thinking 参数语义、thinking 块
> 要求原样回传、M2.x 关不掉 thinking），问「如何用一个标准 provider 同时支持多个
> 差异化方案」。设计与实测全记录：`docs/provider-variant-design.md`。

- [x] **实验脚本自身两处缺陷**（M3 那轮才暴露）：判读器把「产出 thinking」判成**布尔**
  （是否为零），而 M3 的差异全在**次数**上 → 第一次跑完印的是「三臂无差别」，
  真信号被判据本身漏掉；且判读**未按模型分组**，两个模型混在一起统计等于没统计。
  教训：**先想清楚差异会长在哪个量上，再写判据**。
- [x] **设计：三类差异三种机制**（端点 = config merge，已解决；请求参数 = 模型级
  特征表；会话状态 = 可选协议 + 中立消息不透明载荷）。骨架结论：**不动
  `ILLMProvider`**——往 DIP 端口加方法 = 所有实现方破坏性变更；差异化能力放独立
  可选协议、`satisfies?` 探测、不满足即降级。
- [x] **P0 验证实验**（`examples/minimax_thinking_replay_experiment.clj`）：三臂对照
  （完整回传 / 剥 thinking＝当前框架行为 / 删 signature），强制串行四轮工具链。
  **两个模型结论相反**：
  - **M2.7（n=4）**：12/12 无差别——thinking 关不掉，每轮必然思考，回传里有没有
    历史 thinking 都不改变这一点。
  - **M3（n=6，显式 adaptive）**：**思考频率退化**——A 臂 thinking 均值 4.50，
    B 臂（当前框架行为）3.33，两组不重叠，且与另一轮 n=4 独立数据同向。
    但轮数（5）、工具调用数（4）、重复率（0）、报错（0）、末轮答案**全同**——
    **行为差异确认，质量损害未证明**。
  顺带钉死三条：signature 不校验（M2.7/M3 皆是）；thinking 开关语义与官方文档
  一字不差（M2.7 关不掉 / M3 缺省关且可开可关）；流式/非流式块形状同构。
  - 实验本身两次自我纠错，比结论更该记住：**(a)** 第一版只回一个 `tool_result` 而
    MiniMax 一轮并行发了两个 tool_use → 三臂全 400，判读器却报「B 臂报错 = 降级确凿」
    （**假阳性**）；现加守卫「A 臂也报错 = 实验作废」。**(b)** 第一版任务被模型一轮
    并行做完，压根压不到 interleaved thinking（**假阴性温床**）；改强制串行链。
    **(c)** n=2 时 B 臂偶现少一轮被判「软降级」，n=4 复核证明是采样波动。
- [x] **质量对照实验**（`examples/minimax_thinking_quality_experiment.clj`）：为回答
  「少思考到底伤不伤结果」而设计——7 步金库链，答案唯一可自动判定，错误沉默复合
  （工具对任何编号都返回值、从不提示查错），**每轮都可判对错**。判据在 §7.5
  **跑之前**定死，含地板/天花板作废守卫 + 单侧 Fisher 检验。M3，20 次/臂：

  | 臂 | 最终答案对 | 逐轮全对 | thinking 均值 | 轮数 |
  |---|---|---|---|---|
  | A 完整回传 | **20/20** | **20/20** | 8.0 | 全部 8（方差为零） |
  | B 剥 thinking（当前行为） | 16/20 | 13/20 | 2.5 | 8–17 |

  **主指标 p = 0.0530 ≥ 0.05 → 按判据 P3 不立项**（次指标逐轮全对 p = 0.00416，
  但**不能拿来定案**——那是结果切换，预注册要防的就是它）。
- [x] **确证实验**（n=40/臂，跑前声明写在设计文档 §7.5.2：主指标与阈值不变、
  一次性不再延长、不与上一轮合并、脚本只动 `EXPERIMENT_N`）：

  | 臂 | 最终答案对 | 逐轮全对 | thinking 均值 | 轮数 |
  |---|---|---|---|---|
  | A 完整回传 | **40/40（100%）** | **40/40** | 8.0 | 全部 8（方差为零） |
  | B 剥 thinking（当前行为） | **33/40（82.5%）** | 19/40（47.5%） | 2.75 | 3–17 |

  **主指标 p = 0.0059 < 0.05 → P3 立项。** 正确率翻倍样本后几乎没动
  （80.0%→82.5%），动的只有功效——印证了上一轮「功效不足，不是没差别」的判断。
- [x] **P3 已实施**（回传契约：`IReplayableResponse` + 中立 `:blocks` +
  wire 还原 + 降级路径）。这条要记住的不是结论而是路径：**写设计的人（我）
  一开始就倾向做 P3**，而它被自己的实验**否掉一次**（M2.7 无差别）、**卡住一次**
  （p=0.053）、第三轮才拿到够格证据。中间那次尤其关键——当时次指标已显著
  （p=0.004），切过去就能「证明」我本来的判断，但那是结果切换，不作数。
  **规则双向才作数**：它挡住了想立项的我，也在证据足够时放行了同一个我。
  - 记账一处设计失误：主指标选偏了——最终答案是更噪的度量（B 有多次链只对 2/7
    步却仍答对，中途纠错或凑巧回正轨），「过程对不对」才是被 thinking 直接影响
    的量。下次设计对照实验先想清楚**效应会长在哪个量上**。
  - **验收必须钉正确率差异本身**，而不是「thinking 块有没有回去」——后者是手段，
    前者才是这件事被批准的全部理由。
- [x] **P3 实施 + 验收**（详见设计文档 §6.2）：4 处源码全是加法——core 新增**可选**
  协议 `IReplayableResponse`（`satisfies?` 探测，不实现的 provider 一行不改）、
  `LLMResponse` 加 `:replay-blocks`、中立消息加 `:blocks` 契约、client 的
  `response->neutral` 带载荷进历史、provider 抽取 + wire 逐字还原。
  - 实施中**朝更小方向**改了三处设计：协议只留 1 个方法（还原在各 provider 自己的
    wire 里，走协议是绕圈）；**协议绝不能加 `extend-type Object` 兜底**（`ILLMProvider`
    就栽在这，`satisfies?` 恒为 true，见 `model/provider?` 注释）；抽取只在真有
    thinking 块时触发（无脑捕获会盖过重建路径、绕过改写历史的 filter）。
  - **§3.4 的选型被现实推翻**：设计里写「provider 在自己的 `normalize-response` 填」，
    但 agent 实际走的是 **core 的 `service/normalize-response`**——provider 自己那个
    根本不在链路上。探测点因此落在 core，**这恰恰证明了协议的必要性**：通用代码
    要问 provider「有没有要原样带回的东西」，而它不能认识任何厂商格式。
  - 验收：+12 tests / +45 assertions（全套 **331/1369/0**）；真机走 `create-agent`
    全链 **20/20 = 100%**，20 次全部恰好 7 次工具调用——与 A 臂零方差形态一致，
    B 臂（82.5%、轮数 3–17）的行为消失。降级路径三条各有单测。
- [x] **P1 落地**（唯一被实验支持的台阶）：`common/service-config` 把 provider 调用
  config 的白名单 `{:model :max-tokens :temperature}` 换成排除法
  （`orchestration-keys`）。此前 `:thinking`/`:cache-strategy`/`:service-tier`/
  `:top-k`/`:beta` 等**在 provider 侧早已实现**却走不到——门面把它们挡在外面。
  +3 tests / +17 assertions，全套 319/1324/0，真机 M2.7 冒烟通过。
  - 名单里 `:tools` 最危险（service config 是已编译 schema，agent 是 tool var 向量；
    漏下去 provider 转出 `{:name nil}` → MiniMax 400）——单测专钉。
- [ ] **未测的失效边界**（记账，不立项）：Anthropic 官方端点的 signature 校验、
  MiniMax-M3、超长对话下 thinking 缺失是否随上下文累积。见设计文档 §7.5。

---

## P12 — Provider 层对照 Vercel AI SDK 补齐（2026-08-18）✅ 编码完成，待真机验证

> 起因：用户要求「参考 `~/workspace/ai`（Vercel AI SDK）完善当前项目的 provider」。
> 逐个对照其 provider 规格后，真实缺口三块（其余要么已有等价物，要么按 §1 属假想）：
> **多模态输入**（完全缺）、**embedding**（完全缺）、**厂商覆盖**（用户点名四家）。
> 基线 331 tests / 1369 assertions → **358 / 1529 / 0**（+27 tests / +160 assertions）。

- [x] **多模态输入**：core 新增 `model/content`（中立部件：`text-part` /
  `image-part` / `file-part` / `audio-part`；来源吃 URL / data URI / base64 /
  `byte[]` / `File`）；`wire/openai` 与 `wire/anthropic` 各自按 **media type 顶层
  类别**分派翻译。
  - 跟 AI SDK 学的两条形状决策：**图片/音频/PDF 不各立部件类型**（统一 `:file` +
    `:media-type`，新格式不需要新类型）；**部件是数据不是句柄**（二进制以 base64
    字符串落地，才过得了 EDN/SQLite 历史往返——有回归测试）。
  - **不支持的组合抛 `:validation-error` 而不是静默丢**（丢内容 = 模型答非所问，
    排查成本远高于当场报错）：Anthropic 不收音频 / 不收通配 media-type 的内联图片，
    OpenAI 不收 URL 音频与 URL PDF。
  - **既有厂商原生块逃生通道不变**：`:type` 为字符串的元素原样透传，可与中立部件
    混用（Anthropic citations 的 document、`cache_control` 断点等照旧）。
  - 端到端已验：SimpleAgent `chat` 直接收部件向量 → 出站 wire 正确 → 历史落库 →
    第二轮原样重发。
- [x] **顺带逮到并修一个真 bug**：`DashScopeProvider/call-llm` 从来没做 wire 转换，
  把中立消息（`:tool-calls` / `:args` / keyword role）**原样发给了 DashScope**——
  多轮工具历史等于没发。改为复用 `wire/openai`（原生 messages 与 OpenAI 同构），
  回归测试钉出站 body 形状。**这个 bug 在仓库里躺了很久，是被新特性顺出来的**，
  记一笔：没有对着出站请求体断言的 provider，等于没测。
- [x] **Embedding**：core 新增**独立可选协议** `model/embedding/IEmbeddingProvider`
  （单方法 `embed`；与 `ILLMProvider` 并列而非从属，**无 Object 兜底**故
  `satisfies?` 可信）；provider 新增 `embeddings`（工厂 + 3 实现）与
  `common/embeddings`（OpenAI 兼容 + DashScope 原生两种线上形状）。
  内置 9 家；超批自动切片、按服务端 index 重排、usage 累加、条数不符即 `:parse-error`。
  - 形状取自 AI SDK 的 `EmbeddingModel` 与 `LanguageModel` 分离：**能力探测不撒谎**
    比「一个 provider 什么都能干」重要——Anthropic 没有 embedding 服务，表里就
    没有条目，而不是「调了才报不支持」。
- [x] **四个新 provider**（用户点名）：`:xai` / `:moonshot` / `:openrouter` /
  `:siliconflow`，均由 `defprovider` 生成 + factory 注册 + env 前缀 + 默认配置。
  新增测试钉住「defprovider 默认值与 factory 默认配置**两处一致**」——历史上
  anthropic 多一个 `/v1`、mistral 少一个 `/v1` 正是这么漏的。
  - **刻意没做的事**：OpenRouter 的署名头/路由参数不新增字段（既有
    `:extra-headers` / `:extra-body` 即等价，按 §1 四问全落假想列）。
- [x] 文档：三个 README（根 / provider / core）+ CHANGELOG 0.3.0；`bb check-docs` 绿。
- [ ] **记账（不立项）**：`advisor/rag` 与 `re-reading-filter` 至今只改写 **string
  content** 的用户消息，多模态 turn 因此拿不到 RAG 注入（原因见 rag.clj
  `last-user-index` 注释：改写向量会丢图片片段）。中立部件落地后这条限制**在技术上
  已可解除**（往部件向量里追加一个 text-part 即可，不动图片），但没有具体调用方
  要「多模态 + RAG」，按 §1 四问不立项——真需求出现时改一处 `last-user-index`
  加一处注入分支即可。
- [ ] **真机验证未跑**（本轮只编码，无可用 provider key）：脚本已写好
  `examples/multimodal_embedding_live_test.clj`——内联图片（程序生成的红方块，
  答对只可能来自真看见）、URL 图片、embedding 语义排序（同义 > 无关，这条才是
  判据本身：随便返回一堆数能过形状断言，过不了语义排序）、批次切片。
  缺哪个 key 跳哪段。**在真机跑通前，这三块只能算「单测通过」，不算验收**。

---

## `:iteration` 钩子——补齐「内层 advisor」那一层（2026-08-25 立项）

> **来源**：用户提出的四层中间件模型对照。**用户拍板实施**——我的评估是
> 「先别加，等具体用例」（三个 HITL 语义坑，见 §3），用户看过评估后要求做。
> 评估意见保留在此备查，实现按用户决定推进。

### 1. 缺口是什么

用户的四层模型 vs 现状：

| 用户模型 | 频次 | 我们 | |
|---|---|---|---|
| 外层 advisor | 一次对话进出各一次 | `:turn` | ✅ 严格对上（还支持递归重入） |
| 循环 advisor | 驱动 while | 硬编码 `loop/recur` + 参数化扩展点 | ⚠️ 形态不同，控制点齐全，刻意不改 |
| **内层 advisor** | **每轮复入一次** | **无** | ❌ **本项要补的** |
| provider 中间件 | 每次 LLM 调用 | `:chat` | ✅ 对上 |

`:chat` 每轮触发一次，频次与「内层 advisor」相同，但**只包 LLM 调用那一半**：
它的 resp 是 LLM 响应，看不到本轮工具跑出了什么。工具那一半被
`ToolCallingManager` 与 `:tool` 链接管。两半之间没有把它们合起来的 around。

因此做不了：单轮墙钟预算（LLM + 工具一起计时）、单轮重试/回滚（这一轮整个
重来——`:turn` 能重来但是重来*全部*）、本轮收尾时基于工具结果决策。

**现有绕法及其洞**：下一轮 `:chat` 的 delta 就是上一轮的工具结果消息
（`react.clj` 的 `(recur messages ...)`），所以「回头看」能做。但**末轮没有
下一次 `:chat`**——`return-direct` 收尾、或工具执行后循环结束时，那批工具结果
永远不经过 `:chat` 链。想靠这条路做「每轮必然执行一次」的逻辑会在末轮静默漏掉。
callbacks 覆盖了这些位置但只能观察，不能改写/短路/重试。

### 2. 设计

第五钩子 `:iteration`，形状与其余 around 一致 `(fn [req chain] -> resp)`，
挂在 `run-tool-loop` 的**每轮迭代**外面（一轮 = LLM 调用 + 该轮工具批次）。

IterationRequest（filter 可改写 `:messages` / `:context`）：

```clojure
{:messages  本轮 delta（首轮=turn 入口消息；后续轮=上一轮的工具结果消息）
 :context   本轮起始 tool-context
 :index     轮序号（0 起）
 :remaining 进入本轮时的剩余迭代预算（只读快照）}
```

IterationResult：`:continue`（本轮跑完工具、还要接着转）或既有终态
（`:completed` / `:paused` / `:cancelled`）逐字不变。

```clojure
{:status :continue :messages <下一轮 delta> :context <折叠后的 ctx>}
```

filter 能做：改写下一轮 delta、改写 context、`(chain req)` 重入重跑这一轮、
不调 chain 短路成 `:completed`。

**层次全景**（补齐后）：

```
:turn        每 turn 一次           包整个工具循环
 └ :iteration 每轮一次              包 LLM 调用 + 本轮工具批次   ← 本项
    ├ :chat    每轮一次             只包 LLM 调用
    │  └ :token-xform  每 token
    └ :tool    每 tool call 一次    单个工具执行
```

### 3. 三个坑与决定

- [x] **坑 1：暂停语义**。~~立项时判断：暂停发生在一轮中途，调用栈直接返回
      `:paused`，该轮 around 的后半段永不执行，故不能用来做「每轮必然收尾」的
      记账。~~ **写测试时证伪**（`iteration-pause-semantics-test` 第一版按这个
      假设断言 `exited = 0`，实测是 1）：**暂停是终端的返回值而非异常**，
      `:paused` 沿链正常回流，around 后半段照常执行、filter 看得见暂停。
      所以单轮计时/预算记账在 HITL 下**能**正常收尾——比立项时预计的好。
      （`:turn` 一直也是这个行为；其硬规则说的是「看到 :paused 不得重入」，
      不是「后半段不执行」，是我立项时误读了自己的文档。）
      **仍然成立的那半条**：resume 执行的是「暂停那一轮的下半截」（批次已定、
      无新 LLM 调用），**不经过 `:iteration` 链**；续跑的循环从下一个完整轮
      重新进链，`:index` 从 0 重新计。
      **决定**：沿用 `:turn` 的硬规则「`:paused` / `:cancelled` 结果必须透传、
      不得重入」，两处语义（回流、resume 半批）都写进 docstring 并有测试钉住。

- [x] **坑 2：预算记账**。filter 重入一轮 = 那一轮的 LLM 调用与工具批次**真的
      又跑了一遍**，若 `remaining` 只按循环本体推进扣减，filter 无限重入就能
      绕过 `max-iterations`。
      **决定**：`remaining` 从 loop 参数改为 run-tool-loop 作用域内的 volatile，
      **由 terminal 在实际执行工具批次后扣减**——谁真跑了谁记账。语义与现状
      一致（`remaining` = 还能再执行几批工具，检查点仍在「LLM 要调工具但预算
      为 0」处），但 filter 重入自然计入，`max-iterations` 仍是硬上限。
      暂停时 `loop-state :remaining` 读 volatile 当前值。

- [x] **坑 3：与 `:turn` 递归重入叠加**。`:turn` 重入 = 全新 `run-tool-loop` =
      全新 `max-iterations` 预算（既有设计），故也是全新的 iteration 计数与
      volatile。filter 自身的闭包状态跨 turn 共享——与 `:chat` / `:tool` 同，
      无新问题。

- [x] **records 累积**：与 remaining 同处理（volatile，如实累积）。filter 重入
      导致同一轮工具执行两次时，两次都记进 `:tool-calls-made`——如实记录发生
      过什么，而不是记录「逻辑上算几轮」。

### 4. 实施清单

- [x] `filter.clj`：`Filter` record 加 `iteration` 字段（顺序置于 `turn` 之后、
      `token-xform` 之前，按层次从外到内）；`create-filter` 的 `->Filter` 位置
      参数同步；`compile-hooks` / `CompiledHooks` 加第五条链。
- [x] `react.clj`：`run-tool-loop` 的循环体抽成单轮 terminal，`remaining` /
      `records` 改 volatile，外层 loop 按 `:continue` 推进；`:iteration` 链在
      `run-tool-loop` 内组装（每次 turn 一条，terminal 现做）。
- [x] 既有终态形状、`loop-state`、resume 三条路径（approval / env / turn 重入）
      逐字不变——本项**只加一层包装，不改任何既有语义**。
- [x] 测试（`client/iteration_filter_test.clj`，7 deftest / 26 assertions）：轮次触发计数（含末轮）、改写下一轮 delta、短路成 `:completed`、
      重入重跑一轮且预算如实扣减、`max-iterations` 对重入仍是硬上限、
      暂停时后半段不执行且 resume 后不重复进链、与 `:turn` 叠加。
- [x] 文档：`docs/filter-chain-design.md`（§0 层次图 + §2.3 契约 + 硬规则）、
      三个 README 的钩子表、CHANGELOG。`bb check` 绿。

### 5. 验收

- [x] `bb check` 全绿：**370 tests / 1591 assertions / 0 failures** + check-docs 绿。
      既有 363 tests 一条断言没改——改造前后语义等价由它们背书。
- [x] 无 `:iteration` filter 时链是 `identity`，终端即循环体本身
      （`no-iteration-filter-unchanged-test`）。**措辞修正**：不是「逐字相同」——
      外层 loop 多了一次 `{:status :continue …}` map 的构造与判读，这是把
      `recur` 换成「终端返回、外层推进」的必要代价。语义等价，开销可忽略。

---

## `ToolMeta` 表——四个查询合成一张（2026-08-25）

> 上一节收尾时记的「另起一轮」，用户当场要求做掉。

- [x] `serial-tool?` / `return-direct-tool?` / `retry-policy` / `tool-timeout`
      此前各自手抄同一段 `(if-let [v (get tool-vars k)] 读var元数据 查inline-meta)`
      双分支——**四遍**。`:timeout` 正是在这种重复里漏掉 inline 分支、对内联工具
      静默失效的（见 CHANGELOG 0.3.0 与 `build-func-def` docstring）。
      现在 `build-kernel` 把两个来源汇成一张 `ToolMeta` 表，四个查询是它的薄封装
      （签名不变），`:retry` 的默认值 merge 也从每次查询挪到装配期。
      `inline-meta` 与上一轮刚加的 `func-defs` 一并被它取代。
- [x] **顺带逮到一处不一致**：那四处双分支是 **var 优先**，而 `invoke-tool` 的
      执行分派一直是 **内联优先**——同名时会「按 var 的超时/重试策略执行内联的
      handler」。合表后统一为内联优先（与实际执行的那一个对齐）。同名工具本就是
      配置错误，但框架得有确定且自洽的行为。
      **随后用户拍板做掉了这条**（见下节）：不选优先级，装配期直接拒绝重名。
- [x] **校验必须先于建表**：`normalize-retry` 会把非法声明 merge 成看似合法的
      策略，先归一化就等于把错误藏起来。`validate-tool-*!` 保持在建表之前，
      有测试钉住（`validation-precedes-normalization-test`）。
- [x] 测试 `core/tool_meta_test.clj`（6 deftest / 34 assertions）：var 与内联
      各四个声明、无声明的缺省、`:retry` 两种形态的装配期归一化、关键字/字符串
      两种写法、未注册工具不抛、同名内联优先（查询与执行同一优先级）、
      `:func-def` 段、校验先于归一化。
- [x] `bb check` 全绿：**376 tests / 1628 assertions / 0 failures** + check-docs 绿。

---

## 同名工具装配期拒绝（2026-08-25，紧随上节）

> 上节留的未清项，用户当场要求做掉。

- [x] `validate-unique-tool-names!`：var 之间、内联之间、var 与内联之间重名一律
      在 `build-kernel` 抛 `ex-info`（`:duplicates` 点名**全部**重复的键，不止
      第一个）。**排在其它装配期校验之前**——重名时 `var-map` / `inline-handler-map`
      已经被 `into` 把重复悄悄吃掉了，再校验别的就是在错误的地基上校验。
- [x] **不选赢家的理由**：同名工具没有合理用例，只有坏结果——`:tools` schema
      列表里两份定义都发给 LLM（模型看见两个同名工具），而 `tool-meta` /
      `inline-handlers` 只留得下一个，「模型看到的」与「实际执行的」就此对不上，
      且没有任何运行期症状可查。上一节把两套相反的优先级统一成「内联优先」，
      那仍然是在给配置错误编造语义。要替换某个工具，调用方该在传 `:tools` 之前
      处理自己的列表。
- [x] 上一节的 `inline-wins-over-var-test` 随之改写为
      `duplicate-tool-names-rejected-test`（6 组：var×内联 / 内联×内联 /
      同一 var 两次 / `:duplicates` 列全 / 重名校验先于 timeout 校验 /
      不同名照常共存）。
- [x] 既有 376 tests 无一依赖重名注册（`management-tools` 的 5 个工具互不重名，
      `delegate-tool` 的名字由调用方给）。
- [x] `bb check` 全绿：**376 tests / 1630 assertions / 0 failures** + check-docs 绿。

---

## kernel→chat-client / service→chat-model 改名（2026-08-25，对齐 Spring AI）

> 用户拍板：ns + 函数名 + 配置键**全改**（不留兼容别名，与上一轮 advisor→filter
> 同样的硬破坏）；`chat-model` 提升到 `im.ttalk.agent.chat-model`（与 chat-client
> 平级，不留在 `model/` 下）；`im.ttalk.agent.client` 一并改名 `simple-agent`。

- [x] **对标依据**：Spring AI 的 `ChatModel` = 单次 LLM 调用，`ChatClient` =
      其上带 advisor/tool/memory 的编排器。我们的 `service` map 与 `Kernel`
      恰是这两层。
- [x] ns / 文件：`kernel.clj`→`chat_client.clj`、`model/service.clj`→
      `chat_model.clj`、`client.clj`→`simple_agent.clj`（测试同步：
      `client_test`→`simple_agent_test`、`service_config_test`→
      `chat_model_config_test`）。全部走 `git mv`，保住 rename 记录。
- [x] 符号：`build-kernel`→`build-chat-client`、`create-service`→
      `create-chat-model`、`service-config`→`chat-model-config`、
      `Kernel`→`ChatClient`、`Service`→`ChatModel`。
- [x] 配置键：`:service`→`:chat-model`、`:kernel`→`:chat-client`，
      诊断键 `:service-keys`/`:kernel-keys` 同步。
      **陷阱**：Anthropic 的 `:service-tier` / `:service_tier` 是真实 wire 字段，
      改名脚本里先做占位符保护再还原——无差别 `s/:service/` 会把它一起毁掉。
- [x] 错误消息随之统一：「Kernel 未配置 LLM 服务」→「ChatClient 未配置 ChatModel」
      （`context_test` 的断言正则同步）。
- [x] 历史 ns 名不动：`simple_agent.clj` docstring 里的「合并原 kernel-agent /
      process-agent」指的是**已删除**的两个 ns，改成 chat-client 就是伪造历史。
- [x] 别名清理：examples/README 里的 `ka`（kernel agent）/ `pa`（process agent）
      统一为 `sa`；`simpleagent_examples.clj` 里同一 ns 的两条重复 require 合并。
- [x] 缩进：`build-kernel`→`build-chat-client` 长度 +5，对齐续行全部错位。
      写了按「数第 N 个定界符」映射列号的脚本重排（不靠 difflib——首版用它在
      docstring 上误判过），132 处；随后核对 diff 里**零**处纯空白改动残留。
- [x] `modules/README.md` 一处语义冲突：原写 `im.ttalk.agent.client - 高级
      Agent API（≈ ChatClient）`——项目此前把 **SimpleAgent** 对标 Spring 的
      ChatClient。改名后这行自相矛盾，改写为功能描述（有状态对话 + 工具循环 +
      pause/resume）。
- [x] 门禁：`check_docs.clj` 的墓碑表登记 5 条（`im.ttalk.agent.kernel` /
      `.model.service` / `.client` / `build-kernel` / `create-service`）。
      **`:service` / `:kernel` 两个裸键刻意不登记**——README 里 `:service-tier`
      一类合法串会误命中，而该门禁的取舍是「宁可漏报不可误报」。
- [x] `build.clj` 的 core 模块 description 去掉「Semantic Kernel 风格」。
- [x] docs/ 分级处理（照 `6165862` 的先例）：只改仍在描述当前机制的
      `design-principles.md`（§3 标题与两处锚点）/ `filter-chain-design.md` /
      `tool-timeout-design.md` / `token-stream-filter-design.md` / `docs/README.md`
      与六个 README；其余 15 份历史设计文档留旧名。
- [x] `bb check` 全绿：**376 tests / 1630 assertions / 0 failures** + check-docs 绿；
      138 个 clj 文件过 reader 检查；六个 README 的页内锚点全部可达。

---

## 分层对齐 Spring AI / beamai（2026-08-25，改名之后同日）

> 用户四条指令：① 构建 ChatRequest/ChatResponse 类；② ChatModel 体系，Provider
> 只管底层，ChatModel 负重（重试）；③ ChatClient 收窄，对标 Spring ChatClient；
> ④ 该变成类的都变成类。第 ③ 条随后参照 `~/workspace/beamai` 调整为
> **入口模式 + 拆分**，不照 Spring 全改。

### 调研结论（先读代码再设计，两处都改变了方案）

- [x] **Spring AI 当前主线已把工具循环从 `ChatModel` 挪出去了**——
      `OpenAiChatModel.internalCall` / `AnthropicChatModel.internalCall` 现在就是
      单次 LLM 调用，`executeToolCalls` 只出现在 `ToolCallingAdvisor`（advisor 链）。
      即：指令 ② 的「更重的任务」指**重试 + 选项解析 + 可观测**，不含工具循环；
      clj-agent 现有的「循环在 react + :iteration 链」与之位置等价，不动。
- [x] **Spring 是两层两对类型**：`Prompt`/`ChatResponse`（ChatModel 层）+
      `ChatClientRequest`/`ChatClientResponse`（advisor 层），不是一对。
- [x] **beamai 的 `beamai_chat_client` 没有照 Spring 收窄**（443 行，保留
      `invoke_tool` + 整套 Query API）。它做的是 Facade（`beamai.erl` 227 行薄转发）
      + 按职责拆模块（filter → filter/filter_chain/filters；tool → tool/tool_index/
      tool_search/tool_error）。用户据此把 ③ 改成入口模式 + 拆分。
- [x] **beamai 的 chat_model 已跑通重试上移**，两条约束照抄：重试在 filter 栈
      **之下**（filter 只看到一次逻辑调用）；**流式不重试**（token 已投递给 sink）。
      第二条本来会漏——直接给 `invoke-chat-stream` 加重试会当场产生重复 token。

### S1 类型层

- [x] 新 `im.ttalk.agent.model.request`：`ChatRequest [messages options]` +
      `as-chat-request`（扁平 map 写法：除 `:messages` 外全收进 `:options`）。
      **做成叶子 ns** —— chat-model / filter / chat-client 三家都要它，
      放进任何一家都会给另外两家造反向依赖。
- [x] `LLMResponse` record → `ChatResponse`（协议名 `ILLMResponse` 保留，与
      `ILLMProvider` 对称）。
- [x] `filter.clj` 新增 `ChatClientRequest [request context on-token]` /
      `ChatClientResponse [response context]` + `as-*` + 9 个存取器。
      `:on-token` 放外层而非 `:options`——sink 永远不该出现在 wire 上，
      两层结构让这件事无需靠白名单保证。
- [x] 冲击面比预估小：只有 3 个 filter 挂 `:chat` 链（logging / memory /
      tool-search）；`:turn` 与 `:iteration` 链有各自的请求形状，不受影响。

### S2 ChatModel 体系 + 重试上移

- [x] `IChatModel` 协议（`call` / `stream-call` / `model-options`）+
      `DefaultChatModel`（包 provider）/ `FnChatModel`（包 `{:chat-fn :stream-fn}`）+
      `as-chat-model` 归一化 —— 历史裸 map 写法照旧可用。
- [x] 新 `im.ttalk.agent.retry`（core，**零依赖**）：判据 = canonical error 的
      `:retryable?`；三级取值；`:on-retry` 观测真实尝试；满抖动退避。
- [x] **`Retry-After` 不能在上移时丢**：解析 HTTP 头是 provider 边界的事，重试
      却搬到了 core（不认识 HTTP）。做法是 `http/client/response->error` 顺带把它
      解析成 `:retry-after-ms` 挂在 canonical error 上，core 只读那个数。
      不这么传，429 上的服务端退避建议会静默失效。
- [x] provider chat 路径拆掉 `maybe-with-retry`（anthropic / openai_compat）；
      embeddings 与 stream_client **保留**（它们不走 ChatModel）。
- [x] 新 `retry_test.clj`（10 deftest / 29 assertions）钉三条：重试在 filter 栈
      之下（filter 只跑 1 次而 provider 被打 3 次）、流式不重试、401 永不重试。
- [x] **抓到一处静默失败并封掉**：live 脚本用 `(assoc cm :chat-fn (fn ...))` 给
      ChatModel 埋探针（v0.3 前 chat-model 就是那个裸 map）。record 化之后
      assoc 只往 ext-map 塞键，协议分派看不见——注入被无声丢弃，探针不涨、
      程序照跑、无任何运行期症状。`as-chat-model` 现在对「已实现 IChatModel
      却仍带 :chat-fn/:stream-fn」装配期即抛（处置同「拒绝同名工具」）。
      两个 live 脚本改为 `reify IChatModel` 转调内层。
- [x] 踩坑记录：测试里手写 `(errors/error :provider-error … {:status 401})` 构造
      不出「不可重试的 401」——`errors/error` 的默认表里 `:provider-error` 属可重试类，
      `:status` 不参与判定。须走真实路径 `errors/http-response->error`。

### S3 ChatClient 拆分 + Facade

- [x] 新 `im.ttalk.agent.tool-registry`（248 行）：`ToolMeta` + 三个装配期校验 +
      `build-tool-meta` + 8 个查询。拆分判据是「这些函数认识什么」——它们全部
      只认识工具声明，一个都不认识 ChatModel / filter 链 / 消息。
- [x] `filter-hooks` / `with-filters` 归到 `filter` ns：它们认识的是链，对
      ChatClient 只用两个关键字取值 → 依赖方向 chat-client → filter 单向，无循环。
- [x] `chat_client.clj` 565 → **324 行**，公开面 5 个（record + build + 三 invoke）。
- [x] 新 `im.ttalk.agent` Facade（对照 `beamai.erl`）：15 个一行转发。
      `filter` 遮蔽 `clojure.core/filter`，显式 `:refer-clojure :exclude`。
- [x] **Facade 只覆盖 core**——`create-agent` 在 client 模块，core 不能依赖它。
      beamai 的 `beamai.erl` 同样不暴露 agent 层，写法一致。

### S4 文档与门禁

- [x] 六个 README + `modules/README.md` 架构图（四层表 + mermaid）；
      新增 ChatModel / 重试 / 两层请求响应 / Facade 四段。
- [x] `check_docs.clj`：**`LLMResponse` 墓碑登记后当场撤回**——墓碑是子串匹配，
      而仍在用的协议名 `ILLMResponse` 包含它，登记即误报。理由写进注释。
- [x] **`design-principles.md` §1.5 显式修订**（本次唯一的原则级改动）：
      §1.2 四问表把「更像 Spring」整条划进假想列，字面上会把这两轮工作全判成违反。
      用户拍板推翻该读法。修订后的边界——**本原则管「该不该有这个东西」，不管
      「这个东西该叫什么、放哪」**：改名/换分层位置不新增 API 面，§1.1 的
      「成本不对称」论据在那里不成立；为对标而新建抽象/长字段照旧受管。
- [x] **作者判「不做」、用户当场推翻并判做**：`ChatClient` 的四个工具字段收成
      `ToolRegistry` record。作者的三条理由（只换来对称性 / `(:tools cc)` 更绕 /
      第三次改 arity）逐条不成立——换来的是**不变量**（四者总是一起产生、一起
      使用、一起被子 agent 整体替换，收进去之后「一个 ChatClient 一个注册表」
      在类型上成立）；平铺才是让调用方替你记着「这四个要一起换」；**改动次数
      不是判据**，它衡量的是作者的麻烦而非设计的对错，照这条推下去先落地的错
      形状会因为「已经改过两次」而永久固化。复盘写进 §1.5。
- [x] 落地：`ToolRegistry [tools tool-vars inline-handlers tool-meta]`；
      `ChatClient` 9 → **6 个位置参数**；查询函数经 `registry-of` 归一，
      吃 ChatClient 或裸注册表都行（调用形状不变）；新增 `tool-schemas`
      替代 `(:tools cc)`。新增 `chat-client-holds-one-tool-registry` 测试
      钉住「四个旧字段平铺读法必须失效」——否则调用方会以为旧写法还成立。
- [x] `bb check` 全绿：**386 tests / 1665 assertions / 0 failures** + check-docs 绿。

---

## 修 callbacks_integration_test 的既有失败（2026-08-25）

> 跑完 live 脚本后发现的 5 处失败（此前误记为 4 处）。先用 `git worktree` 拉
> HEAD 对照跑，确认与本轮重构无关，再逐个查根因——**其中 4 处不是测试写错，
> 是两个有文档、有设计记录的功能静默消失了**。

- [x] **根因**：callbacks 体系（2026-06）落地时，`:on-tool-call` 版 gate
      **替换**而非补充了 `:on-pause` 版；`:on-pause` 从 `create-agent` 的
      `:settings` select-keys 里掉了，`:sensitive` 从此无人消费
      （`build-func-def` 仍在填它，一路带到 ToolRequest，只是没人读）。
- [x] **三处文档同时成了幽灵**：README「方式二」整节、`deftool {:sensitive true}`
      的说明、`docs/unified-invoke-agent.md`（状态 ✅ 已实施，明写「gate 仅靠
      `:on-pause`」并给了代码）。
- [x] **为什么没被发现**：**零运行期症状**——不暂停的 agent 照跑，只是敏感工具
      直接执行了，`chat` 返回 `:completed`。既有单测**全部**走 `:on-tool-call`
      那条 gate（`dangerous-gate`），一条都照不到 `:on-pause`；唯一在失败的
      `examples/callbacks_integration_test` 不进 `bb check`，被当成既有噪音。
      check_docs 也照不到：`:on-pause` 是 map 键，不是可 resolve 的符号。
- [x] 修复：`gate-of` 两条启用路径并存（回调优先问，放行的敏感工具**仍暂停**——
      `:sensitive` 是工具作者立的下限）；`finalize` 的 `:paused` 分支触发
      `:on-pause`（与 `:on-interrupt` 并存，回调抛异常被吞不影响暂停本身）；
      `:on-pause` 回到 `:settings`。
- [x] 补单测 `on-pause-gate-test`（5 个 testing）+ `on-pause-resume-test`：
      单独配 `:on-pause` 能暂停、非敏感工具不暂停、两条都不配 gate 关闭、
      两条并存时回调放行但敏感仍暂停、回调抛异常不影响暂停。**这是本次唯一
      让该路径进 `bb check` 的东西**——只靠 examples 钉不住。
- [x] 第 5 处是**测试自己的 bug**（场景 5）：工具注册名 `do_research`（下划线），
      mock LLM 却发 `do-research`（连字符），实际拿到的是
      `"错误: 函数未找到: :do-research"`；而「on-tool-result 触发了 do_research」
      这条断言比的正是 `"do-research"`——**描述与断言自相矛盾，所以它一直假装
      通过**。改正名字，并补一条「工具结果不是『函数未找到』」钉住那次假通过。
      框架行为无误（工具错误渲染成字符串喂回模型，turn 照常收尾）。
- [x] `create-agent` docstring 补回 `:on-pause` 与两条 gate 的关系说明。
- [x] `callbacks_integration_test`：**28 PASS / 0 FAIL**；
      `bb check` 全绿：389 tests / 1695 assertions / 0 failures + check-docs 绿。

---

## 修 streaming 示例（undertow / jetty）（2026-08-25）

> 跑 live 时发现这两个连**加载**都过不去。都是 web 框架 API 漂移，与 clj-agent
> 无关；按 §2「框架无关」它们不在项目 deps、不进任何门禁，所以一直没人发现。
> 先用 `git worktree` 拉 HEAD 确认与本轮重构无关，再逐个查。

- [x] **undertow #1（编译期）**：`reify ServerSentEventConnectionCallback` 的
      **参数**上加了类型 hint，`connected` 匹配不上——报错原文就是提示
      "leave off hints for auto match"。hint 挪进 body 的 `let`：既能匹配，
      `.send`/`.close` 也不走反射。
- [x] **jetty（编译期）**：`jetty/send!` 在 ring-jetty9-adapter 0.36.0 里连 var
      都没有。0.30+ 已迁到 **Ring 标准 websocket**：handler 对 upgrade 请求返回
      `{:ring.websocket/listener …}`（listener 可以就是个 map），发送用
      `ring.websocket/send`；`run-jetty :websockets {…}` 路由表一并取消，
      WS 与普通请求走同一个 ring handler。SSE 那半（StreamableResponseBody）没变。
- [x] **undertow #2（运行期，编译看不见）**：`:on-close` 在 1.3.x 已废弃且是
      **assert 掉的**——WS 握手直接 500（`AssertionError: :on-close has been
      deprecated`）。正确的键是 `:on-close-message`，收 `{:channel :message}`。
- [x] **undertow #3（运行期竞态，编译看不见）**：`ServerSentEventConnection.send`
      是**异步**的，`:done!` 里紧跟 `.close` 会把最后一帧冲掉——客户端永远收不到
      done/error。改走带 `EventCallback` 的重载，在回调里再关。
- [x] 四个 streaming 示例的 `MINIMAX_AUTH_TOKEN` 统一为
      `(or MINIMAX_API_KEY MINIMAX_AUTH_TOKEN)`，与其余 10 个脚本一致。
- [x] **验证口径升级为端到端**：只做「能加载」正是让 #2/#3 溜进来的那条线。
      实起服务 + `java.net.http` 打真实流量（WebSocket 与 SSE 各一条，真实
      MiniMax token）：undertow SSE/WS 双 PASS、jetty SSE/WS 双 PASS。
      冒烟脚本自身也踩过一次坑：固定 `take N` 行会在 done 帧之前截断造成**假
      FAIL**，改成读到 done 为止。
- [x] http_kit / aleph 两个未受影响，加载照常通过。
- [x] `bb check` 全绿：389 tests / 1695 assertions / 0 failures + check-docs 绿。

