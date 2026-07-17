# 待完成任务

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

### 📋 历史账本

2026-06 审查（P0/P1/P2 + 测试盲区）与 2026-07 优化轮（P3 + A/B/C 组）全部清零。
