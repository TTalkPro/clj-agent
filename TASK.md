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

### ⏳ 待办（架构级，建议各自专项推进，勿在常规改动中草率重构）

- [~] **BUG2 http-kit 伪流式 —— 传输层已落地并验证**（方案见 `design/streaming-async-design.md`）：
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
- [~] **D5 错误模型统一**（核心已完成 2026-06-10，方案见 `design/error-model-unification.md`）：
  - [x] 步骤1 `exception->error` 升级为幂等枢纽（透传 canonical ex-data + 识别 UnsupportedOperationException）
  - [x] 步骤2 openai_compat / anthropic HTTP 失败改抛 canonical error（`http-response->error` + `throw!`）
  - [x] 步骤3 bailian 同步失败 + 流式不支持均抛 canonical error（不再裸抛 UnsupportedOperationException）
  - [x] 步骤6 README 错误模型说明更新
  - [x] **修复真实 bug**：provider 401 此前经 `exception->error` 被笼统归为 `:provider-error`（∈可重试集）→ 被误标 `retryable? true`；现端到端保留 `:auth-error`/`:retryable? false`/`:status 401`。回归测试 client_test/model_test/providers_test。
  - [~] 步骤4（kernel 工具错误渲染）、5（converter/factory payload）暂缓——边界 polish，收益有限，留待 D7 顺带（理由见设计文档 §7）
- [~] **D6 core 收回厂商 wire 知识**（已定调，方案见 `design/response-path-consolidation.md`）：
  - [x] 清掉 `call-with-tools` 后，usage/finish-reason/reasoning 归一化只剩 `model/response.clj` 单一权威来源（service/response_parser 都委托它）。
  - [~] 保留 permissive 中立层（认识各家字段别名）——彻底协议化（加 `extract-usage`/`extract-finish-reason`）是破坏性变更，收益/破坏不成比例，留 v0.2。converter/json_schema 的 provider 分发同理暂缓。
- [x] **D7 重复抽象——死代码清理已完成**（方案见 `design/response-path-consolidation.md`）：
  - [x] 删协议死方法 `build-result-messages`/`build-assistant-message`（协议 + Object 默认 + 4 provider record + 全部测试 reify）——`:build-result-msgs` 构造即死，历史由 `response->neutral` 中立消息构建。
  - [x] 删 `call-with-tools`（core 第 4 份冗余归一化，仅测试用）。
  - [x] 删死响应字段 `response-assistant-msg`/`:assistant-msg`（恒 nil，无读取方）+ openai_compat 独立死函数 `build-assistant-message`。
  - [x] 响应归一化从「4 份」收敛为 2 份活路径（同步 service + 流式 stream，输入不同，合理分工）。
  - [~] **剩余：双消息体系统一**（`model/types.clj` vs `model/message.clj`）——改动整条消息数据流、高风险、无正确性 bug、转换边界正常工作，建议 v0.2 破坏性版本专项。
- [ ] **测试盲区（增量）**：流式整链路（call-api-stream / process-sse-stream / 断流 / 非 2xx）、并发、timeout/approval filter、provider record 端到端 mock、工厂 env 配置。

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

### 📋 后续候选（按需启动）

- [ ] **C1 双消息体系统一**（types.clj vs message.clj，v0.2 破坏性专项，见 D7 剩余项）。
- [ ] **C2 onion-filter 模块下沉收尾**（或修订设计目标，见 design/onion-filter.md 状态表）。
